package com.zhangwenkang.cinefin

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
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
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinMotion
import com.zhangwenkang.cinefin.core.presentation.theme.ProvideLumen
import com.zhangwenkang.cinefin.playback.EXTRA_OPEN_MUSIC_NOW_PLAYING
import com.zhangwenkang.cinefin.presentation.components.ColdStartSplash
import com.zhangwenkang.cinefin.presentation.theme.CinefinTheme
import com.zhangwenkang.cinefin.presentation.utils.LocalOfflineMode
import com.zhangwenkang.cinefin.utils.EXTRA_OPEN_DOWNLOADS
import com.zhangwenkang.cinefin.viewmodels.MainState
import com.zhangwenkang.cinefin.viewmodels.MainViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    private val viewModel: MainViewModel by viewModels()

    /** W52：多任务下载通知的「打开下载页」请求（冷启动读 Intent，热启动由 onNewIntent 写入）。 */
    private var openDownloadsRequest by mutableStateOf(false)

    /** W68：锁屏 / 通知点击音乐条目的「打开音乐播放界面」请求（同上）。 */
    private var openMusicNowPlayingRequest by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        openDownloadsRequest = consumeOpenDownloadsRequest(intent)
        openMusicNowPlayingRequest = consumeOpenMusicNowPlayingRequest(intent)
        requestDownloadNotificationPermission()

        // 应用固定深色外观：状态栏/导航栏图标始终用浅色，
        // 否则系统处于浅色模式时会出现"深色图标压在墨底上"看不清的情况。
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )

        setContent {
            val state by viewModel.state.collectAsStateWithLifecycle()

            CinefinTheme(dynamicColor = state.isDynamicColors) {
                val navController = rememberNavController()
                // 冷启动过渡（W6-VIS D24）：登录态解析期间显示 Lumen 品牌页，就绪后主界面 220ms 淡入，
                // 取代"先黑屏、再整屏弹出"。只给品牌页套 Lumen——主界面按页面各自的域换皮。
                Box(modifier = Modifier.fillMaxSize()) {
                    if (state.isLoading) {
                        ProvideLumen { ColdStartSplash() }
                    }
                    AnimatedVisibility(
                        visible = !state.isLoading,
                        enter = fadeIn(tween(CinefinMotion.Reader)),
                    ) {
                        MainContent(
                            state = state,
                            navController = navController,
                            openDownloadsRequest = openDownloadsRequest,
                            onOpenDownloadsHandled = { openDownloadsRequest = false },
                            openMusicNowPlayingRequest = openMusicNowPlayingRequest,
                            onOpenMusicNowPlayingHandled = { openMusicNowPlayingRequest = false },
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (consumeOpenDownloadsRequest(intent)) {
            openDownloadsRequest = true
        }
        if (consumeOpenMusicNowPlayingRequest(intent)) {
            openMusicNowPlayingRequest = true
        }
    }

    /** 读取并消费「打开下载页」标记：消费后同一个 Intent 不会重复触发导航。 */
    private fun consumeOpenDownloadsRequest(intent: Intent?): Boolean {
        val requested = intent?.getBooleanExtra(EXTRA_OPEN_DOWNLOADS, false) == true
        if (requested) intent.removeExtra(EXTRA_OPEN_DOWNLOADS)
        return requested
    }

    /** W68：读取并消费「打开音乐播放界面」标记（消费后同一个 Intent 不会重复触发导航）。 */
    private fun consumeOpenMusicNowPlayingRequest(intent: Intent?): Boolean {
        val requested = intent?.getBooleanExtra(EXTRA_OPEN_MUSIC_NOW_PLAYING, false) == true
        if (requested) intent.removeExtra(EXTRA_OPEN_MUSIC_NOW_PLAYING)
        return requested
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
    navController: NavHostController,
    openDownloadsRequest: Boolean,
    onOpenDownloadsHandled: () -> Unit,
    openMusicNowPlayingRequest: Boolean,
    onOpenMusicNowPlayingHandled: () -> Unit,
) {
    CompositionLocalProvider(LocalOfflineMode provides state.isOfflineMode) {
        NavigationRoot(
            navController = navController,
            hasServers = state.hasServers,
            hasCurrentServer = state.hasCurrentServer,
            hasCurrentUser = state.hasCurrentUser,
            openMusicNowPlayingRequest = openMusicNowPlayingRequest,
            onOpenMusicNowPlayingHandled = onOpenMusicNowPlayingHandled,
        )
    }
    LaunchedEffect(openDownloadsRequest) {
        if (!openDownloadsRequest) return@LaunchedEffect
        // NavHost 尚未组合时 currentBackStackEntry 为 null；等它就绪后再跳（冷启动路径）。
        snapshotFlow { navController.currentBackStackEntry }.filterNotNull().first()
        navController.navigate(DownloadsRoute) { launchSingleTop = true }
        onOpenDownloadsHandled()
    }
}
