package com.zhangwenkang.cinefin.film.presentation.home

import com.zhangwenkang.cinefin.models.FindroidCollection
import com.zhangwenkang.cinefin.models.FindroidItem

sealed interface HomeAction {
    data class OnItemClick(val item: FindroidItem) : HomeAction

    data class OnLibraryClick(val library: FindroidCollection) : HomeAction

    data object OnRetryClick : HomeAction

    data object OnSearchClick : HomeAction

    data object OnSettingsClick : HomeAction

    data object OnManageServers : HomeAction
}
