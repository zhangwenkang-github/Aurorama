package com.zhangwenkang.cinefin.viewmodels

import android.content.SharedPreferences
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

@HiltViewModel
class MainViewModel
@Inject
constructor(private val appPreferences: AppPreferences, private val database: ServerDatabaseDao) :
    ViewModel() {
    private val _state = MutableStateFlow(MainState())
    val state = _state.asStateFlow()

    private val _uiState = MutableStateFlow<UiState>(UiState.Loading)
    val uiState = _uiState.asStateFlow()

    sealed class UiState {
        data class Normal(val server: Server?, val user: User?) : UiState()

        data object Loading : UiState()
    }

    private val offlineModeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == appPreferences.offlineMode.backendName) {
            // 只刷新状态，不回到 Loading：否则整棵 UI 会被移出组合，设置页会闪一下并丢掉滚动位置。
            check(showLoading = false)
        }
    }

    init {
        check()
        // 离线模式开关不再重启 Activity（2026-10-01 用户反馈：打开后要停留在设置页），
        // 因此这里监听偏好变化，让 LocalOfflineMode（导航入口 / 抽屉库列表）立即跟上。
        appPreferences.sharedPreferences.registerOnSharedPreferenceChangeListener(
            offlineModeListener
        )
    }

    override fun onCleared() {
        appPreferences.sharedPreferences.unregisterOnSharedPreferenceChangeListener(
            offlineModeListener
        )
        super.onCleared()
    }

    private fun check(showLoading: Boolean = true) {
        viewModelScope.launch {
            if (showLoading) {
                _state.emit(MainState(isLoading = true))
            }
            val mainState =
                MainState(
                    isLoading = false,
                    isDynamicColors = checkIsDynamicColors(),
                    hasServers = checkHasServers(),
                    hasCurrentServer = checkHasCurrentServer(),
                    hasCurrentUser = checkHasCurrentUser(),
                    isOfflineMode = checkIsOfflineMode(),
                )
            _state.emit(mainState)
        }
    }

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

    private suspend fun checkHasServers(): Boolean {
        val nServers = database.getServersCount()
        return nServers > 0
    }

    private suspend fun checkHasCurrentServer(): Boolean {
        return appPreferences.getValue(appPreferences.currentServer)?.let {
            database.getServer(it) != null
        } == true
    }

    private suspend fun checkHasCurrentUser(): Boolean {
        return appPreferences.getValue(appPreferences.currentServer)?.let {
            database.getServerCurrentUser(it) != null
        } == true
    }

    private fun checkIsDynamicColors(): Boolean {
        return appPreferences.getValue(appPreferences.dynamicColors)
    }

    private fun checkIsOfflineMode(): Boolean {
        return appPreferences.getValue(appPreferences.offlineMode)
    }
}

data class MainState(
    val isLoading: Boolean = true,
    val isDynamicColors: Boolean = true,
    val hasServers: Boolean = false,
    val hasCurrentServer: Boolean = false,
    val hasCurrentUser: Boolean = false,
    val isOfflineMode: Boolean = false,
)
