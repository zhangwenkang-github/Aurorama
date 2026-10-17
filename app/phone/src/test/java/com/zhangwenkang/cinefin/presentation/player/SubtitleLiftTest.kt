package com.zhangwenkang.cinefin.presentation.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 主/次字幕统一几何规则：测高、4dp 间隙、80% 降级、隐藏与 mpv 预留区。 */
class SubtitleLiftTest {

    @Test
    fun secondaryBlockHeight_addsTextHeightAndPadding() {
        assertEquals("文本高 + 内边距 = 块高", 70f, secondaryBlockHeightPx(40f, 30f), 0.001f)
        assertEquals("无内边距时即文本高", 40f, secondaryBlockHeightPx(40f, 0f), 0.001f)
    }

    @Test
    fun secondaryBlockHeight_clampsNegativeToZero() {
        assertEquals("负高度 clamp 到 0", 0f, secondaryBlockHeightPx(-10f, 5f), 0.001f)
        assertEquals("负内边距抵消后 clamp 到 0", 0f, secondaryBlockHeightPx(10f, -20f), 0.001f)
    }

    @Test
    fun plan_noPrimary_keepsSecondaryAtFullSize() {
        val layout =
            planDualSubtitleLayout(
                videoHeightPx = 1000f,
                bottomInsetPx = 100f,
                gapPx = 8f,
                primary = null,
                secondaryFullHeightPx = 120f,
                secondaryMinHeightPx = 96f,
            )

        assertTrue(layout.showSecondary)
        assertEquals(1f, layout.secondaryScale, 0.001f)
        assertEquals(120f, layout.secondaryHeightPx, 0.001f)
        assertEquals(0f, layout.primaryLiftPx, 0.001f)
    }

    @Test
    fun plan_libassPrimary_liftsSoGapIsExactAndKeepsFixedPositionAboveSecondary() {
        // 次字幕底边 900，高 120 → 顶边 780；主字幕底边 700 已在上方，不需要抬升。
        val alreadyAbove =
            planDualSubtitleLayout(
                videoHeightPx = 1000f,
                bottomInsetPx = 100f,
                gapPx = 8f,
                primary = PrimarySubtitleExtent.Libass(topPx = 650f, bottomPx = 700f),
                secondaryFullHeightPx = 120f,
                secondaryMinHeightPx = 96f,
            )
        assertTrue(alreadyAbove.showSecondary)
        assertEquals(0f, alreadyAbove.primaryLiftPx, 0.001f)

        // 固定 \pos 把主字幕放在底部 850..900，与次字幕底边对齐 → 需要抬到 772 以下。
        val fixedAtBottom =
            planDualSubtitleLayout(
                videoHeightPx = 1000f,
                bottomInsetPx = 100f,
                gapPx = 8f,
                primary = PrimarySubtitleExtent.Libass(topPx = 850f, bottomPx = 900f),
                secondaryFullHeightPx = 120f,
                secondaryMinHeightPx = 96f,
            )
        assertTrue(fixedAtBottom.showSecondary)
        assertEquals(128f, fixedAtBottom.primaryLiftPx, 0.001f)
    }

    @Test
    fun plan_triesEightyPercentBeforeHiding() {
        // 主字幕块 780，次字幕 100% 放不下，剩余空间只够 80% 的 96。
        val layout =
            planDualSubtitleLayout(
                videoHeightPx = 1000f,
                bottomInsetPx = 100f,
                gapPx = 8f,
                primary = PrimarySubtitleExtent.Native(heightPx = 780f),
                secondaryFullHeightPx = 120f,
                secondaryMinHeightPx = 96f,
            )

        assertTrue(layout.showSecondary)
        assertEquals(SECONDARY_MIN_SCALE, layout.secondaryScale, 0.001f)
        assertEquals(96f, layout.secondaryHeightPx, 0.001f)
        assertEquals((96f + 8f) / 1000f, layout.secondaryReservedFraction, 0.0001f)
    }

    @Test
    fun plan_hidesWhenEightyPercentStillDoesNotFit() {
        val layout =
            planDualSubtitleLayout(
                videoHeightPx = 1000f,
                bottomInsetPx = 100f,
                gapPx = 8f,
                primary = PrimarySubtitleExtent.Native(heightPx = 850f),
                secondaryFullHeightPx = 120f,
                secondaryMinHeightPx = 96f,
            )

        assertFalse(layout.showSecondary)
        assertEquals(0f, layout.secondaryReservedFraction, 0.001f)
    }

    @Test
    fun plan_hidesWhenLiftingPrimaryWouldClipItsTop() {
        val layout =
            planDualSubtitleLayout(
                videoHeightPx = 1000f,
                bottomInsetPx = 100f,
                gapPx = 8f,
                primary = PrimarySubtitleExtent.Libass(topPx = 50f, bottomPx = 900f),
                secondaryFullHeightPx = 120f,
                secondaryMinHeightPx = 96f,
            )

        assertFalse(layout.showSecondary)
        assertEquals(0f, layout.primaryLiftPx, 0.001f)
    }

    @Test
    fun plan_composedPrimaryUsesTotalHeightWithoutLifting() {
        val layout =
            planDualSubtitleLayout(
                videoHeightPx = 1000f,
                bottomInsetPx = 100f,
                gapPx = 8f,
                primary = PrimarySubtitleExtent.Composed(heightPx = 200f),
                secondaryFullHeightPx = 120f,
                secondaryMinHeightPx = 96f,
            )

        assertTrue(layout.showSecondary)
        assertEquals(0f, layout.primaryLiftPx, 0.001f)
        assertEquals(0f, layout.secondaryReservedFraction, 0.001f)
    }

    @Test
    fun plan_nativePrimaryReturnsReservedFractionForSubPos() {
        val layout =
            planDualSubtitleLayout(
                videoHeightPx = 1000f,
                bottomInsetPx = 100f,
                gapPx = 8f,
                primary = PrimarySubtitleExtent.Native(heightPx = 200f),
                secondaryFullHeightPx = 120f,
                secondaryMinHeightPx = 96f,
            )

        assertTrue(layout.showSecondary)
        assertEquals((120f + 8f) / 1000f, layout.secondaryReservedFraction, 0.0001f)
    }

    @Test
    fun plan_invalidVideoHeightHidesSecondary() {
        val layout =
            planDualSubtitleLayout(
                videoHeightPx = 0f,
                bottomInsetPx = 0f,
                gapPx = 8f,
                primary = null,
                secondaryFullHeightPx = 120f,
                secondaryMinHeightPx = 96f,
            )

        assertFalse(layout.showSecondary)
    }
}
