package com.zhangwenkang.cinefin

import android.content.Context
import android.content.Intent
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavOptions
import androidx.navigation.NavOptionsBuilder
import androidx.navigation.Navigator
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.toRoute
import com.zhangwenkang.cinefin.book.presentation.reader.ReaderActivity
import com.zhangwenkang.cinefin.core.R as CoreR
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
import com.zhangwenkang.cinefin.presentation.navigation.CinefinDrawer
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
    // 主导航收进抽屉：内容区获得完整宽度，服务器信息也不再占用首页顶部
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    // 音乐模式复用同一个抽屉（入口在抽屉内），因此也允许手势拉出
    val showNavigation =
        currentRoute in navigationItemClassNames ||
            currentRoute == MusicModeRoute::class.qualifiedName
    val context = LocalContext.current
    val settingsRoute = remember {
        SettingsRoute(indexes = intArrayOf(CoreR.string.title_settings))
    }

    LaunchedEffect(showNavigation) {
        if (!showNavigation && drawerState.isOpen) {
            drawerState.close()
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = showNavigation,
        drawerContent = {
            CinefinDrawer(
                currentRoute = currentRoute,
                homeRoute = HomeRoute,
                mediaRoute = MediaRoute,
                musicRoute = MusicModeRoute,
                downloadsRoute = DownloadsRoute,
                settingsRoute = settingsRoute,
                serversRoute = ServersRoute,
                consoleRoute = ConsoleRoute(),
                metadataRoute = ConsoleRoute(path = "/metadata"),
                showMedia = !isOfflineMode,
                isOpen = drawerState.isOpen,
                onOpenLibrary = { library ->
                    navController.safeNavigate(
                        LibraryRoute(
                            libraryId = library.id.toString(),
                            libraryName = library.name,
                            libraryType = library.type,
                        )
                    )
                },
                onNavigate = { route ->
                    scope.launch { drawerState.close() }
                    navController.navigate(route) {
                        popUpTo(navController.graph.startDestinationId) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                onClose = { scope.launch { drawerState.close() } },
            )
        },
    ) {
        NavHost(
            navController = navController,
            startDestination = startDestination,
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
}

private fun navigateHome(navController: NavHostController) {
    navController.safeNavigate(HomeRoute) {
        popUpTo(navController.graph.startDestinationId)
        launchSingleTop = true
    }
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
                LibraryRoute(
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
