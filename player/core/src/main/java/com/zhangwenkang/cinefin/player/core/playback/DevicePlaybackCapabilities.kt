package com.zhangwenkang.cinefin.player.core.playback

/**
 * HDR 类型（W4-T3b / VIDEO-08）。
 *
 * 同一套枚举同时用于两个方向：① 设备能力探测（`Display.getHdrCapabilities()` 的取值）；② 片源 HDR 类型（T4/T7 从媒体信息判定）。
 * [DevicePlaybackCapabilities] 的比对入口靠它在两边搭桥，探测与片源判定不各写一套字符串。
 */
enum class HdrType {
    HDR10,
    HDR10_PLUS,
    DOLBY_VISION,
    HLG,
}

/** 视频编码（W4-T3b）：探测结果按编码分档，面板与后续片源比对共用。 */
enum class VideoCodec {
    H264,
    HEVC,
    VP9,
    AV1,
    MPEG4,
    DOLBY_VISION,
}

/**
 * 某编码在本机的解码档位。
 *
 * [NONE] 是「本机连软解都解不了」——探测只枚举系统解码器（MediaCodecList），App 自带的 FFmpeg 扩展软解也算在系统解码器之外， 因此 [NONE] 只用于面板标注与
 * T4 的诊断参考，不当作播放能否成功的判据。
 */
enum class CodecDecodeTier {
    HARDWARE,
    SOFTWARE,
    NONE,
}

/** 单个编码的解码档位。 */
data class CodecCapability(val codec: VideoCodec, val tier: CodecDecodeTier)

/**
 * 设备播放能力快照（W4-T3b / VIDEO-08）。
 *
 * 纯数据类，无 Android 依赖：探测由 `player/local` 的 `DeviceCapabilitiesProbe` 完成，本类只承载结果与比对函数，JVM 单测可直接构造断言。
 *
 * 缓存语义：一次进程生命周期内只探测一次（启动时 / 首次开解码面板时），面板不再重探。
 *
 * @property hdrTypes 显示器声明的 HDR 类型（`Display.getHdrCapabilities()`）；空集 = 显示器不支持任何 HDR。
 * @property codecs 各视频编码的解码档位（`MediaCodecList` 枚举结果）。
 * @property mpvHwdecTiers mpv 配置路径实际可下发的 `hwdec` 档位（App 的 `PlayerDecodeMode.mpvHwDec` 值域，不是 mpv
 *   全部档位）。
 */
data class DevicePlaybackCapabilities(
    val hdrTypes: Set<HdrType>,
    val codecs: List<CodecCapability>,
    val mpvHwdecTiers: List<String>,
) {
    val hdr10: Boolean
        get() = HdrType.HDR10 in hdrTypes

    val hdr10Plus: Boolean
        get() = HdrType.HDR10_PLUS in hdrTypes

    val dolbyVision: Boolean
        get() = HdrType.DOLBY_VISION in hdrTypes

    val hlg: Boolean
        get() = HdrType.HLG in hdrTypes

    fun supportsHdr(type: HdrType): Boolean = type in hdrTypes

    /** 某编码的档位；不在快照里按 [CodecDecodeTier.NONE] 处理（探测失败 / 未知编码不会伪装成支持）。 */
    fun codecTier(codec: VideoCodec): CodecDecodeTier =
        codecs.firstOrNull { it.codec == codec }?.tier ?: CodecDecodeTier.NONE

    /**
     * 片源 HDR 类型是否全部能在本机直通（T4/T7 的比对入口）。
     *
     * 空集（SDR 片源）= 恒 true：SDR 不需要任何 HDR 能力。
     */
    fun supportsAll(sourceHdrTypes: Set<HdrType>): Boolean = hdrTypes.containsAll(sourceHdrTypes)

    /** 片源 HDR 类型里本机不支持的子集；空集 = 全支持。T4/T7 用它决定是否提示 / 转交 mpv 色调映射。 */
    fun missingHdrTypes(sourceHdrTypes: Set<HdrType>): Set<HdrType> = sourceHdrTypes - hdrTypes

    companion object {
        /** 探测失败 / 尚未探测时的空快照：不声称支持任何能力。 */
        val Unknown: DevicePlaybackCapabilities =
            DevicePlaybackCapabilities(
                hdrTypes = emptySet(),
                codecs = emptyList(),
                mpvHwdecTiers = emptyList(),
            )
    }
}
