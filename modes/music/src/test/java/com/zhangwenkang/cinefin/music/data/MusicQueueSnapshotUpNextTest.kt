package com.zhangwenkang.cinefin.music.data

import com.zhangwenkang.cinefin.database.music.StoredQueue
import com.zhangwenkang.cinefin.database.music.StoredQueueItem
import com.zhangwenkang.cinefin.database.music.StoredQueueKind
import com.zhangwenkang.cinefin.database.music.StoredQueueState
import com.zhangwenkang.cinefin.player.core.domain.models.MusicQueue
import com.zhangwenkang.cinefin.player.core.domain.models.PlayerItem
import com.zhangwenkang.cinefin.player.core.domain.models.QueueSource
import com.zhangwenkang.cinefin.player.core.domain.models.RepeatMode
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** W5 T5（MUSIC-09/13）：队列快照回环 —— 接下来播放入快照并标注类型，恢复后仍在、顺序不变。 */
class MusicQueueSnapshotUpNextTest {

    private fun item(id: String, position: Long = 0L) =
        PlayerItem(
            name = id,
            itemId = UUID.nameUUIDFromBytes(id.toByteArray()),
            mediaSourceId = id,
            playbackPosition = position,
            mediaSourceUri = "file:///$id",
            thumbnailUri = null,
            indexNumber = null,
        )

    private fun queue(
        mainIds: List<String> = listOf("A", "B", "C"),
        currentIndex: Int = 1,
        upNext: List<String> = emptyList(),
        upNextPlaying: String? = null,
        shuffleEnabled: Boolean = false,
        shuffleKeepCurrentFirst: Boolean = false,
    ) =
        MusicQueue(
            items = mainIds.map { id -> item(id) },
            currentIndex = currentIndex,
            repeatMode = RepeatMode.ALL,
            shuffleEnabled = shuffleEnabled,
            source = QueueSource.PLAYLIST,
            sourceId = "playlist-1",
            upNext = upNext.map { id -> item(id) },
            upNextPlaying = upNextPlaying?.let { id -> item(id) },
            shuffleKeepCurrentFirst = shuffleKeepCurrentFirst,
        )

    @Test
    fun `接下来播放入快照并标注类型`() {
        val stored = queue(upNext = listOf("X", "Y")).toStored(positionMs = 42_000L, savedAt = 99L)

        val main = stored.items.filter { it.kind == StoredQueueKind.MAIN }
        val upNext = stored.items.filter { it.kind == StoredQueueKind.UP_NEXT }
        assertEquals(listOf("A", "B", "C"), main.map { it.name })
        assertEquals(listOf("X", "Y"), upNext.map { it.name })
        // position 全局唯一：主队列在前，接下来播放在后
        assertEquals(listOf(0, 1, 2, 3, 4), stored.items.map { it.position })
        assertEquals(42_000L, stored.state.positionMs)
        assertEquals(1, stored.state.currentIndex)
    }

    @Test
    fun `播放中的接下来播放条目回写成待播队首 不丢曲目`() {
        val playing = queue(upNext = listOf("Y", "Z"), upNextPlaying = "X")
        val stored = playing.toStored(positionMs = 5_000L, savedAt = 1L)
        val upNext = stored.items.filter { it.kind == StoredQueueKind.UP_NEXT }

        assertEquals(listOf("X", "Y", "Z"), upNext.map { it.name })

        val restored = stored.toSnapshot()!!
        assertEquals(listOf("X", "Y", "Z"), restored.queue.upNext.map { it.name })
        assertNull(restored.queue.upNextPlaying)
        // 位置只写回主队列当前曲：播放中的条目位置不污染主队列锚点
        assertEquals(listOf(0L, 5_000L, 0L), restored.queue.items.map { it.playbackPosition })
    }

