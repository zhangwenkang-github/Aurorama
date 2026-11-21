package com.zhangwenkang.cinefin.book.presentation.reader

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PageTurnAnimationTest {

    @Test
    fun `仿真松手过四成阈值完成否则弹回`() {
        val state = PageTurnDragState()
        state.begin(PageTurnDirection.Forward)
        state.drag(0.4f)
        assertEquals(PageTurnRelease.Complete, state.release())

        val below = PageTurnDragState()
        below.begin(PageTurnDirection.Forward)
        below.drag(0.39f)
        assertEquals(PageTurnRelease.Cancel, below.release())
    }

    @Test
    fun `没有进行中的拖动时松手不判定`() {
        val state = PageTurnDragState()
        assertNull(state.release())
        assertFalse(state.active)
        state.begin(PageTurnDirection.Backward)
        assertTrue(state.active)
        state.reset()
        assertFalse(state.active)
        assertEquals(0f, state.progress, 1e-6f)
    }

    @Test
    fun `拖动进度被夹在 0 到 1 之间且可回拖`() {
        val state = PageTurnDragState()
        state.begin(PageTurnDirection.Forward)
        state.drag(0.6f)
        state.drag(1.0f)
        assertEquals(1f, state.progress, 1e-6f)
        state.drag(-0.5f)
        assertEquals(0.5f, state.progress, 1e-6f)
        state.drag(-2f)
        assertEquals(0f, state.progress, 1e-6f)
        assertEquals(PageTurnRelease.Cancel, state.release())
    }

    @Test
    fun `RTL 下入场边与拖动方向镜像`() {
        assertEquals(PageTurnEdge.Right, pageTurnEntryEdge(forward = true, rtl = false))
        assertEquals(PageTurnEdge.Left, pageTurnEntryEdge(forward = false, rtl = false))
        assertEquals(PageTurnEdge.Left, pageTurnEntryEdge(forward = true, rtl = true))
        assertEquals(PageTurnEdge.Right, pageTurnEntryEdge(forward = false, rtl = true))

        // LTR：左移 = 前进，右移 = 后退。
        assertEquals(PageTurnDirection.Forward, pageTurnDragDirection(dx = -12f, rtl = false))
        assertEquals(PageTurnDirection.Backward, pageTurnDragDirection(dx = 12f, rtl = false))
        // RTL：右移 = 前进，左移 = 后退。
        assertEquals(PageTurnDirection.Forward, pageTurnDragDirection(dx = 12f, rtl = true))
        assertEquals(PageTurnDirection.Backward, pageTurnDragDirection(dx = -12f, rtl = true))
        assertNull(pageTurnDragDirection(dx = 0f, rtl = false))
        assertNull(pageTurnDragDirection(dx = Float.NaN, rtl = true))
    }

    @Test
    fun `拖动进度增量按方向与 RTL 归一`() {
        // LTR 前进 = 向左拖（负位移）→ 正进度增量。
        assertEquals(
            0.2f,
            pageTurnDragProgress(-0.2f, PageTurnDirection.Forward, rtl = false),
            1e-6f,
        )
        // LTR 后退 = 向右拖 → 正进度增量。
        assertEquals(
            0.2f,
            pageTurnDragProgress(0.2f, PageTurnDirection.Backward, rtl = false),
            1e-6f,
        )
        // RTL 前进 = 向右拖。
        assertEquals(0.2f, pageTurnDragProgress(0.2f, PageTurnDirection.Forward, rtl = true), 1e-6f)
        assertEquals(
            0.2f,
            pageTurnDragProgress(-0.2f, PageTurnDirection.Backward, rtl = true),
            1e-6f,
        )
        assertEquals(
            0f,
            pageTurnDragProgress(Float.NaN, PageTurnDirection.Forward, rtl = true),
            1e-6f,
        )
    }

    @Test
    fun `中断接管从进行中的进度续上`() {
        val state = PageTurnDragState()
        state.begin(PageTurnDirection.Forward)
        state.drag(0.7f)
        // 新手势接管：沿用当前进度，方向以新判定为准。
        state.begin(PageTurnDirection.Forward, carryOverProgress = state.progress)
        assertEquals(0.7f, state.progress, 1e-6f)
        state.drag(0.2f)
        assertEquals(0.9f, state.progress, 1e-6f)
        assertEquals(PageTurnRelease.Complete, state.release())

        // 越界的接管进度同样被夹住。
        state.begin(PageTurnDirection.Backward, carryOverProgress = 4f)
        assertEquals(1f, state.progress, 1e-6f)
    }

    @Test
    fun `减少动态把覆盖与仿真降级为淡入淡出`() {
        assertEquals(
            PageTurnEffect.Cover,
            pageTurnEffect(ReaderPageAnimation.Cover, reduceMotion = false),
        )
        assertEquals(
            PageTurnEffect.Curl,
            pageTurnEffect(ReaderPageAnimation.Curl, reduceMotion = false),
        )
        assertEquals(
            PageTurnEffect.Fade,
            pageTurnEffect(ReaderPageAnimation.Cover, reduceMotion = true),
        )
        assertEquals(
            PageTurnEffect.Fade,
            pageTurnEffect(ReaderPageAnimation.Curl, reduceMotion = true),
        )
        // 滑动是用户直接拖动的 Pager，不是自动播放的动画；无动画（EPUB 专用）落在 PDF/CBZ 上按滑动。
        assertEquals(
            PageTurnEffect.Slide,
            pageTurnEffect(ReaderPageAnimation.Slide, reduceMotion = true),
        )
        assertEquals(
            PageTurnEffect.Slide,
            pageTurnEffect(ReaderPageAnimation.None, reduceMotion = false),
        )
    }

    @Test
    fun `EPUB 两档映射 Readium animated 参数`() {
        assertTrue(epubPageTurnAnimated(ReaderPageAnimation.Slide, reduceMotion = false))
        assertFalse(epubPageTurnAnimated(ReaderPageAnimation.None, reduceMotion = false))
        // 存的是 PDF/CBZ 的覆盖 / 仿真时，EPUB 回落为有动画（Readium 只支持滑动）。
        assertTrue(epubPageTurnAnimated(ReaderPageAnimation.Cover, reduceMotion = false))
        assertTrue(epubPageTurnAnimated(ReaderPageAnimation.Curl, reduceMotion = false))
        // 系统减少动态：Readium 无法淡入淡出，降级为无动画。
        assertFalse(epubPageTurnAnimated(ReaderPageAnimation.Slide, reduceMotion = true))
    }

    @Test
    fun `可选档位只给 EPUB 两档`() {
        assertEquals(
            listOf(ReaderPageAnimation.Slide, ReaderPageAnimation.None),
            ReaderEpubPageAnimations,
        )
        assertEquals(
            listOf(
                ReaderPageAnimation.Slide,
                ReaderPageAnimation.Cover,
                ReaderPageAnimation.Curl,
            ),
            ReaderSimplePageAnimations,
        )
    }

    @Test
    fun `翻页动画档位可持久化往返`() {
        ReaderPageAnimation.entries.forEach { animation ->
            assertEquals(animation, ReaderPageAnimation.fromStorage(animation.storageValue))
        }
        assertEquals(ReaderPageAnimation.Slide, ReaderPageAnimation.fromStorage(null))
        assertEquals(ReaderPageAnimation.Slide, ReaderPageAnimation.fromStorage("bogus"))
        assertEquals(
            "pref_reader_page_animation",
            ReaderPageAnimationPreferences.pageAnimation.backendName,
        )
        assertEquals("slide", ReaderPageAnimationPreferences.pageAnimation.defaultValue)
    }

    @Test
    fun `动画时长与压暗按定稿数值`() {
        assertEquals(240, pageTurnDurationMs(PageTurnEffect.Cover, PageTurnRelease.Complete))
        assertEquals(240, pageTurnDurationMs(PageTurnEffect.Cover, PageTurnRelease.Cancel))
        assertEquals(260, pageTurnDurationMs(PageTurnEffect.Curl, PageTurnRelease.Complete))
        assertEquals(180, pageTurnDurationMs(PageTurnEffect.Curl, PageTurnRelease.Cancel))
        assertEquals(0, pageTurnDurationMs(PageTurnEffect.Slide, PageTurnRelease.Complete))
        assertEquals(0.08f, PAGE_TURN_OUTGOING_DIM_ALPHA, 1e-6f)
        assertEquals(0.4f, PAGE_TURN_CURL_COMMIT_THRESHOLD, 1e-6f)
    }

    @Test
    fun `起手方向受页面边界限制`() {
        assertTrue(pageTurnCanTurn(0, 5, PageTurnDirection.Forward))
        assertFalse(pageTurnCanTurn(4, 5, PageTurnDirection.Forward))
        assertFalse(pageTurnCanTurn(0, 5, PageTurnDirection.Backward))
        assertTrue(pageTurnCanTurn(1, 5, PageTurnDirection.Backward))
        assertFalse(pageTurnCanTurn(0, 0, PageTurnDirection.Forward))
    }

    @Test
    fun `卷页角左右侧按方向与 RTL 镜像`() {
        assertTrue(curlCornerFromRight(forward = true, rtl = false))
        assertFalse(curlCornerFromRight(forward = false, rtl = false))
        assertFalse(curlCornerFromRight(forward = true, rtl = true))
        assertTrue(curlCornerFromRight(forward = false, rtl = true))
    }

    @Test
    fun `卷页几何从整页收敛到零面积`() {
        val width = 400f
        val height = 600f
        val start = curlGeometry(width, height, progress = 0f, cornerFromRight = true)
        assertEquals(width * height, polygonArea(start.page), 1f)

        val middle = curlGeometry(width, height, progress = 0.5f, cornerFromRight = true)
        val pageArea = polygonArea(middle.page)
        val flapArea = polygonArea(middle.flap)
        // 页身 + 翻折面一起仍是整页面积（折面只是镜像，不增减像素）。
        assertEquals(width * height, pageArea + flapArea, 1f)
        assertTrue(pageArea > 0f)
        assertTrue(flapArea > 0f)
        assertTrue(middle.foldStart != null && middle.foldEnd != null)

        val end = curlGeometry(width, height, progress = 1f, cornerFromRight = true)
        assertEquals(0f, polygonArea(end.page), 1f)
        assertEquals(width * height, polygonArea(end.flap), 1f)
    }

    @Test
    fun `卷页几何在左右角镜像`() {
        val width = 400f
        val right = curlGeometry(width, 600f, progress = 0.5f, cornerFromRight = true)
        val left = curlGeometry(width, 600f, progress = 0.5f, cornerFromRight = false)
        assertEquals(polygonArea(right.page), polygonArea(left.page), 0.5f)
        assertEquals(polygonArea(right.flap), polygonArea(left.flap), 0.5f)
        // 两个角起卷的几何互为左右镜像（按 x 轴翻转后顶点集合一致）。
        assertEquals(mirrored(right.page, width), rounded(left.page))
        assertEquals(mirrored(right.flap, width), rounded(left.flap))
    }

    @Test
    fun `卷页几何对非法尺寸与进度容错`() {
        assertTrue(curlGeometry(0f, 100f, 0.5f, true).page.isEmpty())
        assertTrue(curlGeometry(Float.NaN, 100f, 0.5f, true).flap.isEmpty())
        val clamped = curlGeometry(100f, 100f, progress = 4f, cornerFromRight = true)
        assertEquals(0f, polygonArea(clamped.page), 1f)
        assertEquals(0f, curlFlapShade(Float.NaN) - PAGE_TURN_CURL_FLAP_SHADE, 1e-6f)
    }

    @Test
    fun `进度接近 1 时视为已翻过`() {
        assertTrue(pageTurnFullyTurned(1f))
        assertFalse(pageTurnFullyTurned(0.999f))
    }

    private fun polygonArea(points: List<PageTurnPoint>): Float {
        if (points.size < 3) return 0f
        var sum = 0f
        for (index in points.indices) {
            val current = points[index]
            val next = points[(index + 1) % points.size]
            sum += current.x * next.y - next.x * current.y
        }
        return abs(sum) / 2f
    }

    /** 顶点按 0.01px 取整并排序：浮点裁剪顺序不同也能比较集合。 */
    private fun rounded(points: List<PageTurnPoint>): List<String> =
        points.map { "${(it.x * 100).toInt()},${(it.y * 100).toInt()}" }.sorted()

    private fun mirrored(points: List<PageTurnPoint>, width: Float): List<String> =
        rounded(points.map { PageTurnPoint(width - it.x, it.y) })
}
