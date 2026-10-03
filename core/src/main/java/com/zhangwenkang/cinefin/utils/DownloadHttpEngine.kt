package com.zhangwenkang.cinefin.utils

import android.os.SystemClock
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** W50：允许携带下载令牌的原始主机（重定向到其他主机时剥离凭据）。 */
internal data class DownloadAllowedTokenHost(val host: String, val port: Int)

/**
 * W50：把 `X-Emby-Token` 限定在请求原始主机上。
 *
 * OkHttp 默认只对 `Authorization` 做跨主机剥离；自定义令牌头需要本拦截器兜底，避免服务器重定向到第三方 CDN 时泄露凭据。
 */
internal class DownloadRedirectTokenInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val allowed =
            request.tag(DownloadAllowedTokenHost::class.java) ?: return chain.proceed(request)
        val sameHost =
            request.url.host.equals(allowed.host, ignoreCase = true) &&
                request.url.port == allowed.port
        return if (sameHost) {
            chain.proceed(request)
        } else {
            chain.proceed(request.newBuilder().removeHeader("X-Emby-Token").build())
        }
    }
}

/** W50 传输层失败：携带可读的失败分类，供引擎决定「续传 / 重下 / 停止」。 */
internal class DownloadHttpException(
    val reason: DownloadFailureReason,
    message: String?,
    cause: Throwable? = null,
) : IOException(message, cause)

/** W50 一次传输尝试的结果。 */
internal data class DownloadAttemptResult(
    val downloadedBytes: Long,
    val totalBytes: Long,
    val validator: String?,
)

/**
 * W50 自研下载传输层：OkHttp + HTTP Range 真断点续传。
 *
 * - 残片偏移 > 0：带 `Range: bytes=N-`；有校验器时带 `If-Range`（内容变化服务器回 200 → 截断重下）；
 * - 服务器回 200（忽略 Range / 校验器失效）：从 0 截断重写（安全重下）；
 * - 服务器回 206：校验 `Content-Range` 起点与本地偏移一致后再追加；
 * - 416：残片已 >= 服务器总长时按完成处理，否则报 [DownloadFailureReason.CANNOT_RESUME] 由引擎安全重下；
 * - 401 / 403：鉴权失败；5xx / 其他 4xx：服务器错误；连接中断：网络错误。
 *
 * 令牌只在同主机请求上使用 `X-Emby-Token`（跨主机重定向不携带凭据）；同时保留 Jellyfin 直链自带的 `api_key` 查询参数（与旧 DownloadManager
 * 行为一致）。
 */
internal class DownloadHttpEngine(private val client: OkHttpClient) {

