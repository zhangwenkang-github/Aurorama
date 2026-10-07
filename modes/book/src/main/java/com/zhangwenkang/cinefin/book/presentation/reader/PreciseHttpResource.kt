package com.zhangwenkang.cinefin.book.presentation.reader

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.readium.r2.shared.util.AbsoluteUrl
import org.readium.r2.shared.util.DebugError
import org.readium.r2.shared.util.Try
import org.readium.r2.shared.util.asset.DefaultResourceFactory
import org.readium.r2.shared.util.data.ReadError
import org.readium.r2.shared.util.http.HttpError
import org.readium.r2.shared.util.mediatype.MediaType
import org.readium.r2.shared.util.resource.Resource
import org.readium.r2.shared.util.resource.ResourceFactory
import timber.log.Timber

/**
 * W77：远程资产的**精确** `Resource`（有界 Range + 读完即关）。
 *
 * 为什么不用 Readium 自带的 `HttpResource`（2026-10-07 K60 实测）： `HttpResource.read(range)` 只把 `range.first`
 * 变成 `Range: bytes=N-`（**开放式**）——Jellyfin 于是从 N 一路发送到文件末尾（229 MB 的 EPUB 实测响应头 `Content-Length:
 * 229242026`），而客户端只读它需要的几百字节 就丢弃响应：响应体读不完 → OkHttp 连接无法复用（实测每次都重新 TLS 握手 2.5–4 s，还撞到 30 s 读超时），
 * 服务端也在空转写缓冲。
 *
 * 这里改成两块组合拳：
 * 1. **有界 Range**（`bytes=first-last`）+ 读完即关 —— 普通请求只传需要的字节，连接可复用；
 * 2. **1 MB 块缓存（LRU）** —— ZIP 通道按 8 KB 小步长读，直接透传会把 324 KB 的中央目录拆成约 40 个请求， 对齐成整块后中央目录 + 尾部 +
 *    首章只需个位数请求。
 */
