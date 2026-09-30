package com.zhangwenkang.cinefin.repository

import java.time.Instant
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderBookmarkCodecTest {
    private val itemId = UUID.fromString("8bb46d1a-2e5f-4d5f-9d19-8e4a2f4f0e1a")

    @Test
    fun `书签序列化后可以原样读回`() {
        val bookmarks =
            listOf(
                bookmark(id = "b2", progression = 0.6, label = "第六章 · 60.0%"),
                bookmark(id = "b1", progression = 0.2, label = "20.0%"),
            )

        val decoded = decodeReaderBookmarks(encodeReaderBookmarks(bookmarks))

        // 编解码保持原有顺序，排序由 Store / UI 通过 sortedForReading 决定。
        assertEquals(bookmarks, decoded)
        assertEquals(listOf("b1", "b2"), decoded.sortedForReading().map { it.id })
    }

    @Test
    fun `空文件与脏数据回退为空列表`() {
        assertTrue(decodeReaderBookmarks("").isEmpty())
        assertTrue(decodeReaderBookmarks("not a json").isEmpty())
        assertTrue(decodeReaderBookmarks("""{"items":"oops"}""").isEmpty())
    }

    @Test
    fun `非法 itemId 的条目被跳过`() {
        val json =
            """
            {"items":[
              {"id":"ok","itemId":"$itemId","locatorJson":"{}","progression":0.5,
               "label":"50.0%","createdAtEpochMillis":1000},
              {"id":"bad","itemId":"not-a-uuid","locatorJson":"{}","progression":0.7,
               "label":"70.0%","createdAtEpochMillis":2000}
            ]}
            """
                .trimIndent()

        val decoded = decodeReaderBookmarks(json)

        assertEquals(1, decoded.size)
        assertEquals("ok", decoded.first().id)
    }

    @Test
    fun `进度越界的书签会被夹取`() {
        val json =
            """
            {"items":[
              {"id":"over","itemId":"$itemId","locatorJson":"{}","progression":1.8,
               "label":"180%","createdAtEpochMillis":1000}
            ]}
            """
                .trimIndent()

        assertEquals(1.0, decodeReaderBookmarks(json).first().progression, 0.0001)
        assertEquals(0.0, encodeAndDecode(progression = -3.0).progression, 0.0001)
    }

    private fun encodeAndDecode(progression: Double): ReaderBookmark =
        decodeReaderBookmarks(
                encodeReaderBookmarks(listOf(bookmark(id = "x", progression = progression)))
            )
            .first()

    private fun bookmark(
        id: String,
        progression: Double,
        label: String = "书签",
    ) =
        ReaderBookmark(
            id = id,
            itemId = itemId,
            locatorJson = """{"href":"chapter-$id"}""",
            progression = progression,
            label = label,
            createdAt = Instant.ofEpochMilli(1_000),
        )
}
