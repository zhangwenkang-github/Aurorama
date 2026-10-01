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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import com.zhangwenkang.cinefin.core.presentation.components.CinefinDrawerGroup
import com.zhangwenkang.cinefin.core.presentation.components.CinefinModalDrawer
import com.zhangwenkang.cinefin.core.presentation.components.CinefinNavItem
import com.zhangwenkang.cinefin.core.presentation.components.CinefinNavigationItem
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinSpacing
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinType
import com.zhangwenkang.cinefin.core.presentation.theme.LocalCinefinColors
import com.zhangwenkang.cinefin.models.CollectionType
import com.zhangwenkang.cinefin.models.FindroidBoxSet
import com.zhangwenkang.cinefin.models.FindroidCollection
import com.zhangwenkang.cinefin.models.FindroidEpisode
import com.zhangwenkang.cinefin.models.FindroidFolder
import com.zhangwenkang.cinefin.models.FindroidItem
import com.zhangwenkang.cinefin.models.FindroidMovie
import com.zhangwenkang.cinefin.models.FindroidSeason
import com.zhangwenkang.cinefin.models.FindroidShow
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
import com.zhangwenkang.cinefin.presentation.navigation.CinefinDrawerHeader
import com.zhangwenkang.cinefin.presentation.navigation.DrawerViewModel
import com.zhangwenkang.cinefin.presentation.navigation.NavEntryKey
import com.zhangwenkang.cinefin.presentation.navigation.bottomNavKeys
import com.zhangwenkang.cinefin.presentation.navigation.libraryIconRes
import com.zhangwenkang.cinefin.presentation.navigation.navEntryKeys
import com.zhangwenkang.cinefin.presentation.navigation.navIcon
import com.zhangwenkang.cinefin.presentation.navigation.visibleRailKeys
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
import java.util.UUID
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@Serializable data object WelcomeRoute

@Serializable data object ServersRoute

@Serializable data object AddServerRoute

@Serializable data class ServerAddressesRoute(val serverId: String)

@Serializable data object UsersRoute

@Serializable data class LoginRoute(val username: String? = null)

@Serializable data object HomeRoute

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
)

@Serializable data class CollectionRoute(val collectionId: String, val collectionName: String)

