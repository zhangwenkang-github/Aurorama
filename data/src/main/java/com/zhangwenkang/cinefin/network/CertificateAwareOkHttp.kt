package com.zhangwenkang.cinefin.network

import java.security.KeyStore
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import okhttp3.OkHttpClient

/**
 * 在默认校验之上叠加「用户显式信任的证书指纹」：
 *
 * - 系统 CA 能验证的证书：行为完全不变；
 * - 自签 / 不受系统信任的证书：默认拒绝；只有该 host:port 的 SHA-256 指纹被用户确认过才放行；
 * - 主机名（SAN/CN）校验完全保持 OkHttp 默认：指纹信任不绕过主机名校验， 因此自签证书仍需把访问用的主机名 / IP 写进 SAN。
 *
 * 绝不安装「信任所有证书」的全局策略；用户可随时在服务器管理中清除信任记录。
 */
fun buildCertificateAwareOkHttpClient(
    base: OkHttpClient,
    trustStore: CertificateTrustStore,
    defaultTrustManager: X509TrustManager = defaultX509TrustManager(),
): OkHttpClient {
    val tracker = CurrentTrustKeyTracker()
    val trustManager = ToFuTrustManager(defaultTrustManager, trustStore) { tracker.get() }

    val sslContext = SSLContext.getInstance("TLS")
    sslContext.init(null, arrayOf<TrustManager>(trustManager), null)
    // 信任按「每次连接」判定：限制客户端 TLS 会话缓存，否则清除信任后旧会话仍可被复用（真机实测）。
    runCatching {
        sslContext.clientSessionContext.apply {
            sessionCacheSize = 1
            sessionTimeout = 1
        }
    }

    return base
        .newBuilder()
        .sslSocketFactory(TrackedSslSocketFactory(sslContext.socketFactory, tracker), trustManager)
        .build()
}

/** 平台默认 X509 信任管理器（系统 CA）。 */
fun defaultX509TrustManager(): X509TrustManager {
    val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
    factory.init(null as KeyStore?)
    return factory.trustManagers.filterIsInstance<X509TrustManager>().first()
}
