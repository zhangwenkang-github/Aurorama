package com.zhangwenkang.cinefin.player.local.domain

import com.zhangwenkang.cinefin.player.core.domain.models.PlayerMediaInfo
import kotlin.math.abs
import kotlin.math.roundToInt

/** 信息面板里「拿不到这个字段」的统一占位符（§1.8：取不到就显示它，不留空白、不崩） */
const val MEDIA_INFO_UNKNOWN = "—"

/*
 * Media3 `C.COLOR_TRANSFER_*` / `C.COLOR_SPACE_*` 常量值。
 * 这里刻意用纯整数声明，让判定逻辑保持纯函数、可直接单测（不依赖 Media3 运行时）。
 */
private const val TRANSFER_ST2084 = 6 // C.COLOR_TRANSFER_ST2084（HDR10 / PQ）
private const val TRANSFER_HLG = 7 // C.COLOR_TRANSFER_HLG
private const val COLOR_SPACE_BT2020 = 6 // C.COLOR_SPACE_BT2020

/**
 * 播放信息面板的格式化层（§1.8）。
 *
 * 全部是纯函数：输入原始值（可能为 null / 0 / 空串），输出可直接渲染的字符串； 双内核取不到的字段统一输出
 * [MEDIA_INFO_UNKNOWN]，保证面板行数稳定、不会这行有那行没有。
 */
object PlayerMediaInfoFormat {
    fun container(container: String?): String =
        container?.trim()?.takeIf { it.isNotEmpty() }?.let { normalizeContainer(it) }
            ?: MEDIA_INFO_UNKNOWN

    fun codec(codec: String?): String =
        codec?.trim()?.takeIf { it.isNotEmpty() }?.let { normalizeCodec(it) } ?: MEDIA_INFO_UNKNOWN

    fun resolution(width: Int?, height: Int?): String =
        if (width != null && height != null && width > 0 && height > 0) {
            "$width × $height"
        } else {
            MEDIA_INFO_UNKNOWN
        }

    fun bitrate(bitsPerSecond: Int?): String {
        if (bitsPerSecond == null || bitsPerSecond <= 0) return MEDIA_INFO_UNKNOWN
        return if (bitsPerSecond >= 1_000_000) {
            "%.1f Mbps".format(bitsPerSecond / 1_000_000f)
        } else {
            "${(bitsPerSecond / 1000f).roundToInt()} kbps"
        }
    }

    fun frameRate(fps: Float?): String {
        if (fps == null || fps <= 0f || !fps.isFinite()) return MEDIA_INFO_UNKNOWN
        // 整数帧率不显示小数点；23.976 这类保留三位有效小数
        return if (abs(fps - fps.roundToInt()) < 0.01f) {
            "${fps.roundToInt()} fps"
        } else {
            "${"%.3f".format(fps).trimEnd('0').trimEnd('.')} fps"
        }
    }

    fun hdr(raw: String?): String = raw?.trim()?.takeIf { it.isNotEmpty() } ?: MEDIA_INFO_UNKNOWN

    /** 「编码 · N 声道 · 码率或采样率」，三者都拿不到时输出「—」 */
    fun audio(codec: String?, channels: Int?, bitrate: Int?, sampleRate: Int?): String {
        val parts = mutableListOf<String>()
        codec?.trim()?.takeIf { it.isNotEmpty() }?.let { parts += normalizeCodec(it) }
        if (channels != null && channels > 0) parts += "$channels 声道"
        if (bitrate != null && bitrate > 0) {
            parts += bitrate(bitrate)
        } else if (sampleRate != null && sampleRate > 0) {
            parts += "%.1f kHz".format(sampleRate / 1000f)
        }
        return parts.joinToString(" · ").ifEmpty { MEDIA_INFO_UNKNOWN }
    }

    fun fileSize(bytes: Long?): String {
        if (bytes == null || bytes <= 0L) return MEDIA_INFO_UNKNOWN
        val kb = bytes / 1024.0
        return when {
            kb < 1024 -> "%.0f KB".format(kb)
            kb < 1024 * 1024 -> "%.1f MB".format(kb / 1024)
            else -> "%.2f GB".format(kb / 1024 / 1024)
        }
    }

