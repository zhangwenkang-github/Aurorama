package com.zhangwenkang.cinefin.music.presentation

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.zhangwenkang.cinefin.core.R as CoreR
import com.zhangwenkang.cinefin.core.presentation.components.CinefinIconButton
import com.zhangwenkang.cinefin.core.presentation.components.CinefinProgressVisuals
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinMotion
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinShapes
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinSpacing
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinType
import com.zhangwenkang.cinefin.core.presentation.theme.LocalCinefinColors
import com.zhangwenkang.cinefin.core.presentation.theme.auroraGradientBrush
import com.zhangwenkang.cinefin.music.R
import com.zhangwenkang.cinefin.music.data.MusicPlayMode
import com.zhangwenkang.cinefin.music.data.MusicSong
import com.zhangwenkang.cinefin.music.data.lyrics.LyricsDisplayLanguage
import com.zhangwenkang.cinefin.music.data.lyrics.LyricsFontScale
import com.zhangwenkang.cinefin.music.data.lyrics.LyricsLineSpacing
import com.zhangwenkang.cinefin.music.data.lyrics.LyricsPresenter
import com.zhangwenkang.cinefin.music.data.lyrics.LyricsWindow
import com.zhangwenkang.cinefin.player.core.domain.models.MusicQueue
import com.zhangwenkang.cinefin.player.core.domain.models.PlayerItem
import java.util.UUID
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 全屏音乐播放界面（W23-MUSIC · B 组）。
 *
 * 结构对齐主流音乐播放器：左上箭头返回、大封面、歌名 / 歌手、可拖动进度条 + 时间、 上一曲 / 播放暂停 / 下一曲、底部辅助行（音效 / 歌词 / 队列）；播放模式 / 收藏 / 桌面歌词
 * / 睡眠定时收在顶栏 ⋮。
 *
 * 全屏是音乐模式内的**覆盖层**（不改 `NavigationRoot.kt` 的导航 IA）：进入靠底栏点击， 退出靠左上箭头 / 下滑手势；右滑进歌词页，歌词页左滑或返回箭头回全屏。
 */
@Composable
fun MusicNowPlayingScreen(
    queue: MusicQueue,
    lyricsState: MusicModeViewModel.LyricsUiState,
    isPlaying: Boolean,
    isBuffering: Boolean = false,
    isRestored: Boolean,
    positionMs: Long,
    durationMs: Long,
    meta: MusicSong?,
    playMode: MusicPlayMode,
    lyricsOverlayEnabled: Boolean,
    onClose: () -> Unit,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Long) -> Unit,
    onCyclePlayMode: () -> Unit,
    onToggleLyricsOverlay: () -> Unit,
    onOpenEffects: () -> Unit,
    onOpenQueue: () -> Unit,
    /** W5 T1：工具栏「上下文菜单 ⋮」→ 睡眠定时面板。 */
    onOpenSleepTimer: () -> Unit,
    /** W5 T1：点艺术家名跳艺术家详情页（按艺人名解析）。 */
    onOpenArtist: (String) -> Unit,
    /** W5 T1：宽屏右栏「队列」Tab 的队列编辑（与队列 sheet 同一份编辑链路）。 */
    onJumpQueueItem: (Int) -> Unit,
    onMoveQueueItem: (Int, Int) -> Unit,
    onRemoveQueueItem: (Int) -> Unit,
    /** W5-T3：宽屏「队列」Tab 左滑移除的撤销（与队列 sheet 同一份）。 */
    onUndoQueueItem: (Int, PlayerItem) -> Unit = { _, _ -> },
    /** W5 T5：接下来播放分区的跳播 / 移除 / 清空（宽屏「队列」Tab 与队列 sheet 同一份）。 */
    onJumpUpNextItem: (Int) -> Unit = {},
    onRemoveUpNextItem: (Int) -> Unit = {},
    onClearUpNext: () -> Unit = {},
    onToggleFavorite: (MusicSong) -> Unit,
    onSelectLyricsLanguage: (LyricsDisplayLanguage) -> Unit,
    onToggleLyricsBilingual: () -> Unit,
    onToggleLyricsFollow: () -> Unit,
    onSelectLyricsFontScale: (LyricsFontScale) -> Unit,
    onSelectLyricsLineSpacing: (LyricsLineSpacing) -> Unit,
    onSeekLyricLine: (Long) -> Unit,
) {
    val item = queue.currentItem ?: return
    val colors = LocalCinefinColors.current
    // 歌词页与全屏播放页同属一个覆盖层（W44：左滑进歌词 / 歌词页右滑返回）
    var lyricsPage by rememberSaveable(item.itemId) { mutableStateOf(false) }
    var lyricsEntryToken by rememberSaveable(item.itemId) { mutableLongStateOf(0L) }
    val openLyrics = {
        lyricsEntryToken += 1L
        lyricsPage = true
    }
    BackHandler { if (lyricsPage) lyricsPage = false else onClose() }
    // W5-T3（MUSIC-26）：进歌词 = 封面 fadeOut(200) + 歌词 fadeIn(280) + 内容下滑 16dp；出 = 反向 tween(Player)。
    val reduceMotion = CinefinMotion.reduceMotion()
    val lyricsSlidePx = with(LocalDensity.current) { 16.dp.roundToPx() }

    Box(modifier = Modifier.fillMaxSize().background(colors.surface)) {
        AnimatedContent(
            targetState = lyricsPage,
            transitionSpec = {
                when {
                    reduceMotion ->
                        fadeIn(tween(CinefinMotion.ReducedMotionMillis)) togetherWith
                            fadeOut(tween(CinefinMotion.ReducedMotionMillis))
                    targetState ->
                        (fadeIn(tween(CinefinMotion.Player)) +
                            slideInVertically(CinefinMotion.SpringControlInOffset) {
                                lyricsSlidePx
                            }) togetherWith fadeOut(tween(CinefinMotion.Fast))
                    else ->
                        fadeIn(tween(CinefinMotion.Player)) togetherWith
                            fadeOut(tween(CinefinMotion.Player))
                }
            },
            label = "musicLyricsTransition",
        ) { showLyrics ->
            if (showLyrics) {
                MusicLyricsPage(
                    title = item.name,
                    state = lyricsState,
                    entryToken = lyricsEntryToken,
                    onBack = { lyricsPage = false },
                    onSelectLanguage = onSelectLyricsLanguage,
                    onToggleBilingual = onToggleLyricsBilingual,
                    onToggleFollow = onToggleLyricsFollow,
                    onSelectFontScale = onSelectLyricsFontScale,
                    onSelectLineSpacing = onSelectLyricsLineSpacing,
                    onLineClick = onSeekLyricLine,
                    modifier =
                        Modifier.swipeGestures(
                            onSwipeRight = { lyricsPage = false },
                            onSwipeDown = { lyricsPage = false },
                        ),
                )
            } else {
                PlayerPage(
                    queue = queue,
                    lyricsState = lyricsState,
                    isPlaying = isPlaying,
                    isBuffering = isBuffering,
                    isRestored = isRestored,
                    positionMs = positionMs,
                    durationMs = durationMs,
                    meta = meta,
                    playMode = playMode,
                    lyricsOverlayEnabled = lyricsOverlayEnabled,
                    onClose = onClose,
                    onPlayPause = onPlayPause,
                    onPrevious = onPrevious,
                    onNext = onNext,
                    onSeek = onSeek,
                    onCyclePlayMode = onCyclePlayMode,
                    onToggleLyricsOverlay = onToggleLyricsOverlay,
                    onOpenEffects = onOpenEffects,
                    onOpenLyrics = openLyrics,
                    onOpenQueue = onOpenQueue,
                    onOpenSleepTimer = onOpenSleepTimer,
                    onOpenArtist = onOpenArtist,
                    onSeekLyricLine = onSeekLyricLine,
                    onToggleFavorite = onToggleFavorite,
                )
            }
        }
    }
}

