package com.zhangwenkang.cinefin.player.local.presentation

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlayerKernelMediaInfoTest {

    @Test
    fun `mpv 原生属性纯映射保留当前流字段与未知语义`() {
        val info =
            mpvMediaInfo(
                container = "matroska",
                videoCodec = "hevc",
                width = 3840,
                height = 2160,
                videoBitrate = 18_000_000.0,
                frameRate = 23.976,
                gamma = "pq",
                primaries = "bt.2020",
                dolbyVision = false,
                audioCodec = "eac3",
                audioChannels = 6,
                audioSampleRate = 48_000,
                audioBitrate = 640_000.0,
                fileSizeBytes = 1024.0,
            )

        assertEquals(3840, info.width)
        assertEquals(2160, info.height)
        assertEquals(18_000_000, info.videoBitrate)
        assertEquals(23.976f, info.frameRate!!, 0.001f)
        assertEquals("HDR10", info.hdr)
        assertEquals(6, info.audioChannels)
        assertEquals(48_000, info.audioSampleRate)

        val unknown =
            mpvMediaInfo(
                container = null,
                videoCodec = null,
                width = 0,
                height = -1,
                videoBitrate = 0.0,
                frameRate = 0.0,
                gamma = null,
                primaries = null,
                dolbyVision = false,
                audioCodec = null,
                audioChannels = 0,
                audioSampleRate = 0,
                audioBitrate = 0.0,
                fileSizeBytes = 0.0,
            )
        assertNull(unknown.width)
        assertNull(unknown.height)
        assertNull(unknown.videoBitrate)
        assertNull(unknown.frameRate)
    }

    @Test
    fun `视频读取已选中的第二组而不是首组`() {
        val unselected =
            group(
                Format.Builder()
                    .setSampleMimeType(MimeTypes.VIDEO_H264)
                    .setWidth(640)
                    .setHeight(360)
                    .setAverageBitrate(400_000)
                    .setCodecs("avc1")
                    .build(),
                selected = false,
            )
        val selected =
            group(
                Format.Builder()
                    .setSampleMimeType(MimeTypes.VIDEO_H265)
                    .setWidth(1920)
                    .setHeight(1080)
                    .setAverageBitrate(8_000_000)
                    .setFrameRate(23.976f)
                    .setCodecs("hvc1")
                    .build(),
                selected = true,
            )

        val info = exoPlayerMediaInfo(Tracks(listOf(unselected, selected)))!!

        assertEquals(1920, info.width)
        assertEquals(1080, info.height)
        assertEquals(8_000_000, info.videoBitrate)
        assertEquals(23.976f, info.frameRate!!, 0.001f)
        assertEquals("hvc1", info.videoCodec)
    }

    @Test
    fun `同组内只读已选中的轨道`() {
        val formats =
            TrackGroup(
                Format.Builder()
                    .setSampleMimeType(MimeTypes.VIDEO_H264)
                    .setWidth(1280)
                    .setHeight(720)
                    .setCodecs("avc1")
                    .build(),
                Format.Builder()
                    .setSampleMimeType(MimeTypes.VIDEO_H265)
                    .setWidth(3840)
                    .setHeight(2160)
                    .setCodecs("hvc1")
                    .build(),
            )
        val group =
            Tracks.Group(
                formats,
                true,
                intArrayOf(C.FORMAT_HANDLED, C.FORMAT_HANDLED),
                booleanArrayOf(false, true),
            )

        val info = exoPlayerMediaInfo(Tracks(listOf(group)))!!

        assertEquals(3840, info.width)
        assertEquals(2160, info.height)
        assertEquals("hvc1", info.videoCodec)
    }

    @Test
    fun `没有任何选中轨时当前流未知`() {
        val group =
            group(
                Format.Builder()
                    .setSampleMimeType(MimeTypes.VIDEO_H264)
                    .setWidth(1920)
                    .setHeight(1080)
                    .setCodecs("avc1")
                    .build(),
                selected = false,
            )

        assertNull(exoPlayerMediaInfo(Tracks(listOf(group))))
    }

    private fun group(format: Format, selected: Boolean): Tracks.Group =
        Tracks.Group(
            TrackGroup(format),
            true,
            intArrayOf(C.FORMAT_HANDLED),
            booleanArrayOf(selected),
        )
}
