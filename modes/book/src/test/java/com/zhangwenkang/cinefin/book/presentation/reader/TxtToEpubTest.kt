package com.zhangwenkang.cinefin.book.presentation.reader

import com.zhangwenkang.cinefin.settings.domain.TxtChapterRules
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.nio.file.Files
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TxtToEpubTest {

    private val gbk: Charset = Charset.forName("GBK")
    private val bookId = UUID.fromString("11111111-2222-3333-4444-555555555555")

    @Test
    fun `UTF-8 中文文本按 UTF-8 解码`() {
        val bytes = "第一章 起风了\n夜里下雨了。\n第二章 雨停\n天亮了。\n".toByteArray(Charsets.UTF_8)
        val document = TxtToEpub.decode(bytes)
        assertNotNull(document)
        assertEquals(TxtEncoding.Utf8, document!!.encoding)
        assertTrue(document.text.startsWith("第一章 起风了"))
    }

    @Test
    fun `GBK 文本回落 GB18030 解码`() {
        val text = "第一章 起风了\n夜里下雨了。\n"
        val document = TxtToEpub.decode(text.toByteArray(gbk))
        assertNotNull(document)
        assertEquals(TxtEncoding.Gb18030, document!!.encoding)
        assertEquals(text, document.text)
    }

    @Test
    fun `UTF-8 BOM 被吃掉且仍按 UTF-8 解码`() {
        val body = "第一章 起风了\n".toByteArray(Charsets.UTF_8)
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + body
        val document = TxtToEpub.decode(bytes)
        assertNotNull(document)
        assertEquals(TxtEncoding.Utf8, document!!.encoding)
        assertFalse(document.text.startsWith("\uFEFF"))
        assertTrue(document.text.startsWith("第一章"))
    }

    @Test
    fun `二进制字节不判为文本`() {
        val binary =
            ByteArray(512) {
                // 均匀分布的随机字节：含 NUL 与控制字符，GB18030 宽松解码也不能救回来。
                ((it * 37 + 13) % 256).toByte()
            }
        assertNull(TxtToEpub.detectEncoding(binary))
        assertNull(TxtToEpub.decode(binary))
    }

    @Test
    fun `无 NUL 但控制字符占比高的字节不判为文本`() {
        val bytes = ByteArray(400) { if (it % 10 == 0) 0x01 else 0x41 }
        assertNull(TxtToEpub.detectEncoding(bytes))
    }

    @Test
    fun `探针截断在多字节字符中间仍能识别 UTF-8`() {
        val bytes = "第一章 起风了\n正文内容。\n".toByteArray(Charsets.UTF_8)
        // 故意截到汉字三字节的中间，模拟 1 KiB 探针刚好切断。
        val truncated = bytes.copyOf(4)
        assertEquals(TxtEncoding.Utf8, TxtToEpub.detectEncoding(truncated))
    }

    @Test
    fun `中文章头切章且章名取章头行`() {
        val text =
            """
            序言内容。
            第一章 起风了
            夜里下雨了。
            第二章 雨停
            天亮了。
            """
                .trimIndent()
        val chapters = TxtToEpub.splitChapters(text)
        assertEquals(3, chapters.size)
        assertEquals("开篇", chapters[0].title)
        assertEquals("第一章 起风了", chapters[1].title)
        assertEquals("夜里下雨了。", chapters[1].body)
        assertEquals("第二章 雨停", chapters[2].title)
        assertEquals("天亮了。", chapters[2].body)
    }

    @Test
    fun `无章头整书单章兜底`() {
        val text = "这是一整本没有章头的小说。\n第二段。\n"
        val chapters = TxtToEpub.splitChapters(text)
        assertEquals(1, chapters.size)
        assertNull(chapters.single().title)
        assertEquals("这是一整本没有章头的小说。\n第二段。", chapters.single().body)
    }

    @Test
    fun `英文 Chapter 与番外也识别为章头`() {
        val text = "Chapter 1 The Storm\nIt rained.\n番外一\n后来。\n"
        val chapters = TxtToEpub.splitChapters(text)
        assertEquals(2, chapters.size)
        assertEquals("Chapter 1 The Storm", chapters[0].title)
        assertEquals("番外一", chapters[1].title)
    }

    @Test
    fun `章头必须落在行首且不带句末标点`() {
        val text = "他翻开第二章，愣住了。\n第一章里没有答案。\n真正开始。\n"
        val chapters = TxtToEpub.splitChapters(text)
        assertEquals(1, chapters.size)
        assertNull(chapters.single().title)
    }

    @Test
    fun `超长章头行当正文处理`() {
        val heading = "第一章 " + "很长的标题".repeat(20)
        val chapters = TxtToEpub.splitChapters("$heading\n正文。\n")
        assertEquals(1, chapters.size)
        assertNull(chapters.single().title)
    }

    @Test
    fun `自定义分章正则生效且非法正则回落默认`() {
        val text = "第1话 起点\n内容。\n第2话 终点\n结束。\n"
        assertEquals(2, TxtToEpub.splitChapters(text, "^第\\d+话.*").size)
        // 非法正则：回落默认，命中不到「话」，整书单章。
        assertEquals(1, TxtToEpub.splitChapters(text, "([").size)
        assertEquals(
            TxtChapterRules.DEFAULT_CHAPTER_REGEX,
            TxtChapterRules.resolve("(["),
        )
    }

    @Test
    fun `转换产出可解析的 EPUB 3 结构`() {
        val text = "第一章 起风了\n夜里下雨了。\n\n第二章 雨停\n天亮了。\n"
        val bytes =
            ByteArrayOutputStream()
                .also { out -> TxtToEpub.convert(text.toByteArray(), "雨夜", bookId, out) }
                .toByteArray()
        val file = Files.createTempFile("cinefin-txt-epub", ".epub").toFile()
        try {
            file.writeBytes(bytes)
            ZipFile(file).use { zip ->
                val first = zip.entries().nextElement()
                assertEquals("mimetype", first.name)
                assertEquals(ZipEntry.STORED, first.method)
                assertEquals("application/epub+zip", zip.getInputStream(first).reader().readText())

                assertNotNull(zip.getEntry("META-INF/container.xml"))
                val opf = zip.readText("OEBPS/content.opf")
                assertTrue(opf.contains("<dc:title>雨夜</dc:title>"))
                assertTrue(opf.contains("urn:uuid:$bookId"))
                assertTrue(opf.contains("chapter-0001.xhtml"))
                assertTrue(opf.contains("chapter-0002.xhtml"))

                val nav = zip.readText("OEBPS/nav.xhtml")
                assertTrue(nav.contains("第一章 起风了"))
                assertTrue(nav.contains("第二章 雨停"))

                val chapter = zip.readText("OEBPS/chapter-0001.xhtml")
                assertTrue(chapter.contains("<h1>第一章 起风了</h1>"))
                assertTrue(chapter.contains("<p>夜里下雨了。</p>"))
                assertTrue(chapter.contains("xml:lang=\"zh\""))
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun `无章头书的导航标签用正文`() {
        val bytes =
            ByteArrayOutputStream()
                .also { out -> TxtToEpub.convert("没有章头的小.txt".toByteArray(), "整本", bookId, out) }
                .toByteArray()
        val file = Files.createTempFile("cinefin-txt-epub-flat", ".epub").toFile()
        try {
            file.writeBytes(bytes)
            ZipFile(file).use { zip ->
                val nav = zip.readText("OEBPS/nav.xhtml")
                assertTrue(nav.contains(">正文<"))
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun `重复转换结果字节一致`() {
        val text = "第一章 甲\n内容甲。\n第二章 乙\n内容乙。\n"
        val first =
            ByteArrayOutputStream()
                .also { TxtToEpub.convert(text.toByteArray(), "书", bookId, it) }
                .toByteArray()
        val second =
            ByteArrayOutputStream()
                .also { TxtToEpub.convert(text.toByteArray(), "书", bookId, it) }
                .toByteArray()
        assertTrue(first.contentEquals(second))
    }

    @Test
    fun `书名取文件名去扩展名`() {
        assertEquals("雨夜", TxtToEpub.titleOf("雨夜.txt"))
        assertEquals("a.b", TxtToEpub.titleOf("a.b.txt"))
        assertEquals("无扩展名", TxtToEpub.titleOf("无扩展名"))
    }

    private fun ZipFile.readText(name: String): String =
        getInputStream(getEntry(name)).use { it.reader(Charsets.UTF_8).readText() }
}