@Composable
private fun PlayerPage(
    queue: MusicQueue,
    lyricsState: MusicModeViewModel.LyricsUiState,
    isPlaying: Boolean,
    isBuffering: Boolean,
    isRestored: Boolean,
    positionMs: Long,
    durationMs: Long,
    meta: MusicSong?,
    playMode: MusicPlayMode,
    lyricsOverlayEnabled: Boolean,
    onClose: () -> Unit,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Long) -> Unit,
    onCyclePlayMode: () -> Unit,
    onToggleLyricsOverlay: () -> Unit,
    onOpenEffects: () -> Unit,
    onOpenLyrics: () -> Unit,
    onOpenQueue: () -> Unit,
    onOpenSleepTimer: () -> Unit,
    onOpenArtist: (String) -> Unit,
    onSeekLyricLine: (Long) -> Unit,
    onToggleFavorite: (MusicSong) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (queue.currentItem == null) return
    val colors = LocalCinefinColors.current
    // W5-T3（MUSIC-45/46）：全屏页垂直下拉实时位移；松手 ≥18% 屏高或下甩 ≥1200dp/s 飞出关闭，否则 SpringSnap 回位。
    val reduceMotion = CinefinMotion.reduceMotion()
    var pullY by remember { mutableFloatStateOf(0f) }
    var screenHeightPx by remember { mutableIntStateOf(0) }
    val settleScope = rememberCoroutineScope()
    var settleJob by remember { mutableStateOf<Job?>(null) }
    val pullProgress = { musicPullProgress(pullY, screenHeightPx.toFloat()) }
    // pointerInput 的 key 不随回调重建（否则 500ms 位置 tick 会把进行中的下拉手势打断）
    val currentOnClose by rememberUpdatedState(onClose)
    Box(
        modifier =
            modifier
                .fillMaxSize()
                .onSizeChanged { screenHeightPx = it.height }
                .graphicsLayer { translationY = pullY }
                .pointerInput(reduceMotion) {
                    val densityScale = density
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val tracker = VelocityTracker()
                        tracker.addPosition(down.uptimeMillis, down.position)
                        var totalX = 0f
                        var totalY = 0f
                        var vertical = false
                        var horizontal = false
                        drag(down.id) { change ->
                            if (!change.pressed) return@drag
                            val delta = change.positionChange()
                            totalX += delta.x
                            totalY += delta.y
                            tracker.addPosition(change.uptimeMillis, change.position)
                            if (!vertical && !horizontal) {
                                val slop = viewConfiguration.touchSlop
                                if (abs(totalX) > slop || abs(totalY) > slop) {
                                    if (abs(totalY) >= abs(totalX)) vertical = true
                                    else horizontal = true
                                }
                            }
                            if (vertical && totalY > 0f) {
                                // 自消费：进度条 / 列表等子层已消费的拖动走不到这里（MUSIC-45 手势优先级）
                                change.consume()
                                settleJob?.cancel()
                                pullY = totalY
                            }
                        }
                        val velocity = tracker.calculateVelocity()
                        settleJob?.cancel()
                        settleJob = settleScope.launch {
                            if (vertical && pullY > 0f) {
                                val heightPx = screenHeightPx.toFloat()
                                val dismiss =
                                    musicPullDismissReached(
                                        dragYPx = pullY,
                                        screenHeightPx = heightPx,
                                        velocityDpPerSec = velocity.y / densityScale,
                                    )
                                if (dismiss) {
                                    animate(
                                        initialValue = pullY,
                                        targetValue = heightPx,
                                        animationSpec = tween(CinefinMotion.Fast),
                                    ) { value, _ ->
                                        pullY = value
                                    }
                                    currentOnClose()
                                } else {
                                    animate(
                                        initialValue = pullY,
                                        targetValue = 0f,
                                        animationSpec =
                                            if (reduceMotion) {
                                                tween(CinefinMotion.ReducedMotionMillis)
                                            } else {
                                                CinefinMotion.SpringSnap
                                            },
                                    ) { value, _ ->
                                        pullY = value
                                    }
                                }
                            }
                        }
                    }
                }
                .background(colors.surface)
    ) {
        CompositionLocalProvider(LocalMusicPullProgress provides pullProgress) {
            BoxWithConstraints(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
                when (playerLayoutMode(maxWidth, maxHeight)) {
                    // W5 T1 · MUSIC-20：宽屏（≥840dp）左右分栏——左封面+信息+进度+传输，右「歌词 | 队列」Tab
                    PlayerLayoutMode.Wide ->
                        WidePlayerLayout(
                            queue = queue,
                            lyricsState = lyricsState,
                            isPlaying = isPlaying,
                            isBuffering = isBuffering,
                            isRestored = isRestored,
                            positionMs = positionMs,
                            durationMs = durationMs,
                            meta = meta,
                            playMode = playMode,
                            lyricsOverlayEnabled = lyricsOverlayEnabled,
                            onClose = onClose,
                            onPlayPause = onPlayPause,
                            onPrevious = onPrevious,
                            onNext = onNext,
                            onSeek = onSeek,
                            onCyclePlayMode = onCyclePlayMode,
                            onToggleLyricsOverlay = onToggleLyricsOverlay,
                            onOpenEffects = onOpenEffects,
                            onOpenLyrics = onOpenLyrics,
                            onOpenQueue = onOpenQueue,
                            onOpenSleepTimer = onOpenSleepTimer,
                            onOpenArtist = onOpenArtist,
                            onSeekLyricLine = onSeekLyricLine,
                            onToggleFavorite = onToggleFavorite,
                        )
                    // 横屏手机（K60 横屏 411dp 高）走两栏紧凑布局，避免 W23 单列在矮屏被裁切
                    PlayerLayoutMode.Compact ->
                        CompactPlayerLayout(
                            queue = queue,
                            lyricsState = lyricsState,
                            isPlaying = isPlaying,
                            isBuffering = isBuffering,
                            isRestored = isRestored,
                            positionMs = positionMs,
                            durationMs = durationMs,
                            meta = meta,
                            playMode = playMode,
                            lyricsOverlayEnabled = lyricsOverlayEnabled,
                            coverSize = minOf(maxWidth * 0.34f, maxHeight * 0.52f, 240.dp),
                            onClose = onClose,
                            onPlayPause = onPlayPause,
                            onPrevious = onPrevious,
                            onNext = onNext,
                            onSeek = onSeek,
                            onCyclePlayMode = onCyclePlayMode,
                            onToggleLyricsOverlay = onToggleLyricsOverlay,
                            onOpenEffects = onOpenEffects,
                            onOpenLyrics = onOpenLyrics,
                            onOpenQueue = onOpenQueue,
                            onOpenSleepTimer = onOpenSleepTimer,
                            onToggleFavorite = onToggleFavorite,
                        )
                    else ->
                        RegularPlayerLayout(
                            queue = queue,
                            lyricsState = lyricsState,
                            isPlaying = isPlaying,
                            isBuffering = isBuffering,
                            isRestored = isRestored,
                            positionMs = positionMs,
                            durationMs = durationMs,
                            meta = meta,
                            playMode = playMode,
                            lyricsOverlayEnabled = lyricsOverlayEnabled,
                            // W5 T1：封面 min(宽 × 0.80, 360dp)（MUSIC-20 需求 78–82%）
                            coverSize = nowPlayingCoverSize(maxWidth),
                            onClose = onClose,
                            onPlayPause = onPlayPause,
                            onPrevious = onPrevious,
                            onNext = onNext,
                            onSeek = onSeek,
                            onCyclePlayMode = onCyclePlayMode,
                            onToggleLyricsOverlay = onToggleLyricsOverlay,
                            onOpenEffects = onOpenEffects,
                            onOpenLyrics = onOpenLyrics,
                            onOpenQueue = onOpenQueue,
                            onOpenSleepTimer = onOpenSleepTimer,
                            onOpenArtist = onOpenArtist,
                            onToggleFavorite = onToggleFavorite,
                        )
                }
            }
        }
    }
}

