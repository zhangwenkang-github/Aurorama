package com.zhangwenkang.cinefin.film.presentation.downloads

import com.zhangwenkang.cinefin.utils.DownloadMediaKind
import com.zhangwenkang.cinefin.utils.DownloadTask
import com.zhangwenkang.cinefin.utils.DownloadTaskGroup
import com.zhangwenkang.cinefin.utils.DownloadTaskRules
import com.zhangwenkang.cinefin.utils.DownloadTaskStatus
import java.util.UUID

sealed interface DownloadPlaybackPlan {
    data class Music(val itemIds: List<UUID>) : DownloadPlaybackPlan

    data class Video(val itemIds: List<UUID>) : DownloadPlaybackPlan
}

/** W84：下载管理列表的全选口径。 */
object DownloadSelectionRules {

    /** 多选播放只接受全部已完成、且属于同一播放域的音乐或视频。 */
    fun playbackPlan(state: DownloadManagerState): DownloadPlaybackPlan? {
        val entries = state.selectedEntries
        if (entries.isEmpty() || entries.any { it.status != DownloadTaskStatus.COMPLETED }) {
            return null
        }
        val mediaKind = entries.map { it.mediaKind }.distinct().singleOrNull() ?: return null
        val itemIds = entries.map { it.itemId }.distinct()
        return when (mediaKind) {
            DownloadMediaKind.MUSIC -> DownloadPlaybackPlan.Music(itemIds)
            DownloadMediaKind.VIDEO -> DownloadPlaybackPlan.Video(itemIds)
            DownloadMediaKind.BOOK -> null
        }
    }

    /**
     * 当前分组 + 媒体筛选下的全集。
     *
     * 失败分组只返回可重试失败任务，因此「全选失败」数量与「重试全部失败」的实际新增数一致。
     */
    fun keysFor(
        state: DownloadManagerState,
        group: DownloadTaskGroup,
    ): Set<String> =
        state
            .containersFor(group)
            .flatMap { it.allEntries() }
            .asSequence()
            .filter { entry ->
                group != DownloadTaskGroup.FAILED || entry.status == DownloadTaskStatus.FAILED
            }
            .map { it.key }
            .toSet()

    fun retryableTasks(state: DownloadManagerState): List<DownloadTask> =
        state.selectedTasks
            .asSequence()
            .filter { DownloadTaskRules.canRetry(it.status) }
            .distinctBy { it.itemId }
            .toList()

    /** 书籍下载没有 [DownloadTask]；失败重试直接回到 ReaderRepository 的整本下载队列。 */
    fun retryableBookEntries(state: DownloadManagerState): List<DownloadHierarchyEntry> =
        state.selectedEntries
            .asSequence()
            .filter { entry ->
                entry.mediaKind == DownloadMediaKind.BOOK &&
                    entry.status == DownloadTaskStatus.FAILED
            }
            .distinctBy { it.itemId }
            .toList()
}
