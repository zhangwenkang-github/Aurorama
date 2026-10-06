package com.zhangwenkang.cinefin.presentation.navigation

import android.content.SharedPreferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zhangwenkang.cinefin.database.ServerDatabaseDao
import com.zhangwenkang.cinefin.local.LocalLibraryRepository
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
    val video: Boolean = true,
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
    /** 侧栏「本地媒体库」子分组的行（W53B）：只含库级「在媒体库显示」打开的本地库； 空列表 = 整组隐藏。 本地库只读本机索引，离线模式下同样可用（与服务器库列表无关）。 */
    val localLibraries: List<SidebarLocalLibrary> = emptyList(),
    /** 侧栏条目可见性（客户端设置里可改）。 */
    val sidebarVisibility: SidebarVisibility = SidebarVisibility(),
    /** 「隐藏底栏」（W42）：紧凑形态隐藏底部导航栏；平板形态本无底栏（侧轨常驻）。 */
    val hideBottomBar: Boolean = false,
)

/** 抽屉导航栏的数据源：读取当前服务器与账号，让用户随时知道自己连的是哪台服务器。 */
@HiltViewModel
class DrawerViewModel
@Inject
constructor(
    private val database: ServerDatabaseDao,
    private val appPreferences: AppPreferences,
    /** W53B：侧栏「本地媒体库」子分组的只读数据源（本机 Room 索引，不依赖服务器）。 */
    private val localLibraryRepository: LocalLibraryRepository,
    /**
     * 离线模式开关不重启 Activity（W6-R6N），所以这里按需取仓库：每次 load() 都按**当前**偏好解析 在线 /
     * 离线实现，切回在线后侧轨库列表能立刻回来（内容页仍在下一次启动完全切换）。
     */
    private val repositoryProvider: Provider<JellyfinRepository>,
) : ViewModel() {
    private val _state = MutableStateFlow(DrawerState())
    val state = _state.asStateFlow()

    /**
     * 上一次已解析的当前账号 id（W75 #2）。
     *
     * 同进程内切换账号不会重建 Activity，本 ViewModel 跨账号存活——靠它识别「换账号了」， 从而在换账号时重新判定管理员能力（[refreshAccount]）。
     */
    private var resolvedUserId: String? = null

    /** 侧栏可见性开关：设置页改完偏好后主界面要立刻跟着变——靠 SharedPreferences 变更回调刷新， 不重启 Activity，也不重拉服务器数据。 */
    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when {
            key == null -> Unit
            key.startsWith(SIDEBAR_PREF_PREFIX) ->
                _state.value = _state.value.copy(sidebarVisibility = readSidebarVisibility())
            key == HIDE_BOTTOM_BAR_PREF_KEY ->
                _state.value =
                    _state.value.copy(
                        hideBottomBar = appPreferences.getValue(appPreferences.hideBottomBar)
                    )
        }
    }

    init {
        appPreferences.sharedPreferences.registerOnSharedPreferenceChangeListener(
            preferenceListener
        )
        _state.value =
            _state.value.copy(
                sidebarVisibility = readSidebarVisibility(),
                hideBottomBar = appPreferences.getValue(appPreferences.hideBottomBar),
            )
        // W75 #2：冷启动首帧就把「上次已确认过的管理员身份」带出来——不能等联网确认回来
        seedAdministratorFromCache()
    }

    /**
     * W75 #2：管理员能力判定「首帧即正确」。
     *
     * 抽屉条目由 [DrawerState.isAdministrator] 门控，而联网确认（`isCurrentUserAdministrator()`） 有往返延迟：此前状态默认
     * false，管理员第一次打开抽屉会先看到「没有控制台 / 资料管理」的一帧， 联网结果回来才补上（用户反馈「打开抽屉后才显示」）。这里在冷启动就先用**同一账号**的
     * 缓存值（仓库侧每次成功查询都会写入）把状态填对，联网结果随后覆盖。
     */
    private fun seedAdministratorFromCache() {
        viewModelScope.launch {
            val serverId = appPreferences.getValue(appPreferences.currentServer) ?: return@launch
            val userId = currentUserId(serverId)
            resolvedUserId = userId
            val cached = cachedAdministratorFlag(userId)
            if (cached && !_state.value.isAdministrator) {
                _state.value = _state.value.copy(isAdministrator = cached)
            }
        }
    }

    /**
     * W75 #2：账号能力刷新（导航变化时调用）。
     *
     * - 当前账号与上次解析一致：只把缓存值同步回状态（零请求，首帧即正确）；
     * - 换了账号（同进程内切换）：先用缓存值抹掉上一个账号留下的管理员入口、再向服务器确认一次。
     */
    fun refreshAccount() {
        viewModelScope.launch {
            val serverId = appPreferences.getValue(appPreferences.currentServer) ?: return@launch
            val userId = currentUserId(serverId)
            val cached = cachedAdministratorFlag(userId)
            val switched = userId != resolvedUserId
            resolvedUserId = userId
            when {
                switched -> {
                    if (_state.value.isAdministrator != cached) {
                        _state.value = _state.value.copy(isAdministrator = cached)
                    }
                    load()
                }
                cached && !_state.value.isAdministrator ->
                    _state.value = _state.value.copy(isAdministrator = true)
            }
        }
    }

    private suspend fun currentUserId(serverId: String): String? = runCatching {
        database.getServerCurrentUser(serverId)?.id?.toString()
    }
        .getOrNull()

    private fun cachedAdministratorFlag(userId: String?): Boolean =
        sidebarAdministratorFlag(
            currentUserId = userId,
            cachedUserId = appPreferences.getValue(appPreferences.currentUserIsAdministratorUserId),
            cachedValue = appPreferences.getValue(appPreferences.currentUserIsAdministrator),
        )

    override fun onCleared() {
        appPreferences.sharedPreferences.unregisterOnSharedPreferenceChangeListener(
            preferenceListener
        )
        super.onCleared()
    }

    fun load() {
        // W53B：本地库属于本机索引，先刷新（离线 / 未选服务器时也要显示）；服务器数据随后覆盖其余字段。
        refreshLocalLibraries()
        viewModelScope.launch {
            runCatching {
                val serverId =
                    appPreferences.getValue(appPreferences.currentServer) ?: return@launch
                val server = runCatching { database.getServer(serverId) }.getOrNull()
                val address = runCatching { database.getServerCurrentAddress(serverId) }.getOrNull()
                val user = runCatching { database.getServerCurrentUser(serverId) }.getOrNull()
                resolvedUserId = user?.id?.toString()
                val repository = repositoryProvider.get()
                val isAdministrator = runCatching {
                    repository.isCurrentUserAdministrator()
                }
                    .getOrDefault(false)
                val libraries = runCatching { repository.getLibraries() }.getOrNull()
                // 媒体库目录缓存：设置模块不能依赖 data 层，设置页的「使用哪个媒体库」读这份缓存。
                // 拉取失败（离线 / 服务器不可达）时保留上一次缓存，不写空值。
                libraries?.let(::publishLibraryCatalog)
                // 用 copy 而不是新建：只覆盖服务器相关字段，保留 refreshLocalLibraries() 刚写入的本地库行。
                _state.value =
                    _state.value.copy(
                        serverName = server?.name,
                        serverAddress = address?.address,
                        userName = user?.name,
                        isAdministrator = isAdministrator,
                        // W73 #16：拉取失败（无会话 / 网络）时保留上一次成功列表，不把侧栏库行清空。
                        libraries = libraries ?: _state.value.libraries,
                        sidebarVisibility = readSidebarVisibility(),
                        hideBottomBar = appPreferences.getValue(appPreferences.hideBottomBar),
                    )
            }
                .onFailure { Timber.w(it, "读取抽屉账号信息失败") }
        }
    }

    /**
     * 服务器库只读刷新（W73 #16：侧栏 / 媒体页 / 库选择器三处库集合一致）。
     *
     * 导航变化时顺手拉一次——仓库 `MetadataCache`（TTL 10 分钟）负责去重，缓存有效期内零网络请求； 覆盖「冷启动瞬间会话尚未就绪 →
     * 首拉为空且之后长期不再刷新」的侧栏空库场景（平板侧轨不打开抽屉、没有其它刷新时机）。 拉取失败保留旧值（不清空侧栏）。
     */
    fun refreshServerLibraries() {
        viewModelScope.launch {
            val libraries =
                runCatching { repositoryProvider.get().getLibraries() }.getOrNull() ?: return@launch
            publishLibraryCatalog(libraries)
            _state.value = _state.value.copy(libraries = libraries)
        }
    }

    /** 媒体库目录缓存（`LibraryCatalog` 编码）：设置模块不能依赖 data 层，设置页的「使用哪个媒体库」读这份缓存。 */
    private fun publishLibraryCatalog(libraries: List<FindroidCollection>) {
        if (libraries.isEmpty()) return
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

    /**
     * 本地库行只读刷新（W53B）：不重拉服务器数据。
     *
     * 本地库建立 / 删除 / 重命名 /「在媒体库显示」开关都发生在内容页，侧栏（平板侧轨常显）跟着刷新即可 —— 每次导航变化与本地库详情页设置变化时调用。
     */
    fun refreshLocalLibraries() {
        viewModelScope.launch {
            val libraries = runCatching {
                localLibraryRepository.libraries(includeHidden = true)
            }
                .getOrDefault(emptyList())
            _state.value = _state.value.copy(localLibraries = sidebarLocalLibraries(libraries))
        }
    }

    private fun readSidebarVisibility() =
        SidebarVisibility(
            home = appPreferences.getValue(appPreferences.uiSidebarShowHome),
            video = appPreferences.getValue(appPreferences.uiSidebarShowVideo),
            media = appPreferences.getValue(appPreferences.uiSidebarShowMedia),
            music = appPreferences.getValue(appPreferences.uiSidebarShowMusic),
            bookshelf = appPreferences.getValue(appPreferences.uiSidebarShowBookshelf),
            downloads = appPreferences.getValue(appPreferences.uiSidebarShowDownloads),
            console = appPreferences.getValue(appPreferences.uiSidebarShowConsole),
            metadata = appPreferences.getValue(appPreferences.uiSidebarShowMetadata),
        )

    private companion object {
        const val SIDEBAR_PREF_PREFIX = "pref_ui_sidebar_"
        const val HIDE_BOTTOM_BAR_PREF_KEY = "pref_hide_bottom_bar"
    }
}

/**
 * 侧栏「控制台 / 资料管理」的管理员门控值（纯函数，单测覆盖，W75 #2）。
 *
 * 缓存是「账号 id + 是否管理员」成对的：只有**当前账号就是缓存里那个账号**时缓存才作数， 否则一律按非管理员处理——既不会把上一个账号的管理员身份泄漏给普通账号，
 * 也不会在账号未知（未登录 / 会话未就绪）时露出后台入口。
 */
internal fun sidebarAdministratorFlag(
    currentUserId: String?,
    cachedUserId: String?,
    cachedValue: Boolean,
): Boolean = currentUserId != null && currentUserId == cachedUserId && cachedValue