/** 常规单列布局（平板 / 竖屏手机，高 ≥ [COMPACT_PLAYER_HEIGHT]）。 */
@Composable
private fun RegularPlayerLayout(
    queue: MusicQueue,
    lyricsState: MusicModeViewModel.LyricsUiState,
    isPlaying: Boolean,
    isBuffering: Boolean,
    isRestored: Boolean,
    positionMs: Long,
    durationMs: Long,
    meta: MusicSong?,
    playMode: MusicPlayMode,
    lyricsOverlayEnabled: Boolean,
    coverSize: Dp,
    onClose: () -> Unit,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Long) -> Unit,
    onCyclePlayMode: () -> Unit,
    onToggleLyricsOverlay: () -> Unit,
    onOpenEffects: () -> Unit,
    onOpenLyrics: () -> Unit,
    onOpenQueue: () -> Unit,
    onOpenSleepTimer: () -> Unit,
    onOpenArtist: (String) -> Unit,
    onToggleFavorite: (MusicSong) -> Unit,
) {
    val item = queue.currentItem ?: return
    val colors = LocalCinefinColors.current
    val totalMs = durationMs.takeIf { it > 0L } ?: (meta?.runtimeTicks?.div(TICKS_PER_MS) ?: 0L)
    val currentMs = if (isRestored) item.playbackPosition else positionMs
    // 拖动中显示手指位置，松手才 seek（避免 500ms 位置采样把滑块拽回去）
    var dragValue by remember(item.itemId) { mutableStateOf<Float?>(null) }
    val sliderValue =
        (dragValue ?: currentMs.toFloat()).coerceIn(0f, totalMs.coerceAtLeast(0L).toFloat())
    val lyricsWindow = LyricsPresenter.window(lyricsState.rows, lyricsState.activeIndex)
    // W3-T18 · §4.5：顶栏固定、内容区可滚动（短屏自然降级）、底部辅助行固定在手势区之上。
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = CinefinSpacing.Space6),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // W5 T1 · MUSIC-19：工具栏左「收起」、右「上下文菜单 ⋮」（W3-T18 起 ⋮ 只保留播放模式 / 收藏 / 桌面歌词 / 睡眠定时）
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = CinefinSpacing.Space3),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            ExitFullscreenButton(onClose)
            PlayerContextMenu(
                playMode = playMode,
                meta = meta,
                lyricsOverlayEnabled = lyricsOverlayEnabled,
                onCyclePlayMode = onCyclePlayMode,
                onToggleFavorite = onToggleFavorite,
                onToggleLyricsOverlay = onToggleLyricsOverlay,
                onOpenSleepTimer = onOpenSleepTimer,
            )
        }
        BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
            val viewportHeight = maxHeight
            Column(
                modifier =
                    Modifier.fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .heightIn(min = viewportHeight),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceEvenly,
            ) {
                TrackCover(item = item, index = queue.currentIndex, size = coverSize)
                TrackTitles(item = item, meta = meta, compact = false, onOpenArtist = onOpenArtist)
                // W24 · C8：2–3 行歌词（当前行 ±1）——W3-T18 起仅展示，进歌词页统一走底部「歌词」键
                LyricsPreview(window = lyricsWindow, message = lyricsState.message)
                // W5-T3 · MUSIC-21：波浪进度条
                MusicWaveProgressBar(
                    value = sliderValue,
                    onValueChange = { value -> dragValue = value },
                    onValueChangeFinished = {
                        dragValue?.let { value -> onSeek(value.roundToLong()) }
                        dragValue = null
                    },
                    valueRange = 0f..totalMs.coerceAtLeast(1L).toFloat(),
                    enabled = totalMs > 0L && !isRestored,
                    isPlaying = isPlaying,
                    resetKey = item.itemId,
                    modifier = Modifier.fillMaxWidth(),
                )
                PlaybackTimeRow(
                    currentText = formatPositionMs(dragValue?.roundToLong() ?: currentMs),
                    totalText = if (totalMs > 0L) formatPositionMs(totalMs) else "--:--",
                    isBuffering = isBuffering,
                )
                TransportControls(
                    isPlaying = isPlaying,
                    onPrevious = onPrevious,
                    onPlayPause = onPlayPause,
                    onNext = onNext,
                )
            }
        }
        PlayerActionRow(
            onOpenEffects = onOpenEffects,
            onOpenLyrics = onOpenLyrics,
            onOpenQueue = onOpenQueue,
        )
        Spacer(modifier = Modifier.height(CinefinSpacing.Space4))
    }
}

/**
 * 横屏手机的紧凑两栏布局（高 < [COMPACT_PLAYER_HEIGHT]）：左封面 / 右控制。
 *
 * K60 横屏可用高度只有 411dp，W23 的单列大封面会把进度条与按钮挤出屏幕； 两栏后歌词 2–3 行、进度条、播放列表入口都保持可见。
 */
@Composable
private fun CompactPlayerLayout(
    queue: MusicQueue,
    lyricsState: MusicModeViewModel.LyricsUiState,
    isPlaying: Boolean,
    isBuffering: Boolean,
    isRestored: Boolean,
    positionMs: Long,
    durationMs: Long,
    meta: MusicSong?,
    playMode: MusicPlayMode,
    lyricsOverlayEnabled: Boolean,
    coverSize: Dp,
    onClose: () -> Unit,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Long) -> Unit,
    onCyclePlayMode: () -> Unit,
    onToggleLyricsOverlay: () -> Unit,
    onOpenEffects: () -> Unit,
    onOpenLyrics: () -> Unit,
    onOpenQueue: () -> Unit,
    onOpenSleepTimer: () -> Unit,
    onToggleFavorite: (MusicSong) -> Unit,
) {
    val item = queue.currentItem ?: return
    val colors = LocalCinefinColors.current
    val totalMs = durationMs.takeIf { it > 0L } ?: (meta?.runtimeTicks?.div(TICKS_PER_MS) ?: 0L)
    val currentMs = if (isRestored) item.playbackPosition else positionMs
    var dragValue by remember(item.itemId) { mutableStateOf<Float?>(null) }
    val sliderValue =
        (dragValue ?: currentMs.toFloat()).coerceIn(0f, totalMs.coerceAtLeast(0L).toFloat())
    val lyricsWindow = LyricsPresenter.window(lyricsState.rows, lyricsState.activeIndex)
    Box(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier =
                Modifier.fillMaxSize()
                    .padding(horizontal = CinefinSpacing.Space5, vertical = CinefinSpacing.Space2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier =
                    Modifier.weight(0.85f).fillMaxHeight().padding(top = CinefinSpacing.Space8),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                TrackCover(item = item, index = queue.currentIndex, size = coverSize)
                Spacer(modifier = Modifier.height(CinefinSpacing.Space3))
                TrackTitles(item = item, meta = meta, compact = true)
            }
            Column(
                modifier =
                    Modifier.weight(1.15f).fillMaxHeight().padding(top = CinefinSpacing.Space8),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                LyricsPreview(window = lyricsWindow, message = lyricsState.message)
                Spacer(modifier = Modifier.height(CinefinSpacing.Space2))
                MusicWaveProgressBar(
                    value = sliderValue,
                    onValueChange = { value -> dragValue = value },
                    onValueChangeFinished = {
                        dragValue?.let { value -> onSeek(value.roundToLong()) }
                        dragValue = null
                    },
                    valueRange = 0f..totalMs.coerceAtLeast(1L).toFloat(),
                    enabled = totalMs > 0L && !isRestored,
                    isPlaying = isPlaying,
                    resetKey = item.itemId,
                    modifier = Modifier.fillMaxWidth(),
                )
                PlaybackTimeRow(
                    currentText = formatPositionMs(dragValue?.roundToLong() ?: currentMs),
                    totalText = if (totalMs > 0L) formatPositionMs(totalMs) else "--:--",
                    isBuffering = isBuffering,
                )
                Spacer(modifier = Modifier.height(CinefinSpacing.Space3))
                TransportControls(
                    isPlaying = isPlaying,
                    onPrevious = onPrevious,
                    onPlayPause = onPlayPause,
                    onNext = onNext,
                )
                Spacer(modifier = Modifier.height(CinefinSpacing.Space3))
                PlayerActionRow(
                    onOpenEffects = onOpenEffects,
                    onOpenLyrics = onOpenLyrics,
                    onOpenQueue = onOpenQueue,
                )
            }
        }
        Row(
            modifier =
                Modifier.fillMaxWidth()
                    .padding(horizontal = CinefinSpacing.Space3, vertical = CinefinSpacing.Space2),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            ExitFullscreenButton(onClose)
            PlayerContextMenu(
                playMode = playMode,
                meta = meta,
                lyricsOverlayEnabled = lyricsOverlayEnabled,
                onCyclePlayMode = onCyclePlayMode,
                onToggleFavorite = onToggleFavorite,
                onToggleLyricsOverlay = onToggleLyricsOverlay,
                onOpenSleepTimer = onOpenSleepTimer,
            )
        }
    }
}

