package com.zhangwenkang.cinefin.film.presentation.search

import com.zhangwenkang.cinefin.local.LocalSearchHit
import com.zhangwenkang.cinefin.models.FindroidItem

/** W43 搜索状态：服务器媒体库 + 本地媒体库两个来源，UI 据此分区展示（各带计数与来源徽标）。 */
data class SearchState(
    val serverItems: List<FindroidItem> = emptyList(),
    val localItems: List<LocalSearchHit> = emptyList(),
    val loading: Boolean = false,
) {
    /** 两个来源都没有命中才算「未找到」。 */
    val isEmpty: Boolean
        get() = serverItems.isEmpty() && localItems.isEmpty()
}
