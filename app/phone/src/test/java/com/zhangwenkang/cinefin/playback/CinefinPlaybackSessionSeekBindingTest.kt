package com.zhangwenkang.cinefin.playback

import androidx.media3.common.Player
import com.zhangwenkang.cinefin.player.local.presentation.PlayerViewModel
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class CinefinPlaybackSessionSeekBindingTest {
    @Test
    fun `session binding wraps active player with shared seek sink facade`() {
        val delegate =
            Proxy.newProxyInstance(
                Player::class.java.classLoader,
                arrayOf(Player::class.java),
            ) { _, method, _ ->
                when (method.returnType) {
                    java.lang.Boolean.TYPE -> false
                    java.lang.Integer.TYPE -> 0
                    java.lang.Long.TYPE -> 0L
                    java.lang.Float.TYPE -> 0f
                    else -> null
                }
            } as Player
        val intents = mutableListOf<PlayerViewModel.SeekIntent>()

        val bound = mediaSessionSeekPlayerForSession(delegate) { intents += it }

        assertSame(delegate, bound.wrappedPlayer)
        bound.seekTo(8_000L)
        assertEquals(listOf(PlayerViewModel.SeekIntent.Absolute(8_000L)), intents)
    }
}
