package com.zhangwenkang.cinefin.repository

import java.util.UUID

/**
 * W80（#23）：书籍整本下载任务。
 *
 * 书籍不走视频 / 音乐下载引擎，但任务本身持久化在专用 `bookDownloadTasks` 表；下载页据此显示进行中 / 暂停 / 失败态，进程重建后由
 * `ReaderRepository.bookDownloads` 恢复。完成态仍由 `listLocalFiles()` 的「已完成 · 书籍」接手。
 *
 * W7-T3（V2-DOWNLOAD-21/22/23/24 书籍侧）：状态取主引擎 6 态中的子集——书籍删除走 `FAILED + DELETE_PENDING` receipt， 没有独立
 * DELETING 态，因此这里不新造。
 */
enum class BookDownloadStatus {
    /** 已入队、等待启动（重启恢复时先落这一态，再由启动步骤切 RUNNING）。 */
    PENDING,
    RUNNING,
    /** 用户暂停：在途 call 已取消，`.part` 与字节偏移保留，恢复时走 Range 续传。 */
    PAUSED,
    COMPLETED,
    FAILED,
}

/** 书籍任务的状态规则；与 `DownloadTaskRules` 的活动队列 / 暂停 / 重试口径对齐。 */
object BookDownloadTaskRules {

    /** 可暂停：在跑或刚入队未启动。 */
    fun canPause(status: BookDownloadStatus): Boolean =
        status == BookDownloadStatus.PENDING || status == BookDownloadStatus.RUNNING

    /** 可恢复：只有用户主动暂停的任务。 */
    fun canResume(status: BookDownloadStatus): Boolean = status == BookDownloadStatus.PAUSED

    /** 可手动重试：失败任务（删除 receipt 由调用方用 `isDeleteRecord` 排除）。 */
    fun canRetry(status: BookDownloadStatus): Boolean = status == BookDownloadStatus.FAILED

    /** 活动队列口径（与主引擎一致：暂停也算「进行中」）。 */
    fun isActiveQueueStatus(status: BookDownloadStatus): Boolean =
        status == BookDownloadStatus.PENDING ||
            status == BookDownloadStatus.RUNNING ||
            status == BookDownloadStatus.PAUSED

    /**
     * 进程重启后自动续传的状态。
     *
     * `PAUSED` 是用户意图，重启后保持暂停、等用户点「继续」，不自动恢复。
     */
    fun isAutoResumeOnRestore(status: BookDownloadStatus): Boolean =
        status == BookDownloadStatus.PENDING || status == BookDownloadStatus.RUNNING
}

/**
 * V2-DOWNLOAD-34：有声书识别口径。
 *
 * 只认服务器明确给出的类型 / 容器：`AUDIO` 条目、或书籍条目带音频容器（m4b 等）。识别不了就返回 false，**不猜、不造时长**。
 */
object BookAudioRules {

    private val AUDIO_CONTAINERS =
        setOf("m4b", "m4a", "mp3", "opus", "flac", "aac", "ogg", "wav", "alac")

    /** `mediaKind` = 服务器条目类型（`BaseItemKind` 名或 `audiobook`），`container` = 小写扩展名。 */
    fun isAudiobook(mediaKind: String?, container: String?): Boolean {
        val kind = mediaKind?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        if (kind == "audiobook" || kind == "audio") return true
        if (kind != "book") return false
        return container?.trim()?.lowercase() in AUDIO_CONTAINERS
    }

    /** 从服务器原始路径取小写扩展名；没有扩展名返回 null（不拿文件名当容器）。 */
    fun containerFromPath(path: String?): String? =
        path?.substringAfterLast('/')?.substringAfterLast('.', "")?.trim()?.lowercase()?.takeIf {
            it.isNotEmpty() && it.length <= 5
        }

    /** 有声书才投影时长；电子书 / 识别不了返回 0（界面据此不显示时长，不造数据）。 */
    fun displayRuntimeTicks(
        runtimeTicks: Long,
        mediaKind: String?,
        container: String?,
    ): Long = if (isAudiobook(mediaKind, container)) runtimeTicks.coerceAtLeast(0L) else 0L
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

    /** 是否仍在活动队列（PENDING / RUNNING / PAUSED）。 */
    val isActive: Boolean
        get() = BookDownloadTaskRules.isActiveQueueStatus(status)

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
