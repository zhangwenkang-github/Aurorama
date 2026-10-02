package com.zhangwenkang.cinefin.api

import android.content.Context
import com.zhangwenkang.cinefin.data.BuildConfig
import com.zhangwenkang.cinefin.network.CertificateTrustStore
import com.zhangwenkang.cinefin.network.ServerCertificateProbe
import com.zhangwenkang.cinefin.network.SharedPreferencesCertificateTrustStore
import com.zhangwenkang.cinefin.network.buildCertificateAwareOkHttpClient
import com.zhangwenkang.cinefin.settings.domain.Constants
import java.util.UUID
import kotlin.time.DurationUnit
import kotlin.time.toDuration
import okhttp3.OkHttpClient
import org.jellyfin.sdk.api.client.HttpClientOptions
import org.jellyfin.sdk.api.client.extensions.brandingApi
import org.jellyfin.sdk.api.client.extensions.devicesApi
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.api.client.extensions.mediaInfoApi
import org.jellyfin.sdk.api.client.extensions.mediaSegmentsApi
import org.jellyfin.sdk.api.client.extensions.playStateApi
import org.jellyfin.sdk.api.client.extensions.playlistsApi
import org.jellyfin.sdk.api.client.extensions.quickConnectApi
import org.jellyfin.sdk.api.client.extensions.sessionApi
import org.jellyfin.sdk.api.client.extensions.suggestionsApi
import org.jellyfin.sdk.api.client.extensions.systemApi
import org.jellyfin.sdk.api.client.extensions.trickplayApi
import org.jellyfin.sdk.api.client.extensions.tvShowsApi
import org.jellyfin.sdk.api.client.extensions.userApi
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.api.client.extensions.userViewsApi
import org.jellyfin.sdk.api.client.extensions.videosApi
import org.jellyfin.sdk.api.okhttp.OkHttpFactory
import org.jellyfin.sdk.createJellyfin
import org.jellyfin.sdk.model.ClientInfo

/** 客户端在 HTTP 头中使用的名称，保持 ASCII 且不随界面语言变化。 */
private const val CLIENT_NAME = "Cinefin"

/**
 * Jellyfin API class using org.jellyfin.sdk:jellyfin-platform-android
 *
 * @param androidContext The context
 * @param socketTimeout The socket timeout
 * @param certificateTrustStore 用户显式信任的自签证书指纹（TOFU）
 * @param certificateProbe 只读证书探测（首次连接时取指纹给用户确认）
 * @constructor Creates a new [JellyfinApi] instance
 */
class JellyfinApi(
    androidContext: Context,
    requestTimeout: Long = Constants.NETWORK_DEFAULT_REQUEST_TIMEOUT,
    connectTimeout: Long = Constants.NETWORK_DEFAULT_CONNECT_TIMEOUT,
    socketTimeout: Long = Constants.NETWORK_DEFAULT_SOCKET_TIMEOUT,
    val certificateTrustStore: CertificateTrustStore =
        SharedPreferencesCertificateTrustStore(androidContext),
    val certificateProbe: ServerCertificateProbe =
        ServerCertificateProbe(
            connectTimeoutMs = connectTimeout.coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
        ),
) {
    // 自签证书支持：默认校验 + 用户确认过的指纹，二者之外一律拒绝（不做全局放行）。
    private val okHttpFactory =
        OkHttpFactory(buildCertificateAwareOkHttpClient(OkHttpClient(), certificateTrustStore))

    val jellyfin = createJellyfin {
        clientInfo =
            ClientInfo(
                // 必须使用固定的 ASCII 名称：HTTP 头不允许非 ASCII 字符，
                // 而应用显示名会随语言变化（中文为“影阁”），不能直接用作客户端标识。
                name = CLIENT_NAME,
                version = BuildConfig.VERSION_NAME,
            )
        context = androidContext
        apiClientFactory = okHttpFactory
        socketConnectionFactory = okHttpFactory
    }
    val api =
        jellyfin.createApi(
            httpClientOptions =
                HttpClientOptions(
                    requestTimeout = requestTimeout.toDuration(DurationUnit.MILLISECONDS),
                    connectTimeout = connectTimeout.toDuration(DurationUnit.MILLISECONDS),
                    socketTimeout = socketTimeout.toDuration(DurationUnit.MILLISECONDS),
                )
        )
    var userId: UUID? = null

    val brandingApi = api.brandingApi
    val devicesApi = api.devicesApi
    val itemsApi = api.itemsApi
    val mediaInfoApi = api.mediaInfoApi
    val mediaSegmentsApi = api.mediaSegmentsApi
    val playlistsApi = api.playlistsApi
    val playStateApi = api.playStateApi
    val quickConnectApi = api.quickConnectApi
    val sessionApi = api.sessionApi
    val showsApi = api.tvShowsApi
    val suggestionsApi = api.suggestionsApi
    val systemApi = api.systemApi
    val trickplayApi = api.trickplayApi
    val userApi = api.userApi
    val userLibraryApi = api.userLibraryApi
    val videosApi = api.videosApi
    val viewsApi = api.userViewsApi

    companion object {
        @Volatile private var INSTANCE: JellyfinApi? = null

        fun getInstance(
            context: Context,
            requestTimeout: Long = Constants.NETWORK_DEFAULT_REQUEST_TIMEOUT,
            connectTimeout: Long = Constants.NETWORK_DEFAULT_CONNECT_TIMEOUT,
            socketTimeout: Long = Constants.NETWORK_DEFAULT_SOCKET_TIMEOUT,
        ): JellyfinApi {
            synchronized(this) {
                var instance = INSTANCE
                if (instance == null) {
                    instance =
                        JellyfinApi(
                            androidContext = context.applicationContext,
                            requestTimeout = requestTimeout,
                            connectTimeout = connectTimeout,
                            socketTimeout = socketTimeout,
                        )
                    INSTANCE = instance
                }
                return instance
            }
        }
    }
}
