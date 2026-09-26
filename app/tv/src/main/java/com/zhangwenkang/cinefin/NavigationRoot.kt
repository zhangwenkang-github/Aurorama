package com.zhangwenkang.cinefin

import android.os.Parcel
import android.os.Parcelable
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.zhangwenkang.cinefin.models.CollectionType
import com.zhangwenkang.cinefin.models.FindroidSeason
import com.zhangwenkang.cinefin.presentation.film.LibraryScreen
import com.zhangwenkang.cinefin.presentation.film.SeasonScreen
import com.zhangwenkang.cinefin.presentation.film.ShowScreen
import com.zhangwenkang.cinefin.presentation.settings.SettingsScreen
import com.zhangwenkang.cinefin.presentation.settings.SettingsSubScreen
import com.zhangwenkang.cinefin.presentation.setup.addserver.AddServerScreen
import com.zhangwenkang.cinefin.presentation.setup.login.LoginScreen
import com.zhangwenkang.cinefin.presentation.setup.servers.ServersScreen
import com.zhangwenkang.cinefin.presentation.setup.users.UsersScreen
import com.zhangwenkang.cinefin.presentation.setup.welcome.WelcomeScreen
import com.zhangwenkang.cinefin.ui.MainScreen
import com.zhangwenkang.cinefin.ui.MovieScreen
import com.zhangwenkang.cinefin.ui.PlayerScreen
import com.zhangwenkang.cinefin.utils.base64ToByteArray
import com.zhangwenkang.cinefin.utils.toBase64Str
import java.util.UUID
import kotlinx.parcelize.parcelableCreator
import kotlinx.serialization.Serializable
import org.jellyfin.sdk.model.api.BaseItemKind

inline fun <reified T : Parcelable> T.toBase64(): String {
    val parcel = Parcel.obtain()
    this.writeToParcel(parcel, 0)
    val bytearray = parcel.marshall()
    parcel.recycle()
    return bytearray.toBase64Str()
}

inline fun <reified T : Parcelable> String.base64ToParcelable(): T {
    val bytearray = this.base64ToByteArray()
    val parcel = Parcel.obtain()
    parcel.unmarshall(bytearray, 0, bytearray.size)
    parcel.setDataPosition(0)
    val item = parcelableCreator<T>().createFromParcel(parcel)
    return item
}

@Serializable data object WelcomeRoute

@Serializable data object ServersRoute

@Serializable data object AddServerRoute

@Serializable data object UsersRoute

@Serializable data class LoginRoute(val username: String? = null)

@Serializable data object MainRoute

@Serializable
data class LibraryRoute(
    val libraryId: String,
    val libraryName: String,
    val libraryType: CollectionType,
)

@Serializable data class MovieRoute(val itemId: String)

@Serializable data class ShowRoute(val itemId: String)

@Serializable data class SeasonRoute(val seasonId: String)

@Serializable data class PlayerRoute(val itemId: String, val itemKind: String)

@Serializable data object SettingsRoute

@Serializable data class SettingsSubRoute(val indexes: IntArray)

