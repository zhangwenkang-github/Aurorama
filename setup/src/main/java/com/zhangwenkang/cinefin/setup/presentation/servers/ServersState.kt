package com.zhangwenkang.cinefin.setup.presentation.servers

import com.zhangwenkang.cinefin.models.ServerWithAddresses
import com.zhangwenkang.cinefin.network.TrustedCertificate

data class ServersState(
    val servers: List<ServerWithAddresses> = emptyList(),
    val trustedCertificates: List<TrustedCertificate> = emptyList(),
)
