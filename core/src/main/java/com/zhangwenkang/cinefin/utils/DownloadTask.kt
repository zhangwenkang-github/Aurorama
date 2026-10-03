package com.zhangwenkang.cinefin.utils

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
    /** W50：滑动窗口速度（bytes/s，自研引擎内存态；进程重启后为 0）。 */
    val speedBytesPerSecond: Long = 0,
    /** W50：剩余时间（秒；速度或总大小未知时为 null）。 */
    val etaSeconds: Long? = null,
    /** W50：连续自动重试次数（指数退避）。 */
    val retryCount: Int = 0,
    /** W50：下一次自动重试时间（epoch ms；0 = 立即可运行）。 */
    val nextRetryAt: Long = 0,
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
    /** W50：鉴权失败（HTTP 401 / 403，令牌过期或权限不足），不自动重试。 */
    AUTHENTICATION,
    /** 断点无法继续（DownloadManager 明确报告不能续传）。 */
    CANNOT_RESUME,
    /** 本地文件写入 / 目标文件冲突。 */
    FILE_ERROR,
    /** W50：用户取消 / 旧引擎任务需重新下载。 */
    CANCELLED,
    UNKNOWN,
}

/** W50 恢复策略：残片续传 / 安全重下 / 空间不足阻止。 */
enum class DownloadResumeStrategy {
    /** 从残片偏移继续（服务器支持 Range）。 */
    RESUME,
    /** 删除残片后从头重下（残片失效 / 服务器不接受 Range / 校验器变化）。 */
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
 * W50 自研下载引擎的任务状态机（纯函数，单测覆盖）。
 *
 * 状态不依赖任何 Android 运行时：持久化状态字符串 + 路径 / 文件事实 → 可读状态、恢复策略与退避时间。
 */
object DownloadTaskRules {
    /** 指数退避基数（首次失败 30s，逐次翻倍）。 */
    const val BACKOFF_BASE_MS = 30_000L

    /** 指数退避上限（30 分钟）。 */
    const val BACKOFF_MAX_MS = 30 * 60_000L

    /** 服务器类错误最多自动重试次数（超过后转手动重试）。 */
    const val MAX_SERVER_RETRIES = 5

    /** 残片失效（无法续传）最多自动安全重下次数。 */
    const val MAX_RESUME_RETRIES = 3

    /** W50 同时下载数下限 / 上限 / 默认值（偏好 `pref_download_concurrency`）。 */
    const val MIN_CONCURRENT_TASKS = 1
    const val MAX_CONCURRENT_TASKS = 3
    const val DEFAULT_CONCURRENT_TASKS = 2

    /** 同时下载数钳制到 1–3（缺省值由偏好键 default = 2 提供）。 */
    fun coerceConcurrency(value: Int): Int =
        value.coerceIn(MIN_CONCURRENT_TASKS, MAX_CONCURRENT_TASKS)

    fun resolveStatus(
        persistedStatus: String?,
        pathIsPartial: Boolean,
    ): DownloadTaskStatus {
        val persisted = parseStatus(persistedStatus)
        return when (persisted) {
            DownloadTaskStatus.COMPLETED -> DownloadTaskStatus.COMPLETED
            DownloadTaskStatus.FAILED -> DownloadTaskStatus.FAILED
            DownloadTaskStatus.PAUSED -> DownloadTaskStatus.PAUSED
            DownloadTaskStatus.PENDING,
            DownloadTaskStatus.RUNNING ->
                // 进程重启后没有活动任务：降级为「等待调度」，由恢复逻辑续传。
                DownloadTaskStatus.PENDING
            // 旧数据没有状态列：路径仍是 .download = 失败残片（或需重下），否则视为已完成。
            null -> if (pathIsPartial) DownloadTaskStatus.FAILED else DownloadTaskStatus.COMPLETED
        }
    }

