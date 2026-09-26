package com.zhangwenkang.cinefin.setup.presentation.addserver

sealed interface AddServerEvent {
    data object Success : AddServerEvent
}
