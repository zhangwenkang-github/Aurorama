package com.zhangwenkang.cinefin.music.presentation

import com.zhangwenkang.cinefin.music.data.MusicSong
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** W3-T14：「建议」Tab 三段（最近添加 / 最常播放 / 收藏）组装的纯函数。 */
class MusicSuggestionRulesTest {

    private var nextTick = 0L

    private fun song(
        name: String,
        dateAddedMillis: Long? = null,
        isFavorite: Boolean = false,
    ) =
        MusicSong(
            itemId = UUID.randomUUID(),
            name = name,
            albumName = "专辑",
            artist = "艺人",
            indexNumber = null,
            runtimeTicks = 0L,
            imageUri = null,
            isFavorite = isFavorite,
            dateAddedMillis = dateAddedMillis,
        )

    @Test
    fun `empty section is hidden`() {
        val sections = musicSuggestionSections(listOf(song("a")), playedAt = emptyMap())

        assertTrue(sections.isEmpty())
    }

    @Test
    fun `sections keep design order and only non empty ones`() {
        val first = song("first", dateAddedMillis = 100L)
        val second = song("second", dateAddedMillis = 200L, isFavorite = true)
        val playedAt = mapOf(first.itemId to 1_000L)

        val sections = musicSuggestionSections(listOf(first, second), playedAt)

        assertEquals(listOf("最近添加", "最常播放", "收藏"), sections.map { it.title })
        assertEquals(listOf("second", "first"), sections[0].songs.map { it.name })
        assertEquals(listOf("first"), sections[1].songs.map { it.name })
        assertEquals(listOf("second"), sections[2].songs.map { it.name })
    }

    @Test
    fun `most played follows play history order and ignores unknown ids`() {
        val older = song("older")
        val newer = song("newer")
        val playedAt = mapOf(older.itemId to 10L, newer.itemId to 20L, UUID.randomUUID() to 30L)

        val sections = musicSuggestionSections(listOf(older, newer), playedAt)
        val mostPlayed = sections.first { it.title == "最常播放" }

        assertEquals(listOf("newer", "older"), mostPlayed.songs.map { it.name })
    }

    @Test
    fun `each section is capped at the limit`() {
        val songs = List(15) { index -> song("song-$index", dateAddedMillis = (nextTick++)) }
        val playedAt = songs.associate { it.itemId to nextTick++ }

        val sections = musicSuggestionSections(songs, playedAt)

        assertTrue(sections.all { it.songs.size <= MUSIC_SUGGESTION_SECTION_LIMIT })
        assertEquals(MUSIC_SUGGESTION_SECTION_LIMIT, sections[0].songs.size)
    }

    @Test
    fun `empty library produces no sections`() {
        assertTrue(musicSuggestionSections(emptyList(), emptyMap()).isEmpty())
    }
}
