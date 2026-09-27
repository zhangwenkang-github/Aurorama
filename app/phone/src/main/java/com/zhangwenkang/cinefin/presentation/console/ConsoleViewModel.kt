package com.zhangwenkang.cinefin.presentation.console

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zhangwenkang.cinefin.database.ServerDatabaseDao
import com.zhangwenkang.cinefin.settings.domain.AppPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 服务器控制台所需的信息：地址、登录态与账号。 */
data class ConsoleState(
    val loaded: Boolean = false,
    val serverName: String = "",
    val serverId: String = "",
    val baseUrl: String = "",
    val accessToken: String? = null,
    val userId: String? = null,
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
            _state.value =
                ConsoleState(
                    loaded = true,
                    serverName = data.server.name,
                    serverId = data.server.id,
                    baseUrl = address,
                    accessToken = data.user?.accessToken,
                    userId = data.user?.id?.toString(),
                )
        }
    }
}
