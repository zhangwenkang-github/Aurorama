package com.zhangwenkang.cinefin.film.presentation.downloads

import com.zhangwenkang.cinefin.repository.BookAudioRules
import com.zhangwenkang.cinefin.utils.CanonicalDownloadAction
import com.zhangwenkang.cinefin.utils.DownloadCanonicalIdentity
import com.zhangwenkang.cinefin.utils.DownloadMediaKind
import com.zhangwenkang.cinefin.utils.DownloadSpeedRules
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
    /** 下载创建时冻结的来源；旧记录 / 本地文件可为 null。 */
    val serverId: String? = null,
    val userId: String? = null,
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
    /**
     * W36 层级图规则：节目（Show）海报与季（Season）海报各自独立。
     *
     * W59 起严格同级：剧集行的 [imageUri] 只放**剧集自己的缩略图（帧图）**，节目 / 季海报各自只放自己的 primary；
     * 缺图由视图层回退类型占位图标，**不跨级回退**（避免串图）。
     */
    val showImageUri: String? = null,
    val seasonImageUri: String? = null,
    /** W36 元数据快照：条目时长（Jellyfin ticks，1 tick = 100 ns）；离线详情行显示用。 */
    val runtimeTicks: Long = 0L,
    /** 音乐层级：专辑名 / 艺人 / 音轨号。 */
    val albumName: String? = null,
    val artist: String? = null,
    val trackIndex: Int = 0,
    /** 书籍容器：聚合信息行文案（体积 / 状态）。 */
    val bookDetail: String? = null,
    /**
     * W7-T4：书籍原始文件扩展名（小写、无点；取服务器元数据）。
     *
     * 本地书籍文件落盘为 `books/<itemId>.book`，扩展名只能来自服务器条目，拿不到就是 null（界面不显示徽标，不造数据）。
     */
    val bookContainer: String? = null,
    /** W7-T4：书籍类型分区（电子书 / 有声书 / 漫画）；识别不了由构建器归电子书。 */
    val bookSection: DownloadBookSection? = null,
    /** W36：该条目是否允许在离线模式观看（对应 sources.allowOffline / 书籍偏好）。 */
    val allowOffline: Boolean = true,
    /** Canonical snapshot leaf capabilities; null means this is not an active engine task. */
    val canonicalActions: Set<CanonicalDownloadAction>? = null,
) {
    val canonicalIdentity: DownloadCanonicalIdentity
        get() =
            DownloadCanonicalIdentity.of(
                mediaKind = mediaKind,
                itemId = itemId.toString(),
                serverId = serverId ?: task?.serverId,
                userId = userId ?: task?.userId,
                sourceId = task?.sourceId ?: sourceId,
            )

    val key: String
        get() = "item:${canonicalIdentity.canonicalKey}"

    val canDelete: Boolean
        get() =
            canonicalActions?.contains(CanonicalDownloadAction.DELETE)
                ?: (status != DownloadTaskStatus.COMPLETED || sourceId != null)
}

enum class DownloadHierarchyStatus {
    PENDING,
    RUNNING,
    PAUSED,
    FAILED,
    COMPLETED,
}

/** W7-T4：书籍类型分区（下载中心书籍区的三段分区；顺序即展示顺序）。 */
enum class DownloadBookSection {
    EBOOK,
    AUDIOBOOK,
    COMIC,
}

/**
 * W7-T4：书籍格式徽标 / 类型分区的纯函数口径。
 *
 * - 格式徽标 = 服务器原始路径的扩展名（大写）；不在已知集合里就不显示（未知不造数据）；
 * - 类型分区 = 漫画（cbz/cbr…）优先，其次有声书（复用 T3 的 [BookAudioRules] 识别口径），其余归电子书。
 */
object DownloadBookFormatRules {

    private val COMIC_CONTAINERS = setOf("cbz", "cbr", "cb7", "cbt")

    private val KNOWN_CONTAINERS =
        setOf(
            // 电子书
            "epub",
            "pdf",
            "mobi",
            "azw",
            "azw3",
            "fb2",
            "txt",
            "djvu",
            "doc",
            "docx",
            "rtf",
            "html",
            "htm",
            "md",
            // 漫画
            "cbz",
            "cbr",
            "cb7",
            "cbt",
            // 有声书
            "m4b",
            "m4a",
            "mp3",
            "flac",
            "opus",
            "aac",
            "ogg",
            "wav",
            "alac",
        )

