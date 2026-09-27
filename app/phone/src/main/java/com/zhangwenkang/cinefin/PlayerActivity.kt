package com.zhangwenkang.cinefin

import android.app.AppOpsManager
import android.app.PictureInPictureParams
import android.Manifest
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
import android.os.Process
import android.util.Rational
import android.view.Gravity
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.viewModels
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.core.content.ContextCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.CaptionStyleCompat
import coil3.BitmapImage
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.bitmapConfig
import com.zhangwenkang.cinefin.databinding.ActivityPlayerBinding
import com.zhangwenkang.cinefin.player.local.presentation.PlayerEvents
import com.zhangwenkang.cinefin.player.local.presentation.PlayerViewModel
import com.zhangwenkang.cinefin.presentation.player.PlayerChromeLayout
import com.zhangwenkang.cinefin.presentation.player.PlayerControlOverlay
import com.zhangwenkang.cinefin.presentation.player.PlayerControlsState
import com.zhangwenkang.cinefin.presentation.player.PlayerFormFactor
import com.zhangwenkang.cinefin.presentation.player.PlayerLayoutContext
import com.zhangwenkang.cinefin.presentation.player.keepsComposition
import com.zhangwenkang.cinefin.presentation.player.rememberPlayerLayoutContext
import com.zhangwenkang.cinefin.presentation.theme.CinefinTheme
import com.zhangwenkang.cinefin.repository.JellyfinRepository
import com.zhangwenkang.cinefin.settings.domain.AppPreferences
import com.zhangwenkang.cinefin.utils.AmbientColors
import com.zhangwenkang.cinefin.utils.PlayerGestureHelper
import dagger.hilt.android.AndroidEntryPoint
import java.util.UUID
import javax.inject.Inject
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber

var isControlsLocked: Boolean = false

/** 氛围背景切换的淡入时长 */
private const val AMBIENT_FADE_DURATION = 600

