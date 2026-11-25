package com.zhangwenkang.cinefin.music.presentation

import com.zhangwenkang.cinefin.music.data.MusicAlbum
import com.zhangwenkang.cinefin.music.data.MusicArtist
import com.zhangwenkang.cinefin.music.data.MusicSong
import com.zhangwenkang.cinefin.player.core.domain.models.QueueSource
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** W3-T20：刷新后的曲目替换进详情层（专辑 / 艺术家 / 歌单 / 收藏 / 最近播放）。 */
class MusicMetadataUpdateTest {

    private val itemId = UUID.randomUUID()
    private val otherId = UUID.randomUUID()

    private fun song(id: UUID, imageUri: String? = null, name: String = "旧") =
        MusicSong(
            itemId = id,
            name = name,
            albumName = "专辑",
            artist = "艺人",
            indexNumber = null,
            runtimeTicks = 0L,
            imageUri = imageUri,
        )

    @Test
    fun `album detail replaces song and falls back to refreshed artwork`() {
        val album =
            MusicAlbum(
                key = "album",
                name = "专辑",
                artist = "艺人",
                imageUri = null,
                songs = listOf(song(itemId), song(otherId)),
            )
        val fresh = song(itemId, imageUri = "https://server/cover.jpg", name = "新")

        val updated = MusicDetail.Album(album).withUpdatedSong(fresh)

        assertEquals(listOf(fresh, album.songs[1]), (updated as MusicDetail.Album).album.songs)
        assertEquals("https://server/cover.jpg", updated.album.imageUri)
        assertSame(QueueSource.ALBUM, updated.source)
    }

    @Test
    fun `artist detail replaces only the matching song`() {
        val artist =
            MusicArtist(
                key = "artist",
                name = "艺人",
                imageUri = null,
                songs = listOf(song(itemId), song(otherId)),
            )
        val fresh = song(itemId, name = "新")

        val updated = MusicDetail.Artist(artist).withUpdatedSong(fresh)

        assertEquals(listOf(fresh, artist.songs[1]), (updated as MusicDetail.Artist).artist.songs)
    }

    @Test
    fun `playlist detail replaces song in place`() {
        val detail =
            MusicDetail.Playlist(
                playlist =
                    com.zhangwenkang.cinefin.music.data.MusicPlaylist(
                        id = UUID.randomUUID(),
                        name = "歌单",
                        imageUri = null,
                        songCount = 2,
                    ),
                songs = listOf(song(itemId), song(otherId)),
            )
        val fresh = song(itemId, name = "新")

        val updated = detail.withUpdatedSong(fresh)

        assertEquals(listOf(fresh, detail.songs[1]), (updated as MusicDetail.Playlist).songs)
    }

    @Test
    fun `favorites and recent replace song in place`() {
        val fresh = song(itemId, name = "新")

        val favorites =
            MusicDetail.Favorites(songs = listOf(song(itemId), song(otherId)))
                .withUpdatedSong(fresh)
        val recent =
            MusicDetail.Recent(songs = listOf(song(itemId), song(otherId))).withUpdatedSong(fresh)

        assertEquals(fresh, (favorites as MusicDetail.Favorites).songs.first())
        assertEquals(fresh, (recent as MusicDetail.Recent).songs.first())
    }
}
