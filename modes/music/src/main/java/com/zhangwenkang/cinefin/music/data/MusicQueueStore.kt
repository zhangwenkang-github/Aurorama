package com.zhangwenkang.cinefin.music.data

import com.zhangwenkang.cinefin.database.music.MusicStorage
import com.zhangwenkang.cinefin.database.music.StoredQueue
import com.zhangwenkang.cinefin.database.music.StoredQueueItem
import com.zhangwenkang.cinefin.database.music.StoredQueueState
import com.zhangwenkang.cinefin.player.core.domain.models.MusicQueue
import com.zhangwenkang.cinefin.player.core.domain.models.PlayerItem
import com.zhangwenkang.cinefin.player.core.domain.models.QueueSource
import com.zhangwenkang.cinefin.player.core.domain.models.RepeatMode
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 恢复出的队列与播放位置（位置已写回当前曲目的 `playbackPosition`，续播走既有 MU-9 语义）。 */
data class MusicQueueSnapshot(
    val queue: MusicQueue,
    val positionMs: Long,
    val savedAt: Long,
)

/**
 * 音乐队列持久化的域模型入口（W21-R2）。
 *
 * 只负责 `MusicQueue` ↔ `StoredQueue` 的映射，真正的 Room 读写在 data 层 `MusicStorage`。
 */
@Singleton
class MusicQueueStore @Inject constructor(private val storage: MusicStorage) {

    suspend fun save(queue: MusicQueue, positionMs: Long) {
        withContext(Dispatchers.IO) {
            storage.saveQueue(
                queue.toStored(positionMs = positionMs, savedAt = System.currentTimeMillis())
            )
        }
    }

    suspend fun load(): MusicQueueSnapshot? =
        withContext(Dispatchers.IO) { storage.loadQueue()?.toSnapshot() }

    suspend fun clear() = withContext(Dispatchers.IO) { storage.clearQueue() }
}

internal fun MusicQueue.toStored(positionMs: Long, savedAt: Long): StoredQueue =
    StoredQueue(
        items = items.mapIndexedNotNull { index, item -> item.toStoredItem(position = index) },
        state =
            StoredQueueState(
                currentIndex = currentIndex,
                positionMs = positionMs.coerceAtLeast(0L),
                repeatMode = repeatMode.name,
                shuffleEnabled = shuffleEnabled,
                source = source.name,
                sourceId = sourceId,
                savedAt = savedAt,
            ),
    )

internal fun StoredQueue.toSnapshot(): MusicQueueSnapshot? {
    val restoredItems =
        items.sortedBy { item -> item.position }.mapNotNull { item -> item.toPlayerItemOrNull() }
    if (restoredItems.isEmpty()) return null
    val positionMs = state.positionMs.coerceAtLeast(0L)
    val currentIndex = state.currentIndex.coerceIn(0, restoredItems.lastIndex)
    // 保存位置写回当前曲目：恢复播放时 setQueue 会按 PlayerItem.playbackPosition 续播（与 MU-9 同一条路径）。
    val itemsWithResume = restoredItems.mapIndexed { index, item ->
        if (index == currentIndex) item.copy(playbackPosition = positionMs) else item
    }
    return MusicQueueSnapshot(
        queue =
            MusicQueue(
                items = itemsWithResume,
                currentIndex = currentIndex,
                repeatMode = parseRepeatMode(state.repeatMode),
                shuffleEnabled = state.shuffleEnabled,
                source = parseQueueSource(state.source),
                sourceId = state.sourceId,
            ),
        positionMs = positionMs,
        savedAt = state.savedAt,
    )
}

private fun PlayerItem.toStoredItem(position: Int): StoredQueueItem? {
    // 没有媒体地址的条目无法恢复播放（正常情况下解析过的曲目都有直连或转码地址）。
    if (mediaSourceUri.isBlank()) return null
    return StoredQueueItem(
        position = position,
        itemId = itemId.toString(),
        name = name,
        mediaSourceId = mediaSourceId,
        playbackPositionMs = playbackPosition.coerceAtLeast(0L),
        mediaSourceUri = mediaSourceUri,
        thumbnailUri = thumbnailUri,
        indexNumber = indexNumber,
    )
}

private fun StoredQueueItem.toPlayerItemOrNull(): PlayerItem? {
    val parsedId = runCatching { UUID.fromString(itemId) }.getOrNull() ?: return null
    if (mediaSourceUri.isBlank()) return null
    return PlayerItem(
        name = name,
        itemId = parsedId,
        mediaSourceId = mediaSourceId,
        playbackPosition = playbackPositionMs.coerceAtLeast(0L),
        mediaSourceUri = mediaSourceUri,
        indexNumber = indexNumber,
        thumbnailUri = thumbnailUri,
    )
}

private fun parseRepeatMode(raw: String): RepeatMode = runCatching {
    RepeatMode.valueOf(raw)
}
    .getOrDefault(RepeatMode.OFF)

private fun parseQueueSource(raw: String): QueueSource = runCatching {
    QueueSource.valueOf(raw)
}
    .getOrDefault(QueueSource.MANUAL)
