package com.zhangwenkang.cinefin.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zhangwenkang.cinefin.database.ServerDatabaseDao
import com.zhangwenkang.cinefin.models.Server
import com.zhangwenkang.cinefin.models.User
import com.zhangwenkang.cinefin.settings.domain.AppPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * TV 端本地的启动信息视图模型。
 *
 * core 的 `MainViewModel` 在 W1B 合并后只保留 `state`，不再提供 `uiState` / `loadServerAndUser()`。
 *
 * TV 交付仍处于冻结状态，因此在这里保留旧契约，逻辑照抄 W1B 之前的实现：不监听偏好变化，也不做额外刷新。
 */
@HiltViewModel
class TvMainViewModel
@Inject
constructor(private val appPreferences: AppPreferences, private val database: ServerDatabaseDao) :
    ViewModel() {

    private val _uiState = MutableStateFlow<UiState>(UiState.Loading)
    val uiState = _uiState.asStateFlow()

    sealed class UiState {
        data class Normal(val server: Server?, val user: User?) : UiState()

        data object Loading : UiState()
    }

    /** 读取当前服务器绑定的服务器记录与用户。 */
    fun loadServerAndUser() {
        viewModelScope.launch {
            val serverId = appPreferences.getValue(appPreferences.currentServer)
            serverId?.let { id ->
                database.getServerWithAddressAndUser(id)?.let { data ->
                    _uiState.emit(UiState.Normal(data.server, data.user))
                }
            }
        }
    }
}
