package com.zhangwenkang.cinefin.presentation.player

import com.zhangwenkang.cinefin.player.local.subtitle.AssSubtitleScript
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 主/次字幕统一几何规则：可见边界、4dp 间隙、80% 降级、隐藏与 mpv 原生兜底。
 *
 * 数值按手机（1080px 高 · 2.75x）与平板（1440px 高 · 2.0x）的典型视频矩形取， 字号沿用覆盖层公式 `画面高 × 4.2% × 档位 × 0.9`，行高 1.25。
 */
class SubtitleLiftTest {

    private val phoneDensity = 2.75f
    private val phoneHeight = 1080f
    private val tabletDensity = 2f
    private val tabletHeight = 1440f
    private val bottomFraction = 0.08f
    private val strokeDp = 2f

    private fun gapPx(density: Float): Float = SUBTITLE_LINE_GAP_DP * density

    /** 与 SubtitleLine 的可见边界同口径：外层 3dp + （无背景时）内层 4dp + 描边 1 份。 */
    private fun insetPx(density: Float, backgroundVisible: Boolean = false): Float =
        subtitleVisibleTopInsetPx(
            outerPaddingPx = SECONDARY_LINE_OUTER_PADDING_DP * density,
            innerPaddingPx = SECONDARY_LINE_INNER_PADDING_DP * density,
            strokePx = strokeDp * density,
            backgroundVisible = backgroundVisible,
        )

    /** 次字幕块高：文本行高 + 固定 14dp 垂直 padding（描边不占 Compose 布局高度）。 */
    private fun secondaryBlockPx(
        density: Float,
        videoHeightPx: Float,
        textScale: Float,
        lines: Int,
        secondaryScale: Float = 1f,
    ): Float {
        val fontSizePx =
            videoHeightPx * AssSubtitleScript.BASE_FONT_FRACTION * textScale * 0.9f * secondaryScale
        val textHeightPx = lines * fontSizePx * 1.25f
        return secondaryBlockHeightPx(
            textLayoutHeightPx = textHeightPx,
            verticalPaddingPx = SECONDARY_LINE_VERTICAL_PADDING_DP * density,
        )
    }

    private fun secondaryVisibleTopPx(
        videoHeightPx: Float,
        bottomInsetPx: Float,
        layout: DualSubtitleLayout,
        insetPx: Float,
    ): Float = videoHeightPx - bottomInsetPx - layout.secondaryHeightPx + insetPx

    private fun layout(
        videoHeightPx: Float,
        density: Float,
        textScale: Float,
        lines: Int,
        primary: PrimarySubtitleExtent?,
        backgroundVisible: Boolean = false,
    ): DualSubtitleLayout {
        val inset = insetPx(density, backgroundVisible)
        return planDualSubtitleLayout(
            videoHeightPx = videoHeightPx,
            bottomInsetPx = videoHeightPx * bottomFraction,
            gapPx = gapPx(density),
            primary = primary,
            secondaryFullHeightPx = secondaryBlockPx(density, videoHeightPx, textScale, lines),
            secondaryMinHeightPx =
                secondaryBlockPx(
                    density,
                    videoHeightPx,
                    textScale,
                    lines,
                    secondaryScale = SECONDARY_MIN_SCALE,
                ),
            secondaryVisibleTopInsetPx = inset,
            secondaryVisibleBottomInsetPx = inset,
        )
    }

    private fun assertVisibleGap(
        videoHeightPx: Float,
        density: Float,
        layout: DualSubtitleLayout,
        primaryBottomBeforeLiftPx: Float,
        backgroundVisible: Boolean = false,
    ) {
        val inset = insetPx(density, backgroundVisible)
        val secondaryTop =
            secondaryVisibleTopPx(videoHeightPx, videoHeightPx * bottomFraction, layout, inset)
        val primaryBottom = primaryBottomBeforeLiftPx - layout.primaryLiftPx
        // 间隙 = 次字幕可见顶边 − 主字幕可见底边；允许半个 dp 的取整误差。
        assertEquals(
            "可见间隙必须是 4dp",
            gapPx(density),
            secondaryTop - primaryBottom,
            0.5f * density,
        )
    }

