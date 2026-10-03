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

    @Test
    fun snappedValueRoundsToNearestStep() {
        // steps = 3 → 0 / 25 / 50 / 75 / 100
        assertEquals(25f, cinefinSliderSnappedValue(30f, range, 3), 0.0001f)
        assertEquals(50f, cinefinSliderSnappedValue(60f, range, 3), 0.0001f)
        assertEquals(75f, cinefinSliderSnappedValue(70f, range, 3), 0.0001f)
        assertEquals(0f, cinefinSliderSnappedValue(-5f, range, 3), 0.0001f)
        assertEquals(100f, cinefinSliderSnappedValue(999f, range, 3), 0.0001f)
        // steps = 0：连续滑杆只夹紧、不吸附
        assertEquals(33.3f, cinefinSliderSnappedValue(33.3f, range, 0), 0.0001f)
    }

    @Test
    fun steppedValueMovesOneStepAndClampsAtEnds() {
        // 无档位：键盘按区间 1% 调整（M3 同口径）
        assertEquals(1f, cinefinSliderSteppedValue(0f, range, 0, increase = true), 0.0001f)
        assertEquals(99f, cinefinSliderSteppedValue(100f, range, 0, increase = false), 0.0001f)
        // steps = 3 → 每步 25
        assertEquals(75f, cinefinSliderSteppedValue(50f, range, 3, increase = true), 0.0001f)
        assertEquals(0f, cinefinSliderSteppedValue(0f, range, 3, increase = false), 0.0001f)
        // ReplayGain 覆盖：-12..12 dB、0.5 dB 步进（steps = 47）
        assertEquals(
            0.5f,
            cinefinSliderSteppedValue(0f, -12f..12f, 47, increase = true),
            0.0001f,
        )
        assertEquals(
            -0.5f,
            cinefinSliderSteppedValue(0f, -12f..12f, 47, increase = false),
            0.0001f,
        )
        // 退化区间：返回区间起点，不抛异常
        assertEquals(5f, cinefinSliderSteppedValue(5f, 5f..5f, 0, increase = true), 0.0001f)
    }

    @Test
    fun stepSizeFollowsStepsOrOnePercent() {
        assertEquals(25f, cinefinSliderStepSize(range, 3), 0.0001f)
        assertEquals(0.5f, cinefinSliderStepSize(-12f..12f, 47), 0.0001f)
        assertEquals(1f, cinefinSliderStepSize(range, 0), 0.0001f)
        assertEquals(0f, cinefinSliderStepSize(5f..5f, 3), 0.0001f)
    }

    @Test
    fun pagedValueJumpsTenPercentOfIntervals() {
        // steps = 3 → 4 段 → page = clamp(0, 1, 10) = 1 → 跳 25
        assertEquals(100f, cinefinSliderPagedValue(75f, range, 3, increase = true), 0.0001f)
        assertEquals(50f, cinefinSliderPagedValue(75f, range, 3, increase = false), 0.0001f)
        // 无档位：100 段 → page = 10 → 跳 10% 区间
        assertEquals(10f, cinefinSliderPagedValue(0f, range, 0, increase = true), 0.0001f)
        assertEquals(90f, cinefinSliderPagedValue(100f, range, 0, increase = false), 0.0001f)
    }
}
