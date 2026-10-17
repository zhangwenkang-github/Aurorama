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
    fun `按钮缩放按 25 百分比档位步进`() {
        var scale = 1f
        val visited = mutableListOf(scale)
        repeat(PAGE_ZOOM_STEPS.lastIndex) {
            scale = nextPageZoomStep(scale)
            visited += scale
        }
        assertEquals(listOf(1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f, 4f), visited)
        assertEquals(4f, nextPageZoomStep(4f), 0.0f)
        assertEquals(1f, previousPageZoomStep(1f), 0.0f)
        // 双指连续缩放落在档位之间时，按钮命中相邻的上一档 / 下一档。
        assertEquals(2f, nextPageZoomStep(1.9f), 0.0001f)
        assertEquals(1.25f, previousPageZoomStep(1.4f), 0.0001f)
        assertEquals(3f, previousPageZoomStep(4f), 0.0001f)
        assertEquals(1.25f, nextPageZoomStep(1f), 0.0001f)
    }

    @Test
    fun `百分比读数与边界值`() {
        assertEquals(100, zoomPercent(1f))
        assertEquals(125, zoomPercent(1.25f))
        assertEquals(249, zoomPercent(2.49f))
        assertEquals(400, zoomPercent(9f))
        assertEquals(100, zoomPercent(Float.NaN))
    }

    @Test
    fun `缩放以视口中心或手指焦点为锚点`() {
        val centered = anchorPageOffset(Offset(100f, -40f), 2f, 3f, Offset.Zero)
        assertEquals(150f, centered.x, 0.01f)
        assertEquals(-60f, centered.y, 0.01f)

        // 焦点在右侧 200px：缩放前后焦点下的内容点保持不动。
        val focus = Offset(200f, 0f)
        val anchored = anchorPageOffset(Offset.Zero, 1f, 2f, focus)
        assertEquals(-200f, anchored.x, 0.01f)

        // 缩回 1× 时平移归零。
        assertEquals(Offset.Zero, anchorPageOffset(Offset(90f, 20f), 2f, 1f, focus))
    }

    @Test
    fun `适宽目标分别按分页滚动双栏和合并图槽位计算`() {
        // 分页：2000×1000 槽 + 0.5 页宽高比 → 4×（同时命中上限）。
        assertEquals(4f, pagedFitWidthScale(0.5f, 2000, 1000)!!, 0.0001f)
        // 双栏：每页只占半屏（1000×1000），同一页目标为 2×。
        assertEquals(2f, twoColumnFitWidthScale(0.5f, 2000, 1000)!!, 0.0001f)
        // 滚动：页面在 1× 本来就满宽。
        assertEquals(1f, scrollFitWidthScale(), 0.0001f)
        // 合并对图：使用合并位图的实际 1:1 宽高比，而不是第一页的 0.5。
        assertEquals(2f, mergedFitWidthScale(1000, 1000, 2000, 1000)!!, 0.0001f)
        // 比例 / 槽尺寸未就绪时没有目标，调用方不得重置倍率。
        assertEquals(null, pagedFitWidthScale(null, 2000, 1000))
        assertEquals(null, mergedFitWidthScale(0, 1000, 2000, 1000))
    }

    @Test
    fun `适宽切换丢弃旧页目标且未就绪时不动作`() {
        val target = FitWidthTarget(pageIndex = 1, slotKey = 1, scale = 2f)
        assertEquals(2f, target.scaleForCurrentPage(1)!!, 0.0001f)
        assertEquals(null, target.scaleForCurrentPage(2))
        assertEquals(1f, fitWidthToggleScale(2f, 2f)!!, 0.0001f)
        assertEquals(1.5f, fitWidthToggleScale(1f, 1.5f)!!, 0.0001f)
        assertEquals(null, fitWidthToggleScale(2f, null))
        assertEquals(null, fitWidthToggleScale(2f, Float.NaN))
    }

    @Test
    fun `按钮焦点按双栏槽位与滚动可见偏移计算`() {
        // 双栏：左页中心在屏幕 1/4，视口中心相对它为 +1/4 屏宽；右页相反。
        assertEquals(600f, spreadPageViewportFocus(2400, 0, 2)!!.x, 0.01f)
        assertEquals(-600f, spreadPageViewportFocus(2400, 1, 2)!!.x, 0.01f)
        // 滚动：页顶在视口 1000 px、页高 1000 px，视口中心 750 px → 相对页中心 -750 px。
        assertEquals(-750f, scrollViewportFocus(1500, 1000, 1000)!!.y, 0.01f)
        assertEquals(null, scrollViewportFocus(1500, 0, 0))
        assertEquals(null, spreadPageViewportFocus(2400, 2, 2))
    }

    @Test
    fun `跨页捏合只在两指分落左右半屏时成立`() {
        // 左右各一指（含中缝边界）：跨页捏合，由双栏外层统一手势面接管。
        assertTrue(spreadPinchSpansPages(listOf(100f, 900f), 800f))
        assertTrue(spreadPinchSpansPages(listOf(799f, 800f), 800f))
        // 同半页两指：交给页槽自己的手势层，外层不重复应用。
        assertTrue(!spreadPinchSpansPages(listOf(100f, 700f), 800f))
        assertTrue(!spreadPinchSpansPages(listOf(900f, 1500f), 800f))
        // 单指 / 非法宽度：都不成立。
        assertTrue(!spreadPinchSpansPages(listOf(100f), 800f))
        assertTrue(!spreadPinchSpansPages(listOf(100f, 900f), 0f))
    }

    @Test
    fun `跨页捏合焦点换算到当前页槽局部坐标`() {
        // 屏幕 1600×800、双栏；手指落在整屏 (400, 300)。
        // 左页槽中心 (400, 400) → 焦点 (0, -100)；右页槽中心 (1200, 400) → 焦点 (-800, -100)。
        assertEquals(Offset(0f, -100f), spreadPinchFocus(Offset(400f, 300f), 1600, 800, 0, 2))
        assertEquals(
            Offset(-800f, -100f),
            spreadPinchFocus(Offset(400f, 300f), 1600, 800, 1, 2),
        )
        assertEquals(null, spreadPinchFocus(Offset(400f, 300f), 1600, 800, 2, 2))
        assertEquals(null, spreadPinchFocus(Offset(400f, 300f), 0, 800, 0, 2))
        assertEquals(
            null,
            spreadPinchFocus(Offset(Float.NaN, 300f), 1600, 800, 0, 2),
        )
    }
}
