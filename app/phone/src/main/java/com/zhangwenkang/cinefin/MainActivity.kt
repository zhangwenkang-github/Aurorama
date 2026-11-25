package com.zhangwenkang.cinefin

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import com.zhangwenkang.cinefin.book.presentation.reader.ReaderActivity
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinMotion
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinTheme
import com.zhangwenkang.cinefin.music.presentation.MusicItemRoute
import com.zhangwenkang.cinefin.playback.EXTRA_OPEN_MUSIC_NOW_PLAYING
import com.zhangwenkang.cinefin.presentation.components.ColdStartSplash
import com.zhangwenkang.cinefin.presentation.film.DownloadTab
import com.zhangwenkang.cinefin.presentation.theme.AppThemePresentation
import com.zhangwenkang.cinefin.presentation.theme.resolveAppThemePresentation
import com.zhangwenkang.cinefin.presentation.utils.LocalOfflineMode
import com.zhangwenkang.cinefin.utils.DOWNLOADS_TAB_COMPLETED
import com.zhangwenkang.cinefin.utils.DOWNLOADS_TAB_FAILED
import com.zhangwenkang.cinefin.utils.DownloadMediaKind
import com.zhangwenkang.cinefin.utils.EXTRA_DOWNLOADS_TAB
import com.zhangwenkang.cinefin.utils.EXTRA_DOWNLOAD_COMPLETION_ITEM_ID
import com.zhangwenkang.cinefin.utils.EXTRA_DOWNLOAD_COMPLETION_MEDIA_KIND
import com.zhangwenkang.cinefin.utils.EXTRA_OPEN_DOWNLOADS
import com.zhangwenkang.cinefin.utils.OfflineMediaEntry
import com.zhangwenkang.cinefin.utils.OfflineMediaEntryKind
import com.zhangwenkang.cinefin.utils.OfflineMediaRepository
import com.zhangwenkang.cinefin.utils.parseDownloadCompletionMediaKind
import com.zhangwenkang.cinefin.viewmodels.MainState
import com.zhangwenkang.cinefin.viewmodels.MainViewModel
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.model.api.BaseItemKind

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    private val viewModel: MainViewModel by viewModels()

    /** W7-T5：完成通知点击直达播放 / 阅读；本地可达性在线程池解析后落到 [downloadCompletionLaunch]。 */
    @Inject lateinit var offlineMediaRepository: OfflineMediaRepository

    /** W52 / W90-A2：下载通知的「打开指定页签」请求（冷启动读 Intent，热启动由 onNewIntent 写入）。 */
    private var openDownloadsRequest by mutableStateOf<DownloadsRoute?>(null)
    private val downloadsLaunchRequests =
        DownloadsLaunchRequestState(lastRequestId = System.nanoTime())

    /** W68：锁屏 / 通知点击音乐条目的「打开音乐播放界面」请求（同上）。 */
    private var openMusicNowPlayingRequest by mutableStateOf(false)

    /** W2-T13（需求 #2）：播放页「全部播放设置 →」请求——落到设置页的指定分类子页。 */
    private var openSettingsCategory by mutableStateOf<Int?>(null)

    /** W7-T5（需求 27）：完成通知点击解析后的最终落点。 */
    private var downloadCompletionLaunch by mutableStateOf<DownloadCompletionLaunch?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        openDownloadsRequest = consumeOpenDownloadsRequest(intent)
        openMusicNowPlayingRequest = consumeOpenMusicNowPlayingRequest(intent)
        openSettingsCategory = consumeOpenSettingsRequest(intent)
        consumeDownloadCompletionRequest(intent)?.let(::resolveDownloadCompletionRequest)
        requestDownloadNotificationPermission()

        // 先用深色外观起屏（浅色系统栏图标），避免冷启动闪白；
        // 进入 Compose 后按主题明暗再切换一次（见下方 LaunchedEffect），浅色主题用深色系统栏图标。
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )

        setContent {
            val state by viewModel.state.collectAsStateWithLifecycle()
            val systemInDarkTheme = isSystemInDarkTheme()
            // W5-T1 返修：明暗与色板解析抽成纯函数（`presentation/theme/AppThemeResolution.kt`），
            // 音乐域与桌面歌词悬浮窗共用同一结论，避免第二套映射。
            val appTheme =
                resolveAppThemePresentation(
                    themeId = state.themeId,
                    systemDark = systemInDarkTheme,
                    dynamicColorsEnabled = state.isDynamicColors,
                )
            val darkTheme = appTheme.darkTheme
            val themeId = appTheme.themeId

            val activity = LocalActivity.current as? ComponentActivity
            LaunchedEffect(darkTheme) {
                val barStyle =
                    if (darkTheme) {
                        SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                    } else {
                        SystemBarStyle.light(
                            android.graphics.Color.TRANSPARENT,
                            android.graphics.Color.TRANSPARENT,
                        )
                    }
                activity?.enableEdgeToEdge(
                    statusBarStyle = barStyle,
                    navigationBarStyle = barStyle,
                )
            }

            CinefinTheme(
                themeId = themeId,
                darkTheme = darkTheme,
                dynamicColor = appTheme.dynamicColor,
            ) {
                val navController = rememberNavController()
                // 冷启动过渡（W6-VIS D24）：登录态解析期间显示品牌页，就绪后主界面 220ms 淡入，
                // 取代"先黑屏、再整屏弹出"。
                Box(modifier = Modifier.fillMaxSize()) {
                    if (state.isLoading) {
                        ColdStartSplash()
                    }
                    AnimatedVisibility(
                        visible = !state.isLoading,
                        enter = fadeIn(tween(CinefinMotion.Reader)),
                    ) {
                        MainContent(
                            state = state,
                            appTheme = appTheme,
                            navController = navController,
                            openDownloadsRequest = openDownloadsRequest,
                            onOpenDownloadsHandled = { openDownloadsRequest = null },
                            openMusicNowPlayingRequest = openMusicNowPlayingRequest,
                            onOpenMusicNowPlayingHandled = { openMusicNowPlayingRequest = false },
                            downloadCompletionLaunch = downloadCompletionLaunch,
                            onDownloadCompletionHandled = { downloadCompletionLaunch = null },
                            openSettingsCategory = openSettingsCategory,
                            onOpenSettingsHandled = { openSettingsCategory = null },
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeOpenDownloadsRequest(intent)?.let { openDownloadsRequest = it }
        if (consumeOpenMusicNowPlayingRequest(intent)) {
            openMusicNowPlayingRequest = true
        }
        consumeOpenSettingsRequest(intent)?.let { openSettingsCategory = it }
        consumeDownloadCompletionRequest(intent)?.let(::resolveDownloadCompletionRequest)
    }

    /** 读取并消费「打开下载页」标记：消费后同一个 Intent 不会重复触发导航。 */
    private fun consumeOpenDownloadsRequest(intent: Intent?): DownloadsRoute? {
        val request =
            downloadsLaunchRequests.consume(
                openDownloads = intent?.getBooleanExtra(EXTRA_OPEN_DOWNLOADS, false) == true,
                requestedTab = intent?.getStringExtra(EXTRA_DOWNLOADS_TAB),
            )
        if (request != null && intent != null) {
            intent.removeExtra(EXTRA_OPEN_DOWNLOADS)
            intent.removeExtra(EXTRA_DOWNLOADS_TAB)
        }
        return request
    }

    /** W68：读取并消费「打开音乐播放界面」标记（消费后同一个 Intent 不会重复触发导航）。 */
    private fun consumeOpenMusicNowPlayingRequest(intent: Intent?): Boolean {
        val requested = intent?.getBooleanExtra(EXTRA_OPEN_MUSIC_NOW_PLAYING, false) == true
        if (requested) intent.removeExtra(EXTRA_OPEN_MUSIC_NOW_PLAYING)
        return requested
    }

    /** W2-T13（需求 #2）：读取并消费「跳到设置页指定分类」的标记（播放页「全部播放设置 →」）。 */
    private fun consumeOpenSettingsRequest(intent: Intent?): Int? {
        val category = intent?.getIntExtra(EXTRA_OPEN_SETTINGS_CATEGORY, 0) ?: 0
        if (category == 0) return null
        intent?.removeExtra(EXTRA_OPEN_SETTINGS_CATEGORY)
        return category
    }

    /** W7-T5：读取并消费完成通知的「直达条目」标记。 */
    private fun consumeDownloadCompletionRequest(intent: Intent?): DownloadCompletionRequest? {
        if (intent == null) return null
        val itemId =
            intent
                .getStringExtra(EXTRA_DOWNLOAD_COMPLETION_ITEM_ID)
                ?.trim()
                ?.takeIf(String::isNotEmpty) ?: return null
        val mediaKind =
            parseDownloadCompletionMediaKind(
                intent.getStringExtra(EXTRA_DOWNLOAD_COMPLETION_MEDIA_KIND)
            )
        intent.removeExtra(EXTRA_DOWNLOAD_COMPLETION_ITEM_ID)
        intent.removeExtra(EXTRA_DOWNLOAD_COMPLETION_MEDIA_KIND)
        return DownloadCompletionRequest(itemId = itemId, mediaKind = mediaKind)
    }

    /**
     * W7-T5（需求 27）：在线程池核对条目仍在本地媒体库，再决定直达还是回退下载页「已完成」。
     *
     * 条目已删除 / 离线记录缺失时回退，不闪退也不留死链。
     */
    private fun resolveDownloadCompletionRequest(request: DownloadCompletionRequest) {
        lifecycleScope.launch {
            val entry =
                withContext(Dispatchers.IO) {
                    runCatching { offlineMediaRepository.listEntries() }
                        .getOrNull()
                        ?.firstOrNull {
                            it.itemId.toString().equals(request.itemId, ignoreCase = true)
                        }
                }
            when (val launch = resolveDownloadCompletionLaunch(request.mediaKind, entry)) {
                DownloadCompletionLaunch.CompletedDownloads ->
                    openDownloadsRequest =
                        downloadsLaunchRequests.consume(
                            openDownloads = true,
                            requestedTab = DOWNLOADS_TAB_COMPLETED,
                        )
                else -> downloadCompletionLaunch = launch
            }
        }
    }

    /**
     * W50：自研下载引擎的前台 / 完成通知需要通知权限（Android 13+）。
     *
     * 用户拒绝后系统不再弹出；下载本身不受影响。
     */
    private fun requestDownloadNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted =
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        if (granted) return
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.POST_NOTIFICATIONS),
            REQUEST_POST_NOTIFICATIONS,
        )
    }

    private companion object {
        const val REQUEST_POST_NOTIFICATIONS = 4210
    }
}

