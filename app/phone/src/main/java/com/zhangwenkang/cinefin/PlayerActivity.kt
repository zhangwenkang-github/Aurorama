package com.zhangwenkang.cinefin

import android.app.AppOpsManager
import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.TransitionDrawable
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Rational
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.Space
import android.widget.TextView
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.DefaultTimeBar
import androidx.media3.ui.PlayerControlView
import androidx.media3.ui.PlayerView
import coil3.BitmapImage
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.bitmapConfig
import dagger.hilt.android.AndroidEntryPoint
import com.zhangwenkang.cinefin.databinding.ActivityPlayerBinding
import com.zhangwenkang.cinefin.player.local.presentation.PlayerEvents
import com.zhangwenkang.cinefin.player.local.presentation.PlayerViewModel
import com.zhangwenkang.cinefin.repository.JellyfinRepository
import com.zhangwenkang.cinefin.presentation.player.PlayerControlOverlay
import com.zhangwenkang.cinefin.presentation.player.PlayerControlsState
import com.zhangwenkang.cinefin.presentation.player.SpeedSelectionDialogFragment
import com.zhangwenkang.cinefin.presentation.player.TrackSelectionDialogFragment
import com.zhangwenkang.cinefin.presentation.theme.CinefinTheme
import com.zhangwenkang.cinefin.settings.domain.AppPreferences
import com.zhangwenkang.cinefin.utils.AmbientColors
import com.zhangwenkang.cinefin.utils.PlayerGestureHelper
import com.zhangwenkang.cinefin.utils.PreviewScrubListener
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber

var isControlsLocked: Boolean = false

/** 氛围背景切换的淡入时长 */
private const val AMBIENT_FADE_DURATION = 600

@AndroidEntryPoint
class PlayerActivity : BasePlayerActivity() {

    @Inject lateinit var appPreferences: AppPreferences
    @Inject lateinit var repository: JellyfinRepository

    lateinit var binding: ActivityPlayerBinding
    private var playerGestureHelper: PlayerGestureHelper? = null
    override val viewModel: PlayerViewModel by viewModels()

    override fun isBackgroundAudioEnabled(): Boolean =
        appPreferences.getValue(appPreferences.playerBackgroundAudio)
    private var wasZoom: Boolean = false
    private var ambientItemId: UUID? = null

    /** Compose 控制层的可见性/锁定状态：控件层与手势层共用同一份状态 */
    private val controlsState = PlayerControlsState()

    private val isPipSupported by lazy {
        // Check if device has PiP feature
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
            return@lazy false
        }

        // Check if PiP is enabled for the app
        val appOps = getSystemService(APP_OPS_SERVICE) as AppOpsManager?
        appOps?.checkOpNoThrow(
            AppOpsManager.OPSTR_PICTURE_IN_PICTURE,
            Process.myUid(),
            packageName,
        ) == AppOpsManager.MODE_ALLOWED
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val itemId = UUID.fromString(intent.extras!!.getString("itemId"))
        val itemKind = intent.extras!!.getString("itemKind")
        val startFromBeginning = intent.extras!!.getBoolean("startFromBeginning")

        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        binding.playerView.player = viewModel.player
        configureSubtitleStyle()

        // 控制层改用 Compose 渲染（PlayerControlOverlay），Media3 自带控制器整体停用：
        // 皮肤、面板、动效因此只有一套实现，也不会再出现两套控件互相打架
        binding.playerView.useController = false

        isControlsLocked = false

        binding.controlOverlayCompose.setContent {
            CinefinTheme {
                val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                PlayerControlOverlay(
                    player = viewModel.player,
                    uiState = uiState,
                    controls = controlsState,
                    isPipSupported = isPipSupported,
                    onBack = { finishPlayback() },
                    onPip = { pictureInPicture() },
                    onSelectSpeed = { speed -> viewModel.selectSpeed(speed) },
                    onSelectTrack = { type, index -> viewModel.switchToTrack(type, index) },
                    onSkipSegment = { segment -> viewModel.skipSegment(segment) },
                    onRegionsChanged = { visible, panelOpen, locked ->
                        binding.controlOverlay.controlsVisible = visible
                        binding.controlOverlay.panelOpen = panelOpen
                        binding.controlOverlay.locked = locked
                    },
                )
            }
        }

