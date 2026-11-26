package com.zhangwenkang.cinefin.presentation.film

import com.zhangwenkang.cinefin.local.localItemIdFor
import com.zhangwenkang.cinefin.repository.ReadingProgress
import java.time.Instant
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BookshelfShelfLogicTest {

    private fun progress(itemId: UUID, progression: Double, updatedAtMs: Long) =
        ReadingProgress(
            itemId = itemId,
            progression = progression,
            positionTicks = 0L,
            updatedAt = Instant.ofEpochMilli(updatedAtMs),
        )

    @Test
    fun `本地书架登记 编解码往返并去重`() {
        val a = BookshelfLocalBook(uri = "content://docs/a.epub", name = "甲", addedAtMs = 1L)
        val b = BookshelfLocalBook(uri = "content://docs/b.txt", name = "乙", addedAtMs = 2L)
        val raw = LocalShelfRegistry.encode(listOf(a, b))
        assertEquals(listOf(a, b), LocalShelfRegistry.decode(raw))
        // 同 URI 再加一次 = 覆盖显示名，不产生重复行。
        val updated = LocalShelfRegistry.withBook(raw, a.copy(name = "甲（改名）"))
        assertEquals(2, LocalShelfRegistry.decode(updated).size)
        assertEquals(
            "甲（改名）",
            LocalShelfRegistry.decode(updated).first { it.uri == a.uri }.name,
        )
        // 移出只删登记。
        val removed = LocalShelfRegistry.withoutUri(updated, a.uri)
        assertEquals(listOf(b), LocalShelfRegistry.decode(removed))
    }

    @Test
    fun `本地书架登记 脏数据回落空列表`() {
        assertTrue(LocalShelfRegistry.decode("").isEmpty())
        assertTrue(LocalShelfRegistry.decode("not json").isEmpty())
        assertTrue(LocalShelfRegistry.decode("""[{"uri":"","name":""}]""").isEmpty())
        assertEquals("", LocalShelfRegistry.encode(emptyList()))
    }

    @Test
    fun `进度百分比夹取与未读文案`() {
        assertEquals(42, bookshelfProgressPercent(0.42))
        assertEquals(0, bookshelfProgressPercent(0.0))
        assertEquals(100, bookshelfProgressPercent(1.0))
        assertEquals(100, bookshelfProgressPercent(3.0))
        assertEquals(0, bookshelfProgressPercent(-1.0))
        assertNull(bookshelfProgressPercent(null))
        assertNull(bookshelfProgressPercent(Double.NaN))
        assertEquals("42%", bookshelfProgressText(42))
        assertEquals("未读", bookshelfProgressText(null))
    }

    @Test
    fun `条目附加信息行 作者加进度 缺作者只显进度`() {
        assertEquals("城平京 · 42%", bookshelfMetaLine(listOf("城平京"), 42))
        assertEquals("甲、乙 · 7%", bookshelfMetaLine(listOf("甲", "乙"), 7))
        assertEquals("未读", bookshelfMetaLine(emptyList(), null))
        assertEquals("42%", bookshelfMetaLine(listOf(" ", ""), 42))
    }

    @Test
    fun `最近阅读保留仓储倒序并归因标题与封面`() {
        val newer = UUID.randomUUID()
        val older = UUID.randomUUID()
        val missing = UUID.randomUUID()
        val entries =
            bookshelfRecentEntries(
                progress =
                    listOf(
                        progress(newer, 0.8, 3_000L),
                        progress(older, 0.2, 2_000L),
                        progress(missing, 0.5, 1_000L),
                    ),
                serverBooks =
                    bookshelfServerBooks(
                        listOf(
                            BookshelfServerBook(newer, "新书", "https://c/1"),
                            BookshelfServerBook(older, "旧书", null),
                        )
                    ),
                localBooks = emptyList(),
            )
        assertEquals(listOf("新书", "旧书"), entries.map { it.title })
        assertEquals(listOf(80, 20), entries.map { it.progressPercent })
        assertEquals("https://c/1", entries.first().coverUrl)
        assertNull(entries.last().coverUrl)
        // 没有服务器条目可归因的进度直接跳过（不出现空标题卡）。
        assertFalse(entries.any { it.itemId == missing })
    }

    @Test
    fun `最近阅读包含本地书且点击带 URI`() {
        val book = BookshelfLocalBook(uri = "content://docs/local.epub", name = "本地书")
        val entry =
            bookshelfRecentEntries(
                    progress = listOf(progress(localItemIdFor(book.uri), 0.33, 10L)),
                    serverBooks = emptyMap(),
                    localBooks = listOf(book),
                )
                .single()
        assertEquals("本地书", entry.title)
        assertEquals(33, entry.progressPercent)
        assertEquals(book.uri, entry.localUri)
        assertNull(entry.coverUrl)
    }

    @Test
    fun `最近阅读受上限约束且无进度不入行`() {
        val ids = (1..5).map { UUID.randomUUID() }
        val serverBooks =
            bookshelfServerBooks(
                ids.mapIndexed { index, id -> BookshelfServerBook(id, "书$index", null) }
            )
        val entries =
            bookshelfRecentEntries(
                progress = ids.mapIndexed { index, id -> progress(id, 0.1, 100L - index) },
                serverBooks = serverBooks,
                localBooks = emptyList(),
                limit = 3,
            )
        assertEquals(3, entries.size)
        assertTrue(
            bookshelfRecentEntries(
                    progress = listOf(progress(ids.first(), 0.0, 1L)),
                    serverBooks = serverBooks,
                    localBooks = emptyList(),
                )
                .isNotEmpty()
        )
        assertTrue(
            bookshelfRecentEntries(
                    progress = emptyList(),
                    serverBooks = serverBooks,
                    localBooks = emptyList(),
                )
                .isEmpty()
        )
    }

    @Test
    fun `移出书架集合运算 只动本地标记`() {
        val id = UUID.randomUUID()
        val hidden = bookshelfToggleHidden(emptySet(), id, hidden = true)
        assertEquals(setOf(id.toString()), hidden)
        assertTrue(bookshelfIsHidden(id, hidden))
        val restored = bookshelfToggleHidden(hidden, id, hidden = false)
        assertTrue(restored.isEmpty())
        assertFalse(bookshelfIsHidden(id, restored))
    }

    @Test
    fun `书架增强头部 两段都空时不占位`() {
        val entry =
            BookshelfRecentEntry(
                itemId = UUID.randomUUID(),
                title = "书",
                coverUrl = null,
                progressPercent = 42,
            )
        val local = BookshelfLocalBook(uri = "content://docs/a.epub", name = "甲")
        // 2.0.2 D3：空列表零占位——两段都空时内容流里不插 header 项。
        assertFalse(bookshelfShelfHeaderVisible(emptyList(), emptyList()))
        assertTrue(bookshelfShelfHeaderVisible(listOf(entry), emptyList()))
        assertTrue(bookshelfShelfHeaderVisible(emptyList(), listOf(local)))
    }
}
