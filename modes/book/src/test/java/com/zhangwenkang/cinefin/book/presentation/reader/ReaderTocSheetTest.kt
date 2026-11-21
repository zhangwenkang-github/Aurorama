package com.zhangwenkang.cinefin.book.presentation.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** W6 T2：目录纯逻辑（展平层级 / positions 补起点 / 已读判定 / 当前位置）。 */
class ReaderTocSheetTest {

    private fun entry(
        title: String,
        level: Int = 0,
        progression: Double? = null,
        href: String? = null,
    ): ReaderTocEntry =
        ReaderTocEntry(
            title = title,
            level = level,
            href = href,
            progression = progression,
        )

    @Test
    fun `目录展平保留层级`() {
        val nodes =
            listOf(
                ReaderTocNode(
                    title = "第一部",
                    children =
                        listOf(
                            ReaderTocNode("第一章"),
                            ReaderTocNode(
                                title = "第二章",
                                children = listOf(ReaderTocNode("第一节")),
                            ),
                        ),
                ),
                ReaderTocNode("尾声"),
            )
        val entries = readerTocEntries(nodes)
        assertEquals(
            listOf("第一部", "第一章", "第二章", "第一节", "尾声"),
            entries.map { it.title },
        )
        assertEquals(listOf(0, 1, 1, 2, 0), entries.map { it.level })
    }

    @Test
    fun `空标题节点被跳过且子级提级`() {
        val nodes =
            listOf(
                ReaderTocNode(
                    title = "  ",
                    children = listOf(ReaderTocNode("第一章"), ReaderTocNode("第二章")),
                )
            )
        val entries = readerTocEntries(nodes)
        assertEquals(listOf("第一章", "第二章"), entries.map { it.title })
        assertEquals(listOf(0, 0), entries.map { it.level })
    }

    @Test
    fun `positions 给目录补章节起点`() {
        val entries =
            listOf(
                entry("第一章", href = "ch1.xhtml#top"),
                entry("第二章", href = "ch2.xhtml"),
                entry("附录", href = "missing.xhtml"),
            )
        val positions =
            listOf(
                ReaderPosition("ch1.xhtml", 0.0),
                ReaderPosition("ch1.xhtml", 0.1),
                ReaderPosition("ch2.xhtml#a", 0.6),
                ReaderPosition("ch2.xhtml#b", 0.55),
            )
        val enriched = readerTocWithProgressions(entries, positions)
        assertEquals(0.0, enriched[0].progression ?: -1.0, 0.0)
        assertEquals(0.55, enriched[1].progression ?: -1.0, 0.0)
        assertNull(enriched[2].progression)
    }

    @Test
    fun `已读判定按同级章节区间`() {
        val entries =
            listOf(
                entry("第一部", progression = 0.0),
                entry("第一章", 1, 0.0),
                entry("第二章", 1, 0.3),
                entry("尾声", progression = 0.8),
            )
        // 第一章区间 = [0.0, 0.3)：位置 0.5 时已读完；第二章未读完；第一部区间到尾声 0.8 未读完；尾声到 1.0。
        val flags = readerTocReadFlags(entries, 0.5)
        assertEquals(listOf(false, true, false, false), flags)
        // 到 1.0 全书读完
        assertTrue(readerTocReadFlags(entries, 1.0).all { it })
        // 开头什么都还没读完
        assertFalse(readerTocReadFlags(entries, 0.0).any { it })
    }

    @Test
    fun `positions 不可用时没有已读标记`() {
        val entries = listOf(entry("第一章"), entry("第二章", 1))
        assertFalse(readerTocReadFlags(entries, 1.0).any { it })
        assertNull(readerCurrentTocIndex(entries, 0.5))
    }

    @Test
    fun `当前章取 progression 不超过当前位置的最后一条`() {
        val entries =
            listOf(
                entry("第一部", progression = 0.0),
                entry("第一章", 1, 0.0),
                entry("第二章", 1, 0.3),
                entry("尾声", progression = 0.8),
            )
        assertEquals(2, readerCurrentTocIndex(entries, 0.5))
        assertEquals(3, readerCurrentTocIndex(entries, 0.85))
        assertEquals(1, readerCurrentTocIndex(entries, 0.0))
        assertEquals("第二章", readerChapterTitleAt(entries, 0.5))
        // 非法值按 0 处理：落到书首（这一组条目里书首是「第一章」）。
        assertEquals("第一章", readerChapterTitleAt(entries, Double.NaN))
    }

    @Test
    fun `当前章在越界进度下夹到末尾`() {
        val entries = listOf(entry("第一章", progression = 0.1), entry("第二章", progression = 0.6))
        assertEquals(1, readerCurrentTocIndex(entries, 1.7))
    }
}
