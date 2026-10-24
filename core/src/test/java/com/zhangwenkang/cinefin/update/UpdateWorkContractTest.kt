package com.zhangwenkang.cinefin.update

import androidx.work.Data
import com.zhangwenkang.cinefin.work.UpdateResultMapping
import com.zhangwenkang.cinefin.work.UpdateWorkData
import com.zhangwenkang.cinefin.work.UpdateWorkerOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateWorkContractTest {
    @Test
    fun checkInputContainsOnlyMode() {
        val input = UpdateWorkData.checkInput(UpdateCheckMode.MANUAL)

        assertEquals(setOf(UpdateWorkData.KEY_MODE), input.keyValueMap.keys)
        assertEquals("MANUAL", input.getString(UpdateWorkData.KEY_MODE))
        assertEquals(UpdateCheckMode.MANUAL, UpdateWorkData.parseCheckMode(input))
    }

    @Test
    fun downloadInputContainsOnlyCanonicalUpdateId() {
        val updateId = "b".repeat(64)
        val input = UpdateWorkData.downloadInput(updateId)

        assertEquals(setOf(UpdateWorkData.KEY_UPDATE_ID), input.keyValueMap.keys)
        assertEquals(updateId, UpdateWorkData.parseUpdateId(input))
    }

    @Test
    fun terminalOutputContainsOnlySmallEnumerationAndOptionalIdentity() {
        val updateId = "c".repeat(64)
        val output =
            UpdateWorkData.resultOutput(
                result = UpdateTerminalResult.AVAILABLE,
                updateId = updateId,
            )

        assertEquals(
            setOf(UpdateWorkData.KEY_RESULT, UpdateWorkData.KEY_UPDATE_ID),
            output.keyValueMap.keys,
        )
        assertEquals(
            UpdateTerminalResult.AVAILABLE,
            UpdateWorkData.parseResult(output),
        )
        assertEquals(updateId, UpdateWorkData.parseUpdateId(output))
    }

    @Test
    fun invalidRawInputsAreRejected() {
        assertEquals(
            null,
            UpdateWorkData.parseCheckMode(rawInput(UpdateWorkData.KEY_MODE to "OTHER")),
        )
        assertNull(UpdateWorkData.parseUpdateId(rawInput("offset" to "4096")))
    }

    @Test
    fun checkResultsMapToStableTerminalEnumsWithoutOffsets() {
        assertEquals(
            UpdateWorkerOutcome(UpdateTerminalResult.UP_TO_DATE, isSuccess = true),
            UpdateResultMapping.forCheck(UpdateCheckResult.UpToDate(5L)),
        )
        assertEquals(
            UpdateWorkerOutcome(UpdateTerminalResult.AVAILABLE, isSuccess = true),
            UpdateResultMapping.forCheck(
                UpdateCheckResult.Available(updateId = "d".repeat(64), version = "1.4.0")
            ),
        )
        assertEquals(
            UpdateWorkerOutcome(UpdateTerminalResult.CHECK_GATED, isSuccess = true),
            UpdateResultMapping.forCheck(UpdateCheckResult.Gated),
        )
        assertEquals(
            UpdateWorkerOutcome(UpdateTerminalResult.CHECK_IN_PROGRESS, isSuccess = true),
            UpdateResultMapping.forCheck(UpdateCheckResult.AlreadyRunning),
        )
        assertEquals(
            UpdateWorkerOutcome(UpdateTerminalResult.NETWORK_ERROR, isSuccess = false),
            UpdateResultMapping.forCheck(
                UpdateCheckResult.Failed(UpdateCheckFailure.NETWORK_ERROR)
            ),
        )
        assertEquals(
            UpdateWorkerOutcome(UpdateTerminalResult.INVALID_RELEASE, isSuccess = false),
            UpdateResultMapping.forCheck(
                UpdateCheckResult.Failed(UpdateCheckFailure.INVALID_RELEASE)
            ),
        )
        assertEquals(
            UpdateWorkerOutcome(UpdateTerminalResult.NO_COMPATIBLE_ASSET, isSuccess = false),
            UpdateResultMapping.forCheck(
                UpdateCheckResult.Failed(UpdateCheckFailure.NO_COMPATIBLE_ASSET)
            ),
        )
        assertEquals(
            UpdateWorkerOutcome(UpdateTerminalResult.STORAGE_ERROR, isSuccess = false),
            UpdateResultMapping.forCheck(
                UpdateCheckResult.Failed(UpdateCheckFailure.STORAGE_ERROR)
            ),
        )
    }

    @Test
    fun downloadResultsNeverExceedReadyForVerification() {
        assertEquals(
            UpdateWorkerOutcome(UpdateTerminalResult.READY_FOR_VERIFICATION, isSuccess = true),
            UpdateResultMapping.forDownload(
                UpdateDownloadResult.ReadyForVerification(
                    updateId = "e".repeat(64),
                    completeFile = java.io.File("unused.apk"),
                    bytesWritten = 8L,
                )
            ),
        )
        assertEquals(
            UpdateWorkerOutcome(UpdateTerminalResult.NETWORK_ERROR, isSuccess = false),
            UpdateResultMapping.forDownload(
                UpdateDownloadResult.Failed(UpdateFailureCode.NETWORK_ERROR, "offline")
            ),
        )
        assertTrue(
            UpdateTerminalResult.entries.none {
                it.name.contains("INSTALL") || it.name.contains("VERIFIED")
            }
        )
    }

    private fun rawInput(vararg values: Pair<String, String>): Data {
        val builder = Data.Builder()
        values.forEach { (key, value) -> builder.putString(key, value) }
        return builder.build()
    }
}