@Composable
private fun MainContent(
    state: MainState,
    /** W5-T1 返修：主界面已解析好的主题呈现，音乐域直接复用同一结论。 */
    appTheme: AppThemePresentation,
    navController: NavHostController,
    openDownloadsRequest: DownloadsRoute?,
    onOpenDownloadsHandled: () -> Unit,
    openMusicNowPlayingRequest: Boolean,
    onOpenMusicNowPlayingHandled: () -> Unit,
    downloadCompletionLaunch: DownloadCompletionLaunch?,
    onDownloadCompletionHandled: () -> Unit,
    /** W2-T13（需求 #2）：跳到设置页指定分类子页；null = 无请求。 */
    openSettingsCategory: Int?,
    onOpenSettingsHandled: () -> Unit,
) {
    CompositionLocalProvider(LocalOfflineMode provides state.isOfflineMode) {
        NavigationRoot(
            navController = navController,
            appTheme = appTheme,
            hasServers = state.hasServers,
            hasCurrentServer = state.hasCurrentServer,
            hasCurrentUser = state.hasCurrentUser,
            openMusicNowPlayingRequest = openMusicNowPlayingRequest,
            onOpenMusicNowPlayingHandled = onOpenMusicNowPlayingHandled,
        )
    }
    LaunchedEffect(openDownloadsRequest?.requestId) {
        val request = openDownloadsRequest ?: return@LaunchedEffect
        // NavHost 尚未组合时 currentBackStackEntry 为 null；等它就绪后再跳（冷启动路径）。
        snapshotFlow { navController.currentBackStackEntry }.filterNotNull().first()
        navController.navigate(request) {
            popUpTo<DownloadsRoute> { inclusive = true }
            launchSingleTop = true
        }
        onOpenDownloadsHandled()
    }
    // W2-T13（需求 #2）：播放页「全部播放设置 →」——等 NavHost 就绪后落到设置页对应分类子页。
    LaunchedEffect(openSettingsCategory) {
        val category = openSettingsCategory ?: return@LaunchedEffect
        snapshotFlow { navController.currentBackStackEntry }.filterNotNull().first()
        navController.navigate(SettingsRoute(indexes = intArrayOf(category))) {
            launchSingleTop = true
        }
        onOpenSettingsHandled()
    }
    val context = LocalContext.current
    LaunchedEffect(downloadCompletionLaunch) {
        val launch = downloadCompletionLaunch ?: return@LaunchedEffect
        when (launch) {
            is DownloadCompletionLaunch.Music -> {
                // NavHost 就绪后再导航（冷启动路径），与下载页请求同一等待方式。
                snapshotFlow { navController.currentBackStackEntry }.filterNotNull().first()
                navController.navigate(MusicItemRoute(itemId = launch.itemId)) {
                    launchSingleTop = true
                }
            }
            is DownloadCompletionLaunch.Video -> {
                context.startActivity(
                    Intent(context, PlayerActivity::class.java).apply {
                        putExtra("itemId", launch.itemId)
                        putExtra(
                            "itemKind",
                            if (launch.isEpisode) BaseItemKind.EPISODE.serialName
                            else BaseItemKind.MOVIE.serialName,
                        )
                        addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    }
                )
            }
            is DownloadCompletionLaunch.Book -> {
                context.startActivity(
                    Intent(context, ReaderActivity::class.java).apply {
                        putExtra(ReaderActivity.EXTRA_ITEM_ID, launch.itemId)
                        putExtra(ReaderActivity.EXTRA_TITLE, launch.title)
                        addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    }
                )
            }
            DownloadCompletionLaunch.CompletedDownloads -> Unit
        }
        onDownloadCompletionHandled()
    }
}

