package com.zhangwenkang.cinefin.core.presentation.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 主题注册表与主题 id 迁移的纯 JVM 单测（K1 Aurora Cinema）。
 *
 * 钉值用例守住四套 token 集；对比度用例守住「品牌色块上的内容色可读」这条底线。
 */
class CinefinThemesTest {
    private val tolerance = 0.5f / 255f

    private fun assertHex(expectedHex: Long, actual: Color, label: String) {
        val red = ((expectedHex shr 16) and 0xFF).toInt() / 255f
        val green = ((expectedHex shr 8) and 0xFF).toInt() / 255f
        val blue = (expectedHex and 0xFF).toInt() / 255f
        assertEquals("$label · red", red, actual.red, tolerance)
        assertEquals("$label · green", green, actual.green, tolerance)
        assertEquals("$label · blue", blue, actual.blue, tolerance)
    }

    private fun relativeLuminance(color: Color): Double {
        fun channel(value: Float): Double {
            val c = value.toDouble()
            return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(color.red) +
            0.7152 * channel(color.green) +
            0.0722 * channel(color.blue)
    }

    private fun contrast(foreground: Color, background: Color): Double {
        val a = relativeLuminance(foreground)
        val b = relativeLuminance(background)
        val (lighter, darker) = if (a >= b) a to b else b to a
        return (lighter + 0.05) / (darker + 0.05)
    }

    @Test
    fun `registry exposes aurora light amoled and lumen palettes`() {
        listOf(
                CinefinThemes.AURORA,
                CinefinThemes.LIGHT,
                CinefinThemes.AMOLED,
                CinefinThemes.LUMEN,
            )
            .forEach { id ->
                val palette = CinefinThemes.map.getValue(id)
                assertTrue("$id.dark.surface 未定义", palette.dark.surface != Color.Unspecified)
                assertTrue("$id.light.surface 未定义", palette.light.surface != Color.Unspecified)
            }
        assertEquals(CinefinThemes.AURORA, CinefinThemes.DEFAULT)
    }

    @Test
    fun `unknown theme id falls back to aurora`() {
        assertEquals(
            CinefinThemes.colorsFor(CinefinThemes.AURORA, dark = true),
            CinefinThemes.colorsFor("no-such-theme", dark = true),
        )
    }

    @Test
    fun `dynamic theme resolves to the aurora palette`() {
        assertEquals(
            CinefinThemes.colorsFor(CinefinThemes.AURORA, dark = true),
            CinefinThemes.colorsFor(CinefinThemes.DYNAMIC, dark = true),
        )
        assertEquals(
            CinefinThemes.colorsFor(CinefinThemes.AURORA, dark = false),
            CinefinThemes.colorsFor(CinefinThemes.DYNAMIC, dark = false),
        )
    }

    @Test
    fun `migrateThemeId maps legacy and current values`() {
        assertEquals(CinefinThemes.AURORA, migrateThemeId(null))
        assertEquals(CinefinThemes.AURORA, migrateThemeId("dark"))
        assertEquals(CinefinThemes.LIGHT, migrateThemeId("light"))
        assertEquals(CinefinThemes.SYSTEM, migrateThemeId("system"))
        assertEquals(CinefinThemes.AURORA, migrateThemeId("aurora"))
        assertEquals(CinefinThemes.AMOLED, migrateThemeId("amoled"))
        assertEquals(CinefinThemes.DYNAMIC, migrateThemeId("dynamic"))
        assertEquals(CinefinThemes.AURORA, migrateThemeId("lumen"))
        assertEquals(CinefinThemes.AURORA, migrateThemeId("something-else"))
        assertEquals(CinefinThemes.AURORA, migrateThemeId(""))
    }

    @Test
    fun `brand colors keep readable contrast on every registered palette`() {
        CinefinThemes.map.forEach { (id, palette) ->
            listOf("dark" to palette.dark, "light" to palette.light).forEach { (mode, colors) ->
                listOf(colors.aurora1, colors.aurora2, colors.aurora3).forEachIndexed { index, brand
                    ->
                    val ratio = contrast(colors.onAurora, brand)
                    assertTrue(
                        "$id.$mode onAurora 对 aurora${index + 1} 对比度 $ratio < 4.5",
                        ratio >= 4.5,
                    )
                }
            }
        }
    }
}