/**
 * 宽屏（≥ [WIDE_PLAYER_WIDTH]）左右分栏：左栏封面 + 信息 + 进度 + 传输（居中，最大 [WIDE_PLAYER_COLUMN_MAX] 列宽）， 右栏「歌词 |
 * 队列」Tab（默认歌词，MUSIC-20）。
 *
 * 两栏各自独立滚动（左栏 `verticalScroll`、右栏歌词/队列本身是列表），栏间只留 [CinefinSpacing.Space6]，无分隔线。
 */
@Composable
private fun WidePlayerLayout(
    queue: MusicQueue,
    lyricsState: MusicModeViewModel.LyricsUiState,
    isPlaying: Boolean,
    isBuffering: Boolean,
    isRestored: Boolean,
    positionMs: Long,
    durationMs: Long,
    meta: MusicSong?,
    playMode: MusicPlayMode,
    lyricsOverlayEnabled: Boolean,
    onClose: () -> Unit,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Long) -> Unit,
    onCyclePlayMode: () -> Unit,
    onToggleLyricsOverlay: () -> Unit,
    onOpenEffects: () -> Unit,
    onOpenLyrics: () -> Unit,
    onOpenQueue: () -> Unit,
    onOpenSleepTimer: () -> Unit,
    onOpenArtist: (String) -> Unit,
    onToggleFavorite: (MusicSong) -> Unit,
    onSeekLyricLine: (Long) -> Unit,
) {
    val item = queue.currentItem ?: return
    val colors = LocalCinefinColors.current
    val totalMs = durationMs.takeIf { it > 0L } ?: (meta?.runtimeTicks?.div(TICKS_PER_MS) ?: 0L)
    val currentMs = if (isRestored) item.playbackPosition else positionMs
    var dragValue by remember(item.itemId) { mutableStateOf<Float?>(null) }
    val sliderValue =
        (dragValue ?: currentMs.toFloat()).coerceIn(0f, totalMs.coerceAtLeast(0L).toFloat())
    var followState by
        remember(item.itemId) {
            mutableStateOf(LyricsFollowUiState().onPageEntered(lyricsState.display.follow))
        }
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = CinefinSpacing.Space6)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = CinefinSpacing.Space3),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            ExitFullscreenButton(onClose)
            PlayerContextMenu(
                playMode = playMode,
                meta = meta,
                lyricsOverlayEnabled = lyricsOverlayEnabled,
                onCyclePlayMode = onCyclePlayMode,
                onToggleFavorite = onToggleFavorite,
                onToggleLyricsOverlay = onToggleLyricsOverlay,
                onOpenSleepTimer = onOpenSleepTimer,
            )
        }
        Row(modifier = Modifier.fillMaxSize()) {
            BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxHeight()) {
                // 列宽在 BoxWithConstraints 作用域取；Column 内受 DSL marker 限制拿不到外层 maxWidth
                val columnWidth = maxWidth
                Column(
                    modifier =
                        Modifier.widthIn(max = WIDE_PLAYER_COLUMN_MAX)
                            .fillMaxHeight()
                            .align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        val viewportHeight = maxHeight
                        Column(
                            modifier =
                                Modifier.fillMaxWidth()
                                    .verticalScroll(rememberScrollState())
                                    .heightIn(min = viewportHeight),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.SpaceEvenly,
                        ) {
                            TrackCover(
                                item = item,
                                index = queue.currentIndex,
                                size = nowPlayingCoverSize(columnWidth),
                            )
                            TrackTitles(
                                item = item,
                                meta = meta,
                                compact = false,
                                onOpenArtist = onOpenArtist,
                            )
                            MusicWaveProgressBar(
                                value = sliderValue,
                                onValueChange = { value -> dragValue = value },
                                onValueChangeFinished = {
                                    dragValue?.let { value -> onSeek(value.roundToLong()) }
                                    dragValue = null
                                },
                                valueRange = 0f..totalMs.coerceAtLeast(1L).toFloat(),
                                enabled = totalMs > 0L && !isRestored,
                                isPlaying = isPlaying,
                                resetKey = item.itemId,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            PlaybackTimeRow(
                                currentText =
                                    formatPositionMs(dragValue?.roundToLong() ?: currentMs),
                                totalText =
                                    if (totalMs > 0L) formatPositionMs(totalMs) else "--:--",
                                isBuffering = isBuffering,
                            )
                            TransportControls(
                                isPlaying = isPlaying,
                                onPrevious = onPrevious,
                                onPlayPause = onPlayPause,
                                onNext = onNext,
                            )
                        }
                    }
                    PlayerActionRow(
                        onOpenEffects = onOpenEffects,
                        onOpenLyrics = onOpenLyrics,
                        onOpenQueue = onOpenQueue,
                    )
                    Spacer(modifier = Modifier.height(CinefinSpacing.Space4))
                }
            }
            Spacer(modifier = Modifier.width(CinefinSpacing.Space6))
            // W3-T18 · §4.5 单入口：右栏只保留歌词视图；队列入口统一在底部辅助行。
            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                WideLyricsPane(
                    state = lyricsState,
                    followState = followState,
                    onFollowStateChange = { next -> followState = next },
                    onLineClick = onSeekLyricLine,
                )
            }
        }
    }
}

/** 宽屏右栏歌词：已加载即有行（复用歌词页的跟随/居中滚动），空态回落加载态或「暂无歌词」提示。 */
@Composable
private fun WideLyricsPane(
    state: MusicModeViewModel.LyricsUiState,
    followState: LyricsFollowUiState,
    onFollowStateChange: (LyricsFollowUiState) -> Unit,
    onLineClick: (Long) -> Unit,
) {
    val colors = LocalCinefinColors.current
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when {
            state.loading -> CircularProgressIndicator()
            state.rows.isEmpty() ->
                Text(
                    text = state.message ?: "该曲目暂无歌词",
                    style = CinefinType.BodyMedium,
                    color = colors.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = CinefinSpacing.Space6),
                )
            else ->
                LyricsLines(
                    state = state,
                    followState = followState,
                    onFollowStateChange = onFollowStateChange,
                    onLineClick = onLineClick,
                )
        }
    }
}

@Composable
private fun ExitFullscreenButton(onClose: () -> Unit) {
    CinefinIconButton(onClick = onClose) { tint ->
        Icon(
            painter = painterResource(CoreR.drawable.ic_arrow_left),
            contentDescription = "退出全屏",
            tint = tint,
            modifier = Modifier.size(22.dp),
        )
    }
}

/**
 * 全屏播放页工具栏右侧「上下文菜单 ⋮」（W3-T18 · §4.5 单入口原则）。
 *
 * 只保留底部辅助行没有的功能：播放模式 / 收藏 / 桌面歌词 / 睡眠定时；队列与音效已收敛到底部三键，菜单不再重复。 分享占位按设计定稿一 §1 **不做**；菜单容器沿用音乐页既有
 * DropdownMenu 口径（`surfaceContainerHighest` + `Card` 圆角 + 无阴影）。
 */
