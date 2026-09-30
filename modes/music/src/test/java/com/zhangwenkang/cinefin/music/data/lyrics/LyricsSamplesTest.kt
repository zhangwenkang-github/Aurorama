package com.zhangwenkang.cinefin.music.data.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 三样例结构回归（W3-R2 验收：aLIEz 83 行 / Brave Shine 27 行 / 爱的回归线 57 行）。
 *
 * 说明：**真歌歌词不入库**——仓库是 public，整篇商业歌词有版权风险（决策见 `MUSIC_PLAN`）。 这里用"行数 / 时间戳结构 /
 * 语言构成"完全同构的合成样例做单测；真实三样例的数据另在真机 + 本地 临时夹具上抽验，结论写回 `MUSIC_PLAN` §5。
 */
class LyricsSamplesTest {

    private val credits = listOf("作词：样例作者", "作曲：样例作者", "编曲：样例作者")

    /** 日文原文 + 中文翻译共享同一 Start 时间戳（服务端实测形态）。 */
    private fun bilingualSample(pairCount: Int, creditCount: Int = credits.size): List<LyricLine> {
        val lines = mutableListOf<LyricLine>()
        repeat(creditCount) { index ->
            lines += LyricLine(index * 10_000L, credits[index % credits.size])
        }
        repeat(pairCount) { index ->
            val start = 28_440L + index * 12_000L
            lines += LyricLine(start, "夜を駆ける 第${index + 1}句")
            lines += LyricLine(start, "奔向黑夜 第${index + 1}句翻译")
        }
        return lines
    }

    /** 纯中文歌词（无译文对）。 */
    private fun chineseSample(lineCount: Int): List<LyricLine> =
        List(lineCount) { index -> LyricLine(3_000L + index * 6_000L, "纯中文歌词 第${index + 1}句") }

    @Test
    fun `aLIEz sample - 83 lines pair into 40 bilingual blocks`() {
        val raw = bilingualSample(pairCount = 40)
        assertEquals(83, raw.size)

        val document = LyricsDocumentBuilder.build(raw, LyricsSource.SERVER)

        assertEquals(40, document.blocks.size)
        assertTrue(document.blocks.all { it.secondary != null })
        assertEquals(
            LyricLanguage.JAPANESE,
            LineLanguageDetector.detect(document.blocks.first().primary.text),
        )
        assertEquals(
            LyricLanguage.SIMPLIFIED_CHINESE,
            LineLanguageDetector.detect(document.blocks.first().secondary!!.text),
        )
        assertEquals(
            listOf(LyricLanguage.SIMPLIFIED_CHINESE, LyricLanguage.JAPANESE),
            document.availableLanguages,
        )

        // 默认简体中文：主行取译文，切换只影响显示
        val display = LyricsPresenter.defaultDisplay(document)
        assertEquals(LyricsDisplayLanguage.SIMPLIFIED_CHINESE, display.language)
        val rows = LyricsPresenter.rows(document, display)
        assertEquals(40, rows.size)
        assertTrue(rows.all { it.mainText.contains("翻译") })
        assertEquals(LyricsPresenter.rows(document, display).first().subText, null)
    }

    @Test
    fun `Brave Shine sample - 27 lines pair into 12 bilingual blocks`() {
        val raw = bilingualSample(pairCount = 12)
        assertEquals(27, raw.size)

        val document = LyricsDocumentBuilder.build(raw, LyricsSource.SERVER)

        assertEquals(12, document.blocks.size)
        assertTrue(document.blocks.all { it.secondary != null })
        assertEquals(
            12,
            LyricsPresenter.rows(document, LyricsPresenter.defaultDisplay(document)).size,
        )
    }

    @Test
    fun `pure chinese sample - 57 lines stay single language`() {
        val raw = chineseSample(lineCount = 57)
        assertEquals(57, raw.size)

        val document = LyricsDocumentBuilder.build(raw, LyricsSource.SERVER)

        assertEquals(57, document.blocks.size)
        assertTrue(document.blocks.all { it.secondary == null })
        assertEquals(listOf(LyricLanguage.SIMPLIFIED_CHINESE), document.availableLanguages)
        assertEquals(
            LyricsDisplayLanguage.SIMPLIFIED_CHINESE,
            LyricsPresenter.defaultDisplay(document).language,
        )

        val bilingualRows =
            LyricsPresenter.rows(
                document,
                LyricsPresenter.defaultDisplay(document, bilingual = true),
            )
        assertEquals(57, bilingualRows.size)
        assertTrue(bilingualRows.all { it.subText == null })
    }

    @Test
    fun `external lrc with near timestamps pairs end to end`() {
        val lrc =
            """
            [ti:样例]
            [offset:0]
            [00:12.00]夜を駆ける
            [00:12.20]奔向黑夜
            [00:15.00]brave shine
            """
                .trimIndent()

        val document = LyricsDocumentBuilder.build(LrcParser.parse(lrc), LyricsSource.EXTERNAL_LRC)

        assertEquals(2, document.blocks.size)
        assertEquals(LyricsSource.EXTERNAL_LRC, document.source)
        assertEquals("夜を駆ける", document.blocks.first().primary.text)
        assertEquals("奔向黑夜", document.blocks.first().secondary?.text)
        assertNull(document.blocks.last().secondary)
    }
}
