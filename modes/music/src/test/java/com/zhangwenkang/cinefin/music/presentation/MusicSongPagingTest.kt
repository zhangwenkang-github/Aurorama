package com.zhangwenkang.cinefin.music.presentation

import com.zhangwenkang.cinefin.music.data.MusicSong
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** W3-T14：歌曲 Tab 分页纯函数（页数 / 切片 / 收敛 / 页码指示）。 */
class MusicSongPagingTest {

    private fun songs(count: Int): List<MusicSong> =
        List(count) { index ->
            MusicSong(
                itemId = UUID.randomUUID(),
                name = "song-$index",
                albumName = "专辑",
                artist = "艺人",
                indexNumber = null,
                runtimeTicks = 0L,
                imageUri = null,
            )
        }

    @Test
    fun `page count rounds up and empty library is one page`() {
        assertEquals(1, musicSongPageCount(totalSongs = 0, pageSize = 100))
        assertEquals(1, musicSongPageCount(totalSongs = 100, pageSize = 100))
        assertEquals(2, musicSongPageCount(totalSongs = 101, pageSize = 100))
        assertEquals(17, musicSongPageCount(totalSongs = 830, pageSize = 50))
    }

    @Test
    fun `slice returns page window with tail truncation`() {
        val all = songs(230)

        assertEquals(100, musicSongPageSlice(all, page = 0, pageSize = 100).size)
        assertEquals("song-100", musicSongPageSlice(all, page = 1, pageSize = 100).first().name)
        assertEquals(30, musicSongPageSlice(all, page = 2, pageSize = 100).size)
        assertEquals("song-229", musicSongPageSlice(all, page = 2, pageSize = 100).last().name)
    }

    @Test
    fun `out of range page is clamped before slicing`() {
        val all = songs(120)

        assertEquals("song-100", musicSongPageSlice(all, page = 9, pageSize = 100).first().name)
        assertEquals("song-0", musicSongPageSlice(all, page = -3, pageSize = 100).first().name)
        assertEquals(1, clampMusicSongPage(page = 7, pageCount = 2))
        assertEquals(0, clampMusicSongPage(page = -1, pageCount = 2))
    }

    @Test
    fun `page indicator is one based`() {
        assertEquals("1 / 17", musicSongPageIndicator(page = 0, pageCount = 17))
        assertEquals("2 / 17", musicSongPageIndicator(page = 1, pageCount = 17))
        assertEquals("3 / 3", musicSongPageIndicator(page = 9, pageCount = 3))
        assertEquals("1 / 1", musicSongPageIndicator(page = 0, pageCount = 0))
    }

    @Test
    fun `page size normalization only accepts supported values`() {
        assertEquals(50, normalizeMusicSongPageSize(50))
        assertEquals(200, normalizeMusicSongPageSize(200))
        assertEquals(MUSIC_SONG_DEFAULT_PAGE_SIZE, normalizeMusicSongPageSize(33))
        assertEquals(MUSIC_SONG_DEFAULT_PAGE_SIZE, normalizeMusicSongPageSize(null))
        assertTrue(normalizeMusicSongPageSize(0) in MUSIC_SONG_PAGE_SIZES)
    }
}
