package com.zhangwenkang.cinefin.identity

import com.zhangwenkang.cinefin.settings.domain.AppPreferences
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
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
        val coordinator = coordinator()
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
        assertEquals(newSelection, coordinator.current().selection)
        assertEquals(accepted, coordinator.current())
    }

    @Test
    fun recreatedCoordinatorContinuesFromPersistedGeneration() = runBlocking {
        val sharedPreferences = IdentityTestSharedPreferences()
        val store = IdentityStateStore(AppPreferences(sharedPreferences))
        assertTrue(
            store.write(
                IdentitySnapshot(
                    selection = IdentitySelection(serverId = "server-a"),
                    identityGeneration = 41L,
                    status = IdentityStateStatus.READY,
                )
            )
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
    fun failedCanonicalCommitReturnsFailedAndPreservesPreviousState() = runBlocking {
        val sharedPreferences = IdentityTestSharedPreferences()
        val store = IdentityStateStore(AppPreferences(sharedPreferences))
        val previous =
            IdentitySnapshot(
                selection = IdentitySelection(serverId = "server-old"),
                identityGeneration = 9L,
                status = IdentityStateStatus.READY,
            )
        assertTrue(store.write(previous))
        val coordinator = IdentityWriteCoordinator(store)
        sharedPreferences.failNextCommit = true

        assertEquals(
            IdentityTransitionResult.FAILED,
            coordinator.selectServer(IdentitySelection(serverId = "server-new")),
        )
        assertEquals(previous, coordinator.hydrate())
        assertEquals(previous, store.read())
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
}
