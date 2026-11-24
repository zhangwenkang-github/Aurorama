package com.zhangwenkang.cinefin.settings.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** W1-T04：图片缓存默认无限制（0 = 不设上限）。 */
class ImageCacheSizeTest {
    @Test
    fun `默认上限为 0（不设上限）`() {
        assertEquals(0, ImageCacheSize.DEFAULT_MB)
    }

    @Test
    fun `0 与负值不设上限`() {
        assertNull(ImageCacheSize.maxDiskCacheBytes(0))
        assertNull(ImageCacheSize.maxDiskCacheBytes(-1))
    }

    @Test
    fun `正数按 MB 换算字节`() {
        assertEquals(1024L * 1024, ImageCacheSize.maxDiskCacheBytes(1))
        assertEquals(50L * 1024 * 1024, ImageCacheSize.maxDiskCacheBytes(50))
    }
}
