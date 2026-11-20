package com.zhangwenkang.cinefin.player.local.capabilities

import android.view.Display
import com.zhangwenkang.cinefin.player.core.playback.AudioCodec
import com.zhangwenkang.cinefin.player.core.playback.CodecDecodeTier
import com.zhangwenkang.cinefin.player.core.playback.HdrType
import com.zhangwenkang.cinefin.player.core.playback.VideoCodec
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 能力探测的纯函数部分（W4-T3b / VIDEO-08）。
 *
 * 真实探测依赖设备（`Display.getHdrCapabilities()` / `MediaCodecList`），JVM 单测只钉映射规则； 设备侧结果由 K60 真机解码面板取证。
 */
class DeviceCapabilitiesProbeTest {

    @Test
    fun hdrTypes_mapDisplayConstantsToModel() {
        val types =
            DeviceCapabilitiesProbe.hdrTypesOf(
                intArrayOf(
                    Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION,
                    Display.HdrCapabilities.HDR_TYPE_HDR10,
                    Display.HdrCapabilities.HDR_TYPE_HLG,
                    // HDR_TYPE_HDR10_PLUS（API 29 常量，值为 4）
                    4,
                )
            )
        assertEquals(
            setOf(HdrType.DOLBY_VISION, HdrType.HDR10, HdrType.HLG, HdrType.HDR10_PLUS),
            types,
        )
    }

    @Test
    fun hdrTypes_ignoreUnknownAndDuplicateValues() {
        assertEquals(emptySet<HdrType>(), DeviceCapabilitiesProbe.hdrTypesOf(intArrayOf()))
        // 未知取值（未来新增的 HDR 类型）不能伪造支持
        assertEquals(
            setOf(HdrType.HDR10),
            DeviceCapabilitiesProbe.hdrTypesOf(
                intArrayOf(
                    Display.HdrCapabilities.HDR_TYPE_HDR10,
                    Display.HdrCapabilities.HDR_TYPE_HDR10,
                    99,
                )
            ),
        )
    }

    @Test
    fun decodeTier_prefersHardwareThenSoftwareThenNone() {
        val hardware = setOf("video/hevc", "video/av01")
        val all = setOf("video/hevc", "video/av01", "video/avc", "video/x-vnd.on2.vp9")

        assertEquals(
            CodecDecodeTier.HARDWARE,
            DeviceCapabilitiesProbe.decodeTier(hardware, all, VideoCodec.HEVC),
        )
        // 只有软解解码器声明了该 MIME：档位是软解，不是「不支持」
        assertEquals(
            CodecDecodeTier.SOFTWARE,
            DeviceCapabilitiesProbe.decodeTier(hardware, all, VideoCodec.H264),
        )
        // 本机没有任何解码器声明该 MIME
        assertEquals(
            CodecDecodeTier.NONE,
            DeviceCapabilitiesProbe.decodeTier(hardware, all, VideoCodec.DOLBY_VISION),
        )
    }

    @Test
    fun decodeTier_hardwareWinsEvenWhenSoftwareAlsoPresent() {
        // 硬解与软解解码器同时存在（K60 上 HEVC 常见）：档位按硬解报
        assertEquals(
            CodecDecodeTier.HARDWARE,
            DeviceCapabilitiesProbe.decodeTier(
                hardwareMimeTypes = setOf("video/hevc"),
                allMimeTypes = setOf("video/hevc", "video/avc"),
                codec = VideoCodec.HEVC,
            ),
        )
    }

    @Test
    fun everyCodecHasItsOwnMimeTypes() {
        // 探测按 MIME 对齐，任何一个编码漏配 MIME 都会永远报「不支持」
        VideoCodec.entries.forEach { codec ->
            assertEquals("$codec 缺 MIME 映射", true, codec.mimeTypes.isNotEmpty())
            assertEquals(
                "$codec 的 MIME 不应重复",
                codec.mimeTypes.size,
                codec.mimeTypes.distinct().size,
            )
        }
    }

    @Test
    fun audioDecodeTier_mapsAudioMimesLikeVideo() {
        val hardware = setOf("audio/mpeg", "audio/flac")
        val all = hardware + setOf("audio/mp4a-latm", "audio/opus")

        assertEquals(
            CodecDecodeTier.HARDWARE,
            DeviceCapabilitiesProbe.audioDecodeTier(hardware, all, AudioCodec.FLAC),
        )
        assertEquals(
            CodecDecodeTier.SOFTWARE,
            DeviceCapabilitiesProbe.audioDecodeTier(hardware, all, AudioCodec.AAC),
        )
        assertEquals(
            CodecDecodeTier.NONE,
            DeviceCapabilitiesProbe.audioDecodeTier(hardware, all, AudioCodec.VORBIS),
        )
    }

    @Test
    fun audioDecodeTier_dsdHasNoAndroidDecoder() {
        // DSD 没有解码器 MIME：即使能力集里塞满编码，也必须报「不支持」（协商时会被转码）
        assertEquals(
            CodecDecodeTier.NONE,
            DeviceCapabilitiesProbe.audioDecodeTier(
                hardwareMimeTypes = setOf("audio/raw", "audio/dsd"),
                allMimeTypes = setOf("audio/raw", "audio/dsd"),
                codec = AudioCodec.DSD,
            ),
        )
    }

    @Test
    fun audioCodecs_haveStableJellyfinNamesAndContainers() {
        AudioCodec.entries.forEach { codec ->
            assertEquals("$codec 缺 Jellyfin 编码名", true, codec.jellyfinNames.isNotEmpty())
            assertEquals(
                "$codec 的 Jellyfin 编码名不应重复",
                codec.jellyfinNames.size,
                codec.jellyfinNames.distinct().size,
            )
            assertEquals(
                "$codec 的 Jellyfin 编码名应小写",
                codec.jellyfinNames,
                codec.jellyfinNames.map(String::lowercase),
            )
        }
        assertEquals(AudioCodec.FLAC, AudioCodec.fromJellyfinName("FLAC"))
        assertEquals(AudioCodec.PCM, AudioCodec.fromJellyfinName("pcm_s24le"))
        assertEquals(null, AudioCodec.fromJellyfinName(""))
        assertEquals(null, AudioCodec.fromJellyfinName(null))
    }
}
