package com.zhangwenkang.cinefin.film.presentation.media

import com.zhangwenkang.cinefin.models.FindroidCollection

sealed interface MediaAction {
    data class OnItemClick(val item: FindroidCollection) : MediaAction

    data object OnFavoritesClick : MediaAction

    data object OnRetryClick : MediaAction
}
