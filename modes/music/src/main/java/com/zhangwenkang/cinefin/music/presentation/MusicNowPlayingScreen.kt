package com.zhangwenkang.cinefin.music.presentation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
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
import com.zhangwenkang.cinefin.core.presentation.components.CinefinSegmentedControl
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinShapes
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinSpacing
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinType
import com.zhangwenkang.cinefin.core.presentation.theme.LocalCinefinColors
import com.zhangwenkang.cinefin.core.presentation.theme.LocalMediaColors
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
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * 全屏音乐播放界面（W23-MUSIC · B 组）。
 *
 * 结构对齐主流音乐播放器：左上箭头返回、大封面、歌名 / 歌手、可拖动进度条 + 时间、 上一曲 / 播放暂停 / 下一曲、播放模式 / 收藏 / 歌词入口。
 *
 * 全屏是音乐模式内的**覆盖层**（不改 `NavigationRoot.kt` 的导航 IA）：进入靠底栏点击， 退出靠左上箭头 / 下滑手势；右滑进歌词页，歌词页左滑或返回箭头回全屏。
 */
@Composable
fun MusicNowPlayingScreen(
    queue: MusicQueue,
    lyricsState: MusicModeViewModel.LyricsUiState,
    isPlaying: Boolean,
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
    // W5-T2（MUSIC-17）：封面调色板只覆盖本覆盖层子树（迷你条在 MusicModeScreen 同源缓存），不动全局色板
    val coverMediaColors =
        MusicCoverPalette.mediaColors(
            itemId = item.itemId.toString(),
            imageUri = item.thumbnailUri,
        )
    // 歌词页与全屏播放页同属一个覆盖层（W44：左滑进歌词 / 歌词页右滑返回）
    var lyricsPage by rememberSaveable(item.itemId) { mutableStateOf(false) }
    var lyricsEntryToken by rememberSaveable(item.itemId) { mutableLongStateOf(0L) }
    val openLyrics = {
        lyricsEntryToken += 1L
        lyricsPage = true
    }
    BackHandler { if (lyricsPage) lyricsPage = false else onClose() }

    CompositionLocalProvider(
        LocalMediaColors provides (coverMediaColors ?: LocalMediaColors.current)
    ) {
        Box(modifier = Modifier.fillMaxSize().background(colors.surface)) {
            if (lyricsPage) {
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
                    onJumpQueueItem = onJumpQueueItem,
                    onMoveQueueItem = onMoveQueueItem,
                    onRemoveQueueItem = onRemoveQueueItem,
                    onSeekLyricLine = onSeekLyricLine,
                    onToggleFavorite = onToggleFavorite,
                    modifier =
                        Modifier.swipeGestures(
                            onSwipeLeft = openLyrics,
                            onSwipeDown = onClose,
                        ),
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
    onJumpQueueItem: (Int) -> Unit,
    onMoveQueueItem: (Int, Int) -> Unit,
    onRemoveQueueItem: (Int) -> Unit,
    onToggleFavorite: (MusicSong) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (queue.currentItem == null) return
    val colors = LocalCinefinColors.current
    Box(modifier = modifier.fillMaxSize().background(colors.surface)) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
            when (playerLayoutMode(maxWidth, maxHeight)) {
                // W5 T1 · MUSIC-20：宽屏（≥840dp）左右分栏——左封面+信息+进度+传输，右「歌词 | 队列」Tab
                PlayerLayoutMode.Wide ->
                    WidePlayerLayout(
                        queue = queue,
                        lyricsState = lyricsState,
                        isPlaying = isPlaying,
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
                        onOpenQueue = onOpenQueue,
                        onOpenSleepTimer = onOpenSleepTimer,
                        onOpenArtist = onOpenArtist,
                        onSeekLyricLine = onSeekLyricLine,
                        onJumpQueueItem = onJumpQueueItem,
                        onMoveQueueItem = onMoveQueueItem,
                        onRemoveQueueItem = onRemoveQueueItem,
                        onToggleFavorite = onToggleFavorite,
                    )
                // 横屏手机（K60 横屏 411dp 高）走两栏紧凑布局，避免 W23 单列在矮屏被裁切
                PlayerLayoutMode.Compact ->
                    CompactPlayerLayout(
                        queue = queue,
                        lyricsState = lyricsState,
                        isPlaying = isPlaying,
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
                        onToggleFavorite = onToggleFavorite,
                    )
                else ->
                    RegularPlayerLayout(
                        queue = queue,
                        lyricsState = lyricsState,
                        isPlaying = isPlaying,
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

/** 常规单列布局（平板 / 竖屏手机，高 ≥ [COMPACT_PLAYER_HEIGHT]）。 */
@Composable
private fun RegularPlayerLayout(
    queue: MusicQueue,
    lyricsState: MusicModeViewModel.LyricsUiState,
    isPlaying: Boolean,
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
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = CinefinSpacing.Space6),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // W5 T1 · MUSIC-19：工具栏左「收起」、右「上下文菜单 ⋮」（队列 / 音效 / 睡眠定时）
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = CinefinSpacing.Space3),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            ExitFullscreenButton(onClose)
            PlayerContextMenu(
                onOpenQueue = onOpenQueue,
                onOpenEffects = onOpenEffects,
                onOpenSleepTimer = onOpenSleepTimer,
            )
        }
        Spacer(modifier = Modifier.weight(1f))
        TrackCover(item = item, size = coverSize, onClick = onOpenLyrics)
        Spacer(modifier = Modifier.height(CinefinSpacing.Space6))
        TrackTitles(item = item, meta = meta, compact = false, onOpenArtist = onOpenArtist)
        Spacer(modifier = Modifier.height(CinefinSpacing.Space3))
        // W24 · C8：2–3 行歌词（当前行 ±1），点任意一行进歌词页
        LyricsPreview(
            window = lyricsWindow,
            message = lyricsState.message,
            onClick = onOpenLyrics,
        )
        Spacer(modifier = Modifier.weight(1f))
        // W24 · C9：细轨道 + 圆形发光拖点（去掉 Material Slider 的竖线浮标）
        MusicProgressBar(
            value = sliderValue,
            onValueChange = { value -> dragValue = value },
            onValueChangeFinished = {
                dragValue?.let { value -> onSeek(value.roundToLong()) }
                dragValue = null
            },
            valueRange = 0f..totalMs.coerceAtLeast(1L).toFloat(),
            enabled = totalMs > 0L && !isRestored,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = formatPositionMs(dragValue?.roundToLong() ?: currentMs),
                style = CinefinType.MonoDataSmall,
                color = colors.onSurfaceVariant,
            )
            Text(
                text = if (totalMs > 0L) formatPositionMs(totalMs) else "--:--",
                style = CinefinType.MonoDataSmall,
                color = colors.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.height(CinefinSpacing.Space4))
        TransportControls(
            isPlaying = isPlaying,
            onPrevious = onPrevious,
            onPlayPause = onPlayPause,
            onNext = onNext,
        )
        Spacer(modifier = Modifier.height(CinefinSpacing.Space4))
        PlayerActionRow(
            playMode = playMode,
            meta = meta,
            lyricsOverlayEnabled = lyricsOverlayEnabled,
            onCyclePlayMode = onCyclePlayMode,
            onToggleFavorite = onToggleFavorite,
            onOpenQueue = onOpenQueue,
            onToggleLyricsOverlay = onToggleLyricsOverlay,
            onOpenEffects = onOpenEffects,
        )
        Spacer(modifier = Modifier.height(CinefinSpacing.Space6))
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
                TrackCover(item = item, size = coverSize)
                Spacer(modifier = Modifier.height(CinefinSpacing.Space3))
                TrackTitles(item = item, meta = meta, compact = true)
            }
            Column(
                modifier =
                    Modifier.weight(1.15f).fillMaxHeight().padding(top = CinefinSpacing.Space8),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                LyricsPreview(
                    window = lyricsWindow,
                    message = lyricsState.message,
                    onClick = onOpenLyrics,
                )
                Spacer(modifier = Modifier.height(CinefinSpacing.Space2))
                MusicProgressBar(
                    value = sliderValue,
                    onValueChange = { value -> dragValue = value },
                    onValueChangeFinished = {
                        dragValue?.let { value -> onSeek(value.roundToLong()) }
                        dragValue = null
                    },
                    valueRange = 0f..totalMs.coerceAtLeast(1L).toFloat(),
                    enabled = totalMs > 0L && !isRestored,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = formatPositionMs(dragValue?.roundToLong() ?: currentMs),
                        style = CinefinType.MonoDataSmall,
                        color = colors.onSurfaceVariant,
                    )
                    Text(
                        text = if (totalMs > 0L) formatPositionMs(totalMs) else "--:--",
                        style = CinefinType.MonoDataSmall,
                        color = colors.onSurfaceVariant,
                    )
                }
                Spacer(modifier = Modifier.height(CinefinSpacing.Space3))
                TransportControls(
                    isPlaying = isPlaying,
                    onPrevious = onPrevious,
                    onPlayPause = onPlayPause,
                    onNext = onNext,
                )
                Spacer(modifier = Modifier.height(CinefinSpacing.Space3))
                PlayerActionRow(
                    playMode = playMode,
                    meta = meta,
                    lyricsOverlayEnabled = lyricsOverlayEnabled,
                    onCyclePlayMode = onCyclePlayMode,
                    onToggleFavorite = onToggleFavorite,
                    onOpenQueue = onOpenQueue,
                    onToggleLyricsOverlay = onToggleLyricsOverlay,
                    onOpenEffects = onOpenEffects,
                )
            }
        }
        Row(
            modifier =
                Modifier.fillMaxWidth()
                    .padding(start = CinefinSpacing.Space3, top = CinefinSpacing.Space2)
        ) {
            ExitFullscreenButton(onClose)
        }
    }
}

