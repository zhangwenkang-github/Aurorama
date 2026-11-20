package com.zhangwenkang.cinefin.player.core.playback

/** 直连 / 转码决策（MUSIC-03）。 */
sealed interface MusicAudioDecision {
    /** 直连原始文件：请求不带转码参数。 */
    data object DirectPlay : MusicAudioDecision

    /** 服务端转码：请求带 `AudioCodec` / `AudioBitRate` / `EnableRedirection`。 */
    data class Transcode(
        val audioCodec: String,
        val audioBitRateBps: Int,
        val reason: MusicAudioTranscodeReason,
    ) : MusicAudioDecision
}

/** 触发转码的原因（日志 / 面板诊断用；不参与请求构造）。 */
enum class MusicAudioTranscodeReason {
    /** 本机没有该编码的解码器（含 DSD 这类手机必然不支持的格式）。 */
    CODEC_UNSUPPORTED,

    /** 源码率超过用户选的音质档上限。 */
    BITRATE_ABOVE_CEILING,
}

/**
 * 音乐音质协商（MUSIC-03）：纯函数，无 Android 依赖。
 *
 * 输入是「设备音频解码能力 + 音质档位上限 + 片源编码 / 码率」，输出是直连还是转码； `/Audio/{itemId}/universal` 的请求参数由调用方按结果构造。
 *
 * 判定口径：
 * 1. 原始档（`allowTranscoding = false`）恒直连；
 * 2. 源码率超过档位上限 → 转码到档位码率；
 * 3. 源码码本机解不了 → 转码；未知编码 / 探测失败（能力集为空）按「交给服务端判断」处理，不激进转码；
 * 4. 其余直连。
 */
object MusicAudioNegotiation {
    /** 转码目标编码：Jellyfin 默认音频转码档（mp3），ExoPlayer 原生可解，320 kbps 以内无损于档位语义。 */
    const val TRANSCODE_CODEC: String = "mp3"

    /** 转码目标容器（与 [TRANSCODE_CODEC] 配套；Jellyfin `TranscodingContainer` 参数）。 */
    const val TRANSCODE_CONTAINER: String = "mp3"

    /** 无档位上限时的转码兜底码率（bps）。 */
    const val MAX_OUTPUT_BITRATE_BPS: Int = 320_000

    /** 转码目标码率下限（bps），避免源标签缺失时转出过低码率。 */
    const val MIN_OUTPUT_BITRATE_BPS: Int = 128_000

    /**
     * 直连容器表（`/Audio/{itemId}/universal` 的 `Container` 参数）。
     *
     * 顺序固定为 [AudioCodec] 枚举顺序去重，保证同一能力快照生成同一 URL（可单测钉住）。
     */
    fun directPlayContainers(supportedAudioCodecs: Set<AudioCodec>): List<String> =
        AudioCodec.entries
            .mapNotNull { codec ->
                codec.container?.takeIf { container ->
                    codec in supportedAudioCodecs && container.isNotBlank()
                }
            }
            .distinct()

    /**
     * 决策入口。
     *
     * @param sourceCodec 片源编码（Jellyfin `MediaStream.codec`）；null / 未知 = 不因此转码。
     * @param sourceBitrateBps 片源码率（bit/s）；null = 未知。
     * @param supportedAudioCodecs 本机可解码的音乐编码集；空集 = 探测失败，不作转码依据。
     * @param bitrateCeilingBps 音质档位上限（bps）；0 = 自动（无上限）。
     * @param allowTranscoding 原始档为 false（禁止转码）。
     */
    fun decide(
        sourceCodec: String?,
        sourceBitrateBps: Int?,
        supportedAudioCodecs: Set<AudioCodec>,
        bitrateCeilingBps: Long,
        allowTranscoding: Boolean,
    ): MusicAudioDecision {
        if (!allowTranscoding) return MusicAudioDecision.DirectPlay
        val ceiling = bitrateCeilingBps.takeIf { it > 0L }
        val source = sourceBitrateBps?.takeIf { it > 0 }
        val overCeiling = ceiling != null && source != null && source > ceiling
        val sourceAudioCodec = AudioCodec.fromJellyfinName(sourceCodec)
        val codecUnsupported =
            supportedAudioCodecs.isNotEmpty() &&
                sourceAudioCodec != null &&
                sourceAudioCodec !in supportedAudioCodecs
        if (!overCeiling && !codecUnsupported) return MusicAudioDecision.DirectPlay
        val reason =
            if (overCeiling) {
                MusicAudioTranscodeReason.BITRATE_ABOVE_CEILING
            } else {
                MusicAudioTranscodeReason.CODEC_UNSUPPORTED
            }
        return MusicAudioDecision.Transcode(
            audioCodec = TRANSCODE_CODEC,
            audioBitRateBps = targetBitrate(ceiling, source),
            reason = reason,
        )
    }

    private fun targetBitrate(ceiling: Long?, source: Int?): Int {
        ceiling?.let {
            return it.coerceIn(MIN_OUTPUT_BITRATE_BPS.toLong(), Int.MAX_VALUE.toLong()).toInt()
        }
        return (source ?: MAX_OUTPUT_BITRATE_BPS).coerceIn(
            MIN_OUTPUT_BITRATE_BPS,
            MAX_OUTPUT_BITRATE_BPS,
        )
    }
}
