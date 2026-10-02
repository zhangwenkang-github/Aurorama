package com.zhangwenkang.cinefin.music.data.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LrcParserTest {

    @Test
    fun `parses minute-second timestamp formats`() {
        val lines =
            LrcParser.parse(
                """
                [00:12]无小数
                [00:12.3]一位小数
                [00:12.34]两位小数
                [00:12.345]三位小数
                [01:02.50]跨分钟
                """
                    .trimIndent()
            )

        assertEquals(
            listOf(12_000L, 12_300L, 12_340L, 12_345L, 62_500L),
            lines.map { it.startMs },
        )
        assertEquals("无小数", lines.first().text)
    }

    @Test
    fun `expands multiple timestamps on one line`() {
        val lines = LrcParser.parse("[00:01.00][00:05.50]副歌")

        assertEquals(listOf(1_000L, 5_500L), lines.map { it.startMs })
        assertEquals(listOf("副歌", "副歌"), lines.map { it.text })
    }

    @Test
    fun `applies offset tag to all timestamps`() {
        // offset 语义：时间戳 - offset；正值让歌词整体提前
        assertEquals(9_500L, LrcParser.parse("[offset:+500]\n[00:10.00]早").single().startMs)
        assertEquals(10_500L, LrcParser.parse("[offset:-500]\n[00:10.00]晚").single().startMs)
    }

    @Test
    fun `keeps id tags as metadata and skips blank lines`() {
        val lines =
            LrcParser.parse(
                """
                [ti:aLIEz]
                [ar:SawanoHiroyuki[nZk]]

                [00:12.00]正文
                """
                    .trimIndent()
            )

        val metadata = lines.filter { it.isMetadata }
        assertEquals(2, metadata.size)
        assertEquals("[ti:aLIEz]", metadata.first().text)
        assertTrue(metadata.all { it.startMs == null })
        assertEquals(listOf("正文"), lines.filterNot { it.isMetadata }.map { it.text })
    }

    @Test
    fun `keeps untimed text as unsynced line`() {
        val line = LrcParser.parse("没有时间戳的一行").single()

        assertNull(line.startMs)
        assertEquals("没有时间戳的一行", line.text)
    }

    @Test
    fun `drops timestamp-only lines`() {
        assertTrue(LrcParser.parse("[00:30.00]\n").isEmpty())
    }

    @Test
    fun `parses enhanced lrc word tags`() {
        val line = LrcParser.parse("[00:12.00]<00:12.00>Hel<00:12.50>lo <00:13.00>world").single()

        assertEquals(12_000L, line.startMs)
        assertEquals("Hello world", line.text)
        assertEquals(
            listOf(
                LyricWord(12_000L, "Hel"),
                LyricWord(12_500L, "lo "),
                LyricWord(13_000L, "world"),
            ),
            line.words,
        )
    }

    @Test
    fun `applies offset to word tags and keeps plain lines without words`() {
        val shifted = LrcParser.parse("[offset:+500]\n[00:10.00]<00:10.00>早<00:11.00>安").single()

        assertEquals(9_500L, shifted.startMs)
        assertEquals(listOf(9_500L, 10_500L), shifted.words.map { it.startMs })
        assertEquals(listOf("早", "安"), shifted.words.map { it.text })

        assertTrue(LrcParser.parse("[00:01.00]普通歌词").single().words.isEmpty())
    }

    @Test
    fun `skips empty word segments and keeps leftover text`() {
        val line = LrcParser.parse("[00:01.00]前<00:01.00><00:02.00>后").single()

        assertEquals(
            listOf(LyricWord(1_000L, "前"), LyricWord(2_000L, "后")),
            line.words,
        )
        assertEquals("前后", line.text)
    }
}
