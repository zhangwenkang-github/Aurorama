package com.zhangwenkang.cinefin.book.presentation.reader

import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * EPUB 划词高亮 / 笔记（W6-T8，READER-23–29）。
 *
 * 与 PDF 矩形批注（[ReaderAnnotation]）并存不合并，两者定位语义完全不同：
 * - 定位 = 整条 Readium `Locator` 的 JSON（Readium 3.x 无 CFI 概念），其中 `text`（before/highlight/after）负责文本锚定，
 *   `locations.cssSelector`（放 `otherLocations`）负责把文本查找限定在选区起点所在的元素里，降低重复短句错位概率；
 * - 只落本地：`filesDir/reader/epub_annotations/{itemId}.json`，一个 itemId 一个文件，不写服务器；
 * - **禁止像素坐标**（READER-28）：字号 / 行距 / 分页重排后高亮由 Readium 按 locator 重新定位。
 */
internal const val EPUB_ANNOTATION_FORMAT_VERSION: Int = 1

/** 选中文本入档上限（定稿五 §1：列表展示与导出只用前 200 字）。 */
internal const val EPUB_ANNOTATION_SELECTED_TEXT_MAX_CHARS: Int = 200

/** 笔记上限（与 PDF 批注同一口径）。 */
internal const val EPUB_ANNOTATION_NOTE_MAX_CHARS: Int = 500

/** 列表行摘要长度。 */
internal const val EPUB_ANNOTATION_SUMMARY_CHARS: Int = 40

/** Decoration 分组名：整组替换（改色 / 删除 = 重算整表再下发）。 */
internal const val EPUB_ANNOTATION_DECORATION_GROUP: String = "epub-annotations"

/** 高亮底色透明度（定稿五 §3：模板 alpha 0.28）。 */
internal const val EPUB_HIGHLIGHT_ALPHA: Double = 0.28

/** 高亮圆角（定稿五 §3：cornerRadius 2dp，spike 已证实原生支持）。 */
internal const val EPUB_HIGHLIGHT_CORNER_RADIUS: Int = 2

/** 高亮线宽（Readium 模板参数；高亮样式不画线，保持 2 与设计口径一致）。 */
internal const val EPUB_HIGHLIGHT_LINE_WEIGHT: Int = 2

/**
 * 高亮颜色：**具名枚举**持久化（READER-25，禁止颜色序数）。
 *
 * ARGB 只在本文件定义一次，渲染与 UI 色块共用；透明度统一由 Readium 模板的 [EPUB_HIGHLIGHT_ALPHA] 施加， 所以这里给不透明色。
 */
enum class HighlightColor(val id: String, val argb: Long) {
    Yellow("yellow", 0xFFFFD54F),
    Blue("blue", 0xFF64B5F6),
    Pink("pink", 0xFFF48FB1),
    Green("green", 0xFF81C784);

    companion object {
        fun fromId(raw: String?): HighlightColor? {
            val value = raw?.trim()?.lowercase() ?: return null
            return entries.firstOrNull { it.id == value }
        }
    }
}

/** 一条 EPUB 高亮 / 笔记。 */
data class BookAnnotation(
    val id: String,
    val bookId: String,
    /** 章节资源地址（相对 href，与 locator 的 href 同源）。 */
    val chapterHref: String,
    /** 整条 Readium `Locator` JSON（含 text 与 locations.cssSelector）。 */
    val locatorJson: String,
    val highlightColor: HighlightColor,
    val noteText: String,
    /** 选中的原文（≤200 字，列表 / 导出用；不是定位依据）。 */
    val selectedText: String,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    /** READER-29：导出所需章节树字段（按 spine 顺序）。 */
    val chapterTitle: String = "",
    val spineIndex: Int = -1,
) {
    val label: String
        get() {
            val chapter = chapterTitle.ifBlank {
                if (spineIndex >= 0) "第 ${spineIndex + 1} 章" else ""
            }
            val body = selectedText.ifBlank { noteText.ifBlank { "高亮" } }
            return if (chapter.isBlank()) body else "$chapter · $body"
        }

    val summary: String
        get() = epubAnnotationSummary(selectedText.ifBlank { noteText })
}

/** 折叠空白并截断（列表 / 导出摘要）。 */
internal fun epubAnnotationSummary(
    text: String,
    maxChars: Int = EPUB_ANNOTATION_SUMMARY_CHARS,
): String {
    val collapsed = text.trim().replace(Regex("\\s+"), " ")
    if (collapsed.length <= maxChars) return collapsed
    return collapsed.take(maxChars) + "…"
}

