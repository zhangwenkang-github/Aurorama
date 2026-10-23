package com.zhangwenkang.cinefin.music.presentation

import com.zhangwenkang.cinefin.music.data.MusicItemSource
import com.zhangwenkang.cinefin.music.data.MusicSong
import com.zhangwenkang.cinefin.music.data.TrackRef
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class LocalPlaylistSelectionKeyTest {

    @Test
    fun sameItemIdAcrossNamespaces_hasDistinctSelectionKeys() {
        val itemId = UUID.randomUUID()
        val server =
            song(
                itemId = itemId,
                entryId = "server-entry",
                source = MusicItemSource.SERVER,
                ref = TrackRef.Server("server-a", UUID.randomUUID(), itemId),
            )
        val local =
            song(
                itemId = itemId,
                entryId = "local-entry",
                source = MusicItemSource.LOCAL,
                ref = TrackRef.Local(7, itemId),
            )

        assertNotEquals(server.playlistSelectionKey(), local.playlistSelectionKey())
        assertEquals("server-entry", server.playlistSelectionKey())
        assertEquals("local-entry", local.playlistSelectionKey())
    }

    private fun song(
        itemId: UUID,
        entryId: String,
        source: MusicItemSource,
        ref: TrackRef,
    ) =
        MusicSong(
            itemId = itemId,
            name = "Track",
            albumName = "Album",
            artist = null,
            indexNumber = null,
            runtimeTicks = 1,
            imageUri = null,
            source = source,
            playlistEntryId = entryId,
            trackRef = ref,
        )
}
