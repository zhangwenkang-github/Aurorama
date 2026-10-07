package com.zhangwenkang.cinefin.book.presentation.reader

import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Request
import org.readium.r2.shared.util.DebugError
import org.readium.r2.shared.util.Try
import org.readium.r2.shared.util.http.HttpClient
import org.readium.r2.shared.util.http.HttpError
import org.readium.r2.shared.util.http.HttpRequest
import org.readium.r2.shared.util.http.HttpResponse
import org.readium.r2.shared.util.http.HttpStatus
import org.readium.r2.shared.util.http.HttpStreamResponse
import org.readium.r2.shared.util.mediatype.MediaType
import timber.log.Timber

/**
 * W77 阅读流式：Readium `HttpClient` 的 OkHttp 实现。
 *
 * 为什么不用 `DefaultHttpClient`：
 * 1. 它走 `java.net.HttpURLConnection` + **系统信任库**，拿不到本应用的 TOFU 自签证书信任（用户服务器可能自签）；
 * 2. 它没有注入额外请求头的参数，无法附加 Jellyfin 的 `X-Emby-Token`。
 *
 * 取消语义（对齐 W64）：`stream()` 在协程里注册「Job 完成 → `call.cancel()`」，因此离开阅读页 （`viewModelScope` 取消）会**立刻**中止在途
 * HTTP；[close] 再做一次兜底（`Dispatcher.cancelAll()`）。
 *
 * 本客户端持有自己的 `Dispatcher` / `ConnectionPool`，与「整本下载」共用同一个底层 OkHttp 客户端但 **不共享取消面**，`cancelAll()`
 * 不会误伤下载任务。
 */
class ReadiumRemoteHttpClient(
    baseClient: OkHttpClient,
    private val token: String?,
    private val inFlight: InFlightRequestRegistry? = null,
) : HttpClient {

    private val client =
        baseClient.newBuilder().dispatcher(Dispatcher()).connectionPool(ConnectionPool()).build()

    override suspend fun stream(request: HttpRequest): Try<HttpStreamResponse, HttpError> =
        withContext(Dispatchers.IO) {
            // Jellyfin 的 Download 端点不支持 HEAD（实测 405），而 Readium 的长度探测走 HEAD。
            if (request.method == HttpRequest.Method.HEAD) {
                return@withContext headByRangeProbe(request)
            }
            val okRequest =
                try {
                    request.toOkHttpRequest(token)
                } catch (error: Throwable) {
                    return@withContext Try.failure(
                        HttpError.IO(IOException("无法构造远程阅读请求：${error.message}", error))
                    )
                }

            val call = client.newCall(okRequest)
            inFlight?.register(call)
            val cancelHandle =
                currentCoroutineContext()[Job]?.invokeOnCompletion { cause ->
                    if (cause != null) call.cancel()
                }

            logRequest(okRequest)
            val response =
                try {
                    call.execute()
                } catch (error: Throwable) {
                    cancelHandle?.dispose()
                    inFlight?.unregister(call)
                    currentCoroutineContext().ensureActive()
                    return@withContext Try.failure(error.toHttpError())
                }

            val mediaType =
                response.header("Content-Type")?.let { value ->
                    runCatching { MediaType(value) }.getOrNull()
                }
            Timber.d(
                "阅读流式 %s %s → %d · %s B",
                okRequest.method,
                okRequest.url.encodedPath,
                response.code,
                response.header("Content-Length") ?: "?",
            )

            if (!response.isSuccessful) {
                val body = runCatching { response.body.bytes() }.getOrDefault(ByteArray(0))
                cancelHandle?.dispose()
                inFlight?.unregister(call)
                return@withContext Try.failure(
                    HttpError.ErrorResponse(HttpStatus(response.code), mediaType, body)
                )
            }

            val httpResponse =
                HttpResponse(
                    request = request,
                    url = request.url,
                    statusCode = HttpStatus(response.code),
                    headers = response.headers.toReadiumHeaders(),
                    mediaType = mediaType,
                )
            Try.success(
                HttpStreamResponse(
                    httpResponse,
                    response.body.byteStream().disposeOnClose(cancelHandle) {
                        inFlight?.unregister(call)
                    },
                )
            )
        }

    /**
     * HEAD 容错：用 `GET Range: bytes=0-0` 合成一个 HEAD 响应（空 body）。
     *
     * 为什么必须这样：Jellyfin 10.11 的 `GET /Items/{id}/Download` 对 **HEAD 返回 405**（2026-10-07 实测）， 而
     * Readium 的 `HttpResource.length()` / `properties()` 依赖 `HEAD` + `Content-Length` → 远端 EPUB 会直接
     * `ReadError`。这里取 `Content-Range: bytes 0-0/<total>` 的总长，合成为 200 + `Content-Length` （语义与真正的
     * HEAD 一致，且顺带证明服务器支持 Range）。
     *
     * 服务器若忽略 Range 返回 200：退化为用它的 `Content-Length`（此时后续 `read(range)` 会因拿不到 206
     * 而失败，调用方按「远程不可用」回退整本下载，不会退化成整本远程读取）。
     */
    private suspend fun headByRangeProbe(request: HttpRequest): Try<HttpStreamResponse, HttpError> {
        val okRequest =
            request
                .toOkHttpRequest(token)
                .newBuilder()
                .method("GET", null)
                .header("Range", "bytes=0-0")
                .build()
        val call = client.newCall(okRequest)
        inFlight?.register(call)
        val cancelHandle =
            currentCoroutineContext()[Job]?.invokeOnCompletion { cause ->
                if (cause != null) call.cancel()
            }

        logRequest(okRequest)
        val response =
            try {
                call.execute()
            } catch (error: Throwable) {
                cancelHandle?.dispose()
                inFlight?.unregister(call)
                currentCoroutineContext().ensureActive()
                return Try.failure(error.toHttpError())
            }

        try {
            val mediaType =
                response.header("Content-Type")?.let { value ->
                    runCatching { MediaType(value) }.getOrNull()
                }
            Timber.d(
                "阅读流式长度探测(GET Range) %s → %d · CR=%s · CL=%s",
                okRequest.url.encodedPath,
                response.code,
                response.header("Content-Range") ?: "-",
                response.header("Content-Length") ?: "-",
            )
            if (!response.isSuccessful) {
                val body = runCatching { response.body.bytes() }.getOrDefault(ByteArray(0))
                return Try.failure(
                    HttpError.ErrorResponse(HttpStatus(response.code), mediaType, body)
                )
            }
            val total =
                contentRangeTotal(response.header("Content-Range"))
                    ?: response.header("Content-Length")?.toLongOrNull()
            if (total == null || total <= 0L) {
                return Try.failure(
                    HttpError.IO(IOException("无法确定远程书籍大小（缺少 Content-Length / Content-Range）"))
                )
            }
            val headers = buildMap {
                put("Content-Length", listOf(total.toString()))
                mediaType?.let { put("Content-Type", listOf(it.toString())) }
                // 206 证明服务器支持 Range（Readium `read(range)` 要求 206）。
                if (response.code == 206) put("Accept-Ranges", listOf("bytes"))
            }
            val httpResponse =
                HttpResponse(
                    request = request,
                    url = request.url,
                    statusCode = HttpStatus.Success,
                    headers = headers,
                    mediaType = mediaType,
                )
            return Try.success(HttpStreamResponse(httpResponse, ByteArrayInputStream(ByteArray(0))))
        } finally {
            cancelHandle?.dispose()
            inFlight?.unregister(call)
            runCatching { response.close() }
        }
    }

    /** 请求模式取证：进入请求前记一行（方法 / 路径 / Range；**不含令牌**）。 */
    private fun logRequest(request: Request) {
        Timber.d(
            "阅读流式请求 %s %s Range=%s",
            request.method,
            request.url.encodedPath,
            request.header("Range") ?: "-",
        )
    }

    /**
     * 离开阅读页时的兜底：取消本客户端所有在途请求并清空连接池。
     *
     * 幂等，可重复调用。
     */
    fun close() {
        runCatching { client.dispatcher.cancelAll() }
        runCatching { client.connectionPool.evictAll() }
        runCatching { client.dispatcher.executorService.shutdown() }
    }
}