    /**
     * 路径只展示「主机之后的部分」并去掉鉴权 query： `https://host/Videos/abc/stream.mkv?api_key=…&static=true` →
     * `/Videos/abc/stream.mkv`。
     */
    fun path(uri: String?): String {
        val raw = uri?.trim()?.takeIf { it.isNotEmpty() } ?: return MEDIA_INFO_UNKNOWN
        val withoutQuery = raw.substringBefore('?').substringBefore('#')
        val withoutScheme =
            when {
                withoutQuery.startsWith("https://", ignoreCase = true) ->
                    withoutQuery.drop("https://".length)
                withoutQuery.startsWith("http://", ignoreCase = true) ->
                    withoutQuery.drop("http://".length)
                else -> return withoutQuery.ifEmpty { MEDIA_INFO_UNKNOWN }
            }
        val slash = withoutScheme.indexOf('/')
        return if (slash >= 0) {
            withoutScheme.substring(slash).ifEmpty { MEDIA_INFO_UNKNOWN }
        } else {
            withoutScheme.ifEmpty { MEDIA_INFO_UNKNOWN }
        }
    }
}

private fun normalizeContainer(raw: String): String {
    val value = raw.substringBefore(',').substringBefore(';').trim().lowercase()
    return when (value) {
        "matroska",
        "webm" -> if (value == "webm") "WebM" else "MKV"
        "mov",
        "mp4",
        "m4a",
        "m4v" -> "MP4"
        "m3u8",
        "hls" -> "HLS"
        "mpegts",
        "ts" -> "MPEG-TS"
        "avi" -> "AVI"
        "flv" -> "FLV"
        "mp3" -> "MP3"
        "flac" -> "FLAC"
        else -> raw.substringBefore(',').trim().uppercase().ifEmpty { MEDIA_INFO_UNKNOWN }
    }
}

private fun normalizeCodec(raw: String): String {
    // 注意：不能按 '.' 截断——MimeTypes 形态的编码串（video/x-vnd.on2.vp9）后缀才是关键
    val value = raw.substringBefore(',').trim().lowercase()
    return when {
        value.contains("hevc") || value.contains("h265") || value.contains("hvc1") -> "HEVC（H.265）"
        value.contains("h264") || value.contains("avc") -> "H.264（AVC）"
        value.contains("av01") || value == "av1" -> "AV1"
        value.contains("vp9") -> "VP9"
        value.contains("vp8") -> "VP8"
        value.contains("mpeg2") -> "MPEG-2"
        value.contains("mpeg4") -> "MPEG-4"
        value.contains("vc1") -> "VC-1"
        value.contains("mp4a") || value == "aac" -> "AAC"
        value.contains("eac3") || value.contains("ec-3") -> "E-AC-3"
        value.contains("ac3") || value.contains("ac-3") -> "AC-3"
        value.contains("dts") -> "DTS"
        value.contains("truehd") -> "TrueHD"
        value.contains("flac") -> "FLAC"
        value.contains("opus") -> "Opus"
        value.contains("vorbis") -> "Vorbis"
        value.contains("mp3") -> "MP3"
        value.contains("pcm") -> "PCM"
        else -> raw.substringBefore(',').trim().uppercase().ifEmpty { MEDIA_INFO_UNKNOWN }
    }
}

/** 合并「媒体源元数据」与「播放内核实测」两套信息：**内核优先**，内核缺的字段用媒体源补。 两边都取不到时保持 null，交给格式化层输出「—」。 */
fun mergePlayerMediaInfo(
    source: PlayerMediaInfo?,
    kernel: PlayerMediaInfo?,
): PlayerMediaInfo? {
    if (source == null) return kernel
    if (kernel == null) return source
    return PlayerMediaInfo(
        container = kernel.container ?: source.container,
        videoCodec = kernel.videoCodec ?: source.videoCodec,
        width = kernel.width ?: source.width,
        height = kernel.height ?: source.height,
        videoBitrate = kernel.videoBitrate ?: source.videoBitrate,
        frameRate = kernel.frameRate ?: source.frameRate,
        hdr = kernel.hdr ?: source.hdr,
        audioCodec = kernel.audioCodec ?: source.audioCodec,
        audioChannels = kernel.audioChannels ?: source.audioChannels,
        audioBitrate = kernel.audioBitrate ?: source.audioBitrate,
        audioSampleRate = kernel.audioSampleRate ?: source.audioSampleRate,
        fileSizeBytes = kernel.fileSizeBytes ?: source.fileSizeBytes,
        path = source.path ?: kernel.path,
    )
}

