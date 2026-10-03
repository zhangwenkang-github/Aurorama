package com.zhangwenkang.cinefin.presentation.film

import com.zhangwenkang.cinefin.models.FindroidEpisode
import com.zhangwenkang.cinefin.models.FindroidItem
import com.zhangwenkang.cinefin.models.FindroidMovie
import com.zhangwenkang.cinefin.models.FindroidShow
import com.zhangwenkang.cinefin.models.SortBy
import com.zhangwenkang.cinefin.models.SortOrder

/** 「我的收藏」类型筛选（跨库汇总后的本地筛选；全部 / 电影 / 剧集 / 单集）。 */
enum class FavoriteTypeFilter {
    ALL,
    MOVIES,
    SHOWS,
    EPISODES,
}

/**
 * 「我的收藏」排序（W60b 用户口径）：加入日期 / 名称。
 *
 * 排序在服务器侧完成（客户端拿不到 `DateCreated`），切排序会重新查询一次收藏列表。
 */
enum class FavoriteSort {
    DATE_ADDED,
    NAME,
}

/** 排序选项 → 仓库查询参数（纯函数，单测锁定）。 */
data class FavoriteSortSpec(val sortBy: SortBy, val sortOrder: SortOrder)

fun favoriteSortSpec(sort: FavoriteSort): FavoriteSortSpec =
    when (sort) {
        // 「加入日期」= Jellyfin 的 DateCreated 倒序（最新加入在最上面）。
        FavoriteSort.DATE_ADDED -> FavoriteSortSpec(SortBy.DATE_ADDED, SortOrder.DESCENDING)
        FavoriteSort.NAME -> FavoriteSortSpec(SortBy.NAME, SortOrder.ASCENDING)
    }

/** 单条目是否命中类型筛选（纯函数，单测锁定）。 */
fun favoriteTypeFilterMatches(item: FindroidItem, filter: FavoriteTypeFilter): Boolean =
    when (filter) {
        FavoriteTypeFilter.ALL -> true
        FavoriteTypeFilter.MOVIES -> item is FindroidMovie
        FavoriteTypeFilter.SHOWS -> item is FindroidShow
        FavoriteTypeFilter.EPISODES -> item is FindroidEpisode
    }

/** 类型筛选（保持服务器排序，只过滤类型）。 */
fun filterFavoriteItems(
    items: List<FindroidItem>,
    filter: FavoriteTypeFilter,
): List<FindroidItem> = items.filter { favoriteTypeFilterMatches(it, filter) }
