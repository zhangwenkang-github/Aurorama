package com.zhangwenkang.cinefin.book.presentation.reader

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import com.zhangwenkang.cinefin.utils.RemoteComicArchive
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.math.min
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W77-2：远端 CBZ 页源——**纯 JVM + 本地 HTTP Range 服务**，量化「打开 / 取某一页」的请求数与字节数。
 *
 * 不连真机、不连测试服务器：样本是本测试现场拼出的 CBZ，经 [RemoteComicArchive]（复用生产 `HttpByteSource` + `ZipArchiveReader`）以
 * HTTP Range 拉取。验收口径（W77-R1 spike §2.2）：首屏 = 中央目录（个位数请求） + 当前页（不随页序增长）， 且**不整本下载**。
 */
class RemoteComicPageSourceTest {

    private val client = OkHttpClient.Builder().build()

    @Test
    fun `打开远端 CBZ 只取中央目录 不整本下载`() {
        withSample(stored = true) { file, server ->
            server.reset()
            RemoteComicArchive.openRemote(client, server.url).use { archive ->
                val names = archive.entries.map { it.name }
                // 条目名与本地 ZipFile 逐一一致（远端解析口径与本地一致）。
                ZipFile(file).use { local ->
                    assertEquals(local.entries().toList().map { it.name }.sorted(), names.sorted())
                }
                assertTrue("打开后请求数应个位数：${server.requests.get()}", server.requests.get() <= 6)
                assertTrue(
                    "打开不应整本下载：服务端字节=${server.bytesServed.get()} / 文件=${file.length()}",
                    server.bytesServed.get() < file.length(),
                )
            }
        }
    }

    @Test
    fun `取第一页与最后一页请求数有界且不随页序增长`() {
        withSample(stored = true) { file, server ->
            RemoteComicArchive.openRemote(client, server.url).use { archive ->
                val pages = remoteComicPageNames(archive)
                assertTrue(pages.isNotEmpty())

                server.reset()
                val firstBytes = archive.readBytes(pages.first(), PAGE_MAX_BYTES)
                val firstRequests = server.requests.get()
                assertEquals(PAGE_SIZE, firstBytes.size)

                server.reset()
                val lastBytes = archive.readBytes(pages.last(), PAGE_MAX_BYTES)
                val lastRequests = server.requests.get()
                assertEquals(PAGE_SIZE, lastBytes.size)

                // 单页 ≈ PAGE_SIZE，256 KB 分块 → 每页 ≤ 3 个请求（含跨块边界）；末页与首页同量级。
                assertTrue("首页请求数=$firstRequests", firstRequests in 1..4)
                assertTrue("末页请求数=$lastRequests", lastRequests in 1..4)
                assertTrue(
                    "末页不应比首页显著更贵：首页=$firstRequests 末页=$lastRequests",
                    lastRequests <= firstRequests + 2,
                )
                // 只取两页的字节数远小于整本。
                assertTrue(server.bytesServed.get() < file.length())
            }
        }
    }

    @Test
    fun `DEFLATE 条目同样按需解出`() {
        withSample(stored = false) { _, server ->
            RemoteComicArchive.openRemote(client, server.url).use { archive ->
                val pages = remoteComicPageNames(archive)
                assertTrue(pages.isNotEmpty())
                assertEquals(PAGE_SIZE, archive.readBytes(pages.first(), PAGE_MAX_BYTES).size)
            }
        }
    }

    @Test
    fun `页序过滤与本地一致并排除非页条目`() {
        val file = buildSampleCbz(stored = true, extraEntries = true)
        RemoteComicArchive.openFile(file).use { archive ->
            val pages = remoteComicPageNames(archive)
            assertEquals(
                listOf("001.jpg", "002.jpg", "010.jpg", "011.jpg"),
                pages,
            )
        }
        file.delete()
    }

    @Test
    fun `在途请求登记与注销配平`() {
        withSample(stored = true) { _, server ->
            val registry = InFlightRequestRegistry()
            RemoteComicArchive.openRemote(client, server.url, callRegistry = registry).use { archive
                ->
                assertEquals(0, registry.inFlightCount())
                archive.readBytes(remoteComicPageNames(archive).first(), PAGE_MAX_BYTES)
                // 每次请求都登记并在结束后注销：不能有悬挂 Call（否则会泄漏 / 误取消）。
                assertEquals(0, registry.inFlightCount())
            }
        }
    }

