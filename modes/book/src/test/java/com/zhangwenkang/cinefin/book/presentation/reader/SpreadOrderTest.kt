package com.zhangwenkang.cinefin.book.presentation.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpreadOrderTest {

    @Test
    fun `分页模式 RTL 不改变槽位`() {
        assertEquals(listOf<Int?>(4), spreadPageSlots(4, 24, 1, rtl = false))
        assertEquals(listOf<Int?>(4), spreadPageSlots(4, 24, 1, rtl = true))
    }

    @Test
    fun `双栏 LTR 左奇右偶`() {
        assertEquals(listOf<Int?>(0, 1), spreadPageSlots(0, 24, 2, rtl = false))
        assertEquals(listOf<Int?>(22, 23), spreadPageSlots(11, 24, 2, rtl = false))
    }

    @Test
    fun `双栏 RTL 右奇左偶`() {
        assertEquals(listOf<Int?>(1, 0), spreadPageSlots(0, 24, 2, rtl = true))
        assertEquals(listOf<Int?>(23, 22), spreadPageSlots(11, 24, 2, rtl = true))
    }

    @Test
    fun `尾页单张时空槽留在对侧`() {
        assertEquals(listOf<Int?>(4, null), spreadPageSlots(2, 5, 2, rtl = false))
        assertEquals(listOf<Int?>(null, 4), spreadPageSlots(2, 5, 2, rtl = true))
    }

    @Test
    fun `越界 spread 与非法参数返回空槽`() {
        assertEquals(listOf<Int?>(null, null), spreadPageSlots(12, 24, 2, rtl = false))
        assertEquals(listOf<Int?>(null, null), spreadPageSlots(-1, 24, 2, rtl = true))
        assertEquals(listOf<Int?>(null, null), spreadPageSlots(0, 0, 2, rtl = false))
        assertEquals(emptyList<Int?>(), spreadPageSlots(0, 24, 0, rtl = false))
    }

    @Test
    fun `spread 总数按页数向上取整`() {
        assertEquals(12, spreadCount(24, 2))
        assertEquals(3, spreadCount(5, 2))
        assertEquals(5, spreadCount(5, 1))
        assertEquals(0, spreadCount(0, 2))
    }

    @Test
    fun `RTL 只作用于横向翻页`() {
        assertTrue(isRtlPaging(ReaderMode.Paged, rtl = true))
        assertTrue(isRtlPaging(ReaderMode.TwoColumn, rtl = true))
        assertFalse(isRtlPaging(ReaderMode.Scroll, rtl = true))
        assertFalse(isRtlPaging(ReaderMode.Paged, rtl = false))
    }

    @Test
    fun `页指示在 RTL 横向模式带右起后缀`() {
        assertEquals("分页 · 1/24 · 右起", pageIndicatorText(ReaderMode.Paged, 0, 24, rtl = true))
        assertEquals(
            "双栏 · 1-2/24 · 右起",
            pageIndicatorText(ReaderMode.TwoColumn, 0, 24, rtl = true),
        )
        assertEquals("滚动 · 1/24", pageIndicatorText(ReaderMode.Scroll, 0, 24, rtl = true))
        assertEquals("分页 · 1/24", pageIndicatorText(ReaderMode.Paged, 0, 24, rtl = false))
        assertEquals("分页 · 0/0 · 右起", pageIndicatorText(ReaderMode.Paged, 0, 0, rtl = true))
    }
}
