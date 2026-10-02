package com.zhangwenkang.cinefin.music.data.lyrics

import java.io.File
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** 本机歌词覆盖（W25-MUSIC）：文件存储、LRC 往返、GBK 兼容与清除。 */
class LyricsOverrideStoreTest {

    @get:Rule val temporaryFolder = TemporaryFolder()

    private val itemId = UUID.fromString("08751d9c-a895-1dee-c11e-7292012a6e74")

    private fun store(directory: File = File(temporaryFolder.root, "override")) =
        LyricsOverrideStore(directory)

    @Test
    fun `保存后读取保持时间戳与文本`() {
        val store = store()
        val lines =
            listOf(
                LyricLine(800L, "作曲 : 林俊杰"),
                LyricLine(18_000L, "编曲 : 洪信杰"),
                LyricLine(null, "未同步的说明行"),
            )

        assertTrue(store.save(itemId, lines))

        assertEquals(lines, store.load(itemId))
        assertTrue(store.has(itemId))
        val text = store.loadText(itemId).orEmpty()
        assertTrue(text.contains("[00:00.800]作曲 : 林俊杰"))
        assertTrue(text.contains("未同步的说明行"))
    }

    @Test
    fun `导入文本原样保存并可解析`() {
        val store = store()
        val lrc = "[ti:心做し]\n[00:12.34]第一句\n[00:15.00]第二句\n"

        assertTrue(store.saveText(itemId, lrc))

        assertEquals(lrc, store.loadText(itemId))
        val lines = store.load(itemId).orEmpty()
        assertEquals(3, lines.size)
        assertEquals(12_340L, lines[1].startMs)
        assertEquals("第二句", lines[2].text)
    }

    @Test
    fun `空内容不落盘且清除后可再次不存在`() {
        val store = store()

        assertFalse(store.save(itemId, listOf(LyricLine(null, "   "))))
        assertFalse(store.saveText(itemId, ""))
        assertFalse(store.has(itemId))

        store.save(itemId, listOf(LyricLine(0L, "覆盖行")))
        assertTrue(store.has(itemId))
        assertTrue(store.clear(itemId))
        assertFalse(store.has(itemId))
        assertNull(store.load(itemId))
        assertFalse(store.clear(itemId))
    }

    @Test
    fun `GBK 文本按回退编码解码`() {
        val gbkBytes = "作词：测试".toByteArray(charset("GBK"))
        assertEquals("作词：测试", LyricTextCodec.decode(gbkBytes))
        assertEquals("测试", LyricTextCodec.decode("测试".toByteArray(Charsets.UTF_8)))
    }
}
