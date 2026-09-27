package com.zhangwenkang.cinefin.presentation.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zhangwenkang.cinefin.database.ServerDatabaseDao
import com.zhangwenkang.cinefin.models.FindroidCollection
import com.zhangwenkang.cinefin.repository.JellyfinRepository
import com.zhangwenkang.cinefin.settings.domain.AppPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

/** 抽屉导航中展示的账号与服务器信息。 */
data class DrawerState(
    val serverName: String? = null,
    val serverAddress: String? = null,
    val userName: String? = null,
    /** 当前账号是否为管理员：决定「服务器控制台」入口是否出现 */
    val isAdministrator: Boolean = false,
    /**
     * 服务器上的全部媒体库（含音乐库 / 图书库 / 家庭视频 / 播放列表）。
     * 抽屉里像官方客户端那样直接列出来，不用先点进「媒体库」再找。
     */
    val libraries: List<FindroidCollection> = emptyList(),
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
    private val repository: JellyfinRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(DrawerState())
    val state = _state.asStateFlow()

    fun load() {
        viewModelScope.launch {
            runCatching {
                    val serverId =
                        appPreferences.getValue(appPreferences.currentServer) ?: return@launch
                    val server = runCatching { database.getServer(serverId) }.getOrNull()
                    val address =
                        runCatching { database.getServerCurrentAddress(serverId) }.getOrNull()
                    val user = runCatching { database.getServerCurrentUser(serverId) }.getOrNull()
                    val isAdministrator =
                        runCatching { repository.isCurrentUserAdministrator() }.getOrDefault(false)
                    val libraries =
                        runCatching { repository.getLibraries() }.getOrDefault(emptyList())
                    _state.value =
                        DrawerState(
                            serverName = server?.name,
                            serverAddress = address?.address,
                            userName = user?.name,
                            isAdministrator = isAdministrator,
                            libraries = libraries,
                        )
                }
                .onFailure { Timber.w(it, "读取抽屉账号信息失败") }
        }
    }
}
