package com.zhangwenkang.cinefin

import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding
import androidx.media3.session.MediaSession
import com.zhangwenkang.cinefin.player.local.presentation.PlayerViewModel

abstract class BasePlayerActivity : AppCompatActivity() {

    abstract val viewModel: PlayerViewModel

    private lateinit var mediaSession: MediaSession
    private var wasPip: Boolean = false

    /**
     * 是否允许「后台继续播放」：由子类读取设置项。
     *
     * 打开后离开播放页（锁屏、切到其它应用）不再暂停，只记录进度；
     * 画中画与投屏不受影响。真正的常驻播放仍需要前台服务，见 docs/PLAYER_SPEC.md。
     */
    protected open fun isBackgroundAudioEnabled(): Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
    }

    override fun onStart() {
        super.onStart()

        mediaSession = MediaSession.Builder(this, viewModel.player).build()
    }

    override fun onResume() {
        super.onResume()

        if (wasPip) {
            wasPip = false
        } else {
            viewModel.player.playWhenReady = viewModel.playWhenReady
        }
        hideSystemUI()
    }

    override fun onPause() {
        super.onPause()

        if (isInPictureInPictureMode) {
            wasPip = true
        } else {
            viewModel.playWhenReady = viewModel.player.playWhenReady
            if (!isBackgroundAudioEnabled()) {
                viewModel.player.playWhenReady = false
            }
            viewModel.updatePlaybackProgress()
        }
    }

    override fun onStop() {
        super.onStop()

        mediaSession.release()

        if (wasPip) {
            finish()
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
