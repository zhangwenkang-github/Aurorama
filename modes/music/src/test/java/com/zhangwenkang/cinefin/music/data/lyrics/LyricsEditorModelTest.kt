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

    @Test
    fun `整段偏移与单行微调只改有效时间戳`() {
        val lines =
            listOf(
                LyricEditLine(0L, "00:10.00", "第一句"),
                LyricEditLine(1L, "", "未同步"),
                LyricEditLine(2L, "坏", "非法"),
                LyricEditLine(3L, "00:20.00", "第三句"),
            )

        val shifted = shiftLyricEditLines(lines, -500L)
        assertEquals("00:09.500", shifted[0].timeText)
        assertEquals("", shifted[1].timeText)
        assertEquals("坏", shifted[2].timeText)
        assertEquals("00:19.500", shifted[3].timeText)

        val nudged = nudgeLyricEditLine(shifted, 3L, 1_000L)
        assertEquals("00:09.500", nudged[0].timeText)
        assertEquals("00:20.500", nudged[3].timeText)

        assertEquals(500L, parseLyricOffsetMs("+500"))
        assertEquals(-500L, parseLyricOffsetMs("-500"))
        assertEquals(0L, parseLyricOffsetMs(" 0 "))
        assertNull(parseLyricOffsetMs("半秒"))
        assertNull(parseLyricOffsetMs(""))
    }

    @Test
    fun `偏移同步平移逐字数据并钳制到零`() {
        val words = listOf(LyricWord(10_000L, "逐"), LyricWord(10_500L, "字"))
        val line = LyricEditLine(0L, "00:10.00", "逐字", words = words)

        val shifted = shiftLyricEditLines(listOf(line), -500L).single()
        assertEquals("00:09.500", shifted.timeText)
        assertEquals(listOf(LyricWord(9_500L, "逐"), LyricWord(10_000L, "字")), shifted.words)

        val clamped =
            nudgeLyricEditLine(
                    listOf(
                        LyricEditLine(0L, "00:00.100", "尾", words = listOf(LyricWord(100L, "尾")))
                    ),
                    id = 0L,
                    deltaMs = -500L,
                )
                .single()
        assertEquals("00:00.000", clamped.timeText)
        assertEquals(listOf(LyricWord(0L, "尾")), clamped.words)
    }

    @Test
    fun `保存保留逐字数据但文本或时间被手改后丢弃`() {
        val words = listOf(LyricWord(10_000L, "逐"), LyricWord(10_500L, "字"))
        val line = LyricEditLine(0L, "00:10.00", "逐字", words = words)

        assertEquals(words, lyricEditLinesToLyricLines(listOf(line)).orEmpty().single().words)

        val textEdited = line.copy(text = "改过的文本")
        assertTrue(
            lyricEditLinesToLyricLines(listOf(textEdited)).orEmpty().single().words.isEmpty()
        )

        // 手动改时间后首词与行时间不再一致 → 丢弃逐字（防止高亮错位）
        val timeEdited = line.copy(timeText = "00:15.00")
        assertTrue(
            lyricEditLinesToLyricLines(listOf(timeEdited)).orEmpty().single().words.isEmpty()
        )
    }
}
