package com.zhangwenkang.cinefin.utils

import androidx.work.ForegroundInfo
import com.zhangwenkang.cinefin.models.FindroidItem
import com.zhangwenkang.cinefin.models.FindroidSource
import com.zhangwenkang.cinefin.models.UiText
import java.util.UUID
import kotlinx.coroutines.flow.SharedFlow

interface Downloader {
    /**
     * 入队下载；返回自研引擎的任务句柄（旧 DownloadManager id 语义已替换，UI 轮询 / 取消继续复用）。
     *
     * 返回 -1 表示入队失败。
     */
    suspend fun downloadItem(
        item: FindroidItem,
        sourceId: String,
        storageIndex: Int = 0,
    ): Pair<Long, UiText?>

    /**
     * W34：音乐曲目下载（带专辑 / 艺人侧车元数据）。
     *
     * 默认实现忽略元数据并走普通 [downloadItem]，只有真正的下载器实现才写入侧车。
     */
    suspend fun downloadItem(
        item: FindroidItem,
        sourceId: String,
        storageIndex: Int,
        albumName: String?,
        artist: String? = null,
        trackIndex: Int = 0,
    ): Pair<Long, UiText?> = downloadItem(item, sourceId, storageIndex)

    /**
     * W63：批量入队（整剧 / 全季 / 多选批量）。
     *
     * 与逐条调用 [downloadItem] 的区别：同剧的节目 / 季快照只在首批解析一次并在批内复用（后续条目近乎零网络）， 且调用方以 `NonCancellable`
     * 调用时整批不会被页面退出打断（修复「整剧下载只入队第一集」）。
     *
     * @return 本次实际加入队列的条目 id（保序）+ 最后一次失败文案（无失败为 null）。
     */
    suspend fun enqueueItems(
        items: List<FindroidItem>,
        storageIndex: Int = 0,
    ): DownloadBatchResult

    /**
     * W63：下载活动集变化信号（入队 / 完成 / 失败 / 暂停 / 恢复 / 删除）。
     *
     * 侧栏角标等即时 UI 收到信号后重读只读快照即可；不需要精确载荷。
     */
    val queueChanges: SharedFlow<Unit>

    suspend fun cancelDownload(item: FindroidItem, downloadId: Long)

    suspend fun deleteItem(item: FindroidItem, source: FindroidSource)

    suspend fun getProgress(downloadId: Long?): Pair<Int, Int>

    /**
     * W32：刷新并对账全部未完成下载任务。
     *
     * 对账内容：DownloadManager 已完成但未重命名的任务补重命名；失败任务归档失败原因；进行中任务刷新进度。
     */
    suspend fun refreshDownloadTasks(): List<DownloadTask>

    /** W32：暂停任务（停止系统任务并保留残片，标记 PAUSED + 记录残片字节数）。 */
    suspend fun pauseTask(task: DownloadTask): Boolean

    /** W32：恢复或重试任务；返回 (downloadId, 错误文案)，-1 表示失败。 */
    suspend fun resumeTask(task: DownloadTask): Pair<Long, UiText?>

    /** W32：重试失败任务；返回 (downloadId, 错误文案)，-1 表示失败。 */
    suspend fun retryTask(task: DownloadTask): Pair<Long, UiText?>

    /** W32：删除任务（含残片、DownloadManager 记录与离线索引）。 */
    suspend fun deleteTask(task: DownloadTask): Boolean

    /** W32：网络恢复后自动重试网络 / 服务器类失败任务，返回实际重试数量。 */
    suspend fun retryNetworkFailures(): Int

    /** W32：下载占用与可用空间（下载管理页头部）。 */
    suspend fun getStorageUsage(): DownloadStorageUsage

    /** W34：媒体类型 / 专辑侧车（下载列表层级化的离线元数据）。 */
    fun mediaSidecar(): DownloadMediaSidecar

    /** W34：已经完成下载（LOCAL 完整文件）的条目 id 集合。 */
    suspend fun downloadedItemIds(): Set<UUID>

    /**
     * W51：只读队列快照——「活动任务」（排队 / 下载中 / 暂停）的条目 id 集合。
     *
     * 供详情页下载态与侧栏角标使用：只读 Room，不做对账、不唤醒引擎、不触发网络请求。
     */
    suspend fun activeItemIds(): Set<UUID>

    /**
     * W50：运行下载队列，直到没有「当前可执行」的任务为止。
     *
     * 供 WorkManager 长时 worker 调用：worker 存活期间由前台服务托管常驻通知；队列为空时返回 [DownloadQueueOutcome.shouldRetry]
     * = false，由 worker 结束自己。
     */
    suspend fun runQueue(): DownloadQueueOutcome

    /** W50：启动恢复——旧引擎进行中任务标记「需重下」，自研引擎中断任务回队并触发调度；返回恢复数量。 */
    suspend fun recoverOnStartup(): Int

    /** W50：前台通知信息（WorkManager `setForeground` 用；无活动任务时展示「等待下载任务」）。 */
    suspend fun foregroundInfo(): ForegroundInfo

    /** W50：通知按钮——按 sourceId 暂停任务。 */
    suspend fun pauseTaskById(sourceId: String): Boolean

    /** W50：通知按钮——按 sourceId 取消并删除任务。 */
    suspend fun deleteTaskById(sourceId: String): Boolean
}

/** W50 队列运行结果（WorkManager 重试决策）。 */
data class DownloadQueueOutcome(
    /** 本次实际执行的任务数。 */
    val ranTasks: Int,
    /** 结束后是否仍有待处理任务（含退避等待中的）。 */
    val hasPendingTasks: Boolean,
    /** 是否需要 WorkManager 再次唤醒（有 PENDING 任务时）。 */
    val shouldRetry: Boolean,
)

/** W63 批量入队结果（整剧 / 全季 / 多选批量）。 */
data class DownloadBatchResult(
    /** 实际加入队列的条目 id（保序，已去重）。 */
    val addedIds: List<UUID>,
    /** 最后一次失败文案（全部成功为 null）。 */
    val lastError: UiText? = null,
)
