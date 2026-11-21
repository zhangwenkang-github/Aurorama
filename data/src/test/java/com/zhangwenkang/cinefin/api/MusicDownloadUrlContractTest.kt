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
 * V2-DOWNLOAD 03 契约测试：钉死 `/Audio/{itemId}/stream.{container}` 的请求形状（路由 + 静态直出 + **无凭据**）。
 *
 * 对端是 Jellyfin `AudioController.GetAudioStream`：`static=true` 表示原文件直出、不编码；容器取自媒体源元数据。
 *
 * 鉴权口径与 [MusicStreamUrlContractTest] 相同：URL 里不得出现任何令牌形态，注头由下载引擎负责。
 */
class MusicDownloadUrlContractTest {

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
    fun `带容器命中 stream 端点并强制原文件直出`() {
        val url = MusicDownloadUrl.build("https://jellyfin.example.test", itemId, "flac")!!

        val parsed = url.toHttpUrl()
        assertEquals("/Audio/3f2504e0-4f89-11d3-9a0c-0305e82c3301/stream.flac", parsed.encodedPath)
        // 只有 static 一个协议参数，没有身份 / 凭据参数
        assertEquals(setOf("static"), parsed.queryParameterNames)
        assertEquals("true", parsed.queryParameter("static"))
        parsed.assertNoCredentials(url)
    }

    @Test
    fun `容器缺失或非法时返回 null 交给调用方回退官方下载端点`() {
        assertNull(MusicDownloadUrl.build("https://jellyfin.example.test", itemId, null))
        assertNull(MusicDownloadUrl.build("https://jellyfin.example.test", itemId, ""))
        assertNull(MusicDownloadUrl.build("https://jellyfin.example.test", itemId, "  "))
        assertNull(MusicDownloadUrl.build("https://jellyfin.example.test", itemId, "."))
        // 路径穿越 / 多段容器名不得拼进 URL
        assertNull(MusicDownloadUrl.build("https://jellyfin.example.test", itemId, "../x"))
        assertNull(MusicDownloadUrl.build("https://jellyfin.example.test", itemId, "tar.gz"))
        assertNull(MusicDownloadUrl.build("https://jellyfin.example.test", itemId, "mp3/x"))
    }

    @Test
    fun `容器大小写 前导点与逗号列表被规范化`() {
        assertEquals(
            "/Audio/$itemId/stream.mp3",
            MusicDownloadUrl.build("https://jellyfin.example.test", itemId, ".MP3")!!.toHttpUrl()
                .encodedPath,
        )
        assertEquals(
            "/Audio/$itemId/stream.m4a",
            MusicDownloadUrl.build("https://jellyfin.example.test", itemId, " m4a ,flac")!!
                .toHttpUrl()
                .encodedPath,
        )
    }

    @Test
    fun `反向代理路径前缀被保留且 URL 不含任何令牌`() {
        val url = MusicDownloadUrl.build("https://host.example.test/jellyfin/", itemId, "aac")!!

        val parsed = url.toHttpUrl()
        assertEquals("/jellyfin/Audio/$itemId/stream.aac", parsed.encodedPath)
        parsed.assertNoCredentials(url)
        assertFalse(url.contains("api_key", ignoreCase = true))
        assertFalse(url.contains("token", ignoreCase = true))
        assertTrue(parsed.queryParameterNames.none { it.equals("api_key", ignoreCase = true) })
    }

    @Test
    fun `非法服务器地址返回 null 而不是伪造地址`() {
        assertNull(MusicDownloadUrl.build("", itemId, "mp3"))
        assertNull(MusicDownloadUrl.build("not-a-url", itemId, "mp3"))
    }
}
