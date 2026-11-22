package com.zhangwenkang.cinefin.player.local.network

import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.TransferListener
import com.zhangwenkang.cinefin.network.JellyfinAuth
import java.net.URI

/**
 * 播放请求用的服务器凭据快照。
 *
 * 由调用方在**每次 open 时**现读（登录 / 切换账号 / 换服务器后不需要重建播放器实例）。
 */
data class PlaybackServerCredentials(val baseUrl: String, val accessToken: String?)

/**
 * 播放器 HTTP 凭据的作用域判定（纯函数，JVM 单测覆盖）。
 *
 * 定论来源 `network/JellyfinAuth.kt`：Jellyfin 12 只接受 `Authorization: MediaBrowser Token="…"` 头，
 * `?api_key=` / `X-Emby-Token` / `Bearer` 一律 401（2026-10-17 探针 + 2026-11-20 生产 12.4.0 对
 * `/Audio/{itemId}/universal` 复测：无鉴权 401、api_key 401、带 Authorization 头 200）。
 *
 * 因此播放流不再把令牌拼进 URL，而是在这里按「请求与当前服务器同源」附加请求头；跨主机（含跨主机重定向）不带凭据。
 */
object ServerCredentialScope {
    /**
     * 是否对 [requestUrl] 附加服务器凭据。
     *
     * 判定 = scheme / host / 有效端口三者全等（同源）；任一不同（含反向代理前缀不同但同源 → 仍注头）都不注头。 解析失败、非 http(s)、[baseUrl] 为空一律
     * false（宁可不注，也不把令牌发给不明目标）。
     */
    fun shouldAttach(requestUrl: String?, baseUrl: String?): Boolean {
        val request = origin(requestUrl) ?: return false
        val server = origin(baseUrl) ?: return false
        return request == server
    }

    /**
     * 该请求要附加的请求头；不同源或无令牌时为空表。
     *
     * 头构造只走 [JellyfinAuth.headers]（唯一凭据构造点，禁止在这里自拼头字符串）。
     */
    fun headersFor(
        requestUrl: String?,
        baseUrl: String?,
        accessToken: String?,
    ): Map<String, String> =
        if (shouldAttach(requestUrl, baseUrl)) JellyfinAuth.headers(accessToken) else emptyMap()

    private fun origin(rawUrl: String?): String? {
        val url = rawUrl?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        val host = uri.host?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
        val port = if (uri.port > 0) uri.port else if (scheme == "https") 443 else 80
        return "$scheme://$host:$port"
    }
}

/**
 * ExoPlayer 的 HTTP 数据源工厂：对「当前服务器同源」的请求注入 Jellyfin 鉴权头。
 *
 * 音乐与视频共用 [com.zhangwenkang.cinefin.player.local.presentation.PlayerHolder] 的 Exo 实例，
 * 视频端点（`/Videos/{id}/stream`）在本服务器上匿名可达，所以过去不注头也能播；音乐走 `/Audio/{itemId}/universal` 必须带头，否则 401（旧实现的
 * `?api_key=` 在 Jellyfin 12 上就是 401 根因）。
 *
 * 注头时机 = 每次 [DataSource.open]：读 [credentials] 当前值 → 同源则写头，否则清空（不残留上一次的头）。 非 http(s)
 * 的条目（`file://`、下载引擎落盘的**无 scheme 裸路径**）由 [ServerAuthDataSource] 委托给 `FileDataSource`，不经过 HTTP
 * 数据源（W7-T5b：已下载音乐离线起播曾因裸路径被当 URL 报 MalformedURLException）。
 *
 * 已知边界：media3 的 [DefaultHttpDataSource] 在**同协议跨主机重定向**时会沿用请求头（它没有逐跳剥离钩子）， 这里用
 * `allowCrossProtocolRedirects = false` 兜住跨协议降级；播放地址由本 App 构造、只指向当前服务器且 不请求远端媒体（不传
 * `enableRemoteMedia`），正常情况下不会发生跨主机重定向。
 */
class ServerAuthDataSourceFactory(private val credentials: () -> PlaybackServerCredentials) :
    DataSource.Factory {

    override fun createDataSource(): DataSource = ServerAuthDataSource(credentials)
}

private class ServerAuthDataSource(private val credentials: () -> PlaybackServerCredentials) :
    DataSource {
    private val http: DefaultHttpDataSource =
        DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(false).createDataSource()
    private val file: FileDataSource = FileDataSource()

    /** 本次 open 命中的实际数据源；read/close/getUri 一律转交它。 */
    private var active: DataSource = http

    /**
     * 已下载媒体（下载引擎落盘的 `sources` 表 LOCAL 行）给的是**无 scheme 的裸文件路径** （`/storage/emulated/...`），不是注释里假设的
     * `file://`。裸路径同样路由到文件数据源， 否则会被 `DefaultHttpDataSource` 以 MalformedURLException 拒绝（W7-T5
     * 真机：已下载音乐离线起播失败）。
     */
    private fun delegateFor(uri: Uri): DataSource =
        when (uri.scheme?.lowercase()) {
            "http",
            "https" -> http
            else -> file
        }

    override fun addTransferListener(transferListener: TransferListener) {
        http.addTransferListener(transferListener)
        file.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        active = delegateFor(dataSpec.uri)
        if (active === file) {
            return file.open(dataSpec)
        }
        val current = credentials()
        // 每次 open 重建头表：令牌换了、服务器地址换了、或本条是跨主机地址，都不能沿用旧头
        http.clearAllRequestProperties()
        ServerCredentialScope.headersFor(
                requestUrl = dataSpec.uri.toString(),
                baseUrl = current.baseUrl,
                accessToken = current.accessToken,
            )
            .forEach { (name, value) -> http.setRequestProperty(name, value) }
        return http.open(dataSpec)
    }

    override fun getUri(): Uri? = active.uri

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        active.read(buffer, offset, length)

    override fun close() {
        // 两个底层源都可能处于打开态（切歌路径上前一个未关），幂等关闭
        runCatching { file.close() }
        http.close()
    }
}
