package com.zhangwenkang.cinefin.player.local.presentation

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.zhangwenkang.cinefin.player.core.domain.models.PlaybackMediaIdentity
import com.zhangwenkang.cinefin.player.local.domain.PlayerExplicitExitController
import com.zhangwenkang.cinefin.player.local.domain.PlayerProgressFallbackController
import com.zhangwenkang.cinefin.player.local.domain.PlayerProgressFallbackInput
import com.zhangwenkang.cinefin.player.local.domain.PlayerProgressFallbackSourceKind
import com.zhangwenkang.cinefin.player.local.domain.PlayerProgressRecoveryController
import com.zhangwenkang.cinefin.player.local.domain.PlayerProgressRecoveryInput
import com.zhangwenkang.cinefin.player.local.domain.PlayerProgressRecoveryPhase
import com.zhangwenkang.cinefin.player.local.domain.SeekConfirmationPolicy
import com.zhangwenkang.cinefin.player.local.domain.SeekEngine
import com.zhangwenkang.cinefin.player.local.domain.SeekMediaIdentity
import com.zhangwenkang.cinefin.player.local.domain.SeekState
import com.zhangwenkang.cinefin.player.local.domain.playerProgressFallbackSourceKind
import com.zhangwenkang.cinefin.player.local.session.recoveryRetains
import com.zhangwenkang.cinefin.player.local.subtitle.SubtitleOverlayState
import java.lang.reflect.Proxy
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerProgressFallbackTest {
    @Test
    fun `finalized retry entry rejects before player or retry work access`() {
        val player = recoverySeekPlayer(mutableListOf())
        var playerProviderCalls = 0
        var currentItemAccesses = 0
        var positionAccesses = 0
        var seekRequests = 0
        var transcodeRestarts = 0
        var networkRetries = 0
        var retryAttempts = 0
        val access =
            PlayerAccessInvariant(
                isClosed = { true },
                existingPlayerProvider = {
                    playerProviderCalls += 1
                    player
                },
            )

        val executed = access.withExistingPlayer {
            currentItemAccesses += 1
            positionAccesses += 1
            seekRequests += 1
            transcodeRestarts += 1
            networkRetries += 1
            retryAttempts += 1
        }

        assertFalse(executed)
        assertEquals(0, playerProviderCalls)
        assertEquals(0, currentItemAccesses)
        assertEquals(0, positionAccesses)
        assertEquals(0, seekRequests)
        assertEquals(0, transcodeRestarts)
        assertEquals(0, networkRetries)
        assertEquals(0, retryAttempts)
    }

    @Test
    fun `finalized player error entry rejects before callback work`() {
        val player = recoverySeekPlayer(mutableListOf())
        var playerProviderCalls = 0
        var currentItemAccesses = 0
        var positionAccesses = 0
        var transcodeRestarts = 0
        var networkRetries = 0
        var retryAttempts = 0
        val access =
            PlayerAccessInvariant(
                isClosed = { true },
                existingPlayerProvider = {
                    playerProviderCalls += 1
                    player
                },
            )

        val executed = access.runIfOpen {
            currentItemAccesses += 1
            positionAccesses += 1
            transcodeRestarts += 1
            networkRetries += 1
            retryAttempts += 1
        }

        assertFalse(executed)
        assertEquals(0, playerProviderCalls)
        assertEquals(0, currentItemAccesses)
        assertEquals(0, positionAccesses)
        assertEquals(0, transcodeRestarts)
        assertEquals(0, networkRetries)
        assertEquals(0, retryAttempts)
    }

    @Test
    fun `open access preserves pre-exit retry network and non-exit lifecycle controls`() {
        val player = recoverySeekPlayer(mutableListOf())
        var playerProviderCalls = 0
        var retryAttempts = 0
        var networkCallbacks = 0
        val access =
            PlayerAccessInvariant(
                isClosed = { false },
                existingPlayerProvider = {
                    playerProviderCalls += 1
                    player
                },
            )

        assertTrue(access.runIfOpen { networkCallbacks += 1 })
        assertTrue(access.withExistingPlayer { retryAttempts += 1 })

        assertEquals(1, networkCallbacks)
        assertEquals(1, retryAttempts)
        assertEquals(1, playerProviderCalls)
    }

    @Test
    fun `explicit exit and finalization independently close the shared access gate`() {
        var explicitExit = false
        var finalized = false
        val player = recoverySeekPlayer(mutableListOf())
        val access =
            PlayerAccessInvariant(
                isClosed = { explicitExit || finalized },
                existingPlayerProvider = { player },
            )

        assertTrue(access.isOpen())

        explicitExit = true
        assertFalse(access.isOpen())

        explicitExit = false
        finalized = true
        assertFalse(access.isOpen())
    }

    @Test
    fun `media transition continuation rechecks exit gate after playback start suspend`() =
        runBlocking {
            var closed = false
            val suspendEntered = CompletableDeferred<Unit>()
            val resumeSuspend = CompletableDeferred<Unit>()
            var currentItemReads = 0
            var queueChanges = 0
            var addMediaItemCalls = 0
            var seekRequests = 0
            var playCalls = 0
            var lazyPlayerCreations = 0
            val coordinator =
                PlayerPostSuspendCallbackCoordinator(
                    playerAccess =
                        PlayerAccessInvariant(
                            isClosed = { closed },
                            existingPlayerProvider = {
                                lazyPlayerCreations += 1
                                recoverySeekPlayer(mutableListOf())
                            },
                        )
                )
            val callback = async {
                coordinator.run(
                    suspendWork = {
                        suspendEntered.complete(Unit)
                        resumeSuspend.await()
                    },
                    continuation = {
                        currentItemReads += 1
                        queueChanges += 1
                        addMediaItemCalls += 1
                        seekRequests += 1
                        playCalls += 1
                    },
                )
            }

            suspendEntered.await()
            closed = true
            resumeSuspend.complete(Unit)

            assertFalse(callback.await())
            assertEquals(0, currentItemReads)
            assertEquals(0, queueChanges)
            assertEquals(0, addMediaItemCalls)
            assertEquals(0, seekRequests)
            assertEquals(0, playCalls)
            assertEquals(0, lazyPlayerCreations)
        }

    @Test
    fun `open media transition continuation runs once after playback start suspend`() =
        runBlocking {
            val suspendEntered = CompletableDeferred<Unit>()
            val resumeSuspend = CompletableDeferred<Unit>()
            var continuationCalls = 0
            val coordinator =
                PlayerPostSuspendCallbackCoordinator(
                    playerAccess =
                        PlayerAccessInvariant(
                            isClosed = { false },
                            existingPlayerProvider = { recoverySeekPlayer(mutableListOf()) },
                        )
                )
            val callback = async {
                coordinator.run(
                    suspendWork = {
                        suspendEntered.complete(Unit)
                        resumeSuspend.await()
                    },
                    continuation = { continuationCalls += 1 },
                )
            }

            suspendEntered.await()
            resumeSuspend.complete(Unit)

            assertTrue(callback.await())
            assertEquals(1, continuationCalls)
        }

    @Test
    fun `play when ready continuation rechecks finalize gate after playback stop suspend`() =
        runBlocking {
            var finalized = false
            val suspendEntered = CompletableDeferred<Unit>()
            val resumeSuspend = CompletableDeferred<Unit>()
            var playerReads = 0
            var seekRequests = 0
            var playCalls = 0
            var serviceStops = 0
            val coordinator =
                PlayerPostSuspendCallbackCoordinator(
                    playerAccess =
                        PlayerAccessInvariant(
                            isClosed = { finalized },
                            existingPlayerProvider = { recoverySeekPlayer(mutableListOf()) },
                        )
                )
            val callback = async {
                coordinator.run(
                    suspendWork = {
                        suspendEntered.complete(Unit)
                        resumeSuspend.await()
                    },
                    continuation = {
                        playerReads += 1
                        seekRequests += 1
                        playCalls += 1
                        serviceStops += 1
                    },
                )
            }

            suspendEntered.await()
            finalized = true
            resumeSuspend.complete(Unit)

            assertFalse(callback.await())
            assertEquals(0, playerReads)
            assertEquals(0, seekRequests)
            assertEquals(0, playCalls)
            assertEquals(0, serviceStops)
        }

    @Test
    fun `open play when ready continuation runs once after playback stop suspend`() = runBlocking {
        val suspendEntered = CompletableDeferred<Unit>()
        val resumeSuspend = CompletableDeferred<Unit>()
        var continuationCalls = 0
        val coordinator =
            PlayerPostSuspendCallbackCoordinator(
                playerAccess =
                    PlayerAccessInvariant(
                        isClosed = { false },
                        existingPlayerProvider = { recoverySeekPlayer(mutableListOf()) },
                    )
            )
        val callback = async {
            coordinator.run(
                suspendWork = {
                    suspendEntered.complete(Unit)
                    resumeSuspend.await()
                },
                continuation = { continuationCalls += 1 },
            )
        }

        suspendEntered.await()
        resumeSuspend.complete(Unit)

        assertTrue(callback.await())
        assertEquals(1, continuationCalls)
    }

    @Test
    fun `explicit exit blocks delayed restart with server transcode effects`() {
        val explicitExitController = PlayerExplicitExitController()
        assertNotNull(explicitExitController.begin())
        var playerProviderCalls = 0
        var playerGetterCalls = 0
        var currentItemAccesses = 0
        var positionAccesses = 0
        var restartRequests = 0
        var transcodeRequests = 0
        var serviceStops = 0
        val access =
            PlayerAccessInvariant(
                isClosed = { explicitExitController.isExited },
                existingPlayerProvider = {
                    playerProviderCalls += 1
                    recoverySeekPlayer(mutableListOf())
                },
            )
        val dispatcher =
            PlayerDelayedEventDispatcher(
                playerAccess = access,
                onFallbackToMpv = {},
                onRestartWithServerTranscode = {
                    playerGetterCalls += 1
                    currentItemAccesses += 1
                    positionAccesses += 1
                    restartRequests += 1
                    transcodeRequests += 1
                    serviceStops += 1
                },
                onFallbackToSoftware = {},
                onFallbackToExoForProgressStall = {},
            )

        assertFalse(dispatcher.dispatch(PlayerEvents.RestartWithServerTranscode))
        assertEquals(0, playerProviderCalls)
        assertEquals(0, playerGetterCalls)
        assertEquals(0, currentItemAccesses)
        assertEquals(0, positionAccesses)
        assertEquals(0, restartRequests)
        assertEquals(0, transcodeRequests)
        assertEquals(0, serviceStops)
    }

    @Test
    fun `finalization blocks delayed fallback to software effects`() {
        val finalizationGate = PlayerExitFinalizationGate()
        assertTrue(finalizationGate.run(detachVideoOutput = {}, finalizePlayback = {}))
        var playerProviderCalls = 0
        var playerGetterCalls = 0
        var currentItemAccesses = 0
        var positionAccesses = 0
        var restartRequests = 0
        var fallbackRequests = 0
        var serviceStops = 0
        val access =
            PlayerAccessInvariant(
                isClosed = { finalizationGate.isCompleted },
                existingPlayerProvider = {
                    playerProviderCalls += 1
                    recoverySeekPlayer(mutableListOf())
                },
            )
        val dispatcher =
            PlayerDelayedEventDispatcher(
                playerAccess = access,
                onFallbackToMpv = {},
                onRestartWithServerTranscode = {},
                onFallbackToSoftware = {
                    playerGetterCalls += 1
                    currentItemAccesses += 1
                    positionAccesses += 1
                    restartRequests += 1
                    fallbackRequests += 1
                    serviceStops += 1
                },
                onFallbackToExoForProgressStall = {},
            )

        assertFalse(dispatcher.dispatch(PlayerEvents.FallbackToSoftware))
        assertEquals(0, playerProviderCalls)
        assertEquals(0, playerGetterCalls)
        assertEquals(0, currentItemAccesses)
        assertEquals(0, positionAccesses)
        assertEquals(0, restartRequests)
        assertEquals(0, fallbackRequests)
        assertEquals(0, serviceStops)
    }

    @Test
    fun `finalization blocks async subtitle publication before routing or player access`() {
        val finalizationGate = PlayerExitFinalizationGate()
        assertTrue(finalizationGate.run(detachVideoOutput = {}, finalizePlayback = {}))
        var playerProviderCalls = 0
        var lazyPlayerGetterCalls = 0
        var panelPublishes = 0
        var subtitleRoutes = 0
        var seekRequests = 0
        var serviceStops = 0
        val access =
            PlayerAccessInvariant(
                isClosed = { finalizationGate.isCompleted },
                existingPlayerProvider = {
                    playerProviderCalls += 1
                    recoverySeekPlayer(mutableListOf())
                },
            )
        val coordinator =
            PlayerSubtitlePublicationCoordinator(
                playerAccess = access,
                publishPanel = { panelPublishes += 1 },
                routeSubtitle = {
                    lazyPlayerGetterCalls += 1
                    subtitleRoutes += 1
                    seekRequests += 1
                    serviceStops += 1
                },
            )

        assertFalse(coordinator.publish(SubtitleOverlayState()))
        assertEquals(0, playerProviderCalls)
        assertEquals(0, lazyPlayerGetterCalls)
        assertEquals(0, panelPublishes)
        assertEquals(0, subtitleRoutes)
        assertEquals(0, seekRequests)
        assertEquals(0, serviceStops)
    }

    @Test
    fun `open delayed fallback transcode and subtitle publication preserve pre-exit behavior`() {
        var transcodeRestarts = 0
        var softwareFallbacks = 0
        var progressFallbacks = 0
        var subtitleRoutes = 0
        val access =
            PlayerAccessInvariant(
                isClosed = { false },
                existingPlayerProvider = { recoverySeekPlayer(mutableListOf()) },
            )
        val dispatcher =
            PlayerDelayedEventDispatcher(
                playerAccess = access,
                onFallbackToMpv = { progressFallbacks += 1 },
                onRestartWithServerTranscode = { transcodeRestarts += 1 },
                onFallbackToSoftware = { softwareFallbacks += 1 },
                onFallbackToExoForProgressStall = { progressFallbacks += 1 },
            )
        val subtitlePublication =
            PlayerSubtitlePublicationCoordinator(
                playerAccess = access,
                publishPanel = {},
                routeSubtitle = { subtitleRoutes += 1 },
            )

        assertTrue(dispatcher.dispatch(PlayerEvents.RestartWithServerTranscode))
        assertTrue(dispatcher.dispatch(PlayerEvents.FallbackToSoftware))
        assertTrue(dispatcher.dispatch(PlayerEvents.FallbackToExoForProgressStall("token")))
        assertTrue(subtitlePublication.publish(SubtitleOverlayState()))
        assertEquals(1, transcodeRestarts)
        assertEquals(1, softwareFallbacks)
        assertEquals(1, progressFallbacks)
        assertEquals(1, subtitleRoutes)
    }

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

    @Test
    fun `stale recovery retry is canceled across newer seek pause and resume`() = runBlocking {
        val seekTargets = mutableListOf<Long>()
        val seekSink =
            PlaybackSeekSink(
                scope = CoroutineScope(Dispatchers.Unconfined),
                engineProvider = { SeekEngine.EXOPLAYER },
                clock = { 1_000L },
                mediaIdentityProvider = {
                    SeekMediaIdentity(
                        itemId = EPISODE_ID.toString(),
                        mediaSourceId = SOURCE_ID,
                        sourceFingerprint = "source-fingerprint",
                    )
                },
                mediaSourceUriProvider = { "https://media.example/stream.m3u8" },
            )
        seekSink.attach(recoverySeekPlayer(seekTargets))
        seekSink.requestSeek(40_000L, "initial-user-seek")
        val initialSeek = seekSink.state as SeekState.Seeking
        seekTargets.clear()

        val sessionKey = "item-a|$SOURCE_ID|1|source-fingerprint|x"
        val oldInput =
            recoverySessionInput(
                sessionKey = sessionKey,
                explicitTargetMs = initialSeek.targetMs,
                requestGeneration = initialSeek.requestGeneration,
                sourceEpoch = initialSeek.sourceEpoch,
            )
        val started = PlaybackSessionRules.advance(PlaybackSessionState(), oldInput, 1_000L)
        val timedOut = PlaybackSessionRules.advance(started, oldInput, 21_000L)
        val oldTerminal = present(timedOut.terminal)
        val controller = PlayerProgressRecoveryController()
        val oldRequest =
            present(
                controller.begin(
                    recoveryInput()
                        .copy(
                            sessionKey = sessionKey,
                            targetPositionMs = oldTerminal.positionMs,
                            terminalKind = oldTerminal.kind,
                            terminalToken = oldTerminal.token,
                        )
                )
            )
        val retriedTargets = mutableListOf<Long>()
        val owner =
            ProgressRecoveryJobOwner(
                scope = this,
                // 恢复状态归 ProgressWatchdog；job owner 只驱动协程。
                nextAttempt = controller::nextAttempt,
                recoveryExhausted = {
                    controller.status()?.phase == PlayerProgressRecoveryPhase.EXHAUSTED
                },
                releaseRecovery = { controller.cancel() },
                currentSeekBinding = { seekSink.state.progressRecoverySeekBinding() },
                requestIsCurrent = { request, expectedBinding ->
                    progressRecoveryRequestIdentityIsCurrent(
                        request = request,
                        expectedSeekBinding = expectedBinding,
                        currentSessionKey = sessionKey,
                        currentMediaIdentity = PlaybackMediaIdentity(EPISODE_ID, SOURCE_ID),
                        currentSeekState = seekSink.state,
                    )
                },
                executeRetry = { request, _ ->
                    retriedTargets += request.targetPositionMs
                    seekSink.requestSeek(request.targetPositionMs, "progress-recovery")
                },
                observeWindowMs = 0L,
                onExhausted = {},
            )

        owner.start(oldRequest, oldTerminal)
        yield()
        assertTrue(owner.isActive)

        seekSink.requestSeek(10_000L, "newer-backward-seek")
        val newerSeek = seekSink.state as SeekState.Seeking
        val newerInput =
            recoverySessionInput(
                sessionKey = sessionKey,
                explicitTargetMs = newerSeek.targetMs,
                requestGeneration = newerSeek.requestGeneration,
                sourceEpoch = newerSeek.sourceEpoch,
            )
        val afterSeek = PlaybackSessionRules.advance(timedOut, newerInput, 22_000L)
        val retains = recoveryRetains(oldTerminal, sessionKey, afterSeek.terminal, afterSeek.key)
        owner.retainTerminal(afterSeek.terminal, afterSeek.key)
        // W2-T3b：恢复状态归 ProgressWatchdog，判定为释放时由看门狗同步清空。
        if (!retains) controller.cancel()

        assertNull(afterSeek.terminal)
        assertFalse(retains)
        assertFalse(controller.isCurrent(oldRequest))
        assertFalse(owner.isActive)
        assertNull(owner.activeTerminal)

        val paused =
            PlaybackSessionRules.advance(afterSeek, newerInput.copy(playWhenReady = false), 23_000L)
        val resumed = PlaybackSessionRules.advance(paused, newerInput, 24_000L)
        assertNull(paused.terminal)
        assertNull(resumed.terminal)

        delay(oldRequest.delayMs + 50L)
        assertTrue("old recovery target was retried: $retriedTargets", retriedTargets.isEmpty())
        assertFalse("old 40s target reached seek sink: $seekTargets", seekTargets.contains(40_000L))

        val newTerminal =
            PlaybackSessionRules.advance(
                resumed,
                newerInput,
                24_000L + PlaybackSessionRules.STALL_TIMEOUT_MS + 1L,
            )
        assertEquals(10_000L, newTerminal.terminal?.positionMs)
        val newRequest =
            present(
                controller.begin(
                    recoveryInput()
                        .copy(
                            sessionKey = sessionKey,
                            targetPositionMs = requireNotNull(newTerminal.terminal).positionMs,
                            terminalKind = requireNotNull(newTerminal.terminal).kind,
                            terminalToken = requireNotNull(newTerminal.terminal).token,
                        )
                )
            )
        assertEquals(10_000L, newRequest.targetPositionMs)
    }

    @Test
    fun `progress recovery owner reports exhaustion exactly once`() = runBlocking {
        val controller = PlayerProgressRecoveryController()
        val first = present(controller.begin(recoveryInput()))
        val second = present(controller.nextAttempt(first))
        controller.exhaust(second)
        var exhaustedCallbacks = 0
        val owner =
            ProgressRecoveryJobOwner(
                scope = this,
                // 恢复状态归 ProgressWatchdog；job owner 只驱动协程。
                nextAttempt = controller::nextAttempt,
                recoveryExhausted = {
                    controller.status()?.phase == PlayerProgressRecoveryPhase.EXHAUSTED
                },
                releaseRecovery = { controller.cancel() },
                currentSeekBinding = { null },
                requestIsCurrent = { _, _ -> true },
                executeRetry = { _, _ -> },
                observeWindowMs = 0L,
                onExhausted = { exhaustedCallbacks += 1 },
            )

        owner.start(first.copy(delayMs = 0L), recoveryTerminal())
        yield()
        delay(50L)

        assertEquals(1, exhaustedCallbacks)
        assertFalse(owner.isActive)
        assertNull(owner.activeTerminal)
    }

    @Test
    fun `progress recovery owner does not report exhaustion after cancellation`() = runBlocking {
        val controller = PlayerProgressRecoveryController()
        val first = present(controller.begin(recoveryInput()))
        var exhaustedCallbacks = 0
        val owner =
            ProgressRecoveryJobOwner(
                scope = this,
                // 恢复状态归 ProgressWatchdog；job owner 只驱动协程。
                nextAttempt = controller::nextAttempt,
                recoveryExhausted = {
                    controller.status()?.phase == PlayerProgressRecoveryPhase.EXHAUSTED
                },
                releaseRecovery = { controller.cancel() },
                currentSeekBinding = { null },
                requestIsCurrent = { _, _ -> true },
                executeRetry = { _, _ -> },
                observeWindowMs = 0L,
                onExhausted = { exhaustedCallbacks += 1 },
            )

        owner.start(first.copy(delayMs = 0L), recoveryTerminal())
        controller.cancel()
        yield()
        delay(50L)

        assertEquals(0, exhaustedCallbacks)
        assertFalse(owner.isActive)
    }

    private fun recoveryTerminal(): PlayerViewModel.PlaybackRecovery =
        PlayerViewModel.PlaybackRecovery(
            kind = "PLAYBACK_PROGRESS_TIMEOUT",
            positionMs = 0L,
            positionSource = PlayerViewModel.PlaybackRetryPositionSource.ENGINE_POSITION,
            token = "terminal-a",
        )

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

    private fun recoverySessionInput(
        sessionKey: String,
        explicitTargetMs: Long,
        requestGeneration: Long,
        sourceEpoch: Long,
    ): PlaybackSessionInput =
        PlaybackSessionInput(
            key = sessionKey,
            playWhenReady = true,
            isPlaying = false,
            isBuffering = true,
            isReady = false,
            enginePositionMs = 5_000L,
            bufferedPositionMs = 6_000L,
            hasPlayerError = false,
            hasSeekFailure = false,
            explicitSeekTargetMs = explicitTargetMs,
            explicitSeekRequestGeneration = requestGeneration,
            explicitSeekSourceEpoch = sourceEpoch,
            persistedResumeMs = 0L,
        )

    private fun recoverySeekPlayer(seekTargets: MutableList<Long>): Player =
        Proxy.newProxyInstance(
            Player::class.java.classLoader,
            arrayOf(Player::class.java),
        ) { _, method, args ->
            when (method.name) {
                "addListener",
                "removeListener" -> Unit
                "getCurrentMediaItem" -> MediaItem.fromUri("https://media.example/item")
                "getDuration" -> 120_000L
                "getPlaybackState" -> Player.STATE_READY
                "getCurrentPosition" -> 5_000L
                "getCurrentMediaItemIndex" -> 0
                "getSeekBackIncrement" -> 10_000L
                "getSeekForwardIncrement" -> 10_000L
                "isCommandAvailable" -> true
                "seekTo" -> {
                    seekTargets += requireNotNull(args).last() as Long
                    Unit
                }
                "hashCode" -> System.identityHashCode(this)
                "equals" -> false
                "toString" -> "recoverySeekPlayer"
                else -> defaultValue(method.returnType)
            }
        } as Player

    private fun defaultValue(type: Class<*>): Any? =
        when (type) {
            java.lang.Boolean.TYPE -> false
            java.lang.Byte.TYPE -> 0.toByte()
            java.lang.Short.TYPE -> 0.toShort()
            java.lang.Integer.TYPE -> 0
            java.lang.Long.TYPE -> 0L
            java.lang.Float.TYPE -> 0f
            java.lang.Double.TYPE -> 0.0
            java.lang.Character.TYPE -> '\u0000'
            Void.TYPE -> Unit
            else -> null
        }

    private fun <T : Any> present(value: T?): T {
        assertNotNull(value)
        return requireNotNull(value)
    }

    private companion object {
        val EPISODE_ID: UUID = UUID.fromString("00000000-0000-4000-8000-000000000141")
        const val SOURCE_ID = "source-hls"
    }
}
