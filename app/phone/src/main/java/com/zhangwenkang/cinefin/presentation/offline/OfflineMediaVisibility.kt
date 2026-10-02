package com.zhangwenkang.cinefin.presentation.offline

import com.zhangwenkang.cinefin.film.presentation.downloads.DownloadHierarchyBuilder
import com.zhangwenkang.cinefin.film.presentation.downloads.DownloadHierarchyContainer
import com.zhangwenkang.cinefin.film.presentation.downloads.DownloadHierarchyEntry
import com.zhangwenkang.cinefin.utils.DownloadMediaKind
import com.zhangwenkang.cinefin.utils.DownloadTaskStatus
import com.zhangwenkang.cinefin.utils.OfflineMediaEntry
import com.zhangwenkang.cinefin.utils.OfflineMediaEntryKind

/**
 * W36 离线媒体可见性 / 层级（纯函数，单测覆盖）。
 *
 * 「允许离线模式观看」的过滤规则集中在这里：默认只保留允许的条目；[includeHidden] = true 时连 被关闭的条目一起显示（离线媒体库的「管理」视图，用来把条目重新打开）。
 */
object OfflineMediaVisibility {

    /** 按「允许离线模式观看」过滤；[includeHidden] = true 时不过滤（管理视图）。 */
    fun visibleEntries(
        entries: List<OfflineMediaEntry>,
        includeHidden: Boolean,
    ): List<OfflineMediaEntry> =
        if (includeHidden) entries else entries.filter { entry -> entry.allowOffline }

    /** 过滤 + 组织层级（复用下载页的 [DownloadHierarchyBuilder]）： 视频 节目→季→剧集、音乐 专辑→曲目、书籍 单条。 */
    fun buildHierarchy(
        entries: List<OfflineMediaEntry>,
        includeHidden: Boolean,
    ): List<DownloadHierarchyContainer> =
        DownloadHierarchyBuilder.build(
            visibleEntries(entries, includeHidden).map { entry -> entry.toHierarchyEntry() }
        )
}

internal fun OfflineMediaEntry.toHierarchyEntry(): DownloadHierarchyEntry =
    DownloadHierarchyEntry(
        itemId = itemId,
        name = name,
        mediaKind =
            when (kind) {
                OfflineMediaEntryKind.VIDEO -> DownloadMediaKind.VIDEO
                OfflineMediaEntryKind.MUSIC -> DownloadMediaKind.MUSIC
                OfflineMediaEntryKind.BOOK -> DownloadMediaKind.BOOK
            },
        status = DownloadTaskStatus.COMPLETED,
        sourceId = sourceId,
        sizeBytes = sizeBytes,
        seriesId = seriesId,
        seasonId = seasonId,
        seriesName = seriesName,
        seasonName = seasonName,
        episodeIndex = episodeIndex,
        seasonIndex = seasonIndex,
        albumName = albumName,
        artist = artist,
        trackIndex = trackIndex,
        allowOffline = allowOffline,
    )
