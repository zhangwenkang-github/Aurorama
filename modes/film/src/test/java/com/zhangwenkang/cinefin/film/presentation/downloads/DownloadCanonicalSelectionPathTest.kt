package com.zhangwenkang.cinefin.film.presentation.downloads

import com.zhangwenkang.cinefin.utils.DownloadControlPlaneRules
import com.zhangwenkang.cinefin.utils.DownloadMediaKind
import com.zhangwenkang.cinefin.utils.DownloadTask
import com.zhangwenkang.cinefin.utils.DownloadTaskGroup
import com.zhangwenkang.cinefin.utils.DownloadTaskStatus
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadCanonicalSelectionPathTest {

    private val itemId = UUID.fromString("11111111-1111-4111-8111-111111111111")

    @Test
    fun `真实 builder control plane selection 与删除候选保留跨 server source 同 UUID`() {
        val tasks =
            listOf(
                task("source-a", "server-a"),
                task("source-b", "server-b"),
            )
        val active = DownloadControlPlaneRules.activeUniqueIntents(tasks, emptySet())
        val entries = tasks.map(::entry)
        val containers = DownloadHierarchyBuilder.build(entries)
        val state =
            DownloadManagerState(
                activeTasks = active,
                activeContainers = containers,
                selectionMode = true,
                selection =
                    DownloadSelectionRules.keysFor(
                        DownloadManagerState(activeContainers = containers),
                        DownloadTaskGroup.ACTIVE,
                    ),
            )

        assertEquals(2, active.size)
        assertEquals(2, DownloadSelectionRules.keysFor(state, DownloadTaskGroup.ACTIVE).size)
        val failedTasks = tasks.map { it.copy(status = DownloadTaskStatus.FAILED) }
        val failedEntries = failedTasks.map(::entry)
        assertEquals(
            2,
            DownloadSelectionRules.retryableTasks(
                    DownloadManagerState(
                        failedTasks = failedTasks,
                        failedContainers = DownloadHierarchyBuilder.build(failedEntries),
                        selectionMode = true,
                        selection = failedEntries.map { it.key },
                    )
                )
                .size,
        )
        assertEquals(2, DownloadDeletionRules.candidates(entries, emptyList()).size)
    }

    private fun task(sourceId: String, serverId: String) =
        DownloadTask(
            itemId = itemId,
            sourceId = sourceId,
            name = "电影",
            path = "/tmp/$sourceId.download",
            downloadId = null,
            status = DownloadTaskStatus.RUNNING,
            failureReason = null,
            downloadedBytes = 1L,
            totalBytes = 2L,
            updatedAt = 1L,
            mediaKind = DownloadMediaKind.VIDEO,
            serverId = serverId,
            userId = "user-a",
        )

    private fun entry(task: DownloadTask) =
        DownloadHierarchyEntry(
            itemId = task.itemId,
            name = task.name,
            mediaKind = task.mediaKind,
            status = task.status,
            sourceId = task.sourceId,
            task = task,
            serverId = task.serverId,
            userId = task.userId,
        )
}
