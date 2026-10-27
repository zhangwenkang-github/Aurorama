package com.zhangwenkang.cinefin.utils

import java.util.UUID
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadIntentActionRulesTest {

    @Test
    fun `intent phases never expose pause because no source task is pausable yet`() {
        DownloadIntentPhase.entries.forEach { phase ->
            val task =
                task(
                    sourceId = intentSourceId(ITEM_ID),
                    status = DownloadTaskStatus.PENDING,
                    intentPhase = phase,
                )

            assertFalse("$phase must not expose pause", DownloadTaskRules.canPause(task))
        }
    }

    @Test
    fun `ordinary pending source keeps pause eligibility`() {
        val task =
            task(
                sourceId = "visible-source",
                status = DownloadTaskStatus.PENDING,
                intentPhase = null,
            )

        assertTrue(DownloadTaskRules.canPause(task))
    }

    private fun task(
        sourceId: String,
        status: DownloadTaskStatus,
        intentPhase: DownloadIntentPhase?,
    ): DownloadTask =
        DownloadTask(
            itemId = ITEM_ID,
            sourceId = sourceId,
            name = "Action",
            path = "",
            downloadId = null,
            status = status,
            failureReason = null,
            downloadedBytes = 0L,
            totalBytes = 0L,
            updatedAt = 0L,
            mediaKind = DownloadMediaKind.MUSIC,
            intentPhase = intentPhase,
        )

    private companion object {
        val ITEM_ID: UUID = UUID.fromString("84848484-8484-4484-8484-848484848484")
    }
}
