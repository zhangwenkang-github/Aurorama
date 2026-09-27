package com.zhangwenkang.cinefin.presentation.player

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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import com.zhangwenkang.cinefin.core.R as CoreR
import com.zhangwenkang.cinefin.models.FindroidSegment
import com.zhangwenkang.cinefin.player.core.domain.models.PlayerChapter
import com.zhangwenkang.cinefin.player.core.domain.models.PLAYER_EXTRA_EPISODE_NUMBER
import com.zhangwenkang.cinefin.player.core.domain.models.PLAYER_EXTRA_SEASON_NUMBER
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

private val Ink = Color(0xFF0B0C0E)
private val SurfaceLow = Color(0xFF0F1114)
private val SurfaceHigh = Color(0xFF131518)
private val SurfaceRow = Color(0xFF17191D)
private val Hairline = Color(0xFF24282C)
private val Vermilion = Color(0xFFD2553C)
private val Paper = Color(0xFFF1EDE6)
private val Mist = Color(0xFFA8A49C)

private val ScrimTop =
    Brush.verticalGradient(listOf(Color(0xE00B0C0E), Color(0x990B0C0E), Color(0x000B0C0E)))
private val ScrimBottom =
    Brush.verticalGradient(listOf(Color(0x000B0C0E), Color(0xCC0B0C0E), Color(0xF20B0C0E)))

/** 可选倍速档位：与设置里的「长按倍速」共用同一批数值，保持一致 */
private val SpeedOptions = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 3f)

/** 睡眠定时的档位（分钟） */
private val SleepOptions = listOf(10, 20, 30, 60)

private enum class PlayerPanel {
    None,
    Speed,
    Subtitle,
    Audio,
    Info,
    Queue,
    Sleep,
}

