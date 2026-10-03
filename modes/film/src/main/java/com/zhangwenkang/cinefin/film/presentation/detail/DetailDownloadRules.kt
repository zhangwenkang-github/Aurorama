package com.zhangwenkang.cinefin.film.presentation.detail

import java.util.UUID

/** W51 详情页下载动作三态（单集与容器共用）。 */
enum class DetailDownloadState {
    /** 未下载且不在队列：点击可加入下载队列。 */
    NOT_DOWNLOADED,
    /** 已在队列（排队 / 下载中 / 暂停）：点击只提示，不重复入队。 */
    IN_QUEUE,
    /** 已下载（本机完整文件）：点击只提示，不重复下载。 */
    DOWNLOADED,
}

/**
 * W51 批量下载选择结果（整剧 / 全季）。
 *
 * [selected] 按输入顺序保序；[overflow] = 因单次上限未加入的缺失集数。
 */
data class BatchDownloadSelection(
    val selected: List<UUID>,
    val skippedDownloaded: Int,
    val skippedQueued: Int,
    val overflow: Int,
)

/**
 * W51 下载粒度纯函数：状态判定 / 批量集选择 / 去重 / 上限。
 *
 * 入参只用 id 集合与顺序，便于 JVM 单测；调用方（Show / Season / Episode 详情页）负责把服务器条目映射成 id。
 */
object DetailDownloadRules {
    /** 整剧 / 全季批量下载的默认单次上限（用户 2026-10-03 口径：默认 100 集）。 */
    const val DEFAULT_BATCH_LIMIT = 100

    /** 单集三态：已下载优先于已在队列（同时命中时按「已下载」提示）。 */
    fun stateOf(
        itemId: UUID,
        downloaded: Set<UUID>,
        queued: Set<UUID>,
    ): DetailDownloadState =
        when {
            downloaded.contains(itemId) -> DetailDownloadState.DOWNLOADED
            queued.contains(itemId) -> DetailDownloadState.IN_QUEUE
            else -> DetailDownloadState.NOT_DOWNLOADED
        }

    /**
     * 容器（整剧 / 全季）聚合三态：
     * - 只要还有既未下载也不在队列的集 → 未下载（可继续补齐）；
     * - 全部落定且至少一集在队列 → 已在队列；
     * - 全部已下载 → 已下载。
     */
    fun containerState(
        itemIds: List<UUID>,
        downloaded: Set<UUID>,
        queued: Set<UUID>,
    ): DetailDownloadState {
        if (itemIds.isEmpty()) return DetailDownloadState.NOT_DOWNLOADED
        if (itemIds.any { !downloaded.contains(it) && !queued.contains(it) }) {
            return DetailDownloadState.NOT_DOWNLOADED
        }
        return if (itemIds.any { queued.contains(it) }) {
            DetailDownloadState.IN_QUEUE
        } else {
            DetailDownloadState.DOWNLOADED
        }
    }

    /**
     * 批量选择要入队的剧集：
     * - 已下载 / 已在队列的条目自动跳过（用户口径「仅补齐缺失集」）；
     * - [limit] = 单次上限（null = 不限）；
     * - 超上限的缺失集计入 [BatchDownloadSelection.overflow]，不改动选择顺序。
     */
    fun selectBatch(
        itemIds: List<UUID>,
        downloaded: Set<UUID>,
        queued: Set<UUID>,
        limit: Int? = DEFAULT_BATCH_LIMIT,
    ): BatchDownloadSelection {
        val selected = mutableListOf<UUID>()
        var skippedDownloaded = 0
        var skippedQueued = 0
        var overflow = 0
        for (itemId in itemIds) {
            when {
                downloaded.contains(itemId) -> skippedDownloaded++
                queued.contains(itemId) -> skippedQueued++
                limit != null && selected.size >= limit -> overflow++
                else -> selected += itemId
            }
        }
        return BatchDownloadSelection(
            selected = selected,
            skippedDownloaded = skippedDownloaded,
            skippedQueued = skippedQueued,
            overflow = overflow,
        )
    }
}
