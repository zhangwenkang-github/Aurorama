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
                shuffleOrder = listOf(2, 0, 1),
                shuffleSeed = 7_654_321L,
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
        assertEquals(listOf(2, 0, 1), snapshot.queue.shuffleOrder)
        assertEquals(7_654_321L, snapshot.queue.shuffleSeed)
        // 非当前曲目的续播位置保持各自原值（切到该曲目时仍按 MU-9 续播）
        assertEquals(2_000L, snapshot.queue.items[2].playbackPosition)
        assertEquals(3, snapshot.queue.items[2].indexNumber)
    }

    @Test
    fun `非法持久随机序显式降级但保留其他状态`() {
        val queue =
            MusicQueue(
                items = (0 until 3).map(::item),
                currentIndex = 2,
                repeatMode = RepeatMode.ONE,
                shuffleEnabled = true,
                source = QueueSource.PLAYLIST,
                sourceId = "playlist-1",
                shuffleOrder = listOf(2, 0, 2),
                shuffleSeed = 22L,
            )

        val snapshot = queue.toStored(positionMs = 33L, savedAt = 44L).toSnapshot()!!

        assertEquals(listOf(0, 1, 2), snapshot.queue.items.map { it.indexNumber }.map { it!! - 1 })
        assertEquals(2, snapshot.queue.currentIndex)
        assertEquals(RepeatMode.ONE, snapshot.queue.repeatMode)
        assertTrue(snapshot.queue.shuffleEnabled)
        assertEquals(QueueSource.PLAYLIST, snapshot.queue.source)
        assertEquals("playlist-1", snapshot.queue.sourceId)
        assertEquals(null, snapshot.queue.shuffleOrder)
        assertEquals(null, snapshot.queue.shuffleSeed)
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
                        shuffleOrder = null,
                        shuffleSeed = null,
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

    @Test
    fun `活动队列缺少完整实际序时暂不落盘`() {
        assertEquals(
            false,
            queueCanPersistShuffleSnapshot(
                queue = MusicQueue(items = (0 until 3).map(::item), source = QueueSource.MANUAL),
                liveQueuePresent = true,
            ),
        )
        assertEquals(
            true,
            queueCanPersistShuffleSnapshot(
                queue =
                    MusicQueue(
                        items = (0 until 3).map(::item),
                        source = QueueSource.MANUAL,
                        shuffleOrder = listOf(2, 0, 1),
                        shuffleSeed = 5L,
                    ),
                liveQueuePresent = true,
            ),
        )
        assertEquals(
            true,
            queueCanPersistShuffleSnapshot(
                queue = MusicQueue(items = (0 until 3).map(::item), source = QueueSource.MANUAL),
                liveQueuePresent = false,
            ),
        )
    }
}
