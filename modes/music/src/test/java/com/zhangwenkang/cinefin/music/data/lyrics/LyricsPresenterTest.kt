package com.zhangwenkang.cinefin.music.data.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LyricsPresenterTest {

    private val bilingual =
        LyricsDocumentBuilder.build(
            listOf(
                LyricLine(28_440, "夜に駆ける"),
                LyricLine(28_440, "奔向黑夜"),
                LyricLine(40_000, "brave shine"),
                LyricLine(40_000, "勇敢的光芒"),
            ),
            LyricsSource.SERVER,
        )

    @Test
    fun `defaults to simplified chinese when translation exists`() {
        val display = LyricsPresenter.defaultDisplay(bilingual)

        assertEquals(LyricsDisplayLanguage.SIMPLIFIED_CHINESE, display.language)
        assertEquals(false, display.bilingual)
        assertEquals(true, display.follow)
    }

    @Test
    fun `falls back to original when no chinese exists`() {
        val japaneseOnly =
            LyricsDocumentBuilder.build(listOf(LyricLine(1_000, "夜に駆ける")), LyricsSource.SERVER)

        assertEquals(
            LyricsDisplayLanguage.ORIGINAL,
            LyricsPresenter.defaultDisplay(japaneseOnly).language,
        )
        assertEquals(
            listOf(LyricsDisplayLanguage.JAPANESE, LyricsDisplayLanguage.ORIGINAL),
            LyricsPresenter.displayLanguages(japaneseOnly),
        )
    }

    @Test
    fun `lists languages by priority with original last`() {
        assertEquals(
            listOf(
                LyricsDisplayLanguage.SIMPLIFIED_CHINESE,
                LyricsDisplayLanguage.JAPANESE,
                LyricsDisplayLanguage.ENGLISH,
                LyricsDisplayLanguage.ORIGINAL,
            ),
            LyricsPresenter.displayLanguages(bilingual),
        )
    }

    @Test
    fun `renders selected language as main line`() {
        val display = LyricsPresenter.defaultDisplay(bilingual)
        val rows = LyricsPresenter.rows(bilingual, display)

        assertEquals(listOf("奔向黑夜", "勇敢的光芒"), rows.map { it.mainText })
        assertNull(rows.first().subText)
    }

    @Test
    fun `renders bilingual rows with original as subtitle`() {
        val display = LyricsPresenter.defaultDisplay(bilingual, bilingual = true)
        val rows = LyricsPresenter.rows(bilingual, display)

        assertEquals("奔向黑夜", rows.first().mainText)
        assertEquals("夜に駆ける", rows.first().subText)
    }

    @Test
    fun `lyrics window shows previous current and next`() {
        val rows =
            listOf(
                LyricsRow(startMs = 0L, mainText = "一"),
                LyricsRow(startMs = 1_000L, mainText = "二"),
                LyricsRow(startMs = 2_000L, mainText = "三"),
            )

        assertEquals(
            LyricsWindow(previous = null, current = "一", next = "二"),
            LyricsPresenter.window(rows, 0),
        )
        assertEquals(
            LyricsWindow(previous = "一", current = "二", next = "三"),
            LyricsPresenter.window(rows, 1),
        )
        assertEquals(
            LyricsWindow(previous = "二", current = "三", next = null),
            LyricsPresenter.window(rows, 2),
        )
        assertEquals(LyricsWindow(null, null, null), LyricsPresenter.window(emptyList(), 0))
        // 越界索引收敛到最近一行，空歌词全 null
        assertEquals(
            LyricsWindow(previous = "二", current = "三", next = null),
            LyricsPresenter.window(rows, 99),
        )
    }

    @Test
    fun `switches to japanese original on demand`() {
        val display =
            LyricsPresenter.defaultDisplay(bilingual)
                .copy(language = LyricsDisplayLanguage.JAPANESE)
        val rows = LyricsPresenter.rows(bilingual, display)

        assertEquals(listOf("夜に駆ける", "brave shine"), rows.map { it.mainText })
    }

    @Test
    fun `prefers translation when both sides look chinese`() {
        // 日文原文若全是汉字会被判成中文；简体中文显示必须取译文侧
        val document =
            LyricsDocumentBuilder.build(
                listOf(
                    LyricLine(1_000, "日本"),
                    LyricLine(1_000, "日本的翻译"),
                ),
                LyricsSource.SERVER,
            )
        val display = LyricsPresenter.defaultDisplay(document)

        assertEquals(LyricsDisplayLanguage.SIMPLIFIED_CHINESE, display.language)
        assertEquals("日本的翻译", LyricsPresenter.rows(document, display).single().mainText)
    }

    @Test
    fun `maps playback position to active row`() {
        val document =
            LyricsDocumentBuilder.build(
                listOf(
                    LyricLine(0, "第一句"),
                    LyricLine(5_000, "第二句"),
                    LyricLine(10_000, "第三句"),
                ),
                LyricsSource.SERVER,
            )
        val rows = LyricsPresenter.rows(document, LyricsPresenter.defaultDisplay(document))

        assertEquals(0, LyricsPresenter.activeIndex(rows, -1))
        assertEquals(0, LyricsPresenter.activeIndex(rows, 4_999))
        assertEquals(1, LyricsPresenter.activeIndex(rows, 5_000))
        assertEquals(2, LyricsPresenter.activeIndex(rows, 99_999))
        assertEquals(-1, LyricsPresenter.activeIndex(emptyList(), 1_000))
    }
}
