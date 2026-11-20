package com.zhangwenkang.cinefin.player.local.network

import com.zhangwenkang.cinefin.network.JellyfinAuth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 播放请求凭证作用域（W5-T4r）：同源才注头、跨主机不注、令牌动态读取。
 *
 * 定论口径见 `network/JellyfinAuth.kt`：Jellyfin 12 只接受 `Authorization: MediaBrowser Token="…"`，
 * `?api_key=` 在 `/Audio/{itemId}/universal` 上直接 401（2026-11-20 生产 12.4.0 实测）。
 */
class ServerCredentialScopeTest {

    private val baseUrl = "https://jellyfins.example.test"
    private val token = "s3cr3t-token"

    @Test
    fun `同源请求注入 Authorization 头`() {
        val headers =
            ServerCredentialScope.headersFor(
                requestUrl = "$baseUrl/Audio/abc/universal?Container=mp3&AudioCodec=mp3",
                baseUrl = baseUrl,
                accessToken = token,
            )
        assertEquals(mapOf(JellyfinAuth.HEADER to "MediaBrowser Token=\"$token\""), headers)
    }

    @Test
    fun `反向代理路径前缀不影响同源判定`() {
        val headers =
            ServerCredentialScope.headersFor(
                requestUrl = "https://host.example.test/jellyfin/Audio/abc/universal",
                baseUrl = "https://host.example.test/jellyfin",
                accessToken = token,
            )
        assertEquals(setOf(JellyfinAuth.HEADER), headers.keys)
    }

    @Test
    fun `跨主机请求不注头`() {
        val headers =
            ServerCredentialScope.headersFor(
                requestUrl = "https://cdn.other.test/Audio/abc/universal",
                baseUrl = baseUrl,
                accessToken = token,
            )
        assertTrue("跨主机必须为空表，实际 $headers", headers.isEmpty())
    }

    @Test
    fun `端口不同不注头`() {
        assertEquals(
            emptyMap<String, String>(),
            ServerCredentialScope.headersFor(
                requestUrl = "https://jellyfins.example.test:8096/Audio/abc/universal",
                baseUrl = baseUrl,
                accessToken = token,
            ),
        )
    }

    @Test
    fun `协议不同不注头`() {
        // 同主机但 http 降级：不得把令牌从 https 服务器发给 http 目标
        assertEquals(
            emptyMap<String, String>(),
            ServerCredentialScope.headersFor(
                requestUrl = "http://jellyfins.example.test/Audio/abc/universal",
                baseUrl = baseUrl,
                accessToken = token,
            ),
        )
    }

    @Test
    fun `无令牌或地址非法时不注头`() {
        val requestUrl = "$baseUrl/Audio/abc/universal"
        assertEquals(
            emptyMap<String, String>(),
            ServerCredentialScope.headersFor(requestUrl, baseUrl, null),
        )
        assertEquals(
            emptyMap<String, String>(),
            ServerCredentialScope.headersFor(requestUrl, baseUrl, "  "),
        )
        assertEquals(
            emptyMap<String, String>(),
            ServerCredentialScope.headersFor(requestUrl, "", token),
        )
        assertEquals(
            emptyMap<String, String>(),
            ServerCredentialScope.headersFor("not-a-url", baseUrl, token),
        )
        assertEquals(
            emptyMap<String, String>(),
            ServerCredentialScope.headersFor("content://media/song.flac", baseUrl, token),
        )
        assertEquals(
            emptyMap<String, String>(),
            ServerCredentialScope.headersFor(requestUrl, null, token),
        )
    }

    @Test
    fun `令牌在请求时现读_换号后同一请求得到新头`() {
        // 工厂的 credentials 闭包在每次 open 时现读（登录 / 切换账号后不重建播放器）；
        // 这里验证该闭包语义对应的纯函数路径：同一 URL 因令牌变化得到不同头值。
        var current: String? = "old-token"
        val credentialProvider = { PlaybackServerCredentials(baseUrl, current) }
        val url = "$baseUrl/Audio/a/universal"
        assertEquals(
            "MediaBrowser Token=\"old-token\"",
            ServerCredentialScope.headersFor(
                    url,
                    credentialProvider().baseUrl,
                    credentialProvider().accessToken,
                )
                .getValue(JellyfinAuth.HEADER),
        )
        current = "new-token"
        assertEquals(
            "MediaBrowser Token=\"new-token\"",
            ServerCredentialScope.headersFor(
                    url,
                    credentialProvider().baseUrl,
                    credentialProvider().accessToken,
                )
                .getValue(JellyfinAuth.HEADER),
        )
    }

    @Test
    fun `头表里只有 Authorization_不含历史头与查询参数`() {
        val headers = ServerCredentialScope.headersFor("$baseUrl/Audio/a/universal", baseUrl, token)
        assertEquals(1, headers.size)
        assertTrue(headers.containsKey(JellyfinAuth.HEADER))
        assertFalse(headers.containsKey(JellyfinAuth.LEGACY_HEADER))
        assertFalse(headers.keys.any { it.equals("api_key", ignoreCase = true) })
        // 令牌只出现在头值里，不带任何 URL 形态
        assertFalse(headers.values.any { it.contains("api_key=") })
    }
}