/** Readium 请求 → OkHttp 请求：方法 + 头（含令牌）。 */
private fun HttpRequest.toOkHttpRequest(token: String?): Request {
    require(body == null) { "远程阅读只支持无请求体的 GET / HEAD" }
    val builder = Request.Builder().url(url.toString()).method(method.name, null)
    readiumHeadersWithToken(headers, token).forEach { (name, values) ->
        values.forEach { value -> builder.addHeader(name, value) }
    }
    return builder.build()
}

/** OkHttp 响应头 → Readium 响应头（保留同名多值）。 */
private fun okhttp3.Headers.toReadiumHeaders(): Map<String, List<String>> {
    val result = LinkedHashMap<String, List<String>>(size)
    for (index in 0 until size) {
        val name = name(index)
        val existing = result[name]
        result[name] = if (existing == null) listOf(value(index)) else existing + value(index)
    }
    return result
}

/** 关闭流时释放「Job 完成 → cancel」回调（并注销在途登记），避免回调泄漏。 */
private fun InputStream.disposeOnClose(
    handle: kotlinx.coroutines.DisposableHandle?,
    onClose: (() -> Unit)? = null,
): InputStream {
    if (handle == null && onClose == null) return this
    val source = this
    return object : FilterInputStream(source) {
        override fun close() {
            try {
                super.close()
            } finally {
                handle?.dispose()
                onClose?.invoke()
            }
        }
    }
}

/** OkHttp / JVM 异常 → Readium `HttpError`（保留可读信息，便于错误态文案）。 */
private fun Throwable.toHttpError(): HttpError =
    when (this) {
        is java.net.SocketTimeoutException -> HttpError.Timeout(DebugError("请求超时：$message", null))
        is java.net.UnknownHostException,
        is java.net.ConnectException -> HttpError.Unreachable(DebugError("无法连接服务器：$message", null))
        is javax.net.ssl.SSLException ->
            HttpError.SslHandshake(DebugError("TLS 握手失败：$message", null))
        is IOException -> HttpError.IO(this)
        else -> HttpError.IO(IOException(message ?: "远程阅读请求失败", this))
    }