/** 宽屏播放页右栏分页（W5 T1 · 设计定稿一 §1）。 */
private enum class WidePlayerPane(val label: String) {
    LYRICS("歌词"),
    QUEUE("队列"),
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
    onOpenSleepTimer: () -> Unit,
    onOpenArtist: (String) -> Unit,
    onToggleFavorite: (MusicSong) -> Unit,
    onSeekLyricLine: (Long) -> Unit,
    onJumpQueueItem: (Int) -> Unit,
    onMoveQueueItem: (Int, Int) -> Unit,
    onRemoveQueueItem: (Int) -> Unit,
) {
    val item = queue.currentItem ?: return
    val colors = LocalCinefinColors.current
    val totalMs = durationMs.takeIf { it > 0L } ?: (meta?.runtimeTicks?.div(TICKS_PER_MS) ?: 0L)
    val currentMs = if (isRestored) item.playbackPosition else positionMs
    var dragValue by remember(item.itemId) { mutableStateOf<Float?>(null) }
    val sliderValue =
        (dragValue ?: currentMs.toFloat()).coerceIn(0f, totalMs.coerceAtLeast(0L).toFloat())
    var pane by rememberSaveable(item.itemId) { mutableStateOf(WidePlayerPane.LYRICS) }
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
                onOpenQueue = onOpenQueue,
                onOpenEffects = onOpenEffects,
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
                            .align(Alignment.Center)
                            .verticalScroll(rememberScrollState())
                            .padding(vertical = CinefinSpacing.Space6),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    // 封面点击 = 切到歌词视图（右栏 Tab，设计定稿一 §1）
                    TrackCover(
                        item = item,
                        size = nowPlayingCoverSize(columnWidth),
                        onClick = { pane = WidePlayerPane.LYRICS },
                    )
                    Spacer(modifier = Modifier.height(CinefinSpacing.Space6))
                    TrackTitles(
                        item = item,
                        meta = meta,
                        compact = false,
                        onOpenArtist = onOpenArtist,
                    )
                    Spacer(modifier = Modifier.height(CinefinSpacing.Space5))
                    MusicProgressBar(
                        value = sliderValue,
                        onValueChange = { value -> dragValue = value },
                        onValueChangeFinished = {
                            dragValue?.let { value -> onSeek(value.roundToLong()) }
                            dragValue = null
                        },
                        valueRange = 0f..totalMs.coerceAtLeast(1L).toFloat(),
                        enabled = totalMs > 0L && !isRestored,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = formatPositionMs(dragValue?.roundToLong() ?: currentMs),
                            style = CinefinType.MonoDataSmall,
                            color = colors.onSurfaceVariant,
                        )
                        Text(
                            text = if (totalMs > 0L) formatPositionMs(totalMs) else "--:--",
                            style = CinefinType.MonoDataSmall,
                            color = colors.onSurfaceVariant,
                        )
                    }
                    Spacer(modifier = Modifier.height(CinefinSpacing.Space4))
                    TransportControls(
                        isPlaying = isPlaying,
                        onPrevious = onPrevious,
                        onPlayPause = onPlayPause,
                        onNext = onNext,
                    )
                    Spacer(modifier = Modifier.height(CinefinSpacing.Space4))
                    PlayerActionRow(
                        playMode = playMode,
                        meta = meta,
                        lyricsOverlayEnabled = lyricsOverlayEnabled,
                        onCyclePlayMode = onCyclePlayMode,
                        onToggleFavorite = onToggleFavorite,
                        onOpenQueue = onOpenQueue,
                        onToggleLyricsOverlay = onToggleLyricsOverlay,
                        onOpenEffects = onOpenEffects,
                    )
                }
            }
            Spacer(modifier = Modifier.width(CinefinSpacing.Space6))
            Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                CinefinSegmentedControl(
                    items = WidePlayerPane.entries,
                    selected = pane,
                    onSelect = { selected -> pane = selected },
                    label = { entry -> entry.label },
                    modifier = Modifier.widthIn(max = 320.dp),
                )
                Spacer(modifier = Modifier.height(CinefinSpacing.Space3))
                when (pane) {
                    WidePlayerPane.LYRICS ->
                        WideLyricsPane(
                            state = lyricsState,
                            followState = followState,
                            onFollowStateChange = { next -> followState = next },
                            onLineClick = onSeekLyricLine,
                        )
                    WidePlayerPane.QUEUE ->
                        QueueListBody(
                            queue = queue,
                            onJump = onJumpQueueItem,
                            onMove = onMoveQueueItem,
                            onRemove = onRemoveQueueItem,
                            modifier = Modifier.fillMaxSize(),
                        )
                }
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
 * 全屏播放页工具栏右侧「上下文菜单 ⋮」（W5 T1 · MUSIC-19）：队列 / 音效 / 睡眠定时三项。
 *
 * 分享占位按设计定稿一 §1 **不做**；菜单容器沿用音乐页既有 DropdownMenu 口径（`surfaceContainerHighest` + `Card` 圆角 + 无阴影）。
 */