class PreciseHttpResource(
    private val client: OkHttpClient,
    private val token: String?,
    override val sourceUrl: AbsoluteUrl,
    private val inFlight: InFlightRequestRegistry? = null,
    private val blockBytes: Int = DEFAULT_BLOCK_BYTES,
    private val maxCachedBlocks: Int = DEFAULT_MAX_CACHED_BLOCKS,
) : Resource {

    private val probeMutex = Mutex()
    private var probe: ProbeResult? = null

    /**
     * 块缓存（LRU）。
     *
     * ZIP 通道是按 8 KB 的小步长读的：把「有界 Range」原样透传会把 324 KB 的中央目录拆成约 40 个请求， 在单请求 1.4–4 s 的服务端上直接爆炸。按
     * [blockBytes] 对齐取整块后，中央目录 + 尾部 + 首章一共只需 个位数请求；块大小与 LRU 上限共同约束内存占用（默认 1 MB × 12 = 12 MB 上限）。
     */
    private val cacheMutex = Mutex()
    private val blocks =
        object : LinkedHashMap<Int, ByteArray>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, ByteArray>?) =
                size > maxCachedBlocks
        }

    override suspend fun length(): Try<Long, ReadError> =
        withContext(Dispatchers.IO) {
            try {
                Try.success(probe().totalBytes)
            } catch (cancellation: kotlinx.coroutines.CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                Try.failure(error.toReadError())
            }
        }

    override suspend fun read(range: LongRange?): Try<ByteArray, ReadError> =
        withContext(Dispatchers.IO) {
            try {
                Try.success(if (range == null) readFully() else readRange(range))
            } catch (cancellation: kotlinx.coroutines.CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                Try.failure(error.toReadError())
            }
        }

    override suspend fun properties(): Try<Resource.Properties, ReadError> =
        withContext(Dispatchers.IO) {
            try {
                val probed = probe()
                Try.success(
                    Resource.Properties(
                        buildMap {
                            probed.mediaType?.let { put(MEDIA_TYPE_KEY, it) }
                            sourceUrl.filename
                                ?.takeIf { it.isNotBlank() }
                                ?.let { put(FILENAME_KEY, it) }
                        }
                    )
                )
            } catch (cancellation: kotlinx.coroutines.CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                Try.failure(error.toReadError())
            }
        }

    override fun close() = Unit

    // ------------------------------------------------------------------ 内部

    private suspend fun readRange(range: LongRange): ByteArray {
        val total = probe().totalBytes
        val first = range.first.coerceAtLeast(0L)
        val last = if (range.last == Long.MAX_VALUE) total - 1 else min(range.last, total - 1)
        if (last < first) return ByteArray(0)

        val output = ByteArray((last - first + 1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        var position = first
        var copied = 0
        while (position <= last) {
            val index = (position / blockBytes).toInt()
            val block = blockAt(index, total)
            val offsetInBlock = (position % blockBytes).toInt()
            if (offsetInBlock >= block.size) break
            val size = min(block.size - offsetInBlock, (last - position + 1).toInt())
            System.arraycopy(block, offsetInBlock, output, copied, size)
            copied += size
            position += size
        }
        return if (copied == output.size) output else output.copyOf(copied)
    }

    /** 取一块 `[index * blockBytes, +blockBytes)`：命中缓存直接返回，否则整块拉取（有界 + 读完即关）。 */
    private suspend fun blockAt(index: Int, total: Long): ByteArray {
        cacheMutex
            .withLock { blocks[index] }
            ?.let {
                return it
            }
        val start = index.toLong() * blockBytes
        val end = min(total - 1, start + blockBytes - 1)
        val request = baseRequest().newBuilder().header("Range", rangeHeader(start..end)).build()
        val bytes =
            execute(request) { response ->
                if (response.code != 206) throw UnsupportedRangeException(response.code)
                response.body.byteStream().use { input -> input.readUpTo(-1) }
            }
        Timber.d("阅读流式块 #%d → %d B", index, bytes.size)
        cacheMutex.withLock { blocks[index] = bytes }
        return bytes
    }

    private suspend fun readFully(): ByteArray {
        // 只有「调用方明确要整本」才会走到这里（ZIP 通道始终是有界读）；给一条日志便于取证。
        Timber.w("阅读流式：请求整本远程资源（%s）", sourceUrl)
        return execute(baseRequest()) { response ->
            response.body.byteStream().use { input -> input.readUpTo(-1) }
        }
    }

    /**
     * 长度探测：Jellyfin 的 Download 端点不支持 HEAD（405），改用 **第一块**（`GET Range: bytes=0-<block-1>`） 的
     * `Content-Range .../<total>` 拿总长，顺带把第一块写进缓存。
     *
     * 一次请求同时解决「长度 + 文件头解码（嗅探 / container.xml / OPF）」，在这台单请求 1.4–20 s 的服务器上 直接省掉一次往返。结果缓存（ZIP
     * 通道每次读都要 `length()`）。
     */
    private suspend fun probe(): ProbeResult = probeMutex.withLock {
        probe
            ?: run {
                val request =
                    baseRequest()
                        .newBuilder()
                        .header("Range", rangeHeader(0L until blockBytes.toLong()))
                        .build()
                val first =
                    execute(request) { response ->
                        if (response.code != 206) throw UnsupportedRangeException(response.code)
                        val total =
                            contentRangeTotal(response.header("Content-Range"))
                                ?: throw IOException("无法确定远程书籍大小（缺少 Content-Range）")
                        FirstBlock(
                            totalBytes = total,
                            mediaType = response.header("Content-Type")?.toMediaTypeOrNull(),
                            bytes = response.body.byteStream().use { it.readUpTo(-1) },
                        )
                    }
                cacheMutex.withLock { blocks[0] = first.bytes }
                val probed = ProbeResult(first.totalBytes, first.mediaType)
                Timber.d(
                    "阅读流式长度探测（含首块）%s → %d B · %s",
                    sourceUrl.filename,
                    probed.totalBytes,
                    probed.mediaType ?: "-",
                )
                probe = probed
                probed
            }
    }

    private fun baseRequest(): Request {
        val builder = Request.Builder().url(sourceUrl.toString()).get()
        if (!token.isNullOrBlank()) builder.header(ACCESS_TOKEN_HEADER, token)
        return builder.build()
    }

    private suspend fun <T> execute(request: Request, block: (Response) -> T): T {
        val call = client.newCall(request)
        inFlight?.register(call)
        val cancelHandle =
            currentCoroutineContext()[Job]?.invokeOnCompletion { cause ->
                if (cause != null) {
                    Timber.d("阅读流式：在途请求被取消 %s", request.url.encodedPath)
                    call.cancel()
                }
            }
        Timber.d(
            "阅读流式 %s %s Range=%s",
            request.method,
            request.url.encodedPath,
            request.header("Range") ?: "-",
        )
        try {
            val response =
                try {
                    call.execute()
                } catch (error: Throwable) {
                    currentCoroutineContext().ensureActive()
                    throw error
                }
            val result = response.use { block(it) }
            return result
        } finally {
            cancelHandle?.dispose()
            inFlight?.unregister(call)
        }
    }

    private data class ProbeResult(val totalBytes: Long, val mediaType: MediaType?)

    /** 长度探测顺带取回的第一块（同时用于嗅探 / container.xml / OPF）。 */
    private data class FirstBlock(
        val totalBytes: Long,
        val mediaType: MediaType?,
        val bytes: ByteArray,
    )
}

/** 服务器不支持 Range（非 206）——与 Readium `HttpResource` 的语义一致，走「远程不可用」回退。 */
private class UnsupportedRangeException(val statusCode: Int) :
    IOException("服务器不支持 Range 请求（HTTP $statusCode）")

/** HTTP 错误映射成 Readium `HttpError`，便于上层识别（超时 / 不可达 / TLS）。 */
private fun Throwable.toReadError(): ReadError =
    when (this) {
        is UnsupportedRangeException ->
            ReadError.UnsupportedOperation(DebugError(message ?: "服务器不支持 Range 请求", null))
        is IOException -> ReadError.Access(HttpError.IO(this))
        else -> ReadError.Access(HttpError.IO(IOException(message ?: "远程读取失败", this)))
    }

/** `Content-Type` → Readium `MediaType`（非法值返回 null，不影响读取）。 */
private fun String.toMediaTypeOrNull(): MediaType? = runCatching { MediaType(this) }.getOrNull()

/** 读到 EOF 或读满 [limit]（-1 = 读到 EOF）为止。 */
private fun InputStream.readUpTo(limit: Int): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(READ_BUFFER_BYTES)
    var remaining = limit
    while (remaining != 0) {
        val size = if (remaining < 0) buffer.size else minOf(buffer.size, remaining)
        val read = read(buffer, 0, size)
        if (read == -1) break
        output.write(buffer, 0, read)
        if (remaining > 0) remaining -= read
    }
    return output.toByteArray()
}

