package com.zhangwenkang.cinefin.book.presentation.reader

import java.util.UUID

/**
 * W77 阅读流式：打开来源决策（纯函数，便于单测与回归）。
 *
 * - [Local]：本地已有整本文件（W64 的 2 ms 级路径，行为零变化）；
 * - [RemoteCandidate]：未下载 + 联网 + 有会话，**先试**远程流式（本卡只覆盖 EPUB，格式判定在嗅探后）；
 * - [Download]：其余情况（离线 / 无会话）走既有「整本下载后打开」。
 */
enum class ReaderOpenSource {
    Local,
    RemoteCandidate,
    Download,
}

/**
 * 打开来源决策。
 *
 * @param hasLocalFile 应用私有目录里已有完整书籍文件
 * @param online 当前有活跃网络
 * @param remoteAvailable 能拿到远程读取入口（服务器地址 + 令牌）
 */
fun decideReaderOpenSource(
    hasLocalFile: Boolean,
    online: Boolean,
    remoteAvailable: Boolean,
): ReaderOpenSource =
    when {
        hasLocalFile -> ReaderOpenSource.Local
        online && remoteAvailable -> ReaderOpenSource.RemoteCandidate
        else -> ReaderOpenSource.Download
    }

/**
 * 远程资产嗅探结果的处置：只有确认为 EPUB 才走流式，其余（PDF / CBZ / 未知）一律回退整本下载。
 *
 * 本卡范围只做 EPUB（PDF 的 PdfBox 解析必须整本、CBZ 走页窗口自研视图），因此这里显式收窄。
 */
fun shouldStreamRemoteAsset(isEpub: Boolean): Boolean = isEpub

/**
 * 后台整本下载完成后的热切换判定。
 *
 * @param remoteDocumentOpen 当前阅读页打开的确实是远程流式文档
 * @param sameItem 当前打开的书籍与下载完成的书籍一致
 * @param downloadedBytes 本地文件字节数（<= 0 视为无效，不切换）
 */
fun shouldHotSwapToLocal(
    remoteDocumentOpen: Boolean,
    sameItem: Boolean,
    downloadedBytes: Long,
): Boolean = remoteDocumentOpen && sameItem && downloadedBytes > 0L

/**
 * W77：远程书籍下载地址（纯函数）。
 *
 * 与「整本下载」路径同源：`{baseUrl}/Items/{itemId}/Download`；服务器支持 Range（206）与 HEAD。
 */
fun readerDownloadUrl(baseUrl: String, itemId: UUID): String =
    "${baseUrl.trimEnd('/')}/Items/$itemId/Download"

/**
 * W77：把访问令牌并进 Readium 请求头（纯函数）。
 *
 * 服务器对所有媒体请求都要求 `X-Emby-Token`；Readium 自己只带 `Range` / `Accept` 等标准头， 令牌必须在适配层注入。大小写不敏感地替换已有同名头。
 */
fun readiumHeadersWithToken(
    headers: Map<String, List<String>>,
    token: String?,
): Map<String, List<String>> {
    if (token.isNullOrBlank()) return headers
    val merged = LinkedHashMap<String, List<String>>(headers.size + 1)
    headers.forEach { (name, values) ->
        if (!name.equals(ACCESS_TOKEN_HEADER, ignoreCase = true)) merged[name] = values
    }
    merged[ACCESS_TOKEN_HEADER] = listOf(token)
    return merged
}

/** Jellyfin 访问令牌请求头（与 `ReaderRepositoryImpl` 的下载路径保持一致）。 */
const val ACCESS_TOKEN_HEADER: String = "X-Emby-Token"

/**
 * W77：解析 `Content-Range` 里的资源总长（纯函数）。
 *
 * 兼容 `bytes 0-0/229242149`（206）与 `bytes *&#47;229242149`（416）两种形态；缺失 / 非法 / `*` 一律返回 null。
 * 用于「服务器不支持 HEAD」时用 `GET Range: bytes=0-0` 合成长度探测。
 */
fun contentRangeTotal(contentRange: String?): Long? {
    val value = contentRange?.trim().orEmpty()
    if (value.isEmpty()) return null
    return value.substringAfterLast('/', "").trim().toLongOrNull()?.takeIf { it > 0L }
}

/**
 * W77：把 Readium 的有界读区间转成**有界** `Range` 头（纯函数）。
 *
 * `0..1023` → `bytes=0-1023`；`123..Long.MAX_VALUE` → `bytes=123-`（调用方明确要读到结尾）。
 *
 * 关键在于**保留终点**：Readium 自带的 `HttpResource` 会把终点丢掉，只发 `bytes=123-`，于是服务器把整本 （229 MB
 * 实测）都推过来、客户端却只读几百字节就丢弃——响应读不完导致连接不可复用（每次重新 TLS 握手）。
 */
fun rangeHeader(range: LongRange): String =
    if (range.last == Long.MAX_VALUE) {
        "bytes=${range.first}-"
    } else {
        "bytes=${range.first}-${range.last}"
    }
