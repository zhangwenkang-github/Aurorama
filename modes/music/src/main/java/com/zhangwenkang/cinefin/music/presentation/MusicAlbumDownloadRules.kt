package com.zhangwenkang.cinefin.music.presentation

import com.zhangwenkang.cinefin.music.data.MusicAlbum
import com.zhangwenkang.cinefin.music.data.MusicSong
import com.zhangwenkang.cinefin.utils.DownloadCanonicalIdentity
import java.util.Collections
import java.util.IdentityHashMap

/**
 * W59 专辑下载计划（纯函数，单测覆盖）。
 *
 * 用户口径（2026-10-04）：专辑详情的「下载专辑」**只补齐缺失**（跳过已下载 / 已在队列）， 与详情页整剧下载同一口径并保留**单次上限 100 首**；专辑列表长按多选 →
 * 批量下载整张时同样走这里的合并计划。
 */
object MusicAlbumDownloadRules {

    /** 单次上限（与视频整剧 / 整季下载的 100 集口径一致）。 */
    const val MAX_TRACKS_PER_REQUEST = 100

    data class Plan(
        val targets: List<MusicSong>,
        val skippedDownloaded: Int,
        val skippedActive: Int,
        /** 因单次上限被推迟的曲目数（> 0 时提示用户再点一次）。 */
        val skippedByLimit: Int,
    ) {
        val isEmpty: Boolean
            get() = targets.isEmpty()
    }

    /** 单张专辑：按音轨序号排序，跳过已下载 / 已在队列，取前 [maxTracks] 首。 */
    fun plan(
        album: MusicAlbum,
        downloadedIdentities: Set<DownloadCanonicalIdentity>,
        activeIdentities: Set<DownloadCanonicalIdentity>,
        legacyDownloadedItemIds: Set<java.util.UUID> = emptySet(),
        legacyActiveItemIds: Set<java.util.UUID> = emptySet(),
        maxTracks: Int = MAX_TRACKS_PER_REQUEST,
    ): Plan =
        planAlbums(
            albums = listOf(album),
            downloadedIdentities = downloadedIdentities,
            activeIdentities = activeIdentities,
            legacyDownloadedItemIds = legacyDownloadedItemIds,
            legacyActiveItemIds = legacyActiveItemIds,
            maxTracks = maxTracks,
        )

    /** 多张专辑（专辑列表多选）：按传入专辑顺序 + 专辑内音轨序合并、同一曲目去重、整批上限 [maxTracks]。 */
    fun planAlbums(
        albums: List<MusicAlbum>,
        downloadedIdentities: Set<DownloadCanonicalIdentity>,
        activeIdentities: Set<DownloadCanonicalIdentity>,
        legacyDownloadedItemIds: Set<java.util.UUID> = emptySet(),
        legacyActiveItemIds: Set<java.util.UUID> = emptySet(),
        maxTracks: Int = MAX_TRACKS_PER_REQUEST,
    ): Plan {
        val limit = maxTracks.coerceAtLeast(0)
        var skippedDownloaded = 0
        var skippedActive = 0
        val candidates = mutableListOf<MusicSong>()
        val seenCompleteNamespaces = mutableSetOf<String>()
        val seenUnknownRows = Collections.newSetFromMap(IdentityHashMap<MusicSong, Boolean>())
        val itemIdCounts =
            albums.flatMap(MusicAlbum::songs).groupingBy(MusicSong::itemId).eachCount()
        for (album in albums) {
            val ordered =
                album.songs.sortedWith(
                    compareBy<MusicSong> { it.indexNumber ?: Int.MAX_VALUE }.thenBy { it.name }
                )
            for (song in ordered) {
                if (song.localUri != null) continue
                val identity = song.downloadIdentity()
                val completeKey = identity?.foldKey
                if (
                    if (completeKey != null) {
                        !seenCompleteNamespaces.add(completeKey)
                    } else {
                        !seenUnknownRows.add(song)
                    }
                ) {
                    continue
                }
                val canUseLegacyFallback = identity == null && itemIdCounts[song.itemId] == 1
                when {
                    identity != null &&
                        downloadedIdentities.any { it.matchesProvenance(identity) } ->
                        skippedDownloaded++
                    identity != null && activeIdentities.any { it.matchesProvenance(identity) } ->
                        skippedActive++
                    canUseLegacyFallback && song.itemId in legacyDownloadedItemIds ->
                        skippedDownloaded++
                    canUseLegacyFallback && song.itemId in legacyActiveItemIds -> skippedActive++
                    else -> candidates += song
                }
            }
        }
        val targets = candidates.take(limit)
        return Plan(
            targets = targets,
            skippedDownloaded = skippedDownloaded,
            skippedActive = skippedActive,
            skippedByLimit = candidates.size - targets.size,
        )
    }
}
