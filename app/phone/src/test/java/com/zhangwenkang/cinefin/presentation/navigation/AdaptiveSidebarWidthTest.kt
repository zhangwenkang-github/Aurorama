package com.zhangwenkang.cinefin.presentation.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W65：侧栏宽度自适应（比例 + 夹取）与由此派生的「完整名称优先」尺寸链。
 *
 * 用户 2026-10-04 拍板：侧轨展开 = `clamp(屏宽dp × 30%, 200dp, 240dp)`、手机抽屉 = `clamp(屏宽dp × 55%, 208dp,
 * 280dp)`； 折叠轨恒定 72dp。任务书点名的边界样例：360 / 393 / 600 / 711 / 1000dp。
 */
class AdaptiveSidebarWidthTest {

    @Test
    fun railExpandedWidthFollowsScreenRatio() {
        // Pad 5 约 711dp 宽 → 213.3dp（「名称 + X 项」完整显示的关键样例）。
        assertEquals(213.3f, railExpandedWidthDp(711), 0.01f)
    }

    @Test
    fun railExpandedWidthClampsAtBothBounds() {
        // 360 / 393 / 600dp 都在下界以内（600 × 30% = 180 < 200）→ 夹取到 200dp。
        assertEquals(200f, railExpandedWidthDp(360), 0.001f)
        assertEquals(200f, railExpandedWidthDp(393), 0.001f)
        assertEquals(200f, railExpandedWidthDp(600), 0.001f)
        // 1000 × 30% = 300 > 240 → 封顶 240dp。
        assertEquals(240f, railExpandedWidthDp(1000), 0.001f)
    }

    @Test
    fun drawerWidthFollowsScreenRatioWithClamp() {
        // K60 约 393dp 宽 → 216.15dp（遮挡约一半、文字完整）。
        assertEquals(216.15f, drawerWidthDp(393), 0.01f)
        // 360 × 55% = 198 < 208 → 下界。
        assertEquals(208f, drawerWidthDp(360), 0.001f)
        // 600 / 711 / 1000dp 都超过上界（509.09dp 起封顶）→ 280dp。
        assertEquals(280f, drawerWidthDp(600), 0.001f)
        assertEquals(280f, drawerWidthDp(711), 0.001f)
        assertEquals(280f, drawerWidthDp(1000), 0.001f)
    }

    @Test
    fun railLabelWidthFollowsAdaptiveRailWidth() {
        // 168dp 旧尺寸链保持 68dp（W53B 回归口径）。
        assertEquals(68f, railLibraryLabelWidthDp(168f), 0.001f)
        // Pad 5：213.3 − 2×10 − 16 − 2×14 − 24 − 12 = 113.3dp。
        assertEquals(113.3f, railLibraryLabelWidthDp(railExpandedWidthDp(711)), 0.01f)
    }

    @Test
    fun drawerLabelWidthFollowsAdaptiveDrawerWidth() {
        // 320dp 旧抽屉：320 − 2×12 − 16 − 2×16 − 24 − 16 = 208dp。
        assertEquals(208f, drawerLibraryLabelWidthDp(320f), 0.001f)
        // K60：216.15 − 112 = 104.15dp。
        assertEquals(104.15f, drawerLibraryLabelWidthDp(drawerWidthDp(393)), 0.01f)
    }

    @Test
    fun pad5RailKeepsCountForFourCharLibraryName() {
        // 4 字库名（≈64dp）+ 间距 8dp + 「124 项」（≈36dp）= 108dp ≤ 113.3dp → 名称与项目数都能完整显示。
        val labelWidthDp = railLibraryLabelWidthDp(railExpandedWidthDp(711))
        assertTrue(
            libraryChildCountVisible(
                labelWidthDp = labelWidthDp,
                nameWidthDp = 64f,
                countWidthDp = 36f,
                gapDp = 8f,
            )
        )
    }

    @Test
    fun k60DrawerGivesWayToNameWhenCountDoesNotFit() {
        // K60 抽屉文字可用宽 104.15dp：4 字库名 + 「124 项」放不下 → 省略项目数，保完整库名（名称优先）。
        val labelWidthDp = drawerLibraryLabelWidthDp(drawerWidthDp(393))
        assertFalse(
            libraryChildCountVisible(
                labelWidthDp = labelWidthDp,
                nameWidthDp = 64f,
                countWidthDp = 40f,
                gapDp = 8f,
            )
        )
    }
}
