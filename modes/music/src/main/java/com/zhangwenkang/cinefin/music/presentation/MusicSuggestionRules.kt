package com.zhangwenkang.cinefin.music.presentation

import com.zhangwenkang.cinefin.music.data.MusicSong
import java.util.UUID

/** W3-T14「建议」Tab 的一个纵向分段（标题 + 最多 10 条曲目）。 */
internal data class MusicSuggestionSection(
    val title: String,
    val songs: List<MusicSong>,
)

internal const val MUSIC_SUGGESTION_SECTION_LIMIT = 10

/**
 * 「建议」Tab 三段（负责人 2026-11-25 裁定）：
 *
 * - 最近添加：曲库内带入库时间（服务器 `DateCreated` / 本地文件修改时间）的曲目按时间倒序；
 * - 最常播放：现有播放历史（[MusicRecentStore] 的最近播放序）投影到当前曲库，取前 N 条；
 * - 收藏：曲库内 `isFavorite` 曲目。
 *
 * 空段整体隐藏；不造新请求通道，所有数据都来自已加载曲库 + 本地播放历史。
 */
internal fun musicSuggestionSections(
    songs: List<MusicSong>,
    playedAt: Map<UUID, Long>,
    limit: Int = MUSIC_SUGGESTION_SECTION_LIMIT,
): List<MusicSuggestionSection> {
    if (songs.isEmpty()) return emptyList()
    val recentlyAdded =
        songs
            .filter { it.dateAddedMillis != null }
            .sortedByDescending { it.dateAddedMillis }
            .take(limit)
    val byId = songs.associateBy { it.itemId }
    val mostPlayed = musicSuggestionRecentOrder(songs, playedAt).mapNotNull(byId::get).take(limit)
    val favorites = songs.filter { it.isFavorite }.take(limit)
    return listOf(
            MusicSuggestionSection("最近添加", recentlyAdded),
            MusicSuggestionSection("最常播放", mostPlayed),
            MusicSuggestionSection("收藏", favorites),
        )
        .filter { it.songs.isNotEmpty() }
}
