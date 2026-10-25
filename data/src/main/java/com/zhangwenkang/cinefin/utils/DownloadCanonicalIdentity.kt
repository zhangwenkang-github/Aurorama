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
        mediaKind == other.mediaKind &&
            normalize(serverId) == normalize(other.serverId) &&
            normalize(userId) == normalize(other.userId) &&
            itemId == other.itemId

    fun matchesProvenance(other: DownloadCanonicalIdentity): Boolean =
        sameNamespace(other) &&
            (normalize(sourceId) == null ||
                normalize(other.sourceId) == null ||
                normalize(sourceId) == normalize(other.sourceId))

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

private fun DownloadCanonicalIdentity.normalize(value: String?): String? =
    value?.takeIf(String::isNotBlank)
