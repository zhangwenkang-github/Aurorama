package com.zhangwenkang.cinefin.film.presentation.detail

import com.zhangwenkang.cinefin.core.presentation.downloader.DownloadNetworkPrompt
import com.zhangwenkang.cinefin.core.presentation.downloader.DownloadPrecheckContent
import com.zhangwenkang.cinefin.core.presentation.downloader.DownloadPrecheckKind
import com.zhangwenkang.cinefin.core.presentation.downloader.DownloadPrecheckRules
import com.zhangwenkang.cinefin.core.presentation.downloader.DownloadSpaceStatus
import com.zhangwenkang.cinefin.models.FindroidEpisode
import com.zhangwenkang.cinefin.models.FindroidItem
import com.zhangwenkang.cinefin.models.FindroidSource
import com.zhangwenkang.cinefin.utils.DownloadNetworkDecision
import java.util.Locale
import java.util.UUID
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.MediaStreamType

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

/** W7-T1 批量大小聚合：只统计已知大小，未知条目单独计数（不按 0 计入合计）。 */
data class BatchDownloadSize(
    val knownBytes: Long,
    val knownCount: Int,
    val unknownCount: Int,
) {
    /** 无任何已知大小时返回 null（面板显示「大小未知」而不是 0 B）。 */
    val sizeBytesOrNull: Long?
        get() = if (knownCount == 0) null else knownBytes

    val hasUnknown: Boolean
        get() = unknownCount > 0
}

/**
 * W7-T1 空间截断计划（需求 50）：只入队能装下的前 N 个，剩下的不静默执行。
 *
 * [requiredBytes] = 已选中条目的已知大小之和；未知大小的条目按「可入队、不计入所需空间」处理。
 */
data class BatchSpacePlan(
    val selected: List<UUID>,
    val deferred: Int,
    val requiredBytes: Long,
) {
    val truncated: Boolean
        get() = deferred > 0
}

/**
 * W7-T1b：媒体库多选「下载」入口的前置检查快照（收藏 / 库内容 / 视频聚合网格共用）。
 *
 * - [content] 的数量与合计大小 = **用户所选**（[DownloadPrecheckKind.GENERIC_BATCH] 明细行）；
 * - [items] = 空间截断后可入队的子集（保持原选择顺序），确认时只把它交给 `downloadSelected`；
 * - [space] 的「需要」= 子集所需，所以只有「一项都装不下」（[blocked]）才会判空间不足并禁用确认。
 */
data class MediaBatchPrecheck(
    val content: DownloadPrecheckContent,
    val items: List<FindroidItem>,
    val space: DownloadSpaceStatus,
    /** 因空间不足被延后的条目数（截断说明用）。 */
    val deferredCount: Int,
    /** 截断后子集的已知大小之和（截断说明里的「约 X」）。 */
    val requiredBytes: Long,
) {
    /** 一项都装不下：确认键必须禁用，只给「去清理」。 */
    val blocked: Boolean
        get() = items.isEmpty()

    val truncated: Boolean
        get() = deferredCount > 0
}

/**
 * W51 下载粒度纯函数：状态判定 / 批量集选择 / 去重 / 上限。
 *
 * 入参只用 id 集合与顺序，便于 JVM 单测；调用方（Show / Season / Episode 详情页）负责把服务器条目映射成 id。
 */
object DetailDownloadRules {
    /** 整剧 / 全季批量下载的默认单次上限（用户 2026-10-03 口径：默认 100 集）。 */
    const val DEFAULT_BATCH_LIMIT = 100

