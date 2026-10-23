package com.zhangwenkang.cinefin.database.music

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** 本地歌单持久化端口：只描述存储能力，不依赖音乐 UI 或 Jellyfin。 */
interface LocalPlaylistStorage {
    fun observePlaylists(): Flow<List<StoredLocalPlaylist>>

    fun observeEntries(playlistId: String): Flow<List<StoredLocalPlaylistEntry>>

    suspend fun getEntries(playlistId: String): List<StoredLocalPlaylistEntry>

    suspend fun createPlaylist(
        id: String,
        name: String,
        createdAt: Long,
        updatedAt: Long,
    ): Boolean

    suspend fun renamePlaylist(id: String, name: String, updatedAt: Long): Boolean

    suspend fun addEntries(entries: List<StoredLocalPlaylistEntry>): Boolean

    suspend fun removeEntries(playlistId: String, entryIds: Set<String>): Boolean

    suspend fun moveEntry(playlistId: String, entryId: String, targetPosition: Int): Boolean

    suspend fun deletePlaylist(id: String): Boolean
}

data class StoredLocalPlaylist(
    val id: String,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
    val entryCount: Int,
)

data class StoredLocalPlaylistEntry(
    val id: String,
    val playlistId: String,
    val trackKind: String,
    val serverId: String,
    val userId: String,
    val libraryId: Long,
    val itemId: String,
    val displayName: String,
    val albumName: String,
    val artist: String?,
    val imageUri: String?,
    val mediaSourceUri: String?,
    val runtimeTicks: Long,
    val position: Int,
) {
    val identityKey: String
        get() = "$trackKind\u0000$serverId\u0000$userId\u0000$libraryId\u0000$itemId"
}

class LocalPlaylistStorageImpl constructor(private val dao: LocalPlaylistDao) :
    LocalPlaylistStorage {
    override fun observePlaylists(): Flow<List<StoredLocalPlaylist>> =
        dao.observePlaylists().map { rows ->
            rows.map { row ->
                StoredLocalPlaylist(
                    id = row.id,
                    name = row.name,
                    createdAt = row.createdAt,
                    updatedAt = row.updatedAt,
                    entryCount = row.entryCount,
                )
            }
        }

    override fun observeEntries(playlistId: String): Flow<List<StoredLocalPlaylistEntry>> =
        dao.observeEntries(playlistId).map { entries -> entries.map { it.toStored() } }

    override suspend fun getEntries(playlistId: String): List<StoredLocalPlaylistEntry> =
        dao.getEntries(playlistId).map { it.toStored() }

    override suspend fun createPlaylist(
        id: String,
        name: String,
        createdAt: Long,
        updatedAt: Long,
    ): Boolean {
        if (dao.getPlaylist(id) != null) return true
        val inserted =
            dao.insertPlaylist(
            LocalPlaylistEntity(
                id = id,
                name = name,
                createdAt = createdAt,
                updatedAt = updatedAt,
            )
        )
        return inserted >= 0L || dao.getPlaylist(id) != null
    }

    override suspend fun renamePlaylist(id: String, name: String, updatedAt: Long): Boolean =
        dao.updateName(id, name, updatedAt) > 0

    override suspend fun addEntries(entries: List<StoredLocalPlaylistEntry>): Boolean =
        dao.addEntries(entries.map { it.toEntity(position = 0) })

    override suspend fun removeEntries(
        playlistId: String,
        entryIds: Set<String>,
    ): Boolean = dao.removeEntries(playlistId, entryIds)

    override suspend fun moveEntry(
        playlistId: String,
        entryId: String,
        targetPosition: Int,
    ): Boolean = dao.moveEntry(playlistId, entryId, targetPosition)

    override suspend fun deletePlaylist(id: String): Boolean {
        dao.deletePlaylist(id)
        return true
    }

    private fun LocalPlaylistEntryEntity.toStored() =
        StoredLocalPlaylistEntry(
            id = id,
            playlistId = playlistId,
            trackKind = trackKind,
            serverId = serverId,
            userId = userId,
            libraryId = libraryId,
            itemId = itemId,
            displayName = displayName,
            albumName = albumName,
            artist = artist,
            imageUri = imageUri,
            mediaSourceUri = mediaSourceUri,
            runtimeTicks = runtimeTicks,
            position = position,
        )

    private fun StoredLocalPlaylistEntry.toEntity(position: Int) =
        LocalPlaylistEntryEntity(
            id = id,
            playlistId = playlistId,
            trackKind = trackKind,
            serverId = serverId,
            userId = userId,
            libraryId = libraryId,
            itemId = itemId,
            displayName = displayName,
            albumName = albumName,
            artist = artist,
            imageUri = imageUri,
            mediaSourceUri = mediaSourceUri,
            runtimeTicks = runtimeTicks,
            position = position,
        )
}
