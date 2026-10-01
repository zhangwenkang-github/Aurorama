package com.zhangwenkang.cinefin.presentation.player

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
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
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
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
import androidx.compose.ui.semantics.paneTitle
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
import coil3.compose.AsyncImage
import com.zhangwenkang.cinefin.core.R as CoreR
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinMotion
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinShapes
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinSpacing
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinType
import com.zhangwenkang.cinefin.core.presentation.theme.LocalCinefinColors
import com.zhangwenkang.cinefin.core.presentation.theme.LocalMediaColors
import com.zhangwenkang.cinefin.models.FindroidSegment
import com.zhangwenkang.cinefin.player.core.domain.models.PLAYER_EXTRA_EPISODE_NUMBER
import com.zhangwenkang.cinefin.player.core.domain.models.PLAYER_EXTRA_SEASON_NUMBER
import com.zhangwenkang.cinefin.player.core.domain.models.PlayerChapter
import com.zhangwenkang.cinefin.player.core.domain.models.SubtitleStyle
import com.zhangwenkang.cinefin.player.core.domain.models.Trickplay
import com.zhangwenkang.cinefin.player.local.R as PlayerR
import com.zhangwenkang.cinefin.player.local.audio.AudioDelayProcessor
import com.zhangwenkang.cinefin.player.local.presentation.PlayerViewModel
import com.zhangwenkang.cinefin.player.local.subtitle.PlayerSubtitleController
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlinx.coroutines.delay
import timber.log.Timber

/* =========================================================================
Cinefin · 播放控制层（Compose）

设计解读：私人影音库的沉浸播放层（S1 · 流光「内容即光源」+ Prism 设计系统 v1.0）。
          画面是唯一光源：控制层只在需要时浮现，面板从右侧盖在画面上而不是挤压画面。
颜色锁：  影视域媒体色（琥珀 MediaFilm）只出现在进度、进行中与激活面板上，且必须融进
          控件本体（填充 / 描边 / 图标 / 文字）；其余一律中性色（CinefinColors）。
          禁止独立色块 / 色点 / 发光，禁止任何 Color(0x…) 字面量。
形状锁：  主播放键 22dp、工具键 16dp、面板 22dp、chip 12dp（CinefinShapes）。
动效锁：  控制层 280ms Decelerate（淡入 + 上浮 12dp）；面板 420ms Emphasized；只动 transform / opacity / color。
可读性：  上下渐隐遮罩 + 覆盖层工具键玻璃底，保证任何画面上文字与图标都可读。
========================================================================= */

/** 顶部渐隐遮罩：界面只在需要时浮现（流光 A 手法）。 */
@Composable
internal fun playerTopScrim(): Brush {
    val scrim = LocalCinefinColors.current.scrim
    return remember(scrim) {
        Brush.verticalGradient(
            listOf(scrim.copy(alpha = 0.86f), scrim.copy(alpha = 0.5f), Color.Transparent)
        )
    }
}

