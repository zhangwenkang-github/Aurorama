package com.zhangwenkang.cinefin.player.local.domain

import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerEndBehaviorTest {

    @Test
    fun `自动下一集开且有下一集时播下一条`() {
        assertEquals(
            PlayerItemEndAction.PLAY_NEXT,
            PlayerEndBehavior.itemEndAction(
                repeatMode = Player.REPEAT_MODE_OFF,
                hasNextMediaItem = true,
                autoNextEpisode = true,
            ),
        )
    }

    @Test
    fun `自动下一集关时停在结束帧`() {
        assertEquals(
            PlayerItemEndAction.STAY_AT_END,
            PlayerEndBehavior.itemEndAction(
                repeatMode = Player.REPEAT_MODE_OFF,
                hasNextMediaItem = true,
                autoNextEpisode = false,
            ),
        )
    }

    @Test
    fun `队列末尾没有下一集时停在结束帧`() {
        assertEquals(
            PlayerItemEndAction.STAY_AT_END,
            PlayerEndBehavior.itemEndAction(
                repeatMode = Player.REPEAT_MODE_OFF,
                hasNextMediaItem = false,
                autoNextEpisode = true,
            ),
        )
    }

    @Test
    fun `单集循环优先于自动下一集`() {
        assertEquals(
            PlayerItemEndAction.LOOP_CURRENT,
            PlayerEndBehavior.itemEndAction(
                repeatMode = Player.REPEAT_MODE_ONE,
                hasNextMediaItem = true,
                autoNextEpisode = false,
            ),
        )
    }

    @Test
    fun `列表循环在末尾仍有下一集时自动前进`() {
        assertEquals(
            PlayerItemEndAction.PLAY_NEXT,
            PlayerEndBehavior.itemEndAction(
                repeatMode = Player.REPEAT_MODE_ALL,
                hasNextMediaItem = true,
                autoNextEpisode = true,
            ),
        )
    }

    @Test
    fun `停在结束帧开时队列播完不退出播放页`() {
        assertEquals(PlayerQueueEndAction.STAY_AT_END, PlayerEndBehavior.queueEndAction(true))
    }

    @Test
    fun `停在结束帧关时队列播完退出播放页`() {
        assertEquals(PlayerQueueEndAction.CLOSE_PLAYER, PlayerEndBehavior.queueEndAction(false))
    }
}
