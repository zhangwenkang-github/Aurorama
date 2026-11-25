package com.zhangwenkang.cinefin.music.presentation

import com.zhangwenkang.cinefin.music.data.MusicSong
import java.util.UUID

/** W3-T14：歌曲 Tab 分页每页数量候选（§4.1「每页数量 50 / 100 / 200，默认 100」）。 */
internal val MUSIC_SONG_PAGE_SIZES = listOf(50, 100, 200)

internal const val MUSIC_SONG_DEFAULT_PAGE_SIZE = 100

/** 持久化值归一：只接受候选档位，其它值（含旧版本 / 脏数据）回落默认 100。 */
internal fun normalizeMusicSongPageSize(value: Int?): Int =
    value?.takeIf { it in MUSIC_SONG_PAGE_SIZES } ?: MUSIC_SONG_DEFAULT_PAGE_SIZE

/** 总页数；空列表按 1 页处理（页码指示不会显示 0 / 0）。 */
internal fun musicSongPageCount(totalSongs: Int, pageSize: Int): Int {
    if (pageSize <= 0 || totalSongs <= 0) return 1
    return (totalSongs + pageSize - 1) / pageSize
}

/** 页码收敛到有效区间（0 基）。 */
internal fun clampMusicSongPage(page: Int, pageCount: Int): Int =
    page.coerceIn(0, pageCount.coerceAtLeast(1) - 1)

/** 当前页切片（0 基页码）；越界页码先收敛再切。 */
internal fun musicSongPageSlice(
    songs: List<MusicSong>,
    page: Int,
    pageSize: Int,
): List<MusicSong> {
    if (songs.isEmpty() || pageSize <= 0) return emptyList()
    val safePage = clampMusicSongPage(page, musicSongPageCount(songs.size, pageSize))
    val start = safePage * pageSize
    if (start >= songs.size) return emptyList()
    return songs.subList(start, minOf(start + pageSize, songs.size))
}

/** 页码指示文案（1 基）：如「2 / 17」。 */
internal fun musicSongPageIndicator(page: Int, pageCount: Int): String {
    val total = pageCount.coerceAtLeast(1)
    return "${clampMusicSongPage(page, total) + 1} / $total"
}

/**
 * 播放历史 itemId 序列（监听表用）：最近播放 → 建议 tab「最常播放」段。
 *
 * 只是把已加载曲库投影成「最近播放顺序」，不产生新的网络 / 数据库请求。
 */
internal fun musicSuggestionRecentOrder(
    songs: List<MusicSong>,
    playedAt: Map<UUID, Long>,
): List<UUID> =
    songs
        .mapNotNull { song ->
            playedAt[song.itemId]?.let { playedAtValue -> song.itemId to playedAtValue }
        }
        .sortedByDescending { (_, playedAtValue) -> playedAtValue }
        .map { (itemId, _) -> itemId }
