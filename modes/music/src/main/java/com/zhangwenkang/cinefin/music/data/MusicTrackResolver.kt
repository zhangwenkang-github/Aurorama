package com.zhangwenkang.cinefin.music.data

import com.zhangwenkang.cinefin.models.FindroidSourceType
import com.zhangwenkang.cinefin.player.core.domain.models.PlayerItem
import com.zhangwenkang.cinefin.repository.JellyfinRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 音乐曲目 → [PlayerItem] 的解析（W1 R2）。
 *
 * 列表接口拿到的音频条目没有带播放地址（path），与视频的 `PlaylistManager` 一致： 起播前用 `getMediaSources(includePath = true)`
 * 补一次真实地址。
 */
@Singleton
class MusicTrackResolver @Inject constructor(private val repository: JellyfinRepository) {

    /** 批量解析；任一曲目拿不到媒体源时抛出，由调用方决定是否提示用户。 */
    suspend fun toPlayerItems(songs: List<MusicSong>): List<PlayerItem> = songs.map { song ->
        song.toPlayerItem()
    }

    private suspend fun MusicSong.toPlayerItem(): PlayerItem {
        val sources = repository.getMediaSources(itemId, includePath = true)
        val source =
            sources.firstOrNull { it.type == FindroidSourceType.LOCAL }
                ?: sources.firstOrNull()
                ?: error("曲目「$name」没有可用的媒体源")
        return PlayerItem(
            name = name,
            itemId = itemId,
            mediaSourceId = source.id,
            // W1 从曲目开头播放；续播（UserData.playbackPositionTicks）与进度上报一起在 W2 做
            playbackPosition = 0L,
            // 与视频一致：服务器判定要转码时优先走转码地址，否则直连原始文件
            mediaSourceUri = source.transcodingPath ?: source.path,
            thumbnailUri = imageUri,
        )
    }
}
