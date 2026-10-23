package com.zhangwenkang.cinefin.music.data

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalPlaylistIdentityTest {

    @Test
    fun serverTrack_requiresServerAndUserContext() {
        val song = serverSong(itemId = UUID.randomUUID())

        assertThrows(IllegalArgumentException::class.java) {
            song.toLocalPlaylistTrack(serverId = null, userId = USER_ID)
        }
        assertThrows(IllegalArgumentException::class.java) {
            song.toLocalPlaylistTrack(serverId = SERVER_ID, userId = null)
        }
    }

    @Test
    fun localTrack_requiresLocalLibraryContext() {
        val song = localSong(itemId = UUID.randomUUID(), libraryId = null)

        assertThrows(IllegalArgumentException::class.java) {
            song.toLocalPlaylistTrack(serverId = SERVER_ID, userId = USER_ID)
        }
    }

    @Test
    fun sameItemId_isIsolatedByServerUserAndLocalLibrary() {
        val itemId = UUID.fromString("00000000-0000-0000-0000-000000000101")

        val refs =
            setOf(
                TrackRef.Server(serverId = SERVER_ID, userId = USER_ID, itemId = itemId),
                TrackRef.Server(serverId = "server-b", userId = USER_ID, itemId = itemId),
                TrackRef.Server(serverId = SERVER_ID, userId = USER_B_ID, itemId = itemId),
                TrackRef.Local(libraryId = 7, itemId = itemId),
                TrackRef.Local(libraryId = 8, itemId = itemId),
            )

        assertEquals(5, refs.size)
    }

    @Test
    fun snapshotDoesNotAffectTrackIdentity() {
        val itemId = UUID.randomUUID()
        val ref = TrackRef.Local(libraryId = 7, itemId = itemId)
        val first =
            LocalPlaylistTrack(
                ref = ref,
                snapshot =
                    PlaylistTrackSnapshot(
                        displayName = "Title",
                        albumName = "Album",
                        artist = "Artist",
                        imageUri = "cover-old",
                        mediaSourceUri = "content://old",
                        runtimeTicks = 10,
                    ),
            )
        val second =
            LocalPlaylistTrack(
                ref = ref,
                snapshot =
                    PlaylistTrackSnapshot(
                        displayName = "Renamed",
                        albumName = "Other",
                        artist = null,
                        imageUri = null,
                        mediaSourceUri = "content://new",
                        runtimeTicks = 20,
                    ),
            )

        assertEquals(first.ref, second.ref)
    }

    @Test
    fun unavailableTrack_projectsPlaceholderFromStoredSnapshot() {
        val track =
            LocalPlaylistTrack(
                ref =
                    TrackRef.Server(
                        serverId = SERVER_ID,
                        userId = USER_ID,
                        itemId = UUID.randomUUID(),
                    ),
                snapshot =
                    PlaylistTrackSnapshot(
                        displayName = "保留标题",
                        albumName = "保留专辑",
                        artist = "保留艺人",
                        imageUri = "cover",
                        mediaSourceUri = null,
                        runtimeTicks = 42,
                    ),
            )

        val projected =
            projectLocalPlaylistTrack(
                entryId = "entry-1",
                track = track,
                isAvailable = false,
            )

        assertEquals("保留标题", projected.name)
        assertEquals("保留专辑", projected.albumName)
        assertEquals("保留艺人", projected.artist)
        assertEquals("entry-1", projected.playlistEntryId)
        assertTrue(projected.isPlaylistPlaceholder)
        assertEquals(track.ref, projected.trackRef)
    }

    @Test
    fun availableTrack_projectsPlayableSongWithoutDeletingIdentity() {
        val itemId = UUID.fromString("00000000-0000-0000-0000-000000000102")
        val track =
            LocalPlaylistTrack(
                ref = TrackRef.Local(libraryId = 7, itemId = itemId),
                snapshot =
                    PlaylistTrackSnapshot(
                        displayName = "本地曲目",
                        albumName = "本地文件",
                        artist = null,
                        imageUri = null,
                        mediaSourceUri = "content://music/102",
                        runtimeTicks = 100,
                    ),
            )

        val projected =
            projectLocalPlaylistTrack(
                entryId = "entry-2",
                track = track,
                isAvailable = true,
            )

        assertEquals(itemId, projected.itemId)
        assertEquals("content://music/102", projected.localUri)
        assertEquals(MusicItemSource.LOCAL, projected.source)
        assertFalse(projected.isPlaylistPlaceholder)
    }

    private fun serverSong(itemId: UUID) =
        MusicSong(
            itemId = itemId,
            name = "Server",
            albumName = "Album",
            artist = "Artist",
            indexNumber = 1,
            runtimeTicks = 100,
            imageUri = "cover",
            source = MusicItemSource.SERVER,
        )

    private fun localSong(itemId: UUID, libraryId: Long?) =
        MusicSong(
            itemId = itemId,
            name = "Local",
            albumName = "Album",
            artist = "Artist",
            indexNumber = 1,
            runtimeTicks = 100,
            imageUri = "cover",
            source = MusicItemSource.LOCAL,
            localUri = "content://ignored-as-identity",
            localLibraryId = libraryId,
        )

    private companion object {
        const val SERVER_ID = "server-a"
        val USER_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000201")
        val USER_B_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000202")
    }
}