@Composable
private fun PlayerContextMenu(
    playMode: MusicPlayMode,
    meta: MusicSong?,
    lyricsOverlayEnabled: Boolean,
    onCyclePlayMode: () -> Unit,
    onToggleFavorite: (MusicSong) -> Unit,
    onToggleLyricsOverlay: () -> Unit,
    onOpenSleepTimer: () -> Unit,
) {
    val colors = LocalCinefinColors.current
    var expanded by remember { mutableStateOf(false) }
    Box {
        CinefinIconButton(onClick = { expanded = true }) { tint ->
            // 与曲目行尾部「⋮」同一口径（不新增矢量图）
            Box(modifier = Modifier.clearAndSetSemantics { contentDescription = "上下文菜单" }) {
                Text(text = "⋮", style = CinefinType.TitleMedium, color = tint)
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            shape = CinefinShapes.Card,
            containerColor = colors.surfaceContainerHighest,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
        ) {
            nowPlayingMenuActions(hasSongMeta = meta != null).forEach { action ->
                when (action) {
                    NowPlayingMenuAction.PLAY_MODE ->
                        PlayerContextMenuItem(
                            icon = playMode.iconRes(),
                            label = "播放模式 · ${playMode.label}",
                            onClick = {
                                expanded = false
                                onCyclePlayMode()
                            },
                        )
                    NowPlayingMenuAction.FAVORITE ->
                        PlayerContextMenuItem(
                            icon =
                                if (meta?.isFavorite == true) CoreR.drawable.ic_bookmark_filled
                                else CoreR.drawable.ic_bookmark,
                            label = if (meta?.isFavorite == true) "取消收藏" else "收藏",
                            onClick = {
                                expanded = false
                                meta?.let(onToggleFavorite)
                            },
                        )
                    NowPlayingMenuAction.LYRIC_OVERLAY ->
                        PlayerContextMenuItem(
                            icon = CoreR.drawable.ic_smartphone,
                            label = if (lyricsOverlayEnabled) "关闭桌面歌词" else "桌面歌词",
                            onClick = {
                                expanded = false
                                onToggleLyricsOverlay()
                            },
                        )
                    NowPlayingMenuAction.SLEEP_TIMER ->
                        PlayerContextMenuItem(
                            icon = R.drawable.ic_music_sleep,
                            label = "睡眠定时",
                            onClick = {
                                expanded = false
                                onOpenSleepTimer()
                            },
                        )
                }
            }
        }
    }
}

@Composable
private fun PlayerContextMenuItem(icon: Int, label: String, onClick: () -> Unit) {
    val colors = LocalCinefinColors.current
    DropdownMenuItem(
        text = {
            Text(
                text = label,
                style = CinefinType.BodyMedium,
                color = colors.onSurface,
            )
        },
        leadingIcon = {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                tint = colors.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        },
        onClick = onClick,
    )
}

/** 封面切歌转场键（MUSIC-41）：itemId 变化即一次 Crossfade，index 决定滑入方向。 */
private data class MusicCoverKey(
    val itemId: UUID,
    val index: Int,
    val thumbnailUri: String?,
    val title: String,
)

/**
 * 大封面（W3-T18 起仅展示——歌词页入口统一为底部辅助行「歌词」）。
 *
 * W5-T3（MUSIC-41）：封面交叉淡化 `Crossfade(250)` + 新封面从切歌反侧滑入 14dp（下一首从右、上一首从左，`SpringControlIn`）；
 * W5-T3（MUSIC-46）：下拉收起时按拖幅 1 → 0.96 缩放、圆角 Card(20) → 32 线性插值——两者都在 `graphicsLayer`
 * 的绘制块里读进度，拖动逐帧只触发绘制，不整页重组。
 */
@Composable
private fun TrackCover(item: PlayerItem, index: Int, size: Dp) {
    val colors = LocalCinefinColors.current
    val reduceMotion = CinefinMotion.reduceMotion()
    val pullProgressProvider: () -> Float = LocalMusicPullProgress.current
    val density = LocalDensity.current
    val slidePx = with(density) { MusicCoverSlideDp.dp.roundToPx() }
    val borderWidthPx = with(density) { 1.dp.toPx() }
    val key =
        remember(item.itemId, index, item.thumbnailUri, item.name) {
            MusicCoverKey(item.itemId, index, item.thumbnailUri, item.name)
        }
    Box(
        modifier =
            Modifier.size(size)
                .graphicsLayer {
                    val progress = pullProgressProvider()
                    scaleX = musicPullCoverScale(progress)
                    scaleY = scaleX
                    shape = RoundedCornerShape(musicPullCoverCornerDp(progress).dp)
                    clip = true
                }
                .background(colors.surfaceContainerHigh)
                .drawWithContent {
                    drawContent()
                    val radius = musicPullCoverCornerDp(pullProgressProvider()).dp.toPx()
                    val borderSize =
                        Size(this.size.width - borderWidthPx, this.size.height - borderWidthPx)
                    drawRoundRect(
                        color = colors.glassBorder,
                        topLeft = Offset(borderWidthPx / 2f, borderWidthPx / 2f),
                        size = borderSize,
                        cornerRadius = CornerRadius(radius - borderWidthPx / 2f),
                        style = Stroke(width = borderWidthPx),
                    )
                },
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            targetState = key,
            transitionSpec = {
                if (reduceMotion) {
                    fadeIn(tween(CinefinMotion.ReducedMotionMillis)) togetherWith
                        fadeOut(tween(CinefinMotion.ReducedMotionMillis))
                } else {
                    val direction = if (targetState.index >= initialState.index) 1 else -1
                    (fadeIn(tween(MusicCoverCrossfadeMillis)) +
                        slideInHorizontally(CinefinMotion.SpringControlInOffset) {
                            direction * slidePx
                        }) togetherWith fadeOut(tween(MusicCoverCrossfadeMillis))
                }
            },
            label = "musicCoverCrossfade",
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) { cover ->
            if (cover.thumbnailUri == null) {
                Text(
                    text = cover.title.take(1),
                    style = CinefinType.DisplayMedium,
                    color = colors.onSurfaceVariant,
                )
            } else {
                AsyncImage(
                    model = cover.thumbnailUri,
                    contentDescription = cover.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
        }
    }
}

/** 曲名 / 艺术家切歌转场键（MUSIC-41）。 */
private data class MusicTitleKey(
    val itemId: UUID,
    val title: String,
    val artist: String?,
    val album: String?,
    val transcoded: Boolean,
)

/**
 * 曲名 + 艺术家 / 专辑。
 *
 * W5-T3（MUSIC-41）：切歌时曲名 `fadeIn(150)` + 下滑 8dp 淡入，艺术家行再延迟 `Stagger`(40ms) 淡入。
 */
@Composable
private fun TrackTitles(
    item: PlayerItem,
    meta: MusicSong?,
    compact: Boolean,
    onOpenArtist: ((String) -> Unit)? = null,
) {
    val reduceMotion = CinefinMotion.reduceMotion()
    val slidePx = with(LocalDensity.current) { MusicTitleSlideDp.dp.roundToPx() }
    val key =
        remember(item.itemId, item.name, meta?.artist, meta?.albumName, item.trackSource) {
            MusicTitleKey(
                itemId = item.itemId,
                title = item.name,
                artist = meta?.artist?.takeIf { it.isNotBlank() },
                album = meta?.albumName?.takeIf { it.isNotBlank() },
                transcoded = item.trackSource?.isTranscoded == true,
            )
        }
    AnimatedContent(
        targetState = key,
        transitionSpec = {
            if (reduceMotion) {
                fadeIn(tween(CinefinMotion.ReducedMotionMillis)) togetherWith
                    fadeOut(tween(CinefinMotion.ReducedMotionMillis))
            } else {
                (fadeIn(tween(MusicTitleFadeMillis)) +
                    slideInVertically(CinefinMotion.SpringControlInOffset) { slidePx }) togetherWith
                    fadeOut(tween(MusicTitleFadeMillis))
            }
        },
        label = "musicTitleChange",
    ) { title ->
        TrackTitleText(key = title, compact = compact, onOpenArtist = onOpenArtist)
    }
}

@Composable
private fun TrackTitleText(
    key: MusicTitleKey,
    compact: Boolean,
    onOpenArtist: ((String) -> Unit)?,
) {
    val colors = LocalCinefinColors.current
    val reduceMotion = CinefinMotion.reduceMotion()
    // 艺术家 / 副标题比曲名再晚 Stagger(40ms) 淡入（定稿二 §5）
    var subtitleShown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { subtitleShown = true }
    val subtitleAlpha by
        animateFloatAsState(
            targetValue = if (subtitleShown) 1f else 0f,
            animationSpec =
                if (reduceMotion) {
                    tween(CinefinMotion.ReducedMotionMillis)
                } else {
                    tween(MusicTitleFadeMillis, delayMillis = CinefinMotion.Stagger)
                },
            label = "musicArtistStagger",
        )
    Text(
        text = key.title,
        // 设计定稿一 §1：曲名 18sp / 500（紧凑横屏沿用旧档）
        style =
            if (compact) CinefinType.TitleLarge else CinefinType.TitleMedium.copy(fontSize = 18.sp),
        color = colors.onSurface,
        maxLines = if (compact) 1 else 2,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.Center,
    )
    if (compact) {
        val subtitle = listOfNotNull(key.artist, key.album).distinct().joinToString(" · ")
        if (subtitle.isNotBlank()) {
            Spacer(modifier = Modifier.height(CinefinSpacing.Space1))
            Text(
                text = subtitle,
                style = CinefinType.BodySmall,
                color = colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.graphicsLayer { alpha = subtitleAlpha },
            )
        }
        return
    }
    if (key.artist != null || key.album != null) {
        Spacer(modifier = Modifier.height(CinefinSpacing.Space1))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            // W3-T18 · §4.5：整行占满可用宽，两段文字各分权重并省略，长标题不再重叠。
            modifier = Modifier.fillMaxWidth().graphicsLayer { alpha = subtitleAlpha },
        ) {
            if (key.artist != null) {
                // 艺术家 15sp / 400，可点击跳艺术家详情（设计定稿一 §1）
                Text(
                    text = key.artist,
                    style = CinefinType.BodyMedium,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier =
                        Modifier.weight(1f, fill = false)
                            .then(
                                if (onOpenArtist != null) {
                                    Modifier.clip(CinefinShapes.Button)
                                        .clickable { onOpenArtist(key.artist) }
                                        .padding(
                                            horizontal = CinefinSpacing.Space2,
                                            vertical = CinefinSpacing.Space1,
                                        )
                                } else {
                                    Modifier
                                }
                            ),
                )
            }
            if (key.artist != null && key.album != null) {
                Text(
                    text = "·",
                    style = CinefinType.BodyMedium,
                    color = colors.onSurfaceVariant,
                )
            }
            if (key.album != null) {
                Text(
                    text = key.album,
                    style = CinefinType.BodyMedium,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
        }
    }
    // W5-T4 / MUSIC-03：非侵入式转码标注（曲目信息区一行小字，不占控件位）
    if (key.transcoded) {
        Spacer(modifier = Modifier.height(CinefinSpacing.Space1))
        Text(
            text = "转码中",
            style = CinefinType.MonoDataSmall,
            color = colors.onSurfaceVariant,
            maxLines = 1,
            textAlign = TextAlign.Center,
            modifier = Modifier.graphicsLayer { alpha = subtitleAlpha },
        )
    }
}

@Composable
private fun TransportControls(
    isPlaying: Boolean,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlayerIconButton(onClick = onPrevious) { tint ->
            Icon(
                painter = painterResource(CoreR.drawable.ic_skip_back),
                contentDescription = "上一曲",
                tint = tint,
                modifier = Modifier.size(30.dp),
            )
        }
        Spacer(modifier = Modifier.width(CinefinSpacing.Space6))
        PrimaryPlayButton(isPlaying = isPlaying, onClick = onPlayPause)
        Spacer(modifier = Modifier.width(CinefinSpacing.Space6))
        PlayerIconButton(onClick = onNext) { tint ->
            Icon(
                painter = painterResource(CoreR.drawable.ic_skip_forward),
                contentDescription = "下一首",
                tint = tint,
                modifier = Modifier.size(30.dp),
            )
        }
    }
}

/**
 * MUSIC-22 · 48dp 最小触控目标（可视仍 44dp 图标钮口径）。
 *
 * core 的 `CinefinIconButton` 可点区域就是内层 44dp（48dp 只是外层占位），本卡要求主传输三键与次级五键 ≥48dp 且 core
 * 不可改动，故在音乐模块内补这一层：视觉与 core Icon 变体同色阶（透明底 / `media.bright` 图标，按下 `media.container` 底 +
 * `media.base` 图标）。core 侧把 clickable 提到 48dp 后可删除本层。
 */
@Composable
private fun PlayerIconButton(
    onClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    content: @Composable (tint: Color) -> Unit,
) {
    val colors = LocalCinefinColors.current
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    // W3-T18 · §4.5：传输 / 功能键统一回极光（不再跟随封面取色）。
    val tint =
        when {
            !enabled -> colors.onSurfaceFaint.copy(alpha = colors.disabledAlpha)
            pressed -> colors.auroraInk
            else -> colors.aurora1
        }
    Box(
        modifier =
            modifier
                .size(48.dp)
                .clip(CinefinShapes.Button)
                .background(
                    if (pressed && enabled) {
                        colors.aurora1.copy(alpha = 0.12f)
                    } else {
                        Color.Transparent
                    }
                )
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    enabled = enabled,
                    onClick = onClick,
                ),
        contentAlignment = Alignment.Center,
    ) {
        content(tint)
    }
}

/**
 * 全屏底部辅助行（W3-T18 · §4.5）：左「音效」/ 中「歌词」/ 右「队列」——三键即三个功能的唯一入口。
 *
 * 播放模式 / 收藏 / 桌面歌词 / 睡眠定时不在本行，统一收在顶栏 ⋮ 菜单（[PlayerContextMenu]）。
 */
@Composable
private fun PlayerActionRow(
    onOpenEffects: () -> Unit,
    onOpenLyrics: () -> Unit,
    onOpenQueue: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        nowPlayingBottomActions().forEach { action ->
            when (action) {
                NowPlayingBottomAction.EFFECTS ->
                    NowPlayingAction(
                        icon = painterResource(R.drawable.ic_music_equalizer),
                        label = "音效",
                        onClick = onOpenEffects,
                    )
                NowPlayingBottomAction.LYRICS ->
                    NowPlayingAction(
                        icon = painterResource(R.drawable.ic_music_lyrics),
                        label = "歌词",
                        onClick = onOpenLyrics,
                    )
                NowPlayingBottomAction.QUEUE ->
                    NowPlayingAction(
                        icon = painterResource(CoreR.drawable.ic_playlist),
                        label = "队列",
                        onClick = onOpenQueue,
                    )
            }
        }
    }
}

