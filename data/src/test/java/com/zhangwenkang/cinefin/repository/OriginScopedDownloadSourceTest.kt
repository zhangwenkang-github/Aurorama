package com.zhangwenkang.cinefin.repository

import com.zhangwenkang.cinefin.models.DownloadPathKind
import com.zhangwenkang.cinefin.models.FindroidSourceType
import com.zhangwenkang.cinefin.models.toFindroidSource
import java.util.UUID
import org.jellyfin.sdk.model.api.MediaProtocol
import org.jellyfin.sdk.model.api.MediaSourceInfo
import org.jellyfin.sdk.model.api.MediaSourceType
import org.jellyfin.sdk.model.api.MediaStreamProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OriginScopedDownloadSourceTest {
    @Test
    fun `FILE 下载源是冻结 origin 下的绝对 stream URL`() {
        val itemId = UUID.fromString("11111111-2222-3333-4444-555555555555")
        val source =
            mediaSource(
                protocol = MediaProtocol.FILE,
                id = "media-source-a",
                path = "https://stale-origin.example/wrong.mkv",
            )

        val mapped = source.toFindroidSource(baseUrl = "https://origin.example", itemId = itemId)

        assertEquals(
            "https://origin.example/Videos/$itemId/stream?static=true&mediaSourceId=media-source-a",
            mapped.path,
        )
        assertTrue(mapped.path.startsWith("https://origin.example/"))
        assertEquals(FindroidSourceType.REMOTE, mapped.type)
        assertEquals(itemId, mapped.downloadMarker?.itemId)
        assertEquals("media-source-a", mapped.downloadMarker?.sourceId)
        assertEquals("FILE", mapped.downloadMarker?.protocol)
        assertEquals(DownloadPathKind.SYNTHETIC_STREAM, mapped.downloadMarker?.pathKind)
        assertEquals(false, mapped.downloadMarker?.requestSent)
        assertEquals("READY", mapped.downloadMarker?.status)
    }

    @Test
    fun `HTTP 绝对 path 原样保留`() {
        val absolutePath = "https://cdn.example/media/movie.mkv?token=keep-this-value&download=true"
        val source = mediaSource(protocol = MediaProtocol.HTTP, path = absolutePath)

        val mapped =
            source.toFindroidSource(
                baseUrl = "https://origin.example",
                itemId = UUID.randomUUID(),
            )

        assertEquals(absolutePath, mapped.path)
    }

    @Test
    fun `FILE stream URL 对 mediaSourceId 特殊字符编码`() {
        val itemId = UUID.fromString("11111111-2222-3333-4444-555555555555")
        val source =
            mediaSource(
                protocol = MediaProtocol.FILE,
                id = "source id/part?x",
                path = "/srv/jellyfin/movie.mkv",
            )

        val mapped = source.toFindroidSource(baseUrl = "https://origin.example", itemId = itemId)

        assertEquals(
            "https://origin.example/Videos/$itemId/stream" +
                "?static=true&mediaSourceId=source%20id%2Fpart%3Fx",
            mapped.path,
        )
    }

    @Test
    fun `HTTP 相对 path 绝对化到冻结 origin`() {
        val source =
            mediaSource(protocol = MediaProtocol.HTTP, path = "media/library/movie.mkv?x=1")

        val mapped =
            source.toFindroidSource(
                baseUrl = "https://origin.example/jellyfin",
                itemId = UUID.randomUUID(),
            )

        assertEquals(
            "https://origin.example/jellyfin/media/library/movie.mkv?x=1",
            mapped.path,
        )
        assertEquals(DownloadPathKind.RELATIVE_HTTP, mapped.downloadMarker?.pathKind)
        assertEquals(false, mapped.downloadMarker?.requestSent)
    }

    @Test
    fun `非 http(s) 和非 FILE 协议 fail closed 为空 path`() {
        val ftp = mediaSource(protocol = MediaProtocol.FTP, path = "ftp://files.example/movie.mkv")
        val emptyHttp = mediaSource(protocol = MediaProtocol.HTTP, path = " ")

        val mappedFtp =
            ftp.toFindroidSource(
                baseUrl = "https://origin.example",
                itemId = UUID.randomUUID(),
            )
        val mappedEmptyHttp =
            emptyHttp.toFindroidSource(
                baseUrl = "https://origin.example",
                itemId = UUID.randomUUID(),
            )

        assertEquals("", mappedFtp.path)
        assertEquals("REJECTED", mappedFtp.downloadMarker?.status)
        assertEquals("non_http_protocol", mappedFtp.downloadMarker?.failureReason)
        assertEquals("", mappedEmptyHttp.path)
        assertEquals("REJECTED", mappedEmptyHttp.downloadMarker?.status)
        assertEquals("empty_url", mappedEmptyHttp.downloadMarker?.failureReason)
    }

    private fun mediaSource(
        protocol: MediaProtocol,
        id: String? = null,
        path: String? = null,
    ): MediaSourceInfo =
        MediaSourceInfo(
            protocol = protocol,
            id = id,
            path = path,
            type = MediaSourceType.DEFAULT,
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
            transcodingSubProtocol = MediaStreamProtocol.HTTP,
            hasSegments = false,
        )
}
