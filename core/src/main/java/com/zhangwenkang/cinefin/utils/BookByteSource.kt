package com.zhangwenkang.cinefin.utils

import com.tom_roush.pdfbox.io.RandomAccessRead
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.LinkedHashMap
import kotlin.math.min
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * W59 书籍封面：可随机访问的字节源（本地文件 / HTTP Range / 内存）。
 *
 * 在线书籍只通过 **HTTP Range** 读取必要片段（ZIP 中央目录、PDF 交叉引用与首页内容），**不整本下载**。
 */
internal interface ByteSource : Closeable {
    fun length(): Long

    /** 读取 `[offset, offset + length)`；越界部分自动截断。 */
    fun readAt(offset: Long, length: Int): ByteArray
}

/** 内存字节源（JVM 单测 / 已完整读入的小文件）。 */
internal class ByteArrayByteSource(private val bytes: ByteArray) : ByteSource {
    override fun length(): Long = bytes.size.toLong()

    override fun readAt(offset: Long, length: Int): ByteArray {
        if (offset >= bytes.size || length <= 0) return ByteArray(0)
        val start = offset.toInt()
        val end = min(bytes.size, start + length)
        return bytes.copyOfRange(start, end)
    }

    override fun close() = Unit
}

/** 本地文件字节源（已下载书籍：`files/books/<itemId>.book`）。 */
internal class FileByteSource(private val file: File) : ByteSource {
    private val randomAccess = RandomAccessFile(file, "r")

    override fun length(): Long = randomAccess.length()

    override fun readAt(offset: Long, length: Int): ByteArray {
        if (length <= 0) return ByteArray(0)
        return synchronized(randomAccess) {
            if (offset >= randomAccess.length()) return@synchronized ByteArray(0)
            randomAccess.seek(offset)
            val available = (randomAccess.length() - offset).toInt().coerceAtLeast(0)
            val size = min(available, length)
            val buffer = ByteArray(size)
            randomAccess.readFully(buffer)
            buffer
        }
    }

    override fun close() {
        runCatching { randomAccess.close() }
    }
}

/**
 * HTTP Range 字节源：按 [chunkSize] 分块 + LRU 缓存（默认 8 MB 上限）。
 *
 * - 长度探测：HEAD 的 `Content-Length`，或 `Range: bytes=0-0` 的 `Content-Range` 总长；
 * - 服务器忽略 Range 返回 200 时：只有请求第 0 块且文件 ≤ 一个块才接受（否则报错走失败占位， 避免「不支持 Range」的服务器被整本下载）。
 */
