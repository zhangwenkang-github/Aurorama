package com.zhangwenkang.cinefin.player.local.presentation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackSessionRulesTest {

    private val key =
        PlaybackSessionKey(
            itemId = "item-a",
            sourceGeneration = 1L,
            sessionGeneration = 1L,
        )

    @Test
    fun `play action follows playWhenReady intent while buffering`() {
        val update =
            PlaybackSessionRules.advance(
                state = PlaybackSessionState(),
                snapshot = snapshot(playWhenReady = true, isPlaying = false, isBuffering = true),
                nowElapsedMs = 0L,
            )

        assertEquals(PlaybackAction.PAUSE, update.projection.playPauseAction)
        assertEquals(
            PlaybackSessionClassification.BUFFERING_OR_LOADING,
            update.projection.classification,
        )
        assertTrue(update.projection.loading)
        assertNull(update.projection.terminal)

        val resumed =
            PlaybackSessionRules.advance(
                state = update.state,
                snapshot =
                    snapshot(
                        playWhenReady = true,
                        isPlaying = false,
                        isBuffering = false,
                        isReady = true,
                    ),
                nowElapsedMs = 1_000L,
            )
        assertEquals(PlaybackAction.PAUSE, resumed.projection.playPauseAction)
        assertTrue(resumed.projection.loading)
    }

    @Test
    fun `explicit pause wins and cancels deadline`() {
        val loading =
            PlaybackSessionRules.advance(
                state = PlaybackSessionState(),
                snapshot = snapshot(playWhenReady = true, isPlaying = false, isBuffering = true),
                nowElapsedMs = 0L,
            )
        val paused =
            PlaybackSessionRules.advance(
                state = loading.state,
                snapshot = snapshot(playWhenReady = false, isPlaying = false, isBuffering = true),
                nowElapsedMs = 10_000L,
            )

        assertEquals(PlaybackSessionClassification.EXPLICIT_PAUSE, paused.projection.classification)
        assertEquals(PlaybackAction.PLAY, paused.projection.playPauseAction)
        assertFalse(paused.projection.loading)
        assertNull(paused.state.lastProgressAtMs)
        assertNull(paused.state.terminal)

        val afterDeadline =
            PlaybackSessionRules.advance(
                state = paused.state,
                snapshot = snapshot(playWhenReady = false, isPlaying = false, isBuffering = true),
                nowElapsedMs = PlaybackSessionRules.STALL_TIMEOUT_MS,
            )
        assertNull(afterDeadline.projection.terminal)
    }

    @Test
    fun `stall boundary is exclusive before twenty seconds and terminal at twenty seconds`() {
        val initial =
            PlaybackSessionRules.advance(
                state = PlaybackSessionState(),
                snapshot = snapshot(playWhenReady = true, isPlaying = false, isBuffering = true),
                nowElapsedMs = 0L,
            )
        val before =
            PlaybackSessionRules.advance(
                state = initial.state,
                snapshot = snapshot(playWhenReady = true, isPlaying = false, isBuffering = true),
                nowElapsedMs = PlaybackSessionRules.STALL_TIMEOUT_MS - 1L,
            )
        assertNull(before.projection.terminal)

        val atBoundary =
            PlaybackSessionRules.advance(
                state = before.state,
                snapshot = snapshot(playWhenReady = true, isPlaying = false, isBuffering = true),
                nowElapsedMs = PlaybackSessionRules.STALL_TIMEOUT_MS,
            )
        assertEquals(
            PlaybackSessionClassification.PLAYBACK_PROGRESS_TIMEOUT,
            atBoundary.projection.classification,
        )
        assertEquals(
            PlaybackSessionClassification.PLAYBACK_PROGRESS_TIMEOUT,
            atBoundary.projection.terminal?.classification,
        )
        assertEquals(
            PlaybackRetryPositionSource.ENGINE_POSITION,
            atBoundary.projection.terminal?.retryPosition?.source,
        )
        assertFalse(atBoundary.projection.loading)
    }

    @Test
    fun `noop-like invalid position produces only progress unconfirmed at deadline`() {
        val initial =
            PlaybackSessionRules.advance(
                state = PlaybackSessionState(),
                snapshot =
                    snapshot(
                        playWhenReady = true,
                        isPlaying = false,
                        isBuffering = false,
                        isReady = false,
                        enginePositionMs = null,
                        bufferedPositionMs = null,
                    ),
                nowElapsedMs = 0L,
            )
        assertNull(initial.projection.terminal)
        val terminal =
            PlaybackSessionRules.advance(
                state = initial.state,
                snapshot =
                    snapshot(
                        playWhenReady = true,
                        isPlaying = false,
                        isBuffering = false,
                        isReady = false,
                        enginePositionMs = null,
                        bufferedPositionMs = null,
                    ),
                nowElapsedMs = PlaybackSessionRules.STALL_TIMEOUT_MS,
            )

        assertEquals(
            PlaybackSessionClassification.PROGRESS_UNCONFIRMED,
            terminal.projection.classification,
        )
        assertEquals(
            PlaybackSessionClassification.PROGRESS_UNCONFIRMED,
            terminal.projection.terminal?.classification,
        )
        assertNull(terminal.projection.terminal?.cause)
    }

    @Test
    fun `engine position change resets deadline`() {
        val initial =
            PlaybackSessionRules.advance(
                state = PlaybackSessionState(),
                snapshot =
                    snapshot(
                        playWhenReady = true,
                        isPlaying = true,
                        isBuffering = false,
                        isReady = true,
                        enginePositionMs = 1_000L,
                        bufferedPositionMs = 10_000L,
                    ),
                nowElapsedMs = 0L,
            )
        val progressed =
            PlaybackSessionRules.advance(
                state = initial.state,
                snapshot =
                    snapshot(
                        playWhenReady = true,
                        isPlaying = true,
                        isBuffering = false,
                        isReady = true,
                        enginePositionMs = 11_000L,
                        bufferedPositionMs = 10_000L,
                    ),
                nowElapsedMs = 10_000L,
            )
        val beforeNewDeadline =
            PlaybackSessionRules.advance(
                state = progressed.state,
                snapshot =
                    snapshot(
                        playWhenReady = true,
                        isPlaying = true,
                        isBuffering = false,
                        isReady = true,
                        enginePositionMs = 11_000L,
                        bufferedPositionMs = 10_000L,
                    ),
                nowElapsedMs = 29_999L,
            )

        assertEquals(10_000L, progressed.state.lastProgressAtMs)
        assertEquals(11_000L, progressed.state.lastEnginePositionMs)
        assertNull(beforeNewDeadline.projection.terminal)
    }

    @Test
    fun `buffered position change resets deadline`() {
        val initial =
            PlaybackSessionRules.advance(
                state = PlaybackSessionState(),
                snapshot = snapshot(bufferedPositionMs = 1_000L),
                nowElapsedMs = 0L,
            )
        val progressed =
            PlaybackSessionRules.advance(
                state = initial.state,
                snapshot = snapshot(bufferedPositionMs = 2_000L),
                nowElapsedMs = 10_000L,
            )
        val beforeNewDeadline =
            PlaybackSessionRules.advance(
                state = progressed.state,
                snapshot = snapshot(bufferedPositionMs = 2_000L),
                nowElapsedMs = 29_999L,
            )

        assertEquals(10_000L, progressed.state.lastProgressAtMs)
        assertEquals(2_000L, progressed.state.lastBufferedPositionMs)
        assertNull(beforeNewDeadline.projection.terminal)
    }

    @Test
    fun `loading to ready transition resets deadline`() {
        val loading =
            PlaybackSessionRules.advance(
                state = PlaybackSessionState(),
                snapshot = snapshot(playWhenReady = true, isPlaying = false, isBuffering = true),
                nowElapsedMs = 0L,
            )
        val ready =
            PlaybackSessionRules.advance(
                state = loading.state,
                snapshot =
                    snapshot(
                        playWhenReady = true,
                        isPlaying = true,
                        isBuffering = false,
                        isReady = true,
                    ),
                nowElapsedMs = 10_000L,
            )
        val beforeNewDeadline =
            PlaybackSessionRules.advance(
                state = ready.state,
                snapshot =
                    snapshot(
                        playWhenReady = true,
                        isPlaying = true,
                        isBuffering = false,
                        isReady = true,
                    ),
                nowElapsedMs = 29_999L,
            )

        assertEquals(PlaybackSessionClassification.READY, ready.projection.classification)
        assertFalse(ready.projection.loading)
        assertEquals(10_000L, ready.state.lastProgressAtMs)
        assertNull(beforeNewDeadline.projection.terminal)
    }

    @Test
    fun `generation change clears terminal and retry token`() {
        val initial =
            PlaybackSessionRules.advance(
                state = PlaybackSessionState(),
                snapshot = snapshot(playWhenReady = true, isPlaying = false, isBuffering = true),
                nowElapsedMs = 0L,
            )
        val terminal =
            PlaybackSessionRules.advance(
                state = initial.state,
                snapshot = snapshot(playWhenReady = true, isPlaying = false, isBuffering = true),
                nowElapsedMs = PlaybackSessionRules.STALL_TIMEOUT_MS,
            )
        assertTrue(terminal.projection.terminal != null)

        val nextKey =
            key.copy(
                itemId = "item-b",
                sourceGeneration = 2L,
                sessionGeneration = 2L,
            )
        val changed =
            PlaybackSessionRules.advance(
                state = terminal.state,
                snapshot = snapshot(key = nextKey, playWhenReady = true, isBuffering = true),
                nowElapsedMs = PlaybackSessionRules.STALL_TIMEOUT_MS + 1L,
            )

        assertNull(changed.projection.terminal)
        assertNull(changed.state.terminal)
        assertFalse(changed.state.retryConsumed)
        assertNull(PlaybackSessionRules.consumeRetry(changed.state).intent)
    }

    @Test
    fun `same terminal retry intent is consumed once`() {
        val terminal = terminalAtDeadline(enginePositionMs = 5_000L)

        val first = PlaybackSessionRules.consumeRetry(terminal.state)
        assertEquals(5_000L, first.intent?.positionMs)
        assertEquals(PlaybackRetryPositionSource.ENGINE_POSITION, first.intent?.source)
        assertTrue(first.state.retryConsumed)

        val duplicate = PlaybackSessionRules.consumeRetry(first.state)
        assertNull(duplicate.intent)
    }

    @Test
    fun `retry position follows frozen source priority`() {
        assertEquals(
            PlaybackRetryPositionSource.ENGINE_POSITION,
            terminalAtDeadline(
                    enginePositionMs = 5_000L,
                    explicitSeekTargetMs = 7_000L,
                    persistedResumeMs = 8_000L,
                )
                .projection
                .terminal
                ?.retryPosition
                ?.source,
        )
        assertEquals(
            PlaybackRetryPositionSource.USER_SEEK_TARGET_NEW_REQUEST,
            terminalAtDeadline(
                    enginePositionMs = null,
                    explicitSeekTargetMs = 7_000L,
                    persistedResumeMs = 8_000L,
                )
                .projection
                .terminal
                ?.retryPosition
                ?.source,
        )
        assertEquals(
            PlaybackRetryPositionSource.PERSISTED_RESUME,
            terminalAtDeadline(
                    enginePositionMs = null,
                    explicitSeekTargetMs = null,
                    persistedResumeMs = 8_000L,
                )
                .projection
                .terminal
                ?.retryPosition
                ?.source,
        )
        assertEquals(
            PlaybackRetryPositionSource.START_ZERO,
            terminalAtDeadline(
                    enginePositionMs = null,
                    explicitSeekTargetMs = null,
                    persistedResumeMs = null,
                )
                .projection
                .terminal
                ?.retryPosition
                ?.source,
        )
    }

    @Test
    fun `classification precedence is explicit pause then real error then seek failure`() {
        val paused =
            PlaybackSessionRules.advance(
                state = PlaybackSessionState(),
                snapshot =
                    snapshot(
                        playWhenReady = false,
                        hasPlayerError = true,
                        hasSeekFailure = true,
                    ),
                nowElapsedMs = 0L,
            )
        assertEquals(PlaybackSessionClassification.EXPLICIT_PAUSE, paused.projection.classification)

        val error =
            PlaybackSessionRules.advance(
                state = PlaybackSessionState(),
                snapshot =
                    snapshot(
                        playWhenReady = true,
                        hasPlayerError = true,
                        hasSeekFailure = true,
                    ),
                nowElapsedMs = 0L,
            )
        assertEquals(
            PlaybackSessionClassification.REAL_PLAYER_ERROR,
            error.projection.classification,
        )
        assertFalse(error.projection.loading)

        val seekFailed =
            PlaybackSessionRules.advance(
                state = PlaybackSessionState(),
                snapshot =
                    snapshot(
                        playWhenReady = true,
                        isBuffering = true,
                        hasSeekFailure = true,
                    ),
                nowElapsedMs = 0L,
            )
        assertEquals(
            PlaybackSessionClassification.SEEK_FAILED,
            seekFailed.projection.classification,
        )
        assertFalse(seekFailed.projection.loading)
    }

    @Test
    fun `only authoritative end reason advances existing eos path`() {
        assertTrue(PlaybackSessionRules.advancesToNextItem(PlaybackEndSignal.END_OF_MEDIA_ITEM))
        assertFalse(PlaybackSessionRules.advancesToNextItem(PlaybackEndSignal.OTHER))
    }

    private fun terminalAtDeadline(
        enginePositionMs: Long?,
        explicitSeekTargetMs: Long? = null,
        persistedResumeMs: Long? = null,
    ): PlaybackSessionUpdate {
        val initial =
            PlaybackSessionRules.advance(
                state = PlaybackSessionState(),
                snapshot =
                    snapshot(
                        playWhenReady = true,
                        isPlaying = false,
                        isBuffering = true,
                        enginePositionMs = enginePositionMs,
                        explicitSeekTargetMs = explicitSeekTargetMs,
                        persistedResumeMs = persistedResumeMs,
                    ),
                nowElapsedMs = 0L,
            )
        return PlaybackSessionRules.advance(
            state = initial.state,
            snapshot =
                snapshot(
                    playWhenReady = true,
                    isPlaying = false,
                    isBuffering = true,
                    enginePositionMs = enginePositionMs,
                    explicitSeekTargetMs = explicitSeekTargetMs,
                    persistedResumeMs = persistedResumeMs,
                ),
            nowElapsedMs = PlaybackSessionRules.STALL_TIMEOUT_MS,
        )
    }

    private fun snapshot(
        key: PlaybackSessionKey = this.key,
        playWhenReady: Boolean = true,
        isPlaying: Boolean = false,
        isBuffering: Boolean = true,
        isReady: Boolean = false,
        enginePositionMs: Long? = 2_000_000L,
        bufferedPositionMs: Long? = 2_100_000L,
        hasPlayerError: Boolean = false,
        hasSeekFailure: Boolean = false,
        explicitSeekTargetMs: Long? = null,
        persistedResumeMs: Long? = null,
    ): PlaybackSessionSnapshot =
        PlaybackSessionSnapshot(
            key = key,
            playWhenReady = playWhenReady,
            isPlaying = isPlaying,
            isBuffering = isBuffering,
            isReady = isReady,
            enginePositionMs = enginePositionMs,
            bufferedPositionMs = bufferedPositionMs,
            hasPlayerError = hasPlayerError,
            hasSeekFailure = hasSeekFailure,
            explicitSeekTargetMs = explicitSeekTargetMs,
            persistedResumeMs = persistedResumeMs,
        )
}
