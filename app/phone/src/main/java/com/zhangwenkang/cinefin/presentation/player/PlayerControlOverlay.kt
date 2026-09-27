package com.zhangwenkang.cinefin.presentation.player

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.ui.AspectRatioFrameLayout
import com.zhangwenkang.cinefin.core.R as CoreR
import com.zhangwenkang.cinefin.models.FindroidSegment
import com.zhangwenkang.cinefin.player.core.domain.models.PLAYER_EXTRA_EPISODE_NUMBER
import com.zhangwenkang.cinefin.player.core.domain.models.PLAYER_EXTRA_SEASON_NUMBER
import com.zhangwenkang.cinefin.player.core.domain.models.PlayerChapter
import com.zhangwenkang.cinefin.player.core.domain.models.Trickplay
import com.zhangwenkang.cinefin.player.local.R as PlayerR
import com.zhangwenkang.cinefin.player.local.presentation.PlayerViewModel
import kotlin.math.roundToLong
import kotlinx.coroutines.delay
import timber.log.Timber

/* =========================================================================
影阁 · 播放控制层（Compose）

设计解读：私人影音库的播放控制层，受众是自己家的片库；
          气质是影院式克制暗色，dial = VARIANCE 5 / MOTION 5 / DENSITY 5。
颜色锁：   唯一强调色朱砂 #D2553C；中性色只用墨系（#0B0C0E / #0F1114 / #131518 / #24282C）。
形状锁：   按钮与胶囊 999dp，缩略图 12dp，面板 16dp，进度条 2~4dp。
可读性：   上下渐变遮罩保证任何画面上文字都读得清（对比度 ≥ AA）。
========================================================================= */

internal val Ink = Color(0xFF0B0C0E)
internal val SurfaceLow = Color(0xFF0F1114)
internal val SurfaceHigh = Color(0xFF131518)
internal val SurfaceRow = Color(0xFF17191D)
internal val Hairline = Color(0xFF24282C)
internal val Vermilion = Color(0xFFD2553C)
internal val Paper = Color(0xFFF1EDE6)
internal val Mist = Color(0xFFA8A49C)

internal val ScrimTop =
    Brush.verticalGradient(listOf(Color(0xE00B0C0E), Color(0x990B0C0E), Color(0x000B0C0E)))
internal val ScrimBottom =
    Brush.verticalGradient(listOf(Color(0x000B0C0E), Color(0xCC0B0C0E), Color(0xF20B0C0E)))

/** 可选倍速档位：与设置里的「长按倍速」共用同一批数值，保持一致 */
private val SpeedOptions = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 3f)

/** 睡眠定时的档位（分钟） */
private val SleepOptions = listOf(10, 20, 30, 60)

internal enum class PlayerPanel {
    None,
    /** 小窗 / 窄宽下的「更多」聚合入口：点进去再选具体面板 */
    More,
    Speed,
    Repeat,
    Subtitle,
    Audio,
    Aspect,
    Info,
    Queue,
    Sleep,
}

/**
 * 画面比例档位。
 *
 * [resizeMode] 直接就是 `AspectRatioFrameLayout.RESIZE_MODE_*` 的值，偏好里存的是同一个数字， Activity 拿它写回
 * `PlayerView.resizeMode`，不需要再做一层映射。
 */
private enum class AspectMode(
    val resizeMode: Int,
    @StringRes val labelRes: Int,
    @StringRes val captionRes: Int,
) {
    Fit(
        AspectRatioFrameLayout.RESIZE_MODE_FIT,
        PlayerR.string.player_controls_aspect_fit,
        PlayerR.string.player_controls_aspect_fit_caption,
    ),
    Zoom(
        AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
        PlayerR.string.player_controls_aspect_zoom,
        PlayerR.string.player_controls_aspect_zoom_caption,
    ),
    Fill(
        AspectRatioFrameLayout.RESIZE_MODE_FILL,
        PlayerR.string.player_controls_aspect_fill,
        PlayerR.string.player_controls_aspect_fill_caption,
    );

    companion object {
        fun of(resizeMode: Int): AspectMode =
            entries.firstOrNull { it.resizeMode == resizeMode } ?: Fit
    }
}

/**
 * 控制层可见性与锁定状态。
 *
 * 由 Activity 持有：Compose 控制层渲染它，手势层（[com.zhangwenkang.cinefin.utils.PlayerGestureHelper]） 通过
 * [toggle] 响应单击，两边共用同一份状态，不会出现「控件和手势各说各话」。
 */
@Stable
class PlayerControlsState {
    var visible by mutableStateOf(true)
    var locked by mutableStateOf(false)

    fun toggle() {
        if (!locked) {
            visible = !visible
            Timber.d("player controls visible=$visible")
        }
    }

    fun show() {
        if (!locked) visible = true
    }

    fun hide() {
        visible = false
    }

    fun setLock(locked: Boolean) {
        this.locked = locked
        visible = !locked
        Timber.d("player controls locked=$locked")
    }
}

/** 播放中的易变状态：位置 / 缓冲 / 播放中 / 轨道 / 队列，统一在这里聚合 */
@Stable
private class PlayerRuntime {
    var position by mutableLongStateOf(0L)
    var duration by mutableLongStateOf(0L)
    var buffered by mutableLongStateOf(0L)
    var isPlaying by mutableStateOf(false)
    var isBuffering by mutableStateOf(false)
    var speed by mutableFloatStateOf(1f)
    var tracks by mutableStateOf<Tracks?>(null)
    var currentIndex by mutableIntStateOf(0)
    var queueEntries by mutableStateOf<List<QueueEntry>>(emptyList())
    var title by mutableStateOf("")
    var repeatMode by mutableIntStateOf(Player.REPEAT_MODE_OFF)
    var shuffleEnabled by mutableStateOf(false)

    /** 当前播放核心能不能被随机播放：mpv 后端的 setShuffleModeEnabled 还是 TODO，不能碰 */
    var canShuffle by mutableStateOf(false)

    fun sync(player: Player) {
        position = player.currentPosition.coerceAtLeast(0L)
        duration = player.duration.let { if (it > 0L) it else 0L }
        buffered = player.bufferedPosition.coerceAtLeast(0L)
        isPlaying = player.isPlaying
        isBuffering = player.playbackState == Player.STATE_BUFFERING
        speed = player.playbackParameters.speed
        tracks = player.currentTracks
        currentIndex = player.currentMediaItemIndex
        title = player.currentMediaItem?.mediaMetadata?.title?.toString().orEmpty()
        repeatMode = player.repeatMode
        shuffleEnabled = player.shuffleModeEnabled
        canShuffle = player.availableCommands.contains(Player.COMMAND_SET_SHUFFLE_MODE)
        if (player.mediaItemCount != queueEntries.size) {
            queueEntries =
                (0 until player.mediaItemCount).map { index ->
                    val mediaItem = player.getMediaItemAt(index)
                    val extras = mediaItem.mediaMetadata.extras
                    QueueEntry(
                        title = mediaItem.mediaMetadata.title?.toString().orEmpty(),
                        seasonNumber =
                            extras?.getInt(PLAYER_EXTRA_SEASON_NUMBER, -1)?.takeIf { it >= 0 },
                        episodeNumber =
                            extras?.getInt(PLAYER_EXTRA_EPISODE_NUMBER, -1)?.takeIf { it >= 0 },
                    )
                }
        }
    }
}

