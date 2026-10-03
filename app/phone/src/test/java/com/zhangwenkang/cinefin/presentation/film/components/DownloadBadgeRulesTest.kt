package com.zhangwenkang.cinefin.presentation.film.components

import com.zhangwenkang.cinefin.film.presentation.detail.DetailDownloadState
import com.zhangwenkang.cinefin.utils.DownloadTaskStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** W60b 下载状态徽标判定 + 错位规则（纯函数）单测。 */
class DownloadBadgeRulesTest {

    @Test
    fun `running and pending map to progress ring`() {
        val running =
            downloadBadgeInfo(
                downloaded = false,
                taskStatus = DownloadTaskStatus.RUNNING,
                progress = 0.42f,
                totalKnown = true,
            )
        assertEquals(DownloadBadgeState.IN_PROGRESS, running.state)
        assertEquals(0.42f, running.progress, 0.0001f)
        assertFalse(running.indeterminate)

        val pending =
            downloadBadgeInfo(
                downloaded = false,
                taskStatus = DownloadTaskStatus.PENDING,
                progress = 0f,
                totalKnown = false,
            )
        assertEquals(DownloadBadgeState.IN_PROGRESS, pending.state)
        assertTrue(pending.indeterminate)
    }

    @Test
    fun `paused and failed map to their own states`() {
        assertEquals(
            DownloadBadgeState.PAUSED,
            downloadBadgeInfo(false, DownloadTaskStatus.PAUSED).state,
        )
        assertEquals(
            DownloadBadgeState.FAILED,
            downloadBadgeInfo(false, DownloadTaskStatus.FAILED).state,
        )
        assertEquals(
            DownloadBadgeState.NONE,
            downloadBadgeInfo(false, taskStatus = null).state,
        )
        assertEquals(
            DownloadBadgeState.NONE,
            downloadBadgeInfo(false, DownloadTaskStatus.COMPLETED).state,
        )
    }

    @Test
    fun `downloaded wins over failed task`() {
        // 完整文件在盘上（或旧任务残留）时展示完成角标，不被失败任务抢状态。
        assertEquals(
            DownloadBadgeState.DOWNLOADED,
            downloadBadgeInfo(
                    downloaded = true,
                    taskStatus = DownloadTaskStatus.FAILED,
                )
                .state,
        )
    }

    @Test
    fun `progress is clamped`() {
        assertEquals(
            1f,
            downloadBadgeInfo(
                    downloaded = false,
                    taskStatus = DownloadTaskStatus.RUNNING,
                    progress = 1.4f,
                )
                .progress,
            0.0001f,
        )
        assertEquals(
            0f,
            downloadBadgeInfo(
                    downloaded = false,
                    taskStatus = DownloadTaskStatus.RUNNING,
                    progress = -0.2f,
                )
                .progress,
            0.0001f,
        )
    }

    @Test
    fun `corner moves to bottom end when top end is occupied`() {
        assertEquals(BadgeCorner.TOP_END, downloadBadgeCorner(hasTopEndBadge = false))
        assertEquals(BadgeCorner.BOTTOM_END, downloadBadgeCorner(hasTopEndBadge = true))
    }

    @Test
    fun `detail download state maps to poster badge`() {
        assertEquals(
            DownloadBadgeState.NONE,
            downloadBadgeInfo(DetailDownloadState.NOT_DOWNLOADED).state,
        )
        val queued = downloadBadgeInfo(DetailDownloadState.IN_QUEUE)
        assertEquals(DownloadBadgeState.IN_PROGRESS, queued.state)
        assertTrue(queued.indeterminate)
        assertEquals(
            DownloadBadgeState.DOWNLOADED,
            downloadBadgeInfo(DetailDownloadState.DOWNLOADED).state,
        )
    }
}
