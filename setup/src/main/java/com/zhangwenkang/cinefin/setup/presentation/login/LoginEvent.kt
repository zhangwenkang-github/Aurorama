package com.zhangwenkang.cinefin.setup.presentation.login

sealed interface LoginEvent {
    data object Success : LoginEvent
}
