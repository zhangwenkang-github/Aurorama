package com.zhangwenkang.cinefin.api

import java.util.Locale
import java.util.UUID
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * `/Audio/{itemId}/stream.{container}` 单文件下载地址构造（V2-DOWNLOAD 03；纯函数 + 契约测试钉请求形状）。
 *
 * 音乐下载不再复用视频的 `/Items/{id}/Download`：前者是「条目通用下载」，容器由服务端自行决定， 断点续传与落盘容器都可能随服务端配置漂移；后者直接指向源文件容器，URL
 * 可寻址、可续传。
 *
 * `static=true` 是 Jellyfin 对「原文件直出、不编码」的显式开关（同 `MUSIC_PLAN.md` D50 的 ReplayGain 只读请求）： 不带它时服务端可能按
 * `container` 转码，下载拿到的就不是源文件。
 *
 * 鉴权**不在 URL 里**：下载引擎按请求注入 `Authorization: MediaBrowser Token="…"` （`DownloadHttpEngine`
 * 的同主机令牌拦截器），与 [MusicStreamUrl] 同一口径。
 */
object MusicDownloadUrl {
    /** 服务端路由前缀（`AudioController` 的 `Audio/{itemId}/stream.{container}`）。 */
    private const val CONTROLLER_PREFIX = "Audio"

    /** 容器名只允许安全字符；Jellyfin 的容器名形如 `mp3` / `flac` / `m4a` / `aac`。 */
    private val CONTAINER_PATTERN = Regex("^[A-Za-z0-9]+$")

    /**
     * 拼出完整下载地址。
     *
     * @param baseUrl 服务器根地址（末尾 `/` 可有可无；反代路径前缀会被保留）。
     * @param container 媒体源元数据里的容器名；缺失 / 非法时返回 null，调用方回退 `/Items/{id}/Download` 并记录日志。
     * @return 合法 HTTP 地址；[baseUrl] 非法或 [container] 不可用时返回 null（调用方按「不可用」处理，不伪造地址）。
     */
    fun build(baseUrl: String, itemId: UUID, container: String?): String? {
        val normalizedContainer = normalizeContainer(container) ?: return null
        val origin = baseUrl.trimEnd('/').toHttpUrlOrNull() ?: return null
        // newBuilder 保留 baseUrl 自带的路径前缀与端口，只清掉 query / fragment
        return origin
            .newBuilder()
            .query(null)
            .fragment(null)
            .addPathSegment(CONTROLLER_PREFIX)
            .addPathSegment(itemId.toString())
            .addPathSegment("stream.$normalizedContainer")
            .addQueryParameter("static", "true")
            .build()
            .toString()
    }

    /** 容器规范化：去空白 / 前导点、取逗号前的第一个值、小写；非法字符视为缺失。 */
    private fun normalizeContainer(container: String?): String? {
        val normalized =
            container
                ?.substringBefore(',')
                ?.trim()
                ?.removePrefix(".")
                ?.lowercase(Locale.US)
                ?.takeIf { it.isNotEmpty() } ?: return null
        return normalized.takeIf { CONTAINER_PATTERN.matches(it) }
    }
}
