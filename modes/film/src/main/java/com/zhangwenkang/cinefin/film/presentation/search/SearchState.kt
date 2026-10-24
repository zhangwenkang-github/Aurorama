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
    val serverItems: List<FindroidItem> = emptyList(),
    val localItems: List<LocalSearchHit> = emptyList(),
    val loading: Boolean = false,
    val query: String = "",
    val scope: SearchScope = SearchScope.ALL,
    val serverFailed: Boolean = false,
    val localFailed: Boolean = false,
) {
    /** 两个来源都没有命中才算「未找到」。 */
    val isEmpty: Boolean
        get() = serverItems.isEmpty() && localItems.isEmpty()

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

    fun serverItemsIn(category: SearchCategory): List<FindroidItem> = serverItems.filter {
        category in visibleCategories && SearchResultRules.categoryOf(it) == category
    }

    fun localItemsIn(category: SearchCategory): List<LocalSearchHit> = localItems.filter {
        category in visibleCategories && SearchResultRules.categoryOf(it) == category
    }

    fun countIn(category: SearchCategory): Int =
        serverItemsIn(category).size + localItemsIn(category).size
}
