package com.zhangwenkang.cinefin.api

import java.util.UUID
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MUSIC-03 契约测试：钉死 `/Audio/{itemId}/universal` 的请求形状（路由 + 参数名 + 值）。
 *
 * 对端是 Jellyfin `UniversalAudioController.GetUniversalAudioStream`（10.10 分支，参数名即协议）；
 * 任何一边改名都会让这里失败——测试 fake 不参与。
 */
class MusicStreamUrlContractTest {

    private val itemId = UUID.fromString("3f2504e0-4f89-11d3-9a0c-0305e82c3301")

    @Test
    fun `直连请求只带容器表与身份参数`() {
        val url =
            MusicStreamUrl.build(
                baseUrl = "https://jellyfin.example.test",
                itemId = itemId,
                userId = "user-1",
                deviceId = "device-1",
                apiKey = "token-1",
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
        assertEquals("mp3,aac,flac,m4a", parsed.queryParameter("Container"))
        assertEquals("user-1", parsed.queryParameter("UserId"))
        assertEquals("device-1", parsed.queryParameter("DeviceId"))
        assertEquals("token-1", parsed.queryParameter("api_key"))
        assertEquals("true", parsed.queryParameter("EnableRedirection"))
        // 直连不带转码参数
        assertNull(parsed.queryParameter("AudioCodec"))
        assertNull(parsed.queryParameter("AudioBitRate"))
        assertNull(parsed.queryParameter("TranscodingContainer"))
        assertNull(parsed.queryParameter("MaxStreamingBitrate"))
    }

    @Test
    fun `转码请求带 audioCodec audioBitRate transcodingContainer 与 enableRedirection`() {
        val url =
            MusicStreamUrl.build(
                baseUrl = "https://jellyfin.example.test/",
                itemId = itemId,
                userId = "user-1",
                deviceId = "device-1",
                apiKey = "token-1",
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
    }

    @Test
    fun `反向代理路径前缀被保留且不重复`() {
        val url =
            MusicStreamUrl.build(
                baseUrl = "https://host.example.test/jellyfin/",
                itemId = itemId,
                userId = null,
                deviceId = null,
                apiKey = null,
                request = MusicStreamRequest(directPlayContainers = listOf("flac")),
            )!!
        assertEquals(
            "/jellyfin/Audio/$itemId/universal",
            url.toHttpUrl().encodedPath,
        )
        // 身份缺失时不得伪造空参数
        assertNull(url.toHttpUrl().queryParameter("UserId"))
        assertNull(url.toHttpUrl().queryParameter("DeviceId"))
        assertNull(url.toHttpUrl().queryParameter("api_key"))
    }

    @Test
    fun `非法服务器地址返回 null 而不是伪造地址`() {
        assertNull(
            MusicStreamUrl.build(
                baseUrl = "",
                itemId = itemId,
                userId = "user-1",
                deviceId = "device-1",
                apiKey = "token-1",
                request = MusicStreamRequest(directPlayContainers = listOf("mp3")),
            )
        )
        assertNull(
            MusicStreamUrl.build(
                baseUrl = "not-a-url",
                itemId = itemId,
                userId = "user-1",
                deviceId = "device-1",
                apiKey = "token-1",
                request = MusicStreamRequest(directPlayContainers = listOf("mp3")),
            )
        )
    }

    @Test
    fun `令牌与设备 id 含特殊字符时按 query 编码`() {
        val url =
            MusicStreamUrl.build(
                baseUrl = "https://jellyfin.example.test",
                itemId = itemId,
                userId = "user 1",
                deviceId = "device/1",
                apiKey = "a+b=c&d",
                request = MusicStreamRequest(directPlayContainers = listOf("mp3")),
            )!!
        val parsed = url.toHttpUrl()
        assertEquals("user 1", parsed.queryParameter("UserId"))
        assertEquals("device/1", parsed.queryParameter("DeviceId"))
        assertEquals("a+b=c&d", parsed.queryParameter("api_key"))
        assertTrue(parsed.queryParameterNames.contains("api_key"))
    }
}
