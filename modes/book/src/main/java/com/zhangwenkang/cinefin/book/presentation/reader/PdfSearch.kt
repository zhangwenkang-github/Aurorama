package com.zhangwenkang.cinefin.book.presentation.reader

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive

/**
 * PDF 文本层搜索（W29-READER）。
 *
 * 设计口径（READER_PLAN §2 D22）：
 * - **流式单遍**：一次搜索 = PDF 文本层从头到尾扫一遍，逐页出结果（懒加载），不预先建索引；
 * - **可取消**：换关键词 / 离开阅读页会取消上一条扫描，取消在下一次翻页边界生效；
 * - **有界**：单页最多 [PDF_SEARCH_MAX_HITS_PER_PAGE] 条、整篇最多 [PDF_SEARCH_MAX_HITS] 条， 达到上限即停止扫描（UI
 *   显示「已达上限」），避免 3649 页文档把结果列表撑爆；
 * - **矩形**：命中页同时给出「页面归一化矩形」（[PageRect]，左上原点、0–1），供页面叠加高亮；
 * - **无文本层**：整篇有效字符少于 [PDF_SEARCH_MIN_TEXT_CHARS] 时判定为没有文本层（扫描件）， UI 明确提示（此时矩形批注仍然可用，批注不依赖文本层）。
 *
 * 本文件只放纯 Kotlin 逻辑（可在 JVM 单测里直接跑）；PdfBox 适配在 [PdfBoxPageTextSource]。
 */

/** 整篇最多返回的命中条数。 */
internal const val PDF_SEARCH_MAX_HITS: Int = 400

/** 同一页最多返回的命中条数。 */
internal const val PDF_SEARCH_MAX_HITS_PER_PAGE: Int = 6

/** 命中片段前后保留的字符数。 */
internal const val PDF_SEARCH_SNIPPET_CONTEXT: Int = 24

/** 扫描进度上报步长（页）：结果列表按步长节流刷新，保证长文档滚动流畅。 */
internal const val PDF_SEARCH_PROGRESS_STEP: Int = 25

/** 判定「有文本层」的最小有效字符数。 */
internal const val PDF_SEARCH_MIN_TEXT_CHARS: Int = 40

/** 关键词归一化：去首尾空白 + 空白折叠（CJK 无空格、拉丁词多空格都能命中）。 */
internal fun normalizeSearchQuery(raw: String): String = raw.trim().replace(Regex("\\s+"), " ")

/**
 * 页面归一化矩形：左上原点，(0,0) = 页面左上角，1 = 页宽 / 页高。
 *
 * 页面框以「渲染方向」为准（与 PdfRenderer 的 `Page.getWidth()/getHeight()` 一致）：批注与搜索矩形 都按它存储，所以分页 / 双栏 / RTL /
 * 滚动四种排版共用同一份锚点（D22 兼容边界）。
 */
data class PageRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float
        get() = right - left

    val height: Float
        get() = bottom - top

    fun union(other: PageRect): PageRect =
        PageRect(
            minOf(left, other.left),
            minOf(top, other.top),
            maxOf(right, other.right),
            maxOf(bottom, other.bottom),
        )

    /** 夹取到 0–1；非有限值回退为 0（保存 / 加载越界数据时不炸）。 */
    fun normalized(): PageRect {
        val rawLeft = finiteOrZero(left)
        val rawRight = finiteOrZero(right)
        val rawTop = finiteOrZero(top)
        val rawBottom = finiteOrZero(bottom)
        val left = minOf(rawLeft, rawRight).coerceIn(0f, 1f)
        val right = maxOf(rawLeft, rawRight).coerceIn(0f, 1f)
        val top = minOf(rawTop, rawBottom).coerceIn(0f, 1f)
        val bottom = maxOf(rawTop, rawBottom).coerceIn(0f, 1f)
        return PageRect(left, top, right, bottom)
    }

    /** 四向扩展（用于命中高亮的视觉留白），再夹取到页面内。 */
    fun expanded(horizontal: Float, vertical: Float): PageRect =
        PageRect(left - horizontal, top - vertical, right + horizontal, bottom + vertical)
            .normalized()

    /** 最小可点面积：过细的框（连字符、零宽字形）不会被点中 / 看不到。 */
    fun withMinSize(minWidth: Float = 0.006f, minHeight: Float = 0.008f): PageRect {
        val grown =
            if (width >= minWidth && height >= minHeight) this
            else {
                val centerX = (left + right) / 2f
                val centerY = (top + bottom) / 2f
                val halfWidth = maxOf(width, minWidth) / 2f
                val halfHeight = maxOf(height, minHeight) / 2f
                PageRect(
                    centerX - halfWidth,
                    centerY - halfHeight,
                    centerX + halfWidth,
                    centerY + halfHeight,
                )
            }
        return grown.normalized()
    }

    fun contains(x: Float, y: Float): Boolean = x in left..right && y in top..bottom
}

