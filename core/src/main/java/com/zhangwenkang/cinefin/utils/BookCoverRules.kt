package com.zhangwenkang.cinefin.utils

import java.io.File
import java.util.UUID

/**
 * W59 在线书籍封面自动生成：格式嗅探 / 缓存与失败标记 / 封面计划（纯函数，JVM 单测覆盖）。
 *
 * 口径（用户 2026-10-04 拍板，`DOWNLOAD_PLAN` §22）：
 * - **服务器图优先**（含下载时落盘的本地副本）；没有图就自动生成（未下载的在线书籍也生成）；
 * - 方案与本地书籍一致：PDF 首页 / CBZ 第一图 / EPUB 封面；
 * - 只通过服务器 **Range 请求**读取文件片段（不整本下载）；
 * - **懒生成**（条目可见时一次）+ 磁盘缓存 `files/book_covers/<itemId>.jpg` + 失败标记
 *   `files/book_covers/<itemId>.fail`（失败后不再重试，UI 回退类型占位图）。
 */
object BookCoverRules {

    /** 封面缓存目录（`context.filesDir` 下）。 */
    const val CACHE_DIR = "book_covers"

    /** 生成位图最长边（与本地书籍缩略图同口径）。 */
    const val MAX_SIDE_PX = 512

    /** JPEG 压缩质量。 */
    const val JPEG_QUALITY = 80

    /** 同时生成数量上限（懒生成不阻塞主线程）。 */
    const val MAX_CONCURRENT = 2

    /** 单个 ZIP 条目 / 单张页图的解压体积上限（防超大图 OOM）。 */
    const val MAX_ENTRY_BYTES = 32L * 1024 * 1024

    /** 文件头嗅探结果。 */
    enum class Kind {
        /** `%PDF-`。 */
        PDF,

        /** `PK\x03\x04`：EPUB / CBZ 都是 ZIP，需再看内部结构分流。 */
        ZIP,

        /** 未识别：直接失败占位（不整本下载）。 */
        UNKNOWN,
    }

    /** ZIP 内部结构分流：含 `META-INF/container.xml` = EPUB，否则按 CBZ（页图合集）。 */
    enum class ZipKind {
        EPUB,
        CBZ,
    }

    fun zipKind(hasContainerXml: Boolean): ZipKind =
        if (hasContainerXml) ZipKind.EPUB else ZipKind.CBZ

    /** 封面来源计划（服务器图 → 生成缓存 → 生成 → 占位）。 */
    enum class CoverSource {
        SERVER_IMAGE,
        GENERATED_CACHE,
        GENERATE,
        PLACEHOLDER,
    }

    /**
     * 书籍封面计划（纯函数）。
     *
     * @param serverImageUrl 服务器图片地址（含本地落盘副本的地址）；非空即最高优先。
     * @param generatedPath 已生成的封面缓存路径；非空表示命中缓存。
     * @param generationFailed 失败标记存在（不再重试，直接占位）。
     * @param serverImageUnavailable W69b：服务器图**已确认取不到**（404 / 加载失败）——即使 [serverImageUrl] 非空也跳到「生成缓存
     *   → 生成 → 占位」链路（用户复验：服务器无图时首页书卡要有本地生成封面）。
     * @param remotePdf W77-5：**远端（未下载）+ 无服务器封面 + 文件为 PDF** —— PdfBox 解析远端 PDF 会把整本按 256 KB 块 Range
     *   读完 （W77-4E 实测 0.35 MB/s、离开书架仍存续），因此不走生成，直接回退类型占位。
     */
    fun planCover(
        serverImageUrl: String?,
        generatedPath: String?,
        generationFailed: Boolean,
        serverImageUnavailable: Boolean = false,
        remotePdf: Boolean = false,
    ): CoverSource =
        when {
            !serverImageUnavailable && !serverImageUrl.isNullOrBlank() -> CoverSource.SERVER_IMAGE
            !generatedPath.isNullOrBlank() -> CoverSource.GENERATED_CACHE
            generationFailed -> CoverSource.PLACEHOLDER
            remotePdf -> CoverSource.PLACEHOLDER
            else -> CoverSource.GENERATE
        }

    /**
     * 卡片渲染的本地封面覆盖值（纯函数，书架 / 首页同源）： **服务器图优先**——服务器图存在时返回 null（卡片继续走服务器图）； 缺服务器图时返回已生成的本地封面路径（可能为
     * null = 卡片回退类型占位）。
     */
    fun coverOverride(serverImageUrl: String?, generatedPath: String?): String? =
        if (!serverImageUrl.isNullOrBlank()) null else generatedPath?.takeIf { it.isNotBlank() }

    /**
     * 卡片封面的**显示来源**状态机（纯函数，用户 2026-10-04 第 12 条口径）： 服务器图优先 → 本地封面（已生成缓存 / 本地提取）→ 类型占位；加载失败逐级回落。
     *
     * - [serverFailed] = 服务器图加载失败（如离线）；有本地封面时回落本地，否则占位；
     * - [localFailed] = 本地封面也加载失败 → 占位。
     */
    fun displaySource(
        hasServerImage: Boolean,
        hasLocalCover: Boolean,
        serverFailed: Boolean = false,
        localFailed: Boolean = false,
    ): CoverSource =
        when {
            hasServerImage && !serverFailed -> CoverSource.SERVER_IMAGE
            hasLocalCover && !localFailed -> CoverSource.GENERATED_CACHE
            else -> CoverSource.PLACEHOLDER
        }

