package com.zhangwenkang.cinefin.identity

import com.zhangwenkang.cinefin.settings.domain.OfflineModeWritePort
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Singleton
class IdentityWriteCoordinator @Inject constructor(private val store: IdentityStateStore) :
    IdentityReadPort,
    IdentityReadLeasePort,
    IdentityWritePort,
    IdentityConditionalWritePort,
    OfflineModeWritePort {
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

    override suspend fun acquireReadyLease(): IdentityReadLease? = transitionMutex.withLock {
        val snapshot = visibleSnapshotLocked()
        if (snapshot.status != IdentityStateStatus.READY) return@withLock null
        IdentityReadLease(snapshot = snapshot, intent = intentSequence.get())
    }

    override suspend fun <T> withReadyLease(
        lease: IdentityReadLease,
        effect: suspend (IdentitySelection) -> T,
    ): T? = transitionMutex.withLock {
        if (!isReadyLeaseCurrentLocked(lease)) return@withLock null
        effect(lease.snapshot.selection)
    }

    override suspend fun isReadyLeaseCurrent(lease: IdentityReadLease): Boolean =
        transitionMutex.withLock {
            isReadyLeaseCurrentLocked(lease)
        }

    override suspend fun beginIngressForLease(lease: IdentityReadLease): IdentityIngressAttempt? =
        transitionMutex.withLock {
            if (!isReadyLeaseCurrentLocked(lease)) return@withLock null
            IdentityIngressAttempt(
                intent = intentSequence.incrementAndGet(),
                expectedGeneration = lease.snapshot.identityGeneration,
            )
        }

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

    override suspend fun beginIngress(): IdentityIngressAttempt = transitionMutex.withLock {
        val persisted = store.persistedForTransition()
        IdentityIngressAttempt(
            intent = intentSequence.incrementAndGet(),
            expectedGeneration = persisted.identityGeneration,
        )
    }

    override suspend fun isIngressCurrent(attempt: IdentityIngressAttempt): Boolean =
        transitionMutex.withLock {
            attempt.intent == intentSequence.get() &&
                store.persistedForTransition().identityGeneration == attempt.expectedGeneration
        }

    override suspend fun selectServerIfGeneration(
        attempt: IdentityIngressAttempt,
        selection: IdentitySelection,
        projection: IdentityProjection,
    ): IdentityTransitionResult {
        if (selection.serverId == null) return IdentityTransitionResult.FAILED
        return conditionalTransition(attempt, selection, projection)
    }

    override suspend fun selectAddressIfGeneration(
        attempt: IdentityIngressAttempt,
        selection: IdentitySelection,
        projection: IdentityProjection,
    ): IdentityTransitionResult {
        if (selection.serverId == null || selection.addressId == null) {
            return IdentityTransitionResult.FAILED
        }
        return conditionalTransition(attempt, selection, projection)
    }

    override suspend fun selectSavedUserIfGeneration(
        attempt: IdentityIngressAttempt,
        selection: IdentitySelection,
        projection: IdentityProjection,
    ): IdentityTransitionResult {
        if (selection.serverId == null || selection.userId == null) {
            return IdentityTransitionResult.FAILED
        }
        return conditionalTransition(attempt, selection, projection)
    }

    override suspend fun selectAuthenticatedUserIfGeneration(
        attempt: IdentityIngressAttempt,
        selection: IdentitySelection,
        projection: IdentityProjection,
    ): IdentityTransitionResult {
        if (selection.serverId == null || selection.userId == null) {
            return IdentityTransitionResult.FAILED
        }
        return conditionalTransition(attempt, selection, projection)
    }

    override suspend fun deleteSelectionIfGeneration(
        attempt: IdentityIngressAttempt,
        selection: IdentitySelection,
        projection: IdentityProjection,
    ): IdentityTransitionResult = conditionalTransition(attempt, selection, projection)

    private suspend fun transition(
        selection: IdentitySelection,
        projection: IdentityProjection,
    ): IdentityTransitionResult {
        val prepared = transitionMutex.withLock {
            val intent = intentSequence.incrementAndGet()
            prepareTransitionLocked(intent, selection)
        }
        return project(prepared, projection)
    }

    private suspend fun conditionalTransition(
        attempt: IdentityIngressAttempt,
        selection: IdentitySelection,
        projection: IdentityProjection,
    ): IdentityTransitionResult {
        val prepared = transitionMutex.withLock {
            val persisted = store.persistedForTransition()
            if (
                attempt.intent != intentSequence.get() ||
                    persisted.identityGeneration != attempt.expectedGeneration
            ) {
                return@withLock Preparation.Complete(IdentityTransitionResult.STALE_DISCARDED)
            }

            val intent = intentSequence.incrementAndGet()
            prepareTransitionLocked(
                intent = intent,
                selection = selection,
                persistedOverride = persisted,
            )
        }
        return project(prepared, projection)
    }

    /**
     * Must only be called while [transitionMutex] is held. For conditional transitions the
     * persisted generation comparison, commit-intent registration and RECOVERING preparation all
     * happen in this one critical section.
     */
    private suspend fun prepareTransitionLocked(
        intent: Long,
        selection: IdentitySelection,
        persistedOverride: IdentitySnapshot? = null,
    ): Preparation {
        val previousVisible = store.read().also { cachedSnapshot = it }
        val wasQuarantined = store.isQuarantined()
        if (wasQuarantined && quarantinedSnapshot == null) {
            quarantinedSnapshot = previousVisible
        }
        val persisted = persistedOverride ?: store.persistedForTransition()
        val sameSelection = persisted.selection == selection
        val readyNoOp =
            !wasQuarantined &&
                quarantinedSnapshot == null &&
                sameSelection &&
                persisted.status == IdentityStateStatus.READY

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
                    return Preparation.Complete(IdentityTransitionResult.FAILED)
                }
                when (store.write(recovering)) {
                    IdentityWriteOutcome.COMMITTED -> {
                        store.persistedForTransition().also { cachedSnapshot = it }
                    }
                    IdentityWriteOutcome.NOT_COMMITTED -> {
                        cachedSnapshot = store.read()
                        return Preparation.Complete(IdentityTransitionResult.FAILED)
                    }
                    IdentityWriteOutcome.UNCERTAIN -> {
                        enterProcessQuarantine(store.read())
                        return Preparation.Complete(IdentityTransitionResult.RECOVERING)
                    }
                }
            }
        cachedSnapshot = candidate
        return Preparation.Project(
            intent = intent,
            previousVisible = previousVisible,
            candidate = candidate,
            readyNoOp = readyNoOp,
        )
    }

    private suspend fun project(
        prepared: Preparation,
        projection: IdentityProjection,
    ): IdentityTransitionResult {
        val project =
            when (prepared) {
                is Preparation.Complete -> return prepared.result
                is Preparation.Project -> prepared
            }

        val permit = OneShotProjectionPermit(project.intent, project.candidate)
        val projectionFailure =
            try {
                projection.project(project.candidate, permit)
                null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                error
            } finally {
                permit.close()
            }

        return transitionMutex.withLock {
            if (project.intent != intentSequence.get()) {
                return@withLock IdentityTransitionResult.STALE_DISCARDED
            }

            if (projectionFailure != null) {
                if (project.candidate.status == IdentityStateStatus.READY) {
                    val recovery = project.candidate.copy(status = IdentityStateStatus.RECOVERING)
                    when (store.write(recovery)) {
                        IdentityWriteOutcome.COMMITTED -> cachedSnapshot = recovery
                        IdentityWriteOutcome.NOT_COMMITTED,
                        IdentityWriteOutcome.UNCERTAIN -> {
                            enterProcessQuarantine(project.previousVisible)
                        }
                    }
                } else {
                    refreshVisibleSnapshot(project.candidate)
                }
                return@withLock IdentityTransitionResult.RECOVERING
            }

            if (project.readyNoOp) {
                cachedSnapshot = project.candidate
                return@withLock IdentityTransitionResult.NO_OP
            }

            val ready = project.candidate.copy(status = IdentityStateStatus.READY)
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

    private inner class OneShotProjectionPermit(
        private val intent: Long,
        private val snapshot: IdentitySnapshot,
    ) : IdentityProjectionPermit {
        private var consumed = false
        private val closed = AtomicBoolean(false)

        override suspend fun commit(effect: suspend () -> Unit): Boolean =
            transitionMutex.withLock {
                if (closed.get() || consumed || intent != intentSequence.get()) {
                    return@withLock false
                }
                consumed = true
                withIdentityProjectionAuthorization(snapshot) { effect() }
                true
            }

        fun close() {
            closed.set(true)
        }
    }

    private sealed interface Preparation {
        data class Complete(val result: IdentityTransitionResult) : Preparation

        data class Project(
            val intent: Long,
            val previousVisible: IdentitySnapshot,
            val candidate: IdentitySnapshot,
            val readyNoOp: Boolean,
        ) : Preparation
    }

    private fun refreshVisibleSnapshot(snapshot: IdentitySnapshot) {
        cachedSnapshot = quarantinedSnapshot ?: snapshot
    }

    private fun visibleSnapshotLocked(): IdentitySnapshot {
        val snapshot = quarantinedSnapshot ?: store.read()
        cachedSnapshot = snapshot
        return snapshot
    }

    private fun isReadyLeaseCurrentLocked(lease: IdentityReadLease): Boolean {
        val snapshot = visibleSnapshotLocked()
        return lease.intent == intentSequence.get() &&
            snapshot.status == IdentityStateStatus.READY &&
            snapshot == lease.snapshot
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
