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
    @Volatile private var quarantinedSnapshot: IdentitySnapshot? = null

    override suspend fun hydrate(): IdentitySnapshot = transitionMutex.withLock {
        val snapshot = quarantinedSnapshot ?: store.read()
        cachedSnapshot = snapshot
        snapshot
    }

    override suspend fun current(): IdentitySnapshot =
        quarantinedSnapshot ?: cachedSnapshot ?: hydrate()

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

            val previousVisible = store.read().also { cachedSnapshot = it }
            val wasQuarantined = store.isQuarantined()
            if (wasQuarantined && quarantinedSnapshot == null) {
                quarantinedSnapshot = previousVisible
            }
            val persisted = store.persistedForTransition()
            val sameSelection = persisted.selection == selection
            val readyNoOp =
                !wasQuarantined &&
                    quarantinedSnapshot == null &&
                    sameSelection &&
                    persisted.status == IdentityStateStatus.READY
            if (readyNoOp && projection === IdentityProjection.None) {
                return@withLock IdentityTransitionResult.NO_OP
            }

            val candidate =
                if (sameSelection) {
                    persisted
                } else {
                    val recovering = runCatching {
                        store.nextSnapshot(
                            selection = selection,
                            status = IdentityStateStatus.RECOVERING,
                        )
                    }
                        .getOrNull()
                    if (recovering == null) {
                        cachedSnapshot = store.read()
                        return@withLock IdentityTransitionResult.FAILED
                    }
                    when (store.write(recovering)) {
                        IdentityWriteOutcome.COMMITTED -> {
                            store.persistedForTransition().also { cachedSnapshot = it }
                        }
                        IdentityWriteOutcome.NOT_COMMITTED -> {
                            cachedSnapshot = store.read()
                            return@withLock IdentityTransitionResult.FAILED
                        }
                        IdentityWriteOutcome.UNCERTAIN -> {
                            enterProcessQuarantine(store.read())
                            return@withLock IdentityTransitionResult.RECOVERING
                        }
                    }
                }
            cachedSnapshot = candidate
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
                if (candidate.status == IdentityStateStatus.READY) {
                    val recovery = candidate.copy(status = IdentityStateStatus.RECOVERING)
                    when (store.write(recovery)) {
                        IdentityWriteOutcome.COMMITTED -> cachedSnapshot = recovery
                        IdentityWriteOutcome.NOT_COMMITTED,
                        IdentityWriteOutcome.UNCERTAIN -> {
                            enterProcessQuarantine(previousVisible)
                        }
                    }
                } else {
                    refreshVisibleSnapshot(candidate)
                }
                return@withLock IdentityTransitionResult.RECOVERING
            }
            if (intent != intentSequence.get()) {
                return@withLock IdentityTransitionResult.STALE_DISCARDED
            }

            if (readyNoOp) {
                cachedSnapshot = candidate
                return@withLock IdentityTransitionResult.NO_OP
            }

            val ready = candidate.copy(status = IdentityStateStatus.READY)
            when (store.write(ready)) {
                IdentityWriteOutcome.COMMITTED -> {
                    quarantinedSnapshot = null
                    cachedSnapshot = ready
                    IdentityTransitionResult.APPLIED
                }
                IdentityWriteOutcome.NOT_COMMITTED,
                IdentityWriteOutcome.UNCERTAIN -> {
                    enterProcessQuarantine(store.read())
                    IdentityTransitionResult.RECOVERING
                }
            }
        }
    }

    private fun refreshVisibleSnapshot(snapshot: IdentitySnapshot) {
        cachedSnapshot = quarantinedSnapshot ?: snapshot
    }

    private fun enterProcessQuarantine(base: IdentitySnapshot) {
        val unavailable =
            IdentitySnapshot(
                selection = IdentitySelection(),
                identityGeneration = base.identityGeneration,
                status = IdentityStateStatus.RECOVERING,
            )
        quarantinedSnapshot = unavailable
        cachedSnapshot = unavailable
    }
}
