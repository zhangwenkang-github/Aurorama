package com.zhangwenkang.cinefin.presentation.selection

import com.zhangwenkang.cinefin.models.CollectionType
import com.zhangwenkang.cinefin.models.FindroidEpisode
import com.zhangwenkang.cinefin.models.FindroidFolder
import com.zhangwenkang.cinefin.models.FindroidItem
import com.zhangwenkang.cinefin.models.FindroidMovie
import com.zhangwenkang.cinefin.models.FindroidSeason
import com.zhangwenkang.cinefin.models.FindroidShow
import java.util.UUID

/** W58b 视频 / 书籍多选批量动作。 */
enum class MediaBatchAction {
    /** 视频：加入播放队列开始播（书籍无此动作）。 */
    PLAY,
    DOWNLOAD,
    /** 视频 =「标记已看」、书籍 =「标记已读」；方向由 [batchPlayedTarget] 决定。 */
    MARK_PLAYED,
    FAVORITE,
    /** 红线：只删本机下载（本地文件 / 索引），不提供任何服务器媒体删除。 */
    DELETE,
}

/** 批量目标形态：决定动作集合与「已看 / 已读」文案。 */
enum class MediaBatchMode {
    VIDEO,
    BOOK,
    NONE,
}

/**
 * 库类型 → 批量形态（W58b 范围）。
 *
 * 影视类只认电影 / 剧集 / 家庭视频三个库（聚合页 = 电影 + 剧集）；书籍库单列（无播放、无删除）。 合集 / 播放列表 / 混合 /
 * 文件夹库本波不接（条目不都是可播放媒体，避免"点了没用"的动作）。
 */
fun mediaBatchMode(libraryType: CollectionType): MediaBatchMode =
    when (libraryType) {
        CollectionType.Books -> MediaBatchMode.BOOK
        CollectionType.Movies,
        CollectionType.TvShows,
        CollectionType.HomeVideos -> MediaBatchMode.VIDEO
        else -> MediaBatchMode.NONE
    }

/** 批量工具条的动作集合（视频五键 / 书籍三键）。 */
fun mediaBatchActions(mode: MediaBatchMode): List<MediaBatchAction> =
    when (mode) {
        MediaBatchMode.VIDEO ->
            listOf(
                MediaBatchAction.PLAY,
                MediaBatchAction.DOWNLOAD,
                MediaBatchAction.MARK_PLAYED,
                MediaBatchAction.FAVORITE,
                MediaBatchAction.DELETE,
            )
        MediaBatchMode.BOOK ->
            listOf(
                MediaBatchAction.DOWNLOAD,
                MediaBatchAction.MARK_PLAYED,
                MediaBatchAction.FAVORITE,
            )
        MediaBatchMode.NONE -> emptyList()
    }

/**
 * 单个条目的批量动作可用性（W58b）。
 *
 * [downloaded] / [activeDownload] 来自下载引擎的只读快照； [fromLocalLibrary] = 本地媒体库条目（App 不删用户自己的文件）。
 */
data class MediaBatchCaps(
    val playable: Boolean = false,
    val downloaded: Boolean = false,
    val activeDownload: Boolean = false,
    val fromLocalLibrary: Boolean = false,
) {
    val canDownload: Boolean
        get() = !downloaded && !activeDownload

    /** 删除红线：只删「服务器条目的本地下载」；纯服务器条目 / 本地媒体库条目都不满足。 */
    val canDelete: Boolean
        get() = downloaded && !fromLocalLibrary
}

/** 条目是否可作为视频队列的播放种子（电影 / 单集 / 季 / 剧集，且服务器允许播放）。 */
fun mediaItemPlayable(item: FindroidItem): Boolean =
    item.canPlay &&
        (item is FindroidMovie ||
            item is FindroidEpisode ||
            item is FindroidSeason ||
            item is FindroidShow)

/**
 * 条目是否是「书库里的书」（`FindroidFolder` + kind = BOOK）。
 *
 * 书籍不走下载引擎（那是视频 / 音乐的链路），批量下载要接到阅读器的离线文件链路 （`ReaderRepository.downloadLocalFile` → 私有目录 files/books
 * 下的 .book 文件）。
 */
fun mediaItemIsBook(item: FindroidItem): Boolean =
    item is FindroidFolder && item.kind?.equals("BOOK", ignoreCase = true) == true

fun mediaBatchCaps(
    item: FindroidItem,
    downloadedItemIds: Set<UUID>,
    activeItemIds: Set<UUID>,
    localLibraryItemIds: Set<UUID> = emptySet(),
): MediaBatchCaps =
    MediaBatchCaps(
        playable = mediaItemPlayable(item),
        downloaded = item.id in downloadedItemIds,
        activeDownload = item.id in activeItemIds,
        fromLocalLibrary = item.id in localLibraryItemIds,
    )

/** 批量动作条里某个动作是否可用（任一选中条目满足即可，与下载页 / 音乐口径一致）。 */
fun mediaBatchActionEnabled(action: MediaBatchAction, selected: List<MediaBatchCaps>): Boolean =
    when (action) {
        MediaBatchAction.PLAY -> selected.any { it.playable }
        MediaBatchAction.DOWNLOAD -> selected.any { it.canDownload }
        MediaBatchAction.MARK_PLAYED -> selected.isNotEmpty()
        MediaBatchAction.FAVORITE -> selected.isNotEmpty()
        MediaBatchAction.DELETE -> selected.any { it.canDelete }
    }

/** 批量「播放」的入队顺序：按**当前列表顺序**取可播放条目（不是长按 / 点击顺序）。 */
fun batchPlayOrder(items: List<FindroidItem>): List<FindroidItem> = items.filter {
    mediaItemPlayable(it)
}

/** 批量「下载」目标：跳过已下载 / 已在下载队列的条目。 */
fun batchDownloadTargets(
    items: List<FindroidItem>,
    caps: Map<UUID, MediaBatchCaps>,
): List<FindroidItem> = items.filter { caps[it.id]?.canDownload == true }

/** 批量「删除」目标：只保留「已下载且非本地媒体库」的服务器条目。 */
fun batchDeleteTargets(
    items: List<FindroidItem>,
    caps: Map<UUID, MediaBatchCaps>,
): List<FindroidItem> = items.filter { caps[it.id]?.canDelete == true }

/**
 * 批量「标记已看 / 已读」目标：任一选中条目未完看 → 全部标记已看；全部已看 → 全部取消（与音乐收藏同款双向语义）。
 *
 * 返回 null = 没有选中条目（调用方不写服务器）。
 */
fun batchPlayedTarget(items: List<FindroidItem>): Boolean? =
    if (items.isEmpty()) null else items.any { !it.played }

/** 批量「收藏」目标：任一所选未收藏 → 全部收藏；全部已收藏 → 全部取消。 */
fun batchFavoriteTarget(items: List<FindroidItem>): Boolean? =
    if (items.isEmpty()) null else items.any { !it.favorite }
