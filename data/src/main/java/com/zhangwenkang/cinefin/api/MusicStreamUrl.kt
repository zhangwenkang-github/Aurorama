package com.zhangwenkang.cinefin.api

import java.util.UUID
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * `/Audio/{itemId}/universal` 的请求参数（MUSIC-03）。
 *
 * 只承载「协商结果」，不承载决策：直连 / 转码由 `player.core` 的 `MusicAudioNegotiation` 判定， 这里只按 Jellyfin 协议把结果拼成请求。
 *
 * 参数名与服务端 `UniversalAudioController.GetUniversalAudioStream` 一一对应（大小写即协议）： `Container` /
 * `TranscodingContainer` / `TranscodingProtocol` / `AudioCodec` / `MaxStreamingBitrate` /
 * `AudioBitRate` / `MaxAudioSampleRate` / `MaxAudioBitDepth` / `EnableRedirection` / `UserId` /
 * `DeviceId` / `StartTimeTicks`。
 *
 * 鉴权**不在 URL 里**：Jellyfin 12 只认 `Authorization: MediaBrowser Token="…"` 头， `?api_key=` 查询参数在该端点直接
 * 401（`network/JellyfinAuth.kt` 的定论 + 2026-11-20 生产 12.4.0 实测）。 播放器侧的注头见 `player/local` 的
 * `ServerAuthDataSourceFactory`。
 *
 * @property directPlayContainers 设备可直连的容器（`Container`，逗号分隔）；转码时调用方应剔除源容器， 让服务端判定「不能直连」。
 * @property audioCodec 转码目标编码（`AudioCodec`）；null = 不指定（直连）。
 * @property transcodingContainer 转码目标容器（`TranscodingContainer`）；与 [audioCodec] 配套。
 * @property transcodingProtocol 转码协议（`TranscodingProtocol`）：`http` = 渐进式下载，`hls` = 分片。
 * @property maxStreamingBitrate 码率上限（`MaxStreamingBitrate`，bps）；null = 不传（服务端自由判断）。
 * @property audioBitRate 转码目标码率（`AudioBitRate`，bps）。
 * @property startTimeTicks 起播位置（`StartTimeTicks`，Jellyfin ticks，1 tick = 100 ns）。
 * @property enableRedirection 允许服务端 302 到远端直链（`EnableRedirection`）。
 */
data class MusicStreamRequest(
    val directPlayContainers: List<String>,
    val audioCodec: String? = null,
    val transcodingContainer: String? = null,
    val transcodingProtocol: String? = null,
    val maxStreamingBitrate: Long? = null,
    val audioBitRate: Int? = null,
    val maxAudioSampleRate: Int? = null,
    val maxAudioBitDepth: Int? = null,
    val startTimeTicks: Long? = null,
    val enableRedirection: Boolean = true,
)

/**
 * `/Audio/{itemId}/universal` 地址构造（MUSIC-03；纯函数 + 契约测试钉请求形状）。
 *
 * Jellyfin SDK 1.8.12 没有这个端点的封装，只能按服务端路由手工拼；把拼装集中在这里， 播放链路与测试共用同一份协议映射，避免「请求参数名写错但本地照样能播」的静默漂移。
 *
 * 本类**不接触令牌**：URL 里只有路由与协商参数，鉴权由播放器的 HTTP 数据源按请求注入（同源才注）。
 */
object MusicStreamUrl {
    /** 服务端路由前缀（`UniversalAudioController` 的 `Audio/{itemId}/universal`）。 */
    private const val CONTROLLER_PREFIX = "Audio"

    private const val UNIVERSAL_SEGMENT = "universal"

    /**
     * 拼出完整直链。
     *
     * @param baseUrl 服务器根地址（末尾 `/` 可有可无）。
     * @return 合法 HTTP 地址；[baseUrl] 非法时返回 null（调用方按「解析失败」处理，不伪造地址）。
     */
    fun build(
        baseUrl: String,
        itemId: UUID,
        userId: String?,
        deviceId: String?,
        request: MusicStreamRequest,
    ): String? {
        val origin = baseUrl.trimEnd('/').toHttpUrlOrNull() ?: return null
        // newBuilder 保留 baseUrl 自带的路径前缀（反代 /jellyfin）与端口，只清掉 query / fragment
        val builders = origin.newBuilder().query(null).fragment(null)
        builders.addPathSegment(CONTROLLER_PREFIX)
        builders.addPathSegment(itemId.toString())
        builders.addPathSegment(UNIVERSAL_SEGMENT)
        if (request.directPlayContainers.isNotEmpty()) {
            builders.addQueryParameter(
                "Container",
                request.directPlayContainers.joinToString(","),
            )
        }
        userId?.takeIf { it.isNotBlank() }?.let { builders.addQueryParameter("UserId", it) }
        deviceId?.takeIf { it.isNotBlank() }?.let { builders.addQueryParameter("DeviceId", it) }
        request.audioCodec
            ?.takeIf { it.isNotBlank() }
            ?.let { builders.addQueryParameter("AudioCodec", it) }
        request.transcodingContainer
            ?.takeIf { it.isNotBlank() }
            ?.let { builders.addQueryParameter("TranscodingContainer", it) }
        request.transcodingProtocol
            ?.takeIf { it.isNotBlank() }
            ?.let { builders.addQueryParameter("TranscodingProtocol", it) }
        request.maxStreamingBitrate?.let {
            builders.addQueryParameter("MaxStreamingBitrate", it.toString())
        }
        request.audioBitRate?.let { builders.addQueryParameter("AudioBitRate", it.toString()) }
        request.maxAudioSampleRate?.let {
            builders.addQueryParameter("MaxAudioSampleRate", it.toString())
        }
        request.maxAudioBitDepth?.let {
            builders.addQueryParameter("MaxAudioBitDepth", it.toString())
        }
        request.startTimeTicks?.let { builders.addQueryParameter("StartTimeTicks", it.toString()) }
        builders.addQueryParameter("EnableRedirection", request.enableRedirection.toString())
        return builders.build().toString()
    }
}
