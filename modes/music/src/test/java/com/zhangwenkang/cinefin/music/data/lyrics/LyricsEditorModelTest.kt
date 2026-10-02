package com.zhangwenkang.cinefin.music.data.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 歌词编辑器纯函数（W25-MUSIC）：时间戳解析 / 格式化、文档预填与保存校验。 */
class LyricsEditorModelTest {

    @Test
    fun `时间戳格式化与解析往返`() {
        assertEquals("", formatLyricTime(null))
        assertEquals("00:00.000", formatLyricTime(0L))
        assertEquals("01:23.456", formatLyricTime(83_456L))
        assertEquals("12:00.050", formatLyricTime(720_050L))

        assertEquals(LyricTimeParse(startMs = null, valid = true), parseLyricTime("  "))
        assertEquals(LyricTimeParse(startMs = 83_450L, valid = true), parseLyricTime("01:23.45"))
        assertEquals(LyricTimeParse(startMs = 83_000L, valid = true), parseLyricTime("1:23"))
        assertEquals(LyricTimeParse(startMs = 83_456L, valid = true), parseLyricTime("01:23.456"))
        assertEquals(LyricTimeParse(startMs = 83_450L, valid = true), parseLyricTime("01:23:45"))

        assertFalse(parseLyricTime("00:99").valid)
        assertFalse(parseLyricTime("1分23秒").valid)
        assertFalse(parseLyricTime("abc").valid)
    }

    @Test
    fun `保存时校验非法时间戳并跳过空文本行`() {
        val lines =
            listOf(
                LyricEditLine(0L, "00:01.00", "第一句"),
                LyricEditLine(1L, "", "未同步行"),
                LyricEditLine(2L, "00:05.50", "   "),
            )

        val parsed = lyricEditLinesToLyricLines(lines).orEmpty()
        assertEquals(
            listOf(LyricLine(1_000L, "第一句"), LyricLine(null, "未同步行")),
            parsed,
        )

        val invalid = lines + LyricEditLine(3L, "99", "坏时间")
        assertNull(lyricEditLinesToLyricLines(invalid))
    }

    @Test
    fun `文档预填为原始行且覆盖写出可用 LRC 解析`() {
        val document =
            LyricsDocumentBuilder.build(
                listOf(
                    LyricLine(1_000L, "原文第一句"),
                    LyricLine(1_000L, "译文第一句"),
                    LyricLine(5_000L, "第二句"),
                ),
                LyricsSource.SERVER,
            )

        val editLines = lyricEditLines(document)
        assertEquals(3, editLines.size)
        assertEquals("00:01.000", editLines[0].timeText)
        assertEquals("译文第一句", editLines[1].text)

        val restored =
            lyricEditLinesFromText(
                LyricsOverrideStore.encodeLrc(lyricEditLinesToLyricLines(editLines).orEmpty())
            )
        assertEquals(editLines.map { it.text }, restored.map { it.text })
        assertEquals(editLines.map { it.timeText }, restored.map { it.timeText })
    }

    @Test
    fun `空文档预填为空列表`() {
        assertTrue(lyricEditLines(null).isEmpty())
    }
}
