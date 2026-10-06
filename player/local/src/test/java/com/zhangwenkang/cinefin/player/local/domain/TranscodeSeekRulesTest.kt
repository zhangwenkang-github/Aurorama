package com.zhangwenkang.cinefin.player.local.domain

import androidx.media3.common.C
import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscodeSeekRulesTest {

    @Test
    fun `m3u8 地址识别为转码流`() {
        assertTrue(isTranscodeStreamUri("https://example/Videos/abc/master.m3u8?MediaSourceId=abc"))
        assertFalse(isTranscodeStreamUri("https://example/Videos/abc/stream?static=true"))
        assertFalse(isTranscodeStreamUri("content://local/video.mp4"))
    }

    @Test
    fun `直放与 mpv 不做转码重开会话`() {
        assertFalse(
            shouldRestartTranscodeSession(
                isTranscodeStream = false,
                isExoPlayer = true,
                targetMs = 900_000L,
                currentPositionMs = 10_000L,
                bufferedPositionMs = 30_000L,
                sessionStartMs = 0L,
            )
        )
        assertFalse(
            shouldRestartTranscodeSession(
                isTranscodeStream = true,
                isExoPlayer = false,
                targetMs = 900_000L,
                currentPositionMs = 10_000L,
                bufferedPositionMs = 30_000L,
                sessionStartMs = 0L,
            )
        )
    }

    @Test
    fun `目标在已缓冲窗口内直接落点`() {
        assertFalse(
            shouldRestartTranscodeSession(
                isTranscodeStream = true,
                isExoPlayer = true,
                targetMs = 40_000L,
                currentPositionMs = 30_000L,
                bufferedPositionMs = 60_000L,
                sessionStartMs = 0L,
            )
        )
    }

    @Test
    fun `目标超出缓冲末尾安全余量时重开会话`() {
        assertTrue(
            shouldRestartTranscodeSession(
                isTranscodeStream = true,
                isExoPlayer = true,
                targetMs = 848_000L,
                currentPositionMs = 60_000L,
                bufferedPositionMs = 90_000L,
                sessionStartMs = 0L,
            )
        )
    }

    @Test
    fun `目标早于本次转码会话起点时重开会话`() {
        assertTrue(
            shouldRestartTranscodeSession(
                isTranscodeStream = true,
                isExoPlayer = true,
                targetMs = 100_000L,
                currentPositionMs = 320_000L,
                bufferedPositionMs = 400_000L,
                sessionStartMs = 300_000L,
            )
        )
    }

    @Test
    fun `缓冲信息未知时按当前播放位置粗判`() {
        assertFalse(
            shouldRestartTranscodeSession(
                isTranscodeStream = true,
                isExoPlayer = true,
                targetMs = 20_000L,
                currentPositionMs = 10_000L,
                bufferedPositionMs = C.TIME_UNSET,
                sessionStartMs = 0L,
            )
        )
        assertTrue(
            shouldRestartTranscodeSession(
                isTranscodeStream = true,
                isExoPlayer = true,
                targetMs = 120_000L,
                currentPositionMs = 10_000L,
                bufferedPositionMs = C.TIME_UNSET,
                sessionStartMs = 0L,
            )
        )
    }

    @Test
    fun `未挂载或时长未知时不就绪`() {
        assertFalse(
            isSeekRequestReady(
                playerAttached = false,
                durationMs = 100_000L,
                playbackState = Player.STATE_READY,
                currentPositionMs = 10_000L,
            )
        )
        assertFalse(
            isSeekRequestReady(
                playerAttached = true,
                durationMs = C.TIME_UNSET,
                playbackState = Player.STATE_BUFFERING,
                currentPositionMs = 10_000L,
            )
        )
        assertFalse(
            isSeekRequestReady(
                playerAttached = true,
                durationMs = 100_000L,
                playbackState = Player.STATE_IDLE,
                currentPositionMs = 0L,
            )
        )
    }

    @Test
    fun `时长已知且位置可信时就绪`() {
        assertTrue(
            isSeekRequestReady(
                playerAttached = true,
                durationMs = 100_000L,
                playbackState = Player.STATE_BUFFERING,
                currentPositionMs = 10_000L,
            )
        )
        assertTrue(
            isSeekRequestReady(
                playerAttached = true,
                durationMs = 100_000L,
                playbackState = Player.STATE_READY,
                currentPositionMs = 0L,
            )
        )
    }

    @Test
    fun `时长未知时排队比例并换算落点`() {
        assertEquals(0.5f, pendingSeekFraction(0.5f, C.TIME_UNSET))
        assertEquals(0.5f, pendingSeekFraction(0.5f, 0L))
        assertNull(pendingSeekFraction(0.5f, 100_000L))
        assertEquals(50_000L, seekTargetFromFraction(0.5f, 100_000L))
        assertEquals(100_000L, seekTargetFromFraction(1.5f, 100_000L))
        assertEquals(0L, seekTargetFromFraction(-0.5f, 100_000L))
    }
}
