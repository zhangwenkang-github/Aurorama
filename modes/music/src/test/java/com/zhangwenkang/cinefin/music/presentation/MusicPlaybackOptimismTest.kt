package com.zhangwenkang.cinefin.music.presentation

import com.zhangwenkang.cinefin.music.data.MusicItemSource
import com.zhangwenkang.cinefin.music.data.MusicSong
import com.zhangwenkang.cinefin.player.core.domain.models.PlayerItem
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** W84：点歌乐观展示 / 解析失败回滚。 */
class MusicPlaybackOptimismTest {

    @Test
    fun `pending target replaces old current item immediately`() {
        val old = playerItem("旧曲")
        val target = song("新曲")
        val pending = PendingPlaybackUi(requestId = 2, item = target.toPendingPlayerItem())

        assertEquals(target.itemId, displayPlaybackItem(pending, old)?.itemId)
        assertEquals("新曲", displayPlaybackItem(pending, old)?.name)
    }

    @Test
    fun `failed parse rolls back to the still playing item`() {
        val old = playerItem("旧曲")
        val pending = PendingPlaybackUi(requestId = 2, item = song("坏文件").toPendingPlayerItem())

        val rolledBack = pendingPlaybackAfterFailure(pending, failedRequestId = 2)

        assertNull(rolledBack)
        assertEquals(old.itemId, displayPlaybackItem(rolledBack, old)?.itemId)
    }

    @Test
    fun `stale failure cannot clear a newer selection`() {
        val newer = PendingPlaybackUi(requestId = 3, item = song("新目标").toPendingPlayerItem())

        assertEquals(newer, pendingPlaybackAfterFailure(newer, failedRequestId = 2))
    }

    @Test
    fun `successful parse hands display to real queue item`() {
        val resolved = playerItem("已解析")

        assertEquals(
            resolved.itemId,
            displayPlaybackItem(pending = null, liveItem = resolved)?.itemId,
        )
    }

    private fun song(name: String): MusicSong =
        MusicSong(
            itemId = UUID.randomUUID(),
            name = name,
            albumName = "专辑",
            artist = "歌手",
            indexNumber = 1,
            runtimeTicks = 0L,
            imageUri = null,
            source = MusicItemSource.SERVER,
        )

    private fun playerItem(name: String): PlayerItem =
        PlayerItem(
            name = name,
            itemId = UUID.randomUUID(),
            mediaSourceId = "source",
            playbackPosition = 0L,
        )
}
