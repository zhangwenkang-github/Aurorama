package com.zhangwenkang.cinefin.player.local.capabilities

import android.content.Context
import android.hardware.display.DisplayManager
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import android.view.Display
import com.zhangwenkang.cinefin.player.core.playback.CodecCapability
import com.zhangwenkang.cinefin.player.core.playback.CodecDecodeTier
import com.zhangwenkang.cinefin.player.core.playback.DevicePlaybackCapabilities
import com.zhangwenkang.cinefin.player.core.playback.HdrType
import com.zhangwenkang.cinefin.player.core.playback.VideoCodec
import com.zhangwenkang.cinefin.player.local.domain.PlayerDecodeMode
import timber.log.Timber

/**
 * 设备播放能力探测（W4-T3b / VIDEO-08）。
 *
 * 三个来源：
 * - **HDR**：`Display.getHdrCapabilities().supportedHdrTypes`（API 24+）——这是显示器/面板声明的能力，不是解码器能力；
 * - **codec 硬解档位**：`MediaCodecList(REGULAR_CODECS)` 枚举视频解码器，按 MIME 判定硬解 / 软解 / 不支持；
 * - **mpv `hwdec` 档**：走既有 `PlayerDecodeMode.mpvHwDec` 的配置值域（App 只下发 `mediacodec` / `no`）， 不实例化 mpv
 *   去枚举全部档位——枚举需要真实 mpv 实例，代价远大于面板收益。
 *
 * 结果按进程缓存：[probe] 只探测一次，解码面板每次打开读缓存，不重探。
 */
object DeviceCapabilitiesProbe {
    @Volatile private var cached: DevicePlaybackCapabilities? = null

    /** 进程级缓存入口：同一进程内第一次调用真正探测，之后直接返回快照。 */
    fun probe(context: Context): DevicePlaybackCapabilities {
        cached?.let {
            return it
        }
        return synchronized(this) {
            cached
                ?: build(context).also { snapshot ->
                    cached = snapshot
                    Timber.i(
                        "设备能力探测: hdr=%s codecs=%s mpvHwdec=%s",
                        snapshot.hdrTypes,
                        snapshot.codecs.joinToString(",") { "${it.codec}=${it.tier}" },
                        snapshot.mpvHwdecTiers,
                    )
                }
        }
    }

    private fun build(context: Context): DevicePlaybackCapabilities = runCatching {
        DevicePlaybackCapabilities(
            hdrTypes = hdrTypes(context),
            codecs = codecCapabilities(),
            mpvHwdecTiers = PlayerDecodeMode.MPV_HWDEC_TIERS,
        )
    }
        .onFailure { Timber.w(it, "设备能力探测失败，按空快照处理") }
        .getOrDefault(DevicePlaybackCapabilities.Unknown)

    private fun hdrTypes(context: Context): Set<HdrType> {
        val display =
            context
                .getSystemService(DisplayManager::class.java)
                ?.getDisplay(Display.DEFAULT_DISPLAY) ?: return emptySet()
        val supported = runCatching { display.hdrCapabilities?.supportedHdrTypes }.getOrNull()
        return supported?.let(::hdrTypesOf) ?: emptySet()
    }

    /**
     * `Display.HdrCapabilities.supportedHdrTypes` → 模型类型（纯函数，单测覆盖）。
     *
     * 未知取值（未来新增的 HDR 类型）直接忽略：探测宁可少报，不可错报支持。
     */
    internal fun hdrTypesOf(supportedHdrTypes: IntArray): Set<HdrType> {
        val result = mutableSetOf<HdrType>()
        supportedHdrTypes.forEach { type ->
            when (type) {
                Display.HdrCapabilities.HDR_TYPE_HDR10 -> result += HdrType.HDR10
                Display.HdrCapabilities.HDR_TYPE_HLG -> result += HdrType.HLG
                Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION -> result += HdrType.DOLBY_VISION
                // HDR_TYPE_HDR10_PLUS 是 API 29 常量（值固定为 4）；用字面量避免向 minSdk 28 引入 API 依赖
                HDR_TYPE_HDR10_PLUS -> result += HdrType.HDR10_PLUS
            }
        }
        return result
    }

    private fun codecCapabilities(): List<CodecCapability> {
        val decoders = runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { !it.isEncoder }
        }
            .getOrDefault(emptyList())
        val allMimeTypes = mutableSetOf<String>()
        val hardwareMimeTypes = mutableSetOf<String>()
        decoders.forEach { info ->
            val hardware = isHardwareAccelerated(info)
            info.supportedTypes
                .filter { it.startsWith("video/") }
                .forEach { mime ->
                    allMimeTypes += mime
                    if (hardware) hardwareMimeTypes += mime
                }
        }
        return VideoCodec.entries.map { codec ->
            CodecCapability(codec, decodeTier(hardwareMimeTypes, allMimeTypes, codec))
        }
    }

    /**
     * 编码的硬解档位（纯函数，单测覆盖）。
     *
     * @param hardwareMimeTypes 至少有一个硬解解码器声明的 MIME 集合
     * @param allMimeTypes 所有解码器（含软解）声明的 MIME 集合
     */
    internal fun decodeTier(
        hardwareMimeTypes: Set<String>,
        allMimeTypes: Set<String>,
        codec: VideoCodec,
    ): CodecDecodeTier {
        val mimeTypes = codec.mimeTypes
        return when {
            mimeTypes.any { it in hardwareMimeTypes } -> CodecDecodeTier.HARDWARE
            mimeTypes.any { it in allMimeTypes } -> CodecDecodeTier.SOFTWARE
            else -> CodecDecodeTier.NONE
        }
    }

    private fun isHardwareAccelerated(info: MediaCodecInfo): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            info.isHardwareAccelerated
        } else {
            // API 28：没有 isHardwareAccelerated，按 AOSP 的软件解码器命名约定剔除 google / c2.android 前缀
            !info.name.startsWith("OMX.google.") && !info.name.startsWith("c2.android.")
        }
}

/** [VideoCodec] 对应的容器 MIME；探测按 MIME 与 `MediaCodecList` 对齐。 */
internal val VideoCodec.mimeTypes: List<String>
    get() =
        when (this) {
            VideoCodec.H264 -> listOf("video/avc")
            VideoCodec.HEVC -> listOf("video/hevc")
            VideoCodec.VP9 -> listOf("video/x-vnd.on2.vp9")
            VideoCodec.AV1 -> listOf("video/av01")
            VideoCodec.MPEG4 -> listOf("video/mp4v-es")
            VideoCodec.DOLBY_VISION -> listOf("video/dolby-vision")
        }

/** `Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS`（API 29 引入）的取值。 */
private const val HDR_TYPE_HDR10_PLUS = 4
