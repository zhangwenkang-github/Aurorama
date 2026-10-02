package com.zhangwenkang.cinefin.network

/**
 * 连接因「证书不受系统信任」被拒绝，需要用户确认指纹后才能继续。
 *
 * [previousFingerprint] 非空表示该地址此前信任过另一张证书（证书已变化），界面上要给出额外警告。
 */
class CertificateTrustRequiredException(
    val host: String,
    val port: Int,
    val fingerprint: String,
    val previousFingerprint: String? = null,
) : Exception("Server certificate for $host:$port needs explicit trust") {
    val trustKey: String
        get() = trustKeyOf(host, port)
}
