package com.zhangwenkang.cinefin.player.local.presentation

import androidx.media3.common.C
import androidx.media3.common.Player
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaSessionSeekPlayerTest {
    @Test
    fun `media session seeks become intents without reaching delegate`() {
        val fake = fakePlayer(seekBackIncrementMs = 10_000L, seekForwardIncrementMs = 20_000L)
        val intents = mutableListOf<PlayerViewModel.SeekIntent>()
        val facade = MediaSessionSeekPlayer(fake.player) { intents += it }

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
        val facade = MediaSessionSeekPlayer(fake.player) {}

        facade.play()
        facade.pause()
        facade.stop()

        assertTrue(fake.calls.contains("play"))
        assertTrue(fake.calls.contains("pause"))
        assertTrue(fake.calls.contains("stop"))
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

    private companion object {
        const val SEEK_METHOD_PREFIX = "seekTo"

        fun fakePlayer(
            seekBackIncrementMs: Long = 10_000L,
            seekForwardIncrementMs: Long = 10_000L,
        ): FakePlayer = FakePlayer(seekBackIncrementMs, seekForwardIncrementMs)

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