@Composable
private fun rememberPlayerRuntime(player: Player): PlayerRuntime {
    val runtime = remember { PlayerRuntime() }
    DisposableEffect(player) {
        val listener =
            object : Player.Listener {
                override fun onEvents(p: Player, events: Player.Events) {
                    runtime.sync(p)
                }
            }
        player.addListener(listener)
        runtime.sync(player)
        onDispose { player.removeListener(listener) }
    }
    LaunchedEffect(player) {
        while (true) {
            runtime.sync(player)
            delay(if (runtime.isPlaying) 500L else 1000L)
        }
    }
    return runtime
}

/**
 * 播放页控制层。
 *
 * 五层结构里的第 ③④ 层：控制层与面板层。渲染层仍是 Media3 的 PlayerView / mpv Surface， 手势层仍是既有的
 * PlayerGestureHelper（本层不接管触摸，只有控件本身消费事件）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerControlOverlay(
    player: Player,
    uiState: PlayerViewModel.UiState,
    controls: PlayerControlsState,
    /** 当前形态与骨架（D9）：决定控制层落在画面区还是折痕下屏、内容栏是否常驻 */
    layout: PlayerLayoutContext,
    isPipSupported: Boolean,
    /** 平板 / 折叠展开时侧栏是否展开；收起后画面区占满，内容不再常驻 */
    sidePanelExpanded: Boolean = true,
    onToggleSidePanel: () -> Unit = {},
    onBack: () -> Unit,
    onPip: () -> Unit,
    onSelectSpeed: (Float) -> Unit,
    onSelectTrack: (Int, Int) -> Unit,
    onSkipSegment: (FindroidSegment) -> Unit,
    /** 打开播放页时先用哪个画面比例（Activity 从偏好里读出来的 `PlayerView.RESIZE_MODE_*`） */
    initialResizeMode: Int,
    onSelectResizeMode: (Int) -> Unit,
    /** 当前播放核心是否支持画面比例（mpv 核心由它自己控制画面，这里就只做展示不接管） */
    aspectSupported: Boolean,
    /** 播放失败后的「重试」：清错误并从当前进度重新拉流 */
    onRetry: () -> Unit,
    /** 「换内核」：ExoPlayer ⇄ mpv（由 Activity 写偏好并重启播放页生效） */
    onSwitchBackend: () -> Unit,
    /** 把「控制层可见 / 面板打开 / 锁屏 / 错误卡片」同步给承载视图，用来决定哪些触摸留给播放器 */
    onRegionsChanged:
        (visible: Boolean, panelOpen: Boolean, locked: Boolean, errorVisible: Boolean) -> Unit =
        { _, _, _, _ ->
        },
) {
    val runtime = rememberPlayerRuntime(player)
    var panel by remember { mutableStateOf(PlayerPanel.None) }
    var aspect by remember { mutableStateOf(AspectMode.of(initialResizeMode)) }
    var sleepMinutes by remember { mutableStateOf<Int?>(null) }
    var sleepRemaining by remember { mutableLongStateOf(0L) }
    var skipChipVisible by remember { mutableStateOf(true) }

    LaunchedEffect(controls.visible, panel, controls.locked, uiState.playerError) {
        onRegionsChanged(
            controls.visible,
            panel != PlayerPanel.None,
            controls.locked,
            uiState.playerError != null,
        )
    }

    // 出错时把控制层顶出来：错误卡片是模态的，用户点掉之前一直可见
    LaunchedEffect(uiState.playerError) {
        if (uiState.playerError != null) {
            controls.show()
        }
    }

    // 3.5 秒无操作自动淡出：播放中、没有面板、没有错误时才淡出；
    // 小窗与折叠半开的控制区是常驻操作面（画面小、手势难落），不参与淡出
    LaunchedEffect(controls.visible, runtime.isPlaying, panel, uiState.playerError, layout.chrome) {
        if (
            controls.visible &&
                panel == PlayerPanel.None &&
                runtime.isPlaying &&
                uiState.playerError == null &&
                !layout.isCompact &&
                layout.chrome != PlayerChromeLayout.FoldHalfOpen
        ) {
            delay(3500L)
            controls.hide()
        }
    }

    // 片头/片尾按钮：跟当前片段走，超时后自动收起
    LaunchedEffect(uiState.currentSegment) {
        if (uiState.currentSegment != null) {
            skipChipVisible = true
            delay(8000L)
            skipChipVisible = false
        } else {
            skipChipVisible = false
        }
    }

    // 睡眠定时：到点暂停
    LaunchedEffect(sleepMinutes) {
        val minutes = sleepMinutes
        if (minutes == null) {
            sleepRemaining = 0L
            return@LaunchedEffect
        }
        var remaining = minutes * 60_000L
        while (remaining > 0L) {
            sleepRemaining = remaining
            delay(1000L)
            remaining -= 1000L
        }
        sleepRemaining = 0L
        player.pause()
        sleepMinutes = null
    }

    // 底栏在两处出现（画面区内 / 折叠半开下屏），参数完全一致，抽成一个局部 composable
    val bottomBar: @Composable (Modifier, Brush?) -> Unit = { barModifier, scrim ->
        PlayerBottomBar(
            positionMs = runtime.position,
            durationMs = runtime.duration,
            bufferedMs = runtime.buffered,
            chapters = uiState.currentChapters,
            trickplay = uiState.currentTrickplay,
            speed = runtime.speed,
            subtitleEnabled = hasSelectedTrack(runtime.tracks, C.TRACK_TYPE_TEXT),
            repeatActive = runtime.repeatMode != Player.REPEAT_MODE_OFF || runtime.shuffleEnabled,
            aspectActive = aspect.resizeMode != AspectMode.Fit.resizeMode,
            sleepMinutes = sleepMinutes,
            sleepRemainingMs = sleepRemaining,
            onSeek = { target -> player.seekTo(target) },
            onScrubStart = { controls.show() },
            onOpenSpeed = { panel = PlayerPanel.Speed },
            onOpenRepeat = { panel = PlayerPanel.Repeat },
            onOpenSubtitle = { panel = PlayerPanel.Subtitle },
            onOpenAudio = { panel = PlayerPanel.Audio },
            onOpenAspect = { panel = PlayerPanel.Aspect },
            onOpenInfo = { panel = PlayerPanel.Info },
            onOpenQueue = { panel = PlayerPanel.Queue },
            onOpenSleep = { panel = PlayerPanel.Sleep },
            onLock = { controls.setLock(true) },
            scrim = scrim,
            modifier = barModifier,
        )
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        // 画面区尺寸与 Activity 给 PlayerView 的布局参数同源：侧栏收起后画面区立即变宽
        val videoWidthDp =
            if (layout.hasSideContent && sidePanelExpanded) {
                layout.videoWidthDp
            } else {
                layout.windowWidthDp
            }
        val videoWidth = with(density) { videoWidthDp.dp }
        val videoHeight = with(density) { layout.videoHeightDp.dp }

        if (layout.isCompact) {
            // 小窗：单行控制条 + 细进度条，其余面积留给画面
            PlayerCompactBar(
                isPlaying = runtime.isPlaying,
                title = uiState.currentItemTitle.ifEmpty { runtime.title },
                positionMs = runtime.position,
                durationMs = runtime.duration,
                bufferedMs = runtime.buffered,
                chapters = uiState.currentChapters,
                trickplay = uiState.currentTrickplay,
                onPlayPause = { if (player.isPlaying) player.pause() else player.play() },
                onSeek = { target -> player.seekTo(target) },
                onScrubStart = { controls.show() },
                onOpenMore = { panel = PlayerPanel.More },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        } else {
            Box(modifier = Modifier.width(videoWidth).height(videoHeight).align(Alignment.TopStart)) {
                AnimatedVisibility(
                    visible = controls.visible,
                    enter = fadeIn(tween(180)),
                    exit = fadeOut(tween(180)),
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        PlayerTopBar(
                            title =
                                uiState.currentItemTitle.ifEmpty {
                                    runtime.title.ifEmpty { stringResource(CoreR.string.app_name) }
                                },
                            qualityLabel = videoQualityLabel(runtime.tracks),
                            isPipSupported = isPipSupported,
                            onBack = onBack,
                            onPip = onPip,
                            onLock = { controls.setLock(true) },
                            modifier = Modifier.align(Alignment.TopCenter),
                        )

                        val playerError = uiState.playerError
                        if (playerError != null) {
                            // 出错时中央大控件让位给错误卡片，避免「点了没反应」的假按钮
                            PlayerErrorCard(
                                error = playerError,
                                onRetry = onRetry,
                                onSwitchBackend = onSwitchBackend,
                                modifier = Modifier.align(Alignment.Center),
                            )
                        } else {
                            PlayerCenterControls(
                                isPlaying = runtime.isPlaying,
                                isBuffering = runtime.isBuffering,
                                onPrevious = { player.seekToPreviousMediaItem() },
                                onRewind = { player.seekBack() },
                                onPlayPause = {
                                    if (player.isPlaying) player.pause() else player.play()
                                },
                                onForward = { player.seekForward() },
                                onNext = { player.seekToNextMediaItem() },
                                modifier = Modifier.align(Alignment.Center),
                            )
                        }

                        // 折叠半开的底栏落在折痕下屏，画面区不再重复一份
                        if (layout.chrome != PlayerChromeLayout.FoldHalfOpen) {
                            bottomBar(Modifier.align(Alignment.BottomCenter), ScrimBottom)
                        }
                    }
                }

                // 锁屏态：只留一个解锁按钮，其余触摸交给手势层吞掉
                if (controls.locked) {
                    LockedOverlay(
                        onUnlock = { controls.setLock(false) },
                        modifier = Modifier.align(Alignment.CenterEnd),
                    )
                }

                // 片头/片尾跳过：独立于控制层，超时或播放结束自动消失
                val segment = uiState.currentSegment
                if (segment != null && skipChipVisible && !controls.locked) {
                    SkipSegmentChip(
                        text = stringResource(uiState.currentSkipButtonStringRes),
                        onClick = {
                            onSkipSegment(segment)
                            skipChipVisible = false
                        },
                        modifier =
                            Modifier.align(Alignment.BottomEnd)
                                .padding(end = 24.dp, bottom = 132.dp),
                    )
                }
            }
        }

        // 平板 / 折叠展开：右侧常驻内容栏，可收起换回满屏画面
        if (layout.hasSideContent && sidePanelExpanded) {
            PlayerSideContent(
                entries = runtime.queueEntries,
                currentIndex = runtime.currentIndex,
                onSelect = { index ->
                    player.seekTo(index, 0L)
                    controls.show()
                },
                onCollapse = onToggleSidePanel,
                modifier =
                    Modifier.width(layout.sidePanelWidthDp.dp)
                        .fillMaxHeight()
                        .align(Alignment.TopEnd),
            )
        }

        // 手机竖屏：画面下方的常驻内容区（选集 / 队列）
        if (layout.hasBottomContent) {
            PlayerBottomContent(
                entries = runtime.queueEntries,
                currentIndex = runtime.currentIndex,
                onSelect = { index -> player.seekTo(index, 0L) },
                modifier = Modifier.fillMaxSize().padding(top = videoHeight),
            )
        }

        // 折叠半开（水平折痕）：折痕以下是常驻控制区，不参与自动淡出
        if (layout.chrome == PlayerChromeLayout.FoldHalfOpen) {
            Column(
                modifier = Modifier.fillMaxSize().padding(top = videoHeight).background(SurfaceLow)
            ) {
                bottomBar(Modifier.fillMaxWidth(), null)
                PlayerBottomContent(
                    entries = runtime.queueEntries,
                    currentIndex = runtime.currentIndex,
                    onSelect = { index -> player.seekTo(index, 0L) },
                    showHandle = false,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }

    if (panel != PlayerPanel.None) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { panel = PlayerPanel.None },
            sheetState = sheetState,
            containerColor = SurfaceHigh,
            contentColor = Paper,
            scrimColor = Color(0xB3000000),
            dragHandle = { SheetHandle() },
        ) {
            when (panel) {
                PlayerPanel.More -> MorePanel(onOpen = { panel = it })
                PlayerPanel.Speed ->
                    SpeedPanel(
                        current = runtime.speed,
                        onSelect = {
                            onSelectSpeed(it)
                            panel = PlayerPanel.None
                        },
                    )
                PlayerPanel.Repeat ->
                    RepeatPanel(
                        repeatMode = runtime.repeatMode,
                        shuffleEnabled = runtime.shuffleEnabled,
                        canShuffle = runtime.canShuffle,
                        onSelect = { mode, shuffle ->
                            player.repeatMode = mode
                            // mpv 后端的 setShuffleModeEnabled 还是 TODO，能力不足时不碰它
                            if (
                                player.availableCommands.contains(Player.COMMAND_SET_SHUFFLE_MODE)
                            ) {
                                player.shuffleModeEnabled = shuffle
                            }
                            panel = PlayerPanel.None
                        },
                    )
                PlayerPanel.Subtitle ->
                    TrackPanel(
                        titleRes = PlayerR.string.select_subtitle_track,
                        tracks = trackOptions(runtime.tracks, C.TRACK_TYPE_TEXT),
                        onSelect = { index ->
                            onSelectTrack(C.TRACK_TYPE_TEXT, index)
                            panel = PlayerPanel.None
                        },
                    )
                PlayerPanel.Audio ->
                    TrackPanel(
                        titleRes = PlayerR.string.select_audio_track,
                        tracks = trackOptions(runtime.tracks, C.TRACK_TYPE_AUDIO),
                        onSelect = { index ->
                            onSelectTrack(C.TRACK_TYPE_AUDIO, index)
                            panel = PlayerPanel.None
                        },
                    )
                PlayerPanel.Aspect ->
                    AspectPanel(
                        current = aspect,
                        supported = aspectSupported,
                        onSelect = { mode ->
                            aspect = mode
                            onSelectResizeMode(mode.resizeMode)
                            panel = PlayerPanel.None
                        },
                    )
                PlayerPanel.Info -> InfoPanel(runtime = runtime, title = uiState.currentItemTitle)
                PlayerPanel.Queue ->
                    QueuePanel(
                        entries = runtime.queueEntries,
                        currentIndex = runtime.currentIndex,
                        onSelect = { index ->
                            player.seekTo(index, 0L)
                            panel = PlayerPanel.None
                        },
                    )
                PlayerPanel.Sleep ->
                    SleepPanel(
                        currentMinutes = sleepMinutes,
                        onSelect = {
                            sleepMinutes = it
                            panel = PlayerPanel.None
                        },
                    )
                PlayerPanel.None -> Unit
            }
        }
    }
}

// ---------- 顶部 ----------

@Composable
private fun PlayerTopBar(
    title: String,
    /** 清晰度徽标（例如 1080P）；取不到时传 null，不占位 */
    qualityLabel: String?,
    isPipSupported: Boolean,
    onBack: () -> Unit,
    onPip: () -> Unit,
    onLock: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            modifier
                .fillMaxWidth()
                .background(ScrimTop)
                .padding(horizontal = 8.dp, vertical = 10.dp),
    ) {
        PlayerIconButton(
            iconRes = CoreR.drawable.ic_arrow_left,
            contentDescription = stringResource(PlayerR.string.player_controls_exit),
            onClick = onBack,
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = Paper,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (qualityLabel != null) {
            Spacer(Modifier.width(8.dp))
            Text(
                text = qualityLabel,
                style = MaterialTheme.typography.labelMedium,
                color = Mist,
                modifier =
                    Modifier.clip(RoundedCornerShape(4.dp))
                        .border(1.dp, Hairline, RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
        Spacer(Modifier.weight(1f))
        if (isPipSupported) {
            PlayerIconButton(
                iconRes = CoreR.drawable.ic_picture_in_picture,
                contentDescription = stringResource(PlayerR.string.player_controls_pip),
                onClick = onPip,
            )
        }
        PlayerIconButton(
            iconRes = CoreR.drawable.ic_lock,
            contentDescription = stringResource(PlayerR.string.player_controls_lock),
            onClick = onLock,
        )
    }
}

// ---------- 中央 ----------

@Composable
private fun PlayerCenterControls(
    isPlaying: Boolean,
    isBuffering: Boolean,
    onPrevious: () -> Unit,
    onRewind: () -> Unit,
    onPlayPause: () -> Unit,
    onForward: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier,
    ) {
        PlayerIconButton(
            iconRes = CoreR.drawable.ic_skip_back,
            contentDescription = stringResource(PlayerR.string.player_controls_skip_back),
            onClick = onPrevious,
            size = 48.dp,
        )
        PlayerIconButton(
            iconRes = CoreR.drawable.ic_rewind,
            contentDescription = stringResource(PlayerR.string.player_controls_rewind),
            onClick = onRewind,
            size = 48.dp,
        )
        // 唯一的实心朱砂按钮：播放/暂停是最高频动作
        Box(
            contentAlignment = Alignment.Center,
            modifier =
                Modifier.size(72.dp)
                    .clip(CircleShape)
                    .background(if (isBuffering) SurfaceRow else Vermilion)
                    .clickable(onClick = onPlayPause),
        ) {
            if (isBuffering) {
                androidx.compose.material3.CircularProgressIndicator(
                    modifier = Modifier.size(28.dp),
                    color = Paper,
                    strokeWidth = 2.dp,
                )
            } else {
                Icon(
                    painter =
                        painterResource(
                            if (isPlaying) CoreR.drawable.ic_pause else CoreR.drawable.ic_play
                        ),
                    contentDescription = stringResource(PlayerR.string.player_controls_play_pause),
                    tint = Paper,
                    modifier = Modifier.size(34.dp),
                )
            }
        }
        PlayerIconButton(
            iconRes = CoreR.drawable.ic_fast_forward,
            contentDescription = stringResource(PlayerR.string.player_controls_fast_forward),
            onClick = onForward,
            size = 48.dp,
        )
        PlayerIconButton(
            iconRes = CoreR.drawable.ic_skip_forward,
            contentDescription = stringResource(PlayerR.string.player_controls_skip_forward),
            onClick = onNext,
            size = 48.dp,
        )
    }
}

/**
 * 播放失败卡片：说明 + 重试 + 换内核。
 *
 * 只在 ExoPlayer 内核会走到（mpv 目前不上报错误，见 docs/PLAYER_PLAN.md）； 说明最多三行，按钮命中区 ≥48dp，配色沿用墨底 + 发丝线，不用阴影。
 */
@Composable
private fun PlayerErrorCard(
    error: PlayerViewModel.PlayerErrorInfo,
    onRetry: () -> Unit,
    onSwitchBackend: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 卡片上直接写清「换成哪个内核」，避免用户点下去不知道会发生什么
    val targetBackend =
        if (error.backend == PlayerViewModel.PLAYER_BACKEND_EXOPLAYER) "mpv" else "ExoPlayer"
    val shape = RoundedCornerShape(16.dp)

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier =
            modifier
                .widthIn(max = 420.dp)
                .clip(shape)
                .background(Color(0xE6131518))
                .border(1.dp, Hairline, shape)
                .padding(horizontal = 24.dp, vertical = 20.dp),
    ) {
        Text(
            text = stringResource(PlayerR.string.player_controls_error_title),
            color = Paper,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = error.message,
            color = Mist,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "${error.backend} · ${error.codeName}",
            color = Mist,
            style = MaterialTheme.typography.labelSmall,
        )
        Spacer(modifier = Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ErrorActionButton(
                text = stringResource(PlayerR.string.player_controls_error_retry),
                primary = true,
                onClick = onRetry,
            )
            ErrorActionButton(
                text =
                    stringResource(
                        PlayerR.string.player_controls_error_switch_backend,
                        targetBackend,
                    ),
                primary = false,
                onClick = onSwitchBackend,
            )
        }
    }
}

@Composable
private fun ErrorActionButton(
    text: String,
    primary: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(999.dp)
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier.heightIn(min = 48.dp)
                .clip(shape)
                .background(if (primary) Vermilion else SurfaceRow)
                .border(1.dp, if (primary) Color.Transparent else Hairline, shape)
                .clickable(onClick = onClick)
                .padding(horizontal = 20.dp),
    ) {
        Text(
            text = text,
            color = Paper,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium,
        )
    }
}

// ---------- 底部 ----------

@Composable
private fun PlayerBottomBar(
    positionMs: Long,
    durationMs: Long,
    bufferedMs: Long,
    chapters: List<PlayerChapter>,
    trickplay: Trickplay?,
    speed: Float,
    subtitleEnabled: Boolean,
    repeatActive: Boolean,
    aspectActive: Boolean,
    sleepMinutes: Int?,
    sleepRemainingMs: Long,
    onSeek: (Long) -> Unit,
    onScrubStart: () -> Unit,
    onOpenSpeed: () -> Unit,
    onOpenRepeat: () -> Unit,
    onOpenSubtitle: () -> Unit,
    onOpenAudio: () -> Unit,
    onOpenAspect: () -> Unit,
    onOpenInfo: () -> Unit,
    onOpenQueue: () -> Unit,
    onOpenSleep: () -> Unit,
    onLock: () -> Unit,
    /** 底栏遮罩：叠在画面上的形态用渐变，折痕下屏用 null（背景由内容区承担） */
    scrim: Brush? = ScrimBottom,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .then(if (scrim != null) Modifier.background(scrim) else Modifier)
                .padding(start = 20.dp, end = 20.dp, bottom = 14.dp)
    ) {
        PlayerSeekBar(
            positionMs = positionMs,
            durationMs = durationMs,
            bufferedMs = bufferedMs,
            chapters = chapters,
            trickplay = trickplay,
            onScrubStart = onScrubStart,
            onScrub = { onSeek(it) },
        )

        Spacer(Modifier.height(6.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = formatTime(positionMs),
                style = MaterialTheme.typography.labelMedium,
                color = Paper,
            )
            Text(
                text = " / " + formatTime(durationMs),
                style = MaterialTheme.typography.labelMedium,
                color = Mist,
            )
            if (sleepRemainingMs > 0L) {
                Spacer(Modifier.width(12.dp))
                Text(
                    text = "睡眠 " + formatTime(sleepRemainingMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = Vermilion,
                )
            }
            Spacer(Modifier.weight(1f))
            Text(
                text = formatSpeed(speed),
                style = MaterialTheme.typography.labelMedium,
                color = if (speed != 1f) Vermilion else Mist,
            )
        }

        Spacer(Modifier.height(4.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            /*
             * 图标行可横向滚动：控件数量会随功能增加，窄屏（竖屏 / 分屏小窗）不至于把锁屏按钮挤出屏幕，
             * 宽屏上内容不足以撑满时也只是靠左排列，看起来和固定排布没有区别。
             */
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
            ) {
                PlayerIconButton(
                    iconRes = CoreR.drawable.ic_gauge,
                    contentDescription = stringResource(PlayerR.string.select_playback_speed),
                    onClick = onOpenSpeed,
                )
                PlayerIconButton(
                    iconRes = CoreR.drawable.ic_repeat,
                    contentDescription = stringResource(PlayerR.string.player_controls_repeat),
                    onClick = onOpenRepeat,
                    selected = repeatActive,
                )
                PlayerIconButton(
                    iconRes = CoreR.drawable.ic_closed_caption,
                    contentDescription = stringResource(PlayerR.string.select_subtitle_track),
                    onClick = onOpenSubtitle,
                    selected = subtitleEnabled,
                )
                PlayerIconButton(
                    iconRes = CoreR.drawable.ic_speaker,
                    contentDescription = stringResource(PlayerR.string.select_audio_track),
                    onClick = onOpenAudio,
                )
                PlayerIconButton(
                    iconRes = CoreR.drawable.ic_aspect,
                    contentDescription = stringResource(PlayerR.string.player_controls_aspect),
                    onClick = onOpenAspect,
                    selected = aspectActive,
                )
                PlayerIconButton(
                    iconRes = CoreR.drawable.ic_info,
                    contentDescription = stringResource(PlayerR.string.player_controls_info),
                    onClick = onOpenInfo,
                )
                PlayerIconButton(
                    iconRes = CoreR.drawable.ic_logs,
                    contentDescription = stringResource(PlayerR.string.player_controls_queue),
                    onClick = onOpenQueue,
                )
                PlayerIconButton(
                    iconRes = CoreR.drawable.ic_sun,
                    contentDescription = stringResource(PlayerR.string.player_controls_sleep_timer),
                    onClick = onOpenSleep,
                    selected = sleepMinutes != null,
                )
            }
            PlayerIconButton(
                iconRes = CoreR.drawable.ic_lock,
                contentDescription = stringResource(PlayerR.string.player_controls_lock),
                onClick = onLock,
            )
        }
    }
}

/**
 * 进度条：底座 4dp 发丝线 + 已缓冲段 + 朱砂已播段 + 章节刻度 + 拖动时的 Trickplay 预览。 命中区做到 32dp，视觉仍只有 4dp，符合「可点目标不小于
 * 48dp」的可操作性要求。
 */
@Composable
internal fun PlayerSeekBar(
    positionMs: Long,
    durationMs: Long,
    bufferedMs: Long,
    chapters: List<PlayerChapter>,
    trickplay: Trickplay?,
    onScrubStart: () -> Unit,
    onScrub: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    var scrubbing by remember { mutableStateOf(false) }
    var scrubFraction by remember { mutableFloatStateOf(0f) }

    val safeDuration = durationMs.coerceAtLeast(1L)
    val playedFraction =
        (if (scrubbing) scrubFraction else positionMs.toFloat() / safeDuration).coerceIn(0f, 1f)
    val bufferedFraction = (bufferedMs.toFloat() / safeDuration).coerceIn(0f, 1f)
    val previewPosition = (playedFraction * safeDuration).roundToLong()
    val previewBitmap: android.graphics.Bitmap? =
        if (scrubbing && trickplay != null && trickplay.images.isNotEmpty()) {
            val index = (previewPosition / trickplay.interval.coerceAtLeast(1)).toInt()
            trickplay.images.getOrNull(index.coerceIn(0, trickplay.images.size - 1))
        } else {
            null
        }

    Column(modifier = modifier.fillMaxWidth()) {
        if (previewBitmap != null) {
            Row(
                verticalAlignment = Alignment.Bottom,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            ) {
                Image(
                    bitmap = previewBitmap.asImageBitmap(),
                    contentDescription = null,
                    modifier =
                        Modifier.width(160.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .border(1.dp, Hairline, RoundedCornerShape(12.dp)),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = formatTime(previewPosition),
                    style = MaterialTheme.typography.titleMedium,
                    color = Paper,
                    modifier =
                        Modifier.clip(RoundedCornerShape(8.dp))
                            .background(SurfaceRow)
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        }

        Box(
            modifier =
                Modifier.fillMaxWidth()
                    .height(34.dp)
                    .pointerInput(safeDuration) {
                        detectTapGestures { offset ->
                            val fraction = (offset.x / size.width).coerceIn(0f, 1f)
                            onScrubStart()
                            onScrub((fraction * safeDuration).roundToLong())
                        }
                    }
                    .pointerInput(safeDuration) {
                        detectDragGestures(
                            onDragStart = { offset ->
                                scrubbing = true
                                scrubFraction = (offset.x / size.width).coerceIn(0f, 1f)
                                onScrubStart()
                            },
                            onDrag = { change, _ ->
                                scrubFraction = (change.position.x / size.width).coerceIn(0f, 1f)
                                onScrub((scrubFraction * safeDuration).roundToLong())
                            },
                            onDragEnd = {
                                onScrub((scrubFraction * safeDuration).roundToLong())
                                scrubbing = false
                            },
                            onDragCancel = { scrubbing = false },
                        )
                    },
            contentAlignment = Alignment.CenterStart,
        ) {
            // 底座
            Box(
                modifier =
                    Modifier.fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(Hairline)
            )
            // 已缓冲
            Box(
                modifier =
                    Modifier.fillMaxWidth(bufferedFraction)
                        .height(4.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(Mist.copy(alpha = 0.35f))
            )
            // 已播（唯一强调色）
            Box(
                modifier =
                    Modifier.fillMaxWidth(playedFraction)
                        .height(4.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(Vermilion)
            )
            // 章节刻度：细白线，只做位置提示
            chapters.forEach { chapter ->
                val fraction = (chapter.startPosition.toFloat() / safeDuration).coerceIn(0f, 1f)
                Box(
                    modifier = Modifier.fillMaxWidth(fraction).height(4.dp).padding(end = 0.dp),
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    Box(
                        modifier =
                            Modifier.width(2.dp).height(9.dp).background(Paper.copy(alpha = 0.55f))
                    )
                }
            }
            // 拖动手柄
            Box(
                modifier = Modifier.fillMaxWidth(playedFraction),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Box(
                    modifier =
                        Modifier.size(if (scrubbing) 16.dp else 12.dp)
                            .clip(CircleShape)
                            .background(Paper)
                )
            }
        }
    }
}

// ---------- 小部件 ----------

@Composable
internal fun PlayerIconButton(
    iconRes: Int,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    /** 选中态：该功能已开启（字幕、循环、画面比例、睡眠定时等） */
    selected: Boolean = false,
    enabled: Boolean = true,
    /** 加载态：图标位置显示转圈，期间不可点 */
    loading: Boolean = false,
    /** 错误态：图标转朱砂并压一层淡朱砂底 */
    error: Boolean = false,
    /** 激活徽标：右上角朱砂小点，表示"有内容 / 需注意" */
    badge: Boolean = false,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val focused by interactionSource.collectIsFocusedAsState()
    val shape = CircleShape

    Box(
        contentAlignment = Alignment.Center,
        modifier =
            modifier
                .size(size)
                .graphicsLayer {
                    val scale = if (pressed) 0.94f else 1f
                    scaleX = scale
                    scaleY = scale
                    // 禁用态整体降到 38%，与 Material 的禁用观感一致
                    alpha = if (enabled) 1f else 0.38f
                }
                .clip(shape)
                .background(
                    when {
                        error -> Vermilion.copy(alpha = 0.18f)
                        selected || pressed -> SurfaceRow
                        focused -> SurfaceHigh
                        else -> Color.Transparent
                    }
                )
                .then(if (focused) Modifier.border(2.dp, Vermilion, shape) else Modifier)
                .clickable(
                    interactionSource = interactionSource,
                    // 暗色影院风格不用涟漪：按下反馈由缩放 + 底色承担
                    indication = null,
                    enabled = enabled && !loading,
                    role = Role.Button,
                    onClickLabel = contentDescription,
                    onClick = onClick,
                )
                /*
                 * 语义与点击挂在同一个节点上：以前把 contentDescription 放在子 Icon 上，
                 * 可点节点本身没有标签，TalkBack 会念成"未加标签的按钮"。
                 */
                .semantics(mergeDescendants = true) {
                    this.contentDescription = contentDescription
                    this.selected = selected
                },
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(size * 0.45f),
                color = Paper,
                strokeWidth = 2.dp,
            )
        } else {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = if (error || selected) Vermilion else Paper,
                modifier = Modifier.size(size * 0.5f),
            )
        }
        if (badge && !loading) {
            Box(
                modifier =
                    Modifier.align(Alignment.TopEnd)
                        .padding(top = size * 0.14f, end = size * 0.14f)
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(Vermilion)
            )
        }
    }
}

@Composable
private fun SkipSegmentChip(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            modifier
                .clip(RoundedCornerShape(999.dp))
                .background(SurfaceLow.copy(alpha = 0.92f))
                .border(1.dp, Hairline, RoundedCornerShape(999.dp))
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Icon(
            painter = painterResource(CoreR.drawable.ic_skip_forward),
            contentDescription = null,
            tint = Vermilion,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = Paper,
        )
    }
}

@Composable
private fun LockedOverlay(
    onUnlock: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            modifier
                .padding(end = 20.dp)
                .size(56.dp)
                .clip(CircleShape)
                .background(SurfaceLow.copy(alpha = 0.86f))
                .border(1.dp, Hairline, CircleShape)
                .clickable(onClick = onUnlock),
    ) {
        Icon(
            painter = painterResource(CoreR.drawable.ic_unlock),
            contentDescription = stringResource(PlayerR.string.player_controls_unlock),
            tint = Paper,
            modifier = Modifier.size(24.dp),
        )
    }
}

@Composable
private fun SheetHandle() {
    Box(
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier =
                Modifier.width(36.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(Hairline)
        )
    }
}

// ---------- 工具 ----------

internal fun formatTime(millis: Long): String {
    val totalSeconds = (millis / 1000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%02d:%02d".format(minutes, seconds)
    }
}

internal fun formatSpeed(speed: Float): String {
    val rounded = (speed * 100f).roundToLong() / 100f
    return if (rounded == rounded.toLong().toFloat()) {
        "${rounded.toLong()}×"
    } else {
        "$rounded×"
    }
}

// ---------- 面板 ----------

private data class TrackOption(
    val index: Int,
    val label: String,
    val selected: Boolean,
)

/** 播放队列里的一项：标题 + 季/集号（电影没有季号） */
internal data class QueueEntry(
    val title: String,
    val seasonNumber: Int?,
    val episodeNumber: Int?,
)

private fun trackOptions(tracks: Tracks?, type: Int): List<TrackOption> {
    val groups = tracks?.groups?.filter { it.type == type && it.isSupported }.orEmpty()
    return groups.mapIndexed { index, group ->
        val format = group.mediaTrackGroup.getFormat(0)
        val language = format.language?.takeIf { it.isNotBlank() }
        val label = format.label?.takeIf { it.isNotBlank() }
        TrackOption(
            index = index,
            label = label ?: language ?: format.sampleMimeType ?: "轨道 ${index + 1}",
            selected = group.isSelected,
        )
    }
}

private fun hasSelectedTrack(tracks: Tracks?, type: Int): Boolean =
    tracks?.groups?.any { it.type == type && it.isSupported && it.isSelected } == true

/**
 * 顶栏清晰度徽标：取当前选中视频轨的高度换算成档位名。
 *
 * mpv 内核不上报轨道信息，这时返回 null，顶栏就不显示徽标（而不是显示一个假值）。
 */
private fun videoQualityLabel(tracks: Tracks?): String? {
    val group = tracks?.groups?.firstOrNull { it.type == C.TRACK_TYPE_VIDEO } ?: return null
    val format =
        (0 until group.length)
            .firstOrNull { index -> group.isTrackSelected(index) }
            ?.let { index -> group.getTrackFormat(index) }
    val height = format?.height ?: return null
    if (height <= 0) return null
    return when {
        height >= 2000 -> "4K"
        height >= 1400 -> "1440P"
        height >= 1000 -> "1080P"
        height >= 700 -> "720P"
        height >= 500 -> "576P"
        height >= 400 -> "480P"
        else -> "${height}P"
    }
}

@Composable
internal fun PanelTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = Paper,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp),
    )
}