    fun cacheDir(filesDir: File): File = File(filesDir, CACHE_DIR)

    /** 生成封面缓存：`files/book_covers/<itemId>.jpg`。 */
    fun cacheFile(filesDir: File, itemId: UUID): File = File(cacheDir(filesDir), "$itemId.jpg")

    /** 失败标记：`files/book_covers/<itemId>.fail`（存在即不再重试）。 */
    fun failureMarker(filesDir: File, itemId: UUID): File = File(cacheDir(filesDir), "$itemId.fail")

    /**
     * 文件头嗅探（纯函数）：PDF 头 `%PDF-`；ZIP 头 `PK\x03\x04`（EPUB / CBZ 共用）。
     *
     * 空 / 过短 / 不匹配 → [Kind.UNKNOWN]，调用方写失败标记后回退类型占位。
     */
    fun detectKind(prefix: ByteArray): Kind {
        if (
            prefix.size >= PDF_MAGIC.size &&
                prefix.copyOfRange(0, PDF_MAGIC.size).contentEquals(PDF_MAGIC)
        ) {
            return Kind.PDF
        }
        if (prefix.size >= ZIP_MAGIC.size) {
            var matched = true
            for (index in ZIP_MAGIC.indices) {
                if (prefix[index] != ZIP_MAGIC[index]) {
                    matched = false
                    break
                }
            }
            if (matched) return Kind.ZIP
        }
        return Kind.UNKNOWN
    }

    /**
     * W77-5：服务器元数据里的原始文件路径是否为 PDF（纯函数）。
     *
     * 只做扩展名判定：`/media/books/foo.pdf` → `true`；大小写不敏感；无扩展名 / null → `false`。 这是「远端 PDF
     * 封面直接占位」的**零请求**判据（书架卡片不为此多发任何 Range 请求）； 文件头嗅探（[detectKind]）仍是内容层兜底（改扩展名 / 命名不符的文件）。
     */
    fun isPdfPath(path: String?): Boolean =
        path?.trim()?.substringAfterLast('.', "")?.equals("pdf", ignoreCase = true) == true

    private val PDF_MAGIC = "%PDF-".toByteArray(Charsets.US_ASCII)

    private val ZIP_MAGIC = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
}

/** EPUB 封面定位（纯函数，单测覆盖）：container.xml → OPF → 封面条目 href。 */
object EpubCoverRules {

    data class ManifestItem(
        val id: String,
        val href: String,
        val mediaType: String,
        val properties: String,
    )

    /**
     * 封面条目选择顺序：
     * 1. `properties` 含 `cover-image`（EPUB3 标准）；
     * 2. `<meta name="cover" content="id"/>` 指向的 manifest id（EPUB2 主流写法）；
     * 3. 兜底启发式：图片条目里 id / href 含 `cover`。
     */
    fun selectCoverHref(items: List<ManifestItem>, coverMetaId: String?): String? {
        items
            .firstOrNull { item ->
                item.properties.split(Regex("\\s+")).any { it == "cover-image" }
            }
            ?.let {
                return it.href
            }
        if (!coverMetaId.isNullOrBlank()) {
            items
                .firstOrNull { it.id == coverMetaId }
                ?.let {
                    return it.href
                }
        }
        return items
            .firstOrNull { item ->
                item.mediaType.startsWith("image/", ignoreCase = true) &&
                    (item.id.contains("cover", ignoreCase = true) ||
                        item.href.contains("cover", ignoreCase = true))
            }
            ?.href
    }

    /**
     * 相对 href → OPF 所在目录下的归档内路径（`OPS/` + `../cover.jpg` → `cover.jpg`）。
     *
     * 处理 `%20` 等百分号编码、查询串 / 片段与多余分隔符；越出根目录的 `..` 直接丢弃。
     */
    fun resolveHref(opfPath: String, href: String): String {
        val cleanHref = href.substringBefore('#').substringBefore('?')
        val decoded = decodePercent(cleanHref)
        if (decoded.startsWith("/")) return normalizePath(decoded)
        val baseDir = opfPath.substringBeforeLast('/', "")
        return normalizePath(if (baseDir.isEmpty()) decoded else "$baseDir/$decoded")
    }

    private fun decodePercent(value: String): String = runCatching {
        java.net.URLDecoder.decode(value.replace("+", "%2B"), "UTF-8")
    }
        .getOrDefault(value)

    private fun normalizePath(path: String): String {
        val parts = ArrayDeque<String>()
        for (segment in path.replace('\\', '/').split('/')) {
            when (segment) {
                "",
                "." -> Unit
                ".." -> if (parts.isNotEmpty()) parts.removeLast()
                else -> parts.addLast(segment)
            }
        }
        return parts.joinToString("/")
    }
}