    @Test
    fun visibleInset_excludesTransparentPaddingAndStroke() {
        assertEquals(
            "无背景：外层 3 + 内层 4 − 描边外扩 2 = 5dp",
            5f,
            subtitleVisibleTopInsetPx(3f, 4f, 2f, backgroundVisible = false),
            0.001f,
        )
        assertEquals(
            "有背景：背景框就是可见边界，只扣外层 3",
            3f,
            subtitleVisibleTopInsetPx(3f, 4f, 2f, backgroundVisible = true),
            0.001f,
        )
        assertEquals(
            "无描边时只扣 padding",
            7f,
            subtitleVisibleTopInsetPx(3f, 4f, 0f, backgroundVisible = false),
            0.001f,
        )
        assertEquals(
            "非法值按 0 处理",
            0f,
            subtitleVisibleTopInsetPx(Float.NaN, -4f, Float.POSITIVE_INFINITY, true),
            0.001f,
        )
    }

    @Test
    fun visibleHeight_removesBothVisibleInsets() {
        assertEquals(64f, subtitleVisibleHeightPx(100f, 18f), 0.001f)
        assertEquals("内缩超过块高时 clamp 到 0", 0f, subtitleVisibleHeightPx(10f, 18f), 0.001f)
    }

    @Test
    fun secondaryBlockHeight_addsTextHeightAndPadding() {
        assertEquals(70f, secondaryBlockHeightPx(40f, 30f), 0.001f)
        assertEquals(0f, secondaryBlockHeightPx(-10f, 5f), 0.001f)
    }

    @Test
    fun phone_twoLinesAt200Percent_fixedPosPrimary_gapIsFourDpAndTopNotClipped() {
        // 手机横屏 1080p 视频区；固定 \pos 把主字幕压在底部 850..1000。
        val result =
            layout(
                videoHeightPx = phoneHeight,
                density = phoneDensity,
                textScale = 2f,
                lines = 2,
                primary = PrimarySubtitleExtent.Libass(topPx = 850f, bottomPx = 1000f),
            )

        assertTrue(result.showSecondary)
        assertEquals(1f, result.secondaryScale, 0.001f)
        assertTrue("主字幕必须被上移", result.primaryLiftPx > 0f)
        assertTrue("主字幕顶边不得越过画面顶部", 850f - result.primaryLiftPx >= 0f)
        assertVisibleGap(phoneHeight, phoneDensity, result, primaryBottomBeforeLiftPx = 1000f)
    }

    @Test
    fun tablet_singleLineAt100Percent_gapIsFourDpNotBlockPadding() {
        val result =
            layout(
                videoHeightPx = tabletHeight,
                density = tabletDensity,
                textScale = 1f,
                lines = 1,
                primary = PrimarySubtitleExtent.Libass(topPx = 1200f, bottomPx = 1300f),
            )

        assertTrue(result.showSecondary)
        assertVisibleGap(tabletHeight, tabletDensity, result, primaryBottomBeforeLiftPx = 1300f)

        // 回归证据：旧实现把「块顶」当可见边界，平板上的实际间距 = 4dp + 7dp 透明留白 + 2dp 描边 = 13dp。
        val oldBlockBasedGapDp = SUBTITLE_LINE_GAP_DP + 7f + strokeDp
        assertEquals(13f, oldBlockBasedGapDp, 0.001f)
    }

    @Test
    fun tablet_twoLinesAt200Percent_gapIsFourDp() {
        val result =
            layout(
                videoHeightPx = tabletHeight,
                density = tabletDensity,
                textScale = 2f,
                lines = 2,
                primary = PrimarySubtitleExtent.Libass(topPx = 900f, bottomPx = 1250f),
            )

        assertTrue(result.showSecondary)
        assertTrue(900f - result.primaryLiftPx >= 0f)
        assertVisibleGap(tabletHeight, tabletDensity, result, primaryBottomBeforeLiftPx = 1250f)
    }

    @Test
    fun gapIsFourDpWhenBackgroundMakesBackgroundBoxVisible() {
        val result =
            layout(
                videoHeightPx = tabletHeight,
                density = tabletDensity,
                textScale = 1f,
                lines = 1,
                primary = PrimarySubtitleExtent.Libass(topPx = 1200f, bottomPx = 1300f),
                backgroundVisible = true,
            )

        assertTrue(result.showSecondary)
        assertVisibleGap(
            tabletHeight,
            tabletDensity,
            result,
            primaryBottomBeforeLiftPx = 1300f,
            backgroundVisible = true,
        )
    }

