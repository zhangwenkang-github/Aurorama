package com.zhangwenkang.cinefin

import android.content.Context
import android.content.Intent
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.NavOptions
import androidx.navigation.NavOptionsBuilder
import androidx.navigation.Navigator
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.toRoute
import androidx.window.core.layout.WindowSizeClass
import com.zhangwenkang.cinefin.book.presentation.reader.ReaderActivity
import com.zhangwenkang.cinefin.core.R as CoreR
import com.zhangwenkang.cinefin.core.presentation.components.CinefinBottomTab
import com.zhangwenkang.cinefin.core.presentation.components.CinefinCountBadge
import com.zhangwenkang.cinefin.core.presentation.components.CinefinDrawerGroup
import com.zhangwenkang.cinefin.core.presentation.components.CinefinModalDrawer
import com.zhangwenkang.cinefin.core.presentation.components.CinefinNavItem
import com.zhangwenkang.cinefin.core.presentation.components.CinefinNavigationItem
import com.zhangwenkang.cinefin.core.presentation.components.CinefinRailGroupDivider
import com.zhangwenkang.cinefin.core.presentation.components.CinefinRailSectionDivider
import com.zhangwenkang.cinefin.core.presentation.components.cinefinClickable
import com.zhangwenkang.cinefin.core.presentation.components.lumenEdgeHighlight
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinShapes
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinSpacing
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinTokens
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinType
import com.zhangwenkang.cinefin.core.presentation.theme.LocalCinefinColors
import com.zhangwenkang.cinefin.core.presentation.theme.LocalLumenColors
import com.zhangwenkang.cinefin.core.presentation.theme.LumenColorsDark
import com.zhangwenkang.cinefin.core.presentation.theme.ProvideLumen
import com.zhangwenkang.cinefin.core.presentation.theme.ProvideLumenColors
import com.zhangwenkang.cinefin.film.presentation.downloads.DownloadBadgeViewModel
import com.zhangwenkang.cinefin.models.CollectionType
import com.zhangwenkang.cinefin.models.FindroidBoxSet
import com.zhangwenkang.cinefin.models.FindroidCollection
import com.zhangwenkang.cinefin.models.FindroidEpisode
import com.zhangwenkang.cinefin.models.FindroidFolder
import com.zhangwenkang.cinefin.models.FindroidItem
import com.zhangwenkang.cinefin.models.FindroidMovie
import com.zhangwenkang.cinefin.models.FindroidSeason
import com.zhangwenkang.cinefin.models.FindroidShow
import com.zhangwenkang.cinefin.models.SortBy
import com.zhangwenkang.cinefin.models.SortOrder
import com.zhangwenkang.cinefin.music.presentation.MusicModeRoute
import com.zhangwenkang.cinefin.music.presentation.MusicModeScreen
import com.zhangwenkang.cinefin.presentation.console.WebConsoleScreen
import com.zhangwenkang.cinefin.presentation.film.BookshelfScreen
import com.zhangwenkang.cinefin.presentation.film.CollectionScreen
import com.zhangwenkang.cinefin.presentation.film.DownloadsScreen
import com.zhangwenkang.cinefin.presentation.film.EpisodeScreen
import com.zhangwenkang.cinefin.presentation.film.FavoritesScreen
import com.zhangwenkang.cinefin.presentation.film.HomeScreen
import com.zhangwenkang.cinefin.presentation.film.LibraryScreen
import com.zhangwenkang.cinefin.presentation.film.MediaScreen
import com.zhangwenkang.cinefin.presentation.film.MovieScreen
import com.zhangwenkang.cinefin.presentation.film.PersonScreen
import com.zhangwenkang.cinefin.presentation.film.SeasonScreen
import com.zhangwenkang.cinefin.presentation.film.ShowScreen
import com.zhangwenkang.cinefin.presentation.local.LocalLibraryDetailScreen
import com.zhangwenkang.cinefin.presentation.local.iconRes
import com.zhangwenkang.cinefin.presentation.navigation.CinefinDrawerHeader
import com.zhangwenkang.cinefin.presentation.navigation.DrawerViewModel
import com.zhangwenkang.cinefin.presentation.navigation.MEDIA_GROUP_DEFAULT_EXPANDED
import com.zhangwenkang.cinefin.presentation.navigation.NavEntryKey
import com.zhangwenkang.cinefin.presentation.navigation.RAIL_LIBRARY_COUNT_GAP_DP
import com.zhangwenkang.cinefin.presentation.navigation.SidebarLocalLibrary
import com.zhangwenkang.cinefin.presentation.navigation.TemporaryLibraryKind
import com.zhangwenkang.cinefin.presentation.navigation.TopLevelTapAction
import com.zhangwenkang.cinefin.presentation.navigation.bottomNavKeys
import com.zhangwenkang.cinefin.presentation.navigation.homeViewAllRoute
import com.zhangwenkang.cinefin.presentation.navigation.libraryChildCountVisible
import com.zhangwenkang.cinefin.presentation.navigation.libraryEntryRoute
import com.zhangwenkang.cinefin.presentation.navigation.libraryIconRes
import com.zhangwenkang.cinefin.presentation.navigation.libraryTypeLabelRes
import com.zhangwenkang.cinefin.presentation.navigation.navEntryKeys
import com.zhangwenkang.cinefin.presentation.navigation.navIcon
import com.zhangwenkang.cinefin.presentation.navigation.railGroupBreaks
import com.zhangwenkang.cinefin.presentation.navigation.railLibraryLabelWidthDp
import com.zhangwenkang.cinefin.presentation.navigation.topLevelTapAction
import com.zhangwenkang.cinefin.presentation.navigation.visibleRailKeys
import com.zhangwenkang.cinefin.presentation.offline.OfflineHomeScreen
import com.zhangwenkang.cinefin.presentation.offline.OfflineLibraryScreen
import com.zhangwenkang.cinefin.presentation.offline.OfflineModeViewModel
import com.zhangwenkang.cinefin.presentation.offline.OfflineShelfScreen
import com.zhangwenkang.cinefin.presentation.offline.StartDestinationKind
import com.zhangwenkang.cinefin.presentation.offline.resolveStartDestinationKind
import com.zhangwenkang.cinefin.presentation.settings.AboutScreen
import com.zhangwenkang.cinefin.presentation.settings.SettingsFileEditScreen
import com.zhangwenkang.cinefin.presentation.settings.SettingsScreen
import com.zhangwenkang.cinefin.presentation.setup.addresses.ServerAddressesScreen
import com.zhangwenkang.cinefin.presentation.setup.addserver.AddServerScreen
import com.zhangwenkang.cinefin.presentation.setup.login.LoginScreen
import com.zhangwenkang.cinefin.presentation.setup.servers.ServersScreen
import com.zhangwenkang.cinefin.presentation.setup.users.UsersScreen
import com.zhangwenkang.cinefin.presentation.setup.welcome.WelcomeScreen
import com.zhangwenkang.cinefin.presentation.utils.LocalOfflineMode
import com.zhangwenkang.cinefin.presentation.video.VideoScreen
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.jellyfin.sdk.model.api.BaseItemKind

@Serializable data object WelcomeRoute

@Serializable data object ServersRoute

@Serializable data object AddServerRoute

@Serializable data class ServerAddressesRoute(val serverId: String)

@Serializable data object UsersRoute

@Serializable data class LoginRoute(val username: String? = null)

@Serializable data object HomeRoute

/** 视频模式页（W53）：与首页 / 音乐 / 书架同级的顶层入口。 */
@Serializable data object VideoRoute

@Serializable data object MediaRoute

@Serializable data object DownloadsRoute

/** 顶部「书架」Tab 的独立目的地：页面自己解析书籍库，不依赖抽屉是否打开过（见 BookshelfScreen）。 */
@Serializable data object BookshelfRoute

/**
 * 控制台后台路径（jellyfin-web 的 hash 路由）：`/dashboard` 控制台、`/metadata` 媒体资料管理器。
 *
 * 两类入口共用 [ConsoleRoute] 这一个目的地，只靠 [ConsoleRoute.path] 参数区分，因此路径常量必须 是唯一来源（默认值 / 侧柜条目 / 选中态判定都读这里）。
 */
internal const val ConsolePathDashboard = "/dashboard"

internal const val ConsolePathMetadata = "/metadata"

/**
 * 服务器 Web 控制台。
 *
 * [path] 决定进后台的哪一页：[ConsolePathDashboard] 是控制台，[ConsolePathMetadata] 是媒体资料管理器， `/details?id=…`
 * 用来把图书之类的条目交给服务器自带的阅读器。
 */
@Serializable data class ConsoleRoute(val path: String = ConsolePathDashboard)

@Serializable
data class LibraryRoute(
    val libraryId: String,
    val libraryName: String,
    val libraryType: CollectionType,
    /**
     * W54-D（红线申报）：首页「全部」入口的初始排序参数（`SortBy.name` / `SortOrder.name`）。
     *
     * null = 按用户全局排序偏好进入（侧栏 / 媒体库卡等既有入口不变）；非空 = 本次进入的默认排序， 不写回偏好，用户改排序后才落全局键。
     */
    val sortBy: String? = null,
    val sortOrder: String? = null,
)

/**
 * 临时库视图（W53 追加，用户 2026-10-03 确认）：侧栏 / 抽屉点某个服务器媒体库 → 对应模式页（[kind]）直显该库数据，不写偏好。
 *
 * 返回键 / 「返回默认 ×」= 回到该模式页的**默认库**（入口根页），再按一次才离开页面；点其它底栏 / 侧栏入口同样回默认。
 */
@Serializable
data class TemporaryLibraryRoute(
    val libraryId: String,
    val libraryName: String,
    val kind: TemporaryLibraryKind,
    /** [CollectionType.type]（顶栏类型文案用）。 */
    val libraryType: String,
)

@Serializable data class CollectionRoute(val collectionId: String, val collectionName: String)

@Serializable data object FavoritesRoute

/** W37：本地媒体库详情（本地库总览卡进入）。 */
@Serializable data class LocalLibraryRoute(val libraryId: Long)

@Serializable data class MovieRoute(val movieId: String)

@Serializable data class ShowRoute(val showId: String)

@Serializable data class EpisodeRoute(val episodeId: String)

@Serializable data class SeasonRoute(val seasonId: String)

@Serializable data class PersonRoute(val personId: String)

@Serializable data class SettingsRoute(val indexes: IntArray)

@Serializable data class SettingsFileEditRoute(val filePath: String)

@Serializable data object AboutRoute

