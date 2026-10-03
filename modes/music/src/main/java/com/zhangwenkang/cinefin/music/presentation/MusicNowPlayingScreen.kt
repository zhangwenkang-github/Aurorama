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
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.zhangwenkang.cinefin.core.R as CoreR
import com.zhangwenkang.cinefin.core.presentation.components.CinefinIconButton
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinShapes
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinSpacing
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinType
import com.zhangwenkang.cinefin.core.presentation.theme.LocalCinefinColors
import com.zhangwenkang.cinefin.core.presentation.theme.LocalMediaColors
import com.zhangwenkang.cinefin.music.R
import com.zhangwenkang.cinefin.music.data.MusicPlayMode
import com.zhangwenkang.cinefin.music.data.MusicSong
import com.zhangwenkang.cinefin.music.data.lyrics.LyricsDisplayLanguage
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
    onToggleFavorite: (MusicSong) -> Unit,
    onSelectLyricsLanguage: (LyricsDisplayLanguage) -> Unit,
    onToggleLyricsBilingual: () -> Unit,
    onToggleLyricsFollow: () -> Unit,
    onSeekLyricLine: (Long) -> Unit,
) {
    val item = queue.currentItem ?: return
    val colors = LocalCinefinColors.current
    // 歌词页与全屏播放页同属一个覆盖层（W44：左滑进歌词 / 歌词页右滑返回）
    var lyricsPage by rememberSaveable(item.itemId) { mutableStateOf(false) }
    BackHandler { if (lyricsPage) lyricsPage = false else onClose() }

    Box(modifier = Modifier.fillMaxSize().background(colors.surface)) {
        if (lyricsPage) {
            MusicLyricsPage(
                title = item.name,
                state = lyricsState,
                onBack = { lyricsPage = false },
                onSelectLanguage = onSelectLyricsLanguage,
                onToggleBilingual = onToggleLyricsBilingual,
                onToggleFollow = onToggleLyricsFollow,
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
                onOpenLyrics = { lyricsPage = true },
                onOpenQueue = onOpenQueue,
                onToggleFavorite = onToggleFavorite,
                modifier =
                    Modifier.swipeGestures(
                        onSwipeLeft = { lyricsPage = true },
                        onSwipeDown = onClose,
                    ),
            )
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
    onToggleFavorite: (MusicSong) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (queue.currentItem == null) return
    val colors = LocalCinefinColors.current
    Box(modifier = modifier.fillMaxSize().background(colors.surface)) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
            // 横屏手机（K60 横屏 411dp 高）走两栏紧凑布局，避免 W23 单列在矮屏被裁切
            if (maxHeight < COMPACT_PLAYER_HEIGHT) {
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
            } else {
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
                    coverSize = minOf(maxWidth * 0.58f, 320.dp),
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
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = CinefinSpacing.Space3),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ExitFullscreenButton(onClose)
        }
        Spacer(modifier = Modifier.weight(1f))
        TrackCover(item = item, size = coverSize)
        Spacer(modifier = Modifier.height(CinefinSpacing.Space6))
        TrackTitles(item = item, meta = meta, compact = false)
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
            lyricsAvailable = lyricsState.rows.isNotEmpty(),
            lyricsOverlayEnabled = lyricsOverlayEnabled,
            onCyclePlayMode = onCyclePlayMode,
            onToggleFavorite = onToggleFavorite,
            onOpenLyrics = onOpenLyrics,
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
                    lyricsAvailable = lyricsState.rows.isNotEmpty(),
                    lyricsOverlayEnabled = lyricsOverlayEnabled,
                    onCyclePlayMode = onCyclePlayMode,
                    onToggleFavorite = onToggleFavorite,
                    onOpenLyrics = onOpenLyrics,
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

@Composable
private fun TrackCover(item: PlayerItem, size: Dp) {
    val colors = LocalCinefinColors.current
    Box(
        modifier =
            Modifier.size(size)
                .clip(CinefinShapes.Xl)
                .background(colors.surfaceContainerHigh)
                .border(1.dp, colors.outline, CinefinShapes.Xl),
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
private fun TrackTitles(item: PlayerItem, meta: MusicSong?, compact: Boolean) {
    val colors = LocalCinefinColors.current
    Text(
        text = item.name,
        style = if (compact) CinefinType.TitleLarge else CinefinType.HeadlineSmall,
        color = colors.onSurface,
        maxLines = if (compact) 1 else 2,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.Center,
    )
    val subtitle =
        listOfNotNull(meta?.artist?.takeIf { it.isNotBlank() }, meta?.albumName)
            .distinct()
            .joinToString(" · ")
    if (subtitle.isNotBlank()) {
        Spacer(modifier = Modifier.height(CinefinSpacing.Space1))
        Text(
            text = subtitle,
            style = if (compact) CinefinType.BodySmall else CinefinType.BodyMedium,
            color = colors.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
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
        CinefinIconButton(onClick = onPrevious) { tint ->
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
        CinefinIconButton(onClick = onNext) { tint ->
            Icon(
                painter = painterResource(CoreR.drawable.ic_skip_forward),
                contentDescription = "下一首",
                tint = tint,
                modifier = Modifier.size(30.dp),
            )
        }
    }
}

/** 全屏底部功能行：播放队列 / 播放模式 / 收藏 / 歌词 / 桌面歌词 / 音效（六键）。 */
@Composable
private fun PlayerActionRow(
    playMode: MusicPlayMode,
    meta: MusicSong?,
    lyricsAvailable: Boolean,
    lyricsOverlayEnabled: Boolean,
    onCyclePlayMode: () -> Unit,
    onToggleFavorite: (MusicSong) -> Unit,
    onOpenLyrics: () -> Unit,
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
                    if (meta?.isFavorite == true) CoreR.drawable.ic_heart_filled
                    else CoreR.drawable.ic_heart
                ),
            label = if (meta?.isFavorite == true) "已收藏" else "收藏",
            active = meta?.isFavorite == true,
            enabled = meta != null,
            onClick = { meta?.let(onToggleFavorite) },
        )
        NowPlayingAction(
            icon = painterResource(R.drawable.ic_music_lyrics),
            label = "歌词",
            active = lyricsAvailable,
            onClick = onOpenLyrics,
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
            Modifier.clip(CinefinShapes.Sm)
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
    val alpha = if (enabled) 1f else 0.4f
    Canvas(
        modifier =
            modifier.height(PROGRESS_TOUCH_HEIGHT_DP.dp).pointerInput(enabled, valueRange) {
                if (!enabled) return@pointerInput
                val thumbRadiusPx = PROGRESS_THUMB_RADIUS_DP.dp.toPx()
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
        val thumbRadius = PROGRESS_THUMB_RADIUS_DP.dp.toPx()
        val trackHeight = PROGRESS_TRACK_HEIGHT_DP.dp.toPx()
        val centerY = size.height / 2f
        val startX = thumbRadius
        val endX = size.width - thumbRadius
        val thumbX = startX + (endX - startX) * fraction
        // 发光拖点：以圆形浮标为中心的一圈同色柔光（Prism / 音乐皮肤）
        drawCircle(
            brush =
                Brush.radialGradient(
                    colors =
                        listOf(
                            media.base.copy(alpha = 0.45f * alpha),
                            Color.Transparent,
                        ),
                    center = Offset(thumbX, centerY),
                    radius = thumbRadius * PROGRESS_GLOW_SCALE,
                ),
            radius = thumbRadius * PROGRESS_GLOW_SCALE,
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

private const val PROGRESS_TOUCH_HEIGHT_DP = 36f
private const val PROGRESS_TRACK_HEIGHT_DP = 4f
private const val PROGRESS_THUMB_RADIUS_DP = 9f
private const val PROGRESS_GLOW_SCALE = 2.6f

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
        CinefinIconButton(onClick = onClick, enabled = enabled) { tint ->
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
                .clip(CinefinShapes.Lg)
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
