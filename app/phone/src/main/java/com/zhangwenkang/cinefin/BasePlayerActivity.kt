package com.zhangwenkang.cinefin

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding
import com.zhangwenkang.cinefin.playback.CinefinPlaybackService
import com.zhangwenkang.cinefin.player.local.presentation.PlayerViewModel
import timber.log.Timber

abstract class BasePlayerActivity : AppCompatActivity() {

    abstract val viewModel: PlayerViewModel

    private var wasPip: Boolean = false

    /**
     * 是否允许「后台继续播放」：由子类读取设置项。
     *
     * 打开后离开播放页（锁屏、切到其它应用）不再暂停，只记录进度；画中画不受影响。 常驻播放由 [CinefinPlaybackService] 以 mediaPlayback
     * 前台服务承载（阶段 4）。
     */
    protected open fun isBackgroundAudioEnabled(): Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
    }

    override fun onStart() {
        super.onStart()
        startPlaybackService()
    }

    override fun onResume() {
        super.onResume()

        if (wasPip) {
            wasPip = false
        } else {
            // 起播窗口内不动播放状态，避免把「打开即播」覆盖成暂停（bug ②）
            viewModel.restorePlayWhenReady()
        }
        hideSystemUI()
    }

    override fun onPause() {
        super.onPause()

        if (isInPictureInPictureMode) {
            wasPip = true
        } else {
            // 起播窗口内播放器的值不可信，不回存（bug ②）
            viewModel.rememberPlayWhenReady()
            /*
             * W68：音乐后台播放不受视频页「后台播放」开关控制——用户经锁屏 / 通知误入视频页时，
             * 离开不能把音乐静音；视频页自己的后台播放仍由开关决定。
             */
            if (!isBackgroundAudioEnabled() && !viewModel.isMusicSessionActive) {
                viewModel.player.playWhenReady = false
            }
            // 音乐会话的进度由音乐链路自己上报；视频页不重复写音乐条目。
            if (!viewModel.isMusicSessionActive) {
                viewModel.updatePlaybackProgress()
            }
        }
    }

    override fun onStop() {
        super.onStop()

        if (wasPip) {
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        /*
         * 播放页真正结束（按返回键退出、换内核重启等）时收掉前台服务：
         * 开启后台播放的用户例外——服务要继续把播放交给通知栏与锁屏。
         * W68：音乐会话同样例外——服务是音乐后台播放的通知 / 锁屏宿主，不能随视频页退出而停。
         */
        if (!isBackgroundAudioEnabled() && !viewModel.isMusicSessionActive) {
            stopService(Intent(this, CinefinPlaybackService::class.java))
        }
    }

    private fun startPlaybackService() {
        try {
            startService(Intent(this, CinefinPlaybackService::class.java))
        } catch (e: Exception) {
            Timber.e(e, "启动播放服务失败：通知栏与后台播放不可用，页面内播放不受影响")
        }
    }

    protected fun hideSystemUI() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }

        window.attributes.layoutInDisplayCutoutMode =
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
    }

    protected fun configureInsets(playerControls: View) {
        playerControls.setOnApplyWindowInsetsListener { _, windowInsets ->
            val cutout = windowInsets.displayCutout
            playerControls.updatePadding(
                left = cutout?.safeInsetLeft ?: 0,
                top = cutout?.safeInsetTop ?: 0,
                right = cutout?.safeInsetRight ?: 0,
                bottom = cutout?.safeInsetBottom ?: 0,
            )
            return@setOnApplyWindowInsetsListener windowInsets
        }
    }
}
