package com.zhangwenkang.cinefin.setup.presentation.users

import com.zhangwenkang.cinefin.models.User

data class UsersState(
    val users: List<User> = emptyList(),
    val publicUsers: List<User> = emptyList(),
    val serverName: String? = null,
)
