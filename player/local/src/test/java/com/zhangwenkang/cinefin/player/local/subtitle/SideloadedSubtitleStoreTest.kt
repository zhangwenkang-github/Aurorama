package com.zhangwenkang.cinefin.player.local.subtitle

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SideloadedSubtitleStoreTest {

    @get:Rule val tempFolder = TemporaryFolder()

    private fun store(): SideloadedSubtitleStore =
        SideloadedSubtitleStore(File(tempFolder.root, "subs"))

    private fun srt(text: String = "1\n00:00:01,000 --> 00:00:02,000\n你好\n"): ByteArray =
        text.toByteArray(Charsets.UTF_8)

    @Test
    fun `导入 SRT 按媒体保存并可列出`() {
        val store = store()

        val record = store.import("media-1", "Movie.zh-Hans.srt", srt())

        assertNotNull(record)
        assertEquals("media-1", record!!.mediaId)
        assertEquals("subrip", record.codec)
        assertEquals("zh-Hans", record.language)
        assertEquals(listOf(record.path), store.list("media-1").map { it.path })
        assertTrue(File(record.path).exists())
        // 侧载记录按媒体隔离：另一个条目看不到
        assertEquals(0, store.list("media-2").size)
    }

    @Test
    fun `ASS 与 VTT 的编码映射`() {
        val store = store()

        val ass = store.import("media-1", "特效.ass", srt("[Script Info]\n"))
        val vtt = store.import("media-1", "WebVTT.vtt", srt("WEBVTT\n"))

        assertEquals("ass", ass?.codec)
        assertEquals("webvtt", vtt?.codec)
    }

    @Test
    fun `不支持的扩展名与空内容被拒绝`() {
        val store = store()

        assertNull(store.import("media-1", "字幕.txt", srt()))
        assertNull(store.import("media-1", "字幕.srt", ByteArray(0)))
        assertEquals(0, store.list("media-1").size)
    }

    @Test
    fun `同名文件重复导入不覆盖并保留两份`() {
        val store = store()

        val first = store.import("media-1", "Movie.srt", srt("first"))
        val second = store.import("media-1", "Movie.srt", srt("second"))

        assertNotNull(first)
        assertNotNull(second)
        assertFalse(first!!.path == second!!.path)
        assertEquals(2, store.list("media-1").size)
        assertEquals("first", File(first.path).readText().trim())
        assertEquals("second", File(second.path).readText().trim())
    }

    @Test
    fun `移除只删除一份并清理记录`() {
        val store = store()
        val first = store.import("media-1", "Movie.srt", srt())!!
        val second = store.import("media-1", "Other.ass", srt())!!

        assertTrue(store.remove(first))

        assertEquals(listOf(second.path), store.list("media-1").map { it.path })
        assertFalse(File(first.path).exists())
        assertTrue(File(second.path).exists())
    }
}
