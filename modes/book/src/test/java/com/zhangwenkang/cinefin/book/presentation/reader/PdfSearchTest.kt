package com.zhangwenkang.cinefin.book.presentation.reader

import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfSearchTest {

    @Test
    fun `关键词归一化折叠空白`() {
        assertEquals("虚构 推理", normalizeSearchQuery("  虚构   推理 "))
        assertEquals("attention", normalizeSearchQuery(" attention "))
        assertEquals("", normalizeSearchQuery("   "))
    }

    @Test
    fun `片段折叠换行且命中位置可定位`() {
        val text = "第一行内容\n虚构推理是长篇\n第三行内容"
        val at = text.indexOf("虚构推理")
        val window = buildSnippet(text, at, "虚构推理", context = 3)
        val hit = window.text.substring(window.matchStart, window.matchStart + window.matchLength)
        assertEquals("虚构推理", hit)
        assertFalse(window.text.contains('\n'))
        assertTrue(window.text.contains("…"))
    }

    @Test
    fun `字符框匹配支持大小写与空白无关`() {
        val text = "Attention IS All You Need"
        val chars = charsOf(text)
        assertEquals(1, matchCharRects(chars, "attention", limit = 5).size)
        assertEquals(1, matchCharRects(chars, "is all", limit = 5).size)
        assertEquals(1, matchCharRects(chars, "ALL YOU", limit = 5).size)
        assertEquals(0, matchCharRects(chars, "missing", limit = 5).size)
    }

    @Test
    fun `字符框匹配中文与命中数量上限`() {
        val chars = charsOf("虚构推理虚构推理")
        val rects = matchCharRects(chars, "推理", limit = 5)
        assertEquals(2, rects.size)
        // 命中矩形是字符并集（第 3–4 个字），并带最小尺寸与留白。
        assertTrue(rects.first().left >= 0.02f)
        assertTrue(rects.first().right > rects.first().left)
        assertEquals(1, matchCharRects(chars, "推理", limit = 1).size)
    }

    @Test
    fun `单页命中把片段与矩形按顺序配对`() {
        val text = "虚构推理 是一部作品，虚构推理 有动画"
        val chars = charsOf("虚构推理 是一部作品，虚构推理 有动画")
        val hits = pageSearchHits(text, chars, pageIndex = 4, query = "虚构推理", maxHits = 6)
        assertEquals(2, hits.size)
        assertEquals(4, hits[0].pageIndex)
        assertEquals(1, hits[0].rects.size)
        assertEquals(1, hits[1].rects.size)
        hits.forEach { hit ->
            assertEquals(
                "虚构推理",
                hit.snippet.substring(
                    hit.matchStartInSnippet,
                    hit.matchStartInSnippet + hit.matchLength,
                ),
            )
        }
    }

    @Test
    fun `单页命中数量按上限截断`() {
        val text = "ab ab ab ab"
        val hits = pageSearchHits(text, charsOf(text), pageIndex = 0, query = "ab", maxHits = 2)
        assertEquals(2, hits.size)
    }

    @Test
    fun `同一页多次命中的片段位置互不相同`() {
        // 回归：W29 首轮真机在「一页里同一关键词出现两次」时崩溃（片段位置都指向窗口里第一处 →
        // LazyColumn 重复 key）。片段位置必须按原始下标精确映射。
        val text = "虚构推理 是一部作品，虚构推理 有动画，虚构推理 还有剧场版"
        val hits = pageSearchHits(text, charsOf(text), pageIndex = 0, query = "虚构推理", maxHits = 6)
        assertEquals(3, hits.size)
        assertEquals(hits.size, hits.map { it.matchStartInSnippet }.toSet().size)
        hits.forEach { hit ->
            assertEquals(
                "虚构推理",
                hit.snippet.substring(
                    hit.matchStartInSnippet,
                    hit.matchStartInSnippet + hit.matchLength,
                ),
            )
        }
    }

    @Test
    fun `换行处的命中位置按折叠映射`() {
        val text = "第一行\n\n虚构推理\n第二行"
        val at = text.indexOf("虚构推理")
        val window = buildSnippet(text, at, "虚构推理", context = 2)
        assertEquals(
            "虚构推理",
            window.text.substring(window.matchStart, window.matchStart + window.matchLength),
        )
    }

    @Test
    fun `流式引擎按页出命中并给出完成状态`() = runBlocking {
        val source =
            FakeTextSource(
                listOf(
                    "hello world 这是一段足够长的正文内容",
                    "nothing here 这一段里没有关键词",
                    "hello again 结尾再补一段足够长的正文",
                )
            )
        val events = mutableListOf<PdfSearchEvent>()
        PdfSearchEngine(source, pageCount = 3).search("hello") { events += it }
        val hits = events.filterIsInstance<PdfSearchEvent.HitFound>().map { it.hit }
        assertEquals(listOf(0, 2), hits.map { it.pageIndex })
        val finished = events.filterIsInstance<PdfSearchEvent.Finished>().single()
        assertEquals(3, finished.scannedPages)
        assertFalse(finished.truncated)
        assertTrue(finished.hasTextLayer)
    }

    @Test
    fun `流式引擎达到命中上限即提前结束`() = runBlocking {
        val source = FakeTextSource(List(10) { "hit" })
        val events = mutableListOf<PdfSearchEvent>()
        PdfSearchEngine(source, pageCount = 10, maxHits = 1).search("hit") { events += it }
        assertEquals(1, events.filterIsInstance<PdfSearchEvent.HitFound>().size)
        val finished = events.filterIsInstance<PdfSearchEvent.Finished>().single()
        assertTrue(finished.truncated)
        assertEquals(1, finished.scannedPages)
    }

    @Test
    fun `扫描件没有文本层时明确标记`() = runBlocking {
        val source = FakeTextSource(List(5) { "" })
        val events = mutableListOf<PdfSearchEvent>()
        PdfSearchEngine(source, pageCount = 5).search("任何") { events += it }
        val finished = events.filterIsInstance<PdfSearchEvent.Finished>().single()
        assertFalse(finished.hasTextLayer)
        assertEquals(0, events.filterIsInstance<PdfSearchEvent.HitFound>().size)
    }

    @Test
    fun `空关键词不扫描`() = runBlocking {
        val source = FakeTextSource(listOf("hit"))
        val events = mutableListOf<PdfSearchEvent>()
        PdfSearchEngine(source, pageCount = 1).search("   ") { events += it }
        assertEquals(1, events.size)
        assertTrue(events.single() is PdfSearchEvent.Finished)
        assertEquals(0, source.scannedPages)
    }

    @Test
    fun `取消扫描在下一次翻页边界生效`() = runBlocking {
        val hitCount = java.util.concurrent.atomic.AtomicInteger(0)
        lateinit var job: kotlinx.coroutines.Job
        val source =
            object : PdfPageTextSource {
                override suspend fun streamPageTexts(
                    onPage: (Int, String, List<PageChar>) -> Boolean
                ) {
                    if (!onPage(0, "hit", charsOf("hit"))) return
                    job.cancel()
                    onPage(1, "hit", charsOf("hit"))
                }

                override fun close() {}
            }
        job = launch {
            PdfSearchEngine(source, pageCount = 2).search("hit") { event ->
                if (event is PdfSearchEvent.HitFound) hitCount.incrementAndGet()
            }
        }
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(1, hitCount.get())
    }

    @Test
    fun `进度按步长上报`() = runBlocking {
        val source = FakeTextSource(List(30) { "内容" })
        val progress = mutableListOf<Int>()
        PdfSearchEngine(source, pageCount = 30).search("没有这个词") { event ->
            if (event is PdfSearchEvent.Progress) progress += event.scannedPages
        }
        assertTrue(progress.contains(PDF_SEARCH_PROGRESS_STEP))
    }
}

private class FakeTextSource(private val pages: List<String>) : PdfPageTextSource {
    var scannedPages: Int = 0
        private set

    override suspend fun streamPageTexts(
        onPage: (pageIndex: Int, text: String, chars: List<PageChar>) -> Boolean
    ) {
        pages.forEachIndexed { index, text ->
            scannedPages = index + 1
            if (!onPage(index, text, charsOf(text))) return
        }
    }

    override fun close() {}
}

/** 造一段横排字符框：每个字符占 1% 页宽、纵向固定在 10%–12%。 */
private fun charsOf(text: String): List<PageChar> = text.mapIndexed { index, char ->
    val left = 0.02f + index * 0.01f
    PageChar(char, PageRect(left, 0.10f, left + 0.008f, 0.12f).normalized())
}
