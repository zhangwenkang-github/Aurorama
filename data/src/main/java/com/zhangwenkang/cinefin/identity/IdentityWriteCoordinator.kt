package com.zhangwenkang.cinefin.identity

import com.zhangwenkang.cinefin.settings.domain.OfflineModeWritePort
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Singleton
class IdentityWriteCoordinator @Inject constructor(private val store: IdentityStateStore) :
    IdentityReadPort, IdentityWritePort, OfflineModeWritePort {
    private val transitionMutex = Mutex()
    private val intentSequence = AtomicLong(0L)

    @Volatile private var cachedSnapshot: IdentitySnapshot? = null

    override suspend fun hydrate(): IdentitySnapshot = transitionMutex.withLock {
        store.read().also { snapshot -> cachedSnapshot = snapshot }
    }

    override suspend fun current(): IdentitySnapshot = cachedSnapshot ?: hydrate()

    override suspend fun selectServer(
        selection: IdentitySelection,
        projection: IdentityProjection,
    ): IdentityTransitionResult {
        if (selection.serverId == null) return IdentityTransitionResult.FAILED
        return transition(selection, projection)
    }

    override suspend fun selectAddress(
        selection: IdentitySelection,
        projection: IdentityProjection,
    ): IdentityTransitionResult {
        if (selection.serverId == null || selection.addressId == null) {
            return IdentityTransitionResult.FAILED
        }
        return transition(selection, projection)
    }

    override suspend fun selectSavedUser(
        selection: IdentitySelection,
        projection: IdentityProjection,
    ): IdentityTransitionResult {
        if (selection.serverId == null || selection.userId == null) {
            return IdentityTransitionResult.FAILED
        }
        return transition(selection, projection)
    }

    override suspend fun selectAuthenticatedUser(
        selection: IdentitySelection,
        projection: IdentityProjection,
    ): IdentityTransitionResult {
        if (selection.serverId == null || selection.userId == null) {
            return IdentityTransitionResult.FAILED
        }
        return transition(selection, projection)
    }

    override suspend fun selectOfflineMode(
        enabled: Boolean,
        projection: IdentityProjection,
    ): IdentityTransitionResult {
        val selection = current().selection.copy(offlineMode = enabled)
        return transition(selection, projection)
    }

    override suspend fun deleteSelection(
        selection: IdentitySelection,
        projection: IdentityProjection,
    ): IdentityTransitionResult = transition(selection, projection)

    override suspend fun setOfflineMode(enabled: Boolean) {
        selectOfflineMode(enabled)
    }

    private suspend fun transition(
        selection: IdentitySelection,
        projection: IdentityProjection,
    ): IdentityTransitionResult {
        val intent = intentSequence.incrementAndGet()
        return transitionMutex.withLock {
            if (intent != intentSequence.get()) {
                return@withLock IdentityTransitionResult.STALE_DISCARDED
            }

            val persisted = store.read().also { cachedSnapshot = it }
            when {
                persisted.status == IdentityStateStatus.READY &&
                    persisted.selection == selection -> {
                    return@withLock IdentityTransitionResult.NO_OP
                }
                persisted.status == IdentityStateStatus.RECOVERING &&
                    persisted.selection == selection -> {
                    // Retry the projection without advancing generation.
                }
                else -> {
                    val recovering = runCatching {
                        store.nextSnapshot(
                            selection = selection,
                            status = IdentityStateStatus.RECOVERING,
                        )
                    }
                        .getOrNull()
                    if (recovering == null || !store.write(recovering)) {
                        cachedSnapshot = store.read()
                        return@withLock IdentityTransitionResult.FAILED
                    }
                    cachedSnapshot = recovering
                }
            }
            val candidate = store.read().also { cachedSnapshot = it }
            if (intent != intentSequence.get()) {
                return@withLock IdentityTransitionResult.STALE_DISCARDED
            }

            try {
                projection.project(candidate)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (intent != intentSequence.get()) {
                    return@withLock IdentityTransitionResult.STALE_DISCARDED
                }
                cachedSnapshot = candidate
                return@withLock IdentityTransitionResult.RECOVERING
            }
            if (intent != intentSequence.get()) {
                return@withLock IdentityTransitionResult.STALE_DISCARDED
            }

            val persistedBeforeReady = store.read()
            if (
                persistedBeforeReady.status != IdentityStateStatus.RECOVERING ||
                    persistedBeforeReady.identityGeneration != candidate.identityGeneration ||
                    persistedBeforeReady.selection != candidate.selection
            ) {
                cachedSnapshot = persistedBeforeReady
                return@withLock IdentityTransitionResult.STALE_DISCARDED
            }

            val ready = persistedBeforeReady.copy(status = IdentityStateStatus.READY)
            if (store.write(ready)) {
                cachedSnapshot = ready
                IdentityTransitionResult.APPLIED
            } else {
                cachedSnapshot = persistedBeforeReady
                IdentityTransitionResult.RECOVERING
            }
        }
    }
}
