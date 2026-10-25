package com.zhangwenkang.cinefin.utils

import java.nio.charset.StandardCharsets
import java.util.UUID

data class ImageDownloadOwner(
    val sourceId: String,
    val status: String,
    val generation: Long,
)

enum class ImageDownloadParentKind {
    SHOW,
    SEASON,
}

/**
 * 节目 / 季图片不是下载 source，不能挂在任意剧集 source 上。
 *
 * owner key 只包含父条目真实可见身份：父类型 + itemId + serverId + userId。子集增删只改变“父是否仍存在”，不会改变 key。
 */
data class ImageDownloadParentOwner(
    val kind: ImageDownloadParentKind,
    val itemId: UUID,
    val serverId: String,
    val userId: String,
) {
    init {
        require(serverId.isNotBlank())
        require(userId.isNotBlank())
    }

    val canonicalKey: String
        get() = "${kind.name}\u0000$serverId\u0000$userId\u0000$itemId"
}

object ImageDownloadWorkRules {
    fun isCurrent(
        expectedSourceId: String,
        expectedGeneration: Long,
        persisted: ImageDownloadOwner?,
    ): Boolean =
        persisted != null &&
            persisted.sourceId == expectedSourceId &&
            persisted.generation == expectedGeneration &&
            persisted.status in setOf("PENDING", "RUNNING", "COMPLETED")

    fun uniqueWorkName(sourceId: String): String = "images-$sourceId"

    fun isParentCurrent(
        owner: ImageDownloadParentOwner,
        visibleServerId: String?,
        visibleUserId: String?,
        parentExists: Boolean,
    ): Boolean = parentExists && owner.serverId == visibleServerId && owner.userId == visibleUserId

    fun parentWorkName(owner: ImageDownloadParentOwner): String {
        val suffix = UUID.nameUUIDFromBytes(owner.canonicalKey.toByteArray(StandardCharsets.UTF_8))
        return "images-parent-$suffix"
    }

    fun parentTag(owner: ImageDownloadParentOwner): String =
        "parent:${owner.kind.name}:${owner.itemId}"
}
