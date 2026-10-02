package com.zhangwenkang.cinefin.book.presentation.reader

import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 本地高亮批注（W29-READER，EB-8 后置项）。
 *
 * 数据口径（READER_PLAN §2 D23）：
 * - **粒度 = 矩形选区 + 备注**：不依赖文本层，扫描件 PDF 同样可用（这是选矩形而不是选词的直接原因）；
 * - **锚点 = 页索引 + 页面归一化矩形**（[PageRect]，0–1、左上原点、按渲染方向）：与页码 / progression 语义解耦，分页 / 双栏 / 滚动 / RTL
 *   一切换排版都不需要换算；
 * - **只落本地**：`filesDir/reader/annotations/{itemId}.json`，一个 itemId 一个文件，不写服务器、不用 Room （服务器没有标准批注
 *   API，ARCHITECTURE §3.6）；
 * - **文件格式带版本号**（[READER_ANNOTATION_FORMAT_VERSION]），字段缺省 / 未知字段都能兼容读取。
 *
 * 兼容边界（D23 明确记录）：
 * - **RTL / 双栏（未合并）**：按逻辑页渲染，直接复用同一份归一化矩形，无需换算；
 * - **合并槽位（W22/W26 的对图拼合）**：合并位图是两页拼接而成，本波不把批注映射到合并图上 （合并命中率低的书不受影响；命中合并且有批注的槽位暂不显示叠加，记 §9 遗留）；
 * - **后续若改批注粒度**（文字选择 / 手绘）：新增字段或新增 version，旧记录按矩形继续可读。
 */
internal const val READER_ANNOTATION_FORMAT_VERSION: Int = 1

/** 备注最大长度（避免本地文件被单条批注撑大）。 */
internal const val READER_ANNOTATION_NOTE_MAX_CHARS: Int = 500

/** 备注摘要长度（列表行展示）。 */
internal const val READER_ANNOTATION_SUMMARY_CHARS: Int = 28

/** 批注域模型。 */
data class ReaderAnnotation(
    val id: String,
    val itemId: String,
    val pageIndex: Int,
    val rect: PageRect,
    val note: String,
    val createdAtMs: Long,
    val updatedAtMs: Long,
) {
    /** 列表标签：页码 + 备注摘要（无备注时显示「高亮」）。 */
    val label: String
        get() = "第 ${pageIndex + 1} 页 · " + annotationNoteSummary(note).ifBlank { "高亮" }
}

/** 备注摘要：折叠空白 + 截断（列表行 / 面板标题用）。 */
internal fun annotationNoteSummary(
    note: String,
    maxChars: Int = READER_ANNOTATION_SUMMARY_CHARS,
): String {
    val collapsed = note.trim().replace(Regex("\\s+"), " ")
    if (collapsed.length <= maxChars) return collapsed
    return collapsed.take(maxChars) + "…"
}

/** 备注清洗：折叠首尾空白、限制长度。 */
internal fun sanitizeAnnotationNote(note: String): String =
    note.trim().take(READER_ANNOTATION_NOTE_MAX_CHARS)

/** 新建一条批注（id / 时间戳在这里生成，方便单测用固定值构造）。 */
internal fun newReaderAnnotation(
    itemId: String,
    pageIndex: Int,
    rect: PageRect,
    note: String,
    nowMs: Long = System.currentTimeMillis(),
    id: String = UUID.randomUUID().toString(),
): ReaderAnnotation =
    ReaderAnnotation(
        id = id,
        itemId = itemId,
        pageIndex = pageIndex.coerceAtLeast(0),
        rect = rect.normalized(),
        note = sanitizeAnnotationNote(note),
        createdAtMs = nowMs,
        updatedAtMs = nowMs,
    )

/** 按页序排列（同页按创建时间）。 */
internal fun List<ReaderAnnotation>.sortedForReading(): List<ReaderAnnotation> =
    sortedWith(compareBy({ it.pageIndex }, { it.createdAtMs }, { it.id }))

/**
 * 本地 JSON 编解码（kotlinx.serialization；纯 JVM，可单测）。
 *
 * `version` 是格式版本，`itemId` 冗余写在文件里用于人工排查（文件名也是 itemId）。 解码容错：坏 JSON / 字段缺失回退为空列表，不让脏文件阻塞阅读页（与书签 /
 * 进度同一策略）。
 */
internal object ReaderAnnotationCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(itemId: String, annotations: List<ReaderAnnotation>): String =
        json.encodeToString(
            ReaderAnnotationDocument.serializer(),
            ReaderAnnotationDocument(
                version = READER_ANNOTATION_FORMAT_VERSION,
                itemId = itemId,
                annotations = annotations.sortedForReading().map { it.toRecord() },
            ),
        )

    fun decode(itemId: String, raw: String): List<ReaderAnnotation> = runCatching {
        json.decodeFromString(ReaderAnnotationDocument.serializer(), raw)
    }
        .getOrNull()
        ?.annotations
        ?.mapNotNull { it.toAnnotation(itemId) }
        ?.sortedForReading()
        .orEmpty()
}

@Serializable
internal data class ReaderAnnotationDocument(
    val version: Int = READER_ANNOTATION_FORMAT_VERSION,
    val itemId: String = "",
    val annotations: List<ReaderAnnotationRecord> = emptyList(),
)

@Serializable
internal data class ReaderAnnotationRecord(
    val id: String,
    val page: Int,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val note: String = "",
    val createdAtMs: Long = 0L,
    val updatedAtMs: Long = 0L,
)

private fun ReaderAnnotation.toRecord(): ReaderAnnotationRecord =
    ReaderAnnotationRecord(
        id = id,
        page = pageIndex,
        left = rect.left,
        top = rect.top,
        right = rect.right,
        bottom = rect.bottom,
        note = note,
        createdAtMs = createdAtMs,
        updatedAtMs = updatedAtMs,
    )

private fun ReaderAnnotationRecord.toAnnotation(itemId: String): ReaderAnnotation? {
    if (id.isBlank() || page < 0) return null
    return ReaderAnnotation(
        id = id,
        itemId = itemId,
        pageIndex = page,
        rect = PageRect(left, top, right, bottom).normalized(),
        note = sanitizeAnnotationNote(note),
        createdAtMs = createdAtMs,
        updatedAtMs = updatedAtMs,
    )
}