internal fun parseDownloadsLaunchTab(
    openDownloads: Boolean,
    requestedTab: String?,
): DownloadTab? =
    when {
        !openDownloads -> null
        requestedTab == DOWNLOADS_TAB_FAILED -> DownloadTab.FAILED
        requestedTab == DOWNLOADS_TAB_COMPLETED -> DownloadTab.COMPLETED
        else -> DownloadTab.ACTIVE
    }

/** W7-T5：完成通知点击的条目身份。 */
internal data class DownloadCompletionRequest(
    val itemId: String,
    val mediaKind: DownloadMediaKind?,
)

/** W7-T5：完成通知点击的最终落点。 */
internal sealed interface DownloadCompletionLaunch {
    data class Music(val itemId: String) : DownloadCompletionLaunch

    data class Video(val itemId: String, val isEpisode: Boolean) : DownloadCompletionLaunch

    data class Book(val itemId: String, val title: String) : DownloadCompletionLaunch

    /** 本地已不可达（已删除 / 离线库无记录）→ 下载页「已完成」分组。 */
    data object CompletedDownloads : DownloadCompletionLaunch
}

/**
 * 完成通知目的地映射（纯函数）：只有媒体类型与本地条目类型一致才直达，否则回退下载页。
 *
 * 音乐 → 全屏播放（`MusicItemRoute`）、视频 → `PlayerActivity`、书籍 → 阅读器。
 */
