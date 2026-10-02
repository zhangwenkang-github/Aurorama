package com.zhangwenkang.cinefin.book.presentation.reader

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderAnnotationTest {

    @Test
    fun `矩形夹取越界与反向输入`() {
        val rect = PageRect(left = 0.8f, top = -0.2f, right = 0.2f, bottom = 1.4f).normalized()
        assertEquals(0.2f, rect.left, 0.0001f)
        assertEquals(0.8f, rect.right, 0.0001f)
        assertEquals(0f, rect.top, 0.0001f)
        assertEquals(1f, rect.bottom, 0.0001f)
        val nan = PageRect(Float.NaN, Float.NaN, Float.NaN, Float.NaN).normalized()
        assertEquals(0f, nan.left, 0.0001f)
        assertEquals(0f, nan.width, 0.0001f)
    }

    @Test
    fun `过细的框被撑到最小可点尺寸`() {
        val thin = PageRect(0.5f, 0.5f, 0.5f, 0.5f).withMinSize()
        assertTrue(thin.width >= 0.005f)
        assertTrue(thin.height >= 0.007f)
        assertTrue(thin.contains(0.5f, 0.5f))
    }

    @Test
    fun `备注摘要折叠空白并截断`() {
        assertEquals("第一行 第二行", annotationNoteSummary(" 第一行\n第二行 "))
        assertEquals("一二三四五六…", annotationNoteSummary("一二三四五六七八九十", maxChars = 6))
    }

    @Test
    fun `批注编解码往返保持字段`() {
        val annotations =
            listOf(
                newReaderAnnotation(
                    itemId = "item-1",
                    pageIndex = 12,
                    rect = PageRect(0.1f, 0.2f, 0.4f, 0.3f),
                    note = "  这里是重点  ",
                    nowMs = 1_700_000_000_000L,
                    id = "a1",
                ),
                newReaderAnnotation(
                    itemId = "item-1",
                    pageIndex = 3,
                    rect = PageRect(0.5f, 0.5f, 0.6f, 0.6f),
                    note = "",
                    nowMs = 1_700_000_000_001L,
                    id = "a2",
                ),
            )
        val decoded =
            ReaderAnnotationCodec.decode(
                "item-1",
                ReaderAnnotationCodec.encode("item-1", annotations),
            )
        assertEquals(2, decoded.size)
        // 排序：按页码，3 在前。
        assertEquals(listOf(3, 12), decoded.map { it.pageIndex })
        assertEquals("这里是重点", decoded[1].note)
        assertEquals(0.1f, decoded[1].rect.left, 0.0001f)
        assertEquals("a2", decoded[0].id)
    }

    @Test
    fun `批注标签带页码与摘要`() {
        val annotation =
            newReaderAnnotation("item", 4, PageRect(0f, 0f, 0.2f, 0.1f), "解释", nowMs = 1L, id = "x")
        assertEquals("第 5 页 · 解释", annotation.label)
        val empty = annotation.copy(note = "")
        assertEquals("第 5 页 · 高亮", empty.label)
    }

    @Test
    fun `坏文件与未知字段不会阻塞读取`() {
        assertTrue(ReaderAnnotationCodec.decode("item", "不是 JSON").isEmpty())
        val withUnknown =
            """
            {"version":99,"itemId":"item","futureField":1,
             "annotations":[{"id":"a","page":1,"left":0.1,"top":0.1,"right":0.2,"bottom":0.2,
                             "note":"n","createdAtMs":1,"updatedAtMs":2,"color":"red"}]}
            """
                .trimIndent()
        val decoded = ReaderAnnotationCodec.decode("item", withUnknown)
        assertEquals(1, decoded.size)
        assertEquals("n", decoded.single().note)
    }

    @Test
    fun `存储按 itemId 分文件并支持增改删`() = runBlocking {
        val dir = Files.createTempDirectory("w29-annotations").toFile()
        val store = ReaderAnnotationStore(dir)
        val first =
            newReaderAnnotation(
                "book-a",
                1,
                PageRect(0.1f, 0.1f, 0.3f, 0.3f),
                "备注一",
                nowMs = 10L,
                id = "a",
            )
        val second =
            newReaderAnnotation(
                "book-a",
                2,
                PageRect(0.2f, 0.2f, 0.4f, 0.4f),
                "备注二",
                nowMs = 11L,
                id = "b",
            )

        assertEquals(1, store.save(first).size)
        assertEquals(2, store.save(second).size)
        assertTrue(store.storageFile("book-a").isFile)
        // 另一个 itemId 互不影响。
        assertTrue(store.list("book-b").isEmpty())

        val updated = store.updateNote("book-a", "a", "改过的备注", nowMs = 99L)
        assertEquals("改过的备注", updated.first { it.id == "a" }.note)
        assertEquals(99L, updated.first { it.id == "a" }.updatedAtMs)

        val remaining = store.delete("book-a", "a")
        assertEquals(listOf("b"), remaining.map { it.id })
        // 重新读盘（不依赖内存状态）。
        assertEquals(listOf("b"), store.list("book-a").map { it.id })
    }

    @Test
    fun `备注长度超过上限被截断`() {
        val long = "字".repeat(READER_ANNOTATION_NOTE_MAX_CHARS + 20)
        assertEquals(READER_ANNOTATION_NOTE_MAX_CHARS, sanitizeAnnotationNote(long).length)
        assertFalse(sanitizeAnnotationNote(long).endsWith(" "))
    }
}
