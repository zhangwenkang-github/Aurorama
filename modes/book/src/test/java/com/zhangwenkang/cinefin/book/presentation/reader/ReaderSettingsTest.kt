package com.zhangwenkang.cinefin.book.presentation.reader

import androidx.compose.ui.graphics.Color
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinTokens
import com.zhangwenkang.cinefin.core.presentation.theme.MediaBook
import com.zhangwenkang.cinefin.settings.domain.ReaderThemeCustomColors
import com.zhangwenkang.cinefin.settings.domain.ReaderThemeCustomJson
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
        assertEquals(ReaderInkLight, paper.textColor)

        val eyeCare =
            ReaderSettings(theme = ReaderTheme.EyeCare).toPreferenceSpec(systemDark = false)
        assertFalse(eyeCare.darkTheme)
        assertEquals(CinefinTokens.ReaderEyeCareSurface, eyeCare.backgroundColor)

        val dark = ReaderSettings(theme = ReaderTheme.Dark).toPreferenceSpec(systemDark = false)
        assertTrue(dark.darkTheme)
        assertEquals(CinefinTokens.SurfaceDark, dark.backgroundColor)
        assertEquals(ReaderInkDark, dark.textColor)

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

    @Test
    fun `右起开关不影响 EPUB 偏好快照`() {
        val baseline = ReaderSettings().toPreferenceSpec(systemDark = false)
        assertEquals(baseline, ReaderSettings(rtl = true).toPreferenceSpec(systemDark = false))
        assertTrue(ReaderSettings(rtl = true).rtl)
        assertFalse(ReaderSettings().rtl)
    }

    @Test
    fun `三分区手势设置默认值`() {
        val defaults = ReaderSettings()
        assertFalse(defaults.invertTapZones)
        assertTrue(defaults.tapPagingEnabled)
    }

    @Test
    fun `三分区手势设置不被 sanitize 重置`() {
        val sanitized =
            ReaderSettings(
                    invertTapZones = true,
                    tapPagingEnabled = false,
                    fontSize = 9f,
                )
                .sanitized()
        assertTrue(sanitized.invertTapZones)
        assertFalse(sanitized.tapPagingEnabled)
        assertEquals(2.5f, sanitized.fontSize)
    }

    @Test
    fun `三分区手势设置映射为点按策略`() {
        val defaults = ReaderSettings().tapPolicy(controlsVisible = false)
        assertEquals(ReaderTapPolicy(), defaults)
        assertEquals(
            ReaderTapPolicy(controlsVisible = false, invertZones = true, tapPagingEnabled = true),
            ReaderSettings(invertTapZones = true).tapPolicy(controlsVisible = false),
        )
        assertEquals(
            ReaderTapPolicy(controlsVisible = true, invertZones = false, tapPagingEnabled = false),
            ReaderSettings(tapPagingEnabled = false).tapPolicy(controlsVisible = true),
        )
    }

    @Test
    fun `三分区手势设置不影响 EPUB 偏好快照`() {
        val baseline = ReaderSettings().toPreferenceSpec(systemDark = false)
        assertEquals(
            baseline,
            ReaderSettings(invertTapZones = true, tapPagingEnabled = false)
                .toPreferenceSpec(systemDark = false),
        )
    }

    @Test
    fun `手势偏好键名带 pref_reader_前缀`() {
        assertEquals(
            "pref_reader_invert_tap_zones",
            ReaderGesturePreferences.invertTapZones.backendName,
        )
        assertEquals(
            "pref_reader_tap_paging",
            ReaderGesturePreferences.tapPagingEnabled.backendName,
        )
        assertFalse(ReaderGesturePreferences.invertTapZones.defaultValue)
        assertTrue(ReaderGesturePreferences.tapPagingEnabled.defaultValue)
    }

    // ---- W6 T5：排版扩展与主题自定义色 ----

    @Test
    fun `排版扩展默认值`() {
        val defaults = ReaderSettings()
        assertEquals(ReaderFontWeight.Normal, defaults.fontWeight)
        assertEquals(0f, defaults.paragraphSpacing)
        assertFalse(defaults.paragraphIndent)
        assertFalse(defaults.hyphens)
        assertTrue(defaults.themeCustom.isEmpty())
    }

    @Test
    fun `字重三档映射到 Readium 倍数`() {
        assertEquals(400, ReaderFontWeight.Normal.storageValue)
        assertEquals(500, ReaderFontWeight.Medium.storageValue)
        assertEquals(700, ReaderFontWeight.Bold.storageValue)
        assertEquals(1.0, ReaderFontWeight.Normal.readiumScale, 0.0001)
        assertEquals(1.25, ReaderFontWeight.Medium.readiumScale, 0.0001)
        assertEquals(1.75, ReaderFontWeight.Bold.readiumScale, 0.0001)
        assertEquals(ReaderFontWeight.Bold, ReaderFontWeight.fromStorage(700))
        assertEquals(ReaderFontWeight.Normal, ReaderFontWeight.fromStorage(null))
        assertEquals(ReaderFontWeight.Normal, ReaderFontWeight.fromStorage(600))
    }

    @Test
    fun `段间距裁剪到 0 到 2em 并映射到偏好快照`() {
        assertEquals(2f, ReaderSettings(paragraphSpacing = 9f).sanitized().paragraphSpacing)
        assertEquals(0f, ReaderSettings(paragraphSpacing = -1f).sanitized().paragraphSpacing)
        assertEquals(0f, ReaderSettings(paragraphSpacing = Float.NaN).sanitized().paragraphSpacing)
        assertEquals(
            0.8,
            ReaderSettings(paragraphSpacing = 0.8f).toPreferenceSpec(false).paragraphSpacing,
            0.0001,
        )
    }

    @Test
    fun `首行缩进开启映射为 2em`() {
        assertEquals(
            0.0,
            ReaderSettings(paragraphIndent = false).toPreferenceSpec(false).paragraphIndent,
            0.0001,
        )
        assertEquals(
            ReaderParagraphIndentEm,
            ReaderSettings(paragraphIndent = true).toPreferenceSpec(false).paragraphIndent,
            0.0001,
        )
    }

    @Test
    fun `连字符默认关闭且可开启`() {
        assertFalse(ReaderSettings().toPreferenceSpec(false).hyphens)
        assertTrue(ReaderSettings(hyphens = true).toPreferenceSpec(false).hyphens)
    }

    @Test
    fun `排版扩展的完整偏好快照`() {
        val expected =
            ReaderPreferenceSpec(
                scroll = false,
                twoColumn = false,
                darkTheme = true,
                backgroundColor = CinefinTokens.SurfaceDark,
                textColor = ReaderInkDark,
                font = ReaderFont.Serif,
                fontSize = 1.4f.toDouble(),
                lineHeight = 1.5f.toDouble(),
                pageMargins = 1.2f.toDouble(),
                textAlign = ReaderTextAlign.Start,
                fontWeight = 1.75,
                paragraphSpacing = 0.6f.toDouble(),
                paragraphIndent = 2.0,
                hyphens = true,
            )
        val actual =
            ReaderSettings(
                    mode = ReaderMode.Paged,
                    font = ReaderFont.Serif,
                    fontSize = 1.4f,
                    lineHeight = 1.5f,
                    pageMargins = 1.2f,
                    textAlign = ReaderTextAlign.Start,
                    theme = ReaderTheme.Dark,
                    fontWeight = ReaderFontWeight.Bold,
                    paragraphSpacing = 0.6f,
                    paragraphIndent = true,
                    hyphens = true,
                )
                .toPreferenceSpec(systemDark = false)
        assertEquals(expected, actual)
    }

    @Test
    fun `主题自定义色按主题独立生效且切主题不串`() {
        val paperCustom = ReaderThemeCustomColors(background = 0xFF112233.toInt())
        val custom = mapOf(ReaderTheme.Paper to paperCustom)
        val paper = ReaderSettings(theme = ReaderTheme.Paper, themeCustom = custom)
        assertEquals(Color(0xFF112233), paper.surfaceColor(systemDark = false))
        // 正文色未自定义 → 仍是主题墨色。
        assertEquals(ReaderInkLight, paper.contentColor(systemDark = false))

        val dark = ReaderSettings(theme = ReaderTheme.Dark, themeCustom = custom)
        assertEquals(CinefinTokens.SurfaceDark, dark.surfaceColor(systemDark = false))
        assertEquals(ReaderInkDark, dark.contentColor(systemDark = false))
    }

    @Test
    fun `跟随主题沿用解析后主题的自定义色`() {
        val custom =
            mapOf(
                ReaderTheme.Dark to
                    ReaderThemeCustomColors(
                        background = 0xFF101820.toInt(),
                        content = 0xFFE0E0E0.toInt(),
                    )
            )
        val follow = ReaderSettings(theme = ReaderTheme.System, themeCustom = custom)
        assertEquals(Color(0xFF101820), follow.surfaceColor(systemDark = true))
        assertEquals(Color(0xFFE0E0E0), follow.contentColor(systemDark = true))
        assertEquals(CinefinTokens.PaperSurface, follow.surfaceColor(systemDark = false))
    }

    @Test
    fun `自定义色写进 EPUB 偏好快照`() {
        val custom =
            mapOf(
                ReaderTheme.Paper to
                    ReaderThemeCustomColors(
                        background = 0xFFF0EAD8.toInt(),
                        content = 0xFF202020.toInt(),
                    )
            )
        val spec =
            ReaderSettings(theme = ReaderTheme.Paper, themeCustom = custom)
                .toPreferenceSpec(systemDark = false)
        assertEquals(Color(0xFFF0EAD8), spec.backgroundColor)
        assertEquals(Color(0xFF202020), spec.textColor)
        // 未自定义时仍走主题 token。
        assertEquals(
            CinefinTokens.PaperSurface,
            ReaderSettings(theme = ReaderTheme.Paper).toPreferenceSpec(false).backgroundColor,
        )
    }

    @Test
    fun `背景与正文色板各 6 档且由主题 token 派生`() {
        ReaderCustomizableThemes.forEach { theme ->
            val backgrounds = readerThemeBackgroundSwatches(theme, systemDark = false)
            val contents = readerThemeContentSwatches(theme, systemDark = false)
            assertEquals(6, backgrounds.size)
            assertEquals(6, contents.size)
            assertEquals(backgrounds.size, backgrounds.toSet().size)
            assertEquals(contents.size, contents.toSet().size)
            // 首档 = 主题默认。
            assertEquals(theme.resolve(false).surfaceColor(false), backgrounds.first())
            assertEquals(theme.resolve(false).contentColor(false), contents.first())
        }
    }

    @Test
    fun `自定义色偏好键前缀与主题隔离`() {
        assertEquals("pref_reader_theme_custom_paper", ReaderThemeCustomJson.prefName("paper"))
        val keys = ReaderCustomizableThemes.map { it.storageValue }.toSet()
        assertEquals(4, keys.size)
        assertEquals(setOf("paper", "eyecare", "dark", "oled"), keys)
    }

    @Test
    fun `排版偏好快照合并回设置模型`() {
        val state =
            ReaderTypographyState(
                fontWeight = ReaderFontWeight.Medium,
                paragraphSpacing = 0.4f,
                paragraphIndent = true,
                hyphens = true,
                themeCustom =
                    mapOf(
                        ReaderTheme.Oled to ReaderThemeCustomColors(background = 0xFF000000.toInt())
                    ),
            )
        val merged =
            ReaderSettings(theme = ReaderTheme.Oled, paragraphSpacing = 9f).withTypography(state)
        assertEquals(ReaderFontWeight.Medium, merged.fontWeight)
        // 合并时重跑 sanitize：越界的 9em 被收起为快照里的 0.4em。
        assertEquals(0.4f, merged.paragraphSpacing)
        assertTrue(merged.paragraphIndent)
        assertTrue(merged.hyphens)
        assertEquals(1, merged.themeCustom.size)
    }
}
