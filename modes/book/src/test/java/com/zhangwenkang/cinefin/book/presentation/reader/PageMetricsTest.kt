package com.zhangwenkang.cinefin.book.presentation.reader

import androidx.compose.ui.geometry.Offset
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
        // W26：单页槽（尾页单张）显示单页号（旧口径是 "24-24/24"）
        assertEquals("双栏 · 24/24", pageIndicatorText(ReaderMode.TwoColumn, 23, 24))
        assertEquals("分页 · 0/0", pageIndicatorText(ReaderMode.Paged, 0, 0))
        // W26：lastPageIndex 是 0-based 槽末页；单页槽显示单页号、两页槽显示范围
        assertEquals(
            "双栏 · 15/30",
            pageIndicatorText(ReaderMode.TwoColumn, 14, 30, lastPageIndex = 14),
        )
        assertEquals(
            "双栏 · 21-22/30",
            pageIndicatorText(ReaderMode.TwoColumn, 20, 30, lastPageIndex = 21),
        )
        assertEquals(
            "双栏 · 29/29",
            pageIndicatorText(ReaderMode.TwoColumn, 28, 29, lastPageIndex = 28),
        )
    }

    @Test
    fun `放大后的平移被夹在可移动范围内`() {
        val clamped = clampPageOffset(Offset(500f, -900f), scale = 2f, width = 800, height = 1000)
        assertEquals(400f, clamped.x, 0.01f)
        assertEquals(-500f, clamped.y, 0.01f)
        assertEquals(Offset.Zero, clampPageOffset(Offset(30f, 30f), scale = 1f, 800, 1000))
    }

    @Test
    fun `双指缩放夹取在 1 倍到 4 倍之间`() {
        val zoomed = PageZoom().transform(zoom = 10f, pan = Offset.Zero, width = 800, height = 1000)
        assertEquals(PAGE_MAX_ZOOM, zoomed.scale, 0.0001f)

        val shrunk =
            zoomed.transform(zoom = 0.01f, pan = Offset(120f, 50f), width = 800, height = 1000)
        assertEquals(1f, shrunk.scale, 0.0001f)
        assertEquals(Offset.Zero, shrunk.offset)
    }

    @Test
    fun `缩放平移按放大后的边缘夹取`() {
        val zoomed = PageZoom().transform(zoom = 2f, pan = Offset.Zero, width = 800, height = 1000)
        assertEquals(2f, zoomed.scale, 0.0001f)

        val panned =
            zoomed.transform(zoom = 1f, pan = Offset(500f, -900f), width = 800, height = 1000)
        assertEquals(400f, panned.offset.x, 0.01f)
        assertEquals(-500f, panned.offset.y, 0.01f)
    }

    @Test
    fun `非有限手势值不破坏缩放状态`() {
        val zoomed =
            PageZoom().transform(zoom = 2f, pan = Offset(10f, 10f), width = 800, height = 1000)

        val nanZoom =
            zoomed.transform(zoom = Float.NaN, pan = Offset.Zero, width = 800, height = 1000)
        assertEquals(zoomed.scale, nanZoom.scale, 0.0001f)
        assertEquals(zoomed.offset, nanZoom.offset)

        val infiniteZoom =
            zoomed.transform(
                zoom = Float.POSITIVE_INFINITY,
                pan = Offset.Zero,
                width = 800,
                height = 1000,
            )
        assertEquals(zoomed.scale, infiniteZoom.scale, 0.0001f)

        val nanPan =
            zoomed.transform(
                zoom = 1f,
                pan = Offset(Float.NaN, Float.NaN),
                width = 800,
                height = 1000,
            )
        assertEquals(zoomed.offset, nanPan.offset)
    }
}
