package com.zhangwenkang.cinefin.models

import java.util.UUID
import org.jellyfin.sdk.model.api.MediaProtocol
import org.jellyfin.sdk.model.api.MediaSourceInfo
import org.jellyfin.sdk.model.api.MediaSourceType
import org.jellyfin.sdk.model.api.MediaStream
import org.jellyfin.sdk.model.api.MediaStreamProtocol
import org.jellyfin.sdk.model.api.MediaStreamType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FindroidMediaMetadataMappingTest {

    @Test
    fun `SDK 媒体流精确保留字段并优先参考帧率`() {
        val stream =
            MediaStream(
                codec = "hevc",
                displayTitle = "4K HEVC",
                title = "Main",
                type = MediaStreamType.VIDEO,
                isInterlaced = false,
                isDefault = false,
                isForced = false,
                isHearingImpaired = false,
                index = -1,
                isExternal = false,
                isTextSubtitleStream = false,
                supportsExternalStream = false,
                bitRate = 18_000_000,
                bitDepth = 10,
                width = 3840,
                height = 2160,
                channelLayout = null,
                channels = null,
                sampleRate = null,
                profile = "Main 10",
                averageFrameRate = 60f,
                realFrameRate = 59.94f,
                referenceFrameRate = 23.976f,
            )

        val mapped = stream.toFindroidMediaStream("https://example.test")

        assertEquals(18_000_000, mapped.bitRate)
        assertEquals(10, mapped.bitDepth)
        assertEquals(3840, mapped.width)
        assertEquals(2160, mapped.height)
        assertEquals("Main 10", mapped.profile)
        assertEquals(23.976f, mapped.frameRate!!, 0.001f)
    }

    @Test
    fun `SDK 媒体流 null 和零归一为未知`() {
        val stream =
            MediaStream(
                codec = "aac",
                type = MediaStreamType.AUDIO,
                isInterlaced = false,
                isDefault = false,
                isForced = false,
                isHearingImpaired = false,
                index = -1,
                isExternal = false,
                isTextSubtitleStream = false,
                supportsExternalStream = false,
                bitRate = 0,
                bitDepth = 0,
                channels = 0,
                sampleRate = 0,
                averageFrameRate = 0f,
                realFrameRate = -1f,
                referenceFrameRate = 0f,
            )

        val mapped = stream.toFindroidMediaStream("https://example.test")

        assertNull(mapped.bitRate)
        assertNull(mapped.bitDepth)
        assertNull(mapped.channels)
        assertNull(mapped.sampleRate)
        assertNull(mapped.frameRate)
    }

    @Test
    fun `SDK 媒体源精确保留总码率和容器`() =
        kotlinx.coroutines.runBlocking {
            val itemId = UUID.randomUUID()
            val source =
                MediaSourceInfo(
                    protocol = MediaProtocol.HTTP,
                    id = "source-a",
                    path = "https://example.test/video",
                    type = MediaSourceType.DEFAULT,
                    container = "mkv",
                    isRemote = true,
                    readAtNativeFramerate = false,
                    ignoreDts = false,
                    ignoreIndex = false,
                    genPtsInput = false,
                    supportsTranscoding = false,
                    supportsDirectStream = false,
                    supportsDirectPlay = true,
                    isInfiniteStream = false,
                    requiresOpening = false,
                    requiresClosing = false,
                    requiresLooping = false,
                    supportsProbing = false,
                    bitrate = 20_000_000,
                    transcodingSubProtocol = MediaStreamProtocol.HTTP,
                    hasSegments = false,
                    mediaStreams =
                        listOf(
                            MediaStream(
                                codec = "hevc",
                                type = MediaStreamType.VIDEO,
                                isInterlaced = false,
                                isDefault = false,
                                isForced = false,
                                isHearingImpaired = false,
                                index = -1,
                                isExternal = false,
                                isTextSubtitleStream = false,
                                supportsExternalStream = false,
                                width = 3840,
                                height = 2160,
                            )
                        ),
                )

            val mapped = source.toFindroidSource(baseUrl = "https://example.test", itemId = itemId)

            assertEquals(20_000_000, mapped.bitrate)
            assertEquals("mkv", mapped.container)
        }

    @Test
    fun `离线 DTO 保存并恢复全部新媒体字段`() {
        val source =
            FindroidSource(
                id = "source-a",
                name = "movie.mkv",
                type = FindroidSourceType.REMOTE,
                path = "https://example.test/movie",
                size = 10L,
                bitrate = 20_000_000,
                container = "mkv",
                mediaStreams =
                    listOf(
                        FindroidMediaStream(
                            title = "Main",
                            displayTitle = "4K HEVC",
                            language = "eng",
                            type = MediaStreamType.VIDEO,
                            codec = "hevc",
                            isExternal = false,
                            path = null,
                            channelLayout = null,
                            videoRangeType = null,
                            height = 2160,
                            width = 3840,
                            videoDoViTitle = null,
                            bitRate = 18_000_000,
                            frameRate = 23.976f,
                            bitDepth = 10,
                            channels = null,
                            sampleRate = null,
                            profile = "Main 10",
                        )
                    ),
            )

        val dto = source.toFindroidSourceDto(UUID.randomUUID(), "/local/movie.mkv")
        val streamDto =
            source.mediaStreams
                .single()
                .toFindroidMediaStreamDto(UUID.randomUUID(), dto.id, dto.path)
        val restoredStream = streamDto.toFindroidMediaStream()

        assertEquals(20_000_000, dto.bitrate)
        assertEquals("mkv", dto.container)
        assertEquals(18_000_000, restoredStream.bitRate)
        assertEquals(23.976f, restoredStream.frameRate!!, 0.001f)
        assertEquals(10, restoredStream.bitDepth)
        assertEquals("Main 10", restoredStream.profile)
    }
}
