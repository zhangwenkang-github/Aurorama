package com.zhangwenkang.cinefin.utils

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
