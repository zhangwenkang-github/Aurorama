package com.zhangwenkang.cinefin.music.presentation

import com.zhangwenkang.cinefin.music.data.MusicSong
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Test

/** W3-T14：歌曲 Tab 客户端排序纯函数（键 / 方向 / 空值）。 */
class MusicSongSortRulesTest {

    private fun song(
        name: String,
        album: String = "专辑",
        artist: String? = "艺人",
        indexNumber: Int? = null,
        runtimeTicks: Long = 0L,
        dateAddedMillis: Long? = null,
    ) =
        MusicSong(
            itemId = UUID.randomUUID(),
            name = name,
            albumName = album,
            artist = artist,
            indexNumber = indexNumber,
            runtimeTicks = runtimeTicks,
            imageUri = null,
            dateAddedMillis = dateAddedMillis,
        )

    @Test
    fun `name sort is case insensitive and ascending by default`() {
        val songs = listOf(song("bravo"), song("Alpha"), song("charlie"))

        val sorted = sortMusicSongs(songs, MusicSongSortKey.NAME, ascending = true)

        assertEquals(listOf("Alpha", "bravo", "charlie"), sorted.map { it.name })
    }

    @Test
    fun `name sort descending reverses order`() {
        val songs = listOf(song("bravo"), song("Alpha"), song("charlie"))

        val sorted = sortMusicSongs(songs, MusicSongSortKey.NAME, ascending = false)

        assertEquals(listOf("charlie", "bravo", "Alpha"), sorted.map { it.name })
    }

    @Test
    fun `album sort groups by album then track number ascending`() {
        val songs =
            listOf(
                song("Track 2", album = "B", indexNumber = 2),
                song("Track 1", album = "B", indexNumber = 1),
                song("Track 3", album = "A", indexNumber = 3),
            )

        val sorted = sortMusicSongs(songs, MusicSongSortKey.ALBUM, ascending = true)

        assertEquals(listOf("Track 3", "Track 1", "Track 2"), sorted.map { it.name })
    }

    @Test
    fun `date added descending puts newest first and nulls last`() {
        val withDates =
            listOf(
                song("old", dateAddedMillis = 100L),
                song("unknown"),
                song("new", dateAddedMillis = 300L),
            )

        val descending = sortMusicSongs(withDates, MusicSongSortKey.DATE_ADDED, ascending = false)
        val ascending = sortMusicSongs(withDates, MusicSongSortKey.DATE_ADDED, ascending = true)

        assertEquals(listOf("new", "old", "unknown"), descending.map { it.name })
        assertEquals(listOf("old", "new", "unknown"), ascending.map { it.name })
    }

    @Test
    fun `date played uses play history map and keeps unplayed last`() {
        val played = song("played")
        val unplayed = song("unplayed")
        val playedAt = mapOf(played.itemId to 42L)

        val descending =
            sortMusicSongs(listOf(unplayed, played), MusicSongSortKey.DATE_PLAYED, false, playedAt)
        val ascending =
            sortMusicSongs(listOf(unplayed, played), MusicSongSortKey.DATE_PLAYED, true, playedAt)

        assertEquals(listOf("played", "unplayed"), descending.map { it.name })
        assertEquals(listOf("played", "unplayed"), ascending.map { it.name })
    }

    @Test
    fun `duration sort compares runtime ticks`() {
        val songs =
            listOf(
                song("long", runtimeTicks = 9_000L),
                song("short", runtimeTicks = 1_000L),
                song("medium", runtimeTicks = 5_000L),
            )

        val sorted = sortMusicSongs(songs, MusicSongSortKey.DURATION, ascending = true)

        assertEquals(listOf("short", "medium", "long"), sorted.map { it.name })
    }

    @Test
    fun `unknown persisted sort key falls back to name`() {
        assertEquals(MusicSongSortKey.NAME, MusicSongSortKey.fromPrefValue("LEGACY"))
        assertEquals(MusicSongSortKey.DURATION, MusicSongSortKey.fromPrefValue("DURATION"))
        assertEquals(MusicSongSortKey.NAME, MusicSongSortKey.fromPrefValue(null))
    }
}
