package com.zhangwenkang.cinefin.film.presentation.library

import androidx.paging.PagingData
import com.zhangwenkang.cinefin.models.FindroidItem
import com.zhangwenkang.cinefin.models.FindroidTag
import com.zhangwenkang.cinefin.models.SortBy
import com.zhangwenkang.cinefin.models.SortOrder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

data class LibraryState(
    val items: Flow<PagingData<FindroidItem>> = emptyFlow(),
    val sortBy: SortBy = SortBy.NAME,
    val sortOrder: SortOrder = SortOrder.ASCENDING,
    val isLoading: Boolean = false,
    val error: Exception? = null,
    /** W54-B：库类型对应的顶部 tabs（setup 时算好）。 */
    val tabs: List<LibraryTab> = emptyList(),
    val tab: LibraryTab = LibraryTab.Library,
    /** W54-B：网格 / 列表显示方式（进程内状态，不落偏好——`AppPreferences` 是红线文件）。 */
    val viewMode: LibraryViewMode = LibraryViewMode.Grid,
    /** W54-B：工具行 funnel 选中的常用筛选（null = 全部）。 */
    val filter: LibraryFilter? = null,
    /** W54-B：从「类型」tab 点进来的库内类型过滤（按名称）。 */
    val genre: String? = null,
    /** W54-B：从「制片发行商」tab 点进来的库内发行商过滤（按名称）。 */
    val studio: String? = null,
    /** W54-B：非分页 tab 的条目（建议 / 即将播出 / 剧集）。 */
    val tabItems: List<FindroidItem> = emptyList(),
    /** W54-B：分类 tab 的条目（类型 / 制片发行商）。 */
    val tags: List<FindroidTag> = emptyList(),
    val tabLoading: Boolean = false,
    val tabError: Exception? = null,
    /** W54-B：服务器返回的条目总数（null = 未知，计数退化为已加载条数）。 */
    val totalCount: Int? = null,
    /** W54-B：下拉刷新指示（真实重取：Paging 重建 + 计数 + 当前 tab）。 */
    val refreshing: Boolean = false,
)
