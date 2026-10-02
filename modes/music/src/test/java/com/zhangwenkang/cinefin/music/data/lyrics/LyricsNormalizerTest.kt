package com.zhangwenkang.cinefin.music.data.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsNormalizerTest {

    @Test
    fun `trims text and drops blank lines`() {
        val lines =
            LyricsNormalizer.normalize(
                listOf(
                    LyricLine(1_000, "  夜に駆ける  "),
                    LyricLine(2_000, "   "),
                    LyricLine(3_000, "奔向黑夜"),
                )
            )

        assertEquals(listOf("夜に駆ける", "奔向黑夜"), lines.map { it.text })
    }

    @Test
    fun `drops lrc tags and credit lines`() {
        val lines =
            LyricsNormalizer.normalize(
                listOf(
                    LyricLine(null, "[ti:aLIEz]", isMetadata = true),
                    LyricLine(0, "作词：泽野弘之"),
                    LyricLine(10_000, "作曲：泽野弘之"),
                    LyricLine(20_000, "编曲 : 泽野弘之"),
                    LyricLine(30_000, "Lyrics: SawanoHiroyuki"),
                    LyricLine(28_440, "正文"),
                )
            )

        assertEquals(listOf("正文"), lines.map { it.text })
        assertTrue(lines.none { it.isMetadata })
    }

    @Test
    fun `drops duplicated lines and sorts by start`() {
        val lines =
            LyricsNormalizer.normalize(
                listOf(
                    LyricLine(5_000, "第二句"),
                    LyricLine(1_000, "第一句"),
                    LyricLine(1_000, "第一句"),
                    LyricLine(null, "未同步行"),
                )
            )

        assertEquals(listOf("第一句", "第二句", "未同步行"), lines.map { it.text })
        assertFalse(lines.first().isMetadata)
    }

    @Test
    fun `strips stray timestamps from instrumental placeholder lines`() {
        assertEquals(
            "此歌曲为没有填词的纯音乐，请您欣赏",
            LyricsNormalizer.cleanPlaceholderTimestamp("[00:00:00]此歌曲为没有填词的纯音乐，请您欣赏"),
        )
        assertEquals(
            "纯音乐，请欣赏",
            LyricsNormalizer.cleanPlaceholderTimestamp("[00:35.50]纯音乐，请欣赏"),
        )
        // 非占位行不动：正文里的方括号文本 / 含 Instrumental 字样的署名行都不能被误伤
        assertEquals(
            "[00:12.00]普通歌词",
            LyricsNormalizer.cleanPlaceholderTimestamp("[00:12.00]普通歌词"),
        )
        assertEquals(
            "乐器录音师 Instrumental Recording Engineer：Kevin",
            LyricsNormalizer.cleanPlaceholderTimestamp(
                "乐器录音师 Instrumental Recording Engineer：Kevin"
            ),
        )
    }

    @Test
    fun `normalize cleans placeholder timestamps end to end`() {
        val lines =
            LyricsNormalizer.normalize(
                listOf(
                    LyricLine(null, "[00:00:00]此歌曲为没有填词的纯音乐，请您欣赏"),
                    LyricLine(0L, "纯音乐，请欣赏"),
                )
            )

        assertEquals(
            listOf("纯音乐，请欣赏", "此歌曲为没有填词的纯音乐，请您欣赏"),
            lines.map { it.text },
        )
    }
}
