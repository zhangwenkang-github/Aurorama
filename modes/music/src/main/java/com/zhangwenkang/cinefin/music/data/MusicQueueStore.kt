package com.zhangwenkang.cinefin.music.data

import com.zhangwenkang.cinefin.database.music.MusicStorage
import com.zhangwenkang.cinefin.database.music.StoredQueue
import com.zhangwenkang.cinefin.database.music.StoredQueueItem
import com.zhangwenkang.cinefin.database.music.StoredQueueKind
import com.zhangwenkang.cinefin.database.music.StoredQueueState
import com.zhangwenkang.cinefin.player.core.domain.models.MusicQueue
import com.zhangwenkang.cinefin.player.core.domain.models.PlayerItem
import com.zhangwenkang.cinefin.player.core.domain.models.QueueSource
import com.zhangwenkang.cinefin.player.core.domain.models.RepeatMode
import com.zhangwenkang.cinefin.player.core.domain.models.TrackSource
import com.zhangwenkang.cinefin.player.core.domain.models.TrackSourceType
import com.zhangwenkang.cinefin.player.core.domain.models.isValidShuffleOrder
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

internal fun MusicQueue.toStored(positionMs: Long, savedAt: Long): StoredQueue = run {
    val mainItems = items.mapIndexedNotNull { index, item ->
        item.toStoredItem(position = index, kind = StoredQueueKind.MAIN)
    }
    // 正在播放的「接下来播放」条目回写成待播队首：进程被杀后曲目不丢（恢复后从主队列锚点续播），
    // 播放位置只写回主队列当前曲目（见 toSnapshot）。
    val upNextItems =
        (listOfNotNull(upNextPlaying) + upNext).mapIndexedNotNull { index, item ->
            item.toStoredItem(position = items.size + index, kind = StoredQueueKind.UP_NEXT)
        }
    val storedItems = mainItems + upNextItems
    // 随机序是「时间线下标」的排列：只有没有接下来播放条目时时间线 == 主队列，序才可复用。
    val canPersistOrder =
        upNextCount == 0 &&
            mainItems.size == items.size &&
            isValidShuffleOrder(shuffleOrder, items.size)
    StoredQueue(
        items = storedItems,
        state =
            StoredQueueState(
                currentIndex = currentIndex,
                positionMs = positionMs.coerceAtLeast(0L),
                repeatMode = repeatMode.name,
                shuffleEnabled = shuffleEnabled,
                source = source.name,
                sourceId = sourceId,
                savedAt = savedAt,
                shuffleOrder = shuffleOrder.takeIf { canPersistOrder }?.joinToString(","),
                shuffleSeed = shuffleSeed.takeIf { canPersistOrder },
                shuffleKeepCurrentFirst = shuffleKeepCurrentFirst && shuffleEnabled,
            ),
    )
}

internal fun StoredQueue.toSnapshot(): MusicQueueSnapshot? {
    val ordered = items.sortedBy { item -> item.position }
    val restoredItems =
        ordered
            .filter { item -> item.kind != StoredQueueKind.UP_NEXT }
            .mapNotNull { item -> item.toPlayerItemOrNull() }
    if (restoredItems.isEmpty()) return null
    val restoredUpNext =
        ordered
            .filter { item -> item.kind == StoredQueueKind.UP_NEXT }
            .mapNotNull { item -> item.toPlayerItemOrNull() }
    val positionMs = state.positionMs.coerceAtLeast(0L)
    val currentIndex = state.currentIndex.coerceIn(0, restoredItems.lastIndex)
    val restoredShuffleOrder =
        decodeShuffleOrder(state.shuffleOrder)?.takeIf { order ->
            restoredUpNext.isEmpty() && isValidShuffleOrder(order, restoredItems.size)
        }
    val restoredShuffleSeed = state.shuffleSeed.takeIf { restoredShuffleOrder != null }
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
                shuffleOrder = restoredShuffleOrder,
                shuffleSeed = restoredShuffleSeed,
                upNext = restoredUpNext,
                shuffleKeepCurrentFirst = state.shuffleKeepCurrentFirst && state.shuffleEnabled,
            ),
        positionMs = positionMs,
        savedAt = state.savedAt,
    )
}

internal fun decodeShuffleOrder(raw: String?): List<Int>? {
    if (raw.isNullOrBlank()) return null
    return raw.split(',').map { part -> part.trim().toIntOrNull() ?: return null }
}

private fun PlayerItem.toStoredItem(position: Int, kind: String): StoredQueueItem? {
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
        kind = kind,
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
        trackSource = restoredTrackSource(mediaSourceUri),
    )
}

/**
 * 冷启动恢复队列没有 [TrackSource] 快照，按地址回推最少语义（MUSIC-01/03）： 本地 `content://` =
 * LOCAL；`/Audio/{id}/universal` 且带转码参数 = 仍在转码。
 *
 * 只回推「来源类型 + 是否转码」，codec / 码率等保持 null（未知 ≠ 直连证据），避免恢复后 UI 误标。
 */
internal fun restoredTrackSource(uri: String): TrackSource {
    val local = uri.startsWith("content://", ignoreCase = true)
    val universal = !local && uri.contains(UNIVERSAL_AUDIO_PATH, ignoreCase = true)
    val transcoded =
        universal &&
            (uri.contains("AudioCodec=", ignoreCase = true) ||
                uri.contains("AudioBitRate=", ignoreCase = true))
    return TrackSource(
        id = "",
        sourceType = if (local) TrackSourceType.LOCAL else TrackSourceType.SERVER,
        streamUrl = uri,
        isTranscoded = transcoded,
    )
}

private const val UNIVERSAL_AUDIO_PATH = "/universal"

private fun parseRepeatMode(raw: String): RepeatMode = runCatching {
    RepeatMode.valueOf(raw)
}
    .getOrDefault(RepeatMode.OFF)

private fun parseQueueSource(raw: String): QueueSource = runCatching {
    QueueSource.valueOf(raw)
}
    .getOrDefault(QueueSource.MANUAL)
