package com.zhangwenkang.cinefin.film.presentation.downloads

import com.zhangwenkang.cinefin.utils.DownloadMediaKind
import com.zhangwenkang.cinefin.utils.DownloadTask
import com.zhangwenkang.cinefin.utils.DownloadTaskStatus
import java.util.UUID

/**
 * W34 下载列表层级化的纯函数内核（无 Android 依赖，单测覆盖）。
 *
 * 输入 = 一条条扁平的 [DownloadHierarchyEntry]（下载任务 + 已完成条目归一化后的形态）； 输出 = 媒体类型与层级组织好的容器（视频：节目 → 季 →
 * 剧集；音乐：专辑 → 曲目；书籍：书籍容器）。
 */
/** 归一化后的下载条目：进行中 / 暂停 / 失败任务与已完成条目共用。 */
data class DownloadHierarchyEntry(
    val itemId: UUID,
    val name: String,
    val mediaKind: DownloadMediaKind,
    val status: DownloadTaskStatus,
    /** 已完成条目的 LOCAL source id；进行中 / 失败任务为 null。 */
    val sourceId: String? = null,
    val task: DownloadTask? = null,
    val downloadId: Long? = null,
    val sizeBytes: Long = 0L,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val updatedAt: Long = 0L,
    /** 视频层级：所属节目 / 季；剧集独有。 */
    val seriesId: UUID? = null,
    val seasonId: UUID? = null,
    /** 视频层级：节目 / 季名；剧集独有。 */
    val seriesName: String? = null,
    val seasonName: String? = null,
    val episodeIndex: Int = 0,
    val seasonIndex: Int = 0,
    val imageUri: String? = null,
    /** 音乐层级：专辑名 / 艺人 / 音轨号。 */
    val albumName: String? = null,
    val artist: String? = null,
    val trackIndex: Int = 0,
    /** 书籍容器：聚合信息行文案（体积 / 状态）。 */
    val bookDetail: String? = null,
) {
    val key: String
        get() = itemKey(itemId)

    val canDelete: Boolean
        get() = status != DownloadTaskStatus.COMPLETED || sourceId != null
}

enum class DownloadHierarchyStatus {
    PENDING,
    RUNNING,
    PAUSED,
    FAILED,
    COMPLETED,
}

sealed interface DownloadHierarchyChild {
    val key: String
}

data class DownloadHierarchyLeaf(
    override val key: String,
    val entry: DownloadHierarchyEntry,
) : DownloadHierarchyChild

data class DownloadHierarchySubContainer(
    override val key: String,
    val title: String,
    val detail: String?,
    val status: DownloadHierarchyStatus,
    val completedCount: Int,
    val totalCount: Int,
    val sizeBytes: Long,
    val imageUri: String?,
    val children: List<DownloadHierarchyLeaf>,
) : DownloadHierarchyChild

data class DownloadHierarchyContainer(
    val key: String,
    val title: String,
    val detail: String?,
    val mediaKind: DownloadMediaKind,
    val status: DownloadHierarchyStatus,
    val completedCount: Int,
    val totalCount: Int,
    val sizeBytes: Long,
    val imageUri: String?,
    val canDelete: Boolean,
    val canOpen: Boolean,
    val children: List<DownloadHierarchyChild>,
) {
    val progress: Float
        get() = if (totalCount > 0) completedCount.toFloat() / totalCount else 0f
}

/** 行扁平化结果：LazyColumn 的一行。 */
sealed interface DownloadHierarchyRow {
    val key: String

    data class ContainerRow(
        override val key: String,
        val container: DownloadHierarchyContainer,
        val depth: Int,
        val collapsed: Boolean,
    ) : DownloadHierarchyRow

    data class ChildContainerRow(
        override val key: String,
        val container: DownloadHierarchySubContainer,
        val depth: Int,
        val collapsed: Boolean,
    ) : DownloadHierarchyRow