    suspend fun download(
        url: String,
        token: String?,
        baseUrl: String?,
        target: File,
        existingBytes: Long,
        validator: String?,
        expectedTotalBytes: Long,
        /** W57 限速：≤ 0 = 不限速；按本次会话平均速率节流（读块后计算等待）。 */
        speedLimitBytesPerSecond: Long = 0L,
        onProgress:
            suspend (
                downloadedBytes: Long,
                totalBytes: Long,
                validator: String?,
                resumed: Boolean,
            ) -> Unit,
    ): DownloadAttemptResult =
        withContext(Dispatchers.IO) {
            target.parentFile?.mkdirs()
            val fileLength = if (target.isFile) target.length() else 0L
            var offset = existingBytes.coerceIn(0L, fileLength)

            val request =
                try {
                    buildRequest(url, token, baseUrl, offset, validator)
                } catch (e: IllegalArgumentException) {
                    throw DownloadHttpException(
                        DownloadFailureReason.SERVER_ERROR,
                        "非法下载地址：${e.message}",
                        e,
                    )
                }
            val call = client.newCall(request)
            val cancellationHandle =
                coroutineContext[Job]?.invokeOnCompletion { cause ->
                    if (cause is CancellationException) call.cancel()
                }
            try {
                call.execute().use { response ->
                    val code = response.code
                    when {
                        code == 401 || code == 403 ->
                            throw DownloadHttpException(
                                DownloadFailureReason.AUTHENTICATION,
                                "HTTP $code",
                            )
                        code == 416 -> {
                            val total = parseContentRangeTotal(response.header("Content-Range"))
                            if (offset > 0L && total != null && offset >= total) {
                                return@withContext DownloadAttemptResult(
                                    downloadedBytes = offset,
                                    totalBytes = total,
                                    validator = response.validator(),
                                )
                            }
                            throw DownloadHttpException(
                                DownloadFailureReason.CANNOT_RESUME,
                                "HTTP 416",
                            )
                        }
                        code !in 200..299 ->
                            throw DownloadHttpException(
                                DownloadFailureReason.SERVER_ERROR,
                                "HTTP $code",
                            )
                    }

                    val body =
                        response.body
                            ?: throw DownloadHttpException(
                                DownloadFailureReason.SERVER_ERROR,
                                "响应没有正文",
                            )
                    val resumed = code == 206
                    val declaredLength = body.contentLength()
                    val totalBytes =
                        if (resumed) {
                            val rangeStart =
                                parseContentRangeStart(response.header("Content-Range"))
                            if (rangeStart != null && rangeStart != offset) {
                                throw DownloadHttpException(
                                    DownloadFailureReason.CANNOT_RESUME,
                                    "Content-Range 起点 $rangeStart 与本地偏移 $offset 不一致",
                                )
                            }
                            parseContentRangeTotal(response.header("Content-Range"))
                                ?: declaredLength.takeIf { it >= 0L }?.plus(offset)
                                ?: expectedTotalBytes
                        } else {
                            // 服务器忽略 Range（或不支持）：从头安全重下。
                            offset = 0L
                            declaredLength.takeIf { it >= 0L }
                                ?: expectedTotalBytes.takeIf { it > 0L }
                                ?: 0L
                        }

                    val validatorHeader = response.validator()
                    val input = body.byteStream()
                    RandomAccessFile(target, "rw").use { file ->
                        if (offset == 0L) file.setLength(0L)
                        file.seek(offset)
                        var downloaded = offset
                        onProgress(downloaded, totalBytes, validatorHeader, resumed)

                        val buffer = ByteArray(BUFFER_BYTES)
                        var lastFlushAt = SystemClock.elapsedRealtime()
                        val sessionStartedAt = SystemClock.elapsedRealtime()
                        while (true) {
                            coroutineContext.ensureActive()
                            val read = input.read(buffer)
                            if (read == -1) break
                            file.write(buffer, 0, read)
                            downloaded += read
                            onProgress(downloaded, totalBytes, validatorHeader, resumed)
                            // W57 限速：按本次会话平均速率节流（0 = 不限速）。
                            val waitMillis =
                                DownloadThrottle.waitMillis(
                                    bytesSinceStart = downloaded - offset,
                                    elapsedMillis =
                                        SystemClock.elapsedRealtime() - sessionStartedAt,
                                    limitBytesPerSecond = speedLimitBytesPerSecond,
                                )
                            if (waitMillis > 0L) delay(waitMillis)
                            val now = SystemClock.elapsedRealtime()
                            if (now - lastFlushAt >= FLUSH_INTERVAL_MS) {
                                file.fd.sync()
                                lastFlushAt = now
                            }
                        }
                        file.fd.sync()

                        if (declaredLength >= 0L && downloaded - offset < declaredLength) {
                            throw DownloadHttpException(
                                DownloadFailureReason.NETWORK_UNAVAILABLE,
                                "连接提前结束（${downloaded - offset}/$declaredLength 字节）",
                            )
                        }
                        return@withContext DownloadAttemptResult(
                            downloadedBytes = downloaded,
                            totalBytes = totalBytes,
                            validator = validatorHeader,
                        )
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (http: DownloadHttpException) {
                throw http
            } catch (io: IOException) {
                if (!coroutineContext.isActive) throw CancellationException("下载已取消")
                throw DownloadHttpException(
                    DownloadFailureReason.NETWORK_UNAVAILABLE,
                    io.message,
                    io,
                )
            }
        }

    private fun buildRequest(
        url: String,
        token: String?,
        baseUrl: String?,
        offset: Long,
        validator: String?,
    ): Request {
        val builder = Request.Builder().url(url).get()
        // 只在同主机时附带令牌，避免跟随重定向把凭据发给第三方主机。
        if (!token.isNullOrBlank() && isSameHost(url, baseUrl)) {
            url.toHttpUrlOrNull()?.let { httpUrl ->
                builder
                    .tag(
                        DownloadAllowedTokenHost::class.java,
                        DownloadAllowedTokenHost(host = httpUrl.host, port = httpUrl.port),
                    )
                    .header("X-Emby-Token", token)
            }
        }
        if (offset > 0L) {
            builder.header("Range", "bytes=$offset-")
            if (!validator.isNullOrBlank()) {
                builder.header("If-Range", validator)
            }
        }
        return builder.build()
    }

    private fun okhttp3.Response.validator(): String? =
        header("ETag")?.takeIf { it.isNotBlank() }
            ?: header("Last-Modified")?.takeIf { it.isNotBlank() }

    private companion object {
        /** 读写缓冲（64 KiB）。 */
        const val BUFFER_BYTES = 64 * 1024

        /** 落盘间隔：每 4 MB `fsync` 一次，兼顾断点安全与 IO 开销。 */
        const val FLUSH_INTERVAL_MS = 4_000L

        fun isSameHost(url: String, baseUrl: String?): Boolean {
            if (baseUrl.isNullOrBlank()) return false
            val a = url.toHttpUrlOrNull() ?: return false
            val b = baseUrl.toHttpUrlOrNull() ?: return false
            return a.host.equals(b.host, ignoreCase = true) && a.port == b.port
        }
    }
}

/** 解析 `Content-Range: bytes start-end/total` 的起始字节；非法返回 null。 */
internal fun parseContentRangeStart(header: String?): Long? {
    val match = FULL_RANGE_REGEX.find(header ?: return null) ?: return null
    return match.groupValues[1].toLongOrNull()
}

/**
 * 解析 `Content-Range` 的总字节：
 * - `bytes start-end/total`（206）；
 * - `bytes *\/total`（416，用于判断残片是否已覆盖完整内容）；
 * - `*` / 非法返回 null。
 */
internal fun parseContentRangeTotal(header: String?): Long? {
    val value = header ?: return null
    FULL_RANGE_REGEX.find(value)?.let {
        return it.groupValues[3].toLongOrNull()
    }
    UNSATISFIED_RANGE_REGEX.find(value)?.let {
        return it.groupValues[1].toLongOrNull()
    }
    return null
}

private val FULL_RANGE_REGEX =
    Regex("""^\s*bytes\s+(\d+)-(\d+|\*)/(\d+|\*)\s*$""", RegexOption.IGNORE_CASE)
private val UNSATISFIED_RANGE_REGEX =
    Regex("""^\s*bytes\s+\*/(\d+|\*)\s*$""", RegexOption.IGNORE_CASE)
