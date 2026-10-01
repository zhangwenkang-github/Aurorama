package com.zhangwenkang.cinefin.presentation.navigation

import android.content.SharedPreferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zhangwenkang.cinefin.database.ServerDatabaseDao
import com.zhangwenkang.cinefin.models.FindroidCollection
import com.zhangwenkang.cinefin.repository.JellyfinRepository
import com.zhangwenkang.cinefin.settings.domain.AppPreferences
import com.zhangwenkang.cinefin.settings.domain.models.CatalogLibrary
import com.zhangwenkang.cinefin.settings.domain.models.LibraryCatalog
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import javax.inject.Provider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

/** 侧栏（平板侧轨 / 平板抽屉）条目可见性；由「客户端设置 → 侧栏显示」维护。 */
data class SidebarVisibility(
    val home: Boolean = true,
    val media: Boolean = true,
    val music: Boolean = true,
    val bookshelf: Boolean = true,
    val downloads: Boolean = true,
    val console: Boolean = true,
    val metadata: Boolean = true,
)

/** 抽屉导航中展示的账号与服务器信息。 */
data class DrawerState(
    val serverName: String? = null,
    val serverAddress: String? = null,
    val userName: String? = null,
    /** 当前账号是否为管理员：决定「服务器控制台」入口是否出现 */
    val isAdministrator: Boolean = false,
    /** 服务器上的全部媒体库（含音乐库 / 图书库 / 家庭视频 / 播放列表）。 抽屉里像官方客户端那样直接列出来，不用先点进「媒体库」再找。 */
    val libraries: List<FindroidCollection> = emptyList(),
    /** 侧栏条目可见性（客户端设置里可改）。 */
    val sidebarVisibility: SidebarVisibility = SidebarVisibility(),
)

/** 抽屉导航栏的数据源：读取当前服务器与账号，让用户随时知道自己连的是哪台服务器。 */
@HiltViewModel
class DrawerViewModel
@Inject
constructor(
    private val database: ServerDatabaseDao,
    private val appPreferences: AppPreferences,
    /**
     * 离线模式开关不重启 Activity（W6-R6N），所以这里按需取仓库：每次 load() 都按**当前**偏好解析 在线 /
     * 离线实现，切回在线后侧轨库列表能立刻回来（内容页仍在下一次启动完全切换）。
     */
    private val repositoryProvider: Provider<JellyfinRepository>,
) : ViewModel() {
    private val _state = MutableStateFlow(DrawerState())
    val state = _state.asStateFlow()

    /** 侧栏可见性开关：设置页改完偏好后主界面要立刻跟着变——靠 SharedPreferences 变更回调刷新， 不重启 Activity，也不重拉服务器数据。 */
    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key != null && key.startsWith(SIDEBAR_PREF_PREFIX)) {
            _state.value = _state.value.copy(sidebarVisibility = readSidebarVisibility())
        }
    }

    init {
        appPreferences.sharedPreferences.registerOnSharedPreferenceChangeListener(
            preferenceListener
        )
        _state.value = _state.value.copy(sidebarVisibility = readSidebarVisibility())
    }

    override fun onCleared() {
        appPreferences.sharedPreferences.unregisterOnSharedPreferenceChangeListener(
            preferenceListener
        )
        super.onCleared()
    }

    fun load() {
        viewModelScope.launch {
            runCatching {
                val serverId =
                    appPreferences.getValue(appPreferences.currentServer) ?: return@launch
                val server = runCatching { database.getServer(serverId) }.getOrNull()
                val address = runCatching { database.getServerCurrentAddress(serverId) }.getOrNull()
                val user = runCatching { database.getServerCurrentUser(serverId) }.getOrNull()
                val repository = repositoryProvider.get()
                val isAdministrator = runCatching {
                    repository.isCurrentUserAdministrator()
                }
                    .getOrDefault(false)
                val libraries = runCatching { repository.getLibraries() }.getOrDefault(emptyList())
                // 媒体库目录缓存：设置模块不能依赖 data 层，设置页的「使用哪个媒体库」读这份缓存。
                // 拉取失败（离线 / 服务器不可达）时保留上一次缓存，不写空值。
                if (libraries.isNotEmpty()) {
                    val catalog =
                        LibraryCatalog.encode(
                            libraries.map { library ->
                                CatalogLibrary(
                                    id = library.id.toString(),
                                    name = library.name,
                                    type = library.type.type,
                                )
                            }
                        )
                    if (catalog != appPreferences.getValue(appPreferences.uiLibraryCatalog)) {
                        appPreferences.setValue(appPreferences.uiLibraryCatalog, catalog)
                    }
                }
                _state.value =
                    DrawerState(
                        serverName = server?.name,
                        serverAddress = address?.address,
                        userName = user?.name,
                        isAdministrator = isAdministrator,
                        libraries = libraries,
                        sidebarVisibility = readSidebarVisibility(),
                    )
            }
                .onFailure { Timber.w(it, "读取抽屉账号信息失败") }
        }
    }

    private fun readSidebarVisibility() =
        SidebarVisibility(
            home = appPreferences.getValue(appPreferences.uiSidebarShowHome),
            media = appPreferences.getValue(appPreferences.uiSidebarShowMedia),
            music = appPreferences.getValue(appPreferences.uiSidebarShowMusic),
            bookshelf = appPreferences.getValue(appPreferences.uiSidebarShowBookshelf),
            downloads = appPreferences.getValue(appPreferences.uiSidebarShowDownloads),
            console = appPreferences.getValue(appPreferences.uiSidebarShowConsole),
            metadata = appPreferences.getValue(appPreferences.uiSidebarShowMetadata),
        )

    private companion object {
        const val SIDEBAR_PREF_PREFIX = "pref_ui_sidebar_"
    }
}