private const val READ_BUFFER_BYTES = 64 * 1024

/** 块大小：1 MB（中央目录 / 尾部 / 章节条目都落在一到几块里）。 */
private const val DEFAULT_BLOCK_BYTES = 1024 * 1024

/** 块缓存上限：12 块 = 12 MB。 */
private const val DEFAULT_MAX_CACHED_BLOCKS = 12

/** `Resource.Properties` 的键（与 `FilePropertiesKt` 保持一致）。 */
private const val MEDIA_TYPE_KEY = "mediaType"
private const val FILENAME_KEY = "filename"

/**
 * W77：http(s) 走 [PreciseHttpResource]，其余（file / content）仍交给 Readium 默认实现。
 *
 * 这样 `AssetRetriever` 拿到的 EPUB 根资源就是「精确 Range」版本，ZIP 通道的每次 `read(range)` 都变成有界请求。
 */
class RemoteFirstResourceFactory(
    private val delegate: DefaultResourceFactory,
    private val client: OkHttpClient,
    private val token: String?,
    private val inFlight: InFlightRequestRegistry? = null,
) : ResourceFactory {
    override suspend fun create(url: AbsoluteUrl): Try<Resource, ResourceFactory.Error> =
        if (url.isHttp) {
            Try.success(PreciseHttpResource(client, token, url, inFlight))
        } else {
            delegate.create(url)
        }
}

/**
 * W77：远程读取的在途请求登记表。
 *
 * 协程 Job 的取消回调在真机上被观察到**没有**中止已经在读响应体的 OkHttp call（实测：返回后 3.4 s 请求仍完成）， 因此这里额外登记 call 本体，阅读页
 * `onCleared` 时确定性 `cancelAll()` —— 与 W64 同口径的「最小兜底」。
 */
class InFlightRequestRegistry {
    private val calls = Collections.newSetFromMap(ConcurrentHashMap<Call, Boolean>())

    fun register(call: Call) {
        calls.add(call)
    }

    fun unregister(call: Call) {
        calls.remove(call)
    }

    /** 取消所有在途请求（幂等，重复调用安全）。 */
    fun cancelAll() {
        calls.toList().forEach { call -> runCatching { call.cancel() } }
        calls.clear()
    }
}
