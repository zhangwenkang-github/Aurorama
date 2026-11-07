package com.zhangwenkang.cinefin.identity

import com.zhangwenkang.cinefin.settings.domain.AppPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
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
        val oldSelection = IdentitySelection(serverId = "server-old")
        val newSelection = IdentitySelection(serverId = "server-new")

        val oldAttempt = async {
            coordinator.selectServer(oldSelection) {
                oldProjectionStarted.complete(Unit)
                releaseOldProjection.await()
            }
        }
        oldProjectionStarted.await()

        val newAttempt = async { coordinator.selectServer(newSelection) }
        yield()

        releaseOldProjection.complete(Unit)
        assertEquals(IdentityTransitionResult.STALE_DISCARDED, oldAttempt.await())
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
        assertNoReadySnapshotFor(sharedPreferences, oldSelection)
    }

    @Test
    fun lateOldProjectionFailureDoesNotDowngradeNewIntent() = runBlocking {
        val sharedPreferences = IdentityTestSharedPreferences()
        val store = IdentityStateStore(AppPreferences(sharedPreferences))
        val coordinator = IdentityWriteCoordinator(store)
        val oldProjectionStarted = CompletableDeferred<Unit>()
        val releaseOldProjection = CompletableDeferred<Unit>()
        val oldSelection = IdentitySelection(serverId = "server-old")
        val newSelection = IdentitySelection(serverId = "server-new")

        val oldAttempt = async {
            coordinator.selectServer(oldSelection) {
                oldProjectionStarted.complete(Unit)
                releaseOldProjection.await()
                error("old projection failed")
            }
        }
        oldProjectionStarted.await()
        val newAttempt = async { coordinator.selectServer(newSelection) }
        yield()

        releaseOldProjection.complete(Unit)
        assertEquals(IdentityTransitionResult.STALE_DISCARDED, oldAttempt.await())
        assertEquals(IdentityTransitionResult.APPLIED, newAttempt.await())
        assertEquals(newSelection, coordinator.current().selection)
        assertEquals(IdentityStateStatus.READY, coordinator.current().status)
        assertEquals(2L, coordinator.current().identityGeneration)
        assertNoReadySnapshotFor(sharedPreferences, oldSelection)
    }

    @Test
    fun cancellationPropagatesWhileNewIntentConvergesWithoutOldResurrection() = runBlocking {
        val sharedPreferences = IdentityTestSharedPreferences()
        val store = IdentityStateStore(AppPreferences(sharedPreferences))
        val coordinator = IdentityWriteCoordinator(store)
        val oldProjectionStarted = CompletableDeferred<Unit>()
        val releaseOldProjection = CompletableDeferred<Unit>()
        val oldSelection = IdentitySelection(serverId = "server-old")
        val newSelection = IdentitySelection(serverId = "server-new")

        val oldAttempt = async {
            coordinator.selectServer(oldSelection) {
                oldProjectionStarted.complete(Unit)
                releaseOldProjection.await()
                throw CancellationException("old projection cancelled")
            }
        }
        oldProjectionStarted.await()
        val newAttempt = async { coordinator.selectServer(newSelection) }
        yield()

        releaseOldProjection.complete(Unit)
        assertThrows(CancellationException::class.java) { runBlocking { oldAttempt.await() } }
        assertEquals(IdentityTransitionResult.APPLIED, newAttempt.await())
        assertEquals(newSelection, coordinator.current().selection)
        assertEquals(IdentityStateStatus.READY, coordinator.current().status)
        assertEquals(2L, coordinator.current().identityGeneration)
        assertNoReadySnapshotFor(sharedPreferences, oldSelection)
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
            coordinator.selectServer(selection) { error("projection failed") },
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
            coordinator.selectServer(newSelection) { projectionCalls += 1 },
        )
        assertEquals(1, projectionCalls)
        assertEquals(IdentitySelection(), coordinator.current().selection)
        assertEquals(IdentityStateStatus.RECOVERING, coordinator.current().status)
        assertEquals(IdentitySelection(), coordinator.hydrate().selection)

        assertEquals(
            IdentityTransitionResult.APPLIED,
            coordinator.selectServer(newSelection) { projectionCalls += 1 },
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
            coordinator.selectServer(selection) { successfulProjectionCalls += 1 },
        )
        assertEquals(1, successfulProjectionCalls)
        assertEquals(IdentityStateStatus.READY, coordinator.current().status)
        assertEquals(generation, coordinator.current().identityGeneration)

        var failingProjectionCalls = 0
        assertEquals(
            IdentityTransitionResult.RECOVERING,
            coordinator.selectServer(selection) {
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
}
