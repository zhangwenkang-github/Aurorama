package com.zhangwenkang.cinefin.book.presentation.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderBookmarkLabelsTest {
    @Test
    fun `没有章节标题时只用百分比`() {
        assertEquals("12.3%", bookmarkLabel(null, 0.12345))
        assertEquals("0.0%", bookmarkLabel("   ", 0.0))
    }

    @Test
    fun `有章节标题时拼接百分比`() {
        assertEquals("第六章 · 12.3%", bookmarkLabel("第六章", 0.12345))
    }

    @Test
    fun `标题已含百分比时不重复`() {
        assertEquals("12.3%", bookmarkLabel("12.3%", 0.12345))
    }

    @Test
    fun `进度会夹取到 0 到 100 百分比`() {
        assertEquals("100.0%", formatProgressionPercent(1.8))
        assertEquals("0.0%", formatProgressionPercent(-0.4))
        assertEquals("50.0%", formatProgressionPercent(0.5))
    }

    @Test
    fun `下载体积显示`() {
        assertEquals("990 B", formatBookSize(990))
        assertEquals("2 KB", formatBookSize(2_048))
        assertEquals("2.2 MB", formatBookSize(2_300_000))
    }
}