    @Test
    fun primaryAlreadyAboveSecondary_keepsAtLeastGapWithoutPullingItDown() {
        val result =
            layout(
                videoHeightPx = phoneHeight,
                density = phoneDensity,
                textScale = 1f,
                lines = 1,
                primary = PrimarySubtitleExtent.Libass(topPx = 600f, bottomPx = 700f),
            )

        assertTrue(result.showSecondary)
        assertEquals("已在安全位置的主字幕不强行下移", 0f, result.primaryLiftPx, 0.001f)
        val inset = insetPx(phoneDensity)
        val secondaryTop =
            secondaryVisibleTopPx(phoneHeight, phoneHeight * bottomFraction, result, inset)
        assertTrue("可见间隙至少 4dp", secondaryTop - 700f >= gapPx(phoneDensity))
    }

    @Test
    fun phone_twoLinesAt200Percent_shrinksToEightyPercentBeforeHiding() {
        // 主字幕可见底边 1000、高度 780：100% 上移后顶边为负，80% 才放得下。
        val result =
            layout(
                videoHeightPx = phoneHeight,
                density = phoneDensity,
                textScale = 2f,
                lines = 2,
                primary = PrimarySubtitleExtent.Libass(topPx = 220f, bottomPx = 1000f),
            )

        assertTrue(result.showSecondary)
        assertEquals(SECONDARY_MIN_SCALE, result.secondaryScale, 0.001f)
        assertTrue("80% 时主字幕顶边不得越界", 220f - result.primaryLiftPx >= 0f)
        assertVisibleGap(phoneHeight, phoneDensity, result, primaryBottomBeforeLiftPx = 1000f)
    }

    @Test
    fun hidesWhenEightyPercentStillDoesNotFit() {
        val result =
            layout(
                videoHeightPx = phoneHeight,
                density = phoneDensity,
                textScale = 2f,
                lines = 2,
                primary = PrimarySubtitleExtent.Libass(topPx = 100f, bottomPx = 1000f),
            )

        assertFalse(result.showSecondary)
        assertEquals(0f, result.primaryLiftPx, 0.001f)
    }

    @Test
    fun composedFallback_offsetsPrimarySoVisibleGapIsFourDp() {
        val result =
            layout(
                videoHeightPx = phoneHeight,
                density = phoneDensity,
                textScale = 1f,
                lines = 1,
                primary =
                    PrimarySubtitleExtent.Composed(
                        heightPx = 120f,
                        visibleBottomInsetPx = insetPx(phoneDensity),
                    ),
            )

        assertTrue(result.showSecondary)
        assertTrue("Compose 兜底向下压透明留白", result.primaryOffsetPx > 0f)
        assertEquals(
            "下移量正好抵掉两块不可见留白",
            2f * insetPx(phoneDensity) - gapPx(phoneDensity),
            result.primaryOffsetPx,
            0.001f,
        )
    }

    @Test
    fun nativeFallback_reservesFromVisibleTopAndKeepsSubPosFraction() {
        val result =
            layout(
                videoHeightPx = phoneHeight,
                density = phoneDensity,
                textScale = 1f,
                lines = 1,
                primary = PrimarySubtitleExtent.Native(heightPx = 300f),
            )

        assertTrue(result.showSecondary)
        val inset = insetPx(phoneDensity)
        val expected = (result.secondaryHeightPx - inset + gapPx(phoneDensity)) / phoneHeight
        assertEquals(expected, result.secondaryReservedFraction, 0.0001f)
    }

    @Test
    fun noSecondary_keepsPrimaryUntouched() {
        val result =
            planDualSubtitleLayout(
                videoHeightPx = phoneHeight,
                bottomInsetPx = phoneHeight * bottomFraction,
                gapPx = gapPx(phoneDensity),
                primary = PrimarySubtitleExtent.Libass(topPx = 800f, bottomPx = 900f),
                secondaryFullHeightPx = 0f,
                secondaryMinHeightPx = 0f,
                secondaryVisibleTopInsetPx = insetPx(phoneDensity),
                secondaryVisibleBottomInsetPx = insetPx(phoneDensity),
            )

        assertFalse(result.showSecondary)
        assertEquals(0f, result.primaryLiftPx, 0.001f)
    }

    @Test
    fun invalidVideoHeightHidesSecondary() {
        val result =
            planDualSubtitleLayout(
                videoHeightPx = 0f,
                bottomInsetPx = 0f,
                gapPx = 8f,
                primary = null,
                secondaryFullHeightPx = 120f,
                secondaryMinHeightPx = 96f,
                secondaryVisibleTopInsetPx = 18f,
                secondaryVisibleBottomInsetPx = 18f,
            )

        assertFalse(result.showSecondary)
    }
}
