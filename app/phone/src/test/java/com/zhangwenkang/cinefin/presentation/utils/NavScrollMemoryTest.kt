package com.zhangwenkang.cinefin.presentation.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** W75 #5：详情 / 列表页滚动位置的记忆表（按键取回、LRU 有界、无记录时回落 0）。 */
class NavScrollMemoryTest {

    @Test
    fun scrollOffsetRoundTripsPerKey() {
        val memory = NavScrollMemory()
        memory.rememberScrollOffset("entry:1", 1_234)
        memory.rememberScrollOffset("entry:2", 42)

        assertEquals(1_234, memory.scrollOffset("entry:1"))
        assertEquals(42, memory.scrollOffset("entry:2"))
    }

    @Test
    fun unknownKeyFallsBackToTop() {
        val memory = NavScrollMemory()
        assertEquals(0, memory.scrollOffset("entry:missing"))
        assertNull(memory.lazyPosition("entry:missing"))
    }

    @Test
    fun lazyPositionRoundTrips() {
        val memory = NavScrollMemory()
        memory.rememberLazyPosition("entry:season", index = 7, offset = 128)

        assertEquals(7 to 128, memory.lazyPosition("entry:season"))
    }

    @Test
    fun laterValueWinsForSameKey() {
        val memory = NavScrollMemory()
        memory.rememberScrollOffset("entry:1", 100)
        memory.rememberScrollOffset("entry:1", 900)

        assertEquals(900, memory.scrollOffset("entry:1"))
    }

    @Test
    fun memoryIsBoundedAndDropsLeastRecentlyUsed() {
        val memory = NavScrollMemory(maxEntries = 3)
        memory.rememberScrollOffset("a", 1)
        memory.rememberScrollOffset("b", 2)
        memory.rememberScrollOffset("c", 3)
        // 触碰 a → a 变成最近使用；再写入 d 时被淘汰的应是最久未用的 b。
        memory.rememberScrollOffset("a", 1)
        memory.rememberScrollOffset("d", 4)

        assertEquals(1, memory.scrollOffset("a"))
        assertEquals(0, memory.scrollOffset("b"))
        assertEquals(3, memory.scrollOffset("c"))
        assertEquals(4, memory.scrollOffset("d"))
    }

    @Test
    fun clearDropsEverything() {
        val memory = NavScrollMemory()
        memory.rememberScrollOffset("entry:1", 5)
        memory.rememberLazyPosition("entry:1", 1, 2)
        memory.clear()

        assertEquals(0, memory.scrollOffset("entry:1"))
        assertNull(memory.lazyPosition("entry:1"))
    }

    @Test
    fun navScrollKeyPrefersBackStackEntryId() {
        // 组合不在 NavHost 内（预览 / 测试宿主）时回落调用方给的稳定键。
        assertEquals("show:abc", navScrollKey(owner = null, fallback = "show:abc"))
        assertEquals("show:abc", navScrollKey(owner = Any(), fallback = "show:abc"))
    }
}
