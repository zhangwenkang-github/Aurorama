package com.zhangwenkang.cinefin.film.presentation.downloads

import com.zhangwenkang.cinefin.models.FindroidItem
import com.zhangwenkang.cinefin.models.FindroidSource
import com.zhangwenkang.cinefin.utils.DownloadStorageUsage
import com.zhangwenkang.cinefin.utils.DownloadTask

/** W32 下载管理页 UI 状态：进行中 / 已完成 / 失败三组 + 多选 + 存储占用。 */
data class DownloadManagerState(
    val isLoading: Boolean = true,
    val activeTasks: List<DownloadTask> = emptyList(),
    val failedTasks: List<DownloadTask> = emptyList(),
    val completed: List<CompletedDownload> = emptyList(),
    val storage: DownloadStorageUsage = DownloadStorageUsage(0L, 0L, 0L),
    val selectionMode: Boolean = false,
    val selection: Set<String> = emptySet(),
) {
    val activeCount: Int
        get() = activeTasks.size

    val completedCount: Int
        get() = completed.size

    val failedCount: Int
        get() = failedTasks.size

    val hasSelection: Boolean
        get() = selection.isNotEmpty()
}

/** 已完成条目：source 为 null 表示这是剧集容器（只能点进去，不能在下载页直接删除）。 */
data class CompletedDownload(
    val item: FindroidItem,
    val source: FindroidSource?,
    val sizeBytes: Long,
) {
    val key: String
        get() = completedKey(item)

    val canDelete: Boolean
        get() = source != null
}

sealed interface DownloadAction {
    data class ToggleSelection(val key: String) : DownloadAction

    data object ToggleSelectionMode : DownloadAction

    data object ClearSelection : DownloadAction

    data object PauseSelected : DownloadAction

    data object ResumeSelected : DownloadAction

    data object RetrySelected : DownloadAction

    data object DeleteSelected : DownloadAction

    data class Pause(val task: DownloadTask) : DownloadAction

    data class Resume(val task: DownloadTask) : DownloadAction

    data class Retry(val task: DownloadTask) : DownloadAction

    data class DeleteTask(val task: DownloadTask) : DownloadAction

    data class DeleteCompleted(val download: CompletedDownload) : DownloadAction

    data class Open(val item: FindroidItem) : DownloadAction
}

fun taskKey(task: DownloadTask): String = "task:${task.sourceId}"

fun completedKey(item: FindroidItem): String = "completed:${item.id}"
