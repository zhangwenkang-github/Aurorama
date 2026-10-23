package com.zhangwenkang.cinefin.music.data

import com.zhangwenkang.cinefin.database.music.LocalPlaylistStorage
import com.zhangwenkang.cinefin.database.music.StoredLocalPlaylist
import com.zhangwenkang.cinefin.database.music.StoredLocalPlaylistEntry
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistExchangeImportTest {
    private val serverId = "server-a"
    private val userId = UUID.fromString("00000000-0000-0000-0000-000000000501")
    private val firstItemId = UUID.fromString("00000000-0000-0000-0000-000000000502")
    private val localItemId = UUID.fromString("00000000-0000-0000-0000-000000000503")

    @Test
    fun conflictingImports_createDeterministicCopiesWithoutOverwritingOrReordering() = runBlocking {
        val storage = AtomicPlaylistStorage()
        storage.seed("existing", "Road")
        val repository = LocalPlaylistRepositoryImpl(storage)
        val firstId = UUID.fromString("00000000-0000-0000-0000-000000000504")
        val secondId = UUID.fromString("00000000-0000-0000-0000-000000000505")
        val document =
            PlaylistExchangeDocument(
                name = "Road",
                entries =
                    listOf(
                        PlaylistExchangeEntry(serverTrack(firstItemId), true),
                        PlaylistExchangeEntry(localTrack(localItemId), false),
                    ),
            )

        val first = repository.importPlaylist(document, firstId, now = 10)
        val second = repository.importPlaylist(document, secondId, now = 11)

        assertEquals("Road (导入 2)", first.name)
        assertEquals("Road (导入 3)", second.name)
        assertEquals(
            listOf(
                TrackRef.Server(serverId, userId, firstItemId),
                TrackRef.Local(7L, localItemId),
            ),
            repository.entries(firstId.toString()).first().map { it.track.ref },
        )
        assertEquals("Road", storage.playlistRows.value.single { it.id == "existing" }.name)
        assertEquals(3, storage.playlistRows.value.size)
    }

    @Test
    fun failedAtomicImport_leavesNoPlaylistOrEntryAndCanBeRetried() = runBlocking {
        val storage = AtomicPlaylistStorage()
        storage.failNextImport = true
        val repository = LocalPlaylistRepositoryImpl(storage)
        val id = UUID.fromString("00000000-0000-0000-0000-000000000506")
        val document =
            PlaylistExchangeDocument(
                name = "Atomic",
                entries = listOf(PlaylistExchangeEntry(serverTrack(firstItemId), false)),
            )

        assertThrows(IOException::class.java) {
            runBlocking { repository.importPlaylist(document, id, now = 20) }
        }
        assertTrue(storage.playlistRows.value.isEmpty())
        assertTrue(storage.entryRows.value.isEmpty())

        val summary = repository.importPlaylist(document, id, now = 21)

        assertEquals(id, summary.playlistId)
        assertEquals("Atomic", summary.name)
        assertEquals(1, summary.entryCount)
        assertEquals(1, storage.playlistRows.value.size)
        assertEquals(1, storage.entryRows.value.size)
    }

    @Test
    fun exchangePath_hasNoServerWriterDependencyAndCounterRemainsZero() = runBlocking {
        val storage = AtomicPlaylistStorage()
        val repository = LocalPlaylistRepositoryImpl(storage)
        val serverWriter = FakeServerPlaylistWriter()
        val document =
            PlaylistExchangeDocument(
                name = "Local only",
                entries = listOf(PlaylistExchangeEntry(serverTrack(firstItemId), false)),
            )

        repository.importPlaylist(document, UUID.randomUUID(), now = 30)

        assertEquals(0, serverWriter.writeCalls)
        assertFalse(
            LocalPlaylistRepositoryImpl::class
                .java
                .declaredConstructors
                .single()
                .parameterTypes
                .any { FakeServerPlaylistWriter::class.java.isAssignableFrom(it) }
        )
    }

    private fun serverTrack(itemId: UUID) =
        LocalPlaylistTrack(
            ref = TrackRef.Server(serverId, userId, itemId),
            snapshot =
                PlaylistTrackSnapshot(
                    displayName = "Server",
                    albumName = "Album",
                    artist = "Artist",
                    imageUri = null,
                    mediaSourceUri = null,
                    runtimeTicks = 10,
                ),
        )

    private fun localTrack(itemId: UUID) =
        LocalPlaylistTrack(
            ref = TrackRef.Local(7L, itemId),
            snapshot =
                PlaylistTrackSnapshot(
                    displayName = "Local",
                    albumName = "Album",
                    artist = null,
                    imageUri = null,
                    mediaSourceUri = "content://local/track",
                    runtimeTicks = 20,
                ),
        )

    private class FakeServerPlaylistWriter {
        var writeCalls: Int = 0
            private set

        suspend fun write(): Unit {
            writeCalls++
        }
    }

    private class AtomicPlaylistStorage : LocalPlaylistStorage {
        val playlistRows = MutableStateFlow<List<StoredLocalPlaylist>>(emptyList())
        val entryRows = MutableStateFlow<List<StoredLocalPlaylistEntry>>(emptyList())
        var failNextImport: Boolean = false

        fun seed(id: String, name: String) {
            playlistRows.update {
                it +
                    StoredLocalPlaylist(
                        id = id,
                        name = name,
                        createdAt = 0,
                        updatedAt = 0,
                        entryCount = 0,
                    )
            }
        }

        override fun observePlaylists(): Flow<List<StoredLocalPlaylist>> = playlistRows

        override fun observeEntries(playlistId: String): Flow<List<StoredLocalPlaylistEntry>> =
            entryRows.map { rows ->
                rows.filter { it.playlistId == playlistId }.sortedBy { it.position }
            }

        override suspend fun getEntries(playlistId: String) =
            entryRows.value.filter { it.playlistId == playlistId }.sortedBy { it.position }

        override suspend fun createPlaylist(
            id: String,
            name: String,
            createdAt: Long,
            updatedAt: Long,
        ): Boolean = false

        override suspend fun renamePlaylist(id: String, name: String, updatedAt: Long): Boolean =
            false

        override suspend fun addEntries(entries: List<StoredLocalPlaylistEntry>): Boolean = false

        override suspend fun removeEntries(playlistId: String, entryIds: Set<String>): Boolean =
            false

        override suspend fun moveEntry(
            playlistId: String,
            entryId: String,
            targetPosition: Int,
        ): Boolean = false

        override suspend fun deletePlaylist(id: String): Boolean = false

        override suspend fun getPlaylistNames(): Set<String> =
            playlistRows.value.map { it.name }.toSet()

        override suspend fun importPlaylist(
            playlist: StoredLocalPlaylist,
            entries: List<StoredLocalPlaylistEntry>,
        ): Boolean {
            if (failNextImport) {
                failNextImport = false
                throw IOException("simulated database failure")
            }
            playlistRows.update { it + playlist.copy(entryCount = entries.size) }
            entryRows.update { it + entries }
            return true
        }
    }
}
