package com.zhangwenkang.cinefin.film.presentation.downloads

import com.zhangwenkang.cinefin.utils.DownloadMediaKind

/**
 * W7-T4：下载中心用户可选排序。
 *
 * [DEFAULT] = 现有分组排序（进行中 → 失败 → 已完成 / 季内按集号）；其余三档只作用于**同级节点**（节目卡之间、季卡之间、 集 /
 * 曲目之间），不跨层重排。排序状态只在内存里（按媒体类型分槽），不落盘。
 */
enum class DownloadSortMode {
    DEFAULT,
    NAME,
    DOWNLOAD_TIME,
    SIZE,
}

/** W7-T4：同级节点排序的纯函数口径（容器 / 季 / 叶子）。 */
object DownloadSortRules {

    fun sortContainers(
        containers: List<DownloadHierarchyContainer>,
        mode: DownloadSortMode,
    ): List<DownloadHierarchyContainer> =
        when (mode) {
            DownloadSortMode.DEFAULT -> containers
            else -> containers.sortedWith(containerComparator(mode))
        }

    /** 季卡排序：普通季按当前排序，`其他` 组永远排在最后（它是归集桶，不是一季）。 */
    fun sortSeasons(
        seasons: List<DownloadHierarchySubContainer>,
        mode: DownloadSortMode,
    ): List<DownloadHierarchySubContainer> =
        when (mode) {
            DownloadSortMode.DEFAULT -> seasons.sortedBy { it.otherGroup }
            else ->
                seasons.sortedWith(
                    compareBy<DownloadHierarchySubContainer> { it.otherGroup }
                        .then(seasonComparator(mode))
                )
        }

    fun sortLeaves(
        leaves: List<DownloadHierarchyLeaf>,
        mode: DownloadSortMode,
    ): List<DownloadHierarchyLeaf> =
        when (mode) {
            DownloadSortMode.DEFAULT -> leaves
            else -> leaves.sortedWith(leafComparator(mode))
        }

    private fun containerComparator(
        mode: DownloadSortMode
    ): Comparator<DownloadHierarchyContainer> =
        when (mode) {
            DownloadSortMode.NAME ->
                compareBy<DownloadHierarchyContainer> { it.title.lowercase() }
                    .thenByDescending { it.latestUpdatedAt() }
            DownloadSortMode.DOWNLOAD_TIME ->
                compareByDescending<DownloadHierarchyContainer> { it.latestUpdatedAt() }
                    .thenBy { it.title.lowercase() }
            DownloadSortMode.SIZE ->
                compareByDescending<DownloadHierarchyContainer> { it.sortableBytes }
                    .thenBy { it.title.lowercase() }
            DownloadSortMode.DEFAULT -> error("DEFAULT 不走比较器")
        }

    private fun seasonComparator(
        mode: DownloadSortMode
    ): Comparator<DownloadHierarchySubContainer> =
        when (mode) {
            DownloadSortMode.NAME ->
                compareBy<DownloadHierarchySubContainer> { it.title.lowercase() }
                    .thenByDescending { season ->
                        season.children.maxOfOrNull { leaf -> leaf.entry.updatedAt } ?: 0L
                    }
            DownloadSortMode.DOWNLOAD_TIME ->
                compareByDescending<DownloadHierarchySubContainer> { season ->
                        season.children.maxOfOrNull { leaf -> leaf.entry.updatedAt } ?: 0L
                    }
                    .thenBy { it.title.lowercase() }
            DownloadSortMode.SIZE ->
                compareByDescending<DownloadHierarchySubContainer> { it.sortableBytes }
                    .thenBy { it.title.lowercase() }
            DownloadSortMode.DEFAULT -> error("DEFAULT 不走比较器")
        }

    private fun leafComparator(mode: DownloadSortMode): Comparator<DownloadHierarchyLeaf> =
        when (mode) {
            DownloadSortMode.NAME ->
                compareBy<DownloadHierarchyLeaf> { it.entry.name.lowercase() }
                    .thenByDescending { it.entry.updatedAt }
            DownloadSortMode.DOWNLOAD_TIME ->
                compareByDescending<DownloadHierarchyLeaf> { it.entry.updatedAt }
                    .thenBy { it.entry.name.lowercase() }
            DownloadSortMode.SIZE ->
                compareByDescending<DownloadHierarchyLeaf> { it.entry.expectedTotalBytes }
                    .thenBy { it.entry.name.lowercase() }
            DownloadSortMode.DEFAULT -> error("DEFAULT 不走比较器")
        }

    /** 大小比较口径：聚合总大小优先，已完成 / 旧数据回落到文件体积。 */
    private val DownloadHierarchyContainer.sortableBytes: Long
        get() = if (totalBytes > 0L) totalBytes else sizeBytes

    private val DownloadHierarchySubContainer.sortableBytes: Long
        get() = if (totalBytes > 0L) totalBytes else sizeBytes
}

/**
 * W59 下载页「钻取式 IA」的纯函数内核（无 Android 依赖，单测覆盖）。
 *
 * 口径（`DOWNLOAD_PLAN` §22，用户 2026-10-04 拍板）：
 * - 顶层列表只显示四种顶层项：Show 卡 / 音乐专辑卡 / 电影条目 / 书籍条目；
 * - Show / 专辑可钻取详情（Show 详情 = 季卡可展开剧集；专辑详情 = 曲目列表）；电影 / 书籍是平铺单条目；
 * - 顶层排序：进行中 → 失败 → 已完成；
 * - 进入 Show 详情自动展开**第一个进行中的季**（没有进行中则第一个未完成的季；全完成不自动展开）。
 */
