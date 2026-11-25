package com.zhangwenkang.cinefin.music.presentation

import com.zhangwenkang.cinefin.music.data.MusicPlaylistKind
import org.junit.Assert.assertEquals
import org.junit.Test

/** W3-T19：歌单行 ⋮ 菜单可见性（本地与服务器歌单都提供改名 / 删除）。 */
class PlaylistRowMenuRulesTest {

    @Test
    fun `local playlist exposes rename and delete`() {
        assertEquals(
            listOf(PlaylistRowMenuAction.RENAME, PlaylistRowMenuAction.DELETE),
            playlistRowMenuActions(MusicPlaylistKind.LOCAL),
        )
    }

    @Test
    fun `server playlist exposes the same actions as local`() {
        assertEquals(
            playlistRowMenuActions(MusicPlaylistKind.LOCAL),
            playlistRowMenuActions(MusicPlaylistKind.SERVER),
        )
    }
}
