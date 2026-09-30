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
}
