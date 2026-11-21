package com.zhangwenkang.cinefin.player.local.domain

import androidx.media3.common.Player
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** W5-T12（MUSIC-14）：队尾成因标志——错误跳曲置位、自然 AUTO 推进复位、消费只生效一次。 */
class MusicQueueEndFlagTest {

    @Test
    fun `错误跳曲后 队尾成因是错误 且只消费一次`() {
        val flag = MusicQueueEndFlag()

        flag.onErrorSkip()

        assertTrue(flag.consume())
        assertFalse(flag.consume())
    }

    @Test
    fun `自然推进（AUTO）复位为自然播完`() {
        val flag = MusicQueueEndFlag()

        flag.onErrorSkip()
        flag.onMediaItemTransition(Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)

        assertFalse(flag.consume())
    }

    @Test
    fun `手动切歌与换队列过渡不复位`() {
        val seek = MusicQueueEndFlag()
        seek.onErrorSkip()
        seek.onMediaItemTransition(Player.MEDIA_ITEM_TRANSITION_REASON_SEEK)
        assertTrue(seek.consume())

        val playlistChanged = MusicQueueEndFlag()
        playlistChanged.onErrorSkip()
        playlistChanged.onMediaItemTransition(Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED)
        assertTrue(playlistChanged.consume())
    }

    @Test
    fun `换队列复位后队尾按自然播完处理`() {
        val flag = MusicQueueEndFlag()

        flag.onErrorSkip()
        flag.reset()

        assertFalse(flag.consume())
    }

    @Test
    fun `没有错误跳曲时默认自然播完`() {
        val flag = MusicQueueEndFlag()

        flag.onMediaItemTransition(Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)

        assertFalse(flag.consume())
    }
}
