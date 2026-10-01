package com.zhangwenkang.cinefin.settings.domain.models

/** 服务器媒体库的轻量快照（设置页的库选择与侧栏媒体库分组共用）。 */
data class CatalogLibrary(val id: String, val name: String, val type: String)

/**
 * 媒体库目录的编码 / 解码（纯字符串，存进 `AppPreferences.uiLibraryCatalog`）。
 *
 * 每行一条：`id\u001F名称\u001F类型`。名称里的分隔符在编码时被替换，保证解码不会错位； 解析失败的行直接丢弃（宁可少一个选项，也不要让设置页崩掉）。
 */
object LibraryCatalog {
    private const val FIELD_SEPARATOR = '\u001F'
    private const val LINE_SEPARATOR = '\n'

    fun encode(libraries: List<CatalogLibrary>): String =
        libraries.joinToString(LINE_SEPARATOR.toString()) { library ->
            listOf(library.id, library.name, library.type).joinToString(
                FIELD_SEPARATOR.toString()
            ) { field ->
                field.replace(FIELD_SEPARATOR, ' ').replace(LINE_SEPARATOR, ' ')
            }
        }

    fun decode(raw: String?): List<CatalogLibrary> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split(LINE_SEPARATOR).mapNotNull { line ->
            if (line.isBlank()) return@mapNotNull null
            val parts = line.split(FIELD_SEPARATOR)
            if (parts.size < 3) return@mapNotNull null
            val id = parts[0]
            if (id.isBlank()) return@mapNotNull null
            CatalogLibrary(
                id = id,
                name = parts[1],
                type = parts.drop(2).joinToString(FIELD_SEPARATOR.toString()),
            )
        }
    }
}
