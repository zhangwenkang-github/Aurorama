package com.zhangwenkang.cinefin.player.local.domain

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicPlaybackSurfaceStateTest {
    @Test
    fun `startup buffering keeps the pause action instead of publishing paused`() {
        val fake =
            FakePlayer(
                playWhenReady = true,
                isPlaying = false,
                playbackState = Player.STATE_BUFFERING,
                mediaItemCount = 1,
            )

        val state = MusicPlaybackSurfaceState.from(fake.player)

        assertTrue(state.isPlaybackActive)
        assertEquals(MusicTransportAction.PAUSE, state.transportAction)
        fake.player.toggleMusicPlaybackTransport()
        assertEquals(listOf("pause"), fake.calls)
        assertFalse(fake.playWhenReady)
    }

    @Test
    fun `pause then resume follows playWhenReady across readiness changes`() {
        val fake =
            FakePlayer(
                playWhenReady = false,
                isPlaying = false,
                playbackState = Player.STATE_BUFFERING,
                mediaItemCount = 1,
            )

        val resumed = fake.player.toggleMusicPlaybackTransport()
        assertEquals(MusicTransportAction.PAUSE, resumed.transportAction)
        assertTrue(resumed.isPlaybackActive)
        assertTrue(fake.playWhenReady)

        fake.isPlaying = true
        fake.playbackState = Player.STATE_READY
        val paused = fake.player.toggleMusicPlaybackTransport()
        assertEquals(MusicTransportAction.PLAY, paused.transportAction)
        assertFalse(paused.isPlaybackActive)
        assertFalse(fake.playWhenReady)
    }

    @Test
    fun `source switch keeps one active transport intent for the replacement track`() {
        val fake =
            FakePlayer(
                playWhenReady = true,
                isPlaying = true,
                playbackState = Player.STATE_READY,
                mediaItemCount = 1,
            )
        assertTrue(MusicPlaybackSurfaceState.from(fake.player).isPlaybackActive)

        fake.switchSource()
        val replacement = MusicPlaybackSurfaceState.from(fake.player)

        assertTrue(replacement.isPlaybackActive)
        assertEquals(MusicTransportAction.PAUSE, replacement.transportAction)
        assertEquals("track-2", fake.player.currentMediaItem?.mediaId)
    }

    @Test
    fun `ended player does not advertise a pause action`() {
        val fake =
            FakePlayer(
                playWhenReady = true,
                isPlaying = false,
                playbackState = Player.STATE_ENDED,
                mediaItemCount = 1,
            )

        val state = MusicPlaybackSurfaceState.from(fake.player)

        assertFalse(state.isPlaybackActive)
        assertEquals(MusicTransportAction.PLAY, state.transportAction)
    }

    private class FakePlayer(
        var playWhenReady: Boolean,
        var isPlaying: Boolean,
        var playbackState: Int,
        var mediaItemCount: Int,
    ) {
        val calls = mutableListOf<String>()
        var currentMediaItem: MediaItem? = MediaItem.Builder().setMediaId("track-1").build()
            private set

        val player: Player =
            Proxy.newProxyInstance(
                Player::class.java.classLoader,
                arrayOf(Player::class.java),
            ) { _, method, _ ->
                when (method.name) {
                    "getPlayWhenReady" -> playWhenReady
                    "isPlaying" -> isPlaying
                    "getPlaybackState" -> playbackState
                    "getMediaItemCount" -> mediaItemCount
                    "getCurrentMediaItem" -> currentMediaItem
                    "play" -> {
                        calls += "play"
                        playWhenReady = true
                        Unit
                    }
                    "pause" -> {
                        calls += "pause"
                        playWhenReady = false
                        isPlaying = false
                        Unit
                    }
                    else -> defaultValue(method.returnType)
                }
            } as Player

        fun switchSource() {
            currentMediaItem = MediaItem.Builder().setMediaId("track-2").build()
            playbackState = Player.STATE_BUFFERING
            isPlaying = false
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
