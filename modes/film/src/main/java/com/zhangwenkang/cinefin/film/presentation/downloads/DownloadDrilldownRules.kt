package com.zhangwenkang.cinefin.film.presentation.downloads

import com.zhangwenkang.cinefin.utils.DownloadMediaKind

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

    /** 顶层列表排序：进行中 → 失败 → 已完成；同组按最近更新时间倒序，再按标题。 */
    fun sortForDisplay(
        containers: List<DownloadHierarchyContainer>
    ): List<DownloadHierarchyContainer> =
        containers.sortedWith(
            compareBy<DownloadHierarchyContainer> { statusPriority(it.status) }
                .thenByDescending { it.latestUpdatedAt() }
                .thenBy { it.title }
        )

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
 * [expandedSeasonKeys] 之外的季保持折叠（默认只显示季卡）。
 */
fun detailRows(
    container: DownloadHierarchyContainer,
    expandedSeasonKeys: Set<String>,
): List<DownloadDetailRow> {
    val rows = mutableListOf<DownloadDetailRow>()
    for (child in container.children) {
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

private fun DownloadHierarchyContainer.latestUpdatedAt(): Long =
    allEntries().maxOfOrNull { it.updatedAt } ?: 0L
