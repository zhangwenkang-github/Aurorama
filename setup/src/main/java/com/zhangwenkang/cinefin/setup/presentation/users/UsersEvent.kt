package com.zhangwenkang.cinefin.setup.presentation.users

sealed interface UsersEvent {
    data object NavigateToHome : UsersEvent
}