object DownloadDrilldownRules {

    /**
     * 可钻取（详情页）：Show（季子容器）与**音乐专辑**（曲目平铺在后半区）。
     *
     * 电影 / 书籍 = 平铺单条目行，不钻取。
     */
    fun isDrilldown(container: DownloadHierarchyContainer): Boolean =
        container.mediaKind == DownloadMediaKind.MUSIC ||
            container.children.any { it is DownloadHierarchySubContainer }

    /** 顶层列表排序：DEFAULT = 进行中 → 失败 → 已完成（同组按最近更新时间倒序，再按标题）； 其余三档按用户选择的同级排序（名称 / 下载时间 / 大小）。 */
    fun sortForDisplay(
        containers: List<DownloadHierarchyContainer>,
        mode: DownloadSortMode = DownloadSortMode.DEFAULT,
    ): List<DownloadHierarchyContainer> =
        when (mode) {
            DownloadSortMode.DEFAULT ->
                containers.sortedWith(
                    compareBy<DownloadHierarchyContainer> { statusPriority(it.status) }
                        .thenByDescending { it.latestUpdatedAt() }
                        .thenBy { it.title }
                )
            else -> DownloadSortRules.sortContainers(containers, mode)
        }

    /** 详情页自动展开的季：第一个进行中 → 第一个未完成 → null（全完成不展开）。 */
    fun autoExpandSeasonKey(container: DownloadHierarchyContainer): String? {
        val seasons = container.children.filterIsInstance<DownloadHierarchySubContainer>()
        return seasons.firstOrNull { it.status == DownloadHierarchyStatus.RUNNING }?.key
            ?: seasons.firstOrNull { it.status != DownloadHierarchyStatus.COMPLETED }?.key
    }

    /** 详情页头部聚合统计（与容器卡片同源：字节 / 速度 / 剩余时间）。 */
    fun aggregate(container: DownloadHierarchyContainer): DownloadAggregate =
        DownloadAggregateRules.of(container.allEntries())

    private fun statusPriority(status: DownloadHierarchyStatus): Int =
        when (status) {
            DownloadHierarchyStatus.RUNNING -> 0
            DownloadHierarchyStatus.PENDING -> 1
            DownloadHierarchyStatus.PAUSED -> 2
            DownloadHierarchyStatus.FAILED -> 3
            DownloadHierarchyStatus.COMPLETED -> 4
        }
}

/** 容器内全部条目（含子容器）；顶层卡片聚合计 / 详情页 / 批量操作同一口径。 */
fun DownloadHierarchyContainer.allEntries(): List<DownloadHierarchyEntry> =
    children.flatMap { child ->
        when (child) {
            is DownloadHierarchyLeaf -> listOf(child.entry)
            is DownloadHierarchySubContainer -> child.children.map { it.entry }
        }
    }

/** 详情页行：季卡（Show）/ 单条目行（展开的剧集、专辑曲目）。 */
sealed interface DownloadDetailRow {
    val key: String

    data class Season(
        override val key: String,
        val season: DownloadHierarchySubContainer,
        val expanded: Boolean,
    ) : DownloadDetailRow

    data class Item(
        override val key: String,
        val entry: DownloadHierarchyEntry,
    ) : DownloadDetailRow
}

/**
 * 详情页行扁平化：Show = 季卡（点击展开该季剧集）；其它（专辑）= 曲目平铺。
 *
 * [expandedSeasonKeys] 之外的季保持折叠（默认只显示季卡）。 [sortMode] 只排同级节点：季卡之间、季内集之间、专辑曲目之间；`其他` 组始终在季卡之后。
 */
fun detailRows(
    container: DownloadHierarchyContainer,
    expandedSeasonKeys: Set<String>,
    sortMode: DownloadSortMode = DownloadSortMode.DEFAULT,
): List<DownloadDetailRow> {
    val rows = mutableListOf<DownloadDetailRow>()
    for (child in container.sortedChildren(sortMode)) {
        when (child) {
            is DownloadHierarchyLeaf -> rows += DownloadDetailRow.Item(child.key, child.entry)
            is DownloadHierarchySubContainer -> {
                val expanded = child.key in expandedSeasonKeys
                rows += DownloadDetailRow.Season(child.key, child, expanded)
                if (expanded) {
                    child.children.forEach { leaf ->
                        rows += DownloadDetailRow.Item(leaf.key, leaf.entry)
                    }
                }
            }
        }
    }
    return rows
}

/** W7-T4：按排序模式重排同一层级的子项（DEFAULT 保持构建器顺序）。 */
private fun DownloadHierarchyContainer.sortedChildren(
    sortMode: DownloadSortMode
): List<DownloadHierarchyChild> {
    if (sortMode == DownloadSortMode.DEFAULT) return children
    val seasons = children.filterIsInstance<DownloadHierarchySubContainer>()
    if (seasons.isEmpty()) {
        return DownloadSortRules.sortLeaves(
            children.filterIsInstance<DownloadHierarchyLeaf>(),
            sortMode,
        )
    }
    return DownloadSortRules.sortSeasons(seasons, sortMode).map { season ->
        season.copy(children = DownloadSortRules.sortLeaves(season.children, sortMode))
    }
}

private fun DownloadHierarchyContainer.latestUpdatedAt(): Long =
    allEntries().maxOfOrNull { it.updatedAt } ?: 0L
