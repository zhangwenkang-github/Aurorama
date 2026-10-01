package com.zhangwenkang.cinefin.player.local.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerVideoTransformTest {

    @Test
    fun `默认状态没有任何调整`() {
        val transform = PlayerVideoTransform()
        assertFalse(transform.hasAdjustments)
        assertEquals(0, transform.rotationDegrees)
        assertEquals(VideoMirrorMode.OFF, transform.mirror)
    }

    @Test
    fun `任一调整都会标记为已调整`() {
        assertTrue(PlayerVideoTransform(rotationDegrees = 90).hasAdjustments)
        assertTrue(PlayerVideoTransform(mirror = VideoMirrorMode.HORIZONTAL).hasAdjustments)
        assertTrue(PlayerVideoTransform(cropPercent = 10).hasAdjustments)
        assertTrue(PlayerVideoTransform(letterboxCrop = true).hasAdjustments)
        assertFalse(PlayerVideoTransform(rotationDegrees = 360).hasAdjustments)
    }

    @Test
    fun `旋转缩放只在 90 与 270 度时生效`() {
        assertEquals(1f, rotationFillScale(0, 1600f, 900f), 0.0001f)
        assertEquals(1f, rotationFillScale(180, 1600f, 900f), 0.0001f)
        assertEquals(16f / 9f, rotationFillScale(90, 1600f, 900f), 0.0001f)
        assertEquals(16f / 9f, rotationFillScale(270, 1600f, 900f), 0.0001f)
        // 竖屏画面区：比例反过来
        assertEquals(16f / 9f, rotationFillScale(90, 900f, 1600f), 0.0001f)
        // 尺寸非法时退回 1，不产生 NaN
        assertEquals(1f, rotationFillScale(90, 0f, 0f), 0.0001f)
    }

    @Test
    fun `裁剪缩放按四边百分比换算`() {
        assertEquals(1f, cropScale(0), 0.0001f)
        assertEquals(1.25f, cropScale(10), 0.0001f)
        assertEquals(1.6666f, cropScale(20), 0.001f)
        // 越界收敛
        assertEquals(1f, cropScale(-5), 0.0001f)
        assertEquals(1.6666f, cropScale(50), 0.001f)
    }

    @Test
    fun `去黑边倍数按画面区与视频比例计算`() {
        // 16:9 画面区 + 2.39:1 视频 → 左右填满需要放大
        assertEquals(1.344f, letterboxFillScale(1600f, 900f, 1920, 803), 0.01f)
        // 比例一致 → 不需要放大
        assertEquals(1f, letterboxFillScale(1600f, 900f, 1920, 1080), 0.0001f)
        // 竖屏视频放进横屏画面区：填满要放大约 3.16×（左右裁掉很多，属于「铺满」的数学结果）
        assertEquals(3.1605f, letterboxFillScale(1600f, 900f, 1080, 1920), 0.01f)
        // 信息不足 → 不放大
        assertEquals(1f, letterboxFillScale(0f, 900f, 1920, 1080), 0.0001f)
        assertEquals(1f, letterboxFillScale(1600f, 900f, 0, 0), 0.0001f)
    }
}