/** 面板里的一行：左侧文字，右侧选中标记；整行可点，高度 ≥ 52dp */
@Composable
internal fun PanelRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    caption: String? = null,
    /** 能力不足的档位留个位置说明原因，不做假开关 */
    enabled: Boolean = true,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier.fillMaxWidth()
                .padding(horizontal = 12.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(
                    if (selected && enabled) Vermilion.copy(alpha = 0.14f) else Color.Transparent
                )
                .clickable(enabled = enabled, onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color =
                    when {
                        !enabled -> Mist
                        selected -> Vermilion
                        else -> Paper
                    },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (caption != null) {
                Text(
                    text = caption,
                    style = MaterialTheme.typography.bodySmall,
                    color = Mist,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (selected && enabled) {
            Icon(
                painter = painterResource(CoreR.drawable.ic_check),
                contentDescription = null,
                tint = Vermilion,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
internal fun PanelList(content: @Composable () -> Unit) {
    Column(
        modifier =
            Modifier.fillMaxWidth()
                .heightIn(max = 420.dp)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 20.dp)
    ) {
        content()
    }
}

@Composable
private fun SpeedPanel(
    current: Float,
    onSelect: (Float) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        PanelTitle(stringResource(PlayerR.string.select_playback_speed))
        PanelList {
            SpeedOptions.forEach { speed ->
                val selected = kotlin.math.abs(speed - current) < 0.01f
                PanelRow(
                    label = formatSpeed(speed),
                    selected = selected,
                    onClick = { onSelect(speed) },
                )
            }
        }
    }
}

/**
 * 循环模式：顺序播放（播完暂停）/ 列表循环 / 单集循环 / 随机播放。
 *
 * 前三档改 `Player.repeatMode`，随机档另外打开 `shuffleModeEnabled`； 播放核心没有 `COMMAND_SET_SHUFFLE_MODE` 时（mpv
 * 后端）随机一行禁用并写明原因。
 */
@Composable
private fun RepeatPanel(
    repeatMode: Int,
    shuffleEnabled: Boolean,
    canShuffle: Boolean,
    onSelect: (repeatMode: Int, shuffle: Boolean) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        PanelTitle(stringResource(PlayerR.string.player_controls_repeat))
        PanelList {
            PanelRow(
                label = stringResource(PlayerR.string.player_controls_repeat_off),
                caption = stringResource(PlayerR.string.player_controls_repeat_off_caption),
                selected = !shuffleEnabled && repeatMode == Player.REPEAT_MODE_OFF,
                onClick = { onSelect(Player.REPEAT_MODE_OFF, false) },
            )
            PanelRow(
                label = stringResource(PlayerR.string.player_controls_repeat_all),
                caption = stringResource(PlayerR.string.player_controls_repeat_all_caption),
                selected = !shuffleEnabled && repeatMode == Player.REPEAT_MODE_ALL,
                onClick = { onSelect(Player.REPEAT_MODE_ALL, false) },
            )
            PanelRow(
                label = stringResource(PlayerR.string.player_controls_repeat_one),
                caption = stringResource(PlayerR.string.player_controls_repeat_one_caption),
                selected = !shuffleEnabled && repeatMode == Player.REPEAT_MODE_ONE,
                onClick = { onSelect(Player.REPEAT_MODE_ONE, false) },
            )
            PanelRow(
                label = stringResource(PlayerR.string.player_controls_shuffle),
                caption =
                    stringResource(
                        if (canShuffle) {
                            PlayerR.string.player_controls_shuffle_caption
                        } else {
                            PlayerR.string.player_controls_shuffle_unsupported
                        }
                    ),
                selected = shuffleEnabled,
                enabled = canShuffle,
                onClick = { onSelect(Player.REPEAT_MODE_ALL, true) },
            )
        }
    }
}

/** 画面比例：适应屏幕 / 裁剪填满 / 拉伸填满 */
@Composable
private fun AspectPanel(
    current: AspectMode,
    supported: Boolean,
    onSelect: (AspectMode) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        PanelTitle(stringResource(PlayerR.string.player_controls_aspect))
        PanelList {
            AspectMode.entries.forEach { mode ->
                PanelRow(
                    label = stringResource(mode.labelRes),
                    caption =
                        stringResource(
                            if (supported) {
                                mode.captionRes
                            } else {
                                PlayerR.string.player_controls_aspect_unsupported
                            }
                        ),
                    selected = mode == current,
                    enabled = supported,
                    onClick = { onSelect(mode) },
                )
            }
        }
    }
}

@Composable
private fun TrackPanel(
    titleRes: Int,
    tracks: List<TrackOption>,
    onSelect: (Int) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        PanelTitle(stringResource(titleRes))
        if (tracks.isEmpty()) {
            Text(
                text = stringResource(PlayerR.string.player_controls_no_track),
                style = MaterialTheme.typography.bodyMedium,
                color = Mist,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            )
            return@Column
        }
        PanelList {
            // 字幕轨多一个「关闭」：与官方客户端一致，关闭后不再自动选轨
            if (titleRes == PlayerR.string.select_subtitle_track) {
                PanelRow(
                    label = stringResource(PlayerR.string.player_controls_subtitle_off),
                    selected = tracks.none { it.selected },
                    onClick = { onSelect(-1) },
                )
            }
            tracks.forEach { track ->
                PanelRow(
                    label = track.label,
                    selected = track.selected,
                    onClick = { onSelect(track.index) },
                )
            }
        }
    }
}

@Composable
private fun InfoPanel(
    runtime: PlayerRuntime,
    title: String,
) {
    val videoFormat =
        runtime.tracks
            ?.groups
            ?.firstOrNull { it.type == C.TRACK_TYPE_VIDEO && it.isSupported }
            ?.mediaTrackGroup
            ?.getFormat(0)
    val audioCount =
        runtime.tracks?.groups?.count { it.type == C.TRACK_TYPE_AUDIO && it.isSupported } ?: 0
    val subtitleCount =
        runtime.tracks?.groups?.count { it.type == C.TRACK_TYPE_TEXT && it.isSupported } ?: 0

    Column(modifier = Modifier.fillMaxWidth()) {
        PanelTitle(stringResource(PlayerR.string.player_controls_info))
        PanelList {
            InfoRow(
                label = stringResource(PlayerR.string.player_controls_info_title),
                value = title.ifEmpty { runtime.title },
            )
            videoFormat?.let { format ->
                val resolution =
                    if (format.width > 0 && format.height > 0) {
                        "${format.width} × ${format.height}"
                    } else {
                        "—"
                    }
                InfoRow(
                    label = stringResource(PlayerR.string.player_controls_info_resolution),
                    value = resolution,
                )
                format.frameRate
                    ?.takeIf { it > 0f }
                    ?.let { fps ->
                        InfoRow(
                            label = stringResource(PlayerR.string.player_controls_info_frame_rate),
                            value = "${fps.roundToLong()} fps",
                        )
                    }
                format.sampleMimeType?.let { mime ->
                    InfoRow(
                        label = stringResource(PlayerR.string.player_controls_info_codec),
                        value = mime,
                    )
                }
                format.bitrate
                    ?.takeIf { it > 0 }
                    ?.let { bitrate ->
                        InfoRow(
                            label = stringResource(PlayerR.string.player_controls_info_bitrate),
                            value = "${bitrate / 1000} kbps",
                        )
                    }
            }
            InfoRow(
                label = stringResource(PlayerR.string.player_controls_info_audio_tracks),
                value = audioCount.toString(),
            )
            InfoRow(
                label = stringResource(PlayerR.string.player_controls_info_subtitle_tracks),
                value = subtitleCount.toString(),
            )
            InfoRow(
                label = stringResource(PlayerR.string.player_controls_info_speed),
                value = formatSpeed(runtime.speed),
            )
            InfoRow(
                label = stringResource(PlayerR.string.player_controls_info_duration),
                value = formatTime(runtime.duration),
            )
            InfoRow(
                label = stringResource(PlayerR.string.player_controls_info_buffered),
                value = formatTime(runtime.buffered),
            )
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = Mist,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = Paper,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 240.dp),
        )
    }
}

