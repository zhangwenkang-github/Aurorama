package com.zhangwenkang.cinefin.player.local.presentation

import com.zhangwenkang.cinefin.player.core.domain.models.PlaybackMediaIdentity
import com.zhangwenkang.cinefin.player.local.domain.PlayerProgressFallbackController
import com.zhangwenkang.cinefin.player.local.domain.PlayerProgressFallbackInput
import com.zhangwenkang.cinefin.player.local.domain.PlayerProgressFallbackSourceKind
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
        assertNotNull(PlaybackSessionRules.project(state, playbackInput(state.key)).terminal)
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
        assertNotNull(declined.terminal)
        assertEquals("PLAYBACK_PROGRESS_TIMEOUT", declined.terminal?.kind)
    }

    @Test
    fun `exo fallback failure leaves existing localized recovery in ownership`() {
        val state = timeoutState()
        val controller = PlayerProgressFallbackController()
        val input = fallbackInput(state).copy(backend = PlayerViewModel.PLAYER_BACKEND_EXOPLAYER)

        assertNull(controller.evaluate(input))
        val recovery = PlaybackSessionRules.project(state, playbackInput(state.key))
        assertNotNull(recovery.terminal)
        assertEquals("PLAYBACK_PROGRESS_TIMEOUT", recovery.terminal?.kind)
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

    private fun <T : Any> present(value: T?): T {
        assertNotNull(value)
        return requireNotNull(value)
    }

    private companion object {
        val EPISODE_ID: UUID = UUID.fromString("00000000-0000-4000-8000-000000000141")
        const val SOURCE_ID = "source-hls"
    }
}
