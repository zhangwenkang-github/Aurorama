package com.zhangwenkang.cinefin.repository

import java.util.UUID

/**
 * W80（#23）：书籍整本下载任务。
 *
 * 书籍不走下载引擎（视频 / 音乐那套 Room 任务表），历史上只有"下载完 = 出现一个 `.book` 文件"这一种状态，
 * 于是书架批量下载与阅读页「下载」在**下载页**上完全不可见。这里把进行中的书籍下载登记成进程内任务表， 供下载页显示进度 / 失败态；完成后仍由既有 `listLocalFiles()`
 * 的「已完成 · 书籍」接手。
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
    val updatedAtEpochMillis: Long = 0L,
) {
    /** 服务器未给 `Content-Length` 时为 0（界面按"已下载 x MB"显示）。 */
    val progress: Float
        get() =
            if (totalBytes > 0L) (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
}

/** 入队结果：新入队 / 已在队列 / 本地已有文件。 */
enum class BookDownloadEnqueueResult {
    Queued,
    AlreadyQueued,
    AlreadyDownloaded,
}
