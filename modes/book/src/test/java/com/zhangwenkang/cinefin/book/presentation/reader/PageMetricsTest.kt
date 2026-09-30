package com.zhangwenkang.cinefin.book.presentation.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PageMetricsTest {

    @Test
    fun `降采样系数是 2 的幂且长边不超上限`() {
        assertEquals(1, bitmapSampleSize(1024, 768, 2048))
        assertEquals(2, bitmapSampleSize(4000, 3000, 2048))
        assertEquals(4, bitmapSampleSize(8192, 4096, 2048))
        assertEquals(1, bitmapSampleSize(0, 0, 2048))
    }

    @Test
    fun `PDF 渲染比例按长边缩放到上限`() {
        // A4 竖版（612×792 pt）放大到 2048 长边 ≈ 2.586 倍。
        assertEquals(2.586f, pdfRenderScale(612, 792, 2048), 0.001f)
        assertTrue(pdfRenderScale(4000, 3000, 2048) < 1f)
    }

    @Test
    fun `页索引与 progression 互为逆运算`() {
        val counts = listOf(1, 24, 210, 1000)
        counts.forEach { count ->
            (0 until count).forEach { index ->
                val progression = progressionForPage(index, count)
                assertEquals(index, pageIndexForProgression(progression, count))
            }
        }
    }

    @Test
    fun `progression 越界与非法值被夹取`() {
        assertEquals(0, pageIndexForProgression(-1.0, 24))
        assertEquals(23, pageIndexForProgression(1.5, 24))
        assertEquals(0, pageIndexForProgression(Double.NaN, 24))
        assertEquals(0.0, progressionForPage(5, 0), 0.0)
    }

    @Test
    fun `页指示区分三种模式`() {
        assertEquals("滚动 · 1/24", pageIndicatorText(ReaderMode.Scroll, 0, 24))
        assertEquals("分页 · 5/24", pageIndicatorText(ReaderMode.Paged, 4, 24))
        assertEquals("双栏 · 5-6/24", pageIndicatorText(ReaderMode.TwoColumn, 4, 24))
        assertEquals("双栏 · 24-24/24", pageIndicatorText(ReaderMode.TwoColumn, 23, 24))
        assertEquals("分页 · 0/0", pageIndicatorText(ReaderMode.Paged, 0, 0))
    }
}