private val COMPACT_PLAYER_HEIGHT = 620.dp

/** 宽屏分栏阈值（W5 T1 · 设计定稿一 §1：`maxWidth ≥ 840dp`）。 */
private val WIDE_PLAYER_WIDTH = 840.dp

/** 宽屏左栏（封面 + 信息 + 进度 + 传输）最大列宽。 */
private val WIDE_PLAYER_COLUMN_MAX = 480.dp

/** 封面边长 = `min(可用宽 × 0.80, 360dp)`（MUSIC-20：占手机宽 78–82%）。 */
internal fun nowPlayingCoverSize(availableWidth: Dp): Dp = minOf(availableWidth * 0.80f, 360.dp)

/** 全屏播放页三种布局（W5 T1）：宽屏分栏 / 横屏手机紧凑两栏 / 常规单列。 */
internal enum class PlayerLayoutMode {
    Wide,
    Compact,
    Regular,
}

/**
 * 布局选择（纯函数）：矮屏（可用高 < 620dp，含 K60 横屏 411dp）一律保留紧凑两栏；否则宽 ≥ 840dp 走宽屏分栏，其余常规单列。
 *
 * 顺序有意如此：横屏手机宽 891dp 已越过 840dp 阈值，但按设计定稿一 §1「横屏手机保留现有紧凑两栏不动」必须先命中矮屏分支。
 */
internal fun playerLayoutMode(width: Dp, height: Dp): PlayerLayoutMode =
    when {
        height < COMPACT_PLAYER_HEIGHT -> PlayerLayoutMode.Compact
        width >= WIDE_PLAYER_WIDTH -> PlayerLayoutMode.Wide
        else -> PlayerLayoutMode.Regular
    }

/** 播放模式图标（C 组）：顺序 / 列表循环 / 单曲循环 / 随机 / 随机（当前居首）。 */
private fun MusicPlayMode.iconRes(): Int =
    when (this) {
        MusicPlayMode.SEQUENTIAL -> R.drawable.ic_music_sequence
        MusicPlayMode.LIST_LOOP -> CoreR.drawable.ic_repeat
        MusicPlayMode.SINGLE_LOOP -> R.drawable.ic_music_repeat_one
        MusicPlayMode.SHUFFLE -> R.drawable.ic_music_shuffle
        // T5：Cinefin 图标族暂无「随机·当前居首」专用图标，复用随机图标并以 label 区分（交接说明已标注）。
        MusicPlayMode.SHUFFLE_CURRENT_FIRST -> R.drawable.ic_music_shuffle
    }