/** 从播放地址推断容器（Jellyfin / mpv 都可能不给容器名时的兜底） */
fun inferContainerFromUri(uri: String?): String? {
    val raw = uri?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val withoutQuery = raw.substringBefore('?').substringBefore('#')
    val extension = withoutQuery.substringAfterLast('.', "").lowercase()
    if (extension.isEmpty() || extension.length > 4) return null
    return extension
}

/**
 * 从内核的视频参数判定动态范围（纯函数，便于单测）。
 *
 * @param codecs 编码串（含 `dvhe` / `dvh1` / `dav1` 视为杜比视界）
 * @param colorTransfer Media3 `C.COLOR_TRANSFER_*`
 * @param colorSpace Media3 `C.COLOR_SPACE_*`
 * @param colorPrimaries HDR 静态元数据存在时的原色（部分内核给 BT.2020）
 * @return 「杜比视界 / HDR10 / HLG / SDR」，完全判不出来时返回 null（UI 显示「—」）
 */
fun hdrFromVideo(
    codecs: String?,
    colorTransfer: Int?,
    colorSpace: Int?,
    colorPrimaries: Int? = null,
): String? {
    val codec = codecs?.lowercase().orEmpty()
    if (
        codec.contains("dvhe") ||
            codec.contains("dvh1") ||
            codec.contains("dav1") ||
            codec.contains("dovi")
    ) {
        return "杜比视界"
    }
    // 内核完全没给色彩信息（ExoPlayer 常见）：判定不出来，返回 null 让媒体源那层来补
    if (colorTransfer == null && colorSpace == null && colorPrimaries == null && codec.isEmpty()) {
        return null
    }
    val wideColor = colorSpace == COLOR_SPACE_BT2020 || colorPrimaries == COLOR_SPACE_BT2020
    return when {
        colorTransfer == TRANSFER_ST2084 -> "HDR10"
        colorTransfer == TRANSFER_HLG -> "HLG"
        wideColor -> "HDR（BT.2020）"
        // 有明确的色彩信息且不属于上面几类 → SDR；完全没有色彩信息 → 交给媒体源那层判定
        colorTransfer != null || colorSpace != null -> "SDR"
        else -> null
    }
}

/**
 * mpv 侧的动态范围判定：读 `video-params/gamma` 与 `video-params/primaries`。
 *
 * @param gamma pq / hlg / bt.1886 / gamma2.2 …
 * @param primaries bt.2020 / bt.709 …
 * @param dolbyVision mpv 报告了 dovi 参数
 */
fun hdrFromMpv(gamma: String?, primaries: String?, dolbyVision: Boolean): String? {
    if (dolbyVision) return "杜比视界"
    val g = gamma?.lowercase().orEmpty()
    val p = primaries?.lowercase().orEmpty()
    return when {
        g.contains("pq") || g.contains("st2084") -> "HDR10"
        g.contains("hlg") -> "HLG"
        p.contains("bt.2020") || p.contains("bt2020") -> "HDR（BT.2020）"
        g.isEmpty() && p.isEmpty() -> null
        else -> "SDR"
    }
}

/**
 * Jellyfin 侧 `VideoRangeType` + `VideoDoViTitle` → 动态范围文案（纯字符串入参，便于单测）。
 *
 * 取值：SDR / HDR10 / HLG / DOVI / DOVIWithHDR10 / DOVIWithHLG / DOVIWithSDR / UNKNOWN。
 */
fun hdrFromJellyfin(range: String?, doviTitle: String?): String? {
    val value = range?.trim()?.lowercase().orEmpty()
    if (value.isEmpty() || value == "unknown") return doviTitle?.takeIf { it.isNotBlank() }
    return when {
        value.startsWith("dovi") -> "杜比视界"
        value == "hdr10" -> "HDR10"
        value == "hlg" -> "HLG"
        value == "sdr" -> "SDR"
        else -> doviTitle?.takeIf { it.isNotBlank() }
    }
}

/** 声道布局 → 声道数（stereo=2 / 5.1=6 / 7.1=8）；解析不了返回 null */
fun channelsFromLayout(layout: String?): Int? {
    val raw = layout?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
    raw.toIntOrNull()?.let {
        return it.takeIf { value -> value > 0 }
    }
    when (raw) {
        "mono" -> return 1
        "stereo" -> return 2
        "quad" -> return 4
    }
    // "5.1" / "5.1(side)" / "7.1" 这类小数写法：整数位 + 1（.1 = LFE）
    val major = raw.substringBefore('.').substringBefore('(').trim().toIntOrNull()
    if (major != null && major > 0) {
        return if (raw.contains('.')) major + 1 else major
    }
    return null
}
