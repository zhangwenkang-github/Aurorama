package com.zhangwenkang.cinefin.utils

/**
 * 下载条目的完整来源身份。
 *
 * itemId 只在同一 server / user / media source 下唯一；展示、调度、sidecar 与删除必须共享此 key，不能按裸 itemId 折叠。
 */
data class DownloadCanonicalIdentity(
    val mediaKind: DownloadMediaKind,
    val itemId: String,
    val serverId: String?,
    val userId: String?,
    val sourceId: String?,
) {
    /** 只有 server + user + item 都已知时，两个来源才可证明为同一 namespace。 */
    val hasCompleteNamespace: Boolean
        get() = serverId != null && userId != null

    /**
     * 可供折叠 / 集合查找的完整身份键。
     *
     * 来源未知时不生成共享键，避免两个同 UUID 的 legacy 行互相覆盖。
     */
    val foldKey: String?
        get() = canonicalKey.takeIf { hasCompleteNamespace }

    /** 编码后的存储键；来源未知时仅用于单条记录表示，不可用于折叠或去重。 */
    val canonicalKey: String
        get() =
            listOf(
                    mediaKind.name,
                    encode(serverId),
                    encode(userId),
                    encode(itemId),
                    encode(sourceId),
                )
                .joinToString("|")

    fun sameNamespace(other: DownloadCanonicalIdentity): Boolean =
        hasCompleteNamespace &&
            other.hasCompleteNamespace &&
            mediaKind == other.mediaKind &&
            serverId == other.serverId &&
            userId == other.userId &&
            itemId == other.itemId

    fun matchesProvenance(other: DownloadCanonicalIdentity): Boolean =
        sameNamespace(other) &&
            (sourceId == null || other.sourceId == null || sourceId == other.sourceId)

    /** 显式 legacy 回退：仅在调用点明确允许时按媒体类型 + itemId 比较。 */
    fun matchesLegacyItemId(
        mediaKind: DownloadMediaKind,
        itemId: String,
    ): Boolean = this.mediaKind == mediaKind && this.itemId == itemId

    private fun encode(value: String?): String {
        val normalized = normalize(value) ?: return "n"
        return "v${normalized.length}:$normalized"
    }

    companion object {
        fun of(
            mediaKind: DownloadMediaKind,
            itemId: String,
            serverId: String?,
            userId: String?,
            sourceId: String?,
        ): DownloadCanonicalIdentity =
            DownloadCanonicalIdentity(
                mediaKind = mediaKind,
                itemId = itemId,
                serverId = normalize(serverId),
                userId = normalize(userId),
                sourceId = normalize(sourceId),
            )

        private fun normalize(value: String?): String? = value?.takeIf(String::isNotBlank)
    }
}

fun DownloadMediaRecord.canonicalIdentity(): DownloadCanonicalIdentity =
    DownloadCanonicalIdentity.of(
        mediaKind = kind,
        itemId = itemId,
        serverId = serverId,
        userId = userId,
        sourceId = sourceId,
    )
