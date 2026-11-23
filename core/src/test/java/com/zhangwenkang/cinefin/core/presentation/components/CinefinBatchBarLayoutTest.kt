package com.zhangwenkang.cinefin.core.presentation.components

import com.zhangwenkang.cinefin.core.presentation.theme.CinefinSpacing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W9-T6b：批量条动作行宽度预算（K60 411dp 实测 + 360dp 竖屏等效）。
 *
 * 真机取证 `F:\Develop\code_data\qa\w9-t6\w9-t6-batch-bar-check.md`：411.4dp 页面下计数文本「已选 1 项」61.3dp、
 * 条形容器左右内边距各 16dp、6 动作行布局宽 284dp。360dp 下扣除文本后放不下 6 个 44dp 动作，因此动作行必须横向
 * 滚动（[CinefinBatchBar]）而不是压缩触控面；411dp 下仍一屏放下，视觉与滑动手感不回归。
 *
 * 这里只锁布局口径（44dp 触控面 / 4dp 间距 / 16dp 内边距 / 计数文本实测宽）；滚动本身由真机 dump 取证。
 */
class CinefinBatchBarLayoutTest {

    /** 真机 dump 的「已选 1 项」可见宽：161px ÷ 2.625（420dpi）。 */
    private val countTextWidthDp = 61.3f

    private val barHorizontalPaddingDp = CinefinSpacing.Space4.value * 2
    private val actionTouchTargetDp = CinefinIconButtonSize.value
    private val actionGapDp = CinefinSpacing.Space1.value

    /** 动作行左内缩（滚动视口之外，保证横滚到末尾时与计数文本仍有间隙）。 */
    private val actionRowInsetDp = CinefinSpacing.Space2.value

    private fun actionRowWidthDp(actionCount: Int): Float =
        actionTouchTargetDp * actionCount + actionGapDp * (actionCount - 1)

    private fun actionViewportWidthDp(screenWidthDp: Float): Float =
        screenWidthDp - barHorizontalPaddingDp - countTextWidthDp - actionRowInsetDp

    @Test
    fun `icon actions keep the 44dp touch target and 4dp gap`() {
        assertEquals(44f, actionTouchTargetDp, 0.001f)
        assertEquals(4f, actionGapDp, 0.001f)
        assertEquals(284f, actionRowWidthDp(6), 0.01f)
    }

    @Test
    fun `six actions fit one row at k60 411dp so visuals do not change`() {
        val viewport = actionViewportWidthDp(411.4f)
        assertTrue(
            "411dp 视口 $viewport 应容纳 6 动作 ${actionRowWidthDp(6)}dp（不出现滚动）",
            viewport >= actionRowWidthDp(6),
        )
        // 整条内容（文本 + 内缩 + 动作行 + 内边距）也必须一屏放下。
        val barContent = countTextWidthDp + actionRowInsetDp + actionRowWidthDp(6)
        assertTrue("411dp 条内容 $barContent 应小于可用宽", barContent <= 411.4f - barHorizontalPaddingDp)
    }

    @Test
    fun `six actions overflow 360dp and require horizontal scrolling`() {
        val viewport = actionViewportWidthDp(360f)
        val required = actionRowWidthDp(6)
        assertTrue("360dp 视口 $viewport 必须放不下 6 动作 $required dp", viewport < required)
        // 溢出量即为需要横滚的距离；真机等效与证据一致（17–25dp 量级）。
        assertEquals(25.3f, required - viewport, 0.05f)
        assertTrue("溢出量必须小于一个滚动屏，横滚一次即可看到末按钮", required - viewport < viewport)
    }

    @Test
    fun `fewer than six actions stay below the 360dp budget`() {
        // 下载页 / 书籍 / 视频（1–5 个动作）在 360dp 下不应触发滚动：只有 6 动作歌单条需要横滚。
        for (count in 1..5) {
            assertTrue(
                "$count 动作 ${actionRowWidthDp(count)}dp 应容纳在 360dp 视口 ${actionViewportWidthDp(360f)} 内",
                actionRowWidthDp(count) <= actionViewportWidthDp(360f),
            )
        }
    }
}
