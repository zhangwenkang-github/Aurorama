package com.zhangwenkang.cinefin.music.presentation

import com.zhangwenkang.cinefin.player.core.domain.models.TrackSource
import com.zhangwenkang.cinefin.player.core.domain.models.TrackSourceType
import com.zhangwenkang.cinefin.player.local.audio.MusicAudioOutputInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** W5-T6 · MUSIC-34：音频信息面板的数据组装与格式化。 */
class MusicAudioInfoTest {

    private val serverSource =
        TrackSource(
            id = "item",
            sourceType = TrackSourceType.SERVER,
            streamUrl = "https://example/audio",
            bitrate = 1_411_200,
            codec = "flac",
            container = "flac",
            sampleRate = 44_100,
            bitDepth = 16,
            channelCount = 2,
            isTranscoded = true,
        )

    private val output =
        MusicAudioOutputInfo(
            codec = "mp4a.40.2",
            container = "audio/mp4",
            sampleRate = 48_000,
            channelCount = 2,
            bitrate = 320_000,
        )

    @Test
    fun `builds source and runtime output blocks`() {
        val info = buildMusicAudioInfo("歌名", serverSource, output, playing = true)
        assertEquals("歌名", info.title)
        assertEquals("FLAC / flac", info.sourceFormat)
        assertEquals("44.1 kHz", info.sourceSampleRate)
        assertEquals("16 bit", info.sourceBitDepth)
        assertEquals("2 声道（立体声）", info.sourceChannels)
        assertEquals("MP4A.40.2 / mp4", info.outputFormat)
        assertEquals("48 kHz", info.outputSampleRate)
        assertEquals("2 声道（立体声）", info.outputChannels)
        assertEquals("是（服务端转码流）", info.transcode)
        assertEquals("1411 kbps", info.bitrate)
        assertTrue(info.outputKnown)
    }

    @Test
    fun `non playing item has no output or transcode claim`() {
        val info = buildMusicAudioInfo("歌名", serverSource, null, playing = false)
        assertEquals("—", info.outputFormat)
        assertEquals("—（未播放）", info.transcode)
        assertFalse(info.outputKnown)
    }

    @Test
    fun `direct play and unknown source are distinguished`() {
        assertEquals(
            "否（直连）",
            buildMusicAudioInfo("x", serverSource.copy(isTranscoded = false), output, true)
                .transcode,
        )
        assertEquals(
            "—（来源未知）",
            buildMusicAudioInfo("x", source = null, output = null, playing = true).transcode,
        )
    }

    @Test
    fun `formatting degrades to dash for missing values`() {
        assertEquals("—", formatSampleRate(null))
        assertEquals("—", formatSampleRate(0))
        assertEquals("44.1 kHz", formatSampleRate(44_100))
        assertEquals("96 kHz", formatSampleRate(96_000))
        assertEquals("—", formatBitDepth(null))
        assertEquals("24 bit", formatBitDepth(24))
        assertEquals("—", formatChannelCount(null))
        assertEquals("1 声道（单声道）", formatChannelCount(1))
        assertEquals("6 声道", formatChannelCount(6))
        assertEquals("—", formatBitrate(null))
        assertEquals("128 kbps", formatBitrate(128_000))
        assertEquals("—", formatCodecContainer(null, null))
        assertEquals("AAC", formatCodecContainer("aac", null))
        assertEquals("mp4", formatCodecContainer(null, "video/mp4"))
    }

    @Test
    fun `bit perfect note is fixed copy`() {
        assertEquals("非 bit-perfect 输出（Android 音频混音）", MUSIC_BIT_PERFECT_NOTE)
    }
}
