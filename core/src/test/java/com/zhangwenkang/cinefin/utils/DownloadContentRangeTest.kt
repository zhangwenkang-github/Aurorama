package com.zhangwenkang.cinefin.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** W50 `Content-Range` 解析纯函数单测（206 续传起点 / 总长与 416 提示）。 */
class DownloadContentRangeTest {

    @Test
    fun `解析206起点与总长`() {
        assertEquals(1_000L, parseContentRangeStart("bytes 1000-1999/5000"))
        assertEquals(5_000L, parseContentRangeTotal("bytes 1000-1999/5000"))
    }

    @Test
    fun `解析416未知总长形式`() {
        assertNull(parseContentRangeStart("bytes */5000"))
        assertEquals(5_000L, parseContentRangeTotal("bytes */5000"))
    }

    @Test
    fun `未知总长与非法头返回空`() {
        assertNull(parseContentRangeTotal("bytes 0-99/*"))
        assertNull(parseContentRangeStart(null))
        assertNull(parseContentRangeStart("garbage"))
        assertNull(parseContentRangeTotal("garbage"))
        assertNull(parseContentRangeTotal(null))
    }
}
