package com.zhangwenkang.cinefin.film.presentation.search

import com.zhangwenkang.cinefin.local.LocalSearchHit
import com.zhangwenkang.cinefin.models.FindroidItem
import com.zhangwenkang.cinefin.repository.SearchScope

sealed interface SearchAction {
    /** 输入框只更新当前目的地的 query；防抖后由 [Search] 发起查询。 */
    data class UpdateQuery(val query: String) : SearchAction

    data class Search(val query: String) : SearchAction

    /** 页内切换只影响本次搜索目的地，不写入口偏好。 */
    data class ChangeScope(val scope: SearchScope) : SearchAction

    /** W82 #25：错误态重试当前查询。 */
    data object Retry : SearchAction

    data class OnItemClick(val item: FindroidItem) : SearchAction

    /** W43：点击本地条目（视频 → 播放器；书籍 → 阅读器；音乐 → 音乐播放链路）。 */
    data class OnLocalItemClick(val hit: LocalSearchHit) : SearchAction
}
