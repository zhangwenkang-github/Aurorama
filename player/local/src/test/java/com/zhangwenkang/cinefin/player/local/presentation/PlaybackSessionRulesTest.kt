package com.zhangwenkang.cinefin.player.local.presentation

import android.net.Uri
import android.os.Handler
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.Tracks
import androidx.media3.common.util.ListenerSet
import androidx.media3.exoplayer.source.SinglePeriodTimeline
import com.zhangwenkang.cinefin.player.core.domain.models.PLAYER_EXTRA_MEDIA_SOURCE_ID
import com.zhangwenkang.cinefin.player.local.domain.SeekFailureReason
import com.zhangwenkang.cinefin.player.local.domain.SeekState
import com.zhangwenkang.cinefin.player.local.domain.seekMediaIdentityFor
import com.zhangwenkang.cinefin.player.local.mpv.MPVPlayer
import com.zhangwenkang.cinefin.player.local.mpv.MpvSourcePositionState
import dev.jdtech.mpv.MPVLib
import dev.jdtech.mpv.MPVLib.MpvEvent
import dev.jdtech.mpv.MpvSourceCommandBackend
import dev.jdtech.mpv.MpvSourceCommandNativeResult
import dev.jdtech.mpv.binding.MpvPlaybackEvent
import java.io.ByteArrayOutputStream
import java.io.File
import java.lang.invoke.MethodHandles
import java.lang.reflect.Constructor
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.tools.ToolProvider
import kotlin.jvm.functions.Function0
import kotlin.jvm.functions.Function1
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
    private val capturedSourceMarkers = mutableMapOf<MPVPlayer, MutableList<String>>()
    private val capturedRecoveries = mutableMapOf<MPVPlayer, MutableList<ScheduledRecovery>>()

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
    fun `ordinary mpv time-pos emits supported non-seek position refresh`() {
        val epoch = 271L
        val item = mediaItem("item-progress", "https://media.example/progress")
        val player =
            allocatedMpvPlayer(
                sourceEpoch = epoch,
                mediaItems = mutableListOf(item),
                currentIndex = 0,
                sourcePosition = MpvSourcePositionState(sourceEpoch = epoch),
            )
        setField(
            player,
            "timeline",
            SinglePeriodTimeline(120_000L, true, false, false, null, item),
        )
        setSuperField(player, "window", Timeline.Window())
        val handler = DeferredHandlerFactory.create()
        setField(player, "handler", handler)
        setField(player, "sourceEpochReady", false)
        setField(player, "isPlayerReady", true)
        setField(player, "currentPlayWhenReady", true)
        setField(player, "playbackState", Player.STATE_READY)
        val listeners = getField(player, "listeners") as ListenerSet<Player.Listener>
        val recorder = MpvPositionRefreshRecorder(player)
        listeners.add(recorder)
        listeners.flushEvents()

        assertTrue(
            "PLAYBACK_RESTART transition must anchor the same epoch at 1000ms",
            anchorThroughPlaybackRestartTransition(player, epoch, 1_000L),
        )
        listeners.flushEvents()
        recorder.clear()

        player.eventProperty("time-pos", 2L)
        runHandlerCallbacks(handler)
        listeners.flushEvents()
        assertEquals(1_000L, player.currentPosition)
        assertTrue(
            "sourceEpochReady=false must block the time-pos property route",
            recorder.refreshPositionsMs.isEmpty(),
        )

        setField(player, "sourceEpochReady", true)
        player.eventProperty("time-pos", 2L)
        runHandlerCallbacks(handler)
        listeners.flushEvents()
        assertEquals(2_000L, player.currentPosition)

        player.eventProperty("time-pos", 3L)
        runHandlerCallbacks(handler)
        listeners.flushEvents()
        assertEquals(3_000L, player.currentPosition)

        assertEquals(listOf(2_000L, 3_000L), recorder.refreshPositionsMs)
        assertEquals(2, recorder.positionDiscontinuityEvents)
        assertTrue(
            "ordinary time-pos must never be projected as a seek discontinuity",
            recorder.discontinuityReasons.none { it == Player.DISCONTINUITY_REASON_SEEK },
        )
        assertTrue(
            "ordinary time-pos must not fabricate an EOS or automatic transition",
            recorder.discontinuityReasons.none {
                it == Player.DISCONTINUITY_REASON_AUTO_TRANSITION
            },
        )
        assertEquals(0, recorder.endedPlaybackStates)
        assertEquals(0, recorder.endOfMediaReasons)
        assertEquals(0, recorder.mediaItemTransitions)
        assertEquals(epoch, player.currentSourceEpoch())

        player.eventProperty("time-pos", 4L)
        setField(player, "sourceEpoch", AtomicLong(epoch + 1L))
        recorder.clear()
        runHandlerCallbacks(handler)
        listeners.flushEvents()
        assertTrue(
            "queued time-pos from the old source epoch must not publish a refresh",
            recorder.refreshPositionsMs.isEmpty(),
        )
        assertEquals(epoch + 1L, player.currentSourceEpoch())
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
        installCommandExecutor(player) { captured ->
            commands += captured.arguments
            captured.complete()
        }
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
        installCommandExecutor(player) { captured ->
            commands += captured.arguments
            captured.complete()
        }
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
                SameIdentityCase("idle unknown", Player.STATE_IDLE, false, false, false),
                SameIdentityCase("buffering unknown", Player.STATE_BUFFERING, false, false, false),
                SameIdentityCase("playing unknown", Player.STATE_READY, true, true, false),
                SameIdentityCase("known position", Player.STATE_READY, true, true, true),
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
            setField(player, "currentPlayWhenReady", case.playWhenReady)
            val commands = captureCommands(player)
            assertEquals(
                "${case.name}: fixture 的可观察 playing 必须与 playWhenReady/state 一致",
                case.playWhenReady,
                player.isPlaying,
            )

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
    fun `addMediaItems accepts request metadata media uri without local configuration`() {
        val epoch = 42L
        val first = mediaItem("item-a", "https://media.example/item-a")
        val player =
            allocatedMpvPlayer(
                sourceEpoch = epoch,
                mediaItems = mutableListOf(first),
                currentIndex = 0,
                sourcePosition = MpvSourcePositionState(sourceEpoch = epoch),
            )
        val commands = captureCommands(player)
        val requestOnlyUri = "https://media.example/item-b"

        player.addMediaItems(
            1,
            mutableListOf(requestUriMediaItem("item-b", requestOnlyUri)),
        )

        assertEquals(
            listOf("loadfile", requestOnlyUri, "insert-at", "1"),
            commands.single(),
        )
        assertEquals(listOf("item-a", "item-b"), itemIds(player))
    }

    @Test
    fun `range move command plan preserves candidate order`() {
        val epoch = 43L
        val items =
            listOf("a", "b", "c", "d", "e", "f").map { id ->
                mediaItem("item-$id", "https://media.example/item-$id")
            }
        val cases =
            listOf(
                RangeMoveCase(1, 4, 4, listOf("a", "e", "f", "b", "c", "d")),
                RangeMoveCase(3, 6, 0, listOf("d", "e", "f", "a", "b", "c")),
            )

        cases.forEachIndexed { caseIndex, case ->
            val player =
                allocatedMpvPlayer(
                    sourceEpoch = epoch,
                    mediaItems = items.toMutableList(),
                    currentIndex = 4,
                    sourcePosition = MpvSourcePositionState(sourceEpoch = epoch),
                )
            setField(player, "handler", Handler())
            val commands = captureCommands(player)

            player.moveMediaItems(case.fromIndex, case.toIndex, case.newIndex)

            val prefix = "case $caseIndex"
            assertEquals(
                "$prefix: candidate order",
                case.expectedIds.map { "item-$it" },
                itemIds(player),
            )
            assertEquals("$prefix: source epoch", epoch, player.currentSourceEpoch())
            assertEquals("$prefix: current index", 1, player.currentMediaItemIndex)
            val nativeOrder = items.map(MediaItem::mediaId).toMutableList()
            commands.forEach { command ->
                assertEquals("$prefix: command", "playlist-move", command.first())
                val moved = nativeOrder.removeAt(command[1].toInt())
                nativeOrder.add(command[2].toInt(), moved)
            }
            assertEquals(
                "$prefix: production range move 命令应用后必须等于 candidate order",
                case.expectedIds.map { "item-$it" },
                nativeOrder,
            )
        }
    }

    @Test
    fun `moveMediaItems rejects negative new index before state or native mutation`() {
        val epoch = 44L
        val items =
            listOf("a", "b", "c").map { id ->
                mediaItem("item-$id", "https://media.example/item-$id")
            }
        val player =
            allocatedMpvPlayer(
                sourceEpoch = epoch,
                mediaItems = items.toMutableList(),
                currentIndex = 1,
                sourcePosition = MpvSourcePositionState(sourceEpoch = epoch),
            )
        val commands = captureCommands(player)
        val before = itemIds(player)

        val outcome = runCatching { player.moveMediaItems(0, 1, -1) }

        assertTrue(
            "负 newIndex 必须以 IllegalArgumentException 拒绝: ${outcome.exceptionOrNull()}",
            outcome.exceptionOrNull() is IllegalArgumentException,
        )
        assertEquals("internal playlist 不得变化", before, itemIds(player))
        assertEquals("current index 不得变化", 1, player.currentMediaItemIndex)
        assertEquals("source epoch 不得变化", epoch, player.currentSourceEpoch())
        assertTrue("拒绝前不得产生 native command", commands.isEmpty())
    }

    @Test
    fun `addMediaItems rejects missing media uri atomically`() {
        val epoch = 45L
        val items =
            listOf("a", "b").map { id -> mediaItem("item-$id", "https://media.example/item-$id") }
        val player =
            allocatedMpvPlayer(
                sourceEpoch = epoch,
                mediaItems = items.toMutableList(),
                currentIndex = 1,
                sourcePosition = MpvSourcePositionState(sourceEpoch = epoch),
            )
        val commands = captureCommands(player)
        val before = itemIds(player)

        val outcome = runCatching {
            player.addMediaItems(
                1,
                mutableListOf(
                    mediaItem("item-valid", "https://media.example/item-valid"),
                    MediaItem.Builder().setMediaId("item-missing").build(),
                ),
            )
        }

        assertTrue(
            "无 URI item 必须以 IllegalArgumentException 拒绝: ${outcome.exceptionOrNull()}",
            outcome.exceptionOrNull() is IllegalArgumentException,
        )
        assertEquals("批量 add 必须原子拒绝", before, itemIds(player))
        assertEquals("current index 不得变化", 1, player.currentMediaItemIndex)
        assertEquals("source epoch 不得变化", epoch, player.currentSourceEpoch())
        assertTrue("原子拒绝前不得产生 native command", commands.isEmpty())
    }

    @Test
    fun `setMediaItems rejects missing media uri before replacement state`() {
        val epoch = 46L
        val items =
            listOf("a", "b").map { id -> mediaItem("item-$id", "https://media.example/item-$id") }
        val player =
            allocatedMpvPlayer(
                sourceEpoch = epoch,
                mediaItems = items.toMutableList(),
                currentIndex = 1,
                sourcePosition = MpvSourcePositionState(sourceEpoch = epoch),
            )
        val commands = captureCommands(player)
        val before = itemIds(player)

        val outcome = runCatching {
            player.setMediaItems(
                mutableListOf(
                    mediaItem("item-valid", "https://media.example/item-valid"),
                    MediaItem.Builder().setMediaId("item-missing").build(),
                ),
                0,
                0L,
            )
        }

        assertTrue(
            "replacement 无 URI item 必须以 IllegalArgumentException 拒绝: ${outcome.exceptionOrNull()}",
            outcome.exceptionOrNull() is IllegalArgumentException,
        )
        assertEquals("replacement 必须原子拒绝", before, itemIds(player))
        assertEquals("current index 不得变化", 1, player.currentMediaItemIndex)
        assertEquals("source epoch 不得变化", epoch, player.currentSourceEpoch())
        assertTrue("原子拒绝前不得产生 native command", commands.isEmpty())
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
    fun `old START_FILE arriving after source mutation cannot consume current source initial commands`() {
        val epoch = 53L
        val player =
            allocatedMpvPlayer(
                sourceEpoch = epoch,
                mediaItems = mutableListOf(mediaItem("item-a", "https://media.example/item-a")),
                currentIndex = 0,
                sourcePosition = MpvSourcePositionState(sourceEpoch = epoch),
            )
        setField(player, "isPlayerReady", false)
        val oldCommands = mutableListOf(arrayOf("sub-add", "old.srt", "auto", "old", "zh"))
        val newCommands = mutableListOf(arrayOf("sub-add", "new.srt", "auto", "new", "zh"))
        val commands = captureCommands(player, deliverSourceMarkers = false)

        replaceInitialCommands(player, oldCommands)
        player.setMediaItems(
            mutableListOf(mediaItem("item-b", "https://media.example/item-b")),
            0,
            0L,
        )
        commands.clear()
        val newEpoch = player.currentSourceEpoch()
        assertTrue("source mutation 必须推进 epoch", newEpoch > epoch)
        replaceInitialCommands(player, newCommands)
        val callbacks = captureEventCallbacks(player)

        deliverStartFile(player, callbacks)
        assertFalse(
            "旧 START_FILE 不得消费并发送新 source 的 initialCommands",
            commands.any { it == newCommands.single().toList() },
        )

        deliverSourceMarker(player, 1)
        commands.clear()
        deliverStartFile(player, callbacks)
        assertEquals(
            "新 source 的真实 START_FILE 必须取得自己的快照",
            newCommands.map { it.toList() },
            commands,
        )

        deliverSourceEvent(player, MpvEvent.MPV_EVENT_START_FILE)
        callbacks.forEach(Runnable::run)
        assertEquals("当前 source 初始化不得重复发布", newCommands.map { it.toList() }, commands)
    }

    @Test
    fun `reviewer stale owner without event cannot consume first current START_FILE`() {
        val epoch = 61L
        val player =
            allocatedMpvPlayer(
                sourceEpoch = epoch,
                mediaItems = mutableListOf(mediaItem("item-a", "https://media.example/item-a")),
                currentIndex = 0,
                sourcePosition = MpvSourcePositionState(sourceEpoch = epoch),
            )
        setField(player, "isPlayerReady", false)
        val commands = captureCommands(player, deliverSourceMarkers = false)
        val newCommands = listOf(arrayOf("sub-add", "new.srt", "auto", "new", "zh"))

        replaceInitialCommands(player, listOf(arrayOf("sub-add", "old.srt", "auto", "old", "zh")))
        player.setMediaItems(
            mutableListOf(mediaItem("item-b", "https://media.example/item-b")),
            0,
            0L,
        )
        replaceInitialCommands(player, newCommands)
        commands.clear()
        val callbacks = captureEventCallbacks(player)

        deliverStartFile(player, callbacks)
        commands.clear()
        deliverSourceMarker(player, 1)
        commands.clear()
        deliverStartFile(player, callbacks)

        assertEquals(
            "旧 ownership 无事件时首个新 source START_FILE 必须发布当前快照且仅一次",
            newCommands.map { it.toList() },
            commands,
        )
        deliverSourceEvent(player, MpvEvent.MPV_EVENT_START_FILE)
        callbacks.forEach(Runnable::run)
        assertEquals("当前 source 快照不得重复发布", newCommands.map { it.toList() }, commands)
    }

    @Test
    fun `late start after dropped old barrier cannot claim successor initial commands`() {
        val player = allocatedMpvPlayerForStartFile(epoch = 401L)
        setField(player, "isPlayerReady", false)
        val commands = captureCommands(player)
        val callbacks = captureEventCallbacks(player)
        val a1 =
            mediaItemWithSubtitle(
                mediaId = "item-a1",
                uri = "https://media.example/r17-late-a",
                subtitle = "https://media.example/r17-late-a1.srt",
            )
        val a3 =
            mediaItemWithSubtitle(
                mediaId = "item-a3",
                uri = "https://media.example/r17-late-a",
                subtitle = "https://media.example/r17-late-a3.srt",
            )

        player.setMediaItems(mutableListOf(a1), 0, 0L)
        player.prepare()
        val a1Generation = sourceActionGeneration(player)
        assertEquals(
            1,
            commands.count { it == loadFileCommand("https://media.example/r17-late-a") },
        )

        player.setMediaItems(mutableListOf(a3), 0, 0L)
        player.prepare()
        val a3Generation = sourceActionGeneration(player, pending = true)

        player.playbackEvent(sourceEvent(MpvEvent.MPV_EVENT_START_FILE, 100L, 1L, a1Generation))
        callbacks.forEach(Runnable::run)
        callbacks.clear()
        player.playbackEvent(sourceEvent(MpvEvent.MPV_EVENT_END_FILE, 100L, 1L, a1Generation))
        callbacks.forEach(Runnable::run)
        callbacks.clear()
        assertEquals(
            "旧 generation 的 barrier 变化不得改变 A3 source 发布",
            2,
            commands.count { it == loadFileCommand("https://media.example/r17-late-a") },
        )
        commands.clear()

        player.playbackEvent(sourceEvent(MpvEvent.MPV_EVENT_START_FILE, 100L, 1L, a1Generation))
        callbacks.forEach(Runnable::run)
        callbacks.clear()
        assertEquals(
            "旧 generation 的迟到 START 不得领取 A3 initial commands",
            0,
            subtitleCommandCount(commands),
        )

        player.playbackEvent(sourceEvent(MpvEvent.MPV_EVENT_START_FILE, 200L, 2L, a3Generation))
        callbacks.forEach(Runnable::run)
        assertEquals("A3 真 START 必须领取 initial commands 恰好一次", 1, subtitleCommandCount(commands))
    }

    @Test
    fun `old owner late start without terminal drains pending current source`() {
        val player = allocatedMpvPlayerForStartFile(epoch = 409L)
        setField(player, "isPlayerReady", false)
        val commands = captureCommands(player)
        val callbacks = captureEventCallbacks(player)
        val a1 =
            mediaItemWithSubtitle(
                mediaId = "item-a1",
                uri = "https://media.example/r17-drain-a",
                subtitle = "https://media.example/r17-drain-a1.srt",
            )
        val a3 =
            mediaItemWithSubtitle(
                mediaId = "item-a3",
                uri = "https://media.example/r17-drain-a",
                subtitle = "https://media.example/r17-drain-a3.srt",
            )

        player.setMediaItems(mutableListOf(a1), 0, 0L)
        player.prepare()
        val a1Generation = sourceActionGeneration(player)
        assertEquals(
            1,
            commands.count { it == loadFileCommand("https://media.example/r17-drain-a") },
        )
        player.setMediaItems(mutableListOf(a3), 0, 0L)
        player.prepare()
        commands.clear()

        player.playbackEvent(sourceEvent(MpvEvent.MPV_EVENT_START_FILE, 300L, 3L, a1Generation))
        callbacks.forEach(Runnable::run)
        callbacks.clear()
        var steps = 0
        while (
            commands.none { it == loadFileCommand("https://media.example/r17-drain-a") } &&
                steps < 8 &&
                runNextRecovery(player)
        ) {
            steps++
        }
        assertEquals(
            "旧 owner START 后无 terminal 时必须 drain 并收敛 latest A3: steps=$steps commands=$commands",
            1,
            commands.count { it == loadFileCommand("https://media.example/r17-drain-a") },
        )
    }

    @Test
    fun `source retry exhaustion replaces barrier and converges latest current`() {
        val player = allocatedMpvPlayerForStartFile(epoch = 419L)
        setField(player, "isPlayerReady", false)
        setField(player, "commandsClosed", AtomicBoolean(false))
        val commands = mutableListOf<List<String>>()
        var sourceFailuresRemaining = 4
        val successfulSources = mutableListOf<List<String>>()
        installCommandExecutor(player) { captured ->
            val command = captured.arguments
            commands += command
            if (command.firstOrNull() == "loadfile" && sourceFailuresRemaining-- > 0) {
                captured.complete(false)
            } else {
                captured.complete()
                if (command.firstOrNull() == "loadfile") successfulSources += command
            }
        }
        val callbacks = captureEventCallbacks(player)
        player.setMediaItems(
            mutableListOf(
                mediaItemWithSubtitle(
                    mediaId = "item-marker-exhausted",
                    uri = "https://media.example/r17-marker-exhausted",
                    subtitle = "https://media.example/r17-marker-exhausted.srt",
                )
            ),
            0,
            0L,
        )
        commands.clear()

        player.prepare()
        var steps = 0
        while (successfulSources.isEmpty() && steps < 12 && runNextRecovery(player)) {
            steps++
        }
        assertTrue(
            "source 重试必须由 source identity 收敛，不得写 marker 命令: $commands",
            commands.none { it.firstOrNull() == "set" },
        )
        assertEquals(
            "source 重试耗尽后必须替换 barrier 并继续 latest current: steps=$steps commands=$commands",
            1,
            successfulSources.count {
                it == loadFileCommand("https://media.example/r17-marker-exhausted")
            },
        )
        val generation = sourceActionGeneration(player)
        player.playbackEvent(sourceEvent(MpvEvent.MPV_EVENT_START_FILE, 400L, 4L, generation))
        callbacks.forEach(Runnable::run)
        assertEquals("source 替换后 initial commands 必须恰好一次", 1, subtitleCommandCount(commands))
    }

    @Test
    fun `source and drain retry exhaustion cannot leave latest current starved`() {
        val player = allocatedMpvPlayerForStartFile(epoch = 421L)
        setField(player, "isPlayerReady", false)
        setField(player, "commandsClosed", AtomicBoolean(false))
        val commands = mutableListOf<List<String>>()
        var drainFailuresRemaining = 3
        installCommandExecutor(player) { captured ->
            val command = captured.arguments
            commands += command
            when {
                command.firstOrNull() == "stop" && drainFailuresRemaining-- > 0 ->
                    captured.complete(false)
                else -> captured.complete()
            }
        }
        val callbacks = captureEventCallbacks(player)
        player.setMediaItems(
            mutableListOf(
                mediaItemWithSubtitle(
                    mediaId = "item-drain-a1",
                    uri = "https://media.example/r17-drain-exhaust-a",
                    subtitle = "https://media.example/r17-drain-exhaust-a1.srt",
                )
            ),
            0,
            0L,
        )
        player.prepare()
        player.setMediaItems(
            mutableListOf(
                mediaItemWithSubtitle(
                    mediaId = "item-drain-a3",
                    uri = "https://media.example/r17-drain-exhaust-b",
                    subtitle = "https://media.example/r17-drain-exhaust-a3.srt",
                )
            ),
            0,
            0L,
        )
        player.prepare()
        commands.clear()

        var steps = 0
        while (
            commands.none { it == loadFileCommand("https://media.example/r17-drain-exhaust-b") } &&
                steps < 16 &&
                runNextRecovery(player)
        ) {
            steps++
        }
        assertEquals(
            "drain 重试耗尽后必须释放旧 barrier 并收敛 latest current: steps=$steps commands=$commands",
            1,
            commands.count { it == loadFileCommand("https://media.example/r17-drain-exhaust-b") },
        )
        val generation = sourceActionGeneration(player)
        player.playbackEvent(sourceEvent(MpvEvent.MPV_EVENT_START_FILE, 500L, 5L, generation))
        callbacks.forEach(Runnable::run)
        assertEquals("drain 耗尽恢复后 initial commands 必须恰好一次", 1, subtitleCommandCount(commands))
    }

    @Test
    fun `production seam rejects pre-command same-entry start then accepts current start once`() {
        val backend = BlockingSourceCommandBackend()
        val mpv = MPVLib(1L, backend)
        backend.attach(mpv)
        val player = allocatedMpvPlayerForStartFile(epoch = 431L)
        setField(player, "isPlayerReady", false)
        val commands = installProductionSourceSeam(player, mpv)
        val callbacks = captureEventCallbacks(player)
        mpv.addObserver(player)

        player.setMediaItems(
            mutableListOf(
                mediaItemWithSubtitle(
                    mediaId = "item-r18-race",
                    uri = "https://media.example/r18-race",
                    subtitle = "https://media.example/r18-race.srt",
                )
            ),
            0,
            0L,
        )
        commands.clear()
        player.prepare()
        val gate = checkNotNull(getField(mpv, "sourceEvents"))
        val staleThread =
            checkNotNull(backend.staleThread) { "source command seam was not executed" }
        staleThread.join(2_000L)

        assertEquals(
            "racing event must enter sourceEventLock before command returns",
            Thread.State.BLOCKED,
            backend.observedState,
        )
        assertFalse(
            "racing event thread must finish after command releases the gate",
            staleThread.isAlive,
        )
        assertEquals(
            "pre-command same-entry START 不得被 arm 后消费",
            false,
            getField(gate, "startObserved"),
        )
        assertEquals(
            "pre-command same-entry START 不得领取 successor initial commands: commands=$commands",
            0,
            subtitleCommandCount(commands),
        )

        backend.emitStart(mpv)
        callbacks.forEach(Runnable::run)
        assertEquals("arm 后合法 current START 必须一次成功", 1, subtitleCommandCount(commands))
        backend.emitStart(mpv)
        callbacks.forEach(Runnable::run)
        assertEquals("current START 不得重复领取 initial commands", 1, subtitleCommandCount(commands))
    }

    @Test
    fun `production seam rejects same-entry ingress after native snapshot before arm`() {
        val backend = GateArmRaceBackend()
        val mpv = MPVLib(1L, backend)
        val player = allocatedMpvPlayerForStartFile(epoch = 432L)
        setField(player, "isPlayerReady", false)
        val commands = installProductionSourceSeam(player, mpv)
        val callbacks = captureEventCallbacks(player)
        mpv.addObserver(player)

        player.setMediaItems(
            mutableListOf(
                mediaItemWithSubtitle(
                    mediaId = "item-r19-barrier",
                    uri = "https://media.example/r19-barrier",
                    subtitle = "https://media.example/r19-barrier.srt",
                )
            ),
            0,
            0L,
        )
        commands.clear()
        backend.reset()

        val gate = checkNotNull(getField(mpv, "sourceEvents"))
        val gateHeld = CountDownLatch(1)
        val releaseArm = CountDownLatch(1)
        val armBlocker =
            Thread(
                    {
                        synchronized(gate) {
                            gateHeld.countDown()
                            check(releaseArm.await(2, TimeUnit.SECONDS)) {
                                "test arm blocker timed out"
                            }
                        }
                    },
                    "r19-arm-blocker",
                )
                .also(Thread::start)
        check(gateHeld.await(2, TimeUnit.SECONDS)) { "test arm blocker did not hold the gate" }

        val prepareThread = Thread({ player.prepare() }, "r19-prepare")
        setField(player, "listeners", ListenerSet<Player.Listener>(prepareThread))
        prepareThread.start()
        check(backend.commandEntered.await(2, TimeUnit.SECONDS)) {
            "production source command did not reach native backend"
        }
        val commandThread = checkNotNull(backend.commandThread)
        backend.releaseCommand()
        awaitThreadState(commandThread, Thread.State.BLOCKED)
        val staleThread =
            Thread({ backend.emitStart(mpv) }, "r19-stale-ingress").also(Thread::start)
        try {
            awaitThreadState(staleThread, Thread.State.BLOCKED)
        } finally {
            releaseArm.countDown()
        }
        prepareThread.join(2_000L)
        commandThread.join(2_000L)
        staleThread.join(2_000L)
        armBlocker.join(2_000L)
        callbacks.forEach(Runnable::run)

        assertFalse("prepare caller must finish", prepareThread.isAlive)
        assertFalse("source command must finish", commandThread.isAlive)
        assertFalse("pre-arm ingress thread must finish", staleThread.isAlive)
        assertEquals(
            "native snapshot 后、arm 前进入的 same-entry START 不得领取 successor initial commands",
            0,
            subtitleCommandCount(commands),
        )
    }

    @Test
    fun `source event int cannot claim current initial commands through production observer`() {
        val backend = ImmediateSourceCommandBackend()
        val mpv = MPVLib(1L, backend)
        val player = allocatedMpvPlayerForStartFile(epoch = 433L)
        setField(player, "isPlayerReady", false)
        val commands = installProductionSourceSeam(player, mpv)
        val callbacks = captureEventCallbacks(player)
        mpv.addObserver(player)
        player.setMediaItems(
            mutableListOf(
                mediaItemWithSubtitle(
                    mediaId = "item-r18-event-int",
                    uri = "https://media.example/r18-event-int",
                    subtitle = "https://media.example/r18-event-int.srt",
                )
            ),
            0,
            0L,
        )
        commands.clear()
        player.prepare()

        listOf(
                MpvEvent.MPV_EVENT_START_FILE,
                MpvEvent.MPV_EVENT_FILE_LOADED,
                MpvEvent.MPV_EVENT_END_FILE,
            )
            .forEach { eventId ->
                mpv.event(eventId)
                callbacks.forEach(Runnable::run)
            }

        assertEquals(
            "source-event event(int) START/FILE_LOADED/END 必须全部 fail closed",
            0,
            subtitleCommandCount(commands),
        )
    }

    @Test
    fun `pre-arm current start is recovered by bounded same-entry rearm`() {
        val backend = BlockingSourceCommandBackend()
        val mpv = MPVLib(1L, backend)
        backend.attach(mpv)
        val player = allocatedMpvPlayerForStartFile(epoch = 435L)
        setField(player, "isPlayerReady", false)
        val commands = installProductionSourceSeam(player, mpv)
        val callbacks = captureEventCallbacks(player)
        mpv.addObserver(player)
        player.setMediaItems(
            mutableListOf(
                mediaItemWithSubtitle(
                    mediaId = "item-r18-bounded",
                    uri = "https://media.example/r18-bounded",
                    subtitle = "https://media.example/r18-bounded.srt",
                )
            ),
            0,
            0L,
        )
        commands.clear()
        player.prepare()
        backend.staleThread?.join(2_000L)
        assertEquals(
            "scenario requires the pre-arm event to be blocked",
            Thread.State.BLOCKED,
            backend.observedState,
        )
        val gate = checkNotNull(getField(mpv, "sourceEvents"))
        assertEquals(
            "pre-arm current START 必须在 arm 边界前 fail closed",
            false,
            getField(gate, "startObserved"),
        )
        assertEquals(
            "pre-arm current START 不得发布 initial commands",
            0,
            subtitleCommandCount(commands),
        )

        var steps = 0
        while (
            commands.count { it.firstOrNull() == "loadfile" } < 2 &&
                steps < 8 &&
                runNextRecovery(player)
        ) {
            steps++
        }
        assertEquals(
            "pre-arm current START 被拒绝后必须 bounded drain + 同 entry rearm: steps=$steps commands=$commands",
            2,
            commands.count { it.firstOrNull() == "loadfile" },
        )

        backend.emitStart(mpv)
        callbacks.forEach(Runnable::run)
        assertEquals(
            "bounded rearm 后 current START 必须发布 initial commands 恰好一次",
            1,
            subtitleCommandCount(commands),
        )
    }

    @Test
    fun `nonzero initial window publishes bootstrap append and target source sequence once`() {
        val first =
            mediaItemWithSubtitle(
                mediaId = "item-zero",
                uri = "https://media.example/window-zero",
                subtitle = "https://media.example/window-zero.srt",
            )
        val second = mediaItem("item-one", "https://media.example/window-one")
        val target =
            mediaItemWithSubtitle(
                mediaId = "item-two",
                uri = "https://media.example/window-two",
                subtitle = "https://media.example/window-target.srt",
            )
        val player =
            allocatedMpvPlayer(
                sourceEpoch = 283L,
                mediaItems = mutableListOf(),
                currentIndex = 0,
                sourcePosition = MpvSourcePositionState(sourceEpoch = 283L),
            )
        setField(player, "isPlayerReady", false)
        val commands = captureCommands(player, includeSourceMarkers = true)
        val callbacks = captureEventCallbacks(player)

        player.setMediaItems(mutableListOf(first, second, target), 2, 0L)
        commands.clear()
        player.prepare()

        assertEquals(
            "非零 initial window 必须先发布首个真实 loadfile: $commands",
            1,
            commands.count { it == loadFileCommand("https://media.example/window-zero") },
        )
        assertEquals(
            "非零 initial window 必须 append 其余项: $commands",
            1,
            commands.count {
                it == listOf("loadfile", "https://media.example/window-one", "append")
            },
        )
        assertEquals(
            "非零 initial window 必须 append 目标项: $commands",
            1,
            commands.count {
                it == listOf("loadfile", "https://media.example/window-two", "append")
            },
        )

        deliverSourceEvent(player, MpvEvent.MPV_EVENT_START_FILE)
        callbacks.forEach(Runnable::run)
        deliverSourceEvent(player, MpvEvent.MPV_EVENT_FILE_LOADED)
        callbacks.forEach(Runnable::run)
        assertEquals(
            "bootstrap terminal 后目标 playlist index 必须恰好一次: $commands",
            1,
            commands.count { it == listOf("playlist-play-index", "2") },
        )

        deliverSourceEvent(player, MpvEvent.MPV_EVENT_START_FILE)
        callbacks.forEach(Runnable::run)
        assertEquals("目标 source initial commands 必须恰好一次", 1, subtitleCommandCount(commands))
        deliverSourceEvent(player, MpvEvent.MPV_EVENT_START_FILE)
        callbacks.forEach(Runnable::run)
        assertEquals("目标 source 额外 START 不得重复", 1, subtitleCommandCount(commands))
        assertEquals(
            "目标 playlist-play-index 不得重复",
            1,
            commands.count { it == listOf("playlist-play-index", "2") },
        )
    }

    @Test
    fun `nonterminal source deadline drains before pending current command`() {
        val player = allocatedMpvPlayerForStartFile(epoch = 307L)
        setField(player, "isPlayerReady", false)
        val commands = captureCommands(player, includeSourceMarkers = true)
        val a1 =
            mediaItemWithSubtitle(
                mediaId = "item-a1",
                uri = "https://media.example/drain-a",
                subtitle = "https://media.example/drain-a1.srt",
            )
        val a3 =
            mediaItemWithSubtitle(
                mediaId = "item-a3",
                uri = "https://media.example/drain-a",
                subtitle = "https://media.example/drain-a3.srt",
            )

        player.setMediaItems(mutableListOf(a1), 0, 0L)
        player.prepare()
        player.setMediaItems(mutableListOf(a3), 0, 0L)
        player.prepare()
        commands.clear()

        assertTrue(
            "旧 source 无 terminal 时不得提前发布 pending current: $commands",
            commands.none { it == loadFileCommand("https://media.example/drain-a") },
        )
        var recoverySteps = 0
        while (
            commands.none { it == loadFileCommand("https://media.example/drain-a") } &&
                recoverySteps < 8 &&
                runNextRecovery(player)
        ) {
            recoverySteps++
        }
        assertEquals(
            "有界 drain/barrier 后 current source command 必须恰好一次: steps=$recoverySteps commands=$commands",
            1,
            commands.count { it == loadFileCommand("https://media.example/drain-a") },
        )
    }

    @Test
    fun `invalid playlist indices fail atomically before state epoch pending or native commands`() {
        val player =
            allocatedMpvPlayer(
                sourceEpoch = 313L,
                mediaItems =
                    mutableListOf(
                        mediaItem("item-a", "https://media.example/index-a"),
                        mediaItem("item-b", "https://media.example/index-b"),
                        mediaItem("item-c", "https://media.example/index-c"),
                    ),
                currentIndex = 1,
                sourcePosition = MpvSourcePositionState(sourceEpoch = 313L),
            )
        val commands = captureCommands(player, includeSourceMarkers = true)
        val inserted = mediaItem("item-new", "https://media.example/index-new")
        val before = itemIds(player)
        val epoch = player.currentSourceEpoch()
        val index = player.currentMediaItemIndex

        fun assertRejected(action: () -> Unit) {
            val outcome = runCatching(action)
            assertTrue(
                "非法 index/range 必须以 IllegalArgumentException 拒绝: $outcome",
                outcome.exceptionOrNull() is IllegalArgumentException,
            )
            assertEquals("非法操作不得改变 playlist", before, itemIds(player))
            assertEquals("非法操作不得改变 current index", index, player.currentMediaItemIndex)
            assertEquals("非法操作不得推进 epoch", epoch, player.currentSourceEpoch())
            assertNull("非法操作不得创建 in-flight", getField(player, "sourceStartInFlight"))
            assertNull("非法操作不得创建 pending", getField(player, "pendingSourceStart"))
            assertTrue("非法操作不得发 native command", commands.isEmpty())
        }

        assertRejected { player.addMediaItems(-1, mutableListOf(inserted)) }
        assertRejected { player.addMediaItems(4, mutableListOf(inserted)) }
        assertRejected { player.removeMediaItems(-1, 1) }
        assertRejected { player.removeMediaItems(0, 4) }
        assertRejected { player.moveMediaItems(-1, 1, 2) }
        assertRejected { player.moveMediaItems(0, 1, -1) }
        assertRejected { player.moveMediaItems(1, 1, 2) }
        assertRejected { player.moveMediaItems(0, 2, 2) }
    }

    @Test
    fun `empty initial command ownership never publishes or replays`() {
        val epoch = 71L
        val player =
            allocatedMpvPlayer(
                sourceEpoch = epoch,
                mediaItems = mutableListOf(mediaItem("item-a", "https://media.example/item-a")),
                currentIndex = 0,
                sourcePosition = MpvSourcePositionState(sourceEpoch = epoch),
            )
        setField(player, "isPlayerReady", false)
        val commands = captureCommands(player)
        val callbacks = captureEventCallbacks(player)

        deliverSourceEvent(player, MpvEvent.MPV_EVENT_START_FILE)
        callbacks.forEach(Runnable::run)

        assertTrue("ownership 为空时不得发布快照或重放初始化", commands.isEmpty())
    }

    @Test
    fun `claimed START_FILE is rejected when source mutates before posted handler`() {
        val epoch = 73L
        val player =
            allocatedMpvPlayer(
                sourceEpoch = epoch,
                mediaItems = mutableListOf(mediaItem("item-a", "https://media.example/item-a")),
                currentIndex = 0,
                sourcePosition = MpvSourcePositionState(sourceEpoch = epoch),
            )
        setField(player, "isPlayerReady", false)
        val commands = captureCommands(player, deliverSourceMarkers = false)
        val initialCommands = listOf(arrayOf("sub-add", "a.srt", "auto", "a", "zh"))
        val nextCommands = listOf(arrayOf("sub-add", "c.srt", "auto", "c", "zh"))

        replaceInitialCommands(player, initialCommands)
        val callbacks = captureEventCallbacks(player)
        deliverSourceEvent(player, MpvEvent.MPV_EVENT_START_FILE)
        player.setMediaItems(
            mutableListOf(mediaItem("item-c", "https://media.example/item-c")),
            0,
            0L,
        )
        replaceInitialCommands(player, nextCommands)
        commands.clear()
        callbacks.forEach(Runnable::run)
        callbacks.clear()
        assertTrue("claim 后 mutation 必须让旧 posted handler fail closed", commands.isEmpty())

        setField(player, "released", true)
        deliverSourceEvent(player, MpvEvent.MPV_EVENT_FILE_LOADED)
        callbacks.forEach(Runnable::run)
        callbacks.clear()
        setField(player, "released", false)
        deliverSourceMarker(player, 1)
        commands.clear()
        deliverStartFile(player, callbacks)
        assertEquals("新 source 快照必须随后发布一次", nextCommands.map { it.toList() }, commands)
    }

    @Test
    fun `ambiguous START_FILE without identity cannot replay or publish`() {
        val epoch = 79L
        val player =
            allocatedMpvPlayer(
                sourceEpoch = epoch,
                mediaItems = mutableListOf(mediaItem("item-a", "https://media.example/item-a")),
                currentIndex = 0,
                sourcePosition = MpvSourcePositionState(sourceEpoch = epoch),
            )
        setField(player, "isPlayerReady", false)
        val commands = captureCommands(player)
        val newCommands = listOf(arrayOf("sub-add", "new.srt", "auto", "new", "zh"))

        replaceInitialCommands(player, listOf(arrayOf("sub-add", "old.srt", "auto", "old", "zh")))
        player.setMediaItems(
            mutableListOf(mediaItem("item-b", "https://media.example/item-b")),
            0,
            0L,
        )
        replaceInitialCommands(player, newCommands)
        commands.clear()
        setNullableField(player, "sourceStartInFlight", null)
        setNullableField(player, "pendingSourceStart", null)
        val callbacks = captureEventCallbacks(player)

        deliverSourceEvent(player, MpvEvent.MPV_EVENT_START_FILE)
        callbacks.forEach(Runnable::run)
        callbacks.clear()

        assertTrue("无法归属的 START_FILE 必须 fail closed: $commands", commands.isEmpty())

        commands.clear()
        deliverSourceEvent(player, MpvEvent.MPV_EVENT_START_FILE)
        callbacks.forEach(Runnable::run)
        assertTrue("重复的无 identity START_FILE 仍须拒绝: $commands", commands.isEmpty())
    }

    @Test
    fun `same URI reload recovers current source from stale owner without duplicate`() {
        val epoch = 83L
        val player =
            allocatedMpvPlayer(
                sourceEpoch = epoch,
                mediaItems = mutableListOf(mediaItem("item-a", "https://media.example/item-a")),
                currentIndex = 0,
                sourcePosition = MpvSourcePositionState(sourceEpoch = epoch),
            )
        setField(player, "isPlayerReady", false)
        val commands = captureCommands(player, deliverSourceMarkers = false)
        val currentCommands = listOf(arrayOf("sub-add", "current-a.srt", "auto", "a3", "zh"))

        replaceInitialCommands(player, listOf(arrayOf("sub-add", "old-a.srt", "auto", "a1", "zh")))
        player.setMediaItems(
            mutableListOf(mediaItem("item-b", "https://media.example/item-b")),
            0,
            0L,
        )
        replaceInitialCommands(
            player,
            listOf(arrayOf("sub-add", "middle-b.srt", "auto", "b2", "zh")),
        )
        player.setMediaItems(
            mutableListOf(mediaItem("item-a", "https://media.example/item-a")),
            0,
            0L,
        )
        replaceInitialCommands(player, currentCommands)
        commands.clear()
        val callbacks = captureEventCallbacks(player)

        deliverStaleStartFile(player, callbacks)
        assertTrue("同 URI 旧 owner event 必须先 fail closed: $commands", commands.isEmpty())

        deliverSourceMarker(player, 2)
        commands.clear()
        deliverStartFile(player, callbacks)
        assertEquals(
            "旧 A1 terminal 后必须只推进 A3 source command 一次",
            listOf(listOf("playlist-play-index", "0")),
            commands,
        )
        commands.clear()
        deliverStartFile(player, callbacks)
        assertEquals("恢复后的当前 A3 必须发布恰好一次", currentCommands.map { it.toList() }, commands)

        deliverSourceEvent(player, MpvEvent.MPV_EVENT_START_FILE)
        callbacks.forEach(Runnable::run)
        assertEquals("当前 A3 不得因额外 START_FILE 重复发布", currentCommands.map { it.toList() }, commands)
    }

    @Test
    fun `delayed same URI stale START_FILE never publishes current source commands`() {
        val epoch = 89L
        val player =
            allocatedMpvPlayer(
                sourceEpoch = epoch,
                mediaItems = mutableListOf(mediaItem("item-a", "https://media.example/item-a")),
                currentIndex = 0,
                sourcePosition = MpvSourcePositionState(sourceEpoch = epoch),
            )
        setField(player, "isPlayerReady", false)
        val commands = captureCommands(player, deliverSourceMarkers = false)
        val currentCommands = listOf(arrayOf("sub-add", "current-a.srt", "auto", "a3", "zh"))

        replaceInitialCommands(player, listOf(arrayOf("sub-add", "old-a.srt", "auto", "a1", "zh")))
        player.setMediaItems(
            mutableListOf(mediaItem("item-b", "https://media.example/item-b")),
            0,
            0L,
        )
        replaceInitialCommands(
            player,
            listOf(arrayOf("sub-add", "middle-b.srt", "auto", "b2", "zh")),
        )
        player.setMediaItems(
            mutableListOf(mediaItem("item-a", "https://media.example/item-a")),
            0,
            0L,
        )
        replaceInitialCommands(player, currentCommands)
        commands.clear()
        val callbacks = captureEventCallbacks(player)

        deliverStaleStartFile(player, callbacks)
        assertTrue("延迟旧 A event 必须 fail closed: $commands", commands.isEmpty())

        deliverSourceMarker(player, 2)
        commands.clear()
        deliverStartFile(player, callbacks)
        assertEquals(
            "旧 A1 terminal 后必须只推进 A3 source command 一次",
            listOf(listOf("playlist-play-index", "0")),
            commands,
        )
        commands.clear()
        deliverStartFile(player, callbacks)
        assertEquals("恢复后的真实当前 A event 才可发布 A3", currentCommands.map { it.toList() }, commands)
    }

    @Test
    fun `unowned START_FILE without identity cannot publish current source`() {
        val epoch = 97L
        val player =
            allocatedMpvPlayer(
                sourceEpoch = epoch,
                mediaItems = mutableListOf(mediaItem("item-a", "https://media.example/item-a")),
                currentIndex = 0,
                sourcePosition = MpvSourcePositionState(sourceEpoch = epoch),
            )
        setField(player, "isPlayerReady", false)
        val commands = captureCommands(player)
        val currentCommands = listOf(arrayOf("sub-add", "current-b.srt", "auto", "b2", "zh"))

        replaceInitialCommands(player, listOf(arrayOf("sub-add", "old-a.srt", "auto", "a1", "zh")))
        player.setMediaItems(
            mutableListOf(mediaItem("item-b", "https://media.example/item-b")),
            0,
            0L,
        )
        replaceInitialCommands(player, currentCommands)
        commands.clear()
        setNullableField(player, "sourceStartInFlight", null)
        setNullableField(player, "pendingSourceStart", null)
        val callbacks = captureEventCallbacks(player)

        deliverSourceEvent(player, MpvEvent.MPV_EVENT_START_FILE)
        callbacks.forEach(Runnable::run)
        callbacks.clear()
        assertTrue("无法归属的 START_FILE 必须 fail closed: $commands", commands.isEmpty())

        commands.clear()
        deliverSourceEvent(player, MpvEvent.MPV_EVENT_START_FILE)
        callbacks.forEach(Runnable::run)
        callbacks.clear()
        assertTrue("重复的无 identity START_FILE 不得发布 commands: $commands", commands.isEmpty())

        deliverSourceEvent(player, MpvEvent.MPV_EVENT_START_FILE)
        callbacks.forEach(Runnable::run)
        assertTrue("后续无 identity START_FILE 仍须拒绝: $commands", commands.isEmpty())
    }

    @Test
    fun `percent encoded source accepts equivalent path and exact replay once`() {
        val epoch = 101L
        val encoded = "https://media.example/My%20File.mkv"
        val player =
            allocatedMpvPlayer(
                sourceEpoch = epoch,
                mediaItems = mutableListOf(mediaItem("item-encoded", encoded)),
                currentIndex = 0,
                sourcePosition = MpvSourcePositionState(sourceEpoch = epoch),
            )
        setField(player, "isPlayerReady", false)
        val commands = captureCommands(player)
        val currentCommands = listOf(arrayOf("sub-add", "encoded.srt", "auto", "encoded", "zh"))
        installInitialCommands(player, currentCommands, encoded)
        armSourceStart(player, 0, "https://media.example/My File.mkv")
        commands.clear()
        val callbacks = captureEventCallbacks(player)

        deliverStartFile(player, callbacks)
        assertEquals(
            "percent encoding 等价 path 必须直接归属当前 source",
            currentCommands.map { it.toList() },
            commands,
        )

        callbacks.clear()
        deliverSourceEvent(player, MpvEvent.MPV_EVENT_START_FILE)
        callbacks.forEach(Runnable::run)
        assertEquals(
            "等价 path 之后回到 exact path 不得重复发布",
            currentCommands.map { it.toList() },
            commands,
        )
    }

    @Test
    fun `three owner same URI current START_FILE publishes final commands exactly once`() {
        val player = allocatedMpvPlayerForStartFile(epoch = 131L)
        val commands = captureCommands(player, deliverSourceMarkers = false)
        val currentCommands = listOf(arrayOf("sub-add", "a3.srt", "auto", "a3", "zh"))

        replaceInitialCommands(player, listOf(arrayOf("sub-add", "a1.srt", "auto", "a1", "zh")))
        player.setMediaItems(
            mutableListOf(mediaItem("item-b", "https://media.example/item-b")),
            0,
            0L,
        )
        replaceInitialCommands(player, listOf(arrayOf("sub-add", "b2.srt", "auto", "b2", "zh")))
        player.setMediaItems(
            mutableListOf(mediaItem("item-a", "https://media.example/item-a")),
            0,
            0L,
        )
        replaceInitialCommands(player, currentCommands)
        commands.clear()
        val callbacks = captureEventCallbacks(player)

        deliverStartFile(player, callbacks)
        commands.clear()
        deliverSourceMarker(player, 2)
        commands.clear()
        deliverStartFile(player, callbacks)
        assertEquals(
            "唯一 current 同 URI event 必须一次发布 A3",
            currentCommands.map { it.toList() },
            commands,
        )

        deliverStartFile(player, callbacks)
        assertEquals(
            "额外 current START_FILE 不得重复发布 A3",
            currentCommands.map { it.toList() },
            commands,
        )
    }

    @Test
    fun `repeated stale same URI START_FILE events publish no current commands`() {
        val player = allocatedMpvPlayerForStartFile(epoch = 137L)
        val commands = captureCommands(player, deliverSourceMarkers = false)
        val currentCommands = listOf(arrayOf("sub-add", "a3.srt", "auto", "a3", "zh"))

        replaceInitialCommands(player, listOf(arrayOf("sub-add", "a1.srt", "auto", "a1", "zh")))
        val staleEpoch = player.currentSourceEpoch()
        player.setMediaItems(
            mutableListOf(mediaItem("item-b", "https://media.example/item-b")),
            0,
            0L,
        )
        replaceInitialCommands(player, listOf(arrayOf("sub-add", "b2.srt", "auto", "b2", "zh")))
        player.setMediaItems(
            mutableListOf(mediaItem("item-a", "https://media.example/item-a")),
            0,
            0L,
        )
        replaceInitialCommands(player, currentCommands)
        commands.clear()
        val callbacks = captureEventCallbacks(player)

        repeat(2) {
            deliverSourceEvent(player, MpvEvent.MPV_EVENT_START_FILE)
            callbacks.forEach(Runnable::run)
            callbacks.clear()
            assertFalse(
                "延迟旧 E1/A event #$it 不得发布 current A3: $commands",
                commands.any { it == currentCommands.single().toList() },
            )
        }
        setField(player, "released", true)
        deliverSourceEvent(player, MpvEvent.MPV_EVENT_FILE_LOADED)
        callbacks.forEach(Runnable::run)
        callbacks.clear()
        setField(player, "released", false)
        deliverSourceMarker(player, 2)
        commands.clear()
        deliverStartFile(player, callbacks)
        assertEquals("旧事件屏障完成后 current A3 只发布一次", currentCommands.map { it.toList() }, commands)
    }

    @Test
    fun `all same URI stale events before the unique current START_FILE cannot claim A3`() {
        val player = allocatedMpvPlayerForStartFile(epoch = 139L)
        val commands = captureCommands(player, deliverSourceMarkers = false)
        val currentCommands = listOf(arrayOf("sub-add", "a3.srt", "auto", "a3", "zh"))

        replaceInitialCommands(player, listOf(arrayOf("sub-add", "a1.srt", "auto", "a1", "zh")))
        player.setMediaItems(
            mutableListOf(mediaItem("item-b", "https://media.example/item-b")),
            0,
            0L,
        )
        replaceInitialCommands(player, listOf(arrayOf("sub-add", "b2.srt", "auto", "b2", "zh")))
        player.setMediaItems(
            mutableListOf(mediaItem("item-a", "https://media.example/item-a")),
            0,
            0L,
        )
        replaceInitialCommands(player, currentCommands)
        commands.clear()
        val callbacks = captureEventCallbacks(player)

        repeat(2) {
            deliverSourceEvent(player, MpvEvent.MPV_EVENT_START_FILE)
            callbacks.forEach(Runnable::run)
            callbacks.clear()
        }
        assertFalse(
            "全部 stale events 先到不得发布 A3: $commands",
            commands.any { it == currentCommands.single().toList() },
        )

        setField(player, "released", true)
        deliverSourceEvent(player, MpvEvent.MPV_EVENT_FILE_LOADED)
        callbacks.forEach(Runnable::run)
        callbacks.clear()
        setField(player, "released", false)
        deliverSourceMarker(player, 2)
        commands.clear()
        deliverStartFile(player, callbacks)
        assertEquals("唯一 current event 随后必须发布 A3 一次", currentCommands.map { it.toList() }, commands)
    }

    @Test
    fun `five owner ABA BA sequence publishes only the final source exactly once`() {
        val player = allocatedMpvPlayerForStartFile(epoch = 149L)
        val commands = captureCommands(player, deliverSourceMarkers = false)
        val currentCommands = listOf(arrayOf("sub-add", "a5.srt", "auto", "a5", "zh"))

        replaceInitialCommands(player, listOf(arrayOf("sub-add", "a1.srt", "auto", "a1", "zh")))
        for ((source, suffix) in listOf("b" to "b2", "a" to "a3", "b" to "b4")) {
            player.setMediaItems(
                mutableListOf(
                    mediaItem("item-$source-$suffix", "https://media.example/item-$source")
                ),
                0,
                0L,
            )
            replaceInitialCommands(
                player,
                listOf(arrayOf("sub-add", "$suffix.srt", "auto", "$suffix", "zh")),
            )
        }
        player.setMediaItems(
            mutableListOf(mediaItem("item-a-final", "https://media.example/item-a")),
            0,
            0L,
        )
        replaceInitialCommands(player, currentCommands)
        commands.clear()
        val callbacks = captureEventCallbacks(player)

        deliverStartFile(player, callbacks)
        commands.clear()
        deliverSourceMarker(player, 4)
        commands.clear()
        deliverStartFile(player, callbacks)
        assertEquals(
            "A/B/A/B/A 只有最终 current event 发布 A5",
            currentCommands.map { it.toList() },
            commands,
        )
    }

    @Test
    fun `encoded slash never claims a different literal path`() {
        assertEncodedSourceDoesNotClaim(
            owner = "https://media.example/a%2Fb.mkv",
            event = "https://media.example/a/b.mkv",
        )
    }

    @Test
    fun `encoded question mark never claims a literal query boundary`() {
        assertEncodedSourceDoesNotClaim(
            owner = "https://media.example/p%3Fx",
            event = "https://media.example/p?x",
        )
    }

    @Test
    fun `encoded ampersand never claims a literal query separator`() {
        assertEncodedSourceDoesNotClaim(
            owner = "https://media.example/q?x=%26",
            event = "https://media.example/q?x=&",
        )
    }

    @Test
    fun `public prepare installs owner before loadfile can deliver START_FILE`() {
        val media =
            mediaItemWithSubtitle(
                mediaId = "item-prepare",
                uri = "https://media.example/item-prepare",
                subtitle = "https://media.example/prepare.srt",
            )
        val player =
            allocatedMpvPlayer(
                sourceEpoch = 157L,
                mediaItems = mutableListOf(media),
                currentIndex = 0,
                sourcePosition = MpvSourcePositionState(sourceEpoch = 157L),
            )
        setField(player, "isPlayerReady", false)
        setField(player, "commandsClosed", AtomicBoolean(false))
        val commands = mutableListOf<List<String>>()
        installCommandExecutor(player) { captured ->
            commands += captured.arguments
            if (captured.arguments.firstOrNull() == "loadfile") {
                deliverSourceEvent(player, MpvEvent.MPV_EVENT_START_FILE)
            }
            captured.complete()
        }
        val callbacks = captureEventCallbacks(player)

        player.prepare()
        callbacks.forEach(Runnable::run)

        val expected =
            listOf(
                listOf(
                    "sub-add",
                    "https://media.example/prepare.srt",
                    "auto",
                    "test-subtitle",
                    "zh",
                )
            )
        assertEquals(
            "public prepare 必须先建立 owner，早到 START_FILE 仍发布初始化快照一次",
            expected,
            commands.filter { it.firstOrNull() == "sub-add" },
        )
    }

    @Test
    fun `public prepare never uses user-visible title as source ownership`() {
        val media =
            mediaItemWithSubtitle(
                mediaId = "item-non-visible-owner",
                uri = "https://media.example/non-visible-owner",
                subtitle = "https://media.example/non-visible-owner.srt",
            )
        val player =
            allocatedMpvPlayer(
                sourceEpoch = 173L,
                mediaItems = mutableListOf(media),
                currentIndex = 0,
                sourcePosition = MpvSourcePositionState(sourceEpoch = 173L),
            )
        setField(player, "isPlayerReady", false)
        setField(player, "commandsClosed", AtomicBoolean(false))
        val commands = mutableListOf<List<String>>()
        installCommandExecutor(player) { captured ->
            commands += captured.arguments
            captured.complete()
        }

        player.prepare()

        assertFalse(
            "source ownership 不得写入用户可见 title: $commands",
            commands.any { command ->
                command.any { argument ->
                    argument == "force-media-title" ||
                        argument.startsWith("force-media-title=") ||
                        argument == "media-title" ||
                        argument.startsWith("media-title=")
                }
            },
        )
    }

    @Test
    fun `queued E1 START_FILE cannot claim later E3 ownership`() {
        val e1 =
            mediaItemWithSubtitle(
                mediaId = "item-e1",
                uri = "https://media.example/e1",
                subtitle = "https://media.example/e1.srt",
            )
        val e3 =
            mediaItemWithSubtitle(
                mediaId = "item-e3",
                uri = "https://media.example/e3",
                subtitle = "https://media.example/e3.srt",
            )
        val player =
            allocatedMpvPlayer(
                sourceEpoch = 179L,
                mediaItems = mutableListOf(e1),
                currentIndex = 0,
                sourcePosition = MpvSourcePositionState(sourceEpoch = 179L),
            )
        setField(player, "isPlayerReady", false)
        val commands = captureCommands(player, deliverSourceMarkers = false)
        val callbacks = captureEventCallbacks(player)

        player.prepare()
        commands.clear()
        player.setMediaItems(mutableListOf(e3), 0, 0L)
        player.prepare()
        commands.clear()

        deliverSourceEvent(player, MpvEvent.MPV_EVENT_START_FILE)
        callbacks.forEach(Runnable::run)
        callbacks.clear()
        assertTrue(
            "E1 START_FILE 不得领取后来安装的 E3 ownership 或重放 E3: $commands",
            commands.isEmpty(),
        )

        setField(player, "released", true)
        deliverSourceEvent(player, MpvEvent.MPV_EVENT_FILE_LOADED)
        callbacks.forEach(Runnable::run)
        callbacks.clear()
        setField(player, "released", false)
        deliverSourceMarker(player, 1)
        commands.clear()
        deliverSourceEvent(player, MpvEvent.MPV_EVENT_START_FILE)
        callbacks.forEach(Runnable::run)

        assertEquals(
            "E3 自己的 START_FILE 必须发布其初始化命令恰好一次",
            listOf(
                listOf(
                    "sub-add",
                    "https://media.example/e3.srt",
                    "auto",
                    "test-subtitle",
                    "zh",
                )
            ),
            commands,
        )
    }

    @Test
    fun `current redirect path publishes initial commands exactly once`() {
        val media =
            mediaItemWithSubtitle(
                mediaId = "item-redirect",
                uri = "https://origin.example/start",
                subtitle = "https://origin.example/start.srt",
            )
        val player =
            allocatedMpvPlayer(
                sourceEpoch = 181L,
                mediaItems = mutableListOf(media),
                currentIndex = 0,
                sourcePosition = MpvSourcePositionState(sourceEpoch = 181L),
            )
        setField(player, "isPlayerReady", false)
        setField(player, "commandsClosed", AtomicBoolean(false))
        val commands = mutableListOf<List<String>>()
        installCommandExecutor(player) { captured ->
            commands += captured.arguments
            captured.complete()
        }
        val callbacks = captureEventCallbacks(player)

        player.prepare()
        commands.clear()
        deliverSourceEvent(player, MpvEvent.MPV_EVENT_START_FILE)
        callbacks.forEach(Runnable::run)
        callbacks.clear()

        val expected =
            listOf(
                listOf(
                    "sub-add",
                    "https://origin.example/start.srt",
                    "auto",
                    "test-subtitle",
                    "zh",
                )
            )
        assertEquals(
            "current ownership 已由序列化命令证明，redirect path 不得造成零发布: $commands",
            expected,
            commands,
        )

        deliverSourceEvent(player, MpvEvent.MPV_EVENT_START_FILE)
        callbacks.forEach(Runnable::run)
        assertEquals("redirect current source 不得重复发布", expected, commands)
    }

    @Test
    fun `old END_FILE before restart cannot release the next source attempt`() {
        val mediaA = mediaItem("item-a", "https://media.example/transition-a")
        val mediaB =
            mediaItemWithSubtitle(
                mediaId = "item-b",
                uri = "https://media.example/transition-b",
                subtitle = "https://media.example/transition-b.srt",
            )
        val player =
            allocatedMpvPlayer(
                sourceEpoch = 191L,
                mediaItems = mutableListOf(mediaA),
                currentIndex = 0,
                sourcePosition = MpvSourcePositionState(sourceEpoch = 191L),
            )
        setField(player, "isPlayerReady", true)
        val commands = captureCommands(player)
        val callbacks = captureEventCallbacks(player)

        player.setMediaItems(mutableListOf(mediaB), 0, 0L)
        player.prepare()
        commands.clear()

        setField(player, "released", true)
        deliverSourceEvent(player, MpvEvent.MPV_EVENT_END_FILE)
        callbacks.forEach(Runnable::run)
        callbacks.clear()
        setField(player, "released", false)

        deliverSourceEvent(player, MpvEvent.MPV_EVENT_START_FILE)
        callbacks.forEach(Runnable::run)
        callbacks.clear()
        setField(player, "released", true)
        deliverSourceEvent(player, MpvEvent.MPV_EVENT_FILE_LOADED)
        callbacks.forEach(Runnable::run)
        setField(player, "released", false)

        assertEquals(
            "旧 A END_FILE 不得释放 B attempt；B START_FILE 必须发布初始化命令恰好一次",
            listOf(
                listOf(
                    "sub-add",
                    "https://media.example/transition-b.srt",
                    "auto",
                    "test-subtitle",
                    "zh",
                )
            ),
            commands.filter { command -> command.firstOrNull() == "sub-add" },
        )
    }

    @Test
    fun `current START_FILE END_FILE releases pending source without duplicate END bypass`() {
        val mediaB =
            mediaItemWithSubtitle(
                mediaId = "item-b",
                uri = "https://media.example/terminal-b",
                subtitle = "https://media.example/terminal-b.srt",
            )
        val mediaC =
            mediaItemWithSubtitle(
                mediaId = "item-c",
                uri = "https://media.example/terminal-c",
                subtitle = "https://media.example/terminal-c.srt",
            )
        val player =
            allocatedMpvPlayer(
                sourceEpoch = 193L,
                mediaItems = mutableListOf(mediaB, mediaC),
                currentIndex = 0,
                sourcePosition = MpvSourcePositionState(sourceEpoch = 193L),
            )
        val commands = captureCommands(player, deliverSourceMarkers = false)
        val callbacks = captureEventCallbacks(player)

        player.prepare()
        player.seekTo(1, 0L)
        commands.clear()

        deliverSourceEvent(player, MpvEvent.MPV_EVENT_START_FILE)
        callbacks.forEach(Runnable::run)
        callbacks.clear()
        deliverSourceEvent(player, MpvEvent.MPV_EVENT_END_FILE)
        callbacks.forEach(Runnable::run)
        callbacks.clear()
        assertEquals(
            "B 的 START_FILE 后 END_FILE 必须在 identity 边界推进 pending C 一次",
            listOf(listOf("playlist-play-index", "1")),
            commands,
        )

        setField(player, "released", true)
        deliverSourceEvent(player, MpvEvent.MPV_EVENT_END_FILE)
        callbacks.forEach(Runnable::run)
        callbacks.clear()
        setField(player, "released", false)
        assertEquals("重复 END_FILE 不得释放尚未 START 的 C", 1, commands.size)

        deliverSourceEvent(player, MpvEvent.MPV_EVENT_START_FILE)
        callbacks.forEach(Runnable::run)
        val cSubtitleCommands = commands.filter { command -> command.firstOrNull() == "sub-add" }
        assertEquals(
            "C 只在其自己的 START_FILE 后发布初始化命令一次",
            listOf(
                listOf(
                    "sub-add",
                    "https://media.example/terminal-c.srt",
                    "auto",
                    "test-subtitle",
                    "zh",
                )
            ),
            cSubtitleCommands,
        )
    }

    @Test
    fun `FILE_LOADED without START_FILE releases barrier and advances pending source`() {
        val mediaB =
            mediaItemWithSubtitle(
                mediaId = "item-b",
                uri = "https://media.example/file-loaded-b",
                subtitle = "https://media.example/file-loaded-b.srt",
            )
        val mediaC =
            mediaItemWithSubtitle(
                mediaId = "item-c",
                uri = "https://media.example/file-loaded-c",
                subtitle = "https://media.example/file-loaded-c.srt",
            )
        val player =
            allocatedMpvPlayer(
                sourceEpoch = 197L,
                mediaItems = mutableListOf(mediaB, mediaC),
                currentIndex = 0,
                sourcePosition = MpvSourcePositionState(sourceEpoch = 197L),
            )
        val commands = captureCommands(player, deliverSourceMarkers = false)
        val callbacks = captureEventCallbacks(player)

        player.prepare()
        player.seekTo(1, 0L)
        commands.clear()
        deliverSourceEvent(player, MpvEvent.MPV_EVENT_FILE_LOADED)
        callbacks.forEach(Runnable::run)

        assertEquals(
            "B 仅 FILE_LOADED 时必须在 identity 边界推进 pending C 一次",
            listOf(listOf("playlist-play-index", "1")),
            commands,
        )
    }

    @Test
    fun `source attempt with no terminal keeps pending source fail closed`() {
        val mediaB =
            mediaItemWithSubtitle(
                mediaId = "item-b",
                uri = "https://media.example/no-event-b",
                subtitle = "https://media.example/no-event-b.srt",
            )
        val mediaC =
            mediaItemWithSubtitle(
                mediaId = "item-c",
                uri = "https://media.example/no-event-c",
                subtitle = "https://media.example/no-event-c.srt",
            )
        val player =
            allocatedMpvPlayer(
                sourceEpoch = 199L,
                mediaItems = mutableListOf(mediaB, mediaC),
                currentIndex = 0,
                sourcePosition = MpvSourcePositionState(sourceEpoch = 199L),
            )
        val commands = captureCommands(player)

        player.prepare()
        player.seekTo(1, 0L)
        commands.clear()

        assertTrue("B 无任何 event 时不得猜测 terminal 或发布 C", commands.isEmpty())
    }

    @Test
    fun `FILE_LOADED serializes pending source start exactly once when epoch is current`() {
        val mediaE1 =
            mediaItemWithSubtitle(
                mediaId = "item-e1",
                uri = "https://media.example/epoch-e1",
                subtitle = "https://media.example/epoch-e1.srt",
            )
        val mediaE3 =
            mediaItemWithSubtitle(
                mediaId = "item-e3",
                uri = "https://media.example/epoch-e3",
                subtitle = "https://media.example/epoch-e3.srt",
            )
        val player =
            allocatedMpvPlayer(
                sourceEpoch = 211L,
                mediaItems = mutableListOf(mediaE1),
                currentIndex = 0,
                sourcePosition = MpvSourcePositionState(sourceEpoch = 211L),
            )
        val commands = captureCommands(player, deliverSourceMarkers = false)
        val callbacks = captureEventCallbacks(player)

        player.prepare()
        player.setMediaItems(mutableListOf(mediaE3), 0, 0L)
        player.prepare()
        commands.clear()

        deliverSourceEvent(player, MpvEvent.MPV_EVENT_FILE_LOADED)
        callbacks.forEach(Runnable::run)
        assertEquals(
            "E1 FILE_LOADED 的 identity 边界必须串行发布 E3 一次",
            listOf(listOf("loadfile", "https://media.example/epoch-e3", "replace")),
            commands,
        )
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
    fun `string property callback with uninitialized source epoch fails closed`() {
        val unsafeField =
            Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }
        val unsafe = unsafeField.get(null) as Unsafe
        val player = unsafe.allocateInstance(MPVPlayer::class.java) as MPVPlayer
        setNullableField(player, "sourceEpoch", null)

        assertNull(getField(player, "sourceEpoch"))
        player.eventProperty("track-list", "[]")
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

    private fun mediaItem(mediaId: String, uri: String): MediaItem {
        val mediaUri = TestUriFactory.uri(uri)
        return MediaItem.Builder()
            .setMediaId(mediaId)
            .setUri(mediaUri)
            .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(mediaUri).build())
            .build()
    }

    private fun mediaItemWithSubtitle(
        mediaId: String,
        uri: String,
        subtitle: String,
    ): MediaItem {
        val mediaUri = TestUriFactory.uri(uri)
        val subtitleUri = TestUriFactory.uri(subtitle)
        return MediaItem.Builder()
            .setMediaId(mediaId)
            .setUri(mediaUri)
            .setSubtitleConfigurations(
                listOf(
                    MediaItem.SubtitleConfiguration.Builder(subtitleUri)
                        .setMimeType("application/x-subrip")
                        .setLanguage("zh")
                        .setLabel("test-subtitle")
                        .build()
                )
            )
            .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(mediaUri).build())
            .build()
    }

    private fun requestUriMediaItem(mediaId: String, uri: String): MediaItem =
        MediaItem.Builder()
            .setMediaId(mediaId)
            .setRequestMetadata(
                MediaItem.RequestMetadata.Builder().setMediaUri(TestUriFactory.uri(uri)).build()
            )
            .build()

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
        trySetField(player, "sourceStartAttemptSequence", AtomicLong())
        setField(player, "sourcePosition", sourcePosition)
        setField(player, "internalMediaItems", mediaItems)
        setField(player, "currentMediaItemIndex", currentIndex)
        setField(player, "initialCommands", mutableListOf<Array<String>>())
        setField(player, "externalSubtitleTrackIds", ConcurrentHashMap<String, Int>())
        setField(player, "pendingSideloadedSubtitleAdds", CopyOnWriteArraySet<String>())
        setField(player, "currentTracks", Tracks.EMPTY)
        setField(player, "playbackState", Player.STATE_IDLE)
        setField(player, "listeners", testListeners())
        trySetField(player, "handler", Handler())
        trySetField(player, "initialCommandOwners", ArrayDeque<Any>())
        trySetField(player, "eventPoster", { _: Runnable -> })
        capturedRecoveries[player] = installRecoverySchedulerIfPresent(player)
        return player
    }

    private fun allocatedMpvPlayerForStartFile(epoch: Long): MPVPlayer =
        allocatedMpvPlayer(
            sourceEpoch = epoch,
            mediaItems = mutableListOf(mediaItem("item-a", "https://media.example/item-a")),
            currentIndex = 0,
            sourcePosition = MpvSourcePositionState(sourceEpoch = epoch),
        )

    private fun testListeners(): ListenerSet<Player.Listener> = ListenerSet(Thread.currentThread())

    private fun sourcePosition(player: MPVPlayer): MpvSourcePositionState =
        getField(player, "sourcePosition") as MpvSourcePositionState

    private fun anchorThroughPlaybackRestartTransition(
        player: MPVPlayer,
        epoch: Long,
        positionMs: Long,
    ): Boolean {
        val restarted = sourcePosition(player).restart(epoch, positionMs) ?: return false
        setField(player, "sourcePosition", restarted)
        val publish =
            MPVPlayer::class
                .java
                .getDeclaredMethod("publishPositionDiscontinuity", Long::class.java)
        publish.isAccessible = true
        publish.invoke(player, epoch)
        return sourcePosition(player).positionMs == positionMs
    }

    private fun runHandlerCallbacks(handler: Handler) {
        handler.javaClass.getMethod("runAll").invoke(handler)
    }

    private fun itemAt(player: MPVPlayer, index: Int): MediaItem =
        @Suppress("UNCHECKED_CAST")
        (getField(player, "internalMediaItems") as MutableList<MediaItem>)[index]

    private fun itemIds(player: MPVPlayer): List<String> =
        @Suppress("UNCHECKED_CAST")
        (getField(player, "internalMediaItems") as MutableList<MediaItem>).map(MediaItem::mediaId)

    @Suppress("UNUSED_PARAMETER")
    private fun captureCommands(
        player: MPVPlayer,
        includeSourceMarkers: Boolean = false,
        deliverSourceMarkers: Boolean = true,
    ): MutableList<List<String>> {
        val commands = mutableListOf<List<String>>()
        capturedSourceMarkers[player] = mutableListOf()
        assertTrue(
            "MPVPlayer 必须暴露可捕获的 postCommand 执行入口",
            trySetField(player, "commandsClosed", AtomicBoolean(false)),
        )
        installCommandExecutor(player) { captured ->
            commands += captured.arguments
            captured.complete()
        }
        return commands
    }

    private class CapturedCommand(
        val arguments: List<String>,
        private val completion: Function1<Boolean, Unit>?,
    ) {
        val supportsCompletion: Boolean
            get() = completion != null

        fun complete(success: Boolean = true) {
            completion?.invoke(success)
        }
    }

    private class ScheduledRecovery(
        @Suppress("unused") val delayMs: Long,
        private val action: Function0<Unit>,
    ) {
        fun run() = action.invoke()
    }

    private fun installCommandExecutor(
        player: MPVPlayer,
        handler: (CapturedCommand) -> Unit,
    ) {
        val field =
            MPVPlayer::class.java.getDeclaredField("commandExecutor").apply { isAccessible = true }
        val proxy =
            Proxy.newProxyInstance(field.type.classLoader, arrayOf(field.type)) {
                proxy,
                method,
                args ->
                when (method.name) {
                    "invoke" -> {
                        val command = checkNotNull(args?.firstOrNull()) as Array<*>
                        @Suppress("UNCHECKED_CAST")
                        val completion = (args.getOrNull(1) as? Function1<Boolean, Unit>)
                        handler(
                            CapturedCommand(
                                command.map { it.toString() },
                                completion,
                            )
                        )
                        Unit
                    }
                    "toString" -> "TestCommandExecutor"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.firstOrNull()
                    else -> error("unsupported commandExecutor method: ${method.name}")
                }
            }
        field.set(player, proxy)
    }

    private fun installProductionSourceSeam(
        player: MPVPlayer,
        mpv: MPVLib,
    ): MutableList<List<String>> {
        setField(player, "mpvLib", mpv)
        val commands = mutableListOf<List<String>>()
        assertTrue(
            "production seam test must enable MPVPlayer command path",
            trySetField(player, "commandsClosed", AtomicBoolean(false)),
        )
        installCommandExecutor(player) { captured ->
            commands += captured.arguments
            captured.complete()
        }
        val sourceExecutor: (Array<String>, Long, (Boolean) -> Unit) -> Unit =
            { command, actionGeneration, done ->
                commands += command.toList()
                done(mpv.commandForSource(command, actionGeneration).accepted)
            }
        setField(player, "sourceCommandExecutor", sourceExecutor)
        return commands
    }

    private open class ImmediateSourceCommandBackend : MpvSourceCommandBackend {
        protected val nativeGeneration = AtomicLong(10L)
        protected var blockedThread: Thread? = null
        protected var isBlockingStartEnabled = false

        override fun nativeGeneration(instance: Long): Long = nativeGeneration.get()

        override fun commandForSource(
            instance: Long,
            command: Array<String>,
        ): MpvSourceCommandNativeResult {
            return MpvSourceCommandNativeResult(
                playlistEntryId = SAME_ENTRY_ID,
                nativeGenerationAfterCommand = nativeGeneration.get(),
            )
        }

        fun emitStart(mpv: MPVLib) {
            mpv.eventWithIdentity(
                MpvEvent.MPV_EVENT_START_FILE,
                SAME_ENTRY_ID,
                nativeGeneration.incrementAndGet(),
            )
        }

        companion object {
            const val SAME_ENTRY_ID = 42L
        }
    }

    private class BlockingSourceCommandBackend : ImmediateSourceCommandBackend() {
        private lateinit var mpv: MPVLib
        var observedState: Thread.State = Thread.State.NEW

        val staleThread: Thread?
            get() = blockedThread

        fun attach(mpv: MPVLib) {
            this.mpv = mpv
            isBlockingStartEnabled = true
        }

        override fun commandForSource(
            instance: Long,
            command: Array<String>,
        ): MpvSourceCommandNativeResult {
            if (!isBlockingStartEnabled) {
                return super.commandForSource(instance, command)
            }
            isBlockingStartEnabled = false
            val entered = CountDownLatch(1)
            val generation = nativeGeneration.incrementAndGet()
            blockedThread =
                Thread({
                        entered.countDown()
                        mpv.eventWithIdentity(
                            MpvEvent.MPV_EVENT_START_FILE,
                            SAME_ENTRY_ID,
                            generation,
                        )
                    })
                    .also(Thread::start)
            assertTrue("racing event thread must start", entered.await(2, TimeUnit.SECONDS))
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (blockedThread?.state != Thread.State.BLOCKED && System.nanoTime() < deadline) {
                Thread.yield()
            }
            observedState = blockedThread?.state ?: Thread.State.NEW
            assertEquals(Thread.State.BLOCKED, observedState)
            return MpvSourceCommandNativeResult(
                playlistEntryId = SAME_ENTRY_ID,
                nativeGenerationAfterCommand = nativeGeneration.get(),
            )
        }
    }

    private class GateArmRaceBackend : ImmediateSourceCommandBackend() {
        private var commandLatch = CountDownLatch(1)
        private val commandRelease = CountDownLatch(1)

        @Volatile var commandThread: Thread? = null

        val commandEntered: CountDownLatch
            get() = commandLatch

        fun reset() {
            commandLatch = CountDownLatch(1)
            commandThread = null
        }

        fun releaseCommand() {
            commandRelease.countDown()
        }

        override fun commandForSource(
            instance: Long,
            command: Array<String>,
        ): MpvSourceCommandNativeResult {
            commandThread = Thread.currentThread()
            val result = super.commandForSource(instance, command)
            commandLatch.countDown()
            check(commandRelease.await(2, TimeUnit.SECONDS)) { "test command release timed out" }
            return result
        }
    }

    private fun awaitThreadState(thread: Thread, expected: Thread.State) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (thread.state != expected && System.nanoTime() < deadline) Thread.yield()
        assertEquals("thread state: ${thread.name}", expected, thread.state)
    }

    private fun installRecoverySchedulerIfPresent(
        player: MPVPlayer,
        actions: MutableList<ScheduledRecovery> = mutableListOf(),
    ): MutableList<ScheduledRecovery> {
        val field =
            runCatching {
                MPVPlayer::class.java.getDeclaredField("recoveryScheduler").apply {
                    isAccessible = true
                }
            }
                .getOrNull() ?: return actions
        val proxy =
            Proxy.newProxyInstance(field.type.classLoader, arrayOf(field.type)) {
                proxy,
                method,
                args ->
                when (method.name) {
                    "invoke" -> {
                        val delayMs = (args?.firstOrNull() as? Number)?.toLong() ?: 0L
                        @Suppress("UNCHECKED_CAST")
                        val action = checkNotNull(args.getOrNull(1)) as Function0<Unit>
                        actions += ScheduledRecovery(delayMs, action)
                        Unit
                    }
                    "toString" -> "TestRecoveryScheduler"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.firstOrNull()
                    else -> error("unsupported recoveryScheduler method: ${method.name}")
                }
            }
        field.set(player, proxy)
        return actions
    }

    private fun runNextRecovery(player: MPVPlayer): Boolean {
        val action = capturedRecoveries[player]?.removeFirstOrNull() ?: return false
        action.run()
        return true
    }

    @Suppress("UNUSED_PARAMETER")
    private fun deliverSourceMarker(player: MPVPlayer, index: Int) = Unit

    private fun sourceEvent(
        eventId: Int,
        playlistEntryId: Long,
        nativeGeneration: Long,
        actionGeneration: Long,
    ): MpvPlaybackEvent =
        MpvPlaybackEvent(
            eventId = eventId,
            playlistEntryId = playlistEntryId,
            nativeGeneration = nativeGeneration,
            actionGeneration = actionGeneration,
        )

    private fun sourceActionGeneration(player: MPVPlayer, pending: Boolean = false): Long {
        val attempt =
            if (pending) {
                getField(player, "pendingSourceStart") ?: getField(player, "sourceStartInFlight")
            } else {
                getField(player, "sourceStartInFlight") ?: getField(player, "pendingSourceStart")
            } ?: return 0L
        return attempt.javaClass.getDeclaredField("actionGeneration").let {
            it.isAccessible = true
            it.getLong(attempt)
        }
    }

    private fun deliverSourceEvent(player: MPVPlayer, eventId: Int) {
        val generation = sourceActionGeneration(player)
        player.playbackEvent(
            sourceEvent(
                eventId = eventId,
                playlistEntryId = 1_000_000L + generation,
                nativeGeneration = generation,
                actionGeneration = generation,
            )
        )
    }

    private fun loadFileCommand(uri: String): List<String> = listOf("loadfile", uri, "replace")

    private fun subtitleCommandCount(commands: List<List<String>>): Int = commands.count {
        it.firstOrNull() == "sub-add"
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

    private fun replaceInitialCommands(player: MPVPlayer, commands: List<Array<String>>) {
        val index = getField(player, "currentMediaItemIndex") as Int
        val item = itemAt(player, index)
        val uri =
            (item.localConfiguration?.uri ?: item.requestMetadata.mediaUri)?.toString()
                ?: error("test media item has no URI")
        installInitialCommands(player, commands, uri, index)
        armSourceStart(player, index, uri)
    }

    private fun installInitialCommands(
        player: MPVPlayer,
        commands: List<Array<String>>,
        uri: String,
        index: Int = getField(player, "currentMediaItemIndex") as Int,
    ) {
        val method =
            MPVPlayer::class.java.declaredMethods.single { it.name == "replaceInitialCommands" }
        method.isAccessible = true
        method.invoke(player, commands, uri, index)
    }

    private fun armSourceStart(player: MPVPlayer, index: Int, uri: String) {
        val method =
            MPVPlayer::class.java.declaredMethods.single { it.name == "postSourceStartCommand" }
        method.isAccessible = true
        method.invoke(
            player,
            arrayOf("playlist-play-index", "$index"),
            index,
            uri,
        )
    }

    private fun captureEventCallbacks(player: MPVPlayer): MutableList<Runnable> {
        val callbacks = mutableListOf<Runnable>()
        val poster: (Runnable) -> Unit = callbacks::add
        trySetField(player, "eventPoster", poster)
        return callbacks
    }

    private fun deliverStartFile(
        player: MPVPlayer,
        callbacks: MutableList<Runnable>,
    ) {
        callbacks.clear()
        deliverSourceEvent(player, MpvEvent.MPV_EVENT_START_FILE)
        callbacks.forEach(Runnable::run)
        callbacks.clear()
        setField(player, "released", true)
        deliverSourceEvent(player, MpvEvent.MPV_EVENT_FILE_LOADED)
        callbacks.forEach(Runnable::run)
        callbacks.clear()
        setField(player, "released", false)
    }

    private fun deliverStaleStartFile(
        player: MPVPlayer,
        callbacks: MutableList<Runnable>,
    ) {
        callbacks.clear()
        player.playbackEvent(
            sourceEvent(MpvEvent.MPV_EVENT_START_FILE, 9_000_001L, 500L, Long.MAX_VALUE)
        )
        callbacks.forEach(Runnable::run)
        callbacks.clear()
        player.playbackEvent(
            sourceEvent(MpvEvent.MPV_EVENT_FILE_LOADED, 9_000_001L, 500L, Long.MAX_VALUE)
        )
        callbacks.forEach(Runnable::run)
        callbacks.clear()
    }

    private fun assertEncodedSourceDoesNotClaim(
        owner: String,
        event: String,
    ) {
        val player =
            allocatedMpvPlayer(
                sourceEpoch = 163L,
                mediaItems = mutableListOf(mediaItem("item-encoded", owner)),
                currentIndex = 0,
                sourcePosition = MpvSourcePositionState(sourceEpoch = 163L),
            )
        setField(player, "isPlayerReady", false)
        val commands = captureCommands(player)
        val currentCommands = listOf(arrayOf("sub-add", "current.srt", "auto", "current", "zh"))
        installInitialCommands(player, currentCommands, owner)
        armSourceStart(player, 0, event)
        commands.clear()
        val callbacks = captureEventCallbacks(player)

        deliverStartFile(player, callbacks)

        assertFalse(
            "不同 encoded source 不得领取 current commands: owner=$owner event=$event commands=$commands",
            commands.any { it == currentCommands.single().toList() },
        )
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

    private fun setSuperField(target: Any, name: String, value: Any) {
        var type: Class<*>? = target.javaClass.superclass
        while (type != null) {
            val field = runCatching { type.getDeclaredField(name) }.getOrNull()
            if (field != null) {
                field.isAccessible = true
                field.set(target, value)
                return
            }
            type = type.superclass
        }
        error("missing superclass field: $name")
    }

    private fun setNullableField(target: Any, name: String, value: Any?) {
        target.javaClass.getDeclaredField(name).apply {
            isAccessible = true
            set(target, value)
        }
    }

    private fun getField(target: Any, name: String): Any? =
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)

    private class MpvPositionRefreshRecorder(private val player: Player) : Player.Listener {
        val refreshPositionsMs = mutableListOf<Long>()
        val discontinuityReasons = mutableListOf<Int>()
        var positionDiscontinuityEvents: Int = 0
        var endedPlaybackStates: Int = 0
        var endOfMediaReasons: Int = 0
        var mediaItemTransitions: Int = 0

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            positionDiscontinuityEvents++
            discontinuityReasons += reason
            if (reason != Player.DISCONTINUITY_REASON_SEEK) {
                refreshPositionsMs += player.currentPosition
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) endedPlaybackStates++
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) {
                endOfMediaReasons++
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            mediaItemTransitions++
        }

        fun clear() {
            refreshPositionsMs.clear()
            discontinuityReasons.clear()
            positionDiscontinuityEvents = 0
            endedPlaybackStates = 0
            endOfMediaReasons = 0
            mediaItemTransitions = 0
        }
    }

    private object TestUriFactory {
        private val constructor: Constructor<Uri> by lazy {
            val source =
                """
                package android.net;

                import android.os.Parcel;
                import java.util.Collections;
                import java.util.List;

                public final class TestUri extends Uri {
                    private final String value;

                    public TestUri(String value) {
                        this.value = value;
                    }

                    @Override public Builder buildUpon() { throw new UnsupportedOperationException(); }
                    @Override public String getAuthority() { return null; }
                    @Override public String getEncodedAuthority() { return null; }
                    @Override public String getEncodedFragment() { return null; }
                    @Override public String getEncodedPath() { return null; }
                    @Override public String getEncodedQuery() { return null; }
                    @Override public String getEncodedSchemeSpecificPart() { return value; }
                    @Override public String getEncodedUserInfo() { return null; }
                    @Override public String getFragment() { return null; }
                    @Override public String getHost() { return null; }
                    @Override public String getLastPathSegment() { return null; }
                    @Override public String getPath() { return null; }
                    @Override public List<String> getPathSegments() { return Collections.emptyList(); }
                    @Override public int getPort() { return -1; }
                    @Override public String getQuery() { return null; }
                    @Override public String getScheme() { return null; }
                    @Override public String getSchemeSpecificPart() { return value; }
                    @Override public String getUserInfo() { return null; }
                    @Override public boolean isHierarchical() { return true; }
                    @Override public boolean isRelative() { return false; }
                    @Override public String toString() { return value; }
                    @Override public int describeContents() { return 0; }
                    @Override public void writeToParcel(Parcel parcel, int flags) {}
                }
                """
                    .trimIndent()
            val root = Files.createTempDirectory("player-test-uri").toFile()
            val sourceFile =
                File(root, "android/net/TestUri.java").apply {
                    parentFile.mkdirs()
                    writeText(source)
                }
            val classes = File(root, "classes").apply { mkdirs() }
            val diagnostics = ByteArrayOutputStream()
            val compiler = checkNotNull(ToolProvider.getSystemJavaCompiler())
            val result =
                compiler.run(
                    null,
                    null,
                    diagnostics,
                    "-classpath",
                    File(Uri::class.java.protectionDomain.codeSource.location.toURI()).path,
                    "-d",
                    classes.path,
                    sourceFile.path,
                )
            check(result == 0) { "真实 MediaItem Uri 测试夹具编译失败：${diagnostics.toString("UTF-8")}" }
            val lookup = MethodHandles.privateLookupIn(Uri::class.java, MethodHandles.lookup())
            val defineClass =
                lookup.javaClass.getDeclaredMethod("defineClass", ByteArray::class.java).apply {
                    isAccessible = true
                }
            val type =
                defineClass.invoke(
                    lookup,
                    File(classes, "android/net/TestUri.class").readBytes(),
                ) as Class<*>
            @Suppress("UNCHECKED_CAST")
            type.getDeclaredConstructor(String::class.java) as Constructor<Uri>
        }

        fun uri(value: String): Uri = constructor.newInstance(value)
    }

    private object DeferredHandlerFactory {
        private val constructor: Constructor<Handler> by lazy {
            val source =
                """
                package android.os;

                import java.util.ArrayDeque;

                public final class TestDeferredHandler extends Handler {
                    private final ArrayDeque<Runnable> callbacks = new ArrayDeque<>();

                    @Override
                    public boolean post(Runnable callback) {
                        callbacks.add(callback);
                        return true;
                    }

                    public void runAll() {
                        while (!callbacks.isEmpty()) {
                            callbacks.remove().run();
                        }
                    }
                }
                """
                    .trimIndent()
            val root = Files.createTempDirectory("player-test-handler").toFile()
            val sourceFile =
                File(root, "android/os/TestDeferredHandler.java").apply {
                    parentFile.mkdirs()
                    writeText(source)
                }
            val classes = File(root, "classes").apply { mkdirs() }
            val diagnostics = ByteArrayOutputStream()
            val compiler = checkNotNull(ToolProvider.getSystemJavaCompiler())
            val result =
                compiler.run(
                    null,
                    null,
                    diagnostics,
                    "-classpath",
                    File(Handler::class.java.protectionDomain.codeSource.location.toURI()).path,
                    "-d",
                    classes.path,
                    sourceFile.path,
                )
            check(result == 0) { "真实 Handler 测试夹具编译失败：${diagnostics.toString("UTF-8")}" }
            val lookup = MethodHandles.privateLookupIn(Handler::class.java, MethodHandles.lookup())
            val defineClass =
                lookup.javaClass.getDeclaredMethod("defineClass", ByteArray::class.java).apply {
                    isAccessible = true
                }
            val type =
                defineClass.invoke(
                    lookup,
                    File(classes, "android/os/TestDeferredHandler.class").readBytes(),
                ) as Class<*>
            @Suppress("UNCHECKED_CAST")
            type.getDeclaredConstructor() as Constructor<Handler>
        }

        fun create(): Handler = constructor.newInstance()
    }

    private data class SameIdentityCase(
        val name: String,
        val playbackState: Int,
        val isPlayerReady: Boolean,
        val playWhenReady: Boolean,
        val hasPosition: Boolean,
    )

    private data class RangeMoveCase(
        val fromIndex: Int,
        val toIndex: Int,
        val newIndex: Int,
        val expectedIds: List<String>,
    )
}