private fun finiteOrZero(value: Float): Float = if (value.isFinite()) value else 0f

/** 页面内的一个字符及其页面归一化矩形（由 PdfBox 的 TextPosition 换算而来）。 */
internal data class PageChar(val char: Char, val rect: PageRect)

/** 命中：页码（0-based）+ 上下文片段 + 片段内命中区间 + 页内矩形列表。 */
data class PdfSearchHit(
    val pageIndex: Int,
    val snippet: String,
    val matchStartInSnippet: Int,
    val matchLength: Int,
    val rects: List<PageRect>,
)

/** 片段窗口与命中在窗口内的位置。 */
internal data class SnippetWindow(val text: String, val matchStart: Int, val matchLength: Int)

private val WHITESPACE_RUN = Regex("\\s+")
private const val ELLIPSIS = "…"

/** 命中上下文片段：取命中前后各 [context] 个字符，把换行 / 连续空白折叠为单个空格， 首尾截断时加省略号；命中位置按折叠后的文本重新定位（换行命中也能标对）。 */
internal fun buildSnippet(
    pageText: String,
    matchStart: Int,
    query: String,
    context: Int = PDF_SEARCH_SNIPPET_CONTEXT,
): SnippetWindow {
    if (pageText.isEmpty()) return SnippetWindow("", 0, 0)
    val start = (matchStart - context).coerceIn(0, pageText.length)
    val end = (matchStart + query.length + context).coerceIn(start, pageText.length)
    val rawWindow = pageText.substring(start, end)
    val collapsed = rawWindow.replace(WHITESPACE_RUN, " ")
    val collapsedQuery = query.replace(WHITESPACE_RUN, " ")
    val prefix = if (start > 0) ELLIPSIS else ""
    val suffix = if (end < pageText.length) ELLIPSIS else ""
    val at = collapsed.indexOf(collapsedQuery, ignoreCase = true)
    val matchStartInSnippet =
        if (at >= 0) prefix.length + at
        else (prefix.length + collapsed.length / 2).coerceAtMost((prefix + collapsed).length)
    val matchLength = if (at >= 0) collapsedQuery.length else 0
    return SnippetWindow(prefix + collapsed + suffix, matchStartInSnippet, matchLength)
}

/**
 * 字符框命中：按去掉空白后的字符序列做大小写不敏感匹配，返回每处命中的并集矩形。
 *
 * 与片段匹配的差别：这里是「压缩文本」口径（忽略换行 / 空格），所以"分两行印出的同一个词"也能标出来； 位置按命中序号与片段一一对应（见 [pageSearchHits]）。
 */
internal fun matchCharRects(
    chars: List<PageChar>,
    query: String,
    limit: Int,
    padding: Float = 0.003f,
): List<PageRect> {
    val compactQuery = query.filterNot { it.isWhitespace() }.lowercase()
    if (compactQuery.isEmpty() || limit <= 0) return emptyList()
    val compact = chars.filterNot { it.char.isWhitespace() }
    if (compact.size < compactQuery.length) return emptyList()
    val rects = ArrayList<PageRect>(minOf(limit, 8))
    var index = 0
    while (index <= compact.size - compactQuery.length && rects.size < limit) {
        val at = indexOfIgnoreCase(compact, compactQuery, index)
        if (at < 0) break
        var rect = compact[at].rect
        for (offset in 1 until compactQuery.length) {
            rect = rect.union(compact[at + offset].rect)
        }
        rects += rect.withMinSize().expanded(padding, padding)
        index = at + maxOf(compactQuery.length, 1)
    }
    return rects
}

private fun indexOfIgnoreCase(chars: List<PageChar>, needle: String, fromIndex: Int): Int {
    val last = chars.size - needle.length
    var index = fromIndex
    while (index <= last) {
        var matched = true
        for (offset in needle.indices) {
            val hay = chars[index + offset].char.lowercaseChar()
            if (hay != needle[offset]) {
                matched = false
                break
            }
        }
        if (matched) return index
        index++
    }
    return -1
}

