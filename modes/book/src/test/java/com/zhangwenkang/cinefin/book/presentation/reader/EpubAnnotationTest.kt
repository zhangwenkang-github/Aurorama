package com.zhangwenkang.cinefin.book.presentation.reader

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EpubAnnotationTest {

    private val locatorJson =
        """{"href":"OEBPS/ch1.xhtml","type":"application/xhtml+xml","locations":{"progression":0.25,"cssSelector":"body > div:nth-child(2)"},"text":{"before":"前文","highlight":"重点句","after":"后文"}}"""

    @Test
    fun `编解码往返保持全部字段`() {
        val annotation =
            newBookAnnotation(
                bookId = "book-1",
                chapterHref = "OEBPS/ch1.xhtml",
                locatorJson = locatorJson,
                highlightColor = HighlightColor.Pink,
                noteText = "  这里是想法  ",
                selectedText = "重点句",
                chapterTitle = "第一章",
                spineIndex = 2,
                nowMs = 1_700_000_000_000L,
                id = "a1",
            )
        val decoded =
            BookAnnotationCodec.decode(
                "book-1",
                BookAnnotationCodec.encode("book-1", listOf(annotation)),
            )
        assertEquals(1, decoded.size)
        val restored = decoded.single()
        assertEquals(annotation, restored)
        assertEquals(HighlightColor.Pink, restored.highlightColor)
        assertEquals("这里是想法", restored.noteText)
        assertEquals("第一章", restored.chapterTitle)
        assertEquals(2, restored.spineIndex)
        assertEquals(locatorJson, restored.locatorJson)
    }

    @Test
    fun `选中文本折叠空白并截断到200字`() {
        val long = " 一二 三四\n五六 ".repeat(60)
        val sanitized = sanitizeEpubSelectedText(long)
        assertEquals(200, sanitized.length)
        assertTrue(!sanitized.contains("\n"))
        assertTrue(!sanitized.startsWith(" "))
        assertEquals("一二 三四", sanitizeEpubSelectedText("  一二\n三四  "))
    }

    @Test
    fun `颜色以具名枚举落盘而不是序数`() {
        val annotation =
            newBookAnnotation(
                bookId = "b",
                chapterHref = "c.xhtml",
                locatorJson = locatorJson,
                highlightColor = HighlightColor.Green,
                nowMs = 1L,
                id = "a",
            )
        val raw = BookAnnotationCodec.encode("b", listOf(annotation))
        assertTrue(raw.contains("\"highlightColor\":\"green\""))
        assertTrue(!raw.contains("\"highlightColor\":3"))
        assertEquals(HighlightColor.Green, HighlightColor.fromId("GREEN"))
        assertNull(HighlightColor.fromId("violet"))
    }

    @Test
    fun `未知颜色名回退 yellow 坏 JSON 回退空列表`() {
        val raw =
            """{"version":1,"bookId":"b","annotations":[{"id":"x","locatorJson":"{}","highlightColor":"violet"}]}"""
        val decoded = BookAnnotationCodec.decode("b", raw)
        assertEquals(1, decoded.size)
        assertEquals(HighlightColor.Yellow, decoded.single().highlightColor)
        assertTrue(BookAnnotationCodec.decode("b", "{not json").isEmpty())
        assertTrue(BookAnnotationCodec.decode("b", "").isEmpty())
    }

    @Test
    fun `缺省字段与缺失版本号仍可读`() {
        val raw = """{"annotations":[{"id":"x","locatorJson":"{}"}]}"""
        val decoded = BookAnnotationCodec.decode("b", raw)
        assertEquals(1, decoded.size)
        val annotation = decoded.single()
        assertEquals(HighlightColor.Yellow, annotation.highlightColor)
        assertEquals(-1, annotation.spineIndex)
        assertEquals("", annotation.selectedText)
    }

    @Test
    fun `空白 locator 的记录被丢弃`() {
        val raw =
            """{"version":1,"annotations":[{"id":"x","locatorJson":""},{"id":"y","locatorJson":"{}"}]}"""
        val decoded = BookAnnotationCodec.decode("b", raw)
        assertEquals(listOf("y"), decoded.map { it.id })
    }

    @Test
    fun `改色与改笔记只更新对应字段`() {
        val annotation =
            newBookAnnotation(
                bookId = "b",
                chapterHref = "c.xhtml",
                locatorJson = locatorJson,
                highlightColor = HighlightColor.Yellow,
                noteText = "旧笔记",
                selectedText = "重点句",
                chapterTitle = "第一章",
                spineIndex = 1,
                nowMs = 10L,
                id = "a",
            )
        val recolored = annotation.withHighlightColor(HighlightColor.Blue, nowMs = 20L)
        assertEquals(HighlightColor.Blue, recolored.highlightColor)
        assertEquals(annotation.noteText, recolored.noteText)
        assertEquals(20L, recolored.updatedAtMs)
        assertEquals(10L, recolored.createdAtMs)

        val noted = annotation.withNote("  新笔记  ", nowMs = 30L)
        assertEquals("新笔记", noted.noteText)
        assertEquals(HighlightColor.Yellow, noted.highlightColor)
        assertEquals(30L, noted.updatedAtMs)
    }

    @Test
    fun `阅读顺序按 spine 再按创建时间`() {
        val late =
            newBookAnnotation("b", "c3", "{}", HighlightColor.Yellow, nowMs = 3L, id = "c3")
                .copy(spineIndex = 3)
        val early =
            newBookAnnotation("b", "c1", "{}", HighlightColor.Yellow, nowMs = 9L, id = "c1")
                .copy(spineIndex = 1)
        val unknown =
            newBookAnnotation("b", "c0", "{}", HighlightColor.Yellow, nowMs = 0L, id = "c0")
        assertEquals(
            listOf("c1", "c3", "c0"),
            listOf(early, late, unknown).sortedForReading().map { it.id },
        )
    }

    @Test
    fun `从 locator JSON 读 progression`() {
        assertEquals(0.25, epubAnnotationProgression(locatorJson)!!, 0.0001)
        assertEquals(
            0.5,
            epubAnnotationProgression("""{"locations":{"totalProgression":0.5}}""")!!,
            0.0001,
        )
        assertNull(epubAnnotationProgression("{}"))
        assertNull(epubAnnotationProgression("not json"))
    }

    @Test
    fun `列表标签带章节与摘要`() {
        val annotation =
            newBookAnnotation(
                bookId = "b",
                chapterHref = "c.xhtml",
                locatorJson = locatorJson,
                highlightColor = HighlightColor.Yellow,
                noteText = "",
                selectedText = "重点句",
                chapterTitle = "第一章",
                spineIndex = 0,
                nowMs = 1L,
                id = "a",
            )
        assertEquals("第一章 · 重点句", annotation.label)
        assertEquals("第 1 章 · 重点句", annotation.copy(chapterTitle = "").label)
        assertEquals("重点句", annotation.summary)
    }

    @Test
    fun `存储读写改色删除与脏文件容错`() = runBlocking {
        val dir = Files.createTempDirectory("epub-annotations").toFile()
        try {
            val store = EpubAnnotationStore(dir)
            val first =
                newBookAnnotation(
                    bookId = "book",
                    chapterHref = "c1.xhtml",
                    locatorJson = locatorJson,
                    highlightColor = HighlightColor.Yellow,
                    selectedText = "第一",
                    spineIndex = 1,
                    nowMs = 1L,
                    id = "a1",
                )
            val second = first.copy(id = "a2", selectedText = "第二", spineIndex = 0)

            store.save(first)
            val afterSecondSave = store.save(second)
            assertEquals(listOf("a2", "a1"), afterSecondSave.map { it.id })
            assertEquals(2, store.list("book").size)

            val recolored = store.recolor("book", "a1", HighlightColor.Pink, nowMs = 5L)
            assertEquals(HighlightColor.Pink, recolored.first { it.id == "a1" }.highlightColor)

            val noted = store.updateNote("book", "a1", " 想法 ", nowMs = 6L)
            assertEquals("想法", noted.first { it.id == "a1" }.noteText)

            val remaining = store.delete("book", "a2")
            assertEquals(listOf("a1"), remaining.map { it.id })

            store.storageFile("book").writeText("{ broken")
            assertTrue(store.list("book").isEmpty())
        } finally {
            dir.deleteRecursively()
        }
    }
}