data class TabBarItem(
    @param:StringRes val title: Int,
    @param:DrawableRes val icon: Int,
    val route: Any,
    val enabled: Boolean = true,
)

val homeTab =
    TabBarItem(title = CoreR.string.title_home, icon = CoreR.drawable.ic_home, route = HomeRoute)
val mediaTab =
    TabBarItem(
        title = CoreR.string.title_media,
        icon = CoreR.drawable.ic_library,
        route = MediaRoute,
    )
val downloadsTab =
    TabBarItem(
        title = CoreR.string.title_download,
        icon = CoreR.drawable.ic_download,
        route = DownloadsRoute,
    )

@Composable
fun NavigationRoot(
    navController: NavHostController,
    hasServers: Boolean,
    hasCurrentServer: Boolean,
    hasCurrentUser: Boolean,
) {
    val isOfflineMode = LocalOfflineMode.current
    val offlineModeViewModel: OfflineModeViewModel = hiltViewModel()

    // W36 导航门控（纯函数见 OfflineNavigation.kt，单测覆盖）：离线模式优先落离线首页。
    // 只在首次组合时决定：之后进入 / 退出离线模式由显式导航与页面内容分流处理，避免切换时重建导航图。
    val startDestination = remember {
        when (
            resolveStartDestinationKind(
                isOfflineMode = isOfflineMode,
                hasServers = hasServers,
                hasCurrentServer = hasCurrentServer,
                hasCurrentUser = hasCurrentUser,
            )
        ) {
            StartDestinationKind.OFFLINE_HOME,
            StartDestinationKind.HOME -> HomeRoute
            StartDestinationKind.USERS -> UsersRoute
            StartDestinationKind.SERVERS -> ServersRoute
            StartDestinationKind.WELCOME -> WelcomeRoute
        }
    }

    /** W36：欢迎 / 服务器 / 用户 / 登录页的「离线模式」入口——不依赖任何服务器会话，直接进离线首页。 */
    val enterOfflineMode: () -> Unit = {
        offlineModeViewModel.enterOfflineMode()
        navController.safeNavigate(HomeRoute) {
            popUpTo(0)
            launchSingleTop = true
        }
    }

    val navBackStackEntry by navController.currentBackStackEntryAsState()

    var searchExpanded by remember { mutableStateOf(false) }
    // W56：「顶层图标统一回对应主页」——音乐全屏播放 / 歌词页 / 专辑等详情都在音乐页内（不在导航栈里），
    // 点顶层「音乐」图标时用递增信号让它们收起；音乐页把「页内二级层是否打开」回报回来，用于区分
    // 「已在主页（不重复导航 / 只收二级层）」与「在二级页 / 其它入口（走既有顶层导航）」。
    var musicOverlayReselectSignal by remember { mutableIntStateOf(0) }
    var musicInnerPageOpen by remember { mutableStateOf(false) }

    val currentDestination = navBackStackEntry?.destination
    // 主导航：手机底部 4 tab / 平板侧轨；抽屉继续承载全量入口（库列表 / 控制台 / 服务器）
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    // 顶层页面允许手势拉出抽屉；详情页等保留全宽与返回手势。
    // 统一目的地（D18）：凡是侧柜（底部 Tab / 侧轨 / 抽屉）里能点到的目标都按顶层页面处理——
    // 手机选择后关闭抽屉，平板切换内容区、侧轨常驻，不再出现"某些条目把侧轨顶掉"。
    // 控制台 / 媒体资料管理器也在这份列表里，但只对管理员可见（W5-R3I）。
    // 带参路由（Library / Settings / Console）的 destination.route 是「类名 + 参数模板」，
    // 不能用 ::class.qualifiedName 比较（踩坑 28）；统一用 isRoute 按序列化器哈希匹配。
    // 控制台 / 元数据管理器**不在**这份集合里（W6-R6N，用户反馈 2）：这两个页面只保留控制台
    // 自己的侧栏，app 侧轨 / 底部 Tab 与边缘抽屉手势都要让位给 WebView。
    val showNavigation =
        currentDestination.isRoute<HomeRoute>() ||
            currentDestination.isRoute<VideoRoute>() ||
            currentDestination.isRoute<MediaRoute>() ||
            currentDestination.isRoute<BookshelfRoute>() ||
            currentDestination.isRoute<FavoritesRoute>() ||
            currentDestination.isRoute<DownloadsRoute>() ||
            currentDestination.isRoute<MusicModeRoute>() ||
            currentDestination.isRoute<TemporaryLibraryRoute>() ||
            currentDestination.isRoute<LibraryRoute>() ||
            currentDestination.isRoute<SettingsRoute>()
    val context = LocalContext.current
    val settingsRoute = remember {
        SettingsRoute(indexes = intArrayOf(CoreR.string.title_settings))
    }

    val drawerViewModel: DrawerViewModel = hiltViewModel()
    val drawerData by drawerViewModel.state.collectAsStateWithLifecycle()

    // W51：下载入口角标 = 活动任务数（下载中 + 排队 + 暂停，0 隐藏）；前台可见期间只读快照。
    val downloadBadgeViewModel: DownloadBadgeViewModel = hiltViewModel()
    val downloadActiveCount by downloadBadgeViewModel.activeCount.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                downloadBadgeViewModel.refresh()
                delay(DownloadBadgePollIntervalMs)
            }
        }
    }
    // 抽屉数据要在冷启动就绪：书架 Tab 的跳转与选中态都读这份库列表（只在打开抽屉时加载会让
    // 冷启动点「书架」拿到空列表）。打开抽屉时再刷新一次，保证服务器端新建的库能及时出现。
    // 离线下 / 切回在线时库列表要跟着刷新（W6-R6N：离线开关不再重启 Activity）。
    LaunchedEffect(isOfflineMode) { drawerViewModel.load() }
    LaunchedEffect(drawerState.isOpen) {
        if (drawerState.isOpen) {
            drawerViewModel.load()
        }
    }
    // W53B：本地库不属于服务器数据——导航变化（进出本地库详情 / 媒体库页新建后离开）时只读刷新一次本地库行，
    // 让侧轨 / 抽屉的「本地媒体库」子分组跟着建立 / 删除 /「在媒体库显示」开关变化。
    LaunchedEffect(navBackStackEntry) { drawerViewModel.refreshLocalLibraries() }

    // 形态分级（§4.4）：Compact 底部 tab；Medium 起侧轨（≥1200dp 默认展开 164dp）
    val windowSizeClass = currentWindowAdaptiveInfo().windowSizeClass
    val compactNavigation =
        !windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND)
    val railDefaultExpanded = windowSizeClass.isWidthAtLeastBreakpoint(1200)
    var railExpanded by
        rememberSaveable(railDefaultExpanded) { mutableStateOf(railDefaultExpanded) }
    // 「媒体库」二级分组默认**收起**（W8-R3 用户反馈 4，覆盖 W7-R3 的默认展开）：抽屉与侧轨共用，
    // 展开后才显示服务器的库列表——先给出「首页 / 音乐 / 书架 / 媒体库」四条一级入口。
    var mediaGroupExpanded by rememberSaveable { mutableStateOf(MEDIA_GROUP_DEFAULT_EXPANDED) }

    LaunchedEffect(showNavigation) {
        // 控制台类页面不渲染侧柜（D22 ②），抽屉在这里让位；手机 Compact 自 W7-R3 起恢复抽屉，不再收回。
        if (!showNavigation && drawerState.isOpen) {
            drawerState.close()
        }
    }
    LaunchedEffect(compactNavigation) {
        // W46：平板形态没有抽屉入口（顶栏不再给键、边缘手势停用），窗口从手机切过去时别把已拉开的抽屉留在屏幕上。
        if (!compactNavigation && drawerState.isOpen) {
            drawerState.close()
        }
    }

    val booksLibrary = drawerData.libraries.firstOrNull { it.type == CollectionType.Books }
    val currentLibrary =
        if (currentDestination.isRoute<LibraryRoute>()) {
            runCatching { navBackStackEntry?.toRoute<LibraryRoute>() }.getOrNull()
        } else {
            null
        }
    // W53B：本地库详情（侧栏「本地媒体库」子项 / 媒体库页本地库卡的共同落点）——侧栏子项按它高亮。
    val currentLocalLibraryId =
        if (currentDestination.isRoute<LocalLibraryRoute>()) {
            runCatching { navBackStackEntry?.toRoute<LocalLibraryRoute>() }.getOrNull()?.libraryId
        } else {
            null
        }
    // 临时库视图（W53 追加）：侧栏点服务器媒体库 → 模式页直显该库；选中态按 kind 落到对应入口。
    val temporaryLibrary =
        if (currentDestination.isRoute<TemporaryLibraryRoute>()) {
            runCatching { navBackStackEntry?.toRoute<TemporaryLibraryRoute>() }.getOrNull()
        } else {
            null
        }
    // 侧柜皮肤（W6-VIS D23 → W7-R3 用户反馈 3）：侧轨 / 底栏 / 抽屉**常驻** S1「A · Lumen」——
    // 音乐 / 书架 / 阅读页只是**内容**保持各自皮肤（LumenPage 仍按域分流），侧边菜单统一走 A 稿。
    val lumenChrome = true
    // 控制台 / 媒体资料管理器共用 ConsoleRoute 目的地，选中态要靠 path 参数区分（否则两条入口
    // 会同时高亮，见踩坑 28 的同类问题）。
    val consolePath =
        if (currentDestination.isRoute<ConsoleRoute>()) {
            runCatching { navBackStackEntry?.toRoute<ConsoleRoute>() }.getOrNull()?.path
        } else {
            null
        }

    val homeSelected = currentDestination.isRoute<HomeRoute>()
    val videoSelected =
        currentDestination.isRoute<VideoRoute>() ||
            temporaryLibrary?.kind == TemporaryLibraryKind.Video
    val musicSelected =
        currentDestination.isRoute<MusicModeRoute>() ||
            temporaryLibrary?.kind == TemporaryLibraryKind.Music
    val mediaSelected = currentDestination.isRoute<MediaRoute>()
    val downloadsSelected = currentDestination.isRoute<DownloadsRoute>()
    val settingsSelected = currentDestination.isRoute<SettingsRoute>()
    val booksSelected =
        currentDestination.isRoute<BookshelfRoute>() ||
            (booksLibrary != null && currentLibrary?.libraryId == booksLibrary.id.toString()) ||
            temporaryLibrary?.kind == TemporaryLibraryKind.Books
    val favoritesSelected = currentDestination.isRoute<FavoritesRoute>()

    // 手机（Compact）恢复抽屉入口（W7-R3 用户反馈 1）：顶栏 app 图标可拉出，边缘手势也可用。
    // W46（用户确认）：展开形态取消抽屉——顶栏不再给入口（null），导航入口只剩常显侧轨（收 / 展开由侧轨自身按钮完成）。
    val openDrawer: (() -> Unit)? =
        if (compactNavigation) {
            { scope.launch { drawerState.open() } }
        } else {
            null
        }
    val closeDrawer: () -> Unit = { scope.launch { drawerState.close() } }
    // 顶层入口（底栏 / 侧轨 / 抽屉）：**落点 = 该入口的根页**（W53 Bug A 修复）。
    // `saveState + restoreState` 会按目的地 id 把「离开时整栈」恢复回来（例如 视频 → 某个库内容页），
    // 用户点底栏「视频」时会停在库内容页，看着像"切不回来"；这里恢复后统一把根页之上的子页面弹掉——
    // 根页自身的滚动 / 状态仍由 saveState 保留（踩坑 30 的结论不变）。
    // W56（用户 2026-10-04 拍板）：已在入口主页时停在原地（不重复导航、不闪烁）；音乐主页的
    // 全屏播放 / 歌词页是页面内覆盖层，用 [musicOverlayReselectSignal] 让它收起。
    val navigateTopLevel: (Any) -> Unit = { route ->
        closeDrawer()
        val isOnEntryHome =
            when (route) {
                HomeRoute -> currentDestination.isRoute<HomeRoute>()
                VideoRoute -> currentDestination.isRoute<VideoRoute>()
                MediaRoute -> currentDestination.isRoute<MediaRoute>()
                MusicModeRoute -> currentDestination.isRoute<MusicModeRoute>()
                BookshelfRoute -> currentDestination.isRoute<BookshelfRoute>()
                FavoritesRoute -> currentDestination.isRoute<FavoritesRoute>()
                DownloadsRoute -> currentDestination.isRoute<DownloadsRoute>()
                // 设置子页 = 同一目的地的不同参数（indexes），只有根参数才算「已在主页」。
                is SettingsRoute ->
                    currentDestination.isRoute<SettingsRoute>() &&
                        runCatching { navBackStackEntry?.toRoute<SettingsRoute>() }
                            .getOrNull()
                            ?.indexes
                            ?.toList() == route.indexes.toList()
                else -> false
            }
        when (
            topLevelTapAction(
                isOnEntryHome = isOnEntryHome,
                hasInPageOverlay = route == MusicModeRoute && musicInnerPageOpen,
            )
        ) {
            TopLevelTapAction.Stay -> Unit
            TopLevelTapAction.CollapseOverlay -> musicOverlayReselectSignal++
            TopLevelTapAction.Navigate -> {
                // 从二级页 / 其它入口回音乐主页时，恢复出来的覆盖层同样收起（回主页 = 关闭覆盖层）。
                if (route == MusicModeRoute) {
                    musicOverlayReselectSignal++
                }
                navController.safeNavigate(route) {
                    popUpTo(navController.graph.startDestinationId) { saveState = true }
                    launchSingleTop = true
                    restoreState = true
                }
                // 带子页面（库内容 / 专辑详情等）时弹回根页；已在根页的情况上面已提前返回。
                navController.popBackStack(route, inclusive = false)
            }
        }
    }
    // 控制台两类入口不能用统一入口的 saveState / restoreState：popUpTo(saveState) + restoreState 是按
    // 目的地 id 恢复保存的条目，而两个入口共用 ConsoleRoute 目的地 id——点「媒体资料管理器」会把上一次
    // 保存的 `/dashboard` 条目恢复出来（args 被覆盖，见踩坑 30）。这里按 path 重新建条目。
    val navigateConsole: (String) -> Unit = { path ->
        closeDrawer()
        navController.safeNavigate(ConsoleRoute(path)) {
            popUpTo(navController.graph.startDestinationId)
            launchSingleTop = true
        }
    }
    val openLibrary: (FindroidCollection) -> Unit = { library ->
        closeDrawer()
        // W53 Bug B2：库入口是「同一目的地 + 不同参数」（书籍 / 书籍3、音乐 / 音乐测试），不能走统一入口的
        // saveState / restoreState——按目的地 id 恢复旧条目会把新参数顶掉（踩坑 30 同类），真机表现为
        // 「点 书籍3 页面仍是 书籍」。这里 popUpTo(start) 不回存、不恢复，保证按点击的库新建条目。
        navController.safeNavigate(
            libraryEntryRoute(
                libraryId = library.id.toString(),
                libraryName = library.name,
                libraryType = library.type,
            )
        ) {
            popUpTo(navController.graph.startDestinationId)
            launchSingleTop = true
        }
    }
    // W53B：侧栏「本地媒体库」子分组点一行 → 该本地库详情（既有 LocalLibraryRoute，与媒体库页本地库卡同落点）。
    // 本地库详情是独立目的地（不在模式页里做临时覆盖），返回键 / 切其它入口都自然回到各自默认页面。
    val openLocalLibrary: (SidebarLocalLibrary) -> Unit = { library ->
        closeDrawer()
        // launchSingleTop：已在同一个本地库详情时再点（平板侧轨常显）不重复入栈。
        navController.safeNavigate(LocalLibraryRoute(library.id)) { launchSingleTop = true }
    }
    // 临时库视图（W53 追加）：返回键 / 「返回默认 ×」= 回到该模式页的**默认库**（入口根页）——
    // 用「导航到默认入口 + popUpTo 临时条目 inclusive」替换当前条目，再按一次返回才会离开页面。
    val exitTemporaryLibrary: (Any) -> Unit = { defaultRoute ->
        navController.safeNavigate(defaultRoute) {
            popUpTo<TemporaryLibraryRoute> { inclusive = true }
            launchSingleTop = true
        }
    }

    fun chromeItem(
        @DrawableRes res: Int,
        label: String,
        neutral: Boolean = false,
        badge: (@Composable () -> Unit)? = null,
    ) = CinefinNavItem(label = label, neutral = neutral, icon = navIcon(res), badge = badge)

    val sidebarVisibility = drawerData.sidebarVisibility
    // 「服务器上实际存在什么库」的唯一来源是抽屉数据里的库列表；库列表未就绪时入口保持可见，
    // 由页面自己显示空态——避免把网络故障误判成「服务器没有音乐库 / 书籍库」（见 NavigationIa.kt）。
    val navKeys =
        navEntryKeys(
            isAdministrator = drawerData.isAdministrator,
            librariesLoaded = drawerData.libraries.isNotEmpty(),
            hasVideoLibrary =
                drawerData.libraries.any {
                    it.type == CollectionType.Movies || it.type == CollectionType.TvShows
                },
            hasMusicLibrary = drawerData.libraries.any { it.type == CollectionType.Music },
            hasBooksLibrary = drawerData.libraries.any { it.type == CollectionType.Books },
        )
    val consoleSpecByPath = consoleEntrySpecs(drawerData.isAdministrator).associateBy { it.path }

    // W51：下载角标（活动任务数 > 0 才渲染；99+ 收敛在组件内）。
    val downloadBadge: (@Composable () -> Unit)? =
        if (downloadActiveCount > 0) {
            {
                CinefinCountBadge(
                    count = downloadActiveCount,
                    contentDescription =
                        stringResource(CoreR.string.download_active_badge, downloadActiveCount),
                )
            }
        } else {
            null
        }

    // 顶层 IA（W6-R6N）：首页 / 媒体库 / 音乐 / 书架 /（管理员：服务器控制台 / 元数据管理器）/ 客户端设置。
    // 「媒体库」是二级分组，子项 = 服务器实际返回的全部库（同名多库按服务器顺序逐条列出）。
    val chromeDestinations = navKeys.mapNotNull { key ->
        when (key) {
            NavEntryKey.Home ->
                ChromeDestination(
                    key = key,
                    item =
                        chromeItem(
                            CoreR.drawable.ic_home,
                            stringResource(CoreR.string.title_home),
                            neutral = true,
                        ),
                    selected = homeSelected,
                    bottom = true,
                ) {
                    navigateTopLevel(HomeRoute)
                }
            NavEntryKey.Video ->
                ChromeDestination(
                    key = key,
                    item =
                        chromeItem(
                            CoreR.drawable.ic_film,
                            stringResource(CoreR.string.title_video),
                        ),
                    selected = videoSelected,
                    bottom = true,
                ) {
                    navigateTopLevel(VideoRoute)
                }
            NavEntryKey.Media ->
                ChromeDestination(
                    key = key,
                    item =
                        chromeItem(
                            CoreR.drawable.ic_library,
                            stringResource(CoreR.string.title_media),
                        ),
                    selected = mediaSelected,
                    bottom = true,
                ) {
                    navigateTopLevel(MediaRoute)
                }
            NavEntryKey.Music ->
                ChromeDestination(
                    key = key,
                    item =
                        chromeItem(
                            CoreR.drawable.ic_music,
                            stringResource(CoreR.string.title_music),
                        ),
                    selected = musicSelected,
                    bottom = true,
                ) {
                    navigateTopLevel(MusicModeRoute)
                }
            NavEntryKey.Bookshelf ->
                ChromeDestination(
                    key = key,
                    item =
                        chromeItem(
                            CoreR.drawable.ic_book,
                            stringResource(CoreR.string.title_book_shelf),
                        ),
                    selected = booksSelected,
                    bottom = true,
                ) {
                    // 书架 = 独立目的地：页面自己解析书籍库，没有书库时显示空态，
                    // 不再依赖抽屉数据是否加载完、也不会回退到媒体库总览。
                    navigateTopLevel(BookshelfRoute)
                }
            NavEntryKey.Favorites ->
                ChromeDestination(
                    key = key,
                    item =
                        chromeItem(
                            CoreR.drawable.ic_bookmark,
                            stringResource(CoreR.string.title_my_favorites),
                        ),
                    selected = favoritesSelected,
                    bottom = false,
                ) {
                    // W60b：侧栏一级「我的收藏」（跨库汇总，不占手机底部 Tab）。
                    navigateTopLevel(FavoritesRoute)
                }
            NavEntryKey.Downloads ->
                ChromeDestination(
                    key = key,
                    item =
                        chromeItem(
                            CoreR.drawable.ic_download,
                            stringResource(CoreR.string.title_download),
                            badge = downloadBadge,
                        ),
                    selected = downloadsSelected,
                    bottom = false,
                ) {
                    navigateTopLevel(DownloadsRoute)
                }
            NavEntryKey.Console,
            NavEntryKey.Metadata -> {
                // 控制台 / 媒体资料管理器（W5-R3I）：只对管理员展示、不进手机底部 Tab，
                // 两类入口共用 ConsoleRoute，选中态按 path 判定。
                val path =
                    if (key == NavEntryKey.Metadata) ConsolePathMetadata else ConsolePathDashboard
                val spec = consoleSpecByPath[path] ?: return@mapNotNull null
                ChromeDestination(
                    key = key,
                    item = chromeItem(spec.iconRes, stringResource(spec.titleRes)),
                    selected = consoleEntrySelected(consolePath, spec.path),
                    bottom = false,
                ) {
                    navigateConsole(spec.path)
                }
            }
            NavEntryKey.Settings ->
                ChromeDestination(
                    key = key,
                    item =
                        chromeItem(
                            CoreR.drawable.ic_settings,
                            stringResource(CoreR.string.title_settings),
                            neutral = true,
                        ),
                    selected = settingsSelected,
                    bottom = false,
                ) {
                    navigateTopLevel(settingsRoute)
                }
        }
    }
    val chromeByKey = chromeDestinations.associateBy { it.key }
    // 手机底部 Tab 固定顺序（W53 用户 2026-10-03 确认）：首页 / 视频 / 音乐 / 书架；
    // 「媒体库」移出底栏，仍保留在侧栏 / 抽屉。顺序与侧轨排序解耦（bottomNavKeys 单一来源）。
    val bottomItems = bottomNavKeys.mapNotNull { chromeByKey[it] }
    // 侧栏 = 同一份列表按「客户端设置 → 侧栏显示」过滤（客户端设置常驻，见 NavigationIa.kt）。
    val railKeys = visibleRailKeys(navKeys, sidebarVisibility)
    val railDestinations = railKeys.mapNotNull { chromeByKey[it] }
    // W42：分组之间的空隙 / 细分隔线，以及固定在底部的「客户端设置」区。
    val railBreaks = railGroupBreaks(railKeys)
    val railPinnedTail = if (railKeys.lastOrNull() == NavEntryKey.Settings) 1 else 0

    // 抽屉 = 同一份统一目的地列表，库列表挂在「媒体库」行下（W8-R3 用户反馈 4：**默认收起**、展开后才显示；
    // 行尾箭头与侧轨同一套交互与图标，展开状态与侧轨共用 mediaGroupExpanded）。选中索引与动作列表仍同源（踩坑 17）。
    // W53B：子分组 = 服务器库（服务器返回）→「本地媒体库」标题 + 分隔 → 本地库行。本地库只读本机索引，
    // 离线模式下同样可展开（不再要求 `!isOfflineMode`）；两组都空时保持一级入口、无展开箭头。
    val hasMediaChildren =
        drawerData.libraries.isNotEmpty() || drawerData.localLibraries.isNotEmpty()
    val libraryChildrenVisible = mediaGroupExpanded && hasMediaChildren
    val drawerEntries: List<DrawerEntry> = railDestinations.flatMap { destination ->
        val topLevel =
            DrawerEntry(
                item = destination.item,
                selected = destination.selected,
                onClick = destination.onClick,
            )
        if (destination.key != NavEntryKey.Media) {
            return@flatMap listOf(topLevel)
        }
        val expandable = hasMediaChildren
        val parent =
            DrawerEntry(
                item =
                    CinefinNavItem(
                        label = destination.item.label,
                        neutral = destination.item.neutral,
                        icon = destination.item.icon,
                        trailing =
                            if (expandable) {
                                {
                                    val colors = LocalCinefinColors.current
                                    Icon(
                                        painter =
                                            painterResource(
                                                if (mediaGroupExpanded) {
                                                    CoreR.drawable.ic_chevron_up
                                                } else {
                                                    CoreR.drawable.ic_chevron_down
                                                }
                                            ),
                                        contentDescription =
                                            stringResource(
                                                if (mediaGroupExpanded) {
                                                    CoreR.string.nav_collapse
                                                } else {
                                                    CoreR.string.nav_expand
                                                }
                                            ),
                                        tint = colors.onSurfaceFaint,
                                        modifier =
                                            Modifier.size(20.dp).clickable {
                                                mediaGroupExpanded = !mediaGroupExpanded
                                            },
                                    )
                                }
                            } else {
                                null
                            },
                    ),
                selected = destination.selected,
                onClick = {
                    destination.onClick()
                    if (!mediaGroupExpanded) mediaGroupExpanded = true
                },
            )
        if (!libraryChildrenVisible) {
            listOf(parent)
        } else {
            listOf(parent) +
                drawerData.libraries.map { library ->
                    DrawerEntry(
                        item =
                            CinefinNavItem(
                                label = library.name,
                                icon = navIcon(libraryIconRes(library.type)),
                                nested = true,
                                // W53 Bug B 配套：同类型多库（书籍 / 书籍3、音乐 / 音乐测试）补项目数，便于区分。
                                trailing = libraryChildCountTrailing(library.itemCount),
                            ),
                        selected = currentLibrary?.libraryId == library.id.toString(),
                        onClick = { openLibrary(library) },
                    )
                } +
                drawerData.localLibraries.map { library ->
                    DrawerEntry(
                        item =
                            CinefinNavItem(
                                label = library.name,
                                icon = navIcon(library.type.iconRes()),
                                nested = true,
                                trailing = libraryChildCountTrailing(library.itemCount),
                            ),
                        selected = currentLocalLibraryId == library.id,
                        onClick = { openLocalLibrary(library) },
                        section = DrawerEntrySection.LocalLibrary,
                    )
                }
        }
    }
    // W53B：本地库行单独成一个抽屉分组，复用分组标题样式（「本地媒体库」）+ 标题上方细分隔线；
    // 三段是同一份 `drawerEntries` 的连续切片，拍平顺序不变 → `onSelect(index)` 与动作列表仍同源（踩坑 17）。
    val localEntryIndexes =
        drawerEntries
            .withIndex()
            .filter { it.value.section == DrawerEntrySection.LocalLibrary }
            .map { it.index }
    val drawerGroups =
        if (localEntryIndexes.isEmpty()) {
            listOf(CinefinDrawerGroup(title = null, items = drawerEntries.map { it.item }))
        } else {
            val localStart = localEntryIndexes.first()
            val localEnd = localEntryIndexes.last() + 1
            listOf(
                CinefinDrawerGroup(
                    title = null,
                    items = drawerEntries.take(localStart).map { it.item },
                ),
                CinefinDrawerGroup(
                    title = LocalMediaGroupTitle,
                    items = drawerEntries.subList(localStart, localEnd).map { it.item },
                    dividerAboveTitle = true,
                ),
                CinefinDrawerGroup(
                    title = null,
                    items = drawerEntries.drop(localEnd).map { it.item },
                ),
            )
        }
    val drawerSelectedIndex = drawerEntries.indexOfFirst { it.selected }

    val host: @Composable () -> Unit = {
        NavHost(
            navController = navController,
            startDestination = startDestination,
            modifier = Modifier.fillMaxSize(),
            enterTransition = { fadeIn(tween(300)) },
            exitTransition = { fadeOut(tween(300)) },
            predictivePopEnterTransition = { fadeIn(tween(300)) },
            predictivePopExitTransition = { fadeOut(tween(300)) },
        ) {
            composable<WelcomeRoute> {
                ProvideLumen {
                    WelcomeScreen(
                        onContinueClick = { navController.safeNavigate(ServersRoute) },
                        onOfflineClick = enterOfflineMode,
                    )
                }
            }
            composable<ServersRoute> {
                ProvideLumen {
                    ServersScreen(
                        navigateToUsers = { navController.safeNavigate(UsersRoute) },
                        navigateToAddresses = { serverId ->
                            navController.safeNavigate(ServerAddressesRoute(serverId))
                        },
                        onAddClick = { navController.safeNavigate(AddServerRoute) },
                        onBackClick = { navController.safePopBackStack() },
                        showBack = navController.previousBackStackEntry != null,
                        onOfflineClick = enterOfflineMode,
                    )
                }
            }
            composable<AddServerRoute> {
                ProvideLumen {
                    AddServerScreen(
                        onSuccess = { navController.safeNavigate(UsersRoute) },
                        onBackClick = { navController.safePopBackStack() },
                    )
                }
            }
            composable<ServerAddressesRoute> { backStackEntry ->
                val route: ServerAddressesRoute = backStackEntry.toRoute()
                ProvideLumen {
                    ServerAddressesScreen(
                        serverId = route.serverId,
                        navigateBack = { navController.safePopBackStack() },
                    )
                }
            }
            composable<UsersRoute> {
                ProvideLumen {
                    UsersScreen(
                        navigateToHome = { navigateHome(navController) },
                        onChangeServerClick = {
                            navController.safeNavigate(ServersRoute) {
                                popUpTo(ServersRoute) { inclusive = false }
                                launchSingleTop = true
                            }
                        },
                        onAddClick = { navController.safeNavigate(LoginRoute()) },
                        onBackClick = { navController.safePopBackStack() },
                        onPublicUserClick = { username ->
                            navController.safeNavigate(LoginRoute(username = username))
                        },
                        showBack = navController.previousBackStackEntry != null,
                        onOfflineClick = enterOfflineMode,
                    )
                }
            }
            composable<LoginRoute> { backStackEntry ->
                val route: LoginRoute = backStackEntry.toRoute()
                ProvideLumen {
                    LoginScreen(
                        onSuccess = {
                            // W36：登录成功自动退出离线模式，回到在线首页。
                            offlineModeViewModel.exitOfflineMode()
                            navController.safeNavigate(HomeRoute) {
                                popUpTo(0)
                                launchSingleTop = true
                            }
                        },
                        onChangeServerClick = {
                            navController.safeNavigate(ServersRoute) {
                                popUpTo(ServersRoute) { inclusive = false }
                                launchSingleTop = true
                            }
                        },
                        onBackClick = { navController.safePopBackStack() },
                        prefilledUsername = route.username,
                        onOfflineClick = enterOfflineMode,
                    )
                }
            }
            composable<HomeRoute> {
                if (isOfflineMode) {
                    // W36 离线首页：已下载内容入口 + 空态 + 网络恢复提示（W37 本地文件库入口占位）。
                    OfflineHomeScreen(
                        onOpenDrawer = openDrawer,
                        onOpenVideos = { navigateTopLevel(MediaRoute) },
                        onOpenMusic = { navigateTopLevel(MusicModeRoute) },
                        onOpenBooks = { navigateTopLevel(BookshelfRoute) },
                        onExitOffline =
                            if (hasCurrentUser) {
                                { offlineModeViewModel.exitOfflineMode() }
                            } else {
                                null
                            },
                        onOpenLogin =
                            if (hasServers) {
                                {
                                    navController.safeNavigate(UsersRoute) {
                                        launchSingleTop = true
                                    }
                                }
                            } else {
                                null
                            },
                    )
                } else {
                    HomeScreen(
                        onOpenDrawer = openDrawer,
                        onSearchClick = {
                            searchExpanded = true
                            navController.safeNavigate(MediaRoute) {
                                popUpTo(navController.graph.startDestinationId) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        onItemClick = { item ->
                            navigateToItem(
                                navController = navController,
                                item = item,
                                context = context,
                            )
                        },
                        // W54-D 修 bug ①：首页「最新 · <库名>」的「全部」→ 该库内容页，
                        // 默认「最近添加」排序（不写偏好；用户改排序才会落全局键）。
                        onLibraryClick = { library ->
                            navController.safeNavigate(
                                homeViewAllRoute(
                                    libraryId = library.id.toString(),
                                    libraryName = library.name,
                                    libraryType = library.type,
                                )
                            ) {
                                popUpTo(navController.graph.startDestinationId)
                                launchSingleTop = true
                            }
                        },
                        onOpenLocalLibrary = { libraryId ->
                            navController.safeNavigate(LocalLibraryRoute(libraryId))
                        },
                    )
                }
            }
            composable<VideoRoute> {
                // 视频模式页（W53）：影视域页面走 Lumen 皮肤（D24），条目点击复用统一详情分发。
                ProvideLumen {
                    VideoScreen(
                        onOpenDrawer = openDrawer,
                        onItemClick = { item ->
                            navigateToItem(
                                navController = navController,
                                item = item,
                                context = context,
                            )
                        },
                        onOpenDownloads = { navigateTopLevel(DownloadsRoute) },
                    )
                }
            }
            composable<MediaRoute> {
                if (isOfflineMode) {
                    // W36 离线媒体库：只展示已下载媒体，点条目直接走本地文件。
                    OfflineLibraryScreen(
                        onOpenDrawer = openDrawer,
                        onPlayVideo = { itemId, isEpisode ->
                            val intent = Intent(context, PlayerActivity::class.java)
                            intent.putExtra("itemId", itemId.toString())
                            intent.putExtra(
                                "itemKind",
                                if (isEpisode) BaseItemKind.EPISODE.serialName
                                else BaseItemKind.MOVIE.serialName,
                            )
                            context.startActivity(intent)
                        },
                        onOpenBook = { itemId, title ->
                            openReader(context = context, itemId = itemId.toString(), title = title)
                        },
                        onOpenLocalLibrary = { libraryId ->
                            navController.safeNavigate(LocalLibraryRoute(libraryId))
                        },
                    )
                } else {
                    ProvideLumen {
                        MediaScreen(
                            onOpenDrawer = openDrawer,
                            onItemClick = { item ->
                                navigateToItem(
                                    navController = navController,
                                    item = item,
                                    context = context,
                                )
                            },
                            onOpenLocalLibrary = { libraryId ->
                                navController.safeNavigate(LocalLibraryRoute(libraryId))
                            },
                            // W43：搜索结果打开本地条目（红线文件，已申报）——与本地库详情页同一链路。
                            onPlayLocalVideo = { itemId ->
                                val intent = Intent(context, PlayerActivity::class.java)
                                intent.putExtra("itemId", itemId.toString())
                                intent.putExtra("itemKind", BaseItemKind.MOVIE.serialName)
                                context.startActivity(intent)
                            },
                            onOpenLocalBook = { itemId, title, documentUri ->
                                openReader(
                                    context = context,
                                    itemId = itemId.toString(),
                                    title = title,
                                    localUri = documentUri,
                                )
                            },
                            onLocalMusicStarted = { navigateTopLevel(MusicModeRoute) },
                            searchExpanded = searchExpanded,
                            onSearchExpand = { searchExpanded = it },
                        )
                    }
                }
            }
            composable<LocalLibraryRoute> { backStackEntry ->
                val route: LocalLibraryRoute = backStackEntry.toRoute()
                LocalLibraryDetailScreen(
                    libraryId = route.libraryId,
                    onBack = { navController.safePopBackStack() },
                    // W53B：库级设置（「在媒体库显示」开关 / 重命名 / 条目数）变化后刷新侧栏「本地媒体库」子分组。
                    onLocalLibrariesChanged = drawerViewModel::refreshLocalLibraries,
                    onPlayVideo = { itemId ->
                        val intent = Intent(context, PlayerActivity::class.java)
                        intent.putExtra("itemId", itemId.toString())
                        intent.putExtra("itemKind", BaseItemKind.MOVIE.serialName)
                        context.startActivity(intent)
                    },
                    onOpenBook = { itemId, title, documentUri ->
                        openReader(
                            context = context,
                            itemId = itemId.toString(),
                            title = title,
                            localUri = documentUri,
                        )
                    },
                    onMusicStarted = { navigateTopLevel(MusicModeRoute) },
                )
            }
            composable<BookshelfRoute> {
                if (isOfflineMode) {
                    // W36 离线书架：已下载书籍，点击直接进本机阅读器。
                    OfflineShelfScreen(
                        onOpenDrawer = openDrawer,
                        onOpenBook = { itemId, title ->
                            openReader(context = context, itemId = itemId.toString(), title = title)
                        },
                    )
                } else {
                    BookshelfScreen(
                        onOpenDrawer = openDrawer,
                        onItemClick = { item ->
                            navigateToItem(
                                navController = navController,
                                item = item,
                                context = context,
                            )
                        },
                        navigateBack = { navController.safePopBackStack() },
                        onOpenDownloads = { navigateTopLevel(DownloadsRoute) },
                    )
                }
            }
            composable<DownloadsRoute> {
                ProvideLumen {
                    DownloadsScreen(
                        onOpenDrawer = openDrawer,
                        onItemClick = { item ->
                            navigateToItem(
                                navController = navController,
                                item = item,
                                context = context,
                            )
                        },
                    )
                }
            }
            composable<MusicModeRoute> {
                MusicModeScreen(
                    onOpenDrawer = openDrawer,
                    // W56：顶层「音乐」图标再点 = 回音乐主页（先收起全屏播放 / 歌词覆盖层，再退页内详情）。
                    reselectSignal = musicOverlayReselectSignal,
                    onInnerPageOpenChange = { musicInnerPageOpen = it },
                    onOpenDownloads = { navigateTopLevel(DownloadsRoute) },
                )
            }
            composable<TemporaryLibraryRoute> { backStackEntry ->
                // 临时库视图（W53 追加）：侧栏点服务器库 → 对应模式页直显该库；不写偏好。
                // 返回键 / 「返回默认 ×」→ 该模式页的默认库（exitTemporaryLibrary）。
                val route: TemporaryLibraryRoute = backStackEntry.toRoute()
                when (route.kind) {
                    TemporaryLibraryKind.Video ->
                        ProvideLumen {
                            VideoScreen(
                                onOpenDrawer = openDrawer,
                                onItemClick = { item ->
                                    navigateToItem(
                                        navController = navController,
                                        item = item,
                                        context = context,
                                    )
                                },
                                temporaryLibraryId = route.libraryId,
                                onExitTemporaryLibrary = { exitTemporaryLibrary(VideoRoute) },
                                onOpenDownloads = { navigateTopLevel(DownloadsRoute) },
                            )
                        }
                    TemporaryLibraryKind.Music ->
                        MusicModeScreen(
                            onOpenDrawer = openDrawer,
                            // libraryId 由 SavedStateHandle 进 ViewModel（按该库加载），name 只用于顶栏标题。
                            libraryName = route.libraryName,
                            libraryTypeLabel =
                                stringResource(
                                    libraryTypeLabelRes(
                                        CollectionType.fromString(route.libraryType)
                                    )
                                ),
                            onExitTemporaryLibrary = { exitTemporaryLibrary(MusicModeRoute) },
                            onOpenDownloads = { navigateTopLevel(DownloadsRoute) },
                        )
                    TemporaryLibraryKind.Books ->
                        BookshelfScreen(
                            onOpenDrawer = openDrawer,
                            onItemClick = { item ->
                                navigateToItem(
                                    navController = navController,
                                    item = item,
                                    context = context,
                                )
                            },
                            navigateBack = { navController.safePopBackStack() },
                            temporaryLibraryId = route.libraryId,
                            onExitTemporaryLibrary = { exitTemporaryLibrary(BookshelfRoute) },
                            onOpenDownloads = { navigateTopLevel(DownloadsRoute) },
                        )
                }
            }
            composable<ConsoleRoute> { backStackEntry ->
                val route: ConsoleRoute = backStackEntry.toRoute()
                // 控制台页不渲染 app 侧轨 / 底栏（D22 ②，避免与 jellyfin-web 自己的侧栏打架），
                // 但要给一个**可见**的回 app 入口（W7-R3 用户反馈 4）：右下角 A 风格悬浮胶囊。
                // 系统返回键的既有行为不变：控制台内先退网页历史，退无可退再离开控制台（踩坑 31）。
                Box(modifier = Modifier.fillMaxSize()) {
                    WebConsoleScreen(
                        initialPath = route.path,
                        onBack = { navController.safePopBackStack() },
                    )
                    ConsoleBackToAppPill(
                        onClick = { navigateHome(navController) },
                        modifier =
                            Modifier.align(Alignment.BottomEnd)
                                .navigationBarsPadding()
                                .padding(
                                    end = CinefinSpacing.Space4,
                                    bottom = CinefinSpacing.Space4,
                                ),
                    )
                }
            }
            composable<LibraryRoute> { backStackEntry ->
                val route: LibraryRoute = backStackEntry.toRoute()
                if (route.libraryType == CollectionType.Music) {
                    // 兜底：任何残留路由落到音乐库时同样进音乐模式（见 libraryEntryRoute）
                    MusicModeScreen(onOpenDrawer = openDrawer)
                } else {
                    // 库内容页按域换皮（W6-VIS D23）：书籍库属阅读域、保持 Prism，影视类库走 Lumen
                    LumenPage(enabled = route.libraryType != CollectionType.Books) {
                        LibraryScreen(
                            libraryId = UUID.fromString(route.libraryId),
                            libraryName = route.libraryName,
                            libraryType = route.libraryType,
                            // W54-D：首页「全部」入口带「最近添加」初始排序；其余入口为 null（沿用偏好）。
                            initialSortBy =
                                route.sortBy?.let { name ->
                                    SortBy.entries.firstOrNull { it.name == name }
                                },
                            initialSortOrder =
                                route.sortOrder?.let { name ->
                                    SortOrder.entries.firstOrNull { it.name == name }
                                },
                            onItemClick = { item ->
                                navigateToItem(
                                    navController = navController,
                                    item = item,
                                    context = context,
                                )
                            },
                            navigateBack = { navController.safePopBackStack() },
                            onOpenDownloads = { navigateTopLevel(DownloadsRoute) },
                        )
                    }
                }
            }
            composable<CollectionRoute> { backStackEntry ->
                val route: CollectionRoute = backStackEntry.toRoute()
                ProvideLumen {
                    CollectionScreen(
                        collectionId = UUID.fromString(route.collectionId),
                        collectionName = route.collectionName,
                        onItemClick = { item ->
                            navigateToItem(
                                navController = navController,
                                item = item,
                                context = context,
                            )
                        },
                        navigateBack = { navController.safePopBackStack() },
                    )
                }
            }
            composable<FavoritesRoute> {
                ProvideLumen {
                    FavoritesScreen(
                        onOpenDrawer = openDrawer,
                        onItemClick = { item ->
                            navigateToItem(
                                navController = navController,
                                item = item,
                                context = context,
                            )
                        },
                        onOpenDownloads = { navigateTopLevel(DownloadsRoute) },
                    )
                }
            }
            composable<MovieRoute> { backStackEntry ->
                val route: MovieRoute = backStackEntry.toRoute()
                MovieScreen(
                    movieId = UUID.fromString(route.movieId),
                    navigateBack = { navController.safePopBackStack() },
                    navigateHome = { navigateHome(navController) },
                    navigateToPerson = { personId ->
                        navController.safeNavigate(PersonRoute(personId.toString()))
                    },
                    onOpenDownloads = { navigateTopLevel(DownloadsRoute) },
                )
            }
            composable<ShowRoute> { backStackEntry ->
                val route: ShowRoute = backStackEntry.toRoute()
                ShowScreen(
                    showId = UUID.fromString(route.showId),
                    navigateBack = { navController.safePopBackStack() },
                    navigateHome = { navigateHome(navController) },
                    navigateToItem = { item ->
                        navigateToItem(
                            navController = navController,
                            item = item,
                            context = context,
                        )
                    },
                    navigateToPerson = { personId ->
                        navController.safeNavigate(PersonRoute(personId.toString()))
                    },
                    onOpenDownloads = { navigateTopLevel(DownloadsRoute) },
                )
            }
            composable<SeasonRoute> { backStackEntry ->
                val route: SeasonRoute = backStackEntry.toRoute()
                SeasonScreen(
                    seasonId = UUID.fromString(route.seasonId),
                    navigateBack = { navController.safePopBackStack() },
                    navigateHome = { navigateHome(navController) },
                    navigateToItem = { item ->
                        navigateToItem(
                            navController = navController,
                            item = item,
                            context = context,
                        )
                    },
                    navigateToSeries = { seriesId ->
                        navController.safeNavigate(ShowRoute(showId = seriesId.toString())) {
                            popUpTo(ShowRoute(showId = seriesId.toString()))
                            launchSingleTop = true
                        }
                    },
                    onOpenDownloads = { navigateTopLevel(DownloadsRoute) },
                )
            }
            composable<EpisodeRoute> { backStackEntry ->
                val route: EpisodeRoute = backStackEntry.toRoute()
                EpisodeScreen(
                    episodeId = UUID.fromString(route.episodeId),
                    navigateBack = { navController.safePopBackStack() },
                    navigateHome = { navigateHome(navController) },
                    navigateToPerson = { personId ->
                        navController.safeNavigate(PersonRoute(personId.toString()))
                    },
                    navigateToSeason = { seasonId ->
                        navController.safeNavigate(SeasonRoute(seasonId = seasonId.toString())) {
                            popUpTo(SeasonRoute(seasonId = seasonId.toString()))
                            launchSingleTop = true
                        }
                    },
                    onOpenDownloads = { navigateTopLevel(DownloadsRoute) },
                )
            }
            composable<PersonRoute> { backStackEntry ->
                val route: PersonRoute = backStackEntry.toRoute()
                ProvideLumen {
                    PersonScreen(
                        personId = UUID.fromString(route.personId),
                        navigateBack = { navController.safePopBackStack() },
                        navigateHome = { navigateHome(navController) },
                        navigateToItem = { item ->
                            navigateToItem(
                                navController = navController,
                                item = item,
                                context = context,
                            )
                        },
                    )
                }
            }
            composable<SettingsRoute> { backStackEntry ->
                val route: SettingsRoute = backStackEntry.toRoute()
                ProvideLumen {
                    SettingsScreen(
                        indexes = route.indexes,
                        navigateToSettings = { indexes ->
                            navController.safeNavigate(SettingsRoute(indexes = indexes))
                        },
                        navigateToSettingsFileEdit = { filePath ->
                            navController.safeNavigate(SettingsFileEditRoute(filePath = filePath))
                        },
                        navigateToServers = { navController.safeNavigate(ServersRoute) },
                        navigateToUsers = { navController.safeNavigate(UsersRoute) },
                        navigateToAbout = { navController.safeNavigate(AboutRoute) },
                        navigateBack = { navController.safePopBackStack() },
                        // W42：平板形态没有底栏，「隐藏底栏」开关需要置灰并给出说明。
                        compactForm = compactNavigation,
                    )
                }
            }
            composable<SettingsFileEditRoute> { backStackEntry ->
                val route: SettingsFileEditRoute = backStackEntry.toRoute()
                ProvideLumen {
                    SettingsFileEditScreen(
                        filePath = route.filePath,
                        navigateBack = { navController.safePopBackStack() },
                    )
                }
            }
            composable<AboutRoute> {
                ProvideLumen { AboutScreen(navigateBack = { navController.safePopBackStack() }) }
            }
        }
    }

    CinefinModalDrawer(
        drawerState = drawerState,
        // 手机可从边缘滑出抽屉（W7-R3 用户反馈 1 恢复）；控制台类页面让位给 WebView；
        // W46（用户确认）：平板展开形态禁用边缘手势——抽屉整体停用，导航走常显侧轨。
        gesturesEnabled = showNavigation && compactNavigation,
        header = {
            CinefinDrawerHeader(
                userName = drawerData.userName,
                serverName = drawerData.serverName,
                serverAddress = drawerData.serverAddress,
                onOpenServers = { navigateTopLevel(ServersRoute) },
            )
        },
        groups = drawerGroups,
        selectedIndex = drawerSelectedIndex,
        onSelect = { index -> drawerEntries.getOrNull(index)?.onClick?.invoke() },
        drawerSkin = { drawerContent -> LumenChrome(lumenChrome) { drawerContent() } },
    ) {
        when {
            compactNavigation && showNavigation ->
                Column(modifier = Modifier.fillMaxSize()) {
                    Box(modifier = Modifier.weight(1f)) { host() }
                    // W42「隐藏底栏」：紧凑形态可整体隐藏底栏（重启保留）；各页自身按 safeDrawing
                    // 底部留白，隐藏后不会顶到手势条，导航仍可从顶栏 logo / 汉堡打开抽屉到达。
                    if (!drawerData.hideBottomBar) {
                        LumenChrome(lumenChrome) {
                            // 手势条安全区也要跟着底栏一起铺满底色，否则系统导航区会露出外层主题的石板色
                            // （真机表现为底栏下缘一条 21,26,33 的色带）。
                            Box(
                                modifier =
                                    Modifier.fillMaxWidth()
                                        .background(
                                            LocalLumenColors.current?.background
                                                ?: LocalCinefinColors.current.surface
                                        )
                                        .background(
                                            LocalLumenColors.current
                                                ?.panel
                                                ?.copy(alpha = CinefinTokens.ChromeTranslucency)
                                                ?: LocalCinefinColors.current.surface
                                        )
                            ) {
                                CinefinBottomTab(
                                    items = bottomItems.map { it.item },
                                    selectedIndex = bottomItems.indexOfFirst { it.selected },
                                    onSelect = { index ->
                                        bottomItems.getOrNull(index)?.onClick?.invoke()
                                    },
                                    modifier = Modifier.navigationBarsPadding(),
                                )
                            }
                        }
                    }
                }
            !compactNavigation && showNavigation ->
                Row(modifier = Modifier.fillMaxSize()) {
                    LumenChrome(lumenChrome) {
                        CinefinSideNavigation(
                            destinations = railDestinations,
                            mediaLibraries =
                                if (isOfflineMode) emptyList() else drawerData.libraries,
                            // W53B：本地库只读本机索引，离线模式也列出（与服务器库分开的子分组）。
                            localLibraries = drawerData.localLibraries,
                            currentLibraryId = currentLibrary?.libraryId,
                            currentLocalLibraryId = currentLocalLibraryId,
                            mediaGroupExpanded = mediaGroupExpanded,
                            onToggleMediaGroup = { mediaGroupExpanded = !mediaGroupExpanded },
                            onOpenLibrary = openLibrary,
                            onOpenLocalLibrary = openLocalLibrary,
                            expanded = railExpanded,
                            onToggleExpanded = { railExpanded = !railExpanded },
                            groupBreaks = railBreaks,
                            pinnedTailCount = railPinnedTail,
                        )
                    }
                    Box(modifier = Modifier.weight(1f)) { host() }
                }
            else -> host()
        }
    }
}

/**
 * 侧柜皮肤开关（W6-VIS D23）：影视域 = S1「A · Lumen」，其余 = 当前域 Prism。
 *
 * 只换色板，不加壳——侧轨 / 底栏 / 抽屉各自带底色，用 [ProvideLumenColors] 才不会在 Column / Row 里抢空间。
 */
@Composable
private fun LumenChrome(enabled: Boolean, content: @Composable () -> Unit) {
    if (enabled) {
        ProvideLumenColors { content() }
    } else {
        content()
    }
}

/** 页面级皮肤开关：影视域页面铺 S1 A 稿底（曜石黑 + A 色板），音乐 / 阅读域保持各自皮肤。 */
@Composable
private fun LumenPage(enabled: Boolean, content: @Composable () -> Unit) {
    if (enabled) {
        ProvideLumen { content() }
    } else {
        content()
    }
}

/**
 * 控制台页的悬浮「返回极光幕」胶囊（W7-R3 用户反馈 4）。
 *
 * A 稿手法：石墨底 + 1dp 发丝线 + 顶缘内高光 + 月白文字与品牌图标；无投影（§5.3 无投影规则）。 自铺底色、只借色板，因此用
 * [ProvideLumenColors]（与侧柜同一入口）；点击回主界面， 系统返回键的「控制台内先退网页历史」行为保持不变（踩坑 31）。
 */
@Composable
private fun ConsoleBackToAppPill(onClick: () -> Unit, modifier: Modifier = Modifier) {
    ProvideLumenColors {
        val lumen = LocalLumenColors.current ?: LumenColorsDark
        Row(
            modifier =
                modifier
                    .clip(CinefinShapes.Full)
                    .background(lumen.panel.copy(alpha = 0.94f))
                    .drawBehind {
                        drawRect(
                            brush = lumenEdgeHighlight(lumen),
                            size = Size(size.width, 1.dp.toPx()),
                        )
                    }
                    .border(1.dp, lumen.line, CinefinShapes.Full)
                    .cinefinClickable(onClick = onClick)
                    .defaultMinSize(minHeight = 44.dp)
                    .padding(horizontal = CinefinSpacing.Space4, vertical = CinefinSpacing.Space2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(CoreR.drawable.ic_logo),
                contentDescription = null,
                tint = Color.Unspecified,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(CinefinSpacing.Space2))
            Text(
                text = stringResource(CoreR.string.console_back_to_app),
                style = CinefinType.NavLabel,
                color = lumen.text,
                maxLines = 1,
            )
        }
    }
}

private data class ChromeDestination(
    val key: NavEntryKey,
    val item: CinefinNavItem,
    val selected: Boolean,
    val bottom: Boolean,
    val onClick: () -> Unit,
)

/** 抽屉条目分区（W53B）：本地库行单独切成一个带标题的抽屉分组（「本地媒体库」），其余条目保持原顺序—— 分组只是渲染切片，拍平索引与动作列表仍同源（踩坑 17）。 */
private enum class DrawerEntrySection {
    Normal,
    LocalLibrary,
}

private data class DrawerEntry(
    val item: CinefinNavItem,
    val selected: Boolean,
    val onClick: () -> Unit,
    val section: DrawerEntrySection = DrawerEntrySection.Normal,
)

/**
 * 平板侧导航（§8.6）：logo 38dp + 条目 48dp（二级子项 44dp）/ 圆角 14dp；W46 起折叠 72dp / 展开 168dp。
 *
 * IA（W6-R6N）：「媒体库」是二级分组，子项是服务器实际返回的库（同名多库逐条列出）。 折叠轨（72dp）只显示一级图标，展开后子项才出现——避免 72dp 宽出现半截库名。
 */
@Composable
private fun CinefinSideNavigation(
    destinations: List<ChromeDestination>,
    mediaLibraries: List<FindroidCollection>,
    localLibraries: List<SidebarLocalLibrary>,
    currentLibraryId: String?,
    currentLocalLibraryId: Long?,
    mediaGroupExpanded: Boolean,
    onToggleMediaGroup: () -> Unit,
    onOpenLibrary: (FindroidCollection) -> Unit,
    onOpenLocalLibrary: (SidebarLocalLibrary) -> Unit,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    groupBreaks: Set<Int> = emptySet(),
    pinnedTailCount: Int = 0,
) {
    val colors = LocalCinefinColors.current
    val lumen = LocalLumenColors.current
    Column(
        modifier =
            // W42：折叠 72dp；W46：展开 150 → 168dp（168dp 下最长的侧栏文案也不省略）。
            // 底色 = 半透明石墨（W46 起 ~74%）+ 右缘发丝线 + 顶缘内高光。
            Modifier.width(if (expanded) 168.dp else 72.dp)
                .fillMaxHeight()
                .background(lumen?.background ?: colors.surface)
                .background(
                    lumen?.panel?.copy(alpha = CinefinTokens.RailTranslucency) ?: colors.navSurface
                )
                .drawBehind {
                    val stroke = 1.dp.toPx()
                    if (lumen != null) {
                        drawRect(
                            brush = lumenEdgeHighlight(lumen),
                            size = Size(size.width, stroke),
                        )
                        drawRect(
                            color = lumen.line,
                            topLeft = Offset(size.width - stroke, 0f),
                            size = Size(stroke, size.height),
                        )
                    }
                }
                .then(
                    if (lumen == null) {
                        Modifier.border(1.dp, colors.outline)
                    } else {
                        Modifier
                    }
                )
    ) {
        Row(
            modifier =
                Modifier.fillMaxWidth()
                    .height(72.dp)
                    .drawBehind {
                        if (lumen != null) {
                            val stroke = 1.dp.toPx()
                            drawRect(
                                color = lumen.lineSoft,
                                topLeft = Offset(0f, size.height - stroke),
                                size = Size(size.width, stroke),
                            )
                        }
                    }
                    .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = if (expanded) Arrangement.Start else Arrangement.Center,
        ) {
            if (lumen != null) {
                // 品牌印记放进"雾灰 + 发丝线"的小方框：与抽屉头部、设置页磁贴同一套材质语言
                Box(
                    modifier =
                        Modifier.size(38.dp)
                            .clip(CinefinShapes.Sm)
                            .background(lumen.panelElevated)
                            .border(1.dp, lumen.line, CinefinShapes.Sm),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(CoreR.drawable.ic_logo),
                        contentDescription = null,
                        tint = Color.Unspecified,
                        modifier = Modifier.size(26.dp),
                    )
                }
            } else {
                Icon(
                    painter = painterResource(CoreR.drawable.ic_logo),
                    contentDescription = null,
                    tint = Color.Unspecified,
                    modifier = Modifier.size(38.dp),
                )
            }
            if (expanded) {
                Spacer(Modifier.width(CinefinSpacing.Space3))
                Text(
                    text = stringResource(CoreR.string.app_name),
                    style = CinefinType.TitleMedium,
                    color = colors.onSurface,
                    maxLines = 1,
                )
            }
        }
        Column(
            modifier =
                Modifier.fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(
                        horizontal = if (expanded) 10.dp else 12.dp,
                        vertical = CinefinSpacing.Space3,
                    ),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            destinations.forEachIndexed { index, destination ->
                // 末尾条目（客户端设置）固定到底部设置区（W42），不参与导航区排序。
                if (pinnedTailCount > 0 && index >= destinations.size - pinnedTailCount) {
                    return@forEachIndexed
                }
                if (index > 0 && groupBreaks.contains(index - 1)) {
                    CinefinRailGroupDivider(expanded = expanded)
                }
                if (destination.key != NavEntryKey.Media) {
                    CinefinNavigationItem(
                        item = destination.item,
                        selected = destination.selected,
                        expanded = expanded,
                        onClick = destination.onClick,
                    )
                    return@forEachIndexed
                }

                CinefinNavigationItem(
                    item = destination.item,
                    // 进入某个库时父项保持高亮（子项另有高亮），与「书架 → 书籍库」的既有行为一致。
                    selected =
                        destination.selected ||
                            currentLibraryId != null ||
                            currentLocalLibraryId != null,
                    expanded = expanded,
                    onClick = {
                        destination.onClick()
                        if (!mediaGroupExpanded) onToggleMediaGroup()
                    },
                    trailing =
                        if (
                            (mediaLibraries.isNotEmpty() || localLibraries.isNotEmpty()) && expanded
                        ) {
                            {
                                Icon(
                                    painter =
                                        painterResource(
                                            if (mediaGroupExpanded) CoreR.drawable.ic_chevron_up
                                            else CoreR.drawable.ic_chevron_down
                                        ),
                                    contentDescription =
                                        stringResource(
                                            if (mediaGroupExpanded) CoreR.string.nav_collapse
                                            else CoreR.string.nav_expand
                                        ),
                                    tint = colors.onSurfaceFaint,
                                    modifier =
                                        Modifier.size(20.dp)
                                            .clickable(onClick = onToggleMediaGroup),
                                )
                            }
                        } else {
                            null
                        },
                )
                if (
                    mediaGroupExpanded &&
                        expanded &&
                        (mediaLibraries.isNotEmpty() || localLibraries.isNotEmpty())
                ) {
                    mediaLibraries.forEach { library ->
                        CinefinNavigationItem(
                            item =
                                CinefinNavItem(
                                    label = library.name,
                                    icon = navIcon(libraryIconRes(library.type)),
                                ),
                            selected = currentLibraryId == library.id.toString(),
                            expanded = true,
                            compact = true,
                            onClick = { onOpenLibrary(library) },
                            // 侧轨的 trailing 走组件参数（`CinefinNavigationItem(trailing = …)`），
                            // 与抽屉用的 `CinefinNavItem.trailing` 是两个槽位（W53 Bug B 配套）；
                            // W53B 起按「完整名称优先」实测宽度决定是否显示项目数（168dp 轨宽取舍）。
                            trailing = railLibraryCountTrailing(library.name, library.itemCount),
                            modifier = Modifier.padding(start = CinefinSpacing.Space4),
                        )
                    }
                    // W53B：服务器库之后 =「本地媒体库」子分组（分隔线 + 标题 + 每库一行）。
                    if (localLibraries.isNotEmpty()) {
                        CinefinRailGroupDivider(expanded = expanded)
                        RailSubgroupTitle(text = LocalMediaGroupTitle)
                        localLibraries.forEach { library ->
                            CinefinNavigationItem(
                                item =
                                    CinefinNavItem(
                                        label = library.name,
                                        icon = navIcon(library.type.iconRes()),
                                    ),
                                selected = currentLocalLibraryId == library.id,
                                expanded = true,
                                compact = true,
                                onClick = { onOpenLocalLibrary(library) },
                                trailing =
                                    railLibraryCountTrailing(library.name, library.itemCount),
                                modifier = Modifier.padding(start = CinefinSpacing.Space4),
                            )
                        }
                    }
                }
            }
        }
        // 底部设置区（W42）：「客户端设置」用发丝线与导航区分隔并常驻（它自己的可见性开关不能被关掉，
        // 见 NavigationIa.visibleRailKeys）。
        if (pinnedTailCount > 0 && destinations.size >= pinnedTailCount) {
            CinefinRailSectionDivider()
            destinations.takeLast(pinnedTailCount).forEach { destination ->
                CinefinNavigationItem(
                    item = destination.item,
                    selected = destination.selected,
                    expanded = expanded,
                    onClick = destination.onClick,
                    modifier =
                        Modifier.padding(
                            start = if (expanded) 10.dp else 12.dp,
                            end = if (expanded) 10.dp else 12.dp,
                            top = 4.dp,
                            bottom = 4.dp,
                        ),
                )
            }
        }
        Row(
            modifier =
                Modifier.fillMaxWidth()
                    .height(56.dp)
                    .drawBehind {
                        if (lumen != null) {
                            val stroke = 1.dp.toPx()
                            drawRect(
                                brush = lumenEdgeHighlight(lumen),
                                size = Size(size.width, stroke),
                            )
                        }
                    }
                    .clickable(onClick = onToggleExpanded)
                    .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = if (expanded) Arrangement.Start else Arrangement.Center,
        ) {
            Icon(
                painter = painterResource(CoreR.drawable.ic_arrow_right),
                contentDescription = null,
                tint = colors.onSurfaceVariant,
                modifier = Modifier.size(20.dp).rotate(if (expanded) 180f else 0f),
            )
            if (expanded) {
                Spacer(Modifier.width(CinefinSpacing.Space3))
                Text(
                    text = stringResource(CoreR.string.nav_collapse),
                    style = CinefinType.NavLabel,
                    color = colors.onSurfaceVariant,
                )
            }
        }
    }
}

