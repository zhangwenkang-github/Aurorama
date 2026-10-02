package com.zhangwenkang.cinefin.book.presentation.reader

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 跨页对图合并的纯函数层（W22）：几何门槛、双栏槽位相位、内缘取样与中缝判定。
 *
 * 阈值来自本地标定（见 READER_PLAN §7.9）：真对图（人为拆开）相关系数 0.52–0.93，独立页强切 ≤0.74， 真实中缝 / 真实相邻页全部落在门槛之外。
 */
class SpreadMergeTest {

    @Test
    fun `对图几何：两张竖版半页并排命中并钉住长边`() {
        val geometry =
            spreadMergeGeometry(
                PagePairGeometry(widthA = 1000, heightA = 1414, widthB = 1000, heightB = 1414),
                maxSidePx = SPREAD_MERGE_MAX_SIDE_PX,
            )
        assertNotNull(geometry)
        val value = requireNotNull(geometry)
        assertEquals(1.4144f, value.combinedAspect, 0.001f)
        // 目标高 = floor(长边上限 / 整幅宽高比)：合并图宽度不会超过长边上限
        assertEquals(1447, value.targetHeightPx)
        assertTrue(value.targetHeightPx * value.combinedAspect <= SPREAD_MERGE_MAX_SIDE_PX + 1f)
    }

    @Test
    fun `对图几何：宽度略差的两半仍命中（真机素材的封面成对页）`() {
        assertNotNull(
            spreadMergeGeometry(
                PagePairGeometry(widthA = 1031, heightA = 1440, widthB = 1026, heightB = 1440),
                maxSidePx = SPREAD_MERGE_MAX_SIDE_PX,
            )
        )
    }

    @Test
    fun `对图几何：横版整页与不符合对开比例的组合全部否决`() {
        // 横版整页（宽高比 2.0）不是"被拆开的一半"
        assertNull(
            spreadMergeGeometry(
                PagePairGeometry(2000, 1000, 2000, 1000),
                SPREAD_MERGE_MAX_SIDE_PX,
            )
        )
        // 近方形页
        assertNull(
            spreadMergeGeometry(
                PagePairGeometry(1000, 1000, 1000, 1000),
                SPREAD_MERGE_MAX_SIDE_PX,
            )
        )
        // 两半高度差 12%（超过 2% 容差）
        assertNull(
            spreadMergeGeometry(
                PagePairGeometry(1000, 1414, 1000, 1600),
                SPREAD_MERGE_MAX_SIDE_PX,
            )
        )
        // 拼出来太窄（1120 / 1000 = 1.12 < 1.15）
        assertNull(
            spreadMergeGeometry(
                PagePairGeometry(560, 1000, 560, 1000),
                SPREAD_MERGE_MAX_SIDE_PX,
            )
        )
        // 非法参数
        assertNull(spreadMergeGeometry(PagePairGeometry(1000, 1414, 1000, 1414), 0))
        assertNull(spreadMergeGeometry(PagePairGeometry(0, 1414, 1000, 1414), 2048))
        assertNull(spreadMergeGeometry(PagePairGeometry(1000, 1414, 1000, -1), 2048))
    }

    @Test
    fun `拼合高度取目标高与两半自然高的较小值`() {
        val geometry = SpreadMergeGeometry(targetHeightPx = 1447, combinedAspect = 1.414f)
        assertEquals(1447, mergedSpreadHeight(geometry, listOf(1447, 1447)))
        // CBZ 的 2 的幂降采样可能给出更小的图：不放大
        assertEquals(1200, mergedSpreadHeight(geometry, listOf(1447, 1200)))
        assertEquals(1447, mergedSpreadHeight(geometry, listOf(0, 0)))
    }

    @Test
    fun `半页宽度按统一高度缩放并守卫非法参数`() {
        assertEquals(500, scaledHalfWidth(widthPx = 1000, heightPx = 2000, targetHeightPx = 1000))
        assertEquals(0, scaledHalfWidth(widthPx = 0, heightPx = 2000, targetHeightPx = 1000))
        assertEquals(0, scaledHalfWidth(widthPx = 1000, heightPx = 2000, targetHeightPx = 0))
    }

    @Test
    fun `内缘取样取内缘 band 列的逐行平均亮度`() {
        // 8×3：左内缘（第 0/1 列）= 10×行号，右内缘（第 6/7 列）= 200，其余纸白
        val pixels =
            page(width = 8, height = 3) { x, y ->
                when (x) {
                    0,
                    1 -> y * 10
                    6,
                    7 -> 200
                    else -> 255
                }
            }
        val left = spreadEdgeSample(pixels, widthPx = 8, heightPx = 3, edge = SpreadEdge.Left)
        val right = spreadEdgeSample(pixels, widthPx = 8, heightPx = 3, edge = SpreadEdge.Right)
        assertArrayEquals(intArrayOf(0, 10, 20), left.brightness)
        assertArrayEquals(intArrayOf(200, 200, 200), right.brightness)
        assertEquals(8, left.widthPx)
    }

    @Test
    fun `内缘取样对非法尺寸与短像素数组返回空样本`() {
        assertEquals(0, spreadEdgeSample(IntArray(4), 4, 4, SpreadEdge.Left).brightness.size)
        assertEquals(0, spreadEdgeSample(IntArray(16), 0, 4, SpreadEdge.Left).brightness.size)
    }

