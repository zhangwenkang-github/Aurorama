package com.zhangwenkang.cinefin.film.presentation.library

import com.zhangwenkang.cinefin.models.FindroidItem
import com.zhangwenkang.cinefin.models.SortBy
import com.zhangwenkang.cinefin.models.SortOrder

sealed interface LibraryAction {
    data class OnItemClick(val item: FindroidItem) : LibraryAction

    data object OnBackClick : LibraryAction

    data class ChangeSorting(val sortBy: SortBy, val sortOrder: SortOrder) : LibraryAction
}
