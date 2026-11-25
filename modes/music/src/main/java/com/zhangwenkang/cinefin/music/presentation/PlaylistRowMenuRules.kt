package com.zhangwenkang.cinefin.music.presentation

import com.zhangwenkang.cinefin.music.data.MusicPlaylistKind

/** W3-T19：歌单列表行的 ⋮ 菜单项（本地 / 服务器歌单共用同一份）。 */
internal enum class PlaylistRowMenuAction {
    RENAME,
    DELETE,
}

/**
 * W3-T19：歌单行菜单可见性（纯函数）。
 *
 * 本地与服务器歌单都支持重命名 / 删除；后续新增动作时在这里统一决定，避免 UI 各处各自判断 kind。
 */
internal fun playlistRowMenuActions(kind: MusicPlaylistKind): List<PlaylistRowMenuAction> =
    when (kind) {
        MusicPlaylistKind.LOCAL,
        MusicPlaylistKind.SERVER ->
            listOf(PlaylistRowMenuAction.RENAME, PlaylistRowMenuAction.DELETE)
    }
