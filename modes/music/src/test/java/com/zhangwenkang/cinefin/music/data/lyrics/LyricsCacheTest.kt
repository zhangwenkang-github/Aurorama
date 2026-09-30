package com.zhangwenkang.cinefin.music.data.lyrics

import java.io.File
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LyricsCacheTest {

    @get:Rule val temporaryFolder = TemporaryFolder()

    private val itemId = UUID.fromString("4e610c49-2dfe-2a8e-c2b9-ef66892fcad5")

    @Test
    fun `round trips lines with special characters`() {
        val cache = LyricsCache(File(temporaryFolder.root, "lyrics"))
        val lines =
            listOf(
                LyricLine(28_440, "夜に駆ける\t副歌"),
                LyricLine(40_000, "第一行\n第二行"),
                LyricLine(null, "反斜杠 \\ 保留"),
            )

        cache.save(itemId, lines)
        val loaded = cache.load(itemId)

        assertEquals(lines, loaded)
    }

    @Test
    fun `returns null when nothing cached`() {
        val cache = LyricsCache(File(temporaryFolder.root, "lyrics"))

        assertNull(cache.load(itemId))
    }

    @Test
    fun `clear removes cached file`() {
        val cache = LyricsCache(File(temporaryFolder.root, "lyrics"))
        cache.save(itemId, listOf(LyricLine(1_000, "歌词")))

        cache.clear(itemId)

        assertNull(cache.load(itemId))
    }

    @Test
    fun `ignores empty payload`() {
        val cache = LyricsCache(File(temporaryFolder.root, "lyrics"))
        cache.save(itemId, emptyList())

        assertNull(cache.load(itemId))
    }
}
