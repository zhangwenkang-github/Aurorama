package com.zhangwenkang.cinefin.core.presentation.components

import org.junit.Assert.assertEquals
import org.junit.Test

/** W44：统一滑杆的数值 / 触摸换算纯函数单测（`CinefinSlider`）。 */
class CinefinSliderMathTest {

    private val range = 0f..100f
    private val widthPx = 200f
    private val thumbRadiusPx = 9f

    @Test
    fun fractionClampsToUnitRange() {
        assertEquals(0f, cinefinSliderFraction(-10f, range), 0.0001f)
        assertEquals(0.25f, cinefinSliderFraction(25f, range), 0.0001f)
        assertEquals(1f, cinefinSliderFraction(140f, range), 0.0001f)
    }

    @Test
    fun fractionHandlesDegenerateRange() {
        assertEquals(0f, cinefinSliderFraction(5f, 5f..5f), 0.0001f)
    }

    @Test
    fun valueAtThumbEdgesMapsToRangeBounds() {
        assertEquals(0f, cinefinSliderValueAt(9f, widthPx, thumbRadiusPx, range, 0), 0.0001f)
        assertEquals(100f, cinefinSliderValueAt(191f, widthPx, thumbRadiusPx, range, 0), 0.0001f)
        assertEquals(50f, cinefinSliderValueAt(100f, widthPx, thumbRadiusPx, range, 0), 0.0001f)
    }

    @Test
    fun valueAtClampsBeyondTrack() {
        assertEquals(0f, cinefinSliderValueAt(-40f, widthPx, thumbRadiusPx, range, 0), 0.0001f)
        assertEquals(100f, cinefinSliderValueAt(400f, widthPx, thumbRadiusPx, range, 0), 0.0001f)
    }

    @Test
    fun valueAtSnapsToSteps() {
        // steps = 3 → 0 / 25 / 50 / 75 / 100 四段五档。
        assertEquals(25f, cinefinSliderValueAt(60f, widthPx, thumbRadiusPx, range, 3), 0.0001f)
        assertEquals(75f, cinefinSliderValueAt(135f, widthPx, thumbRadiusPx, range, 3), 0.0001f)
        assertEquals(100f, cinefinSliderValueAt(190f, widthPx, thumbRadiusPx, range, 3), 0.0001f)
    }

    @Test
    fun valueAtSnapsReplayGainOverrideHalfDbSteps() {
        // 覆盖滑杆：-12..+12 dB、0.5 dB 步进（steps = 24 / 0.5 - 1 = 47）。
        val gainRange = -12f..12f
        val steps = 47
        assertEquals(
            -6f,
            cinefinSliderValueAt(54.5f, widthPx, thumbRadiusPx, gainRange, steps),
            0.0001f,
        )
        assertEquals(
            0f,
            cinefinSliderValueAt(100f, widthPx, thumbRadiusPx, gainRange, steps),
            0.0001f,
        )
        assertEquals(
            6f,
            cinefinSliderValueAt(145.5f, widthPx, thumbRadiusPx, gainRange, steps),
            0.0001f,
        )
        // 档位之间的位置吸附到最近的 0.5 dB。
        assertEquals(
            -5.5f,
            cinefinSliderValueAt(60f, widthPx, thumbRadiusPx, gainRange, steps),
            0.0001f,
        )
    }

    @Test
    fun valueAtHandlesTooNarrowTrack() {
        assertEquals(0f, cinefinSliderValueAt(10f, 12f, thumbRadiusPx, range, 0), 0.0001f)
    }
}
