package com.zhangwenkang.cinefin.api

import java.util.UUID
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MUSIC-03 契约测试：钉死 `/Audio/{itemId}/universal` 的请求形状（路由 + 参数名 + 值 + **无凭据**）。
 *
 * 对端是 Jellyfin `UniversalAudioController.GetUniversalAudioStream`（10.10 分支，参数名即协议）。
 *
 * 鉴权口径（2026-11-20 生产 12.4.0 实测，见 `network/JellyfinAuth.kt`）：该端点无鉴权 401、带有效 `?api_key=` **同样
 * 401**，只有 `Authorization: MediaBrowser Token="…"` 请求头返回 200 与真实转码流。 所以这里反向钉住「URL 里不得出现任何令牌形态」，注头由
 * `player/local` 的 `ServerAuthDataSourceFactory` 负责（`ServerCredentialScopeTest` 覆盖）。
 */
class MusicStreamUrlContractTest {

    private val itemId = UUID.fromString("3f2504e0-4f89-11d3-9a0c-0305e82c3301")

    /** 令牌形态参数名（大小写/连字符/下划线不敏感比对）；命中即失败。 */
    private val forbiddenCredentialParams =
        setOf("API_KEY", "APIKEY", "TOKEN", "XEMBYTOKEN", "XEMBYAUTHORIZATION")

    private fun HttpUrl.assertNoCredentials(url: String) {
        queryParameterNames.forEach { name ->
            val normalized = name.uppercase().replace("-", "").replace("_", "")
            assertFalse(
                "URL 不得携带凭据参数（$name）：$url",
                normalized in forbiddenCredentialParams,
            )
        }
    }

    @Test
    fun `直连请求只带容器表与身份参数且不含任何凭据`() {
        val url =
            MusicStreamUrl.build(
                baseUrl = "https://jellyfin.example.test",
                itemId = itemId,
                userId = "user-1",
                deviceId = "device-1",
                request =
                    MusicStreamRequest(
                        directPlayContainers = listOf("mp3", "aac", "flac", "m4a"),
                        maxStreamingBitrate = null,
                    ),
            )!!

        val parsed = url.toHttpUrl()
        assertEquals(
            "/Audio/3f2504e0-4f89-11d3-9a0c-0305e82c3301/universal",
            parsed.encodedPath,
        )
        // 参数全集：协商参数 + 身份参数（UserId/DeviceId 是协议参数，不是凭据）
        assertEquals(
            setOf("Container", "UserId", "DeviceId", "EnableRedirection"),
            parsed.queryParameterNames,
        )
        assertEquals("mp3,aac,flac,m4a", parsed.queryParameter("Container"))
        assertEquals("user-1", parsed.queryParameter("UserId"))
        assertEquals("device-1", parsed.queryParameter("DeviceId"))
        assertEquals("true", parsed.queryParameter("EnableRedirection"))
        // 直连不带转码参数
        assertNull(parsed.queryParameter("AudioCodec"))
        assertNull(parsed.queryParameter("AudioBitRate"))
        assertNull(parsed.queryParameter("TranscodingContainer"))
        assertNull(parsed.queryParameter("MaxStreamingBitrate"))
        parsed.assertNoCredentials(url)
    }

    @Test
    fun `转码请求带 audioCodec audioBitRate transcodingContainer 与 enableRedirection`() {
        val url =
            MusicStreamUrl.build(
                baseUrl = "https://jellyfin.example.test/",
                itemId = itemId,
                userId = "user-1",
                deviceId = "device-1",
                request =
                    MusicStreamRequest(
                        directPlayContainers = listOf("mp3"),
                        audioCodec = "mp3",
                        transcodingContainer = "mp3",
                        transcodingProtocol = "http",
                        maxStreamingBitrate = 192_000L,
                        audioBitRate = 192_000,
                        maxAudioSampleRate = 48_000,
                        maxAudioBitDepth = 16,
                        startTimeTicks = 0L,
                    ),
            )!!

        val parsed = url.toHttpUrl()
        assertEquals(
            setOf(
                "Container",
                "UserId",
                "DeviceId",
                "AudioCodec",
                "TranscodingContainer",
                "TranscodingProtocol",
                "MaxStreamingBitrate",
                "AudioBitRate",
                "MaxAudioSampleRate",
                "MaxAudioBitDepth",
                "StartTimeTicks",
                "EnableRedirection",
            ),
            parsed.queryParameterNames,
        )
        assertEquals("mp3", parsed.queryParameter("AudioCodec"))
        assertEquals("mp3", parsed.queryParameter("TranscodingContainer"))
        assertEquals("http", parsed.queryParameter("TranscodingProtocol"))
        assertEquals("192000", parsed.queryParameter("AudioBitRate"))
        assertEquals("192000", parsed.queryParameter("MaxStreamingBitrate"))
        assertEquals("48000", parsed.queryParameter("MaxAudioSampleRate"))
        assertEquals("16", parsed.queryParameter("MaxAudioBitDepth"))
        assertEquals("0", parsed.queryParameter("StartTimeTicks"))
        assertEquals("true", parsed.queryParameter("EnableRedirection"))
        assertEquals("mp3", parsed.queryParameter("Container"))
        parsed.assertNoCredentials(url)
    }

    @Test
    fun `反向代理路径前缀被保留且不重复`() {
        val url =
            MusicStreamUrl.build(
                baseUrl = "https://host.example.test/jellyfin/",
                itemId = itemId,
                userId = null,
                deviceId = null,
                request = MusicStreamRequest(directPlayContainers = listOf("flac")),
            )!!
        val parsed = url.toHttpUrl()
        assertEquals("/jellyfin/Audio/$itemId/universal", parsed.encodedPath)
        // 身份缺失时不得伪造空参数
        assertNull(parsed.queryParameter("UserId"))
        assertNull(parsed.queryParameter("DeviceId"))
        parsed.assertNoCredentials(url)
    }

    @Test
    fun `非法服务器地址返回 null 而不是伪造地址`() {
        assertNull(
            MusicStreamUrl.build(
                baseUrl = "",
                itemId = itemId,
                userId = "user-1",
                deviceId = "device-1",
                request = MusicStreamRequest(directPlayContainers = listOf("mp3")),
            )
        )
        assertNull(
            MusicStreamUrl.build(
                baseUrl = "not-a-url",
                itemId = itemId,
                userId = "user-1",
                deviceId = "device-1",
                request = MusicStreamRequest(directPlayContainers = listOf("mp3")),
            )
        )
    }

    @Test
    fun `身份参数含特殊字符时按 query 编码且仍无凭据`() {
        val url =
            MusicStreamUrl.build(
                baseUrl = "https://jellyfin.example.test",
                itemId = itemId,
                userId = "user 1",
                deviceId = "device/1",
                request = MusicStreamRequest(directPlayContainers = listOf("mp3")),
            )!!
        val parsed = url.toHttpUrl()
        assertEquals("user 1", parsed.queryParameter("UserId"))
        assertEquals("device/1", parsed.queryParameter("DeviceId"))
        assertTrue(parsed.queryParameterNames.contains("DeviceId"))
        parsed.assertNoCredentials(url)
    }
}