private fun navigateHome(navController: NavHostController) {
    navController.safeNavigate(HomeRoute) {
        popUpTo(navController.graph.startDestinationId)
        launchSingleTop = true
    }
}

/**
 * 侧栏 / 抽屉里媒体库子项的「项目数」尾标（W53 Bug B 配套）。
 *
 * 服务器上同类型多库很常见（书籍 / 书籍3、音乐 / 音乐测试），光看名字不好区分——名称右侧补一行项目数 （服务器没返回 `ChildCount` 时整条不显示，不占位）。
 */
@Composable
private fun LibraryChildCount(count: Int) {
    Text(
        text = stringResource(CoreR.string.nav_library_item_count, count),
        style = CinefinType.BodySmall,
        color = LocalCinefinColors.current.onSurfaceFaint,
        maxLines = 1,
    )
}

/** `trailing` 槽位的可选内容：[count] 为空时返回 null（条目不显示尾标）。 */
private fun libraryChildCountTrailing(count: Int?): (@Composable () -> Unit)? =
    count?.let { value ->
        { LibraryChildCount(value) }
    }

/**
 * 侧轨库子项的尾标（W53B）：先用 [rememberTextMeasurer] 量出「库名 / 项目数」的实际宽度，再按 [libraryChildCountVisible]
 * 的「完整名称优先」规则决定是否显示项目数 —— 168dp 展开轨下文字可用宽 [railLibraryLabelWidthDp]dp，名称 +
 * 项目数放不下时省略项目数（不把库名截断成「音乐测…」）。
 *
 * 抽屉宽 320dp、文字可用宽充裕，仍走 [libraryChildCountTrailing] 的既有「有值就显示」口径。
 */
