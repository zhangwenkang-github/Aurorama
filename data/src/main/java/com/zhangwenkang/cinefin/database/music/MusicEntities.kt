package com.zhangwenkang.cinefin.database.music

import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey

/** 音乐队列条目（position = 队列内顺序，整表替换式保存）。 */
@Entity(tableName = "music_queue_items")
data class MusicQueueItemEntity(
    @PrimaryKey val position: Int,
    val itemId: String,
    val name: String,
    val mediaSourceId: String,
    val playbackPositionMs: Long,
    val mediaSourceUri: String,
    val thumbnailUri: String?,
    val indexNumber: Int?,
)

/** 队列状态（固定单行，id 恒为 [SINGLETON_ID]）。 */
@Entity(tableName = "music_queue_state")
data class MusicQueueStateEntity(
    @PrimaryKey val id: Int = SINGLETON_ID,
    val currentIndex: Int,
    val positionMs: Long,
    val repeatMode: String,
    val shuffleEnabled: Boolean,
    val source: String,
    val sourceId: String?,
    val savedAt: Long,
    /** 逗号分隔的 ExoPlayer 实际 shuffle 序；旧 v2 行为 null。 */
    val shuffleOrder: String? = null,
    /** 与 [shuffleOrder] 同代的恢复种子；无可恢复实际序时为 null。 */
    val shuffleSeed: Long? = null,
) {
    companion object {
        const val SINGLETON_ID = 0
    }
}

/** 最近播放（本地，itemId 主键；重复播放覆盖 playedAt）。 */
@Entity(tableName = "music_recent")
data class MusicRecentEntity(
    @PrimaryKey val itemId: String,
    val name: String,
    val albumName: String,
    val artist: String?,
    val imageUri: String?,
    val runtimeTicks: Long,
    val playedAt: Long,
)

/** 本地歌单元数据；条目顺序与曲目身份由 [LocalPlaylistEntryEntity] 持有。 */
@Entity(tableName = "local_playlists")
data class LocalPlaylistEntity(
    @PrimaryKey val id: String,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
)

/**
 * 本地歌单有序条目。
 *
 * [serverId] / [userId] / [libraryId] 是全量命名空间；不属于当前来源的列使用固定哨兵，避免 SQLite 唯一索引把多个 `NULL`
 * 视为互不冲突。标题、路径和 URI 只保存为展示 / 解析快照，不参与身份。
 */
@Entity(
    tableName = "local_playlist_entries",
    foreignKeys =
        [
            ForeignKey(
                entity = LocalPlaylistEntity::class,
                parentColumns = ["id"],
                childColumns = ["playlistId"],
                onDelete = ForeignKey.CASCADE,
            )
        ],
    indices =
        [
            Index(value = ["playlistId"], name = "index_local_playlist_entries_playlistId"),
            Index(
                value =
                    [
                        "playlistId",
                        "trackKind",
                        "serverId",
                        "userId",
                        "libraryId",
                        "itemId",
                    ],
                name = "index_local_playlist_entries_identity",
                unique = true,
            ),
            Index(
                value = ["playlistId", "position"],
                name = "index_local_playlist_entries_position",
                unique = true,
            ),
        ],
)
data class LocalPlaylistEntryEntity(
    @PrimaryKey val id: String,
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
)
