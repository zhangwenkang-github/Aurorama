package com.zhangwenkang.cinefin.film.presentation.person

import com.zhangwenkang.cinefin.models.FindroidItem

sealed interface PersonAction {
    data object NavigateBack : PersonAction

    data object NavigateHome : PersonAction

    data class NavigateToItem(val item: FindroidItem) : PersonAction
}
