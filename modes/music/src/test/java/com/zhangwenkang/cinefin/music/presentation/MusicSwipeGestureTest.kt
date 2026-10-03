package com.zhangwenkang.cinefin.music.presentation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * W44：全屏播放页 / 歌词页滑动手势判定纯函数单测。
 *
 * 映射（用户 2026-10-03 确认）：全屏左滑 → 歌词页；歌词页右滑 → 返回全屏；下滑关闭 / 返回；未达阈值不动作。
 */
class MusicSwipeGestureTest {

    private val horizontal = 96f
    private val vertical = 120f

    @Test
    fun leftSwipeBeyondThresholdIsLeft() {
        assertEquals(
            SwipeDirection.Left,
            swipeGestureDirection(totalX = -140f, totalY = 10f, horizontal, vertical),
        )
    }

    @Test
    fun rightSwipeBeyondThresholdIsRight() {
        assertEquals(
            SwipeDirection.Right,
            swipeGestureDirection(totalX = 140f, totalY = -8f, horizontal, vertical),
        )
    }

    @Test
    fun downSwipeBeyondThresholdIsDown() {
        assertEquals(
            SwipeDirection.Down,
            swipeGestureDirection(totalX = 12f, totalY = 180f, horizontal, vertical),
        )
    }

    @Test
    fun belowThresholdsReturnsNull() {
        assertNull(swipeGestureDirection(totalX = -60f, totalY = 4f, horizontal, vertical))
        assertNull(swipeGestureDirection(totalX = 8f, totalY = 90f, horizontal, vertical))
        assertNull(swipeGestureDirection(totalX = 0f, totalY = 0f, horizontal, vertical))
    }

    @Test
    fun dominantAxisWins() {
        // 横向位移占优时，纵向即使超过自身阈值也不算下滑。
        assertEquals(
            SwipeDirection.Left,
            swipeGestureDirection(totalX = -230f, totalY = 200f, horizontal, vertical),
        )
        // 纵向占优但未达纵向阈值时，不误判为横滑。
        assertNull(swipeGestureDirection(totalX = -80f, totalY = 100f, horizontal, vertical))
    }
}
