package com.zhangwenkang.cinefin

import android.Manifest
import android.app.AppOpsManager
import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.TransitionDrawable
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.OpenableColumns
import android.util.Rational
import android.view.Gravity
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import coil3.BitmapImage
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.bitmapConfig
import com.zhangwenkang.cinefin.core.presentation.theme.ContentDomain
import com.zhangwenkang.cinefin.core.presentation.theme.ProvideLumenColors
import com.zhangwenkang.cinefin.databinding.ActivityPlayerBinding
import com.zhangwenkang.cinefin.player.core.domain.models.SubtitleStyle
import com.zhangwenkang.cinefin.player.local.R as PlayerR
import com.zhangwenkang.cinefin.player.local.domain.PlayerVideoTransform
import com.zhangwenkang.cinefin.player.local.domain.VideoMirrorMode
import com.zhangwenkang.cinefin.player.local.domain.cropScale
import com.zhangwenkang.cinefin.player.local.domain.letterboxFillScale
import com.zhangwenkang.cinefin.player.local.domain.parsePlaybackQueueEntries
import com.zhangwenkang.cinefin.player.local.domain.rotationFillScale
import com.zhangwenkang.cinefin.player.local.mpv.MPVPlayer
import com.zhangwenkang.cinefin.player.local.presentation.PlayerEvents
import com.zhangwenkang.cinefin.player.local.presentation.PlayerViewModel
import com.zhangwenkang.cinefin.presentation.player.PlayerChromeLayout
import com.zhangwenkang.cinefin.presentation.player.PlayerControlOverlay
import com.zhangwenkang.cinefin.presentation.player.PlayerControlsState
import com.zhangwenkang.cinefin.presentation.player.PlayerFormFactor
import com.zhangwenkang.cinefin.presentation.player.PlayerLayoutContext
import com.zhangwenkang.cinefin.presentation.player.PlayerSettingsController
import com.zhangwenkang.cinefin.presentation.player.PlayerSubtitleOverlay
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
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

var isControlsLocked: Boolean = false

/** 氛围背景切换的淡入时长 */
private const val AMBIENT_FADE_DURATION = 600

/** 换解码内核重开播放页时携带的精确续播位置（毫秒） */
private const val EXTRA_START_POSITION_MS = "startPositionMs"

/**
 * W58b 视频多选批量播放：显式播放队列的条目 id / 类型（两个并行 `ArrayList<String>`）。
 *
 * 与 [EXTRA_START_POSITION_MS] 挂在同一份 Intent 上——回退重启复用 Intent，队列随之保留； 读取端为
 * `parsePlaybackQueueEntries`（id 与类型按位配对，非法项直接丢弃）。
 */
internal const val EXTRA_QUEUE_ITEM_IDS = "queueItemIds"

internal const val EXTRA_QUEUE_ITEM_KINDS = "queueItemKinds"

/**
 * 本次播放会话的 id（W19）。
 *
 * 回退 / 手动切内核重启播放页时原样带回 → 解码回退档位与重启守卫在会话内保持；新开播放页 / 通知另起 播放时不会带它 → Activity 生成新 id，档位清零（回退链从头走）。
 */
private const val EXTRA_PLAYBACK_SESSION = "playbackSessionId"

