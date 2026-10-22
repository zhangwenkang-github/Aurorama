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
    fun `完整视频轨保留精确源字段`() = runBlocking {
        val source =
            source(
                bitrate = 20_000_000,
                container = "mkv",
                stream =
                    stream(
                        type = MediaStreamType.VIDEO,
                        width = 3840,
                        height = 2160,
                        codec = "hevc",
                        bitRate = 18_000_000,
                        frameRate = 23.976f,
                    ),
            )

        val metadata = VideoMetadataParser.parse(source)

        assertEquals(3840, metadata.width)
        assertEquals(2160, metadata.height)
        assertEquals(18_000_000, metadata.videoBitrate)
        assertEquals(23.976f, metadata.frameRate!!, 0.001f)
        assertEquals("hevc", metadata.videoCodec)
        assertEquals(20_000_000, metadata.sourceBitrate)
        assertEquals("mkv", metadata.container)
    }

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

    @Test
    fun `无视频轨时精确字段全部未知`() = runBlocking {
        val source =
            source(
                stream(
                    type = MediaStreamType.AUDIO,
                    width = null,
                    height = null,
                    codec = "aac",
                )
            )

        val metadata = VideoMetadataParser.parse(source)

        assertEquals(null, metadata.width)
        assertEquals(null, metadata.height)
        assertEquals(null, metadata.videoBitrate)
        assertEquals(null, metadata.frameRate)
        assertEquals(null, metadata.videoCodec)
    }

    @Test
    fun `空源安全返回未知字段`() = runBlocking {
        val metadata = VideoMetadataParser.parse(source())

        assertEquals(null, metadata.width)
        assertEquals(null, metadata.height)
        assertEquals(null, metadata.videoBitrate)
        assertEquals(null, metadata.frameRate)
        assertEquals(null, metadata.videoCodec)
    }

    private fun source(
        vararg streams: FindroidMediaStream,
        bitrate: Int? = null,
        container: String? = null,
        stream: FindroidMediaStream? = null,
    ): FindroidSource =
        FindroidSource(
            id = "source-a",
            name = "source-a.mkv",
            type = FindroidSourceType.REMOTE,
            path = "",
            size = 0,
            bitrate = bitrate,
            container = container,
            mediaStreams = (streams.toList() + listOfNotNull(stream)),
        )

    private fun stream(
        type: MediaStreamType,
        width: Int?,
        height: Int?,
        codec: String,
        bitRate: Int? = null,
        frameRate: Float? = null,
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
            bitRate = bitRate,
            frameRate = frameRate,
        )
}
