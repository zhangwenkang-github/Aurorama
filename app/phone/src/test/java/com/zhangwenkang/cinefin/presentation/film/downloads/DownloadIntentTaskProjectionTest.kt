package com.zhangwenkang.cinefin.presentation.film.downloads

import com.zhangwenkang.cinefin.film.presentation.downloads.DownloadContainerTaskAction
import com.zhangwenkang.cinefin.film.presentation.downloads.DownloadHierarchyBuilder
import com.zhangwenkang.cinefin.film.presentation.downloads.DownloadHierarchyEntry
import com.zhangwenkang.cinefin.film.presentation.downloads.taskActionSpecs
import com.zhangwenkang.cinefin.utils.DownloadIntentPhase
import com.zhangwenkang.cinefin.utils.DownloadTask
import com.zhangwenkang.cinefin.utils.DownloadTaskPhase
import com.zhangwenkang.cinefin.utils.DownloadTaskRules
import com.zhangwenkang.cinefin.utils.DownloadTaskStatus
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadIntentTaskProjectionTest {

    @Test
    fun `intent only phases expose delete but never an ineffective pause`() {
        DownloadIntentPhase.entries.forEach { intentPhase ->
            val task = pendingIntentTask(intentPhase)
            val specs = downloadRowActionSpecs(task, canDelete = true, pending = false)

            assertFalse("$intentPhase 不得显示无效 Pause", DownloadTaskRules.canPause(task))
            assertEquals(
                "$intentPhase 只保留有效删除动作：${specs.map { it.action }}",
                listOf(DownloadRowTaskAction.DELETE),
                specs.map { it.action },
            )
        }
    }

    @Test
    fun `ordinary pending source without intent keeps pause eligibility`() {
        val task = pendingSourceTask()

        assertTrue(DownloadTaskRules.canPause(task))
        assertEquals(
            listOf(DownloadRowTaskAction.PAUSE, DownloadRowTaskAction.DELETE),
            downloadRowActionSpecs(task, canDelete = true, pending = false).map { it.action },
        )
    }

    @Test
    fun `source-created pending source keeps pause eligibility`() {
        val task = pendingSourceTask().copy(intentPhase = DownloadIntentPhase.SOURCE_PENDING)

        assertTrue(DownloadTaskRules.canPause(task))
        assertEquals(
            listOf(DownloadRowTaskAction.PAUSE, DownloadRowTaskAction.DELETE),
            downloadRowActionSpecs(task, canDelete = true, pending = false).map { it.action },
        )
    }

    @Test
    fun `intent only container action specs never offer pause`() {
        val task = pendingIntentTask(DownloadIntentPhase.SOURCE_PENDING)
        val container =
            DownloadHierarchyBuilder.build(
                    listOf(
                        DownloadHierarchyEntry(
                            itemId = task.itemId,
                            name = task.name,
                            mediaKind = task.mediaKind,
                            status = task.status,
                            task = task,
                            totalBytes = 0L,
                            albumName = "Album",
                            trackIndex = 0,
                        )
                    )
                )
                .single()

        assertTrue(
            "intent-only container must not advertise a pause action",
            container.taskActionSpecs().none { it.action == DownloadContainerTaskAction.PAUSE },
        )
    }

    @Test
    fun `real row badge and projection test share the same phase resource mapping`() {
        assertEquals(
            com.zhangwenkang.cinefin.core.R.string.download_intent_queued,
            downloadIntentPhaseLabel(DownloadTaskPhase.QUEUED),
        )
        assertEquals(
            com.zhangwenkang.cinefin.core.R.string.download_intent_resolving,
            downloadIntentPhaseLabel(DownloadTaskPhase.RESOLVING),
        )
        assertEquals(
            com.zhangwenkang.cinefin.core.R.string.download_intent_source_pending,
            downloadIntentPhaseLabel(DownloadTaskPhase.SOURCE_PENDING),
        )
        assertNotNull(
            "production badge must consume downloadIntentPhaseLabel for source-created rows",
            downloadIntentPhaseLabel(DownloadTaskPhase.SOURCE_PENDING),
        )
    }

    private fun pendingIntentTask(phase: DownloadIntentPhase): DownloadTask =
        DownloadTask(
            itemId = ITEM_ID,
            sourceId = "intent:$ITEM_ID",
            name = "Intent",
            path = "",
            downloadId = null,
            status = DownloadTaskStatus.PENDING,
            failureReason = null,
            downloadedBytes = 0L,
            totalBytes = 0L,
            updatedAt = 0L,
            intentPhase = phase,
        )

    private fun pendingSourceTask(): DownloadTask =
        DownloadTask(
            itemId = ITEM_ID,
            sourceId = "source",
            name = "Source",
            path = "",
            downloadId = null,
            status = DownloadTaskStatus.PENDING,
            failureReason = null,
            downloadedBytes = 0L,
            totalBytes = 0L,
            updatedAt = 0L,
        )

    private companion object {
        val ITEM_ID: UUID = UUID.fromString("83838383-8383-4383-8383-838383838383")
    }
}
