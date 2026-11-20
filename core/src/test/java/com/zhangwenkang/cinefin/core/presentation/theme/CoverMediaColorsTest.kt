package com.zhangwenkang.cinefin.core.presentation.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** MUSIC-17 封面调色板纯函数单测（W5-T2 定稿 §3）：取色优先级、纯色回落、3:1 护栏与派生槽位。 */
class CoverMediaColorsTest {
    private val dark = CinefinThemes.colorsFor(CinefinThemes.AURORA, dark = true)
    private val light = CinefinThemes.colorsFor(CinefinThemes.LIGHT, dark = false)
    private val tolerance = 1f / 255f

    private fun assertColorEquals(expected: Color, actual: Color, label: String) {
        assertEquals("$label · red", expected.red, actual.red, tolerance)
        assertEquals("$label · green", expected.green, actual.green, tolerance)
        assertEquals("$label · blue", expected.blue, actual.blue, tolerance)
        assertEquals("$label · alpha", expected.alpha, actual.alpha, tolerance)
    }

    @Test
    fun `vibrant wins over muted and dominant`() {
        val vibrant = Color(0xFFCC3366)
        val colors =
            mediaColorsFromCover(
                vibrant = vibrant,
                muted = Color(0xFF886644),
                dominant = Color(0xFF445566),
                colors = dark,
            )
        assertNotNull(colors)
        assertColorEquals(readableCoverBase(vibrant, dark.surface), colors!!.base, "base")
    }

    @Test
    fun `muted used when vibrant missing`() {
        val muted = Color(0xFF3366CC)
        val colors =
            mediaColorsFromCover(
                vibrant = null,
                muted = muted,
                dominant = Color(0xFF665544),
                colors = dark,
            )
        assertNotNull(colors)
        assertColorEquals(readableCoverBase(muted, dark.surface), colors!!.base, "base")
    }

    @Test
    fun `dominant used when vibrant and muted missing`() {
        val dominant = Color(0xFF669933)
        val colors =
            mediaColorsFromCover(
                vibrant = null,
                muted = null,
                dominant = dominant,
                colors = dark,
            )
        assertNotNull(colors)
        assertColorEquals(readableCoverBase(dominant, dark.surface), colors!!.base, "base")
    }

    @Test
    fun `no cover color falls back to domain color`() {
        assertNull(mediaColorsFromCover(null, null, null, dark))
    }

    @Test
    fun `plain cover falls back even when saturated candidates are missing`() {
        // 饱和度 0 的灰：纯色封面。HSL 饱和度 = (max-min)/(max+min) = 0。
        assertNull(mediaColorsFromCover(Color(0xFF808080), null, null, dark))
        assertEquals(0f, Color(0xFF808080).toHsl().saturation, 0.001f)
    }

    @Test
    fun `plain threshold uses hsl saturation of the picked color`() {
        // 极低饱和的近灰（约 0.05）< 0.08 → 回落；稍带色相（约 0.12）→ 保留。
        assertNull(mediaColorsFromCover(Color(0xFF7F8080), null, null, dark))
        assertNotNull(mediaColorsFromCover(Color(0xFF7F9F80), null, null, dark))
    }

    @Test
    fun `derived colors keep base and compose container slots over surfaceContainer`() {
        val base = Color(0xFF3FC9A0)
        val colors = mediaColorsForCoverColor(base, dark)
        assertColorEquals(base, colors.base, "base")
        assertColorEquals(
            colors.containerOver(dark.surfaceContainer),
            colors.container,
            "container",
        )
        assertColorEquals(
            colors.containerPressedOver(dark.surfaceContainer),
            colors.containerPressed,
            "containerPressed",
        )
        assertColorEquals(colors.outlineOver(dark.surfaceContainer), colors.outline, "outline")
    }

    @Test
    fun `bright is lighter and dim is darker than base`() {
        val colors = mediaColorsForCoverColor(Color(0xFF3FC9A0), dark)
        assertTrue("bright 明度应更高", colors.bright.toHsl().lightness > colors.base.toHsl().lightness)
        assertTrue("dim 明度应更低", colors.dim.toHsl().lightness < colors.base.toHsl().lightness)
    }

    @Test
    fun `onBase is the higher-contrast of black and white`() {
        val colors = mediaColorsForCoverColor(Color(0xFF3FC9A0), dark)
        val white = contrastRatio(Color.White, colors.base)
        val black = contrastRatio(Color.Black, colors.base)
        assertEquals(colors.onBase == Color.White, white >= black)
    }

    @Test
    fun `readable base leaves a contrasting color untouched`() {
        val base = Color(0xFF7FE0C0)
        assertTrue(contrastRatio(base, dark.surface) >= CoverMediaColors.MinBaseContrast)
        assertColorEquals(base, readableCoverBase(base, dark.surface), "base")
    }

    @Test
    fun `readable base lightens low-contrast colors by eight percent steps`() {
        // 暗色 surface 上贴近底色的深色封面：对比度 <3:1，需要按 +8% 明度提亮。
        val base = Color(0xFF2A2233)
        assertTrue(contrastRatio(base, dark.surface) < CoverMediaColors.MinBaseContrast)
        val lifted = readableCoverBase(base, dark.surface)
        assertTrue(
            "提亮后明度应至少 +8%",
            lifted.toHsl().lightness >=
                base.toHsl().lightness + CoverMediaColors.BaseLightnessStep - 0.001f,
        )
        assertTrue(
            "最多提亮 3 步",
            lifted.toHsl().lightness <=
                base.toHsl().lightness +
                    CoverMediaColors.BaseLightnessStep * CoverMediaColors.BaseMaxSteps +
                    0.001f,
        )
        assertTrue(
            "提亮不改变饱和度",
            abs(lifted.toHsl().saturation - base.toHsl().saturation) <= 0.01f,
        )
        assertTrue("提亮不改变色相", abs(lifted.toHsl().hue - base.toHsl().hue) <= 3f)
    }

    @Test
    fun `readable base either reaches three-to-one or stops at the three-step cap`() {
        val surface = light.surface
        listOf(0xFFB0B0C0, 0xFF9090A0, 0xFF6A6A9A, 0xFFC0A0A0).forEach { argb ->
            val base = Color(argb)
            if (contrastRatio(base, surface) >= CoverMediaColors.MinBaseContrast) return@forEach
            val lifted = readableCoverBase(base, surface)
            val reached = contrastRatio(lifted, surface) >= CoverMediaColors.MinBaseContrast
            val capped =
                lifted.toHsl().lightness >=
                    base.toHsl().lightness +
                        CoverMediaColors.BaseLightnessStep * CoverMediaColors.BaseMaxSteps - 0.005f
            assertTrue("$argb 既未达 3:1 也未走满 3 步", reached || capped)
        }
    }

    @Test
    fun `readable base gives up after three steps`() {
        val black = Color.Black
        val lifted = readableCoverBase(black, Color.Black)
        assertTrue(lifted.toHsl().lightness >= 0.24f - 0.005f)
        assertTrue(lifted.toHsl().lightness <= 0.24f + 0.02f)
    }
}