    @Test
    fun `中缝证据：内容连续时命中（折痕阴影不影响）`() {
        val profile =
            intArrayOf(20, 60, 110, 160, 80, 40, 20, 60, 110, 160, 80, 40, 20, 60, 110, 160)
        val first = SpreadEdgeSample(profile, widthPx = 100)
        // 对页形状一致、整体亮 30（折痕阴影 / 曝光差）
        val second = SpreadEdgeSample(IntArray(profile.size) { (profile[it] + 30) }, widthPx = 100)
        val evidence = spreadSeamEvidence(first, second)
        assertTrue(evidence.continuity >= SPREAD_SEAM_MIN_CONTINUITY)
        assertTrue(evidence.variation >= SPREAD_SEAM_MIN_VARIATION)
        assertEquals(1f, evidence.correlation, 0.001f)
        assertEquals(0f, evidence.difference, 0.001f)
        assertTrue(shouldMergeSpread(evidence))
    }

    @Test
    fun `中缝证据：两张互不相干的页不命中`() {
        val first =
            SpreadEdgeSample(
                intArrayOf(20, 60, 110, 160, 80, 40, 20, 60, 110, 160, 80, 40, 20, 60, 110, 160),
                widthPx = 100,
            )
        val second =
            SpreadEdgeSample(
                intArrayOf(160, 110, 60, 20, 40, 80, 160, 110, 60, 20, 40, 80, 160, 110, 60, 20),
                widthPx = 100,
            )
        val evidence = spreadSeamEvidence(first, second)
        assertTrue(evidence.correlation < SPREAD_SEAM_MIN_CORRELATION)
        assertFalse(shouldMergeSpread(evidence))
    }

    @Test
    fun `中缝证据：纸白中缝与均匀色块都不命中`() {
        // 真实中缝：两侧内缘都是纸白（对图不成立）
        val white =
            spreadSeamEvidence(
                SpreadEdgeSample(IntArray(16) { 250 }, 100),
                SpreadEdgeSample(IntArray(16) { 250 }, 100),
            )
        assertEquals(0f, white.continuity, 0f)
        assertFalse(shouldMergeSpread(white))
        // 均匀深色带：没有起伏，无法确认连续性 → 保守不合并
        val flat =
            spreadSeamEvidence(
                SpreadEdgeSample(IntArray(16) { 90 }, 100),
                SpreadEdgeSample(IntArray(16) { 90 }, 100),
            )
        assertTrue(flat.continuity > 0.9f)
        assertTrue(flat.variation < SPREAD_SEAM_MIN_VARIATION)
        assertFalse(shouldMergeSpread(flat))
    }

    @Test
    fun `中缝证据：行数太少或样本为空判定为不合并`() {
        val empty =
            spreadSeamEvidence(SpreadEdgeSample(IntArray(0), 0), SpreadEdgeSample(IntArray(0), 0))
        assertEquals(0f, empty.continuity, 0f)
        assertFalse(shouldMergeSpread(empty))
        val short =
            spreadSeamEvidence(
                SpreadEdgeSample(IntArray(4) { 50 }, 10),
                SpreadEdgeSample(IntArray(4) { 50 }, 10),
            )
        assertFalse(shouldMergeSpread(short))
    }

    @Test
    fun `判定四条门槛：任一不过都不合并`() {
        val hit =
            SpreadSeamEvidence(
                continuity = 0.5f,
                variation = 40f,
                correlation = 0.9f,
                difference = 0.05f,
            )
        assertTrue(shouldMergeSpread(hit))
        assertFalse(shouldMergeSpread(hit.copy(continuity = SPREAD_SEAM_MIN_CONTINUITY - 0.01f)))
        assertFalse(shouldMergeSpread(hit.copy(variation = SPREAD_SEAM_MIN_VARIATION - 0.1f)))
        assertFalse(shouldMergeSpread(hit.copy(correlation = SPREAD_SEAM_MIN_CORRELATION - 0.01f)))
        assertFalse(shouldMergeSpread(hit.copy(difference = SPREAD_SEAM_MAX_DIFFERENCE + 0.01f)))
    }

    @Test
    fun `双栏槽位相位：LTR 先读在左、RTL 先读在右，与 W9 的槽位层一致`() {
        assertEquals(listOf(0, 1), spreadPieceOrder(rtl = false))
        assertEquals(listOf(1, 0), spreadPieceOrder(rtl = true))
        assertEquals(spreadPageSlots(0, 24, 2, rtl = false), spreadPieceOrder(rtl = false))
        assertEquals(spreadPageSlots(0, 24, 2, rtl = true), spreadPieceOrder(rtl = true))
        assertEquals(SpreadEdge.Right, spreadInnerEdge(slot = 0, rtl = false))
        assertEquals(SpreadEdge.Left, spreadInnerEdge(slot = 1, rtl = false))
        assertEquals(SpreadEdge.Left, spreadInnerEdge(slot = 0, rtl = true))
        assertEquals(SpreadEdge.Right, spreadInnerEdge(slot = 1, rtl = true))
    }

    @Test
    fun `合并只作用于双栏，分页与滚动保持 W4 行为`() {
        assertTrue(spreadMergeEnabled(ReaderMode.TwoColumn))
        assertFalse(spreadMergeEnabled(ReaderMode.Paged))
        assertFalse(spreadMergeEnabled(ReaderMode.Scroll))
    }

    /** 造一张 width×height 的 ARGB 图；亮度通道相同，保证 [spreadEdgeSample] 的亮度换算可预期。 */
    private fun page(width: Int, height: Int, brightness: (x: Int, y: Int) -> Int): IntArray =
        IntArray(width * height) { index -> gray(brightness(index % width, index / width)) }

    private fun gray(value: Int): Int = (0xFF shl 24) or (value shl 16) or (value shl 8) or value
}
