package com.zhangwenkang.cinefin.player.local.presentation

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.zhangwenkang.cinefin.player.local.domain.seekMediaIdentityFor
import com.zhangwenkang.cinefin.player.local.mpv.MpvSourcePositionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackSessionRulesTest {
    private val key = "item-a|source-a|1|1"

    @Test
    fun `play action follows intent while loading`() {
        val loading = advance(input(isPlaying = false, isBuffering = true), 0L)
        assertTrue(loading.second.loading)
        assertTrue(loading.second.pauseWhenReady)
        assertNull(loading.second.terminal)
    }

    @Test
    fun `explicit pause wins and cancels deadline`() {
        val loading = advance(input(isPlaying = false, isBuffering = true), 0L)
        val paused =
            advance(
                input(playWhenReady = false, isPlaying = false, isBuffering = true),
                10_000L,
                loading.first,
            )
        assertFalse(paused.second.pauseWhenReady)
        assertFalse(paused.second.loading)
        assertNull(paused.first.lastProgressAtMs)
        assertNull(
            PlaybackSessionRules.advance(
                    paused.first,
                    input(playWhenReady = false, isPlaying = false, isBuffering = true),
                    PlaybackSessionRules.STALL_TIMEOUT_MS,
                )
                .terminal
        )
    }

    @Test
    fun `stall boundary is exclusive before twenty seconds`() {
        val initial = advance(input(isPlaying = false, isBuffering = true), 0L)
        val before =
            advance(
                input(isPlaying = false, isBuffering = true),
                PlaybackSessionRules.STALL_TIMEOUT_MS - 1L,
                initial.first,
            )
        assertNull(before.second.terminal)

        val terminal =
            advance(
                input(isPlaying = false, isBuffering = true),
                PlaybackSessionRules.STALL_TIMEOUT_MS,
                before.first,
            )
        assertEquals("PLAYBACK_PROGRESS_TIMEOUT", terminal.second.terminal?.kind)
        assertEquals(
            PlayerViewModel.PlaybackRetryPositionSource.ENGINE_POSITION,
            terminal.second.terminal?.positionSource,
        )
        assertFalse(terminal.second.loading)
    }

    @Test
    fun `noop-like input only yields progress unconfirmed`() {
        val input =
            input(
                isPlaying = false,
                isBuffering = false,
                isReady = false,
                enginePositionMs = null,
                bufferedPositionMs = null,
            )
        val initial = advance(input, 0L)
        assertNull(initial.second.terminal)
        val terminal = advance(input, PlaybackSessionRules.STALL_TIMEOUT_MS, initial.first)
        assertEquals("PROGRESS_UNCONFIRMED", terminal.second.terminal?.kind)
        assertNull(terminal.second.terminal?.cause)
    }

    @Test
    fun `engine position change resets deadline`() {
        val ready = input(isPlaying = true, isBuffering = false, isReady = true)
        val initial = advance(ready.copy(enginePositionMs = 1_000L), 0L)
        val progressed = advance(ready.copy(enginePositionMs = 11_000L), 10_000L, initial.first)
        val beforeNewDeadline =
            advance(ready.copy(enginePositionMs = 11_000L), 29_999L, progressed.first)
        assertEquals(10_000L, progressed.first.lastProgressAtMs)
        assertEquals(11_000L, progressed.first.lastEnginePositionMs)
        assertNull(beforeNewDeadline.second.terminal)
    }

    @Test
    fun `buffered position change does not reset deadline`() {
        val initial = advance(input(bufferedPositionMs = 1_000L), 0L)
        val progressed = advance(input(bufferedPositionMs = 2_000L), 10_000L, initial.first)
        val beforeNewDeadline =
            advance(
                input(bufferedPositionMs = 2_000L),
                PlaybackSessionRules.STALL_TIMEOUT_MS - 1L,
                progressed.first,
            )
        val atOriginalDeadline =
            advance(
                input(bufferedPositionMs = 2_000L),
                PlaybackSessionRules.STALL_TIMEOUT_MS,
                progressed.first,
            )
        assertEquals(0L, progressed.first.lastProgressAtMs)
        assertEquals(2_000L, progressed.first.lastBufferedPositionMs)
        assertNull(beforeNewDeadline.second.terminal)
        assertTrue(atOriginalDeadline.second.terminal != null)
    }

    @Test
    fun `loading to ready transition resets deadline`() {
        val loading = advance(input(isPlaying = false, isBuffering = true), 0L)
        val readyInput = input(isPlaying = true, isBuffering = false, isReady = true)
        val ready = advance(readyInput, 10_000L, loading.first)
        val beforeNewDeadline = advance(readyInput, 29_999L, ready.first)
        assertFalse(ready.second.loading)
        assertEquals(10_000L, ready.first.lastProgressAtMs)
        assertNull(beforeNewDeadline.second.terminal)
    }

    @Test
    fun `generation change clears terminal and retry token`() {
        val input = input(isPlaying = false, isBuffering = true)
        val initial = advance(input, 0L)
        val terminal = advance(input, PlaybackSessionRules.STALL_TIMEOUT_MS, initial.first)
        assertTrue(terminal.second.terminal != null)
        val changed =
            advance(
                input.copy(key = "item-b|source-b|2|2"),
                PlaybackSessionRules.STALL_TIMEOUT_MS + 1L,
                terminal.first,
            )
        assertNull(changed.second.terminal)
        assertNull(changed.first.terminal)
        assertFalse(changed.first.retryConsumed)
        assertNull(PlaybackSessionRules.consumeRetry(changed.first, "stale"))
    }

    @Test
    fun `uri-only source replacement changes session key`() {
        val source = sourceIdentity("https://media.example/video/master-a.m3u8?StartTimeTicks=0")
        val replaced = sourceIdentity("https://media.example/video/master-b.m3u8?StartTimeTicks=0")

        assertNotEquals(source.sourceFingerprint, replaced.sourceFingerprint)
        assertNotEquals(playbackSessionKeyFor(source, 9L), playbackSessionKeyFor(replaced, 9L))
    }

    @Test
    fun `start-time-only source replacement changes session key`() {
        val source = sourceIdentity("https://media.example/video/master.m3u8?StartTimeTicks=0")
        val replaced =
            sourceIdentity("https://media.example/video/master.m3u8?StartTimeTicks=450000000")

        assertNotEquals(source.sourceFingerprint, replaced.sourceFingerprint)
        assertNotEquals(playbackSessionKeyFor(source, 9L), playbackSessionKeyFor(replaced, 9L))
    }

    @Test
    fun `same source fingerprint keeps terminal and retry token`() {
        val source =
            sourceIdentity("https://media.example/video/master.m3u8?StartTimeTicks=450000000")
        val sameSource =
            sourceIdentity("https://media.example/video/master.m3u8?StartTimeTicks=450000000")
        val key = playbackSessionKeyFor(source, 9L)
        assertEquals(key, playbackSessionKeyFor(sameSource, 9L))

        val initial = advance(input(key = key), 0L)
        val terminal =
            advance(input(key = key), PlaybackSessionRules.STALL_TIMEOUT_MS, initial.first)
        val token = requireNotNull(terminal.first.terminal).token
        val consumed = requireNotNull(PlaybackSessionRules.consumeRetry(terminal.first, token))
        val sameSourceState =
            advance(
                input(key = key),
                PlaybackSessionRules.STALL_TIMEOUT_MS + 1L,
                consumed,
            )

        assertEquals(terminal.first.terminal, sameSourceState.first.terminal)
        assertTrue(sameSourceState.first.retryConsumed)
    }

    @Test
    fun `new source epoch can produce one new terminal`() {
        val source = sourceIdentity("https://media.example/video/master-a.m3u8?StartTimeTicks=0")
        val replaced = sourceIdentity("https://media.example/video/master-b.m3u8?StartTimeTicks=0")
        val oldKey = playbackSessionKeyFor(source, 9L)
        val newKey = playbackSessionKeyFor(replaced, 9L)
        val initial = advance(input(key = oldKey), 0L)
        val oldTerminal =
            advance(input(key = oldKey), PlaybackSessionRules.STALL_TIMEOUT_MS, initial.first)
        assertTrue(oldTerminal.second.terminal != null)

        val switched =
            advance(
                input(key = newKey),
                PlaybackSessionRules.STALL_TIMEOUT_MS,
                oldTerminal.first,
            )
        assertNull(switched.second.terminal)
        assertNull(switched.first.terminal)
        assertFalse(switched.first.retryConsumed)

        val beforeNewTerminal =
            advance(
                input(key = newKey),
                PlaybackSessionRules.STALL_TIMEOUT_MS * 2L - 1L,
                switched.first,
            )
        assertNull(beforeNewTerminal.second.terminal)
        val newTerminal =
            advance(
                input(key = newKey),
                PlaybackSessionRules.STALL_TIMEOUT_MS * 2L,
                beforeNewTerminal.first,
            )
        assertTrue(newTerminal.second.terminal != null)
        val token = requireNotNull(newTerminal.first.terminal).token
        val consumed = requireNotNull(PlaybackSessionRules.consumeRetry(newTerminal.first, token))
        assertNull(PlaybackSessionRules.consumeRetry(consumed, token))
    }

    @Test
    fun `same terminal retry is consumed once`() {
        val terminal = terminalAtDeadline(enginePositionMs = 5_000L)
        val token = requireNotNull(terminal.first.terminal).token
        assertTrue(token.isNotBlank())
        assertNull(PlaybackSessionRules.consumeRetry(terminal.first, "other-terminal"))
        val consumed = requireNotNull(PlaybackSessionRules.consumeRetry(terminal.first, token))
        assertEquals(5_000L, terminal.second.terminal?.positionMs)
        assertTrue(consumed.retryConsumed)
        assertNull(
            PlaybackSessionRules.project(consumed, input(isPlaying = false, isBuffering = true))
                .terminal
        )
        assertNull(PlaybackSessionRules.consumeRetry(consumed, token))
    }

    @Test
    fun `retry position follows frozen source priority`() {
        fun source(
            engine: Long?,
            explicit: Long?,
            persisted: Long?,
        ): PlayerViewModel.PlaybackRetryPositionSource? =
            terminalAtDeadline(engine, explicit, persisted).second.terminal?.positionSource

        assertEquals(
            PlayerViewModel.PlaybackRetryPositionSource.ENGINE_POSITION,
            source(5_000L, 7_000L, 8_000L),
        )
        assertEquals(
            PlayerViewModel.PlaybackRetryPositionSource.USER_SEEK_TARGET_NEW_REQUEST,
            source(null, 7_000L, 8_000L),
        )
        assertEquals(
            PlayerViewModel.PlaybackRetryPositionSource.PERSISTED_RESUME,
            source(null, null, 8_000L),
        )
        assertEquals(
            PlayerViewModel.PlaybackRetryPositionSource.START_ZERO,
            source(null, null, null),
        )
    }

    @Test
    fun `invalid engine sample never becomes a fake zero position`() {
        val invalid =
            input(
                isPlaying = false,
                isBuffering = true,
                enginePositionMs = androidx.media3.common.C.TIME_UNSET,
                bufferedPositionMs = null,
            )
        val initial = advance(invalid, 0L)
        assertNull(initial.first.lastEnginePositionMs)

        val terminal = advance(invalid, PlaybackSessionRules.STALL_TIMEOUT_MS, initial.first)
        assertEquals(
            PlayerViewModel.PlaybackRetryPositionSource.START_ZERO,
            terminal.second.terminal?.positionSource,
        )
        assertEquals(0L, terminal.second.terminal?.positionMs)
    }

    @Test
    fun `confirmed zero needs current item timeline and ready anchor`() {
        val item = MediaItem.Builder().setMediaId("item-a").build()

        assertNull(
            confirmedEnginePosition(
                mediaItem = null,
                timelineEmpty = false,
                playbackState = Player.STATE_READY,
                currentPositionMs = 0L,
            )
        )
        assertNull(
            confirmedEnginePosition(
                mediaItem = item,
                timelineEmpty = true,
                playbackState = Player.STATE_READY,
                currentPositionMs = 0L,
            )
        )
        assertNull(
            confirmedEnginePosition(
                mediaItem = item,
                timelineEmpty = false,
                playbackState = Player.STATE_BUFFERING,
                currentPositionMs = 0L,
            )
        )
        assertEquals(
            0L,
            confirmedEnginePosition(
                mediaItem = item,
                timelineEmpty = false,
                playbackState = Player.STATE_READY,
                currentPositionMs = 0L,
            ),
        )
        assertEquals(
            PlayerViewModel.PlaybackRetryPositionSource.ENGINE_POSITION,
            terminalAtDeadline(enginePositionMs = 0L).second.terminal?.positionSource,
        )
    }

    @Test
    fun `external buffered position is never below confirmed current`() {
        assertEquals(7_000L, boundedBufferedPosition(currentPositionMs = 7_000L, buffered = 4_000L))
        assertEquals(
            7_000L,
            boundedBufferedPosition(currentPositionMs = 7_000L, buffered = C.TIME_UNSET),
        )
        assertNull(boundedBufferedPosition(currentPositionMs = null, buffered = 4_000L))
    }

    @Test
    fun `mpv source epoch replacement invalidates position cache and pending discontinuity`() {
        val old =
            MpvSourcePositionState(
                sourceEpoch = 1L,
                positionMs = 5_000L,
                cacheEndMs = 9_000L,
                pendingDiscontinuityFromMs = 2_000L,
                isAnchored = true,
            )
        val switched = old.replaceSource(2L)

        assertNull(switched.positionMs)
        assertNull(switched.cacheEndMs)
        assertNull(switched.pendingDiscontinuityFromMs)
        assertNull(switched.acceptPosition(epoch = 1L, positionMs = 8_000L))
        assertNull(switched.acceptCache(epoch = 1L, cacheEndMs = 12_000L))
        assertNull(switched.restart(epoch = 1L, positionMs = 8_000L))
        assertNull(switched.consumeDiscontinuity(epoch = 1L))
        assertNull(switched.acceptPosition(epoch = 2L, positionMs = 0L))
        val anchored = requireNotNull(switched.restart(epoch = 2L, positionMs = 0L))
        assertEquals(0L, anchored.positionMs)
        assertEquals(1_000L, anchored.acceptPosition(epoch = 2L, positionMs = 1_000L)?.positionMs)
    }

    @Test
    fun `mpv source epoch is part of playback session key`() {
        val source = sourceIdentity("https://media.example/video/master.m3u8?StartTimeTicks=0")

        assertNotEquals(
            playbackSessionKeyFor(source, 9L, 3L),
            playbackSessionKeyFor(source, 9L, 4L),
        )
    }

    @Test
    fun `mpv seek clears stale cache but keeps a legal current buffered bound`() {
        val confirmed =
            MpvSourcePositionState(
                sourceEpoch = 4L,
                positionMs = 5_000L,
                cacheEndMs = 9_000L,
                isAnchored = true,
            )
        val seek = requireNotNull(confirmed.seekStarted(epoch = 4L))

        assertNull(seek.cacheEndMs)
        assertEquals(5_000L, seek.bufferedPositionMs)
        val lowerLateCache = requireNotNull(seek.acceptCache(epoch = 4L, cacheEndMs = 3_000L))
        assertEquals(5_000L, lowerLateCache.bufferedPositionMs)
    }

    @Test
    fun `buffered growth alone does not retire progress terminal`() {
        val loading =
            input(
                isPlaying = false,
                isBuffering = true,
                enginePositionMs = 5_000L,
                bufferedPositionMs = 6_000L,
            )
        val initial = advance(loading, 0L)
        val terminal = advance(loading, PlaybackSessionRules.STALL_TIMEOUT_MS, initial.first)
        assertTrue(terminal.second.terminal != null)

        val bufferedGrowth =
            advance(
                loading.copy(bufferedPositionMs = 9_000L),
                PlaybackSessionRules.STALL_TIMEOUT_MS + 1L,
                terminal.first,
            )
        assertTrue(bufferedGrowth.second.terminal != null)
        assertEquals(0L, bufferedGrowth.first.lastProgressAtMs)
    }

    @Test
    fun `confirmed engine advance retires progress terminal`() {
        val terminal = terminalAtDeadline(enginePositionMs = 5_000L)
        val advanced =
            advance(
                input(
                    isPlaying = true,
                    isBuffering = false,
                    isReady = true,
                    enginePositionMs = 6_000L,
                ),
                PlaybackSessionRules.STALL_TIMEOUT_MS + 1L,
                terminal.first,
            )

        assertNull(advanced.second.terminal)
    }

    @Test
    fun `ready recovery retires progress terminal without cache growth`() {
        val terminal = terminalAtDeadline(enginePositionMs = 0L)
        val recovered =
            advance(
                input(
                    isPlaying = true,
                    isBuffering = false,
                    isReady = true,
                    enginePositionMs = 0L,
                    bufferedPositionMs = 0L,
                ),
                PlaybackSessionRules.STALL_TIMEOUT_MS + 1L,
                terminal.first,
            )

        assertNull(recovered.second.terminal)
    }

    @Test
    fun `buffering keeps the last valid engine position`() {
        val ready = input(isPlaying = true, isBuffering = false, isReady = true)
        val initial = advance(ready.copy(enginePositionMs = 5_000L), 0L)
        val buffering =
            advance(
                ready.copy(
                    isPlaying = false,
                    isBuffering = true,
                    isReady = false,
                    enginePositionMs = androidx.media3.common.C.TIME_UNSET,
                    bufferedPositionMs = androidx.media3.common.C.TIME_UNSET,
                ),
                1_000L,
                initial.first,
            )

        assertEquals(5_000L, buffering.first.lastEnginePositionMs)
        assertNull(buffering.second.terminal)
    }

    @Test
    fun `source change invalidates the current terminal before retry`() {
        val terminal = terminalAtDeadline(enginePositionMs = 5_000L)
        assertTrue(terminal.first.terminal != null)
        val token = requireNotNull(terminal.first.terminal).token
        val changed =
            advance(
                input(key = "item-b|source-b|2|2"),
                PlaybackSessionRules.STALL_TIMEOUT_MS + 1L,
                terminal.first,
            )

        assertNull(changed.second.terminal)
        assertNull(PlaybackSessionRules.consumeRetry(changed.first, token))
    }

    @Test
    fun `classification precedence is pause then error then seek failure`() {
        assertEquals(
            false,
            advance(
                    input(
                        playWhenReady = false,
                        hasPlayerError = true,
                        hasSeekFailure = true,
                    ),
                    0L,
                )
                .second
                .pauseWhenReady,
        )
        assertTrue(
            advance(input(hasPlayerError = true, hasSeekFailure = true), 0L).second.showPlayerError
        )
        assertTrue(advance(input(hasSeekFailure = true), 0L).second.showSeekFailure)
    }

    @Test
    fun `only authoritative end reason advances eos path`() {
        assertTrue(
            PlaybackSessionRules.advancesToNextItem(
                Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM
            )
        )
        assertFalse(
            PlaybackSessionRules.advancesToNextItem(
                Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST
            )
        )
    }

    private fun advance(
        input: PlaybackSessionInput,
        now: Long,
        state: PlaybackSessionState = PlaybackSessionState(),
    ): Pair<PlaybackSessionState, PlayerViewModel.PlaybackSessionUi> {
        val next = PlaybackSessionRules.advance(state, input, now)
        return next to PlaybackSessionRules.project(next, input)
    }

    private fun input(
        key: String = this.key,
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
    ) =
        PlaybackSessionInput(
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

    private fun terminalAtDeadline(
        enginePositionMs: Long?,
        explicitSeekTargetMs: Long? = null,
        persistedResumeMs: Long? = null,
    ): Pair<PlaybackSessionState, PlayerViewModel.PlaybackSessionUi> {
        val input =
            input(
                enginePositionMs = enginePositionMs,
                explicitSeekTargetMs = explicitSeekTargetMs,
                persistedResumeMs = persistedResumeMs,
            )
        val initial = advance(input, 0L)
        return advance(input, PlaybackSessionRules.STALL_TIMEOUT_MS, initial.first)
    }

    private fun sourceIdentity(uri: String) =
        seekMediaIdentityFor(
            itemId = "item-a",
            mediaSourceId = "source-a",
            mediaSourceUri = uri,
        )
}
