package com.zhangwenkang.cinefin.music.presentation

import org.junit.Assert.assertEquals
import org.junit.Test

/** W56：迷你播放条「关闭面板」落点（停播 / 只丢展示快照 / 无操作）。 */
class MusicMiniBarRulesTest {

    @Test
    fun liveSessionStopsPlayback() {
        assertEquals(
            MusicMiniBarDismissTarget.StopPlayback,
            musicMiniBarDismissTarget(hasLiveQueue = true, hasRestoredQueue = false),
        )
    }

    @Test
    fun restoredOnlyDropsInMemorySnapshot() {
        assertEquals(
            MusicMiniBarDismissTarget.DropRestoredQueue,
            musicMiniBarDismissTarget(hasLiveQueue = false, hasRestoredQueue = true),
        )
    }

    @Test
    fun noQueueDoesNothing() {
        assertEquals(
            MusicMiniBarDismissTarget.None,
            musicMiniBarDismissTarget(hasLiveQueue = false, hasRestoredQueue = false),
        )
    }

    // ---- W66b：迷你条时间文本（时间永不截断，状态可省略）----

    @Test
    fun timeTextShowsPositionOverDuration() {
        assertEquals("2:31 / 4:56", musicMiniBarTimeText(currentMs = 151_000L, totalMs = 296_000L))
        assertEquals("0:00 / 3:00", musicMiniBarTimeText(currentMs = 0L, totalMs = 180_000L))
    }

    @Test
    fun timeTextFallsBackWhenDurationUnknown() {
        assertEquals("1:05 / --:--", musicMiniBarTimeText(currentMs = 65_000L, totalMs = 0L))
    }

    // ---- W5-T2：左右滑切歌 / 上滑展开 / 边缘安全区 / 进度线 ----

    @Test
    fun swipeNeedsBothDistanceAndVelocity() {
        assertEquals(
            MusicMiniBarSwipe.Next,
            musicMiniBarSwipeTarget(displacementDp = -56f, velocityDpPerSec = -800f),
        )
        assertEquals(
            MusicMiniBarSwipe.Previous,
            musicMiniBarSwipeTarget(displacementDp = 80f, velocityDpPerSec = 1200f),
        )
        // 位移够、速度不够
        assertEquals(null, musicMiniBarSwipeTarget(displacementDp = -70f, velocityDpPerSec = -799f))
        // 速度够、位移不够
        assertEquals(
            null,
            musicMiniBarSwipeTarget(displacementDp = -55f, velocityDpPerSec = -2000f),
        )
    }

    @Test
    fun swipeIgnoresOppositeVelocityDirection() {
        assertEquals(null, musicMiniBarSwipeTarget(displacementDp = -70f, velocityDpPerSec = 900f))
        assertEquals(null, musicMiniBarSwipeTarget(displacementDp = 70f, velocityDpPerSec = -900f))
    }

    @Test
    fun swipeJustBelowThresholdsDoesNotTrigger() {
        assertEquals(
            null,
            musicMiniBarSwipeTarget(displacementDp = -55.9f, velocityDpPerSec = -799.9f),
        )
        assertEquals(
            MusicMiniBarSwipe.Next,
            musicMiniBarSwipeTarget(displacementDp = -56.1f, velocityDpPerSec = -800.1f),
        )
    }

    @Test
    fun upwardSwipeExpandsByDistanceOrVelocity() {
        assertEquals(true, musicMiniBarExpands(dragYDp = -48f, velocityYDpPerSec = 0f))
        assertEquals(true, musicMiniBarExpands(dragYDp = -10f, velocityYDpPerSec = -600f))
        assertEquals(false, musicMiniBarExpands(dragYDp = -47.9f, velocityYDpPerSec = -599.9f))
        assertEquals(false, musicMiniBarExpands(dragYDp = 60f, velocityYDpPerSec = 900f))
    }

    @Test
    fun edgeGuardBlocksSixteenDpOnBothSides() {
        assertEquals(true, musicMiniBarEdgeGuardHit(startXDp = 0f, widthDp = 360f))
        assertEquals(true, musicMiniBarEdgeGuardHit(startXDp = 15.9f, widthDp = 360f))
        assertEquals(false, musicMiniBarEdgeGuardHit(startXDp = 16f, widthDp = 360f))
        assertEquals(false, musicMiniBarEdgeGuardHit(startXDp = 344f, widthDp = 360f))
        assertEquals(true, musicMiniBarEdgeGuardHit(startXDp = 344.1f, widthDp = 360f))
    }

    @Test
    fun progressFractionClampsToRange() {
        assertEquals(
            0.5f,
            musicMiniBarProgressFraction(currentMs = 30_000L, totalMs = 60_000L),
            0.0001f,
        )
        assertEquals(0f, musicMiniBarProgressFraction(currentMs = 30_000L, totalMs = 0L), 0.0001f)
        assertEquals(
            1f,
            musicMiniBarProgressFraction(currentMs = 90_000L, totalMs = 60_000L),
            0.0001f,
        )
        assertEquals(
            0f,
            musicMiniBarProgressFraction(currentMs = -1_000L, totalMs = 60_000L),
            0.0001f,
        )
    }
}
