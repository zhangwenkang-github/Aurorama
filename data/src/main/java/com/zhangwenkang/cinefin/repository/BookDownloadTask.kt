package com.zhangwenkang.cinefin.repository

import java.util.UUID

/**
 * W80（#23）：书籍整本下载任务。
 *
 * 书籍不走视频 / 音乐下载引擎，但任务本身持久化在专用 `bookDownloadTasks` 表；下载页据此显示进行中 / 失败态，进程重建后由
 * `ReaderRepository.bookDownloads` 恢复。完成态仍由 `listLocalFiles()` 的「已完成 · 书籍」接手。
 */
enum class BookDownloadStatus {
    RUNNING,
    FAILED,
    COMPLETED,
}

data class BookDownloadTask(
    val itemId: UUID,
    val title: String? = null,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val status: BookDownloadStatus = BookDownloadStatus.RUNNING,
    val error: String? = null,
    val failureReason: String? = null,
    val taskGeneration: Long = 0L,
    val canonicalKey: String = itemId.toString(),
    val serverId: String? = null,
    val userId: UUID? = null,
    val targetPath: String? = null,
    val resumeValidator: String? = null,
    val createdAtEpochMillis: Long = 0L,
    val updatedAtEpochMillis: Long = 0L,
) {
    /** 服务器未给 `Content-Length` 时为 0（界面按"已下载 x MB"显示）。 */
    val progress: Float
        get() =
            if (totalBytes > 0L) (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f

    /** 删除 receipt 已被同 item 的、可证明更新的 canonical owner 取代；仅内部持久化，不投影到用户列表。 */
    val isSupersededDeleteRecord: Boolean
        get() = failureReason == "DELETE_SUPERSEDED"
}

/** 入队结果：新入队 / 已在队列 / 本地已有文件 / 等待旧删除收口。 */
enum class BookDownloadEnqueueResult {
    Queued,
    AlreadyQueued,
    AlreadyDownloaded,
    BlockedByDelete,
}

/** `deleteBookDownload` 的可归因结果；裸 itemId 无法唯一归属时绝不任选。 */
enum class BookDownloadDeleteResult {
    Deleted,
    CleanupFailed,
    AmbiguousOwnership,
    OwnershipUnavailable,
    AlreadySuperseded,
}
