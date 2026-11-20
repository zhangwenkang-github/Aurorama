package com.zhangwenkang.cinefin.player.local.presentation

import androidx.media3.common.C
import androidx.media3.common.Player
import com.zhangwenkang.cinefin.player.local.syncplay.SyncPlayTransportCommands
import com.zhangwenkang.cinefin.player.local.syncplay.SyncPlayTransportGate
import com.zhangwenkang.cinefin.syncplay.SyncPlayError
import java.lang.reflect.Proxy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaSessionSeekPlayerTest {
    @Test
    fun `media session seeks become intents without reaching delegate`() {
        val fake = fakePlayer(seekBackIncrementMs = 10_000L, seekForwardIncrementMs = 20_000L)
        val intents = mutableListOf<PlayerViewModel.SeekIntent>()
        val facade = MediaSessionSeekPlayer(fake.player, submitSeek = { intents += it })

        facade.seekTo(12_345L)
        facade.seekTo(3, 23_456L)
        facade.seekToDefaultPosition()
        facade.seekToDefaultPosition(4)
        facade.seekBack()
        facade.seekForward()
        facade.seekToPreviousMediaItem()
        facade.seekToPrevious()
        facade.seekToNextMediaItem()
        facade.seekToNext()

        assertEquals(
            listOf(
                PlayerViewModel.SeekIntent.Absolute(12_345L),
                PlayerViewModel.SeekIntent.MediaItem(index = 3, positionMs = 23_456L),
                PlayerViewModel.SeekIntent.Absolute(0L),
                PlayerViewModel.SeekIntent.MediaItem(index = 4, positionMs = C.TIME_UNSET),
                PlayerViewModel.SeekIntent.Relative(-10_000L),
                PlayerViewModel.SeekIntent.Relative(20_000L),
                PlayerViewModel.SeekIntent.AdjacentMediaItem(forward = false),
                PlayerViewModel.SeekIntent.AdjacentMediaItem(forward = false),
                PlayerViewModel.SeekIntent.AdjacentMediaItem(forward = true),
                PlayerViewModel.SeekIntent.AdjacentMediaItem(forward = true),
            ),
            intents,
        )
        assertFalse(fake.calls.any { it.startsWith(SEEK_METHOD_PREFIX) })
    }

    @Test
    fun `non seek commands remain delegated`() {
        val fake = fakePlayer()
        val facade = MediaSessionSeekPlayer(fake.player, submitSeek = {})

        facade.play()
        facade.pause()
        facade.stop()

        assertTrue(fake.calls.contains("play"))
        assertTrue(fake.calls.contains("pause"))
        assertTrue(fake.calls.contains("stop"))
    }

    @Test
    fun `组内媒体键只发组命令 不写本地引擎`() {
        val fake = fakePlayer()
        val commands = RecordingCommands()
        val gate = gateWith(commands)
        gate.updateOwned(true)
        val facade = MediaSessionSeekPlayer(fake.player, {}, gate)

        runBlocking {
            facade.play()
            yield()
            facade.pause()
            yield()
        }

        assertEquals(listOf("unpause", "pause"), commands.calls)
        assertFalse(fake.calls.contains("play"))
        assertFalse(fake.calls.contains("pause"))
    }

    @Test
    fun `出组后媒体键恢复直写引擎`() {
        val fake = fakePlayer()
        val commands = RecordingCommands()
        val gate = gateWith(commands)
        gate.updateOwned(true)
        gate.updateOwned(false)
        val facade = MediaSessionSeekPlayer(fake.player, {}, gate)

        facade.play()
        facade.pause()

        assertTrue(commands.calls.isEmpty())
        assertTrue(fake.calls.contains("play"))
        assertTrue(fake.calls.contains("pause"))
    }

    private class FakePlayer(
        private val seekBackIncrementMs: Long,
        private val seekForwardIncrementMs: Long,
    ) {
        val calls = mutableListOf<String>()

        val player: Player =
            Proxy.newProxyInstance(
                Player::class.java.classLoader,
                arrayOf(Player::class.java),
            ) { _, method, _ ->
                when (method.name) {
                    "getSeekBackIncrement" -> seekBackIncrementMs
                    "getSeekForwardIncrement" -> seekForwardIncrementMs
                    "getPlaybackState" -> Player.STATE_READY
                    "getDuration" -> 120_000L
                    "getMediaItemCount" -> 0
                    "isCommandAvailable" -> true
                    else -> {
                        calls += method.name
                        defaultValue(method.returnType)
                    }
                }
            } as Player
    }

    private class RecordingCommands : SyncPlayTransportCommands {
        val calls = mutableListOf<String>()
        var failWith: SyncPlayError? = null

        override suspend fun pause(): SyncPlayError? {
            calls += "pause"
            return failWith
        }

        override suspend fun unpause(): SyncPlayError? {
            calls += "unpause"
            return failWith
        }

        override suspend fun seek(positionMs: Long): SyncPlayError? {
            calls += "seek:$positionMs"
            return failWith
        }
    }

    private companion object {
        const val SEEK_METHOD_PREFIX = "seekTo"

        fun fakePlayer(
            seekBackIncrementMs: Long = 10_000L,
            seekForwardIncrementMs: Long = 10_000L,
        ): FakePlayer = FakePlayer(seekBackIncrementMs, seekForwardIncrementMs)

        fun gateWith(commands: SyncPlayTransportCommands): SyncPlayTransportGate =
            SyncPlayTransportGate(
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
                isPlaying = { false },
                currentPositionMs = { 0L },
                commands = commands,
            )

        fun defaultValue(type: Class<*>): Any? =
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