internal class HttpByteSource(
    private val client: OkHttpClient,
    url: String,
    private val headers: Map<String, String> = emptyMap(),
    private val chunkSize: Int = DEFAULT_CHUNK_BYTES,
    private val maxCachedChunks: Int = DEFAULT_MAX_CHUNKS,
) : ByteSource {

    private val httpUrl = url.toHttpUrlOrNull() ?: throw IOException("书籍下载地址非法：$url")
    private val totalSize: Long by lazy { probeSize() }
    private var closed = false

    private val chunks =
        object : LinkedHashMap<Int, ByteArray>(16, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<Int, ByteArray>?
            ): Boolean = size > maxCachedChunks
        }

    override fun length(): Long = totalSize

    override fun readAt(offset: Long, count: Int): ByteArray {
        check(!closed) { "HttpByteSource 已关闭" }
        val total = length()
        if (count <= 0 || offset >= total) return ByteArray(0)
        val end = min(total, offset + count)
        val out = ByteArray((end - offset).toInt())
        var copied = 0
        while (copied < out.size) {
            val position = offset + copied
            val index = (position / chunkSize).toInt()
            val chunk = chunkAt(index)
            val inChunk = (position % chunkSize).toInt()
            val available = chunk.size - inChunk
            if (available <= 0) throw IOException("HTTP Range 返回字节数不足（chunk $index）")
            val size = min(available, out.size - copied)
            System.arraycopy(chunk, inChunk, out, copied, size)
            copied += size
        }
        return out
    }

    override fun close() {
        closed = true
        synchronized(chunks) { chunks.clear() }
    }

    private fun chunkAt(index: Int): ByteArray =
        synchronized(chunks) { chunks[index] ?: fetchChunk(index).also { chunks[index] = it } }

    private fun fetchChunk(index: Int): ByteArray {
        val start = index.toLong() * chunkSize
        val end = min(totalSize - 1, start + chunkSize - 1)
        if (start > end) return ByteArray(0)
        val request =
            Request.Builder()
                .url(httpUrl)
                .apply { headers.forEach { (name, value) -> header(name, value) } }
                .header("Range", "bytes=$start-$end")
                .build()
        client.newCall(request).execute().use { response ->
            when (response.code) {
                206 -> return response.body.bytes()
                200 -> {
                    if (index != 0) throw IOException("服务器忽略 Range 请求（HTTP 200）")
                    val body = response.body.bytes()
                    if (body.size > chunkSize) {
                        throw IOException("服务器不支持 Range 且文件过大（${body.size} 字节）")
                    }
                    return body
                }
                else -> throw IOException("HTTP ${response.code}")
            }
        }
    }

    private fun probeSize(): Long {
        val head =
            Request.Builder()
                .url(httpUrl)
                .apply { headers.forEach { (name, value) -> header(name, value) } }
                .head()
                .build()
        client.newCall(head).execute().use { response ->
            if (response.isSuccessful) {
                response.header("Content-Length")?.toLongOrNull()?.let { if (it > 0L) return it }
            }
        }
        val probe =
            Request.Builder()
                .url(httpUrl)
                .apply { headers.forEach { (name, value) -> header(name, value) } }
                .header("Range", "bytes=0-0")
                .build()
        client.newCall(probe).execute().use { response ->
            if (response.code == 206) {
                parseContentRangeTotal(response.header("Content-Range"))?.let {
                    return it
                }
            }
            if (response.isSuccessful) {
                response.header("Content-Length")?.toLongOrNull()?.let { if (it > 0L) return it }
            }
        }
        throw IOException("无法确定远程书籍大小（服务器不支持 Range / Content-Length）")
    }

    companion object {
        /** 分块大小：256 KB（PDF 顺序解析 / ZIP 随机读取的折中）。 */
        const val DEFAULT_CHUNK_BYTES = 256 * 1024

        /** 缓存块数上限（8 MB）。 */
        const val DEFAULT_MAX_CHUNKS = 32
    }
}

/**
 * 把 [ByteSource] 适配成 PdfBox 的 `RandomAccessRead`（PDF 首页解析用）。
 *
 * PdfBox 会以大量小步长读 / 随机 seek，读取经 [ByteSource] 的分块缓存后不会放大成整本下载。
 */
internal class PdfBoxRandomAccess(private val source: ByteSource) : RandomAccessRead {

    private var position = 0L
    private var closed = false

    override fun read(): Int {
        val buffer = ByteArray(1)
        val read = read(buffer, 0, 1)
        return if (read <= 0) -1 else buffer[0].toInt() and 0xFF
    }

    override fun read(byteArray: ByteArray): Int = read(byteArray, 0, byteArray.size)

    override fun read(byteArray: ByteArray, offset: Int, length: Int): Int {
        check(!closed) { "PdfBoxRandomAccess 已关闭" }
        if (length <= 0) return 0
        if (position >= source.length()) return -1
        val size =
            min(min(length.toLong(), source.length() - position), MAX_READ_BYTES.toLong()).toInt()
        val bytes = source.readAt(position, size)
        if (bytes.isEmpty()) return -1
        System.arraycopy(bytes, 0, byteArray, offset, bytes.size)
        position += bytes.size
        return bytes.size
    }

    override fun getPosition(): Long = position

    override fun seek(position: Long) {
        this.position = position.coerceAtLeast(0L)
    }

    override fun length(): Long = source.length()

    override fun isClosed(): Boolean = closed

    override fun peek(): Int {
        val saved = position
        val value = read()
        position = saved
        return value
    }

    override fun rewind(amount: Int) {
        position = (position - amount).coerceAtLeast(0L)
    }

    override fun readFully(length: Int): ByteArray {
        if (length > MAX_READ_BYTES) throw IOException("单次读取过大：$length")
        val buffer = ByteArray(length)
        var copied = 0
        while (copied < length) {
            val read = read(buffer, copied, length - copied)
            if (read < 0) throw IOException("读取越界")
            copied += read
        }
        return buffer
    }

    override fun isEOF(): Boolean = position >= source.length()

    override fun available(): Int =
        min(source.length() - position, Int.MAX_VALUE.toLong()).toInt().coerceAtLeast(0)

    override fun close() {
        closed = true
    }

    private companion object {
        const val MAX_READ_BYTES = 8 * 1024 * 1024
    }
}
