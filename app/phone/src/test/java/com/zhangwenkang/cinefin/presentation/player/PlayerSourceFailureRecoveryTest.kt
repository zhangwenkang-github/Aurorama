package com.zhangwenkang.cinefin.presentation.player

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import android.widget.Toast
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
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
import com.zhangwenkang.cinefin.repository.PlaybackSourceAttemptContext
import com.zhangwenkang.cinefin.settings.domain.AppPreferences
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import java.io.IOException
import java.util.Collections
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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
        val attempts = harness.sourceAttempts.toList()
        assertEquals(2, attempts.size)
        assertEquals(attempts[0].sourceIntentId, attempts[1].sourceIntentId)
        assertNotEquals(attempts[0].attemptId, attempts[1].attemptId)
        coVerify(exactly = 2) {
            harness.playlistManager.getInitialItem(
                itemId = ITEM_ID,
                itemKind = BaseItemKind.MOVIE,
                mediaSourceIndex = null,
                preferredMediaSourceId = "source-2",
                startFromBeginning = true,
                startPositionMs = 42_000L,
                sourceAttempt = any(),
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

            delay(36_000L)
            pump(harness.dispatcher)

            val error = harness.viewModel.uiState.value.playerError
            assertNotNull(error)
            assertEquals("SOURCE_RESOLUTION_FAILED", error?.codeName)
            assertTrue(harness.viewModel.uiState.value.playbackSession.showPlayerError)
            assertEquals(0, harness.toastCalls.get())
        }

    @Test
    fun `startup network errors exhaust into a retryable source failure card`() = runBlocking {
        val harness =
            harness(
                hangSource = true,
                // 只设置 mediaId：JVM 单测里 android.net.Uri.parse 没有实现，不能走 setUri。
                mediaItem = MediaItem.Builder().setMediaId("transcode-session").build(),
            )
        harness.viewModel.initializePlayer(
            itemId = ITEM_ID,
            itemKind = BaseItemKind.MOVIE.serialName,
            startFromBeginning = false,
            startPositionMs = 42_000L,
            playbackSessionId = "startup-network-session",
        )
        pump(harness.dispatcher)
        assertEquals(1, harness.sourceCalls.get())

        harness.viewModel.onPlayerError(startupSourceTimeout())
        pump(harness.dispatcher)

        // 起播期只做一次原地重试，不弹错误卡片
        assertNull(harness.viewModel.uiState.value.playerError)
        assertEquals(1, harness.prepareCalls.get())

        harness.viewModel.onPlayerError(startupSourceTimeout())
        pump(harness.dispatcher)

        val error = harness.viewModel.uiState.value.playerError
        assertNotNull(error)
        assertEquals("STARTUP_SOURCE_FAILED", error?.codeName)
        assertEquals(PlayerViewModel.PLAYER_BACKEND_EXOPLAYER, error?.backend)
        assertTrue(harness.viewModel.uiState.value.playbackSession.showPlayerError)

        harness.viewModel.retryPlayerError()
        pump(harness.dispatcher)

        // 重试必须重新解析源（新转码会话），不是对死地址再 prepare 一次
        assertEquals(2, harness.sourceCalls.get())
        assertEquals(1, harness.prepareCalls.get())
        val attempts = harness.sourceAttempts.toList()
        assertEquals(2, attempts.size)
        assertEquals(attempts[0].sourceIntentId, attempts[1].sourceIntentId)
    }

    private fun startupSourceTimeout(): PlaybackException =
        PlaybackException(
            "source timeout",
            null,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        )

    private fun harness(hangSource: Boolean = false, mediaItem: MediaItem? = null): Harness {
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
        val prepareCalls = AtomicInteger()
        val player = mockk<Player>(relaxed = true)
        every { player.currentMediaItem } returns mediaItem
        every { player.currentTimeline } returns Timeline.EMPTY
        every { player.currentMediaItemIndex } returns -1
        every { player.mediaItemCount } returns 0
        every { player.playbackState } returns Player.STATE_IDLE
        every { player.isPlaying } returns false
        every { player.playWhenReady } returns false
        every { player.prepare() } answers
            {
                prepareCalls.incrementAndGet()
                Unit
            }
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
        val sourceAttempts =
            Collections.synchronizedList(mutableListOf<PlaybackSourceAttemptContext>())
        if (hangSource) {
            coEvery {
                playlistManager.getInitialItem(
                    itemId = any(),
                    itemKind = any(),
                    mediaSourceIndex = any(),
                    preferredMediaSourceId = any(),
                    startFromBeginning = any(),
                    startPositionMs = any(),
                    sourceAttempt = any(),
                )
            } coAnswers
                {
                    sourceAttempts += invocation.args[6] as PlaybackSourceAttemptContext
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
                    sourceAttempt = any(),
                )
            } answers
                {
                    sourceAttempts += invocation.args[6] as PlaybackSourceAttemptContext
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
        return Harness(
            viewModel,
            playlistManager,
            sourceCalls,
            toastCalls,
            sourceAttempts,
            prepareCalls,
            dispatcher,
        )
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
        val sourceAttempts: MutableList<PlaybackSourceAttemptContext>,
        val prepareCalls: AtomicInteger,
        val dispatcher: TestDispatcher,
    )
}
