package com.zhangwenkang.cinefin.database.music

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import kotlinx.coroutines.flow.Flow

/** 音乐队列持久化（W21-R2）：整表替换 + 状态单行。 */
@Dao
interface MusicQueueDao {
    @Query("SELECT * FROM music_queue_items ORDER BY position")
    suspend fun getItems(): List<MusicQueueItemEntity>

    @Query("SELECT * FROM music_queue_state WHERE id = 0")
    suspend fun getState(): MusicQueueStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertItems(items: List<MusicQueueItemEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertState(state: MusicQueueStateEntity)

    @Query("DELETE FROM music_queue_items") suspend fun clearItems()

    @Query("DELETE FROM music_queue_state") suspend fun clearState()

    /** 保存队列：清掉旧快照后写入新快照（同一事务，避免读到半份队列）。 */
    @Transaction
    suspend fun replace(items: List<MusicQueueItemEntity>, state: MusicQueueStateEntity) {
        clearItems()
        clearState()
        insertItems(items)
        insertState(state)
    }

    @Transaction
    suspend fun clear() {
        clearItems()
        clearState()
    }
}

/** 本地最近播放（W21-R2）。 */
@Dao
interface MusicRecentDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(entry: MusicRecentEntity)

    @Query("SELECT * FROM music_recent ORDER BY playedAt DESC LIMIT :limit")
    suspend fun getRecent(limit: Int): List<MusicRecentEntity>

    /** 只保留最近 [keep] 条，避免本地列表无限增长。 */
    @Query(
        "DELETE FROM music_recent WHERE itemId NOT IN (SELECT itemId FROM music_recent ORDER BY playedAt DESC LIMIT :keep)"
    )
    suspend fun trim(keep: Int)
}

/** 本地歌单读写；所有多步改动在 DAO 事务内完成。 */
@Dao
interface LocalPlaylistDao {
    @Query(
        "SELECT p.id, p.name, p.createdAt, p.updatedAt, COUNT(e.id) AS entryCount " +
            "FROM local_playlists p LEFT JOIN local_playlist_entries e " +
            "ON e.playlistId = p.id GROUP BY p.id, p.name, p.createdAt, p.updatedAt " +
            "ORDER BY p.updatedAt DESC, p.id ASC"
    )
    fun observePlaylists(): Flow<List<LocalPlaylistSummaryRow>>

    @Query("SELECT * FROM local_playlist_entries WHERE playlistId = :playlistId ORDER BY position")
    fun observeEntries(playlistId: String): Flow<List<LocalPlaylistEntryEntity>>

    @Query("SELECT * FROM local_playlist_entries WHERE playlistId = :playlistId ORDER BY position")
    suspend fun getEntries(playlistId: String): List<LocalPlaylistEntryEntity>

    @Query("SELECT * FROM local_playlists WHERE id = :playlistId")
    suspend fun getPlaylist(playlistId: String): LocalPlaylistEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPlaylist(playlist: LocalPlaylistEntity): Long

    @Query("UPDATE local_playlists SET name = :name, updatedAt = :updatedAt WHERE id = :playlistId")
    suspend fun updateName(playlistId: String, name: String, updatedAt: Long): Int

    @Query("DELETE FROM local_playlists WHERE id = :playlistId")
    suspend fun deletePlaylist(playlistId: String): Int

    @Query(
        "SELECT * FROM local_playlist_entries WHERE playlistId = :playlistId " +
            "AND trackKind = :trackKind AND serverId = :serverId AND userId = :userId " +
            "AND libraryId = :libraryId AND itemId = :itemId LIMIT 1"
    )
    suspend fun findEntryByIdentity(
        playlistId: String,
        trackKind: String,
        serverId: String,
        userId: String,
        libraryId: Long,
        itemId: String,
    ): LocalPlaylistEntryEntity?

    @Query(
        "SELECT COALESCE(MAX(position) + 1, 0) FROM local_playlist_entries " +
            "WHERE playlistId = :playlistId"
    )
    suspend fun nextPosition(playlistId: String): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertEntry(entry: LocalPlaylistEntryEntity): Long

    @Query(
        "DELETE FROM local_playlist_entries WHERE playlistId = :playlistId AND id IN (:entryIds)"
    )
    suspend fun deleteEntries(playlistId: String, entryIds: List<String>): Int

    @Query(
        "UPDATE local_playlist_entries SET position = position + $POSITION_SHIFT " +
            "WHERE playlistId = :playlistId"
    )
    suspend fun shiftPositions(playlistId: String)

    @Query(
        "UPDATE local_playlist_entries SET position = :position " +
            "WHERE playlistId = :playlistId AND id = :entryId"
    )
    suspend fun updatePosition(playlistId: String, entryId: String, position: Int)

    @Transaction
    suspend fun addEntries(entries: List<LocalPlaylistEntryEntity>): Boolean {
        val playlistId = entries.firstOrNull()?.playlistId ?: return true
        if (getPlaylist(playlistId) == null) return false
        var position = nextPosition(playlistId)
        entries.forEach { entry ->
            val existing =
                findEntryByIdentity(
                    playlistId = playlistId,
                    trackKind = entry.trackKind,
                    serverId = entry.serverId,
                    userId = entry.userId,
                    libraryId = entry.libraryId,
                    itemId = entry.itemId,
                )
            if (existing == null) {
                val inserted = insertEntry(entry.copy(position = position))
                if (inserted >= 0L) position++
            }
        }
        return true
    }

    @Transaction
    suspend fun removeEntries(playlistId: String, entryIds: Set<String>): Boolean {
        if (entryIds.isNotEmpty()) deleteEntries(playlistId, entryIds.toList())
        getEntries(playlistId).forEachIndexed { index, entry ->
            if (entry.position != index) updatePosition(playlistId, entry.id, index)
        }
        return true
    }

    @Transaction
    suspend fun moveEntry(playlistId: String, entryId: String, targetPosition: Int): Boolean {
        val current = getEntries(playlistId)
        val from = current.indexOfFirst { entry -> entry.id == entryId }
        if (from < 0 || targetPosition !in current.indices || from == targetPosition) {
            return from >= 0 && targetPosition == from
        }
        val target = current.toMutableList()
        val moved = target.removeAt(from)
        target.add(targetPosition, moved)
        shiftPositions(playlistId)
        target.forEachIndexed { index, entry -> updatePosition(playlistId, entry.id, index) }
        return true
    }

    companion object {
        /** 两阶段排序的临时偏移。唯一索引 `(playlistId, position)` 会拒绝直接交换；先整体移到无冲突区间，再写入目标序。 */
        private const val POSITION_SHIFT = 1_000_000
    }
}

/** 本地歌单列表投影（不含条目正文）。 */
data class LocalPlaylistSummaryRow(
    val id: String,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
    val entryCount: Int,
)