/** 底部渐隐遮罩：给进度条与工具行托底。 */
@Composable
internal fun playerBottomScrim(): Brush {
    val scrim = LocalCinefinColors.current.scrim
    return remember(scrim) {
        Brush.verticalGradient(
            listOf(Color.Transparent, scrim.copy(alpha = 0.66f), scrim.copy(alpha = 0.92f))
        )
    }
}

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
                        artworkUri = mediaItem.mediaMetadata.artworkUri?.toString(),
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
    /** 字幕面板状态（两个内核汇总后的同一份数据） */
    subtitlePanelState: PlayerViewModel.SubtitlePanelState,
    /** 选主字幕；null 表示关闭 */
    onSelectPrimarySubtitle: (Int?) -> Unit,
    /** 选次字幕（双语）；null 表示关闭 */
    onSelectSecondarySubtitle: (Int?) -> Unit,
    /** 字幕延迟步进（±100ms） */
    onAdjustSubtitleDelay: (Long) -> Unit,
    /** 字幕延迟归零 */
    onResetSubtitleDelay: () -> Unit,
    /** 字幕外观调整 */
    onUpdateSubtitleStyle: (SubtitleStyle) -> Unit,
    /** 音轨面板状态（音轨延迟 + 轨道描述） */
    audioPanelState: PlayerViewModel.AudioPanelState,
    /** 选音轨（音频轨道组下标） */
    onSelectAudioTrack: (Int) -> Unit,
    /** 音轨延迟步进（±50ms） */
    onAdjustAudioDelay: (Long) -> Unit,
    /** 音轨延迟归零 */
    onResetAudioDelay: () -> Unit,
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
    // 抽屉退场动画期间保留最后一个面板的内容，避免「滑走的是一块空板」
    var lastPanel by remember { mutableStateOf(PlayerPanel.More) }
    LaunchedEffect(panel) { if (panel != PlayerPanel.None) lastPanel = panel }
    // 打开期间直接用当前面板（首帧不闪「更多」）；关闭后交给 lastPanel 走退场动画
    val drawerPanel = if (panel == PlayerPanel.None) lastPanel else panel
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

    /*
     * 底栏在两处出现（画面区内 / 折叠半开下屏），参数完全一致，抽成一个局部 composable。
     *
     * 队列入口只留一个：有常驻内容栏的骨架（平板 / 折叠展开）里，底栏按钮变成「显示 / 隐藏选集栏」，
     * 不再弹底部面板；手机等没有内容栏的骨架才用底部面板兜底。
     */
    val hasSidePanel = layout.hasSideContent
    val bottomBar: @Composable (Modifier, Brush?) -> Unit = { barModifier, scrim ->
        PlayerBottomBar(
            positionMs = runtime.position,
            durationMs = runtime.duration,
            bufferedMs = runtime.buffered,
            chapters = uiState.currentChapters,
            trickplay = uiState.currentTrickplay,
            speed = runtime.speed,
            subtitleEnabled = hasSelectedTrack(runtime.tracks, C.TRACK_TYPE_TEXT),
            aspectActive = aspect.resizeMode != AspectMode.Fit.resizeMode,
            queueActive = hasSidePanel && sidePanelExpanded,
            sleepRemainingMs = sleepRemaining,
            onSeek = { target -> player.seekTo(target) },
            onScrubStart = { controls.show() },
            onOpenSubtitle = { panel = PlayerPanel.Subtitle },
            onOpenAudio = { panel = PlayerPanel.Audio },
            onOpenAspect = { panel = PlayerPanel.Aspect },
            onOpenQueue =
                if (hasSidePanel) {
                    onToggleSidePanel
                } else {
                    { panel = PlayerPanel.Queue }
                },
            queueDescription =
                stringResource(
                    when {
                        !hasSidePanel -> PlayerR.string.player_controls_queue
                        sidePanelExpanded -> PlayerR.string.player_controls_side_panel_hide
                        else -> PlayerR.string.player_controls_side_panel_show
                    }
                ),
            onOpenMore = { panel = PlayerPanel.More },
            scrim = scrim,
            modifier = barModifier,
        )
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val colors = LocalCinefinColors.current
        // 画面区尺寸与 Activity 给 PlayerView 的布局参数同源：侧栏收起后画面区立即变宽
        val videoWidthDp =
            if (layout.hasSideContent && sidePanelExpanded) {
                layout.videoWidthDp
            } else {
                layout.windowWidthDp
            }
        val videoWidth = with(density) { videoWidthDp.dp }
        val videoHeight = with(density) { layout.videoHeightDp.dp }
        val bottomScrim = playerBottomScrim()
        // 面板抽屉宽度：手机 / 窄窗整宽；宽屏取 46%（360–560dp）。抽屉盖在画面上，不改画面布局
        val drawerWidth =
            if (maxWidth < 420.dp) maxWidth else (maxWidth * 0.46f).coerceIn(360.dp, 560.dp)

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
            Box(
                modifier = Modifier.width(videoWidth).height(videoHeight).align(Alignment.TopStart)
            ) {
                AnimatedVisibility(
                    visible = controls.visible,
                    /*
                     * 流光：控制层淡入 + 上浮 12dp（只动 opacity / transform），退出用 Accelerate 快速让位。
                     * 时长走设计系统 token（motion-player 280ms），不再用历史 180ms。
                     */
                    enter =
                        fadeIn(tween(CinefinMotion.Player, easing = CinefinMotion.Decelerate)) +
                            slideInVertically(
                                animationSpec =
                                    tween(CinefinMotion.Player, easing = CinefinMotion.Decelerate),
                                initialOffsetY = { with(density) { 12.dp.roundToPx() } },
                            ),
                    exit = fadeOut(tween(CinefinMotion.Fast, easing = CinefinMotion.Accelerate)),
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        PlayerTopBar(
                            title =
                                uiState.currentItemTitle.ifEmpty {
                                    runtime.title.ifEmpty { stringResource(CoreR.string.app_name) }
                                },
                            qualityLabel = videoQualityLabel(runtime.tracks),
                            onBack = onBack,
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
                            bottomBar(Modifier.align(Alignment.BottomCenter), bottomScrim)
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
                modifier =
                    Modifier.fillMaxSize().padding(top = videoHeight).background(colors.surfaceDim)
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

        /* ---------- 面板层：右侧抽屉（§11 C）----------
         * 统一从右侧滑出（与选集栏同款），半透明底直接盖在画面上，画面布局不动；
         * 打开期间触摸由 PlayerOverlayContainer 整层接管（panelOpen），点抽屉外的空白关闭。
         */
        if (panel != PlayerPanel.None) {
            Box(
                modifier =
                    Modifier.fillMaxSize()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { panel = PlayerPanel.None },
                        )
            )
        }
        AnimatedVisibility(
            visible = panel != PlayerPanel.None,
            enter =
                slideInHorizontally(
                    animationSpec = tween(CinefinMotion.Page, easing = CinefinMotion.Emphasized),
                    initialOffsetX = { it },
                ) + fadeIn(tween(CinefinMotion.Fast)),
            exit =
                slideOutHorizontally(
                    animationSpec = tween(CinefinMotion.Page, easing = CinefinMotion.Accelerate),
                    targetOffsetX = { it },
                ) + fadeOut(tween(CinefinMotion.Fast)),
            modifier = Modifier.align(Alignment.TopEnd),
        ) {
            PlayerPanelDrawer(
                titleRes = panelTitleRes(drawerPanel),
                width = drawerWidth,
                onClose = { panel = PlayerPanel.None },
            ) {
                when (drawerPanel) {
                    PlayerPanel.More ->
                        MorePanel(
                            isPipSupported = isPipSupported,
                            onPip = onPip,
                            onOpen = { panel = it },
                        )
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
                                    player.availableCommands.contains(
                                        Player.COMMAND_SET_SHUFFLE_MODE
                                    )
                                ) {
                                    player.shuffleModeEnabled = shuffle
                                }
                                panel = PlayerPanel.None
                            },
                        )
                    PlayerPanel.Subtitle ->
                        SubtitlePanel(
                            state = subtitlePanelState,
                            onSelectPrimary = onSelectPrimarySubtitle,
                            onSelectSecondary = onSelectSecondarySubtitle,
                            onAdjustDelay = onAdjustSubtitleDelay,
                            onResetDelay = onResetSubtitleDelay,
                            onUpdateStyle = onUpdateSubtitleStyle,
                        )
                    PlayerPanel.Audio ->
                        AudioPanel(
                            state = audioPanelState,
                            onSelectTrack = onSelectAudioTrack,
                            onAdjustDelay = onAdjustAudioDelay,
                            onResetDelay = onResetAudioDelay,
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
                    PlayerPanel.Info ->
                        InfoPanel(runtime = runtime, title = uiState.currentItemTitle)
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
}

// ---------- 顶部 ----------

@Composable
private fun PlayerTopBar(
    title: String,
    /** 清晰度徽标（例如 1080P）；取不到时传 null，不占位 */
    qualityLabel: String?,
    onBack: () -> Unit,
    onLock: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalCinefinColors.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            modifier
                .fillMaxWidth()
                .background(playerTopScrim())
                .padding(horizontal = CinefinSpacing.Space2, vertical = CinefinSpacing.Space2),
    ) {
        PlayerIconButton(
            iconRes = CoreR.drawable.ic_arrow_left,
            contentDescription = stringResource(PlayerR.string.player_controls_exit),
            onClick = onBack,
        )
        Spacer(Modifier.width(CinefinSpacing.Space1))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = colors.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (qualityLabel != null) {
            Spacer(Modifier.width(CinefinSpacing.Space2))
            Text(
                text = qualityLabel,
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant,
                modifier =
                    Modifier.clip(CinefinShapes.Xs)
                        .border(1.dp, colors.outlineVariant, CinefinShapes.Xs)
                        .padding(horizontal = CinefinSpacing.Space2, vertical = 2.dp),
            )
        }
        Spacer(Modifier.weight(1f))
        // 锁定只保留这一个常驻入口（§11 A：顶栏 / 底栏不再重复；锁定后由 LockedOverlay 解锁）
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
    val colors = LocalCinefinColors.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CinefinSpacing.Space3),
        modifier = modifier,
    ) {
        PlayerTransportButton(
            iconRes = CoreR.drawable.ic_skip_back,
            contentDescription = stringResource(PlayerR.string.player_controls_skip_back),
            onClick = onPrevious,
        )
        PlayerTransportButton(
            iconRes = CoreR.drawable.ic_rewind,
            contentDescription = stringResource(PlayerR.string.player_controls_rewind),
            onClick = onRewind,
        )
        /*
         * 唯一实心键：设计系统 §8.7 主播放键——70dp、圆角 22dp、
         * OnSurface 底 + InverseOnSurface 图标。播放器覆盖层不在这里用媒体色（避免与画面互抢）。
         */
        Box(
            contentAlignment = Alignment.Center,
            modifier =
                Modifier.size(70.dp)
                    .clip(CinefinShapes.Lg)
                    .background(colors.onSurface)
                    .clickable(onClick = onPlayPause),
        ) {
            if (isBuffering) {
                CircularProgressIndicator(
                    modifier = Modifier.size(30.dp),
                    color = colors.inverseOnSurface,
                    strokeWidth = 2.dp,
                )
            } else {
                Icon(
                    painter =
                        painterResource(
                            if (isPlaying) CoreR.drawable.ic_pause else CoreR.drawable.ic_play
                        ),
                    contentDescription = stringResource(PlayerR.string.player_controls_play_pause),
                    tint = colors.inverseOnSurface,
                    modifier = Modifier.size(30.dp),
                )
            }
        }
        PlayerTransportButton(
            iconRes = CoreR.drawable.ic_fast_forward,
            contentDescription = stringResource(PlayerR.string.player_controls_fast_forward),
            onClick = onForward,
        )
        PlayerTransportButton(
            iconRes = CoreR.drawable.ic_skip_forward,
            contentDescription = stringResource(PlayerR.string.player_controls_skip_forward),
            onClick = onNext,
        )
    }
}

/** 次级传输键（±10s / 上下集）：44dp 玻璃圆底 + OnSurface 图标（§8.7 覆盖层次级键）。 */
@Composable
private fun PlayerTransportButton(
    iconRes: Int,
    contentDescription: String,
    onClick: () -> Unit,
) {
    val colors = LocalCinefinColors.current
    val media = LocalMediaColors.current
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val focused by interactionSource.collectIsFocusedAsState()
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier.size(46.dp)
                .graphicsLayer {
                    val scale = if (pressed) 0.92f else 1f
                    scaleX = scale
                    scaleY = scale
                }
                .clip(CinefinShapes.Full)
                .background(colors.scrim.copy(alpha = if (pressed) 0.7f else 0.42f))
                .then(
                    if (focused) {
                        Modifier.border(
                            2.dp,
                            media.base.copy(alpha = 0.6f),
                            CinefinShapes.Full,
                        )
                    } else {
                        Modifier
                    }
                )
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    role = Role.Button,
                    onClickLabel = contentDescription,
                    onClick = onClick,
                )
                .semantics(mergeDescendants = true) {
                    this.contentDescription = contentDescription
                },
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = colors.onSurface,
            modifier = Modifier.size(22.dp),
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
    val colors = LocalCinefinColors.current
    val shape = CinefinShapes.Md

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier =
            modifier
                .widthIn(max = 420.dp)
                .clip(shape)
                .background(colors.surfaceContainer.copy(alpha = 0.96f))
                .border(1.dp, colors.outline, shape)
                .padding(horizontal = CinefinSpacing.Space6, vertical = CinefinSpacing.Space5),
    ) {
        Text(
            text = stringResource(PlayerR.string.player_controls_error_title),
            color = colors.onSurface,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(modifier = Modifier.height(CinefinSpacing.Space2))
        Text(
            text = error.message,
            color = colors.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(modifier = Modifier.height(CinefinSpacing.Space1))
        Text(
            text = "${error.backend} · ${error.codeName}",
            color = colors.onSurfaceFaint,
            style = MaterialTheme.typography.labelSmall,
        )
        Spacer(modifier = Modifier.height(CinefinSpacing.Space5))
        Row(horizontalArrangement = Arrangement.spacedBy(CinefinSpacing.Space3)) {
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
    val colors = LocalCinefinColors.current
    val media = LocalMediaColors.current
    val shape = CinefinShapes.Sm
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier.heightIn(min = 48.dp)
                .clip(shape)
                .background(if (primary) media.base else Color.Transparent)
                .border(1.dp, if (primary) Color.Transparent else media.outline, shape)
                .clickable(onClick = onClick)
                .padding(horizontal = CinefinSpacing.Space5),
    ) {
        Text(
            text = text,
            color = if (primary) media.onBase else media.bright,
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
    aspectActive: Boolean,
    queueActive: Boolean,
    sleepRemainingMs: Long,
    onSeek: (Long) -> Unit,
    onScrubStart: () -> Unit,
    onOpenSubtitle: () -> Unit,
    onOpenAudio: () -> Unit,
    onOpenAspect: () -> Unit,
    onOpenQueue: () -> Unit,
    onOpenMore: () -> Unit,
    /** 队列按钮的语义：平板（有内容栏）时它变成「显示 / 隐藏选集栏」的开关 */
    queueDescription: String,
    /** 底栏遮罩：叠在画面上的形态用渐变，折痕下屏用 null（背景由内容区承担） */
    scrim: Brush? = null,
    modifier: Modifier = Modifier,
) {
    val colors = LocalCinefinColors.current
    val media = LocalMediaColors.current
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .then(if (scrim != null) Modifier.background(scrim) else Modifier)
                .padding(
                    start = CinefinSpacing.Space5,
                    end = CinefinSpacing.Space5,
                    bottom = CinefinSpacing.Space3,
                )
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

        Spacer(Modifier.height(CinefinSpacing.Space2))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = formatTime(positionMs),
                style = CinefinType.MonoData,
                color = colors.onSurface,
            )
            Text(
                text = " / " + formatTime(durationMs),
                style = CinefinType.MonoData,
                color = colors.onSurfaceVariant,
            )
            if (sleepRemainingMs > 0L) {
                Spacer(Modifier.width(CinefinSpacing.Space3))
                Text(
                    text = "睡眠 " + formatTime(sleepRemainingMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = media.bright,
                )
            }
            Spacer(Modifier.weight(1f))
            Text(
                text = formatSpeed(speed),
                style = CinefinType.MonoData,
                color = if (speed != 1f) media.bright else colors.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(CinefinSpacing.Space2))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            /*
             * 高频工具行（§11 B/E）：图标 + 文字标签。低频入口（倍速 / 循环 / 信息 / 睡眠 / 画中画）
             * 已收进「更多」，一屏只保留常用路径；窄屏（竖屏 / 小窗）仍可横向滚动兜底。
             */
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(CinefinSpacing.Space2),
                modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
            ) {
                PlayerToolButton(
                    iconRes = CoreR.drawable.ic_closed_caption,
                    label = stringResource(PlayerR.string.player_controls_label_subtitle),
                    contentDescription = stringResource(PlayerR.string.select_subtitle_track),
                    selected = subtitleEnabled,
                    onClick = onOpenSubtitle,
                )
                PlayerToolButton(
                    iconRes = CoreR.drawable.ic_speaker,
                    label = stringResource(PlayerR.string.player_controls_label_audio),
                    contentDescription = stringResource(PlayerR.string.select_audio_track),
                    onClick = onOpenAudio,
                )
                PlayerToolButton(
                    iconRes = CoreR.drawable.ic_aspect,
                    label = stringResource(PlayerR.string.player_controls_label_aspect),
                    contentDescription = stringResource(PlayerR.string.player_controls_aspect),
                    selected = aspectActive,
                    onClick = onOpenAspect,
                )
                PlayerToolButton(
                    iconRes = CoreR.drawable.ic_playlist,
                    label = stringResource(PlayerR.string.player_controls_episodes),
                    contentDescription = queueDescription,
                    selected = queueActive,
                    onClick = onOpenQueue,
                )
                PlayerToolButton(
                    iconRes = PlayerR.drawable.ic_player_more,
                    label = stringResource(PlayerR.string.player_controls_more),
                    contentDescription = stringResource(PlayerR.string.player_controls_more),
                    onClick = onOpenMore,
                )
            }
        }
    }
}

/**
 * 进度条（画面覆盖层 §8.7）：6dp 轨道白 16% + 已缓冲白 24% + 媒体色已播段 + 章节刻度 + Trickplay 预览。 视觉细、命中区 34dp——「可点目标不小于
 * 48dp」由轨道两端的工具行外扩消化。
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
    val colors = LocalCinefinColors.current
    val media = LocalMediaColors.current

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
                modifier = Modifier.fillMaxWidth().padding(bottom = CinefinSpacing.Space2),
            ) {
                Image(
                    bitmap = previewBitmap.asImageBitmap(),
                    contentDescription = null,
                    modifier =
                        Modifier.width(160.dp)
                            .clip(CinefinShapes.Xs)
                            .border(1.dp, colors.outline, CinefinShapes.Xs),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                )
                Spacer(Modifier.width(CinefinSpacing.Space3))
                Text(
                    text = formatTime(previewPosition),
                    style = CinefinType.MonoData,
                    color = colors.onSurface,
                    modifier =
                        Modifier.clip(CinefinShapes.Xs)
                            .background(colors.scrim.copy(alpha = 0.8f))
                            .padding(horizontal = CinefinSpacing.Space2, vertical = 2.dp),
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
                        .height(6.dp)
                        .clip(CinefinShapes.TwoXs)
                        .background(colors.progressTrackOnImage)
            )
            // 已缓冲
            Box(
                modifier =
                    Modifier.fillMaxWidth(bufferedFraction)
                        .height(6.dp)
                        .clip(CinefinShapes.TwoXs)
                        .background(colors.onSurface.copy(alpha = 0.24f))
            )
            // 已播（唯一强调色：进度是播放器里媒体色的第一落点）
            Box(
                modifier =
                    Modifier.fillMaxWidth(playedFraction)
                        .height(6.dp)
                        .clip(CinefinShapes.TwoXs)
                        .background(media.base)
            )
            // 章节刻度：细白线，只做位置提示
            chapters.forEach { chapter ->
                val fraction = (chapter.startPosition.toFloat() / safeDuration).coerceIn(0f, 1f)
                Box(
                    modifier = Modifier.fillMaxWidth(fraction).height(6.dp),
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    Box(
                        modifier =
                            Modifier.width(2.dp)
                                .height(14.dp)
                                .background(colors.onSurface.copy(alpha = 0.5f))
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
                        Modifier.size(if (scrubbing) 20.dp else 14.dp)
                            .clip(CinefinShapes.TwoXs)
                            .background(colors.onSurface)
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
    /** 是否使用覆盖层玻璃底：画面上的按钮用玻璃（§8.7），实体面板内的按钮保持透明 */
    glass: Boolean = true,
) {
    val colors = LocalCinefinColors.current
    val media = LocalMediaColors.current
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val focused by interactionSource.collectIsFocusedAsState()
    val shape = CinefinShapes.Md

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
                    alpha = if (enabled) 1f else colors.disabledAlpha
                }
                .clip(shape)
                .background(
                    when {
                        error -> colors.error.copy(alpha = 0.16f)
                        selected -> media.container
                        pressed -> colors.scrim.copy(alpha = 0.72f)
                        focused -> colors.stateHover
                        glass -> colors.scrim.copy(alpha = 0.45f)
                        else -> Color.Transparent
                    }
                )
                .then(if (selected) Modifier.border(1.dp, media.outline, shape) else Modifier)
                .then(
                    if (focused) Modifier.border(2.dp, media.base.copy(alpha = 0.6f), shape)
                    else Modifier
                )
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
                color = colors.onSurface,
                strokeWidth = 2.dp,
            )
        } else {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint =
                    when {
                        error -> colors.error
                        selected -> media.bright
                        else -> colors.onSurface
                    },
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
                        .background(media.base)
            )
        }
    }
}

