package com.zhangwenkang.cinefin.utils

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.Executors
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadHttpEngineTest {
    @Test
    fun `MockWebServer 收到真实 GET 与 Range 且断点续传`() = runBlocking {
        val workDir = Files.createTempDirectory("download-http-engine").toFile()
        val target = File(workDir, "movie.bin")
        val server = MockWebServer(body = "0123456789".toByteArray(StandardCharsets.UTF_8))
        server.start()
        try {
            val engine = DownloadHttpEngine(OkHttpClient(), elapsedRealtimeMillis = { 0L })
            val url = server.url("/Videos/item-a/stream?static=true&mediaSourceId=source-a")

            val first =
                engine.download(
                    url = url,
                    token = null,
                    baseUrl = server.baseUrl,
                    target = target,
                    existingBytes = 0L,
                    validator = null,
                    expectedTotalBytes = 0L,
                ) { _, _, _, _ ->
                }
            val second =
                engine.download(
                    url = url,
                    token = null,
                    baseUrl = server.baseUrl,
                    target = target,
                    existingBytes = 4L,
                    validator = first.validator,
                    expectedTotalBytes = first.totalBytes,
                ) { _, _, _, _ ->
                }

            assertEquals(10L, first.downloadedBytes)
            assertEquals(10L, second.downloadedBytes)
            assertEquals("0123456789", target.readText())
            assertEquals(listOf("GET", "GET"), server.requests.map { it.method })
            assertEquals(listOf(null, "bytes=4-"), server.requests.map { it.range })
            assertEquals(listOf(null, "\"v1\""), server.requests.map { it.ifRange })
            assertEquals(2, server.requests.size)
            assertEquals("item-a", second.marker?.itemId)
            assertEquals("source-a", second.marker?.sourceId)
            assertTrue(second.marker?.requestSent == true)
            assertEquals("SUCCESS", second.marker?.status)
        } finally {
            server.close()
            workDir.deleteRecursively()
        }
    }

    @Test
    fun `跨 origin 重定向不携带下载令牌`() = runBlocking {
        val workDir = Files.createTempDirectory("download-http-redirect").toFile()
        val destination = MockWebServer(body = "redirected".toByteArray(StandardCharsets.UTF_8))
        destination.start()
        val origin =
            MockWebServer(
                body = byteArrayOf(),
                redirectTo = destination.url("/Videos/final/stream"),
            )
        origin.start()
        try {
            val engine = DownloadHttpEngine(OkHttpClient(), elapsedRealtimeMillis = { 0L })
            val secret = "must-not-cross-origin"
            val result =
                engine.download(
                    url = origin.url("/redirect"),
                    token = secret,
                    baseUrl = origin.baseUrl,
                    target = File(workDir, "redirected.bin"),
                    existingBytes = 0L,
                    validator = null,
                    expectedTotalBytes = 0L,
                ) { _, _, _, _ ->
                }

            assertEquals("redirected", File(workDir, "redirected.bin").readText())
            assertTrue(
                origin.requests.single().authorization.orEmpty().contains("MediaBrowser Token")
            )
            assertNull(destination.requests.single().authorization)
            assertFalse(result.marker.toString().contains(secret))
        } finally {
            origin.close()
            destination.close()
            workDir.deleteRecursively()
        }
    }

    @Test
    fun `空和非 http URL 在发请求前得到 SERVER_ERROR`() = runBlocking {
        val workDir = Files.createTempDirectory("download-http-invalid").toFile()
        val server = MockWebServer(body = byteArrayOf(1))
        server.start()
        try {
            val engine = DownloadHttpEngine(OkHttpClient(), elapsedRealtimeMillis = { 0L })
            val before = server.requests.size

            val empty = captureHttpFailure {
                engine.download(
                    url = "",
                    token = "must-not-leak",
                    baseUrl = server.baseUrl,
                    target = File(workDir, "empty.bin"),
                    existingBytes = 0L,
                    validator = null,
                    expectedTotalBytes = 0L,
                ) { _, _, _, _ ->
                }
            }
            val ftp = captureHttpFailure {
                engine.download(
                    url = "ftp://files.example/movie.mkv",
                    token = "must-not-leak",
                    baseUrl = server.baseUrl,
                    target = File(workDir, "ftp.bin"),
                    existingBytes = 0L,
                    validator = null,
                    expectedTotalBytes = 0L,
                ) { _, _, _, _ ->
                }
            }

            assertEquals(before, server.requests.size)
            assertEquals(DownloadFailureReason.SERVER_ERROR, empty.reason)
            assertEquals("REJECTED", empty.marker?.status)
            assertFalse(empty.marker?.requestSent ?: true)
            assertEquals("EMPTY", empty.marker?.pathKind)
            assertNull(empty.marker?.itemId)
            assertEquals(DownloadFailureReason.SERVER_ERROR, ftp.reason)
            assertEquals("REJECTED", ftp.marker?.status)
            assertFalse(ftp.marker?.requestSent ?: true)
            assertEquals("ftp", ftp.marker?.protocol)
            assertEquals("NON_HTTP", ftp.marker?.pathKind)
        } finally {
            server.close()
            workDir.deleteRecursively()
        }
    }

    private suspend fun captureHttpFailure(block: suspend () -> Unit): DownloadHttpException =
        try {
            block()
            throw AssertionError("expected DownloadHttpException")
        } catch (failure: DownloadHttpException) {
            failure
        }
}

private class MockWebServer(
    private val body: ByteArray,
    private val redirectTo: String? = null,
) {
    data class Request(
        val method: String,
        val path: String,
        val range: String?,
        val ifRange: String?,
        val authorization: String?,
    )

    private val executor = Executors.newSingleThreadExecutor()
    private val httpServer =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                requests +=
                    Request(
                        method = exchange.requestMethod,
                        path = exchange.requestURI.toString(),
                        range = exchange.requestHeaders.getFirst("Range"),
                        ifRange = exchange.requestHeaders.getFirst("If-Range"),
                        authorization = exchange.requestHeaders.getFirst("Authorization"),
                    )
                respond(exchange)
            }
            executor = this@MockWebServer.executor
        }

    val requests = Collections.synchronizedList(mutableListOf<Request>())
    val baseUrl: String
        get() = "http://127.0.0.1:${httpServer.address.port}"

    fun url(path: String): String = baseUrl + path

    fun start() {
        httpServer.start()
    }

    fun close() {
        httpServer.stop(0)
        executor.shutdownNow()
    }

    private fun respond(exchange: HttpExchange) {
        if (redirectTo != null) {
            exchange.responseHeaders.add("Location", redirectTo)
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
            return
        }
        val range = exchange.requestHeaders.getFirst("Range")
        if (range == "bytes=4-") {
            val tail = body.copyOfRange(4, body.size)
            exchange.responseHeaders.add("ETag", "\"v1\"")
            exchange.responseHeaders.add("Content-Range", "bytes 4-${body.lastIndex}/${body.size}")
            exchange.sendResponseHeaders(206, tail.size.toLong())
            exchange.responseBody.use { it.write(tail) }
        } else {
            exchange.responseHeaders.add("ETag", "\"v1\"")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
    }
}
