package com.zhangwenkang.cinefin.film.domain

import com.zhangwenkang.cinefin.models.FindroidMediaStream
import com.zhangwenkang.cinefin.models.FindroidSource
import com.zhangwenkang.cinefin.models.FindroidSourceType
import kotlinx.coroutines.runBlocking
import org.jellyfin.sdk.model.api.MediaStreamType
import org.junit.Assert.assertEquals
import org.junit.Test

class VideoMetadataParserTest {

    @Test
    fun `缺尺寸的视频轨不崩溃并保留未知值`() = runBlocking {
        val source =
            source(
                stream(
                    type = MediaStreamType.VIDEO,
                    width = null,
                    height = 2160,
                    codec = "hevc",
                )
            )

        val metadata = VideoMetadataParser.parse(source)

        assertEquals(null, metadata.width)
        assertEquals(2160, metadata.height)
        assertEquals(null, metadata.videoBitrate)
        assertEquals(null, metadata.frameRate)
        assertEquals("hevc", metadata.videoCodec)
    }

    private fun source(vararg streams: FindroidMediaStream): FindroidSource =
        FindroidSource(
            id = "source-a",
            name = "source-a.mkv",
            type = FindroidSourceType.REMOTE,
            path = "",
            size = 0,
            mediaStreams = streams.toList(),
        )

    private fun stream(
        type: MediaStreamType,
        width: Int?,
        height: Int?,
        codec: String,
    ): FindroidMediaStream =
        FindroidMediaStream(
            title = "",
            displayTitle = null,
            language = "",
            type = type,
            codec = codec,
            isExternal = false,
            path = null,
            channelLayout = null,
            videoRangeType = null,
            height = height,
            width = width,
            videoDoViTitle = null,
        )
}
