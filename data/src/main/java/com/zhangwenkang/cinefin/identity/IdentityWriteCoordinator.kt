package com.zhangwenkang.cinefin.identity

import com.zhangwenkang.cinefin.settings.domain.OfflineModeWritePort
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

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
        val intent = transitionMutex.withLock { intentSequence.incrementAndGet() }
        val prepared = transitionMutex.withLock {
            if (intent != intentSequence.get()) {
                return@withLock Preparation.Complete(IdentityTransitionResult.STALE_DISCARDED)
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
                return@withLock Preparation.Complete(IdentityTransitionResult.NO_OP)
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
                        return@withLock Preparation.Complete(IdentityTransitionResult.FAILED)
                    }
                    when (store.write(recovering)) {
                        IdentityWriteOutcome.COMMITTED -> {
                            store.persistedForTransition().also { cachedSnapshot = it }
                        }
                        IdentityWriteOutcome.NOT_COMMITTED -> {
                            cachedSnapshot = store.read()
                            return@withLock Preparation.Complete(IdentityTransitionResult.FAILED)
                        }
                        IdentityWriteOutcome.UNCERTAIN -> {
                            enterProcessQuarantine(store.read())
                            return@withLock Preparation.Complete(
                                IdentityTransitionResult.RECOVERING
                            )
                        }
                    }
                }
            cachedSnapshot = candidate
            Preparation.Project(
                previousVisible = previousVisible,
                candidate = candidate,
                readyNoOp = readyNoOp,
            )
        }

        when (prepared) {
            is Preparation.Complete -> return prepared.result
            is Preparation.Project -> Unit
        }

        val permit = OneShotProjectionPermit(intent)
        val projectionFailure =
            try {
                projection.project(prepared.candidate, permit)
                null
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) { transitionMutex.withLock { permit.close() } }
                throw cancelled
            } catch (error: Exception) {
                error
            }

        return transitionMutex.withLock {
            permit.close()
            if (intent != intentSequence.get()) {
                return@withLock IdentityTransitionResult.STALE_DISCARDED
            }

            if (projectionFailure != null) {
                if (prepared.candidate.status == IdentityStateStatus.READY) {
                    val recovery = prepared.candidate.copy(status = IdentityStateStatus.RECOVERING)
                    when (store.write(recovery)) {
                        IdentityWriteOutcome.COMMITTED -> cachedSnapshot = recovery
                        IdentityWriteOutcome.NOT_COMMITTED,
                        IdentityWriteOutcome.UNCERTAIN -> {
                            enterProcessQuarantine(prepared.previousVisible)
                        }
                    }
                } else {
                    refreshVisibleSnapshot(prepared.candidate)
                }
                return@withLock IdentityTransitionResult.RECOVERING
            }

            if (prepared.readyNoOp) {
                cachedSnapshot = prepared.candidate
                return@withLock IdentityTransitionResult.NO_OP
            }

            val ready = prepared.candidate.copy(status = IdentityStateStatus.READY)
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

    private inner class OneShotProjectionPermit(private val intent: Long) :
        IdentityProjectionPermit {
        private var consumed = false
        private var closed = false

        override suspend fun commit(effect: suspend () -> Unit): Boolean =
            transitionMutex.withLock {
                if (closed || consumed || intent != intentSequence.get()) {
                    return@withLock false
                }
                consumed = true
                effect()
                true
            }

        fun close() {
            closed = true
        }
    }

    private sealed interface Preparation {
        data class Complete(val result: IdentityTransitionResult) : Preparation

        data class Project(
            val previousVisible: IdentitySnapshot,
            val candidate: IdentitySnapshot,
            val readyNoOp: Boolean,
        ) : Preparation
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
