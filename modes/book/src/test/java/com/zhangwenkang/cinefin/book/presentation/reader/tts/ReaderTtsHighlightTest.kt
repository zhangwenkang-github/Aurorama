package com.zhangwenkang.cinefin.book.presentation.reader.tts

import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderTtsHighlightTest {

    private val accent = 0xFF5AC8FA.toInt()
    private val surface = 0xFF1B212B.toInt()

    @Test
    fun `alpha 为零时 tint 等于页面底色`() {
        assertEquals(surface, ReaderTtsHighlight.tint(accent, surface, alpha = 0f))
    }

    @Test
    fun `alpha 等于模板值时 tint 等于 accent`() {
        assertEquals(
            accent,
            ReaderTtsHighlight.tint(accent, surface, alpha = ReaderTtsTemplateAlpha),
        )
    }

    @Test
    fun `18 百分比按模板反解`() {
        val tint = ReaderTtsHighlight.tint(accent, surface)
        val template = ReaderTtsTemplateAlpha
        val effective = ReaderTtsHighlightAlpha
        // 模板铺 0.28·tint + 0.72·surface 应等于 0.18·accent + 0.82·surface（±1 量化误差）。
        for (shift in intArrayOf(16, 8, 0)) {
            val tintChannel = (tint shr shift) and 0xFF
            val accentChannel = (accent shr shift) and 0xFF
            val surfaceChannel = (surface shr shift) and 0xFF
            val rendered = template * tintChannel + (1 - template) * surfaceChannel
            val expected = effective * accentChannel + (1 - effective) * surfaceChannel
            assertEquals(expected, rendered, 1.0f)
        }
    }

    @Test
    fun `淡入进度线性缩放`() {
        assertEquals(surface, ReaderTtsHighlight.fadeTint(accent, surface, 0f))
        assertEquals(
            ReaderTtsHighlight.tint(accent, surface),
            ReaderTtsHighlight.fadeTint(accent, surface, 1f),
        )
    }

    @Test
    fun `tint 线性插值端点与中点`() {
        val from = ReaderTtsHighlight.fadeTint(accent, surface, 0f)
        val to = ReaderTtsHighlight.fadeTint(accent, surface, 1f)
        assertEquals(from, ReaderTtsHighlight.lerpArgb(from, to, 0f))
        assertEquals(to, ReaderTtsHighlight.lerpArgb(from, to, 1f))
        // 越界自动收进 [0,1]，不会溢出通道。
        assertEquals(from, ReaderTtsHighlight.lerpArgb(from, to, -1f))
        assertEquals(to, ReaderTtsHighlight.lerpArgb(from, to, 2f))
        val middle = ReaderTtsHighlight.lerpArgb(from, to, 0.5f)
        for (shift in intArrayOf(16, 8, 0)) {
            val f = (from shr shift) and 0xFF
            val t = (to shr shift) and 0xFF
            val m = (middle shr shift) and 0xFF
            assertEquals((f + t) / 2.0, m.toDouble(), 1.0)
        }
        assertEquals(0xFF, (middle shr 24) and 0xFF)
    }
}
