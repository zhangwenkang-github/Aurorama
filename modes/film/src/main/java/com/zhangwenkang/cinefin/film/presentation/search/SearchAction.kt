package com.zhangwenkang.cinefin.film.presentation.search

import com.zhangwenkang.cinefin.models.FindroidItem

sealed interface SearchAction {
    data class Search(val query: String) : SearchAction

    data class OnItemClick(val item: FindroidItem) : SearchAction
}