        // 锁定状态回写：手势层读同一个标记，锁屏后只留解锁按钮
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                snapshotFlow { controlsState.locked }.collect { locked ->
                    isControlsLocked = locked
                    requestedOrientation =
                        if (locked) {
                            ActivityInfo.SCREEN_ORIENTATION_LOCKED
                        } else {
                            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                        }
                }
            }
        }

        if (appPreferences.getValue(appPreferences.playerGestures)) {
            playerGestureHelper =
                PlayerGestureHelper(
                    appPreferences,
                    this,
                    binding.playerView,
                    getSystemService(AUDIO_SERVICE) as AudioManager,
                    onSingleTap = { controlsState.toggle() },
                )
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.uiState.collect { uiState ->
                        Timber.d("$uiState")
                        uiState.apply {
                            // 氛围背景：跟随当前影片海报取色
                            currentItemId?.let { updateAmbientBackdrop(it) }

                            // 标题 / 章节 / 片段 / Trickplay 直接由 Compose 控制层消费 uiState；
                            // 这里只把 Trickplay 同步给手势层的进度 HUD
                            playerGestureHelper?.let { it.currentTrickplay = currentTrickplay }
                        }
                    }
                }

                launch {
                    viewModel.eventsChannelFlow.collect { event ->
                        when (event) {
                            is PlayerEvents.NavigateBack -> finishPlayback()
                            is PlayerEvents.IsPlayingChanged -> {
                                if (event.isPlaying) {
                                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                                } else {
                                    window.clearFlags(
                                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                                    )
                                }

                                if (appPreferences.getValue(appPreferences.playerPipGesture)) {
                                    try {
                                        setPictureInPictureParams(pipParams(event.isPlaying))
                                    } catch (_: IllegalArgumentException) {}
                                }
                            }
                        }
                    }
                }

                launch {
                    while (true) {
                        viewModel.updatePlaybackProgress()
                        delay(5000L)
                    }
                }

                if (
                    appPreferences.getValue(appPreferences.playerMediaSegmentsSkipButton) ||
                        appPreferences.getValue(appPreferences.playerMediaSegmentsAutoSkip)
                ) {
                    launch {
                        while (true) {
                            viewModel.updateCurrentSegment()
                            delay(1000L)
                        }
                    }
                }
            }
        }

        viewModel.initializePlayer(
            itemId = itemId,
            itemKind = itemKind ?: "",
            startFromBeginning = startFromBeginning,
        )
        hideSystemUI()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)

        val itemId = UUID.fromString(intent.extras!!.getString("itemId"))
        val itemKind = intent.extras!!.getString("itemKind")
        val startFromBeginning = intent.extras!!.getBoolean("startFromBeginning")

        viewModel.initializePlayer(
            itemId = itemId,
            itemKind = itemKind ?: "",
            startFromBeginning = startFromBeginning,
        )
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S &&
                appPreferences.getValue(appPreferences.playerPipGesture) &&
                viewModel.player.isPlaying &&
                !isControlsLocked
        ) {
            pictureInPicture()
        }
    }

    /**
     * 字幕样式：media3 默认沿用系统字幕样式，系统字幕未开启时会退回「白字 + 不透明黑底」，
     * 在画面上呈现为突兀的黑色方块。这里统一改为白字 + 黑色描边，不绘制底色。
     */
    @androidx.annotation.OptIn(UnstableApi::class)
    private fun configureSubtitleStyle() {
        binding.playerView.subtitleView?.setStyle(
            CaptionStyleCompat(
                /* foregroundColor = */ Color.WHITE,
                /* backgroundColor = */ Color.TRANSPARENT,
                /* windowColor = */ Color.TRANSPARENT,
                /* edgeType = */ CaptionStyleCompat.EDGE_TYPE_OUTLINE,
                /* edgeColor = */ Color.BLACK,
                /* typeface = */ null,
            )
        )
    }

    /**
     * 用当前影片的海报/剧照生成播放页氛围背景。
     *
     * 只在画面未铺满屏幕的区域（上下黑边）与缓冲加载态可见；
     * 控件配色保持应用统一的冰蓝，避免整屏变色干扰观看。
     */
    private fun updateAmbientBackdrop(itemId: UUID) {
        if (ambientItemId == itemId) return
        ambientItemId = itemId

        lifecycleScope.launch {
            val item = runCatching { repository.getItem(itemId) }.getOrNull() ?: return@launch
            val imageUri = item.images.backdrop ?: item.images.primary ?: return@launch

            val request =
                ImageRequest.Builder(this@PlayerActivity)
                    .data(imageUri)
                    .size(AmbientColors.SAMPLE_SIZE)
                    // 必须是软件位图，硬件位图无法读取像素
                    .bitmapConfig(Bitmap.Config.ARGB_8888)
                    .build()

            val result =
                runCatching { SingletonImageLoader.get(this@PlayerActivity).execute(request) }
                    .getOrNull() ?: return@launch

            val bitmap = (result.image as? BitmapImage)?.bitmap ?: return@launch
            val color = AmbientColors.extract(bitmap) ?: return@launch
            val target = AmbientColors.gradientFor(color)

            val current = binding.ambientBackdrop.background
            if (current == null) {
                binding.ambientBackdrop.background = target
            } else {
                // 切换剧集时平滑过渡，避免背景突然跳色
                val transition = TransitionDrawable(arrayOf(current, target))
                transition.isCrossFadeEnabled = true
                binding.ambientBackdrop.background = transition
                transition.startTransition(AMBIENT_FADE_DURATION)
            }
        }
    }

    private fun finishPlayback() {
        try {
            viewModel.player.clearVideoSurfaceView(
                binding.playerView.videoSurfaceView as SurfaceView
            )
        } catch (e: Exception) {
            Timber.e(e)
        }
        finish()
    }

    private fun pipParams(
        enableAutoEnter: Boolean = viewModel.player.isPlaying
    ): PictureInPictureParams {
        val displayAspectRatio = Rational(binding.playerView.width, binding.playerView.height)

        val aspectRatio =
            binding.playerView.player?.videoSize?.let {
                Rational(
                    it.width.coerceAtMost((it.height * 2.39f).toInt()),
                    it.height.coerceAtMost((it.width * 2.39f).toInt()),
                )
            }

        val sourceRectHint =
            if (displayAspectRatio < aspectRatio!!) {
                val space =
                    ((binding.playerView.height -
                            (binding.playerView.width.toFloat() / aspectRatio.toFloat())) / 2)
                        .toInt()
                Rect(
                    0,
                    space,
                    binding.playerView.width,
                    (binding.playerView.width.toFloat() / aspectRatio.toFloat()).toInt() + space,
                )
            } else {
                val space =
                    ((binding.playerView.width -
                            (binding.playerView.height.toFloat() * aspectRatio.toFloat())) / 2)
                        .toInt()
                Rect(
                    space,
                    0,
                    (binding.playerView.height.toFloat() * aspectRatio.toFloat()).toInt() + space,
                    binding.playerView.height,
                )
            }

        val builder =
            PictureInPictureParams.Builder()
                .setAspectRatio(aspectRatio)
                .setSourceRectHint(sourceRectHint)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(enableAutoEnter)
        }

        return builder.build()
    }

    private fun pictureInPicture() {
        if (!isPipSupported) {
            return
        }

        try {
            enterPictureInPictureMode(pipParams())
        } catch (_: IllegalArgumentException) {}
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        viewModel.isInPictureInPictureMode = isInPictureInPictureMode
        when (isInPictureInPictureMode) {
            true -> {
                controlsState.visible = false
                playerGestureHelper?.isSuspended = true

                wasZoom = playerGestureHelper?.isZoomEnabled == true
                playerGestureHelper?.updateZoomMode(false)

                // Brightness mode Auto
                window.attributes =
                    window.attributes.apply {
                        screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                    }
            }

            false -> {
                controlsState.show()
                playerGestureHelper?.isSuspended = false
                playerGestureHelper?.updateZoomMode(wasZoom)

                // Override auto brightness
                if (
                    appPreferences.getValue(appPreferences.playerGesturesVB) &&
                        appPreferences.getValue(appPreferences.playerGesturesBrightnessRemember)
                ) {
                    window.attributes =
                        window.attributes.apply {
                            screenBrightness =
                                appPreferences.getValue(appPreferences.playerBrightness)
                        }
                }
            }
        }
    }
}
