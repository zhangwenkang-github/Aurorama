package com.zhangwenkang.cinefin.music.presentation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** W79-QA-D1：歌词行居中微调必须始终使用 LazyList 的内容坐标系。 */
class LyricsCenterScrollTest {

    @Test
    fun `computes delta with no content padding`() {
        val delta =
            lyricsCenterScrollDelta(
                viewportStart = 0,
                viewportEnd = 1_000,
                itemOffset = 700,
                itemSize = 100,
            )

        assertEquals(250f, delta, 0.001f)
    }

    @Test
    fun `computes delta when half viewport padding shifts the viewport origin`() {
        val delta =
            lyricsCenterScrollDelta(
                viewportStart = -500,
                viewportEnd = 500,
                itemOffset = 0,
                itemSize = 100,
            )

        assertEquals(50f, delta, 0.001f)
    }

    @Test
    fun `already centered item needs no adjustment`() {
        val delta =
            lyricsCenterScrollDelta(
                viewportStart = -500,
                viewportEnd = 500,
                itemOffset = -50,
                itemSize = 100,
            )

        assertEquals(0f, delta, 0.001f)
    }

    @Test
    fun `target below accumulates real row heights instead of one average`() {
        val heights = intArrayOf(100, 40, 160)

        val offset =
            lyricsTargetItemOffset(
                firstVisibleIndex = 1,
                firstVisibleOffset = -50,
                targetIndex = 3,
                itemSpacingPx = 8,
                heightOf = { index -> heights[index] },
            )

        assertEquals(-50 + (40 + 8) + (160 + 8), offset)
    }

    @Test
    fun `target above walks backwards with real row heights`() {
        val heights = intArrayOf(100, 40, 160)

        val offset =
            lyricsTargetItemOffset(
                firstVisibleIndex = 2,
                firstVisibleOffset = 20,
                targetIndex = 0,
                itemSpacingPx = 8,
                heightOf = { index -> heights[index] },
            )

        assertEquals(20 - (40 + 8) - (100 + 8), offset)
    }

    @Test
    fun `measured target height centers a far row exactly`() {
        val heights = intArrayOf(100, 140, 180, 220, 260)
        val target = 4
        val viewportCenter = 0f
        val itemOffset =
            lyricsTargetItemOffset(
                firstVisibleIndex = 1,
                firstVisibleOffset = -30,
                targetIndex = target,
                itemSpacingPx = 8,
                heightOf = { index -> heights[index] },
            )

        val delta =
            lyricsCenterScrollDelta(
                viewportStart = -500,
                viewportEnd = 500,
                itemOffset = itemOffset,
                itemSize = heights[target],
            )

        // 动画结束后的目标中心 = 当前中心 + delta，必须正好落在视口中心。
        val itemCenter = itemOffset + heights[target] / 2f
        assertEquals(0f, itemCenter - (viewportCenter + delta), 0.001f)
        assertTrue(delta > 0f)
    }
}
