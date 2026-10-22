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