/** 重启播放页时要恢复到的条目（W19）：当前实际播放的条目，而不是 Intent 里的原始条目 */
private data class PlayerRestartTarget(val itemId: String, val itemKind: String)

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

    /**
     * W27 外挂字幕导入：系统文件选择器（SAF）。
     *
     * 选完后把内容复制进 App 私有目录（按当前播放条目保存，不写服务器），再交给 ViewModel 侧载； 解析与渲染完全复用既有字幕管线（Exo 走 libass，mpv 走
     * sub-add + 内置 libass）。
     */
    private val subtitleImportLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { importSubtitleFromUri(it) }
        }

    /** 画中画状态：控制层据此切到 Pip 骨架（PiP 窗口里不渲染控制层） */
    private val pipMode = mutableStateOf(false)

    /**
     * 平板 / 折叠展开时右侧内容栏是否展开。
     *
     * 默认收起（用户要求：侧栏不要一进来就占位，要手动从底栏点开）， 点画面会顺手收起它，把宽度还给视频。
     */
    private val sidePanelExpanded = mutableStateOf(false)

    /**
     * W67b：竖屏「画面下方的常驻内容区」（选集 / 队列）是否展开。
     *
     * 默认展开（保持既有竖屏骨架）；内容区右上角 × 收起后画面区铺满整窗，顶栏「选集」键可再次展开。
     */
    private val bottomContentExpanded = mutableStateOf(true)

    /** 全屏（W11 反馈⑥）：收紧常驻内容栏 + 强制横屏；同一个键按状态换图标 */
    private val fullscreenMode = mutableStateOf(false)

    /** 进全屏前的侧栏展开状态：退出全屏时原样还原 */
    private var sidePanelBeforeFullscreen = false

    /** Compose 侧解析出的形态上下文；Activity 用它给画面区排版（同一份数值，避免两边错位） */
    private var layoutContext: PlayerLayoutContext? = null

    /** 播放页设置面板（§1.9）的读写器：六组设置都在页内直接改 */
    private lateinit var settingsController: PlayerSettingsController

    /** 画面调整（§1.6）当前状态：旋转 / 镜像 / 裁剪 / 去黑边 */
    private val videoTransform = mutableStateOf(PlayerVideoTransform())

    /** 已经按哪个条目重算过画面变换（换集后视频分辨率可能不同） */
    private var appliedTransformItemId: UUID? = null

    /** 本次播放会话 id（W19）：回退重启复用，重开播放页换新（见 [EXTRA_PLAYBACK_SESSION]） */
    private var playbackSessionId: String = ""

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
        // W58b：视频多选批量播放的显式队列；没有这两个 extra 时是空列表，单条播放行为不变。
        val queueEntries =
            parsePlaybackQueueEntries(
                ids = intent.extras?.getStringArrayList(EXTRA_QUEUE_ITEM_IDS),
                kinds = intent.extras?.getStringArrayList(EXTRA_QUEUE_ITEM_KINDS),
            )
        // 换内核会重开播放页：把失败前的精确进度带过来，不用服务端 5 秒一次的上报值续播
        val startPositionMs = intent.extras?.getLong(EXTRA_START_POSITION_MS, 0L) ?: 0L
        // 读掉就删：这个 Intent 会被 recreate() 复用，留着会让下次重建又跳回老位置
        intent.removeExtra(EXTRA_START_POSITION_MS)
        // W19：回退重启复用同一个会话 id；新开播放页（没有这个 extra）换新会话
        playbackSessionId =
            intent.extras?.getString(EXTRA_PLAYBACK_SESSION)?.takeIf { it.isNotBlank() }
                ?: UUID.randomUUID().toString()

        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        binding.playerView.player = viewModel.player
        // 画面比例：沿用上次选过的档位（RESIZE_MODE_*，默认 0 = 适应屏幕）；两个内核都落到各自的输出通路
        applyResizeModeToKernel(appPreferences.getValue(appPreferences.playerResizeMode))
        // 设置面板（§1.9）与画面调整（§1.6）：偏好是唯一来源，进页面就按偏好还原
        // W19：解码面板的「播放内核」选中态以**实际生效内核**为准（PlayerHolder 实例优先，而不是偏好快照）
        settingsController = PlayerSettingsController(appPreferences) { viewModel.playerBackend }
        videoTransform.value = settingsController.readVideoTransform()
        configureSubtitleStyle()

        // 控制层改用 Compose 渲染（PlayerControlOverlay），Media3 自带控制器整体停用：
        // 皮肤、面板、动效因此只有一套实现，也不会再出现两套控件互相打架
        binding.playerView.useController = false

        isControlsLocked = false

        /*
         * 自研字幕层（§1.1）：独立 ComposeView，避免控制层隐藏时字幕跟着消失；
         * 只画字幕不处理触摸，手势仍由 PlayerView 上的 PlayerGestureHelper 接管。
         */
        binding.subtitleOverlayCompose.setContent {
            // 播放视频 = 影视域：字幕层与控制层共用琥珀媒体色，颜色只服务进度 / 激活 / 主行动
            CinefinTheme(domain = ContentDomain.Movie, surfaceBackground = false) {
                val subtitleState by
                    viewModel.subtitleController.overlayState.collectAsStateWithLifecycle()
                PlayerSubtitleOverlay(
                    player = viewModel.player,
                    state = subtitleState,
                    // W16：libass 字幕层按「视频实际显示区」定位（与 mpv 一致），而不是整窗
                    videoRectProvider = ::currentVideoRect,
                )
            }
        }

        binding.controlOverlayCompose.setContent {
            // 注意：这里必须关掉主题底色，否则那层不透明 Surface 会把视频画面整个盖住
            CinefinTheme(domain = ContentDomain.Movie, surfaceBackground = false) {
                // 反馈⑦：播放页整层切到 A · Lumen（极光青强调 / 曜石黑面板 / 月白主行动）；
                // 组件仍只引用语义 token，离开本页自动回到 Prism
                ProvideLumenColors {
                    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                    val subtitlePanelState by
                        viewModel.subtitlePanelState.collectAsStateWithLifecycle()
                    val audioPanelState by viewModel.audioPanelState.collectAsStateWithLifecycle()
                    val sleepTimerState by viewModel.sleepTimerState.collectAsStateWithLifecycle()
                    // 形态判定放在 Compose 侧：窗口尺寸 / 折叠姿势 / 多窗口状态变化都会触发重组
                    // W67b：竖屏内容区展开 / 收起由页面状态参与布局（画面区高度、内容区占位一并生效）
                    val layout =
                        rememberPlayerLayoutContext(isPip = pipMode.value)
                            .copy(bottomContentExpanded = bottomContentExpanded.value)
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
                        onCollapseBottomContent = { bottomContentExpanded.value = false },
                        onExpandBottomContent = { bottomContentExpanded.value = true },
                        isPipSupported = isPipSupported,
                        isFullscreen = fullscreenMode.value,
                        onToggleFullscreen = { toggleFullscreen() },
                        // W73（#7）：seek 统一走 ViewModel——未就绪排队 + 转码超窗重开会话
                        onSeekRequest = { target -> viewModel.requestSeek(target, "progress") },
                        onSeekFractionRequest = { fraction ->
                            viewModel.requestSeek(0L, "progress-unknown", fraction)
                        },
                        onSeekRelativeRequest = { delta ->
                            viewModel.requestSeekRelative(delta, "step")
                        },
                        // W55 睡眠定时统一：状态与选择都走进程级单例（音乐 / 视频共享）
                        sleepState = sleepTimerState,
                        onSelectSleepMinutes = { minutes -> viewModel.selectSleepTimer(minutes) },
                        onBack = { finishPlayback() },
                        onPip = { pictureInPicture() },
                        onSelectSpeed = { speed -> viewModel.selectSpeed(speed) },
                        subtitlePanelState = subtitlePanelState,
                        onSelectPrimarySubtitle = { id -> viewModel.selectSubtitlePrimary(id) },
                        onSelectSecondarySubtitle = { id -> viewModel.selectSubtitleSecondary(id) },
                        onAdjustSubtitleDelay = { delta -> viewModel.adjustSubtitleDelay(delta) },
                        onResetSubtitleDelay = { viewModel.resetSubtitleDelay() },
                        onUpdateSubtitleStyle = { style -> viewModel.updateSubtitleStyle(style) },
                        audioPanelState = audioPanelState,
                        onSelectAudioTrack = { index -> viewModel.selectAudioTrack(index) },
                        onAdjustAudioDelay = { delta -> viewModel.adjustAudioDelay(delta) },
                        onResetAudioDelay = { viewModel.resetAudioDelay() },
                        onSkipSegment = { segment -> viewModel.skipSegment(segment) },
                        initialResizeMode =
                            appPreferences.getValue(appPreferences.playerResizeMode),
                        onSelectResizeMode = { mode -> selectResizeMode(mode) },
                        settingsController = settingsController,
                        videoTransform = videoTransform.value,
                        onSelectBackend = { backend -> switchBackendAndRestart(backend) },
                        onSelectMpvHwdec = { hwDec -> applyMpvHwDec(hwDec) },
                        onSelectDecodeMode = { mode -> selectDecodeMode(mode) },
                        onAutoFallbackChange = { enabled -> selectAutoFallback(enabled) },
                        onSelectBitrate = { bitrate -> selectStreamingBitrate(bitrate) },
                        onSubtitleModeChanged = { mode -> viewModel.setSubtitleMode(mode) },
                        // W27：侧载字幕导入 / 移除 + PlayerDebugOverlay 实时数据源
                        onImportSubtitle = { subtitleImportLauncher.launch(arrayOf("*/*")) },
                        onRemoveSideloadedSubtitle = { id ->
                            viewModel.removeSideloadedSubtitle(id)
                        },
                        debugStatsProvider = { viewModel.readDebugStats() },
                        onVideoTransformChanged = { transform -> updateVideoTransform(transform) },
                        onQueueMove = { from, to -> viewModel.moveQueueItem(from, to) },
                        onQueueRemove = { index -> viewModel.removeQueueItem(index) },
                        onQueueClear = { viewModel.clearQueue() },
                        // W20（§1.11）：Trickplay 预览按需取（未命中触发后台拉取，不阻塞拖动）
                        trickplayFrameAt = { position -> viewModel.trickplayFrameAt(position) },
                        showChapterMarkers = settingsController.state.chapterMarkers,
                        onRetry = { viewModel.retryPlayback() },
                        onSwitchBackend = { target -> switchBackendAndRestart(target) },
                        onRegionsChanged = {
                            visible,
                            panelOpen,
                            locked,
                            errorVisible,
                            buffering,
                            skipChipVisible,
                            debugOverlayVisible ->
                            binding.controlOverlay.controlsVisible = visible
                            binding.controlOverlay.panelOpen = panelOpen
                            binding.controlOverlay.locked = locked
                            binding.controlOverlay.errorVisible = errorVisible
                            binding.controlOverlay.debugOverlayVisible = debugOverlayVisible
                            /*
                             * 控制层隐藏时整层退出合成（INVISIBLE），不要留一个满屏的 Compose 层
                             * 一直盖在视频 SurfaceView 上：部分设备会据此判定「画面被遮挡」而黑屏。
                             * 但平板侧栏、手机竖屏下方内容区是常驻的，这些骨架必须保持可见。
                             * 缓冲期间也要保持合成：那时播放键里的转圈是唯一加载图标（反馈⑤）。
                             */
                            val chromeKeepsComposition =
                                layoutContext?.chrome?.keepsComposition() == true
                            binding.controlOverlay.visibility =
                                if (
                                    visible ||
                                        panelOpen ||
                                        locked ||
                                        buffering ||
                                        skipChipVisible ||
                                        debugOverlayVisible ||
                                        chromeKeepsComposition
                                ) {
                                    View.VISIBLE
                                } else {
                                    View.INVISIBLE
                                }
                        },
                        onTopBarHeight = { px ->
                            binding.controlOverlay.topBarHeightPx = px.toFloat()
                        },
                        onBottomBarHeight = { px ->
                            binding.controlOverlay.bottomBarHeightPx = px.toFloat()
                            // 小窗只有这一条控制条，命中带读同一个实测高度（§9 踩坑：不写死 dp）
                            binding.controlOverlay.compactBarHeightPx = px.toFloat()
                        },
                        onCenterClusterSize = { width, height ->
                            binding.controlOverlay.centerClusterWidthPx = width.toFloat()
                            binding.controlOverlay.centerClusterHeightPx = height.toFloat()
                        },
                    )
                }
            }
        }

        // 锁定状态回写：手势层读同一个标记，锁屏后只留解锁按钮
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                snapshotFlow { controlsState.locked }
                    .collect { locked ->
                        isControlsLocked = locked
                        requestedOrientation =
                            if (locked) ActivityInfo.SCREEN_ORIENTATION_LOCKED
                            else desiredOrientation()
                    }
            }
        }

        /*
         * 手势层常驻挂载：总开关由 PlayerGestureHelper 每次触摸现读偏好（§1.9 设置面板里能即时开关），
         * 关掉后所有自定义手势直接放行，不存在「要重开播放页才生效」的中间态。
         */
        playerGestureHelper =
            PlayerGestureHelper(
                appPreferences,
                this,
                binding.playerView,
                getSystemService(AUDIO_SERVICE) as AudioManager,
                onSingleTap = {
                    // 错误卡片是模态的：错误消失前不让单击把控制层收走
                    if (viewModel.uiState.value.playerError == null) {
                        /*
                         * 单击画面：选集栏开着就先把栏收回去（把宽度还给视频），
                         * 否则按惯例切换控制层显隐。
                         */
                        if (sidePanelExpanded.value) {
                            sidePanelExpanded.value = false
                        } else {
                            controlsState.toggle()
                        }
                    }
                },
            )

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.uiState.collect { uiState ->
                        Timber.d("$uiState")
                        uiState.apply {
                            // 氛围背景：跟随当前影片海报取色
                            currentItemId?.let { itemId ->
                                updateAmbientBackdrop(itemId)
                                // 换集后视频分辨率可能不同：裁剪比例与填满倍数要重算（§1.6）
                                if (itemId != appliedTransformItemId) {
                                    appliedTransformItemId = itemId
                                    applyVideoTransform()
                                }
                            }

                            /*
                             * 标题 / 章节 / 片段 / Trickplay 直接由 Compose 控制层消费 uiState；
                             * 这里把 Trickplay 的「按需取图」入口同步给手势层的进度 HUD（W20）：
                             * HUD 每次移动只查缓存 + 触发后台拉取，未命中先不显示图。
                             */
                            playerGestureHelper?.let { helper ->
                                helper.trickplayFrameAt = { position ->
                                    viewModel.trickplayFrameAt(position)
                                }
                            }
                        }
                    }
                }

                launch {
                    viewModel.eventsChannelFlow.collect { event ->
                        when (event) {
                            is PlayerEvents.NavigateBack -> finishPlayback()
                            // 解码能力不足：静默换 mpv 内核重播（不弹提示，进度由 switchBackendForFallback 带过去）
                            is PlayerEvents.FallbackToMpv ->
                                switchBackendForFallback(PlayerViewModel.PLAYER_BACKEND_MPV)
                            // W16 回退链第 2 档：服务器解码/转码（PlaybackInfo 已按档位强制转码，重启拉新流）
                            is PlayerEvents.RestartWithServerTranscode ->
                                restartPlaybackKeepingPosition("fallback=server-transcode")
                            /*
                             * W16 回退链第 3 档：本地软解（mpv hwdec=no，档位由 PlayerHolder 读偏好强制）。
                             * W17：目标内核必须**显式指定 mpv**，不能再用 toggle——回退档位写入后 PlayerHolder
                             * 可能已把实例重建为 mpv，toggle 会误判成「切回 ExoPlayer」，导致回退链断在第 2 档。
                             */
                            is PlayerEvents.FallbackToSoftware ->
                                switchBackendForFallback(PlayerViewModel.PLAYER_BACKEND_MPV)
                            // 字幕外观变化：同步给 PlayerView 的原生字幕（图形字幕 / 兜底路径）
                            is PlayerEvents.SubtitleStyleChanged -> configureSubtitleStyle()
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

                /*
                 * 片头 / 片尾轮询常驻：开关由 ViewModel 每秒现读偏好（§1.9 设置面板页内改完即生效），
                 * 两个开关都关时 updateCurrentSegment 会直接返回，不做任何额外工作。
                 *
                 * W20：这里的 5 秒进度上报循环已移除——进度改成 ViewModel 常驻协程
                 * （切后台 / 锁屏也不停），并补上暂停 / 切集 / 退出的即时落盘，见 §23。
                 */
                launch {
                    while (true) {
                        viewModel.updateCurrentSegment()
                        delay(1000L)
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
                playbackSessionId = playbackSessionId,
                queueEntries = queueEntries,
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
        val queueEntries =
            parsePlaybackQueueEntries(
                ids = intent.extras?.getStringArrayList(EXTRA_QUEUE_ITEM_IDS),
                kinds = intent.extras?.getStringArrayList(EXTRA_QUEUE_ITEM_KINDS),
            )

        // W19：onNewIntent = 新的播放请求 = 新会话（清回退档位，链路从头走）
        playbackSessionId = UUID.randomUUID().toString()
        viewModel.initializePlayer(
            itemId = itemId,
            itemKind = itemKind ?: "",
            startFromBeginning = startFromBeginning,
            playbackSessionId = playbackSessionId,
            queueEntries = queueEntries,
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
     * 原生字幕样式（走 Media3 渲染的那部分：图形字幕 / 没有独立文件的兜底字幕）。
     *
     * 文本字幕归自研渲染层（PlayerSubtitleOverlay），外观直接读 SubtitleStyle； 这里把同一套「大小 / 颜色 / 背景 / 描边 / 位置」翻译成
     * SubtitleView 的 API， 保证两类字幕在面板里调出来的观感一致。
     */
    @androidx.annotation.OptIn(UnstableApi::class)
    private fun configureSubtitleStyle() {
        val style = readSubtitleStyle()
        val subtitleView = binding.playerView.subtitleView ?: return
        subtitleView.setStyle(
            CaptionStyleCompat(
                /* foregroundColor = */ style.textColor,
                /* backgroundColor = */ style.backgroundColor,
                /* windowColor = */ Color.TRANSPARENT,
                /* edgeType = */ if (style.edgeWidthDp <= 0f) {
                    CaptionStyleCompat.EDGE_TYPE_NONE
                } else {
                    CaptionStyleCompat.EDGE_TYPE_OUTLINE
                },
                /* edgeColor = */ Color.BLACK,
                /* typeface = */ null,
            )
        )
        // 字号与位置：与自研渲染层用同一份档位（基准 4.2% 画面高度）
        subtitleView.setFractionalTextSize(0.042f * style.textScale, false)
        subtitleView.setBottomPaddingFraction(style.bottomFraction)
        // 面板里调了颜色/字号就应生效，不能让字幕自带的嵌入样式盖回去
        subtitleView.setApplyEmbeddedStyles(false)
        subtitleView.setApplyEmbeddedFontSizes(false)
    }

    /** 读面板里保存的字幕外观档位（与 PlayerViewModel.readSubtitleStyle 一致） */
    private fun readSubtitleStyle(): SubtitleStyle =
        SubtitleStyle(
            sizeIndex = appPreferences.getValue(appPreferences.playerSubtitleStyleSize),
            colorIndex = appPreferences.getValue(appPreferences.playerSubtitleStyleColor),
            backgroundIndex = appPreferences.getValue(appPreferences.playerSubtitleStyleBackground),
            edgeIndex = appPreferences.getValue(appPreferences.playerSubtitleStyleEdge),
            positionIndex = appPreferences.getValue(appPreferences.playerSubtitleStylePosition),
        )

    /**
     * W27：读取系统文件选择器返回的侧载字幕（文件名 + 字节），交给 ViewModel 复制到 App 私有目录。
     *
     * 读文件放 IO 线程；完成后弹一条 Toast（成功带文件名，失败带原因）。
     */
    private fun importSubtitleFromUri(uri: Uri) {
        lifecycleScope.launch {
            val (displayName, bytes) =
                withContext(Dispatchers.IO) {
                    val name =
                        runCatching {
                            contentResolver
                                .query(
                                    uri,
                                    arrayOf(OpenableColumns.DISPLAY_NAME),
                                    null,
                                    null,
                                    null,
                                )
                                ?.use { cursor ->
                                    if (cursor.moveToFirst()) cursor.getString(0) else null
                                }
                        }
                            .getOrNull()
                            ?.takeIf { it.isNotBlank() }
                            ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf {
                                it.isNotBlank()
                            }
                            ?: "subtitle.srt"
                    val data = runCatching {
                        contentResolver.openInputStream(uri)?.use { stream -> stream.readBytes() }
                    }
                        .getOrNull()
                    name to data
                }
            if (bytes == null || bytes.isEmpty()) {
                Toast.makeText(
                        this@PlayerActivity,
                        getString(PlayerR.string.player_subtitle_import_failed),
                        Toast.LENGTH_SHORT,
                    )
                    .show()
                return@launch
            }
            viewModel.importSideloadedSubtitle(displayName, bytes) { success, message ->
                Toast.makeText(
                        this@PlayerActivity,
                        if (success) {
                            getString(PlayerR.string.player_subtitle_imported, message)
                        } else {
                            message
                        },
                        Toast.LENGTH_SHORT,
                    )
                    .show()
            }
        }
    }

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
     * 画面区尺寸是「播放器输出」与「控制层命中区」的共同基准：两边读同一份 [PlayerLayoutContext]， 竖屏 16:9
     * 定高、平板让出右侧栏、折叠半开只占折痕以上——改一处不会让另一边错位。
     */
    private fun applyVideoArea(layout: PlayerLayoutContext) {
        binding.root.post {
            if (isFinishing || isDestroyed) return@post
            val density = resources.displayMetrics.density
            val expanded = sidePanelExpanded.value
            /*
             * W12 反馈 C：选集栏改成**覆盖层**——画面区永远是整窗宽度，打开 / 收起选集都不再挤压或右移画面。
             * 覆盖层自身的触摸命中由 controlOverlay.sidePanelOpen / sidePanelWidthPx 单独接管（见 PlayerOverlayContainer）。
             */
            val videoWidthDp = layout.windowWidthDp
            val videoWidthPx = videoWidthDp * density
            val videoHeightPx = layout.videoHeightDp * density

            binding.playerView.updateLayoutParams<FrameLayout.LayoutParams> {
                width = ViewGroup.LayoutParams.MATCH_PARENT
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
            // 覆盖层形态的选集栏：打开时右缘这一条由控制层接管触摸
            binding.controlOverlay.sidePanelOpen = layout.hasSideContent && expanded
            binding.controlOverlay.sidePanelWidthPx =
                if (layout.hasSideContent) layout.sidePanelWidthDp * density else 0f
            /*
             * 小窗控制条：标题行 + 工具行 + 6dp 进度条 ≈ 132dp，这里只作首帧兜底——
             * 真正的高度由 PlayerCompactBar 的 onSizeChanged 实测回传（与底栏共用同一条通路）。
             */
            binding.controlOverlay.compactBarHeightPx = 132f * density
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
            // 画面区尺寸变了：裁剪 / 旋转的缩放系数要跟着重算
            applyVideoTransform()
        }
    }

    /** 方向策略：手机 / 平板自由旋转，车机与 TV 锁横屏；锁屏时由锁定按钮单独接管 */
    private fun orientationForFormFactor(): Int =
        when (layoutContext?.formFactor) {
            PlayerFormFactor.Car,
            PlayerFormFactor.Tv -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            else -> ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
        }

    /** 当前该给系统的方向：全屏优先横屏，否则按形态（车机 / TV 锁横屏，手机 / 平板自由旋转）。 */
    private fun desiredOrientation(): Int =
        if (fullscreenMode.value) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            orientationForFormFactor()
        }

    /**
     * 全屏 / 退出全屏（W11 反馈⑥）。
     *
     * 播放页本身就是沉浸式，所以「全屏」= 画面独占：收起右侧常驻内容栏（平板 / 折叠）并强制横屏 （手机竖屏的选集区让位给画面）；退出时把侧栏状态与方向策略原样还原。
     */
    private fun toggleFullscreen() {
        val target = !fullscreenMode.value
        fullscreenMode.value = target
        if (target) {
            sidePanelBeforeFullscreen = sidePanelExpanded.value
            sidePanelExpanded.value = false
        } else {
            sidePanelExpanded.value = sidePanelBeforeFullscreen
        }
        requestedOrientation = desiredOrientation()
        Timber.d("player fullscreen=%s", target)
    }

    /** 系统栏策略：手机 / 平板 / TV 进沉浸式全屏；车机保持系统栏可见 （车机 HMI 不允许应用长期霸占整屏，返回与 Home 必须始终可达）。 */
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
     * 系统只在用户没做过选择时弹窗，重复调用不会打扰；拒绝后通知栏控制不可见， 但前台服务与播放本身照常工作。
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

    /**
     * 手动切内核（解码面板 / 错误卡片「改用 X 内核」）。
     *
     * W18（用户实测反馈）：**手动选内核不等于关掉回退链**——这里把回退档位清回第 1 档（用户显式选择优先， 链路从头走），失败后仍按「本地硬解 → 服务器解码/转码 →
     * 本地软解」逐级下降。
     *
     * 注意顺序：先读当前位置，再写偏好——写完后 [PlayerViewModel.player] 会按新偏好重建实例， 后读位置会拿到 0。
     */
    private fun switchBackendAndRestart(target: String) {
        val position = viewModel.player.currentPosition.coerceAtLeast(0L)
        // W19：重启目标必须在写偏好之前取——偏好一变，PlayerHolder 会立刻按新偏好重建空实例
        val restartTarget = currentRestartTarget()
        viewModel.setBackend(target)
        viewModel.clearDecodeFallback()
        // 面板快照跟随（真正生效的内核在 Activity 重建后由 PlayerSettingsController 现读）
        settingsController.refresh()
        Timber.d("Restart player with backend=$target from position=$position (manual)")
        restartPlaybackFromPosition(position, restartTarget)
    }

    /**
     * 回退链换内核（第 3 档：mpv + hwdec=no）。
     *
     * 与手动切换的区别：**保留**回退档位（PlayerHolder 靠它强制软解），且目标内核必须显式指定，不能用 toggle——档位写入后 PlayerHolder 可能已把实例重建为
     * mpv，toggle 会误判成「切回 ExoPlayer」（W17 根因）。
     */
    private fun switchBackendForFallback(target: String) {
        val position = viewModel.player.currentPosition.coerceAtLeast(0L)
        val restartTarget = currentRestartTarget()
        viewModel.setBackend(target)
        Timber.d("Restart player with backend=$target from position=$position (fallback)")
        restartPlaybackFromPosition(position, restartTarget)
    }

    /**
     * W19：当前实际播放的条目（id + 类型）；取不到时返回 null（调用方保持 Intent 原条目）。
     *
     * 必须**在写 `pref_player_backend` 之前**调用：偏好一变，[PlayerViewModel.player] 会立即按新偏好重建空实例。
     */
    private fun currentRestartTarget(): PlayerRestartTarget? {
        val kind = viewModel.currentPlaybackItemKind() ?: return null
        val mediaId = viewModel.player.currentMediaItem?.mediaId?.takeIf { it.isNotBlank() }
        return mediaId?.let { PlayerRestartTarget(it, kind) }
    }

    /**
     * 把比例档位落到当前内核并留下验收日志。
     *
     * ExoPlayer 的画面输出由 Media3 `PlayerView` 负责；mpv 自己渲染画面，比例必须翻译成 `keepaspect` / `panscan`（§1.6 的
     * `video-zoom` 只负责裁剪 / 去黑边的放大）。
     */
    private fun applyResizeModeToKernel(mode: Int) {
        binding.playerView.resizeMode = mode
        val mpv = viewModel.player as? MPVPlayer
        mpv?.applyResizeMode(mode)
        Timber.d("player resize mode=%d kernel=%s", mode, if (mpv != null) "mpv" else "exoplayer")
    }

    /** 画面比例：即时生效 + 记住选择（两个内核都生效） */
    private fun selectResizeMode(mode: Int) {
        appPreferences.setValue(appPreferences.playerResizeMode, mode)
        applyResizeModeToKernel(mode)
        // 去黑边开着时，有效比例由 applyVideoTransform 决定，这里要重算一次
        applyVideoTransform()
    }

    /**
     * 画面调整（§1.6）：旋转 / 镜像 / 裁剪 / 去黑边。
     *
     * 偏好是唯一来源（[PlayerExtraPreferences]），写完之后立即应用到画面输出： ExoPlayer 走 `PlayerView` 的视图变换 + 容器宽高比，mpv
     * 走原生 `video-rotate` / `video-scale-*` / `video-zoom`。
     */
    private fun updateVideoTransform(transform: PlayerVideoTransform) {
        videoTransform.value = transform
        settingsController.setVideoTransform(transform)
        applyVideoTransform()
        Timber.d(
            "video transform: rotation=%d mirror=%d crop=%d letterbox=%s",
            transform.rotationDegrees,
            transform.mirror,
            transform.cropPercent,
            transform.letterboxCrop,
        )
    }

    private fun applyVideoTransform() {
        val transform = videoTransform.value
        val mpv = viewModel.player as? MPVPlayer
        val videoWidth = viewModel.uiState.value.currentMediaInfo?.width ?: 0
        val videoHeight = viewModel.uiState.value.currentMediaInfo?.height ?: 0
        if (mpv != null) {
            // 比例档位重放一次：切内核 / 退出重进 / 双指缩放之后都不留旧值
            mpv.applyResizeMode(appPreferences.getValue(appPreferences.playerResizeMode))
            val viewWidth = binding.playerView.width.toFloat()
            val viewHeight = binding.playerView.height.toFloat()
            val fillScale =
                if (transform.letterboxCrop) {
                    letterboxFillScale(viewWidth, viewHeight, videoWidth, videoHeight)
                } else {
                    1f
                }
            mpv.applyVideoTransform(
                rotationDegrees = transform.rotationDegrees,
                mirror = transform.mirror,
                cropPercent = transform.cropPercent,
                fillScale = fillScale,
            )
            return
        }
        applyExoPlayerVideoTransform(transform, videoWidth, videoHeight)
    }

    @androidx.annotation.OptIn(UnstableApi::class)
    private fun applyExoPlayerVideoTransform(
        transform: PlayerVideoTransform,
        videoWidth: Int,
        videoHeight: Int,
    ) {
        val view = binding.playerView
        val baseMode = appPreferences.getValue(appPreferences.playerResizeMode)
        // 去黑边：把「适应屏幕」提升为「裁剪填满」（填满 = 没有黑边）；其它档位保持用户选择
        val mode =
            if (transform.letterboxCrop && baseMode == AspectRatioFrameLayout.RESIZE_MODE_FIT) {
                AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            } else {
                baseMode
            }
        /*
         * Media3 的三档语义（真机实测结论）：
         * - FIT：内容框按视频比例，四周留黑边；
         * - FILL：内容框撑满画面区，画面被拉伸（允许变形）；
         * - ZOOM：内容框本来就等于视频比例时**没有任何视觉效果**（Pad 5 实测三档画面逐像素相同），
         *   所以「裁剪填满」改成「内容框按视频比例（走 FIT）+ 等比视图缩放填满画面区」——
         *   放大倍数取画面区与视频比例差（与 mpv 的 panscan 语义一致，溢出部分被父容器裁掉）。
         */
        val videoRatio =
            if (videoWidth > 0 && videoHeight > 0) videoWidth.toFloat() / videoHeight else 0f
        val zoomFill =
            if (mode == AspectRatioFrameLayout.RESIZE_MODE_ZOOM) {
                letterboxFillScale(
                    view.width.toFloat(),
                    view.height.toFloat(),
                    videoWidth,
                    videoHeight,
                )
            } else {
                1f
            }
        view.resizeMode =
            if (mode == AspectRatioFrameLayout.RESIZE_MODE_ZOOM) {
                AspectRatioFrameLayout.RESIZE_MODE_FIT
            } else {
                mode
            }
        view.rotation = transform.rotationDegrees.toFloat()
        // 裁剪：四边各裁 N% ⇒ 等比放大 1/(1-2N%)（与 mpv 的 video-zoom 同一套算法）
        val scale =
            rotationFillScale(
                transform.rotationDegrees,
                view.width.toFloat(),
                view.height.toFloat(),
            ) * cropScale(transform.cropPercent) * zoomFill
        view.scaleX = (if (transform.mirror == VideoMirrorMode.HORIZONTAL) -1f else 1f) * scale
        view.scaleY = (if (transform.mirror == VideoMirrorMode.VERTICAL) -1f else 1f) * scale
        // 排障 / 验收证据：把实际落到视图上的参数打出来（真机走查用文本核对，不靠截图）
        Timber.d(
            "ExoPlayer 画面变换: rotation=%.1f scaleX=%.3f scaleY=%.3f resizeMode=%d" +
                " zoomFill=%.3f viewAspect=%.3f videoAspect=%.3f crop=%d",
            view.rotation,
            view.scaleX,
            view.scaleY,
            view.resizeMode,
            zoomFill,
            if (view.height > 0) view.width.toFloat() / view.height else 0f,
            videoRatio,
            transform.cropPercent,
        )
    }

    /**
     * 画面显示区（视频实际占用的像素矩形，相对字幕覆盖层坐标系）。
     *
     * W16：libass 字幕层拿它当渲染分辨率——字幕定位跟视频画面走（与 mpv 一致），而不是跟整窗走。 取 `exo_content_frame`（PlayerView
     * 已按视频比例排好的内容框），再叠加视图侧的旋转 / 缩放 （裁剪填满 / 去黑边改的都是视图变换），最后与画面区求交。
     */
    private fun currentVideoRect(): Rect {
        val view = binding.playerView
        val overlay = binding.subtitleOverlayCompose
        if (view.width <= 0 || view.height <= 0) return Rect()
        val content = view.findViewById<View>(androidx.media3.ui.R.id.exo_content_frame) ?: view

        val centerX = view.width / 2f
        val centerY = view.height / 2f
        val scaleX = abs(view.scaleX)
        val scaleY = abs(view.scaleY)
        val rad = Math.toRadians(view.rotation.toDouble())
        val cos = cos(rad).toFloat()
        val sin = sin(rad).toFloat()

        val corners =
            floatArrayOf(
                content.left.toFloat(),
                content.top.toFloat(),
                content.right.toFloat(),
                content.top.toFloat(),
                content.right.toFloat(),
                content.bottom.toFloat(),
                content.left.toFloat(),
                content.bottom.toFloat(),
            )
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        var index = 0
        while (index < corners.size) {
            val dx = (corners[index] - centerX) * scaleX
            val dy = (corners[index + 1] - centerY) * scaleY
            val x = dx * cos - dy * sin + centerX
            val y = dx * sin + dy * cos + centerY
            minX = min(minX, x)
            maxX = max(maxX, x)
            minY = min(minY, y)
            maxY = max(maxY, y)
            index += 2
        }

        val viewLocation = IntArray(2).also { view.getLocationInWindow(it) }
        val overlayLocation = IntArray(2).also { overlay.getLocationInWindow(it) }
        val offsetX = (viewLocation[0] - overlayLocation[0]).toFloat()
        val offsetY = (viewLocation[1] - overlayLocation[1]).toFloat()

        val combined = RectF(minX + offsetX, minY + offsetY, maxX + offsetX, maxY + offsetY)
        val area = RectF(offsetX, offsetY, offsetX + view.width, offsetY + view.height)
        if (!combined.intersect(area)) return Rect()
        val out = Rect()
        combined.round(out)
        return out
    }

    /** mpv 换硬件解码：播放中即时生效（ExoPlayer 不走这条） */
    private fun applyMpvHwDec(hwDec: String) {
        (viewModel.player as? MPVPlayer)?.applyHwDec(hwDec)
        Timber.d("mpv hwdec=%s", hwDec)
    }

    /**
     * 解码策略（W12 反馈 B）：硬解优先 / 仅软解。
     *
     * 偏好已由面板写入 [AppPreferences.playerDecodeMode]；两个内核都要用新参数重新创建实例 （ExoPlayer 的扩展渲染器模式、mpv 的 hwdec
     * 都是构造期参数），所以这里走「重启播放页 + 保留进度」。 同时把 mpv 的 hwdec 键同步成同一语义，避免两个入口给出互相矛盾的解码策略。
     */
    private fun selectDecodeMode(mode: String) {
        val software = mode == PlayerViewModel.DECODE_MODE_SOFTWARE
        appPreferences.setValue(appPreferences.playerDecodeMode, mode)
        appPreferences.setValue(appPreferences.playerMpvHwdec, if (software) "no" else "mediacodec")
        // 用户显式选解码策略：清空回退档位（否则「硬解优先」会被上一次的回退档位压成软解）
        viewModel.clearDecodeFallback()
        restartPlaybackKeepingPosition("decode mode=$mode")
    }

    /**
     * 「失败自动回退」开关（W19 用户新需求）：开 = 既有回退链（硬解 → 服务器转码 → mpv 软解）； 关 = 强制使用所选内核，失败只提示错误（不切换、不重启）。
     *
     * 关闭时顺带清空回退档位与重启守卫：面板的「当前档位」与「强制所选内核」语义一致，下一次失败直接进错误卡片。
     */
    private fun selectAutoFallback(enabled: Boolean) {
        settingsController.setAutoFallback(enabled)
        if (!enabled) viewModel.clearDecodeFallback()
        Timber.d("解码自动回退开关：%s", if (enabled) "开" else "关（强制所选内核）")
    }

    /**
     * 码率档位（W12 反馈 B）：选具体 Mbps 时重新拉取播放信息，服务器返回 `transcodingPath`（HLS 转码流）后播放它。
     *
     * 偏好已由面板写入 [AppPreferences.playerStreamingBitrate]，data 层据此构造 PlaybackInfo 请求； 这里只需重启播放页让
     * `getMediaSources` 重新走一遍（不清偏好，不重登）。
     */
    private fun selectStreamingBitrate(bitrate: Long) {
        appPreferences.setValue(appPreferences.playerStreamingBitrate, bitrate)
        // 用户显式选码率：清空回退档位（自动档重新按优先级链走）
        viewModel.clearDecodeFallback()
        restartPlaybackKeepingPosition("streaming bitrate=$bitrate")
    }

    /** 重启播放页并从当前位置续播：清 ViewModel（连着旧 player）后 recreate，Intent 里带上位置 */
    private fun restartPlaybackKeepingPosition(reason: String) {
        val position = viewModel.player.currentPosition.coerceAtLeast(0L)
        Timber.d("Restart player (%s) from position=%d", reason, position)
        restartPlaybackFromPosition(position)
    }

    /**
     * 从给定位置重开播放页。
     *
     * W18：单独抽出来是因为「手动切内核」必须**先读位置、再写偏好**（见 [switchBackendAndRestart]），
     * 位置要由调用方传入，不能在这里再读一次（那时实例已重建、位置是 0）。
     *
     * W19：重启前把「正在播放的条目」和「播放会话 id」写回 Intent—— ① 季 / 剧集入口与队列换集时，Intent 里的原始条目 ≠
     * 实际播放条目，重启用实际条目才不会跳回第一集 （找不到条目信息时保持原样）；② 会话 id 让解码回退档位活过 Activity 重建（见
     * [EXTRA_PLAYBACK_SESSION]）。
     */
    private fun restartPlaybackFromPosition(
        positionMs: Long,
        target: PlayerRestartTarget? = currentRestartTarget(),
    ) {
        target?.let {
            intent.putExtra("itemId", it.itemId)
            intent.putExtra("itemKind", it.itemKind)
            Timber.d("Restart player 对准当前条目：itemId=%s kind=%s", it.itemId, it.itemKind)
        }
        intent.putExtra(EXTRA_PLAYBACK_SESSION, playbackSessionId)
        intent.putExtra(EXTRA_START_POSITION_MS, positionMs)
        viewModelStore.clear()
        recreate()
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
