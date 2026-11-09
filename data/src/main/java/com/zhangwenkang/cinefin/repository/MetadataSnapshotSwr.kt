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

internal enum class MetadataSnapshotFreshness {
    FRESH,
    STALE,
}

internal data class MetadataSnapshotRead<T>(
    val value: T,
    val freshness: MetadataSnapshotFreshness,
    val observedAtEpochMs: Long,
)

internal class MetadataSnapshotFenceChangedException :
    CancellationException("metadata fence changed")

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
    ): T =
        readWithMetadata(
                fence = fence,
                key = key,
                policy = policy,
                decodeSnapshot = decodeSnapshot,
                fetch = fetch,
                materialize = materialize,
            )
            .value

    suspend fun <P : Any, T> readWithMetadata(
        fence: MetadataFence,
        key: String,
        policy: MetadataSnapshotTtlPolicy,
        decodeSnapshot: (Any) -> P?,
        fetch: suspend () -> P,
        materialize: suspend (P) -> T,
        validateFence: (suspend (MetadataFence) -> Boolean)? = null,
    ): MetadataSnapshotRead<T> {
        requireCurrent(fence, validateFence)
        val now = cache.now()
        cache.get<P>(fence, key)?.let { entry ->
            val freshness =
                if (MetadataCacheRules.isFresh(entry.fetchedAtMs, now, policy.softTtlMs)) {
                    MetadataSnapshotFreshness.FRESH
                } else {
                    MetadataSnapshotFreshness.STALE
                }
            if (freshness == MetadataSnapshotFreshness.STALE) {
                scheduleRefresh(fence, key, fetch, validateFence)
            }
            return MetadataSnapshotRead(
                value = materializeCurrent(fence, entry.value, materialize, validateFence),
                freshness = freshness,
                observedAtEpochMs = entry.fetchedAtMs,
            )
        }

        val diskResult =
            withContext(ioDispatcher) { store.read(fence, key, maxAgeMs = policy.maxAgeMs) }
        if (diskResult is MetadataSnapshotReadResult.Hit) {
            val value = decodeSnapshot(diskResult.value)
            if (value != null) {
                requireCurrent(fence, validateFence)
                if (cache.putIfCurrent(fence, key, value, diskResult.savedAtEpochMs)) {
                    val freshness =
                        if (
                            MetadataCacheRules.isFresh(
                                diskResult.savedAtEpochMs,
                                now,
                                policy.softTtlMs,
                            )
                        ) {
                            MetadataSnapshotFreshness.FRESH
                        } else {
                            MetadataSnapshotFreshness.STALE
                        }
                    if (freshness == MetadataSnapshotFreshness.STALE) {
                        scheduleRefresh(fence, key, fetch, validateFence)
                    }
                    return MetadataSnapshotRead(
                        value = materializeCurrent(fence, value, materialize, validateFence),
                        freshness = freshness,
                        observedAtEpochMs = diskResult.savedAtEpochMs,
                    )
                }
            }
        }

        val value =
            fetchForeground(
                fence = fence,
                key = key,
                allowJoin = true,
                fetch = fetch,
                validateFence = validateFence,
            )
        val observedAtEpochMs = cache.get<P>(fence, key)?.fetchedAtMs ?: cache.now()
        return MetadataSnapshotRead(
            value = materializeCurrent(fence, value, materialize, validateFence),
            freshness = MetadataSnapshotFreshness.FRESH,
            observedAtEpochMs = observedAtEpochMs,
        )
    }

    fun invalidateAll() {
        val expectedFence = cache.currentFence() ?: return
        invalidateAll(expectedFence)
    }

    /** Invalidate the explicitly bound fence, never re-reading a potentially newer namespace. */
    fun invalidateAll(expectedFence: MetadataFence): Boolean {
        if (!cache.invalidateAll(expectedFence)) return false
        store.invalidateNamespace(expectedFence.namespace)
        return true
    }

    private suspend fun <P : Any> scheduleRefresh(
        fence: MetadataFence,
        key: String,
        fetch: suspend () -> P,
        validateFence: (suspend (MetadataFence) -> Boolean)?,
    ) {
        requireCurrent(fence, validateFence)
        val now = cache.now()
        if (!cache.canStartFetch(key, now)) return
        val (deferred, owner) = coordinator.join(fence, key)
        if (!owner) return

        backgroundScope.launch {
            try {
                executeOwner(fence, key, deferred, fetch, validateFence)
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
        validateFence: (suspend (MetadataFence) -> Boolean)?,
    ): P {
        requireCurrent(fence, validateFence)
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
                        throw MetadataSnapshotFenceChangedException()
                    }
                    fetchForeground(
                        fence = fence,
                        key = key,
                        allowJoin = false,
                        fetch = fetch,
                        validateFence = validateFence,
                    )
                }
            }
            return try {
                executeOwner(fence, key, deferred, fetch, validateFence)
            } finally {
                coordinator.finish(fence, key, deferred)
            }
        }

        cache.markFetchStarted(key, cache.now())
        val value = fetch()
        if (!commit(fence, key, value, validateFence)) {
            throw MetadataSnapshotFenceChangedException()
        }
        return value
    }

    private suspend fun <P : Any> executeOwner(
        fence: MetadataFence,
        key: String,
        deferred: CompletableDeferred<Any?>,
        fetch: suspend () -> P,
        validateFence: (suspend (MetadataFence) -> Boolean)?,
    ): P {
        requireCurrent(fence, validateFence)
        cache.markFetchStarted(key, cache.now())
        val value =
            try {
                fetch()
            } catch (throwable: Throwable) {
                if (deferred.isActive) deferred.completeExceptionally(throwable)
                throw throwable
            }
        if (!commit(fence, key, value, validateFence)) {
            throw MetadataSnapshotFenceChangedException()
        }
        deferred.complete(value)
        return value
    }

    private suspend fun <P : Any> commit(
        fence: MetadataFence,
        key: String,
        value: P,
        validateFence: (suspend (MetadataFence) -> Boolean)?,
    ): Boolean {
        if (!cache.isCurrent(fence) || validateFence?.invoke(fence) == false) return false
        withContext(ioDispatcher) { store.write(fence, key, value) }
        if (!cache.isCurrent(fence) || validateFence?.invoke(fence) == false) return false
        return cache.putIfCurrent(fence, key, value)
    }

    private suspend fun <T, R> materializeCurrent(
        fence: MetadataFence,
        value: T,
        materialize: suspend (T) -> R,
        validateFence: (suspend (MetadataFence) -> Boolean)?,
    ): R {
        requireCurrent(fence, validateFence)
        val result = materialize(value)
        requireCurrent(fence, validateFence)
        return result
    }

    private suspend fun requireCurrent(
        fence: MetadataFence,
        validateFence: (suspend (MetadataFence) -> Boolean)?,
    ) {
        if (!cache.isCurrent(fence) || validateFence?.invoke(fence) == false) {
            throw MetadataSnapshotFenceChangedException()
        }
    }
}
