package com.zhangwenkang.cinefin.repository

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** W62：媒体库「共 N 个项目」数字口径 + 稳定计数缓存单测。 */
class LibraryItemCountTest {

    @Test
    fun `稳定计数优先于服务器 ChildCount`() {
        assertEquals(17, stableLibraryItemCount(stableCount = 17, childCount = 6))
        assertEquals(0, stableLibraryItemCount(stableCount = 0, childCount = 3))
    }

    @Test
    fun `稳定计数缺失时回退 ChildCount`() {
        assertEquals(6, stableLibraryItemCount(stableCount = null, childCount = 6))
    }

    @Test
    fun `两者都缺失时返回 null 不占位`() {
        assertNull(stableLibraryItemCount(stableCount = null, childCount = null))
    }

    @Test
    fun `负数不参与上屏口径`() {
        assertNull(stableLibraryItemCount(stableCount = -1, childCount = -5))
        assertEquals(4, stableLibraryItemCount(stableCount = -1, childCount = 4))
    }

    @Test
    fun `缓存命中与 TTL 过期`() {
        var clock = 1_000L
        val cache = LibraryItemCountCache(ttlMillis = 60_000L, now = { clock })
        val first = UUID.randomUUID()
        val second = UUID.randomUUID()

        assertNull(cache.get(first))
        cache.put(first, 94)
        cache.put(second, 8)
        assertEquals(94, cache.get(first))
        assertEquals(8, cache.get(second))

        // 到期边界仍算命中，超过 TTL 立刻失效。
        clock += 60_000L
        assertEquals(94, cache.get(first))
        clock += 1L
        assertNull(cache.get(first))
        assertNull(cache.get(second))

        // 重新写入后按新时间戳命中。
        cache.put(second, 8)
        assertEquals(8, cache.get(second))

        cache.clear()
        assertNull(cache.get(second))
    }
}
