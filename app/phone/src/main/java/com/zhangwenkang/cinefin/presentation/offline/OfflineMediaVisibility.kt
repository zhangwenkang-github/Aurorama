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

    /** W36 补充要求：离线模式的「媒体库」页面只展示 ①已下载且允许离线的节目（视频）； ②本地媒体库（W37，占位）。音乐与书籍分别走「音乐」「书架」入口，不混进媒体库。 */
    fun videoOnly(entries: List<OfflineMediaEntry>): List<OfflineMediaEntry> = entries.filter {
        it.kind == OfflineMediaEntryKind.VIDEO
    }

    /**
     * W62：离线书架的书籍集合口径——**不按 [OfflineMediaEntry.allowOffline] 过滤**（下载了就在列）。
     *
     * 书籍没有「管理视图」可用：离线媒体库只列视频（[videoOnly]），若书籍也按 [visibleEntries] 过滤，被关闭
     * 「允许离线模式观看」的书会从所有离线界面消失且无处重新打开；同时与下载页「已完成 · 书籍」清单对不上 （W61 F2：书架 4 本 vs 下载页 5 本，差的就是被关闭的
     * `futuristic_tales`）。行内仍保留开关，被关闭的 行走 `OfflineLeafCard` 的置灰表现。
     */
    fun downloadedBooks(entries: List<OfflineMediaEntry>): List<OfflineMediaEntry> =
        entries.filter {
            it.kind == OfflineMediaEntryKind.BOOK
        }
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
        imageUri = imageUri,
        showImageUri = showImageUri,
        seasonImageUri = seasonImageUri,
        runtimeTicks = runtimeTicks,
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
