package com.zhangwenkang.cinefin.presentation.film

import com.zhangwenkang.cinefin.local.localItemIdFor
import com.zhangwenkang.cinefin.repository.ReadingProgress
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 书架增强（W6-T10，READER-39）的纯逻辑与本地登记。
 *
 * 定稿七 §3 的三件事在这里各有一个可单测的入口：
 * - **最近阅读**：[bookshelfRecentEntries] 按 `ReadingProgress.updatedAt` 倒序（仓储已排序）拼出横滑卡片；
 * - **条目附加信息**：[bookshelfMetaLine] 产出「作者 · 42%」，无进度显「未读」；
 * - **移出书架 / 本地书架**：[LocalShelfRegistry] 把 SAF 导入的单文件书籍登记为本地书架条目 （只存 URI
 *   与显示名，**不拷贝源文件**），服务器书的「移出」走
 *   [com.zhangwenkang.cinefin.settings.domain.AppPreferences.readerShelfHiddenIds]。
 */

/** 最近阅读行最多几张卡（横滑一屏多一点）。 */
internal const val BOOKSHELF_RECENT_LIMIT: Int = 10

/** 本地书架登记条目：SAF 文档 URI + 显示名 + 加入时间。 */
@Serializable
data class BookshelfLocalBook(
    val uri: String,
    val name: String,
    val addedAtMs: Long = 0L,
)

/** 最近阅读卡片所需的服务器条目投影（只取展示必需的三个字段，避免在上层传整个模型）。 */
data class BookshelfServerBook(
    val itemId: UUID,
    val title: String,
    val coverUrl: String?,
    val authors: List<String> = emptyList(),
)

/** 最近阅读行的一张卡。 */
data class BookshelfRecentEntry(
    val itemId: UUID,
    val title: String,
    val coverUrl: String?,
    val progressPercent: Int,
    /** 非空 = 本地书（点击走本地阅读器链路）。 */
    val localUri: String? = null,
)

/**
 * 本地书架登记（`pref_reader_local_shelf` 的 JSON 编解码）。
 *
 * 脏数据策略与其它本地 JSON 一致：解析失败回落空列表，不阻塞书架页。
 */
internal object LocalShelfRegistry {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun decode(raw: String): List<BookshelfLocalBook> {
        val text = raw.trim()
        if (text.isEmpty()) return emptyList()
        return runCatching { json.decodeFromString<List<BookshelfLocalBook>>(text) }
            .getOrDefault(emptyList())
            .filter { it.uri.isNotBlank() && it.name.isNotBlank() }
            .distinctBy { it.uri }
    }

    fun encode(books: List<BookshelfLocalBook>): String {
        if (books.isEmpty()) return ""
        return runCatching { json.encodeToString(books.distinctBy { it.uri }) }.getOrDefault("")
    }

    /** 加入（同 URI 覆盖显示名与时间，不重复登记）。 */
    fun withBook(raw: String, book: BookshelfLocalBook): String =
        encode(decode(raw).filterNot { it.uri == book.uri } + book)

    /** 移出（只删登记，不删 / 不改源文件）。 */
    fun withoutUri(raw: String, uri: String): String =
        encode(decode(raw).filterNot { it.uri == uri })
}

/**
 * 阅读进度 → 显示百分比（整数 0–100）。
 *
 * `progression` 越界夹取；非有限值视为没有进度（返回 null，UI 显「未读」）。
 */
internal fun bookshelfProgressPercent(progression: Double?): Int? {
    val value = progression ?: return null
    if (!value.isFinite()) return null
    return (value.coerceIn(0.0, 1.0) * 100).toInt()
}

/** 进度文案：有进度 `42%`，无进度「未读」。 */
internal fun bookshelfProgressText(progressPercent: Int?): String =
    progressPercent?.let { "$it%" } ?: "未读"

/**
 * 书籍卡附加信息行（定稿七 §3）：`作者 · 42%` / `42%` / `未读`。
 *
 * 作者可能多值（合著），用顿号连接；空作者不占位。
 */
internal fun bookshelfMetaLine(authors: List<String>, progressPercent: Int?): String {
    val progress = bookshelfProgressText(progressPercent)
    val author = authors.filter { it.isNotBlank() }.joinToString("、")
    return if (author.isEmpty()) progress else "$author · $progress"
}

/**
 * 最近阅读行（纯函数）：[progress] 已由仓储按 `updatedAt` 倒序给出，这里只做标题 / 封面归因。
 *
 * - 服务器书：从 [serverBooks]（书架库已加载的条目）取书名与封面；取不到的直接跳过（避免出现无标题空卡）；
 * - 本地书：从 [localBooks] 登记取书名，进度按 `localItemIdFor(uri)` 匹配；
 * - 结果按输入顺序（= 最近读的在前），最多 [limit] 张。
 */
internal fun bookshelfRecentEntries(
    progress: List<ReadingProgress>,
    serverBooks: Map<UUID, BookshelfServerBook>,
    localBooks: List<BookshelfLocalBook>,
    limit: Int = BOOKSHELF_RECENT_LIMIT,
): List<BookshelfRecentEntry> {
    if (limit <= 0) return emptyList()
    val localByItemId = localBooks.associateBy { localItemIdFor(it.uri) }
    val entries = ArrayList<BookshelfRecentEntry>(minOf(progress.size, limit))
    for (item in progress) {
        if (entries.size >= limit) break
        val percent = bookshelfProgressPercent(item.progression) ?: continue
        val local = localByItemId[item.itemId]
        if (local != null) {
            entries +=
                BookshelfRecentEntry(
                    itemId = item.itemId,
                    title = local.name,
                    coverUrl = null,
                    progressPercent = percent,
                    localUri = local.uri,
                )
            continue
        }
        val server = serverBooks[item.itemId] ?: continue
        entries +=
            BookshelfRecentEntry(
                itemId = server.itemId,
                title = server.title,
                coverUrl = server.coverUrl,
                progressPercent = percent,
            )
    }
    return entries
}

/** 服务器书架条目 → 进度 / 作者映射（书架页一次加载，卡片与最近阅读共用）。 */
internal fun bookshelfServerBooks(
    items: List<BookshelfServerBook>
): Map<UUID, BookshelfServerBook> = items.associateBy { it.itemId }

/** 隐藏集合判定：`pref_reader_shelf_hidden` 存的是 itemId 字符串。 */
internal fun bookshelfIsHidden(itemId: UUID, hiddenIds: Set<String>): Boolean =
    itemId.toString() in hiddenIds

/** 移出 / 加入的集合运算（纯函数；返回可直接写回偏好的新集合）。 */
internal fun bookshelfToggleHidden(
    hiddenIds: Set<String>,
    itemId: UUID,
    hidden: Boolean,
): Set<String> {
    val key = itemId.toString()
    return if (hidden) hiddenIds + key else hiddenIds - key
}
