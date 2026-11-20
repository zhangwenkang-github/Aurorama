package com.zhangwenkang.cinefin.music.data.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 增强 LRC（`.elrc`）fixture 回归（W5-T11 / MUSIC-24/25/43）。
 *
 * fixture `lyrics/night-drive.elrc` 覆盖三类真实写法：行内 `<mm:ss.xx>` 词级标签、`[offset:+200]` 整体平移、同时间戳的 原文 +
 * 译文双语行。断言链：解析 → 文档构建（配对 / 语言）→ 逐词进度插值（卡拉 OK 扫词）。
 */
class ElrcFixtureTest {

    private val text: String = elrcFixture("night-drive.elrc")

    @Test
    fun `parses word tags with offset and bilingual lines`() {
        val lines = LrcParser.parse(text).filterNot { it.isMetadata }

        assertEquals(
            listOf(800L, 800L, 4_800L, 4_800L, 9_300L, 9_300L),
            lines.map { it.startMs },
        )
        assertEquals("When the night falls", lines[0].text)
        assertEquals("当夜幕降临", lines[1].text)
        assertEquals(
            listOf(
                LyricWord(800L, "When "),
                LyricWord(1_200L, "the "),
                LyricWord(1_600L, "night "),
                LyricWord(2_000L, "falls"),
            ),
            lines[0].words,
        )
        assertEquals(
            listOf(
                LyricWord(800L, "当"),
                LyricWord(1_200L, "夜"),
                LyricWord(1_600L, "幕"),
                LyricWord(2_000L, "降临"),
            ),
            lines[1].words,
        )
    }

    @Test
    fun `document pairs bilingual lines and keeps words`() {
        val document = LyricsDocumentBuilder.build(LrcParser.parse(text), LyricsSource.EXTERNAL_LRC)

        assertEquals(3, document.blocks.size)
        assertTrue(document.synced)
        val first = document.blocks.first()
        assertEquals("When the night falls", first.primary.text)
        assertEquals("当夜幕降临", first.secondary?.text)
        document.blocks.forEach { block ->
            assertTrue(lyricWordsMatchText(block.primary.words, block.primary.text))
            val secondary = checkNotNull(block.secondary)
            assertTrue(lyricWordsMatchText(secondary.words, secondary.text))
        }
    }

    @Test
    fun `display rows keep word data for the picked language`() {
        val document = LyricsDocumentBuilder.build(LrcParser.parse(text), LyricsSource.EXTERNAL_LRC)
        val display = LyricsPresenter.defaultDisplay(document, bilingual = true)

        val row = LyricsPresenter.rows(document, display).first()

        // 默认显示简体中文 → 取中文译文侧；原文件里的逐字标签必须跟着选中侧一起走
        assertEquals("当夜幕降临", row.mainText)
        assertEquals("When the night falls", row.subText)
        assertEquals(listOf("当", "夜", "幕", "降临"), row.words.map { it.text })
        assertTrue(lyricWordsMatchText(row.words, row.mainText))
    }

    @Test
    fun `word highlights hit 0 50 and 100 percent boundaries`() {
        val words =
            LyricsDocumentBuilder.build(LrcParser.parse(text), LyricsSource.EXTERNAL_LRC)
                .blocks
                .first()
                .primary
                .words

        // 首词起点：未唱（0%）
        assertEquals(
            listOf(0f, 0f, 0f, 0f),
            LyricsPresenter.wordHighlights(words, 800L).map { it.progress },
        )
        // 词内中点：首词 50%，后续词仍未唱
        val half = LyricsPresenter.wordHighlights(words, 1_000L)
        assertEquals(0.5f, half[0].progress, 0.0001f)
        assertEquals(0f, half[1].progress)
        // 到达下一词起点：首词 100%，次词从 0% 起
        val boundary = LyricsPresenter.wordHighlights(words, 1_200L)
        assertEquals(1f, boundary[0].progress)
        assertEquals(0f, boundary[1].progress)
        // 末词用行尾兜底（下一行与之同时间戳，兜底到 +DEFAULT_WORD_MS）
        assertEquals(0f, LyricsPresenter.wordHighlights(words, 2_000L)[3].progress)
        assertEquals(1f, LyricsPresenter.wordHighlights(words, 2_600L)[3].progress)
    }

    @Test
    fun `words mismatch guard rejects edited text`() {
        val words =
            LyricsDocumentBuilder.build(LrcParser.parse(text), LyricsSource.EXTERNAL_LRC)
                .blocks
                .first()
                .primary
                .words

        assertTrue(lyricWordsMatchText(words, "When the night falls"))
        assertFalse(lyricWordsMatchText(words, "When the night falls!"))
        assertFalse(lyricWordsMatchText(emptyList(), ""))
    }
}

/** 读取 `src/test/resources/lyrics/<name>` 的 UTF-8 原文（`.elrc` fixture 共用入口）。 */
internal fun elrcFixture(name: String): String =
    checkNotNull(ElrcFixtureTest::class.java.classLoader?.getResourceAsStream("lyrics/$name")) {
            "缺少歌词 fixture：lyrics/$name"
        }
        .use { it.readBytes().toString(Charsets.UTF_8) }
