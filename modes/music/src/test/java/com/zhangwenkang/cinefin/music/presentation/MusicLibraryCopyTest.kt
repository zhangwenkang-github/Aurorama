package com.zhangwenkang.cinefin.music.presentation

import com.zhangwenkang.cinefin.music.data.MusicItemSourceFilter
import org.junit.Assert.assertEquals
import org.junit.Test

/** W39：音乐库计数副题与空态文案（纯函数）。 */
class MusicLibraryCopyTest {

    @Test
    fun subtitleAddsSourcePrefixWhenFiltered() {
        assertEquals(
            "共 3 张本地专辑",
            musicLibrarySubtitle(MusicTab.ALBUMS, MusicItemSourceFilter.LOCAL, 3, offline = false),
        )
        assertEquals(
            "共 5 张服务器专辑",
            musicLibrarySubtitle(MusicTab.ALBUMS, MusicItemSourceFilter.SERVER, 5, offline = false),
        )
        assertEquals(
            "共 8 张专辑",
            musicLibrarySubtitle(MusicTab.ALBUMS, MusicItemSourceFilter.ALL, 8, offline = false),
        )
        assertEquals(
            "共 2 位本地艺术家",
            musicLibrarySubtitle(MusicTab.ARTISTS, MusicItemSourceFilter.LOCAL, 2, offline = false),
        )
        assertEquals(
            "共 7 首服务器歌曲",
            musicLibrarySubtitle(MusicTab.SONGS, MusicItemSourceFilter.SERVER, 7, offline = false),
        )
        assertEquals(
            "共 4 个歌单",
            musicLibrarySubtitle(
                MusicTab.PLAYLISTS,
                MusicItemSourceFilter.LOCAL,
                4,
                offline = false,
            ),
        )
    }

    @Test
    fun subtitleHasNoSourcePrefixInOfflineMode() {
        assertEquals(
            "共 2 张专辑",
            musicLibrarySubtitle(MusicTab.ALBUMS, MusicItemSourceFilter.SERVER, 2, offline = true),
        )
    }

    @Test
    fun emptyCopyBranchesBySource() {
        val local = musicEmptyCopy(MusicTab.ALBUMS, MusicItemSourceFilter.LOCAL, offline = false)
        assertEquals("本地还没有音乐", local.title)
        assertEquals("在『媒体库 → 本地媒体库』添加包含音乐的文件夹后回来", local.message)

        val server = musicEmptyCopy(MusicTab.ALBUMS, MusicItemSourceFilter.SERVER, offline = false)
        assertEquals("服务器音乐库里还没有专辑", server.title)
        assertEquals("下拉可刷新", server.message)

        val all = musicEmptyCopy(MusicTab.ALBUMS, MusicItemSourceFilter.ALL, offline = false)
        assertEquals("服务器和本地都还没有音乐", all.title)
        assertEquals("在服务器或本地媒体库添加音乐后，下拉刷新", all.message)
    }

    @Test
    fun emptyCopyArtistAndSongTabsKeepSourceTitle() {
        assertEquals(
            "服务器音乐库里还没有艺术家",
            musicEmptyCopy(MusicTab.ARTISTS, MusicItemSourceFilter.SERVER, offline = false).title,
        )
        assertEquals(
            "服务器音乐库里还没有歌曲",
            musicEmptyCopy(MusicTab.SONGS, MusicItemSourceFilter.SERVER, offline = false).title,
        )
        assertEquals(
            "本地还没有音乐",
            musicEmptyCopy(MusicTab.SONGS, MusicItemSourceFilter.LOCAL, offline = false).title,
        )
    }

    @Test
    fun playlistAndOfflineEmptyCopyStayTruthful() {
        assertEquals(
            "服务器上没有歌单",
            musicEmptyCopy(MusicTab.PLAYLISTS, MusicItemSourceFilter.ALL, offline = false).title,
        )
        assertEquals(
            "下拉可刷新",
            musicEmptyCopy(MusicTab.PLAYLISTS, MusicItemSourceFilter.ALL, offline = false).message,
        )
        assertEquals(
            "离线模式还没有可播放的音乐",
            musicEmptyCopy(MusicTab.ALBUMS, MusicItemSourceFilter.LOCAL, offline = true).title,
        )
        assertEquals(
            "离线模式没有歌单",
            musicEmptyCopy(MusicTab.PLAYLISTS, MusicItemSourceFilter.ALL, offline = true).title,
        )
    }
}
