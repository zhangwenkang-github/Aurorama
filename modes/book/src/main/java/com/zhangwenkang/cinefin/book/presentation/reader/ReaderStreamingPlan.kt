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
 * W77-2：远端首块能识别的流式格式。
 *
 * - [Pdf]：`%PDF-`（当前引擎不流式，回退整本下载）；
 * - [Epub]：ZIP 且判定为 EPUB（Readium 路径）；
 * - [ComicArchive]：ZIP 且非 EPUB（远端页源路径）；
 * - [Unknown]：无法判定（回退整本下载）。
 */
enum class RemoteStreamKind {
    Pdf,
    Epub,
    ComicArchive,
    Unknown,
}

/**
 * W77-2：远端首块的格式判定（纯函数）。
 *
 * 只读一小段文件头（几百字节到 1 KB）即可分流，避免为了判定 EPUB / CBZ 先读一遍中央目录：
 * - ZIP（`PK\x03\x04`）：
 *     - 第一个本地文件头条目名为 `mimetype`（EPUB 规范强制首条目、STORED）或首块含 `META-INF/container.xml` →
 *       [RemoteStreamKind.Epub]；
 *     - 其余 → [RemoteStreamKind.ComicArchive]（非规范 EPUB 由调用方读中央目录后用 [remoteZipIsEpub] 复核兜底）；
 * - 前 1 KiB 含 `%PDF-` → [RemoteStreamKind.Pdf]；
 * - 其余 → [RemoteStreamKind.Unknown]。
 *
 * ZIP 魔数先判（与 [sniffBookFormat] 同口径）：压缩包前 1 KB 恰好含 `%PDF-` 字节时不会误判成 PDF。
 */
fun classifyRemoteHeader(prefix: ByteArray): RemoteStreamKind {
    if (prefix.startsWithBytes(ZIP_MAGIC)) {
        if (zipFirstEntryName(prefix) == "mimetype") return RemoteStreamKind.Epub
        if (prefix.containsSequence(EPUB_CONTAINER_MAGIC, prefix.size)) {
            return RemoteStreamKind.Epub
        }
        return RemoteStreamKind.ComicArchive
    }
    if (prefix.size >= PDF_MAGIC.size && prefix.containsSequence(PDF_MAGIC, prefix.size)) {
        return RemoteStreamKind.Pdf
    }
    return RemoteStreamKind.Unknown
}

/**
 * W77-2：读中央目录后复核 ZIP 是否为 EPUB（纯函数）。
 *
 * 首块启发式（[classifyRemoteHeader]）可能把「首条目不是 `mimetype`」的非规范 EPUB 误判成 CBZ；读中央目录拿到
 * **全部**条目名后用它兜底（`META-INF/container.xml` / `mimetype` 命中即 EPUB）。
 */
fun remoteZipIsEpub(entryNames: Collection<String>): Boolean = entryNames.any { name ->
    name == "META-INF/container.xml" ||
        name == "mimetype" ||
        name.endsWith("/META-INF/container.xml")
}

/** 读取 ZIP 第一个本地文件头的条目名（`PK\x03\x04` + 偏移 26 的名字长度）；非 ZIP / 越界返回 null。 */
internal fun zipFirstEntryName(prefix: ByteArray): String? {
    if (prefix.size < ZIP_LOCAL_HEADER_BYTES) return null
    if (!prefix.startsWithBytes(ZIP_MAGIC)) return null
    val nameLength = u16le(prefix, 26)
    if (nameLength <= 0 || ZIP_LOCAL_HEADER_BYTES + nameLength > prefix.size) return null
    return String(prefix, ZIP_LOCAL_HEADER_BYTES, nameLength, Charsets.UTF_8)
}

private fun ByteArray.startsWithBytes(sequence: ByteArray): Boolean {
    if (size < sequence.size) return false
    for (index in sequence.indices) {
        if (this[index] != sequence[index]) return false
    }
    return true
}

private fun u16le(bytes: ByteArray, offset: Int): Int =
    (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

/** 在 [length] 字节的有效范围内查找字节序列（避免把未读满的尾部当成数据）。 */
private fun ByteArray.containsSequence(sequence: ByteArray, length: Int): Boolean {
    val lastStart = length - sequence.size
    if (lastStart < 0) return false
    for (start in 0..lastStart) {
        var matched = true
        for (offset in sequence.indices) {
            if (this[start + offset] != sequence[offset]) {
                matched = false
                break
            }
        }
        if (matched) return true
    }
    return false
}

private val PDF_MAGIC = "%PDF-".toByteArray(Charsets.US_ASCII)

private val ZIP_MAGIC = byteArrayOf(0x50, 0x4B, 0x03, 0x04)

private val EPUB_CONTAINER_MAGIC = "META-INF/container.xml".toByteArray(Charsets.US_ASCII)

/** ZIP 本地文件头固定长度（名字 / 扩展区随其后）。 */
private const val ZIP_LOCAL_HEADER_BYTES = 30

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
