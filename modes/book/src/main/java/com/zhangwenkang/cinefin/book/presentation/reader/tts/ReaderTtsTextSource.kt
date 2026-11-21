package com.zhangwenkang.cinefin.book.presentation.reader.tts

import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.content.Content
import org.readium.r2.shared.publication.services.content.content
import timber.log.Timber

/** 句 → locator 的前后文消歧窗口（字符）：重复短句（「是的。」）靠前后文落到同一处。 */
private const val TTS_LOCATOR_CONTEXT_CHARS = 40

/**
 * EPUB 朗读文本源（W6 T9，定稿六 §1）：按 spine 资源逐个提取纯文本并切句。
 *
 * 句 → Readium locator 的映射走 T8-spike 确认的通路：`publication.content(资源 locator)` 拿 `TextElement`，每段
 * `Segment` 自带 locator；句首 offset 落在哪个 segment 就复用该 segment 的 locator（href + locations），再把本句塞进
 * `Locator.Text(highlight)`，前后各 40 字作为消歧上下文。
 *
 * 映射失败（资源无文本层 / segment 缺失）的句子 `locatorJson = null`：朗读照常，高亮降级（R5 观察项）， 每章结束打一条日志汇总。
 */
@OptIn(ExperimentalReadiumApi::class)
internal class ReaderTtsTextSource(
    private val publication: Publication,
    /** 目录标题解析（找不到就由调用方给「第 N 章」）。 */
    private val chapterTitle: (href: String) -> String,
) {
    val chapterCount: Int
        get() = publication.readingOrder.size

    /** 章节 href（用于「当前位置 → 章」与 UI 展示）；越界返回 null。 */
    fun chapterHref(index: Int): String? =
        publication.readingOrder.getOrNull(index)?.href?.toString()?.substringBefore('#')

    /** href → spine 序号（「当前位置 / 章首」起点解析）；找不到返回 null。 */
    fun chapterIndexOfHref(href: String): Int? {
        val target = href.substringBefore('#')
        return publication.readingOrder
            .indexOfFirst { it.href.toString().substringBefore('#') == target }
            .takeIf { it >= 0 }
    }

    /** 解析一章；越界 / 无文本返回空句章（控制器会跳过）。 */
    suspend fun loadChapter(index: Int): TtsChapter? {
        val link = publication.readingOrder.getOrNull(index) ?: return null
        val href = link.href.toString().substringBefore('#')
        val title = chapterTitle(href).ifBlank { "第 ${index + 1} 章" }
        val start = runCatching { publication.manifest.locatorFromLink(link) }.getOrNull()
        val content =
            start?.let { runCatching { publication.content(it) }.getOrNull() }
                ?: return TtsChapter(index, title, href, emptyList())
        val sentences = mutableListOf<TtsSentence>()
        var mappingFailures = 0
        try {
            val iterator = content.iterator()
            while (iterator.hasNext()) {
                val element = iterator.next()
                if (element.locator.href.toString().substringBefore('#') != href) break
                if (element !is Content.TextElement) continue
                if (element.role is Content.TextElement.Role.Footnote) continue
                val segments = element.segments
                if (segments.isEmpty()) continue
                val blockText = segments.joinToString(separator = "") { it.text }
                if (blockText.isBlank()) continue
                val segmentLengths = segments.map { it.text.length }
                TtsSentenceSplitter.split(blockText).forEach { slice ->
                    val segmentIndex = segmentIndexForOffset(slice.start, segmentLengths)
                    val segment = segmentIndex?.let(segments::getOrNull)
                    val locator = segment?.let {
                        sentenceLocator(publication, it, blockText, slice)
                    }
                    if (locator == null) mappingFailures++
                    sentences +=
                        TtsSentence(
                            chapterIndex = index,
                            index = sentences.size,
                            text = slice.text,
                            href = href,
                            locatorJson =
                                locator?.let { runCatching { it.toJSON().toString() }.getOrNull() },
                            progression =
                                locator?.locations?.totalProgression
                                    ?: locator?.locations?.progression,
                        )
                }
            }
        } catch (error: Exception) {
            Timber.w(error, "TTS 章节文本提取中断（第 ${index + 1} 章）")
        }
        if (mappingFailures > 0) {
            Timber.w(
                "TTS 句→locator 映射失败 ${mappingFailures}/${sentences.size} 句（第 ${index + 1} 章，降级为只读不高亮）"
            )
        }
        return TtsChapter(index, title, href, sentences)
    }
}

/** 句首 offset 落在哪个 segment；offset 越界时退到最后一个 segment。 */
internal fun segmentIndexForOffset(offset: Int, segmentLengths: List<Int>): Int? {
    if (segmentLengths.isEmpty()) return null
    var cursor = 0
    segmentLengths.forEachIndexed { index, length ->
        if (offset < cursor + length) return index
        cursor += length
    }
    return segmentLengths.lastIndex
}

/** 用句所属 segment 的 locator + 本句文本 / 前后文，拼出可渲染 Decoration 的定位。 */
@OptIn(ExperimentalReadiumApi::class)
private fun sentenceLocator(
    publication: Publication,
    segment: Content.TextElement.Segment,
    blockText: String,
    slice: TtsSentenceSlice,
): Locator? = runCatching {
    val before =
        blockText
            .substring(
                (slice.start - TTS_LOCATOR_CONTEXT_CHARS).coerceAtLeast(0),
                slice.start,
            )
            .trim()
    val after =
        blockText
            .substring(
                slice.end,
                (slice.end + TTS_LOCATOR_CONTEXT_CHARS).coerceAtMost(blockText.length),
            )
            .trim()
    segment.locator.copy(
        text = Locator.Text(before = before, highlight = slice.text, after = after)
    )
}
    .getOrNull()
