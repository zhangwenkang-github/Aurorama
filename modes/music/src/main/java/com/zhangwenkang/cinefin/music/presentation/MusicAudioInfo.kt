package com.zhangwenkang.cinefin.music.presentation

import com.zhangwenkang.cinefin.player.core.domain.models.TrackSource
import com.zhangwenkang.cinefin.player.local.audio.MusicAudioOutputInfo
import java.util.Locale
import kotlin.math.roundToInt

/** MUSIC-31 的提示义务落地：不做独占 / bit-perfect 输出，末行恒显。 */
const val MUSIC_BIT_PERFECT_NOTE = "非 bit-perfect 输出（Android 音频混音）"

private const val UNKNOWN = "—"

/**
 * 音频输出信息面板的展示模型（W5-T6 · MUSIC-34）。
 *
 * 源侧来自 [TrackSource]（T4 唯一来源，含 codec / container / 采样率 / 位深 / 声道 / 码率 / 是否转码）， 输出侧来自 ExoPlayer
 * 当前选中音频轨的 `Format` 运行时读数（[MusicAudioOutputInfo]）。
 */
data class MusicAudioInfo(
    val title: String,
    val sourceFormat: String,
    val sourceSampleRate: String,
    val sourceBitDepth: String,
    val sourceChannels: String,
    val outputFormat: String,
    val outputSampleRate: String,
    val outputChannels: String,
    val transcode: String,
    val bitrate: String,
    /** 输出侧是否有真实读数（false = 未在本机播放 / 内核尚未给出音频轨）。 */
    val outputKnown: Boolean,
)

/**
 * 组装信息面板数据。
 *
 * - [playing] = 该曲目是否是当前音乐会话的曲目：只有当前曲目才有输出侧读数与「是否转码」的确切值； 非当前曲目显示「—（未播放）」，不拿历史值冒充。
 * - 转码判定只读 [TrackSource.isTranscoded]（T4 唯一来源）。
 */
fun buildMusicAudioInfo(
    title: String,
    source: TrackSource?,
    output: MusicAudioOutputInfo?,
    playing: Boolean,
): MusicAudioInfo =
    MusicAudioInfo(
        title = title,
        sourceFormat = formatCodecContainer(source?.codec, source?.container),
        sourceSampleRate = formatSampleRate(source?.sampleRate),
        sourceBitDepth = formatBitDepth(source?.bitDepth),
        sourceChannels = formatChannelCount(source?.channelCount),
        outputFormat = formatCodecContainer(output?.codec, output?.container),
        outputSampleRate = formatSampleRate(output?.sampleRate),
        outputChannels = formatChannelCount(output?.channelCount),
        transcode = formatTranscode(playing, source),
        bitrate = formatBitrate(source?.bitrate ?: output?.bitrate),
        outputKnown = output != null,
    )

/** codec / container 展示：`FLAC / flac`；MIME（`audio/mp4`）只保留子类型。 */
fun formatCodecContainer(codec: String?, container: String?): String {
    val codecText = codec?.takeIf { it.isNotBlank() }?.uppercase(Locale.US)
    val containerText = container?.takeIf { it.isNotBlank() }?.let(::normalizeContainer)
    return when {
        codecText != null && containerText != null -> "$codecText / $containerText"
        codecText != null -> codecText
        containerText != null -> containerText
        else -> UNKNOWN
    }
}

private fun normalizeContainer(container: String): String =
    container.substringAfterLast('/').lowercase(Locale.US)

/** 采样率：48000 → `48 kHz`，44100 → `44.1 kHz`。 */
fun formatSampleRate(hz: Int?): String {
    if (hz == null || hz <= 0) return UNKNOWN
    return if (hz % 1_000 == 0) {
        "${hz / 1_000} kHz"
    } else {
        String.format(Locale.US, "%.1f kHz", hz / 1_000f)
    }
}

fun formatBitDepth(bits: Int?): String = if (bits == null || bits <= 0) UNKNOWN else "$bits bit"

fun formatChannelCount(channels: Int?): String =
    when {
        channels == null || channels <= 0 -> UNKNOWN
        channels == 1 -> "1 声道（单声道）"
        channels == 2 -> "2 声道（立体声）"
        else -> "$channels 声道"
    }

fun formatBitrate(bps: Int?): String {
    if (bps == null || bps <= 0) return UNKNOWN
    return if (bps >= 1_000) "${(bps / 1_000f).roundToInt()} kbps" else "$bps bps"
}

fun formatTranscode(playing: Boolean, source: TrackSource?): String =
    when {
        !playing -> "$UNKNOWN（未播放）"
        source == null -> "$UNKNOWN（来源未知）"
        source.isTranscoded -> "是（服务端转码流）"
        else -> "否（直连）"
    }
