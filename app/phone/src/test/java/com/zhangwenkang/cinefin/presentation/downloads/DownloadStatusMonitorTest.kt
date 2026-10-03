package com.zhangwenkang.cinefin.presentation.downloads

import com.zhangwenkang.cinefin.presentation.film.components.DownloadBadgeState
import com.zhangwenkang.cinefin.utils.DownloadTask
import com.zhangwenkang.cinefin.utils.DownloadTaskStatus
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** W60b 下载角标快照（任务 + 已下载集合 → itemId → 徽标）纯函数单测。 */
class DownloadStatusMonitorTest {

    @Test
    fun `highest priority state wins for duplicated tasks`() {
        val itemId = UUID.randomUUID()
        val map =
            badgeMapFor(
                tasks =
                    listOf(
                        task(itemId, DownloadTaskStatus.FAILED),
                        task(itemId, DownloadTaskStatus.RUNNING, downloadedBytes = 50, total = 100),
                    ),
                downloaded = emptySet(),
            )

        val badge = map.getValue(itemId)
        assertEquals(DownloadBadgeState.IN_PROGRESS, badge.state)
        assertEquals(0.5f, badge.progress, 0.0001f)
        assertFalse(badge.indeterminate)
    }

    @Test
    fun `downloaded items without tasks still get a badge`() {
        val itemId = UUID.randomUUID()
        val map = badgeMapFor(tasks = emptyList(), downloaded = setOf(itemId))
        assertEquals(DownloadBadgeState.DOWNLOADED, map.getValue(itemId).state)
    }

    @Test
    fun `downloaded beats running task for the same item`() {
        val itemId = UUID.randomUUID()
        val map =
            badgeMapFor(
                tasks = listOf(task(itemId, DownloadTaskStatus.RUNNING)),
                downloaded = setOf(itemId),
            )
        assertEquals(DownloadBadgeState.DOWNLOADED, map.getValue(itemId).state)
    }

    @Test
    fun `paused task maps to paused badge`() {
        val itemId = UUID.randomUUID()
        val map =
            badgeMapFor(
                tasks = listOf(task(itemId, DownloadTaskStatus.PAUSED)),
                downloaded = emptySet(),
            )
        assertEquals(DownloadBadgeState.PAUSED, map.getValue(itemId).state)
    }

    private fun task(
        itemId: UUID,
        status: DownloadTaskStatus,
        downloadedBytes: Long = 0L,
        total: Long = 0L,
    ) =
        DownloadTask(
            itemId = itemId,
            sourceId = "source-${itemId}",
            name = "测试条目",
            path = "/tmp/test.download",
            downloadId = null,
            status = status,
            failureReason = null,
            downloadedBytes = downloadedBytes,
            totalBytes = total,
            updatedAt = 0L,
        )
}