internal fun resolveDownloadCompletionLaunch(
    mediaKind: DownloadMediaKind?,
    entry: OfflineMediaEntry?,
): DownloadCompletionLaunch {
    if (entry == null) return DownloadCompletionLaunch.CompletedDownloads
    return when (mediaKind) {
        DownloadMediaKind.MUSIC ->
            if (entry.kind == OfflineMediaEntryKind.MUSIC) {
                DownloadCompletionLaunch.Music(entry.itemId.toString())
            } else {
                DownloadCompletionLaunch.CompletedDownloads
            }
        DownloadMediaKind.VIDEO ->
            if (entry.kind == OfflineMediaEntryKind.VIDEO) {
                DownloadCompletionLaunch.Video(
                    itemId = entry.itemId.toString(),
                    isEpisode = entry.isEpisode,
                )
            } else {
                DownloadCompletionLaunch.CompletedDownloads
            }
        DownloadMediaKind.BOOK ->
            if (entry.kind == OfflineMediaEntryKind.BOOK) {
                DownloadCompletionLaunch.Book(
                    itemId = entry.itemId.toString(),
                    title = entry.name,
                )
            } else {
                DownloadCompletionLaunch.CompletedDownloads
            }
        null -> DownloadCompletionLaunch.CompletedDownloads
    }
}

internal class DownloadsLaunchRequestState(private var lastRequestId: Long) {
    fun consume(
        openDownloads: Boolean,
        requestedTab: String?,
    ): DownloadsRoute? {
        val tab = parseDownloadsLaunchTab(openDownloads, requestedTab) ?: return null
        lastRequestId = if (lastRequestId == Long.MAX_VALUE) 1L else lastRequestId + 1L
        return DownloadsRoute(initialTab = tab, requestId = lastRequestId)
    }
}