@Composable
private fun railLibraryCountTrailing(name: String, count: Int?): (@Composable () -> Unit)? {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val countText =
        if (count != null) stringResource(CoreR.string.nav_library_item_count, count) else null
    val nameWidthDp =
        with(density) {
            measurer
                .measure(name, style = CinefinType.NavLabel, maxLines = 1)
                .size
                .width
                .toDp()
                .value
        }
    val countWidthDp =
        countText?.let { text ->
            with(density) {
                measurer
                    .measure(text, style = CinefinType.BodySmall, maxLines = 1)
                    .size
                    .width
                    .toDp()
                    .value
            }
        } ?: 0f
    val show =
        count != null &&
            libraryChildCountVisible(
                labelWidthDp = railLibraryLabelWidthDp(),
                nameWidthDp = nameWidthDp,
                countWidthDp = countWidthDp,
                gapDp = RAIL_LIBRARY_COUNT_GAP_DP,
            )
    return if (show) libraryChildCountTrailing(count) else null
}

/** 侧轨二级子分组标题（W53B）：复用抽屉分组标题的字阶与颜色（`LabelSmall` + 三级文字）， 不新增配色 / 字体 / 位图；只在展开态出现（折叠轨本来就不画子项）。 */
@Composable
private fun RailSubgroupTitle(text: String) {
    val colors = LocalCinefinColors.current
    val lumen = LocalLumenColors.current
    Text(
        text = text,
        style = CinefinType.LabelSmall,
        color = lumen?.textFaint ?: colors.onSurfaceFaint,
        maxLines = 1,
        modifier =
            Modifier.fillMaxWidth()
                .padding(
                    start = 14.dp,
                    top = CinefinSpacing.Space2,
                    bottom = CinefinSpacing.Space1,
                ),
    )
}

