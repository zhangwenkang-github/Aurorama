package com.zhangwenkang.cinefin.local

import java.util.Locale

/** W43 搜索结果：条目 + 来源标签（`库名 · 文件夹名`；库 / 文件夹已删除时为 null）。 */
data class LocalSearchHit(val entry: LocalLibraryEntry, val folderLabel: String?)

/**
 * W43 本地搜索匹配（纯函数，便于单测与内存过滤）：
 * - **归一化** = `trim` + `Locale.ROOT` 小写（文件名 / 扩展名大小写不敏感）；
 * - **匹配范围** = 文件名（含扩展名，`TONE_A.WAV` 用 `tone_a` 可命中）+ 音乐内嵌标签标题；
 * - 空白查询不返回任何结果（调用方据此显示空态）；
 * - [filter] 单趟扫描 + 稳定排序（类型 → 文件名），大库下无需 Room 侧 LIKE 索引。
 */
object LocalLibrarySearch {

    fun normalize(raw: String): String = raw.trim().lowercase(Locale.ROOT)

    fun matches(entry: LocalLibraryEntry, query: String): Boolean =
        matchesNormalized(entry, normalize(query))

    fun filter(entries: List<LocalLibraryEntry>, query: String): List<LocalLibraryEntry> {
        val normalized = normalize(query)
        if (normalized.isEmpty()) return emptyList()
        return entries
            .asSequence()
            .filter { matchesNormalized(it, normalized) }
            .sortedWith(compareBy({ it.kind.ordinal }, { normalize(it.name) }))
            .toList()
    }

    private fun matchesNormalized(entry: LocalLibraryEntry, query: String): Boolean {
        if (query.isEmpty()) return false
        if (normalize(entry.name).contains(query)) return true
        val title = entry.title?.takeIf { it.isNotBlank() } ?: return false
        return normalize(title).contains(query)
    }
}
