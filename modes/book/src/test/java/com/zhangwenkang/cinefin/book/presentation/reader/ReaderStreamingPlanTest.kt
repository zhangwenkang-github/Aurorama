package com.zhangwenkang.cinefin.book.presentation.reader

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** W77 阅读流式：打开来源决策 / URL 映射 / 请求头注入 / 热切换判定（纯函数）。 */
class ReaderStreamingPlanTest {

    @Test
    fun `本地已有文件时永远走本地路径`() {
        assertEquals(
            ReaderOpenSource.Local,
            decideReaderOpenSource(hasLocalFile = true, online = true, remoteAvailable = true),
        )
        assertEquals(
            ReaderOpenSource.Local,
            decideReaderOpenSource(hasLocalFile = true, online = false, remoteAvailable = false),
        )
    }

    @Test
    fun `未下载但联网且有会话时先试远程流式`() {
        assertEquals(
            ReaderOpenSource.RemoteCandidate,
            decideReaderOpenSource(hasLocalFile = false, online = true, remoteAvailable = true),
        )
    }

    @Test
    fun `离线或无会话时回退整本下载`() {
        assertEquals(
            ReaderOpenSource.Download,
            decideReaderOpenSource(hasLocalFile = false, online = false, remoteAvailable = true),
        )
        assertEquals(
            ReaderOpenSource.Download,
            decideReaderOpenSource(hasLocalFile = false, online = true, remoteAvailable = false),
        )
    }

    @Test
    fun `只有 EPUB 走远程流式`() {
        assertTrue(shouldStreamRemoteAsset(isEpub = true))
        assertFalse(shouldStreamRemoteAsset(isEpub = false))
    }

    @Test
    fun `下载地址与整本下载路径同源`() {
        val itemId = UUID.fromString("01234567-89ab-cdef-0123-456789abcdef")
        assertEquals(
            "https://jellyfin.example.com/Items/$itemId/Download",
            readerDownloadUrl("https://jellyfin.example.com", itemId),
        )
        // 结尾斜杠不产生双斜杠。
        assertEquals(
            "http://10.0.0.2:8096/Items/$itemId/Download",
            readerDownloadUrl("http://10.0.0.2:8096/", itemId),
        )
    }

    @Test
    fun `令牌注入保留原有请求头`() {
        val original = mapOf("Range" to listOf("bytes=0-1023"), "Accept" to listOf("*/*"))
        val merged = readiumHeadersWithToken(original, "abc123")
        assertEquals(listOf("bytes=0-1023"), merged["Range"])
        assertEquals(listOf("*/*"), merged["Accept"])
        assertEquals(listOf("abc123"), merged[ACCESS_TOKEN_HEADER])
        // 不修改入参。
        assertFalse(original.containsKey(ACCESS_TOKEN_HEADER))
    }

    @Test
    fun `令牌注入大小写不敏感地替换同名头`() {
        val original = mapOf("x-emby-token" to listOf("old"))
        val merged = readiumHeadersWithToken(original, "new")
        assertEquals(1, merged.size)
        assertEquals(listOf("new"), merged.values.first())
    }

    @Test
    fun `没有令牌时请求头原样返回`() {
        val original = mapOf("Range" to listOf("bytes=0-"))
        assertEquals(original, readiumHeadersWithToken(original, null))
        assertEquals(original, readiumHeadersWithToken(original, "   "))
    }

    @Test
    fun `只有远程文档 + 同书 + 有效文件才热切换`() {
        assertTrue(
            shouldHotSwapToLocal(remoteDocumentOpen = true, sameItem = true, downloadedBytes = 1024)
        )
        assertFalse(
            shouldHotSwapToLocal(
                remoteDocumentOpen = false,
                sameItem = true,
                downloadedBytes = 1024,
            )
        )
        assertFalse(
            shouldHotSwapToLocal(
                remoteDocumentOpen = true,
                sameItem = false,
                downloadedBytes = 1024,
            )
        )
        assertFalse(
            shouldHotSwapToLocal(remoteDocumentOpen = true, sameItem = true, downloadedBytes = 0)
        )
    }

    @Test
    fun `解析 Content-Range 总长`() {
        assertEquals(229_242_149L, contentRangeTotal("bytes 0-0/229242149"))
        assertEquals(229_242_149L, contentRangeTotal("bytes */229242149"))
        assertEquals(1L, contentRangeTotal("bytes 0-0/1"))
        assertEquals(null, contentRangeTotal(null))
        assertEquals(null, contentRangeTotal(""))
        assertEquals(null, contentRangeTotal("bytes 0-0/*"))
        assertEquals(null, contentRangeTotal("bytes 0-0/0"))
        assertEquals(null, contentRangeTotal("bytes 0-0/abc"))
    }

    @Test
    fun `有界读区间保留终点`() {
        assertEquals("bytes=0-1023", rangeHeader(0L..1023L))
        assertEquals("bytes=123-8314", rangeHeader(123L..8314L))
        assertEquals("bytes=0-0", rangeHeader(0L..0L))
        // 只有明确「读到结尾」才退化成开放式 Range。
        assertEquals("bytes=123-", rangeHeader(123L..Long.MAX_VALUE))
    }
}