/**
 * 打开本机阅读器（EB-10 入口改造）。
 *
 * `ReaderActivity` 是独立 Activity（Readium 导航器是 Fragment 体系，暂时不塞进 NavHost）， `exported=false` + 显式
 * Intent：入口只对 App 内可达。PDF / CBZ 由 W4 补齐前， 非 EPUB 书会在阅读页给出「打不开这本书」的说明与重试。
 */
private fun openReader(
    context: Context,
    itemId: String,
    title: String,
    /** W37：本地媒体库书籍的 SAF 文档 URI（非空时阅读器直接读用户文件夹，不拷贝源文件）。 */
    localUri: String? = null,
) {
    context.startActivity(
        Intent(context, ReaderActivity::class.java).apply {
            putExtra(ReaderActivity.EXTRA_ITEM_ID, itemId)
            putExtra(ReaderActivity.EXTRA_TITLE, title)
            if (localUri != null) putExtra(ReaderActivity.EXTRA_LOCAL_URI, localUri)
        }
    )
}

private fun navigateToItem(
    navController: NavHostController,
    item: FindroidItem,
    context: Context,
) {
    when (item) {
        is FindroidBoxSet ->
            navController.safeNavigate(
                CollectionRoute(collectionId = item.id.toString(), collectionName = item.name)
            )
        is FindroidMovie -> navController.safeNavigate(MovieRoute(movieId = item.id.toString()))
        is FindroidShow -> navController.safeNavigate(ShowRoute(showId = item.id.toString()))
        is FindroidSeason -> navController.safeNavigate(SeasonRoute(seasonId = item.id.toString()))
        is FindroidEpisode ->
            navController.safeNavigate(EpisodeRoute(episodeId = item.id.toString()))
        is FindroidCollection ->
            navController.safeNavigate(
                libraryEntryRoute(
                    libraryId = item.id.toString(),
                    libraryName = item.name,
                    libraryType = item.type,
                )
            )
        is FindroidFolder ->
            // 图书（EPUB）进本机阅读器；其余文件夹按目录继续往下浏览。
            // `kind` 存的是 Jellyfin `BaseItemKind` 的枚举常量名（BOOK），与 JSON 名（Book）大小写不同，
            // 原 `== "Book"` 永不命中，会退化成文件夹下钻（W3 R3 修正）。
            if (item.kind?.equals("BOOK", ignoreCase = true) == true) {
                openReader(context = context, itemId = item.id.toString(), title = item.name)
            } else {
                navController.safeNavigate(
                    LibraryRoute(
                        libraryId = item.id.toString(),
                        libraryName = item.name,
                        libraryType = CollectionType.Folders,
                    )
                )
            }
        else -> Unit
    }
}

