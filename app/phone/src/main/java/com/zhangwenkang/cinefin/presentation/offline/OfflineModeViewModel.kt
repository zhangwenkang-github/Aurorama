package com.zhangwenkang.cinefin.presentation.offline

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zhangwenkang.cinefin.settings.domain.OfflineModeWritePort
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.launch

/**
 * W36 离线模式开关：欢迎 / 服务器 / 用户 / 登录页的「离线模式」入口与「登录成功后自动退出离线模式」都走它。
 *
 * 只写 [OfflineModeWritePort]；`MainViewModel` 监听该偏好并刷新 `LocalOfflineMode` 与导航 IA。
 */
@HiltViewModel
class OfflineModeViewModel
@Inject
constructor(private val offlineModeWritePort: OfflineModeWritePort) : ViewModel() {

    fun enterOfflineMode() {
        viewModelScope.launch { offlineModeWritePort.setOfflineMode(true) }
    }

    fun exitOfflineMode() {
        viewModelScope.launch { offlineModeWritePort.setOfflineMode(false) }
    }
}
