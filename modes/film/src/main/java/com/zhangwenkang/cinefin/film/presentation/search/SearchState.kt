package com.zhangwenkang.cinefin.film.presentation.search

import com.zhangwenkang.cinefin.local.LocalSearchHit
import com.zhangwenkang.cinefin.models.FindroidItem

/**
 * W43 搜索状态：服务器媒体库 + 本地媒体库两个来源。
 *
 * W82 #25：UI 先按 [SearchCategory]（影视 / 音乐 / 书籍）分区，再在每个分区内区分服务器卡片与本地行； `query` 供错误态重试，`serverFailed`
 * 用于「本地有结果但服务器失败」的提示。
 */
data class SearchState(
    val serverItems: List<FindroidItem> = emptyList(),
    val localItems: List<LocalSearchHit> = emptyList(),
    val loading: Boolean = false,
    val query: String = "",
    val serverFailed: Boolean = false,
) {
    /** 两个来源都没有命中才算「未找到」。 */
    val isEmpty: Boolean
        get() = serverItems.isEmpty() && localItems.isEmpty()

    fun serverItemsIn(category: SearchCategory): List<FindroidItem> = serverItems.filter {
        SearchResultRules.categoryOf(it) == category
    }

    fun localItemsIn(category: SearchCategory): List<LocalSearchHit> = localItems.filter {
        SearchResultRules.categoryOf(it) == category
    }

    fun countIn(category: SearchCategory): Int =
        serverItemsIn(category).size + localItemsIn(category).size
}
