package com.zhangwenkang.cinefin.presentation.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zhangwenkang.cinefin.database.ServerDatabaseDao
import com.zhangwenkang.cinefin.settings.domain.AppPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 抽屉导航中展示的账号与服务器信息。 */
data class DrawerState(
    val serverName: String? = null,
    val serverAddress: String? = null,
    val userName: String? = null,
)

/**
 * 抽屉导航栏的数据源：读取当前服务器与账号，让用户随时知道自己连的是哪台服务器。
 */
@HiltViewModel
class DrawerViewModel
@Inject
constructor(
    private val database: ServerDatabaseDao,
    private val appPreferences: AppPreferences,
) : ViewModel() {
    private val _state = MutableStateFlow(DrawerState())
    val state = _state.asStateFlow()

    fun load() {
        viewModelScope.launch {
            val serverId = appPreferences.getValue(appPreferences.currentServer) ?: return@launch
            val server = database.getServer(serverId)
            val address = runCatching { database.getServerCurrentAddress(serverId) }.getOrNull()
            val user = runCatching { database.getServerCurrentUser(serverId) }.getOrNull()
            _state.value =
                DrawerState(
                    serverName = server?.name,
                    serverAddress = address?.address,
                    userName = user?.name,
                )
        }
    }
}
