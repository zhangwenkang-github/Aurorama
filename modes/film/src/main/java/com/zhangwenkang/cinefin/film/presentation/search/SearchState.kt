package com.zhangwenkang.cinefin.film.presentation.search

import com.zhangwenkang.cinefin.local.LocalSearchHit
import com.zhangwenkang.cinefin.models.FindroidItem
import com.zhangwenkang.cinefin.repository.SearchScope

/**
 * W43 搜索状态：服务器媒体库 + 本地媒体库两个来源。
 *
 * W82 #25：UI 先按 [SearchCategory]（影视 / 音乐 / 书籍）分区，再在每个分区内区分服务器卡片与本地行。 W88-A：当前 query / scope /
 * 两类失败归本搜索目的地所有。
 */
data class SearchState(
    /** W88-B：唯一结果列表；服务器 / 本地 / 下载来源已在纯函数中按 canonical identity 折叠。 */
    val results: List<SearchResult> = emptyList(),
    val loading: Boolean = false,
    val query: String = "",
    val scope: SearchScope = SearchScope.ALL,
    val serverFailed: Boolean = false,
    val localFailed: Boolean = false,
) {
    val serverItems: List<FindroidItem>
        get() = results.mapNotNull {
            when (it) {
                is SearchResult.Server -> it.item
                is SearchResult.UnidentifiedServer -> it.item
                else -> null
            }
        }

    val localItems: List<LocalSearchHit>
        get() = results.filterIsInstance<SearchResult.LocalLibrary>().map { it.hit }

    /** 两个来源都没有命中才算「未找到」。 */
    val isEmpty: Boolean
        get() = results.isEmpty()

    val visibleCategories: List<SearchCategory>
        get() =
            when (scope) {
                SearchScope.ALL -> SearchCategory.entries
                SearchScope.VIDEO -> listOf(SearchCategory.VIDEO)
                SearchScope.MUSIC -> listOf(SearchCategory.MUSIC)
                SearchScope.BOOK -> listOf(SearchCategory.BOOK)
            }

    val hasFailure: Boolean
        get() = serverFailed || localFailed

    fun resultsIn(category: SearchCategory): List<SearchResult> = results.filter {
        category in visibleCategories && it.category == category
    }

    fun serverItemsIn(category: SearchCategory): List<FindroidItem> =
        resultsIn(category).mapNotNull {
            when (it) {
                is SearchResult.Server -> it.item
                is SearchResult.UnidentifiedServer -> it.item
                else -> null
            }
        }

    fun localItemsIn(category: SearchCategory): List<LocalSearchHit> =
        resultsIn(category).filterIsInstance<SearchResult.LocalLibrary>().map { it.hit }

    fun countIn(category: SearchCategory): Int = resultsIn(category).size
}