    /** 从服务器原始路径取小写扩展名；没有扩展名 / 不像扩展名时返回 null。 */
    fun containerOf(path: String?): String? {
        val name =
            path?.trim()?.substringAfterLast('/')?.substringAfterLast('\\')?.takeIf {
                it.isNotBlank()
            } ?: return null
        val extension = name.substringAfterLast('.', "").trim().lowercase()
        return extension.takeIf { value ->
            value.isNotEmpty() && value.length <= 5 && value.all { it.isLetterOrDigit() }
        }
    }

    /** 徽标文字：已知格式返回大写扩展名，未知返回 null（界面不显示徽标）。 */
    fun badge(container: String?): String? =
        container?.trim()?.lowercase()?.takeIf { it in KNOWN_CONTAINERS }?.uppercase()

    /** 类型分区：漫画 → 有声书 → 电子书（识别不了归电子书，不猜）。 */
    fun section(mediaKind: String?, container: String?): DownloadBookSection {
        val normalized = container?.trim()?.lowercase()
        return when {
            normalized in COMIC_CONTAINERS -> DownloadBookSection.COMIC
            BookAudioRules.isAudiobook(mediaKind, normalized) -> DownloadBookSection.AUDIOBOOK
            else -> DownloadBookSection.EBOOK
        }
    }
}

/**
 * W7-T4：视频季归属口径——特别篇 / SP / 无季归属统一进显式「其他」折叠组。
 *
 * 判定只看季名：服务器 `Specials`（各语言常见别名）或缺失季名都归「其他」；不拿季号 0 作判据 —— 快照里的 `seasonIndex` 缺省为 0，用它会把普通季误收进「其他」。
 */
object DownloadSeasonGrouping {

    /** 「其他」组组名（与既有「未知季」「未分类」同类，纯函数内固定中文文案）。 */
    const val OTHER_GROUP_TITLE = "其他"

    /** 「其他」组容器 key 后缀（同一节目下唯一）。 */
    const val OTHER_SEASON_KEY = "other"

    private val SPECIAL_SEASON_NAMES =
        setOf(
            "specials",
            "special",
            "sp",
            "extras",
            "特別篇",
            "特别篇",
            "特辑",
            "特輯",
            "番外",
            "其他",
            "未知季",
            "未分类",
            "未分類",
        )

    fun isOther(seasonName: String?): Boolean {
        val name = seasonName?.trim()?.lowercase().orEmpty()
        return name.isEmpty() || name in SPECIAL_SEASON_NAMES
    }
}

/**
 * W52 条目级聚合口径（纯函数）：
 *
 * - [expectedTotalBytes]：优先任务上报的总大小；已完成条目 / 旧数据没有总大小时回落到文件体积；
 * - [effectiveDownloadedBytes]：已完成条目 = 文件体积（任务字段不保留历史字节），其余 = 已下载字节；
 * - [remainingBytes]：剩余待下载字节（不会为负）。
 */
val DownloadHierarchyEntry.expectedTotalBytes: Long
    get() = if (totalBytes > 0L) totalBytes else sizeBytes.coerceAtLeast(0L)

val DownloadHierarchyEntry.effectiveDownloadedBytes: Long
    get() =
        if (status == DownloadTaskStatus.COMPLETED) expectedTotalBytes
        else downloadedBytes.coerceAtLeast(0L)

val DownloadHierarchyEntry.remainingBytes: Long
    get() = (expectedTotalBytes - effectiveDownloadedBytes).coerceAtLeast(0L)

/** W52：一层容器（节目 / 季 / 专辑 / 书籍）的聚合值。 */
data class DownloadAggregate(
    val downloadedBytes: Long,
    val totalBytes: Long,
    val remainingBytes: Long,
    val speedBytesPerSecond: Long,
    val etaSeconds: Long?,
)

/**
 * W52 容器聚合口径（纯函数，单测覆盖）：
 *
 * - 已下载 = 子条目已下载字节之和；总大小 = 子条目期待总大小之和；
 * - **速度 = 子任务速度之和**（进程重启后任务速度为 0，界面显示占位「—」）；
 * - 剩余时间 = 剩余字节 / 聚合速度（无速度或无剩余量时为 null，界面显示「—」）。
 */
