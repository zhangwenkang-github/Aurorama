package com.zhangwenkang.cinefin.utils

import com.zhangwenkang.cinefin.models.FindroidMediaStream
import com.zhangwenkang.cinefin.models.FindroidMediaStreamDto
import com.zhangwenkang.cinefin.models.FindroidSource
import com.zhangwenkang.cinefin.models.FindroidSourceDto
import com.zhangwenkang.cinefin.models.FindroidSourceType
import com.zhangwenkang.cinefin.models.toFindroidSource
import java.util.UUID
import org.jellyfin.sdk.model.api.MediaStreamType
import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadedMediaStreamPersistenceTest {

    @Test
    fun `普通下载完成持久化内嵌视频和音频流`() =
        kotlinx.coroutines.runBlocking {
            val sourceId = "source-a"
            val localPath = "/downloads/movie.source-a.mkv"
            val remoteSource = remoteSource(sourceId)
            val roomRows = linkedMapOf<UUID, FindroidMediaStreamDto>()

            persistDownloadedMediaStreams(remoteSource, localPath) { row -> roomRows[row.id] = row }

            val offlineSource =
                FindroidSourceDto(
                        id = sourceId,
                        itemId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
                        name = remoteSource.name,
                        type = FindroidSourceType.LOCAL,
                        path = localPath,
                        bitrate = remoteSource.bitrate,
                        container = remoteSource.container,
                    )
                    .toFindroidSource(roomRows.values.toList())

            assertEquals(20_000_000, offlineSource.bitrate)
            assertEquals("mkv", offlineSource.container)
            assertEquals(2, offlineSource.mediaStreams.size)

            val video = offlineSource.mediaStreams.single { it.type == MediaStreamType.VIDEO }
            assertEquals("hevc", video.codec)
            assertEquals(3840, video.width)
            assertEquals(2160, video.height)
            assertEquals(18_000_000, video.bitRate)
            assertEquals(23.976f, video.frameRate!!, 0.001f)
            assertEquals(10, video.bitDepth)
            assertEquals("Main 10", video.profile)
            assertEquals(localPath, video.path)

            val audio = offlineSource.mediaStreams.single { it.type == MediaStreamType.AUDIO }
            assertEquals("eac3", audio.codec)
            assertEquals(640_000, audio.bitRate)
            assertEquals(6, audio.channels)
            assertEquals(48_000, audio.sampleRate)
            assertEquals("Dolby Digital Plus", audio.profile)
            assertEquals(localPath, audio.path)
        }

    @Test
    fun `重复完成下载以稳定行 id 替换内嵌流元数据`() =
        kotlinx.coroutines.runBlocking {
            val remoteSource = remoteSource("source-a")
            val roomRows = linkedMapOf<UUID, FindroidMediaStreamDto>()

            persistDownloadedMediaStreams(remoteSource, "/downloads/movie.source-a.mkv") { row ->
                roomRows[row.id] = row
            }
            persistDownloadedMediaStreams(remoteSource, "/downloads/movie.source-a.mkv") { row ->
                roomRows[row.id] = row
            }

            assertEquals(2, roomRows.size)
        }

    private fun remoteSource(sourceId: String) =
        FindroidSource(
            id = sourceId,
            name = "movie.mkv",
            type = FindroidSourceType.REMOTE,
            path = "https://example.test/movie",
            size = 10L,
            bitrate = 20_000_000,
            container = "mkv",
            mediaStreams =
                listOf(
                    videoStream(),
                    audioStream(),
                ),
        )

    private fun videoStream() =
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
            profile = "Main 10",
        )

    private fun audioStream() =
        FindroidMediaStream(
            title = "Surround",
            displayTitle = "English EAC3",
            language = "eng",
            type = MediaStreamType.AUDIO,
            codec = "eac3",
            isExternal = false,
            path = null,
            channelLayout = "5.1",
            videoRangeType = null,
            height = null,
            width = null,
            videoDoViTitle = null,
            bitRate = 640_000,
            channels = 6,
            sampleRate = 48_000,
            profile = "Dolby Digital Plus",
        )
}
