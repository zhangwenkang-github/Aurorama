package com.zhangwenkang.cinefin.book.presentation.reader

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BookZoomStateTest {

    @Test
    fun `倍率跨槽共享且翻到新槽平移为零`() {
        val zoom = BookZoomState(newBookKey())
        zoom.registerSize("page-0", IntSize(800, 1000))
        zoom.transform(
            slotKey = "page-0",
            zoomFactor = 2f,
            pan = Offset(120f, -80f),
            focus = Offset.Zero,
        )

        assertEquals(2f, zoom.scale, 0.0001f)
        assertEquals(Offset.Zero, zoom.offsetFor("page-1"))

        zoom.updateViewportFocus("page-1", Offset.Zero)
        zoom.zoomIn()
        assertEquals(2.5f, zoom.scale, 0.0001f)
        assertEquals(Offset.Zero, zoom.offsetFor("page-1"))
    }

    @Test
    fun `snapTo 只清当前槽并拒绝非有限目标`() {
        val zoom = BookZoomState(newBookKey())
        zoom.registerSize("page-0", IntSize(800, 1000))
        zoom.registerSize("page-1", IntSize(800, 1000))
        zoom.transform("page-0", 2f, Offset(100f, 0f), Offset.Zero)
        zoom.transform("page-1", 1f, Offset(50f, 0f), Offset.Zero)

        zoom.snapTo(1.5f, "page-0")
        assertEquals(Offset.Zero, zoom.offsetFor("page-0"))
        assertEquals(50f, zoom.offsetFor("page-1").x, 0.01f)

        val before = zoom.scale
        zoom.snapTo(Float.NaN, "page-0")
        zoom.snapTo(Float.POSITIVE_INFINITY, "page-0")
        assertEquals(before, zoom.scale, 0.0001f)
    }

    @Test
    fun `同书恢复切书一倍且非法会话值不污染`() {
        val book = newBookKey()
        val other = newBookKey()
        BookZoomSession.remember(book, 2f)
        assertEquals(2f, BookZoomState(book).scale, 0.0001f)
        assertEquals(1f, BookZoomState(other).scale, 0.0001f)

        BookZoomSession.remember(other, Float.POSITIVE_INFINITY)
        assertEquals(1f, BookZoomSession.scaleFor(other), 0.0001f)
        BookZoomSession.remember(other, 9f)
        assertEquals(PAGE_MAX_ZOOM, BookZoomSession.scaleFor(other), 0.0001f)
    }

    @Test
    fun `双栏按钮缩放保持当前槽视口焦点`() {
        val zoom = BookZoomState(newBookKey())
        zoom.registerSize(0, IntSize(1200, 1500))
        val focus =
            spreadPageViewportFocus(viewportWidth = 2400, visualIndex = 0, visualCount = 2)!!
        zoom.updateViewportFocus(0, focus)

        val contentBefore = (focus.x - zoom.offsetFor(0).x) / zoom.scale
        zoom.zoomIn()
        val contentAfter = (focus.x - zoom.offsetFor(0).x) / zoom.scale

        assertEquals(contentBefore, contentAfter, 0.01f)
    }

    @Test
    fun `滚动当前页部分可见时按钮缩放焦点不变`() {
        val zoom = BookZoomState(newBookKey())
        zoom.registerSize(3, IntSize(1000, 1000))
        zoom.transform(3, 1.5f, Offset(0f, 40f), Offset.Zero)
        val focus = scrollViewportFocus(viewportHeight = 1500, itemOffset = 1000, itemSize = 1000)!!
        zoom.updateViewportFocus(3, focus)

        val contentBefore = (focus.y - zoom.offsetFor(3).y) / zoom.scale
        zoom.zoomIn()
        val contentAfter = (focus.y - zoom.offsetFor(3).y) / zoom.scale

        assertEquals(contentBefore, contentAfter, 0.01f)
    }

    @Test
    fun `非有限手势与焦点不会污染缩放状态`() {
        val zoom = BookZoomState(newBookKey())
        zoom.registerSize(0, IntSize(800, 1000))
        zoom.transform(0, 2f, Offset(10f, 10f), Offset.Zero)
        val scale = zoom.scale
        val offset = zoom.offsetFor(0)

        zoom.transform(0, Float.NaN, Offset.Zero, Offset.Zero)
        zoom.transform(0, Float.POSITIVE_INFINITY, Offset.Zero, Offset.Zero)
        zoom.transform(0, 1f, Offset(Float.NaN, Float.NaN), Offset(Float.NaN, Float.NaN))

        assertEquals(scale, zoom.scale, 0.0001f)
        assertTrue(zoom.offsetFor(0).x.isFinite())
        assertTrue(zoom.offsetFor(0).y.isFinite())
        val anchored = anchorPageOffset(Offset(10f, 20f), 2f, 3f, Offset(Float.NaN, 0f))
        assertEquals(Offset(10f, 20f), anchored)
        assertTrue(offset.x.isFinite())
    }

    private fun newBookKey(): String = "book-zoom-test-${System.nanoTime()}-${hashCode()}"
}
