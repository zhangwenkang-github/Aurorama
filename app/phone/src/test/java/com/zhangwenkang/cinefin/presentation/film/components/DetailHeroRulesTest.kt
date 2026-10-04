package com.zhangwenkang.cinefin.presentation.film.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W66：`DetailHero` 尺寸纯函数——竖屏海报宽（96–120dp 自适应）与头图基准高度（400 / 300dp）。
 *
 * 头图整体结构（海报 + 标题 + 动作排同区底排）由真机视觉验收；这里钉住尺寸参数不回归。
 */
class DetailHeroRulesTest {

    @Test
    fun `phone poster width clamps between 96 and 120`() {
        assertEquals(96f, detailHeroPosterWidthDp(320f), 0.01f)
        assertEquals(96f, detailHeroPosterWidthDp(360f), 0.01f)
        assertEquals(120f, detailHeroPosterWidthDp(600f), 0.01f)
        assertEquals(120f, detailHeroPosterWidthDp(1280f), 0.01f)
    }

    @Test
    fun `phone poster width scales with screen width inside clamp`() {
        // K60 411dp ≈ 106.9dp；分档之间单调不减。
        assertEquals(106.86f, detailHeroPosterWidthDp(411f), 0.01f)
        assertTrue(detailHeroPosterWidthDp(411f) > detailHeroPosterWidthDp(360f))
        assertTrue(detailHeroPosterWidthDp(560f) > detailHeroPosterWidthDp(411f))
    }

    @Test
    fun `hero min height is 400 on expanded and 300 on compact`() {
        assertEquals(400f, detailHeroMinHeightDp(expanded = true), 0.01f)
        assertEquals(300f, detailHeroMinHeightDp(expanded = false), 0.01f)
    }
}
