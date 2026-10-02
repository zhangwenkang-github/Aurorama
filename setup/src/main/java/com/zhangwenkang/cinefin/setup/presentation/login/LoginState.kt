package com.zhangwenkang.cinefin.setup.presentation.login

import com.zhangwenkang.cinefin.models.UiText
import com.zhangwenkang.cinefin.setup.presentation.certificate.CertificateTrustPrompt

data class LoginState(
    val serverName: String? = null,
    val disclaimer: String? = null,
    val quickConnectEnabled: Boolean = false,
    val quickConnectCode: String? = null,
    val isLoading: Boolean = false,
    val error: UiText? = null,
    val certificatePrompt: CertificateTrustPrompt? = null,
)
