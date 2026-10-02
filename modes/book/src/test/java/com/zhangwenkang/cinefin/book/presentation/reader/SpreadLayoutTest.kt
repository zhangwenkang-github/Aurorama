package com.zhangwenkang.cinefin.book.presentation.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 双栏「横版整页独占」版式层（W26）：阈值、槽位划分、逻辑页 → 槽位映射与 RTL 视觉顺序。
 *
 * 与 [SpreadMergeTest] 的分工：本层只决定「哪几页进同一个槽位」（先版式）；合并层只在两页槽内判定 （后合并），两页槽不含横版整页是两层之间的不变量。
 */
class SpreadLayoutTest {

    @Test
    fun `横版阈值 1_15：以上独占，非有限与缺失按竖版处理`() {
        assertTrue(isLandscapeFullPage(1.15f))
        assertTrue(isLandscapeFullPage(1.423f))
        assertTrue(isLandscapeFullPage(2.32f))
        assertFalse(isLandscapeFullPage(1.149f))
        assertFalse(isLandscapeFullPage(0.98f))
        assertFalse(isLandscapeFullPage(0.71f))
        assertFalse(isLandscapeFullPage(null))
        assertFalse(isLandscapeFullPage(Float.NaN))
        assertFalse(isLandscapeFullPage(Float.POSITIVE_INFINITY))
    }

    @Test
    fun `全竖版页与 W22 固定两页划分一致`() {
        val slots = twoColumnSlots(List(5) { 0.71f }, pageCount = 5)
        assertEquals(listOf(listOf(0, 1), listOf(2, 3), listOf(4)), slots.map { it.pages })
        assertTrue(slots.none { it.fullscreen })
        // 槽位总数与 W9 的 spreadCount 划分一致
        assertEquals(spreadCount(5, 2), slots.size)
    }

    @Test
    fun `横版整页独占且不与相邻页配对`() {
        val aspects = listOf(0.71f, 0.71f, 1.42f, 0.71f, 0.71f, 0.71f, 1.42f)
        val slots = twoColumnSlots(aspects, pageCount = 7)
        assertEquals(
            listOf(listOf(0, 1), listOf(2), listOf(3, 4), listOf(5), listOf(6)),
            slots.map { it.pages },
        )
        assertEquals(listOf(false, true, false, false, true), slots.map { it.fullscreen })
    }

    @Test
    fun `相邻横版页各自独占`() {
        val slots = twoColumnSlots(listOf(1.42f, 1.42f, 0.71f, 0.71f), pageCount = 4)
        assertEquals(listOf(listOf(0), listOf(1), listOf(2, 3)), slots.map { it.pages })
        assertEquals(listOf(true, true, false), slots.map { it.fullscreen })
    }

    @Test
    fun `尺寸缺失与扫描未完成时保持 W22 配对行为`() {
        val missing = twoColumnSlots(listOf<Float?>(null, null, null), pageCount = 3)
        assertEquals(listOf(listOf(0, 1), listOf(2)), missing.map { it.pages })
        assertTrue(missing.none { it.fullscreen })
        // 扫描未完成（空表）与 W22 完全一致：按空尺寸表走固定两页划分
        assertEquals(missing.map { it.pages }, twoColumnSlots(emptyList(), 3).map { it.pages })
    }

    @Test
    fun `两页槽内不出现横版整页（与合并层的优先级不变量）`() {
        val aspects = listOf(0.71f, 1.42f, 0.71f, 0.71f, 1.42f, 0.99f, 1.10f, 0.71f)
        val slots = twoColumnSlots(aspects, pageCount = aspects.size)
        slots
            .filter { it.pages.size == 2 }
            .forEach { slot ->
                slot.pages.forEach { page ->
                    assertFalse("page=$page", isLandscapeFullPage(aspects[page]))
                }
            }
        // 所有页恰好出现一次且顺序递增（不重排、不丢页）
        assertEquals((0 until aspects.size).toList(), slots.flatMap { it.pages })
    }

    @Test
    fun `逻辑页到槽位索引在重排后保持位置`() {
        val aspects = listOf(0.71f, 0.71f, 1.42f, 0.71f, 0.71f, 0.71f, 1.42f)
        val slots = twoColumnSlots(aspects, pageCount = 7)
        assertEquals(0, slotIndexForPage(slots, 1))
        assertEquals(1, slotIndexForPage(slots, 2))
        assertEquals(2, slotIndexForPage(slots, 3))
        assertEquals(2, slotIndexForPage(slots, 4))
        assertEquals(3, slotIndexForPage(slots, 5))
        assertEquals(4, slotIndexForPage(slots, 6))
        assertEquals(0, slotIndexForPage(emptyList(), 3))
        assertEquals(4, slotIndexForPage(slots, 99))
    }

    @Test
    fun `视觉槽位顺序与 W9 语义一致`() {
        val spread = SpreadSlot(listOf(4, 5), fullscreen = false)
        assertEquals(listOf<Int?>(4, 5), visualSlotPages(spread, rtl = false))
        assertEquals(listOf<Int?>(5, 4), visualSlotPages(spread, rtl = true))
        val single = SpreadSlot(listOf(6), fullscreen = false)
        assertEquals(listOf<Int?>(6, null), visualSlotPages(single, rtl = false))
        assertEquals(listOf<Int?>(null, 6), visualSlotPages(single, rtl = true))
        val full = SpreadSlot(listOf(7), fullscreen = true)
        assertEquals(listOf<Int?>(7), visualSlotPages(full, rtl = false))
        assertEquals(listOf<Int?>(7), visualSlotPages(full, rtl = true))
    }

    @Test
    fun `分页槽位每页独占一屏`() {
        val slots = pagedSlots(3)
        assertEquals(listOf(listOf(0), listOf(1), listOf(2)), slots.map { it.pages })
        assertTrue(slots.all { it.fullscreen })
        assertTrue(pagedSlots(0).isEmpty())
    }
}