/** 选中文本清洗：折叠换行 / 连续空白，截断到 200 字。 */
internal fun sanitizeEpubSelectedText(text: String): String =
    text.trim().replace(Regex("\\s+"), " ").take(EPUB_ANNOTATION_SELECTED_TEXT_MAX_CHARS)

/** 笔记清洗：折叠首尾空白、限长。 */
internal fun sanitizeEpubNote(note: String): String =
    note.trim().take(EPUB_ANNOTATION_NOTE_MAX_CHARS)

/** 新建一条标注（id / 时间戳在这一层生成，单测可注入固定值）。 */
internal fun newBookAnnotation(
    bookId: String,
    chapterHref: String,
    locatorJson: String,
    highlightColor: HighlightColor,
    noteText: String = "",
    selectedText: String = "",
    chapterTitle: String = "",
    spineIndex: Int = -1,
    nowMs: Long = System.currentTimeMillis(),
    id: String = UUID.randomUUID().toString(),
): BookAnnotation =
    BookAnnotation(
        id = id,
        bookId = bookId,
        chapterHref = chapterHref,
        locatorJson = locatorJson,
        highlightColor = highlightColor,
        noteText = sanitizeEpubNote(noteText),
        selectedText = sanitizeEpubSelectedText(selectedText),
        createdAtMs = nowMs,
        updatedAtMs = nowMs,
        chapterTitle = chapterTitle.trim(),
        spineIndex = spineIndex.coerceAtLeast(-1),
    )

/** 改色（不动定位与笔记）。 */
internal fun BookAnnotation.withHighlightColor(
    color: HighlightColor,
    nowMs: Long = System.currentTimeMillis(),
): BookAnnotation = copy(highlightColor = color, updatedAtMs = nowMs)

/** 改笔记。 */
internal fun BookAnnotation.withNote(
    note: String,
    nowMs: Long = System.currentTimeMillis(),
): BookAnnotation = copy(noteText = sanitizeEpubNote(note), updatedAtMs = nowMs)

/**
 * 从 locator JSON 里读 progression（`totalProgression` 优先）。
 *
 * 仅供跳转通道填一个兜底值——Readium 跳转以整条 locator 为准；解析失败返回 null。
 */
internal fun epubAnnotationProgression(locatorJson: String): Double? {
    val locations =
        runCatching {
            Json.parseToJsonElement(locatorJson).jsonObject["locations"]?.jsonObject
        }
            .getOrNull() ?: return null
    val value = locations["totalProgression"] ?: locations["progression"] ?: return null
    return runCatching { value.jsonPrimitive.doubleOrNull }.getOrNull()
}

/** 阅读顺序：spine 序（未知排最后）→ 创建时间 → id。 */
internal fun List<BookAnnotation>.sortedForReading(): List<BookAnnotation> =
    sortedWith(
        compareBy(
            { if (it.spineIndex < 0) Int.MAX_VALUE else it.spineIndex },
            { it.createdAtMs },
            { it.id },
        )
    )

/** `filesDir/reader/epub_annotations`（定稿五 §1 的落盘路径）。 */
internal fun epubAnnotationStorageDir(filesDir: File): File =
    File(File(filesDir, "reader"), "epub_annotations")

/**
 * 本地 JSON 编解码（纯 JVM，可单测）。
 *
 * 坏 JSON / 字段缺失 / 未知颜色名都回退到可继续渲染的状态，不让脏文件阻塞阅读页（与 PDF 批注同一策略）。
 */
internal object BookAnnotationCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(bookId: String, annotations: List<BookAnnotation>): String =
        json.encodeToString(
            BookAnnotationDocument.serializer(),
            BookAnnotationDocument(
                version = EPUB_ANNOTATION_FORMAT_VERSION,
                bookId = bookId,
                annotations = annotations.sortedForReading().map { it.toRecord() },
            ),
        )

    fun decode(bookId: String, raw: String): List<BookAnnotation> = runCatching {
        json.decodeFromString(BookAnnotationDocument.serializer(), raw)
    }
        .getOrNull()
        ?.annotations
        ?.mapNotNull { it.toAnnotation(bookId) }
        ?.sortedForReading()
        .orEmpty()
}

@Serializable
internal data class BookAnnotationDocument(
    val version: Int = EPUB_ANNOTATION_FORMAT_VERSION,
    val bookId: String = "",
    val annotations: List<BookAnnotationRecord> = emptyList(),
)

