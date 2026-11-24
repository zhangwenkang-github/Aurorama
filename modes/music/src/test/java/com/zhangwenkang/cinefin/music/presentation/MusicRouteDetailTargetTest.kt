package com.zhangwenkang.cinefin.music.presentation

import com.zhangwenkang.cinefin.music.data.MusicSong
import com.zhangwenkang.cinefin.music.data.groupAlbums
import com.zhangwenkang.cinefin.music.data.groupArtists
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** W1-T08：搜索结果按条目类型进音乐页对应详情层的纯函数单测。 */
class MusicRouteDetailTargetTest {

    @Test
    fun `专辑与艺术家按标题定位详情层`() {
        val id = UUID.randomUUID()

        assertEquals(
            MusicRouteDetailTarget.Album("多雨的夜"),
            musicRouteDetailTarget("MUSIC_ALBUM", "多雨的夜", id),
        )
        assertEquals(
            MusicRouteDetailTarget.Artist("新乐尘符"),
            musicRouteDetailTarget("music_artist", "新乐尘符", id),
        )
    }

    @Test
    fun `歌单按条目 id 定位详情层`() {
        val id = UUID.randomUUID()

        assertEquals(
            MusicRouteDetailTarget.Playlist(id),
            musicRouteDetailTarget("PLAYLIST", "我的歌单", id),
        )
    }

    @Test
    fun `歌曲与本地下载等无容器类型回落按曲目起播`() {
        val id = UUID.randomUUID()

        assertNull(musicRouteDetailTarget("AUDIO", "9420", id))
        assertNull(musicRouteDetailTarget(null, "本地文件", id))
        assertNull(musicRouteDetailTarget("MOVIE", "影片", id))
    }

    @Test
    fun `专辑艺术家缺标题时不猜详情层`() {
        val id = UUID.randomUUID()

        assertNull(musicRouteDetailTarget("MUSIC_ALBUM", null, id))
        assertNull(musicRouteDetailTarget("MUSIC_ALBUM", "  ", id))
        assertNull(musicRouteDetailTarget("MUSIC_ARTIST", null, id))
    }

    @Test
    fun `专辑定位优先聚合结果再按曲目现聚合`() {
        val song = song(albumName = "多雨的夜", artist = "新乐尘符")
        val albums = groupAlbums(listOf(song))

        assertEquals(
            "多雨的夜",
            findMusicAlbum(albums, emptyList(), "多雨的夜")?.name,
        )
        assertEquals(
            "多雨的夜",
            findMusicAlbum(emptyList(), listOf(song), "多雨的夜")?.name,
        )
        assertNull(findMusicAlbum(emptyList(), listOf(song), "不存在的专辑"))
    }

    @Test
    fun `艺术家定位优先聚合结果再按曲目现聚合`() {
        val song = song(albumName = "多雨的夜", artist = "新乐尘符")
        val artists = groupArtists(listOf(song))

        assertEquals("新乐尘符", findMusicArtist(artists, emptyList(), "新乐尘符")?.name)
        assertEquals("新乐尘符", findMusicArtist(emptyList(), listOf(song), "新乐尘符")?.name)
        assertNull(findMusicArtist(emptyList(), listOf(song), "不存在的艺术家"))
    }

    private fun song(albumName: String, artist: String) =
        MusicSong(
            itemId = UUID.randomUUID(),
            name = "9420",
            albumName = albumName,
            artist = artist,
            indexNumber = 1,
            runtimeTicks = 0L,
            imageUri = null,
        )
}
