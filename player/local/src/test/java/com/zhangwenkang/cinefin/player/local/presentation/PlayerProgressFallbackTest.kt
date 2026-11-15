package com.zhangwenkang.cinefin.player.local.presentation

import com.zhangwenkang.cinefin.player.core.domain.models.PlaybackMediaIdentity
import com.zhangwenkang.cinefin.player.local.domain.PlayerProgressFallbackController
import com.zhangwenkang.cinefin.player.local.domain.PlayerProgressFallbackInput
import com.zhangwenkang.cinefin.player.local.domain.PlayerProgressFallbackSourceKind
import com.zhangwenkang.cinefin.player.local.domain.PlayerProgressRecoveryController
import com.zhangwenkang.cinefin.player.local.domain.PlayerProgressRecoveryInput
import com.zhangwenkang.cinefin.player.local.domain.PlayerProgressRecoveryPhase
import com.zhangwenkang.cinefin.player.local.domain.SeekConfirmationPolicy
import com.zhangwenkang.cinefin.player.local.domain.SeekMediaIdentity
import com.zhangwenkang.cinefin.player.local.domain.SeekState
import com.zhangwenkang.cinefin.player.local.domain.playerProgressFallbackSourceKind
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerProgressFallbackTest {
    @Test
    fun `positive progress keeps mpv without a fallback request`() {
        val state = positiveProgressState()
        val input = fallbackInput(state)

        assertNull(state.terminal)
        assertNull(PlayerProgressFallbackController().evaluate(input))
    }

    @Test
    fun `empty result never requests a backend replacement`() {
        val empty = PlaybackSessionState()
        val input =
            fallbackInput(empty)
                .copy(
                    mediaIdentity = null,
                    sourceKind = PlayerProgressFallbackSourceKind.NONE,
                )

        assertNull(PlayerProgressFallbackController().evaluate(input))
    }

    @Test
    fun `auto fallback off preserves mpv and existing recovery ownership`() {
        val state = timeoutState()
        val controller = PlayerProgressFallbackController()
        val input = fallbackInput(state).copy(autoFallbackEnabled = false)

        assertNotNull(state.terminal)
        assertNull(controller.evaluate(input))
        assertNull(PlaybackSessionRules.project(state, playbackInput(state.key)).terminal)
    }

    @Test
    fun `remote hls mpv stall requests exo exactly once per media generation`() {
        val state = timeoutState()
        val controller = PlayerProgressFallbackController()
        val input = fallbackInput(state)

        val request = present(controller.evaluate(input))
        assertEquals(input.mediaIdentity, request.mediaIdentity)
        assertNull(controller.evaluate(input))
        assertNull(controller.evaluate(input.copy(terminalToken = "watchdog-duplicate")))
    }

    @Test
    fun `new media generation rearms one fallback attempt`() {
        val firstState = timeoutState("generation-a")
        val controller = PlayerProgressFallbackController()
        assertNotNull(controller.evaluate(fallbackInput(firstState)))

        val secondState = timeoutState("generation-b")
        assertNotNull(controller.evaluate(fallbackInput(secondState)))
    }

    @Test
    fun `manual backend choice cancels the pending generation`() {
        val state = timeoutState()
        val controller = PlayerProgressFallbackController()
        val input = fallbackInput(state)
        val request = present(controller.evaluate(input))

        controller.cancelCurrentGeneration()

        assertNull(controller.consume(request.token, input))
        assertNull(controller.evaluate(input))
        assertFalse(controller.suppressesTerminal(input.sessionKey))
    }

    @Test
    fun `pause after timeout cancels the pending fallback`() {
        val state = timeoutState()
        val controller = PlayerProgressFallbackController()
        val input = fallbackInput(state)
        val request = present(controller.evaluate(input))

        assertNull(controller.evaluate(input.copy(playWhenReady = false)))
        assertNull(controller.consume(request.token, input.copy(playWhenReady = false)))
        assertNull(controller.evaluate(input))
    }

    @Test
    fun `stale generation cannot consume an older fallback request`() {
        val oldState = timeoutState("generation-old")
        val controller = PlayerProgressFallbackController()
        val oldInput = fallbackInput(oldState)
        val request = present(controller.evaluate(oldInput))

        val newInput =
            fallbackInput(PlaybackSessionState(), sessionKey = "generation-new")
                .copy(
                    terminalKind = null,
                    terminalToken = null,
                )
        controller.evaluate(newInput)

        assertNull(controller.consume(request.token, newInput))
    }

    @Test
    fun `fallback request preserves episode and media source identity`() {
        val state = timeoutState()
        val controller = PlayerProgressFallbackController()
        val input = fallbackInput(state)
        val request = present(controller.evaluate(input))

        val consumed = present(controller.consume(request.token, input))

        assertEquals(EPISODE_ID, consumed.mediaIdentity.itemId)
        assertEquals(SOURCE_ID, consumed.mediaIdentity.mediaSourceId)
        assertEquals(0L, consumed.positionMs)
        assertNull(controller.consume(request.token, input))
    }

    @Test
    fun `accepted fallback suppresses terminal while declined fallback keeps local recovery`() {
        val state = timeoutState()
        val controller = PlayerProgressFallbackController()
        val input = fallbackInput(state)
        val request = present(controller.evaluate(input))

        val accepted =
            PlaybackSessionRules.project(
                state,
                playbackInput(state.key),
                suppressTerminal = controller.suppressesTerminal(request.sessionKey),
            )
        val declined =
            PlaybackSessionRules.project(
                state,
                playbackInput(state.key),
                suppressTerminal = false,
            )

        assertNull(accepted.terminal)
        assertTrue(accepted.loading)
        assertNull(declined.terminal)
        assertEquals("PLAYBACK_PROGRESS_TIMEOUT", state.terminal?.kind)
    }

    @Test
    fun `exo fallback failure leaves existing localized recovery in ownership`() {
        val state = timeoutState()
        val controller = PlayerProgressFallbackController()
        val input = fallbackInput(state).copy(backend = PlayerViewModel.PLAYER_BACKEND_EXOPLAYER)

        assertNull(controller.evaluate(input))
        val recovery = PlaybackSessionRules.project(state, playbackInput(state.key))
        assertNull(recovery.terminal)
        assertEquals("PLAYBACK_PROGRESS_TIMEOUT", state.terminal?.kind)
    }

    @Test
    fun `restart recovery has no persisted automatic switch command`() {
        val freshController = PlayerProgressFallbackController()
        val freshState = PlaybackSessionState()

        assertNull(freshController.evaluate(fallbackInput(freshState)))
        assertFalse(freshController.suppressesTerminal(null))
    }

    @Test
    fun `only remote hls sources are eligible`() {
        assertEquals(
            PlayerProgressFallbackSourceKind.REMOTE_HLS,
            playerProgressFallbackSourceKind(
                "https://example.test/videos/master.m3u8?api_key=redacted",
                container = null,
                mimeType = null,
            ),
        )
        assertEquals(
            PlayerProgressFallbackSourceKind.REMOTE_HLS,
            playerProgressFallbackSourceKind(
                "https://example.test/videos/session",
                container = "hls",
                mimeType = null,
            ),
        )
        assertEquals(
            PlayerProgressFallbackSourceKind.NONE,
            playerProgressFallbackSourceKind(
                "file:///storage/emulated/0/Download/master.m3u8",
                container = "hls",
                mimeType = null,
            ),
        )
        assertEquals(
            PlayerProgressFallbackSourceKind.NONE,
            playerProgressFallbackSourceKind(
                "https://example.test/videos/movie.mkv",
                container = "mkv",
                mimeType = "video/x-matroska",
            ),
        )
    }

    @Test
    fun `bounded progress recovery coalesces duplicates and uses increasing backoff`() {
        val controller = PlayerProgressRecoveryController()
        val input = recoveryInput()

        val first = present(controller.begin(input))

        assertNull(controller.begin(input))
        assertEquals(1, first.attempt)
        assertTrue(first.delayMs > 0L)

        val second = present(controller.nextAttempt(first))

        assertEquals(2, second.attempt)
        assertTrue(second.delayMs > first.delayMs)
        controller.exhaust(second)
        assertNull(controller.begin(input))
        assertEquals(
            PlayerProgressRecoveryPhase.EXHAUSTED,
            controller.status()?.phase,
        )
    }

    @Test
    fun `buffered growth is not confirmation but engine or ready evidence is`() {
        val controller = PlayerProgressRecoveryController()
        val input = recoveryInput(enginePositionMs = 5_000L, bufferedPositionMs = 6_000L)
        val request = present(controller.begin(input))

        val bufferedOnly =
            controller.observe(
                input.copy(
                    bufferedPositionMs = 9_000L,
                    isReady = false,
                    isPlaying = false,
                )
            )

        assertEquals(PlayerProgressRecoveryPhase.RETRYING, bufferedOnly.phase)
        assertTrue(controller.isCurrent(request))

        val engineConfirmed = controller.observe(input.copy(enginePositionMs = 6_000L))

        assertEquals(PlayerProgressRecoveryPhase.CONFIRMED, engineConfirmed.phase)
        assertNull(controller.status())

        val readyController = PlayerProgressRecoveryController()
        val readyInput = recoveryInput(enginePositionMs = 0L, wasLoading = true)
        val readyRequest = present(readyController.begin(readyInput))
        val readyConfirmed =
            readyController.observe(
                readyInput.copy(
                    isReady = true,
                    isPlaying = true,
                    wasLoading = false,
                )
            )

        assertTrue(readyController.isCurrent(readyRequest))
        assertEquals(PlayerProgressRecoveryPhase.CONFIRMED, readyConfirmed.phase)
    }

    @Test
    fun `confirmed recovery terminal stays retired across pause and resume`() {
        val controller = PlayerProgressRecoveryController()
        val input = recoveryInput()
        present(controller.begin(input))

        val confirmed =
            controller.observe(
                input.copy(
                    enginePositionMs = 1_000L,
                    bufferedPositionMs = 2_000L,
                )
            )

        assertEquals(PlayerProgressRecoveryPhase.CONFIRMED, confirmed.phase)
        var retainedTerminal: PlayerViewModel.PlaybackRecovery? =
            PlayerViewModel.PlaybackRecovery(
                kind = "PLAYBACK_PROGRESS_TIMEOUT",
                positionMs = 0L,
                positionSource = PlayerViewModel.PlaybackRetryPositionSource.ENGINE_POSITION,
                token = "terminal-a",
            )
        retainedTerminal =
            retireConfirmedProgressRecoveryTerminal(retainedTerminal, confirmed.phase)
        assertNull(retainedTerminal)

        val paused = controller.observe(input.copy(playWhenReady = false))
        assertEquals(PlayerProgressRecoveryPhase.CANCELLED, paused.phase)
        assertNull(retireConfirmedProgressRecoveryTerminal(retainedTerminal, paused.phase))
        assertNull(
            controller.begin(
                input.copy(
                    terminalKind = retainedTerminal?.kind,
                    terminalToken = retainedTerminal?.token,
                )
            )
        )
    }

    @Test
    fun `newer explicit seek makes a recovery request stale`() {
        val controller = PlayerProgressRecoveryController()
        val request = present(controller.begin(recoveryInput()))
        val seekIdentity =
            SeekMediaIdentity(
                itemId = EPISODE_ID.toString(),
                mediaSourceId = SOURCE_ID,
                sourceFingerprint = "source-fingerprint",
            )
        val policy =
            SeekConfirmationPolicy(
                completionTimeoutMs = 5_000L,
                positionToleranceMs = 500L,
                minimumTargetDisplayMs = 0L,
            )
        val originalSeek =
            SeekState.Seeking(
                targetMs = 40_000L,
                requestGeneration = 11L,
                sourceEpoch = 7L,
                mediaIdentity = seekIdentity,
                requestedAtMs = 1_000L,
                policy = policy,
            )
        val originalBinding = originalSeek.progressRecoverySeekBinding()

        assertTrue(
            progressRecoveryRequestIdentityIsCurrent(
                request = request,
                expectedSeekBinding = originalBinding,
                currentSessionKey = request.sessionKey,
                currentMediaIdentity = request.mediaIdentity,
                currentSeekState = originalSeek,
            )
        )
        assertFalse(
            progressRecoveryRequestIdentityIsCurrent(
                request = request,
                expectedSeekBinding = originalBinding,
                currentSessionKey = request.sessionKey,
                currentMediaIdentity = request.mediaIdentity,
                currentSeekState =
                    originalSeek.copy(
                        targetMs = 55_000L,
                        requestGeneration = 12L,
                    ),
            )
        )
        assertFalse(
            progressRecoveryRequestIdentityIsCurrent(
                request = request,
                expectedSeekBinding = originalBinding,
                currentSessionKey = request.sessionKey,
                currentMediaIdentity = request.mediaIdentity,
                currentSeekState = originalSeek.copy(sourceEpoch = 8L),
            )
        )
    }

    @Test
    fun `pause source change and stale completion cancel the active recovery`() {
        val paused = PlayerProgressRecoveryController()
        val pausedInput = recoveryInput()
        val pausedRequest = present(paused.begin(pausedInput))

        assertEquals(
            PlayerProgressRecoveryPhase.CANCELLED,
            paused.observe(pausedInput.copy(playWhenReady = false)).phase,
        )
        assertFalse(paused.isCurrent(pausedRequest))
        assertNull(paused.nextAttempt(pausedRequest))

        val changed = PlayerProgressRecoveryController()
        val oldRequest = present(changed.begin(pausedInput))
        val changedInput =
            recoveryInput(
                sessionKey = "generation-new",
                terminalToken = "terminal-new",
            )

        assertEquals(
            PlayerProgressRecoveryPhase.CANCELLED,
            changed.observe(changedInput).phase,
        )
        assertNull(changed.nextAttempt(oldRequest))

        val replaced = PlayerProgressRecoveryController()
        val old = present(replaced.begin(pausedInput))
        val current = present(replaced.begin(changedInput))

        assertFalse(replaced.isCurrent(old))
        replaced.exhaust(old)
        assertTrue(replaced.isCurrent(current))
        assertEquals(
            PlayerProgressRecoveryPhase.RETRYING,
            replaced.status()?.phase,
        )
    }

    @Test
    fun `new terminal generation reopens a fresh bounded budget`() {
        val controller = PlayerProgressRecoveryController()
        val first = present(controller.begin(recoveryInput()))
        val second = present(controller.nextAttempt(first))
        controller.exhaust(second)

        val nextGeneration =
            present(controller.begin(recoveryInput(terminalToken = "terminal-next")))

        assertEquals(1, nextGeneration.attempt)
        assertTrue(nextGeneration.generation > first.generation)
    }

    private fun positiveProgressState(): PlaybackSessionState {
        val input =
            playbackInput(sessionKey = "generation-a", isBuffering = false)
                .copy(
                    isPlaying = true,
                    isReady = true,
                    enginePositionMs = 1_000L,
                    bufferedPositionMs = 2_000L,
                )
        val started = PlaybackSessionRules.advance(PlaybackSessionState(), input, 1_000L)
        return PlaybackSessionRules.advance(
            started,
            input.copy(
                enginePositionMs = 2_000L,
                bufferedPositionMs = 3_000L,
            ),
            2_000L,
        )
    }

    private fun timeoutState(sessionKey: String = "generation-a"): PlaybackSessionState {
        val input = playbackInput(sessionKey)
        val started = PlaybackSessionRules.advance(PlaybackSessionState(), input, 1_000L)
        return PlaybackSessionRules.advance(started, input, 21_000L)
    }

    private fun playbackInput(
        sessionKey: String?,
        isBuffering: Boolean = true,
    ): PlaybackSessionInput =
        PlaybackSessionInput(
            key = sessionKey,
            playWhenReady = true,
            isPlaying = false,
            isBuffering = isBuffering,
            isReady = false,
            enginePositionMs = null,
            bufferedPositionMs = null,
            hasPlayerError = false,
            hasSeekFailure = false,
            persistedResumeMs = 0L,
        )

    private fun fallbackInput(
        state: PlaybackSessionState,
        sessionKey: String? = state.key,
    ): PlayerProgressFallbackInput =
        PlayerProgressFallbackInput(
            sessionKey = sessionKey,
            mediaIdentity = PlaybackMediaIdentity(EPISODE_ID, SOURCE_ID),
            sourceKind =
                if (state.key == null) {
                    PlayerProgressFallbackSourceKind.NONE
                } else {
                    PlayerProgressFallbackSourceKind.REMOTE_HLS
                },
            backend = PlayerViewModel.PLAYER_BACKEND_MPV,
            autoFallbackEnabled = true,
            playWhenReady = true,
            terminalKind = state.terminal?.kind,
            terminalToken = state.terminal?.token,
            positionMs = state.terminal?.positionMs ?: 0L,
        )

    private fun recoveryInput(
        sessionKey: String = "generation-a",
        terminalToken: String = "terminal-a",
        enginePositionMs: Long? = 0L,
        bufferedPositionMs: Long? = 1_000L,
        wasLoading: Boolean = true,
    ): PlayerProgressRecoveryInput =
        PlayerProgressRecoveryInput(
            sessionKey = sessionKey,
            mediaIdentity = PlaybackMediaIdentity(EPISODE_ID, SOURCE_ID),
            targetPositionMs = enginePositionMs ?: 0L,
            terminalKind = "PLAYBACK_PROGRESS_TIMEOUT",
            terminalToken = terminalToken,
            playWhenReady = true,
            hasPlayerError = false,
            hasSeekFailure = false,
            enginePositionMs = enginePositionMs,
            bufferedPositionMs = bufferedPositionMs,
            isReady = false,
            isPlaying = false,
            wasLoading = wasLoading,
        )

    private fun <T : Any> present(value: T?): T {
        assertNotNull(value)
        return requireNotNull(value)
    }

    private companion object {
        val EPISODE_ID: UUID = UUID.fromString("00000000-0000-4000-8000-000000000141")
        const val SOURCE_ID = "source-hls"
    }
}
