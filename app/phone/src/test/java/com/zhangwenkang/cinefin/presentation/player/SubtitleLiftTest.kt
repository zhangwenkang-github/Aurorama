package com.zhangwenkang.cinefin.presentation.player

import org.junit.Assert.assertEquals
import org.junit.Test

/** W74 #20：主/次字幕「上下两行」的几何计算单测（次字幕块高度推导 + libass 上移量 clamp）。 */
class SubtitleLiftTest {

    // ---------- 次字幕块高度推导 ----------

    @Test
    fun secondaryBlockHeight_addsTextHeightAndPadding() {
        assertEquals("文本高 + 内边距 = 块高", 70f, secondaryBlockHeightPx(40f, 30f), 0.001f)
        assertEquals("无内边距时即文本高", 40f, secondaryBlockHeightPx(40f, 0f), 0.001f)
    }

    @Test
    fun secondaryBlockHeight_clampsNegativeToZero() {
        assertEquals("负高度 clamp 到 0", 0f, secondaryBlockHeightPx(-10f, 5f), 0.001f)
        assertEquals("负内边距抵消后 clamp 到 0", 0f, secondaryBlockHeightPx(10f, -20f), 0.001f)
    }

    // ---------- libass 上移量 ----------

    @Test
    fun lift_zeroWhenNoSecondaryAndNoGap() {
        assertEquals(0, libassLiftPx(0f, 0f, topmostImageY = null))
    }

    @Test
    fun lift_sumsSecondaryHeightAndGap() {
        assertEquals(110, libassLiftPx(100f, 10f, topmostImageY = null))
    }

    @Test
    fun lift_roundsToNearestPixel() {
        assertEquals(16, libassLiftPx(10.4f, 5.6f, topmostImageY = null))
        assertEquals(0, libassLiftPx(0.2f, 0.2f, topmostImageY = null))
    }

    @Test
    fun lift_clampsToTopmostImageYSoSubtitleStaysInsideVideo() {
        // 次字幕太高时不能把主字幕顶出渲染区顶部
        assertEquals(80, libassLiftPx(100f, 10f, topmostImageY = 80))
        // topmostImageY 足够大时不 clamp
        assertEquals(110, libassLiftPx(100f, 10f, topmostImageY = 200))
    }

    @Test
    fun lift_clampsNegativeTopmostImageYToZero() {
        assertEquals(0, libassLiftPx(100f, 10f, topmostImageY = -5))
    }

    @Test
    fun lift_ignoresClampWhenNoImages() {
        assertEquals(110, libassLiftPx(100f, 10f, topmostImageY = null))
    }
}
