package com.zhangwenkang.cinefin.music.presentation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
        val viewportStart = -500
        val viewportEnd = 500
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
                viewportStart = viewportStart,
                viewportEnd = viewportEnd,
                itemOffset = itemOffset,
                itemSize = heights[target],
            )

        // 按首见行 offset 与中间各行真实高度独立算出目标坐标，再核对实际滚动距离。
        val expectedTargetOffset = -30 + (140 + 8) + (180 + 8) + (220 + 8)
        assertEquals(expectedTargetOffset, itemOffset)
        assertEquals(664f, delta, 0.001f)

        // animateScrollBy(delta) 会把目标中心移到视口中心。
        val itemCenter = itemOffset + heights[target] / 2f
        val viewportCenter = (viewportStart + viewportEnd) / 2f
        assertEquals(viewportCenter, itemCenter - delta, 0.001f)
        assertTrue(delta > 0f)
    }

    @Test
    fun `line click clears manual pause and skips centering the old active row`() {
        val outcome =
            lyricsLineClickFollowOutcome(
                followPausedByUser = true,
                displayFollow = true,
                clickedIndex = 7,
                activeIndex = 3,
            )

        assertFalse(outcome.followPausedByUser)
        assertEquals(7, outcome.suppressCenteringForIndex)
    }

    @Test
    fun `line click keeps follow disabled when user turned it off`() {
        val outcome =
            lyricsLineClickFollowOutcome(
                followPausedByUser = true,
                displayFollow = false,
                clickedIndex = 7,
                activeIndex = 3,
            )

        assertFalse(outcome.followPausedByUser)
        assertNull(outcome.suppressCenteringForIndex)
    }

    @Test
    fun `line click on the active row resumes follow without suppression`() {
        val outcome =
            lyricsLineClickFollowOutcome(
                followPausedByUser = true,
                displayFollow = true,
                clickedIndex = 3,
                activeIndex = 3,
            )

        assertFalse(outcome.followPausedByUser)
        assertNull(outcome.suppressCenteringForIndex)
    }

    @Test
    fun `re-entering lyrics clears manual pause and requests recenter`() {
        val before = LyricsFollowUiState(manualPause = true, restoreToken = 4L)

        val after = before.onPageEntered(displayFollow = true)

        assertFalse(after.manualPause)
        assertEquals(5L, after.restoreToken)
    }

    @Test
    fun `re-entering lyrics respects a globally disabled follow setting`() {
        val before = LyricsFollowUiState(manualPause = true, restoreToken = 4L)

        val after = before.onPageEntered(displayFollow = false)

        assertFalse(after.manualPause)
        assertEquals(4L, after.restoreToken)
    }
}
