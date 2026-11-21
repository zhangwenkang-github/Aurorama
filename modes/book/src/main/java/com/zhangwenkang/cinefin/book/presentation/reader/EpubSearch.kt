package com.zhangwenkang.cinefin.book.presentation.reader

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * EPUB 书内搜索（W6-T10，READER-31）。
 *
 * 执行层直接用 Readium 3.4.0 自带的 `publication.search(query, options)` + `SearchIterator.next()`：
 * - `SearchIterator` 按资源顺序逐批返回 `LocatorCollection`，每条 `Locator` 自带 `text(before/highlight/after)`；
 * - **本文件只放纯数据转换**（可在 JVM 单测里跑）：把 Readium 的文本三元组裁成「前后各 30 字」的片段， 再把 `href` 归因到目录章节名，产出可展示的命中行；
 * - 跳转 = 整条 `Locator` JSON（与 T8 标注同一定位体系，禁像素坐标）；
 * - 有界：整本最多 [EPUB_SEARCH_MAX_HITS] 条，达到上限即停止翻页（UI 明确提示已达上限）。
 */

/** 命中片段前后保留的字符数（定稿七 §4：前后各 30 字）。 */
internal const val EPUB_SEARCH_CONTEXT_CHARS: Int = 30

/** 整本最多返回的命中条数。 */
internal const val EPUB_SEARCH_MAX_HITS: Int = 300

/** 搜索面板里片段的最大展示长度（片段本身已按 30 字裁剪，这里只防御超长资源）。 */
internal const val EPUB_SEARCH_SNIPPET_MAX_CHARS: Int = 200

private const val ELLIPSIS = "…"
private val WHITESPACE_RUN = Regex("\\s+")

/** 目录章节条目（[ReaderTocEntry] 的纯数据投影，避免纯逻辑层依赖 Compose 文件）。 */
data class EpubSearchChapter(val title: String, val href: String?)

/** 片段与命中在片段内的区间（[matchStart] / [matchLength] 以字符计）。 */
data class EpubSearchSnippet(val text: String, val matchStart: Int, val matchLength: Int)

/**
 * 一条 Readium 搜索定位的纯数据投影（由执行层从 `Locator` 抽取）。
 *
 * [before] / [highlight] / [after] 是 Readium 片段的三段；[progression] 取 `totalProgression`， 不可用时为
 * null（跳转仍以 [locatorJson] 为准）。
 */
data class EpubSearchLocator(
    val href: String,
    val locatorJson: String,
    val before: String = "",
    val highlight: String = "",
    val after: String = "",
    val progression: Double? = null,
)

/** 可展示的命中行：章节名 + 上下文片段 + 跳转 locator。 */
data class EpubSearchHit(
    val locatorJson: String,
    val chapterTitle: String,
    val chapterHref: String,
    /** spine 序（0-based；未知为 -1，导出 / 排序兜底用）。 */
    val spineIndex: Int,
    val snippet: String,
    val matchStartInSnippet: Int,
    val matchLength: Int,
    val progression: Double?,
)

/** 搜索面板状态（加载 / 空 / 错误三态由 [running] + [error] + [hits]/[finished] 推导）。 */
data class EpubSearchUiState(
    val query: String = "",
    val running: Boolean = false,
    val hits: List<EpubSearchHit> = emptyList(),
    /** 扫描已走完全书（或达到上限）。 */
    val finished: Boolean = false,
    /** 达到 [EPUB_SEARCH_MAX_HITS] 上限而提前停止。 */
    val truncated: Boolean = false,
    val error: String? = null,
)

/**
 * 片段裁剪：把 Readium 的三段文本折叠空白后，前面保留最后 [context] 字、后面保留最前 [context] 字。
 *
 * 首尾截断加省略号（与 PDF 搜索同一视觉口径）；[matchStart] 已包含省略号宽度，列表可直接按区间上色。
 */
