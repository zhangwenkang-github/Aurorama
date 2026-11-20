package com.zhangwenkang.cinefin.music.data

import com.zhangwenkang.cinefin.local.LocalLibraryEntry
import com.zhangwenkang.cinefin.local.LocalMediaKind
import com.zhangwenkang.cinefin.local.localItemIdFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** MUSIC-35/36：本地条目 → 音乐曲目的字段映射（含不可播格式标记）。 */
class LocalMusicSongMappingTest {

    @Test
    fun `内嵌标签与音频参数进入曲目模型`() {
        val song =
            entry(
                    name = "song.flac",
                    title = "曲名",
                    artist = null,
                    albumArtist = "专辑艺人",
                    album = "专辑",
                    trackIndex = 3,
                    discNumber = 2,
                    genre = "Jazz",
                    year = 2001,
                    bitrate = 1_411_200,
                    sampleRate = 44_100,
                    bitDepth = 24,
                    durationMs = 210_000,
                )
                .toLocalMusicSong(libraryId = 7L)

        assertEquals("曲名", song.name)
        assertEquals("专辑", song.albumName)
        // 曲目艺人缺失时用专辑艺人兜底（MUSIC-35「缺失时用占位 fallback」）
        assertEquals("专辑艺人", song.artist)
        assertEquals("专辑艺人", song.albumArtist)
        assertEquals(3, song.indexNumber)
        assertEquals(2, song.discNumber)
        assertEquals("Jazz", song.genre)
        assertEquals(2001, song.year)
        assertEquals("flac", song.codec)
        assertEquals(1_411_200, song.bitrate)
        assertEquals(44_100, song.sampleRate)
        assertEquals(24, song.bitDepth)
        assertEquals(210_000L * 10_000L, song.runtimeTicks)
        assertEquals(7L, song.localLibraryId)
        assertEquals(MusicItemSource.LOCAL, song.source)
        assertFalse(song.formatUnsupported)
    }

    @Test
    fun `APE 与 DSD 曲目带引擎不支持标记`() {
        listOf("song.ape", "album/01.dsf", "album/02.dff").forEach { path ->
            val song = entry(name = path).toLocalMusicSong()
            assertTrue("$path 必须标记为引擎不支持", song.formatUnsupported)
        }
        assertFalse(entry(name = "song.m4a").toLocalMusicSong().formatUnsupported)
    }

    @Test
    fun `缺失标签保持未知而不是伪造零值`() {
        val song = entry(name = "song.mp3").toLocalMusicSong()
        assertEquals(null, song.albumArtist)
        assertEquals(null, song.discNumber)
        assertEquals(null, song.genre)
        assertEquals(null, song.year)
        assertEquals(null, song.bitrate)
        assertEquals(null, song.sampleRate)
        assertEquals(null, song.bitDepth)
        assertEquals(null, song.artist)
        // 专辑缺失回退既有分组名
        assertEquals("本地文件", song.albumName)
    }

    private fun entry(
        name: String,
        title: String? = null,
        artist: String? = null,
        albumArtist: String? = null,
        album: String? = null,
        trackIndex: Int = 0,
        discNumber: Int? = null,
        genre: String? = null,
        year: Int? = null,
        bitrate: Int? = null,
        sampleRate: Int? = null,
        bitDepth: Int? = null,
        durationMs: Long = 0L,
    ): LocalLibraryEntry {
        val uri = "content://test/$name"
        return LocalLibraryEntry(
            itemId = localItemIdFor(uri),
            folderId = 1L,
            name = name.substringAfterLast('/'),
            kind = LocalMediaKind.MUSIC,
            documentUri = uri,
            relativePath = name,
            sizeBytes = 1024,
            lastModified = 0L,
            durationMs = durationMs,
            title = title,
            artist = artist,
            album = album,
            trackIndex = trackIndex,
            albumArtist = albumArtist,
            discNumber = discNumber,
            genre = genre,
            year = year,
            bitrate = bitrate,
            sampleRate = sampleRate,
            bitDepth = bitDepth,
        )
    }
}
