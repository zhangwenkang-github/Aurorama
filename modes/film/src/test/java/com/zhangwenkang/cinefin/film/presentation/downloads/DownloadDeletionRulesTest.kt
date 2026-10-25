package com.zhangwenkang.cinefin.film.presentation.downloads

import com.zhangwenkang.cinefin.utils.DownloadMediaKind
import com.zhangwenkang.cinefin.utils.DownloadTask
import com.zhangwenkang.cinefin.utils.DownloadTaskStatus
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadDeletionRulesTest {

    private fun entry(
        itemId: UUID,
        sourceId: String,
        serverId: String,
    ): DownloadHierarchyEntry {
        val task =
            DownloadTask(
                itemId = itemId,
                sourceId = sourceId,
                name = "曲目",
                path = "/tmp/$sourceId.download",
                downloadId = null,
                status = DownloadTaskStatus.FAILED,
                failureReason = null,
                downloadedBytes = 0L,
                totalBytes = 0L,
                updatedAt = 0L,
                mediaKind = DownloadMediaKind.MUSIC,
                serverId = serverId,
                userId = "user-a",
            )
        return DownloadHierarchyEntry(
            itemId = itemId,
            name = "曲目",
            mediaKind = DownloadMediaKind.MUSIC,
            status = task.status,
            sourceId = sourceId,
            task = task,
        )
    }

    @Test
    fun `全选目标按完整来源去重但跨 server 保留两项`() {
        val itemId = UUID(0L, 7L)
        val entries =
            listOf(
                entry(itemId, "source-7", "server-a"),
                entry(itemId, "source-7", "server-a"),
                entry(itemId, "source-7", "server-b"),
            )

        val candidates = DownloadDeletionRules.candidates(entries, mediaRecords = emptyList())

        assertEquals(2, candidates.size)
        assertEquals(setOf("server-a", "server-b"), candidates.map { it.request.serverId }.toSet())
    }
}
