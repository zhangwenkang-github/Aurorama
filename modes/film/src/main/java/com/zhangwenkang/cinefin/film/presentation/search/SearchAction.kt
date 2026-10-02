package com.zhangwenkang.cinefin.film.presentation.search

import com.zhangwenkang.cinefin.local.LocalSearchHit
import com.zhangwenkang.cinefin.models.FindroidItem

sealed interface SearchAction {
    data class Search(val query: String) : SearchAction

    data class OnItemClick(val item: FindroidItem) : SearchAction

    /** W43：点击本地条目（视频 → 播放器；书籍 → 阅读器；音乐 → 音乐播放链路）。 */
    data class OnLocalItemClick(val hit: LocalSearchHit) : SearchAction
}