internal fun epubSearchSnippet(
    before: String,
    highlight: String,
    after: String,
    context: Int = EPUB_SEARCH_CONTEXT_CHARS,
): EpubSearchSnippet {
    val safeContext = context.coerceAtLeast(0)
    val collapsedBefore = collapseWhitespace(before)
    val collapsedHighlight = collapseWhitespace(highlight)
    val collapsedAfter = collapseWhitespace(after)
    val beforeTrimmed = collapsedBefore.takeLast(safeContext)
    val afterTrimmed = collapsedAfter.take(safeContext)
    val prefix = if (beforeTrimmed.length < collapsedBefore.length) ELLIPSIS else ""
    val suffix = if (afterTrimmed.length < collapsedAfter.length) ELLIPSIS else ""
    val text = prefix + beforeTrimmed + collapsedHighlight + afterTrimmed + suffix
    return EpubSearchSnippet(
        text = text.take(EPUB_SEARCH_SNIPPET_MAX_CHARS),
        matchStart = prefix.length + beforeTrimmed.length,
        matchLength = collapsedHighlight.length,
    )
}

private fun collapseWhitespace(raw: String): String = raw.replace(WHITESPACE_RUN, " ").trim()

/** href 归一化：去掉 fragment（不同版本 EPUBCFI 前缀差异也按同一资源对齐）。 */
internal fun epubHrefKey(href: String?): String = href.orEmpty().substringBefore('#').trim()

/** 章节名归因：目录同 href 条目优先，其次「第 N 章」，都没有返回空串（UI 只显示片段）。 */
internal fun epubChapterTitle(
    href: String,
    chapters: List<EpubSearchChapter>,
    spineIndex: Int,
): String {
    val key = epubHrefKey(href)
    chapters
        .firstOrNull { epubHrefKey(it.href) == key }
        ?.title
        ?.takeIf { it.isNotBlank() }
        ?.let {
            return it
        }
    return if (spineIndex >= 0) "第 ${spineIndex + 1} 章" else ""
}

/** spine 序：`spineHrefs` 里同 href 的下标；未知为 -1。 */
internal fun epubSpineIndexOf(href: String, spineHrefs: List<String>): Int {
    val key = epubHrefKey(href)
    if (key.isEmpty()) return -1
    return spineHrefs.indexOfFirst { epubHrefKey(it) == key }
}

/**
 * Readium 定位 → 命中行（纯函数，执行层与单测共用）。
 *
 * 空 query / 空 highlight 的定位被丢弃（Readium 的正常结果一定有 highlight）； 超过 [maxHits] 的尾部结果直接截断，保证与
 * [EPUB_SEARCH_MAX_HITS] 口径一致。
 */
internal fun epubSearchHits(
    locators: List<EpubSearchLocator>,
    chapters: List<EpubSearchChapter>,
    spineHrefs: List<String>,
    maxHits: Int = EPUB_SEARCH_MAX_HITS,
): List<EpubSearchHit> {
    if (maxHits <= 0) return emptyList()
    val result = ArrayList<EpubSearchHit>(minOf(locators.size, maxHits))
    for (locator in locators) {
        if (result.size >= maxHits) break
        if (locator.locatorJson.isBlank()) continue
        if (locator.highlight.isBlank()) continue
        val spineIndex = epubSpineIndexOf(locator.href, spineHrefs)
        val snippet = epubSearchSnippet(locator.before, locator.highlight, locator.after)
        result +=
            EpubSearchHit(
                locatorJson = locator.locatorJson,
                chapterTitle = epubChapterTitle(locator.href, chapters, spineIndex),
                chapterHref = locator.href,
                spineIndex = spineIndex,
                snippet = snippet.text,
                matchStartInSnippet = snippet.matchStart,
                matchLength = snippet.matchLength,
                progression = locator.progression?.takeIf { it.isFinite() },
            )
    }
    return result
}