    @Test
    fun `取消在途请求会立即中止阻塞读`() {
        val file = buildSampleCbz(stored = true)
        val gate = CountDownLatch(1)
        BlockingRangeServer(file, gate).use { server ->
            val registry = InFlightRequestRegistry()
            val archive = RemoteComicArchive.openRemote(client, server.url, callRegistry = registry)
            val pool = Executors.newSingleThreadExecutor()
            try {
                // 先读中央目录（不阻塞），拿到页名。
                val page = remoteComicPageNames(archive).first()
                server.blockNextResponse()
                val future =
                    pool.submit<Throwable?> {
                        runCatching { archive.readBytes(page, PAGE_MAX_BYTES) }.exceptionOrNull()
                    }
                assertTrue("请求应已发起并登记", server.awaitRequest(5_000))
                assertTrue("在途请求应被登记", registry.inFlightCount() > 0)
                registry.cancelAll()
                val failure = future.get(5, TimeUnit.SECONDS)
                assertTrue("取消后读取应失败而不是跑完", failure != null)
                assertEquals(0, registry.inFlightCount())
            } finally {
                gate.countDown()
                pool.shutdownNow()
                archive.close()
            }
        }
    }

    // ---------------------------------------------------------------- 样本

    private fun withSample(stored: Boolean, block: (File, RangeServer) -> Unit) {
        val file = buildSampleCbz(stored = stored)
        RangeServer(file).use { server -> block(file, server) }
        file.delete()
    }

    /** 拼一个 CBZ：页图 [PAGE_SIZE] 字节，文件头带可识别的 JPEG SOI，另有非页条目（字体 / 元数据 / 隐藏）。 */
    private fun buildSampleCbz(stored: Boolean, extraEntries: Boolean = false): File {
        val entries = mutableListOf<Pair<String, ByteArray>>()
        if (extraEntries) {
            entries += "Fonts/caveatbrush-regular.ttf" to ByteArray(256) { it.toByte() }
            entries += "AndasGame.acbf" to "<acbf/>".toByteArray()
            entries += "ComicInfo.xml" to "<ComicInfo/>".toByteArray()
            entries += "Thumbs.db" to ByteArray(8)
            entries += "__MACOSX/001.jpg" to pageBytes(seed = 9)
            entries += ".hidden.jpg" to pageBytes(seed = 8)
        }
        entries += "001.jpg" to pageBytes(seed = 1)
        entries += "002.jpg" to pageBytes(seed = 2)
        entries += "010.jpg" to pageBytes(seed = 10)
        entries += "011.jpg" to pageBytes(seed = 11)
        val bytes = zip(entries, stored)
        val file = Files.createTempFile("w77cbz", ".cbz").toFile()
        file.writeBytes(bytes)
        file.deleteOnExit()
        return file
    }

    private fun pageBytes(seed: Int): ByteArray {
        val bytes = ByteArray(PAGE_SIZE)
        // JPEG SOI + 伪数据；内容不参与断言，只保证体积 / 可解出。
        bytes[0] = 0xFF.toByte()
        bytes[1] = 0xD8.toByte()
        var value = seed * 31 + 7
        for (index in 2 until bytes.size) {
            value = value * 1103515245 + 12345
            bytes[index] = (value ushr 16).toByte()
        }
        return bytes
    }

