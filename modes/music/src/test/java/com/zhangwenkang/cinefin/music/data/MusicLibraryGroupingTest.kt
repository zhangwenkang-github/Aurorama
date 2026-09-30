package com.zhangwenkang.cinefin.music.data

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 曲库客户端聚合与续播换算的纯函数单测（W2 R2）。 */
class MusicLibraryGroupingTest {

    private fun song(
        name: String,
        album: String,
        artist: String?,
        index: Int?,
        positionTicks: Long? = null,
        runtimeTicks: Long = 240_000_0000L,
    ) =
        MusicSong(
            itemId = UUID.nameUUIDFromBytes(name.toByteArray()),
            name = name,
            albumName = album,
            artist = artist,
            indexNumber = index,
            runtimeTicks = runtimeTicks,
            imageUri = null,
            resumePositionMs = resumePositionMs(positionTicks, runtimeTicks),
        )

    @Test
    fun `专辑按名称分组且组内按音轨序号排序`() {
        val songs =
            listOf(
                song("B2", "专辑乙", "甲", 2),
                song("A1", "专辑甲", "甲", 1),
                song("B1", "专辑乙", "甲", 1),
            )

        val albums = groupAlbums(songs)

        assertEquals(listOf("专辑乙", "专辑甲"), albums.map { it.name })
        assertEquals(listOf("B1", "B2"), albums.first().songs.map { it.name })
        assertEquals("甲", albums.first().artist)
    }

    @Test
    fun `无艺人的曲目归入未知艺术家`() {
        val artists = groupArtists(listOf(song("无名", "专辑", null, 1)))

        assertEquals(1, artists.size)
        assertEquals("未知艺术家", artists.first().name)
        assertTrue(artists.first().songs.isNotEmpty())
    }

    @Test
    fun `艺术家组内按专辑名与音轨序号排序`() {
        val artists =
            groupArtists(
                listOf(
                    song("乙-2", "乙专辑", "歌手", 2),
                    song("甲-1", "甲专辑", "歌手", 1),
                    song("乙-1", "乙专辑", "歌手", 1),
                )
            )

        assertEquals(listOf("乙-1", "乙-2", "甲-1"), artists.first().songs.map { it.name })
    }

    @Test
    fun `续播位置换算与听完回落`() {
        // 60 秒：600_000_000 ticks
        assertEquals(60_000L, resumePositionMs(600_000_000L, 2_400_000_000L))
        // 已听完（≥90%）：从头播放
        assertEquals(0L, resumePositionMs(2_300_000_000L, 2_400_000_000L))
        assertEquals(0L, resumePositionMs(0L, 2_400_000_000L))
        assertEquals(0L, resumePositionMs(null, 2_400_000_000L))
        assertEquals(0L, resumePositionMs(-100L, 2_400_000_000L))
        // 时长未知时直接换算
        assertEquals(10_000L, resumePositionMs(100_000_000L, 0L))
    }
}
