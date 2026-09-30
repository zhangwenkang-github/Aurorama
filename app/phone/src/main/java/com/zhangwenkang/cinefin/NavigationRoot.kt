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
import com.zhangwenkang.cinefin.core.presentation.components.CinefinSideRail
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
import com.zhangwenkang.cinefin.presentation.navigation.libraryIconRes
import com.zhangwenkang.cinefin.presentation.navigation.navIcon
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

/**
 * 服务器 Web 控制台。
 *
 * [path] 决定进后台的哪一页：`/dashboard` 是控制台，`/metadata` 是媒体资料管理器， `/details?id=…` 用来把图书之类的条目交给服务器自带的阅读器。
 */
@Serializable data class ConsoleRoute(val path: String = "/dashboard")

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

    val navigationItems =
        when (isOfflineMode) {
            false -> listOf(homeTab, mediaTab, downloadsTab)
            true -> listOf(homeTab, downloadsTab)
        }
    val navigationItemClassNames = navigationItems.map { it.route::class.qualifiedName }

    val navBackStackEntry by navController.currentBackStackEntryAsState()

    var searchExpanded by remember { mutableStateOf(false) }

    val currentRoute = navBackStackEntry?.destination?.route
    // 主导航：手机底部 4 tab / 平板侧轨；抽屉继续承载全量入口（库列表 / 控制台 / 服务器）
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    // 顶层页面允许手势拉出抽屉；详情页等保留全宽与返回手势
    val showNavigation =
        currentRoute in navigationItemClassNames ||
            currentRoute == MusicModeRoute::class.qualifiedName ||
            currentRoute == LibraryRoute::class.qualifiedName
    val context = LocalContext.current
    val settingsRoute = remember {
        SettingsRoute(indexes = intArrayOf(CoreR.string.title_settings))
    }

    val drawerViewModel: DrawerViewModel = hiltViewModel()
    val drawerData by drawerViewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(drawerState.isOpen) {
        if (drawerState.isOpen) {
            drawerViewModel.load()
        }
    }

    LaunchedEffect(showNavigation) {
        if (!showNavigation && drawerState.isOpen) {
            drawerState.close()
        }
    }

    // 形态分级（§4.4）：Compact 底部 tab；Medium 起侧轨（≥1200dp 默认展开 164dp）
    val windowSizeClass = currentWindowAdaptiveInfo().windowSizeClass
    val compactNavigation =
        !windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND)
    val railDefaultExpanded = windowSizeClass.isWidthAtLeastBreakpoint(1200)
    var railExpanded by
        rememberSaveable(railDefaultExpanded) { mutableStateOf(railDefaultExpanded) }

    val booksLibrary = drawerData.libraries.firstOrNull { it.type == CollectionType.Books }
    val currentLibrary =
        if (currentRoute == LibraryRoute::class.qualifiedName) {
            runCatching { navBackStackEntry?.toRoute<LibraryRoute>() }.getOrNull()
        } else {
            null
        }

    val homeSelected = currentRoute == HomeRoute::class.qualifiedName
    val musicSelected = currentRoute == MusicModeRoute::class.qualifiedName
    val mediaSelected = currentRoute == MediaRoute::class.qualifiedName
    val downloadsSelected = currentRoute == DownloadsRoute::class.qualifiedName
    val settingsSelected = currentRoute == SettingsRoute::class.qualifiedName
    val booksSelected =
        booksLibrary != null && currentLibrary?.libraryId == booksLibrary.id.toString()

    val openDrawer: () -> Unit = { scope.launch { drawerState.open() } }
    val closeDrawer: () -> Unit = { scope.launch { drawerState.close() } }
    val navigateTopLevel: (Any) -> Unit = { route ->
        closeDrawer()
        navController.safeNavigate(route) {
            popUpTo(navController.graph.startDestinationId) { saveState = true }
            launchSingleTop = true
            restoreState = true
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

    val chromeDestinations =
        listOf(
            ChromeDestination(
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
            },
            ChromeDestination(
                item =
                    chromeItem(CoreR.drawable.ic_music, stringResource(CoreR.string.title_music)),
                selected = musicSelected,
                bottom = true,
            ) {
                navigateTopLevel(MusicModeRoute)
            },
            ChromeDestination(
                item =
                    chromeItem(
                        CoreR.drawable.ic_book,
                        stringResource(CoreR.string.title_book_shelf),
                    ),
                selected = booksSelected,
                bottom = true,
            ) {
                if (booksLibrary != null) {
                    navigateTopLevel(
                        libraryEntryRoute(
                            libraryId = booksLibrary.id.toString(),
                            libraryName = booksLibrary.name,
                            libraryType = booksLibrary.type,
                        )
                    )
                } else {
                    openDrawer()
                }
            },
            ChromeDestination(
                item =
                    chromeItem(CoreR.drawable.ic_library, stringResource(CoreR.string.title_media)),
                selected = mediaSelected,
                bottom = false,
            ) {
                navigateTopLevel(MediaRoute)
            },
            ChromeDestination(
                item =
                    chromeItem(
                        CoreR.drawable.ic_download,
                        stringResource(CoreR.string.title_download),
                    ),
                selected = downloadsSelected,
                bottom = false,
            ) {
                navigateTopLevel(DownloadsRoute)
            },
            ChromeDestination(
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
            },
            ChromeDestination(
                item =
                    chromeItem(
                        CoreR.drawable.ic_menu,
                        stringResource(CoreR.string.title_more),
                        neutral = true,
                    ),
                selected = false,
                bottom = true,
            ) {
                openDrawer()
            },
        )
    val bottomItems = chromeDestinations.filter { it.bottom }

    val drawerSpecs: List<Pair<String?, DrawerEntry>> = buildList {
        add(
            null to
                DrawerEntry(
                    item =
                        chromeItem(
                            CoreR.drawable.ic_home,
                            stringResource(CoreR.string.title_home),
                            neutral = true,
                        ),
                    selected = homeSelected,
                ) {
                    navigateTopLevel(HomeRoute)
                }
        )
        add(
            null to
                DrawerEntry(
                    item =
                        chromeItem(
                            CoreR.drawable.ic_music,
                            stringResource(CoreR.string.title_music),
                        ),
                    selected = musicSelected,
                ) {
                    navigateTopLevel(MusicModeRoute)
                }
        )
        if (!isOfflineMode) {
            add(
                stringResource(CoreR.string.drawer_section_media) to
                    DrawerEntry(
                        item =
                            chromeItem(
                                CoreR.drawable.ic_library,
                                stringResource(CoreR.string.title_media),
                            ),
                        selected = mediaSelected,
                    ) {
                        navigateTopLevel(MediaRoute)
                    }
            )
            drawerData.libraries.forEach { library ->
                add(
                    stringResource(CoreR.string.drawer_section_media) to
                        DrawerEntry(
                            item = chromeItem(libraryIconRes(library.type), library.name),
                            selected = false,
                        ) {
                            openLibrary(library)
                        }
                )
            }
        }
        add(
            null to
                DrawerEntry(
                    item =
                        chromeItem(
                            CoreR.drawable.ic_download,
                            stringResource(CoreR.string.title_download),
                        ),
                    selected = downloadsSelected,
                ) {
                    navigateTopLevel(DownloadsRoute)
                }
        )
        if (drawerData.isAdministrator) {
            add(
                stringResource(CoreR.string.drawer_section_management) to
                    DrawerEntry(
                        item =
                            chromeItem(
                                CoreR.drawable.ic_globe,
                                stringResource(CoreR.string.title_console),
                            ),
                        selected = currentRoute == ConsoleRoute::class.qualifiedName,
                    ) {
                        navigateTopLevel(ConsoleRoute())
                    }
            )
            add(
                stringResource(CoreR.string.drawer_section_management) to
                    DrawerEntry(
                        item =
                            chromeItem(
                                CoreR.drawable.ic_database,
                                stringResource(CoreR.string.title_metadata_manager),
                            ),
                        selected = false,
                    ) {
                        navigateTopLevel(ConsoleRoute(path = "/metadata"))
                    }
            )
        }
        add(
            stringResource(CoreR.string.drawer_section_user) to
                DrawerEntry(
                    item =
                        chromeItem(
                            CoreR.drawable.ic_settings,
                            stringResource(CoreR.string.title_settings),
                            neutral = true,
                        ),
                    selected = settingsSelected,
                ) {
                    navigateTopLevel(settingsRoute)
                }
        )
    }
    val drawerEntries = drawerSpecs.map { it.second }
    val drawerGroups =
        drawerSpecs.groupBy({ it.first }, { it.second.item }).map { (title, items) ->
            CinefinDrawerGroup(title = title, items = items)
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
                    onOpenDrawer = { scope.launch { drawerState.open() } },
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
                    onOpenDrawer = { scope.launch { drawerState.open() } },
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
            composable<DownloadsRoute> {
                DownloadsScreen(
                    onOpenDrawer = { scope.launch { drawerState.open() } },
                    onItemClick = { item ->
                        navigateToItem(
                            navController = navController,
                            item = item,
                            context = context,
                        )
                    },
                )
            }
            composable<MusicModeRoute> {
                MusicModeScreen(onOpenDrawer = { scope.launch { drawerState.open() } })
            }
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
                    MusicModeScreen(onOpenDrawer = { scope.launch { drawerState.open() } })
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
        gesturesEnabled = showNavigation,
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
                        items = chromeDestinations.map { it.item },
                        selectedIndex = chromeDestinations.indexOfFirst { it.selected },
                        onSelect = { index ->
                            chromeDestinations.getOrNull(index)?.onClick?.invoke()
                        },
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

/** 平板侧导航（§8.6）：logo 38dp + 条目 54dp / 圆角 14dp；折叠 88dp / 展开 164dp。 */
@Composable
private fun CinefinSideNavigation(
    items: List<CinefinNavItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
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
        CinefinSideRail(
            items = items,
            selectedIndex = selectedIndex,
            onSelect = onSelect,
            modifier = Modifier.weight(1f),
            expanded = expanded,
        )
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
