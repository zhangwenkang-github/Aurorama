package com.zhangwenkang.cinefin.music.data

import com.zhangwenkang.cinefin.database.music.LocalPlaylistStorage
import com.zhangwenkang.cinefin.database.music.StoredLocalPlaylist
import com.zhangwenkang.cinefin.database.music.StoredLocalPlaylistEntry
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 稳定曲目身份。
 *
 * 服务器曲目必须包含 serverId + userId + itemId；本地曲目必须包含 libraryId + itemId。标题、路径、URI 只存在于
 * [PlaylistTrackSnapshot]，不能构造或替换身份。
 */
sealed interface TrackRef {
    data class Server(
        val serverId: String,
        val userId: UUID,
        val itemId: UUID,
    ) : TrackRef {
        init {
            require(serverId.isNotBlank()) { "服务器曲目缺少 serverId，不能猜测身份" }
        }
    }

    data class Local(val libraryId: Long, val itemId: UUID) : TrackRef {
        init {
            require(libraryId > 0L) { "本地曲目缺少 libraryId，不能用 URI 代替身份" }
        }
    }
}

data class PlaylistTrackSnapshot(
    val displayName: String,
    val albumName: String,
    val artist: String?,
    val imageUri: String?,
    val mediaSourceUri: String?,
    val runtimeTicks: Long,
)

data class LocalPlaylistTrack(
    val ref: TrackRef,
    val snapshot: PlaylistTrackSnapshot,
)

data class LocalPlaylist(
    val id: UUID,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
    val entryCount: Int,
)

data class LocalPlaylistEntry(
    val entryId: String,
    val playlistId: UUID,
    val track: LocalPlaylistTrack,
)

/** app-scope 本地歌单真源；实现不持有 UI 状态，也不依赖 Jellyfin 写 API。 */
@Singleton
class LocalPlaylistRepositoryImpl @Inject constructor(private val storage: LocalPlaylistStorage) {
    val playlists: Flow<List<LocalPlaylist>> =
        storage.observePlaylists().map { rows -> rows.map { it.toDomain() } }

    fun entries(playlistId: String): Flow<List<LocalPlaylistEntry>> =
        storage.observeEntries(playlistId).map { rows -> rows.mapNotNull { it.toDomain() } }

    suspend fun create(id: UUID, name: String, now: Long = System.currentTimeMillis()): Boolean {
        val normalized = name.trim()
        if (normalized.isEmpty()) return false
        return storage.createPlaylist(
            id = id.toString(),
            name = normalized,
            createdAt = now,
            updatedAt = now,
        )
    }

    suspend fun rename(
        playlistId: UUID,
        name: String,
        now: Long = System.currentTimeMillis(),
    ): Boolean {
        val normalized = name.trim()
        if (normalized.isEmpty()) return false
        return storage.renamePlaylist(playlistId.toString(), normalized, now)
    }

    suspend fun add(playlistId: UUID, track: LocalPlaylistTrack): Boolean =
        addAll(playlistId, listOf(track))

    suspend fun addAll(playlistId: UUID, tracks: List<LocalPlaylistTrack>): Boolean {
        if (tracks.isEmpty()) return true
        val rows = tracks.map { track -> track.toStored(playlistId) }
        return storage.addEntries(rows)
    }

    suspend fun remove(playlistId: UUID, entryIds: Set<String>): Boolean =
        storage.removeEntries(playlistId.toString(), entryIds)

    suspend fun move(
        playlistId: UUID,
        entryId: String,
        targetPosition: Int,
    ): Boolean {
        if (targetPosition < 0) return false
        return storage.moveEntry(playlistId.toString(), entryId, targetPosition)
    }

    suspend fun delete(playlistId: UUID): Boolean = storage.deletePlaylist(playlistId.toString())

    private fun StoredLocalPlaylist.toDomain() =
        LocalPlaylist(
            id = UUID.fromString(id),
            name = name,
            createdAt = createdAt,
            updatedAt = updatedAt,
            entryCount = entryCount,
        )

    private fun StoredLocalPlaylistEntry.toDomain(): LocalPlaylistEntry? {
        val ref =
            when (trackKind) {
                TRACK_SERVER -> runCatching {
                        TrackRef.Server(
                            serverId = serverId,
                            userId = UUID.fromString(userId),
                            itemId = UUID.fromString(itemId),
                        )
                    }
                        .getOrNull()
                TRACK_LOCAL -> runCatching {
                        TrackRef.Local(
                            libraryId = libraryId,
                            itemId = UUID.fromString(itemId),
                        )
                    }
                        .getOrNull()
                else -> null
            } ?: return null
        return LocalPlaylistEntry(
            entryId = id,
            playlistId = UUID.fromString(playlistId),
            track =
                LocalPlaylistTrack(
                    ref = ref,
                    snapshot =
                        PlaylistTrackSnapshot(
                            displayName = displayName,
                            albumName = albumName,
                            artist = artist,
                            imageUri = imageUri,
                            mediaSourceUri = mediaSourceUri,
                            runtimeTicks = runtimeTicks,
                        ),
                ),
        )
    }

