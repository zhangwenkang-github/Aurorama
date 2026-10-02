package com.zhangwenkang.cinefin.music.presentation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
import com.zhangwenkang.cinefin.player.core.domain.models.MusicQueue
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
    onClose: () -> Unit,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Long) -> Unit,
    onCyclePlayMode: () -> Unit,
    onToggleFavorite: (MusicSong) -> Unit,
    onSelectLyricsLanguage: (LyricsDisplayLanguage) -> Unit,
    onToggleLyricsBilingual: () -> Unit,
    onToggleLyricsFollow: () -> Unit,
    onSeekLyricLine: (Long) -> Unit,
) {
    val item = queue.currentItem ?: return
    val colors = LocalCinefinColors.current
    // 歌词页与全屏播放页同属一个覆盖层（右滑进歌词 / 歌词页左滑返回）
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
                        onSwipeLeft = { lyricsPage = false },
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
                onClose = onClose,
                onPlayPause = onPlayPause,
                onPrevious = onPrevious,
                onNext = onNext,
                onSeek = onSeek,
                onCyclePlayMode = onCyclePlayMode,
                onOpenLyrics = { lyricsPage = true },
                onToggleFavorite = onToggleFavorite,
                modifier =
                    Modifier.swipeGestures(
                        onSwipeRight = { lyricsPage = true },
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
    onClose: () -> Unit,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Long) -> Unit,
    onCyclePlayMode: () -> Unit,
    onOpenLyrics: () -> Unit,
    onToggleFavorite: (MusicSong) -> Unit,
    modifier: Modifier = Modifier,
) {
    val item = queue.currentItem ?: return
    val colors = LocalCinefinColors.current
    val media = LocalMediaColors.current
    val totalMs = durationMs.takeIf { it > 0L } ?: (meta?.runtimeTicks?.div(TICKS_PER_MS) ?: 0L)
    val currentMs = if (isRestored) item.playbackPosition else positionMs

    // 拖动中显示手指位置，松手才 seek（避免 500ms 位置采样把滑块拽回去）
    var dragValue by remember(item.itemId) { mutableStateOf<Float?>(null) }
    val sliderValue =
        (dragValue ?: currentMs.toFloat()).coerceIn(0f, totalMs.coerceAtLeast(0L).toFloat())
    val activeLyric = lyricsState.rows.getOrNull(lyricsState.activeIndex)?.mainText

    Box(modifier = modifier.fillMaxSize().background(colors.surface)) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
            val coverSize = minOf(maxWidth * 0.58f, 320.dp)
            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = CinefinSpacing.Space6),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = CinefinSpacing.Space3),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CinefinIconButton(onClick = onClose) { tint ->
                        Icon(
                            painter = painterResource(CoreR.drawable.ic_arrow_left),
                            contentDescription = "退出全屏",
                            tint = tint,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
                Spacer(modifier = Modifier.weight(1f))
                Box(
                    modifier =
                        Modifier.size(coverSize)
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
                Spacer(modifier = Modifier.height(CinefinSpacing.Space6))
                Text(
                    text = item.name,
                    style = CinefinType.HeadlineSmall,
                    color = colors.onSurface,
                    maxLines = 2,
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
                        style = CinefinType.BodyMedium,
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(modifier = Modifier.height(CinefinSpacing.Space3))
                // 当前歌词行：点歌词文本同样进歌词页（与主流播放器一致）
                Text(
                    text = activeLyric ?: lyricsState.message ?: "点击查看歌词",
                    style = CinefinType.BodyMedium,
                    color = media.bright,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier =
                        Modifier.clip(CinefinShapes.Sm)
                            .clickable(onClick = onOpenLyrics)
                            .padding(
                                horizontal = CinefinSpacing.Space4,
                                vertical = CinefinSpacing.Space2,
                            ),
                )
                Spacer(modifier = Modifier.weight(1f))
                Slider(
                    value = sliderValue,
                    onValueChange = { value -> dragValue = value },
                    onValueChangeFinished = {
                        dragValue?.let { value -> onSeek(value.roundToLong()) }
                        dragValue = null
                    },
                    valueRange = 0f..totalMs.coerceAtLeast(1L).toFloat(),
                    enabled = totalMs > 0L && !isRestored,
                    colors =
                        SliderDefaults.colors(
                            thumbColor = colors.onSurface,
                            activeTrackColor = media.base,
                            inactiveTrackColor = colors.onSurface.copy(alpha = 0.12f),
                        ),
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
                Spacer(modifier = Modifier.height(CinefinSpacing.Space4))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
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
                        active = lyricsState.rows.isNotEmpty(),
                        onClick = onOpenLyrics,
                    )
                }
                Spacer(modifier = Modifier.height(CinefinSpacing.Space6))
            }
        }
    }
}

/** 播放模式图标（C 组）：顺序 / 列表循环 / 单曲循环 / 随机。 */
private fun MusicPlayMode.iconRes(): Int =
    when (this) {
        MusicPlayMode.SEQUENTIAL -> R.drawable.ic_music_sequence
        MusicPlayMode.LIST_LOOP -> CoreR.drawable.ic_repeat
        MusicPlayMode.SINGLE_LOOP -> R.drawable.ic_music_repeat_one
        MusicPlayMode.SHUFFLE -> R.drawable.ic_music_shuffle
    }

/** 全屏底部功能键（图标 + 文字，44dp 图标钮 + 12sp 标签）。 */
@Composable
private fun NowPlayingAction(
    icon: Painter,
    label: String,
    onClick: () -> Unit,
    active: Boolean = false,
    enabled: Boolean = true,
) {
    val colors = LocalCinefinColors.current
    val media = LocalMediaColors.current
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        CinefinIconButton(onClick = onClick, enabled = enabled) { tint ->
            Icon(
                painter = icon,
                contentDescription = label,
                tint = if (active) media.base else tint,
                modifier = Modifier.size(22.dp),
            )
        }
        Text(
            text = label,
            style = CinefinType.LabelSmall,
            color =
                when {
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
 * 全屏播放页的滑动手势：右滑进歌词 / 左滑回全屏 / 下滑退出。
 *
 * 子组件（进度条、歌词滚动）先消费自己的拖动，父层检测拿不到已消费的手势， 因此拖动进度条与滚动歌词不会误触发翻页。
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
                    if (abs(total.x) >= abs(total.y)) {
                        if (total.x >= horizontalPx) onSwipeRight?.invoke()
                        else if (total.x <= -horizontalPx) onSwipeLeft?.invoke()
                    } else if (total.y >= verticalPx) {
                        onSwipeDown?.invoke()
                    }
                },
            )
        }
    )

/** Jellyfin runtimeTicks → 毫秒（1 tick = 100 ns）。 */
internal const val TICKS_PER_MS = 10_000L
