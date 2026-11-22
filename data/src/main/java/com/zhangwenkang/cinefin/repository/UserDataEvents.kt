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
 * W8-T5：条目被删除后列表 / 首页 / 收藏页同样必须重新拉取（条目已不存在，不能只靠收藏 patch），删除成功时调用 [notifyContentChanged]，订阅方按
 * [contentVersion] 触发同一次刷新。
 *
 * 用递增版本号而不是事件流：页面被详情页盖住（组合销毁）期间发生的变更，回到页面时也能靠「版本号 > 已处理版本」补刷新。
 */
object UserDataEvents {
    private val _favoriteVersion = MutableStateFlow(0)
    val favoriteVersion: StateFlow<Int> = _favoriteVersion.asStateFlow()

    private val _contentVersion = MutableStateFlow(0)
    val contentVersion: StateFlow<Int> = _contentVersion.asStateFlow()

    fun notifyFavoriteChanged() {
        _favoriteVersion.value += 1
    }

    /** W8-T5：条目内容集合发生变化（如服务器文件被删除），展示方应重新拉取当前页。 */
    fun notifyContentChanged() {
        _contentVersion.value += 1
    }
}