private fun <T : Any> NavHostController.safeNavigate(
    route: T,
    navOptions: NavOptions? = null,
    navigatorExtras: Navigator.Extras? = null,
) {
    if (this.currentBackStackEntry?.lifecycle?.currentState == Lifecycle.State.RESUMED) {
        this.navigate(route, navOptions, navigatorExtras)
    }
}

private fun <T : Any> NavHostController.safeNavigate(
    route: T,
    builder: NavOptionsBuilder.() -> Unit,
) {
    if (this.currentBackStackEntry?.lifecycle?.currentState == Lifecycle.State.RESUMED) {
        this.navigate(route, builder)
    }
}

private fun NavHostController.safePopBackStack(): Boolean {
    return if (this.currentBackStackEntry?.lifecycle?.currentState == Lifecycle.State.RESUMED) {
        this.popBackStack()
    } else {
        false
    }
}

/**
 * 判断当前目的地是否为给定路由。
 *
 * `NavDestination.route` 对 data object 是 qualifiedName，对带参路由是「类名 + /{arg}」模板， 直接与
 * `::class.qualifiedName` 比较会恒为 false（踩坑 28）。`hasRoute` 用路由类的序列化器 哈希与 `composable<T>` 注册的目的地 id
 * 比对，带参 / 默认值路由都能命中。
 */
