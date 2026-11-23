package com.zhangwenkang.cinefin.presentation.search

import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import com.zhangwenkang.cinefin.presentation.film.components.searchBarMeasureConstraints
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W9-T3 回归：搜索页是导航目的地根节点，NavHost 的 `AnimatedContent` 以 `fillMaxSize`（min=max）固定约束测量它。
 *
 * M3 展开态 `SearchBar` 的 `defaultStartHeight` 会被 `constraints.constrainHeight(...)` 钳到固定高度， 结果区
 * maxHeight 变 0（踩坑 55）。这里钉死两点：约束放宽后结果区必须仍有正高度；固定约束下确实是 0。
 */
class SearchBarMeasureConstraintsTest {
    @Test
    fun `导航目的地固定高度约束测量前放宽最小高度`() {
        val destination =
            Constraints(minWidth = 1600, maxWidth = 1600, minHeight = 2560, maxHeight = 2560)

        val relaxed = searchBarMeasureConstraints(destination)

        assertEquals(0, relaxed.minHeight)
        assertEquals(2560, relaxed.maxHeight)
        assertEquals(1600, relaxed.minWidth)
        assertEquals(1600, relaxed.maxWidth)
    }

    @Test
    fun `结果区高度在固定约束下为 0 而放宽后为正`() {
        val destination =
            Constraints(minWidth = 1600, maxWidth = 1600, minHeight = 2560, maxHeight = 2560)

        assertEquals(0, contentMaxHeight(destination))
        assertTrue(contentMaxHeight(searchBarMeasureConstraints(destination)) > 0)
    }

    /** M3 `SearchBarLayout` 的结果区约束：maxHeight − (顶部内边距 + 输入框起始高度 + 底部内边距)。 */
    private fun contentMaxHeight(constraints: Constraints): Int {
        val topPadding = 16 // 8dp
        val bottomPadding = 16 // 8dp
        val startHeight = constraints.constrainHeight(56 * 2) // 56dp 输入框最小高度
        return (constraints.maxHeight - (topPadding + startHeight + bottomPadding)).coerceAtLeast(0)
    }
}
