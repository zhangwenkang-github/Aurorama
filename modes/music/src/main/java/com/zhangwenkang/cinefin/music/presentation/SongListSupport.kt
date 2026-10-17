package com.zhangwenkang.cinefin.music.presentation

import com.zhangwenkang.cinefin.music.data.MusicAlbum
import com.zhangwenkang.cinefin.music.data.MusicSong
import java.util.UUID

/**
 * 歌曲行封面来源（W79b · #33）。
 *
 * 曲目自身封面优先：服务器曲目是音频条目的 Primary 图，本地曲目已在索引阶段落好「内嵌标签 → 同目录封面」链。 个别服务器曲目没有 Primary
 * 图时，用同专辑已加载的封面兜底；这张表由曲库快照聚合，不产生额外网络请求。
 */
internal fun resolveSongArtworkUri(
    imageUri: String?,
    albumName: String,
    albumArtwork: Map<String, String>,
): String? =
    imageUri?.takeIf { it.isNotBlank() } ?: albumArtwork[albumName]?.takeIf { it.isNotBlank() }

/** 专辑名 → 封面 URI（只取有图的专辑；同名专辑保留首个有效封面）。 */
internal fun albumArtworkUris(albums: List<MusicAlbum>): Map<String, String> = buildMap {
    albums.forEach { album ->
        val uri = album.imageUri?.takeIf { it.isNotBlank() } ?: return@forEach
        putIfAbsent(album.name, uri)
    }
}

/**
 * 当前播放曲在歌曲列表中的下标（含列表头占位）。
 *
 * 队列为空、当前曲不在当前筛选列表时返回 `-1` —— 调用方静默跳过定位。
 */
internal fun songListAutoLocateIndex(
    songs: List<MusicSong>,
    currentItemId: UUID?,
    headerCount: Int = 0,
): Int {
    if (currentItemId == null) return -1
    val index = songs.indexOfFirst { it.itemId == currentItemId }
    return if (index >= 0) index + headerCount else -1
}

/** #34：只有「歌曲 Tab 可见 + 找得到当前曲 + 用户尚未手动滚动」时才允许自动定位。 */
internal fun shouldAutoLocateSong(
    enabled: Boolean,
    targetIndex: Int,
    manualScrolled: Boolean,
): Boolean = enabled && targetIndex >= 0 && !manualScrolled
