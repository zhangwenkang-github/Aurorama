package com.zhangwenkang.cinefin.settings.domain.models

/**
 * 首页模块的库级偏好：编解码 + 纯规则（W54-D，用户 2026-10-03 确认）。
 *
 * 三个偏好键共用这一份口径：
 * - `pref_ui_home_libraries_hidden`（逗号无关的字符串集合）：被关掉「在首页显示」的库 id，默认空 = 全部开；
 * - `pref_ui_home_library_pages`：库 id → 默认分页 key（与 W54-B `LibraryTab` 同名，落盘后待库页消费）；
 * - `pref_ui_home_library_order`：用户上下调整过的库顺序（只存 id 列表，服务器新增的库自动补到最后）。
 *
 * 放在 settings 域（而不是 `modes:film`）的原因：设置页与首页 ViewModel 都要读同一份口径， 而 settings 是两个模块共同的下游依赖；纯函数放这里可以直接被
 * `app:phone` 单测覆盖。
 */
object HomeLibrarySettings {
    const val PAGE_LIBRARY = "library"

    const val PAGE_SUGGESTIONS = "suggestions"

    const val PAGE_UPCOMING = "upcoming"

    const val PAGE_GENRES = "genres"

    const val PAGE_STUDIOS = "studios"

    const val PAGE_EPISODES = "episodes"

    /** 默认分页 = 库内容（W54-B 顶部 tabs 的第一项）。 */
    const val DEFAULT_PAGE_KEY = PAGE_LIBRARY

    private const val LINE_SEPARATOR = '\n'

    private const val VALUE_SEPARATOR = '\u001F'

    /**
     * 库类型的「默认分页」候选，与 W54-B `libraryTabs()` 的 tab 集合一一对应。
     *
     * settings 模块不依赖 `modes:film`（依赖方向相反），所以这里用与 `LibraryTab` 同名的 key 字符串； 两边任何一处改 tab
     * 集合，都要同步这份表的单测。
     */
    fun pageKeys(libraryType: String): List<String> =
        when (libraryType.lowercase()) {
            "movies" -> listOf(PAGE_LIBRARY, PAGE_SUGGESTIONS, PAGE_GENRES, PAGE_STUDIOS)
            "tvshows" ->
                listOf(
                    PAGE_LIBRARY,
                    PAGE_SUGGESTIONS,
                    PAGE_UPCOMING,
                    PAGE_GENRES,
                    PAGE_STUDIOS,
                    PAGE_EPISODES,
                )
            "books",
            "homevideos",
            "boxsets",
            "music" -> listOf(PAGE_LIBRARY, PAGE_SUGGESTIONS, PAGE_GENRES)
            "playlists",
            "livetv",
            "unknown" -> listOf(PAGE_LIBRARY)
            // 混合（collectionType = null）/ 文件夹库：与 Movie 同口径（含制片发行商）
            else -> listOf(PAGE_LIBRARY, PAGE_SUGGESTIONS, PAGE_GENRES, PAGE_STUDIOS)
        }

    /** 落盘值非法（库类型变了 / 选项被删）时回落默认分页，不把错误值透给库页。 */
    fun resolvePageKey(libraryType: String, stored: String?): String =
        stored?.takeIf { it in pageKeys(libraryType) } ?: DEFAULT_PAGE_KEY

    /** 每库「在首页显示」映射：默认全部开，存的是**关闭**集合（服务器新增的库自动可见）。 */
    fun isLibraryVisible(libraryId: String, hiddenLibraryIds: Set<String>): Boolean =
        libraryId !in hiddenLibraryIds

    fun setLibraryVisible(
        hiddenLibraryIds: Set<String>,
        libraryId: String,
        visible: Boolean,
    ): Set<String> = if (visible) hiddenLibraryIds - libraryId else hiddenLibraryIds + libraryId

    /**
     * 媒体库顺序：存储顺序优先；未出现在存储顺序里的 id（服务器新增）按原始顺序追加。
     *
     * 输入与输出都是完整的 id 列表，保持调用方不需要区分「已排序 / 未排序」。
     */
    fun applyOrder(ids: List<String>, storedOrder: List<String>): List<String> {
        if (storedOrder.isEmpty()) return ids
        val rank = storedOrder.withIndex().associate { (index, id) -> id to index }
        return ids.sortedWith(compareBy({ rank[it] ?: Int.MAX_VALUE }, { ids.indexOf(it) }))
    }

    /** 上下调整：把 [libraryId] 移动 [delta] 步（越界夹到端点，找不到 / 无变化时原样返回）。 */
    fun moveLibrary(ids: List<String>, libraryId: String, delta: Int): List<String> {
        if (delta == 0 || ids.isEmpty()) return ids
        val from = ids.indexOf(libraryId)
        if (from < 0) return ids
        val to = (from + delta).coerceIn(0, ids.lastIndex)
        if (to == from) return ids
        return ids.toMutableList().apply { add(to, removeAt(from)) }
    }

    fun encodeIdList(ids: List<String>): String =
        ids.filter { it.isNotBlank() }
            .joinToString(LINE_SEPARATOR.toString(), transform = ::encodeValue)

    fun decodeIdList(raw: String?): List<String> =
        decodeLines(raw).mapNotNull { line -> line.takeIf { it.isNotBlank() } }

    fun encodePageMap(pages: Map<String, String>): String =
        pages.entries
            .filter { (id, page) -> id.isNotBlank() && page.isNotBlank() }
            .joinToString(LINE_SEPARATOR.toString()) { (id, page) ->
                encodeValue(id) + VALUE_SEPARATOR + encodeValue(page)
            }

    fun decodePageMap(raw: String?): Map<String, String> =
        decodeLines(raw)
            .mapNotNull { line ->
                val separator = line.indexOf(VALUE_SEPARATOR)
                if (separator <= 0 || separator >= line.lastIndex) return@mapNotNull null
                val id = line.substring(0, separator)
                val page = line.substring(separator + 1)
                if (id.isBlank() || page.isBlank()) null else id to page
            }
            .toMap()

    private fun decodeLines(raw: String?): List<String> =
        raw?.takeIf { it.isNotBlank() }?.split(LINE_SEPARATOR) ?: emptyList()

    private fun encodeValue(value: String): String =
        value.replace(LINE_SEPARATOR, ' ').replace(VALUE_SEPARATOR, ' ')
}
