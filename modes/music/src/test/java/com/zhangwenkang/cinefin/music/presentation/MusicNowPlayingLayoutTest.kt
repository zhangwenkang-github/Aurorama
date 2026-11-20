package com.zhangwenkang.cinefin.music.presentation

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * W5 T1：全屏播放页布局选择与封面尺寸纯函数单测（设计定稿一 §1）。
 *
 * 封面 = `min(宽 × 0.80, 360dp)`（MUSIC-20 需求 78–82% 手机宽）； 布局 = 宽 ≥840dp 分栏、高 <620dp 紧凑两栏（横屏手机 K60
 * 411dp 高）、其余常规单列。
 */
class MusicNowPlayingLayoutTest {

    @Test
    fun phonePortraitCoverIsEightyPercentOfWidth() {
        // K60 竖屏可用宽 411dp：0.80 × 411 = 328.8dp，占屏宽 80%（需求 78–82%）
        val cover = nowPlayingCoverSize(411.dp)
        assertEquals(328.8f, cover.value, 0.01f)
        assertEquals(80f, cover.value / 411f * 100f, 0.01f)
    }

    @Test
    fun coverCapsAt360DpOnTablets() {
        assertEquals(360.dp, nowPlayingCoverSize(1280.dp))
        assertEquals(360.dp, nowPlayingCoverSize(450.dp))
    }

    @Test
    fun wideScreenUsesSplitLayout() {
        assertEquals(PlayerLayoutMode.Wide, playerLayoutMode(width = 840.dp, height = 800.dp))
        assertEquals(PlayerLayoutMode.Wide, playerLayoutMode(width = 1280.dp, height = 800.dp))
    }

    @Test
    fun landscapePhoneKeepsCompactLayout() {
        assertEquals(PlayerLayoutMode.Compact, playerLayoutMode(width = 891.dp, height = 411.dp))
    }

    @Test
    fun phonePortraitUsesRegularLayout() {
        assertEquals(PlayerLayoutMode.Regular, playerLayoutMode(width = 411.dp, height = 891.dp))
        assertEquals(PlayerLayoutMode.Regular, playerLayoutMode(width = 600.dp, height = 900.dp))
    }
}
