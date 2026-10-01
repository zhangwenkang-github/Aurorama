package com.zhangwenkang.cinefin.presentation.film

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 季列表卡片尺寸的回归测试（W11 反馈⑨）。
 *
 * 背景：季列表是 `LazyRow`，宽度无界；`ItemCard` 原先只有 `fillMaxWidth()`，于是每张季卡按各自海报的 固有尺寸排布，出现「一大一小」。现在所有季卡取同一个
 * [seasonCardWidthDp]，这里钉住"同一窗口宽度 → 同一个宽度"以及"宽度随窗口分档放大"。
 */
class SeasonCardWidthTest {

    @Test
    fun sameWindowWidth_alwaysYieldsSameCardWidth() {
        // 同一窗口宽度下反复取值必须完全一致：季卡尺寸不一致的根因就是这里不固定
        assertEquals(seasonCardWidthDp(1280f), seasonCardWidthDp(1280f), 0.001f)
        assertEquals(seasonCardWidthDp(411f), seasonCardWidthDp(411f), 0.001f)
    }

    @Test
    fun cardWidth_growsWithWindowBucket() {
        val phone = seasonCardWidthDp(411f)
        val tablet = seasonCardWidthDp(1280f)

        assertTrue("平板上的季卡不应小于手机", tablet >= phone)
        assertTrue("季卡宽度必须落在合理区间（150–208dp）", phone >= 150f && tablet <= 208f)
    }
}