@Composable
private fun QueuePanel(
    entries: List<QueueEntry>,
    currentIndex: Int,
    onSelect: (Int) -> Unit,
) {
    val seasons = entries.mapNotNull { it.seasonNumber }.distinct().sorted()
    val currentSeason = entries.getOrNull(currentIndex)?.seasonNumber
    var selectedSeason by
        remember(seasons, currentSeason) { mutableStateOf(currentSeason ?: seasons.firstOrNull()) }

    Column(modifier = Modifier.fillMaxWidth()) {
        PanelTitle(stringResource(PlayerR.string.player_controls_queue))
        if (entries.isEmpty()) {
            Text(
                text = stringResource(PlayerR.string.player_controls_queue_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = Mist,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            )
            return@Column
        }

        // 剧集：按季分组，先选季再看集；电影等没有季信息时退化成平铺列表
        if (seasons.isNotEmpty()) {
            ScrollableTabRow(
                selectedTabIndex = seasons.indexOf(selectedSeason).coerceAtLeast(0),
                containerColor = Color.Transparent,
                contentColor = Vermilion,
                edgePadding = 20.dp,
                divider = {},
            ) {
                seasons.forEach { season ->
                    Tab(
                        selected = season == selectedSeason,
                        onClick = { selectedSeason = season },
                        text = {
                            Text(
                                text =
                                    stringResource(PlayerR.string.player_controls_season, season),
                                style = MaterialTheme.typography.labelLarge,
                            )
                        },
                        selectedContentColor = Vermilion,
                        unselectedContentColor = Mist,
                    )
                }
            }
        }

        PanelList {
            entries.forEachIndexed { index, entry ->
                val visible =
                    seasons.isEmpty() ||
                        entry.seasonNumber == null ||
                        entry.seasonNumber == selectedSeason
                if (!visible) return@forEachIndexed

                PanelRow(
                    label = queueLabel(index = index, entry = entry),
                    selected = index == currentIndex,
                    onClick = { onSelect(index) },
                )
            }
        }
    }
}

/** 队列里的单行：有集号时显示「E03 标题」，否则退化成「3. 标题」 */
internal fun queueLabel(index: Int, entry: QueueEntry): String {
    val title = entry.title.ifBlank { "—" }
    val episode = entry.episodeNumber
    return if (episode != null) {
        "E%02d  %s".format(episode, title)
    } else {
        "${index + 1}. $title"
    }
}

@Composable
private fun MorePanel(onOpen: (PlayerPanel) -> Unit) {
    /*
     * 小窗 / 窄宽形态的聚合入口：底栏那排图标放不下时，把同样的功能收进一个列表。
     * 选中后直接把面板切过去（不先关闭再打开），少一次弹出动画。
     */
    Column(modifier = Modifier.fillMaxWidth()) {
        PanelTitle(stringResource(PlayerR.string.player_controls_more))
        PanelRow(
            label = stringResource(PlayerR.string.select_playback_speed),
            selected = false,
            onClick = { onOpen(PlayerPanel.Speed) },
        )
        PanelRow(
            label = stringResource(PlayerR.string.player_controls_repeat),
            selected = false,
            onClick = { onOpen(PlayerPanel.Repeat) },
        )
        PanelRow(
            label = stringResource(PlayerR.string.select_subtitle_track),
            selected = false,
            onClick = { onOpen(PlayerPanel.Subtitle) },
        )
        PanelRow(
            label = stringResource(PlayerR.string.select_audio_track),
            selected = false,
            onClick = { onOpen(PlayerPanel.Audio) },
        )
        PanelRow(
            label = stringResource(PlayerR.string.player_controls_aspect),
            selected = false,
            onClick = { onOpen(PlayerPanel.Aspect) },
        )
        PanelRow(
            label = stringResource(PlayerR.string.player_controls_queue),
            selected = false,
            onClick = { onOpen(PlayerPanel.Queue) },
        )
        PanelRow(
            label = stringResource(PlayerR.string.player_controls_info),
            selected = false,
            onClick = { onOpen(PlayerPanel.Info) },
        )
        PanelRow(
            label = stringResource(PlayerR.string.player_controls_sleep_timer),
            selected = false,
            onClick = { onOpen(PlayerPanel.Sleep) },
        )
    }
}

@Composable
private fun SleepPanel(
    currentMinutes: Int?,
    onSelect: (Int?) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        PanelTitle(stringResource(PlayerR.string.player_controls_sleep_timer))
        PanelList {
            PanelRow(
                label = stringResource(PlayerR.string.player_controls_sleep_off),
                selected = currentMinutes == null,
                onClick = { onSelect(null) },
            )
            SleepOptions.forEach { minutes ->
                PanelRow(
                    label = stringResource(PlayerR.string.player_controls_sleep_minutes, minutes),
                    selected = currentMinutes == minutes,
                    onClick = { onSelect(minutes) },
                )
            }
        }
    }
}
