package com.zhangwenkang.cinefin.music.presentation

import com.zhangwenkang.cinefin.music.data.MusicItemSource
import com.zhangwenkang.cinefin.music.data.MusicPlaylist
import com.zhangwenkang.cinefin.music.data.MusicPlaylistKind
import com.zhangwenkang.cinefin.music.data.MusicSong
import java.util.UUID

/** W8-T4：服务器歌单名去首尾空白；全空白视为无效（不写服务器）。 */
internal fun normalizedServerPlaylistName(raw: String): String? =
    raw.trim().takeIf(String::isNotEmpty)

/** W8-T4：可写入服务器歌单的曲目 id（本地媒体库曲目没有服务器身份，不参与服务器写）。 */
internal fun serverPlaylistItemIds(songs: List<MusicSong>): List<UUID> =
    songs.filter { it.source == MusicItemSource.SERVER }.map(MusicSong::itemId)

/**
 * W8-T4：「加入歌单」里服务器歌单行是否可点。
 *
 * 服务器歌单只能收服务器曲目；混入本地媒体库曲目时整行禁用（不做部分写入）。
 */
internal fun canAddToServerPlaylist(playlist: MusicPlaylist, songs: List<MusicSong>): Boolean =
    playlist.kind == MusicPlaylistKind.SERVER &&
        songs.isNotEmpty() &&
        songs.all { it.source == MusicItemSource.SERVER }

/** W8-T4：「加入歌单」对话框里的行文案（服务器歌单纯文本后缀，不新增组件）。 */
internal fun playlistAddRowLabel(playlist: MusicPlaylist): String =
    if (playlist.kind == MusicPlaylistKind.SERVER) {
        "${playlist.name} · 服务器"
    } else {
        playlist.name
    }
