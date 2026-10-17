package com.zhangwenkang.cinefin.film.presentation.media

import com.zhangwenkang.cinefin.models.FindroidCollection

sealed interface MediaAction {
    data class OnItemClick(val item: FindroidCollection) : MediaAction

    data object OnFavoritesClick : MediaAction

    data object OnRetryClick : MediaAction

    /** W83 #31：媒体库总览下拉刷新（清会话缓存后静默重取，保留旧库卡）。 */
    data object Refresh : MediaAction
}
