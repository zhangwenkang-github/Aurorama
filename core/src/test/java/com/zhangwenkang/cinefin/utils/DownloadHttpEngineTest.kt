package com.zhangwenkang.cinefin.utils

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import com.zhangwenkang.cinefin.di.configureDownloadHttpClient
import com.zhangwenkang.cinefin.network.JellyfinAuth
import java.io.File
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
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
            val engine =
                DownloadHttpEngine(
                    configureDownloadHttpClient(OkHttpClient.Builder()).build(),
                    elapsedRealtimeMillis = { 0L },
                )
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
            val engine =
                DownloadHttpEngine(
                    configureDownloadHttpClient(OkHttpClient.Builder()).build(),
                    elapsedRealtimeMillis = { 0L },
                )
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
    fun `连接前失败保持 requestSent false`() = runBlocking {
        val workDir = Files.createTempDirectory("download-http-connect-failure").toFile()
        val closedPort =
            java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use {
                it.localPort
            }
        val client =
            OkHttpClient.Builder()
                .connectTimeout(500, TimeUnit.MILLISECONDS)
                .retryOnConnectionFailure(false)
                .build()
        val engine = DownloadHttpEngine(client, elapsedRealtimeMillis = { 0L })
        try {
            val failure = captureHttpFailure {
                engine.download(
                    url = "http://127.0.0.1:$closedPort/Videos/item/stream?static=true",
                    token = null,
                    baseUrl = "http://127.0.0.1:$closedPort",
                    target = File(workDir, "unreachable.bin"),
                    existingBytes = 0L,
                    validator = null,
                    expectedTotalBytes = 0L,
                ) { _, _, _, _ ->
                }
            }

            assertFalse(failure.marker?.requestSent ?: true)
            assertEquals("FAILED", failure.marker?.status)
        } finally {
            workDir.deleteRecursively()
        }
    }

    @Test
    fun `production network interceptor 同 host 不同 port 剥离两类凭据`() = runBlocking {
        val destination = MockWebServer(body = "ok".toByteArray(StandardCharsets.UTF_8))
        destination.start()
        val origin =
            MockWebServer(
                body = byteArrayOf(),
                redirectTo = destination.url("/Videos/final/stream"),
            )
        origin.start()
        try {
            val secret = "interceptor-red-test"
            val client = configureDownloadHttpClient(OkHttpClient.Builder()).build()
            val request =
                Request.Builder()
                    .url(origin.url("/redirect"))
                    .header(JellyfinAuth.HEADER, JellyfinAuth.headerValue(secret))
                    .header(JellyfinAuth.LEGACY_HEADER, secret)
                    .tag(
                        DownloadAllowedTokenHost::class.java,
                        DownloadAllowedTokenHost("127.0.0.1", origin.port),
                    )
                    .build()

            client.newCall(request).execute().use { assertTrue(it.isSuccessful) }

            assertTrue(origin.requests.single().authorization.orEmpty().contains(secret))
            assertTrue(origin.requests.single().legacyAuthorization.orEmpty().contains(secret))
            assertNull(destination.requests.single().authorization)
            assertNull(destination.requests.single().legacyAuthorization)
        } finally {
            origin.close()
            destination.close()
        }
    }

    @Test
    fun `production network interceptor 同 host 同 port 保留两类凭据`() = runBlocking {
        val server = MockWebServer(body = "ok".toByteArray(StandardCharsets.UTF_8))
        server.start()
        server.redirectTo = server.url("/Videos/final/stream")
        try {
            val secret = "interceptor-preserve-test"
            val client = configureDownloadHttpClient(OkHttpClient.Builder()).build()
            val request =
                Request.Builder()
                    .url(server.url("/redirect"))
                    .header(JellyfinAuth.HEADER, JellyfinAuth.headerValue(secret))
                    .header(JellyfinAuth.LEGACY_HEADER, secret)
                    .tag(
                        DownloadAllowedTokenHost::class.java,
                        DownloadAllowedTokenHost("127.0.0.1", server.port),
                    )
                    .build()

            client.newCall(request).execute().use { assertTrue(it.isSuccessful) }

            assertEquals(2, server.requests.size)
            server.requests.forEach {
                assertTrue(it.authorization.orEmpty().contains(secret))
                assertTrue(it.legacyAuthorization.orEmpty().contains(secret))
            }
        } finally {
            server.close()
        }
    }

    @Test
    fun `收到 HTTP 错误响应后 requestSent 为 true`() = runBlocking {
        val workDir = Files.createTempDirectory("download-http-response-failure").toFile()
        val server = MockWebServer(body = byteArrayOf(), responseCode = 500)
        server.start()
        try {
            val engine =
                DownloadHttpEngine(
                    configureDownloadHttpClient(OkHttpClient.Builder()).build(),
                    elapsedRealtimeMillis = { 0L },
                )
            val failure = captureHttpFailure {
                engine.download(
                    url = server.url("/Videos/item/stream?static=true"),
                    token = null,
                    baseUrl = server.baseUrl,
                    target = File(workDir, "server-error.bin"),
                    existingBytes = 0L,
                    validator = null,
                    expectedTotalBytes = 0L,
                ) { _, _, _, _ ->
                }
            }

            assertTrue(failure.marker?.requestSent == true)
            assertEquals("FAILED", failure.marker?.status)
            assertEquals(DownloadFailureReason.SERVER_ERROR, failure.reason)
        } finally {
            server.close()
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
    redirectTo: String? = null,
    private val responseCode: Int = 200,
) {
    var redirectTo: String? = redirectTo

    data class Request(
        val method: String,
        val path: String,
        val range: String?,
        val ifRange: String?,
        val authorization: String?,
        val legacyAuthorization: String?,
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
                        legacyAuthorization = exchange.requestHeaders.getFirst("X-Emby-Token"),
                    )
                respond(exchange)
            }
            executor = this@MockWebServer.executor
        }

    val requests = Collections.synchronizedList(mutableListOf<Request>())
    val baseUrl: String
        get() = "http://127.0.0.1:${httpServer.address.port}"

    val port: Int
        get() = httpServer.address.port

    fun url(path: String): String = baseUrl + path

    fun start() {
        httpServer.start()
    }

    fun close() {
        httpServer.stop(0)
        executor.shutdownNow()
    }

    private fun respond(exchange: HttpExchange) {
        val redirectTarget = redirectTo
        if (redirectTarget != null && exchange.requestURI.path == "/redirect") {
            exchange.responseHeaders.add("Location", redirectTarget)
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
            return
        }
        if (responseCode !in 200..299) {
            exchange.sendResponseHeaders(responseCode, -1)
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
