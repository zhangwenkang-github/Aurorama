package com.zhangwenkang.cinefin.core.presentation.dummy

import com.zhangwenkang.cinefin.models.User
import java.util.UUID

val dummyUser = User(id = UUID.randomUUID(), name = "Username", serverId = "")

val dummyUsers = listOf(dummyUser)
