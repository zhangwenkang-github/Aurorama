package com.zhangwenkang.cinefin.presentation.console

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zhangwenkang.cinefin.database.ServerDatabaseDao
import com.zhangwenkang.cinefin.settings.domain.AppPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** 服务器控制台所需的信息：地址、登录态与账号。 */
data class ConsoleState(
    val loaded: Boolean = false,
    val serverName: String = "",
    val serverId: String = "",
    val baseUrl: String = "",
    val accessToken: String? = null,
    val userId: String? = null,
    /** App 侧令牌是否仍然有效；失效时不再覆盖网页端登录态，避免把网页也一起搞失效 */
    val appTokenValid: Boolean = false,
) {
    /** Web 客户端的控制台地址 */
    val consoleUrl: String
        get() = baseUrl.trimEnd('/') + "/web/#/dashboard"
}

/**
 * 控制台使用服务器自带的 Web 客户端（与官方客户端同思路），
 * 因此需要读出当前服务器的地址与访问令牌用于自动登录。
 */
@HiltViewModel
class ConsoleViewModel
@Inject
constructor(
    private val database: ServerDatabaseDao,
    private val appPreferences: AppPreferences,
) : ViewModel() {
    private val _state = MutableStateFlow(ConsoleState())
    val state = _state.asStateFlow()

    fun load() {
        if (_state.value.loaded) return
        viewModelScope.launch {
            val serverId = appPreferences.getValue(appPreferences.currentServer) ?: return@launch
            val data = database.getServerWithAddressAndUser(serverId) ?: return@launch
            val address = data.address?.address ?: return@launch
            val token = data.user?.accessToken
            _state.value =
                ConsoleState(
                    loaded = true,
                    serverName = data.server.name,
                    serverId = data.server.id,
                    baseUrl = address,
                    accessToken = token,
                    userId = data.user?.id?.toString(),
                    appTokenValid =
                        !token.isNullOrBlank() &&
                            withContext(Dispatchers.IO) { isTokenValid(address, token) },
                )
        }
    }

    /** 校验 App 保存的访问令牌是否仍然可用（服务器可能已失效该会话） */
    private fun isTokenValid(
        baseUrl: String,
        token: String,
    ): Boolean =
        runCatching {
                val request =
                    Request.Builder()
                        .url(baseUrl.trimEnd('/') + "/Users/Me")
                        .header("X-Emby-Token", token)
                        .build()
                client.newCall(request).execute().use { it.isSuccessful }
            }
            .getOrDefault(false)

    private val client =
        OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build()
}