@Serializable
internal data class BookAnnotationRecord(
    val id: String,
    val chapterHref: String = "",
    val locatorJson: String = "",
    val highlightColor: String = HighlightColor.Yellow.id,
    val noteText: String = "",
    val selectedText: String = "",
    val chapterTitle: String = "",
    val spineIndex: Int = -1,
    val createdAtMs: Long = 0L,
    val updatedAtMs: Long = 0L,
)

private fun BookAnnotation.toRecord(): BookAnnotationRecord =
    BookAnnotationRecord(
        id = id,
        chapterHref = chapterHref,
        locatorJson = locatorJson,
        highlightColor = highlightColor.id,
        noteText = noteText,
        selectedText = selectedText,
        chapterTitle = chapterTitle,
        spineIndex = spineIndex,
        createdAtMs = createdAtMs,
        updatedAtMs = updatedAtMs,
    )

private fun BookAnnotationRecord.toAnnotation(bookId: String): BookAnnotation? {
    if (id.isBlank() || locatorJson.isBlank()) return null
    return BookAnnotation(
        id = id,
        bookId = bookId,
        chapterHref = chapterHref,
        locatorJson = locatorJson,
        highlightColor = HighlightColor.fromId(highlightColor) ?: HighlightColor.Yellow,
        noteText = sanitizeEpubNote(noteText),
        selectedText = sanitizeEpubSelectedText(selectedText),
        createdAtMs = createdAtMs,
        updatedAtMs = updatedAtMs,
        chapterTitle = chapterTitle.trim(),
        spineIndex = spineIndex.coerceAtLeast(-1),
    )
}

/**
 * 标注本地存储：`filesDir/reader/epub_annotations/{itemId}.json`。
 *
 * 与 PDF 批注同一套原子写策略（先写 `.tmp` 再 rename）；读失败回退为空列表。
 */
internal class EpubAnnotationStore(private val storageDir: File) {
    private val mutex = Mutex()

    fun storageFile(bookId: String): File = File(storageDir, "$bookId.json")

    suspend fun list(bookId: String): List<BookAnnotation> =
        withContext(Dispatchers.IO) { mutex.withLock { readFile(bookId) } }

    /** 新增 / 覆盖（按 id 去重），返回写入后的完整列表。 */
    suspend fun save(annotation: BookAnnotation): List<BookAnnotation> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val items =
                    readFile(annotation.bookId).filterNot { it.id == annotation.id } + annotation
                val sorted = items.sortedForReading()
                writeFile(annotation.bookId, sorted)
                sorted
            }
        }

    /** 改色（Decoration 整组重下发由调用方负责）。 */
    suspend fun recolor(
        bookId: String,
        annotationId: String,
        color: HighlightColor,
        nowMs: Long = System.currentTimeMillis(),
    ): List<BookAnnotation> =
        update(bookId) { annotation ->
            if (annotation.id == annotationId) annotation.withHighlightColor(color, nowMs)
            else annotation
        }

    suspend fun updateNote(
        bookId: String,
        annotationId: String,
        note: String,
        nowMs: Long = System.currentTimeMillis(),
    ): List<BookAnnotation> =
        update(bookId) { annotation ->
            if (annotation.id == annotationId) annotation.withNote(note, nowMs) else annotation
        }

    suspend fun delete(bookId: String, annotationId: String): List<BookAnnotation> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val remaining =
                    readFile(bookId).filterNot { it.id == annotationId }.sortedForReading()
                writeFile(bookId, remaining)
                remaining
            }
        }

    private suspend fun update(
        bookId: String,
        transform: (BookAnnotation) -> BookAnnotation,
    ): List<BookAnnotation> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val sorted = readFile(bookId).map(transform).sortedForReading()
                writeFile(bookId, sorted)
                sorted
            }
        }

    private fun readFile(bookId: String): List<BookAnnotation> {
        val file = storageFile(bookId)
        if (!file.isFile) return emptyList()
        return runCatching { BookAnnotationCodec.decode(bookId, file.readText()) }
            .getOrElse { emptyList() }
    }

    private fun writeFile(bookId: String, annotations: List<BookAnnotation>) {
        val file = storageFile(bookId)
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(BookAnnotationCodec.encode(bookId, annotations))
        if (!tmp.renameTo(file)) {
            // 极端情况下 rename 失败（同目录一般不会）：退化成覆盖写，保证不留下半截文件。
            file.writeText(tmp.readText())
            tmp.delete()
        }
    }
}
