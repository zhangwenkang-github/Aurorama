package com.zhangwenkang.cinefin.music.data.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LyricsPairerTest {

    @Test
    fun `pairs lines sharing the same start`() {
        val blocks =
            LyricsPairer.pair(
                listOf(
                    LyricLine(28_440, "夜に駆ける"),
                    LyricLine(28_440, "奔向黑夜"),
                )
            )

        assertEquals(1, blocks.size)
        assertEquals("夜に駆ける", blocks.single().primary.text)
        assertEquals("奔向黑夜", blocks.single().secondary?.text)
        assertEquals(28_440L, blocks.single().startMs)
    }

    @Test
    fun `pairs near timestamps within tolerance`() {
        val blocks =
            LyricsPairer.pair(
                listOf(
                    LyricLine(12_000, "夜に駆ける"),
                    LyricLine(12_200, "奔向黑夜"),
                )
            )

        assertEquals(1, blocks.size)
        assertEquals("奔向黑夜", blocks.single().secondary?.text)
    }

    @Test
    fun `does not pair lines beyond tolerance`() {
        val blocks =
            LyricsPairer.pair(
                listOf(
                    LyricLine(12_000, "夜に駆ける"),
                    LyricLine(12_400, "奔向黑夜"),
                )
            )

        assertEquals(2, blocks.size)
        assertNull(blocks.first().secondary)
        assertNull(blocks.last().secondary)
    }

    @Test
    fun `keeps consecutive chinese lines separate even when close`() {
        // 纯中文歌（爱的回归线类）相邻两句间隔很近时不能被误配成"原文 + 译文"
        val blocks =
            LyricsPairer.pair(
                listOf(
                    LyricLine(12_000, "第一句歌词"),
                    LyricLine(12_150, "第二句歌词"),
                )
            )

        assertEquals(2, blocks.size)
        assertEquals("第一句歌词", blocks.first().primary.text)
        assertEquals("第二句歌词", blocks.last().primary.text)
    }

    @Test
    fun `treats traditional line as original and simplified as translation`() {
        val blocks =
            LyricsPairer.pair(
                listOf(
                    LyricLine(8_000, "我們這樣說話"),
                    LyricLine(8_000, "我们这样说话"),
                )
            )

        assertEquals("我們這樣說話", blocks.single().primary.text)
        assertEquals("我们这样说话", blocks.single().secondary?.text)
    }

    @Test
    fun `keeps source order for same language pair`() {
        val blocks =
            LyricsPairer.pair(
                listOf(
                    LyricLine(9_000, "first line"),
                    LyricLine(9_000, "second line"),
                )
            )

        assertEquals("first line", blocks.single().primary.text)
        assertEquals("second line", blocks.single().secondary?.text)
    }
}
