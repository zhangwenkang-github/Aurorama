package com.zhangwenkang.cinefin.identity

import android.content.SharedPreferences
import com.zhangwenkang.cinefin.settings.domain.AppPreferences
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class IdentityWriteCoordinatorTest {
    @Test
    fun acceptedSwitchAdvancesExactlyOnceAndNoOpDoesNotAdvance() = runBlocking {
        val coordinator = coordinator()
        val serverA = IdentitySelection(serverId = "server-a")
        val serverB = IdentitySelection(serverId = "server-b")

        assertEquals(
            IdentityTransitionResult.APPLIED,
            coordinator.selectServer(serverA),
        )
        val first = coordinator.current()
        assertEquals(1L, first.identityGeneration)
        assertEquals(IdentityStateStatus.READY, first.status)

        assertEquals(
            IdentityTransitionResult.NO_OP,
            coordinator.selectServer(serverA),
        )
        assertEquals(first, coordinator.current())

        assertEquals(
            IdentityTransitionResult.APPLIED,
            coordinator.selectServer(serverB),
        )
        val second = coordinator.current()
        assertEquals(2L, second.identityGeneration)
        assertEquals(serverB, second.selection)
    }

    @Test
    fun lateOldIntentReturnsStaleWithoutOverwritingNewCanonicalState() = runBlocking {
        val sharedPreferences = IdentityTestSharedPreferences()
        val store = IdentityStateStore(AppPreferences(sharedPreferences))
        val coordinator = IdentityWriteCoordinator(store)
        val oldProjectionStarted = CompletableDeferred<Unit>()
        val releaseOldProjection = CompletableDeferred<Unit>()
        var oldSideEffects = 0
        val oldSelection = IdentitySelection(serverId = "server-old", offlineMode = true)
        val newSelection = IdentitySelection(serverId = "server-new", offlineMode = true)

        val oldAttempt = async {
            coordinator.selectServer(oldSelection) { _, permit ->
                oldProjectionStarted.complete(Unit)
                releaseOldProjection.await()
                permit.commit { oldSideEffects += 1 }
            }
        }
        oldProjectionStarted.await()

        val newAttempt =
            async(start = CoroutineStart.UNDISPATCHED) { coordinator.selectServer(newSelection) }

        releaseOldProjection.complete(Unit)
        assertEquals(IdentityTransitionResult.STALE_DISCARDED, oldAttempt.await())
        assertEquals(0, oldSideEffects)
        assertEquals(IdentityTransitionResult.APPLIED, newAttempt.await())
        val accepted = coordinator.current()
        assertEquals(newSelection, accepted.selection)
        assertEquals(IdentityStateStatus.READY, accepted.status)
        assertEquals(2L, accepted.identityGeneration)
        assertEquals(newSelection, store.read().selection)
        assertEquals(
            "server-new",
            sharedPreferences.getString(
                AppPreferences(sharedPreferences).currentServer.backendName,
                null,
            ),
        )
        assertEquals(
            true,
            sharedPreferences.getBoolean(
                AppPreferences(sharedPreferences).offlineMode.backendName,
                false,
            ),
        )
        assertNoReadySnapshotFor(sharedPreferences, oldSelection)
    }

    @Test
    fun lateOldProjectionFailureDoesNotDowngradeNewIntent() = runBlocking {
        val sharedPreferences = IdentityTestSharedPreferences()
        val store = IdentityStateStore(AppPreferences(sharedPreferences))
        val coordinator = IdentityWriteCoordinator(store)
        val oldProjectionStarted = CompletableDeferred<Unit>()
        val releaseOldProjection = CompletableDeferred<Unit>()
        var oldSideEffects = 0
        val oldSelection = IdentitySelection(serverId = "server-old", offlineMode = true)
        val newSelection = IdentitySelection(serverId = "server-new", offlineMode = true)

        val oldAttempt = async {
            coordinator.selectServer(oldSelection) { _, permit ->
                oldProjectionStarted.complete(Unit)
                releaseOldProjection.await()
                permit.commit { oldSideEffects += 1 }
                error("old projection failed")
            }
        }
        oldProjectionStarted.await()
        val newAttempt =
            async(start = CoroutineStart.UNDISPATCHED) { coordinator.selectServer(newSelection) }

        releaseOldProjection.complete(Unit)
        assertEquals(IdentityTransitionResult.STALE_DISCARDED, oldAttempt.await())
        assertEquals(0, oldSideEffects)
        assertEquals(IdentityTransitionResult.APPLIED, newAttempt.await())
        assertEquals(newSelection, coordinator.current().selection)
        assertEquals(IdentityStateStatus.READY, coordinator.current().status)
        assertEquals(2L, coordinator.current().identityGeneration)
        assertEquals(newSelection, store.read().selection)
        assertEquals(2L, store.read().identityGeneration)
        assertEquals(
            "server-new",
            sharedPreferences.getString(
                AppPreferences(sharedPreferences).currentServer.backendName,
                null,
            ),
        )
        assertEquals(
            true,
            sharedPreferences.getBoolean(
                AppPreferences(sharedPreferences).offlineMode.backendName,
                false,
            ),
        )
        assertNoReadySnapshotFor(sharedPreferences, oldSelection)
    }

    @Test
    fun cancellationPropagatesWhileNewIntentConvergesWithoutOldResurrection() = runBlocking {
        val sharedPreferences = IdentityTestSharedPreferences()
        val store = IdentityStateStore(AppPreferences(sharedPreferences))
        val coordinator = IdentityWriteCoordinator(store)
        val oldProjectionStarted = CompletableDeferred<Unit>()
        val releaseOldProjection = CompletableDeferred<Unit>()
        var oldSideEffects = 0
        val oldSelection = IdentitySelection(serverId = "server-old", offlineMode = true)
        val newSelection = IdentitySelection(serverId = "server-new", offlineMode = true)

        val oldAttempt = async {
            coordinator.selectServer(oldSelection) { _, permit ->
                oldProjectionStarted.complete(Unit)
                releaseOldProjection.await()
                permit.commit { oldSideEffects += 1 }
                throw CancellationException("old projection cancelled")
            }
        }
        oldProjectionStarted.await()
        val newAttempt =
            async(start = CoroutineStart.UNDISPATCHED) { coordinator.selectServer(newSelection) }

        releaseOldProjection.complete(Unit)
        assertThrows(CancellationException::class.java) { runBlocking { oldAttempt.await() } }
        assertEquals(0, oldSideEffects)
        assertEquals(IdentityTransitionResult.APPLIED, newAttempt.await())
        assertEquals(newSelection, coordinator.current().selection)
        assertEquals(IdentityStateStatus.READY, coordinator.current().status)
        assertEquals(2L, coordinator.current().identityGeneration)
        assertEquals(newSelection, store.read().selection)
        assertEquals(2L, store.read().identityGeneration)
        assertEquals(
            "server-new",
            sharedPreferences.getString(
                AppPreferences(sharedPreferences).currentServer.backendName,
                null,
            ),
        )
        assertEquals(
            true,
            sharedPreferences.getBoolean(
                AppPreferences(sharedPreferences).offlineMode.backendName,
                false,
            ),
        )
        assertNoReadySnapshotFor(sharedPreferences, oldSelection)
    }

    @Test
    fun cancellationDuringFinalPermitCloseInvalidatesCapturedPermit() = runBlocking {
        val sharedPreferences = BlockingIdentitySharedPreferences()
        val store = IdentityStateStore(AppPreferences(sharedPreferences))
        val coordinator = IdentityWriteCoordinator(store)
        val selection = IdentitySelection(serverId = "server-cancelled")
        val projectionStarted = CompletableDeferred<Unit>()
        val allowProjectionReturn = CompletableDeferred<Unit>()
        var capturedPermit: IdentityProjectionPermit? = null

        val transition = async {
            coordinator.selectServer(selection) { _, permit ->
                capturedPermit = permit
                projectionStarted.complete(Unit)
                allowProjectionReturn.await()
                currentCoroutineContext().job.cancel()
            }
        }
        boundedStage("projection-started") { projectionStarted.await() }

        sharedPreferences.armBlock()
        val blocker = async(Dispatchers.Default) { coordinator.hydrate() }
        sharedPreferences.awaitBlocked()
        allowProjectionReturn.complete(Unit)

        val cancellation =
            boundedStage("transition-cancellation") {
                runCatching { transition.await() }.exceptionOrNull()
            }
        assertTrue("expected transition cancellation", cancellation is CancellationException)

        sharedPreferences.releaseBlock()
        boundedStage("blocked-hydrate") { blocker.await() }

        var effects = 0
        val permit = checkNotNull(capturedPermit)
        val committed = boundedStage("late-permit-commit") { permit.commit { effects += 1 } }
        assertFalse(
            "cancelled transition permit must be closed; committed=$committed effects=$effects",
            committed,
        )
        assertEquals(0, effects)
    }

    @Test
    fun recreatedCoordinatorContinuesFromPersistedGeneration() = runBlocking {
        val sharedPreferences = IdentityTestSharedPreferences()
        val store = IdentityStateStore(AppPreferences(sharedPreferences))
        assertEquals(
            IdentityWriteOutcome.COMMITTED,
            store.write(
                IdentitySnapshot(
                    selection = IdentitySelection(serverId = "server-a"),
                    identityGeneration = 41L,
                    status = IdentityStateStatus.READY,
                )
            ),
        )

        val restartedStore = IdentityStateStore(AppPreferences(sharedPreferences))
        val result =
            IdentityWriteCoordinator(restartedStore)
                .selectServer(IdentitySelection(serverId = "server-b"))

        assertEquals(IdentityTransitionResult.APPLIED, result)
        assertEquals(42L, restartedStore.read().identityGeneration)
    }

    @Test
    fun projectionFailureStaysRecoveringAndRetryReusesGeneration() = runBlocking {
        val coordinator = coordinator()
        val selection = IdentitySelection(serverId = "server-a", userId = "user-a")

        assertEquals(
            IdentityTransitionResult.RECOVERING,
            coordinator.selectServer(selection) { _, _ -> error("projection failed") },
        )
        val recovering = coordinator.current()
        assertEquals(IdentityStateStatus.RECOVERING, recovering.status)
        assertEquals(1L, recovering.identityGeneration)

        assertEquals(
            IdentityTransitionResult.APPLIED,
            coordinator.selectServer(selection),
        )
        val ready = coordinator.current()
        assertEquals(IdentityStateStatus.READY, ready.status)
        assertEquals(recovering.identityGeneration, ready.identityGeneration)

        assertEquals(
            IdentityTransitionResult.NO_OP,
            coordinator.selectServer(selection),
        )
        assertEquals(ready, coordinator.current())
    }

    @Test
    fun uncertainCanonicalCommitFailsClosedUntilACompleteTupleCommits() = runBlocking {
        val sharedPreferences = IdentityTestSharedPreferences()
        val store = IdentityStateStore(AppPreferences(sharedPreferences))
        val previous =
            IdentitySnapshot(
                selection = IdentitySelection(serverId = "server-old"),
                identityGeneration = 9L,
                status = IdentityStateStatus.READY,
            )
        assertEquals(IdentityWriteOutcome.COMMITTED, store.write(previous))
        val coordinator = IdentityWriteCoordinator(store)
        sharedPreferences.failNextCommit = true

        assertEquals(
            IdentityTransitionResult.RECOVERING,
            coordinator.selectServer(IdentitySelection(serverId = "server-new")),
        )
        val unavailable = coordinator.hydrate()
        assertEquals(IdentitySelection(), unavailable.selection)
        assertEquals(IdentityStateStatus.RECOVERING, unavailable.status)
        assertEquals(IdentitySelection(), coordinator.current().selection)

        assertEquals(
            IdentityTransitionResult.APPLIED,
            coordinator.selectServer(IdentitySelection(serverId = "server-new")),
        )
        assertEquals(
            IdentitySnapshot(
                selection = IdentitySelection(serverId = "server-new"),
                identityGeneration = previous.identityGeneration + 1L,
                status = IdentityStateStatus.READY,
            ),
            coordinator.current(),
        )
    }

    @Test
    fun uncertainReadyCommitNeverPublishesReadyAndRetryVerifiesProjectionAgain() = runBlocking {
        val sharedPreferences = IdentityTestSharedPreferences()
        val store = IdentityStateStore(AppPreferences(sharedPreferences))
        val coordinator = IdentityWriteCoordinator(store)
        val oldSelection = IdentitySelection(serverId = "server-old")
        val newSelection = IdentitySelection(serverId = "server-new")
        assertEquals(IdentityTransitionResult.APPLIED, coordinator.selectServer(oldSelection))
        sharedPreferences.resetWriteCounts()
        sharedPreferences.failCommitOnCall = 2
        var projectionCalls = 0

        assertEquals(
            IdentityTransitionResult.RECOVERING,
            coordinator.selectServer(newSelection) { _, _ -> projectionCalls += 1 },
        )
        assertEquals(1, projectionCalls)
        assertEquals(IdentitySelection(), coordinator.current().selection)
        assertEquals(IdentityStateStatus.RECOVERING, coordinator.current().status)
        assertEquals(IdentitySelection(), coordinator.hydrate().selection)

        assertEquals(
            IdentityTransitionResult.APPLIED,
            coordinator.selectServer(newSelection) { _, _ -> projectionCalls += 1 },
        )
        assertEquals(2, projectionCalls)
        assertEquals(newSelection, coordinator.current().selection)
        assertEquals(IdentityStateStatus.READY, coordinator.current().status)
        assertEquals(2L, coordinator.current().identityGeneration)
    }

    @Test
    fun readyNoOpSkipsAbsentProjectionButRevalidatesProvidedProjection() = runBlocking {
        val coordinator = coordinator()
        val selection = IdentitySelection(serverId = "server-a")
        assertEquals(IdentityTransitionResult.APPLIED, coordinator.selectServer(selection))
        val generation = coordinator.current().identityGeneration

        assertEquals(
            IdentityTransitionResult.NO_OP,
            coordinator.selectServer(selection),
        )
        assertEquals(generation, coordinator.current().identityGeneration)

        var successfulProjectionCalls = 0
        assertEquals(
            IdentityTransitionResult.NO_OP,
            coordinator.selectServer(selection) { _, permit ->
                permit.commit { successfulProjectionCalls += 1 }
            },
        )
        assertEquals(1, successfulProjectionCalls)
        assertEquals(IdentityStateStatus.READY, coordinator.current().status)
        assertEquals(generation, coordinator.current().identityGeneration)

        var failingProjectionCalls = 0
        assertEquals(
            IdentityTransitionResult.RECOVERING,
            coordinator.selectServer(selection) { _, _ ->
                failingProjectionCalls += 1
                error("projection now fails")
            },
        )
        assertEquals(1, failingProjectionCalls)
        assertEquals(selection, coordinator.current().selection)
        assertEquals(IdentityStateStatus.RECOVERING, coordinator.current().status)
        assertEquals(generation, coordinator.current().identityGeneration)
    }

    @Test
    fun staleReadyNoOpProjectionReturnsStaleWithoutMutatingGenerationOrStatus() = runBlocking {
        val sharedPreferences = IdentityTestSharedPreferences()
        val store = IdentityStateStore(AppPreferences(sharedPreferences))
        val coordinator = IdentityWriteCoordinator(store)
        val readySelection = IdentitySelection(serverId = "server-ready", offlineMode = true)
        val newSelection = IdentitySelection(serverId = "server-new", offlineMode = true)
        assertEquals(IdentityTransitionResult.APPLIED, coordinator.selectServer(readySelection))
        val readyGeneration = coordinator.current().identityGeneration
        val oldProjectionStarted = CompletableDeferred<Unit>()
        val releaseOldProjection = CompletableDeferred<Unit>()
        var oldSideEffects = 0

        val oldAttempt = async {
            coordinator.selectServer(readySelection) { _, permit ->
                oldProjectionStarted.complete(Unit)
                releaseOldProjection.await()
                permit.commit { oldSideEffects += 1 }
            }
        }
        oldProjectionStarted.await()
        val newAttempt =
            async(start = CoroutineStart.UNDISPATCHED) { coordinator.selectServer(newSelection) }

        releaseOldProjection.complete(Unit)
        assertEquals(IdentityTransitionResult.STALE_DISCARDED, oldAttempt.await())
        assertEquals(0, oldSideEffects)
        assertEquals(IdentityTransitionResult.APPLIED, newAttempt.await())
        assertEquals(newSelection, store.read().selection)
        assertEquals(IdentityStateStatus.READY, store.read().status)
        assertEquals(readyGeneration + 1L, store.read().identityGeneration)
    }

    @Test
    fun permitCommitBeforeNewIntentRegistrationOrdersEffectBeforeNewProjection() = runBlocking {
        val sharedPreferences = IdentityTestSharedPreferences()
        val store = IdentityStateStore(AppPreferences(sharedPreferences))
        val coordinator = IdentityWriteCoordinator(store)
        val oldSelection = IdentitySelection(serverId = "server-old")
        val newSelection = IdentitySelection(serverId = "server-new")
        val permitAcquired = CompletableDeferred<Unit>()
        val releaseEffect = CompletableDeferred<Unit>()
        val newProjectionStarted = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()

        val oldAttempt = async {
            coordinator.selectServer(oldSelection) { _, permit ->
                assertTrue(
                    permit.commit {
                        events += "old-effect-start"
                        permitAcquired.complete(Unit)
                        releaseEffect.await()
                        events += "old-effect-end"
                    }
                )
                var repeatedEffectRan = false
                assertFalse(permit.commit { repeatedEffectRan = true })
                assertFalse(repeatedEffectRan)
                newProjectionStarted.await()
            }
        }
        permitAcquired.await()
        val newAttempt =
            async(start = CoroutineStart.UNDISPATCHED) {
                coordinator.selectServer(newSelection) { _, _ ->
                    events += "new-projection"
                    newProjectionStarted.complete(Unit)
                }
            }

        assertTrue(!newAttempt.isCompleted)
        releaseEffect.complete(Unit)
        assertEquals(IdentityTransitionResult.STALE_DISCARDED, oldAttempt.await())
        assertEquals(IdentityTransitionResult.APPLIED, newAttempt.await())
        assertTrue(
            "new projection started before old effect completed: $events",
            events.indexOf("old-effect-end") < events.indexOf("new-projection"),
        )
        assertEquals(newSelection, store.read().selection)
        assertEquals(IdentityStateStatus.READY, store.read().status)
        assertEquals(2L, store.read().identityGeneration)
    }

    @Test
    fun conditionalTransitionDiscardsAttemptAfterExternalGenerationAdvance() = runBlocking {
        val coordinator = coordinator()
        val attempt = coordinator.beginIngress()
        val externalSelection = IdentitySelection(serverId = "server-external")
        val oldSelection = IdentitySelection(serverId = "server-old")
        var oldProjectionEffects = 0

        assertEquals(
            IdentityTransitionResult.APPLIED,
            coordinator.selectServer(externalSelection),
        )
        assertEquals(
            IdentityTransitionResult.STALE_DISCARDED,
            coordinator.selectServerIfGeneration(attempt, oldSelection) { _, permit ->
                permit.commit { oldProjectionEffects += 1 }
            },
        )

        assertEquals(0, oldProjectionEffects)
        assertEquals(1L, coordinator.current().identityGeneration)
        assertEquals(externalSelection, coordinator.current().selection)
    }

    @Test
    fun conditionalRetryReusesRecoveringGenerationWhenSelectionIsUnchanged() = runBlocking {
        val coordinator = coordinator()
        val selection = IdentitySelection(serverId = "server-a", userId = "user-a")
        val firstAttempt = coordinator.beginIngress()

        assertEquals(
            IdentityTransitionResult.RECOVERING,
            coordinator.selectServerIfGeneration(firstAttempt, selection) { _, _ ->
                error("projection failed")
            },
        )
        val recovering = coordinator.current()
        assertEquals(IdentityStateStatus.RECOVERING, recovering.status)
        assertEquals(1L, recovering.identityGeneration)

        val retryAttempt = coordinator.beginIngress()
        var committed = false
        assertEquals(
            IdentityTransitionResult.APPLIED,
            coordinator.selectServerIfGeneration(retryAttempt, selection) { snapshot, permit ->
                assertEquals(selection, snapshot.selection)
                assertTrue(
                    permit.commit {
                        assertEquals(snapshot, IdentityProjectionScope.currentSnapshot())
                        committed = true
                    }
                )
            },
        )

        assertTrue(committed)
        assertNull(IdentityProjectionScope.currentSnapshot())
        assertEquals(1L, coordinator.current().identityGeneration)
        assertEquals(IdentityStateStatus.READY, coordinator.current().status)
    }

    @Test
    fun oneIssuedAttemptCanCommitOnlyOneConcurrentConditionalTransition() = runBlocking {
        val coordinator = coordinator()
        val attempt = coordinator.beginIngress()
        val first = async { coordinator.selectServerIfGeneration(attempt, server("a")) }
        val second = async { coordinator.selectServerIfGeneration(attempt, server("b")) }

        val results = listOf(first.await(), second.await())
        assertEquals(1, results.count { it == IdentityTransitionResult.APPLIED })
        assertEquals(1, results.count { it == IdentityTransitionResult.STALE_DISCARDED })
        assertEquals(1L, coordinator.current().identityGeneration)
    }

    @Test
    fun externalPermitImplementationCannotMintProjectionScope() = runBlocking {
        var fabricatedEffects = 0
        val fabricated =
            object : IdentityProjectionPermit {
                override suspend fun commit(effect: suspend () -> Unit): Boolean {
                    effect()
                    return true
                }
            }

        assertTrue(fabricated.commit { fabricatedEffects += 1 })
        assertEquals(1, fabricatedEffects)
        assertNull(IdentityProjectionScope.currentSnapshot())
    }

    @Test
    fun readLeaseRejectsReadyRecoveringReadyAbaAndSkipsEffect() = runBlocking {
        val coordinator = coordinator()
        val selection = IdentitySelection(serverId = "server-a")
        assertEquals(IdentityTransitionResult.APPLIED, coordinator.selectServer(selection))
        val lease = checkNotNull(coordinator.acquireReadyLease())
        assertTrue(coordinator.isReadyLeaseCurrent(lease))

        assertEquals(
            IdentityTransitionResult.RECOVERING,
            coordinator.selectServer(selection) { _, _ -> error("projection failed") },
        )
        assertEquals(IdentityStateStatus.RECOVERING, coordinator.current().status)
        assertEquals(
            IdentityTransitionResult.APPLIED,
            coordinator.selectServer(selection),
        )
        assertEquals(IdentityStateStatus.READY, coordinator.current().status)
        assertEquals(1L, coordinator.current().identityGeneration)

        var effects = 0
        assertFalse(coordinator.isReadyLeaseCurrent(lease))
        assertNull(
            coordinator.withReadyLease(lease) {
                effects += 1
                selection
            }
        )
        assertEquals(0, effects)
    }

    @Test
    fun readyLeaseIssuesIngressAttemptAndRemainsSerializedWithHydrate() = runBlocking {
        val coordinator = coordinator()
        val selection = IdentitySelection(serverId = "server-a")
        assertEquals(IdentityTransitionResult.APPLIED, coordinator.selectServer(selection))
        val lease = checkNotNull(coordinator.acquireReadyLease())
        val effectEntered = CompletableDeferred<Unit>()
        val releaseEffect = CompletableDeferred<Unit>()
        var hydrated = false

        val leasedEffect = async {
            coordinator.withReadyLease(lease) {
                effectEntered.complete(Unit)
                releaseEffect.await()
                assertEquals(selection, coordinator.current().selection)
                selection
            }
        }
        boundedStage("lease-effect-entered") { effectEntered.await() }
        val hydrate = async { coordinator.hydrate().also { hydrated = true } }
        assertFalse(hydrate.isCompleted)
        releaseEffect.complete(Unit)

        assertEquals(selection, boundedStage("leased-effect") { leasedEffect.await() })
        assertEquals(
            selection,
            boundedStage("hydrate-after-lease") { hydrate.await().selection },
        )
        assertTrue(hydrated)

        val freshLease = checkNotNull(coordinator.acquireReadyLease())
        val attempt = checkNotNull(coordinator.beginIngressForLease(freshLease))
        assertTrue(coordinator.isIngressCurrent(attempt))
        assertEquals(
            IdentityTransitionResult.APPLIED,
            coordinator.selectServerIfGeneration(
                attempt,
                IdentitySelection(serverId = "server-b"),
            ),
        )
        assertEquals("server-b", coordinator.current().selection.serverId)
    }

    @Test
    fun staleReadyLeaseCannotRegisterIngressAttempt() = runBlocking {
        val coordinator = coordinator()
        val selection = IdentitySelection(serverId = "server-a")
        assertEquals(IdentityTransitionResult.APPLIED, coordinator.selectServer(selection))
        val lease = checkNotNull(coordinator.acquireReadyLease())
        assertEquals(
            IdentityTransitionResult.APPLIED,
            coordinator.selectServer(IdentitySelection(serverId = "server-b")),
        )

        assertFalse(coordinator.isReadyLeaseCurrent(lease))
        assertNull(coordinator.beginIngressForLease(lease))
    }

    @Test
    fun offlineTransitionAndFrozenWriteEntriesAreAvailable() = runBlocking {
        val coordinator = coordinator()
        val base =
            IdentitySelection(
                serverId = "server-a",
                userId = "user-a",
                addressId = "address-a",
            )

        assertEquals(IdentityTransitionResult.APPLIED, coordinator.selectServer(base))
        coordinator.setOfflineMode(true)

        val offline = coordinator.current()
        assertTrue(offline.selection.offlineMode)
        assertEquals(2L, offline.identityGeneration)

        assertEquals(
            IdentityTransitionResult.APPLIED,
            coordinator.selectAddress(base.copy(offlineMode = true, addressId = "address-b")),
        )
        assertEquals(
            IdentityTransitionResult.APPLIED,
            coordinator.selectSavedUser(base.copy(offlineMode = true, userId = "user-b")),
        )
        assertEquals(
            IdentityTransitionResult.APPLIED,
            coordinator.selectAuthenticatedUser(base.copy(offlineMode = true, userId = "user-c")),
        )
        assertEquals(
            IdentityTransitionResult.APPLIED,
            coordinator.deleteSelection(IdentitySelection(offlineMode = true)),
        )
    }

    private fun coordinator(): IdentityWriteCoordinator =
        IdentityWriteCoordinator(
            IdentityStateStore(AppPreferences(IdentityTestSharedPreferences()))
        )

    private fun server(suffix: String) = IdentitySelection(serverId = "server-$suffix")

    private fun assertNoReadySnapshotFor(
        sharedPreferences: IdentityTestSharedPreferences,
        selection: IdentitySelection,
    ) {
        sharedPreferences.committedSnapshots.forEach { snapshot ->
            assertTrue(
                "unexpected READY for $selection in $snapshot",
                snapshot.status != IdentityStateStatus.READY || snapshot.selection != selection,
            )
        }
    }

    private suspend fun <T> boundedStage(name: String, block: suspend () -> T): T =
        try {
            withTimeout(10_000L) { block() }
        } catch (timeout: TimeoutCancellationException) {
            throw AssertionError("TIMEOUT_STAGE=$name", timeout)
        }
}

