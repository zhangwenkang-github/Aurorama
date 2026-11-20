package com.zhangwenkang.cinefin.music.presentation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W5-T3 音乐动效包的几何 / 阈值数学（MUSIC-21/41/45/46/42）。
 *
 * 参数逐字对齐 `docs/V2_W5_T3_TASKS.md`「设计定稿二」：波形相位与流动方向、拖拽隆起的高斯权重、进度映射与气泡文案、下拉收起阈值与封面缩放 / 圆角、队列落点与左滑阈值。
 */
class MusicMotionMathTest {

    private val tolerance = 0.001f

    @Test
    fun `波形周期对齐 1_5 周每秒`() {
        assertEquals(667, musicWavePeriodMillis())
        assertEquals(1.5f, 1000f / musicWavePeriodMillis(), 0.01f)
    }

    @Test
    fun `相位为零时波长四分之一处为波峰`() {
        val amplitude = MusicWaveAmplitudeDp
        assertEquals(0f, musicWaveOffsetDp(0f, 0f, amplitude), tolerance)
        assertEquals(
            amplitude,
            musicWaveOffsetDp(MusicWaveWavelengthDp / 4f, 0f, amplitude),
            tolerance,
        )
        assertEquals(
            0f,
            musicWaveOffsetDp(MusicWaveWavelengthDp / 2f, 0f, amplitude),
            tolerance,
        )
        assertEquals(
            -amplitude,
            musicWaveOffsetDp(MusicWaveWavelengthDp * 3f / 4f, 0f, amplitude),
            tolerance,
        )
    }

    @Test
    fun `相位递增使波峰向右流动`() {
        // 相位 0.25 周时，波峰从 λ/4 移到 λ/2（右流）
        assertEquals(
            MusicWaveAmplitudeDp,
            musicWaveOffsetDp(MusicWaveWavelengthDp / 2f, 0.25f, MusicWaveAmplitudeDp),
            tolerance,
        )
        assertEquals(
            0f,
            musicWaveOffsetDp(MusicWaveWavelengthDp / 4f, 0.25f, MusicWaveAmplitudeDp),
            tolerance,
        )
    }

    @Test
    fun `振幅为零时波形恒为直线`() {
        for (distance in listOf(0f, 3f, 7f, 21f, 40f)) {
            assertEquals(0f, musicWaveOffsetDp(distance, phaseCycles = 0.3f, amplitudeDp = 0f), 0f)
        }
        // 暂停 / 减少动态下即使拖拽也不隆起
        assertEquals(
            0f,
            musicWaveOffsetDp(0f, 0f, amplitudeDp = 0f, bulgeCenterDp = 0f),
            0f,
        )
    }

    @Test
    fun `高斯隆起中心为 1 半径处归零`() {
        assertEquals(1f, musicWaveBulgeWeight(0f), tolerance)
        assertEquals(0f, musicWaveBulgeWeight(MusicWaveBulgeRadiusDp), 0f)
        assertEquals(0f, musicWaveBulgeWeight(MusicWaveBulgeRadiusDp + 1f), 0f)
        // 半半径处约 0.545（截断并归一化后的高斯）
        assertEquals(0.545f, musicWaveBulgeWeight(MusicWaveBulgeRadiusDp / 2f), 0.01f)
        // 单调递减
        assertTrue(musicWaveBulgeWeight(10f) > musicWaveBulgeWeight(30f))
    }

    @Test
    fun `拖拽隆起抬高手指处的振幅`() {
        val plain = musicWaveOffsetDp(7f, 0f, MusicWaveAmplitudeDp)
        val bulged = musicWaveOffsetDp(7f, 0f, MusicWaveAmplitudeDp, bulgeCenterDp = 7f)
        assertEquals(MusicWaveAmplitudeDp + MusicWaveBulgeAmplitudeDp, bulged, tolerance)
        assertTrue(bulged > plain)
        // 隆起半径外与无隆起一致
        assertEquals(
            plain,
            musicWaveOffsetDp(7f, 0f, MusicWaveAmplitudeDp, bulgeCenterDp = 7f + 60f),
            tolerance,
        )
    }

    @Test
    fun `触控位置映射到进度比例并夹住两端`() {
        assertEquals(0f, musicSeekFraction(9f, 9f, 309f), 0f)
        assertEquals(0.5f, musicSeekFraction(159f, 9f, 309f), tolerance)
        assertEquals(0f, musicSeekFraction(-40f, 9f, 309f), 0f)
        assertEquals(1f, musicSeekFraction(400f, 9f, 309f), 0f)
        assertEquals(0f, musicSeekFraction(10f, 20f, 20f), 0f)
    }

    @Test
    fun `拇指位置与进度比例互逆`() {
        val width = 300f
        val radius = 9f
        assertEquals(radius, musicThumbX(width, radius, 0f), tolerance)
        assertEquals(width - radius, musicThumbX(width, radius, 1f), tolerance)
        assertEquals(width / 2f, musicThumbX(width, radius, 0.5f), tolerance)
    }

    @Test
    fun `拖拽气泡文案为正在跳转到 mm_ss`() {
        assertEquals("正在跳转到 0:00", musicSeekBubbleText(0L))
        assertEquals("正在跳转到 1:05", musicSeekBubbleText(65_000L))
        assertEquals("正在跳转到 0:00", musicSeekBubbleText(-5L))
    }