    /**
     * 旧 DownloadManager 引擎的「进行中」任务：升级后不做无缝接管，标记为失败并提示重下。
     *
     * 判据 = engineVersion != 1（不是自研引擎写入的行）且存在系统 downloadId 痕迹。
     */
    fun requiresRedownloadAfterEngineUpgrade(
        engineVersion: Int,
        downloadId: Long?,
        status: DownloadTaskStatus?,
        pathIsPartial: Boolean,
    ): Boolean {
        if (engineVersion >= 1) return false
        if (downloadId == null) return false
        return when (status) {
            null -> pathIsPartial
            DownloadTaskStatus.COMPLETED,
            DownloadTaskStatus.FAILED -> false
            DownloadTaskStatus.PENDING,
            DownloadTaskStatus.RUNNING,
            DownloadTaskStatus.PAUSED -> true
        }
    }

    /**
     * 恢复判定：
     * - 空间不足 → 阻止自动重试；
     * - 暂停 / 可继续的失败 → 残片续传；
     * - 其余失败 → 安全重下。
     */
    fun resumeStrategy(
        status: DownloadTaskStatus,
        failureReason: DownloadFailureReason?,
    ): DownloadResumeStrategy =
        when {
            status == DownloadTaskStatus.FAILED &&
                failureReason == DownloadFailureReason.STORAGE_INSUFFICIENT ->
                DownloadResumeStrategy.BLOCKED
            status == DownloadTaskStatus.PAUSED -> DownloadResumeStrategy.RESUME
            status == DownloadTaskStatus.PENDING || status == DownloadTaskStatus.RUNNING ->
                DownloadResumeStrategy.RESUME
            failureReason == DownloadFailureReason.CANNOT_RESUME -> DownloadResumeStrategy.RESTART
            failureReason == DownloadFailureReason.FILE_ERROR -> DownloadResumeStrategy.RESTART
            failureReason == DownloadFailureReason.CANCELLED -> DownloadResumeStrategy.RESTART
            else -> DownloadResumeStrategy.RESUME
        }

    /**
     * 自动重试资格：
     * - 网络类：无限重试（退避封顶 30 分钟），网络恢复后由 CONNECTED 约束唤醒；
     * - 服务器类：最多 [MAX_SERVER_RETRIES] 次；
     * - 残片失效：最多 [MAX_RESUME_RETRIES] 次安全重下；
     * - 空间 / 鉴权 / 用户取消：不自动重试。
     */
    fun isAutoRetryEligible(
        status: DownloadTaskStatus,
        failureReason: DownloadFailureReason?,
        retryCount: Int = 0,
    ): Boolean {
        if (status != DownloadTaskStatus.FAILED && status != DownloadTaskStatus.PENDING)
            return false
        return when (failureReason) {
            DownloadFailureReason.NETWORK_UNAVAILABLE -> true
            DownloadFailureReason.SERVER_ERROR -> retryCount < MAX_SERVER_RETRIES
            DownloadFailureReason.CANNOT_RESUME -> retryCount < MAX_RESUME_RETRIES
            else -> false
        }
    }

    /** 指数退避：30s、60s、120s … 封顶 30 分钟（retryCount = 已失败次数）。 */
    fun backoffDelayMs(
        retryCount: Int,
        baseMs: Long = BACKOFF_BASE_MS,
        maxMs: Long = BACKOFF_MAX_MS,
    ): Long {
        if (retryCount <= 0) return 0L
        val shift = (retryCount - 1).coerceIn(0, 20)
        val delay = baseMs * (1L shl shift)
        return if (delay <= 0L) maxMs else delay.coerceAtMost(maxMs)
    }

    /** 续传起始偏移：优先信任持久化字节数，文件缺失归零，文件比记录短时按文件长度（截断残片）。 */
    fun initialOffset(downloadedBytes: Long, fileExists: Boolean, fileLength: Long): Long {
        if (!fileExists) return 0L
        val recorded = downloadedBytes.coerceAtLeast(0L).takeIf { it > 0L } ?: fileLength
        return recorded.coerceAtMost(fileLength.coerceAtLeast(0L))
    }

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

    fun parseStatus(raw: String?): DownloadTaskStatus? = raw?.toTaskStatus()
}

enum class DownloadTaskGroup {
    ACTIVE,
    COMPLETED,
    FAILED,
}

internal fun String?.toTaskStatus(): DownloadTaskStatus? =
    DownloadTaskStatus.entries.firstOrNull { it.name == this }

internal fun String?.toFailureReason(): DownloadFailureReason? =
    DownloadFailureReason.entries.firstOrNull { it.name == this }
