package com.zhangwenkang.cinefin.music.data

import com.zhangwenkang.cinefin.database.music.MusicStorage
import com.zhangwenkang.cinefin.database.music.StoredRecentSong
import com.zhangwenkang.cinefin.player.core.domain.models.PlayerItem
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 本地最近播放（W21-R2）。
 *
 * 记录时机 = 音乐起播 / 切歌（ViewModel 观察当前曲目）；同一首重复播放只刷新时间并排到最前。 曲库快照里能找到完整元数据时补上专辑 / 艺人 /
 * 时长，找不到（如恢复队列里的曲目）则用播放条目兜底。
 */
@Singleton
class MusicRecentStore @Inject constructor(private val storage: MusicStorage) {

    suspend fun record(item: PlayerItem, librarySong: MusicSong?) {
        withContext(Dispatchers.IO) {
            val song = librarySong?.takeIf { it.itemId == item.itemId }
            storage.recordRecent(
                StoredRecentSong(
                    itemId = item.itemId.toString(),
                    name = song?.name ?: item.name,
                    albumName = song?.albumName.orEmpty(),
                    artist = song?.artist,
                    imageUri = song?.imageUri ?: item.thumbnailUri,
                    runtimeTicks = song?.runtimeTicks ?: 0L,
                    playedAt = System.currentTimeMillis(),
                )
            )
        }
    }

    suspend fun load(limit: Int = MusicStorage.DEFAULT_RECENT_LIMIT): List<MusicSong> =
        withContext(Dispatchers.IO) {
            storage.loadRecent(limit).mapNotNull { entry -> entry.toMusicSongOrNull() }
        }
}

internal fun StoredRecentSong.toMusicSongOrNull(): MusicSong? {
    val parsedId = runCatching { UUID.fromString(itemId) }.getOrNull() ?: return null
    return MusicSong(
        itemId = parsedId,
        name = name,
        albumName = albumName,
        artist = artist,
        indexNumber = null,
        runtimeTicks = runtimeTicks,
        imageUri = imageUri,
        resumePositionMs = 0L,
    )
}
