package com.zhangwenkang.cinefin.setup.presentation.addserver

sealed interface AddServerAction {
    data class OnConnectClick(val address: String) : AddServerAction

    data object OnTrustCertificate : AddServerAction

    data object OnDismissCertificatePrompt : AddServerAction

    data object OnBackClick : AddServerAction
}
