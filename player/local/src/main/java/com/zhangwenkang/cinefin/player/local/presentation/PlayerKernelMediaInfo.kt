package com.zhangwenkang.cinefin.player.local.presentation

import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import com.zhangwenkang.cinefin.player.core.domain.models.PlayerMediaInfo
import com.zhangwenkang.cinefin.player.local.domain.hdrFromVideo
import com.zhangwenkang.cinefin.player.local.mpv.MPVPlayer

/**
 * 读「当前播放内核」实测到的媒体参数（§1.8 信息面板的第二层）。
 *
 * - **ExoPlayer**：从 `currentTracks` 里取视频 / 音频 Format（解码器实际拿到的编码、分辨率、码率、帧率、色彩）；
 * - **mpv**：读 mpv 原生属性（`video-format` / `container-fps` / 视频参数组等）。
 *
 * 两个内核能拿到的字段天然不同：**取不到一律留 null**，由 UI 降级显示「—」。 mpv 分支会走到 native，必须在后台线程调用。
 */
fun readKernelMediaInfo(player: Player): PlayerMediaInfo? =
    when (player) {
        is MPVPlayer -> player.queryMediaInfo()
        else -> exoPlayerMediaInfo(player.currentTracks)
    }

/** ExoPlayer 的 [Tracks] → 信息快照（纯数据转换，便于单测与复用） */
fun exoPlayerMediaInfo(tracks: Tracks?): PlayerMediaInfo? {
    val videoFormat = tracks?.pickFormat(C.TRACK_TYPE_VIDEO)
    val audioFormat = tracks?.pickFormat(C.TRACK_TYPE_AUDIO)
    if (videoFormat == null && audioFormat == null) return null
    return PlayerMediaInfo(
        videoCodec = videoFormat?.codecs ?: videoFormat?.sampleMimeType,
        width = videoFormat?.width?.takeIf { it > 0 },
        height = videoFormat?.height?.takeIf { it > 0 },
        videoBitrate = videoFormat?.bitrate?.takeIf { it > 0 },
        frameRate = videoFormat?.frameRate?.takeIf { it > 0f },
        hdr =
            hdrFromVideo(
                codecs = videoFormat?.codecs,
                colorTransfer = videoFormat?.colorInfo?.colorTransfer,
                colorSpace = videoFormat?.colorInfo?.colorSpace,
            ),
        audioCodec = audioFormat?.codecs ?: audioFormat?.sampleMimeType,
        audioChannels = audioFormat?.channelCount?.takeIf { it > 0 },
        audioBitrate = audioFormat?.bitrate?.takeIf { it > 0 },
        audioSampleRate = audioFormat?.sampleRate?.takeIf { it > 0 },
    )
}

/** 取第一个有实际参数的轨道格式（同类型里可能有多个空壳轨道） */
private fun Tracks.pickFormat(type: @C.TrackType Int) =
    groups
        .firstOrNull { it.type == type }
        ?.mediaTrackGroup
        ?.let { group ->
            (0 until group.length)
                .map { index -> group.getFormat(index) }
                .firstOrNull { format ->
                    format.width > 0 || format.height > 0 || format.channelCount > 0
                } ?: group.getFormat(0)
        }