private inline fun <reified T : Any> NavDestination?.isRoute(): Boolean =
    this?.hasRoute<T>() == true

/** 侧柜控制台入口的纯描述（标题 / 图标 / 后台路径）：把「门控 + 列表构造」从 Composable 里拆出来， 便于单测覆盖（见 `ConsoleEntrySpecTest`）。 */
internal data class ConsoleEntrySpec(
    @param:StringRes val titleRes: Int,
    @param:DrawableRes val iconRes: Int,
    val path: String,
)

/**
 * 控制台 / 媒体资料管理器入口（W5-R3I 恢复 D18 删除的入口）。
 *
 * 历史实现（`20c4fe3^`）用 `DrawerViewModel.isAdministrator` 门控：非管理员（含管理员状态读取失败） 一律不给入口，避免把服务端管理页面暴露给普通账号。
 */
internal fun consoleEntrySpecs(isAdministrator: Boolean): List<ConsoleEntrySpec> =
    if (!isAdministrator) {
        emptyList()
    } else {
        listOf(
            ConsoleEntrySpec(
                titleRes = CoreR.string.title_console,
                iconRes = CoreR.drawable.ic_globe,
                path = ConsolePathDashboard,
            ),
            ConsoleEntrySpec(
                titleRes = CoreR.string.title_metadata_manager,
                iconRes = CoreR.drawable.ic_database,
                path = ConsolePathMetadata,
            ),
        )
    }

/**
 * 控制台两类入口的选中态：同一个 [ConsoleRoute] 目的地只能靠 path 参数区分。
 *
 * `currentPath = null` 表示**当前不在控制台目的地**（[currentPath] 只在 `ConsoleRoute` 上取值）—— 这种情况两条入口都不能高亮（W7-R3
 * 用户反馈 5：旧实现把 null 回退成 `/dashboard`， 退出控制台后「服务器控制台」仍显示选中）。
 */
internal fun consoleEntrySelected(currentPath: String?, entryPath: String): Boolean =
    currentPath != null && currentPath == entryPath

/** W51：下载角标轮询间隔（只读 Room 快照，不触发对账 / 引擎唤醒）。 */
private const val DownloadBadgePollIntervalMs = 2_000L

/** 侧栏「本地媒体库」子分组标题（W53B）：与本地库界面既有文案一致，侧轨 / 抽屉共用一份字面量。 */
private const val LocalMediaGroupTitle = "本地媒体库"
