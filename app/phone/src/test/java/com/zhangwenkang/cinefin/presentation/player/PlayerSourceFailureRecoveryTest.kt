package com.zhangwenkang.cinefin.presentation.player

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import android.widget.Toast
import androidx.media3.common.Player
import com.zhangwenkang.cinefin.api.JellyfinApi
import com.zhangwenkang.cinefin.player.local.domain.PlaybackCoordinatorImpl
import com.zhangwenkang.cinefin.player.local.domain.PlaybackPositionWriter
import com.zhangwenkang.cinefin.player.local.domain.PlaylistManager
import com.zhangwenkang.cinefin.player.local.domain.SeekState
import com.zhangwenkang.cinefin.player.local.domain.SleepTimerController
import com.zhangwenkang.cinefin.player.local.presentation.PlaybackSeekSink
import com.zhangwenkang.cinefin.player.local.presentation.PlayerHolder
import com.zhangwenkang.cinefin.player.local.presentation.PlayerViewModel
import com.zhangwenkang.cinefin.repository.JellyfinRepository
import com.zhangwenkang.cinefin.settings.domain.AppPreferences
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.jellyfin.sdk.model.api.BaseItemKind
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerSourceFailureRecoveryTest {
    companion object {
        private val ITEM_ID = UUID.fromString("33333333-3333-4333-8333-333333333333")

        init {
            System.setProperty(
                "slf4j.provider",
                "org.slf4j.helpers.NOP_FallbackServiceProvider",
            )
        }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    @Test
    fun `source resolution failure publishes persistent player error`() = runBlocking {
        val harness = harness()
        harness.viewModel.initializePlayer(
            itemId = ITEM_ID,
            itemKind = BaseItemKind.MOVIE.serialName,
            startFromBeginning = false,
            playbackSessionId = "source-failure-session",
        )

        pump(harness.dispatcher)

        assertEquals(1, harness.sourceCalls.get())
        val error = harness.viewModel.uiState.value.playerError
        assertNotNull(error)
        assertEquals(PlayerViewModel.PLAYER_BACKEND_EXOPLAYER, error?.backend)
        assertEquals("SOURCE_RESOLUTION_FAILED", error?.codeName)
        assertTrue(harness.viewModel.uiState.value.playbackSession.showPlayerError)
        assertEquals(0, harness.toastCalls.get())
    }

    @Test
    fun `source resolution retry repeats the same source intent`() = runBlocking {
        val harness = harness()
        harness.viewModel.initializePlayer(
            itemId = ITEM_ID,
            itemKind = BaseItemKind.MOVIE.serialName,
            startFromBeginning = true,
            startPositionMs = 42_000L,
            playbackSessionId = "source-retry-session",
            preferredMediaSourceId = "source-2",
        )
        pump(harness.dispatcher)
        assertEquals(1, harness.sourceCalls.get())

        harness.viewModel.retryPlayerError()
        pump(harness.dispatcher)

        assertEquals(2, harness.sourceCalls.get())
        coVerify(exactly = 2) {
            harness.playlistManager.getInitialItem(
                itemId = ITEM_ID,
                itemKind = BaseItemKind.MOVIE,
                mediaSourceIndex = null,
                preferredMediaSourceId = "source-2",
                startFromBeginning = true,
                startPositionMs = 42_000L,
            )
        }
        val error = harness.viewModel.uiState.value.playerError
        assertNotNull(error)
        assertEquals("SOURCE_RESOLUTION_FAILED", error?.codeName)
    }

    @Test
    fun `source resolution without a terminal event times out into persistent error`() =
        runBlocking {
            val harness = harness(hangSource = true)
            harness.viewModel.initializePlayer(
                itemId = ITEM_ID,
                itemKind = BaseItemKind.MOVIE.serialName,
                startFromBeginning = false,
                playbackSessionId = "source-timeout-session",
            )
            pump(harness.dispatcher)
            assertEquals(1, harness.sourceCalls.get())

            delay(31_000L)
            pump(harness.dispatcher)

            val error = harness.viewModel.uiState.value.playerError
            assertNotNull(error)
            assertEquals("SOURCE_RESOLUTION_FAILED", error?.codeName)
            assertTrue(harness.viewModel.uiState.value.playbackSession.showPlayerError)
            assertEquals(0, harness.toastCalls.get())
        }

    private fun harness(hangSource: Boolean = false): Harness {
        val dispatcher = StandardTestDispatcher()
        Dispatchers.setMain(dispatcher)
        mockkStatic(SystemClock::class)
        every { SystemClock.elapsedRealtime() } returns 10_000L
        val toastCalls = AtomicInteger()
        mockkStatic(Toast::class)
        every { Toast.makeText(any<Context>(), any<String>(), any()) } answers
            {
                toastCalls.incrementAndGet()
                mockk<Toast>(relaxed = true)
            }

        val sharedPreferences = mockk<SharedPreferences>(relaxed = true)
        val editor = mockk<SharedPreferences.Editor>(relaxed = true)
        every { sharedPreferences.contains(any()) } returns false
        every { sharedPreferences.getBoolean(any(), any()) } answers { secondArg() }
        every { sharedPreferences.getInt(any(), any()) } answers { secondArg() }
        every { sharedPreferences.getLong(any(), any()) } answers { secondArg() }
        every { sharedPreferences.getFloat(any(), any()) } answers { secondArg() }
        every { sharedPreferences.getString(any(), any()) } answers { secondArg() }
        every { sharedPreferences.getStringSet(any(), any()) } answers { secondArg() }
        every { sharedPreferences.edit() } returns editor
        every { editor.putBoolean(any(), any()) } returns editor
        every { editor.putInt(any(), any()) } returns editor
        every { editor.putLong(any(), any()) } returns editor
        every { editor.putFloat(any(), any()) } returns editor
        every { editor.putString(any(), any()) } returns editor
        every { editor.putStringSet(any(), any()) } returns editor

        val application = mockk<Application>(relaxed = true)
        every { application.getString(any<Int>()) } returns "Playback failed"
        val player = mockk<Player>(relaxed = true)
        every { player.currentMediaItem } returns null
        every { player.currentMediaItemIndex } returns -1
        every { player.mediaItemCount } returns 0
        every { player.playbackState } returns Player.STATE_IDLE
        every { player.isPlaying } returns false
        every { player.playWhenReady } returns false
        val seekSink = mockk<PlaybackSeekSink>(relaxed = true)
        every { seekSink.stateFlow } returns MutableStateFlow(SeekState.Idle)
        val playlistManager = mockk<PlaylistManager>(relaxed = true)
        val playerHolder = mockk<PlayerHolder>(relaxed = true)
        every { playerHolder.player } returns player
        every { playerHolder.existingPlayer } returns player
        every { playerHolder.seekSink } returns seekSink
        every { playerHolder.playlistManager } returns playlistManager
        every { playerHolder.backend } returns PlayerViewModel.PLAYER_BACKEND_EXOPLAYER
        every { playerHolder.musicSessionActive } returns false
        every { playerHolder.isCurrentItemMusic } returns false
        val sourceCalls = AtomicInteger()
        if (hangSource) {
            coEvery {
                playlistManager.getInitialItem(
                    itemId = any(),
                    itemKind = any(),
                    mediaSourceIndex = any(),
                    preferredMediaSourceId = any(),
                    startFromBeginning = any(),
                    startPositionMs = any(),
                )
            } coAnswers
                {
                    sourceCalls.incrementAndGet()
                    awaitCancellation()
                }
        } else {
            coEvery {
                playlistManager.getInitialItem(
                    itemId = any(),
                    itemKind = any(),
                    mediaSourceIndex = any(),
                    preferredMediaSourceId = any(),
                    startFromBeginning = any(),
                    startPositionMs = any(),
                )
            } answers
                {
                    sourceCalls.incrementAndGet()
                    throw IOException("source unavailable")
                }
        }

        val viewModel =
            PlayerViewModel(
                application = application,
                repository = mockk<JellyfinRepository>(relaxed = true),
                playbackPositionWriter = mockk<PlaybackPositionWriter>(relaxed = true),
                appPreferences = AppPreferences(sharedPreferences),
                playerHolder = playerHolder,
                savedStateHandle = androidx.lifecycle.SavedStateHandle(),
                sleepTimerController = mockk<SleepTimerController>(relaxed = true),
                playbackCoordinator = mockk<PlaybackCoordinatorImpl>(relaxed = true),
                jellyfinApi = mockk<JellyfinApi>(relaxed = true),
            )
        return Harness(viewModel, playlistManager, sourceCalls, toastCalls, dispatcher)
    }

    private suspend fun pump(dispatcher: TestDispatcher) {
        repeat(40) {
            dispatcher.scheduler.runCurrent()
            delay(5)
        }
        dispatcher.scheduler.runCurrent()
    }

    private data class Harness(
        val viewModel: PlayerViewModel,
        val playlistManager: PlaylistManager,
        val sourceCalls: AtomicInteger,
        val toastCalls: AtomicInteger,
        val dispatcher: TestDispatcher,
    )
}
