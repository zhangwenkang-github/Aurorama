package com.zhangwenkang.cinefin.film.presentation.library

import com.zhangwenkang.cinefin.models.FindroidItem
import com.zhangwenkang.cinefin.models.FindroidTag
import com.zhangwenkang.cinefin.models.SortBy
import com.zhangwenkang.cinefin.models.SortOrder

sealed interface LibraryAction {
    data class OnItemClick(val item: FindroidItem) : LibraryAction

    data object OnBackClick : LibraryAction

    data class ChangeSorting(val sortBy: SortBy, val sortOrder: SortOrder) : LibraryAction

    /** W54-B：切换顶部 tab（库名 / 建议 / 即将播出 / 类型 / 制片发行商 / 剧集）。 */
    data class SelectTab(val tab: LibraryTab) : LibraryAction

    /** W54-B：网格 / 列表显示方式。 */
    data class SelectViewMode(val viewMode: LibraryViewMode) : LibraryAction

    /** W54-B：工具行 funnel 筛选（null = 全部）。 */
    data class SelectFilter(val filter: LibraryFilter?) : LibraryAction

    /** W54-B：分类 tab 里点一个类型 / 制片发行商——回到库名 tab 并带上库内过滤。 */
    data class SelectTag(val tag: FindroidTag) : LibraryAction

    /** W54-B：清掉库内类型 / 发行商过滤。 */
    data object ClearTag : LibraryAction

    /** W54-B：下拉刷新（真实重取计数 + 当前 tab；分页由页面 `items.refresh()` 重取）。 */
    data object Refresh : LibraryAction
}
