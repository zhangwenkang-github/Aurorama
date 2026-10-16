package com.zhangwenkang.cinefin.presentation.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * W80（B12）：横屏展开侧栏 + 展开「媒体库」时，子库列表限高规则。
 *
 * 判据：空间足够时保持原样（行为不变）；空间不足时给顶层条目（下载等）留位，子库超出部分交给子分组自己滚动。
 */
class RailFitRulesTest {

    @Test
    fun `空间足够时返回全部子行 行为与改动前一致`() {
        // Pad 5 竖屏：可用高度很大，9 个顶层 + 6 个子行都放得下。
        assertEquals(
            6,
            railMediaSubGroupMaxRows(availableHeightDp = 1000, topLevelRows = 9, subRows = 6),
        )
    }

    @Test
    fun `空间不足时子分组被限高 顶层条目优先`() {
        // Pad 5 横屏实测：导航列 ≈ 512dp、9 个顶层条目（52dp/行 ≈ 468dp）→ 只剩 44dp ≈ 0 行。
        val rows = railMediaSubGroupMaxRows(availableHeightDp = 512, topLevelRows = 9, subRows = 8)
        assertEquals(RAIL_MEDIA_SUBGROUP_MIN_ROWS, rows)
    }

    @Test
    fun `可用高度按行数与分隔线折算`() {
        // 8 个顶层（8 + 2 分隔线 = 10 行 = 520dp）+ 余 208dp = 4 行给子库。
        assertEquals(
            4,
            railMediaSubGroupMaxRows(availableHeightDp = 728, topLevelRows = 8, subRows = 6),
        )
    }

    @Test
    fun `边界情况不炸`() {
        assertEquals(
            0,
            railMediaSubGroupMaxRows(availableHeightDp = 600, topLevelRows = 4, subRows = 0),
        )
        // 高度未知（0 / 负值）时不限高：宁可不裁剪，也不要算出 0 行把子库全藏起来。
        assertEquals(
            6,
            railMediaSubGroupMaxRows(availableHeightDp = 0, topLevelRows = 8, subRows = 6),
        )
        assertEquals(
            6,
            railMediaSubGroupMaxRows(availableHeightDp = -10, topLevelRows = 8, subRows = 6),
        )
    }
}
