package com.zhangwenkang.cinefin.player.local.domain

import com.zhangwenkang.cinefin.player.core.domain.models.TrickplayInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** W20（§1.11）：Trickplay 按需加载的索引换算 / 缓存 / 请求去重。 */
class TrickplayPreviewTest {

    /** 5×5 = 25 张缩略图一张精灵图，共 26 张 → 两张图（最后一张只有 1 张缩略图） */
    private val info =
        TrickplayInfo(
            width = 160,
            height = 90,
            tileWidth = 5,
            tileHeight = 5,
            thumbnailCount = 26,
            interval = 10_000,
            bandwidth = 0,
        )

    @Test
    fun `sheetSize and sheetCount handle partial last sheet`() {
        assertEquals(25, TrickplayTiles.sheetSize(info))
        assertEquals(26, TrickplayTiles.tileCount(info))
        assertEquals(2, TrickplayTiles.sheetCount(info))
        assertEquals(1, TrickplayTiles.sheetCount(info.copy(thumbnailCount = 25)))
        assertEquals(0, TrickplayTiles.sheetCount(info.copy(thumbnailCount = 0)))
    }

    @Test
    fun `sheetOf maps tile index to sheet and clamps beyond total`() {
        assertEquals(0, TrickplayTiles.sheetOf(0, info))
        assertEquals(0, TrickplayTiles.sheetOf(24, info))
        assertEquals(1, TrickplayTiles.sheetOf(25, info))
        // 超出总数：收敛到最后一张缩略图，而不是请求不存在的图
        assertEquals(1, TrickplayTiles.sheetOf(999, info))
        // 负值：收敛到 0
        assertEquals(0, TrickplayTiles.sheetOf(-3, info))
    }

    @Test
    fun `sheet tile range covers partial last sheet`() {
        assertEquals(0, TrickplayTiles.firstTileOfSheet(0, info))
        assertEquals(24, TrickplayTiles.lastTileOfSheet(0, info))
        assertEquals(25, TrickplayTiles.firstTileOfSheet(1, info))
        assertEquals(25, TrickplayTiles.lastTileOfSheet(1, info))
    }

    @Test
    fun `tileIndexAt converts position with interval and clamps`() {
        assertEquals(0, TrickplayTiles.tileIndexAt(0L, info))
        assertEquals(2, TrickplayTiles.tileIndexAt(25_000L, info))
        assertEquals(0, TrickplayTiles.tileIndexAt(-5_000L, info))
        assertEquals(25, TrickplayTiles.tileIndexAt(10_000_000L, info))
        // interval 非法（0）：按 1ms 处理，不抛异常
        assertEquals(25, TrickplayTiles.tileIndexAt(1_000_000L, info.copy(interval = 0)))
        // 没有 trickplay：永远 0
        assertEquals(0, TrickplayTiles.tileIndexAt(1_000_000L, info.copy(thumbnailCount = 0)))
    }

    @Test
    fun `sheet cache keeps only recent sheets`() {
        val cache = TrickplaySheetCache<String>(capacity = 2)
        cache.put(0, listOf("a"))
        cache.put(1, listOf("b"))
        assertEquals("a", cache.get(0)?.first())
        cache.put(2, listOf("c"))
        // get(0) 刚访问过 → 淘汰的是 1
        assertTrue(cache.contains(0))
        assertFalse(cache.contains(1))
        assertTrue(cache.contains(2))
        assertEquals(2, cache.size)
        cache.clear()
        assertEquals(0, cache.size)
        assertNull(cache.get(0))
    }

    @Test
    fun `request state dedupes in-flight loaded and failed sheets`() {
        val state = TrickplayRequestState()
        assertTrue(state.canRequest(0))
        state.markInFlight(0)
        // 同一张图拖动期间会被反复请求：in-flight 时不再重复发
        assertFalse(state.canRequest(0))
        assertTrue(state.isInFlight(0))
        state.markLoaded(0)
        assertTrue(state.isLoaded(0))
        assertFalse(state.canRequest(0))

        assertTrue(state.canRequest(1))
        state.markInFlight(1)
        state.markFailed(1)
        // 失败后不再重试（否则拖动 = 重试风暴）
        assertTrue(state.isFailed(1))
        assertFalse(state.canRequest(1))
    }
}
