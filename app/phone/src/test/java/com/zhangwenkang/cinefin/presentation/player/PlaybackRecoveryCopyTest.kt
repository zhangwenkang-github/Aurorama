package com.zhangwenkang.cinefin.presentation.player

import android.app.Application
import android.content.SharedPreferences
import android.os.SystemClock
import androidx.lifecycle.SavedStateHandle
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.Tracks
import com.zhangwenkang.cinefin.PlayerExitServiceStopGate
import com.zhangwenkang.cinefin.api.JellyfinApi
import com.zhangwenkang.cinefin.player.local.R as PlayerR
import com.zhangwenkang.cinefin.player.local.domain.PlaybackCoordinatorImpl
import com.zhangwenkang.cinefin.player.local.domain.PlaybackPositionWriter
import com.zhangwenkang.cinefin.player.local.domain.SleepTimerController
import com.zhangwenkang.cinefin.player.local.presentation.PlaybackSeekSink
import com.zhangwenkang.cinefin.player.local.presentation.PlayerAccessInvariant
import com.zhangwenkang.cinefin.player.local.presentation.PlayerExitFinalizationGate
import com.zhangwenkang.cinefin.player.local.presentation.PlayerHolder
import com.zhangwenkang.cinefin.player.local.presentation.PlayerSegmentCallbackCoordinator
import com.zhangwenkang.cinefin.player.local.presentation.PlayerViewModel
import com.zhangwenkang.cinefin.repository.JellyfinRepository
import com.zhangwenkang.cinefin.settings.domain.AppPreferences
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackRecoveryCopyTest {
    companion object {
        init {
            System.setProperty(
                "slf4j.provider",
                "org.slf4j.helpers.NOP_FallbackServiceProvider",
            )
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun finalizedRetryPlayerErrorRejectsBeforeAnyPlayerOrWorkAccess() {
        Dispatchers.setMain(StandardTestDispatcher())
        mockkStatic(SystemClock::class)
        every { SystemClock.elapsedRealtime() } returns 10_000L
        try {
            val harness = playerViewModelHarness()
            assertTrue(harness.viewModel.onExplicitVideoExit())
            harness.resetAccessCounters()

            harness.viewModel.retryPlayerError()

            assertEquals(0, harness.lazyPlayerGetterCalls.get())
            assertEquals(0, harness.lazyPlayerCreations.get())
            assertEquals(0, harness.playerProbe.currentMediaItemAccesses.get())
            assertEquals(0, harness.playerProbe.prepareCalls.get())
            assertEquals(0, harness.playerProbe.seekCalls.get())
            assertEquals(0, harness.playerProbe.playCalls.get())
        } finally {
            Dispatchers.resetMain()
            unmockkAll()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun finalizedNetworkErrorCallbackRejectsBeforePlayerPositionOrRetryWork() {
        Dispatchers.setMain(StandardTestDispatcher())
        mockkStatic(SystemClock::class)
        every { SystemClock.elapsedRealtime() } returns 10_000L
        try {
            val harness = playerViewModelHarness()
            assertTrue(harness.viewModel.onExplicitVideoExit())
            harness.resetAccessCounters()

            harness.viewModel.onPlayerError(
                PlaybackException(
                    "network unavailable",
                    null,
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                )
            )

            assertEquals(0, harness.lazyPlayerGetterCalls.get())
            assertEquals(0, harness.lazyPlayerCreations.get())
            assertEquals(0, harness.playerProbe.currentMediaItemAccesses.get())
            assertEquals(0, harness.playerProbe.prepareCalls.get())
            assertEquals(0, harness.playerProbe.seekCalls.get())
            assertEquals(0, harness.playerProbe.playCalls.get())
            assertNull(harness.viewModel.uiState.value.playerError)
        } finally {
            Dispatchers.resetMain()
            unmockkAll()
        }
    }

    @Test
    fun progressTimeout_mapsToStalledRecoveryCopy() {
        val copy =
            resolvePlaybackRecoveryCopy(
                kind = "PLAYBACK_PROGRESS_TIMEOUT",
                positionMs = 0L,
                positionSource = PlayerViewModel.PlaybackRetryPositionSource.START_ZERO,
            )

        assertEquals(PlaybackRecoveryTitle.Stalled, copy.title)
        assertEquals(
            PlayerR.string.player_progress_recovery_stalled,
            PlaybackRecoveryTitle.Stalled.stringRes(),
        )
        assertEquals(PlaybackRecoveryDetail.RetryFromStart, copy.detail)
        assertEquals(
            PlayerR.string.player_progress_recovery_retry_from_start,
            PlaybackRecoveryDetail.RetryFromStart.stringRes(),
        )
    }

    @Test
    fun progressUnconfirmed_mapsToUnconfirmedRecoveryCopy() {
        val copy =
            resolvePlaybackRecoveryCopy(
                kind = "PROGRESS_UNCONFIRMED",
                positionMs = 0L,
                positionSource = PlayerViewModel.PlaybackRetryPositionSource.START_ZERO,
            )

        assertEquals(PlaybackRecoveryTitle.ProgressUnconfirmed, copy.title)
        assertEquals(
            PlayerR.string.player_progress_recovery_unconfirmed,
            PlaybackRecoveryTitle.ProgressUnconfirmed.stringRes(),
        )
        assertEquals(PlaybackRecoveryDetail.RetryFromStart, copy.detail)
    }

    @Test
    fun unknownKind_mapsToGenericRecoveryCopy() {
        val copy =
            resolvePlaybackRecoveryCopy(
                kind = "NEW_BACKEND_FAILURE",
                positionMs = 0L,
                positionSource = PlayerViewModel.PlaybackRetryPositionSource.START_ZERO,
            )

        assertEquals(PlaybackRecoveryTitle.Generic, copy.title)
        assertEquals(
            PlayerR.string.player_progress_recovery_generic,
            PlaybackRecoveryTitle.Generic.stringRes(),
        )
        assertEquals(PlaybackRecoveryDetail.RetryFromStart, copy.detail)
        assertFalse(copy.toString().contains("NEW_BACKEND_FAILURE"))
    }

    @Test
    fun startZero_neverExposesPositionSourceOrTimestamp() {
        PlayerViewModel.PlaybackRetryPositionSource.entries.forEach { source ->
            val copy =
                resolvePlaybackRecoveryCopy(
                    kind = "PLAYBACK_PROGRESS_TIMEOUT",
                    positionMs = 42_000L,
                    positionSource = source,
                )
            val rendered = copy.toString()

            if (source == PlayerViewModel.PlaybackRetryPositionSource.START_ZERO) {
                assertEquals(PlaybackRecoveryDetail.RetryFromStart, copy.detail)
                assertNull(copy.positionMs)
            }
            assertFalse(rendered.contains(source.name))
        }
    }

    @Test
    fun confirmedEnginePosition_exposesOnlySafeFormattedPosition() {
        val copy =
            resolvePlaybackRecoveryCopy(
                kind = "PLAYBACK_PROGRESS_TIMEOUT",
                positionMs = 42_000L,
                positionSource = PlayerViewModel.PlaybackRetryPositionSource.ENGINE_POSITION,
            )

        assertEquals(PlaybackRecoveryDetail.RetryFromPosition, copy.detail)
        assertEquals(
            PlayerR.string.player_progress_recovery_retry_from_position,
            PlaybackRecoveryDetail.RetryFromPosition.stringRes(),
        )
        assertEquals(42_000L, copy.positionMs)
        assertFalse(copy.toString().contains("ENGINE_POSITION"))
    }

    @Test
    fun knownKinds_neverAppearInRenderedCopyModel() {
        listOf("PLAYBACK_PROGRESS_TIMEOUT", "PROGRESS_UNCONFIRMED").forEach { kind ->
            val copy =
                resolvePlaybackRecoveryCopy(
                    kind = kind,
                    positionMs = 0L,
                    positionSource = PlayerViewModel.PlaybackRetryPositionSource.START_ZERO,
                )

            assertFalse(copy.toString().contains(kind))
        }
    }

    @Test
    fun progressTerminal_isNonBlockingAndDoesNotProduceActionBar() {
        val presentation =
            playbackRecoveryPresentation(
                showPlayerError = false,
                seekFailed = false,
                progressRecovery = true,
            )

        assertFalse(presentation.forceControlsVisible)
        assertTrue(presentation.canAutoHideControls)
        assertFalse(presentation.showProgressRecoveryAction)
    }

    @Test
    fun fatalPlayerErrorAndSeekFailureKeepBlockingControls() {
        val playerError =
            playbackRecoveryPresentation(
                showPlayerError = true,
                seekFailed = false,
                progressRecovery = true,
            )
        val seekFailure =
            playbackRecoveryPresentation(
                showPlayerError = false,
                seekFailed = true,
                progressRecovery = true,
            )

        assertTrue(playerError.forceControlsVisible)
        assertFalse(playerError.canAutoHideControls)
        assertTrue(seekFailure.forceControlsVisible)
        assertFalse(seekFailure.canAutoHideControls)
    }

    @Test
    fun finalizedComposeDependenciesRequestNoPlayerOrSubtitleAccess() {
        val activePlayer =
            java.lang.reflect.Proxy.newProxyInstance(
                Player::class.java.classLoader,
                arrayOf(Player::class.java),
            ) { _, _, _ ->
                null
            } as Player
        val finalizationGate = PlayerExitFinalizationGate()
        var playerProviderCalls = 0
        var downstreamCalls = 0
        val playerAccess =
            PlayerAccessInvariant(
                isClosed = { finalizationGate.isCompleted },
                existingPlayerProvider = {
                    playerProviderCalls += 1
                    activePlayer
                },
            )

        val preExitPlayer = playerAccess.existingPlayer()
        assertTrue(preExitPlayer === activePlayer)

        assertTrue(finalizationGate.run(detachVideoOutput = {}, finalizePlayback = {}))
        val postExitPlayer = playerAccess.existingPlayer()
        postExitPlayer?.let { downstreamCalls += 1 }

        assertNull(postExitPlayer)
        assertEquals(1, playerProviderCalls)
        assertEquals(0, downstreamCalls)
    }

    @Test
    fun finalizedActivityCallbackNeverCreatesPlayerAndServiceStopsOnce() {
        val activePlayer =
            java.lang.reflect.Proxy.newProxyInstance(
                Player::class.java.classLoader,
                arrayOf(Player::class.java),
            ) { _, _, _ ->
                null
            } as Player
        val finalizationGate = PlayerExitFinalizationGate()
        var playerProviderCalls = 0
        val playerAccess =
            PlayerAccessInvariant(
                isClosed = { finalizationGate.isCompleted },
                existingPlayerProvider = {
                    playerProviderCalls += 1
                    activePlayer
                },
            )
        val segmentCallbacks = PlayerSegmentCallbackCoordinator(playerAccess = playerAccess)
        var segmentCallbacksExecuted = 0
        var blockedWorkRestarts = 0

        assertTrue(segmentCallbacks.runIfActive { segmentCallbacksExecuted += 1 })

        val serviceStopGate = PlayerExitServiceStopGate()
        var serviceStops = 0
        assertTrue(
            finalizationGate.run(
                detachVideoOutput = {},
                finalizePlayback = {
                    assertTrue(
                        serviceStopGate.request {
                            serviceStops += 1
                            true
                        }
                    )
                },
            )
        )
        assertFalse(
            serviceStopGate.request {
                serviceStops += 1
                true
            }
        )
        assertFalse(
            segmentCallbacks.runIfActive {
                segmentCallbacksExecuted += 1
                blockedWorkRestarts += 1
            }
        )

        assertEquals(1, segmentCallbacksExecuted)
        assertEquals(1, serviceStops)
        assertEquals(0, blockedWorkRestarts)
        assertEquals(1, playerAccess.existingPlayerAccessCount)
        assertEquals(1, playerProviderCalls)
    }

    private fun playerViewModelHarness(): PlayerViewModelHarness {
        val lazyPlayerGetterCalls = AtomicInteger()
        val lazyPlayerCreations = AtomicInteger()
        val playerProbe = PlayerProbe()
        val sharedPreferences = mockk<SharedPreferences>(relaxed = true)
        val sharedPreferencesEditor = mockk<SharedPreferences.Editor>(relaxed = true)
        every { sharedPreferences.contains(any()) } returns false
        every { sharedPreferences.getBoolean(any(), any()) } answers { secondArg() }
        every { sharedPreferences.getInt(any(), any()) } answers { secondArg() }
        every { sharedPreferences.getLong(any(), any()) } answers { secondArg() }
        every { sharedPreferences.getFloat(any(), any()) } answers { secondArg() }
        every { sharedPreferences.getString(any(), any()) } answers { secondArg() }
        every { sharedPreferences.getStringSet(any(), any()) } answers { secondArg() }
        every { sharedPreferences.edit() } returns sharedPreferencesEditor
        every { sharedPreferencesEditor.putBoolean(any(), any()) } returns sharedPreferencesEditor
        every { sharedPreferencesEditor.putInt(any(), any()) } returns sharedPreferencesEditor
        every { sharedPreferencesEditor.putLong(any(), any()) } returns sharedPreferencesEditor
        every { sharedPreferencesEditor.putFloat(any(), any()) } returns sharedPreferencesEditor
        every { sharedPreferencesEditor.putString(any(), any()) } returns sharedPreferencesEditor
        every { sharedPreferencesEditor.putStringSet(any(), any()) } returns sharedPreferencesEditor

        val playerHolder = mockk<PlayerHolder>(relaxed = true)
        val seekSink = mockk<PlaybackSeekSink>(relaxed = true)
        every { playerHolder.player } answers
            {
                lazyPlayerGetterCalls.incrementAndGet()
                lazyPlayerCreations.incrementAndGet()
                playerProbe.player
            }
        every { playerHolder.existingPlayer } returns playerProbe.player
        every { playerHolder.seekSink } returns seekSink
        every { playerHolder.backend } returns PlayerViewModel.PLAYER_BACKEND_EXOPLAYER
        every { playerHolder.musicSessionActive } returns false
        every { playerHolder.isCurrentItemMusic } returns false

        val viewModel =
            PlayerViewModel(
                application = mockk<Application>(relaxed = true),
                repository = mockk<JellyfinRepository>(relaxed = true),
                playbackPositionWriter = mockk<PlaybackPositionWriter>(relaxed = true),
                appPreferences = AppPreferences(sharedPreferences),
                playerHolder = playerHolder,
                savedStateHandle = SavedStateHandle(),
                sleepTimerController = mockk<SleepTimerController>(relaxed = true),
                playbackCoordinator = mockk<PlaybackCoordinatorImpl>(relaxed = true),
                jellyfinApi = mockk<JellyfinApi>(relaxed = true),
            )
        lazyPlayerGetterCalls.set(0)
        lazyPlayerCreations.set(0)
        playerProbe.reset()
        return PlayerViewModelHarness(
            viewModel = viewModel,
            playerProbe = playerProbe,
            lazyPlayerGetterCalls = lazyPlayerGetterCalls,
            lazyPlayerCreations = lazyPlayerCreations,
        )
    }

    private data class PlayerViewModelHarness(
        val viewModel: PlayerViewModel,
        val playerProbe: PlayerProbe,
        val lazyPlayerGetterCalls: AtomicInteger,
        val lazyPlayerCreations: AtomicInteger,
    ) {
        fun resetAccessCounters() {
            lazyPlayerGetterCalls.set(0)
            lazyPlayerCreations.set(0)
            playerProbe.reset()
        }
    }

    private class PlayerProbe : InvocationHandler {
        val currentMediaItemAccesses = AtomicInteger()
        val prepareCalls = AtomicInteger()
        val seekCalls = AtomicInteger()
        val playCalls = AtomicInteger()

        val player: Player =
            Proxy.newProxyInstance(
                Player::class.java.classLoader,
                arrayOf(Player::class.java),
                this,
            ) as Player

        fun reset() {
            currentMediaItemAccesses.set(0)
            prepareCalls.set(0)
            seekCalls.set(0)
            playCalls.set(0)
        }

        override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? =
            when (method.name) {
                "equals" -> proxy === args?.firstOrNull()
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "PlayerProbe"
                "getCurrentMediaItem" -> {
                    currentMediaItemAccesses.incrementAndGet()
                    null
                }
                "getCurrentTracks" -> Tracks.EMPTY
                "getCurrentTimeline" -> Timeline.EMPTY
                "getAvailableCommands" -> Player.Commands.EMPTY
                "getDuration" -> C.TIME_UNSET
                "getCurrentPosition",
                "getBufferedPosition" -> 0L
                "getPlaybackState" -> Player.STATE_IDLE
                "getCurrentMediaItemIndex",
                "getMediaItemCount" -> 0
                "getRepeatMode" -> Player.REPEAT_MODE_OFF
                "prepare" -> {
                    prepareCalls.incrementAndGet()
                    null
                }
                "seekTo" -> {
                    seekCalls.incrementAndGet()
                    null
                }
                "play" -> {
                    playCalls.incrementAndGet()
                    null
                }
                else -> defaultValue(method.returnType)
            }

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
                else -> null
            }
    }
}
