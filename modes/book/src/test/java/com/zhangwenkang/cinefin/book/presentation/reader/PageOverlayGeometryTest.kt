package com.zhangwenkang.cinefin.book.presentation.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PageOverlayGeometryTest {

    @Test
    fun `Fit 落位与 Image 的 ContentScale 一致`() {
        // 1000×2000 的页槽放 500×1000 的位图：等比铺满高度，左右留边。
        val wide = fittedImageRect(1000f, 2000f, 500f, 1000f)
        assertEquals(0f, wide.left, 0.001f)
        assertEquals(1000f, wide.width, 0.001f)
        assertEquals(2000f, wide.height, 0.001f)

        // 1000×1000 的页槽放 500×1000 的竖版位图：高度铺满，居中留左右边。
        val tall = fittedImageRect(1000f, 1000f, 500f, 1000f)
        assertEquals(250f, tall.left, 0.001f)
        assertEquals(500f, tall.width, 0.001f)
        assertEquals(0f, tall.top, 0.001f)
        assertEquals(1000f, tall.height, 0.001f)
    }

    @Test
    fun `退化尺寸返回空矩形而不是除零`() {
        val empty = fittedImageRect(0f, 100f, 100f, 100f)
        assertEquals(0f, empty.width, 0.001f)
        assertEquals(0f, empty.height, 0.001f)
    }

    @Test
    fun `归一化矩形映射到像素并反向可逆`() {
        val fitted = fittedImageRect(1000f, 2000f, 500f, 1000f)
        val pageRect = PageRect(0.1f, 0.2f, 0.4f, 0.5f)
        val pixel = pageRect.toPixelRect(fitted)
        assertEquals(100f, pixel.left, 0.001f)
        assertEquals(400f, pixel.top, 0.001f)
        assertEquals(400f, pixel.right, 0.001f)
        assertEquals(1000f, pixel.bottom, 0.001f)

        val back =
            pixelRectToPageRect(
                startX = pixel.left,
                startY = pixel.top,
                endX = pixel.right,
                endY = pixel.bottom,
                fitted = fitted,
            )
        assertEquals(0.1f, back!!.left, 0.001f)
        assertEquals(0.4f, back.right, 0.001f)
    }

    @Test
    fun `框选越界被夹取过小框被忽略`() {
        val fitted = fittedImageRect(1000f, 1000f, 1000f, 1000f)
        val outside = pixelRectToPageRect(-100f, -100f, 2000f, 2000f, fitted)!!
        assertEquals(0f, outside.left, 0.001f)
        assertEquals(1f, outside.right, 0.001f)
        assertNull(pixelRectToPageRect(500f, 500f, 501f, 501f, fitted))
    }
}