private class BlockingIdentitySharedPreferences(
    private val delegate: IdentityTestSharedPreferences = IdentityTestSharedPreferences()
) : SharedPreferences {
    private val armBlock = AtomicBoolean(false)
    private val blocked = CompletableFuture<Unit>()
    private val release = CompletableFuture<Unit>()

    fun armBlock() {
        armBlock.set(true)
    }

    fun awaitBlocked() {
        check(blocked.get(10, TimeUnit.SECONDS) == Unit) { "blocker did not enter read" }
    }

    fun releaseBlock() {
        release.complete(Unit)
    }

    override fun getAll(): MutableMap<String, *> = delegate.getAll()

    override fun getString(key: String, defValue: String?): String? {
        if (armBlock.compareAndSet(true, false)) {
            blocked.complete(Unit)
            check(release.get(10, TimeUnit.SECONDS) == Unit) { "blocker release timed out" }
        }
        return delegate.getString(key, defValue)
    }

    override fun getStringSet(
        key: String,
        defValues: MutableSet<String>?,
    ): MutableSet<String>? = delegate.getStringSet(key, defValues)

    override fun getInt(key: String, defValue: Int): Int = delegate.getInt(key, defValue)

    override fun getLong(key: String, defValue: Long): Long = delegate.getLong(key, defValue)

    override fun getFloat(key: String, defValue: Float): Float = delegate.getFloat(key, defValue)

    override fun getBoolean(key: String, defValue: Boolean): Boolean =
        delegate.getBoolean(key, defValue)

    override fun contains(key: String): Boolean = delegate.contains(key)

    override fun edit(): SharedPreferences.Editor = delegate.edit()

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?
    ) = delegate.registerOnSharedPreferenceChangeListener(listener)

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?
    ) = delegate.unregisterOnSharedPreferenceChangeListener(listener)
}
