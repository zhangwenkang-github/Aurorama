package com.zhangwenkang.cinefin.presentation.film.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W66 / W66b：`DetailHero` 尺寸纯函数——竖屏海报宽（120–140dp 自适应，约 130dp）、平板基准高度 400dp 与 hero 动作区降级阈值（<360dp）。
 *
 * 头图整体结构（居中海报 + 标题块 + 一行四键）由真机视觉验收；这里钉住尺寸参数不回归。
 */
class DetailHeroRulesTest {

    @Test
    fun `phone poster width clamps between 120 and 140`() {
        assertEquals(120f, detailHeroPosterWidthDp(320f), 0.01f)
        assertEquals(120f, detailHeroPosterWidthDp(360f), 0.01f)
        assertEquals(140f, detailHeroPosterWidthDp(600f), 0.01f)
        assertEquals(140f, detailHeroPosterWidthDp(1280f), 0.01f)
    }

    @Test
    fun `phone poster width scales with screen width inside clamp`() {
        // K60 411dp ≈ 131.5dp（约 130dp）；分档之间单调不减。
        assertEquals(131.52f, detailHeroPosterWidthDp(411f), 0.01f)
        assertTrue(detailHeroPosterWidthDp(411f) > detailHeroPosterWidthDp(360f))
        assertTrue(detailHeroPosterWidthDp(560f) >= detailHeroPosterWidthDp(411f))
    }

    @Test
    fun `hero min height is 400 on expanded and 300 on compact`() {
        assertEquals(400f, detailHeroMinHeightDp(expanded = true), 0.01f)
        assertEquals(300f, detailHeroMinHeightDp(expanded = false), 0.01f)
    }

    @Test
    fun `hero actions degrade below 360dp`() {
        assertTrue(detailHeroActionsDegraded(320f))
        assertTrue(detailHeroActionsDegraded(359.9f))
        assertFalse(detailHeroActionsDegraded(360f))
        assertFalse(detailHeroActionsDegraded(411f))
    }
}