    /**
     * W51b：整剧 / 全季取集时必须显式请求的字段。
     *
     * `MediaSources`（引擎按 `sources.first().id` 入队）与 `CanDownload`（下载权限）都是 Jellyfin 的 **按需字段**：只请求
     * `Overview` 时服务器的 `BaseItemDto` 里两者为 null → SDK 映射出空 `sources` / `canDownload =
     * false`，批量目标会被误杀成 0 条（W51 真机缺陷根因，只读探针已复核）。
     */
    val EPISODE_FETCH_FIELDS: List<ItemFields> =
        listOf(ItemFields.OVERVIEW, ItemFields.CAN_DOWNLOAD, ItemFields.MEDIA_SOURCES)

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
     * W51b：整剧 / 全季的下载目标筛选。
     *
     * 判据 = 非虚拟集（`!missing`）且**有媒体源**（`sources` 非空，引擎据此取 sourceId）。 刻意不把 `canDownload`
     * 当门槛：该字段缺失时会被映射成 `false`，正是 W51 真机缺陷的成因； 服务器权限由引擎按 401/403 分类反馈（见
     * `DownloadFailureReason.AUTHENTICATION`）。
     */
    fun downloadTargets(episodes: List<FindroidEpisode>): List<FindroidEpisode> =
        episodes.filter { episode ->
            !episode.missing && episode.sources.isNotEmpty()
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

    // ── W7-T1：下载前置检查（空间 / 网络 / 媒体类型确认） ─────────────────────────

    /** 任意条目已知大小（取首个 size > 0 的源；书籍 / 容器 / 缺 size 的条目返回 null）。 */
    fun itemSizeBytes(item: FindroidItem): Long? =
        item.sources.firstOrNull { source -> source.size > 0 }?.size

    /** 单集已知大小（与 [itemSizeBytes] 同一口径；未知返回 null）。 */
    fun episodeSizeBytes(episode: FindroidEpisode): Long? = itemSizeBytes(episode)

    /**
     * W7-T1b 批量大小聚合（媒体库多选）：[items] 即待入队条目，未知大小单独计数（不按 0 计入合计）。
     *
     * 书籍 / 缺 size 的条目按既有口径「可入队、不计空间」处理，落在 [BatchDownloadSize.unknownCount]。
     */
    fun summarizeItemsBytes(items: List<FindroidItem>): BatchDownloadSize {
        var knownBytes = 0L
        var knownCount = 0
        var unknownCount = 0
        for (item in items) {
            val size = itemSizeBytes(item)
            if (size != null) {
                knownBytes += size
                knownCount++
            } else {
                unknownCount++
            }
        }
        return BatchDownloadSize(
            knownBytes = knownBytes,
            knownCount = knownCount,
            unknownCount = unknownCount,
        )
    }

    /** 批量大小聚合：入参是当前选中的集 id 集合，未知大小单独计数。 */
    fun summarizeBatchBytes(
        episodes: List<FindroidEpisode>,
        selectedIds: Collection<UUID>,
    ): BatchDownloadSize =
        summarizeItemsBytes(episodes.filter { episode -> episode.id in selectedIds })

    /**
     * 空间截断：按顺序装入已知大小的条目，装不下的第一个条目起全部延后（[BatchSpacePlan.deferred]）。
     *
     * 未知大小的条目按「可装下」处理且不累加所需空间——与需求「未知不计并注明」一致，避免把可下载的内容误杀。
     */
    fun fitBatchToSpace(
        orderedIds: List<UUID>,
        sizeOf: (UUID) -> Long?,
        availableBytes: Long,
    ): BatchSpacePlan {
        val selected = mutableListOf<UUID>()
        var requiredBytes = 0L
        var deferred = 0
        var blocked = false
        for (id in orderedIds) {
            if (blocked) {
                deferred++
                continue
            }
            val size = sizeOf(id)
            if (size != null && requiredBytes + size > availableBytes) {
                blocked = true
                deferred++
                continue
            }
            selected += id
            if (size != null) requiredBytes += size
        }
        return BatchSpacePlan(
            selected = selected,
            deferred = deferred,
            requiredBytes = requiredBytes,
        )
    }

    /** 空间预检（直接复用 core 口径，供面板与单测共用一条实现）。 */
    fun spaceStatus(requiredBytes: Long?, availableBytes: Long): DownloadSpaceStatus =
        DownloadPrecheckRules.spaceStatus(requiredBytes, availableBytes)

    /** 网络提示判定：移动数据 / 漫游且未获允许 → 需要用户确认（复用 W57 规则）。 */
    fun networkPrompt(decision: DownloadNetworkDecision): DownloadNetworkPrompt =
        DownloadPrecheckRules.networkPrompt(decision)

    /**
     * W7-T1b：媒体库多选批量的前置检查（纯函数，**零网络**）。
     *
     * [items] 必须是**待入队条目**（调用方已过滤已下载 / 在队列）；[availableBytes] 取下载目标位置（`storageIndex = 0`，与
     * `MediaBatchViewModel.downloadSelected` 的入队位置一致）。空间不足时按原选择顺序只保留装得下的前 N 项，其余计入
     * [MediaBatchPrecheck.deferredCount]。
     */
    fun mediaBatchPrecheck(
        items: List<FindroidItem>,
        availableBytes: Long,
        title: String,
    ): MediaBatchPrecheck {
        val summary = summarizeItemsBytes(items)
        val sizes = items.associate { item -> item.id to itemSizeBytes(item) }
        val plan =
            fitBatchToSpace(
                orderedIds = items.map { item -> item.id },
                sizeOf = { id -> sizes[id] },
                availableBytes = availableBytes,
            )
        val itemsById = items.associateBy { item -> item.id }
        val selected = plan.selected.mapNotNull(itemsById::get)
        val selectedKnownCount = plan.selected.count { id -> sizes[id] != null }
        val spaceRequired =
            when {
                // 一项都装不下：回落到整批所需，让「空间不足」成立（确认禁用 + 去清理）。
                plan.selected.isEmpty() -> summary.sizeBytesOrNull
                // 子集大小全未知：按「未知 / 可用」口径展示，不误判不足。
                selectedKnownCount == 0 -> null
                else -> plan.requiredBytes
            }
        return MediaBatchPrecheck(
            content =
                DownloadPrecheckContent(
                    kind = DownloadPrecheckKind.GENERIC_BATCH,
                    title = title,
                    itemCount = items.size,
                    sizeBytes = summary.sizeBytesOrNull,
                    unknownSizeCount = summary.unknownCount,
                ),
            items = selected,
            space = spaceStatus(spaceRequired, availableBytes),
            deferredCount = plan.deferred,
            requiredBytes = plan.requiredBytes,
        )
    }

    /** 源格式标签：优先服务器容器名，其次路径扩展名；都没有返回 null。 */
    fun formatLabelFor(source: FindroidSource): String? {
        val container = source.container?.trim()?.takeIf { it.isNotEmpty() }
        if (container != null) return container.uppercase(Locale.US)
        val extension = source.path.substringAfterLast('.', "").trim()
        return extension.takeIf { it.isNotEmpty() && it.length <= 5 }?.uppercase(Locale.US)
    }

    /** 视频单集 / 电影的前置确认内容：标注原始文件下载 + 容器格式 + 文件大小； 有外部字幕时补一行「将同时保存附属文件」。没有可用媒体源返回 null（保持既有失败路径）。 */
    fun videoPrecheckContent(item: FindroidItem): DownloadPrecheckContent? {
        val source = item.sources.firstOrNull() ?: return null
        val hasExternalSubtitles =
            source.mediaStreams.any { stream ->
                stream.type == MediaStreamType.SUBTITLE && stream.isExternal
            }
        return DownloadPrecheckContent(
            kind = DownloadPrecheckKind.VIDEO,
            title = item.name,
            itemCount = 1,
            sizeBytes = source.size.takeIf { size -> size > 0 },
            formatLabel = formatLabelFor(source),
            hasSidecarFiles = hasExternalSubtitles,
        )
    }
}
