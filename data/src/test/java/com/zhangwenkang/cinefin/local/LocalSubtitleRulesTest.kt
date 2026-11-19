package com.zhangwenkang.cinefin.local

import com.zhangwenkang.cinefin.models.FindroidSourceType
import java.util.UUID
import org.jellyfin.sdk.model.api.MediaStreamType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** VIDEO-48 同级目录外挂字幕：匹配规则（同名 / 语言后缀 / 不同名 / 子目录 / 扩展名大小写）与注入后的 `FindroidSource` 结构。 */
class LocalSubtitleRulesTest {

    private fun candidate(
        name: String,
        dir: String = "电影",
        uri: String = "content://test/$dir/$name",
    ) = LocalSubtitleCandidate(name = name, relativePath = "$dir/$name", uri = uri)

    @Test
    fun `同名字幕命中且无标签`() {
        val matches =
            LocalSubtitleRules.match(
                videoName = "demo.mkv",
                videoRelativePath = "电影/demo.mkv",
                candidates = listOf(candidate("demo.srt")),
            )
        assertEquals(1, matches.size)
        assertEquals("demo.srt", matches.single().fileName)
        assertEquals("", matches.single().tag)
        assertEquals("subrip", matches.single().codec)
    }

    @Test
    fun `带语言或标签后缀的字幕命中并提取标签`() {
        val matches =
            LocalSubtitleRules.match(
                videoName = "demo.mkv",
                videoRelativePath = "电影/demo.mkv",
                candidates =
                    listOf(
                        candidate("demo.zh.srt"),
                        candidate("demo.chs.ass"),
                        candidate("demo.forced.zh-hans.vtt"),
                    ),
            )
        assertEquals(
            listOf("demo.chs.ass", "demo.forced.zh-hans.vtt", "demo.zh.srt"),
            matches.map { it.fileName },
        )
        assertEquals(
            listOf("chs", "forced.zh-hans", "zh"),
            matches.map { it.tag },
        )
        assertEquals(listOf("ass", "webvtt", "subrip"), matches.map { it.codec })
    }

    @Test
    fun `不同名不命中_前缀相近也不命中`() {
        val matches =
            LocalSubtitleRules.match(
                videoName = "demo.mkv",
                videoRelativePath = "电影/demo.mkv",
                candidates = listOf(candidate("demo2.srt"), candidate("demoxyz.srt")),
            )
        assertTrue(matches.isEmpty())
    }

    @Test
    fun `子目录里的同名文件不命中`() {
        val matches =
            LocalSubtitleRules.match(
                videoName = "demo.mkv",
                videoRelativePath = "电影/demo.mkv",
                candidates = listOf(candidate("demo.srt", dir = "电影/字幕")),
            )
        assertTrue(matches.isEmpty())
    }

    @Test
    fun `扩展名大小写不敏感_非字幕扩展名不命中`() {
        val matches =
            LocalSubtitleRules.match(
                videoName = "Demo.MKV",
                videoRelativePath = "电影/Demo.MKV",
                candidates =
                    listOf(candidate("Demo.SRT"), candidate("Demo.ass"), candidate("Demo.txt")),
            )
        assertEquals(listOf("Demo.SRT", "Demo.ass"), matches.map { it.fileName }.sorted())
        assertEquals("zh", LocalSubtitleRules.tagFor("Demo.MKV", "Demo.zh.SRT"))
        assertEquals("", LocalSubtitleRules.tagFor("Demo.MKV", "Demo.SRT"))
        assertNull(LocalSubtitleRules.tagFor("Demo.MKV", "demo.SRT"))
        assertNull(LocalSubtitleRules.tagFor("demo.mkv", "demo..srt"))
    }

    @Test
    fun `注入后 FindroidSource 结构为外挂字幕流`() {
        val source =
            LocalPlayableSource(
                    itemId = UUID.nameUUIDFromBytes("content://test/demo.mkv".toByteArray()),
                    title = "demo",
                    uri = "content://test/demo.mkv",
                    kind = LocalMediaKind.VIDEO,
                    subtitles =
                        listOf(
                            LocalSubtitleFile(
                                uri = "content://test/demo.zh.ass",
                                fileName = "demo.zh.ass",
                                tag = "zh",
                                codec = "ass",
                            ),
                            LocalSubtitleFile(
                                uri = "content://test/demo.srt",
                                fileName = "demo.srt",
                                tag = "",
                                codec = "subrip",
                            ),
                        ),
                )
                .toFindroidSource()

        assertEquals(FindroidSourceType.LOCAL, source.type)
        assertEquals("content://test/demo.mkv", source.path)
        assertEquals(2, source.mediaStreams.size)
        source.mediaStreams.forEachIndexed { position, stream ->
            assertEquals(MediaStreamType.SUBTITLE, stream.type)
            assertTrue(stream.isExternal)
            assertTrue(stream.path!!.startsWith("content://test/"))
            assertEquals(position, stream.index)
        }
        assertEquals(
            listOf("demo.zh.ass", "demo.srt"),
            source.mediaStreams.map { it.title },
        )
        assertEquals(listOf("zh", ""), source.mediaStreams.map { it.language })
        assertEquals(listOf("ass", "subrip"), source.mediaStreams.map { it.codec })
    }

    @Test
    fun `无字幕的本地视频仍是空流清单`() {
        val source =
            LocalPlayableSource(
                    itemId = UUID.randomUUID(),
                    title = "demo2",
                    uri = "content://test/demo2.mkv",
                    kind = LocalMediaKind.VIDEO,
                )
                .toFindroidSource()
        assertTrue(source.mediaStreams.isEmpty())
    }
}
