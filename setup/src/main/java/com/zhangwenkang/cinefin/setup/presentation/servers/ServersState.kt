package com.zhangwenkang.cinefin.setup.presentation.servers

import com.zhangwenkang.cinefin.models.ServerWithAddresses

data class ServersState(val servers: List<ServerWithAddresses> = emptyList())
