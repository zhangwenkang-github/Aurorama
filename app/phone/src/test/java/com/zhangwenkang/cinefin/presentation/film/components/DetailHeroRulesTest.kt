package com.zhangwenkang.cinefin.presentation.film.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W66 / W66b：`DetailHero` 尺寸纯函数——竖屏海报宽（120–140dp 自适应，约 130dp）与平板基准高度 400dp。
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

    /** 2.0.3-F3：7 键单行放得下（411dp 屏 ≈ 379dp 可用）：47dp；360dp 屏 40dp；再窄保底 40dp（宁可折行）。 */
    @Test
    fun `action key size fits every key on one row`() {
        assertEquals(47f, detailActionKeySizeDp(379f, keyCount = 7), 0.01f)
        assertEquals(40f, detailActionKeySizeDp(328f, keyCount = 7), 0.01f)
        assertEquals(40f, detailActionKeySizeDp(288f, keyCount = 7), 0.01f)
        assertEquals(48f, detailActionKeySizeDp(600f, keyCount = 7), 0.01f)
        assertEquals(44f, detailActionKeySizeDp(600f, keyCount = 7, preferredDp = 44f), 0.01f)
        assertEquals(48f, detailActionKeySizeDp(379f, keyCount = 0), 0.01f)
    }

    @Test
    fun `action key row of n fits the measured width`() {
        val available = 379f
        val keyCount = 7
        val size = detailActionKeySizeDp(available, keyCount)
        val total = size * keyCount + 8f * (keyCount - 1)
        assertTrue("键宽合计 $total 超可用宽 $available", total <= available)
    }

    @Test
    fun `detail body mode prefers content then error then loading`() {
        assertEquals(DetailBodyMode.Content, detailBodyMode(hasContent = true, hasError = true))
        assertEquals(DetailBodyMode.Error, detailBodyMode(hasContent = false, hasError = true))
        assertEquals(DetailBodyMode.Loading, detailBodyMode(hasContent = false, hasError = false))
    }

    /** W76-Q1：可点眉标追加 `›`；不可点（电影 / 节目 / 季页）保持 W66 原文案。 */
    @Test
    fun `clickable eyebrow appends chevron and plain eyebrow stays untouched`() {
        assertEquals("第 1 季 - 第 3 集", heroEyebrowLabel("第 1 季 - 第 3 集", clickable = false))
        assertEquals("第 1 季 - 第 3 集 ›", heroEyebrowLabel("第 1 季 - 第 3 集", clickable = true))
    }
}
