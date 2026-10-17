package com.zhangwenkang.cinefin.music.presentation

import com.zhangwenkang.cinefin.music.data.MusicAlbum
import com.zhangwenkang.cinefin.music.data.MusicItemSource
import com.zhangwenkang.cinefin.music.data.MusicSong
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** W79b：#33 封面来源 / #34 自动定位判定的纯函数回归。 */
class SongListSupportTest {

    @Test
    fun `song image wins over album fallback`() {
        assertEquals(
            "https://server/items/1/Images/Primary",
            resolveSongArtworkUri(
                imageUri = "https://server/items/1/Images/Primary",
                albumName = "专辑",
                albumArtwork = mapOf("专辑" to "https://server/albums/a.jpg"),
            ),
        )
    }

    @Test
    fun `missing song image falls back to same album artwork`() {
        assertEquals(
            "https://server/albums/a.jpg",
            resolveSongArtworkUri(
                imageUri = null,
                albumName = "专辑",
                albumArtwork = mapOf("专辑" to "https://server/albums/a.jpg"),
            ),
        )
    }

    @Test
    fun `blank image and unknown album resolve to placeholder`() {
        assertNull(resolveSongArtworkUri("", "神秘专辑", emptyMap()))
    }

    @Test
    fun `album artwork map skips albums without image and keeps first`() {
        val albums =
            listOf(
                album(name = "有图", imageUri = "a.jpg"),
                album(name = "无图", imageUri = null),
                album(name = "有图", imageUri = "b.jpg"),
            )

        assertEquals(mapOf("有图" to "a.jpg"), albumArtworkUris(albums))
    }

    @Test
    fun `locate index accounts for header offset`() {
        val songs = listOf(song("a"), song("b"), song("c"))

        assertEquals(0, songListAutoLocateIndex(songs, songs[0].itemId))
        assertEquals(1, songListAutoLocateIndex(songs, songs[0].itemId, headerCount = 1))
    }

    @Test
    fun `null or filtered out current song has no target`() {
        val songs = listOf(song("a"), song("b"))

        assertEquals(-1, songListAutoLocateIndex(songs, null))
        assertEquals(-1, songListAutoLocateIndex(songs, UUID.randomUUID()))
        assertEquals(-1, songListAutoLocateIndex(emptyList(), songs[0].itemId))
    }

    @Test
    fun `auto locate only runs when enabled and user has not scrolled`() {
        assertTrue(shouldAutoLocateSong(enabled = true, targetIndex = 3, manualScrolled = false))
        assertFalse(shouldAutoLocateSong(enabled = false, targetIndex = 3, manualScrolled = false))
        assertFalse(shouldAutoLocateSong(enabled = true, targetIndex = -1, manualScrolled = false))
        assertFalse(shouldAutoLocateSong(enabled = true, targetIndex = 3, manualScrolled = true))
    }

    @Test
    fun `fully visible song needs no list movement`() {
        assertEquals(
            0,
            songListMinimalRevealDelta(
                viewportStart = 0,
                viewportEnd = 800,
                itemOffset = 120,
                itemSize = 72,
            ),
        )
    }

    @Test
    fun `clipped song reveals only the missing edge`() {
        assertEquals(
            -20,
            songListMinimalRevealDelta(
                viewportStart = 0,
                viewportEnd = 800,
                itemOffset = -20,
                itemSize = 72,
            ),
        )
        assertEquals(
            24,
            songListMinimalRevealDelta(
                viewportStart = 0,
                viewportEnd = 800,
                itemOffset = 752,
                itemSize = 72,
            ),
        )
    }

    @Test
    fun `offscreen reveal aligns to nearest edge instead of centering`() {
        assertEquals(
            72,
            songListRevealScrollOffset(
                targetIndex = 2,
                firstVisibleIndex = 5,
                lastVisibleIndex = 12,
                viewportHeight = 800,
                itemHeight = 72,
            ),
        )
        assertEquals(
            728,
            songListRevealScrollOffset(
                targetIndex = 20,
                firstVisibleIndex = 5,
                lastVisibleIndex = 12,
                viewportHeight = 800,
                itemHeight = 72,
            ),
        )
    }

    @Test
    fun `initial song tab locate centers without a second animation`() {
        assertEquals(
            -364,
            songListCenteredScrollOffset(viewportHeight = 800, itemHeight = 72),
        )
    }

    @Test
    fun `song row index keeps three and four digits on one label`() {
        assertEquals("01", songRowIndexLabel(1))
        assertEquals("99", songRowIndexLabel(99))
        assertEquals("100", songRowIndexLabel(100))
        assertEquals("343", songRowIndexLabel(343))
        assertEquals("1000", songRowIndexLabel(1000))
    }

    private fun song(name: String): MusicSong =
        MusicSong(
            itemId = UUID.randomUUID(),
            name = name,
            albumName = "专辑",
            artist = null,
            indexNumber = null,
            runtimeTicks = 0L,
            imageUri = null,
            source = MusicItemSource.SERVER,
        )

    private fun album(name: String, imageUri: String?): MusicAlbum =
        MusicAlbum(
            key = name,
            name = name,
            artist = null,
            imageUri = imageUri,
            songs = emptyList(),
        )
}
