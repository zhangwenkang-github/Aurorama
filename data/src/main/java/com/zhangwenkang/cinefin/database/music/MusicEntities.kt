package com.zhangwenkang.cinefin.database.music

import androidx.room3.Entity
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
