package com.zhangwenkang.cinefin.network

import java.net.InetAddress
import java.net.Socket
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager

/** TOFU（Trust On First Use）信任判定：**先走系统默认校验**，只有用户显式信任过该地址的证书指纹时， 才放行这一张证书。任何其它情况都保持默认拒绝。 */
internal class ToFuTrustManager(
    private val delegate: X509TrustManager,
    private val trustStore: CertificateTrustStore,
    private val currentTrustKey: () -> String?,
) : X509TrustManager {
    override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) {
        delegate.checkClientTrusted(chain, authType)
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
        try {
            delegate.checkServerTrusted(chain, authType)
            return
        } catch (e: CertificateException) {
            // 系统校验失败：只有「当前正在连接的地址」且指纹与用户确认过的一致时才放行。
            val trustKey = currentTrustKey() ?: throw e
            val trustedFingerprint = trustStore.trustedFingerprint(trustKey) ?: throw e
            val leaf = chain.firstOrNull() ?: throw e
            if (!fingerprintsMatch(trustedFingerprint, fingerprintOf(leaf))) throw e
        }
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = delegate.acceptedIssuers
}

/**
 * 记录「当前线程正在连接哪个 host:port」。
 *
 * JSSE 的 [X509TrustManager] 接口不携带主机名，而 OkHttp 建立 TLS 连接前会调用
 * `SSLSocketFactory.createSocket(rawSocket, host, port, autoClose)`，随后在同一线程完成握手； 因此在包装的 socket
 * factory 里记下 host，即可让 trust manager 按地址判定。
 */
internal class CurrentTrustKeyTracker {
    private val threadLocal = ThreadLocal<String?>()

    fun set(trustKey: String?) {
        threadLocal.set(trustKey)
    }

    fun get(): String? = threadLocal.get()
}

/** 把 createSocket 的 host/port 记录给 [ToFuTrustManager]，其余行为原样委托给平台实现。 */
internal class TrackedSslSocketFactory(
    private val delegate: SSLSocketFactory,
    private val tracker: CurrentTrustKeyTracker,
) : SSLSocketFactory() {
    override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites

    override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites

    override fun createSocket(socket: Socket, host: String, port: Int, autoClose: Boolean): Socket {
        tracker.set(trustKeyOf(host, port))
        return delegate.createSocket(socket, host, port, autoClose)
    }

    override fun createSocket(host: String, port: Int): Socket {
        tracker.set(trustKeyOf(host, port))
        return delegate.createSocket(host, port)
    }

    override fun createSocket(
        host: String,
        port: Int,
        localHost: InetAddress,
        localPort: Int,
    ): Socket {
        tracker.set(trustKeyOf(host, port))
        return delegate.createSocket(host, port, localHost, localPort)
    }

    override fun createSocket(host: InetAddress, port: Int): Socket {
        tracker.set(trustKeyOf(host.hostAddress ?: host.hostName ?: "", port))
        return delegate.createSocket(host, port)
    }

    override fun createSocket(
        address: InetAddress,
        port: Int,
        localAddress: InetAddress,
        localPort: Int,
    ): Socket {
        tracker.set(trustKeyOf(address.hostAddress ?: address.hostName ?: "", port))
        return delegate.createSocket(address, port, localAddress, localPort)
    }
}
