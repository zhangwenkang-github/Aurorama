package com.zhangwenkang.cinefin.setup.presentation.certificate

import com.zhangwenkang.cinefin.network.CertificateTrustRequiredException

/** 等待用户确认的服务器证书（首次连接或证书已变化）。 */
data class CertificateTrustPrompt(
    val trustKey: String,
    val host: String,
    val port: Int,
    val fingerprint: String,
    val previousFingerprint: String? = null,
)

fun CertificateTrustRequiredException.toPrompt(): CertificateTrustPrompt =
    CertificateTrustPrompt(
        trustKey = trustKey,
        host = host,
        port = port,
        fingerprint = fingerprint,
        previousFingerprint = previousFingerprint,
    )
