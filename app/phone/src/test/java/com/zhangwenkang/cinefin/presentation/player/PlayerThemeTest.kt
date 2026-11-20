package com.zhangwenkang.cinefin.presentation.player

import com.zhangwenkang.cinefin.core.presentation.theme.CinefinThemes
import org.junit.Assert.assertEquals
import org.junit.Test

/** W4-L3：播放页色板映射——amoled 直通，其余主题（light/dynamic/system/未知）一律 aurora 深色。 */
class PlayerThemeTest {

    @Test
    fun amoled_passesThrough() {
        assertEquals(CinefinThemes.AMOLED, resolvePlayerThemeId(CinefinThemes.AMOLED))
    }

    @Test
    fun aurora_staysAurora() {
        assertEquals(CinefinThemes.AURORA, resolvePlayerThemeId(CinefinThemes.AURORA))
    }

    @Test
    fun lightFallsBackToAuroraDark() {
        assertEquals(CinefinThemes.AURORA, resolvePlayerThemeId(CinefinThemes.LIGHT))
    }

    @Test
    fun dynamicFallsBackToAuroraDark() {
        assertEquals(CinefinThemes.AURORA, resolvePlayerThemeId(CinefinThemes.DYNAMIC))
    }

    @Test
    fun systemAndUnknownFallBackToAuroraDark() {
        assertEquals(CinefinThemes.AURORA, resolvePlayerThemeId(CinefinThemes.SYSTEM))
        assertEquals(CinefinThemes.AURORA, resolvePlayerThemeId(CinefinThemes.LUMEN))
        assertEquals(CinefinThemes.AURORA, resolvePlayerThemeId(null))
    }
}
