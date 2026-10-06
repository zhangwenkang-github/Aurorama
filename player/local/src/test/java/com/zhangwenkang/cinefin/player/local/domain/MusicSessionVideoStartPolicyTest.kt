package com.zhangwenkang.cinefin.player.local.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * W76-B10：视频起播去粘住的状态迁移判定。
 *
 * 回归口径：粘住态（先播音乐 → 同进程进视频）必须清掉 [MusicSessionVideoStartAction]，否则视频被钉在音频实例上， 换内核 / 回退链 /
 * 错误卡片兜底全部失效；而音乐真在播时要走「停播 + 上报」这一档，不能只清标志。
 */
class MusicSessionVideoStartPolicyTest {

    @Test
    fun noMusicSessionNeedsNoHandoff() {
        assertEquals(
            MusicSessionVideoStartAction.NONE,
            musicSessionActionOnVideoStart(
                musicSessionActive = false,
                currentItemIsMusic = false,
            ),
        )
        assertEquals(
            "没有音乐会话时，即使当前条目是音乐（如视频页刚抢过实例）也不动它",
            MusicSessionVideoStartAction.NONE,
            musicSessionActionOnVideoStart(
                musicSessionActive = false,
                currentItemIsMusic = true,
            ),
        )
    }

    @Test
    fun playingMusicStopsAndClearsFlag() {
        assertEquals(
            MusicSessionVideoStartAction.STOP_MUSIC_AND_CLEAR_FLAG,
            musicSessionActionOnVideoStart(
                musicSessionActive = true,
                currentItemIsMusic = true,
            ),
        )
    }

    @Test
    fun staleFlagOnlyClearsFlag() {
        assertEquals(
            "标志粘住但实例上已不是音乐（视频已抢占 / 实例已释放）：只清标志，不重复停播",
            MusicSessionVideoStartAction.CLEAR_STALE_FLAG,
            musicSessionActionOnVideoStart(
                musicSessionActive = true,
                currentItemIsMusic = false,
            ),
        )
    }
}
