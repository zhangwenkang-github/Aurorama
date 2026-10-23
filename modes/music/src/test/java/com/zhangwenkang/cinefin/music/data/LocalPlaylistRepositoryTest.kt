package com.zhangwenkang.cinefin.music.data

import com.zhangwenkang.cinefin.database.music.LocalPlaylistStorage
import com.zhangwenkang.cinefin.database.music.StoredLocalPlaylist
import com.zhangwenkang.cinefin.database.music.StoredLocalPlaylistEntry
import com.zhangwenkang.cinefin.repository.JellyfinRepository
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalPlaylistRepositoryTest {

    private val playlistId = UUID.fromString("00000000-0000-0000-0000-000000000301")
    private val serverId = "server-a"
    private val userId = UUID.fromString("00000000-0000-0000-0000-000000000302")
    private val itemId = UUID.fromString("00000000-0000-0000-0000-000000000303")

    @Test
    fun repeatedCreateAndAdd_areIdempotentAndKeepInsertionOrder() = runBlocking {
        val storage = FakeLocalPlaylistStorage()
        val repository = LocalPlaylistRepositoryImpl(storage)
        val first = serverTrack(itemId)
        val second = localTrack(UUID.randomUUID(), libraryId = 9)

        assertTrue(repository.create(playlistId, "Road", now = 1))
        assertTrue(repository.create(playlistId, "Road", now = 2))
        assertTrue(repository.add(playlistId, first))
        assertTrue(repository.add(playlistId, first))
        assertTrue(repository.add(playlistId, second))

        val entries = repository.entries(playlistId.toString()).firstValue()
        assertEquals(listOf(first.ref, second.ref), entries.map { it.track.ref })
    }

    @Test
    fun sameUuidAcrossNamespaces_isStoredSeparately() = runBlocking {
        val storage = FakeLocalPlaylistStorage()
        val repository = LocalPlaylistRepositoryImpl(storage)
        val tracks =
            listOf(
                serverTrack(itemId, serverId = "server-a", userId = userId),
                serverTrack(itemId, serverId = "server-b", userId = userId),
                serverTrack(itemId, serverId = "server-a", userId = UUID.randomUUID()),
                localTrack(itemId, libraryId = 7),
                localTrack(itemId, libraryId = 8),
            )
        repository.create(playlistId, "Isolation", now = 1)

        repository.addAll(playlistId, tracks)

        val refs = repository.entries(playlistId.toString()).firstValue().map { it.track.ref }
        assertEquals(5, refs.size)
        assertEquals(5, refs.toSet().size)
    }

    @Test
    fun illegalMove_returnsFalseAndPreservesOrder() = runBlocking {
        val storage = FakeLocalPlaylistStorage()
        val repository = LocalPlaylistRepositoryImpl(storage)
        val tracks =
            (1..3).map { index -> serverTrack(UUID.randomUUID(), serverId = "server-$index") }
        repository.create(playlistId, "Order", now = 1)
        repository.addAll(playlistId, tracks)
        val before = repository.entries(playlistId.toString()).firstValue()

        assertFalse(repository.move(playlistId, before.first().entryId, targetPosition = -1))
        assertFalse(repository.move(playlistId, before.first().entryId, targetPosition = 3))
        assertFalse(repository.move(playlistId, "missing", targetPosition = 1))

        assertEquals(before, repository.entries(playlistId.toString()).firstValue())
    }

    @Test
    fun validMove_isAtomicAndRewritesCompleteOrder() = runBlocking {
        val storage = FakeLocalPlaylistStorage()
        val repository = LocalPlaylistRepositoryImpl(storage)
        val tracks =
            (1..4).map { index -> serverTrack(UUID.randomUUID(), serverId = "server-$index") }
        repository.create(playlistId, "Order", now = 1)
        repository.addAll(playlistId, tracks)
        val before = repository.entries(playlistId.toString()).firstValue()

        assertTrue(repository.move(playlistId, before.last().entryId, targetPosition = 1))

        val after = repository.entries(playlistId.toString()).firstValue().map { it.track.ref }
        val expected = listOf(tracks[0].ref, tracks[3].ref, tracks[1].ref, tracks[2].ref)
        assertEquals(expected, after)
        assertTrue(storage.moveWasTransactional)
    }

    @Test
    fun deleteRemovesPlaylistAndEntriesLocallyWithoutServerWrites() = runBlocking {
        val storage = FakeLocalPlaylistStorage()
        val repository = LocalPlaylistRepositoryImpl(storage)
        val serverWrites = ServerWriteProbe()
        repository.create(playlistId, "Delete", now = 1)
        repository.add(playlistId, serverTrack(itemId))

        assertTrue(repository.delete(playlistId))
        assertTrue(repository.delete(playlistId))

        assertTrue(repository.playlists.firstValue().isEmpty())
        assertTrue(repository.entries(playlistId.toString()).firstValue().isEmpty())
        assertEquals(0, serverWrites.count)
    }

    @Test
    fun unavailableProjection_doesNotRemovePersistedEntry() = runBlocking {
        val storage = FakeLocalPlaylistStorage()
        val repository = LocalPlaylistRepositoryImpl(storage)
        repository.create(playlistId, "Missing", now = 1)
        repository.add(playlistId, serverTrack(itemId))
        val entry = repository.entries(playlistId.toString()).firstValue().single()

        val projected =
            projectLocalPlaylistTrack(
                entryId = entry.entryId,
                track = entry.track,
                isAvailable = false,
            )

        assertTrue(projected.isPlaylistPlaceholder)
        assertEquals(entry, repository.entries(playlistId.toString()).firstValue().single())
    }

    @Test
    fun blankNames_areRejectedWithoutChangingTables() = runBlocking {
        val storage = FakeLocalPlaylistStorage()
        val repository = LocalPlaylistRepositoryImpl(storage)

        assertFalse(repository.create(playlistId, "  ", now = 1))
        assertTrue(repository.playlists.firstValue().isEmpty())
        assertTrue(repository.create(playlistId, "Valid", now = 1))
        assertFalse(repository.rename(playlistId, "", now = 2))
        assertEquals("Valid", repository.playlists.firstValue().single().name)
    }

    @Test
    fun repositoryDependencySurface_hasNoServerWriteImplementation() {
        val parameterTypes =
            LocalPlaylistRepositoryImpl::class.java.declaredConstructors.single().parameterTypes

        assertFalse(parameterTypes.any { JellyfinRepository::class.java.isAssignableFrom(it) })
        assertFalse(parameterTypes.any { MusicRepository::class.java.isAssignableFrom(it) })
    }

    private suspend fun <T> Flow<T>.firstValue(): T = first()

    private fun serverTrack(
        itemId: UUID,
        serverId: String = this.serverId,
        userId: UUID = this.userId,
    ) =
        LocalPlaylistTrack(
            ref = TrackRef.Server(serverId = serverId, userId = userId, itemId = itemId),
            snapshot =
                PlaylistTrackSnapshot(
                    displayName = "Server $serverId",
                    albumName = "Album",
                    artist = "Artist",
                    imageUri = null,
                    mediaSourceUri = null,
                    runtimeTicks = 1,
                ),
        )

    private fun localTrack(itemId: UUID, libraryId: Long) =
        LocalPlaylistTrack(
            ref = TrackRef.Local(libraryId = libraryId, itemId = itemId),
            snapshot =
                PlaylistTrackSnapshot(
                    displayName = "Local $libraryId",
                    albumName = "Album",
                    artist = null,
                    imageUri = null,
                    mediaSourceUri = "content://$libraryId/$itemId",
                    runtimeTicks = 1,
                ),
        )

    private class ServerWriteProbe {
        var count: Int = 0
    }

    private class FakeLocalPlaylistStorage : LocalPlaylistStorage {
        private val playlistRows = MutableStateFlow<List<StoredLocalPlaylist>>(emptyList())
        private val entryRows = MutableStateFlow<List<StoredLocalPlaylistEntry>>(emptyList())
        var moveWasTransactional: Boolean = false
            private set

        override fun observePlaylists(): Flow<List<StoredLocalPlaylist>> = playlistRows

        override fun observeEntries(playlistId: String): Flow<List<StoredLocalPlaylistEntry>> =
            entryRows.map { entries ->
                entries.filter { it.playlistId == playlistId }.sortedBy { it.position }
            }

        override suspend fun getEntries(playlistId: String): List<StoredLocalPlaylistEntry> =
            entryRows.value.filter { it.playlistId == playlistId }.sortedBy { it.position }

        override suspend fun createPlaylist(
            id: String,
            name: String,
            createdAt: Long,
            updatedAt: Long,
        ): Boolean {
            if (playlistRows.value.any { it.id == id }) return true
            playlistRows.update {
                it +
                    StoredLocalPlaylist(
                        id = id,
                        name = name,
                        createdAt = createdAt,
                        updatedAt = updatedAt,
                        entryCount = 0,
                    )
            }
            return true
        }

        override suspend fun renamePlaylist(
            id: String,
            name: String,
            updatedAt: Long,
        ): Boolean {
            if (playlistRows.value.none { it.id == id }) return false
            playlistRows.update { rows ->
                rows.map { row ->
                    if (row.id == id) row.copy(name = name, updatedAt = updatedAt) else row
                }
            }
            return true
        }

        override suspend fun addEntries(entries: List<StoredLocalPlaylistEntry>): Boolean {
            val playlistId = entries.firstOrNull()?.playlistId ?: return true
            if (playlistRows.value.none { it.id == playlistId }) return false
            entryRows.update { current ->
                val result = current.toMutableList()
                entries.forEach { entry ->
                    if (result.none { it.identityKey == entry.identityKey }) {
                        val position = result.count { it.playlistId == entry.playlistId }
                        result += entry.copy(position = position)
                    }
                }
                result
            }
            return true
        }

        override suspend fun removeEntries(
            playlistId: String,
            entryIds: Set<String>,
        ): Boolean {
            entryRows.update { current ->
                current.filterNot { it.playlistId == playlistId && it.id in entryIds }
            }
            renumber(playlistId)
            return true
        }

        override suspend fun moveEntry(
            playlistId: String,
            entryId: String,
            targetPosition: Int,
        ): Boolean {
            val current = getEntries(playlistId)
            val from = current.indexOfFirst { it.id == entryId }
            if (from < 0 || targetPosition !in current.indices) return false
            val reordered = current.toMutableList()
            val moved = reordered.removeAt(from)
            reordered.add(targetPosition, moved)
            entryRows.update { rows ->
                val others = rows.filterNot { it.playlistId == playlistId }
                others + reordered.mapIndexed { index, entry -> entry.copy(position = index) }
            }
            moveWasTransactional = true
            return true
        }

        override suspend fun deletePlaylist(id: String): Boolean {
            playlistRows.update { rows -> rows.filterNot { it.id == id } }
            entryRows.update { rows -> rows.filterNot { it.playlistId == id } }
            return true
        }

        private suspend fun renumber(playlistId: String) {
            val ordered = getEntries(playlistId)
            entryRows.update { rows ->
                val others = rows.filterNot { it.playlistId == playlistId }
                others + ordered.mapIndexed { index, entry -> entry.copy(position = index) }
            }
        }
    }
}
