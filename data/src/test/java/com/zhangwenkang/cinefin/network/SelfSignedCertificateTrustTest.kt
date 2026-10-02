package com.zhangwenkang.cinefin.network

import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import java.net.InetSocketAddress
import java.security.KeyStore
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自签证书 TOFU 的本地联调证据（真实 TLS 握手，不依赖真机 / 服务器）：
 *
 * ① 默认 OkHttp 拒绝自签证书；② 确认指纹并写入信任后连接成功；③ 清除信任后再次拒绝； ④ 信任只对记录的主机名生效；⑤ 主机名不匹配（局域网 IP 常见）时凭已确认的指纹放行。
 */
class SelfSignedCertificateTrustTest {
    private val servers = mutableListOf<HttpsServer>()

    @After
    fun stopServers() {
        servers.forEach { it.stop(0) }
    }

    @Test
    fun `默认 OkHttp 拒绝自签证书`() {
        val server = startServer("/self-signed-localhost.p12")
        val result = fetch(OkHttpClient(), "https://127.0.0.1:${server.address.port}/")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull().hasSslCause())
    }

    @Test
    fun `指纹确认后连接成功且清除信任后再次拒绝`() {
        val server = startServer("/self-signed-localhost.p12")
        val port = server.address.port
        val url = "https://127.0.0.1:$port/"
        val store = InMemoryCertificateTrustStore()

        val info = requireNotNull(ServerCertificateProbe().probe("127.0.0.1", port))
        assertFalse("自签证书不应被系统信任", info.systemTrusted)

        // 未确认指纹前：拒绝
        assertTrue(fetch(newClient(store), url).isFailure)

        // 用户确认指纹：连接成功
        store.trust(info.trustKey, info.fingerprint)
        assertEquals(200, fetch(newClient(store), url).getOrThrow())

        // 清除信任：立即回到默认拒绝
        store.clear(info.trustKey)
        assertTrue(fetch(newClient(store), url).isFailure)
    }

    @Test
    fun `信任只对记录的主机名生效`() {
        val server = startServer("/self-signed-localhost.p12")
        val port = server.address.port
        val store = InMemoryCertificateTrustStore()

        val info = requireNotNull(ServerCertificateProbe().probe("127.0.0.1", port))
        // 指纹记在 localhost 名下，连接 127.0.0.1 不应被自动授权
        store.trust(trustKeyOf("localhost", port), info.fingerprint)

        assertTrue(fetch(newClient(store), "https://127.0.0.1:$port/").isFailure)
    }

    @Test
    fun `指纹信任不绕过主机名校验`() {
        // 证书只签给 cinefin.invalid，连接 127.0.0.1 时即便指纹已信任，主机名校验仍应拒绝
        val server = startServer("/self-signed-wronghost.p12")
        val port = server.address.port
        val store = InMemoryCertificateTrustStore()

        val info = requireNotNull(ServerCertificateProbe().probe("127.0.0.1", port))
        store.trust(info.trustKey, info.fingerprint)

        assertTrue(fetch(newClient(store), "https://127.0.0.1:$port/").isFailure)
    }

    private fun newClient(store: CertificateTrustStore): OkHttpClient =
        buildCertificateAwareOkHttpClient(OkHttpClient(), store)

    private fun fetch(client: OkHttpClient, url: String): Result<Int> = runCatching {
        client.newCall(Request.Builder().url(url).build()).execute().use { it.code }
    }

    private fun Throwable?.hasSslCause(): Boolean {
        var current = this
        while (current != null) {
            if (current is SSLException) return true
            current = current.cause
        }
        return false
    }

    private fun startServer(resource: String): HttpsServer {
        val keyStore = KeyStore.getInstance("PKCS12")
        javaClass.getResourceAsStream(resource).use { keyStore.load(it, PASSWORD) }

        val keyManagerFactory =
            KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
                init(keyStore, PASSWORD)
            }
        val sslContext =
            SSLContext.getInstance("TLS").apply { init(keyManagerFactory.keyManagers, null, null) }

        val server = HttpsServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.httpsConfigurator = HttpsConfigurator(sslContext)
        server.createContext("/") { exchange ->
            val body = "cinefin".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        servers += server
        return server
    }

    private class InMemoryCertificateTrustStore : CertificateTrustStore {
        private val entries = linkedMapOf<String, String>()

        override fun trustedFingerprint(trustKey: String): String? = entries[trustKey]

        override fun trustedCertificates(): List<TrustedCertificate> =
            entries.map { (trustKey, fingerprint) ->
                TrustedCertificate(trustKey, fingerprint)
            }

        override fun trust(trustKey: String, fingerprint: String) {
            normalizeFingerprint(fingerprint)?.let { entries[trustKey] = it }
        }

        override fun clear(trustKey: String) {
            entries.remove(trustKey)
        }
    }

    companion object {
        private val PASSWORD = "cinefin".toCharArray()
    }
}
