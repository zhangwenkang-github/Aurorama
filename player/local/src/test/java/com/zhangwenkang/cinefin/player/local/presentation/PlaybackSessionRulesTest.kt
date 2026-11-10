package com.zhangwenkang.cinefin.player.local.presentation

import android.os.Handler
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import com.zhangwenkang.cinefin.player.core.domain.models.PLAYER_EXTRA_MEDIA_SOURCE_ID
import com.zhangwenkang.cinefin.player.local.domain.SeekFailureReason
import com.zhangwenkang.cinefin.player.local.domain.SeekState
import com.zhangwenkang.cinefin.player.local.domain.seekMediaIdentityFor
import com.zhangwenkang.cinefin.player.local.mpv.MPVPlayer
import com.zhangwenkang.cinefin.player.local.mpv.MpvSourcePositionState
import dev.jdtech.mpv.MPVLib.MpvEvent
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import sun.misc.Unsafe

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
            PlayerViewModel.PlaybackRetryPositionSource.USER_SEEK_TARGET_NEW_REQUEST,
            source(5_000L, 7_000L, 8_000L),
        )
        assertEquals(
            PlayerViewModel.PlaybackRetryPositionSource.ENGINE_POSITION,
            source(5_000L, null, 8_000L),
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
    fun `pending explicit seek survives buffering and remains ahead of confirmed position`() {
        val ready = input(isPlaying = true, isBuffering = false, isReady = true)
        val buffering =
            input(
                isPlaying = false,
                isBuffering = true,
                isReady = false,
                enginePositionMs = null,
                bufferedPositionMs = null,
                explicitSeekTargetMs = 60_000L,
                persistedResumeMs = 8_000L,
            )
        val atReady =
            advance(ready.copy(enginePositionMs = 5_000L, explicitSeekTargetMs = 60_000L), 0L)
        val stillBuffering = advance(buffering, 1_000L, atReady.first)
        val terminal =
            advance(buffering, PlaybackSessionRules.STALL_TIMEOUT_MS, stillBuffering.first)

        assertEquals(60_000L, terminal.second.terminal?.positionMs)
        assertEquals(
            PlayerViewModel.PlaybackRetryPositionSource.USER_SEEK_TARGET_NEW_REQUEST,
            terminal.second.terminal?.positionSource,
        )
        assertEquals(
            5_000L,
            terminalAtDeadline(enginePositionMs = 5_000L, persistedResumeMs = 8_000L)
                .second
                .terminal
                ?.positionMs,
        )
    }

    @Test
    fun `source change invalidates a pending target already bound to the old source`() {
        val oldInput = input(enginePositionMs = 5_000L, explicitSeekTargetMs = 60_000L)
        val oldState = advance(oldInput, 0L).first
        val changedSource =
            input(
                key = "item-b|source-b|1|2",
                enginePositionMs = null,
                bufferedPositionMs = null,
                explicitSeekTargetMs = 60_000L,
                persistedResumeMs = 8_000L,
            )
        val switched = advance(changedSource, 0L, oldState)
        val terminal =
            advance(
                changedSource,
                PlaybackSessionRules.STALL_TIMEOUT_MS,
                switched.first,
            )

        assertEquals(8_000L, terminal.second.terminal?.positionMs)
        assertEquals(
            PlayerViewModel.PlaybackRetryPositionSource.PERSISTED_RESUME,
            terminal.second.terminal?.positionSource,
        )
    }

    @Test
    fun `pending seek binding invalidates on epoch change and renews by generation`() {
        val generation = 41L
        val oldEpoch = 7L
        val initial =
            input(
                enginePositionMs = 5_000L,
                explicitSeekTargetMs = 60_000L,
                explicitSeekRequestGeneration = generation,
                explicitSeekSourceEpoch = oldEpoch,
            )
        val bound = advance(initial, 0L).first
        assertEquals(60_000L, bound.pendingSeekTargetMs)

        val changedEpoch =
            initial.copy(
                key = "item-a|source-a|1|1",
                explicitSeekSourceEpoch = oldEpoch + 1L,
            )
        val invalidated = advance(changedEpoch, 100L, bound)
        assertNull(invalidated.first.pendingSeekTargetMs)
        assertNull(invalidated.first.pendingSeekBinding)

        val terminal =
            advance(
                changedEpoch,
                PlaybackSessionRules.STALL_TIMEOUT_MS + 100L,
                invalidated.first,
            )
        assertEquals(5_000L, terminal.second.terminal?.positionMs)
        assertEquals(
            PlayerViewModel.PlaybackRetryPositionSource.ENGINE_POSITION,
            terminal.second.terminal?.positionSource,
        )

        val renewed =
            changedEpoch.copy(
                explicitSeekTargetMs = 75_000L,
                explicitSeekRequestGeneration = generation + 1L,
            )
        val renewedState = advance(renewed, 200L, invalidated.first).first
        assertEquals(75_000L, renewedState.pendingSeekTargetMs)
        assertEquals(generation + 1L, renewedState.pendingSeekGeneration)

        val renewedTerminal =
            advance(
                    renewed,
                    PlaybackSessionRules.STALL_TIMEOUT_MS + 200L,
                    renewedState,
                )
                .first
        assertNotNull(renewedTerminal.terminal)
        val otherSessionKey = "item-b|source-b|1|1"
        assertFalse(PlaybackSessionRules.retrySourceIsCurrent(renewedTerminal, otherSessionKey))
    }

    @Test
    fun `failed seek keeps explicit retry target while failure UI suppresses terminal`() {
        val targetMs = 64_000L
        val generation = 91L
        val sourceEpoch = 12L
        val failedState =
            SeekState.Failed(
                targetMs = targetMs,
                requestGeneration = generation,
                sourceEpoch = sourceEpoch,
                mediaIdentity = sourceIdentity("https://media.example/item-a"),
                reason = SeekFailureReason.TIMEOUT,
                retryable = true,
                policy =
                    com.zhangwenkang.cinefin.player.local.domain.SeekConfirmationPolicy(
                        completionTimeoutMs = 1_000L,
                        positionToleranceMs = 500L,
                        minimumTargetDisplayMs = 0L,
                    ),
            )
        val fields = failedState.explicitSeekFields()
        assertNotNull("SeekState.Failed 必须保留 explicit target 映射", fields)
        assertEquals(targetMs, fields?.first)
        assertEquals(generation, fields?.second)
        assertEquals(sourceEpoch, fields?.third)

        val failedInput =
            input(
                enginePositionMs = 5_000L,
                hasSeekFailure = true,
                explicitSeekTargetMs = fields?.first,
                explicitSeekRequestGeneration = fields?.second,
                explicitSeekSourceEpoch = fields?.third,
                persistedResumeMs = 8_000L,
            )
        val failed =
            advance(
                failedInput,
                PlaybackSessionRules.STALL_TIMEOUT_MS,
            )
        assertEquals(targetMs, failed.first.pendingSeekTargetMs)
        assertTrue(failed.second.showSeekFailure)
        assertFalse(failed.second.loading)
        assertNull(failed.second.terminal)
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
        val noSourceZero =
            advance(
                input(key = null, enginePositionMs = 0L),
                0L,
            )
        assertNull(noSourceZero.second.lastConfirmedPositionMs)
        val readyZero =
            advance(
                input(enginePositionMs = 0L),
                0L,
            )
        assertEquals(0L, readyZero.second.lastConfirmedPositionMs)
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
        assertEquals(5_000L, buffering.second.lastConfirmedPositionMs)
        assertNull(buffering.second.terminal)

        val terminal =
            advance(
                ready.copy(
                    isPlaying = false,
                    isBuffering = true,
                    isReady = false,
                    enginePositionMs = null,
                    bufferedPositionMs = null,
                ),
                PlaybackSessionRules.STALL_TIMEOUT_MS,
                buffering.first,
            )

        assertEquals(5_000L, terminal.second.terminal?.positionMs)
        assertEquals(
            PlayerViewModel.PlaybackRetryPositionSource.ENGINE_POSITION,
            terminal.second.terminal?.positionSource,
        )
    }

    @Test
    fun `setMediaItems advances source epoch and invalidates old source truth`() {
        val oldEpoch = 7L
        val oldUri = "https://media.example/item-a"
        val newUri = "https://media.example/item-b"
        val oldItem = mediaItem("item-a", oldUri)
        val newItem = mediaItem("item-b", newUri)
        val playerResult = runCatching {
            allocatedMpvPlayer(
                sourceEpoch = oldEpoch,
                mediaItems = mutableListOf(oldItem),
                currentIndex = 0,
                sourcePosition =
                    MpvSourcePositionState(
                        sourceEpoch = oldEpoch,
                        positionMs = 5_000L,
                        cacheEndMs = 9_000L,
                        pendingDiscontinuityFromMs = 2_000L,
                        isAnchored = true,
                    ),
            )
        }
        assertTrue(
            "无法分配无构造 MPVPlayer 测试对象: ${playerResult.exceptionOrNull()}",
            playerResult.isSuccess,
        )
        val player = requireNotNull(playerResult.getOrNull())
        val commands = mutableListOf<List<String>>()
        assertTrue(
            "MPVPlayer 必须暴露可捕获的 postCommand 执行入口",
            trySetField(player, "commandsClosed", AtomicBoolean(false)),
        )
        val commandExecutor: (Array<String>) -> Unit = { command -> commands += command.toList() }
        assertTrue(
            "MPVPlayer 必须暴露可捕获的 postCommand 执行入口",
            trySetField(player, "commandExecutor", commandExecutor),
        )
        val oldSessionKey =
            playbackSessionKeyFor(
                seekMediaIdentityFor(
                    itemId = oldItem.mediaId,
                    mediaSourceId = "source-a",
                    mediaSourceUri = oldUri,
                ),
                9L,
                oldEpoch,
            )
        val initial = advance(input(key = oldSessionKey), 0L)
        val terminal =
            advance(
                    input(key = oldSessionKey),
                    PlaybackSessionRules.STALL_TIMEOUT_MS,
                    initial.first,
                )
                .first
        val token = requireNotNull(terminal.terminal).token

        val outcome = runCatching { player.setMediaItems(mutableListOf(newItem), true) }

        assertTrue("setMediaItems 生产入口失败: ${outcome.exceptionOrNull()}", outcome.isSuccess)
        assertEquals(
            listOf(
                listOf("playlist-clear"),
                listOf("playlist-remove", "current"),
            ),
            commands,
        )
        val currentEpoch = player.currentSourceEpoch()
        assertTrue(currentEpoch > oldEpoch)
        assertNull(player.confirmedPosition())
        val cleared = sourcePosition(player)
        assertNull(cleared.positionMs)
        assertNull(cleared.cacheEndMs)
        assertNull(cleared.pendingDiscontinuityFromMs)
        assertNull(cleared.acceptPosition(oldEpoch, 8_000L))
        assertNull(cleared.acceptCache(oldEpoch, 12_000L))
        assertNull(cleared.seekStarted(oldEpoch))
        assertNull(cleared.restart(oldEpoch, 8_000L))
        assertNull(cleared.consumeDiscontinuity(oldEpoch))

        val currentKey =
            playbackSessionKeyFor(
                seekMediaIdentityFor(
                    itemId = newItem.mediaId,
                    mediaSourceId = "source-b",
                    mediaSourceUri = newUri,
                ),
                9L,
                currentEpoch,
            )
        assertFalse(PlaybackSessionRules.retrySourceIsCurrent(terminal, currentKey))
        assertEquals(token, terminal.terminal.token)
    }

    @Test
    fun `removeMediaItems on the current item advances source epoch atomically`() {
        val oldEpoch = 11L
        val currentUri = "https://media.example/item-a"
        val nextUri = "https://media.example/item-b"
        val currentItem = mediaItem("item-a", currentUri)
        val nextItem = mediaItem("item-b", nextUri)
        val playerResult = runCatching {
            allocatedMpvPlayer(
                sourceEpoch = oldEpoch,
                mediaItems = mutableListOf(currentItem, nextItem),
                currentIndex = 0,
                sourcePosition =
                    MpvSourcePositionState(
                        sourceEpoch = oldEpoch,
                        positionMs = 4_000L,
                        cacheEndMs = 8_000L,
                        pendingDiscontinuityFromMs = 1_000L,
                        isAnchored = true,
                    ),
            )
        }
        assertTrue(
            "无法分配无构造 MPVPlayer 测试对象: ${playerResult.exceptionOrNull()}",
            playerResult.isSuccess,
        )
        val player = requireNotNull(playerResult.getOrNull())
        val oldSessionKey =
            playbackSessionKeyFor(
                seekMediaIdentityFor(
                    itemId = currentItem.mediaId,
                    mediaSourceId = "source-a",
                    mediaSourceUri = currentUri,
                ),
                9L,
                oldEpoch,
            )
        val terminal =
            advance(
                    input(key = oldSessionKey),
                    PlaybackSessionRules.STALL_TIMEOUT_MS,
                    advance(input(key = oldSessionKey), 0L).first,
                )
                .first
        val token = requireNotNull(terminal.terminal).token

        val outcome = runCatching { player.removeMediaItems(0, 1) }

        assertTrue("removeMediaItems 生产入口失败: ${outcome.exceptionOrNull()}", outcome.isSuccess)
        val currentEpoch = player.currentSourceEpoch()
        assertTrue(currentEpoch > oldEpoch)
        assertNull(player.confirmedPosition())
        val cleared = sourcePosition(player)
        assertNull(cleared.positionMs)
        assertNull(cleared.cacheEndMs)
        assertNull(cleared.pendingDiscontinuityFromMs)
        assertNull(cleared.seekStarted(oldEpoch))
        assertNull(cleared.restart(oldEpoch, 7_000L))
        assertNull(cleared.consumeDiscontinuity(oldEpoch))

        val currentKey =
            playbackSessionKeyFor(
                seekMediaIdentityFor(
                    itemId = nextItem.mediaId,
                    mediaSourceId = "source-b",
                    mediaSourceUri = nextUri,
                ),
                9L,
                currentEpoch,
            )
        assertFalse(PlaybackSessionRules.retrySourceIsCurrent(terminal, currentKey))
        assertEquals(token, terminal.terminal.token)
    }

    @Test
    fun `addMediaItems before current remaps its index and preserves source truth`() {
        val oldEpoch = 13L
        val inserted = mediaItem("item-z", "https://media.example/item-z")
        val before = mediaItem("item-a", "https://media.example/item-a")
        val current = mediaItem("item-b", "https://media.example/item-b")
        val player =
            allocatedMpvPlayer(
                sourceEpoch = oldEpoch,
                mediaItems = mutableListOf(before, current),
                currentIndex = 1,
                sourcePosition =
                    MpvSourcePositionState(
                        sourceEpoch = oldEpoch,
                        positionMs = 5_000L,
                        cacheEndMs = 9_000L,
                        pendingDiscontinuityFromMs = 2_000L,
                        isAnchored = true,
                    ),
            )
        val oldKey = sessionKey(current, oldEpoch)
        val oldTerminal =
            advance(
                    input(key = oldKey),
                    PlaybackSessionRules.STALL_TIMEOUT_MS,
                    advance(input(key = oldKey), 0L).first,
                )
                .first
        val token = requireNotNull(oldTerminal.terminal).token

        player.addMediaItems(0, mutableListOf(inserted))

        assertEquals(oldEpoch, player.currentSourceEpoch())
        assertEquals(2, player.currentMediaItemIndex)
        assertEquals("item-b", itemAt(player, player.currentMediaItemIndex).mediaId)
        assertEquals(5_000L, sourcePosition(player).positionMs)
        assertEquals(9_000L, sourcePosition(player).cacheEndMs)
        assertEquals(2_000L, sourcePosition(player).pendingDiscontinuityFromMs)
        assertTrue(
            PlaybackSessionRules.retrySourceIsCurrent(
                oldTerminal,
                sessionKey(
                    itemAt(player, player.currentMediaItemIndex),
                    player.currentSourceEpoch(),
                ),
            )
        )
        requireNotNull(PlaybackSessionRules.consumeRetry(oldTerminal, token))
    }

    @Test
    fun `moveMediaItems remaps the active item without advancing source`() {
        val oldEpoch = 17L
        val first = mediaItem("item-a", "https://media.example/item-a")
        val current = mediaItem("item-b", "https://media.example/item-b")
        val last = mediaItem("item-c", "https://media.example/item-c")
        val player =
            allocatedMpvPlayer(
                sourceEpoch = oldEpoch,
                mediaItems = mutableListOf(first, current, last),
                currentIndex = 1,
                sourcePosition =
                    MpvSourcePositionState(
                        sourceEpoch = oldEpoch,
                        positionMs = 4_000L,
                        cacheEndMs = 8_000L,
                        pendingDiscontinuityFromMs = 1_000L,
                        isAnchored = true,
                    ),
            )
        val oldKey = sessionKey(current, oldEpoch)
        val oldTerminal =
            advance(
                    input(key = oldKey),
                    PlaybackSessionRules.STALL_TIMEOUT_MS,
                    advance(input(key = oldKey), 0L).first,
                )
                .first
        val token = requireNotNull(oldTerminal.terminal).token

        player.moveMediaItems(1, 2, 2)

        assertEquals(oldEpoch, player.currentSourceEpoch())
        assertEquals(2, player.currentMediaItemIndex)
        assertEquals("item-b", itemAt(player, player.currentMediaItemIndex).mediaId)
        assertEquals(4_000L, sourcePosition(player).positionMs)
        assertEquals(8_000L, sourcePosition(player).cacheEndMs)
        assertEquals(1_000L, sourcePosition(player).pendingDiscontinuityFromMs)
        assertTrue(
            PlaybackSessionRules.retrySourceIsCurrent(
                oldTerminal,
                sessionKey(
                    itemAt(player, player.currentMediaItemIndex),
                    player.currentSourceEpoch(),
                ),
            )
        )
        requireNotNull(PlaybackSessionRules.consumeRetry(oldTerminal, token))
    }

    @Test
    fun `removeMediaItems before current remaps its index and preserves source truth`() {
        val oldEpoch = 19L
        val first = mediaItem("item-a", "https://media.example/item-a")
        val current = mediaItem("item-b", "https://media.example/item-b")
        val last = mediaItem("item-c", "https://media.example/item-c")
        val player =
            allocatedMpvPlayer(
                sourceEpoch = oldEpoch,
                mediaItems = mutableListOf(first, current, last),
                currentIndex = 2,
                sourcePosition =
                    MpvSourcePositionState(
                        sourceEpoch = oldEpoch,
                        positionMs = 6_000L,
                        cacheEndMs = 10_000L,
                        pendingDiscontinuityFromMs = 3_000L,
                        isAnchored = true,
                    ),
            )

        player.removeMediaItems(0, 2)

        assertEquals(oldEpoch, player.currentSourceEpoch())
        assertEquals(0, player.currentMediaItemIndex)
        assertEquals("item-c", itemAt(player, player.currentMediaItemIndex).mediaId)
        assertEquals(6_000L, sourcePosition(player).positionMs)
        assertEquals(10_000L, sourcePosition(player).cacheEndMs)
        assertEquals(3_000L, sourcePosition(player).pendingDiscontinuityFromMs)
    }

    @Test
    fun `setMediaItems with the same active identity preserves source truth and retry token`() {
        val oldEpoch = 23L
        val replacedNeighbor = mediaItem("item-a", "https://media.example/item-a")
        val current = mediaItem("item-b", "https://media.example/item-b")
        val removedTail = mediaItem("item-c", "https://media.example/item-c")
        val player =
            allocatedMpvPlayer(
                sourceEpoch = oldEpoch,
                mediaItems = mutableListOf(replacedNeighbor, current, removedTail),
                currentIndex = 1,
                sourcePosition =
                    MpvSourcePositionState(
                        sourceEpoch = oldEpoch,
                        positionMs = 7_000L,
                        cacheEndMs = 11_000L,
                        pendingDiscontinuityFromMs = 4_000L,
                        isAnchored = true,
                    ),
            )
        setField(player, "playbackState", Player.STATE_READY)
        setField(player, "isPlayerReady", true)
        val commands = mutableListOf<List<String>>()
        assertTrue(
            "MPVPlayer 必须暴露可捕获的 postCommand 执行入口",
            trySetField(player, "commandsClosed", AtomicBoolean(false)),
        )
        val commandExecutor: (Array<String>) -> Unit = { command -> commands += command.toList() }
        assertTrue(
            "MPVPlayer 必须暴露可捕获的 postCommand 执行入口",
            trySetField(player, "commandExecutor", commandExecutor),
        )
        val oldKey = sessionKey(current, oldEpoch)
        val oldTerminal =
            advance(
                    input(key = oldKey),
                    PlaybackSessionRules.STALL_TIMEOUT_MS,
                    advance(input(key = oldKey), 0L).first,
                )
                .first
        val token = requireNotNull(oldTerminal.terminal).token
        val replacement = mediaItem("item-x", "https://media.example/item-x")
        val appended = mediaItem("item-y", "https://media.example/item-y")

        player.setMediaItems(mutableListOf(replacement, current, appended), 1, 7_000L)

        assertEquals(4, commands.size)
        assertEquals(listOf("playlist-remove", "2"), commands[0])
        assertEquals(listOf("playlist-remove", "0"), commands[1])
        assertEquals("loadfile", commands[2].first())
        assertEquals(listOf("insert-at", "0"), commands[2].drop(2))
        assertEquals("loadfile", commands[3].first())
        assertEquals(listOf("insert-at", "2"), commands[3].drop(2))
        assertFalse(commands.any { it == listOf("playlist-clear") })
        assertFalse(commands.any { it == listOf("playlist-remove", "current") })
        assertEquals(oldEpoch, player.currentSourceEpoch())
        assertEquals(1, player.currentMediaItemIndex)
        assertEquals("item-b", itemAt(player, player.currentMediaItemIndex).mediaId)
        assertEquals(7_000L, sourcePosition(player).positionMs)
        assertEquals(11_000L, sourcePosition(player).cacheEndMs)
        assertEquals(4_000L, sourcePosition(player).pendingDiscontinuityFromMs)
        assertTrue(
            PlaybackSessionRules.retrySourceIsCurrent(
                oldTerminal,
                sessionKey(
                    itemAt(player, player.currentMediaItemIndex),
                    player.currentSourceEpoch(),
                ),
            )
        )
        requireNotNull(PlaybackSessionRules.consumeRetry(oldTerminal, token))

        val prepareOutcome = runCatching { player.prepare() }
        assertTrue(
            "same-identity prepare 失败: ${prepareOutcome.exceptionOrNull()}",
            prepareOutcome.isSuccess,
        )
        assertEquals(4, commands.size)
        assertEquals(oldEpoch, player.currentSourceEpoch())
        assertEquals(7_000L, sourcePosition(player).positionMs)
        assertEquals(11_000L, sourcePosition(player).cacheEndMs)
        assertEquals(4_000L, sourcePosition(player).pendingDiscontinuityFromMs)
    }

    @Test
    fun `same identity preserves current native entry for every playback state`() {
        val cases =
            listOf(
                SameIdentityCase("idle unknown", Player.STATE_IDLE, false, false),
                SameIdentityCase("buffering unknown", Player.STATE_BUFFERING, false, false),
                SameIdentityCase("playing unknown", Player.STATE_READY, true, false),
                SameIdentityCase("known position", Player.STATE_READY, true, true),
            )

        cases.forEach { case ->
            val epoch = 31L
            val neighbor = mediaItem("item-a", "https://media.example/item-a")
            val current = mediaItem("item-b", "https://media.example/item-b")
            val tail = mediaItem("item-c", "https://media.example/item-c")
            val position =
                MpvSourcePositionState(
                    sourceEpoch = epoch,
                    positionMs = if (case.hasPosition) 7_000L else null,
                    cacheEndMs = if (case.hasPosition) 11_000L else null,
                    pendingDiscontinuityFromMs = if (case.hasPosition) 4_000L else null,
                    isAnchored = case.hasPosition,
                )
            val player =
                allocatedMpvPlayer(
                    sourceEpoch = epoch,
                    mediaItems = mutableListOf(neighbor, current, tail),
                    currentIndex = 1,
                    sourcePosition = position,
                )
            setField(player, "playbackState", case.playbackState)
            setField(player, "isPlayerReady", case.isPlayerReady)
            val commands = captureCommands(player)

            player.setMediaItems(
                mutableListOf(
                    mediaItem("item-x", "https://media.example/item-x"),
                    current,
                    mediaItem("item-y", "https://media.example/item-y"),
                ),
                true,
            )

            assertEquals(
                "${case.name}: 应先删除 current 之后的旧项",
                listOf("playlist-remove", "2"),
                commands[0],
            )
            assertEquals(
                "${case.name}: 应再删除 current 之前的旧项",
                listOf("playlist-remove", "0"),
                commands[1],
            )
            assertEquals(
                "${case.name}: candidate 前缀应插入 native index 0",
                listOf("insert-at", "0"),
                commands[2].drop(2),
            )
            assertEquals(
                "${case.name}: candidate 后缀应插入 native index 2",
                listOf("insert-at", "2"),
                commands[3].drop(2),
            )
            assertFalse(
                "${case.name}: same identity 不得 clear playlist",
                commands.any { it == listOf("playlist-clear") },
            )
            assertFalse(
                "${case.name}: same identity 不得 remove current",
                commands.any { it == listOf("playlist-remove", "current") },
            )
            assertEquals(epoch, player.currentSourceEpoch())
            assertEquals(1, player.currentMediaItemIndex)
            assertEquals("item-b", itemAt(player, player.currentMediaItemIndex).mediaId)
            assertEquals(position, sourcePosition(player))
        }
    }

    @Test
    fun `same identity prepares the preserved current source before a real source switch`() {
        val epoch = 37L
        val neighbor = mediaItem("item-a", "https://media.example/item-a")
        val current = mediaItem("item-b", "https://media.example/item-b")
        val tail = mediaItem("item-c", "https://media.example/item-c")
        val player =
            allocatedMpvPlayer(
                sourceEpoch = epoch,
                mediaItems = mutableListOf(neighbor, current, tail),
                currentIndex = 1,
                sourcePosition = MpvSourcePositionState(sourceEpoch = epoch),
            )
        setField(player, "playbackState", Player.STATE_IDLE)
        setField(player, "isPlayerReady", false)
        val commands = captureCommands(player)

        player.setMediaItems(
            mutableListOf(
                mediaItem("item-x", "https://media.example/item-x"),
                current,
                mediaItem("item-y", "https://media.example/item-y"),
            ),
            true,
        )
        assertEquals(4, commands.size)
        assertFalse(commands.any { it == listOf("playlist-clear") })
        assertFalse(commands.any { it == listOf("playlist-remove", "current") })

        player.prepare()

        assertEquals(
            "IDLE+UNKNOWN 只能启动仍保留的 current native entry",
            listOf(listOf("playlist-play-index", "1")),
            commands.drop(4),
        )
        assertEquals(epoch, player.currentSourceEpoch())
        assertEquals("item-b", itemAt(player, player.currentMediaItemIndex).mediaId)

        commands.clear()
        val replacement = mediaItem("item-z", "https://media.example/item-z")
        player.setMediaItems(mutableListOf(replacement), 0, 0L)

        assertTrue(player.currentSourceEpoch() > epoch)
        assertEquals(
            "真实换源必须进入 replacement 命令计划",
            listOf(listOf("playlist-clear"), listOf("playlist-remove", "current")),
            commands,
        )
    }

    @Test
    fun `multi item add command plan preserves candidate order`() {
        val epoch = 41L
        val first = mediaItem("item-a", "https://media.example/item-a")
        val current = mediaItem("item-c", "https://media.example/item-c")
        val last = mediaItem("item-d", "https://media.example/item-d")
        val player =
            allocatedMpvPlayer(
                sourceEpoch = epoch,
                mediaItems = mutableListOf(first, current, last),
                currentIndex = 1,
                sourcePosition = MpvSourcePositionState(sourceEpoch = epoch),
            )
        val commands = captureCommands(player)
        val inserted =
            listOf(
                mediaItem("item-b1", "https://media.example/item-b1"),
                mediaItem("item-b2", "https://media.example/item-b2"),
                mediaItem("item-b3", "https://media.example/item-b3"),
            )

        player.addMediaItems(1, inserted.toMutableList())

        assertEquals(
            listOf("item-a", "item-b1", "item-b2", "item-b3", "item-c", "item-d"),
            itemIds(player),
        )
        assertEquals(epoch, player.currentSourceEpoch())
        assertEquals(4, player.currentMediaItemIndex)
        val nativeOrder = mutableListOf("item-a", "item-c", "item-d")
        commands.forEachIndexed { offset, command ->
            assertEquals("loadfile", command.first())
            assertEquals("insert-at", command[2])
            assertTrue(
                "第 ${offset + 1} 个 candidate 应插入 native index ${1 + offset}",
                command[3] == (1 + offset).toString(),
            )
            nativeOrder.add(command[3].toInt(), inserted[offset].mediaId)
        }
        assertEquals(listOf("1", "2", "3"), commands.map { it[3] })
        assertEquals(
            "production insert-at 命令应用后必须等于 candidate order",
            listOf("item-a", "item-b1", "item-b2", "item-b3", "item-c", "item-d"),
            nativeOrder,
        )
    }

    @Test
    fun `range move command plan preserves candidate order`() {
        val epoch = 43L
        val items =
            listOf("a", "b", "c", "d", "e", "f").map { id ->
                mediaItem("item-$id", "https://media.example/item-$id")
            }
        val player =
            allocatedMpvPlayer(
                sourceEpoch = epoch,
                mediaItems = items.toMutableList(),
                currentIndex = 4,
                sourcePosition = MpvSourcePositionState(sourceEpoch = epoch),
            )
        setField(player, "handler", Handler())
        val commands = captureCommands(player)

        player.moveMediaItems(1, 4, 4)

        assertEquals(
            listOf("item-a", "item-e", "item-f", "item-b", "item-c", "item-d"),
            itemIds(player),
        )
        assertEquals(epoch, player.currentSourceEpoch())
        assertEquals(1, player.currentMediaItemIndex)
        val nativeOrder = items.map(MediaItem::mediaId).toMutableList()
        commands.forEach { command ->
            assertEquals("playlist-move", command.first())
            val moved = nativeOrder.removeAt(command[1].toInt())
            nativeOrder.add(command[2].toInt(), moved)
        }
        assertEquals(
            "production range move 命令应用后必须等于 candidate order",
            listOf("item-a", "item-e", "item-f", "item-b", "item-c", "item-d"),
            nativeOrder,
        )
    }

    @Test
    fun `stale string and boolean callbacks cannot change source state after epoch mutation`() {
        val epoch = 47L
        val player =
            allocatedMpvPlayer(
                sourceEpoch = epoch,
                mediaItems = mutableListOf(mediaItem("item-a", "https://media.example/item-a")),
                currentIndex = 0,
                sourcePosition = MpvSourcePositionState(sourceEpoch = epoch),
            )
        setField(player, "handler", Handler())
        setField(player, "currentTracks", Tracks.EMPTY)
        setField(player, "externalSubtitleTrackIds", ConcurrentHashMap<String, Int>())
        setField(player, "pendingSideloadedSubtitleAdds", CopyOnWriteArraySet<String>())
        setField(player, "playbackState", Player.STATE_READY)
        setField(player, "isPlayerReady", true)
        player.eventProperty(
            "track-list",
            """[{"type":"sub","external":true,"id":7,"external-filename":"old.srt"}]""",
        )
        player.eventProperty("paused-for-cache", true)
        setField(player, "sourceEpoch", AtomicLong(epoch + 1L))

        val stringOutcome = runCatching {
            invokeEventPropertyStringCallback(
                player,
                epoch,
                "track-list",
                """[{"type":"sub","external":true,"id":7,"external-filename":"old.srt"}]""",
            )
        }
        val booleanOutcome = runCatching {
            invokeEventPropertyBooleanCallback(player, epoch, "paused-for-cache", true)
        }

        assertNull(player.subtitleTrackIdForPath("old.srt"))
        assertEquals(Player.STATE_READY, player.playbackState)
        assertTrue("旧 string callback 必须被 epoch gate 拒绝", stringOutcome.isSuccess)
        assertTrue("旧 boolean callback 必须被 epoch gate 拒绝", booleanOutcome.isSuccess)
    }

    @Test
    fun `stale START_FILE callback cannot post another source initial commands`() {
        val epoch = 53L
        val player =
            allocatedMpvPlayer(
                sourceEpoch = epoch,
                mediaItems = mutableListOf(mediaItem("item-a", "https://media.example/item-a")),
                currentIndex = 0,
                sourcePosition = MpvSourcePositionState(sourceEpoch = epoch),
            )
        setField(player, "handler", Handler())
        setField(player, "isPlayerReady", false)
        val oldCommands = mutableListOf(arrayOf("sub-add", "old.srt", "auto", "old", "zh"))
        val newCommands = mutableListOf(arrayOf("sub-add", "new.srt", "auto", "new", "zh"))
        setField(player, "initialCommands", oldCommands)
        val commands = captureCommands(player)

        player.event(MpvEvent.MPV_EVENT_START_FILE)
        setField(player, "sourceEpoch", AtomicLong(epoch + 1L))
        setField(player, "initialCommands", newCommands)

        invokeEventCallback(player, epoch, MpvEvent.MPV_EVENT_START_FILE, oldCommands)

        assertTrue("旧 START_FILE 不得读取并发送新 source 的 initialCommands", commands.isEmpty())
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
        key: String? = this.key,
        playWhenReady: Boolean = true,
        isPlaying: Boolean = false,
        isBuffering: Boolean = true,
        isReady: Boolean = false,
        enginePositionMs: Long? = 2_000_000L,
        bufferedPositionMs: Long? = 2_100_000L,
        hasPlayerError: Boolean = false,
        hasSeekFailure: Boolean = false,
        explicitSeekTargetMs: Long? = null,
        explicitSeekRequestGeneration: Long? = null,
        explicitSeekSourceEpoch: Long? = null,
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
            explicitSeekRequestGeneration = explicitSeekRequestGeneration,
            explicitSeekSourceEpoch = explicitSeekSourceEpoch,
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

    private fun mediaItem(mediaId: String, uri: String): MediaItem =
        MediaItem.Builder().setMediaId(mediaId).setUri(uri).build()

    private fun sessionKey(item: MediaItem, engineEpoch: Long) =
        playbackSessionKeyFor(
            seekMediaIdentityFor(
                itemId = item.mediaId,
                mediaSourceId =
                    item.mediaMetadata.extras?.getString(PLAYER_EXTRA_MEDIA_SOURCE_ID).orEmpty(),
                mediaSourceUri =
                    item.localConfiguration?.uri?.toString()
                        ?: item.requestMetadata.mediaUri?.toString().orEmpty(),
            ),
            9L,
            engineEpoch,
        )

    private fun allocatedMpvPlayer(
        sourceEpoch: Long,
        mediaItems: MutableList<MediaItem>,
        currentIndex: Int,
        sourcePosition: MpvSourcePositionState,
    ): MPVPlayer {
        val unsafeField =
            Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }
        val unsafe = unsafeField.get(null) as Unsafe
        val player = unsafe.allocateInstance(MPVPlayer::class.java) as MPVPlayer
        setField(player, "commandsClosed", AtomicBoolean(true))
        setField(player, "sourceEpoch", AtomicLong(sourceEpoch))
        setField(player, "sourcePosition", sourcePosition)
        setField(player, "internalMediaItems", mediaItems)
        setField(player, "currentMediaItemIndex", currentIndex)
        return player
    }

    private fun sourcePosition(player: MPVPlayer): MpvSourcePositionState =
        getField(player, "sourcePosition") as MpvSourcePositionState

    private fun itemAt(player: MPVPlayer, index: Int): MediaItem =
        @Suppress("UNCHECKED_CAST")
        (getField(player, "internalMediaItems") as MutableList<MediaItem>)[index]

    private fun itemIds(player: MPVPlayer): List<String> =
        @Suppress("UNCHECKED_CAST")
        (getField(player, "internalMediaItems") as MutableList<MediaItem>).map(MediaItem::mediaId)

    private fun captureCommands(player: MPVPlayer): MutableList<List<String>> {
        val commands = mutableListOf<List<String>>()
        assertTrue(
            "MPVPlayer 必须暴露可捕获的 postCommand 执行入口",
            trySetField(player, "commandsClosed", AtomicBoolean(false)),
        )
        val commandExecutor: (Array<String>) -> Unit = { command -> commands += command.toList() }
        assertTrue(
            "MPVPlayer 必须暴露可捕获的 postCommand 执行入口",
            trySetField(player, "commandExecutor", commandExecutor),
        )
        return commands
    }

    private fun invokeEventPropertyStringCallback(
        player: MPVPlayer,
        epoch: Long,
        property: String,
        value: String,
    ) {
        val method =
            MPVPlayer::class.java.declaredMethods.single {
                it.name.startsWith("eventProperty\$lambda\$") &&
                    it.parameterTypes.any { type -> type == String::class.java } &&
                    it.parameterTypes.count { type -> type == String::class.java } == 2 &&
                    it.parameterTypes.none { type -> type == Boolean::class.javaPrimitiveType }
            }
        invokeStaticCallback(method, player, property, value, epoch)
    }

    private fun invokeEventPropertyBooleanCallback(
        player: MPVPlayer,
        epoch: Long,
        property: String,
        value: Boolean,
    ) {
        val method =
            MPVPlayer::class.java.declaredMethods.single {
                it.name.startsWith("eventProperty\$lambda\$") &&
                    it.parameterTypes.any { type -> type == Boolean::class.javaPrimitiveType } &&
                    it.parameterTypes.any { type -> type == String::class.java }
            }
        invokeStaticCallback(method, player, property, value, epoch)
    }

    private fun invokeEventCallback(
        player: MPVPlayer,
        epoch: Long,
        eventId: Int,
        initialCommands: MutableList<Array<String>>,
    ) {
        val method =
            MPVPlayer::class.java.declaredMethods.single {
                it.name.startsWith("event\$lambda\$") &&
                    it.parameterTypes.any { type -> type == Int::class.javaPrimitiveType } &&
                    it.parameterTypes.any { type -> type == Long::class.javaPrimitiveType }
            }
        invokeStaticCallback(method, player, eventId, epoch, initialCommands)
    }

    private fun invokeStaticCallback(method: java.lang.reflect.Method, vararg values: Any) {
        val remaining = values.toMutableList()
        val arguments =
            method.parameterTypes
                .map { type ->
                    val valueIndex = remaining.indexOfFirst { value ->
                        when (type) {
                            java.lang.Integer.TYPE -> value is Int
                            java.lang.Long.TYPE -> value is Long
                            java.lang.Boolean.TYPE -> value is Boolean
                            else -> type.isInstance(value)
                        }
                    }
                    check(valueIndex >= 0) { "callback 参数不匹配: ${method.name} $type" }
                    remaining.removeAt(valueIndex)
                }
                .toTypedArray()
        method.isAccessible = true
        method.invoke(null, *arguments)
    }

    private fun trySetField(target: Any, name: String, value: Any): Boolean = runCatching {
        setField(target, name, value)
    }
        .isSuccess

    private fun setField(target: Any, name: String, value: Any) {
        target.javaClass.getDeclaredField(name).apply {
            isAccessible = true
            set(target, value)
        }
    }

    private fun getField(target: Any, name: String): Any? =
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)

    private data class SameIdentityCase(
        val name: String,
        val playbackState: Int,
        val isPlayerReady: Boolean,
        val hasPosition: Boolean,
    )
}