    data class ItemRow(
        override val key: String,
        val entry: DownloadHierarchyEntry,
        val depth: Int,
    ) : DownloadHierarchyRow
}

fun itemKey(itemId: UUID): String = "item:$itemId"

fun containerKey(kind: DownloadMediaKind, key: String): String = "container:${kind.name}:$key"

private fun childContainerKey(containerKey: String, key: String): String = "sub:$containerKey:$key"

/** 小组聚合规则：先看未完成项——只要有失败即失败，其次下载中，其次全部暂停，其次等待； 没有未完成项（全部已完成 / 列表为空）才算完成。已完成项不把容器状态往下拉。 */
fun aggregateStatus(statuses: Collection<DownloadHierarchyStatus>): DownloadHierarchyStatus = run {
    val unfinished = statuses.filter { it != DownloadHierarchyStatus.COMPLETED }
    when {
        unfinished.isEmpty() -> DownloadHierarchyStatus.COMPLETED
        unfinished.any { it == DownloadHierarchyStatus.FAILED } -> DownloadHierarchyStatus.FAILED
        unfinished.any { it == DownloadHierarchyStatus.RUNNING } -> DownloadHierarchyStatus.RUNNING
        unfinished.all { it == DownloadHierarchyStatus.PAUSED } -> DownloadHierarchyStatus.PAUSED
        else -> DownloadHierarchyStatus.PENDING
    }
}

fun DownloadHierarchyEntry.toHierarchyStatus(): DownloadHierarchyStatus =
    when (status) {
        DownloadTaskStatus.PENDING -> DownloadHierarchyStatus.PENDING
        DownloadTaskStatus.RUNNING -> DownloadHierarchyStatus.RUNNING
        DownloadTaskStatus.PAUSED -> DownloadHierarchyStatus.PAUSED
        DownloadTaskStatus.FAILED -> DownloadHierarchyStatus.FAILED
        DownloadTaskStatus.COMPLETED -> DownloadHierarchyStatus.COMPLETED
    }

/** 层级构建器：按媒体类型输出容器列表（视频容器内再分季）。 */
object DownloadHierarchyBuilder {

    fun build(entries: List<DownloadHierarchyEntry>): List<DownloadHierarchyContainer> {
        val videos = entries.filter { it.mediaKind == DownloadMediaKind.VIDEO }
        val music = entries.filter { it.mediaKind == DownloadMediaKind.MUSIC }
        val books = entries.filter { it.mediaKind == DownloadMediaKind.BOOK }

        return buildList {
            addAll(buildVideoContainers(videos))
            addAll(buildMusicContainers(music))
            addAll(buildBookContainers(books))
        }
    }

    /** 视频：电影 = 顶层叶子容器（无子项，直接展示）；剧集按 节目 → 季 聚合。 */
    private fun buildVideoContainers(
        entries: List<DownloadHierarchyEntry>
    ): List<DownloadHierarchyContainer> {
        val movies = entries.filter { it.seriesId == null }
        val episodes = entries.filter { it.seriesId != null }

        val movieContainers =
            movies
                .sortedWith(
                    compareByDescending<DownloadHierarchyEntry> { it.updatedAt }.thenBy { it.name }
                )
                .map { entry ->
                    DownloadHierarchyContainer(
                        key = containerKey(DownloadMediaKind.VIDEO, "movie:${entry.itemId}"),
                        title = entry.name,
                        detail = null,
                        mediaKind = DownloadMediaKind.VIDEO,
                        status = entry.toHierarchyStatus(),
                        completedCount = if (entry.status == DownloadTaskStatus.COMPLETED) 1 else 0,
                        totalCount = 1,
                        sizeBytes = entry.sizeBytes,
                        imageUri = entry.imageUri,
                        canDelete = entry.canDelete,
                        canOpen = entry.status == DownloadTaskStatus.COMPLETED,
                        children = listOf(DownloadHierarchyLeaf(entry.key, entry)),
                    )
                }

        val showContainers =
            episodes
                .groupBy { it.seriesId!! }
                .map { (seriesId, showEntries) -> buildShowContainer(seriesId, showEntries) }
                .sortedWith(
                    compareByDescending<DownloadHierarchyContainer> { it.updatedAtSort }
                        .thenBy { it.title }
                )

        return movieContainers + showContainers
    }