object DownloadAggregateRules {

    fun of(entries: List<DownloadHierarchyEntry>): DownloadAggregate {
        val downloaded = entries.sumOf { it.effectiveDownloadedBytes }
        val total = entries.sumOf { it.expectedTotalBytes }
        val remaining = entries.sumOf { it.remainingBytes }
        val speed = entries.sumOf { entry -> entry.task?.speedBytesPerSecond ?: 0L }
        return DownloadAggregate(
            downloadedBytes = downloaded,
            totalBytes = total,
            remainingBytes = remaining,
            speedBytesPerSecond = speed,
            etaSeconds =
                if (remaining > 0L) {
                    DownloadSpeedRules.etaSeconds(total, downloaded, speed)
                } else {
                    null
                },
        )
    }
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
    /** W52 聚合：已下载 / 总大小 / 速度 / 剩余时间。 */
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val speedBytesPerSecond: Long = 0L,
    val etaSeconds: Long? = null,
    /** W7-T4：是否为「其他」归集组（特别篇 / SP / 无季归属）；排序时永远排在普通季之后。 */
    val otherGroup: Boolean = false,
) : DownloadHierarchyChild {
    val progress: Float
        get() = if (totalCount > 0) completedCount.toFloat() / totalCount else 0f

    /** W52：按字节的聚合进度（比条目计数更平滑；总大小未知时回落到条目计数）。 */
    val byteProgress: Float
        get() =
            when {
                totalBytes > 0L -> (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
                else -> progress
            }
}

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
    /** W52 聚合：已下载 / 总大小 / 速度 / 剩余时间。 */
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val speedBytesPerSecond: Long = 0L,
    val etaSeconds: Long? = null,
    /** W7-T4：书籍格式徽标（EPUB / PDF / M4B…）；非书籍或扩展名未知为 null。 */
    val formatLabel: String? = null,
    /** W7-T4：书籍类型分区；非书籍为 null。 */
    val bookSection: DownloadBookSection? = null,
) {
    val progress: Float
        get() = if (totalCount > 0) completedCount.toFloat() / totalCount else 0f

    /** W52：按字节的聚合进度（比条目计数更平滑；总大小未知时回落到条目计数）。 */
    val byteProgress: Float
        get() =
            when {
                totalBytes > 0L -> (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
                else -> progress
            }
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

fun entryKey(
    mediaKind: DownloadMediaKind,
    itemId: UUID,
    serverId: String?,
    userId: String?,
    sourceId: String?,
): String =
    "item:" +
        DownloadCanonicalIdentity.of(
                mediaKind = mediaKind,
                itemId = itemId.toString(),
                serverId = serverId,
                userId = userId,
                sourceId = sourceId,
            )
            .canonicalKey

fun itemKey(itemId: UUID): String =
    entryKey(
        mediaKind = DownloadMediaKind.VIDEO,
        itemId = itemId,
        serverId = null,
        userId = null,
        sourceId = null,
    )

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
        DownloadTaskStatus.DELETING -> DownloadHierarchyStatus.PENDING
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
                    val aggregate = DownloadAggregateRules.of(listOf(entry))
                    DownloadHierarchyContainer(
                        key = containerKey(DownloadMediaKind.VIDEO, "movie:${entry.key}"),
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
                        downloadedBytes = aggregate.downloadedBytes,
                        totalBytes = aggregate.totalBytes,
                        speedBytesPerSecond = aggregate.speedBytesPerSecond,
                        etaSeconds = aggregate.etaSeconds,
                    )
                }

        val showContainers =
            episodes
                .groupBy {
                    VideoShowGroup(
                        seriesId = it.seriesId!!,
                        serverId = it.serverId,
                        userId = it.userId,
                    )
                }
                .map { (group, showEntries) -> buildShowContainer(group, showEntries) }
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
        group: VideoShowGroup,
        entries: List<DownloadHierarchyEntry>,
    ): DownloadHierarchyContainer {
        val showKey = listOf(group.seriesId, group.serverId, group.userId).joinToString("|")
        val otherEntries = entries.filter { DownloadSeasonGrouping.isOther(it.seasonName) }
        val regularEntries = entries.filterNot { DownloadSeasonGrouping.isOther(it.seasonName) }
        val seasons = buildList {
            addAll(buildSeasonContainers(showKey, regularEntries))
            // W7-T4：特别篇 / SP / 无季归属合并为一个显式「其他」组，始终排在季卡之后。
            if (otherEntries.isNotEmpty()) add(buildOtherSeason(showKey, otherEntries))
        }

        val allEntries = seasons.flatMap { it.children.map { leaf -> leaf.entry } }
        val statuses = allEntries.map { it.toHierarchyStatus() }
        val completed = allEntries.count { it.status == DownloadTaskStatus.COMPLETED }
        val aggregate = DownloadAggregateRules.of(allEntries)
        val seriesName = entries.first().seriesName ?: entries.first().name
        return DownloadHierarchyContainer(
            key =
                containerKey(
                    DownloadMediaKind.VIDEO,
                    listOf(group.seriesId, group.serverId, group.userId).joinToString("|"),
                ),
            title = seriesName,
            detail = episodeDetail(completed, allEntries.size, aggregateStatus(statuses)),
            mediaKind = DownloadMediaKind.VIDEO,
            status = aggregateStatus(statuses),
            completedCount = completed,
            totalCount = allEntries.size,
            sizeBytes = allEntries.sumOf { it.sizeBytes },
            // W59：节目只用自己的海报（缺图 → 类型占位，不跨级回退）。
            imageUri = entries.firstNotNullOfOrNull { it.showImageUri },
            canDelete = allEntries.any { it.canDelete },
            canOpen = false,
            children = seasons,
            downloadedBytes = aggregate.downloadedBytes,
            totalBytes = aggregate.totalBytes,
            speedBytesPerSecond = aggregate.speedBytesPerSecond,
            etaSeconds = aggregate.etaSeconds,
        )
    }

    /** 普通季：按季 ID 分桶，季内按集号；季卡按标题排序（W7-T4 前的既有口径）。 */
    private fun buildSeasonContainers(
        showKey: String,
        entries: List<DownloadHierarchyEntry>,
    ): List<DownloadHierarchySubContainer> =
        entries
            .groupBy { it.seasonId }
            .map { (seasonId, seasonEntries) ->
                seasonContainer(
                    key = childContainerKey(showKey, "${seasonId ?: "unknown"}"),
                    title = seasonEntries.firstNotNullOfOrNull { it.seasonName },
                    sorted = seasonEntries,
                )
            }
            .sortedBy { it.title }

    /** W7-T4：「其他」组——所有特别篇 / SP / 无季归属集合并成一卡，视觉与季卡同语言。 */
    private fun buildOtherSeason(
        showKey: String,
        entries: List<DownloadHierarchyEntry>,
    ): DownloadHierarchySubContainer =
        seasonContainer(
            key = childContainerKey(showKey, DownloadSeasonGrouping.OTHER_SEASON_KEY),
            title = DownloadSeasonGrouping.OTHER_GROUP_TITLE,
            sorted = entries,
            otherGroup = true,
        )

    private fun seasonContainer(
        key: String,
        title: String?,
        sorted: List<DownloadHierarchyEntry>,
        otherGroup: Boolean = false,
    ): DownloadHierarchySubContainer {
        val ordered =
            sorted.sortedWith(
                compareBy<DownloadHierarchyEntry> { it.episodeIndex }.thenBy { it.name }
            )
        val statuses = ordered.map { it.toHierarchyStatus() }
        val completed = ordered.count { it.status == DownloadTaskStatus.COMPLETED }
        val aggregate = DownloadAggregateRules.of(ordered)
        return DownloadHierarchySubContainer(
            key = key,
            title = title ?: DownloadSeasonGrouping.OTHER_GROUP_TITLE,
            detail =
                episodeDetail(
                    completed = completed,
                    total = ordered.size,
                    status = aggregateStatus(statuses),
                ),
            status = aggregateStatus(statuses),
            completedCount = completed,
            totalCount = ordered.size,
            sizeBytes = ordered.sumOf { it.sizeBytes },
            // W59：季只用自己的海报（缺图 → 类型占位，不跨级回退）。
            imageUri = ordered.firstNotNullOfOrNull { it.seasonImageUri },
            children = ordered.map { DownloadHierarchyLeaf(it.key, it) },
            downloadedBytes = aggregate.downloadedBytes,
            totalBytes = aggregate.totalBytes,
            speedBytesPerSecond = aggregate.speedBytesPerSecond,
            etaSeconds = aggregate.etaSeconds,
            otherGroup = otherGroup,
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
            .groupBy {
                MusicAlbumGroup(
                    albumName = it.albumName ?: "未分类",
                    serverId = it.serverId,
                    userId = it.userId,
                )
            }
            .map { (group, albumEntries) ->
                val albumName = group.albumName
                val sorted =
                    albumEntries.sortedWith(
                        compareBy<DownloadHierarchyEntry> { it.trackIndex }.thenBy { it.name }
                    )
                val statuses = sorted.map { it.toHierarchyStatus() }
                val completed = sorted.count { it.status == DownloadTaskStatus.COMPLETED }
                val artist = sorted.firstNotNullOfOrNull { it.artist }
                val aggregate = DownloadAggregateRules.of(sorted)
                DownloadHierarchyContainer(
                    key =
                        containerKey(
                            DownloadMediaKind.MUSIC,
                            listOf(albumName, group.serverId, group.userId).joinToString("|"),
                        ),
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
                    downloadedBytes = aggregate.downloadedBytes,
                    totalBytes = aggregate.totalBytes,
                    speedBytesPerSecond = aggregate.speedBytesPerSecond,
                    etaSeconds = aggregate.etaSeconds,
                )
            }
            .sortedWith(
                compareByDescending<DownloadHierarchyContainer> { it.updatedAtSort }
                    .thenBy { it.title }
            )

    /** 书籍：每本书一个容器（无子项，直接展示封面与体积）；W7-T4 追加格式徽标与类型分区。 */
    private fun buildBookContainers(
        entries: List<DownloadHierarchyEntry>
    ): List<DownloadHierarchyContainer> =
        entries
            .sortedBy { it.name }
            .map { entry ->
                val aggregate = DownloadAggregateRules.of(listOf(entry))
                DownloadHierarchyContainer(
                    key = containerKey(DownloadMediaKind.BOOK, entry.key),
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
                    downloadedBytes = aggregate.downloadedBytes,
                    totalBytes = aggregate.totalBytes,
                    speedBytesPerSecond = aggregate.speedBytesPerSecond,
                    etaSeconds = aggregate.etaSeconds,
                    formatLabel = DownloadBookFormatRules.badge(entry.bookContainer),
                    bookSection = entry.bookSection ?: DownloadBookSection.EBOOK,
                )
            }
}

private data class VideoShowGroup(
    val seriesId: UUID,
    val serverId: String?,
    val userId: String?,
)

/** W7-T4：书籍区一个类型分区（电子书 / 有声书 / 漫画）及其书籍容器（分区内已按当前排序）。 */
data class DownloadBookSectionGroup(
    val section: DownloadBookSection,
    val containers: List<DownloadHierarchyContainer>,
)

/**
 * W7-T4：书籍区按类型分区（需求 44）。
 *
 * - 分区顺序 = [DownloadBookSection] 声明顺序（电子书 / 有声书 / 漫画）；
 * - 空分区不渲染；
 * - 分区内排序沿用 [DownloadSortRules]（同级节点排序，不跨分区混排）。
 */
object DownloadBookSectionRules {

    fun group(
        containers: List<DownloadHierarchyContainer>,
        sortMode: DownloadSortMode = DownloadSortMode.DEFAULT,
    ): List<DownloadBookSectionGroup> {
        val books = containers.filter { it.mediaKind == DownloadMediaKind.BOOK }
        return DownloadBookSection.entries.mapNotNull { section ->
            val members = books.filter { (it.bookSection ?: DownloadBookSection.EBOOK) == section }
            if (members.isEmpty()) {
                null
            } else {
                DownloadBookSectionGroup(
                    section = section,
                    containers = DownloadSortRules.sortContainers(members, sortMode),
                )
            }
        }
    }
}

private data class MusicAlbumGroup(
    val albumName: String,
    val serverId: String?,
    val userId: String?,
)

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