/** 单页命中：片段从页面文本（含换行）取，矩形从字符框取，二者按顺序配对。 */
internal fun pageSearchHits(
    pageText: String,
    pageChars: List<PageChar>,
    pageIndex: Int,
    query: String,
    maxHits: Int,
): List<PdfSearchHit> {
    if (query.isEmpty() || maxHits <= 0) return emptyList()
    val rects = matchCharRects(pageChars, query, maxHits)
    val hits = ArrayList<PdfSearchHit>(minOf(maxHits, rects.size.coerceAtLeast(1)))
    var from = 0
    while (hits.size < maxHits) {
        val at = pageText.indexOf(query, from, ignoreCase = true)
        if (at < 0) break
        val window = buildSnippet(pageText, at, query)
        hits +=
            PdfSearchHit(
                pageIndex = pageIndex,
                snippet = window.text,
                matchStartInSnippet = window.matchStart,
                matchLength = window.matchLength,
                rects = listOfNotNull(rects.getOrNull(hits.size)),
            )
        from = at + query.length
    }
    return hits
}

/** 搜索事件：进度 / 命中 / 完成（UI 按事件增量刷新，不做整表替换）。 */
internal sealed interface PdfSearchEvent {
    data class Progress(val scannedPages: Int, val pageCount: Int) : PdfSearchEvent

    data class HitFound(val hit: PdfSearchHit) : PdfSearchEvent

    data class Finished(
        val scannedPages: Int,
        val pageCount: Int,
        val truncated: Boolean,
        val hasTextLayer: Boolean,
    ) : PdfSearchEvent
}

/** 文本层数据源：逐页流式给出页面文本与字符框。 */
internal interface PdfPageTextSource : AutoCloseable {
    /**
     * 从头到尾顺序抽取每一页；页码从 0 连续递增（没有内容流的页也会以空文本上报）。
     *
     * 回调返回 false 表示调用方要求提前结束；实现可抛 [CancellationException] 中止。
     */
    suspend fun streamPageTexts(
        onPage: (pageIndex: Int, text: String, chars: List<PageChar>) -> Boolean
    )
}

/** 流式搜索执行器：不持有文档，只负责「扫一遍 + 出事件」的纯逻辑（单测用假源即可覆盖）。 */
internal class PdfSearchEngine(
    private val source: PdfPageTextSource,
    private val pageCount: Int,
    private val maxHits: Int = PDF_SEARCH_MAX_HITS,
    private val maxHitsPerPage: Int = PDF_SEARCH_MAX_HITS_PER_PAGE,
) {

    suspend fun search(query: String, onEvent: (PdfSearchEvent) -> Unit) {
        val normalized = normalizeSearchQuery(query)
        if (normalized.isEmpty() || pageCount <= 0) {
            onEvent(PdfSearchEvent.Finished(0, pageCount, truncated = false, hasTextLayer = false))
            return
        }
        val context = currentCoroutineContext()
        var scannedPages = 0
        var hits = 0
        var textChars = 0L
        var truncated = false
        var lastProgress = 0
        source.streamPageTexts { pageIndex, text, chars ->
            if (!context.isActive) throw CancellationException("PDF 搜索已取消")
            scannedPages = pageIndex + 1
            textChars += text.count { !it.isWhitespace() }
            if (hits < maxHits) {
                val remaining = maxHits - hits
                val pageHits =
                    pageSearchHits(
                        pageText = text,
                        pageChars = chars,
                        pageIndex = pageIndex,
                        query = normalized,
                        maxHits = minOf(maxHitsPerPage, remaining),
                    )
                pageHits.forEach { hit ->
                    hits++
                    onEvent(PdfSearchEvent.HitFound(hit))
                }
            }
            if (hits >= maxHits) {
                truncated = pageIndex + 1 < pageCount
                return@streamPageTexts false
            }
            if (scannedPages - lastProgress >= PDF_SEARCH_PROGRESS_STEP) {
                lastProgress = scannedPages
                onEvent(PdfSearchEvent.Progress(scannedPages, pageCount))
            }
            true
        }
        onEvent(
            PdfSearchEvent.Finished(
                scannedPages = if (truncated) scannedPages else pageCount,
                pageCount = pageCount,
                truncated = truncated,
                hasTextLayer = textChars >= PDF_SEARCH_MIN_TEXT_CHARS,
            )
        )
    }
}
