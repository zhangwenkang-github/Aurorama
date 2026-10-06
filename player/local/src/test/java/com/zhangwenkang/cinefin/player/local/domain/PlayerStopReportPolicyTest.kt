package com.zhangwenkang.cinefin.player.local.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W76-Q5：退出播放页（`onCleared` → `releasePlayer`）时的停止上报归属。
 *
 * 回归口径：`currentItemIsMusic` 取反写错会直接回归成「视频页 attach 音乐会话后退出，用音乐条目补发一条 `Sessions/Playing/Stopped`」（B10
 * 遗留③）；反过来漏报视频停止会让退出后的进度落不回服务端。
 */
class PlayerStopReportPolicyTest {

    @Test
    fun videoItemReportsStopOnExit() {
        assertTrue(
            "本页自己启动的视频会话（当前条目 = 视频）退出时要正常补发停止上报",
            shouldReportStopOnPlayerExit(currentItemIsMusic = false),
        )
    }

    @Test
    fun musicItemDoesNotReportStopOnExit() {
        assertFalse(
            "attach 到音乐会话（当前条目 = 音乐）退出时不得补发 Stopped：音乐条目归音乐侧管理",
            shouldReportStopOnPlayerExit(currentItemIsMusic = true),
        )
    }
}
