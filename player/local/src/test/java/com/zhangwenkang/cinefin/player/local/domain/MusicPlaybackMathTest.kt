package com.zhangwenkang.cinefin.player.local.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 音乐播放纯计算函数的边界用例（W1 R2）。 */
class MusicPlaybackMathTest {

    @Test
    fun `起始索引越界时回落到合法范围`() {
        assertEquals(0, normalizeStartIndex(-3, 5))
        assertEquals(2, normalizeStartIndex(2, 5))
        assertEquals(4, normalizeStartIndex(99, 5))
        assertEquals(0, normalizeStartIndex(3, 0))
        // W23-MUSIC 真机回归：从 100 首随机队列切到 1 首时，旧索引 84 曾直接喂给 setMediaItems
        // → IllegalSeekPositionException（起播失败），归一化后固定回落到 0
        assertEquals(0, normalizeStartIndex(84, 1))
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

    @Test
    fun `进度上报按 10 秒节流`() {
        assertFalse(isProgressReportDue(9_999L, 0L))
        assertTrue(isProgressReportDue(10_000L, 0L))
        assertFalse(isProgressReportDue(19_000L, 10_000L))
        assertTrue(isProgressReportDue(20_000L, 10_000L))
    }

    @Test
    fun `曲目切换时停止上报的百分比与位置一致`() {
        // 曲目播到一半切歌：Stop 上报 50%
        assertEquals(50, playbackPercentage(120_000L, 240_000L))
        assertEquals(1_200_000_000L, playbackPositionTicks(120_000L))
        // 队列播完：Stop 上报 100%
        assertEquals(100, playbackPercentage(240_000L, 240_000L))
    }
}