    private val DownloadHierarchyContainer.updatedAtSort: Long
        get() =
            children.maxOfOrNull { child ->
                when (child) {
                    is DownloadHierarchyLeaf -> child.entry.updatedAt
                    is DownloadHierarchySubContainer ->
                        child.children.maxOfOrNull { it.entry.updatedAt } ?: 0L
                }
            } ?: 0L

    private fun buildShowContainer(
        seriesId: UUID,
        entries: List<DownloadHierarchyEntry>,
    ): DownloadHierarchyContainer {
        val seasons =
            entries
                .groupBy { it.seasonId }
                .map { (seasonId, seasonEntries) ->
                    val sorted =
                        seasonEntries.sortedWith(
                            compareBy<DownloadHierarchyEntry> { it.episodeIndex }.thenBy { it.name }
                        )
                    val statuses = sorted.map { it.toHierarchyStatus() }
                    val completed = sorted.count { it.status == DownloadTaskStatus.COMPLETED }
                    DownloadHierarchySubContainer(
                        key = childContainerKey(seriesId.toString(), "${seasonId ?: "unknown"}"),
                        title = sorted.first().seasonName ?: "未知季",
                        detail =
                            episodeDetail(
                                completed = completed,
                                total = sorted.size,
                                status = aggregateStatus(statuses),
                            ),
                        status = aggregateStatus(statuses),
                        completedCount = completed,
                        totalCount = sorted.size,
                        sizeBytes = sorted.sumOf { it.sizeBytes },
                        imageUri = null,
                        children = sorted.map { DownloadHierarchyLeaf(it.key, it) },
                    )
                }
                .sortedBy { it.title }

        val allEntries = seasons.flatMap { it.children.map { leaf -> leaf.entry } }
        val statuses = allEntries.map { it.toHierarchyStatus() }
        val completed = allEntries.count { it.status == DownloadTaskStatus.COMPLETED }
        val seriesName = entries.first().seriesName ?: entries.first().name
        return DownloadHierarchyContainer(
            key = containerKey(DownloadMediaKind.VIDEO, seriesId.toString()),
            title = seriesName,
            detail = episodeDetail(completed, allEntries.size, aggregateStatus(statuses)),
            mediaKind = DownloadMediaKind.VIDEO,
            status = aggregateStatus(statuses),
            completedCount = completed,
            totalCount = allEntries.size,
            sizeBytes = allEntries.sumOf { it.sizeBytes },
            imageUri = entries.firstOrNull { it.imageUri != null }?.imageUri,
            canDelete = allEntries.any { it.canDelete },
            canOpen = false,
            children = seasons,
        )
    }

    private fun episodeDetail(
        completed: Int,
        total: Int,
        status: DownloadHierarchyStatus,
    ): String {
        val progressText = "$completed/$total 集"
        return when (status) {
            DownloadHierarchyStatus.FAILED -> "$progressText · 失败"
            DownloadHierarchyStatus.RUNNING -> "$progressText · 下载中"
            DownloadHierarchyStatus.PENDING -> "$progressText · 等待下载"
            DownloadHierarchyStatus.PAUSED -> "$progressText · 已暂停"
            DownloadHierarchyStatus.COMPLETED -> "$progressText · 已完成"
        }
    }

