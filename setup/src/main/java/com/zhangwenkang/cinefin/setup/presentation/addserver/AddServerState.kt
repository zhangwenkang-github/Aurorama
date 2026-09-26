package com.zhangwenkang.cinefin.setup.presentation.addserver

import com.zhangwenkang.cinefin.models.DiscoveredServer
import com.zhangwenkang.cinefin.models.UiText

data class AddServerState(
    val isLoading: Boolean = false,
    val discoveredServers: List<DiscoveredServer> = emptyList(),
    val error: Collection<UiText>? = null,
)
