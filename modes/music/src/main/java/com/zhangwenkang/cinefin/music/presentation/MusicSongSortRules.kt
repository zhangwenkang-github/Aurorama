package com.zhangwenkang.cinefin.music.presentation

import com.zhangwenkang.cinefin.music.data.MusicSong
import java.util.UUID

/**
 * W3-T14：歌曲 Tab 客户端排序键（§4.1 工具行「排序」）。
 *
 * 只对「当前已加载」列表排序，不改服务端请求参数；排序键 / 方向落偏好（`pref_music_song_sort_*`）。
 */
enum class MusicSongSortKey(val prefValue: String, val label: String) {
    NAME("NAME", "名称"),
    ALBUM("ALBUM", "专辑"),
    ARTIST("ARTIST", "艺术家"),
    DATE_ADDED("DATE_ADDED", "添加日期"),
    DATE_PLAYED("DATE_PLAYED", "播放日期"),
    DURATION("DURATION", "时长");

    companion object {
        fun fromPrefValue(raw: String?): MusicSongSortKey =
            entries.firstOrNull { it.prefValue == raw } ?: NAME
    }
}

/** 排序方向文案（菜单项）。 */
internal fun musicSongSortDirectionLabel(ascending: Boolean): String = if (ascending) "升序" else "降序"

/**
 * 客户端排序。
 *
 * - 排序键之外恒定用「名称 → 专辑」做稳定次序，保证同一键值下顺序可预期；
 * - `添加日期` / `播放日期` 缺数据（null）的曲目不分升降序，恒定排末尾；
 * - `专辑` 键在专辑名相同后按碟内曲序（缺曲序排末尾）。
 */
internal fun sortMusicSongs(
    songs: List<MusicSong>,
    key: MusicSongSortKey,
    ascending: Boolean,
    playedAt: Map<UUID, Long> = emptyMap(),
): List<MusicSong> {
    if (songs.size <= 1) return songs
    val byName = Comparator<MusicSong> { left, right -> compareSongText(left.name, right.name) }
    val byAlbum =
        Comparator<MusicSong> { left, right -> compareSongText(left.albumName, right.albumName) }
    val primary =
        when (key) {
            MusicSongSortKey.NAME ->
                Comparator<MusicSong> { left, right ->
                    directed(compareSongText(left.name, right.name), ascending)
                }
            MusicSongSortKey.ALBUM ->
                Comparator<MusicSong> { left, right ->
                    val album =
                        directed(compareSongText(left.albumName, right.albumName), ascending)
                    if (album != 0) album
                    else
                        compareNullableLong(
                            left.indexNumber?.toLong(),
                            right.indexNumber?.toLong(),
                            true,
                        )
                }
            MusicSongSortKey.ARTIST ->
                Comparator<MusicSong> { left, right ->
                    directed(
                        compareSongText(left.artist.orEmpty(), right.artist.orEmpty()),
                        ascending,
                    )
                }
            MusicSongSortKey.DATE_ADDED ->
                Comparator<MusicSong> { left, right ->
                    compareNullableLong(left.dateAddedMillis, right.dateAddedMillis, ascending)
                }
            MusicSongSortKey.DATE_PLAYED ->
                Comparator<MusicSong> { left, right ->
                    compareNullableLong(playedAt[left.itemId], playedAt[right.itemId], ascending)
                }
            MusicSongSortKey.DURATION ->
                Comparator<MusicSong> { left, right ->
                    directed(left.runtimeTicks.compareTo(right.runtimeTicks), ascending)
                }
        }
    return songs.sortedWith(primary.then(byName).then(byAlbum))
}

private fun directed(comparison: Int, ascending: Boolean): Int =
    if (ascending) comparison else -comparison

private fun compareSongText(left: String, right: String): Int =
    left.compareTo(right, ignoreCase = true)

/** 空值恒定排末尾：两个都为空视为相等，只有一个为空时该条排后。 */
private fun compareNullableLong(left: Long?, right: Long?, ascending: Boolean): Int =
    when {
        left == null && right == null -> 0
        left == null -> 1
        right == null -> -1
        else -> directed(left.compareTo(right), ascending)
    }