    private fun LocalPlaylistTrack.toStored(playlistId: UUID): StoredLocalPlaylistEntry {
        val (trackKind, serverIdValue, userIdValue, libraryIdValue) =
            when (val identity = ref) {
                is TrackRef.Server ->
                    StoredIdentity(
                        trackKind = TRACK_SERVER,
                        serverId = identity.serverId,
                        userId = identity.userId.toString(),
                        libraryId = NO_LOCAL_LIBRARY,
                    )
                is TrackRef.Local ->
                    StoredIdentity(
                        trackKind = TRACK_LOCAL,
                        serverId = NO_SERVER,
                        userId = NO_USER,
                        libraryId = identity.libraryId,
                    )
            }
        return StoredLocalPlaylistEntry(
            id = UUID.randomUUID().toString(),
            playlistId = playlistId.toString(),
            trackKind = trackKind,
            serverId = serverIdValue,
            userId = userIdValue,
            libraryId = libraryIdValue,
            itemId = ref.itemId.toString(),
            displayName = snapshot.displayName,
            albumName = snapshot.albumName,
            artist = snapshot.artist,
            imageUri = snapshot.imageUri,
            mediaSourceUri = snapshot.mediaSourceUri,
            runtimeTicks = snapshot.runtimeTicks,
            position = 0,
        )
    }

    private data class StoredIdentity(
        val trackKind: String,
        val serverId: String,
        val userId: String,
        val libraryId: Long,
    )

    private companion object {
        const val TRACK_SERVER = "SERVER"
        const val TRACK_LOCAL = "LOCAL"
        const val NO_SERVER = ""
        const val NO_USER = ""
        const val NO_LOCAL_LIBRARY = -1L
    }
}

private val TrackRef.itemId: UUID
    get() =
        when (this) {
            is TrackRef.Server -> itemId
            is TrackRef.Local -> itemId
        }

/** 从当前曲目构造持久身份；当前服务器 / 用户 / 本地库上下文缺失时显式失败。 */
fun MusicSong.toLocalPlaylistTrack(serverId: String?, userId: UUID?): LocalPlaylistTrack {
    val ref =
        if (source == MusicItemSource.LOCAL) {
            val libraryId =
                requireNotNull(localLibraryId) { "本地曲目「$name」缺少 libraryId，不能用 URI 猜测身份" }
            TrackRef.Local(libraryId = libraryId, itemId = itemId)
        } else {
            require(!serverId.isNullOrBlank()) { "服务器曲目「$name」缺少 serverId，不能创建本地歌单身份" }
            requireNotNull(userId) { "服务器曲目「$name」缺少 userId，不能创建本地歌单身份" }
            TrackRef.Server(serverId = serverId, userId = userId, itemId = itemId)
        }
    return LocalPlaylistTrack(
        ref = ref,
        snapshot =
            PlaylistTrackSnapshot(
                displayName = name,
                albumName = albumName,
                artist = artist,
                imageUri = imageUri,
                mediaSourceUri = localUri,
                runtimeTicks = runtimeTicks,
            ),
    )
}

/** 歌单读取投影：不可解析条目保留快照并明确标记占位，绝不从持久层删除。 */
fun projectLocalPlaylistTrack(
    entryId: String,
    track: LocalPlaylistTrack,
    isAvailable: Boolean,
): MusicSong {
    val ref = track.ref
    val isLocal = ref is TrackRef.Local
    return MusicSong(
        itemId = ref.itemId,
        name = track.snapshot.displayName,
        albumName = track.snapshot.albumName,
        artist = track.snapshot.artist,
        indexNumber = null,
        runtimeTicks = track.snapshot.runtimeTicks,
        imageUri = track.snapshot.imageUri,
        source = if (isLocal) MusicItemSource.LOCAL else MusicItemSource.SERVER,
        localUri = track.snapshot.mediaSourceUri.takeIf { isLocal && isAvailable },
        localLibraryId = (ref as? TrackRef.Local)?.libraryId,
        playlistEntryId = entryId,
        trackRef = ref,
        isPlaylistPlaceholder = !isAvailable,
    )
}
