package com.zhangwenkang.cinefin.music.presentation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** W3-T15：歌曲行动作 / 上下文菜单动作 / 队列插入位置（纯函数）。 */
class MusicSongActionsRulesTest {

    private fun menu(
        showAddToPlaylist: Boolean = true,
        showDownload: Boolean = true,
        showViewAlbum: Boolean = true,
        showViewArtist: Boolean = true,
        canMoveUp: Boolean = false,
        canMoveDown: Boolean = false,
        showAudioInfo: Boolean = true,
    ): List<MusicSongAction> =
        musicSongMenuActions(
            showAddToPlaylist = showAddToPlaylist,
            showDownload = showDownload,
            showViewAlbum = showViewAlbum,
            showViewArtist = showViewArtist,
            canMoveUp = canMoveUp,
            canMoveDown = canMoveDown,
            showAudioInfo = showAudioInfo,
        )

    @Test
    fun `compact row shows favorite and overflow only`() {
        assertEquals(
            listOf(MusicSongRowAction.FAVORITE, MusicSongRowAction.MORE),
            musicSongRowActions(wideLayout = false, canAddToPlaylist = true),
        )
    }

    @Test
    fun `wide row promotes add to playlist`() {
        assertEquals(
            listOf(
                MusicSongRowAction.ADD_TO_PLAYLIST,
                MusicSongRowAction.FAVORITE,
                MusicSongRowAction.MORE,
            ),
            musicSongRowActions(wideLayout = true, canAddToPlaylist = true),
        )
    }

    @Test
    fun `wide row without playlist callback keeps compact actions`() {
        assertEquals(
            listOf(MusicSongRowAction.FAVORITE, MusicSongRowAction.MORE),
            musicSongRowActions(wideLayout = true, canAddToPlaylist = false),
        )
    }

    @Test
    fun `width breakpoint matches project compact threshold`() {
        assertFalse(musicSongRowWideLayout(599))
        assertTrue(musicSongRowWideLayout(600))
        assertTrue(musicSongRowWideLayout(840))
    }

    @Test
    fun `menu keeps design order for full context`() {
        assertEquals(
            listOf(
                MusicSongAction.PLAY,
                MusicSongAction.PLAY_NEXT,
                MusicSongAction.ENQUEUE,
                MusicSongAction.ADD_TO_PLAYLIST,
                MusicSongAction.FAVORITE,
                MusicSongAction.DOWNLOAD,
                MusicSongAction.VIEW_ALBUM,
                MusicSongAction.VIEW_ARTIST,
                MusicSongAction.REFRESH_METADATA,
                MusicSongAction.AUDIO_INFO,
            ),
            menu(),
        )
    }

    @Test
    fun `optional entries disappear when not applicable`() {
        val actions =
            menu(
                showAddToPlaylist = false,
                showDownload = false,
                showViewAlbum = false,
                showViewArtist = false,
                showAudioInfo = false,
            )

        assertEquals(
            listOf(
                MusicSongAction.PLAY,
                MusicSongAction.PLAY_NEXT,
                MusicSongAction.ENQUEUE,
                MusicSongAction.FAVORITE,
                MusicSongAction.REFRESH_METADATA,
            ),
            actions,
        )
    }

    @Test
    fun `playlist context appends move entries at the end`() {
        val actions = menu(canMoveUp = true, canMoveDown = false)

        assertEquals(MusicSongAction.MOVE_UP, actions.last())
        assertFalse(actions.contains(MusicSongAction.MOVE_DOWN))

        val both = menu(canMoveUp = true, canMoveDown = true)
        assertEquals(
            listOf(MusicSongAction.MOVE_UP, MusicSongAction.MOVE_DOWN),
            both.takeLast(2),
        )
    }

    @Test
    fun `favorite label follows current state`() {
        assertEquals(
            "收藏",
            musicSongActionLabel(MusicSongAction.FAVORITE, isFavorite = false),
        )
        assertEquals(
            "取消收藏",
            musicSongActionLabel(MusicSongAction.FAVORITE, isFavorite = true),
        )
    }

    @Test
    fun `queue labels match design copy`() {
        assertEquals("播放", musicSongActionLabel(MusicSongAction.PLAY, isFavorite = false))
        assertEquals(
            "下一首播放",
            musicSongActionLabel(MusicSongAction.PLAY_NEXT, isFavorite = false),
        )
        assertEquals(
            "加入队列",
            musicSongActionLabel(MusicSongAction.ENQUEUE, isFavorite = false),
        )
    }

    @Test
    fun `download label delegates to existing copy and hides when empty`() {
        assertEquals(
            "下载 · 离线",
            musicSongActionLabel(
                MusicSongAction.DOWNLOAD,
                isFavorite = false,
                downloadLabel = "下载 · 离线",
            ),
        )
        assertNull(
            musicSongActionLabel(
                MusicSongAction.DOWNLOAD,
                isFavorite = false,
                downloadLabel = null,
            )
        )
    }

    @Test
    fun `queue insertion maps next to head and enqueue to tail`() {
        assertEquals(
            MusicQueueInsertion.UP_NEXT_HEAD,
            musicSongQueueInsertion(MusicSongAction.PLAY_NEXT),
        )
        assertEquals(
            MusicQueueInsertion.UP_NEXT_TAIL,
            musicSongQueueInsertion(MusicSongAction.ENQUEUE),
        )
        assertNull(musicSongQueueInsertion(MusicSongAction.PLAY))
        assertNull(musicSongQueueInsertion(MusicSongAction.FAVORITE))
    }
}
