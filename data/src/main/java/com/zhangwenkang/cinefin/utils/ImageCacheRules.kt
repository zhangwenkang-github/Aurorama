package com.zhangwenkang.cinefin.utils

import java.net.URLEncoder
import java.util.UUID

/**
 * W66：本地图片缓存（`files/images/<id>/primary`）的过期替换规则（纯函数 + 元数据编解码，单测覆盖）。
 *
 * 用户口径：缩略图必须本地缓存，且要有过期替换机制——**绝不「有文件就跳过」**。 判定 = 文件缺失 / 元数据缺失 / 源 URL 变化 / 超 TTL → 重新拉取并用 `.part` +
 * rename 原子替换。
 *
 * 元数据落盘在 `files/images/<id>/primary.meta`（[encodeMeta] / [decodeMeta]），记录来源 URL、ETag、 Last-Modified
 * 与拉取时间，供条件请求（`If-None-Match` / `If-Modified-Since`）与判定使用。
 */
object ImageCacheRules {

    /** 缓存有效期：30 天（用户 2026-10-04 口径建议值，集中定义）。 */
    const val TTL_MS: Long = 30L * 24 * 60 * 60 * 1000

    /** 缩略图生成尺寸 / 压缩质量的唯一来源（在线书籍封面与本地媒体缩略图共用）。 */
    const val THUMBNAIL_MAX_SIDE_PX: Int = 512
    const val THUMBNAIL_JPEG_QUALITY: Int = 80

    /** Jellyfin 图片位；[pathSegment] 与服务器路由一致。 */
    enum class ArtworkVariant(val pathSegment: String) {
        PRIMARY("Primary"),
        BACKDROP("Backdrop"),
        LOGO("Logo"),
    }

    /** 存量 URL 大小写兼容：`FindroidImages` 一直用 `items/`，下载 / 音乐链路用 `Items/`。 */
    enum class ItemPathCase {
        LOWER,
        UPPER,
    }

    /** 卡片缩略图最终显示来源（服务器图 → 本地封面 → 类型占位）。 */
    enum class ArtworkSource {
        SERVER_IMAGE,
        LOCAL_COVER,
        PLACEHOLDER,
    }

    data class ArtworkSelection(
        val source: ArtworkSource,
        val serverModel: Any? = null,
        val localModel: String? = null,
    ) {
        val model: Any?
            get() =
                when (source) {
                    ArtworkSource.SERVER_IMAGE -> serverModel
                    ArtworkSource.LOCAL_COVER -> localModel
                    ArtworkSource.PLACEHOLDER -> null
                }
    }

    /**
     * 条目图片 URL（单一构造点）。
     *
     * [pathCase] 只用于兼容两条存量缓存路径；两条路由在 Jellyfin 上大小写不敏感。
     */
    fun itemImageUrl(
        baseUrl: String,
        itemId: UUID,
        variant: ArtworkVariant,
        tag: String?,
        index: Int? = null,
        pathCase: ItemPathCase = ItemPathCase.UPPER,
    ): String? {
        if (tag.isNullOrBlank()) return null
        val itemsSegment =
            when (pathCase) {
                ItemPathCase.LOWER -> "items"
                ItemPathCase.UPPER -> "Items"
            }
        val imageIndex = index?.let { "/$it" }.orEmpty()
        val encodedTag = URLEncoder.encode(tag, Charsets.UTF_8.name()).replace("+", "%20")
        return "${baseUrl.trimEnd('/')}/$itemsSegment/$itemId/Images/" +
            "${variant.pathSegment}$imageIndex?tag=$encodedTag"
    }

    /** 主图 URL（下载 / 音乐链路）。 */
    fun primaryImageUrl(
        baseUrl: String,
        itemId: UUID,
        tag: String?,
        pathCase: ItemPathCase = ItemPathCase.UPPER,
    ): String? = itemImageUrl(baseUrl, itemId, ArtworkVariant.PRIMARY, tag, pathCase = pathCase)

    /** 离线 / 本地落图相对路径：`images/<id>/<variant>`（小写，与既有缓存目录一致）。 */
    fun localImagePath(itemId: UUID, variant: ArtworkVariant): String =
        "images/$itemId/${variant.pathSegment.lowercase()}"

    /** 缩略图显示来源（纯函数，卡片 / 书籍封面共用）。 */
    fun artworkSource(
        hasServerImage: Boolean,
        hasLocalCover: Boolean,
        serverFailed: Boolean = false,
        localFailed: Boolean = false,
    ): ArtworkSource =
        when {
            hasServerImage && !serverFailed -> ArtworkSource.SERVER_IMAGE
            hasLocalCover && !localFailed -> ArtworkSource.LOCAL_COVER
            else -> ArtworkSource.PLACEHOLDER
        }