@Serializable data object FavoritesRoute

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

    val startDestination =
        when {
            hasServers && hasCurrentServer && hasCurrentUser -> HomeRoute
            hasServers && hasCurrentServer -> UsersRoute
            hasServers -> ServersRoute
            else -> WelcomeRoute
        }

    val navBackStackEntry by navController.currentBackStackEntryAsState()

    var searchExpanded by remember { mutableStateOf(false) }

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
            currentDestination.isRoute<MediaRoute>() ||
            currentDestination.isRoute<BookshelfRoute>() ||
            currentDestination.isRoute<DownloadsRoute>() ||
            currentDestination.isRoute<MusicModeRoute>() ||
            currentDestination.isRoute<LibraryRoute>() ||
            currentDestination.isRoute<SettingsRoute>()
    val context = LocalContext.current
    val settingsRoute = remember {
        SettingsRoute(indexes = intArrayOf(CoreR.string.title_settings))
    }

    val drawerViewModel: DrawerViewModel = hiltViewModel()
    val drawerData by drawerViewModel.state.collectAsStateWithLifecycle()
    // 抽屉数据要在冷启动就绪：书架 Tab 的跳转与选中态都读这份库列表（只在打开抽屉时加载会让
    // 冷启动点「书架」拿到空列表）。打开抽屉时再刷新一次，保证服务器端新建的库能及时出现。
    // 离线下 / 切回在线时库列表要跟着刷新（W6-R6N：离线开关不再重启 Activity）。
    LaunchedEffect(isOfflineMode) { drawerViewModel.load() }
    LaunchedEffect(drawerState.isOpen) {
        if (drawerState.isOpen) {
            drawerViewModel.load()
        }
    }

    // 形态分级（§4.4）：Compact 底部 tab；Medium 起侧轨（≥1200dp 默认展开 164dp）
    val windowSizeClass = currentWindowAdaptiveInfo().windowSizeClass
    val compactNavigation =
        !windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND)
    val railDefaultExpanded = windowSizeClass.isWidthAtLeastBreakpoint(1200)
    var railExpanded by
        rememberSaveable(railDefaultExpanded) { mutableStateOf(railDefaultExpanded) }
    // 「媒体库」二级分组默认展开（用户反馈 3：所有实际存在的库要作为子选项直接可见）。
    var mediaGroupExpanded by rememberSaveable { mutableStateOf(true) }

    LaunchedEffect(showNavigation, compactNavigation) {
        // 手机形态（Compact）没有抽屉；窗口从平板缩回手机时若抽屉还开着，一并收回。
        if ((!showNavigation || compactNavigation) && drawerState.isOpen) {
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
    // 控制台 / 媒体资料管理器共用 ConsoleRoute 目的地，选中态要靠 path 参数区分（否则两条入口
    // 会同时高亮，见踩坑 28 的同类问题）。
    val consolePath =
        if (currentDestination.isRoute<ConsoleRoute>()) {
            runCatching { navBackStackEntry?.toRoute<ConsoleRoute>() }.getOrNull()?.path
        } else {
            null
        }

    val homeSelected = currentDestination.isRoute<HomeRoute>()
    val musicSelected = currentDestination.isRoute<MusicModeRoute>()
    val mediaSelected = currentDestination.isRoute<MediaRoute>()
    val downloadsSelected = currentDestination.isRoute<DownloadsRoute>()
    val settingsSelected = currentDestination.isRoute<SettingsRoute>()
    val booksSelected =
        currentDestination.isRoute<BookshelfRoute>() ||
            (booksLibrary != null && currentLibrary?.libraryId == booksLibrary.id.toString())

    // 手机（Compact）不再有抽屉：底栏已经覆盖四个入口，左侧抽屉（含 hamburger 与边缘滑出）
    // 按用户反馈整体移除，避免误滑；平板保留抽屉（侧轨为主，抽屉兜底全量入口）。
    val openDrawer: (() -> Unit)? =
        if (compactNavigation) null else ({ scope.launch { drawerState.open() } })
    val closeDrawer: () -> Unit = { scope.launch { drawerState.close() } }
    val navigateTopLevel: (Any) -> Unit = { route ->
        closeDrawer()
        navController.safeNavigate(route) {
            popUpTo(navController.graph.startDestinationId) { saveState = true }
            launchSingleTop = true
            restoreState = true
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
        navController.safeNavigate(
            libraryEntryRoute(
                libraryId = library.id.toString(),
                libraryName = library.name,
                libraryType = library.type,
            )
        )
    }

    fun chromeItem(@DrawableRes res: Int, label: String, neutral: Boolean = false) =
        CinefinNavItem(label = label, neutral = neutral, icon = navIcon(res))

    val sidebarVisibility = drawerData.sidebarVisibility
    // 「服务器上实际存在什么库」的唯一来源是抽屉数据里的库列表；库列表未就绪时入口保持可见，
    // 由页面自己显示空态——避免把网络故障误判成「服务器没有音乐库 / 书籍库」（见 NavigationIa.kt）。
    val navKeys =
        navEntryKeys(
            isAdministrator = drawerData.isAdministrator,
            librariesLoaded = drawerData.libraries.isNotEmpty(),
            hasMusicLibrary = drawerData.libraries.any { it.type == CollectionType.Music },
            hasBooksLibrary = drawerData.libraries.any { it.type == CollectionType.Books },
        )
    val consoleSpecByPath = consoleEntrySpecs(drawerData.isAdministrator).associateBy { it.path }

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
            NavEntryKey.Downloads ->
                ChromeDestination(
                    key = key,
                    item =
                        chromeItem(
                            CoreR.drawable.ic_download,
                            stringResource(CoreR.string.title_download),
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
    // 手机底部 Tab 保持既有顺序（首页 / 音乐 / 书架 / 媒体库），不随侧轨排序变化。
    val bottomItems = bottomNavKeys.mapNotNull { chromeByKey[it] }
    // 侧栏 = 同一份列表按「客户端设置 → 侧栏显示」过滤（客户端设置常驻，见 NavigationIa.kt）。
    val railDestinations =
        visibleRailKeys(navKeys, sidebarVisibility).mapNotNull { chromeByKey[it] }

    // 抽屉 = 同一份统一目的地列表 + 服务器库列表（D18：不再有「更多」分区，也没有分组标题，
    // 选中索引与动作列表同源，杜绝分组聚合带来的索引错位，见踩坑 17）
    val drawerEntries: List<DrawerEntry> =
        railDestinations.map { destination ->
            DrawerEntry(
                item = destination.item,
                selected = destination.selected,
                onClick = destination.onClick,
            )
        } +
            if (isOfflineMode) {
                emptyList()
            } else {
                drawerData.libraries.map { library ->
                    DrawerEntry(
                        item = chromeItem(libraryIconRes(library.type), library.name),
                        selected = false,
                    ) {
                        openLibrary(library)
                    }
                }
            }
    val drawerGroups =
        listOf(CinefinDrawerGroup(title = null, items = drawerEntries.map { it.item }))
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
                WelcomeScreen(onContinueClick = { navController.safeNavigate(ServersRoute) })
            }
            composable<ServersRoute> {
                ServersScreen(
                    navigateToUsers = { navController.safeNavigate(UsersRoute) },
                    navigateToAddresses = { serverId ->
                        navController.safeNavigate(ServerAddressesRoute(serverId))
                    },
                    onAddClick = { navController.safeNavigate(AddServerRoute) },
                    onBackClick = { navController.safePopBackStack() },
                    showBack = navController.previousBackStackEntry != null,
                )
            }
            composable<AddServerRoute> {
                AddServerScreen(
                    onSuccess = { navController.safeNavigate(UsersRoute) },
                    onBackClick = { navController.safePopBackStack() },
                )
            }
            composable<ServerAddressesRoute> { backStackEntry ->
                val route: ServerAddressesRoute = backStackEntry.toRoute()
                ServerAddressesScreen(
                    serverId = route.serverId,
                    navigateBack = { navController.safePopBackStack() },
                )
            }
            composable<UsersRoute> {
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
                )
            }
            composable<LoginRoute> { backStackEntry ->
                val route: LoginRoute = backStackEntry.toRoute()
                LoginScreen(
                    onSuccess = {
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
                )
            }
            composable<HomeRoute> {
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
                )
            }
            composable<MediaRoute> {
                MediaScreen(
                    onOpenDrawer = openDrawer,
                    onItemClick = { item ->
                        navigateToItem(
                            navController = navController,
                            item = item,
                            context = context,
                        )
                    },
                    onFavoritesClick = { navController.safeNavigate(FavoritesRoute) },
                    searchExpanded = searchExpanded,
                    onSearchExpand = { searchExpanded = it },
                )
            }
            composable<BookshelfRoute> {
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
                )
            }
            composable<DownloadsRoute> {
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
            composable<MusicModeRoute> { MusicModeScreen(onOpenDrawer = openDrawer) }
            composable<ConsoleRoute> { backStackEntry ->
                val route: ConsoleRoute = backStackEntry.toRoute()
                WebConsoleScreen(
                    initialPath = route.path,
                    onBack = { navController.safePopBackStack() },
                )
            }
            composable<LibraryRoute> { backStackEntry ->
                val route: LibraryRoute = backStackEntry.toRoute()
                if (route.libraryType == CollectionType.Music) {
                    // 兜底：任何残留路由落到音乐库时同样进音乐模式（见 libraryEntryRoute）
                    MusicModeScreen(onOpenDrawer = openDrawer)
                } else {
                    LibraryScreen(
                        libraryId = UUID.fromString(route.libraryId),
                        libraryName = route.libraryName,
                        libraryType = route.libraryType,
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
            composable<CollectionRoute> { backStackEntry ->
                val route: CollectionRoute = backStackEntry.toRoute()
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
            composable<FavoritesRoute> {
                FavoritesScreen(
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
            composable<MovieRoute> { backStackEntry ->
                val route: MovieRoute = backStackEntry.toRoute()
                MovieScreen(
                    movieId = UUID.fromString(route.movieId),
                    navigateBack = { navController.safePopBackStack() },
                    navigateHome = { navigateHome(navController) },
                    navigateToPerson = { personId ->
                        navController.safeNavigate(PersonRoute(personId.toString()))
                    },
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
                )
            }
            composable<PersonRoute> { backStackEntry ->
                val route: PersonRoute = backStackEntry.toRoute()
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
            composable<SettingsRoute> { backStackEntry ->
                val route: SettingsRoute = backStackEntry.toRoute()
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
                )
            }
            composable<SettingsFileEditRoute> { backStackEntry ->
                val route: SettingsFileEditRoute = backStackEntry.toRoute()
                SettingsFileEditScreen(
                    filePath = route.filePath,
                    navigateBack = { navController.safePopBackStack() },
                )
            }
            composable<AboutRoute> {
                AboutScreen(navigateBack = { navController.safePopBackStack() })
            }
        }
    }

    CinefinModalDrawer(
        drawerState = drawerState,
        // 手机（Compact）没有抽屉：连边缘滑出的手势一并关掉，避免误滑（用户反馈 1）。
        gesturesEnabled = showNavigation && !compactNavigation,
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
    ) {
        when {
            compactNavigation && showNavigation ->
                Column(modifier = Modifier.fillMaxSize()) {
                    Box(modifier = Modifier.weight(1f)) { host() }
                    CinefinBottomTab(
                        items = bottomItems.map { it.item },
                        selectedIndex = bottomItems.indexOfFirst { it.selected },
                        onSelect = { index -> bottomItems.getOrNull(index)?.onClick?.invoke() },
                        modifier = Modifier.navigationBarsPadding(),
                    )
                }
            !compactNavigation && showNavigation ->
                Row(modifier = Modifier.fillMaxSize()) {
                    CinefinSideNavigation(
                        destinations = railDestinations,
                        mediaLibraries = if (isOfflineMode) emptyList() else drawerData.libraries,
                        currentLibraryId = currentLibrary?.libraryId,
                        mediaGroupExpanded = mediaGroupExpanded,
                        onToggleMediaGroup = { mediaGroupExpanded = !mediaGroupExpanded },
                        onOpenLibrary = openLibrary,
                        expanded = railExpanded,
                        onToggleExpanded = { railExpanded = !railExpanded },
                    )
                    Box(modifier = Modifier.weight(1f)) { host() }
                }
            else -> host()
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

private data class DrawerEntry(
    val item: CinefinNavItem,
    val selected: Boolean,
    val onClick: () -> Unit,
)

/**
 * 平板侧导航（§8.6）：logo 38dp + 条目 54dp / 圆角 14dp；折叠 88dp / 展开 164dp。
 *
 * IA（W6-R6N）：「媒体库」是二级分组，子项是服务器实际返回的库（同名多库逐条列出）。 折叠轨（88dp）只显示一级图标，展开后子项才出现——避免 88dp 宽出现半截库名。
 */
@Composable
private fun CinefinSideNavigation(
    destinations: List<ChromeDestination>,
    mediaLibraries: List<FindroidCollection>,
    currentLibraryId: String?,
    mediaGroupExpanded: Boolean,
    onToggleMediaGroup: () -> Unit,
    onOpenLibrary: (FindroidCollection) -> Unit,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
) {
    val colors = LocalCinefinColors.current
    Column(
        modifier =
            Modifier.width(if (expanded) 164.dp else 88.dp)
                .fillMaxHeight()
                .background(colors.navSurface)
                .border(1.dp, colors.outline)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(72.dp).padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = if (expanded) Arrangement.Start else Arrangement.Center,
        ) {
            Icon(
                painter = painterResource(CoreR.drawable.ic_logo),
                contentDescription = null,
                tint = Color.Unspecified,
                modifier = Modifier.size(38.dp),
            )
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
            destinations.forEach { destination ->
                if (destination.key != NavEntryKey.Media) {
                    CinefinNavigationItem(
                        item = destination.item,
                        selected = destination.selected,
                        expanded = expanded,
                        onClick = destination.onClick,
                    )
                    return@forEach
                }

                CinefinNavigationItem(
                    item = destination.item,
                    // 进入某个库时父项保持高亮（子项另有高亮），与「书架 → 书籍库」的既有行为一致。
                    selected = destination.selected || currentLibraryId != null,
                    expanded = expanded,
                    onClick = {
                        destination.onClick()
                        if (!mediaGroupExpanded) onToggleMediaGroup()
                    },
                    trailing =
                        if (mediaLibraries.isNotEmpty() && expanded) {
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
                if (mediaGroupExpanded && expanded && mediaLibraries.isNotEmpty()) {
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
                            modifier = Modifier.padding(start = CinefinSpacing.Space4),
                        )
                    }
                }
            }
        }
        Row(
            modifier =
                Modifier.fillMaxWidth()
                    .height(56.dp)
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
 * 媒体库入口路由（W3 R3 音乐库分支，P0 修复）。
 *
 * 测试服务器（Jellyfin 10.11.8）**没有 MusicAlbum 实体**：`LibraryViewModel` 对音乐库只查
 * `BaseItemKind.MUSIC_ALBUM`，返回 0 条 → 音乐库空列表（W2-R2 已实测确认）。因此音乐库不走 通用媒体库页， 直接进音乐模式：`MusicRepository`
 * 拉曲目后由 `MusicLibraryGrouping` 在客户端分组出 专辑 / 艺术家 / 歌曲 / 歌单（MU-2）。
 */
private fun libraryEntryRoute(
    libraryId: String,
    libraryName: String,
    libraryType: CollectionType,
): Any =
    if (libraryType == CollectionType.Music) {
        MusicModeRoute
    } else {
        LibraryRoute(libraryId = libraryId, libraryName = libraryName, libraryType = libraryType)
    }

/**
 * 打开本机阅读器（EB-10 入口改造）。
 *
 * `ReaderActivity` 是独立 Activity（Readium 导航器是 Fragment 体系，暂时不塞进 NavHost）， `exported=false` + 显式
 * Intent：入口只对 App 内可达。PDF / CBZ 由 W4 补齐前， 非 EPUB 书会在阅读页给出「打不开这本书」的说明与重试。
 */
private fun openReader(context: Context, itemId: String, title: String) {
    context.startActivity(
        Intent(context, ReaderActivity::class.java).apply {
            putExtra(ReaderActivity.EXTRA_ITEM_ID, itemId)
            putExtra(ReaderActivity.EXTRA_TITLE, title)
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

/** 控制台两类入口的选中态：同一个 [ConsoleRoute] 目的地只能靠 path 参数区分；默认值（参数缺失） 等于控制台路径。 */
internal fun consoleEntrySelected(currentPath: String?, entryPath: String): Boolean =
    (currentPath ?: ConsolePathDashboard) == entryPath