@Composable
private fun PlayerContextMenu(
    onOpenQueue: () -> Unit,
    onOpenEffects: () -> Unit,
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
            PlayerContextMenuItem(
                icon = CoreR.drawable.ic_playlist,
                label = "播放队列",
                onClick = {
                    expanded = false
                    onOpenQueue()
                },
            )
            PlayerContextMenuItem(
                icon = R.drawable.ic_music_equalizer,
                label = "音效",
                onClick = {
                    expanded = false
                    onOpenEffects()
                },
            )
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

@Composable
private fun TrackCover(item: PlayerItem, size: Dp, onClick: (() -> Unit)? = null) {
    val colors = LocalCinefinColors.current
    Box(
        modifier =
            Modifier.size(size)
                .clip(CinefinShapes.Card)
                .background(colors.surfaceContainerHigh)
                .border(1.dp, colors.glassBorder, CinefinShapes.Card)
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        if (item.thumbnailUri == null) {
            Text(
                text = item.name.take(1),
                style = CinefinType.DisplayMedium,
                color = colors.onSurfaceVariant,
            )
        } else {
            AsyncImage(
                model = item.thumbnailUri,
                contentDescription = item.name,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
    }
}

@Composable
private fun TrackTitles(
    item: PlayerItem,
    meta: MusicSong?,
    compact: Boolean,
    onOpenArtist: ((String) -> Unit)? = null,
) {
    val colors = LocalCinefinColors.current
    val artist = meta?.artist?.takeIf { it.isNotBlank() }
    val album = meta?.albumName?.takeIf { it.isNotBlank() }
    Text(
        text = item.name,
        // 设计定稿一 §1：曲名 18sp / 500（紧凑横屏沿用旧档）
        style =
            if (compact) CinefinType.TitleLarge else CinefinType.TitleMedium.copy(fontSize = 18.sp),
        color = colors.onSurface,
        maxLines = if (compact) 1 else 2,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.Center,
    )
    if (compact) {
        val subtitle = listOfNotNull(artist, album).distinct().joinToString(" · ")
        if (subtitle.isNotBlank()) {
            Spacer(modifier = Modifier.height(CinefinSpacing.Space1))
            Text(
                text = subtitle,
                style = CinefinType.BodySmall,
                color = colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
        return
    }
    if (artist != null || album != null) {
        Spacer(modifier = Modifier.height(CinefinSpacing.Space1))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            if (artist != null) {
                // 艺术家 15sp / 400，可点击跳艺术家详情（设计定稿一 §1）
                Text(
                    text = artist,
                    style = CinefinType.BodyMedium,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier =
                        if (onOpenArtist != null) {
                            Modifier.clip(CinefinShapes.Button)
                                .clickable { onOpenArtist(artist) }
                                .padding(
                                    horizontal = CinefinSpacing.Space2,
                                    vertical = CinefinSpacing.Space1,
                                )
                        } else {
                            Modifier
                        },
                )
            }
            if (artist != null && album != null) {
                Text(
                    text = "·",
                    style = CinefinType.BodyMedium,
                    color = colors.onSurfaceVariant,
                )
            }
            if (album != null) {
                Text(
                    text = album,
                    style = CinefinType.BodyMedium,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
    // W5-T4 / MUSIC-03：非侵入式转码标注（曲目信息区一行小字，不占控件位）
    if (item.trackSource?.isTranscoded == true) {
        Spacer(modifier = Modifier.height(CinefinSpacing.Space1))
        Text(
            text = "转码中",
            style = CinefinType.MonoDataSmall,
            color = colors.onSurfaceVariant,
            maxLines = 1,
            textAlign = TextAlign.Center,
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
    val media = LocalMediaColors.current
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val tint =
        when {
            !enabled -> colors.onSurfaceFaint.copy(alpha = colors.disabledAlpha)
            pressed -> media.base
            else -> media.bright
        }
    Box(
        modifier =
            modifier
                .size(48.dp)
                .clip(CinefinShapes.Button)
                .background(if (pressed && enabled) media.container else Color.Transparent)
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
 * 全屏底部功能行：播放队列 / 播放模式 / 收藏 / 桌面歌词 / 音效（五键）。
 *
 * W56（用户 2026-10-04 拍板）：删除「歌词」入口按钮——进歌词页改由既有 ① 点歌词行进页 （[LyricsPreview]）/ ② 左滑手势承担；迷你播放条上的「词」按钮保留不动。
 */
@Composable
private fun PlayerActionRow(
    playMode: MusicPlayMode,
    meta: MusicSong?,
    lyricsOverlayEnabled: Boolean,
    onCyclePlayMode: () -> Unit,
    onToggleFavorite: (MusicSong) -> Unit,
    onOpenQueue: () -> Unit,
    onToggleLyricsOverlay: () -> Unit,
    onOpenEffects: () -> Unit,
) {
    val media = LocalMediaColors.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NowPlayingAction(
            icon = painterResource(CoreR.drawable.ic_playlist),
            label = "播放队列",
            onClick = onOpenQueue,
        )
        NowPlayingAction(
            icon = painterResource(playMode.iconRes()),
            label = playMode.label,
            active = playMode != MusicPlayMode.SEQUENTIAL,
            onClick = onCyclePlayMode,
        )
        NowPlayingAction(
            icon =
                painterResource(
                    if (meta?.isFavorite == true) CoreR.drawable.ic_bookmark_filled
                    else CoreR.drawable.ic_bookmark
                ),
            label = if (meta?.isFavorite == true) "已收藏" else "收藏",
            active = meta?.isFavorite == true,
            enabled = meta != null,
            onClick = { meta?.let(onToggleFavorite) },
        )
        NowPlayingAction(
            icon = painterResource(CoreR.drawable.ic_smartphone),
            label = "桌面歌词",
            tintOverride = if (lyricsOverlayEnabled) media.base else Color.White,
            onClick = onToggleLyricsOverlay,
        )
        NowPlayingAction(
            icon = painterResource(R.drawable.ic_music_equalizer),
            label = "音效",
            onClick = onOpenEffects,
        )
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

/** 播放模式图标（C 组）：顺序 / 列表循环 / 单曲循环 / 随机。 */
private fun MusicPlayMode.iconRes(): Int =
    when (this) {
        MusicPlayMode.SEQUENTIAL -> R.drawable.ic_music_sequence
        MusicPlayMode.LIST_LOOP -> CoreR.drawable.ic_repeat
        MusicPlayMode.SINGLE_LOOP -> R.drawable.ic_music_repeat_one
        MusicPlayMode.SHUFFLE -> R.drawable.ic_music_shuffle
    }

/** 全屏播放页的歌词预览：当前行 ±1，点任意一行进歌词页（W24 · C8）。 */
@Composable
private fun LyricsPreview(window: LyricsWindow, message: String?, onClick: () -> Unit) {
    val colors = LocalCinefinColors.current
    val media = LocalMediaColors.current
    Column(
        modifier =
            Modifier.clip(CinefinShapes.Button)
                .clickable(onClick = onClick)
                .padding(
                    horizontal = CinefinSpacing.Space4,
                    vertical = CinefinSpacing.Space1,
                ),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val current = window.current
        if (current == null) {
            Text(
                text = message ?: "点击查看歌词",
                style = CinefinType.BodyMedium,
                color = media.bright,
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
                color = media.bright,
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
 * 播放进度条（W24 · C9）：细轨道 + 圆形发光拖点，去掉 Material Slider 的竖线浮标。
 *
 * 整条 36dp 触控带都响应按下与拖动（点按 = 跳到该位置，拖动 = 连续 seek）， 消费掉自己的手势所以不会误触发全屏页的左滑呼出队列。
 */
@Composable
private fun MusicProgressBar(
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    enabled: Boolean,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalCinefinColors.current
    val media = LocalMediaColors.current
    val span = (valueRange.endInclusive - valueRange.start).takeIf { it > 0f } ?: 1f
    val fraction = ((value - valueRange.start) / span).coerceIn(0f, 1f)
    val alpha = if (enabled) 1f else CinefinProgressVisuals.DisabledAlpha
    Canvas(
        modifier =
            modifier.height(CinefinProgressVisuals.TouchHeight).pointerInput(enabled, valueRange) {
                if (!enabled) return@pointerInput
                val thumbRadiusPx = CinefinProgressVisuals.ThumbRadius.toPx()
                fun valueAt(x: Float): Float {
                    val startX = thumbRadiusPx
                    val endX = size.width - thumbRadiusPx
                    if (endX <= startX) return valueRange.start
                    val ratio = ((x - startX) / (endX - startX)).coerceIn(0f, 1f)
                    return valueRange.start + ratio * span
                }
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    onValueChange(valueAt(down.position.x))
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            change.consume()
                            onValueChangeFinished()
                            break
                        }
                        onValueChange(valueAt(change.position.x))
                        change.consume()
                    }
                }
            }
    ) {
        val thumbRadius = CinefinProgressVisuals.ThumbRadius.toPx()
        val trackHeight = CinefinProgressVisuals.TrackHeight.toPx()
        val centerY = size.height / 2f
        val startX = thumbRadius
        val endX = size.width - thumbRadius
        val thumbX = startX + (endX - startX) * fraction
        // 发光拖点：以圆形浮标为中心的一圈同色柔光（App 主题 / 音乐皮肤）
        drawCircle(
            brush =
                Brush.radialGradient(
                    colors =
                        listOf(
                            media.base.copy(alpha = CinefinProgressVisuals.GlowAlpha * alpha),
                            Color.Transparent,
                        ),
                    center = Offset(thumbX, centerY),
                    radius = thumbRadius * CinefinProgressVisuals.GlowScale,
                ),
            radius = thumbRadius * CinefinProgressVisuals.GlowScale,
            center = Offset(thumbX, centerY),
        )
        drawLine(
            color = colors.onSurface.copy(alpha = 0.12f * alpha),
            start = Offset(startX, centerY),
            end = Offset(endX, centerY),
            strokeWidth = trackHeight,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = media.base.copy(alpha = alpha),
            start = Offset(startX, centerY),
            end = Offset(thumbX, centerY),
            strokeWidth = trackHeight,
            cap = StrokeCap.Round,
        )
        drawCircle(
            color = Color.White.copy(alpha = alpha),
            radius = thumbRadius,
            center = Offset(thumbX, centerY),
        )
    }
}

/** 全屏底部功能键（图标 + 文字，44dp 图标钮 + 12sp 标签）。 */
@Composable
private fun NowPlayingAction(
    icon: Painter,
    label: String,
    onClick: () -> Unit,
    active: Boolean = false,
    enabled: Boolean = true,
    tintOverride: Color? = null,
) {
    val colors = LocalCinefinColors.current
    val media = LocalMediaColors.current
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        PlayerIconButton(onClick = onClick, enabled = enabled) { tint ->
            Icon(
                painter = icon,
                contentDescription = label,
                tint = tintOverride ?: if (active) media.base else tint,
                modifier = Modifier.size(22.dp),
            )
        }
        Text(
            text = label,
            style = CinefinType.LabelSmall,
            color =
                tintOverride
                    ?: when {
                        !enabled -> colors.onSurfaceFaint
                        active -> media.bright
                        else -> colors.onSurfaceVariant
                    },
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
    Box(
        modifier =
            Modifier.size(70.dp)
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
 * 全屏播放页与歌词页的滑动手势（W44 用户确认）：
 * - 全屏播放页：**左滑进入歌词页** / 下滑退出（右滑留空，队列只由「队列」按钮打开）；
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
