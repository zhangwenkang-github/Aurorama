package com.zhangwenkang.cinefin.music.presentation

import org.junit.Assert.assertEquals
import org.junit.Test

/** W56：迷你播放条「关闭面板」落点（停播 / 只丢展示快照 / 无操作）。 */
class MusicMiniBarRulesTest {

    @Test
    fun liveSessionStopsPlayback() {
        assertEquals(
            MusicMiniBarDismissTarget.StopPlayback,
            musicMiniBarDismissTarget(hasLiveQueue = true, hasRestoredQueue = false),
        )
    }

    @Test
    fun restoredOnlyDropsInMemorySnapshot() {
        assertEquals(
            MusicMiniBarDismissTarget.DropRestoredQueue,
            musicMiniBarDismissTarget(hasLiveQueue = false, hasRestoredQueue = true),
        )
    }

    @Test
    fun noQueueDoesNothing() {
        assertEquals(
            MusicMiniBarDismissTarget.None,
            musicMiniBarDismissTarget(hasLiveQueue = false, hasRestoredQueue = false),
        )
    }

    // ---- W66b：迷你条时间文本（时间永不截断，状态可省略）----

    @Test
    fun timeTextShowsPositionOverDuration() {
        assertEquals("2:31 / 4:56", musicMiniBarTimeText(currentMs = 151_000L, totalMs = 296_000L))
        assertEquals("0:00 / 3:00", musicMiniBarTimeText(currentMs = 0L, totalMs = 180_000L))
    }

    @Test
    fun timeTextFallsBackWhenDurationUnknown() {
        assertEquals("1:05 / --:--", musicMiniBarTimeText(currentMs = 65_000L, totalMs = 0L))
    }
}
