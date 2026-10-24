package com.zhangwenkang.cinefin.repository

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class MetadataSnapshotTtlPolicy(
    val softTtlMs: Long,
    val maxAgeMs: Long,
) {
    companion object {
        private const val DAY_MS = 24L * 60L * 60L * 1000L
        private const val BROWSE_MAX_AGE_MS = 7L * DAY_MS
        private const val SEARCH_MAX_AGE_MS = DAY_MS

        fun forKey(key: String): MetadataSnapshotTtlPolicy =
            when {
                key.startsWith(MetadataCacheKeys.SEARCH_PREFIX) ->
                    MetadataSnapshotTtlPolicy(
                        softTtlMs = MetadataCacheRules.SEARCH_TTL_MS,
                        maxAgeMs = SEARCH_MAX_AGE_MS,
                    )
                key.startsWith(MetadataCacheKeys.ITEMS_PREFIX) ->
                    MetadataSnapshotTtlPolicy(
                        softTtlMs = MetadataCacheRules.PAGING_TTL_MS,
                        maxAgeMs = BROWSE_MAX_AGE_MS,
                    )
                else ->
                    MetadataSnapshotTtlPolicy(
                        softTtlMs = MetadataCacheRules.DEFAULT_TTL_MS,
                        maxAgeMs = BROWSE_MAX_AGE_MS,
                    )
            }
    }
}

internal object MetadataSnapshotStoreLimits {
    const val maxFiles: Int = 4096
    const val maxBytes: Long = 256L * 1024L * 1024L
}

/**
 * Repository-side stale-while-revalidate coordinator.
 *
 * Memory is checked first, then a validated disk snapshot. Any usable old value is returned before
 * an expired snapshot is refreshed. Background work shares the same fence-aware
 * [MetadataRequestCoordinator] as foreground misses, so one namespace/generation/key has at most
 * one active network request.
 */
internal class MetadataSnapshotSwr(
    private val cache: MetadataCache,
    private val store: MetadataSnapshotStore,
    private val coordinator: MetadataRequestCoordinator = MetadataRequestCoordinator(),
    private val backgroundScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun <P : Any, T> read(
        fence: MetadataFence,
        key: String,
        policy: MetadataSnapshotTtlPolicy,
        decodeSnapshot: (Any) -> P?,
        fetch: suspend () -> P,
        materialize: suspend (P) -> T,
    ): T {
        requireCurrent(fence)
        val now = cache.now()
        cache.get<P>(fence, key)?.let { entry ->
            if (!MetadataCacheRules.isFresh(entry.fetchedAtMs, now, policy.softTtlMs)) {
                scheduleRefresh(fence, key, fetch)
            }
            return materializeCurrent(fence, entry.value, materialize)
        }

        val diskResult =
            withContext(ioDispatcher) { store.read(fence, key, maxAgeMs = policy.maxAgeMs) }
        if (diskResult is MetadataSnapshotReadResult.Hit) {
            val value = decodeSnapshot(diskResult.value)
            if (value != null && cache.putIfCurrent(fence, key, value, diskResult.savedAtEpochMs)) {
                if (
                    !MetadataCacheRules.isFresh(
                        diskResult.savedAtEpochMs,
                        now,
                        policy.softTtlMs,
                    )
                ) {
                    scheduleRefresh(fence, key, fetch)
                }
                return materializeCurrent(fence, value, materialize)
            }
        }

        val value = fetchForeground(fence, key, allowJoin = true, fetch = fetch)
        return materializeCurrent(fence, value, materialize)
    }

    fun invalidateAll() {
        val fence = cache.currentFence() ?: return
        cache.invalidateAll()
        store.invalidateNamespace(fence.namespace)
    }

    private fun <P : Any> scheduleRefresh(
        fence: MetadataFence,
        key: String,
        fetch: suspend () -> P,
    ) {
        val now = cache.now()
        if (!cache.canStartFetch(key, now)) return
        val (deferred, owner) = coordinator.join(fence, key)
        if (!owner) return

        backgroundScope.launch {
            try {
                executeOwner(fence, key, deferred, fetch)
            } catch (cancellation: CancellationException) {
                if (deferred.isActive) deferred.completeExceptionally(cancellation)
            } catch (_: Throwable) {
                if (deferred.isActive) {
                    deferred.completeExceptionally(
                        IllegalStateException("background refresh failed")
                    )
                }
            } finally {
                coordinator.finish(fence, key, deferred)
            }
        }
    }

    private suspend fun <P : Any> fetchForeground(
        fence: MetadataFence,
        key: String,
        allowJoin: Boolean,
        fetch: suspend () -> P,
    ): P {
        requireCurrent(fence)
        if (allowJoin) {
            val (deferred, owner) = coordinator.join(fence, key)
            if (!owner) {
                return try {
                    @Suppress("UNCHECKED_CAST")
                    deferred.await() as P
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Throwable) {
                    if (!cache.isCurrent(fence)) {
                        throw CancellationException("metadata fence changed after owner failure")
                    }
                    fetchForeground(fence, key, allowJoin = false, fetch = fetch)
                }
            }
            return try {
                executeOwner(fence, key, deferred, fetch)
            } finally {
                coordinator.finish(fence, key, deferred)
            }
        }

        cache.markFetchStarted(key, cache.now())
        val value = fetch()
        if (!commit(fence, key, value)) {
            throw CancellationException("metadata fence changed before commit")
        }
        return value
    }

    private suspend fun <P : Any> executeOwner(
        fence: MetadataFence,
        key: String,
        deferred: CompletableDeferred<Any?>,
        fetch: suspend () -> P,
    ): P {
        requireCurrent(fence)
        cache.markFetchStarted(key, cache.now())
        val value =
            try {
                fetch()
            } catch (throwable: Throwable) {
                if (deferred.isActive) deferred.completeExceptionally(throwable)
                throw throwable
            }
        if (!commit(fence, key, value)) {
            throw CancellationException("metadata fence changed before commit")
        }
        deferred.complete(value)
        return value
    }

    private suspend fun <P : Any> commit(
        fence: MetadataFence,
        key: String,
        value: P,
    ): Boolean {
        if (!cache.isCurrent(fence)) return false
        withContext(ioDispatcher) { store.write(fence, key, value) }
        if (!cache.isCurrent(fence)) return false
        return cache.putIfCurrent(fence, key, value)
    }

    private suspend fun <T, R> materializeCurrent(
        fence: MetadataFence,
        value: T,
        materialize: suspend (T) -> R,
    ): R {
        val result = materialize(value)
        requireCurrent(fence)
        return result
    }

    private fun requireCurrent(fence: MetadataFence) {
        if (!cache.isCurrent(fence)) {
            throw CancellationException("metadata fence changed")
        }
    }
}
