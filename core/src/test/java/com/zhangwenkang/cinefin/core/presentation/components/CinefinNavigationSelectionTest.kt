package com.zhangwenkang.cinefin.core.presentation.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinColors
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinThemes
import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 导航选中态取色（K1 / W3-T2）的纯 JVM 单测。
 *
 * 守住两条：① 三形态容器共用同一套选中色派生（深色 `aurora1`、浅色 #0A7A66）；② 浅色主题的选中图文在页面底与选中 pill 上仍然 可读（pill 底 =
 * `navSelectedFill` 合成到容器底之后的实际像素）。
 */
class CinefinNavigationSelectionTest {
    private val light = CinefinThemes.colorsFor(CinefinThemes.LIGHT, dark = false)
    private val auroraDark = CinefinThemes.colorsFor(CinefinThemes.AURORA, dark = true)
    private val amoledDark = CinefinThemes.colorsFor(CinefinThemes.AMOLED, dark = true)

    private fun assertHex(expectedHex: Long, actual: Color, label: String) {
        val tolerance = 0.5f / 255f
        assertEquals(
            "$label · red",
            ((expectedHex shr 16) and 0xFF).toInt() / 255f,
            actual.red,
            tolerance,
        )
        assertEquals(
            "$label · green",
            ((expectedHex shr 8) and 0xFF).toInt() / 255f,
            actual.green,
            tolerance,
        )
        assertEquals("$label · blue", (expectedHex and 0xFF).toInt() / 255f, actual.blue, tolerance)
    }

    private fun channel(value: Float): Double {
        val c = value.toDouble()
        return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }

    private fun relativeLuminance(color: Color): Double =
        0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)

    private fun contrast(foreground: Color, background: Color): Double {
        val a = relativeLuminance(foreground)
        val b = relativeLuminance(background)
        val (lighter, darker) = if (a >= b) a to b else b to a
        return (lighter + 0.05) / (darker + 0.05)
    }

    /** 选中 pill 在某个容器底上的实际像素：`navSelectedFill` 带 alpha 合成上去。 */
    private fun pillOver(colors: CinefinColors, container: Color): Color =
        navSelectedFill(colors).compositeOver(container)

    @Test
    fun `selected content follows the fill lightness rule`() {
        assertHex(0xFF0A6B5A, navSelectedContent(light), "light selected content")
        assertNotEquals(
            "浅色主题不能再直接用 aurora1（3.2:1）",
            light.aurora1,
            navSelectedContent(light),
        )
        assertEquals(
            "aurora.dark 选中图文保持 aurora1",
            auroraDark.aurora1,
            navSelectedContent(auroraDark),
        )
        assertEquals(
            "amoled.dark 选中图文保持 aurora1",
            amoledDark.aurora1,
            navSelectedContent(amoledDark),
        )
    }

    @Test
    fun `light theme keeps the fill and content rules on the same switch`() {
        // 同一判据：浅色底 → 16% 填充 + 加深图文；深色底 → 12% 填充 + aurora1 图文。
        // 容差 0.01：Compose 的 Color 把 alpha 存成 8 bit（0.12 → 31/255、0.16 → 41/255）。
        assertEquals(0.16f, navSelectedFill(light).alpha, 0.01f)
        assertEquals(0.12f, navSelectedFill(auroraDark).alpha, 0.01f)
        assertEquals(0.12f, navSelectedFill(amoledDark).alpha, 0.01f)
        assertTrue("浅色底判定失败", light.surface.luminance() > 0.5f)
        assertTrue("深色底判定失败", auroraDark.surface.luminance() <= 0.5f)
    }

    @Test
    fun `light selected content stays readable on page and pill backgrounds`() {
        val content = navSelectedContent(light)
        val page = contrast(content, light.surface)
        assertTrue("浅色选中图文对页面底 $page < 4.5", page >= 4.5)

        // pill 底取真实合成值：侧轨画在页面底上，抽屉画在面板容器（bg2）上。
        val overPage = contrast(content, pillOver(light, light.surface))
        val overDrawer = contrast(content, pillOver(light, light.surfaceContainer))
        // #0A6B5A 对三类底全部过 WCAG AA 4.5（页面 6.17 / 侧轨 pill 5.16 / 抽屉 pill 4.82，2026-11-19 设计裁决）。
        assertTrue("浅色选中图文对侧轨 pill 底 $overPage < 4.5", overPage >= 4.5)
        assertTrue("浅色选中图文对抽屉 pill 底 $overDrawer < 4.5", overDrawer >= 4.5)
    }
}
