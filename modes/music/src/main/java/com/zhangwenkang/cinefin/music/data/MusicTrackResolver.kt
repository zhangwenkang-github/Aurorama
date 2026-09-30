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

    /** 单曲解析（W3-R3b）：起播只需要被点的那一首，先解析它就能立刻出声， 其余曲目由调用方随后按需补齐——避免"点歌曲页任意一首要先串行解析整份列表"。 */
    suspend fun toPlayerItem(song: MusicSong): PlayerItem = song.resolve()

    private suspend fun MusicSong.resolve(): PlayerItem {
        val sources = repository.getMediaSources(itemId, includePath = true)
        val source =
            sources.firstOrNull { it.type == FindroidSourceType.LOCAL }
                ?: sources.firstOrNull()
                ?: error("曲目「$name」没有可用的媒体源")
        return PlayerItem(
            name = name,
            itemId = itemId,
            mediaSourceId = source.id,
            // W2：服务器 UserData.playbackPositionTicks 换算出的续播位置（0 = 从头播放）
            playbackPosition = resumePositionMs,
            // 与视频一致：服务器判定要转码时优先走转码地址，否则直连原始文件
            mediaSourceUri = source.transcodingPath ?: source.path,
            thumbnailUri = imageUri,
        )
    }
}
