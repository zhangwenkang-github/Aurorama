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
    fun `themeSelectorIds only exposes dynamic from Android 12`() {
        listOf(28, 29, 30).forEach { sdk ->
            assertEquals(
                listOf(CinefinThemes.AURORA, CinefinThemes.LIGHT, CinefinThemes.AMOLED),
                themeSelectorIds(sdk),
            )
        }
        listOf(31, 35).forEach { sdk ->
            assertEquals(
                listOf(
                    CinefinThemes.AURORA,
                    CinefinThemes.LIGHT,
                    CinefinThemes.AMOLED,
                    CinefinThemes.DYNAMIC,
                ),
                themeSelectorIds(sdk),
            )
        }
    }

    @Test
    fun `resolveThemeSelectorSelection maps every stored branch`() {
        assertEquals(CinefinThemes.AURORA, resolveThemeSelectorSelection("dark", false))
        assertEquals(CinefinThemes.AURORA, resolveThemeSelectorSelection("system", false))
        assertEquals(CinefinThemes.DYNAMIC, resolveThemeSelectorSelection("system", true))
        assertEquals(CinefinThemes.AURORA, resolveThemeSelectorSelection("aurora", false))
        assertEquals(CinefinThemes.LIGHT, resolveThemeSelectorSelection("light", false))
        assertEquals(CinefinThemes.AMOLED, resolveThemeSelectorSelection("amoled", false))
        assertEquals(CinefinThemes.DYNAMIC, resolveThemeSelectorSelection("dynamic", false))
        assertEquals(CinefinThemes.AURORA, resolveThemeSelectorSelection(null, false))
        assertEquals(CinefinThemes.AURORA, resolveThemeSelectorSelection(null, true))
        assertEquals(CinefinThemes.AURORA, resolveThemeSelectorSelection("unknown", false))
    }

    @Test
    fun `registered palettes pin the K1 token values`() {
        val auroraDark = CinefinThemes.colorsFor(CinefinThemes.AURORA, dark = true)
        assertHex(0xFF080D17, auroraDark.surface, "aurora.dark surface")
        assertHex(0xFF3DE8C2, auroraDark.aurora1, "aurora.dark aurora1")
        assertHex(0xFF04201A, auroraDark.onAurora, "aurora.dark onAurora")
        assertHex(0xFF3DE8C2, auroraDark.auroraInk, "aurora.dark auroraInk")

        val light = CinefinThemes.colorsFor(CinefinThemes.LIGHT, dark = false)
        assertHex(0xFFFAFAFB, light.surface, "light surface")
        assertHex(0xFF0C9E82, light.aurora1, "light aurora1")
        assertHex(0xFF000000, light.onAurora, "light onAurora")
        assertHex(0xFF0A6B5A, light.auroraInk, "light auroraInk")

        val amoled = CinefinThemes.colorsFor(CinefinThemes.AMOLED, dark = true)
        assertHex(0xFF000000, amoled.surface, "amoled.dark surface")
        assertHex(0xFF0A0F18, amoled.surfaceContainer, "amoled.dark surfaceContainer")
        assertHex(0xFF3DE8C2, amoled.auroraInk, "amoled.dark auroraInk")

        val lumen = CinefinThemes.colorsFor(CinefinThemes.LUMEN, dark = true)
        assertHex(0xFF08090C, lumen.surface, "lumen.dark surface")
        assertHex(0xFF5CE1D2, lumen.auroraInk, "lumen.dark auroraInk")
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

    @Test
    fun `semantic status colors keep readable content contrast`() {
        CinefinThemes.map.forEach { (id, palette) ->
            listOf("dark" to palette.dark, "light" to palette.light).forEach { (mode, colors) ->
                listOf(
                        "warning" to (colors.warning to colors.onWarning),
                        "success" to (colors.success to colors.onSuccess),
                        "info" to (colors.info to colors.onInfo),
                    )
                    .forEach { (name, pair) ->
                        val ratio = contrast(pair.second, pair.first)
                        assertTrue("$id.$mode $name 对比度 $ratio < 4.5", ratio >= 4.5)
                    }
            }
        }
    }

    @Test
    fun `material color scheme keeps content contrast on brand and neutral roles`() {
        CinefinThemes.map.forEach { (id, palette) ->
            listOf("dark" to (palette.dark to true), "light" to (palette.light to false)).forEach {
                (mode, variant) ->
                val (colors, dark) = variant
                val scheme = cinefinColorScheme(colors, dark)
                listOf(
                        "primary" to (scheme.primary to scheme.onPrimary),
                        "primaryContainer" to
                            (scheme.primaryContainer to scheme.onPrimaryContainer),
                        "secondary" to (scheme.secondary to scheme.onSecondary),
                        "secondaryContainer" to
                            (scheme.secondaryContainer to scheme.onSecondaryContainer),
                        "tertiary" to (scheme.tertiary to scheme.onTertiary),
                    )
                    .forEach { (name, pair) ->
                        val ratio = contrast(pair.second, pair.first)
                        assertTrue("$id.$mode $name 对比度 $ratio < 4.5", ratio >= 4.5)
                    }
            }
        }
    }
}