    private fun zip(entries: List<Pair<String, ByteArray>>, stored: Boolean): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            for ((name, bytes) in entries) {
                val entry = ZipEntry(name)
                if (stored) {
                    entry.method = ZipEntry.STORED
                    entry.size = bytes.size.toLong()
                    entry.compressedSize = bytes.size.toLong()
                    entry.crc = CRC32().apply { update(bytes) }.value
                }
                zip.putNextEntry(entry)
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    private companion object {
        /** 单页 300 KB：> 一个 256 KB 分块，覆盖跨块边界。 */
        const val PAGE_SIZE = 300 * 1024

        const val PAGE_MAX_BYTES = 48L * 1024 * 1024
    }
}

/** 本地 HTTP Range 服务（Jellyfin 口径：HEAD 返回 405 → 走 `GET Range: bytes=0-0` 合成）。 */
private open class RangeServer(private val file: File, private val headAllowed: Boolean = false) :
    AutoCloseable {

    private val randomAccess = RandomAccessFile(file, "r")
    private val total = randomAccess.length()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

    val requests = AtomicInteger()
    val bytesServed = AtomicLong()

    val url: String

    init {
        server.createContext("/book") { exchange -> handle(exchange) }
        server.executor = null
        server.start()
        url = "http://127.0.0.1:${server.address.port}/book"
    }

    fun reset() {
        requests.set(0)
        bytesServed.set(0)
    }

    /** 供子类在响应前阻塞（取消测试用）。 */
    protected open fun beforeResponse() = Unit

    private fun handle(exchange: HttpExchange) {
        requests.incrementAndGet()
        beforeResponse()
        val rangeHeader = exchange.requestHeaders.getFirst("Range")
        try {
            if (exchange.requestMethod == "HEAD") {
                if (!headAllowed) {
                    // Jellyfin 的 Download 端点对 HEAD 返回 405（W77-1 实测）。
                    exchange.sendResponseHeaders(405, -1)
                    return
                }
                exchange.responseHeaders.add("Accept-Ranges", "bytes")
                exchange.responseHeaders.add("Content-Length", total.toString())
                exchange.sendResponseHeaders(200, -1)
                return
            }
            var start = 0L
            var end = total - 1
            var partial = false
            if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                val spec = rangeHeader.removePrefix("bytes=")
                val dash = spec.indexOf('-')
                start = spec.substring(0, dash).toLongOrNull() ?: 0L
                end =
                    spec.substring(dash + 1).takeIf { it.isNotEmpty() }?.toLongOrNull()
                        ?: (total - 1)
                end = min(end, total - 1)
                partial = true
            }
            if (start > end) {
                exchange.sendResponseHeaders(416, -1)
                return
            }
            val length = (end - start + 1).toInt()
            exchange.responseHeaders.add("Accept-Ranges", "bytes")
            exchange.responseHeaders.add("Content-Type", "application/octet-stream")
            if (partial) {
                exchange.responseHeaders.add("Content-Range", "bytes $start-$end/$total")
                exchange.sendResponseHeaders(206, length.toLong())
            } else {
                exchange.sendResponseHeaders(200, length.toLong())
            }
            val buffer = ByteArray(min(length, 64 * 1024).coerceAtLeast(1))
            var written = 0
            while (written < length) {
                val count = min(buffer.size, length - written)
                val read =
                    synchronized(randomAccess) {
                        randomAccess.seek(start + written)
                        randomAccess.read(buffer, 0, count)
                    }
                if (read <= 0) break
                exchange.responseBody.write(buffer, 0, read)
                written += read
            }
            bytesServed.addAndGet(written.toLong())
        } catch (ignored: Throwable) {
            // 客户端取消会打断连接，忽略即可。
        } finally {
            runCatching { exchange.responseBody.close() }
            runCatching { exchange.close() }
        }
    }

    override fun close() {
        server.stop(0)
        runCatching { randomAccess.close() }
    }
}

/** 可阻塞下一次响应的 Range 服务（取消测试）：命中 [blockNextResponse] 后等 [gate] 放行。 */
private class BlockingRangeServer(file: File, private val gate: CountDownLatch) :
    RangeServer(file) {

    private val requestArrived = CountDownLatch(1)
    private val blocking = java.util.concurrent.atomic.AtomicBoolean(false)

    fun blockNextResponse() {
        blocking.set(true)
    }

    fun awaitRequest(timeoutMs: Long): Boolean =
        requestArrived.await(timeoutMs, TimeUnit.MILLISECONDS)

    override fun beforeResponse() {
        if (blocking.compareAndSet(true, false)) {
            requestArrived.countDown()
            gate.await(10, TimeUnit.SECONDS)
        }
    }
}
