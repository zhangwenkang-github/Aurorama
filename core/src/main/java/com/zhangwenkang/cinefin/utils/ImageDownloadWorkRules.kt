package com.zhangwenkang.cinefin.utils

data class ImageDownloadOwner(
    val sourceId: String,
    val status: String,
    val generation: Long,
)

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
}