/**
 * 画面覆盖层工具键（§8.7 + §11 B）：图标 + 文字标签，玻璃底。
 *
 * 选中态用媒体色融入控件本体（底 Media.Container + 描边 Media.Outline + 亮色图标文字）， 不使用独立色点。命中区高 56dp，宽 ≥60dp。
 */
@Composable
internal fun PlayerToolButton(
    iconRes: Int,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    contentDescription: String = label,
) {
    val colors = LocalCinefinColors.current
    val media = LocalMediaColors.current
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val focused by interactionSource.collectIsFocusedAsState()
    val shape = CinefinShapes.Md
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier =
            modifier
                // 5 个高频键在 360dp 手机上不换行：56dp 底 + 8dp 间距 + 20dp 边距 ≈ 352dp
                .widthIn(min = 56.dp)
                .heightIn(min = 56.dp)
                .clip(shape)
                .background(
                    when {
                        selected && pressed -> media.containerPressed
                        selected -> media.container
                        pressed -> colors.scrim.copy(alpha = 0.72f)
                        else -> colors.scrim.copy(alpha = 0.45f)
                    }
                )
                .then(if (selected) Modifier.border(1.dp, media.outline, shape) else Modifier)
                .then(
                    if (focused) Modifier.border(2.dp, media.base.copy(alpha = 0.6f), shape)
                    else Modifier
                )
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    role = Role.Button,
                    onClickLabel = contentDescription,
                    onClick = onClick,
                )
                .semantics(mergeDescendants = true) {
                    this.selected = selected
                    this.contentDescription = contentDescription
                }
                .padding(horizontal = CinefinSpacing.Space1, vertical = CinefinSpacing.Space1),
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = if (selected) media.bright else colors.onSurface,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) media.bright else colors.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

