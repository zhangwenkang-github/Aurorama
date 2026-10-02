package com.zhangwenkang.cinefin.utils

import android.app.DownloadManager
import java.util.UUID

/** W34 下载层级：条目所属媒体类型（下载列表层级化用）。 */
enum class DownloadMediaKind {
    VIDEO,
    MUSIC,
    BOOK,
}

/**
 * W32 下载任务（下载管理页的单一数据模型）。
 *
 * 进行中 / 暂停 / 失败三类任务都由它表示；已完成条目由离线仓库的 [com.zhangwenkang.cinefin.models.FindroidItem] 表示。
 */
data class DownloadTask(
    val itemId: UUID,
    val sourceId: String,
    /** 媒体标题（优先 item 名，缺失时回落来源名）。 */
    val name: String,
    /** DownloadManager 的目标文件路径（进行中为 `.download`，完成后由对账重命名）。 */
    val path: String,
    val downloadId: Long?,
    val status: DownloadTaskStatus,
    val failureReason: DownloadFailureReason?,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val updatedAt: Long,
    /** W34：媒体类型（默认视频；音乐由 DownloaderImpl 按本地曲库 / 侧车元数据判定）。 */
    val mediaKind: DownloadMediaKind = DownloadMediaKind.VIDEO,
    /** W34：视频层级（剧集）；电影为 null。 */
    val seriesId: UUID? = null,
    val seasonId: UUID? = null,
    val seriesName: String? = null,
    val seasonName: String? = null,
    val episodeIndex: Int = 0,
    val seasonIndex: Int = 0,
    /** W34：音乐层级。 */
    val albumName: String? = null,
    val artist: String? = null,
) {
    val progress: Float
        get() =
            if (totalBytes > 0) {
                (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
            } else {
                0f
            }

    val isActive: Boolean
        get() = status == DownloadTaskStatus.PENDING || status == DownloadTaskStatus.RUNNING
}

enum class DownloadTaskStatus {
    PENDING,
    RUNNING,
    PAUSED,
    COMPLETED,
    FAILED,
}

/** 失败原因分类：UI 文案与自动重试策略都以此为准。 */
enum class DownloadFailureReason {
    /** 空间不足 / 存储设备不可用。 */
    STORAGE_INSUFFICIENT,
    /** 网络不可达 / 等待网络 / 系统等待重试。 */
    NETWORK_UNAVAILABLE,
    /** 服务器返回错误（HTTP 数据错误 / 未知状态码 / 重定向过多）。 */
    SERVER_ERROR,
    /** 断点无法继续（DownloadManager 明确报告不能续传）。 */
    CANNOT_RESUME,
    /** 本地文件写入 / 目标文件冲突。 */
    FILE_ERROR,
    UNKNOWN,
}

/** 恢复策略：系统自愈续传 / 安全重试（重新入队）/ 空间不足时阻止。 */
enum class DownloadResumeStrategy {
    /** 系统暂停（等待网络等），不动任务，网络恢复后 DownloadManager 自行续传。 */
    WAIT_FOR_SYSTEM,
    /** 删除残留后重新入队（安全重试；能否续传由服务器 Range 与系统决定）。 */
    RESTART,
    /** 空间不足，自动重试只会再次失败；需要用户先清理空间。 */
    BLOCKED,
}

data class DownloadStorageUsage(
    val usedBytes: Long,
    val availableBytes: Long,
    val totalBytes: Long,
)

/**
 * 下载任务状态机（纯函数，单测覆盖）。
 *
 * 输入只使用 DownloadManager 的状态 / reason 常量、持久化状态字符串与文件是否存在，输出可读状态与恢复策略。
 */
object DownloadTaskRules {
    fun resolveStatus(
        persistedStatus: String?,
        managerStatus: Int?,
        pathIsPartial: Boolean,
    ): DownloadTaskStatus {
        when (managerStatus) {
            DownloadManager.STATUS_SUCCESSFUL -> return DownloadTaskStatus.COMPLETED
            DownloadManager.STATUS_RUNNING -> return DownloadTaskStatus.RUNNING
            DownloadManager.STATUS_PENDING -> return DownloadTaskStatus.PENDING
            DownloadManager.STATUS_PAUSED -> return DownloadTaskStatus.PAUSED
            DownloadManager.STATUS_FAILED -> return DownloadTaskStatus.FAILED
        }

        val persisted = persistedStatus?.toTaskStatus()
        return when (persisted) {
            DownloadTaskStatus.COMPLETED -> DownloadTaskStatus.COMPLETED
            DownloadTaskStatus.FAILED -> DownloadTaskStatus.FAILED
            // 用户暂停的任务：系统记录已被 remove，状态由数据库保持。
            DownloadTaskStatus.PAUSED -> DownloadTaskStatus.PAUSED
            // 标记为进行中但系统任务已消失（被系统清理 / 异常中断）：视为失败，可重试。
            DownloadTaskStatus.PENDING,
            DownloadTaskStatus.RUNNING -> DownloadTaskStatus.FAILED
            // 旧数据没有状态列：路径仍是 .download = 失败残片，否则就是已经下载完成。
            null -> if (pathIsPartial) DownloadTaskStatus.FAILED else DownloadTaskStatus.COMPLETED
        }
    }

    fun resolveFailureReason(
        persistedReason: String?,
        managerStatus: Int?,
        managerReason: Int?,
    ): DownloadFailureReason? {
        if (
            managerStatus == DownloadManager.STATUS_FAILED ||
                managerStatus == DownloadManager.STATUS_PAUSED
        ) {
            when (managerReason) {
                DownloadManager.ERROR_INSUFFICIENT_SPACE,
                DownloadManager.ERROR_DEVICE_NOT_FOUND ->
                    return DownloadFailureReason.STORAGE_INSUFFICIENT
                DownloadManager.ERROR_FILE_ERROR,
                DownloadManager.ERROR_FILE_ALREADY_EXISTS -> return DownloadFailureReason.FILE_ERROR
                DownloadManager.ERROR_HTTP_DATA_ERROR,
                DownloadManager.ERROR_UNHANDLED_HTTP_CODE,
                DownloadManager.ERROR_TOO_MANY_REDIRECTS ->
                    return DownloadFailureReason.SERVER_ERROR
                DownloadManager.ERROR_CANNOT_RESUME -> return DownloadFailureReason.CANNOT_RESUME
                DownloadManager.PAUSED_WAITING_FOR_NETWORK,
                DownloadManager.PAUSED_WAITING_TO_RETRY,
                DownloadManager.PAUSED_QUEUED_FOR_WIFI ->
                    return DownloadFailureReason.NETWORK_UNAVAILABLE
                DownloadManager.ERROR_UNKNOWN -> return DownloadFailureReason.UNKNOWN
            }
        }
        return persistedReason?.toFailureReason()
    }

    /**
     * 恢复判定：
     * - 系统暂停（等待网络）→ 交给系统续传；
     * - 空间不足 → 阻止自动重试；
     * - 其余失败 → 安全重试（重新入队）。
     */
    fun resumeStrategy(
        status: DownloadTaskStatus,
        failureReason: DownloadFailureReason?,
    ): DownloadResumeStrategy =
        when {
            status == DownloadTaskStatus.FAILED &&
                failureReason == DownloadFailureReason.STORAGE_INSUFFICIENT ->
                DownloadResumeStrategy.BLOCKED
            status == DownloadTaskStatus.FAILED -> DownloadResumeStrategy.RESTART
            status == DownloadTaskStatus.PAUSED &&
                failureReason == DownloadFailureReason.NETWORK_UNAVAILABLE ->
                DownloadResumeStrategy.WAIT_FOR_SYSTEM
            else -> DownloadResumeStrategy.WAIT_FOR_SYSTEM
        }

    /** 网络恢复后允许自动重试的失败原因（空间 / 文件类不自动重试，避免死循环）。 */
    fun isAutoRetryEligible(
        status: DownloadTaskStatus,
        failureReason: DownloadFailureReason?,
    ): Boolean =
        status == DownloadTaskStatus.FAILED &&
            (failureReason == DownloadFailureReason.NETWORK_UNAVAILABLE ||
                failureReason == DownloadFailureReason.SERVER_ERROR)

    fun canPause(status: DownloadTaskStatus): Boolean =
        status == DownloadTaskStatus.PENDING || status == DownloadTaskStatus.RUNNING

    fun canResume(status: DownloadTaskStatus): Boolean = status == DownloadTaskStatus.PAUSED

    fun canRetry(status: DownloadTaskStatus): Boolean = status == DownloadTaskStatus.FAILED

    fun groupKey(status: DownloadTaskStatus): DownloadTaskGroup =
        when (status) {
            DownloadTaskStatus.PENDING,
            DownloadTaskStatus.RUNNING,
            DownloadTaskStatus.PAUSED -> DownloadTaskGroup.ACTIVE
            DownloadTaskStatus.FAILED -> DownloadTaskGroup.FAILED
            DownloadTaskStatus.COMPLETED -> DownloadTaskGroup.COMPLETED
        }
}

enum class DownloadTaskGroup {
    ACTIVE,
    COMPLETED,
    FAILED,
}

private fun String.toTaskStatus(): DownloadTaskStatus? =
    DownloadTaskStatus.entries.firstOrNull { it.name == this }

private fun String.toFailureReason(): DownloadFailureReason? =
    DownloadFailureReason.entries.firstOrNull { it.name == this }
