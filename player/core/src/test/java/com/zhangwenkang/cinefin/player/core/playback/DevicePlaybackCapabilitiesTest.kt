package com.zhangwenkang.cinefin.player.core.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 设备能力快照的纯函数回归（W4-T3b / VIDEO-08）。
 *
 * 重点钉两件事：① 四个 HDR 布尔只从 [HdrType] 集合派生，不另存一套可能漂移的字段； ②
 * 片源比对入口（[DevicePlaybackCapabilities.missingHdrTypes]）的「不支持子集」语义，T4/T7 会直接调用。
 */
class DevicePlaybackCapabilitiesTest {

    private val k60Like =
        DevicePlaybackCapabilities(
            hdrTypes = setOf(HdrType.HDR10, HdrType.HDR10_PLUS, HdrType.HLG),
            codecs =
                listOf(
                    CodecCapability(VideoCodec.H264, CodecDecodeTier.HARDWARE),
                    CodecCapability(VideoCodec.HEVC, CodecDecodeTier.HARDWARE),
                    CodecCapability(VideoCodec.AV1, CodecDecodeTier.HARDWARE),
                    CodecCapability(VideoCodec.VP9, CodecDecodeTier.SOFTWARE),
                ),
            mpvHwdecTiers = listOf("mediacodec", "no"),
        )

    @Test
    fun hdrBooleans_deriveFromSupportedSet() {
        assertTrue(k60Like.hdr10)
        assertTrue(k60Like.hdr10Plus)
        assertTrue(k60Like.hlg)
        assertFalse("Dolby Vision 不在快照里就不能说支持", k60Like.dolbyVision)
        assertTrue(k60Like.supportsHdr(HdrType.HDR10_PLUS))
        assertFalse(k60Like.supportsHdr(HdrType.DOLBY_VISION))
    }

    @Test
    fun unknownSnapshot_claimsNothing() {
        val unknown = DevicePlaybackCapabilities.Unknown
        assertFalse(unknown.hdr10)
        assertFalse(unknown.hdr10Plus)
        assertFalse(unknown.dolbyVision)
        assertFalse(unknown.hlg)
        assertTrue(unknown.mpvHwdecTiers.isEmpty())
        assertEquals(CodecDecodeTier.NONE, unknown.codecTier(VideoCodec.HEVC))
    }

    @Test
    fun codecTier_missingCodecReportsNone() {
        assertEquals(CodecDecodeTier.HARDWARE, k60Like.codecTier(VideoCodec.HEVC))
        assertEquals(CodecDecodeTier.SOFTWARE, k60Like.codecTier(VideoCodec.VP9))
        assertEquals(CodecDecodeTier.NONE, k60Like.codecTier(VideoCodec.MPEG4))
    }

    @Test
    fun sourceHdrComparison_reportsMissingTypes() {
        // SDR 片源：任何设备都算全支持，不触发提示
        assertTrue(k60Like.supportsAll(emptySet()))
        assertEquals(emptySet<HdrType>(), k60Like.missingHdrTypes(emptySet()))

        assertTrue(k60Like.supportsAll(setOf(HdrType.HDR10)))
        assertEquals(
            setOf(HdrType.DOLBY_VISION),
            k60Like.missingHdrTypes(setOf(HdrType.HDR10, HdrType.DOLBY_VISION)),
        )
        assertFalse(k60Like.supportsAll(setOf(HdrType.DOLBY_VISION)))
    }
}
