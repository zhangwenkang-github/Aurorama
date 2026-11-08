package com.zhangwenkang.cinefin.identity

import com.zhangwenkang.cinefin.settings.domain.AppPreferences
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IdentityHydrationTest {
    @Test
    fun newProcessHydratesPersistedCanonicalInsteadOfOldCoordinatorMemory() = runBlocking {
        val preferences = IdentityTestSharedPreferences()
        val appPreferences = AppPreferences(preferences)
        val oldSnapshot =
            IdentitySnapshot(
                selection =
                    IdentitySelection(
                        serverId = "server-old",
                        userId = "user-old",
                        addressId = "address-old",
                    ),
                identityGeneration = 4L,
                status = IdentityStateStatus.READY,
            )
        val currentSnapshot =
            IdentitySnapshot(
                selection =
                    IdentitySelection(
                        serverId = "server-current",
                        userId = "user-current",
                        addressId = "address-current",
                    ),
                identityGeneration = 8L,
                status = IdentityStateStatus.READY,
            )
        assertEquals(
            IdentityWriteOutcome.COMMITTED,
            IdentityStateStore(appPreferences).write(oldSnapshot),
        )
        val previousProcess = IdentityWriteCoordinator(IdentityStateStore(appPreferences))
        assertEquals(oldSnapshot, previousProcess.hydrate())
        assertEquals(
            IdentityWriteOutcome.COMMITTED,
            IdentityStateStore(appPreferences).write(currentSnapshot),
        )

        val restartedProcess = IdentityWriteCoordinator(IdentityStateStore(appPreferences))

        assertEquals(currentSnapshot, restartedProcess.hydrate())
        assertEquals(currentSnapshot, restartedProcess.current())
        assertFalse(restartedProcess.current().selection == oldSnapshot.selection)
    }

    @Test
    fun recoveringCanonicalIsExplicitlyReturnedAndNeverGuessedFromLegacyProjection() = runBlocking {
        val preferences = IdentityTestSharedPreferences()
        val appPreferences = AppPreferences(preferences)
        val recovering =
            IdentitySnapshot(
                selection =
                    IdentitySelection(
                        serverId = "server-recovering",
                        userId = "user-recovering",
                        addressId = "address-recovering",
                    ),
                identityGeneration = 12L,
                status = IdentityStateStatus.RECOVERING,
            )
        assertEquals(
            IdentityWriteOutcome.COMMITTED,
            IdentityStateStore(appPreferences).write(recovering),
        )

        val firstHydration = IdentityWriteCoordinator(IdentityStateStore(appPreferences))
        val secondHydration = IdentityWriteCoordinator(IdentityStateStore(appPreferences))

        assertEquals(recovering, firstHydration.hydrate())
        assertEquals(recovering, secondHydration.hydrate())
    }

    @Test
    fun corruptCanonicalAndLegacyProjectionProduceNoIdentityOnRestart() = runBlocking {
        val preferences = IdentityTestSharedPreferences()
        preferences
            .edit()
            .putString(IDENTITY_STATE_PREFERENCE_KEY, "identity-v1:corrupt")
            .putString("pref_current_server", "legacy-server")
            .putBoolean("pref_offline_mode", true)
            .commit()

        val snapshot =
            IdentityWriteCoordinator(IdentityStateStore(AppPreferences(preferences))).hydrate()

        assertEquals(IdentitySelection(), snapshot.selection)
        assertEquals(IdentityStateStatus.RECOVERING, snapshot.status)
    }

    @Test
    fun missingCanonicalStateProducesNoIdentityOnRestart() = runBlocking {
        val preferences = IdentityTestSharedPreferences()
        val snapshot =
            IdentityWriteCoordinator(IdentityStateStore(AppPreferences(preferences))).hydrate()

        assertEquals(IdentitySelection(), snapshot.selection)
    }

    @Test
    fun apiModuleMustHydrateCanonicalBeforeAnyLegacyTokenInference() {
        val source =
            repositoryFile("core/src/main/java/com/zhangwenkang/cinefin/di/ApiModule.kt").readText()

        assertTrue(source.contains("class IdentityHydrationGate"))
        assertTrue(source.contains("identityReadPort.hydrate()"))
        assertFalse(source.contains("runBlocking"))
        assertFalse(source.contains("appPreferences.currentServer"))
    }

    private fun repositoryFile(path: String): File {
        var current = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        while (!File(current, "settings.gradle.kts").isFile) {
            current = requireNotNull(current.parentFile) { "Repository root not found for $path" }
        }
        return File(current, path)
    }
}
