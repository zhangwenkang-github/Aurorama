package com.zhangwenkang.cinefin.music.data

import com.zhangwenkang.cinefin.database.music.StoredQueue
import com.zhangwenkang.cinefin.database.music.StoredQueueState
import com.zhangwenkang.cinefin.player.core.domain.models.MusicQueue
import com.zhangwenkang.cinefin.player.core.domain.models.PlayerItem
import com.zhangwenkang.cinefin.player.core.domain.models.QueueSource
import com.zhangwenkang.cinefin.player.core.domain.models.RepeatMode
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 队列持久化映射（W21-R2：保存 / 恢复当前曲目与播放位置）。 */
class MusicQueueStoreTest {

    private fun item(index: Int, uri: String = "file:///music/$index.mp3") =
        PlayerItem(
            name = "曲目$index",
            itemId = UUID.nameUUIDFromBytes("song-$index".toByteArray()),
            mediaSourceId = "source-$index",
            playbackPosition = 1_000L * index,
            mediaSourceUri = uri,
            indexNumber = index + 1,
            thumbnailUri = "https://example.test/$index.jpg",
        )

    @Test
    fun `队列快照往返保留顺序当前曲目与位置`() {
        val items = (0 until 3).map(::item)
        val queue =
            MusicQueue(
                items = items,
                currentIndex = 1,
                repeatMode = RepeatMode.ALL,
                shuffleEnabled = true,
                source = QueueSource.ALBUM,
                sourceId = "album-1",
            )

        val snapshot = queue.toStored(positionMs = 12_345L, savedAt = 999L).toSnapshot()!!

        assertEquals(items.map { it.itemId }, snapshot.queue.items.map { it.itemId })
        assertEquals(1, snapshot.queue.currentIndex)
        assertEquals(12_345L, snapshot.queue.currentItem!!.playbackPosition)
        assertEquals(12_345L, snapshot.positionMs)
        assertEquals(999L, snapshot.savedAt)
        assertEquals(RepeatMode.ALL, snapshot.queue.repeatMode)
        assertTrue(snapshot.queue.shuffleEnabled)
        assertEquals(QueueSource.ALBUM, snapshot.queue.source)
        assertEquals("album-1", snapshot.queue.sourceId)
        // 非当前曲目的续播位置保持各自原值（切到该曲目时仍按 MU-9 续播）
        assertEquals(2_000L, snapshot.queue.items[2].playbackPosition)
        assertEquals(3, snapshot.queue.items[2].indexNumber)
    }

    @Test
    fun `无媒体地址的条目被丢弃且当前索引归一`() {
        val queue =
            MusicQueue(
                items = listOf(item(0), item(1, uri = ""), item(2)),
                currentIndex = 2,
                source = QueueSource.MANUAL,
            )

        val snapshot = queue.toStored(positionMs = 0L, savedAt = 0L).toSnapshot()!!

        assertEquals(2, snapshot.queue.items.size)
        assertEquals(1, snapshot.queue.currentIndex)
        assertEquals(item(2).itemId, snapshot.queue.currentItem!!.itemId)
    }

    @Test
    fun `空快照返回 null`() {
        val stored =
            StoredQueue(
                items = emptyList(),
                state =
                    StoredQueueState(
                        currentIndex = 0,
                        positionMs = 0L,
                        repeatMode = RepeatMode.OFF.name,
                        shuffleEnabled = false,
                        source = QueueSource.MANUAL.name,
                        sourceId = null,
                        savedAt = 0L,
                    ),
            )

        assertNull(stored.toSnapshot())
    }

    @Test
    fun `存档位置恢复态用快照位置而不是播放器位置`() {
        // 活动会话：实时位置优先
        assertEquals(
            12_345L,
            queuePersistPositionMs(
                liveQueuePresent = true,
                livePositionMs = 12_345L,
                restoredPositionMs = 999L,
            ),
        )
        // 恢复态：播放器无会话（0）时保留快照位置
        assertEquals(
            999L,
            queuePersistPositionMs(
                liveQueuePresent = false,
                livePositionMs = 0L,
                restoredPositionMs = 999L,
            ),
        )
        // 负值归一为 0
        assertEquals(
            0L,
            queuePersistPositionMs(
                liveQueuePresent = false,
                livePositionMs = 0L,
                restoredPositionMs = -5L,
            ),
        )
    }
}