/**
 * 控制层可见性与锁定状态。
 *
 * 由 Activity 持有：Compose 控制层渲染它，手势层（[com.zhangwenkang.cinefin.utils.PlayerGestureHelper]）
 * 通过 [toggle] 响应单击，两边共用同一份状态，不会出现「控件和手势各说各话」。
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
 * 五层结构里的第 ③④ 层：控制层与面板层。渲染层仍是 Media3 的 PlayerView / mpv Surface，
 * 手势层仍是既有的 PlayerGestureHelper（本层不接管触摸，只有控件本身消费事件）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerControlOverlay(
    player: Player,
    uiState: PlayerViewModel.UiState,
    controls: PlayerControlsState,
    isPipSupported: Boolean,
    onBack: () -> Unit,
    onPip: () -> Unit,
    onSelectSpeed: (Float) -> Unit,
    onSelectTrack: (Int, Int) -> Unit,
    onSkipSegment: (FindroidSegment) -> Unit,
    /** 把「控制层可见 / 面板打开 / 锁屏」同步给承载视图，用来决定哪些触摸留给播放器 */
    onRegionsChanged: (visible: Boolean, panelOpen: Boolean, locked: Boolean) -> Unit = { _, _, _ -> },
) {
    val runtime = rememberPlayerRuntime(player)
    var panel by remember { mutableStateOf(PlayerPanel.None) }
    var sleepMinutes by remember { mutableStateOf<Int?>(null) }
    var sleepRemaining by remember { mutableLongStateOf(0L) }
    var skipChipVisible by remember { mutableStateOf(true) }

    LaunchedEffect(controls.visible, panel, controls.locked) {
        onRegionsChanged(controls.visible, panel != PlayerPanel.None, controls.locked)
    }

    // 3.5 秒无操作自动淡出：播放中且没有面板打开时才淡出
    LaunchedEffect(controls.visible, runtime.isPlaying, panel) {
        if (controls.visible && panel == PlayerPanel.None && runtime.isPlaying) {
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

    Box(modifier = Modifier.fillMaxSize()) {
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
                    isPipSupported = isPipSupported,
                    onBack = onBack,
                    onPip = onPip,
                    onLock = { controls.setLock(true) },
                    modifier = Modifier.align(Alignment.TopCenter),
                )

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

                PlayerBottomBar(
                    positionMs = runtime.position,
                    durationMs = runtime.duration,
                    bufferedMs = runtime.buffered,
                    chapters = uiState.currentChapters,
                    trickplay = uiState.currentTrickplay,
                    speed = runtime.speed,
                    subtitleEnabled = hasSelectedTrack(runtime.tracks, C.TRACK_TYPE_TEXT),
                    sleepMinutes = sleepMinutes,
                    sleepRemainingMs = sleepRemaining,
                    onSeek = { target -> player.seekTo(target) },
                    onScrubStart = { controls.show() },
                    onOpenSpeed = { panel = PlayerPanel.Speed },
                    onOpenSubtitle = { panel = PlayerPanel.Subtitle },
                    onOpenAudio = { panel = PlayerPanel.Audio },
                    onOpenInfo = { panel = PlayerPanel.Info },
                    onOpenQueue = { panel = PlayerPanel.Queue },
                    onOpenSleep = { panel = PlayerPanel.Sleep },
                    onLock = { controls.setLock(true) },
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
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
                PlayerPanel.Speed ->
                    SpeedPanel(
                        current = runtime.speed,
                        onSelect = {
                            onSelectSpeed(it)
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

// ---------- 顶部 ----------

@Composable
private fun PlayerTopBar(
    title: String,
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
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
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
                    contentDescription =
                        stringResource(PlayerR.string.player_controls_play_pause),
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
    sleepMinutes: Int?,
    sleepRemainingMs: Long,
    onSeek: (Long) -> Unit,
    onScrubStart: () -> Unit,
    onOpenSpeed: () -> Unit,
    onOpenSubtitle: () -> Unit,
    onOpenAudio: () -> Unit,
    onOpenInfo: () -> Unit,
    onOpenQueue: () -> Unit,
    onOpenSleep: () -> Unit,
    onLock: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .background(ScrimBottom)
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
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            PlayerIconButton(
                iconRes = CoreR.drawable.ic_gauge,
                contentDescription = stringResource(PlayerR.string.select_playback_speed),
                onClick = onOpenSpeed,
            )
            PlayerIconButton(
                iconRes = CoreR.drawable.ic_closed_caption,
                contentDescription = stringResource(PlayerR.string.select_subtitle_track),
                onClick = onOpenSubtitle,
                tint = if (subtitleEnabled) Vermilion else Paper,
            )
            PlayerIconButton(
                iconRes = CoreR.drawable.ic_speaker,
                contentDescription = stringResource(PlayerR.string.select_audio_track),
                onClick = onOpenAudio,
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
                tint = if (sleepMinutes != null) Vermilion else Paper,
            )
            Spacer(Modifier.weight(1f))
            PlayerIconButton(
                iconRes = CoreR.drawable.ic_lock,
                contentDescription = stringResource(PlayerR.string.player_controls_lock),
                onClick = onLock,
            )
        }
    }
}

/**
 * 进度条：底座 4dp 发丝线 + 已缓冲段 + 朱砂已播段 + 章节刻度 + 拖动时的 Trickplay 预览。
 * 命中区做到 32dp，视觉仍只有 4dp，符合「可点目标不小于 48dp」的可操作性要求。
 */
@Composable
private fun PlayerSeekBar(
    positionMs: Long,
    durationMs: Long,
    bufferedMs: Long,
    chapters: List<PlayerChapter>,
    trickplay: Trickplay?,
    onScrubStart: () -> Unit,
    onScrub: (Long) -> Unit,
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

    Column(modifier = Modifier.fillMaxWidth()) {
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
                val fraction =
                    (chapter.startPosition.toFloat() / safeDuration).coerceIn(0f, 1f)
                Box(
                    modifier =
                        Modifier.fillMaxWidth(fraction)
                            .height(4.dp)
                            .padding(end = 0.dp),
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    Box(
                        modifier =
                            Modifier.width(2.dp)
                                .height(9.dp)
                                .background(Paper.copy(alpha = 0.55f))
                    )
                }
            }
            // 拖动手柄
            Box(
                modifier =
                    Modifier.fillMaxWidth(playedFraction),
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
private fun PlayerIconButton(
    iconRes: Int,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 44.dp,
    tint: Color = Paper,
) {
    IconButton(onClick = onClick, modifier = modifier.size(size)) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(size * 0.55f),
        )
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
        modifier =
            Modifier.fillMaxWidth().padding(vertical = 12.dp),
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

private fun formatTime(millis: Long): String {
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

private fun formatSpeed(speed: Float): String {
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
private data class QueueEntry(
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

@Composable
private fun PanelTitle(text: String) {
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
private fun PanelRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    caption: String? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier.fillMaxWidth()
                .padding(horizontal = 12.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(if (selected) Vermilion.copy(alpha = 0.14f) else Color.Transparent)
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = if (selected) Vermilion else Paper,
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
        if (selected) {
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
private fun PanelList(content: @Composable () -> Unit) {
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
        runtime.tracks?.groups
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
                format.frameRate?.takeIf { it > 0f }?.let { fps ->
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
                format.bitrate?.takeIf { it > 0 }?.let { bitrate ->
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
    var selectedSeason by remember(seasons, currentSeason) {
        mutableStateOf(currentSeason ?: seasons.firstOrNull())
    }

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
private fun queueLabel(index: Int, entry: QueueEntry): String {
    val title = entry.title.ifBlank { "—" }
    val episode = entry.episodeNumber
    return if (episode != null) {
        "E%02d  %s".format(episode, title)
    } else {
        "${index + 1}. $title"
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
