package com.zhangwenkang.cinefin.presentation.theme

import com.zhangwenkang.cinefin.core.presentation.theme.CinefinThemes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W5-T1 返修：App 主题解析纯函数单测（音乐域跟随全局四变体，`system` 按系统明暗解析）。
 *
 * 规则与 `MainActivity` 抽取前一致：light 固定浅色；system / dynamic 跟系统明暗；其余固定深色。
 */
class AppThemeResolutionTest {

    @Test
    fun amoledKeepsIdAndDark() {
        val theme = resolveAppThemePresentation(CinefinThemes.AMOLED, systemDark = false, false)
        assertEquals(CinefinThemes.AMOLED, theme.themeId)
        assertTrue(theme.darkTheme)
        assertFalse(theme.dynamicColor)
    }

    @Test
    fun auroraKeepsIdAndDark() {
        val theme = resolveAppThemePresentation(CinefinThemes.AURORA, systemDark = false, false)
        assertEquals(CinefinThemes.AURORA, theme.themeId)
        assertTrue(theme.darkTheme)
    }

    @Test
    fun lightForcesLightAppearance() {
        val theme = resolveAppThemePresentation(CinefinThemes.LIGHT, systemDark = true, false)
        assertEquals(CinefinThemes.LIGHT, theme.themeId)
        assertFalse(theme.darkTheme)
    }

    @Test
    fun systemResolvesToAuroraWhenSystemDark() {
        val theme = resolveAppThemePresentation(CinefinThemes.SYSTEM, systemDark = true, false)
        assertEquals(CinefinThemes.AURORA, theme.themeId)
        assertTrue(theme.darkTheme)
    }

    @Test
    fun systemResolvesToLightWhenSystemLight() {
        val theme = resolveAppThemePresentation(CinefinThemes.SYSTEM, systemDark = false, false)
        assertEquals(CinefinThemes.LIGHT, theme.themeId)
        assertFalse(theme.darkTheme)
    }

    @Test
    fun dynamicKeepsIdAndFollowsSystemAppearance() {
        val dark = resolveAppThemePresentation(CinefinThemes.DYNAMIC, systemDark = true, false)
        assertEquals(CinefinThemes.DYNAMIC, dark.themeId)
        assertTrue(dark.darkTheme)
        assertTrue(dark.dynamicColor)

        val light = resolveAppThemePresentation(CinefinThemes.DYNAMIC, systemDark = false, false)
        assertEquals(CinefinThemes.DYNAMIC, light.themeId)
        assertFalse(light.darkTheme)
        assertTrue(light.dynamicColor)
    }

    @Test
    fun dynamicColorsPreferenceEnablesDynamicColorButKeepsPaletteId() {
        val theme = resolveAppThemePresentation(CinefinThemes.AURORA, systemDark = true, true)
        assertEquals(CinefinThemes.AURORA, theme.themeId)
        assertTrue(theme.dynamicColor)
    }

    @Test
    fun unknownOrMissingThemeFallsBackToDefaultDark() {
        val unknown = resolveAppThemePresentation("nope", systemDark = false, false)
        assertEquals("nope", unknown.themeId)
        assertTrue(unknown.darkTheme)

        val missing = resolveAppThemePresentation(null, systemDark = false, false)
        assertEquals(CinefinThemes.DEFAULT, missing.themeId)
        assertTrue(missing.darkTheme)
    }
}