/** 状态行文案（空态 / 扫描中 / 上限 / 错误 / 无结果），同时充当真机文本证据。 */
internal fun epubSearchStatusText(state: EpubSearchUiState): String {
    val error = state.error
    if (error != null) return "搜索失败：$error"
    if (state.query.isBlank()) return "输入关键词后从全书搜索"
    if (state.running) {
        return "搜索中 · 命中 ${state.hits.size} 条${if (state.truncated) "（已达上限）" else ""}"
    }
    if (state.truncated) return "命中已达上限 ${EPUB_SEARCH_MAX_HITS} 条，只显示前 ${state.hits.size} 条"
    if (state.finished) {
        return if (state.hits.isEmpty()) "没有找到「${state.query}」"
        else "搜索完成 · 共 ${state.hits.size} 条命中"
    }
    return "等待输入"
}

/** 空命中区提示（与三态文案互补，避免重复）。 */
internal fun epubSearchEmptyHint(state: EpubSearchUiState): String =
    when {
        state.query.isBlank() -> "搜索只读本地已下载 / 已导入的 EPUB；不向服务器发送任何请求。"
        state.running -> "正在搜索…已命中的结果会先出现在这里。"
        state.error != null -> "可以修改关键词重试，或关闭面板继续阅读。"
        state.finished -> "换一个关键词试试；命中结果按书籍顺序排列。"
        else -> "等待输入。"
    }

/**
 * `positions` 缺失时的兜底跳转进度：命中 launcher 里没有 progression 时用 0 起（不阻断跳转）。
 *
 * `totalProgression` 优先；退化为资源内 `progression` 会造成跨书错位，所以只接受 total。
 */
internal fun epubSearchJumpProgression(hit: EpubSearchHit, fallback: Double): Double =
    hit.progression?.coerceIn(0.0, 1.0) ?: fallback.coerceIn(0.0, 1.0)

/**
 * locator JSON → progression（`totalProgression` 优先）。
 *
 * 与 `epubAnnotationProgression` 同一口径，独立一份是为了在纯逻辑层解析「执行层拿到的 locator JSON」 而不依赖 Readium 类型；解析失败返回
 * null。
 */
internal fun epubSearchProgressionFromLocator(locatorJson: String): Double? {
    val locations =
        runCatching {
            Json.parseToJsonElement(locatorJson).jsonObject["locations"]?.jsonObject
        }
            .getOrNull() ?: return null
    val value = locations["totalProgression"] ?: locations["progression"] ?: return null
    return runCatching { value.jsonPrimitive.doubleOrNull }.getOrNull()
}

/** 命中条目的稳定 key（同片段位置不重排；LazyColumn 重复 key 会崩，沿用 PDF 搜索的教训）。 */
internal fun epubSearchHitKey(index: Int, hit: EpubSearchHit): String =
    "$index-${hit.chapterHref}-${hit.matchStartInSnippet}-${hit.matchLength}"

/** 当前命中集合是否按 spine 升序（执行层按资源顺序扫描，天然升序；测试守这条不变量）。 */
internal fun List<EpubSearchHit>.isInReadingOrder(): Boolean =
    zipWithNext().all { (a, b) ->
        val left = if (a.spineIndex < 0) Int.MAX_VALUE else a.spineIndex
        val right = if (b.spineIndex < 0) Int.MAX_VALUE else b.spineIndex
        left <= right
    }

/** `spine` 链接列表 → href 列表（`spineHrefs` 入参的构造辅助，纯 JVM）。 */
internal fun epubSpineHrefs(readingOrderHrefs: List<String>): List<String> = readingOrderHrefs.map {
    epubHrefKey(it)
}

/** 供执行层解析 `Locator.locations.totalProgression` 之外的整数字段时复用（position 等）。 */
internal fun epubLocationLongOrNull(locatorJson: String, field: String): Long? {
    val locations =
        runCatching {
            Json.parseToJsonElement(locatorJson).jsonObject["locations"]?.jsonObject
        }
            .getOrNull() ?: return null
    return runCatching { locations[field]?.jsonPrimitive?.longOrNull }.getOrNull()
}