/** 换解码内核重开播放页时携带的精确续播位置（毫秒） */
private const val EXTRA_START_POSITION_MS = "startPositionMs"

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

    /** Android 13+ 通知权限：通知栏播放控制需要它；没授予只影响通知，不影响播放本身 */
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            Timber.d("通知权限结果：%s", granted)
        }

    /** 画中画状态：控制层据此切到 Pip 骨架（PiP 窗口里不渲染控制层） */
    private val pipMode = mutableStateOf(false)

    /** 平板 / 折叠展开时右侧内容栏是否展开；收起后画面区占满整宽 */
    private val sidePanelExpanded = mutableStateOf(true)

    /** Compose 侧解析出的形态上下文；Activity 用它给画面区排版（同一份数值，避免两边错位） */
    private var layoutContext: PlayerLayoutContext? = null

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

        /*
         * itemId 允许为空：从通知 / 锁屏回到播放页时，播放会话已经在服务里跑着，
         * 页面只需要接管渲染，不能再拉一次流。
         */
        val itemId =
            intent.extras?.getString("itemId")?.let { raw ->
                runCatching { UUID.fromString(raw) }.getOrNull()
            }
        val itemKind = intent.extras?.getString("itemKind")
        val startFromBeginning = intent.extras?.getBoolean("startFromBeginning") ?: false
        // 换内核会重开播放页：把失败前的精确进度带过来，不用服务端 5 秒一次的上报值续播
        val startPositionMs = intent.extras?.getLong(EXTRA_START_POSITION_MS, 0L) ?: 0L
        // 读掉就删：这个 Intent 会被 recreate() 复用，留着会让下次重建又跳回老位置
        intent.removeExtra(EXTRA_START_POSITION_MS)

        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        binding.playerView.player = viewModel.player
        // 画面比例：沿用上次选过的档位（RESIZE_MODE_*，默认 0 = 适应屏幕）
        binding.playerView.resizeMode = appPreferences.getValue(appPreferences.playerResizeMode)
        configureSubtitleStyle()

        // 控制层改用 Compose 渲染（PlayerControlOverlay），Media3 自带控制器整体停用：
        // 皮肤、面板、动效因此只有一套实现，也不会再出现两套控件互相打架
        binding.playerView.useController = false

        isControlsLocked = false

        binding.controlOverlayCompose.setContent {
            // 注意：这里必须关掉主题底色，否则那层不透明 Surface 会把视频画面整个盖住
            CinefinTheme(surfaceBackground = false) {
                val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                // 形态判定放在 Compose 侧：窗口尺寸 / 折叠姿势 / 多窗口状态变化都会触发重组
                val layout = rememberPlayerLayoutContext(isPip = pipMode.value)
                LaunchedEffect(layout, sidePanelExpanded.value) {
                    layoutContext = layout
                    applyVideoArea(layout)
                }
                PlayerControlOverlay(
                    player = viewModel.player,
                    uiState = uiState,
                    controls = controlsState,
                    layout = layout,
                    sidePanelExpanded = sidePanelExpanded.value,
                    onToggleSidePanel = { sidePanelExpanded.value = !sidePanelExpanded.value },
                    isPipSupported = isPipSupported,
                    onBack = { finishPlayback() },
                    onPip = { pictureInPicture() },
                    onSelectSpeed = { speed -> viewModel.selectSpeed(speed) },
                    onSelectTrack = { type, index -> viewModel.switchToTrack(type, index) },
                    onSkipSegment = { segment -> viewModel.skipSegment(segment) },
                    initialResizeMode = appPreferences.getValue(appPreferences.playerResizeMode),
                    onSelectResizeMode = { mode -> selectResizeMode(mode) },
                    aspectSupported = isExoPlayerBackend,
                    onRetry = { viewModel.retryPlayback() },
                    onSwitchBackend = { switchBackendAndRestart() },
                    onRegionsChanged = { visible, panelOpen, locked, errorVisible ->
                        binding.controlOverlay.controlsVisible = visible
                        binding.controlOverlay.panelOpen = panelOpen
                        binding.controlOverlay.locked = locked
                        binding.controlOverlay.errorVisible = errorVisible
                        /*
                         * 控制层隐藏时整层退出合成（INVISIBLE），不要留一个满屏的 Compose 层
                         * 一直盖在视频 SurfaceView 上：部分设备会据此判定「画面被遮挡」而黑屏。
                         * 但平板侧栏、手机竖屏下方内容区是常驻的，这些骨架必须保持可见。
                         */
                        val chromeKeepsComposition =
                            layoutContext?.chrome?.keepsComposition() == true
                        binding.controlOverlay.visibility =
                            if (visible || panelOpen || locked || chromeKeepsComposition) {
                                View.VISIBLE
                            } else {
                                View.INVISIBLE
                            }
                    },
                )
            }
        }

        // 锁定状态回写：手势层读同一个标记，锁屏后只留解锁按钮
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                snapshotFlow { controlsState.locked }
                    .collect { locked ->
                        isControlsLocked = locked
                        requestedOrientation =
                            if (locked) {
                                ActivityInfo.SCREEN_ORIENTATION_LOCKED
                            } else {
                                orientationForFormFactor()
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
                    onSingleTap = {
                        // 错误卡片是模态的：错误消失前不让单击把控制层收走
                        if (viewModel.uiState.value.playerError == null) {
                            controlsState.toggle()
                        }
                    },
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

        if (itemId != null) {
            viewModel.initializePlayer(
                itemId = itemId,
                itemKind = itemKind ?: "",
                startFromBeginning = startFromBeginning,
                startPositionMs = startPositionMs,
            )
        } else if (viewModel.player.mediaItemCount > 0) {
            // 从通知回来：会话还在跑，接管它（补标题/章节等信息），不重新拉流
            viewModel.attachToExistingSession()
        } else {
            // 既没有条目、也没有可接管的会话：不留一个空白播放页
            finishPlayback()
            return
        }
        hideSystemUI()
        requestNotificationPermissionIfNeeded()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)

        val itemId =
            intent.extras?.getString("itemId")?.let { raw ->
                runCatching { UUID.fromString(raw) }.getOrNull()
            }
        if (itemId == null) {
            // 从通知点回正在播放的会话：什么都不用做，页面已经在前台
            controlsState.show()
            return
        }
        val itemKind = intent.extras?.getString("itemKind")
        val startFromBeginning = intent.extras?.getBoolean("startFromBeginning") ?: false

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

    /** 字幕样式：media3 默认沿用系统字幕样式，系统字幕未开启时会退回「白字 + 不透明黑底」， 在画面上呈现为突兀的黑色方块。这里统一改为白字 + 黑色描边，不绘制底色。 */
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
     * 画面比例由 Media3 的 PlayerView 负责；换成 mpv 播放核心时画面输出由 mpv 自己控制， 这时比例档位在面板里显示为不可用，而不是给一个点了没反应的假开关。
     */
    private val isExoPlayerBackend: Boolean
        get() =
            appPreferences.getValue(appPreferences.playerBackend) ==
                PlayerViewModel.PLAYER_BACKEND_EXOPLAYER

    /**
     * 一键切换解码内核（ExoPlayer ⇄ mpv）。
     *
     * 播放器和 MediaSession 都在 ViewModel 构造时绑定，就地换实例要连带重建会话。 这里先清空 ViewModelStore 再
     * recreate()：既保证拿到新内核的 player，又保留返回栈。 不能用 startActivity 重启——PlayerActivity 是 singleTask，新
     * Intent 会被复用给同一个实例， 旧实例随即 finish 就退回上一页（模拟器实测如此）。
     */
    /**
     * 按骨架给画面区排版。
     *
     * 画面区尺寸是「播放器输出」与「控制层命中区」的共同基准：两边读同一份 [PlayerLayoutContext]，
     * 竖屏 16:9 定高、平板让出右侧栏、折叠半开只占折痕以上——改一处不会让另一边错位。
     */
    private fun applyVideoArea(layout: PlayerLayoutContext) {
        binding.root.post {
            if (isFinishing || isDestroyed) return@post
            val density = resources.displayMetrics.density
            val expanded = sidePanelExpanded.value
            val videoWidthDp =
                if (layout.hasSideContent && expanded) {
                    layout.videoWidthDp
                } else {
                    layout.windowWidthDp
                }
            val videoWidthPx = videoWidthDp * density
            val videoHeightPx = layout.videoHeightDp * density

            binding.playerView.updateLayoutParams<FrameLayout.LayoutParams> {
                width =
                    if (layout.chrome == PlayerChromeLayout.SplitSide) {
                        videoWidthPx.roundToInt()
                    } else {
                        ViewGroup.LayoutParams.MATCH_PARENT
                    }
                height =
                    when (layout.chrome) {
                        PlayerChromeLayout.SplitPortrait,
                        PlayerChromeLayout.FoldHalfOpen -> videoHeightPx.roundToInt()
                        else -> ViewGroup.LayoutParams.MATCH_PARENT
                    }
                gravity = Gravity.TOP or Gravity.START
            }

            binding.controlOverlay.chrome = layout.chrome
            binding.controlOverlay.videoWidthPx = videoWidthPx
            binding.controlOverlay.videoHeightPx = videoHeightPx
            binding.controlOverlay.compactBarHeightPx = 72f * density
            /*
             * 骨架切换后要重算整层可见性：平板侧栏 / 竖屏内容区 / 小窗控制条是常驻内容，
             * 控制层淡出时它们不能跟着一起退出合成（否则内容栏消失且点不动）。
             */
            binding.controlOverlay.visibility =
                if (layout.chrome.keepsComposition() || controlsState.visible) {
                    View.VISIBLE
                } else {
                    View.INVISIBLE
                }
            applySystemUiVisibility()
        }
    }

    /** 方向策略：手机 / 平板自由旋转，车机与 TV 锁横屏；锁屏时由锁定按钮单独接管 */
    private fun orientationForFormFactor(): Int =
        when (layoutContext?.formFactor) {
            PlayerFormFactor.Car, PlayerFormFactor.Tv ->
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            else -> ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
        }

    /**
     * 系统栏策略：手机 / 平板 / TV 进沉浸式全屏；车机保持系统栏可见
     * （车机 HMI 不允许应用长期霸占整屏，返回与 Home 必须始终可达）。
     */
    private fun applySystemUiVisibility() {
        if (layoutContext?.formFactor == PlayerFormFactor.Car) {
            WindowCompat.getInsetsController(window, window.decorView)
                .show(WindowInsetsCompat.Type.systemBars())
        } else {
            hideSystemUI()
        }
    }

    /**
     * 首次进入播放页时申请通知权限（Android 13+）。
     *
     * 系统只在用户没做过选择时弹窗，重复调用不会打扰；拒绝后通知栏控制不可见，
     * 但前台服务与播放本身照常工作。
     */
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted =
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        if (!granted) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun switchBackendAndRestart() {
        val target = viewModel.switchBackend()
        val position = viewModel.player.currentPosition.coerceAtLeast(0L)
        Timber.d("Restart player with backend=$target from position=$position")

        // recreate() 会复用同一个 Intent，把续播位置写回去即可
        intent.putExtra(EXTRA_START_POSITION_MS, position)
        // 不清空的话 recreate() 会把同一个 ViewModel（连着旧 player）交回来
        viewModelStore.clear()
        recreate()
    }

    /** 画面比例：即时生效 + 记住选择 */
    private fun selectResizeMode(mode: Int) {
        binding.playerView.resizeMode = mode
        appPreferences.setValue(appPreferences.playerResizeMode, mode)
        Timber.d("player resize mode=$mode")
    }

    /**
     * 用当前影片的海报/剧照生成播放页氛围背景。
     *
     * 只在画面未铺满屏幕的区域（上下黑边）与缓冲加载态可见； 控件配色保持应用统一的冰蓝，避免整屏变色干扰观看。
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
            // 视频输出已改为 TextureView（避免控制层覆盖时 SurfaceView 被遮挡变黑），
            // 这里同时兼容两种输出类型
            when (val output = binding.playerView.videoSurfaceView) {
                is SurfaceView -> viewModel.player.clearVideoSurfaceView(output)
                is TextureView -> viewModel.player.clearVideoTextureView(output)
                else -> Unit
            }
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
        pipMode.value = isInPictureInPictureMode
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
