package com.zhangwenkang.cinefin.repository

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import org.jellyfin.sdk.model.api.CodecProfile
import org.jellyfin.sdk.model.api.CodecType
import org.jellyfin.sdk.model.api.ProfileCondition
import org.jellyfin.sdk.model.api.ProfileConditionType
import org.jellyfin.sdk.model.api.ProfileConditionValue
import timber.log.Timber

/**
 * 影阁：按**设备真实解码能力**生成 Jellyfin 的 [CodecProfile]。
 *
 * 为什么需要它：
 * 之前 App 用的是「直连一切」（`codecProfiles = emptyList()`）。而动漫片源大量是 10-bit 编码
 * （HEVC Main 10、H.264 High 10 / Hi10P），绝大多数设备的硬件解码器**不支持 10-bit**，
 * 却又不会报错：解码器能配上、进度照走、有声音，**画面全黑**。
 * 于是表现为「视频无法播放」，而且换多少部 10-bit 片子都一样。
 *
 * 做法：读 [MediaCodecList]，看设备到底支持哪些 profile；不支持的明确写进 CodecProfile，
 * 服务器就会把这类片源转成 8-bit H.264 再发过来（转码流能被设备正常解码）。
 * 支持 10-bit 的设备（例如较新的骁龙机型）不会命中限制，仍然保持直连、不吃服务器算力。
 */
object DeviceCodecProfiles {

    fun build(): List<CodecProfile> =
        listOfNotNull(
            h264Profile(),
            hevcProfile(),
            vp9Profile(),
        )

    /** H.264：不支持 High 10 / High 4:4:4 Predictive 时声明出来（Hi10P 片源因此走转码） */
    private fun h264Profile(): CodecProfile? {
        val caps = capabilitiesFor(MediaFormat.MIMETYPE_VIDEO_AVC)
        val high10 = MediaCodecInfo.CodecProfileLevel.AVCProfileHigh10
        val high444 = MediaCodecInfo.CodecProfileLevel.AVCProfileHigh444
        if (caps.supports(high10) && caps.supports(high444)) return null
        return videoProfile(
            codec = "h264",
            unsupportedProfiles =
                buildList {
                    if (!caps.supports(high10)) add("High 10")
                    if (!caps.supports(high444)) add("High 4:4:4 Predictive")
                },
            fullyUnsupported = !caps.hasDecoder,
        )
    }

    /** HEVC：不支持 Main 10 时声明出来（10-bit HEVC 是动漫片源最常见的格式） */
    private fun hevcProfile(): CodecProfile? {
        val caps = capabilitiesFor(MediaFormat.MIMETYPE_VIDEO_HEVC)
        val main10 = MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10
        val main10Hdr = MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10
        if (caps.supports(main10) || caps.supports(main10Hdr)) return null
        return videoProfile(
            codec = "hevc",
            unsupportedProfiles = listOf("Main 10"),
            fullyUnsupported = !caps.hasDecoder,
        )
    }

    /** VP9：只支持 Profile 0 的设备，10-bit（Profile 2）同样要转码 */
    private fun vp9Profile(): CodecProfile? {
        val caps = capabilitiesFor(MediaFormat.MIMETYPE_VIDEO_VP9)
        val profile2 = MediaCodecInfo.CodecProfileLevel.VP9Profile2
        if (caps.supports(profile2)) return null
        return videoProfile(
            codec = "vp9",
            unsupportedProfiles = listOf("Profile 2"),
            fullyUnsupported = !caps.hasDecoder,
        )
    }

    private fun videoProfile(
        codec: String,
        unsupportedProfiles: List<String>,
        fullyUnsupported: Boolean = false,
    ): CodecProfile =
        CodecProfile(
            type = CodecType.VIDEO,
            codec = codec,
            conditions =
                if (fullyUnsupported) {
                    // 设备没有这类解码器：给一个永远不成立的条件，服务器必然转码
                    listOf(
                        ProfileCondition(
                            condition = ProfileConditionType.EQUALS,
                            property = ProfileConditionValue.VIDEO_PROFILE,
                            value = "None",
                            isRequired = false,
                        )
                    )
                } else {
                    unsupportedProfiles.map { profile ->
                        ProfileCondition(
                            condition = ProfileConditionType.NOT_EQUALS,
                            property = ProfileConditionValue.VIDEO_PROFILE,
                            value = profile,
                            isRequired = false,
                        )
                    }
                },
            // 空列表 = 对所有该编码的片源都生效
            applyConditions = emptyList(),
        )

    private data class CodecSupport(val profiles: Set<Int>, val hasDecoder: Boolean) {
        fun supports(profile: Int): Boolean = hasDecoder && profiles.contains(profile)
    }

    private fun capabilitiesFor(mime: String): CodecSupport {
        val profiles = mutableSetOf<Int>()
        var hasDecoder = false
        runCatching {
                val codecList = MediaCodecList(MediaCodecList.ALL_CODECS)
                for (codecInfo in codecList.codecInfos) {
                    if (codecInfo.isEncoder) continue
                    if (!codecInfo.supportedTypes.contains(mime)) continue
                    val caps =
                        runCatching { codecInfo.getCapabilitiesForType(mime) }.getOrNull() ?: continue
                    hasDecoder = true
                    caps.profileLevels?.forEach { profileLevel -> profiles.add(profileLevel.profile) }
                }
            }
            .onFailure { Timber.w(it, "读取设备解码能力失败: $mime") }

        if (!hasDecoder) {
            // 设备完全没有这类解码器：让服务器一律转码
            Timber.i("设备无 $mime 解码器，将请求服务器转码")
        }
        return CodecSupport(profiles, hasDecoder)
    }
}