    @Test
    fun `快照写入恢复回环 主队列与索引不变`() {
        val original = queue(upNext = listOf("X", "Y"))
        val restored = original.toStored(positionMs = 12_000L, savedAt = 7L).toSnapshot()!!.queue

        assertEquals(original.items.map { it.name }, restored.items.map { it.name })
        assertEquals(original.currentIndex, restored.currentIndex)
        assertEquals(listOf("X", "Y"), restored.upNext.map { it.name })
        assertEquals(RepeatMode.ALL, restored.repeatMode)
        assertEquals(QueueSource.PLAYLIST, restored.source)
        assertEquals("playlist-1", restored.sourceId)
        // 恢复后时间线仍是「当前曲 → 接下来播放 → 主队列剩余」
        assertEquals(
            listOf("A", "B", "X", "Y", "C"),
            restored.playbackItems.map { it.name },
        )
    }

    @Test
    fun `随机细分入快照 恢复后模式不丢`() {
        val stored =
            queue(shuffleEnabled = true, shuffleKeepCurrentFirst = true)
                .toStored(positionMs = 0L, savedAt = 0L)
        assertTrue(stored.state.shuffleKeepCurrentFirst)

        val restored = stored.toSnapshot()!!
        assertTrue(restored.queue.shuffleEnabled)
        assertTrue(restored.queue.shuffleKeepCurrentFirst)
    }

    @Test
    fun `随机关闭时居首标记不落盘`() {
        val stored =
            queue(shuffleEnabled = false, shuffleKeepCurrentFirst = true)
                .toStored(positionMs = 0L, savedAt = 0L)
        assertFalse(stored.state.shuffleKeepCurrentFirst)

        // 旧快照（v3 迁移补 false）恢复为顺序/列表循环，不误判成随机
        val legacyState =
            StoredQueueState(
                currentIndex = 0,
                positionMs = 0L,
                repeatMode = RepeatMode.ALL.name,
                shuffleEnabled = false,
                source = QueueSource.PLAYLIST.name,
                sourceId = null,
                savedAt = 0L,
            )
        val legacy =
            StoredQueue(
                    items =
                        listOf(
                            StoredQueueItem(
                                position = 0,
                                itemId = item("A").itemId.toString(),
                                name = "A",
                                mediaSourceId = "A",
                                playbackPositionMs = 0L,
                                mediaSourceUri = "file:///A",
                                thumbnailUri = null,
                                indexNumber = null,
                            )
                        ),
                    state = legacyState,
                )
                .toSnapshot()!!
        assertEquals(RepeatMode.ALL, legacy.queue.repeatMode)
        assertFalse(legacy.queue.shuffleEnabled)
        assertFalse(legacy.queue.shuffleKeepCurrentFirst)
        assertTrue(legacy.queue.upNext.isEmpty())
    }

    @Test
    fun `有接下来播放时不持久化随机序 恢复后时间线不再包含它`() {
        val withUpNext =
            queue(shuffleEnabled = true, upNext = listOf("X"))
                .copy(shuffleOrder = listOf(1, 0, 2), shuffleSeed = 5L)
        val stored = withUpNext.toStored(positionMs = 0L, savedAt = 0L)
        assertNull(stored.state.shuffleOrder)
        assertNull(stored.state.shuffleSeed)

        val restored = stored.toSnapshot()!!
        assertNull(restored.queue.shuffleOrder)
        assertEquals(listOf("X"), restored.queue.upNext.map { it.name })
    }

    @Test
    fun `空主队列的快照不恢复`() {
        val stored =
            StoredQueue(
                items =
                    listOf(
                        StoredQueueItem(
                            position = 0,
                            itemId = item("X").itemId.toString(),
                            name = "X",
                            mediaSourceId = "X",
                            playbackPositionMs = 0L,
                            mediaSourceUri = "file:///X",
                            thumbnailUri = null,
                            indexNumber = null,
                            kind = StoredQueueKind.UP_NEXT,
                        )
                    ),
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
}
