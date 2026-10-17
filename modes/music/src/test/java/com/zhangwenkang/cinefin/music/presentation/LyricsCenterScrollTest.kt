package com.zhangwenkang.cinefin.music.presentation

import org.junit.Assert.assertEquals
import org.junit.Test

/** W79-QA-D1：歌词行居中微调必须始终使用 LazyList 的内容坐标系。 */
class LyricsCenterScrollTest {

    @Test
    fun `computes delta with no content padding`() {
        val delta =
            lyricsCenterScrollDelta(
                viewportStart = 0,
                viewportEnd = 1_000,
                itemOffset = 700,
                itemSize = 100,
            )

        assertEquals(250f, delta, 0.001f)
    }

    @Test
    fun `computes delta when half viewport padding shifts the viewport origin`() {
        val delta =
            lyricsCenterScrollDelta(
                viewportStart = -500,
                viewportEnd = 500,
                itemOffset = 0,
                itemSize = 100,
            )

        assertEquals(50f, delta, 0.001f)
    }

    @Test
    fun `already centered item needs no adjustment`() {
        val delta =
            lyricsCenterScrollDelta(
                viewportStart = -500,
                viewportEnd = 500,
                itemOffset = -50,
                itemSize = 100,
            )

        assertEquals(0f, delta, 0.001f)
    }
}
