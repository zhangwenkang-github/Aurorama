package com.zhangwenkang.cinefin.repository

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataPreloaderTest {

    @Test
    fun `page prefetch accepts only one bounded visible page`() {
        assertTrue(MetadataPreloader.allowsPagePrefetch(startIndex = 0, limit = 30))
        assertTrue(MetadataPreloader.allowsPagePrefetch(startIndex = 30, limit = 10))
        assertFalse(MetadataPreloader.allowsPagePrefetch(startIndex = -1, limit = 10))
        assertFalse(MetadataPreloader.allowsPagePrefetch(startIndex = 60, limit = 10))
        assertFalse(MetadataPreloader.allowsPagePrefetch(startIndex = 0, limit = 0))
        assertFalse(MetadataPreloader.allowsPagePrefetch(startIndex = 0, limit = 1000))
    }
}
