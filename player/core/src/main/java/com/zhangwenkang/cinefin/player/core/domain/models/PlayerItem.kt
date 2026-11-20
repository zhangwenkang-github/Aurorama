package com.zhangwenkang.cinefin.player.core.domain.models

import android.os.Parcelable
import java.util.UUID
import kotlinx.parcelize.Parcelize

@Parcelize
data class PlayerItem(
    val name: String,
    val itemId: UUID,
    val mediaSourceId: String,
    val playbackPosition: Long,
    val mediaSourceUri: String = "",
    val parentIndexNumber: Int? = null,
    val indexNumber: Int? = null,
    val indexNumberEnd: Int? = null,
    /**
     * 音乐条目的艺人名（W68：媒体会话 / 系统桌面媒体胶囊 / 锁屏显示；视频条目为 null）。
     *
     * 只用于媒体元数据展示，不参与播放；恢复队列（Room 快照）不含它，拿不到时留 null。
     */
    val artist: String? = null,
    /** 剧集 / 影片缩略图（Jellyfin 图片地址）：队列列表与通知封面共用 */
    val thumbnailUri: String? = null,
    val externalSubtitles: List<ExternalSubtitle> = emptyList(),
    /**
     * 全部字幕源（含内嵌字幕）：字幕面板的轨道列表与自研渲染管线都读它。
     *
     * [externalSubtitles] 仍然保留给播放内核做原生兜底渲染；两者互不替代。
     */
    val subtitleSources: List<PlayerSubtitleSource> = emptyList(),
    val chapters: List<PlayerChapter> = emptyList(),
    val trickplayInfo: TrickplayInfo? = null,
    /** 播放信息面板用的媒体元数据（§1.8）；构建时取不到的字段留 null，UI 降级显示「—」 */
    val mediaInfo: PlayerMediaInfo? = null,
    /**
     * 音乐轨道来源描述（MUSIC-01/02）：服务器曲目 / 本地文件统一携带 codec、码率、封面、歌词引用与 是否转码。
     *
     * 视频条目为 null；音乐条目缺失时按「来源未知」处理，播放与 UI 都不得把它当作必需字段。
     */
    val trackSource: TrackSource? = null,
    /**
     * W5-T8（MUSIC-49）：本地文件缺失 / 授权失效，条目暂不可播。
     *
     * 只在本地 `content://` 源解析或播放失败后置 true；队列保留条目（可手动移除），自动播放时跳过。 旧队列快照没有这一列，反序列化按 false 处理，不炸。
     */
    val unavailable: Boolean = false,
) : Parcelable

/** MediaItem extras：季号（队列面板按季分组用） */
const val PLAYER_EXTRA_SEASON_NUMBER = "cinefin.seasonNumber"

/** MediaItem extras：集号 */
const val PLAYER_EXTRA_EPISODE_NUMBER = "cinefin.episodeNumber"

/** MediaItem extras：播放信息面板的媒体元数据（[PlayerMediaInfo]） */
const val PLAYER_EXTRA_MEDIA_INFO = "cinefin.mediaInfo"

/** MediaItem extras：从 PlaybackInfo 选中的稳定 media source id。 */
const val PLAYER_EXTRA_MEDIA_SOURCE_ID = "cinefin.mediaSourceId"

/**
 * MediaItem extras：全部字幕源（[PlayerSubtitleSource] 的 Parcelable ArrayList）。
 *
 * ExoPlayer 走自研渲染管线、不读它；mpv 在「容器没有内嵌字幕」时（典型是服务器转码 / HLS） 用它把 Jellyfin 交付的独立字幕文件 sub-add 给 mpv，让
 * libass 仍能渲染 ASS/SSA 特效。
 */
const val PLAYER_EXTRA_SUBTITLE_SOURCES = "cinefin.subtitleSources"
