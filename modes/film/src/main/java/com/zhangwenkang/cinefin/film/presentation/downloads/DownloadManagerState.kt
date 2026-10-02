package com.zhangwenkang.cinefin.film.presentation.downloads

import com.zhangwenkang.cinefin.database.DownloadedEpisodeHierarchy
import com.zhangwenkang.cinefin.models.FindroidItem
import com.zhangwenkang.cinefin.models.FindroidSource
import com.zhangwenkang.cinefin.utils.DownloadMediaKind
import com.zhangwenkang.cinefin.utils.DownloadStorageUsage
import com.zhangwenkang.cinefin.utils.DownloadTask
import com.zhangwenkang.cinefin.utils.DownloadTaskGroup

/** W34：媒体类型筛选（全部 / 视频 / 音乐 / 书籍）。 */
enum class DownloadMediaFilter {
    ALL,
    VIDEO,
    MUSIC,
    BOOK,
}

/** W32 下载管理页 UI 状态：进行中 / 已完成 / 失败三组 + 多选 + 存储占用；W34 追加层级容器。 */
data class DownloadManagerState(
    val isLoading: Boolean = true,
    val activeTasks: List<DownloadTask> = emptyList(),
    val failedTasks: List<DownloadTask> = emptyList(),
    val completed: List<CompletedDownload> = emptyList(),
    val activeContainers: List<DownloadHierarchyContainer> = emptyList(),
    val completedContainers: List<DownloadHierarchyContainer> = emptyList(),
    val failedContainers: List<DownloadHierarchyContainer> = emptyList(),
    /** W34：已展开的容器 key（默认全部折叠，用户点开后写入）。 */
    val expandedKeys: Set<String> = emptySet(),
    val mediaFilter: DownloadMediaFilter = DownloadMediaFilter.ALL,
    /** W34：书籍离线文件占用（阅读器 `books/`，不在 DownloadManager 目录里）。 */
    val bookStorageBytes: Long = 0L,
    val storage: DownloadStorageUsage = DownloadStorageUsage(0L, 0L, 0L),
    val selectionMode: Boolean = false,
    val selection: Set<String> = emptySet(),
) {
    val activeCount: Int
        get() = activeContainers.size

    val completedCount: Int
        get() = completedContainers.size

    val failedCount: Int
        get() = failedContainers.size

    val hasSelection: Boolean
        get() = selection.isNotEmpty()

    /** 当前筛选下的容器（按标题 / 更新时间排序由构建器给出）。 */
    fun containersFor(group: DownloadTaskGroup): List<DownloadHierarchyContainer> =
        when (group) {
            DownloadTaskGroup.ACTIVE -> activeContainers
            DownloadTaskGroup.COMPLETED -> completedContainers
            DownloadTaskGroup.FAILED -> failedContainers
        }.filter { container -> mediaFilter.matches(container.mediaKind) }

    val filteredActiveCount: Int
        get() = activeContainers.count { mediaFilter.matches(it.mediaKind) }

    val filteredCompletedCount: Int
        get() = completedContainers.count { mediaFilter.matches(it.mediaKind) }

    val filteredFailedCount: Int
        get() = failedContainers.count { mediaFilter.matches(it.mediaKind) }

    /** W34：当前选中的层级条目（供多选操作栏判断可暂停 / 可重试）。 */
    val selectedEntries: List<DownloadHierarchyEntry>
        get() =
            (activeContainers + completedContainers + failedContainers)
                .flatMap { container -> container.descendantEntries() }
                .filter { it.key in selection }
                .distinctBy { it.key }

    val selectedTasks: List<DownloadTask>
        get() = selectedEntries.mapNotNull { entry -> entry.task }
}

private fun DownloadHierarchyContainer.descendantEntries(): List<DownloadHierarchyEntry> =
    children.flatMap { child ->
        when (child) {
            is DownloadHierarchyLeaf -> listOf(child.entry)
            is DownloadHierarchySubContainer -> child.children.map { it.entry }
        }
    }

private fun DownloadMediaFilter.matches(kind: DownloadMediaKind): Boolean =
    when (this) {
        DownloadMediaFilter.ALL -> true
        DownloadMediaFilter.VIDEO -> kind == DownloadMediaKind.VIDEO
        DownloadMediaFilter.MUSIC -> kind == DownloadMediaKind.MUSIC
        DownloadMediaFilter.BOOK -> kind == DownloadMediaKind.BOOK
    }

/** 已完成条目：source 为 null 表示这是剧集容器（只能点进去，不能在下载页直接删除）。 */
data class CompletedDownload(
    val item: FindroidItem,
    val source: FindroidSource?,
    val sizeBytes: Long,
    /** W34：剧集的节目 / 季归属；非剧集为 null。 */
    val episode: DownloadedEpisodeHierarchy? = null,
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

    /** W34：展开 / 折叠层级容器。 */
    data class ToggleContainer(val key: String) : DownloadAction

    /** W34：媒体类型筛选。 */
    data class SetMediaFilter(val filter: DownloadMediaFilter) : DownloadAction

    /** W34：选中层级里的单个条目。 */
    data class ToggleEntry(val key: String) : DownloadAction

    /** W34：删除层级里的单个条目（任务或已完成 / 离线书籍）。 */
    data class DeleteEntry(val key: String) : DownloadAction

    /** W34：打开层级条目（已完成条目 / 书籍）。 */
    data class OpenEntry(val key: String) : DownloadAction

    /** W34：删除整个层级容器（含下载中 / 已完成子项）。 */
    data class DeleteContainer(val key: String) : DownloadAction

    /** W36：切换单个已完成条目的「允许离线模式观看」。 */
    data class ToggleOffline(val key: String) : DownloadAction

    /** W36：容器级（节目 / 专辑 / 季）批量切换「允许离线模式观看」。 */
    data class SetContainerOffline(val key: String, val allow: Boolean) : DownloadAction

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
