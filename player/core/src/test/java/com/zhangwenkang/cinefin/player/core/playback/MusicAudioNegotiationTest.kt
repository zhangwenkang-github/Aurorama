package com.zhangwenkang.cinefin.player.core.playback

import org.junit.Assert.assertEquals
import org.junit.Test

/** MUSIC-03 协商矩阵：直连 / 转码的判定与请求目标码率。 */
class MusicAudioNegotiationTest {

    private val phoneCodecs = setOf(AudioCodec.MP3, AudioCodec.AAC, AudioCodec.FLAC)

    @Test
    fun `原始档禁止转码时码率再高也直连`() {
        val decision =
            MusicAudioNegotiation.decide(
                sourceCodec = "flac",
                sourceBitrateBps = 1_411_200,
                supportedAudioCodecs = phoneCodecs,
                bitrateCeilingBps = MusicAudioNegotiation.MAX_OUTPUT_BITRATE_BPS.toLong(),
                allowTranscoding = false,
            )
        assertEquals(MusicAudioDecision.DirectPlay, decision)
    }

    @Test
    fun `自动档同编码同码率直连`() {
        val decision =
            MusicAudioNegotiation.decide(
                sourceCodec = "flac",
                sourceBitrateBps = 900_000,
                supportedAudioCodecs = phoneCodecs,
                bitrateCeilingBps = 0L,
                allowTranscoding = true,
            )
        assertEquals(MusicAudioDecision.DirectPlay, decision)
    }

    @Test
    fun `码率超档位上限时按档位码率转码`() {
        val decision =
            MusicAudioNegotiation.decide(
                sourceCodec = "flac",
                sourceBitrateBps = 1_411_200,
                supportedAudioCodecs = phoneCodecs,
                bitrateCeilingBps = 192_000L,
                allowTranscoding = true,
            )
        assertEquals(
            MusicAudioDecision.Transcode(
                audioCodec = MusicAudioNegotiation.TRANSCODE_CODEC,
                audioBitRateBps = 192_000,
                reason = MusicAudioTranscodeReason.BITRATE_ABOVE_CEILING,
            ),
            decision,
        )
    }

    @Test
    fun `码率不超上限时保持直连`() {
        val decision =
            MusicAudioNegotiation.decide(
                sourceCodec = "mp3",
                sourceBitrateBps = 320_000,
                supportedAudioCodecs = phoneCodecs,
                bitrateCeilingBps = 320_000L,
                allowTranscoding = true,
            )
        assertEquals(MusicAudioDecision.DirectPlay, decision)
    }

    @Test
    fun `本机解不了的编码在自动档转码到源码率并夹在上限内`() {
        val decision =
            MusicAudioNegotiation.decide(
                sourceCodec = "dsd_lsbf",
                sourceBitrateBps = 5_644_800,
                supportedAudioCodecs = phoneCodecs,
                bitrateCeilingBps = 0L,
                allowTranscoding = true,
            )
        assertEquals(
            MusicAudioDecision.Transcode(
                audioCodec = MusicAudioNegotiation.TRANSCODE_CODEC,
                audioBitRateBps = MusicAudioNegotiation.MAX_OUTPUT_BITRATE_BPS,
                reason = MusicAudioTranscodeReason.CODEC_UNSUPPORTED,
            ),
            decision,
        )
    }

    @Test
    fun `源码率未知时不因编码不支持而转出低码率`() {
        val decision =
            MusicAudioNegotiation.decide(
                sourceCodec = "alac",
                sourceBitrateBps = null,
                supportedAudioCodecs = phoneCodecs,
                bitrateCeilingBps = 0L,
                allowTranscoding = true,
            )
        assertEquals(
            MusicAudioDecision.Transcode(
                audioCodec = MusicAudioNegotiation.TRANSCODE_CODEC,
                audioBitRateBps = MusicAudioNegotiation.MAX_OUTPUT_BITRATE_BPS,
                reason = MusicAudioTranscodeReason.CODEC_UNSUPPORTED,
            ),
            decision,
        )
    }

    @Test
    fun `探测失败的空能力集不激进转码`() {
        val decision =
            MusicAudioNegotiation.decide(
                sourceCodec = "alac",
                sourceBitrateBps = 900_000,
                supportedAudioCodecs = emptySet(),
                bitrateCeilingBps = 0L,
                allowTranscoding = true,
            )
        assertEquals(MusicAudioDecision.DirectPlay, decision)
    }

    @Test
    fun `未知编码名交给服务端判断`() {
        val decision =
            MusicAudioNegotiation.decide(
                sourceCodec = "ape",
                sourceBitrateBps = 800_000,
                supportedAudioCodecs = phoneCodecs,
                bitrateCeilingBps = 0L,
                allowTranscoding = true,
            )
        assertEquals(MusicAudioDecision.DirectPlay, decision)
    }

    @Test
    fun `直连容器表只含可解码编码且顺序稳定去重`() {
        assertEquals(
            listOf("mp3", "aac", "flac", "m4a"),
            MusicAudioNegotiation.directPlayContainers(
                setOf(AudioCodec.ALAC, AudioCodec.FLAC, AudioCodec.AAC, AudioCodec.MP3)
            ),
        )
        assertEquals(emptyList<String>(), MusicAudioNegotiation.directPlayContainers(emptySet()))
        // DSD 无直连容器：能力表里有它也不会出现在 Container 参数里
        assertEquals(
            listOf("mp3"),
            MusicAudioNegotiation.directPlayContainers(setOf(AudioCodec.MP3, AudioCodec.DSD)),
        )
    }

    @Test
    fun `空快照不声称支持任何音乐编码`() {
        val unknown = DevicePlaybackCapabilities.Unknown
        assertEquals(emptySet<AudioCodec>(), unknown.supportedAudioCodecs())
        assertEquals(false, unknown.supportsAudio(AudioCodec.MP3))
    }
}
