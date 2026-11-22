package com.zhangwenkang.cinefin.repository

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 用户数据变更广播（W60b）。
 *
 * 「收藏」是条目级唯一状态，详情页 / 库网格 / 首页走廊 / 我的收藏页都可能同时展示它。为了让所有页面共用**单一数据源**， 写入口（
 * [JellyfinRepositoryImpl.markAsFavorite] / [JellyfinRepositoryImpl.unmarkAsFavorite]
 * 与音乐仓库的同名动作）在本地状态落库后调用 [notifyFavoriteChanged]；展示方订阅 [favoriteVersion]，发现版本变化时重新拉取当前页数据即可（不做细粒度
 * patch，避免漏掉 排序 / 筛选 / 角标等派生状态）。
 *
 * 用递增版本号而不是事件流：页面被详情页盖住（组合销毁）期间发生的变更，回到页面时也能靠「版本号 > 已处理版本」补刷新。
 */
object UserDataEvents {
    private val _favoriteVersion = MutableStateFlow(0)
    val favoriteVersion: StateFlow<Int> = _favoriteVersion.asStateFlow()

    fun notifyFavoriteChanged() {
        _favoriteVersion.value += 1
    }

    /**
     * W8-T3：个人评分版本（`UserData.Rating`）。
     *
     * 单独一个版本位而不是复用 [favoriteVersion]：评分目前只在电影 / 单集详情展示，收藏版本位会连带收藏页 / 库网格重拉； 展示方订阅
     * [ratingVersion]，版本变化时重取当前详情即可。
     */
    private val _ratingVersion = MutableStateFlow(0)
    val ratingVersion: StateFlow<Int> = _ratingVersion.asStateFlow()

    fun notifyRatingChanged() {
        _ratingVersion.value += 1
    }
}
