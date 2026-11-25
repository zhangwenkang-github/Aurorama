package com.zhangwenkang.cinefin.music.data

import com.zhangwenkang.cinefin.database.music.StoredQueue
import com.zhangwenkang.cinefin.database.music.StoredQueueItem
import com.zhangwenkang.cinefin.database.music.StoredQueueState
import com.zhangwenkang.cinefin.player.core.domain.models.QueueSource
import com.zhangwenkang.cinefin.player.core.domain.models.RepeatMode
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** W3-T17：队列快照「只剔除确认过时条目」——当前曲目被剔除时就近迁移，不整快照作废。 */
class MusicQueueRestoreIndexTest {

    private val staleUri = ""

    private fun storedItem(position: Int, uri: String) =
        StoredQueueItem(
            position = position,
            itemId = UUID.nameUUIDFromBytes("song-$position".toByteArray()).toString(),
            name = "曲目$position",
            mediaSourceId = "source-$position",
            playbackPositionMs = 0L,
            mediaSourceUri = uri,
            thumbnailUri = null,
            indexNumber = position + 1,
        )

    private fun stored(currentIndex: Int, uris: List<String>) =
        StoredQueue(
            items = uris.mapIndexed { index, uri -> storedItem(index, uri) },
            state =
                StoredQueueState(
                    currentIndex = currentIndex,
                    positionMs = 500L,
                    repeatMode = RepeatMode.OFF.name,
                    shuffleEnabled = false,
                    source = QueueSource.MANUAL.name,
                    sourceId = null,
                    savedAt = 1L,
                ),
        )

    @Test
    fun `stale current item relocates to next playable item`() {
        val snapshot =
            stored(
                    currentIndex = 1,
                    uris = listOf("file:///m/0.mp3", staleUri, "file:///m/2.mp3"),
                )
                .toSnapshot()!!

        assertEquals(2, snapshot.queue.items.size)
        assertEquals(1, snapshot.queue.currentIndex)
        assertEquals("曲目2", snapshot.queue.currentItem!!.name)
    }

    @Test
    fun `stale tail current item falls back to previous playable item`() {
        val snapshot =
            stored(
                    currentIndex = 2,
                    uris = listOf("file:///m/0.mp3", "file:///m/1.mp3", staleUri),
                )
                .toSnapshot()!!

        assertEquals(2, snapshot.queue.items.size)
        assertEquals(1, snapshot.queue.currentIndex)
        assertEquals("曲目1", snapshot.queue.currentItem!!.name)
    }

    @Test
    fun `only structurally broken rows are dropped and unavailable markers survive`() {
        val snapshot =
            stored(
                    currentIndex = 0,
                    uris = listOf("file:///m/0.mp3", staleUri, "file:///m/2.mp3"),
                )
                .toSnapshot()!!

        assertEquals(
            listOf("曲目0", "曲目2"),
            snapshot.queue.items.map { item -> item.name },
        )
    }

    @Test
    fun `snapshot is discarded only when every row is unusable`() {
        assertNull(stored(currentIndex = 1, uris = listOf(staleUri, staleUri)).toSnapshot())
    }

    @Test
    fun `pure relocation picks exact next previous or last resort`() {
        assertEquals(
            1,
            restoredCurrentIndex(savedCurrentIndex = 2, keptOriginalIndices = listOf(0, 2)),
        )
        assertEquals(
            1,
            restoredCurrentIndex(savedCurrentIndex = 1, keptOriginalIndices = listOf(0, 2)),
        )
        assertEquals(
            0,
            restoredCurrentIndex(savedCurrentIndex = 0, keptOriginalIndices = listOf(1, 2)),
        )
        assertEquals(
            1,
            restoredCurrentIndex(savedCurrentIndex = 9, keptOriginalIndices = listOf(0, 1)),
        )
        assertNull(restoredCurrentIndex(savedCurrentIndex = 0, keptOriginalIndices = emptyList()))
    }
}
