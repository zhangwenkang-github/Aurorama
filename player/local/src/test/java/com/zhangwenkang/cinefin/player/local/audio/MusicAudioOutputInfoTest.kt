package com.zhangwenkang.cinefin.player.local.audio

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** W5-T6 · MUSIC-34：Exo `Format` → 输出信息快照的纯映射。 */
class MusicAudioOutputInfoTest {

    @Test
    fun `format maps codec container sample rate channels and bitrate`() {
        val format =
            Format.Builder()
                .setSampleMimeType("audio/flac")
                .setCodecs("flac")
                .setContainerMimeType("audio/flac")
                .setSampleRate(48_000)
                .setChannelCount(2)
                .setAverageBitrate(1_411_200)
                .build()
        val info = audioOutputInfo(format)
        assertEquals("flac", info?.codec)
        assertEquals("audio/flac", info?.container)
        assertEquals(48_000, info?.sampleRate)
        assertEquals(2, info?.channelCount)
        assertEquals(1_411_200, info?.bitrate)
    }

    @Test
    fun `unknown format fields stay null instead of zero`() {
        val format = Format.Builder().setSampleMimeType("audio/mp4").build()
        val info = audioOutputInfo(format)
        assertEquals("audio/mp4", info?.codec)
        assertNull(info?.container)
        assertNull(info?.sampleRate)
        assertNull(info?.channelCount)
        assertNull(info?.bitrate)
        assertNull(audioOutputInfo(null))
    }

    @Test
    fun `codecs wins over sample mime type`() {
        val format =
            Format.Builder()
                .setSampleMimeType("audio/mp4")
                .setCodecs("mp4a.40.2")
                .setSampleRate(44_100)
                .setChannelCount(2)
                .build()
        assertEquals("mp4a.40.2", audioOutputInfo(format)?.codec)
    }

    @Test
    fun `mediaId 一致时返回 Exo 输出读数`() {
        val itemId = UUID.nameUUIDFromBytes("track-a".toByteArray())

        val info = audioOutputInfoForItem(itemId, itemId.toString(), audioTracks())

        assertEquals("flac", info?.codec)
        assertEquals(48_000, info?.sampleRate)
        assertEquals(2, info?.channelCount)
    }

    @Test
    fun `mediaId 不一致时返回 null 不把新曲目读数混排进旧曲目面板`() {
        val panelItemId = UUID.nameUUIDFromBytes("track-a".toByteArray())
        val exoItemId = UUID.nameUUIDFromBytes("track-b".toByteArray())

        assertNull(audioOutputInfoForItem(panelItemId, exoItemId.toString(), audioTracks()))
    }

    @Test
    fun `播放器没有当前 mediaItem 时不返回读数`() {
        assertNull(audioOutputInfoForItem(UUID.randomUUID(), null, audioTracks()))
    }

    private fun audioTracks(): Tracks {
        val format =
            Format.Builder()
                .setSampleMimeType("audio/flac")
                .setCodecs("flac")
                .setSampleRate(48_000)
                .setChannelCount(2)
                .build()
        return Tracks(
            listOf(
                Tracks.Group(
                    TrackGroup(format),
                    /* adaptiveSupported = */ false,
                    intArrayOf(C.FORMAT_HANDLED),
                    booleanArrayOf(true),
                )
            )
        )
    }
}
