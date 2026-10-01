package com.zhangwenkang.cinefin.presentation.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 播放页控件分布与流光进度条的回归测试（W11 反馈①③④⑧）。
 *
 * 两条约束容易在后续会话里被改回去，所以钉成断言：
 * 1. 右上工具簇在窄屏必须收成纯图标、并把「播放设置」下移到左下工具行（否则手机顶栏放不下 / 键被挤出屏幕）；
 * 2. 已播段渐变里辅光蓝的占比必须 <10%（A 稿：辅光蓝只做渐变辅助）。
 */
class PlayerControlLayoutTest {

    @Test
    fun wideLayout_keepsSettingsInTopClusterWithTextLabels() {
        val spec = playerControlSpec(800f)

        assertFalse("宽屏右上工具簇带文字标签", spec.compactTools)
        assertFalse("宽屏把播放设置留在右上工具簇", spec.settingsInBottomRow)
        assertEquals(48f, spec.toolKeySizeDp, 0.001f)
    }

    @Test
    fun narrowLayout_movesSettingsToBottomRowAndShrinksKeys() {
        // 411dp ≈ Pad 5 手机形态 / K60 竖屏
        val spec = playerControlSpec(411f)

        assertTrue("窄屏工具簇退化成纯图标", spec.compactTools)
        assertTrue("窄屏把播放设置下移到左下工具行", spec.settingsInBottomRow)
        assertEquals(42f, spec.toolKeySizeDp, 0.001f)
        assertTrue("窄屏留白也要收一档", spec.toolRowPaddingDp < 20f)
    }

    @Test
    fun thresholdAtSixHundredDp_switchesToCompactTools() {
        assertTrue(playerControlSpec(599.9f).compactTools)
        assertFalse(playerControlSpec(600f).compactTools)
    }

    @Test
    fun progressGradient_keepsAccentSecondaryUnderTenPercent() {
        val stops = playerProgressGradientStops()

        assertEquals("色标从 0 开始", 0f, stops.first(), 0.0001f)
        assertEquals("色标到 1 结束", 1f, stops.last(), 0.0001f)
        assertStopsSorted(stops)
        // 浮点减法会带误差（1f - 0.9f = 0.100000024），所以直接卡起点位置
        val secondaryStart = stops[stops.size - 2]
        assertTrue("辅光蓝起点必须 ≥0.9（占比 <10%），实际 $secondaryStart", secondaryStart >= 0.9f - 1e-4f)
        assertEquals(PLAYER_PROGRESS_ACCENT_END, secondaryStart, 0.0001f)
    }

    private fun assertStopsSorted(stops: List<Float>) {
        stops.zipWithNext { left, right -> assertTrue("色标必须单调递增：$left -> $right", left < right) }
    }

    @Test
    fun backKey_closesPanelBeforeLeavingPlayer() {
        assertEquals(
            "面板打开时必须先关面板，而不是退出播放",
            PlayerBackAction.ClosePanel,
            resolvePlayerBack(panelOpen = true, hasParentPanel = false),
        )
    }

    @Test
    fun backKey_subPanelGoesBackToParentPanel() {
        assertEquals(
            "子面板先回上一级（与抽屉返回箭头一致）",
            PlayerBackAction.BackToParentPanel,
            resolvePlayerBack(panelOpen = true, hasParentPanel = true),
        )
    }

    @Test
    fun backKey_withoutPanelFallsThroughToSystem() {
        assertEquals(
            "没有面板时返回键交回系统（真正退出播放页）",
            PlayerBackAction.Ignore,
            resolvePlayerBack(panelOpen = false, hasParentPanel = false),
        )
        assertEquals(
            "没有面板时即使残留上一级标记也不拦截",
            PlayerBackAction.Ignore,
            resolvePlayerBack(panelOpen = false, hasParentPanel = true),
        )
    }

    @Test
    fun backKey_closesSidePanelBeforeLeavingPlayer() {
        assertEquals(
            "覆盖层选集栏打开时要先收栏，不能退出播放页（W12 反馈 C）",
            PlayerBackAction.CloseSidePanel,
            resolvePlayerBack(panelOpen = false, hasParentPanel = false, sidePanelOpen = true),
        )
    }

    @Test
    fun backKey_prefersDrawerPanelOverSidePanel() {
        assertEquals(
            "抽屉面板与选集栏同时开着：先关抽屉面板",
            PlayerBackAction.ClosePanel,
            resolvePlayerBack(panelOpen = true, hasParentPanel = false, sidePanelOpen = true),
        )
    }

    @Test
    fun centerCluster_neverOverlapsLockKey() {
        // 锁定键 = 48dp 键 + 右侧 12dp 留白，固定在画面区右缘垂直居中；
        // 中央簇居中排布，因此不重叠条件是：簇宽 ≤ 画面区宽 − 2×(48+12)
        val lockReserve = 2f * (48f + 12f)
        listOf(280f, 305f, 320f, 360f, 411f, 600f, 800f, 1280f).forEach { width ->
            val cluster = playerCenterSpec(width).totalWidthDp
            assertTrue(
                "画面区 $width dp 时中央簇 $cluster dp 会与右缘锁定键重叠",
                cluster <= width - lockReserve,
            )
        }
    }

    @Test
    fun centerCluster_keepsPlayKeyLargest() {
        listOf(280f, 360f, 420f, 800f).forEach { width ->
            val spec = playerCenterSpec(width)
            assertTrue(
                "画面区 $width dp：主播放键必须仍是簇里最大的键",
                spec.playSizeDp > spec.transportSizeDp,
            )
        }
    }
}
