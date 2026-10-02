package com.zhangwenkang.cinefin.network

import java.net.InetSocketAddress
import java.security.cert.X509Certificate
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/** 服务器出示的证书信息（首次连接时展示给用户做信任确认）。 */
data class ServerCertificateInfo(
    val host: String,
    val port: Int,
    val fingerprint: String,
    val subject: String,
    val issuer: String,
    val notAfterEpochMillis: Long,
    /** 系统 CA 是否能验证这张证书（自签 / 私有 CA 时为 false）。 */
    val systemTrusted: Boolean,
) {
    val trustKey: String
        get() = trustKeyOf(host, port)
}

/**
 * 只读的证书探测：与目标做一次 TLS 握手、取出服务器出示的证书链，不做任何应用层请求。
 *
 * 探测阶段使用一次性的「接受任意证书」信任管理器——它只用于**读取**证书指纹供用户判断， 该 SSLContext 不会用于任何业务流量；业务连接始终使用
 * [buildCertificateAwareOkHttpClient] 的校验策略。
 */
class ServerCertificateProbe(
    private val connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
    private val systemTrustManager: X509TrustManager = defaultX509TrustManager(),
) {
    fun probe(host: String, port: Int): ServerCertificateInfo? {
        if (host.isBlank() || port !in 1..65535) return null
        return try {
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, arrayOf<TrustManager>(ProbeTrustManager), null)
            @Suppress("DEPRECATION")
            val socket = sslContext.socketFactory.createSocket() as SSLSocket
            socket.use {
                it.connect(InetSocketAddress(host, port), connectTimeoutMs)
                configureSni(it, host)
                it.startHandshake()

                val chain = it.session.peerCertificates.filterIsInstance<X509Certificate>()
                val leaf = chain.firstOrNull() ?: return null
                ServerCertificateInfo(
                    host = host,
                    port = port,
                    fingerprint = fingerprintOf(leaf),
                    subject = leaf.subjectX500Principal.name,
                    issuer = leaf.issuerX500Principal.name,
                    notAfterEpochMillis = leaf.notAfter.time,
                    systemTrusted = isSystemTrusted(chain, leaf),
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun isSystemTrusted(chain: List<X509Certificate>, leaf: X509Certificate): Boolean =
        try {
            systemTrustManager.checkServerTrusted(chain.toTypedArray(), authTypeOf(leaf))
            true
        } catch (_: Exception) {
            false
        }

    private fun authTypeOf(leaf: X509Certificate): String =
        when (leaf.publicKey.algorithm.uppercase()) {
            "EC" -> "EC"
            "DSA" -> "DSA"
            else -> "RSA"
        }

    private fun configureSni(socket: SSLSocket, host: String) {
        val parameters = socket.sslParameters
        parameters.serverNames =
            try {
                // IP 字面量不是合法 SNI 主机名，跳过即可。
                listOf(SNIHostName(host))
            } catch (_: IllegalArgumentException) {
                null
            }
        socket.sslParameters = parameters
    }

    private object ProbeTrustManager : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) = Unit

        override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) = Unit

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    companion object {
        const val DEFAULT_CONNECT_TIMEOUT_MS = 5_000
    }
}
