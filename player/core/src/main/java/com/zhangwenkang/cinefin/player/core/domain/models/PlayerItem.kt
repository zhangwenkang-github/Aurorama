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
) : Parcelable

/** MediaItem extras：季号（队列面板按季分组用） */
const val PLAYER_EXTRA_SEASON_NUMBER = "cinefin.seasonNumber"

/** MediaItem extras：集号 */
const val PLAYER_EXTRA_EPISODE_NUMBER = "cinefin.episodeNumber"
