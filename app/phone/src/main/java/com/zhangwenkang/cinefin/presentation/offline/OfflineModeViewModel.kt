package com.zhangwenkang.cinefin.presentation.offline

import androidx.lifecycle.ViewModel
import com.zhangwenkang.cinefin.settings.domain.AppPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/**
 * W36 离线模式开关：欢迎 / 服务器 / 用户 / 登录页的「离线模式」入口与「登录成功后自动退出离线模式」都走它。
 *
 * 只写 `AppPreferences.offlineMode`；`MainViewModel` 监听该偏好并刷新 `LocalOfflineMode` 与导航 IA。
 */
@HiltViewModel
class OfflineModeViewModel @Inject constructor(private val appPreferences: AppPreferences) :
    ViewModel() {

    fun enterOfflineMode() {
        appPreferences.setValue(appPreferences.offlineMode, true)
    }

    fun exitOfflineMode() {
        appPreferences.setValue(appPreferences.offlineMode, false)
    }
}
