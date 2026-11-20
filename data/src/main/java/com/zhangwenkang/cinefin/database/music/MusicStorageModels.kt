package com.zhangwenkang.cinefin.database.music

/**
 * 音乐队列 / 最近播放的持久化 DTO（W21-R2）。
 *
 * 这些类型只描述"存什么"，不依赖播放域模型（`player:core`）， 音乐模块在 `MusicQueueStore` / `MusicRecentStore` 里做域模型 ↔ DTO
 * 映射。
 */
data class StoredQueueItem(
    val position: Int,
    val itemId: String,
    val name: String,
    val mediaSourceId: String,
    val playbackPositionMs: Long,
    val mediaSourceUri: String,
    val thumbnailUri: String?,
    val indexNumber: Int?,
    /** 分区（W5 T5）：[StoredQueueKind.MAIN] 主队列 / [StoredQueueKind.UP_NEXT] 接下来播放。 */
    val kind: String = StoredQueueKind.MAIN,
    /** W5-T8（MUSIC-49）：本地文件缺失 / 授权失效；v4 旧快照没有该字段，按 false 恢复。 */
    val unavailable: Boolean = false,
)

/** 队列条目分区取值（W5 T5，MUSIC-09）：手动插入的「接下来播放」入快照并标注类型。 */
object StoredQueueKind {
    const val MAIN = "MAIN"
    const val UP_NEXT = "UP_NEXT"
}

/** 队列状态（单行）：当前索引、播放位置与播放模式。 */
data class StoredQueueState(
    val currentIndex: Int,
    val positionMs: Long,
    val repeatMode: String,
    val shuffleEnabled: Boolean,
    val source: String,
    val sourceId: String?,
    val savedAt: Long,
    val shuffleOrder: String? = null,
    val shuffleSeed: Long? = null,
    /** 随机模式细分（W5 T5）：true = 随机并保持当前曲目居首。 */
    val shuffleKeepCurrentFirst: Boolean = false,
)

/** 一份完整的队列快照（items 按 [StoredQueueItem.position] 升序）。 */
data class StoredQueue(
    val items: List<StoredQueueItem>,
    val state: StoredQueueState,
)

/** 最近播放条目（itemId 唯一，重复播放只更新时间）。 */
data class StoredRecentSong(
    val itemId: String,
    val name: String,
    val albumName: String,
    val artist: String?,
    val imageUri: String?,
    val runtimeTicks: Long,
    val playedAt: Long,
)
