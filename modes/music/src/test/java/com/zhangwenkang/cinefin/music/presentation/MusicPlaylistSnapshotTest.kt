package com.zhangwenkang.cinefin.music.presentation

import com.zhangwenkang.cinefin.music.data.MusicPlaylist
import com.zhangwenkang.cinefin.music.data.MusicPlaylistKind
import com.zhangwenkang.cinefin.music.data.MusicQueueSnapshot
import com.zhangwenkang.cinefin.player.core.domain.models.MusicQueue
import com.zhangwenkang.cinefin.player.core.domain.models.PlayerItem
import com.zhangwenkang.cinefin.player.core.domain.models.QueueSource
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MusicPlaylistSnapshotTest {

    private val playlistId = UUID.nameUUIDFromBytes("playlist".toByteArray())
    private val firstId = UUID.nameUUIDFromBytes("first".toByteArray())
    private val secondId = UUID.nameUUIDFromBytes("second".toByteArray())

    private fun playlist() =
        MusicPlaylist(
            id = playlistId,
            name = "Drive",
            imageUri = null,
            songCount = 2,
            kind = MusicPlaylistKind.SERVER,
        )

    private fun snapshot(sourceId: String = playlistId.toString()) =
        MusicQueueSnapshot(
            queue =
                MusicQueue(
                    items =
                        listOf(
                            item(firstId, "First", "https://example.test/first"),
                            item(secondId, "Second", "https://example.test/second"),
                        ),
                    currentIndex = 1,
                    shuffleEnabled = true,
                    source = QueueSource.PLAYLIST,
                    sourceId = sourceId,
                    shuffleOrder = listOf(1, 0),
                    shuffleSeed = 7L,
                ),
            positionMs = 1_234L,
            savedAt = 2_345L,
        )

    private fun item(id: UUID, name: String, uri: String) =
        PlayerItem(
            name = name,
            itemId = id,
            mediaSourceId = "source-$name",
            playbackPosition = 99L,
            mediaSourceUri = uri,
        )

    @Test
    fun `matching playlist snapshot projects local songs without network`() {
        val songs = playlistSnapshotSongs(playlist(), snapshot())

        assertEquals(listOf(firstId, secondId), songs.map { it.itemId })
        assertEquals(listOf("First", "Second"), songs.map { it.name })
        assertEquals(listOf(99L, 99L), songs.map { it.resumePositionMs })
    }

    @Test
    fun `snapshot for another playlist is not reused`() {
        assertEquals(
            emptyList<com.zhangwenkang.cinefin.music.data.MusicSong>(),
            playlistSnapshotSongs(playlist(), snapshot(sourceId = "other")),
        )
    }

    @Test
    fun `playable snapshot target can be reused by id`() {
        assertEquals(firstId, snapshotPlaybackItem(snapshot(), firstId)?.itemId)
        assertNull(snapshotPlaybackItem(snapshot(), UUID.randomUUID()))
    }

    @Test
    fun `matching snapshot exposes all playable queue items`() {
        val items = playlistSnapshotPlaybackItems(playlist(), snapshot())

        assertEquals(setOf(firstId, secondId), items.keys)
        assertEquals("https://example.test/second", items[secondId]?.mediaSourceUri)
    }

    @Test
    fun `only the current playback generation may continue filling`() {
        assertEquals(true, isCurrentPlaybackRequest(requestId = 4L, latestRequestId = 4L))
        assertEquals(false, isCurrentPlaybackRequest(requestId = 3L, latestRequestId = 4L))
        assertEquals(false, isCurrentPlaybackRequest(requestId = 0L, latestRequestId = 0L))
    }
}
