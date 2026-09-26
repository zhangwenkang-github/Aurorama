package com.zhangwenkang.cinefin.film.presentation.collection

import com.zhangwenkang.cinefin.models.FindroidItem

sealed interface CollectionAction {
    data class OnItemClick(val item: FindroidItem) : CollectionAction

    data object OnBackClick : CollectionAction
}
