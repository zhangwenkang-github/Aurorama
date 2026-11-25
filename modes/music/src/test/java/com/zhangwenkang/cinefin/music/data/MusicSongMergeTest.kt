package com.zhangwenkang.cinefin.music.data

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** W3-T20：刷新快照合并语义（只覆盖服务端给出的字段，身份 / 本地字段保持不变）。 */
class MusicSongMergeTest {

    private fun song(
        name: String = "旧名字",
        album: String = "旧专辑",
        artist: String? = "旧艺人",
        imageUri: String? = null,
        isFavorite: Boolean = false,
        runtimeTicks: Long = 100L,
        localUri: String? = null,
        dateAddedMillis: Long? = 10L,
    ) =
        MusicSong(
            itemId = ITEM_ID,
            name = name,
            albumName = album,
            artist = artist,
            indexNumber = 1,
            runtimeTicks = runtimeTicks,
            imageUri = imageUri,
            isFavorite = isFavorite,
            localUri = localUri,
            source = if (localUri == null) MusicItemSource.SERVER else MusicItemSource.LOCAL,
            dateAddedMillis = dateAddedMillis,
        )

    @Test
    fun `fresh snapshot fills missing image and updates server fields`() {
        val current = song(localUri = "content://music/1")
        val fresh =
            song(
                name = "新名字",
                album = "新专辑",
                artist = "新艺人",
                imageUri = "https://server/cover.jpg",
                dateAddedMillis = 99L,
            )

        val merged = current.mergedWithFresh(fresh)

        assertEquals("新名字", merged.name)
        assertEquals("新专辑", merged.albumName)
        assertEquals("新艺人", merged.artist)
        assertEquals("https://server/cover.jpg", merged.imageUri)
        assertEquals(99L, merged.dateAddedMillis)
        // 本地可播地址不能因为刷新被冲掉
        assertEquals("content://music/1", merged.localUri)
    }

    @Test
    fun `blank fresh fields keep existing values`() {
        val current = song(imageUri = "https://server/old.jpg")
        val fresh =
            song(
                name = " ",
                album = " ",
                artist = null,
                imageUri = null,
                runtimeTicks = 0L,
                dateAddedMillis = null,
            )

        val merged = current.mergedWithFresh(fresh)

        assertEquals("旧名字", merged.name)
        assertEquals("旧专辑", merged.albumName)
        assertEquals("旧艺人", merged.artist)
        assertEquals("https://server/old.jpg", merged.imageUri)
        assertEquals(100L, merged.runtimeTicks)
        assertEquals(10L, merged.dateAddedMillis)
    }

    @Test
    fun `favorite only upgrades and never silently clears`() {
        val favoriteLocal = song(isFavorite = true)

        assertEquals(
            true,
            favoriteLocal.mergedWithFresh(song(isFavorite = false)).isFavorite,
        )
        assertEquals(
            true,
            song(isFavorite = false).mergedWithFresh(song(isFavorite = true)).isFavorite,
        )
    }

    @Test
    fun `fresh without image keeps null image`() {
        assertNull(song().mergedWithFresh(song()).imageUri)
    }

    private companion object {
        val ITEM_ID: UUID = UUID.fromString("33333333-3333-4333-8333-333333333333")
    }
}