@OptIn(ExperimentalStdlibApi::class)
@Composable
fun NavigationRoot(
    navController: NavHostController,
    hasServers: Boolean,
    hasCurrentServer: Boolean,
    hasCurrentUser: Boolean,
) {
    val startDestination =
        when {
            hasServers && hasCurrentServer && hasCurrentUser -> MainRoute
            hasServers && hasCurrentServer -> UsersRoute
            hasServers -> ServersRoute
            else -> WelcomeRoute
        }
    NavHost(navController = navController, startDestination = startDestination) {
        composable<WelcomeRoute> {
            WelcomeScreen(onContinueClick = { navController.navigate(ServersRoute) })
        }
        composable<ServersRoute> {
            ServersScreen(
                navigateToUsers = { navController.navigate(UsersRoute) },
                onAddClick = { navController.navigate(AddServerRoute) },
            )
        }
        composable<AddServerRoute> {
            AddServerScreen(onSuccess = { navController.navigate(UsersRoute) })
        }
        composable<UsersRoute> {
            UsersScreen(
                navigateToHome = {
                    navController.navigate(MainRoute) {
                        popUpTo(startDestination) { inclusive = true }
                    }
                },
                onChangeServerClick = {
                    navController.navigate(ServersRoute) {
                        popUpTo(ServersRoute) { inclusive = false }
                        launchSingleTop = true
                    }
                },
                onAddClick = { navController.navigate(LoginRoute()) },
                onPublicUserClick = { username ->
                    navController.navigate(LoginRoute(username = username))
                },
            )
        }
        composable<LoginRoute> { backStackEntry ->
            val route: LoginRoute = backStackEntry.toRoute()
            LoginScreen(
                onSuccess = {
                    navController.navigate(MainRoute) {
                        popUpTo(startDestination) { inclusive = true }
                    }
                },
                onChangeServerClick = {
                    navController.navigate(ServersRoute) {
                        popUpTo(ServersRoute) { inclusive = false }
                        launchSingleTop = true
                    }
                },
                prefilledUsername = route.username,
            )
        }
        composable<MainRoute> {
            MainScreen(
                navigateToSettings = { navController.navigate(SettingsRoute) },
                navigateToLibrary = { libraryId, libraryName, libraryType ->
                    navController.navigate(
                        LibraryRoute(
                            libraryId = libraryId.toString(),
                            libraryName = libraryName,
                            libraryType = libraryType,
                        )
                    )
                },
                navigateToMovie = { itemId ->
                    navController.navigate(MovieRoute(itemId.toString()))
                },
                navigateToShow = { itemId -> navController.navigate(ShowRoute(itemId.toString())) },
                navigateToPlayer = { itemId, itemKind ->
                    navController.navigate(
                        PlayerRoute(itemId = itemId.toString(), itemKind = itemKind.serialName)
                    )
                },
            )
        }
        composable<LibraryRoute> { backStackEntry ->
            val route: LibraryRoute = backStackEntry.toRoute()
            LibraryScreen(
                libraryId = UUID.fromString(route.libraryId),
                libraryName = route.libraryName,
                libraryType = route.libraryType,
                navigateToLibrary = { libraryId, libraryName, libraryType ->
                    navController.navigate(
                        LibraryRoute(
                            libraryId = libraryId.toString(),
                            libraryName = libraryName,
                            libraryType = libraryType,
                        )
                    )
                },
                navigateToMovie = { itemId ->
                    navController.navigate(MovieRoute(itemId.toString()))
                },
                navigateToShow = { itemId -> navController.navigate(ShowRoute(itemId.toString())) },
            )
        }
        composable<MovieRoute> { backStackEntry ->
            val route: MovieRoute = backStackEntry.toRoute()
            MovieScreen(
                movieId = UUID.fromString(route.itemId),
                navigateToPlayer = { itemId ->
                    navController.navigate(
                        PlayerRoute(
                            itemId = itemId.toString(),
                            itemKind = BaseItemKind.MOVIE.serialName,
                        )
                    )
                },
            )
        }
        composable<ShowRoute> { backStackEntry ->
            val route: ShowRoute = backStackEntry.toRoute()
            ShowScreen(
                showId = UUID.fromString(route.itemId),
                navigateToItem = { item ->
                    when (item) {
                        is FindroidSeason -> {
                            navController.navigate(SeasonRoute(seasonId = item.id.toString()))
                        }
                    }
                },
                navigateToPlayer = { itemId ->
                    navController.navigate(
                        PlayerRoute(
                            itemId = itemId.toString(),
                            itemKind = BaseItemKind.SERIES.serialName,
                        )
                    )
                },
            )
        }
        composable<SeasonRoute> { backStackEntry ->
            val route: SeasonRoute = backStackEntry.toRoute()
            SeasonScreen(
                seasonId = UUID.fromString(route.seasonId),
                navigateToPlayer = { itemId ->
                    navController.navigate(
                        PlayerRoute(
                            itemId = itemId.toString(),
                            itemKind = BaseItemKind.SEASON.serialName,
                        )
                    )
                },
            )
        }
        composable<PlayerRoute> { backStackEntry ->
            val route: PlayerRoute = backStackEntry.toRoute()
            PlayerScreen(
                itemId = UUID.fromString(route.itemId),
                itemKind = route.itemKind,
                startFromBeginning = false,
            )
        }
        composable<SettingsRoute> {
            SettingsScreen(
                navigateToUsers = { navController.navigate(UsersRoute) },
                navigateToServers = { navController.navigate(ServersRoute) },
                navigateToSubSettings = { indexes ->
                    navController.navigate(SettingsSubRoute(indexes = indexes))
                },
            )
        }
        composable<SettingsSubRoute> { backStackEntry ->
            val route: SettingsSubRoute = backStackEntry.toRoute()
            SettingsSubScreen(
                indexes = route.indexes,
                navigateToUsers = { navController.navigate(UsersRoute) },
                navigateToServers = { navController.navigate(ServersRoute) },
                navigateToSubSettings = { indexes ->
                    navController.navigate(SettingsSubRoute(indexes = indexes))
                },
            )
        }
    }
}
