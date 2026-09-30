package com.zhangwenkang.cinefin.book.presentation.reader

import com.zhangwenkang.cinefin.core.presentation.theme.CinefinTokens
import com.zhangwenkang.cinefin.core.presentation.theme.MediaBook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.readium.r2.navigator.preferences.FontFamily
import org.readium.r2.navigator.preferences.TextAlign

class ReaderSettingsTest {

    @Test
    fun `阅读模式映射到滚动与双栏`() {
        val scroll = ReaderSettings(mode = ReaderMode.Scroll).toPreferenceSpec(systemDark = false)
        assertTrue(scroll.scroll)
        assertFalse(scroll.twoColumn)

        val paged = ReaderSettings(mode = ReaderMode.Paged).toPreferenceSpec(systemDark = false)
        assertFalse(paged.scroll)
        assertFalse(paged.twoColumn)

        val twoColumn =
            ReaderSettings(mode = ReaderMode.TwoColumn).toPreferenceSpec(systemDark = false)
        assertFalse(twoColumn.scroll)
        assertTrue(twoColumn.twoColumn)
    }

    @Test
    fun `阅读主题映射到明暗与底色`() {
        val paper = ReaderSettings(theme = ReaderTheme.Paper).toPreferenceSpec(systemDark = false)
        assertFalse(paper.darkTheme)
        assertEquals(CinefinTokens.PaperSurface, paper.backgroundColor)
        assertEquals(CinefinTokens.OnSurfaceLight, paper.textColor)

        val eyeCare =
            ReaderSettings(theme = ReaderTheme.EyeCare).toPreferenceSpec(systemDark = false)
        assertFalse(eyeCare.darkTheme)
        assertEquals(CinefinTokens.ReaderEyeCareSurface, eyeCare.backgroundColor)

        val dark = ReaderSettings(theme = ReaderTheme.Dark).toPreferenceSpec(systemDark = false)
        assertTrue(dark.darkTheme)
        assertEquals(CinefinTokens.SurfaceDark, dark.backgroundColor)
        assertEquals(CinefinTokens.OnSurfaceDark, dark.textColor)

        val oled = ReaderSettings(theme = ReaderTheme.Oled).toPreferenceSpec(systemDark = false)
        assertTrue(oled.darkTheme)
        assertEquals(CinefinTokens.ReaderOledSurface, oled.backgroundColor)
    }

    @Test
    fun `跟随主题按系统明暗解析`() {
        val darkSystem = ReaderSettings(theme = ReaderTheme.System).toPreferenceSpec(true)
        assertTrue(darkSystem.darkTheme)
        assertEquals(CinefinTokens.SurfaceDark, darkSystem.backgroundColor)

        val lightSystem = ReaderSettings(theme = ReaderTheme.System).toPreferenceSpec(false)
        assertFalse(lightSystem.darkTheme)
        assertEquals(CinefinTokens.PaperSurface, lightSystem.backgroundColor)
    }

    @Test
    fun `字体映射到 Readium 内置字体族`() {
        assertNull(
            ReaderSettings(font = ReaderFont.Publisher).toPreferenceSpec(false).font.fontFamily
        )
        assertEquals(
            FontFamily.SERIF,
            ReaderSettings(font = ReaderFont.Serif).toPreferenceSpec(false).font.fontFamily,
        )
        assertEquals(
            FontFamily.SANS_SERIF,
            ReaderSettings(font = ReaderFont.SansSerif).toPreferenceSpec(false).font.fontFamily,
        )
        assertEquals(
            FontFamily.MONOSPACE,
            ReaderSettings(font = ReaderFont.Monospace).toPreferenceSpec(false).font.fontFamily,
        )
    }

    @Test
    fun `对齐映射到 Readium 对齐枚举`() {
        assertEquals(
            TextAlign.JUSTIFY,
            ReaderSettings(textAlign = ReaderTextAlign.Justify)
                .toPreferenceSpec(false)
                .textAlign
                .readiumValue,
        )
        assertEquals(
            TextAlign.START,
            ReaderSettings(textAlign = ReaderTextAlign.Start)
                .toPreferenceSpec(false)
                .textAlign
                .readiumValue,
        )
        assertEquals(
            TextAlign.CENTER,
            ReaderSettings(textAlign = ReaderTextAlign.Center)
                .toPreferenceSpec(false)
                .textAlign
                .readiumValue,
        )
    }

    @Test
    fun `越界与非有限值被裁剪到可用区间`() {
        val sanitized =
            ReaderSettings(
                    fontSize = 9f,
                    lineHeight = 0.2f,
                    pageMargins = -3f,
                )
                .sanitized()
        assertEquals(2.5f, sanitized.fontSize)
        assertEquals(1.0f, sanitized.lineHeight)
        assertEquals(0.0f, sanitized.pageMargins)

        val nonFinite =
            ReaderSettings(fontSize = Float.NaN, lineHeight = Float.POSITIVE_INFINITY).sanitized()
        assertEquals(1.0f, nonFinite.fontSize)
        assertEquals(1.2f, nonFinite.lineHeight)

        val spec = ReaderSettings(fontSize = 9f).toPreferenceSpec(false)
        assertEquals(2.5, spec.fontSize, 0.0001)
    }

    @Test
    fun `持久化字符串解析非法值回退默认`() {
        assertEquals(ReaderMode.Scroll, ReaderMode.fromStorage(null))
        assertEquals(ReaderMode.TwoColumn, ReaderMode.fromStorage("two_column"))
        assertEquals(ReaderTheme.Dark, ReaderTheme.fromStorage("unknown"))
        assertEquals(ReaderTheme.Oled, ReaderTheme.fromStorage("oled"))
        assertEquals(ReaderFont.Publisher, ReaderFont.fromStorage(null))
        assertEquals(ReaderFont.Serif, ReaderFont.fromStorage("serif"))
        assertEquals(ReaderTextAlign.Justify, ReaderTextAlign.fromStorage("bogus"))
        assertEquals(ReaderTextAlign.Center, ReaderTextAlign.fromStorage("center"))
    }

    @Test
    fun `顶栏模式按钮循环切换`() {
        assertEquals(ReaderMode.Paged, ReaderMode.Scroll.next())
        assertEquals(ReaderMode.TwoColumn, ReaderMode.Paged.next())
        assertEquals(ReaderMode.Scroll, ReaderMode.TwoColumn.next())
    }

    @Test
    fun `强调色按主题家族切换`() {
        assertEquals(CinefinTokens.PaperAccent, ReaderTheme.Paper.accentColor(false))
        assertEquals(CinefinTokens.PaperAccent, ReaderTheme.EyeCare.accentColor(false))
        assertEquals(MediaBook.base, ReaderTheme.Dark.accentColor(false))
        assertEquals(MediaBook.base, ReaderTheme.Oled.accentColor(false))
        assertEquals(MediaBook.base, ReaderTheme.System.accentColor(true))
        assertEquals(CinefinTokens.PaperAccent, ReaderTheme.System.accentColor(false))
    }

    @Test
    fun `深色家族使用阅读面板底色`() {
        assertEquals(CinefinTokens.ReaderPanelDark, ReaderTheme.Dark.chromeColor(false))
        assertEquals(CinefinTokens.ReaderPanelDark, ReaderTheme.Oled.chromeColor(false))
        assertEquals(CinefinTokens.PaperSurface, ReaderTheme.Paper.chromeColor(false))
        assertEquals(CinefinTokens.ReaderEyeCareSurface, ReaderTheme.EyeCare.chromeColor(false))
    }
}
