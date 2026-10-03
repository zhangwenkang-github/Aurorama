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
}