/** 全屏播放页的歌词预览：当前行 ±1（W3-T18 起仅展示，歌词页唯一入口 = 底部辅助行「歌词」）。 */
@Composable
private fun LyricsPreview(window: LyricsWindow, message: String?) {
    val colors = LocalCinefinColors.current
    Column(
        modifier =
            Modifier.padding(
                horizontal = CinefinSpacing.Space4,
                vertical = CinefinSpacing.Space1,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val current = window.current
        if (current == null) {
            Text(
                text = message ?: "暂无歌词",
                style = CinefinType.BodyMedium,
                color = colors.aurora1,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        } else {
            window.previous?.let { previous ->
                Text(
                    text = previous,
                    style = CinefinType.BodySmall,
                    color = colors.onSurfaceVariant.copy(alpha = 0.55f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
            }
            Text(
                text = current,
                style = CinefinType.TitleSmall.copy(fontSize = 17.sp),
                color = colors.aurora1,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
            window.next?.let { next ->
                Text(
                    text = next,
                    style = CinefinType.BodySmall,
                    color = colors.onSurfaceVariant.copy(alpha = 0.82f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/**
 * W5-T8（MUSIC-49）：进度区时间行。
 *
 * 缓冲中在当前位置前叠加 10dp 轻量指示；播放键保持「暂停」语义（缓冲不算暂停）。
 */
@Composable
private fun PlaybackTimeRow(
    currentText: String,
    totalText: String,
    isBuffering: Boolean,
) {
    val colors = LocalCinefinColors.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (isBuffering) {
                CircularProgressIndicator(
                    modifier = Modifier.size(10.dp),
                    color = colors.onSurfaceVariant,
                    strokeWidth = 1.5.dp,
                )
                Spacer(modifier = Modifier.width(CinefinSpacing.Space1))
            }
            Text(
                text = currentText,
                style = CinefinType.MonoDataSmall,
                color = colors.onSurfaceVariant,
            )
        }
        Text(
            text = totalText,
            style = CinefinType.MonoDataSmall,
            color = colors.onSurfaceVariant,
        )
    }
}

/**
 * 波浪进度条（W5-T3 · MUSIC-21，替换 W24 的细轨道版）。
 *
 * 形态（定稿二 §4）：Canvas 正弦波——播放中振幅 3dp、波长 28dp、相位 1.5 周/s 向右流动；暂停 `tween(Fast)` 压平为直线；拖拽中拇指弹性放大 1 →
 * 1.35（`SpringOvershoot`）并在手指处按 48dp 半径的高斯权重局部隆起。拖拽气泡「正在跳转到 mm:ss」在拇指上方 12dp（玻璃 chip）。 松手 seek
 * 生效，拇指停在落点等真实播放位置追平后用 `SpringSnap` 回实际位；非拖拽位置更新走 `tween(Fast)`；减少动态时振幅恒 0 且不做弹性放大（气泡保留）。
 *
 * 整条 [CinefinProgressVisuals.TouchHeight] 触控带自消费手势（且先于父层下拉收起拿到事件），因此拖进度不会误触发收起 / 翻歌词。
 */
@Composable
private fun MusicWaveProgressBar(
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    enabled: Boolean,
    isPlaying: Boolean,
    resetKey: Any,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalCinefinColors.current
    val reduceMotion = CinefinMotion.reduceMotion()
    val density = LocalDensity.current
    val span = (valueRange.endInclusive - valueRange.start).takeIf { it > 0f } ?: 1f
    val targetFraction = ((value - valueRange.start) / span).coerceIn(0f, 1f)
    // 显示位置：只在绘制块里读，逐帧变化不触发重组
    var displayFraction by remember { mutableFloatStateOf(targetFraction) }
    var lastResetKey by remember { mutableStateOf(resetKey) }
    var dragging by remember { mutableStateOf(false) }
    var settleTarget by remember { mutableStateOf<Float?>(null) }
    var barWidthPx by remember { mutableIntStateOf(0) }
    val alpha = if (enabled) 1f else CinefinProgressVisuals.DisabledAlpha

    LaunchedEffect(targetFraction, resetKey, dragging, settleTarget, reduceMotion) {
        if (dragging) return@LaunchedEffect
        val settle = settleTarget
        when {
            settle != null -> {
                // 拇指停在拖拽落点，等真实位置追平再 SpringSnap（定稿二 §4「松手 seek 生效」）
                if (musicSeekSettleReached(settle, targetFraction)) {
                    settleTarget = null
                    animate(
                        initialValue = displayFraction,
                        targetValue = targetFraction,
                        animationSpec = CinefinMotion.SpringSnap,
                    ) { v, _ ->
                        displayFraction = v
                    }
                }
            }
            resetKey != lastResetKey -> {
                // 切歌：thumb 归零后 SpringSnap 微过冲回实际位（定稿二 §5）
                lastResetKey = resetKey
                displayFraction = 0f
                if (reduceMotion) {
                    displayFraction = targetFraction
                } else {
                    animate(
                        initialValue = 0f,
                        targetValue = targetFraction,
                        animationSpec = CinefinMotion.SpringSnap,
                    ) { v, _ ->
                        displayFraction = v
                    }
                }
            }
            else ->
                animate(
                    initialValue = displayFraction,
                    targetValue = targetFraction,
                    animationSpec = tween(CinefinMotion.Fast, easing = CinefinMotion.Standard),
                ) { v, _ ->
                    displayFraction = v
                }
        }
    }
    // 落点等待超时兜底：位置流异常时不至于永远停在拖拽点
    LaunchedEffect(settleTarget) {
        val settle = settleTarget ?: return@LaunchedEffect
        delay(MusicSeekSettleTimeoutMillis)
        if (settleTarget == settle) {
            settleTarget = null
            animate(
                initialValue = displayFraction,
                targetValue = targetFraction,
                animationSpec = CinefinMotion.SpringSnap,
            ) { v, _ ->
                displayFraction = v
            }
        }
    }
    val amplitude by
        animateFloatAsState(
            targetValue = if (isPlaying && !reduceMotion) MusicWaveAmplitudeDp else 0f,
            animationSpec = tween(CinefinMotion.Fast),
            label = "musicWaveAmplitude",
        )
    val thumbScale by
        animateFloatAsState(
            targetValue = if (dragging && !reduceMotion) MusicWaveThumbDragScale else 1f,
            animationSpec = CinefinMotion.SpringOvershoot,
            label = "musicWaveThumb",
        )
    val infinitePhase = rememberInfiniteTransition(label = "musicWavePhase")
    val phaseFraction by
        infinitePhase.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec =
                infiniteRepeatable(
                    animation = tween(musicWavePeriodMillis(), easing = LinearEasing),
                    repeatMode = RepeatMode.Restart,
                ),
            label = "musicWavePhaseValue",
        )
    val wavePhase = if (isPlaying && !reduceMotion) phaseFraction else 0f

    Box(
        modifier =
            modifier.fillMaxWidth().height(CinefinProgressVisuals.TouchHeight).onSizeChanged {
                barWidthPx = it.width
            }
    ) {
        Canvas(
            modifier =
                Modifier.fillMaxSize().pointerInput(enabled, valueRange, resetKey) {
                    if (!enabled) return@pointerInput
                    val thumbRadiusPx = CinefinProgressVisuals.ThumbRadius.toPx()
                    fun fractionAt(x: Float): Float =
                        musicSeekFraction(x, thumbRadiusPx, size.width - thumbRadiusPx)
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        down.consume()
                        dragging = true
                        settleTarget = null
                        var released = fractionAt(down.position.x)
                        displayFraction = released
                        onValueChange(valueRange.start + released * span)
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) {
                                change.consume()
                                break
                            }
                            change.consume()
                            released = fractionAt(change.position.x)
                            displayFraction = released
                            onValueChange(valueRange.start + released * span)
                        }
                        dragging = false
                        settleTarget = released
                        onValueChangeFinished()
                    }
                }
        ) {
            val thumbRadiusPx = CinefinProgressVisuals.ThumbRadius.toPx()
            val trackHeightPx = CinefinProgressVisuals.TrackHeight.toPx()
            val centerY = size.height / 2f
            val startX = thumbRadiusPx
            val endX = size.width - thumbRadiusPx
            val thumbX = musicThumbX(size.width, thumbRadiusPx, displayFraction)
            if (endX <= startX) return@Canvas
            // 未播放段：细直线
            drawLine(
                color = colors.onSurface.copy(alpha = 0.12f * alpha),
                start = Offset(startX, centerY),
                end = Offset(endX, centerY),
                strokeWidth = trackHeightPx,
                cap = StrokeCap.Round,
            )
            // 已播放段：正弦波（暂停 / 减少动态时振幅为 0，压平成直线）
            if (thumbX > startX) {
                val densityScale = density.density
                val bulgeCenterDp = if (dragging) (thumbX - startX) / densityScale else null
                val wave = Path()
                wave.moveTo(
                    startX,
                    centerY +
                        musicWaveOffsetDp(
                                distanceDp = 0f,
                                phaseCycles = wavePhase,
                                amplitudeDp = amplitude,
                                bulgeCenterDp = bulgeCenterDp,
                            )
                            .dp
                            .toPx(),
                )
                val stepPx = 2.dp.toPx()
                var x = startX
                while (x < thumbX) {
                    x = (x + stepPx).coerceAtMost(thumbX)
                    val distanceDp = (x - startX) / densityScale
                    wave.lineTo(
                        x,
                        centerY +
                            musicWaveOffsetDp(
                                    distanceDp = distanceDp,
                                    phaseCycles = wavePhase,
                                    amplitudeDp = amplitude,
                                    bulgeCenterDp = bulgeCenterDp,
                                )
                                .dp
                                .toPx(),
                    )
                }
                // W3-T18 · §4.5：进度一律极光渐变（不再跟随封面取色）。
                drawPath(
                    path = wave,
                    brush = auroraGradientBrush(colors, size),
                    alpha = alpha,
                    style = Stroke(width = trackHeightPx, cap = StrokeCap.Round),
                )
            }
            // 发光拖点 + 拇指（拖拽弹性放大）
            val glowRadius = thumbRadiusPx * CinefinProgressVisuals.GlowScale * thumbScale
            drawCircle(
                brush =
                    Brush.radialGradient(
                        colors =
                            listOf(
                                colors.aurora1.copy(
                                    alpha = CinefinProgressVisuals.GlowAlpha * alpha
                                ),
                                Color.Transparent,
                            ),
                        center = Offset(thumbX, centerY),
                        radius = glowRadius,
                    ),
                radius = glowRadius,
                center = Offset(thumbX, centerY),
            )
            drawCircle(
                color = Color.White.copy(alpha = alpha),
                radius = thumbRadiusPx * thumbScale,
                center = Offset(thumbX, centerY),
            )
        }
        if (dragging) {
            // 拖拽气泡：拇指上方 12dp，玻璃 chip（colors.glass + glassBorder + Xs）
            val bubbleGapPx = with(density) { MusicSeekBubbleGapDp.dp.toPx() }
            val thumbRadiusPx = with(density) { CinefinProgressVisuals.ThumbRadius.toPx() }
            Text(
                text = musicSeekBubbleText(value.roundToLong()),
                style = CinefinType.LabelMedium,
                color = colors.onSurface,
                maxLines = 1,
                modifier =
                    Modifier.graphicsLayer {
                            val thumbX =
                                musicThumbX(barWidthPx.toFloat(), thumbRadiusPx, displayFraction)
                            translationX = thumbX - size.width / 2f
                            translationY = -size.height - bubbleGapPx
                        }
                        .clip(CinefinShapes.Xs)
                        .background(colors.glass)
                        .border(1.dp, colors.glassBorder, CinefinShapes.Xs)
                        .padding(
                            horizontal = CinefinSpacing.Space2,
                            vertical = CinefinSpacing.Space1,
                        ),
            )
        }
    }
}

/** 全屏底部功能键（图标 + 文字，44dp 图标钮 + 12sp 标签）。 */
@Composable
private fun NowPlayingAction(icon: Painter, label: String, onClick: () -> Unit) {
    val colors = LocalCinefinColors.current
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        PlayerIconButton(onClick = onClick) { tint ->
            Icon(
                painter = icon,
                contentDescription = label,
                tint = tint,
                modifier = Modifier.size(22.dp),
            )
        }
        Text(
            text = label,
            style = CinefinType.LabelSmall,
            color = colors.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

/** 主播放键（§8.7）：70dp 方圆形，`OnSurface` 填充 + `InverseOnSurface` 图标。 */
@Composable
private fun PrimaryPlayButton(isPlaying: Boolean, onClick: () -> Unit) {
    val colors = LocalCinefinColors.current
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    // W5-T3（MUSIC-40）：播 / 停脉冲 1 → 0.92 → 1（SpringOvershoot，一次往返）
    val pulse = rememberPlayPulseScale(isPlaying)
    Box(
        modifier =
            Modifier.size(70.dp)
                .graphicsLayer {
                    scaleX = pulse.value
                    scaleY = pulse.value
                }
                .clip(CinefinShapes.Xl)
                .background(
                    if (pressed) {
                        colors.onSurface.copy(alpha = 0.88f)
                    } else {
                        colors.onSurface
                    }
                )
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onClick,
                ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter =
                painterResource(if (isPlaying) CoreR.drawable.ic_pause else CoreR.drawable.ic_play),
            contentDescription = if (isPlaying) "暂停" else "播放",
            tint = colors.inverseOnSurface,
            modifier = Modifier.size(32.dp),
        )
    }
}

/**
 * 全屏播放页与歌词页的滑动手势（W44 用户确认；W3-T18 起歌词入口统一为底部辅助行「歌词」）：
 * - 全屏播放页：**下滑退出**（左右滑留空——歌词 / 队列只由底部辅助行对应键打开）；
 * - 歌词页：**右滑返回全屏播放页** / 下滑返回。
 *
 * 判定逻辑抽成纯函数 [swipeGestureDirection]；子组件（进度条、歌词滚动）先消费自己的拖动，父层检测拿不到已消费的手势， 因此拖动进度条与滚动歌词不会误触发翻页。
 */
internal fun Modifier.swipeGestures(
    onSwipeRight: (() -> Unit)? = null,
    onSwipeLeft: (() -> Unit)? = null,
    onSwipeDown: (() -> Unit)? = null,
    horizontalThresholdDp: Float = 96f,
    verticalThresholdDp: Float = 120f,
): Modifier =
    this.then(
        Modifier.pointerInput(onSwipeRight, onSwipeLeft, onSwipeDown) {
            val horizontalPx = horizontalThresholdDp.dp.toPx()
            val verticalPx = verticalThresholdDp.dp.toPx()
            var total = Offset.Zero
            detectDragGestures(
                onDragStart = { total = Offset.Zero },
                onDrag = { change, amount ->
                    change.consume()
                    total += amount
                },
                onDragEnd = {
                    when (swipeGestureDirection(total.x, total.y, horizontalPx, verticalPx)) {
                        SwipeDirection.Right -> onSwipeRight?.invoke()
                        SwipeDirection.Left -> onSwipeLeft?.invoke()
                        SwipeDirection.Down -> onSwipeDown?.invoke()
                        null -> Unit
                    }
                },
            )
        }
    )

/** 全屏播放页 / 歌词页的滑动方向。 */
internal enum class SwipeDirection {
    Right,
    Left,
    Down,
}

/**
 * 拖动位移 → 手势方向（纯函数）：
 *
 * 横向位移绝对值占优时按横向阈值判定左 / 右滑（未达阈值返回 `null`，不触发任何动作）； 否则只有向下位移达到纵向阈值才算下滑。
 */
internal fun swipeGestureDirection(
    totalX: Float,
    totalY: Float,
    horizontalThresholdPx: Float,
    verticalThresholdPx: Float,
): SwipeDirection? =
    if (abs(totalX) >= abs(totalY)) {
        when {
            totalX >= horizontalThresholdPx -> SwipeDirection.Right
            totalX <= -horizontalThresholdPx -> SwipeDirection.Left
            else -> null
        }
    } else if (totalY >= verticalThresholdPx) {
        SwipeDirection.Down
    } else {
        null
    }

/** Jellyfin runtimeTicks → 毫秒（1 tick = 100 ns）。 */
internal const val TICKS_PER_MS = 10_000L
