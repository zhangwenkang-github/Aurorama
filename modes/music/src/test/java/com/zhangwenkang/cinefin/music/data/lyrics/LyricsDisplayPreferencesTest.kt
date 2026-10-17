package com.zhangwenkang.cinefin.music.data.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** W79 · #35 / #37：歌词字号 / 行距档位 + 双语对照三态偏好的纯函数口径。 */
class LyricsDisplayPreferencesTest {

    @Test
    fun `font scale defaults to standard and is neutral`() {
        assertEquals(LyricsFontScale.STANDARD, LyricsFontScale.DEFAULT)
        assertEquals(1f, LyricsFontScale.DEFAULT.multiplier, 0f)
        assertEquals(LyricsFontScale.STANDARD, LyricsFontScale.fromKey(null))
        assertEquals(LyricsFontScale.STANDARD, LyricsFontScale.fromKey("不存在"))
    }

    @Test
    fun `font scale multipliers ascend`() {
        val multipliers = LyricsFontScale.entries.map { it.multiplier }

        assertEquals(multipliers.sorted(), multipliers)
        assertTrue(LyricsFontScale.SMALL.multiplier < 1f)
        assertTrue(LyricsFontScale.EXTRA_LARGE.multiplier > 1f)
    }

    @Test
    fun `line spacing defaults to standard and cycles`() {
        assertEquals(LyricsLineSpacing.STANDARD, LyricsLineSpacing.fromKey(null))
        assertEquals(LyricsLineSpacing.STANDARD, LyricsLineSpacing.fromKey("nope"))
        assertEquals(LyricsLineSpacing.LOOSE, LyricsLineSpacing.STANDARD.next())
        assertEquals(LyricsLineSpacing.TIGHT.next(), LyricsLineSpacing.STANDARD)
        assertEquals(LyricsLineSpacing.STANDARD, LyricsLineSpacing.entries[1])
    }

    @Test
    fun `standard settings keep the natural line box of both surfaces`() {
        val settings = LyricsTextSettings()

        // 歌词页：当前行 32sp；面板：titleMedium 17sp —— 行高 = 字号 × 自然行高比例 1.33
        assertEquals(32f, settings.fontSp(32f), 0.001f)
        assertEquals(32f * 1.33f, settings.lineSp(32f), 0.001f)
        assertEquals(17f, settings.fontSp(17f), 0.001f)
        assertEquals(17f * 1.33f, settings.lineSp(17f), 0.001f)
    }

    @Test
    fun `font scale multiplies the base size and line height follows`() {
        val settings = LyricsTextSettings(fontScale = LyricsFontScale.EXTRA_LARGE)

        assertEquals(32f * 1.3f, settings.fontSp(32f), 0.001f)
        // 行高按同一倍数跟随 → 行高/字号比恒为自然比例，不会出现"特大"档行间重叠
        assertEquals(32f * 1.3f * 1.33f, settings.lineSp(32f), 0.001f)
        assertEquals(1.33f, settings.lineSp(32f) / settings.fontSp(32f), 0.001f)
    }

    @Test
    fun `line spacing scales line height only and stays above the natural box`() {
        val settings = LyricsTextSettings(lineSpacing = LyricsLineSpacing.LOOSE)

        assertEquals(32f, settings.fontSp(32f), 0.001f)
        assertEquals(32f * 1.33f * 1.25f, settings.lineSp(32f), 0.001f)
        // 比自然行盒更大：基准样式 lineHeight（如 28sp）低于自然行高时会被渲染层忽略，不能作为基准
        assertTrue(settings.lineSp(32f) > 32f * LyricsTextSettings.NATURAL_LINE_RATIO)
    }

    @Test
    fun `line spacing moves the gap between lyric rows`() {
        // 行块间距（每句歌词之间 / 主行与译文行之间）：单行文本框不吃 lineHeight，靠这个增量出效果
        assertEquals(16f, LyricsTextSettings().gapDp(16f), 0.001f)
        assertEquals(
            24f,
            LyricsTextSettings(lineSpacing = LyricsLineSpacing.LOOSE).gapDp(16f),
            0.001f,
        )
        assertEquals(
            12f,
            LyricsTextSettings(lineSpacing = LyricsLineSpacing.TIGHT).gapDp(16f),
            0.001f,
        )
        // 主行与译文行之间基准 0：紧凑档不会变成负间距
        assertEquals(
            0f,
            LyricsTextSettings(lineSpacing = LyricsLineSpacing.TIGHT).gapDp(0f),
            0.001f,
        )
        assertEquals(
            8f,
            LyricsTextSettings(lineSpacing = LyricsLineSpacing.LOOSE).gapDp(0f),
            0.001f,
        )
    }

    @Test
    fun `settings restore from stored keys`() {
        assertEquals(
            LyricsTextSettings(LyricsFontScale.LARGE, LyricsLineSpacing.TIGHT),
            LyricsTextSettings.fromKeys("large", "tight"),
        )
        assertEquals(LyricsTextSettings(), LyricsTextSettings.fromKeys(null, ""))
        assertEquals(
            LyricsTextSettings(),
            LyricsTextSettings()
                .withFontScale(LyricsFontScale.SMALL)
                .withLineSpacing(LyricsLineSpacing.STANDARD)
                .withFontScale(LyricsFontScale.STANDARD),
        )
    }

    @Test
    fun `bilingual defaults to enabled when never stored`() {
        assertTrue(LyricsBilingualPreference.DEFAULT_ENABLED)
        assertNull(LyricsBilingualPreference.stored(LyricsBilingualPreference.UNSET))
        assertNull(LyricsBilingualPreference.stored(null))
        assertNull(LyricsBilingualPreference.stored("yes"))
        assertTrue(LyricsBilingualPreference.resolve(LyricsBilingualPreference.UNSET))
        assertTrue(LyricsBilingualPreference.resolve(null))
    }

    @Test
    fun `bilingual keeps the explicit choice`() {
        val off = LyricsBilingualPreference.encode(false)
        val on = LyricsBilingualPreference.encode(true)

        assertEquals(LyricsBilingualPreference.OFF, off)
        assertEquals(LyricsBilingualPreference.ON, on)
        assertEquals(false, LyricsBilingualPreference.resolve(off))
        assertEquals(true, LyricsBilingualPreference.resolve(on))
        assertEquals(false, LyricsBilingualPreference.stored(off))
    }
}
