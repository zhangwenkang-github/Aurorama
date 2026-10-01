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
}