@Composable
private fun SkipSegmentChip(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalCinefinColors.current
    val media = LocalMediaColors.current
    val shape = CinefinShapes.Full
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            modifier
                .clip(shape)
                .background(colors.scrim.copy(alpha = 0.8f))
                .border(1.dp, media.outline, shape)
                .clickable(onClick = onClick)
                .padding(horizontal = CinefinSpacing.Space4, vertical = CinefinSpacing.Space3),
    ) {
        Icon(
            painter = painterResource(CoreR.drawable.ic_skip_forward),
            contentDescription = null,
            tint = media.bright,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(CinefinSpacing.Space2))
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = colors.onSurface,
        )
    }
}

@Composable
private fun LockedOverlay(
    onUnlock: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val media = LocalMediaColors.current
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            modifier
                .padding(end = CinefinSpacing.Space5)
                .size(56.dp)
                .clip(CinefinShapes.Lg)
                .background(media.container)
                .border(1.dp, media.outline, CinefinShapes.Lg)
                .clickable(onClick = onUnlock),
    ) {
        Icon(
            painter = painterResource(CoreR.drawable.ic_unlock),
            contentDescription = stringResource(PlayerR.string.player_controls_unlock),
            tint = media.bright,
            modifier = Modifier.size(24.dp),
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

/** 播放队列里的一项：标题 + 季/集号（电影没有季号） */
internal data class QueueEntry(
    val title: String,
    val seasonNumber: Int?,
    val episodeNumber: Int?,
    /** 剧集缩略图（来自媒体项的 artworkUri）；取不到时列表退化成纯文字 */
    val artworkUri: String? = null,
)

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
    val colors = LocalCinefinColors.current
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = colors.onSurfaceFaint,
        modifier =
            Modifier.padding(
                start = CinefinSpacing.Space5,
                end = CinefinSpacing.Space5,
                top = CinefinSpacing.Space3,
                bottom = CinefinSpacing.Space2,
            ),
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
    /** 列表型面板（如播放队列）在文字前放一张 16:9 缩略图 */
    leadingArtworkUri: String? = null,
) {
    val colors = LocalCinefinColors.current
    val media = LocalMediaColors.current
    val shape = CinefinShapes.Sm
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier.fillMaxWidth()
                .padding(horizontal = CinefinSpacing.Space3, vertical = 2.dp)
                .heightIn(min = 52.dp)
                .clip(shape)
                .background(
                    if (selected && enabled) media.container else colors.surfaceContainerLow
                )
                .border(
                    1.dp,
                    if (selected && enabled) media.outline else colors.outlineVariant,
                    shape,
                )
                .clickable(enabled = enabled, onClick = onClick)
                .padding(horizontal = CinefinSpacing.Space3, vertical = CinefinSpacing.Space2),
    ) {
        if (!leadingArtworkUri.isNullOrBlank()) {
            Box(
                modifier =
                    Modifier.width(72.dp)
                        .height(41.dp)
                        .clip(CinefinShapes.Xs)
                        .background(colors.surfaceContainerHigh)
            ) {
                AsyncImage(
                    model = leadingArtworkUri,
                    contentDescription = null,
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Spacer(Modifier.width(CinefinSpacing.Space3))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color =
                    when {
                        !enabled -> colors.onSurfaceFaint
                        selected -> media.bright
                        else -> colors.onSurface
                    },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (caption != null) {
                Text(
                    text = caption,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (selected && enabled) {
            Icon(
                painter = painterResource(CoreR.drawable.ic_check),
                contentDescription = null,
                tint = media.base,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
internal fun ColumnScope.PanelList(content: @Composable () -> Unit) {
    Column(
        modifier =
            Modifier.fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(bottom = CinefinSpacing.Space5)
    ) {
        content()
    }
}

/**
 * 右侧面板抽屉（§11 C）：半透明底直接盖在画面上，标题固定在顶部、内容区滚动。
 *
 * 宿主负责宽度（手机整宽 / 宽屏 46% 收敛到 360–560dp）与滑入滑出动画；本组件只画表面： 左缘 1dp 结构线 + 顶部标题 +
 * 关闭键。抽屉本体吞掉空白点击，避免误触「点外部关闭」的捕获层。
 */
@Composable
private fun PlayerPanelDrawer(
    @StringRes titleRes: Int,
    width: Dp,
    onClose: () -> Unit,
    content: @Composable () -> Unit,
) {
    val colors = LocalCinefinColors.current
    val title = stringResource(titleRes)
    Row(
        modifier =
            Modifier.width(width)
                .fillMaxHeight()
                .background(colors.surfaceContainer.copy(alpha = 0.95f))
                // 抽屉本体吞掉空白点击：用 pointerInput 而不是 clickable，避免给整块面板叠一个"按钮"语义
                .pointerInput(Unit) { detectTapGestures {} }
                .semantics { paneTitle = title }
    ) {
        Box(Modifier.width(1.dp).fillMaxHeight().background(colors.outline))
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier =
                    Modifier.fillMaxWidth()
                        .padding(start = CinefinSpacing.Space5, end = CinefinSpacing.Space2),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                PlayerIconButton(
                    iconRes = CoreR.drawable.ic_close,
                    contentDescription = stringResource(PlayerR.string.player_controls_panel_close),
                    onClick = onClose,
                    glass = false,
                )
            }
            content()
        }
    }
}

/** 抽屉标题：与面板内容一一对应（各面板的顶层标题已移除，标题只在抽屉头出现一次）。 */
@StringRes
private fun panelTitleRes(panel: PlayerPanel): Int =
    when (panel) {
        PlayerPanel.Speed -> PlayerR.string.select_playback_speed
        PlayerPanel.Repeat -> PlayerR.string.player_controls_repeat
        PlayerPanel.Subtitle -> PlayerR.string.select_subtitle_track
        PlayerPanel.Audio -> PlayerR.string.select_audio_track
        PlayerPanel.Aspect -> PlayerR.string.player_controls_aspect
        PlayerPanel.Info -> PlayerR.string.player_controls_info
        PlayerPanel.Queue -> PlayerR.string.player_controls_queue
        PlayerPanel.Sleep -> PlayerR.string.player_controls_sleep_timer
        PlayerPanel.More,
        PlayerPanel.None -> PlayerR.string.player_controls_more
    }

@Composable
private fun SpeedPanel(
    current: Float,
    onSelect: (Float) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
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
    Column(modifier = Modifier.fillMaxSize()) {
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
    Column(modifier = Modifier.fillMaxSize()) {
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

/**
 * 字幕面板（§1.1）：主字幕 / 次字幕（双语）/ 延迟 ±0.1s / 外观。
 *
 * 轨道清单由 ViewModel 汇总（ExoPlayer 读 Jellyfin 字幕源，mpv 读它的 sid）， 面板本身不区分内核。
 */
@Composable
private fun SubtitlePanel(
    state: PlayerViewModel.SubtitlePanelState,
    onSelectPrimary: (Int?) -> Unit,
    onSelectSecondary: (Int?) -> Unit,
    onAdjustDelay: (Long) -> Unit,
    onResetDelay: () -> Unit,
    onUpdateStyle: (SubtitleStyle) -> Unit,
) {
    val colorLabels =
        listOf(
                PlayerR.string.player_subtitle_color_white,
                PlayerR.string.player_subtitle_color_yellow,
                PlayerR.string.player_subtitle_color_cyan,
                PlayerR.string.player_subtitle_color_green,
                PlayerR.string.player_subtitle_color_orange,
            )
            .map { stringResource(it) }
    val backgroundLabels =
        listOf(
                PlayerR.string.player_subtitle_background_none,
                PlayerR.string.player_subtitle_background_light,
                PlayerR.string.player_subtitle_background_solid,
                PlayerR.string.player_subtitle_background_opaque,
            )
            .map { stringResource(it) }
    val edgeLabels =
        listOf(
                PlayerR.string.player_subtitle_edge_none,
                PlayerR.string.player_subtitle_edge_thin,
                PlayerR.string.player_subtitle_edge_thick,
            )
            .map { stringResource(it) }
    val positionLabels =
        listOf(
                PlayerR.string.player_subtitle_position_low,
                PlayerR.string.player_subtitle_position_mid_low,
                PlayerR.string.player_subtitle_position_middle,
                PlayerR.string.player_subtitle_position_mid_high,
                PlayerR.string.player_subtitle_position_high,
            )
            .map { stringResource(it) }
    val sizeLabels = SubtitleStyle.SIZES.map { "${(it * 100).roundToInt()}%" }
    val colors = LocalCinefinColors.current

    Column(modifier = Modifier.fillMaxSize()) {
        PanelList {
            /*
             * 延迟放最上面：调 ±0.1s 是字幕面板最高频的操作，
             * 轨道多的时候（内嵌多语言字幕）不能让它被挤到滚动区下面。
             */
            PanelTitle(stringResource(PlayerR.string.player_subtitle_delay))
            if (state.loading) {
                Text(
                    text = stringResource(PlayerR.string.player_subtitle_loading),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    modifier =
                        Modifier.padding(
                            horizontal = CinefinSpacing.Space5,
                            vertical = CinefinSpacing.Space1,
                        ),
                )
            }
            SubtitleDelayRow(
                delayMs = state.delayMs,
                onAdjust = onAdjustDelay,
                onReset = onResetDelay,
            )

            if (state.primaryOptions.isEmpty()) {
                PanelTitle(stringResource(PlayerR.string.player_subtitle_primary))
                Text(
                    text = stringResource(PlayerR.string.player_subtitle_unavailable),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                    modifier =
                        Modifier.padding(
                            horizontal = CinefinSpacing.Space5,
                            vertical = CinefinSpacing.Space2,
                        ),
                )
            } else {
                // 主字幕
                PanelTitle(stringResource(PlayerR.string.player_subtitle_primary))
                PanelRow(
                    label = stringResource(PlayerR.string.player_controls_subtitle_off),
                    selected = state.primaryId == null,
                    onClick = { onSelectPrimary(null) },
                )
                state.primaryOptions.forEach { option ->
                    PanelRow(
                        label = option.label,
                        caption = option.caption,
                        selected = option.selected,
                        onClick = { onSelectPrimary(option.id) },
                    )
                }
            }

            // 次字幕（双语）：只有在当前媒体有可自管字幕时才有意义
            if (state.controllable) {
                PanelTitle(stringResource(PlayerR.string.player_subtitle_secondary))
                PanelRow(
                    label = stringResource(PlayerR.string.player_controls_subtitle_off),
                    selected = state.secondaryId == null,
                    onClick = { onSelectSecondary(null) },
                )
                state.secondaryOptions.forEach { option ->
                    PanelRow(
                        label = option.label,
                        caption = option.caption,
                        selected = option.id == state.secondaryId,
                        onClick = { onSelectSecondary(option.id) },
                    )
                }
            }

            // 外观：大小 / 颜色 / 背景 / 描边 / 位置
            PanelTitle(stringResource(PlayerR.string.player_subtitle_appearance))
            SubtitleStyleRow(
                titleRes = PlayerR.string.player_subtitle_size,
                labels = sizeLabels,
                selectedIndex = state.style.sizeIndex,
                onSelect = { onUpdateStyle(state.style.copy(sizeIndex = it)) },
            )
            SubtitleStyleRow(
                titleRes = PlayerR.string.player_subtitle_color,
                labels = colorLabels,
                selectedIndex = state.style.colorIndex,
                onSelect = { onUpdateStyle(state.style.copy(colorIndex = it)) },
            )
            SubtitleStyleRow(
                titleRes = PlayerR.string.player_subtitle_background,
                labels = backgroundLabels,
                selectedIndex = state.style.backgroundIndex,
                onSelect = { onUpdateStyle(state.style.copy(backgroundIndex = it)) },
            )
            SubtitleStyleRow(
                titleRes = PlayerR.string.player_subtitle_edge,
                labels = edgeLabels,
                selectedIndex = state.style.edgeIndex,
                onSelect = { onUpdateStyle(state.style.copy(edgeIndex = it)) },
            )
            SubtitleStyleRow(
                titleRes = PlayerR.string.player_subtitle_position,
                labels = positionLabels,
                selectedIndex = state.style.positionIndex,
                onSelect = { onUpdateStyle(state.style.copy(positionIndex = it)) },
            )
        }
    }
}

/** 延迟一行：−0.1s / 当前值（点击归零）/ +0.1s */
@Composable
private fun SubtitleDelayRow(
    delayMs: Long,
    onAdjust: (Long) -> Unit,
    onReset: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
    ) {
        PanelChip(
            label = "−0.1s",
            selected = false,
            contentDescription = stringResource(PlayerR.string.player_subtitle_delay_earlier),
            onClick = { onAdjust(-PlayerSubtitleController.DELAY_STEP_MS) },
        )
        PanelChip(
            label = formatSubtitleDelay(delayMs),
            selected = true,
            contentDescription = stringResource(PlayerR.string.player_subtitle_delay_reset),
            onClick = onReset,
        )
        PanelChip(
            label = "+0.1s",
            selected = false,
            contentDescription = stringResource(PlayerR.string.player_subtitle_delay_later),
            onClick = { onAdjust(PlayerSubtitleController.DELAY_STEP_MS) },
        )
    }
}

/** 外观里的一行档位：标题 + 可横向滚动的一组胶囊 */
@Composable
private fun SubtitleStyleRow(
    @StringRes titleRes: Int,
    labels: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    val colors = LocalCinefinColors.current
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            text = stringResource(titleRes),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant,
            modifier =
                Modifier.padding(
                    start = CinefinSpacing.Space5,
                    end = CinefinSpacing.Space5,
                    bottom = CinefinSpacing.Space1,
                ),
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier =
                Modifier.fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 2.dp),
        ) {
            labels.forEachIndexed { index, label ->
                PanelChip(
                    label = label,
                    selected = index == selectedIndex,
                    onClick = { onSelect(index) },
                )
            }
        }
    }
}

/** 面板里的小胶囊按钮（§8.3 Chip）：未选中中性底 + 描边，选中态媒体色融入控件本体。 */
@Composable
private fun PanelChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    contentDescription: String? = null,
) {
    val colors = LocalCinefinColors.current
    val media = LocalMediaColors.current
    val shape = CinefinShapes.Sm
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier.clip(shape)
                .background(if (selected) media.container else colors.surfaceContainer)
                .border(1.dp, if (selected) media.outline else colors.outline, shape)
                .clickable(onClick = onClick)
                .semantics {
                    this.selected = selected
                    if (contentDescription != null) {
                        this.contentDescription = contentDescription
                    }
                }
                .padding(horizontal = CinefinSpacing.Space4, vertical = CinefinSpacing.Space2),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) media.bright else colors.onSurfaceVariant,
        )
    }
}

/** 延迟显示：+0.3s / −0.1s / 0.0s */
private fun formatSubtitleDelay(delayMs: Long): String {
    val seconds = kotlin.math.abs(delayMs) / 1000.0
    return when {
        delayMs > 0L -> "+%.1fs".format(seconds)
        delayMs < 0L -> "−%.1fs".format(seconds)
        else -> "0.0s"
    }
}

/**
 * 音轨面板（§1.2）：音轨延迟 ±0.05s + 带描述的轨道列表。
 *
 * 轨道清单由 ViewModel 汇总（ExoPlayer 与 mpv 同一份数据）， 描述里带编码、声道与码率，多音轨片源不用再靠猜。
 */
@Composable
private fun AudioPanel(
    state: PlayerViewModel.AudioPanelState,
    onSelectTrack: (Int) -> Unit,
    onAdjustDelay: (Long) -> Unit,
    onResetDelay: () -> Unit,
) {
    val colors = LocalCinefinColors.current
    Column(modifier = Modifier.fillMaxSize()) {
        PanelList {
            // 延迟放最上面：和字幕面板一致，最高频的调节项不该被列表挤到下面
            PanelTitle(stringResource(PlayerR.string.player_audio_delay))
            AudioDelayRow(
                delayMs = state.delayMs,
                onAdjust = onAdjustDelay,
                onReset = onResetDelay,
            )
            if (state.options.isEmpty()) {
                Text(
                    text = stringResource(PlayerR.string.player_audio_no_track),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                    modifier =
                        Modifier.padding(
                            horizontal = CinefinSpacing.Space5,
                            vertical = CinefinSpacing.Space2,
                        ),
                )
            } else {
                state.options.forEach { option ->
                    PanelRow(
                        label = option.label,
                        caption = option.caption,
                        selected = option.selected,
                        onClick = { onSelectTrack(option.id) },
                    )
                }
            }
        }
    }
}

/** 音轨延迟一行：−0.05s / 当前值（点击归零）/ +0.05s */
@Composable
private fun AudioDelayRow(
    delayMs: Long,
    onAdjust: (Long) -> Unit,
    onReset: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
    ) {
        PanelChip(
            label = "−0.05s",
            selected = false,
            contentDescription = stringResource(PlayerR.string.player_audio_delay_earlier),
            onClick = { onAdjust(-AudioDelayProcessor.STEP_MS) },
        )
        PanelChip(
            label = formatAudioDelay(delayMs),
            selected = true,
            contentDescription = stringResource(PlayerR.string.player_audio_delay_reset),
            onClick = onReset,
        )
        PanelChip(
            label = "+0.05s",
            selected = false,
            contentDescription = stringResource(PlayerR.string.player_audio_delay_later),
            onClick = { onAdjust(AudioDelayProcessor.STEP_MS) },
        )
    }
}

/** 音轨延迟显示：+0.25s / −0.05s / 0.00s */
private fun formatAudioDelay(delayMs: Long): String {
    val seconds = kotlin.math.abs(delayMs) / 1000.0
    return when {
        delayMs > 0L -> "+%.2fs".format(seconds)
        delayMs < 0L -> "−%.2fs".format(seconds)
        else -> "0.00s"
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

    Column(modifier = Modifier.fillMaxSize()) {
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
    val colors = LocalCinefinColors.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier.fillMaxWidth()
                .padding(horizontal = CinefinSpacing.Space5, vertical = CinefinSpacing.Space2),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = CinefinType.MonoDataSmall,
            color = colors.onSurface,
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
    val colors = LocalCinefinColors.current
    val media = LocalMediaColors.current
    val seasons = entries.mapNotNull { it.seasonNumber }.distinct().sorted()
    val currentSeason = entries.getOrNull(currentIndex)?.seasonNumber
    var selectedSeason by
        remember(seasons, currentSeason) { mutableStateOf(currentSeason ?: seasons.firstOrNull()) }

    Column(modifier = Modifier.fillMaxSize()) {
        if (entries.isEmpty()) {
            Text(
                text = stringResource(PlayerR.string.player_controls_queue_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
                modifier =
                    Modifier.padding(
                        horizontal = CinefinSpacing.Space5,
                        vertical = CinefinSpacing.Space3,
                    ),
            )
            return@Column
        }

        // 剧集：按季分组，先选季再看集；电影等没有季信息时退化成平铺列表
        if (seasons.isNotEmpty()) {
            ScrollableTabRow(
                selectedTabIndex = seasons.indexOf(selectedSeason).coerceAtLeast(0),
                containerColor = Color.Transparent,
                contentColor = media.base,
                edgePadding = CinefinSpacing.Space5,
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
                        selectedContentColor = media.bright,
                        unselectedContentColor = colors.onSurfaceVariant,
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
                    leadingArtworkUri = entry.artworkUri,
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

/**
 * 「更多」聚合面板（§11 A/E）：低频入口统一收在这里，不再常驻画面。
 *
 * 底栏只留高频（字幕 / 音轨 / 画面 / 选集 / 更多）；倍速、循环、信息、睡眠、画中画从「更多」进，
 * 小窗（Compact）形态下这里也是唯一的功能入口。选中后直接把面板切过去，少一次开合动画。
 */
@Composable
private fun MorePanel(
    isPipSupported: Boolean,
    onPip: () -> Unit,
    onOpen: (PlayerPanel) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        PanelList {
            if (isPipSupported) {
                PanelRow(
                    label = stringResource(PlayerR.string.player_controls_pip),
                    selected = false,
                    onClick = onPip,
                )
            }
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
}

@Composable
private fun SleepPanel(
    currentMinutes: Int?,
    onSelect: (Int?) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
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
