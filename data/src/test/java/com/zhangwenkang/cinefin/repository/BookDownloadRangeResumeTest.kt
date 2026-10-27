package com.zhangwenkang.cinefin.repository

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.Closeable
import java.io.File
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Task 5 RED：书籍下载必须复用已有 `.part`，并用真实 production transfer 覆盖 200 / 206 / 416 协议分支。
 *
 * 测试服务器只监听 127.0.0.1，不接触 Jellyfin 生产环境。
 */
class BookDownloadRangeResumeTest {

    @Test
    fun `已有 part 与 validator 时先发 Range 再追加响应`() = runBlocking {
        val root = createTempDirectory(prefix = "book-range-part").toFile()
        val target = File(root, "$ITEM_ID.book")
        val partial = File(root, "$ITEM_ID.book.part")
        val prefixLength = 50L * 1024L * 1024L
        java.io.RandomAccessFile(partial, "rw").use { it.setLength(prefixLength) }
        val remainder = "range-tail".toByteArray(StandardCharsets.UTF_8)
        val observedPartLength = CopyOnWriteArrayList<Long>()
        val server = LocalRangeServer { exchange, _ ->
            observedPartLength += partial.length()
            exchange.responseHeaders.add(
                "Content-Range",
                "bytes $prefixLength-${prefixLength + remainder.size - 1}/${prefixLength + remainder.size}",
            )
            exchange.sendResponseHeaders(206, remainder.size.toLong())
            exchange.responseBody.use { it.write(remainder) }
        }
        try {
            val result =
                BookDownloadRangeTransfer(OkHttpClient())
                    .download(
                        BookDownloadTransferRequest(
                            itemId = ITEM_ID,
                            targetFile = target,
                            partialFile = partial,
                            credentials = server.credentials(),
                            resumeValidator = "\"v1\"",
                            taskGeneration = 1,
                        )
                    )

            assertEquals(listOf(prefixLength), observedPartLength)
            assertEquals("bytes=$prefixLength-", server.requests.single().range)
            assertEquals("\"v1\"", server.requests.single().ifRange)
            assertEquals(
                "MediaBrowser Token=\"frozen-token\"",
                server.requests.single().authorization,
            )
            assertTrue(target.isFile)
            assertEquals(prefixLength + remainder.size, target.length())
            assertEquals(prefixLength + remainder.size, result.totalBytes)
            assertTrue(!partial.exists())
        } finally {
            server.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun `无 validator 时首请求不带 Range 且 200 从零截断旧残片`() = runBlocking {
        val root = createTempDirectory(prefix = "book-range-200").toFile()
        val target = File(root, "$ITEM_ID.book")
        val partial = File(root, "$ITEM_ID.book.part")
        partial.writeText("stale-prefix")
        val payload = "fresh-book".toByteArray(StandardCharsets.UTF_8)
        val server = LocalRangeServer { exchange, _ ->
            exchange.sendResponseHeaders(200, payload.size.toLong())
            exchange.responseBody.use { it.write(payload) }
        }
        try {
            BookDownloadRangeTransfer(OkHttpClient())
                .download(
                    BookDownloadTransferRequest(
                        itemId = ITEM_ID,
                        targetFile = target,
                        partialFile = partial,
                        credentials = server.credentials(),
                        resumeValidator = null,
                        taskGeneration = 2,
                    )
                )

            val request = server.requests.single()
            assertNull(request.range)
            assertNull(request.ifRange)
            assertArrayEquals(payload, target.readBytes())
        } finally {
            server.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun `206 Content-Range 起点不匹配时只安全重下一次`() = runBlocking {
        val root = createTempDirectory(prefix = "book-range-mismatch").toFile()
        val target = File(root, "$ITEM_ID.book")
        val partial = File(root, "$ITEM_ID.book.part")
        partial.writeBytes(byteArrayOf(0, 1, 2, 3))
        val full = "0123456789".toByteArray(StandardCharsets.UTF_8)
        val server = LocalRangeServer { exchange, index ->
            if (index == 0) {
                exchange.responseHeaders.add("Content-Range", "bytes 1-3/10")
                exchange.sendResponseHeaders(206, 3)
                exchange.responseBody.use { it.write(byteArrayOf(1, 2, 3)) }
            } else {
                exchange.sendResponseHeaders(200, full.size.toLong())
                exchange.responseBody.use { it.write(full) }
            }
        }
        try {
            BookDownloadRangeTransfer(OkHttpClient())
                .download(
                    BookDownloadTransferRequest(
                        itemId = ITEM_ID,
                        targetFile = target,
                        partialFile = partial,
                        credentials = server.credentials(),
                        resumeValidator = "\"v1\"",
                        taskGeneration = 3,
                    )
                )

            assertEquals(2, server.requests.size)
            assertEquals("bytes=4-", server.requests[0].range)
            assertNull(server.requests[1].range)
            assertArrayEquals(full, target.readBytes())
        } finally {
            server.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun `416 且偏移等于总长时直接完成已有 part`() = runBlocking {
        val root = createTempDirectory(prefix = "book-range-416-complete").toFile()
        val target = File(root, "$ITEM_ID.book")
        val partial = File(root, "$ITEM_ID.book.part")
        val full = "ready".toByteArray(StandardCharsets.UTF_8)
        partial.writeBytes(full)
        val server = LocalRangeServer { exchange, _ ->
            exchange.responseHeaders.add("Content-Range", "bytes */${full.size}")
            exchange.sendResponseHeaders(416, -1)
        }
        try {
            BookDownloadRangeTransfer(OkHttpClient())
                .download(
                    BookDownloadTransferRequest(
                        itemId = ITEM_ID,
                        targetFile = target,
                        partialFile = partial,
                        credentials = server.credentials(),
                        resumeValidator = "\"v1\"",
                        taskGeneration = 4,
                    )
                )

            assertEquals(1, server.requests.size)
            assertArrayEquals(full, target.readBytes())
        } finally {
            server.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun `416 非终态安全重下一次并从零写完整文件`() = runBlocking {
        val root = createTempDirectory(prefix = "book-range-416-reset").toFile()
        val target = File(root, "$ITEM_ID.book")
        val partial = File(root, "$ITEM_ID.book.part")
        partial.writeBytes(byteArrayOf(7, 8, 9))
        val full = "complete-after-416".toByteArray(StandardCharsets.UTF_8)
        val server = LocalRangeServer { exchange, index ->
            if (index == 0) {
                exchange.responseHeaders.add("Content-Range", "bytes */${full.size}")
                exchange.sendResponseHeaders(416, -1)
            } else {
                exchange.sendResponseHeaders(200, full.size.toLong())
                exchange.responseBody.use { it.write(full) }
            }
        }
        try {
            BookDownloadRangeTransfer(OkHttpClient())
                .download(
                    BookDownloadTransferRequest(
                        itemId = ITEM_ID,
                        targetFile = target,
                        partialFile = partial,
                        credentials = server.credentials(),
                        resumeValidator = "\"v1\"",
                        taskGeneration = 5,
                    )
                )

            assertEquals(2, server.requests.size)
            assertNull(server.requests[1].range)
            assertArrayEquals(full, target.readBytes())
        } finally {
            server.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun `416 非终态安全重下失败时有限失败并保留 part`() = runBlocking {
        val root = createTempDirectory(prefix = "book-range-416-fail").toFile()
        val target = File(root, "$ITEM_ID.book")
        val partial = File(root, "$ITEM_ID.book.part")
        partial.writeBytes(byteArrayOf(7, 8, 9))
        val server = LocalRangeServer { exchange, index ->
            if (index == 0) {
                exchange.responseHeaders.add("Content-Range", "bytes */11")
                exchange.sendResponseHeaders(416, -1)
            } else {
                val body = "still-down".toByteArray(StandardCharsets.UTF_8)
                exchange.sendResponseHeaders(500, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
        }
        try {
            try {
                BookDownloadRangeTransfer(OkHttpClient())
                    .download(
                        BookDownloadTransferRequest(
                            itemId = ITEM_ID,
                            targetFile = target,
                            partialFile = partial,
                            credentials = server.credentials(),
                            resumeValidator = "\"v1\"",
                            taskGeneration = 6,
                        )
                    )
                fail("第二次请求失败后不得无限重试")
            } catch (_: Exception) {
                // 有限失败由调用方持久化为 FAILED；原始 `.part` 必须保留。
            }

            assertEquals(2, server.requests.size)
            assertArrayEquals(byteArrayOf(7, 8, 9), partial.readBytes())
            assertTrue(!target.exists())
        } finally {
            server.close()
            root.deleteRecursively()
        }
    }

    private companion object {
        val ITEM_ID: UUID = UUID.fromString("11111111-1111-4111-8111-111111111111")
    }
}

internal class LocalRangeServer(private val handler: (HttpExchange, Int) -> Unit) : Closeable {
    private val sequence = AtomicInteger()
    private val executor: ExecutorService = Executors.newCachedThreadPool()
    private val server =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                val index = sequence.getAndIncrement()
                requests +=
                    RecordedRangeRequest(
                        range = exchange.requestHeaders.getFirst("Range"),
                        ifRange = exchange.requestHeaders.getFirst("If-Range"),
                        authorization = exchange.requestHeaders.getFirst("Authorization"),
                    )
                try {
                    handler(exchange, index)
                } finally {
                    exchange.close()
                }
            }
            this.executor = this@LocalRangeServer.executor
            start()
        }

    val requests = CopyOnWriteArrayList<RecordedRangeRequest>()

    fun credentials(): BookDownloadCredentials =
        BookDownloadCredentials(
            serverId = "server-a",
            userId = UUID.fromString("22222222-2222-4222-8222-222222222222"),
            baseUrl = "http://127.0.0.1:${server.address.port}",
            accessToken = "frozen-token",
        )

    override fun close() {
        server.stop(0)
        executor.shutdownNow()
    }
}

internal data class RecordedRangeRequest(
    val range: String?,
    val ifRange: String?,
    val authorization: String?,
)