    fun selectArtwork(
        serverImage: Any?,
        localCover: String?,
        serverFailed: Boolean = false,
        localFailed: Boolean = false,
        preferLocal: Boolean = false,
    ): ArtworkSelection {
        val source =
            if (preferLocal && !localCover.isNullOrBlank()) {
                ArtworkSource.LOCAL_COVER
            } else {
                artworkSource(
                    hasServerImage = serverImage != null,
                    hasLocalCover = !localCover.isNullOrBlank(),
                    serverFailed = serverFailed,
                    localFailed = localFailed,
                )
            }
        return ArtworkSelection(
            source = source,
            serverModel = serverImage,
            localModel = localCover,
        )
    }

    /** 服务器图存在时不再叠加本地生成图；否则用本地封面（既有书籍封面口径）。 */
    fun localCoverOverride(serverImageUrl: String?, generatedPath: String?): String? =
        if (!serverImageUrl.isNullOrBlank()) null else generatedPath?.takeIf { it.isNotBlank() }

    /** 图片缓存元数据（[sourceUrl] = 落盘时的服务器图片地址）。 */
    data class Meta(
        val sourceUrl: String,
        val etag: String? = null,
        val lastModified: String? = null,
        val fetchedAt: Long,
    )

    /** 判定结果（带原因，便于日志与单测断言）。 */
    enum class Decision {
        KEEP,
        REFETCH_FILE_MISSING,
        REFETCH_META_MISSING,
        REFETCH_SOURCE_CHANGED,
        REFETCH_EXPIRED,
    }

    /**
     * 缓存判定（纯函数）：
     * - 文件缺失 / 空文件 → 重拉；
     * - 元数据缺失 → 重拉（历史存量缓存没有 `.meta`，首次访问即补齐）；
     * - 当前源 URL 已知且与元数据不同 → 重拉（服务器地址 / 端口变化等）；
     * - 距上次拉取超过 [ttlMillis] → 重拉；
     * - 否则保留。
     *
     * [currentSourceUrl] 传 null 表示「本次不比对 URL」（例如离线 / 未查询服务器时）。
     */
    fun decide(
        fileExists: Boolean,
        fileSizeBytes: Long,
        meta: Meta?,
        currentSourceUrl: String?,
        nowMillis: Long,
        ttlMillis: Long = TTL_MS,
    ): Decision =
        when {
            !fileExists || fileSizeBytes <= 0L -> Decision.REFETCH_FILE_MISSING
            meta == null -> Decision.REFETCH_META_MISSING
            !currentSourceUrl.isNullOrBlank() &&
                meta.sourceUrl.isNotBlank() &&
                // Jellyfin 路由大小写不敏感（worker 侧 `items/` 与仓库侧 `Items/` 指向同一图）：
                // 比较忽略大小写，避免两条落图路径互相判定「URL 变化」而反复重拉。
                !meta.sourceUrl.equals(currentSourceUrl, ignoreCase = true) ->
                Decision.REFETCH_SOURCE_CHANGED
            nowMillis - meta.fetchedAt > ttlMillis -> Decision.REFETCH_EXPIRED
            else -> Decision.KEEP
        }

    /** 是否需要重新拉取（[Decision.KEEP] 之外全部要）。 */
    fun shouldRefetch(decision: Decision): Boolean = decision != Decision.KEEP

    /**
     * UI 层内存缓存键（W66）：本地缓存路径带上文件 mtime——过期替换（`.part` + rename）后 mtime 变化， Coil 内存缓存键随之变化，下载页 /
     * 离线页立即显示新图；远程 URL / null 返回 null（沿用 Coil 默认键）。
     */
    fun artworkMemoryCacheKey(path: String?): String? {
        if (path.isNullOrBlank() || path.contains("://")) return null
        val lastModified = runCatching { java.io.File(path).lastModified() }.getOrDefault(0L)
        return "$path#$lastModified"
    }

    /** 元数据编码（`key=value` 行格式；URL 含 `=` 由 `limit = 2` 容忍）。 */
    fun encodeMeta(meta: Meta): String = buildString {
        appendLine("sourceUrl=${meta.sourceUrl}")
        appendLine("etag=${meta.etag.orEmpty()}")
        appendLine("lastModified=${meta.lastModified.orEmpty()}")
        appendLine("fetchedAt=${meta.fetchedAt}")
    }

    /** 元数据解码；缺 `sourceUrl` / `fetchedAt` 或格式非法 → null（按元数据缺失处理）。 */
    fun decodeMeta(text: String?): Meta? {
        if (text.isNullOrBlank()) return null
        val values =
            text
                .lineSequence()
                .mapNotNull { line ->
                    val index = line.indexOf('=')
                    if (index <= 0) null else line.substring(0, index) to line.substring(index + 1)
                }
                .toMap()
        val sourceUrl = values["sourceUrl"]?.takeIf { it.isNotBlank() } ?: return null
        val fetchedAt = values["fetchedAt"]?.toLongOrNull() ?: return null
        return Meta(
            sourceUrl = sourceUrl,
            etag = values["etag"]?.takeIf { it.isNotBlank() },
            lastModified = values["lastModified"]?.takeIf { it.isNotBlank() },
            fetchedAt = fetchedAt,
        )
    }
}
