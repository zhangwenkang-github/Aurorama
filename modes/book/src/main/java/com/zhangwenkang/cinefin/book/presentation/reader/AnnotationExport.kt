package com.zhangwenkang.cinefin.book.presentation.reader

import java.util.Locale

/**
 * 整书标注 Markdown 导出（W6-T10，READER-30）。
 *
 * 定稿七 §5 的文件结构：
 * - 头部：书名 / 作者 / 导出时阅读进度百分比；
 * - 正文按**章节树**分组（`spineIndex` 升序，未知排最后；同章按创建时间）；
 * - 每条标注 = 引文（blockquote）+ 笔记（可选）+ 高亮色（具名枚举 id）。
 *
 * 本文件是纯 JVM（无 Android / Readium 依赖），单测用 fixture 标注直接跑；写文件与分享 Intent 在阅读页。
 */

/** 导出文档的头部与数据源。 */
data class AnnotationExportDocument(
    val bookTitle: String,
    /** 作者；空串 = 服务器 / 本地书都没有作者信息，导出行省略。 */
    val bookAuthor: String = "",
    /** 导出时的阅读进度（0–100）；null = 还没开始读（省略进度行）。 */
    val progressPercent: Double? = null,
    /** 导出时间文案（由调用方按本地时区格式化；空串 = 省略该行，便于单测快照稳定）。 */
    val exportedAtText: String = "",
    val annotations: List<BookAnnotation> = emptyList(),
)

private const val EXPORT_EMPTY_PLACEHOLDER = "（暂无标注）"

/** 头部进度：0–1 的 progression → 0–100 保留一位小数；越界夹取，NaN 视为无进度。 */
internal fun annotationExportProgressPercent(progression: Double?): Double? {
    val value = progression ?: return null
    if (!value.isFinite()) return null
    return (value.coerceIn(0.0, 1.0) * 1000).let { Math.round(it) / 10.0 }
}

/** 百分比文案：`42.5%`；整数不补 `.0`（`100%`）。 */
internal fun annotationExportPercentText(percent: Double?): String {
    val value = percent ?: return ""
    val text =
        if (value % 1.0 == 0.0) value.toInt().toString() else "%.1f".format(Locale.ROOT, value)
    return "$text%"
}

/**
 * 生成 Markdown 正文（纯函数）。
 *
 * 章节分组键 = `spineIndex`（-1 归到末尾）；标题取标注里第一个非空 `chapterTitle`， 缺标题时用「第 N 章」/「未标注章节」，保证每组都有可读标题。
 */
internal fun buildAnnotationMarkdown(doc: AnnotationExportDocument): String {
    val title = doc.bookTitle.trim().ifBlank { "未命名书籍" }
    val builder = StringBuilder()
    builder.append("# ").append(title.replace(Regex("\\s+"), " ")).append('\n').append('\n')
    if (doc.bookAuthor.isNotBlank()) {
        builder.append("- 作者：").append(doc.bookAuthor.trim()).append('\n')
    }
    annotationExportPercentText(doc.progressPercent)
        .takeIf { it.isNotEmpty() }
        ?.let { percent -> builder.append("- 阅读进度：").append(percent).append('\n') }
    if (doc.exportedAtText.isNotBlank()) {
        builder.append("- 导出时间：").append(doc.exportedAtText.trim()).append('\n')
    }
    builder.append("- 标注数：").append(doc.annotations.size).append('\n')

    if (doc.annotations.isEmpty()) {
        builder.append('\n').append(EXPORT_EMPTY_PLACEHOLDER).append('\n')
        return builder.toString()
    }

    val groups = doc.annotations.sortedForReading().groupBy { it.chapterGroupKey() }
    val orderedKeys =
        groups.keys.sortedWith(
            compareBy<ChapterGroupKey> { if (it.spineIndex < 0) Int.MAX_VALUE else it.spineIndex }
                .thenBy { it.title }
        )
    for (key in orderedKeys) {
        val annotations = groups.getValue(key)
        builder.append('\n')
        builder.append("## ").append(key.title).append('\n')
        for (annotation in annotations) {
            builder.append('\n')
            appendQuote(builder, annotation.selectedText.ifBlank { annotation.noteText })
            annotation.noteText
                .takeIf { it.isNotBlank() && annotation.selectedText.isNotBlank() }
                ?.let { appendNote(builder, it) }
            builder.append("- 高亮：").append(annotation.highlightColor.id).append('\n')
        }
    }
    return builder.toString()
}

private data class ChapterGroupKey(val spineIndex: Int, val title: String)

private fun BookAnnotation.chapterGroupKey(): ChapterGroupKey =
    ChapterGroupKey(spineIndex, chapterGroupTitle())

private fun BookAnnotation.chapterGroupTitle(): String =
    chapterTitle.trim().ifBlank { if (spineIndex >= 0) "第 ${spineIndex + 1} 章" else "未标注章节" }

/** 引文：多行原文逐行加 `>` 前缀，保持 Markdown 引用块不断行；块与后续列表之间留一空行。 */
private fun appendQuote(builder: StringBuilder, raw: String) {
    val text = raw.trim().ifBlank { "（空引文）" }
    text
        .lineSequence()
        .filter { it.isNotBlank() }
        .forEach { line -> builder.append("> ").append(line.trim()).append('\n') }
    builder.append('\n')
}

/** 笔记：多行缩进到列表项内（4 空格），避免打断后续列表。 */
private fun appendNote(builder: StringBuilder, raw: String) {
    val lines = raw.trim().lines().filter { it.isNotBlank() }
    if (lines.isEmpty()) return
    builder.append("- 笔记：").append(lines.first().trim()).append('\n')
    lines.drop(1).forEach { line -> builder.append("  ").append(line.trim()).append('\n') }
}

/** 导出文件名：书名清洗（文件系统非法字符 → `_`），空名回落 `annotations`，固定 `.md` 后缀。 */
internal fun annotationExportFileName(bookTitle: String): String {
    val cleaned =
        bookTitle
            .trim()
            .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
            .replace(Regex("\\s+"), " ")
    val base = cleaned.take(60).ifBlank { "annotations" }
    return "$base.md"
}

/** 分享用的标题（Intent chooser 的 subject）。 */
internal fun annotationExportShareSubject(bookTitle: String): String =
    "${bookTitle.trim().ifBlank { "书籍" }} · 标注"
