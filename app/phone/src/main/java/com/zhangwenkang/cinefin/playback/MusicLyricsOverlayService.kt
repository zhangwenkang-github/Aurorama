package com.zhangwenkang.cinefin.playback

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinTheme
import com.zhangwenkang.cinefin.core.presentation.theme.ContentDomain
import com.zhangwenkang.cinefin.music.data.MusicLyricsOverlayController
import com.zhangwenkang.cinefin.music.presentation.MusicLyricsOverlayContent
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * 桌面歌词悬浮窗（W23-MUSIC · D 组）。
 *
 * 普通 Service（**不是**前台服务）：音乐播放期间进程由既有播放前台服务 [CinefinPlaybackService] 保住，这里只负责把 `WindowManager`
 * 覆盖层加上去 / 摘下来， 不额外占一条通知，避免与通知播放服务抢前台。
 *
 * 窗口内容来自 [MusicLyricsOverlayController.state]（当前句 + 下一句 + 颜色 / 字号 / 语言 / 锁定）；
 * 关闭开关或停止播放后控制器会停掉本服务，服务自己也监听 `visible` 兜底退出。
 */
@AndroidEntryPoint
class MusicLyricsOverlayService : Service() {

    @Inject lateinit var controller: MusicLyricsOverlayController

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var windowManager: WindowManager? = null
    private var overlayView: ComposeView? = null
    private var params: WindowManager.LayoutParams? = null
    private var lifecycleOwner: OverlayLifecycleOwner? = null

    override fun onCreate() {
        super.onCreate()
        if (!Settings.canDrawOverlays(this)) {
            Timber.w("桌面歌词：没有「显示在其他应用上层」权限，悬浮窗不启动")
            stopSelf()
            return
        }
        val manager = getSystemService(WindowManager::class.java)
        windowManager = manager
        val owner = OverlayLifecycleOwner()
        owner.create()
        lifecycleOwner = owner
        val view = ComposeView(this)
        view.setViewTreeLifecycleOwner(owner)
        view.setViewTreeViewModelStoreOwner(owner)
        view.setViewTreeSavedStateRegistryOwner(owner)
        view.setContent {
            val state by controller.state.collectAsState()
            var panelOpen by remember { mutableStateOf(false) }
            CinefinTheme(domain = ContentDomain.Music, surfaceBackground = false) {
                MusicLyricsOverlayContent(
                    state = state,
                    panelOpen = panelOpen,
                    onTogglePanel = { panelOpen = !panelOpen },
                    onDrag = ::moveBy,
                    onCycleTint = controller::cycleTint,
                    onCycleSize = controller::cycleSize,
                    onCycleLanguage = controller::cycleLanguage,
                    onToggleLock = controller::toggleLock,
                    onClose = controller::dismiss,
                )
            }
        }
        val screen = screenBounds()
        val layoutParams =
            WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    PixelFormat.TRANSLUCENT,
                )
                .apply {
                    gravity = Gravity.TOP or Gravity.START
                    x = WINDOW_MARGIN_PX
                    y = screen.height() / 3
                }
        overlayView = view
        params = layoutParams
        runCatching { manager.addView(view, layoutParams) }
            .onFailure { error ->
                Timber.e(error, "桌面歌词：悬浮窗添加失败")
                stopSelf()
                return
            }
        owner.start()
        owner.resume()
        Timber.i("桌面歌词悬浮窗已显示")
        controller.start()
        scope.launch {
            var sawVisible = false
            controller.state.collect { state ->
                if (state.visible) {
                    sawVisible = true
                } else if (sawVisible) {
                    // 退出播放 / 关闭开关：控制器已决定隐藏，这里兜底退出
                    stopSelf()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Timber.i("桌面歌词悬浮窗已销毁")
        scope.cancel()
        overlayView?.let { view -> runCatching { windowManager?.removeView(view) } }
        overlayView = null
        params = null
        lifecycleOwner?.destroy()
        lifecycleOwner = null
        super.onDestroy()
    }

    /** 拖动：按手指位移移动窗口，并夹在屏幕内（面板展开变高时同样安全）。 */
    private fun moveBy(delta: Offset) {
        val layoutParams = params ?: return
        val view = overlayView ?: return
        val screen = screenBounds()
        val maxX = (screen.width() - view.width).coerceAtLeast(0)
        val maxY = (screen.height() - view.height).coerceAtLeast(0)
        layoutParams.x = (layoutParams.x + delta.x.toInt()).coerceIn(0, maxX)
        layoutParams.y = (layoutParams.y + delta.y.toInt()).coerceIn(0, maxY)
        runCatching { windowManager?.updateViewLayout(view, layoutParams) }
    }

    private fun screenBounds(): android.graphics.Rect =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            windowManager?.currentWindowMetrics?.bounds
                ?: android.graphics.Rect(
                    0,
                    0,
                    resources.displayMetrics.widthPixels,
                    resources.displayMetrics.heightPixels,
                )
        } else {
            android.graphics.Rect(
                0,
                0,
                resources.displayMetrics.widthPixels,
                resources.displayMetrics.heightPixels,
            )
        }

    private companion object {
        const val WINDOW_MARGIN_PX = 24
    }
}

/**
 * 给悬浮窗 ComposeView 用的最小生命周期宿主。
 *
 * Service 不是 `LifecycleOwner`，而 `ComposeView` 需要 `ViewTreeLifecycleOwner` /
 * `ViewTreeViewModelStoreOwner` / `ViewTreeSavedStateRegistryOwner` 才能挂载组合。
 */
private class OverlayLifecycleOwner : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    private val registry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()

    override val lifecycle: Lifecycle
        get() = registry

    override val viewModelStore: ViewModelStore
        get() = store

    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateController.savedStateRegistry

    fun create() {
        savedStateController.performRestore(null)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
    }

    fun start() = registry.handleLifecycleEvent(Lifecycle.Event.ON_START)

    fun resume() = registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

    fun destroy() {
        registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        store.clear()
    }
}