    /** 音乐：专辑 → 曲目（专辑按名称聚合，与音乐线的客户端聚合口径一致）。 */
    private fun buildMusicContainers(
        entries: List<DownloadHierarchyEntry>
    ): List<DownloadHierarchyContainer> =
        entries
            .groupBy { it.albumName ?: "未分类" }
            .map { (albumName, albumEntries) ->
                val sorted =
                    albumEntries.sortedWith(
                        compareBy<DownloadHierarchyEntry> { it.trackIndex }.thenBy { it.name }
                    )
                val statuses = sorted.map { it.toHierarchyStatus() }
                val completed = sorted.count { it.status == DownloadTaskStatus.COMPLETED }
                val artist = sorted.firstNotNullOfOrNull { it.artist }
                DownloadHierarchyContainer(
                    key = containerKey(DownloadMediaKind.MUSIC, albumName),
                    title = albumName,
                    detail =
                        listOfNotNull(
                                artist,
                                "${completed}/${sorted.size} 首",
                            )
                            .joinToString(" · "),
                    mediaKind = DownloadMediaKind.MUSIC,
                    status = aggregateStatus(statuses),
                    completedCount = completed,
                    totalCount = sorted.size,
                    sizeBytes = sorted.sumOf { it.sizeBytes },
                    imageUri = sorted.firstNotNullOfOrNull { it.imageUri },
                    canDelete = sorted.any { it.canDelete },
                    canOpen = false,
                    children = sorted.map { DownloadHierarchyLeaf(it.key, it) },
                )
            }
            .sortedWith(
                compareByDescending<DownloadHierarchyContainer> { it.updatedAtSort }
                    .thenBy { it.title }
            )

    /** 书籍：每本书一个容器（无子项，直接展示封面与体积）。 */
    private fun buildBookContainers(
        entries: List<DownloadHierarchyEntry>
    ): List<DownloadHierarchyContainer> =
        entries
            .sortedBy { it.name }
            .map { entry ->
                DownloadHierarchyContainer(
                    key = containerKey(DownloadMediaKind.BOOK, entry.itemId.toString()),
                    title = entry.name,
                    detail = entry.bookDetail,
                    mediaKind = DownloadMediaKind.BOOK,
                    status = entry.toHierarchyStatus(),
                    completedCount = if (entry.status == DownloadTaskStatus.COMPLETED) 1 else 0,
                    totalCount = 1,
                    sizeBytes = entry.sizeBytes,
                    imageUri = entry.imageUri,
                    canDelete = entry.canDelete,
                    canOpen = entry.status == DownloadTaskStatus.COMPLETED,
                    children = listOf(DownloadHierarchyLeaf(entry.key, entry)),
                )
            }
}

/** 层级 → LazyColumn 行（容器可展开 / 折叠；深度供缩进）。 */
object DownloadHierarchyFlattener {

    /** [expandedKeys] = 已展开的容器；默认折叠（用户没点开就只显示容器行）。 */
    fun flatten(
        containers: List<DownloadHierarchyContainer>,
        expandedKeys: Set<String>,
    ): List<DownloadHierarchyRow> {
        val rows = mutableListOf<DownloadHierarchyRow>()
        for (container in containers) {
            val collapsed = container.key !in expandedKeys
            rows +=
                DownloadHierarchyRow.ContainerRow(
                    container.key,
                    container,
                    depth = 0,
                    collapsed = collapsed,
                )
            if (collapsed) continue
            for (child in container.children) {
                when (child) {
                    is DownloadHierarchyLeaf ->
                        rows += DownloadHierarchyRow.ItemRow(child.key, child.entry, depth = 1)
                    is DownloadHierarchySubContainer -> {
                        val subCollapsed = child.key !in expandedKeys
                        rows +=
                            DownloadHierarchyRow.ChildContainerRow(
                                child.key,
                                child,
                                depth = 1,
                                collapsed = subCollapsed,
                            )
                        if (subCollapsed) continue
                        for (leaf in child.children) {
                            rows += DownloadHierarchyRow.ItemRow(leaf.key, leaf.entry, depth = 2)
                        }
                    }
                }
            }
        }
        return rows
    }
}