    @Test
    fun `松手后位置追平判定`() {
        assertTrue(musicSeekSettleReached(0.5f, 0.51f))
        assertFalse(musicSeekSettleReached(0.5f, 0.6f))
        assertTrue(musicSeekSettleReached(0.5f, 0.6f, tolerance = 0.2f))
    }

    @Test
    fun `下拉进度按屏高 18% 归一`() {
        val screenHeight = 1000f
        assertEquals(0f, musicPullProgress(0f, screenHeight), 0f)
        assertEquals(0.5f, musicPullProgress(90f, screenHeight), tolerance)
        assertEquals(1f, musicPullProgress(180f, screenHeight), tolerance)
        assertEquals(1f, musicPullProgress(400f, screenHeight), 0f)
        assertEquals(0f, musicPullProgress(-40f, screenHeight), 0f)
        assertEquals(0f, musicPullProgress(40f, 0f), 0f)
    }

    @Test
    fun `下拉收起的拖动与速度双阈值`() {
        val screenHeight = 1000f
        assertTrue(musicPullDismissReached(180f, screenHeight, 0f))
        assertFalse(musicPullDismissReached(179f, screenHeight, 0f))
        assertTrue(
            musicPullDismissReached(
                dragYPx = 40f,
                screenHeightPx = screenHeight,
                velocityDpPerSec = MusicPullDismissVelocityDpPerSec,
            )
        )
        assertFalse(
            musicPullDismissReached(
                dragYPx = 40f,
                screenHeightPx = screenHeight,
                velocityDpPerSec = MusicPullDismissVelocityDpPerSec - 1f,
            )
        )
    }

    @Test
    fun `下拉过程中封面缩放与圆角线性插值`() {
        assertEquals(1f, musicPullCoverScale(0f), 0f)
        assertEquals(MusicPullCoverMinScale, musicPullCoverScale(1f), 0f)
        assertEquals(0.98f, musicPullCoverScale(0.5f), tolerance)
        assertEquals(MusicCoverCornerDp, musicPullCoverCornerDp(0f), 0f)
        assertEquals(MusicPullCoverMaxCornerDp, musicPullCoverCornerDp(1f), 0f)
        assertEquals(26f, musicPullCoverCornerDp(0.5f), tolerance)
        // 越界夹住（2 → 1；-2 → 0）
        assertEquals(MusicPullCoverMinScale, musicPullCoverScale(2f), 0f)
        assertEquals(MusicCoverCornerDp, musicPullCoverCornerDp(-2f), 0f)
    }

    @Test
    fun `队列拖拽落点按行高换算并夹住`() {
        assertEquals(
            5,
            musicQueueDropTarget(3, dragOffsetPx = 72f * 2f, rowHeightPx = 72f, lastIndex = 9),
        )
        assertEquals(
            3,
            musicQueueDropTarget(3, dragOffsetPx = 20f, rowHeightPx = 72f, lastIndex = 9),
        )
        assertEquals(
            0,
            musicQueueDropTarget(1, dragOffsetPx = -500f, rowHeightPx = 72f, lastIndex = 9),
        )
        assertEquals(
            9,
            musicQueueDropTarget(8, dragOffsetPx = 500f, rowHeightPx = 72f, lastIndex = 9),
        )
        assertEquals(
            2,
            musicQueueDropTarget(2, dragOffsetPx = 100f, rowHeightPx = 0f, lastIndex = 9),
        )
    }

    @Test
    fun `队列左滑移除阈值`() {
        assertTrue(musicQueueSwipeRemoves(-72f, 72f))
        assertFalse(musicQueueSwipeRemoves(-71.9f, 72f))
        assertFalse(musicQueueSwipeRemoves(72f, 72f))
    }

    @Test
    fun `动效参数与定稿二一致`() {
        assertEquals(3f, MusicWaveAmplitudeDp, 0f)
        assertEquals(28f, MusicWaveWavelengthDp, 0f)
        assertEquals(1.5f, MusicWaveCyclesPerSecond, 0f)
        assertEquals(48f, MusicWaveBulgeRadiusDp, 0f)
        assertEquals(1.35f, MusicWaveThumbDragScale, 0f)
        assertEquals(12f, MusicSeekBubbleGapDp, 0f)
        assertEquals(0.18f, MusicPullDismissFraction, 0f)
        assertEquals(1200f, MusicPullDismissVelocityDpPerSec, 0f)
        assertEquals(0.96f, MusicPullCoverMinScale, 0f)
        assertEquals(20f, MusicCoverCornerDp, 0f)
        assertEquals(32f, MusicPullCoverMaxCornerDp, 0f)
        assertEquals(0.92f, MusicPlayPulseScale, 0f)
        assertEquals(1.02f, MusicQueueLiftScale, 0f)
        assertEquals(6f, MusicQueueLiftElevationDp, 0f)
        assertEquals(250, MusicCoverCrossfadeMillis)
        assertEquals(14f, MusicCoverSlideDp, 0f)
        assertEquals(150, MusicTitleFadeMillis)
        assertEquals(8f, MusicTitleSlideDp, 0f)
        assertEquals(0.94f, MusicFullScreenExpandScale, 0f)
        assertEquals(0.96f, MusicMiniBarReappearScale, 0f)
        assertEquals(3_000L, MusicQueueSwipeUndoMillis)
    }
}
