package com.zhangwenkang.cinefin.player.local.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/** 音乐播放纯计算函数的边界用例（W1 R2）。 */
class MusicPlaybackMathTest {

    @Test
    fun `起始索引越界时回落到合法范围`() {
        assertEquals(0, normalizeStartIndex(-3, 5))
        assertEquals(2, normalizeStartIndex(2, 5))
        assertEquals(4, normalizeStartIndex(99, 5))
        assertEquals(0, normalizeStartIndex(3, 0))
    }

    @Test
    fun `播放百分比按 0 到 100 截断`() {
        assertEquals(0, playbackPercentage(0L, 0L))
        assertEquals(0, playbackPercentage(-5L, 10_000L))
        assertEquals(50, playbackPercentage(50_000L, 100_000L))
        assertEquals(100, playbackPercentage(120_000L, 100_000L))
    }

    @Test
    fun `毫秒换算为 Jellyfin 位置 ticks`() {
        assertEquals(0L, playbackPositionTicks(0L))
        assertEquals(10_000_000L, playbackPositionTicks(1_000L))
        assertEquals(0L, playbackPositionTicks(-100L))
    }
}
