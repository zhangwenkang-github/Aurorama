package com.zhangwenkang.cinefin.music.presentation

import com.zhangwenkang.cinefin.music.data.MusicItemSource
import com.zhangwenkang.cinefin.music.data.MusicSong
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** W3-T20：无封面补取候选与批次（纯函数）。 */
class MusicArtworkBackfillRulesTest {

    private fun song(
        source: MusicItemSource = MusicItemSource.SERVER,
        imageUri: String? = null,
        placeholder: Boolean = false,
    ) =
        MusicSong(
            itemId = UUID.randomUUID(),
            name = "曲目",
            albumName = "专辑",
            artist = "艺人",
            indexNumber = null,
            runtimeTicks = 0L,
            imageUri = imageUri,
            source = source,
            isPlaylistPlaceholder = placeholder,
        )

    @Test
    fun `only server songs without artwork are candidates`() {
        val missing = song()
        val hasArt = song(imageUri = "https://server/cover.jpg")
        val local = song(source = MusicItemSource.LOCAL)
        val placeholder = song(placeholder = true)

        val ids =
            musicArtworkBackfillCandidates(listOf(missing, hasArt, local, placeholder), emptySet())

        assertEquals(listOf(missing.itemId), ids)
    }

    @Test
    fun `already tried songs are skipped and deduplicated`() {
        val first = song()
        val second = song(imageUri = "")

        val ids =
            musicArtworkBackfillCandidates(
                songs = listOf(first, first, second),
                triedItemIds = setOf(first.itemId),
            )

        assertEquals(listOf(second.itemId), ids)
    }

    @Test
    fun `batch limit caps candidates per load cycle`() {
        val songs = List(30) { song() }

        val ids = musicArtworkBackfillCandidates(songs, emptySet(), limit = 20)

        assertEquals(20, ids.size)
        assertEquals(20, ids.distinct().size)
    }

    @Test
    fun `chunks split by concurrency`() {
        val ids = List(10) { UUID.randomUUID() }

        val chunks = musicArtworkBackfillChunks(ids, concurrency = 4)

        assertEquals(listOf(4, 4, 2), chunks.map { it.size })
        assertEquals(ids, chunks.flatten())
    }

    @Test
    fun `blank artwork counts as missing`() {
        val blank = song(imageUri = " ")

        assertEquals(
            listOf(blank.itemId),
            musicArtworkBackfillCandidates(listOf(blank), emptySet()),
        )
    }

    @Test
    fun `empty library produces no candidates`() {
        assertTrue(musicArtworkBackfillCandidates(emptyList(), emptySet()).isEmpty())
    }
}
