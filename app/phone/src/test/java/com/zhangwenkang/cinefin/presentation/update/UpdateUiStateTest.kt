package com.zhangwenkang.cinefin.presentation.update

import com.zhangwenkang.cinefin.update.StoredReleaseState
import com.zhangwenkang.cinefin.update.UPDATE_STATE_SCHEMA_VERSION
import com.zhangwenkang.cinefin.update.UpdateFailureCode
import com.zhangwenkang.cinefin.update.UpdateTerminalResult
import com.zhangwenkang.cinefin.update.UpdateTransferPhase
import com.zhangwenkang.cinefin.update.UpdateTransferState
import com.zhangwenkang.cinefin.update.releaseAssetUrl
import com.zhangwenkang.cinefin.update.stableUpdateId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateUiStateTest {

    @Test
    fun availableReleaseIsDiscoverableWithManualCheckAndDownloadActions() {
        val release = releaseState()

        val state = buildUpdateUiState(currentVersion = "1.3.0", release = release)

        assertEquals(UpdateUiPhase.AVAILABLE, state.phase)
        assertEquals("1.4.0", state.availableVersion)
        assertEquals(UpdatePrimaryAction.DOWNLOAD, state.primaryAction)
        assertTrue(state.canCheck)
    }

    @Test
    fun persistedDownloadingStateRestoresByteProgress() {
        val transfer = transferState(phase = UpdateTransferPhase.DOWNLOADING)

        val state =
            buildUpdateUiState(
                currentVersion = "1.3.0",
                release = releaseState(),
                transfer = transfer,
                downloadedBytes = 37,
            )

        assertEquals(UpdateUiPhase.DOWNLOADING, state.phase)
        assertEquals(37L, state.downloadedBytes)
        assertEquals(100L, state.totalBytes)
        assertEquals(UpdatePrimaryAction.CANCEL, state.primaryAction)
    }

    @Test
    fun persistedFailureStateRestoresRetryWithoutClaimingInstallability() {
        val transfer =
            transferState(
                phase = UpdateTransferPhase.FAILED,
                failureCode = UpdateFailureCode.NETWORK_ERROR,
            )

        val state =
            buildUpdateUiState(
                currentVersion = "1.3.0",
                release = releaseState(),
                transfer = transfer,
            )

        assertEquals(UpdateUiPhase.FAILED, state.phase)
        assertEquals(UpdateFailureCode.NETWORK_ERROR, state.failureCode)
        assertEquals(UpdatePrimaryAction.RETRY, state.primaryAction)
    }

    @Test
    fun persistedCancelledStateRestoresAnIndependentRetryWithoutClaimingFailure() {
        val transfer = transferState(phase = UpdateTransferPhase.CANCELLED)

        val state =
            buildUpdateUiState(
                currentVersion = "1.3.0",
                release = releaseState(),
                transfer = transfer,
                downloadedBytes = 37,
            )

        assertEquals(UpdateUiPhase.CANCELLED, state.phase)
        assertEquals(UpdatePrimaryAction.RETRY, state.primaryAction)
        assertEquals(37L, state.downloadedBytes)
        assertNull(state.failureCode)
    }

    @Test
    fun completedDownloadStopsAtReadyForVerification() {
        val transfer = transferState(phase = UpdateTransferPhase.READY_FOR_VERIFICATION)

        val state =
            buildUpdateUiState(
                currentVersion = "1.3.0",
                release = releaseState(),
                transfer = transfer,
            )

        assertEquals(UpdateUiPhase.READY_FOR_VERIFICATION, state.phase)
        assertEquals(UpdatePrimaryAction.NONE, state.primaryAction)
        assertNull(state.failureCode)
    }

    @Test
    fun persistedAvailableReleaseSurvivesARecordReload() {
        val release = releaseState()

        val first = buildUpdateUiState(currentVersion = "1.3.0", release = release)
        val reloaded = buildUpdateUiState(currentVersion = "1.3.0", release = release.copy())

        assertEquals(first, reloaded)
        assertEquals(stableUpdateId(release.release), reloaded.updateId)
    }

    @Test
    fun checkRunningMapsToCheckingWhileCheckFailureRemainsActionable() {
        val checking =
            buildUpdateUiState(
                currentVersion = "1.3.0",
                release = null,
                checkRunning = true,
            )
        val failed =
            buildUpdateUiState(
                currentVersion = "1.3.0",
                release = null,
                checkTerminal = UpdateTerminalResult.NETWORK_ERROR,
            )

        assertEquals(UpdateUiPhase.CHECKING, checking.phase)
        assertEquals(UpdatePrimaryAction.NONE, checking.primaryAction)
        assertEquals(UpdateUiPhase.FAILED, failed.phase)
        assertEquals(UpdatePrimaryAction.RETRY_CHECK, failed.primaryAction)
    }

    @Test
    fun manualTerminalWinsOverEveryAutomaticTerminalCombination() {
        val combinations =
            listOf(
                UpdateTerminalResult.AVAILABLE to UpdateTerminalResult.UP_TO_DATE,
                UpdateTerminalResult.UP_TO_DATE to UpdateTerminalResult.NETWORK_ERROR,
                UpdateTerminalResult.NETWORK_ERROR to UpdateTerminalResult.AVAILABLE,
            )

        combinations.forEach { (automaticResult, manualResult) ->
            val selected =
                resolveUpdateCheckWork(
                    automatic =
                        UpdateCheckWorkObservation(
                            status = UpdateCheckWorkStatus.SUCCEEDED,
                            terminal = automaticResult,
                        ),
                    manual =
                        UpdateCheckWorkObservation(
                            status = UpdateCheckWorkStatus.SUCCEEDED,
                            terminal = manualResult,
                        ),
                )

            assertFalse(selected.running)
            assertEquals(manualResult, selected.terminal)
        }
    }

    @Test
    fun manualQueuedOrRunningTakesPriorityOverAutomaticTerminal() {
        val automaticTerminal =
            UpdateCheckWorkObservation(
                status = UpdateCheckWorkStatus.SUCCEEDED,
                terminal = UpdateTerminalResult.AVAILABLE,
            )

        val queued =
            resolveUpdateCheckWork(
                automatic = automaticTerminal,
                manual = UpdateCheckWorkObservation(status = UpdateCheckWorkStatus.QUEUED),
            )
        val running =
            resolveUpdateCheckWork(
                automatic = automaticTerminal,
                manual = UpdateCheckWorkObservation(status = UpdateCheckWorkStatus.RUNNING),
            )

        assertTrue(queued.running)
        assertNull(queued.terminal)
        assertTrue(running.running)
        assertNull(running.terminal)
    }

    @Test
    fun queuedAutomaticWorkDoesNotShadowManualSuccess() {
        val selected =
            resolveUpdateCheckWork(
                automatic = UpdateCheckWorkObservation(status = UpdateCheckWorkStatus.QUEUED),
                manual =
                    UpdateCheckWorkObservation(
                        status = UpdateCheckWorkStatus.SUCCEEDED,
                        terminal = UpdateTerminalResult.UP_TO_DATE,
                    ),
            )

        assertFalse(selected.running)
        assertEquals(UpdateTerminalResult.UP_TO_DATE, selected.terminal)
    }

    @Test
    fun automaticTerminalRemainsVisibleButLongDelayQueuedAutomaticIsIdle() {
        val terminal =
            resolveUpdateCheckWork(
                automatic =
                    UpdateCheckWorkObservation(
                        status = UpdateCheckWorkStatus.SUCCEEDED,
                        terminal = UpdateTerminalResult.UP_TO_DATE,
                    ),
                manual = UpdateCheckWorkObservation(),
            )
        val queued =
            resolveUpdateCheckWork(
                automatic = UpdateCheckWorkObservation(status = UpdateCheckWorkStatus.QUEUED),
                manual = UpdateCheckWorkObservation(),
            )

        assertFalse(terminal.running)
        assertEquals(UpdateTerminalResult.UP_TO_DATE, terminal.terminal)
        assertFalse(queued.running)
        assertNull(queued.terminal)
    }

    private fun releaseState(): StoredReleaseState {
        val version = "1.4.0"
        val assetName = "Aurorama-$version-arm64-v8a.apk"
        return StoredReleaseState(
            schemaVersion = UPDATE_STATE_SCHEMA_VERSION,
            releaseTag = "v$version",
            version = version,
            assetName = assetName,
            assetSize = 100L,
            assetDigest = null,
            downloadUrl = releaseAssetUrl(version, assetName),
            discoveredAtEpochMs = 1_000L,
            updatedAtEpochMs = 2_000L,
        )
    }

    private fun transferState(
        phase: UpdateTransferPhase,
        failureCode: UpdateFailureCode? =
            if (phase == UpdateTransferPhase.FAILED) UpdateFailureCode.NETWORK_ERROR else null,
    ): UpdateTransferState {
        val release = releaseState().release
        return UpdateTransferState(
            schemaVersion = UPDATE_STATE_SCHEMA_VERSION,
            updateId = stableUpdateId(release),
            releaseTag = release.releaseTag,
            version = release.version,
            assetName = release.assetName,
            assetSize = release.assetSize,
            assetDigest = release.assetDigest,
            downloadUrl = release.downloadUrl,
            phase = phase,
            etag = null,
            lastModified = null,
            startedAtEpochMs = 1_000L,
            updatedAtEpochMs = 2_000L,
            failureCode = failureCode,
        )
    }
}
