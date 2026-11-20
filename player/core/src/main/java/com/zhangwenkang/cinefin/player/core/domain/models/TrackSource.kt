package com.zhangwenkang.cinefin.player.core.domain.models

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/** 轨道来源类型（MUSIC-01）：Jellyfin 远程流还是本机文件。 */
enum class TrackSourceType {
    SERVER,
    LOCAL,
}

/**
 * 一条可播放轨道的描述（MUSIC-01 / MUSIC-02）。
 *
 * 播放队列用它承载「这首曲子从哪来、什么格式、多大码率、有没有歌词 / 封面」， 因此服务器曲目与本机文件在队列里是同一个类型，播放层不需要再知道来源差异。
 *
 * 字段口径：
 * - [streamUrl] 是**最终起播地址**：服务器曲目为 `/Audio/{itemId}/universal` 协商地址（可能触发转码）， 本地曲目为 SAF `content://`
 *   文档 URI；
 * - [replayGainTrack] / [replayGainAlbum] 单位 dB，取不到为 null（当前服务器曲目通常为空：RG 由播放时的内嵌标签 Range 解析补齐）；
 * - [lyricsRef] 是歌词引用：服务器曲目为 `Audio/{itemId}/Lyrics`，本地曲目为 null（由歌词链路自行探测）；
 * - [isTranscoded] 表示 [streamUrl] 是否指向服务端转码流（MUSIC-03 的「转码中」标注来源）。
 */
@Parcelize
data class TrackSource(
    val id: String,
    val sourceType: TrackSourceType,
    val streamUrl: String,
    /** 时长（毫秒）；未知为 0。 */
    val duration: Long = 0L,
    /** 源码率（bit/s）；未知为 null。 */
    val bitrate: Int? = null,
    /** 源编码（Jellyfin codec 名 / 本地扩展名归一值，例如 flac / aac / mp3）。 */
    val codec: String? = null,
    /** 采样率（Hz）。 */
    val sampleRate: Int? = null,
    /** 位深（bit）。 */
    val bitDepth: Int? = null,
    /** 曲目级 ReplayGain（dB）。 */
    val replayGainTrack: Float? = null,
    /** 专辑级 ReplayGain（dB）。 */
    val replayGainAlbum: Float? = null,
    /** 封面地址（服务器图片 URL / 本地封面文件）。 */
    val coverArtUrl: String? = null,
    /** 歌词引用（见类注释）。 */
    val lyricsRef: String? = null,
    /** 当前流是否由服务端转码产生（服务端曲目直连时为 false，本地文件恒 false）。 */
    val isTranscoded: Boolean = false,
) : Parcelable
