package com.zhangwenkang.cinefin.book.presentation.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W80（#32 / #24）阅读器手势与版式纯规则。
 *
 * 覆盖：双击三等分命中、上下滑亮度换算与生效区、适宽倍数、EPUB 双栏兜底阈值、滚动到资源末尾的章节衔接判据。
 */
class ReaderGesturesTest {

    @Test
    fun `双击三等分命中左中右`() {
        assertEquals(ReaderTapTarget.Previous, readerTapTarget(100f, 1200f))
        assertEquals(ReaderTapTarget.ToggleFit, readerTapTarget(600f, 1200f))
        assertEquals(ReaderTapTarget.Next, readerTapTarget(1100f, 1200f))
    }

    @Test
    fun `双击边界落在分界线上进中间区`() {
        // 400/1200 = 1/3：分界线本身属于中间（适宽），避免"差一像素翻页"。
        assertEquals(ReaderTapTarget.ToggleFit, readerTapTarget(400f, 1200f))
        assertEquals(ReaderTapTarget.ToggleFit, readerTapTarget(800f, 1200f))
        assertEquals(ReaderTapTarget.Previous, readerTapTarget(399f, 1200f))
        assertEquals(ReaderTapTarget.Next, readerTapTarget(801f, 1200f))
    }

    @Test
    fun `宽度非法时退化为适宽而不是翻页`() {
        assertEquals(ReaderTapTarget.ToggleFit, readerTapTarget(10f, 0f))
        assertEquals(ReaderTapTarget.ToggleFit, readerTapTarget(Float.NaN, 1080f))
    }

    @Test
    fun `向上拖变亮 向下拖变暗 并夹在可用区间`() {
        assertEquals(0.7f, readerBrightnessAfterDrag(0.5f, -0.2f), 0.0001f)
        assertEquals(0.3f, readerBrightnessAfterDrag(0.5f, 0.2f), 0.0001f)
        assertEquals(1f, readerBrightnessAfterDrag(0.9f, -0.5f), 0.0001f)
        assertEquals(ReaderBrightnessRange.start, readerBrightnessAfterDrag(0.1f, 0.5f), 0.0001f)
    }

    @Test
    fun `亮度非有限输入不产生 NaN`() {
        val value = readerBrightnessAfterDrag(0.5f, Float.NaN)
        assertTrue(value.isFinite())
        assertEquals(0.5f, value, 0.0001f)
    }

    @Test
    fun `滚动档只有左侧窄带认亮度手势`() {
        assertTrue(readerBrightnessDragAllowed(scrollMode = true, startX = 100f, width = 1600f))
        assertFalse(readerBrightnessDragAllowed(scrollMode = true, startX = 900f, width = 1600f))
        // 分页 / 双栏：整屏都可以（纵向拖动在那些档位没有别的用途）。
        assertTrue(readerBrightnessDragAllowed(scrollMode = false, startX = 900f, width = 1600f))
    }

    @Test
    fun `适宽倍数 竖向页面放大 横向页面保持 1`() {
        // 页面 3:4（0.75）比容器（0.625）更"宽" → 本来就满宽，倍数 1。
        assertEquals(1f, fitWidthScale(pageAspect = 0.75f, boxAspect = 0.625f), 0.0001f)
        // 页面 0.5（细长）比容器 1.0（横）更竖 → 放大 2 倍才满宽。
        assertEquals(2f, fitWidthScale(pageAspect = 0.5f, boxAspect = 1f), 0.0001f)
        // 横向页面（a ≥ b）本来就满宽。
        assertEquals(1f, fitWidthScale(pageAspect = 1.6f, boxAspect = 1f), 0.0001f)
    }

    @Test
    fun `适宽倍数上限不超过缩放硬顶`() {
        assertEquals(
            PAGE_MAX_ZOOM,
            fitWidthScale(pageAspect = 0.05f, boxAspect = 1f),
            0.0001f,
        )
    }

    @Test
    fun `宽高比未知时不缩放`() {
        assertEquals(1f, fitWidthScale(pageAspect = null, boxAspect = 1f), 0.0001f)
        assertEquals(1f, fitWidthScale(pageAspect = 0f, boxAspect = 1f), 0.0001f)
        assertEquals(1f, fitWidthScale(pageAspect = 0.6f, boxAspect = 0f), 0.0001f)
    }

    @Test
    fun `平板宽度起强制 EPUB 双栏`() {
        assertTrue(shouldForceEpubTwoColumns(711))
        assertTrue(shouldForceEpubTwoColumns(EPUB_TWO_COLUMN_MIN_WIDTH_DP))
        assertFalse(shouldForceEpubTwoColumns(EPUB_TWO_COLUMN_MIN_WIDTH_DP - 1))
        assertFalse(shouldForceEpubTwoColumns(393))
    }

    @Test
    fun `资源内拖不动且方向明确时衔接下一章`() {
        // 同一资源、progression 没变、向上拖过半屏 → 下一章。
        assertEquals(
            1,
            readerScrollChainStep(
                beforeHref = "ch1.xhtml",
                beforeProgression = 0.44,
                afterHref = "ch1.xhtml",
                afterProgression = 0.44,
                dragDyPx = -600f,
                viewportHeightPx = 2000,
            ),
        )
        // 向下拖 → 上一章。
        assertEquals(
            -1,
            readerScrollChainStep(
                beforeHref = "ch2.xhtml",
                beforeProgression = 0.0,
                afterHref = "ch2.xhtml",
                afterProgression = 0.0,
                dragDyPx = 600f,
                viewportHeightPx = 2000,
            ),
        )
    }

    @Test
    fun `位置真的动了或换了资源就不衔接`() {
        assertEquals(
            0,
            readerScrollChainStep(
                beforeHref = "ch1.xhtml",
                beforeProgression = 0.10,
                afterHref = "ch1.xhtml",
                afterProgression = 0.35,
                dragDyPx = -600f,
                viewportHeightPx = 2000,
            ),
        )
        assertEquals(
            0,
            readerScrollChainStep(
                beforeHref = "ch1.xhtml",
                beforeProgression = 0.44,
                afterHref = "ch2.xhtml",
                afterProgression = 0.0,
                dragDyPx = -600f,
                viewportHeightPx = 2000,
            ),
        )
    }

    @Test
    fun `位移不足或高度非法时不衔接`() {
        assertEquals(
            0,
            readerScrollChainStep(
                beforeHref = "ch1.xhtml",
                beforeProgression = 0.44,
                afterHref = "ch1.xhtml",
                afterProgression = 0.44,
                dragDyPx = -100f,
                viewportHeightPx = 2000,
            ),
        )
        assertEquals(
            0,
            readerScrollChainStep(
                beforeHref = "ch1.xhtml",
                beforeProgression = 0.44,
                afterHref = "ch1.xhtml",
                afterProgression = 0.44,
                dragDyPx = -600f,
                viewportHeightPx = 0,
            ),
        )
    }
}
