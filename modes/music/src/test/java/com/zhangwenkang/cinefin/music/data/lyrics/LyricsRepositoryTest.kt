package com.zhangwenkang.cinefin.music.data.lyrics

import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.jellyfin.sdk.model.api.LyricLine as JellyfinLyricLine
import org.jellyfin.sdk.model.api.LyricLineCue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 歌词来源优先级与"断网可用"回归（MU-5 / D15）。
 *
 * 网络实现换成假对象：`failing = true` 模拟断网 / 服务端不可达。
 */
class LyricsRepositoryTest {

    @get:Rule val temporaryFolder = TemporaryFolder()

    private val itemId = UUID.fromString("3b31291f-b0b4-1e86-aae6-94d9f1a60799")

    private class FakeRemote(
        var lines: List<LyricLine>? = null,
        var failing: Boolean = false,
        var calls: Int = 0,
    ) : LyricsRemoteSource {
        override suspend fun fetch(itemId: UUID): List<LyricLine>? {
            calls++
            if (failing) error("模拟断网")
            return lines
        }
    }

    private fun repository(remote: FakeRemote, directory: File) =
        LyricsRepositoryImpl(
            remote,
            LyricsCache(directory),
            LyricsOverrideStore(File(directory, LyricsOverrideStore.DIRECTORY_NAME)),
        )

    @Test
    fun `returns server lyrics and writes cache`() = runBlocking {
        val directory = File(temporaryFolder.root, "lyrics")
        val cache = LyricsCache(directory)
        val remote =
            FakeRemote(
                lines =
                    listOf(
                        LyricLine(28_440, "夜に駆ける"),
                        LyricLine(28_440, "奔向黑夜"),
                    )
            )

        val document = repository(remote, directory).getLyrics(itemId)

        assertEquals(LyricsSource.SERVER, document?.source)
        assertEquals(1, document?.blocks?.size)
        assertEquals(
            listOf(LyricLine(28_440, "夜に駆ける"), LyricLine(28_440, "奔向黑夜")),
            cache.load(itemId),
        )
    }

    @Test
    fun `falls back to cache when server is unreachable`() = runBlocking {
        val directory = File(temporaryFolder.root, "lyrics")
        LyricsCache(directory).save(itemId, listOf(LyricLine(1_000, "缓存的中文歌词")))
        val remote = FakeRemote(failing = true)

        val document = repository(remote, directory).getLyrics(itemId)

        assertEquals(LyricsSource.CACHE, document?.source)
        assertEquals("缓存的中文歌词", document?.blocks?.single()?.primary?.text)
    }

    @Test
    fun `returns null when offline without cache`() = runBlocking {
        val directory = File(temporaryFolder.root, "lyrics")

        assertNull(repository(FakeRemote(failing = true), directory).getLyrics(itemId))
    }

    @Test
    fun `external lrc wins over server`() = runBlocking {
        val directory = File(temporaryFolder.root, "lyrics")
        directory.mkdirs()
        File(directory, "$itemId.lrc").writeText("[00:01.00]外挂歌词\n", Charsets.UTF_8)
        val remote = FakeRemote(lines = listOf(LyricLine(9_000, "服务端歌词")))

        val document = repository(remote, directory).getLyrics(itemId)

        assertEquals(LyricsSource.EXTERNAL_LRC, document?.source)
        assertEquals("外挂歌词", document?.blocks?.single()?.primary?.text)
        assertEquals(0, remote.calls)
    }

    @Test
    fun `media sibling lrc is preferred for local files`() = runBlocking {
        val directory = File(temporaryFolder.root, "lyrics")
        val media = File(temporaryFolder.root, "song.flac")
        media.writeText("", Charsets.UTF_8)
        File(temporaryFolder.root, "song.lrc").writeText("[00:02.00]本地同目录歌词\n", Charsets.UTF_8)

        val document =
            repository(FakeRemote(lines = listOf(LyricLine(9_000, "服务端歌词"))), directory)
                .getLyrics(itemId, localMediaPath = media.path)

        assertEquals(LyricsSource.EXTERNAL_LRC, document?.source)
        assertEquals("本地同目录歌词", document?.blocks?.single()?.primary?.text)
    }

    @Test
    fun `local override wins over external lrc and server`() = runBlocking {
        val directory = File(temporaryFolder.root, "lyrics")
        directory.mkdirs()
        File(directory, "$itemId.lrc").writeText("[00:01.00]外挂歌词\n", Charsets.UTF_8)
        val overrideStore = LyricsOverrideStore(File(directory, LyricsOverrideStore.DIRECTORY_NAME))
        val remote = FakeRemote(lines = listOf(LyricLine(9_000, "服务端歌词")))
        val repository = LyricsRepositoryImpl(remote, LyricsCache(directory), overrideStore)

        overrideStore.save(itemId, listOf(LyricLine(2_000, "本机编辑歌词")))
        val document = repository.getLyrics(itemId)

        assertEquals(LyricsSource.LOCAL_OVERRIDE, document?.source)
        assertEquals("本机编辑歌词", document?.blocks?.single()?.primary?.text)
        assertEquals(0, remote.calls)
    }

    @Test
    fun `clearing override falls back to server`() = runBlocking {
        val directory = File(temporaryFolder.root, "lyrics")
        val overrideStore = LyricsOverrideStore(File(directory, LyricsOverrideStore.DIRECTORY_NAME))
        val remote = FakeRemote(lines = listOf(LyricLine(9_000, "服务端歌词")))
        val repository = LyricsRepositoryImpl(remote, LyricsCache(directory), overrideStore)

        assertTrue(repository.saveLocalOverrideText(itemId, "[00:02.00]导入歌词\n"))
        assertTrue(repository.hasLocalOverride(itemId))
        assertEquals(LyricsSource.LOCAL_OVERRIDE, repository.getLyrics(itemId)?.source)

        assertTrue(repository.clearLocalOverride(itemId))
        assertEquals(LyricsSource.SERVER, repository.getLyrics(itemId)?.source)
    }

    @Test
    fun `maps server cues to word segments`() {
        val lines =
            listOf(
                JellyfinLyricLine(
                    text = "Hello world",
                    start = 12_000L * LYRICS_TICKS_PER_MS,
                    cues =
                        listOf(
                            LyricLineCue(
                                position = 0,
                                endPosition = 6,
                                start = 12_000L * LYRICS_TICKS_PER_MS,
                            ),
                            LyricLineCue(
                                position = 6,
                                endPosition = 11,
                                start = 12_500L * LYRICS_TICKS_PER_MS,
                            ),
                            // 越界 cue 跳过，不产生半截词
                            LyricLineCue(
                                position = 99,
                                endPosition = 120,
                                start = 13_000L * LYRICS_TICKS_PER_MS,
                            ),
                        ),
                ),
                JellyfinLyricLine(text = "无逐字", start = null, cues = null),
            )

        val mapped = mapServerLyrics(lines)

        assertEquals(12_000L, mapped[0].startMs)
        assertEquals(
            listOf(LyricWord(12_000L, "Hello "), LyricWord(12_500L, "world")),
            mapped[0].words,
        )
        assertNull(mapped[1].startMs)
        assertTrue(mapped[1].words.isEmpty())
    }
}
