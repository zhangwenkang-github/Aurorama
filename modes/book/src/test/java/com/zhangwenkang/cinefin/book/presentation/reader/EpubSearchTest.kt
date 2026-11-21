package com.zhangwenkang.cinefin.book.presentation.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EpubSearchTest {

    private val chapters =
        listOf(
            EpubSearchChapter(title = "第一章 起点", href = "OEBPS/ch1.xhtml"),
            EpubSearchChapter(title = "第二章 转折", href = "OEBPS/ch2.xhtml#part-2"),
        )
    private val spine = listOf("OEBPS/ch1.xhtml", "OEBPS/ch2.xhtml", "OEBPS/ch3.xhtml")

    private fun locator(
        href: String = "OEBPS/ch2.xhtml",
        before: String = "前文",
        highlight: String = "重点",
        after: String = "后文",
        progression: Double? = 0.42,
        json: String = """{"href":"$href","locations":{"totalProgression":0.42}}""",
    ) =
        EpubSearchLocator(
            href = href,
            locatorJson = json,
            before = before,
            highlight = highlight,
            after = after,
            progression = progression,
        )

    @Test
    fun `片段前后各保留 30 字并加省略号`() {
        val before = "甲".repeat(40)
        val after = "乙".repeat(40)
        val snippet = epubSearchSnippet(before, "命中词", after)
        assertEquals("…" + "甲".repeat(30) + "命中词" + "乙".repeat(30) + "…", snippet.text)
        // 省略号占 1 字符：命中区间从 31 开始。
        assertEquals(31, snippet.matchStart)
        assertEquals(3, snippet.matchLength)
    }

    @Test
    fun `短于上下文的片段不补省略号`() {
        val snippet = epubSearchSnippet("他说", "你好", "。")
        assertEquals("他说你好。", snippet.text)
        assertEquals(2, snippet.matchStart)
        assertEquals(2, snippet.matchLength)
    }

    @Test
    fun `换行与连续空白折叠为单空格`() {
        val snippet = epubSearchSnippet("第一行\n\n第二行 ", "命  中", "接着\n下一段")
        assertEquals("第一行 第二行命 中接着 下一段", snippet.text)
        assertEquals(7, snippet.matchStart)
        assertEquals(3, snippet.matchLength)
    }

    @Test
    fun `章节名按 href 归因且忽略 fragment`() {
        val hits = epubSearchHits(listOf(locator(href = "OEBPS/ch2.xhtml#anchor")), chapters, spine)
        assertEquals(1, hits.size)
        assertEquals("第二章 转折", hits.single().chapterTitle)
        assertEquals(1, hits.single().spineIndex)
    }

    @Test
    fun `目录没有的 href 回落到章节序号`() {
        val hits = epubSearchHits(listOf(locator(href = "OEBPS/ch3.xhtml")), chapters, spine)
        assertEquals("第 3 章", hits.single().chapterTitle)
        assertEquals(2, hits.single().spineIndex)
    }

    @Test
    fun `既无目录也无 spine 时章节名为空`() {
        val hits =
            epubSearchHits(listOf(locator(href = "OEBPS/unknown.xhtml")), emptyList(), emptyList())
        assertEquals("", hits.single().chapterTitle)
        assertEquals(-1, hits.single().spineIndex)
    }

    @Test
    fun `空 highlight 的定位被丢弃`() {
        val hits =
            epubSearchHits(
                listOf(locator(highlight = ""), locator(highlight = "有效")),
                chapters,
                spine,
            )
        assertEquals(1, hits.size)
        assertEquals("前文有效后文", hits.single().snippet)
    }

    @Test
    fun `命中总数按上限截断`() {
        val locators = (1..5).map { locator(json = """{"index":$it}""") }
        val hits = epubSearchHits(locators, chapters, spine, maxHits = 3)
        assertEquals(3, hits.size)
        assertEquals(
            listOf("""{"index":1}""", """{"index":2}""", """{"index":3}"""),
            hits.map { it.locatorJson },
        )
    }

    @Test
    fun `结果保持书籍顺序`() {
        val hits =
            epubSearchHits(
                listOf(
                    locator(href = "OEBPS/ch1.xhtml"),
                    locator(href = "OEBPS/ch2.xhtml"),
                    locator(href = "OEBPS/ch3.xhtml"),
                ),
                chapters,
                spine,
            )
        assertTrue(hits.isInReadingOrder())
        assertEquals(listOf(0, 1, 2), hits.map { it.spineIndex })
    }

    @Test
    fun `状态行覆盖空态 扫描中 完成 上限 错误`() {
        assertTrue(epubSearchStatusText(EpubSearchUiState()).contains("输入关键词"))
        assertEquals(
            "搜索中 · 命中 1 条",
            epubSearchStatusText(
                EpubSearchUiState(query = "x", running = true, hits = listOf(hit()))
            ),
        )
        assertEquals(
            "搜索完成 · 共 1 条命中",
            epubSearchStatusText(
                EpubSearchUiState(query = "x", finished = true, hits = listOf(hit()))
            ),
        )
        assertEquals(
            "没有找到「x」",
            epubSearchStatusText(EpubSearchUiState(query = "x", finished = true)),
        )
        assertTrue(
            epubSearchStatusText(EpubSearchUiState(query = "x", finished = true, truncated = true))
                .contains("上限")
        )
        assertEquals(
            "搜索失败：网络错误",
            epubSearchStatusText(EpubSearchUiState(query = "x", error = "网络错误")),
        )
    }

    @Test
    fun `跳转进度优先用命中自身 缺失时回落当前进度`() {
        val withProgress = hit()
        assertEquals(0.42, epubSearchJumpProgression(withProgress, 0.1), 1e-9)
        val noProgress = withProgress.copy(progression = null)
        assertEquals(0.1, epubSearchJumpProgression(noProgress, 0.1), 1e-9)
        assertEquals(1.0, epubSearchJumpProgression(noProgress, 2.0), 1e-9)
    }

    @Test
    fun `locator JSON 解析 totalProgression`() {
        val json =
            """{"href":"OEBPS/ch1.xhtml","locations":{"totalProgression":0.625,"position":12}}"""
        assertEquals(0.625, epubSearchProgressionFromLocator(json)!!, 1e-9)
        assertEquals(12L, epubLocationLongOrNull(json, "position"))
        assertNull(epubSearchProgressionFromLocator("not json"))
        assertNull(epubSearchProgressionFromLocator("""{"href":"a"}"""))
    }

    @Test
    fun `命中 key 含下标与区间 避免重复 key 崩溃`() {
        val first = hit()
        val second = hit()
        assertEquals(epubSearchHitKey(0, first), epubSearchHitKey(0, second))
        assertFalse(epubSearchHitKey(0, first) == epubSearchHitKey(1, second))
    }

    private fun hit() =
        EpubSearchHit(
            locatorJson = """{"href":"OEBPS/ch2.xhtml"}""",
            chapterTitle = "第二章 转折",
            chapterHref = "OEBPS/ch2.xhtml",
            spineIndex = 1,
            snippet = "前文重点后文",
            matchStartInSnippet = 2,
            matchLength = 2,
            progression = 0.42,
        )
}
