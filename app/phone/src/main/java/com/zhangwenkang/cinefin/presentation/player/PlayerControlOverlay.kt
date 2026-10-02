package com.zhangwenkang.cinefin.presentation.player

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.layout.onSizeChanged
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
import androidx.compose.ui.unit.sp
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
import com.zhangwenkang.cinefin.core.presentation.theme.LocalLumenColors
import com.zhangwenkang.cinefin.core.presentation.theme.LocalMediaColors
import com.zhangwenkang.cinefin.models.FindroidSegment
import com.zhangwenkang.cinefin.player.core.domain.models.PLAYER_EXTRA_EPISODE_NUMBER
import com.zhangwenkang.cinefin.player.core.domain.models.PLAYER_EXTRA_SEASON_NUMBER
import com.zhangwenkang.cinefin.player.core.domain.models.PlayerChapter
import com.zhangwenkang.cinefin.player.core.domain.models.PlayerMediaInfo
import com.zhangwenkang.cinefin.player.core.domain.models.SubtitleStyle
import com.zhangwenkang.cinefin.player.local.R as PlayerR
import com.zhangwenkang.cinefin.player.local.audio.AudioDelayProcessor
import com.zhangwenkang.cinefin.player.local.domain.PlayerMediaInfoFormat
import com.zhangwenkang.cinefin.player.local.domain.PlayerVideoTransform
import com.zhangwenkang.cinefin.player.local.domain.mergePlayerMediaInfo
import com.zhangwenkang.cinefin.player.local.presentation.PlayerViewModel
import com.zhangwenkang.cinefin.player.local.presentation.readKernelMediaInfo
import com.zhangwenkang.cinefin.player.local.subtitle.PlayerSubtitleController
import com.zhangwenkang.cinefin.settings.domain.Constants
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
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

/**
 * 覆盖层控件「玻璃框」的不透明度（W12 反馈 A）。
 *
 * 用户要求比 W11 更透、框只包住图标与文字并带描边：底色从 0.45 降到 0.28（按下 0.44）， 未选中的描边用极淡月白（0.16）——描边只负责在亮画面上勾出边界，不做色块（§2.6
 * 第 1 条）。
 */
private const val PLAYER_GLASS_ALPHA = 0.28f
private const val PLAYER_GLASS_PRESSED_ALPHA = 0.44f
private const val PLAYER_GLASS_BORDER_ALPHA = 0.16f

/**
 * 「图标 + 小字」工具键在纯图标键框基础上加宽 / 加高的尺寸（W17 反馈⑤）。
 *
 * 加字后键框从 44×44 变成 56×58（窄屏 38×38 不加字），最窄的「画中画」也不挤； 图标缩到 iconSize 的 0.82，给 10sp 小字让出一行高度。
 */
private const val PLAYER_TOOL_KEY_LABEL_WIDTH_EXTRA_DP = 12f
private const val PLAYER_TOOL_KEY_LABEL_HEIGHT_EXTRA_DP = 14f
private const val PLAYER_TOOL_KEY_LABEL_FONT_SIZE_SP = 10f

/**
 * 1× 徽标（W14 起文本自适应）在底栏宽度预算里的最宽估值（dp）：labelSmall 11sp 下最宽档为「0.25×」， 含 Space2 × 2 内边距与 1dp 描边 ≈
 * 48dp。只用于 [PlayerControlSpec.bottomRowWidthDp] 的兜底估算。
 */
private const val PLAYER_SPEED_BADGE_WIDTH_DP = 48f

internal enum class PlayerPanel {
    None,
    Speed,
    Repeat,
    Subtitle,
    Audio,
    Aspect,
    Info,
    Queue,
    Sleep,
    /** 播放页设置：播放 / 解码 / 字幕 / 音频 / 画面 / 手势 六组（§1.9） */
    Settings,
    /** 解码面板（W12 反馈 B）：内核切换 + 硬解 / 软解策略，独立入口 */
    Decode,
    /** 码率面板（W12 反馈 B）：服务器转码档位，独立入口 */
    Bitrate,
}

/**
 * 控制层版式规格（W12 终版布局 + 反馈 A 控件样式统一）。
 *
 * 终版（用户确认，勿再变动）：进度条一行 = 当前时间 · 进度条 · 总时长；进度条下方左侧 6 键 = 音轨 / 字幕 / 倍率 / 码率 / 解码 / 详细信息，右侧 = 全屏键；右上角 5
 * 键 = 画中画 / 睡眠 / 选集 / 画面 / 设置；锁定键贴画面区右缘垂直居中；中央五键居中。
 *
 * 窄屏（<600dp，手机 / 分屏 / 小窗）整体收一档，保证工具行 + 全屏键一行不越界（极窄窗由横向滚动兜底）。
 *
 * W17 起工具行**6 键恒定齐全**（见 [playerToolLabelsVisible]）：全屏 / 宽度充足 = 图标下加小字； 窄屏 / Compact =
 * 只留图标。倍率键只显示图标，当前值由 1× 徽标显示。
 */
internal data class PlayerControlSpec(
    /** 窄屏（<600dp）收一档 */
    val narrow: Boolean,
    /** 图标键边长（dp）；倍率键与它同宽 */
    val toolKeySizeDp: Float,
    /** 图标键内图标尺寸（dp）——倍率键的图标同宽（W12 反馈 A） */
    val iconSizeDp: Float,
    /** 键间距（dp） */
    val keyGapDp: Float,
    /** 工具行左右留白（dp） */
    val toolRowPaddingDp: Float,
) {
    /**
     * 底栏一行 = 6 个工具键 + 1× 徽标 + 右下全屏键 + 8 个间距（含全屏键前的留白，W13 反馈③新增 1×）。 1× 徽标 W14
     * 起按文本自适应（不再占一个键框），按最宽档估值计；极端窄窗由工具行的横向滚动兜底。
     */
    val bottomRowWidthDp: Float
        get() = bottomRowWidthDp(showLabels = false)

    /** W17：底栏一行宽度预算。[showLabels] = true 时 6 个工具键按「图标 + 小字」的加宽键框计 （全屏键恒为纯图标，不参与加宽）。 */
    fun bottomRowWidthDp(showLabels: Boolean): Float {
        val toolKeyWidth =
            if (showLabels) {
                toolKeySizeDp + PLAYER_TOOL_KEY_LABEL_WIDTH_EXTRA_DP
            } else {
                toolKeySizeDp
            }
        return toolKeyWidth * 6f +
            toolKeySizeDp +
            maxOf(toolKeyWidth, PLAYER_SPEED_BADGE_WIDTH_DP) +
            keyGapDp * 8f
    }
}

internal fun playerControlSpec(widthDp: Float): PlayerControlSpec {
    val narrow = widthDp < 600f
    return if (narrow) {
        PlayerControlSpec(
            narrow = true,
            toolKeySizeDp = 38f,
            iconSizeDp = 22f,
            keyGapDp = 6f,
            toolRowPaddingDp = 12f,
        )
    } else {
        PlayerControlSpec(
            narrow = false,
            toolKeySizeDp = 44f,
            iconSizeDp = 24f,
            keyGapDp = 8f,
            toolRowPaddingDp = 20f,
        )
    }
}

/**
 * 进度条下方左侧 6 键的**固定顺序**（W12 终版布局，勿再变动）：音轨 · 字幕 · 倍率 · 码率 · 解码 · 详细信息。
 *
 * 抽成有序表是为了让「顺序」可被单测钉住——这一版布局是多轮确认的最终版，改顺序就是回归。
 */
internal enum class PlayerBottomKey {
    Audio,
    Subtitle,
    Speed,
    Bitrate,
    Decode,
    Info,
}

internal val PLAYER_BOTTOM_KEY_ORDER: List<PlayerBottomKey> =
    listOf(
        PlayerBottomKey.Audio,
        PlayerBottomKey.Subtitle,
        PlayerBottomKey.Speed,
        PlayerBottomKey.Bitrate,
        PlayerBottomKey.Decode,
        PlayerBottomKey.Info,
    )

/**
 * 工具区「宽度充足」的阈值（W17 沿用 W13 的 600dp）：与 [playerControlSpec] 的窄屏档、 `PlayerFormFactor` 的 Phone → Tablet
 * 分档同源。低于它的非全屏窗口 = 「窄屏」，工具键只留图标； 达到它（或全屏 / 平板 / 折叠）才在图标下加文字。
 */
internal const val PLAYER_TOOL_ROW_WIDE_WIDTH_DP = 600f

/**
 * 工具区（右上 5 键 + 左下 6 键）是否在图标下显示小字（W17 用户确认）。
 *
 * 判据：Compact（自由窗口 / 分屏窄宽）一律只留图标；全屏（`PlayerActivity.fullscreenMode`）一律显示； 非全屏时宽度 ≥ 600dp 或平板 /
 * 折叠展开形态显示。非全屏手机形态（窄屏）只留图标—— 38dp 键框放不下文字，硬塞会撑破底栏。
 *
 * 键本身（6 键）永远都在，文字只是可读性增强：W17 起「码率 / 解码」不再从工具行隐藏， 因此设置面板里 W13 加的两行兜底入口已删除（用户确认不留兜底入口）。
 */
internal fun playerToolLabelsVisible(
    isFullscreen: Boolean,
    widthDp: Float,
    formFactor: PlayerFormFactor,
    /** Compact 骨架（自由窗口 / 分屏窄宽）：无论宽度一律不加文字 */
    isCompact: Boolean = false,
): Boolean =
    !isCompact &&
        (isFullscreen ||
            widthDp >= PLAYER_TOOL_ROW_WIDE_WIDTH_DP ||
            formFactor == PlayerFormFactor.Tablet ||
            formFactor == PlayerFormFactor.Foldable)

/** 右上角 5 键的**固定顺序**（W12 终版布局，勿再变动）：画中画 · 睡眠 · 选集 · 画面 · 设置。 */
internal enum class PlayerTopKey {
    Pip,
    Sleep,
    Episode,
    Aspect,
    Settings,
}

internal val PLAYER_TOP_KEY_ORDER: List<PlayerTopKey> =
    listOf(
        PlayerTopKey.Pip,
        PlayerTopKey.Sleep,
        PlayerTopKey.Episode,
        PlayerTopKey.Aspect,
        PlayerTopKey.Settings,
    )

/** 返回键该做什么（W11 反馈⑦）。 */
internal enum class PlayerBackAction {
    /** 没有面板打开：交给系统处理（退出播放页） */
    Ignore,
    /** 一级面板：直接关掉，留在播放页 */
    ClosePanel,
    /** 子面板：先回上一级面板（与抽屉左上角的返回箭头同一套逻辑） */
    BackToParentPanel,
    /** 覆盖层选集栏打开（W12 反馈 C）：先收起它，留在播放页 */
    CloseSidePanel,
}

/**
 * 播放页返回键优先级（W11 反馈⑦ + W12 反馈 C）。
 *
 * 面板 / 覆盖层打开时**先关面板**：子面板回上一级、一级面板直接收起、覆盖层选集栏先收栏，都不退出播放页； 只有没有任何面板时，返回键才交回系统（真正退出播放）。
 * 抽成纯函数是为了让「顺序」可被单测钉住——它曾经完全没有拦截，按返回直接退 Activity。
 */
internal fun resolvePlayerBack(
    panelOpen: Boolean,
    hasParentPanel: Boolean,
    /** 覆盖层选集栏（平板 / 折叠展开）是否打开：它是最后一道「先关面板」 */
    sidePanelOpen: Boolean = false,
): PlayerBackAction =
    when {
        // 子面板：先回上一级（与抽屉左上角的返回箭头同一套逻辑）
        panelOpen && hasParentPanel -> PlayerBackAction.BackToParentPanel
        // 一级面板：直接关掉
        panelOpen -> PlayerBackAction.ClosePanel
        // 没有任何抽屉面板、但覆盖层选集栏开着：先收栏，别退出播放页
        sidePanelOpen -> PlayerBackAction.CloseSidePanel
        else -> PlayerBackAction.Ignore
    }

/** 中央播放簇的键尺寸（W11 反馈③⑧）。 */
internal data class PlayerCenterSpec(
    val playSizeDp: Float,
    val transportSizeDp: Float,
    val gapDp: Float,
) {
    /** 簇总宽 = 播放键 + 4 个次级传输键 + 4 个间距 */
    val totalWidthDp: Float
        get() = playSizeDp + transportSizeDp * 4f + gapDp * 4f
}

/**
 * 中央簇按画面区宽度收放（W11 反馈③⑧）：锁定键固定在画面区**右缘垂直居中**， 两者都占中部空间；窗口越窄越要先收中央簇的键尺寸，否则窄窗里两个控件会叠在一起 （Pad 5 `wm
 * 800x2400` 实测：不收尺寸时簇右端与锁定键重叠 26dp）。
 *
 * 非重叠条件：簇宽 ≤ 画面区宽 − 2×(锁定键 48dp + 留白 12dp)。
 */
internal fun playerCenterSpec(videoWidthDp: Float): PlayerCenterSpec =
    when {
        videoWidthDp >= 600f -> PlayerCenterSpec(70f, 46f, 12f) // 302dp
        videoWidthDp >= 420f -> PlayerCenterSpec(62f, 42f, 10f) // 266dp
        videoWidthDp >= 360f -> PlayerCenterSpec(56f, 38f, 8f) // 240dp
        else -> PlayerCenterSpec(40f, 26f, 4f) // 160dp：极窄窗（多为小窗 / 分屏）最后一档
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

    /** 队列快照对应的 mediaId 序列：队列增删 / 拖拽排序后用它判断要不要重建列表（§1.7） */
    private var queueMediaIds: List<String> = emptyList()

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
        val ids =
            (0 until player.mediaItemCount).map { index -> player.getMediaItemAt(index).mediaId }
        if (ids != queueMediaIds) {
            queueMediaIds = ids
            queueEntries =
                ids.indices.map { index ->
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
    /** 播放页设置面板（§1.9）：六组设置的读写器由 Activity 持有 */
    settingsController: PlayerSettingsController,
    /** 画面调整（§1.6）当前状态：旋转 / 镜像 / 裁剪 / 去黑边 */
    videoTransform: PlayerVideoTransform,
    /** 切播放内核（ExoPlayer ⇄ mpv）：由 Activity 走「重启播放页 + 续播」的既有路径 */
    onSelectBackend: (String) -> Unit,
    /** 解码策略（硬解优先 / 仅软解）：由 Activity 落偏好并重启播放页让两个内核重新创建实例（W12 反馈 B） */
    onSelectDecodeMode: (String) -> Unit = {},
    /** 失败自动回退开关（W19）：开 = 既有回退链；关 = 强制所选内核，失败只提示错误 */
    onAutoFallbackChange: (Boolean) -> Unit = {},
    /** 码率档位（自动 / 原始画质 / 具体 Mbps）：由 Activity 落偏好并重启播放页重新拉取播放信息（W12 反馈 B） */
    onSelectBitrate: (Long) -> Unit = {},
    /** mpv 换硬件解码：即时写 mpv 属性 */
    onSelectMpvHwdec: (String) -> Unit,
    /** 字幕模式变化：ViewModel 立即重选字幕 */
    onSubtitleModeChanged: (String) -> Unit,
    /** 画面调整变化：写入偏好并立即应用到画面输出 */
    onVideoTransformChanged: (PlayerVideoTransform) -> Unit,
    /** 队列整理（§1.7）：拖拽排序 / 移除单条 / 清空待播 */
    onQueueMove: (Int, Int) -> Unit = { _, _ -> },
    onQueueRemove: (Int) -> Unit = {},
    onQueueClear: () -> Unit = {},
    /** W20（§1.11 Trickplay）：按位置取预览图；未命中会触发后台按需拉取并返回 null。 传 null 时进度条不做任何预览查询（旧行为里的「没有图」）。 */
    trickplayFrameAt: (Long) -> Bitmap? = { null },
    /** 进度条是否显示章节刻度（§1.9 设置面板「播放」组；关掉后不画刻度） */
    showChapterMarkers: Boolean = true,
    /** 循环模式附加项「播完暂停」变化 */
    onPauseAfterCurrentItemChanged: (Boolean) -> Unit = {},
    /** 播放失败后的「重试」：清错误并从当前进度重新拉流 */
    onRetry: () -> Unit,
    /** 「换内核」：ExoPlayer ⇄ mpv（由 Activity 写偏好并重启播放页生效） */
    onSwitchBackend: (String) -> Unit,
    /**
     * 把「控制层可见 / 面板打开 / 锁屏 / 错误卡片 / 片头片尾提示条可见」同步给承载视图： 前四项用来决定哪些触摸留给播放器，最后一项（W20）用来决定整层 ComposeView
     * 是否保持合成—— 控制层 3.5 秒淡出时提示条不能跟着消失（§6.1）。
     */
    onRegionsChanged:
        (
            visible: Boolean,
            panelOpen: Boolean,
            locked: Boolean,
            errorVisible: Boolean,
            buffering: Boolean,
            skipChipVisible: Boolean,
        ) -> Unit =
        { _, _, _, _, _, _ ->
        },
    /** 实测顶栏 / 底栏高度（px）回传给命中区：控件高度一变，触摸分区跟着变（§9 踩坑） */
    onTopBarHeight: (Int) -> Unit = {},
    onBottomBarHeight: (Int) -> Unit = {},
    /** 实测中央簇尺寸（px）回传给命中区：窄窗收尺寸后命中块跟着收，画面其余部分仍留给手势 */
    onCenterClusterSize: (Int, Int) -> Unit = { _, _ -> },
    /** 全屏（W11 反馈⑥）：收起常驻内容栏 + 强制横屏；同一个键按状态换图标 */
    isFullscreen: Boolean = false,
    onToggleFullscreen: () -> Unit = {},
) {
    val runtime = rememberPlayerRuntime(player)
    var panel by remember { mutableStateOf(PlayerPanel.None) }
    // 抽屉退场动画期间保留最后一个面板的内容，避免「滑走的是一块空板」
    var lastPanel by remember { mutableStateOf(PlayerPanel.Speed) }
    // 从「更多」进的子面板给一个返回箭头，回到「更多」；从主界面工具键进的没有上一级
    var panelBackTarget by remember { mutableStateOf<PlayerPanel?>(null) }
    LaunchedEffect(panel) { if (panel != PlayerPanel.None) lastPanel = panel }
    // 打开期间直接用当前面板（首帧不闪「更多」）；关闭后交给 lastPanel 走退场动画
    val drawerPanel = if (panel == PlayerPanel.None) lastPanel else panel
    var aspect by remember { mutableStateOf(AspectMode.of(initialResizeMode)) }
    var sleepMinutes by remember { mutableStateOf<Int?>(null) }
    var sleepRemaining by remember { mutableLongStateOf(0L) }
    var skipChipVisible by remember { mutableStateOf(true) }

    LaunchedEffect(
        controls.visible,
        panel,
        controls.locked,
        uiState.playerError,
        runtime.isBuffering,
        skipChipVisible,
    ) {
        onRegionsChanged(
            controls.visible,
            panel != PlayerPanel.None,
            controls.locked,
            uiState.playerError != null,
            // 缓冲期间承载视图要保持合成：控件整层隐藏时还要画那一个独立的缓冲圈（反馈⑤）
            runtime.isBuffering,
            // W20（§6.1）：跳过提示条独立于控制层，可见时承载视图必须保持合成
            skipChipVisible && uiState.currentSegment != null && !controls.locked,
        )
    }

    /*
     * 返回键（W11 反馈⑦ + W12 反馈 C）：面板 / 覆盖层选集栏打开时先关，不能直接退出播放页。
     * 子面板（「播放设置 → 循环模式」这类）先回上一级，与抽屉左上角的返回箭头同一套优先级（纯函数 + 单测）。
     */
    val sidePanelOnScreen = layout.hasSideContent && sidePanelExpanded
    val backAction =
        resolvePlayerBack(
            panelOpen = panel != PlayerPanel.None,
            hasParentPanel = panelBackTarget != null,
            sidePanelOpen = sidePanelOnScreen,
        )
    BackHandler(enabled = backAction != PlayerBackAction.Ignore) {
        when (backAction) {
            PlayerBackAction.BackToParentPanel -> {
                panel = panelBackTarget ?: PlayerPanel.None
                // 回上一级后必须清掉标记：否则下一次返回还会被判定成「回上一级」，面板关不掉（Pad 5 真机踩到）
                panelBackTarget = null
            }
            PlayerBackAction.ClosePanel -> {
                panel = PlayerPanel.None
                panelBackTarget = null
            }
            // 覆盖层选集栏：收栏并让控制层保持可见，用户能接着操作，不退出播放页
            PlayerBackAction.CloseSidePanel -> {
                onToggleSidePanel()
                controls.show()
            }
            PlayerBackAction.Ignore -> Unit
        }
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

    /*
     * 片头/片尾按钮：跟当前片段走，超时后自动收起。
     * W20（§6.1）：超时时长读设置里的「跳过按钮显示时长」（uiState.skipChipDurationMs），
     * 不再写死 8 秒；改设置后新的片段按新阈值收起。
     */
    LaunchedEffect(uiState.currentSegment, uiState.skipChipDurationMs) {
        if (uiState.currentSegment != null) {
            skipChipVisible = true
            delay(uiState.skipChipDurationMs.coerceAtLeast(1000L))
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
    /*
     * W17 反馈⑥：平板 / 折叠展开的选集栏是覆盖在画面右缘的 320dp 面板（W12 反馈 C），
     * 底栏若仍铺满整窗，右下角的全屏键会被面板盖住——真机实测点它没有任何反应
     * （「横屏退出全屏偶发无效」的层级拦截根因）。这里让底栏在侧栏展开时整体让出侧栏宽度，
     * 全屏键落到面板左侧，始终可见可点；全屏态侧栏收起，底栏仍铺满整窗。
     */
    val sidePanelInset =
        if (layout.hasSideContent && sidePanelExpanded) layout.sidePanelWidthDp.dp else 0.dp
    /*
     * W17 反馈⑤：工具区（右上 5 键 + 左下 6 键）是否在图标下加小字。
     * Compact 一律只留图标；全屏 / 宽度 ≥600dp / 平板·折叠显示；非全屏手机形态（窄屏）只留图标。
     */
    val toolLabelsVisible =
        playerToolLabelsVisible(
            isFullscreen = isFullscreen,
            widthDp = layout.windowWidthDp.toFloat(),
            formFactor = layout.formFactor,
            isCompact = layout.isCompact,
        )
    // 面板导航：从「播放设置」进的子面板保留上一级，抽屉左上角的返回箭头回到那里；
    // 从主界面工具键进的是一级面板，没有返回箭头（只有关闭）。
    val navigatePanel: (PlayerPanel) -> Unit = { target ->
        panelBackTarget = if (panel == PlayerPanel.Settings) panel else null
        panel = target
    }
    val bottomBar: @Composable (Modifier, Brush?, PlayerControlSpec) -> Unit =
        { barModifier, scrim, spec ->
            PlayerBottomBar(
                positionMs = runtime.position,
                durationMs = runtime.duration,
                bufferedMs = runtime.buffered,
                chapters = if (showChapterMarkers) uiState.currentChapters else emptyList(),
                trickplayIntervalMs = uiState.trickplayIntervalMs,
                trickplayVersion = uiState.trickplayVersion,
                trickplayFrameAt = trickplayFrameAt,
                speed = runtime.speed,
                sleepActive = sleepRemaining > 0L,
                subtitleEnabled = hasSelectedTrack(runtime.tracks, C.TRACK_TYPE_TEXT),
                bitrateActive = settingsController.state.streamingBitrate > 0L,
                decodeActive =
                    settingsController.state.decodeMode == PlayerViewModel.DECODE_MODE_SOFTWARE ||
                        settingsController.state.backend == PlayerViewModel.PLAYER_BACKEND_MPV,
                spec = spec,
                isFullscreen = isFullscreen,
                // W17：6 键恒定齐全；文字只在窄屏以外的形态出现
                showLabels = toolLabelsVisible,
                onSeek = { target -> player.seekTo(target) },
                onScrubStart = { controls.show() },
                onAudio = { navigatePanel(PlayerPanel.Audio) },
                onSubtitle = { navigatePanel(PlayerPanel.Subtitle) },
                onSpeed = { navigatePanel(PlayerPanel.Speed) },
                onBitrate = { navigatePanel(PlayerPanel.Bitrate) },
                onDecode = { navigatePanel(PlayerPanel.Decode) },
                onInfo = { navigatePanel(PlayerPanel.Info) },
                onToggleFullscreen = onToggleFullscreen,
                scrim = scrim,
                modifier = barModifier,
                onHeightChanged = onBottomBarHeight,
            )
        }

    /*
     * 控件分布（W12 终版，勿再变动）：
     * 右上角 5 键 = 画中画 · 睡眠 · 选集 · 画面 · 设置；进度条下一行 = 左 6 键
     * （音轨 · 字幕 · 倍率 · 码率 · 解码 · 详细信息）+ 右下全屏键；中央五键；锁定键贴画面区右缘垂直居中。
     */
    val toolCluster: @Composable () -> Unit = {
        PlayerToolCluster(
            isPipSupported = isPipSupported,
            // 画面键的激活态：比例不是「适应屏幕」，或做过旋转 / 镜像 / 裁剪 / 去黑边
            aspectActive =
                aspect.resizeMode != AspectMode.Fit.resizeMode || videoTransform.hasAdjustments,
            queueActive = hasSidePanel && sidePanelExpanded,
            sleepActive = sleepRemaining > 0L,
            queueDescription =
                stringResource(
                    when {
                        !hasSidePanel -> PlayerR.string.player_controls_queue
                        sidePanelExpanded -> PlayerR.string.player_controls_side_panel_hide
                        else -> PlayerR.string.player_controls_side_panel_show
                    }
                ),
            onPip = onPip,
            onSleep = { navigatePanel(PlayerPanel.Sleep) },
            onOpenAspect = { navigatePanel(PlayerPanel.Aspect) },
            onOpenQueue =
                if (hasSidePanel) {
                    onToggleSidePanel
                } else {
                    { navigatePanel(PlayerPanel.Queue) }
                },
            onOpenSettings = { navigatePanel(PlayerPanel.Settings) },
            showLabels = toolLabelsVisible,
        )
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val colors = LocalCinefinColors.current
        // 画面区尺寸与 Activity 给 PlayerView 的布局参数同源：选集栏是覆盖层，不再改变画面区宽度（W12 反馈 C）
        val videoWidthDp = layout.windowWidthDp
        val videoWidth = with(density) { videoWidthDp.dp }
        val videoHeight = with(density) { layout.videoHeightDp.dp }
        val bottomScrim = playerBottomScrim()
        // 版式规格（W11 反馈①③⑧）：<600dp 收一档——右上工具簇退化纯图标、播放设置下移左下、键尺寸收一档
        val spec = playerControlSpec(maxWidth.value)
        // 面板抽屉宽度：手机 / 窄窗整宽；宽屏取 46%（360–560dp）。抽屉盖在画面上，不改画面布局
        val drawerWidth =
            if (maxWidth < 420.dp) maxWidth else (maxWidth * 0.46f).coerceIn(360.dp, 560.dp)

        if (layout.isCompact) {
            // 小窗：标题行 + 工具行 + 细进度条。「更多」取消后，工具行横向可滚，兜住全部入口（不缩水也不越界）
            PlayerCompactBar(
                isPlaying = runtime.isPlaying,
                buffering = runtime.isBuffering,
                title = uiState.currentItemTitle.ifEmpty { runtime.title },
                positionMs = runtime.position,
                durationMs = runtime.duration,
                bufferedMs = runtime.buffered,
                chapters = if (showChapterMarkers) uiState.currentChapters else emptyList(),
                trickplayIntervalMs = uiState.trickplayIntervalMs,
                trickplayVersion = uiState.trickplayVersion,
                trickplayFrameAt = trickplayFrameAt,
                isFullscreen = isFullscreen,
                onPlayPause = { if (player.isPlaying) player.pause() else player.play() },
                onPrevious = { player.seekToPreviousMediaItem() },
                onRewind = { player.seekBack() },
                onForward = { player.seekForward() },
                onNext = { player.seekToNextMediaItem() },
                onSeek = { target -> player.seekTo(target) },
                onScrubStart = { controls.show() },
                onToggleFullscreen = onToggleFullscreen,
                tools = {
                    PlayerCompactToolKeys(
                        subtitleEnabled = hasSelectedTrack(runtime.tracks, C.TRACK_TYPE_TEXT),
                        aspectActive =
                            aspect.resizeMode != AspectMode.Fit.resizeMode ||
                                videoTransform.hasAdjustments,
                        isPipSupported = isPipSupported,
                        speed = runtime.speed,
                        onOpenSubtitle = { navigatePanel(PlayerPanel.Subtitle) },
                        onOpenAudio = { navigatePanel(PlayerPanel.Audio) },
                        onOpenAspect = { navigatePanel(PlayerPanel.Aspect) },
                        onOpenQueue = { navigatePanel(PlayerPanel.Queue) },
                        onOpenInfo = { navigatePanel(PlayerPanel.Info) },
                        onOpenSettings = { navigatePanel(PlayerPanel.Settings) },
                        onOpenBitrate = { navigatePanel(PlayerPanel.Bitrate) },
                        onOpenDecode = { navigatePanel(PlayerPanel.Decode) },
                        bitrateActive = settingsController.state.streamingBitrate > 0L,
                        decodeActive =
                            settingsController.state.decodeMode ==
                                PlayerViewModel.DECODE_MODE_SOFTWARE ||
                                settingsController.state.backend ==
                                    PlayerViewModel.PLAYER_BACKEND_MPV,
                        onSpeed = { navigatePanel(PlayerPanel.Speed) },
                        onSleep = { navigatePanel(PlayerPanel.Sleep) },
                        onPip = onPip,
                        onLock = { controls.setLock(true) },
                    )
                },
                modifier = Modifier.align(Alignment.BottomCenter),
                onHeightChanged = onBottomBarHeight,
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
                            // 右上角 5 键：画中画 · 睡眠 · 选集 · 画面 · 设置（W12 终版）
                            showQualityLabel = !spec.narrow,
                            tools = toolCluster,
                            modifier = Modifier.align(Alignment.TopCenter),
                            onHeightChanged = onTopBarHeight,
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
                            // 中央播放簇（W11 反馈③）：播放 / 快进 / 播放下一个 回到屏幕正中
                            PlayerCenterCluster(
                                isPlaying = runtime.isPlaying,
                                // 缓冲中把播放键的图标换成转圈（反馈⑤：全屏只留一个加载图标）
                                buffering = runtime.isBuffering,
                                // 中央簇随画面区宽度收放：窄窗不跟右缘锁定键打架（反馈⑧）
                                spec = playerCenterSpec(videoWidthDp.toFloat()),
                                onPlayPause = {
                                    if (player.isPlaying) player.pause() else player.play()
                                },
                                onPrevious = { player.seekToPreviousMediaItem() },
                                onRewind = { player.seekBack() },
                                onForward = { player.seekForward() },
                                onNext = { player.seekToNextMediaItem() },
                                modifier = Modifier.align(Alignment.Center),
                                onSizeChanged = onCenterClusterSize,
                            )
                        }

                        // 锁定键（W11 反馈②）：屏幕右缘垂直居中、贴边，样式与其它覆盖键一致
                        PlayerIconButton(
                            iconRes = CoreR.drawable.ic_lock,
                            contentDescription =
                                stringResource(PlayerR.string.player_controls_lock),
                            onClick = { controls.setLock(true) },
                            modifier =
                                Modifier.align(Alignment.CenterEnd)
                                    .padding(end = CinefinSpacing.Space3),
                        )

                        // 折叠半开的底栏落在折痕下屏，画面区不再重复一份
                        if (layout.chrome != PlayerChromeLayout.FoldHalfOpen) {
                            bottomBar(
                                Modifier.align(Alignment.BottomCenter)
                                    .padding(end = sidePanelInset),
                                bottomScrim,
                                spec,
                            )
                        }
                    }
                }

                /*
                 * 缓冲（W11 反馈⑤）：控件可见时由中央播放键里的转圈表达；控件整层隐藏（用户手动收起）
                 * 时才用独立的玻璃圈兜底——任何时刻整个播放页只有 1 个加载图标，也不会与播放键叠在一起。
                 */
                if (runtime.isBuffering && !controls.visible) {
                    PlayerBufferingIndicator(
                        visible = true,
                        modifier = Modifier.align(Alignment.Center),
                    )
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

        /*
         * 平板 / 折叠展开：右侧「选集」覆盖层（W12 反馈 C）。
         *
         * 打开时画面区**不变**（不挤压、不右移），只把右缘 320dp 盖一层半透明面板 + 1dp 结构线；
         * 它比控制层后绘制，所以盖在控件之上；触摸命中由 PlayerOverlayContainer.sidePanelOpen 单独接管。
         */
        if (layout.hasSideContent && sidePanelExpanded) {
            Box(
                modifier =
                    Modifier.width(layout.sidePanelWidthDp.dp)
                        .fillMaxHeight()
                        .background(colors.surfaceDim.copy(alpha = 0.94f))
                        .align(Alignment.TopEnd)
            ) {
                PlayerSideContent(
                    entries = runtime.queueEntries,
                    currentIndex = runtime.currentIndex,
                    onSelect = { index ->
                        player.seekTo(index, 0L)
                        controls.show()
                    },
                    onCollapse = onToggleSidePanel,
                    onQueueMove = onQueueMove,
                    onQueueRemove = onQueueRemove,
                    onQueueClear = onQueueClear,
                    containerColor = colors.surfaceDim.copy(alpha = 0.94f),
                    modifier = Modifier.fillMaxSize(),
                )
                // 覆盖层左缘结构线：与右侧抽屉同一套语言
                Box(
                    Modifier.align(Alignment.CenterStart)
                        .width(1.dp)
                        .fillMaxHeight()
                        .background(colors.outline)
                )
            }
        }

        // 手机竖屏：画面下方的常驻内容区（选集 / 队列）
        if (layout.hasBottomContent) {
            PlayerBottomContent(
                entries = runtime.queueEntries,
                currentIndex = runtime.currentIndex,
                onSelect = { index -> player.seekTo(index, 0L) },
                onQueueMove = onQueueMove,
                onQueueRemove = onQueueRemove,
                onQueueClear = onQueueClear,
                modifier = Modifier.fillMaxSize().padding(top = videoHeight),
            )
        }

        // 折叠半开（水平折痕）：折痕以下是常驻控制区，不参与自动淡出
        if (layout.chrome == PlayerChromeLayout.FoldHalfOpen) {
            Column(
                modifier =
                    Modifier.fillMaxSize().padding(top = videoHeight).background(colors.surfaceDim)
            ) {
                bottomBar(Modifier.fillMaxWidth(), null, spec)
                PlayerBottomContent(
                    entries = runtime.queueEntries,
                    currentIndex = runtime.currentIndex,
                    onSelect = { index -> player.seekTo(index, 0L) },
                    onQueueMove = onQueueMove,
                    onQueueRemove = onQueueRemove,
                    onQueueClear = onQueueClear,
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
                            onClick = {
                                panel = PlayerPanel.None
                                panelBackTarget = null
                            },
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
                onClose = {
                    panel = PlayerPanel.None
                    panelBackTarget = null
                },
                onBack =
                    panelBackTarget?.let { target ->
                        {
                            panel = target
                            panelBackTarget = null
                        }
                    },
            ) {
                when (drawerPanel) {
                    PlayerPanel.Speed ->
                        SpeedPanel(
                            current = runtime.speed,
                            // 反馈④：选档位只更新状态，面板保持打开，只有显式关闭 / 返回才退出
                            onSelect = { onSelectSpeed(it) },
                        )
                    PlayerPanel.Repeat ->
                        RepeatPanel(
                            repeatMode = runtime.repeatMode,
                            shuffleEnabled = runtime.shuffleEnabled,
                            canShuffle = runtime.canShuffle,
                            pauseAfterItem = settingsController.state.pauseAfterCurrentItem,
                            onTogglePauseAfterItem = { enabled ->
                                settingsController.setPauseAfterCurrentItem(enabled)
                                onPauseAfterCurrentItemChanged(enabled)
                            },
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
                            // W12 反馈 B：字幕模式 / 记忆选择并入主字幕面板，全播放页只有这一个字幕入口
                            controller = settingsController,
                            onSubtitleModeChanged = onSubtitleModeChanged,
                        )
                    PlayerPanel.Audio ->
                        AudioPanel(
                            state = audioPanelState,
                            onSelectTrack = onSelectAudioTrack,
                            onAdjustDelay = onAdjustAudioDelay,
                            onResetDelay = onResetAudioDelay,
                            // W12 反馈 B：音轨语言优先级并入主音轨面板，唯一入口
                            controller = settingsController,
                        )
                    PlayerPanel.Aspect ->
                        AspectPanel(
                            current = aspect,
                            transform = videoTransform,
                            onTransformChange = onVideoTransformChanged,
                            onSelect = { mode ->
                                aspect = mode
                                onSelectResizeMode(mode.resizeMode)
                            },
                        )
                    PlayerPanel.Settings ->
                        PlayerSettingsPanel(
                            controller = settingsController,
                            onOpenPanel = { target -> navigatePanel(target) },
                        )
                    PlayerPanel.Decode ->
                        PlayerDecodePanel(
                            controller = settingsController,
                            onSelectBackend = onSelectBackend,
                            onSelectDecodeMode = onSelectDecodeMode,
                            onAutoFallbackChange = onAutoFallbackChange,
                        )
                    PlayerPanel.Bitrate ->
                        PlayerBitratePanel(
                            controller = settingsController,
                            onSelectBitrate = onSelectBitrate,
                        )
                    PlayerPanel.Info ->
                        InfoPanel(
                            player = player,
                            runtime = runtime,
                            title = uiState.currentItemTitle,
                            sourceInfo = uiState.currentMediaInfo,
                        )
                    PlayerPanel.Queue ->
                        QueuePanel(
                            entries = runtime.queueEntries,
                            currentIndex = runtime.currentIndex,
                            // 反馈④：跳转不关面板，换集后仍可继续整理队列
                            onSelect = { index -> player.seekTo(index, 0L) },
                            onMove = onQueueMove,
                            onRemove = onQueueRemove,
                            onClear = onQueueClear,
                        )
                    PlayerPanel.Sleep ->
                        SleepPanel(
                            currentMinutes = sleepMinutes,
                            onSelect = { sleepMinutes = it },
                        )
                    PlayerPanel.None -> Unit
                }
            }
        }
    }
}

// ---------- 顶部 ----------

/**
 * 覆盖层文本徽标（W14 抽共用组件）：顶栏「清晰度」与左下「倍率」共用同一条样式规则—— labelSmall 字号 + Xs 圆角（8dp）+ 1dp OutlineVariant 描边 +
 * 水平 Space2 / 垂直 2dp 内边距。
 *
 * 底色交给所在遮罩（顶栏 / 底栏渐隐），徽标本身不铺色块；**纯展示**，不含任何点击行为。 两处样式必须同源（W14 起 1× 徽标对齐清晰度徽标），内联两份会漂移。
 */
@Composable
private fun PlayerOverlayBadge(text: String, modifier: Modifier = Modifier) {
    val colors = LocalCinefinColors.current
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = colors.onSurfaceVariant,
        maxLines = 1,
        modifier =
            modifier
                .clip(CinefinShapes.Xs)
                .border(1.dp, colors.outlineVariant, CinefinShapes.Xs)
                .padding(horizontal = CinefinSpacing.Space2, vertical = 2.dp),
    )
}

@Composable
private fun PlayerTopBar(
    title: String,
    /** 清晰度徽标（例如 1080P）；取不到时传 null，不占位 */
    qualityLabel: String?,
    onBack: () -> Unit,
    /** 窄屏：清晰度徽标不占位，把宽度让给标题与 5 个工具键 */
    showQualityLabel: Boolean,
    /** 右上角 5 键（画中画 / 睡眠 / 选集 / 画面 / 设置），由调用方注入保持单一入口与固定顺序 */
    tools: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    onHeightChanged: (Int) -> Unit = {},
) {
    val colors = LocalCinefinColors.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            modifier
                .fillMaxWidth()
                .onSizeChanged { onHeightChanged(it.height) }
                .background(playerTopScrim())
                .padding(
                    start = CinefinSpacing.Space2,
                    end = CinefinSpacing.Space2,
                    top = CinefinSpacing.Space2,
                    bottom = CinefinSpacing.Space2,
                ),
    ) {
        PlayerIconButton(
            iconRes = CoreR.drawable.ic_arrow_left,
            contentDescription = stringResource(PlayerR.string.player_controls_exit),
            onClick = onBack,
        )
        Spacer(Modifier.width(CinefinSpacing.Space1))
        /*
         * 标题占满剩余宽度（weight + fill），确保右侧的清晰度徽标与锁定键贴住顶栏右端：
         * 旧写法是「标题 weight(1f, fill=false) + 尾部 Spacer(weight(1f))」，实测锁被挤到
         * 顶栏中部（2026-10-01 Pad 5 走查），所以改为「标题吃掉剩余空间」。
         */
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = colors.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (qualityLabel != null && showQualityLabel) {
            Spacer(Modifier.width(CinefinSpacing.Space2))
            PlayerOverlayBadge(text = qualityLabel)
        }
        Spacer(Modifier.width(CinefinSpacing.Space2))
        // 右上角 5 键（W12 终版）：画中画 · 睡眠 · 选集 · 画面 · 设置；锁移到右缘中部
        tools()
    }
}

// ---------- 中央 ----------

/** 中央缓冲提示（反馈⑥）：传输键整体挪到左下角后，中央只保留一个轻量转圈， 其余面积完整让给画面与手势。 */
@Composable
private fun PlayerBufferingIndicator(visible: Boolean, modifier: Modifier = Modifier) {
    val colors = LocalCinefinColors.current
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(CinefinMotion.Fast)),
        exit = fadeOut(tween(CinefinMotion.Fast)),
        modifier = modifier,
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier =
                Modifier.size(64.dp)
                    .clip(CinefinShapes.Full)
                    .background(colors.scrim.copy(alpha = 0.45f)),
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(30.dp),
                color = colors.onSurface,
                strokeWidth = 2.dp,
            )
        }
    }
}

/**
 * 主播放键（W17 反馈④：与其它覆盖键统一到同一套玻璃底 / 描边语言）。
 *
 * 旧版是「月白填充 + 深色图标」，与中央四个玻璃传输键不是一套视觉；现在改为 「黑 28% 玻璃底 + 1dp 白 16% 描边 + OnSurface
 * 图标」，只靠尺寸（56/70dp）与圆角（Lg） 保持主行动辨识度。中央五键与右缘锁定键一样**保持纯图标，不加文字**。
 *
 * [buffering] = true 时图标位换成同色转圈：全屏唯一的那一个加载图标就落在这里（W11 反馈⑤）， 键本身仍可点（缓冲中卡住时照样能暂停）。
 */
@Composable
internal fun PlayerPlayKey(
    isPlaying: Boolean,
    onClick: () -> Unit,
    size: Dp = 56.dp,
    buffering: Boolean = false,
) {
    val colors = LocalCinefinColors.current
    val playPauseLabel = stringResource(PlayerR.string.player_controls_play_pause)
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val focused by interactionSource.collectIsFocusedAsState()
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier.size(size)
                .graphicsLayer {
                    val scale = if (pressed) 0.94f else 1f
                    scaleX = scale
                    scaleY = scale
                }
                .clip(CinefinShapes.Lg)
                .background(
                    colors.scrim.copy(
                        alpha = if (pressed) PLAYER_GLASS_PRESSED_ALPHA else PLAYER_GLASS_ALPHA
                    )
                )
                .border(
                    1.dp,
                    colors.onSurface.copy(alpha = PLAYER_GLASS_BORDER_ALPHA),
                    CinefinShapes.Lg,
                )
                .then(
                    if (focused) {
                        Modifier.border(2.dp, colors.onSurface.copy(alpha = 0.6f), CinefinShapes.Lg)
                    } else {
                        Modifier
                    }
                )
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    role = Role.Button,
                    onClickLabel = playPauseLabel,
                    onClick = onClick,
                )
                .semantics(mergeDescendants = true) { contentDescription = playPauseLabel },
    ) {
        if (buffering) {
            CircularProgressIndicator(
                modifier = Modifier.size(size * 0.34f),
                color = colors.onSurface,
                strokeWidth = 2.dp,
            )
        } else {
            Icon(
                painter =
                    painterResource(
                        if (isPlaying) CoreR.drawable.ic_pause else CoreR.drawable.ic_play
                    ),
                contentDescription = null,
                tint = colors.onSurface,
                modifier = Modifier.size(size * 0.45f),
            )
        }
    }
}

/**
 * 中央播放簇（W11 反馈③）：上一个 · 快退 −10s · 播放 / 暂停 · 快进 +10s · 下一个——五键居中， 与上一版的传输簇一致。传输键只在中央出现（右上工具簇 /
 * 左下工具行都不再重复）； 簇之外的触摸区域照旧交给手势层（命中带见 [PlayerOverlayContainer]）。
 */
@Composable
private fun PlayerCenterCluster(
    isPlaying: Boolean,
    buffering: Boolean,
    /** 版式规格：窄窗收一档，避免与右缘锁定键重叠（W11 反馈⑧） */
    spec: PlayerCenterSpec,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onRewind: () -> Unit,
    onForward: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
    onSizeChanged: (Int, Int) -> Unit = { _, _ -> },
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spec.gapDp.dp),
        modifier = modifier.onSizeChanged { onSizeChanged(it.width, it.height) },
    ) {
        PlayerTransportButton(
            iconRes = CoreR.drawable.ic_skip_back,
            contentDescription = stringResource(PlayerR.string.player_controls_previous_episode),
            onClick = onPrevious,
            size = spec.transportSizeDp.dp,
        )
        PlayerTransportButton(
            iconRes = CoreR.drawable.ic_rewind,
            contentDescription = stringResource(PlayerR.string.player_controls_rewind),
            onClick = onRewind,
            size = spec.transportSizeDp.dp,
        )
        PlayerPlayKey(
            isPlaying = isPlaying,
            onClick = onPlayPause,
            size = spec.playSizeDp.dp,
            buffering = buffering,
        )
        PlayerTransportButton(
            iconRes = CoreR.drawable.ic_fast_forward,
            contentDescription = stringResource(PlayerR.string.player_controls_fast_forward),
            onClick = onForward,
            size = spec.transportSizeDp.dp,
        )
        PlayerTransportButton(
            iconRes = CoreR.drawable.ic_skip_forward,
            contentDescription = stringResource(PlayerR.string.player_controls_next_episode),
            onClick = onNext,
            size = spec.transportSizeDp.dp,
        )
    }
}

/** 次级传输键（±10s / 上下集）：玻璃圆底 + OnSurface 图标（§8.7 覆盖层次级键）， 按压缩放、键盘焦点描边，默认命中区 46dp。 */
@Composable
private fun PlayerTransportButton(
    iconRes: Int,
    contentDescription: String,
    onClick: () -> Unit,
    size: Dp = 46.dp,
) {
    val colors = LocalCinefinColors.current
    val media = LocalMediaColors.current
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val focused by interactionSource.collectIsFocusedAsState()
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier.size(size)
                .graphicsLayer {
                    val scale = if (pressed) 0.92f else 1f
                    scaleX = scale
                    scaleY = scale
                }
                .clip(CinefinShapes.Full)
                // 与其它覆盖键同一套玻璃底与描边（W12 反馈 A：更透 + 描边）
                .background(
                    colors.scrim.copy(
                        alpha = if (pressed) PLAYER_GLASS_PRESSED_ALPHA else PLAYER_GLASS_ALPHA
                    )
                )
                .border(
                    1.dp,
                    colors.onSurface.copy(alpha = PLAYER_GLASS_BORDER_ALPHA),
                    CinefinShapes.Full,
                )
                .then(
                    if (focused) {
                        Modifier.border(2.dp, media.base.copy(alpha = 0.6f), CinefinShapes.Full)
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
 * 右上角 5 键（W12 终版布局，顺序固定）：画中画 · 睡眠 · 选集 · 画面 · 设置。
 *
 * 顺序表 [PLAYER_TOP_KEY_ORDER] 是这一版布局的验收点之一（用户明确「画面在选集与设置之间」），改顺序即回归。 键一律贴边玻璃框（W12 反馈
 * A：更透、更小、带描边）；W17 反馈⑤起宽屏在全屏 / 宽度充足时于图标下加 10sp 小字，窄屏与 Compact 仍只留图标。
 */
@Composable
private fun PlayerToolCluster(
    isPipSupported: Boolean,
    aspectActive: Boolean,
    queueActive: Boolean,
    sleepActive: Boolean,
    onPip: () -> Unit,
    onSleep: () -> Unit,
    onOpenAspect: () -> Unit,
    onOpenQueue: () -> Unit,
    onOpenSettings: () -> Unit,
    queueDescription: String,
    /** W17 反馈⑤：是否在图标下加小字（窄屏 / Compact 为 false） */
    showLabels: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CinefinSpacing.Space1),
        modifier = modifier,
    ) {
        PLAYER_TOP_KEY_ORDER.forEach { key ->
            when (key) {
                PlayerTopKey.Pip ->
                    if (isPipSupported) {
                        PlayerIconButton(
                            iconRes = PlayerR.drawable.ic_player_pip,
                            contentDescription =
                                stringResource(PlayerR.string.player_controls_label_pip),
                            onClick = onPip,
                            label =
                                if (showLabels) {
                                    stringResource(PlayerR.string.player_controls_label_pip)
                                } else {
                                    null
                                },
                        )
                    }
                PlayerTopKey.Sleep ->
                    PlayerIconButton(
                        iconRes = PlayerR.drawable.ic_player_sleep,
                        contentDescription =
                            stringResource(PlayerR.string.player_controls_label_sleep),
                        selected = sleepActive,
                        onClick = onSleep,
                        label =
                            if (showLabels) {
                                stringResource(PlayerR.string.player_controls_label_sleep)
                            } else {
                                null
                            },
                    )
                PlayerTopKey.Episode ->
                    PlayerIconButton(
                        iconRes = CoreR.drawable.ic_playlist,
                        contentDescription = queueDescription,
                        selected = queueActive,
                        onClick = onOpenQueue,
                        label =
                            if (showLabels) {
                                stringResource(PlayerR.string.player_controls_episodes)
                            } else {
                                null
                            },
                    )
                PlayerTopKey.Aspect ->
                    PlayerIconButton(
                        iconRes = CoreR.drawable.ic_aspect,
                        contentDescription = stringResource(PlayerR.string.player_controls_aspect),
                        selected = aspectActive,
                        onClick = onOpenAspect,
                        label =
                            if (showLabels) {
                                stringResource(PlayerR.string.player_controls_label_aspect)
                            } else {
                                null
                            },
                    )
                PlayerTopKey.Settings ->
                    PlayerIconButton(
                        iconRes = PlayerR.drawable.ic_player_settings,
                        contentDescription =
                            stringResource(PlayerR.string.player_controls_settings),
                        onClick = onOpenSettings,
                        label =
                            if (showLabels) {
                                stringResource(PlayerR.string.player_controls_label_settings)
                            } else {
                                null
                            },
                    )
            }
        }
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
    onSwitchBackend: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 卡片上直接写清「换成哪个内核」，避免用户点下去不知道会发生什么
    val targetBackend =
        if (error.backend == PlayerViewModel.PLAYER_BACKEND_EXOPLAYER) {
            PlayerViewModel.PLAYER_BACKEND_MPV
        } else {
            PlayerViewModel.PLAYER_BACKEND_EXOPLAYER
        }
    val targetLabel =
        if (targetBackend == PlayerViewModel.PLAYER_BACKEND_MPV) "mpv" else "ExoPlayer"
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
                        targetLabel,
                    ),
                primary = false,
                onClick = { onSwitchBackend(targetBackend) },
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

/**
 * 底栏（W12 终版布局 + W17 文字化）：进度条一行（当前时间 · 进度条 · 总时长）+ 左下工具行 + 右下全屏键。
 *
 * 左下工具行（W17 用户确认）：**6 键恒定齐全**（音轨 · 字幕 · 倍速 · 码率 · 解码 · 详细信息）+ 「详细信息」右侧的 1× 徽标；[showLabels] =
 * true（全屏 / 宽度充足 / 平板）时在图标下加 10sp 小字， 窄屏与 Compact 只留图标。倍速键只显示图标，当前值由 1× 徽标（纯展示、不可点击）显示。
 * 右下角全屏键**保持纯图标**，与工具行不重叠。
 */
@Composable
private fun PlayerBottomBar(
    positionMs: Long,
    durationMs: Long,
    bufferedMs: Long,
    chapters: List<PlayerChapter>,
    trickplayIntervalMs: Int,
    trickplayVersion: Int,
    trickplayFrameAt: (Long) -> Bitmap?,
    speed: Float,
    sleepActive: Boolean,
    /** 字幕键激活态（改在进度条下方这一行，W12 终版布局） */
    subtitleEnabled: Boolean,
    /** 码率键激活态：选了具体 Mbps（服务器转码中） */
    bitrateActive: Boolean,
    /** 解码键激活态：软解或 mpv 内核 */
    decodeActive: Boolean,
    /** 版式规格（W12 反馈 A）：窄屏整体收一档 */
    spec: PlayerControlSpec,
    isFullscreen: Boolean,
    /** W17 反馈⑤：是否在 6 键图标下加小字（窄屏 / Compact 只留图标） */
    showLabels: Boolean = false,
    onSeek: (Long) -> Unit,
    onScrubStart: () -> Unit,
    onAudio: () -> Unit,
    onSubtitle: () -> Unit,
    onSpeed: () -> Unit,
    onBitrate: () -> Unit,
    onDecode: () -> Unit,
    onInfo: () -> Unit,
    onToggleFullscreen: () -> Unit,
    /** 底栏遮罩：叠在画面上的形态用渐变，折痕下屏用 null（背景由内容区承担） */
    scrim: Brush? = null,
    modifier: Modifier = Modifier,
    /** 实测高度回传给命中区，保证触摸分区与控件实高一致（§9 踩坑） */
    onHeightChanged: (Int) -> Unit = {},
) {
    val colors = LocalCinefinColors.current
    val media = LocalMediaColors.current
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .onSizeChanged { onHeightChanged(it.height) }
                .then(if (scrim != null) Modifier.background(scrim) else Modifier)
                .padding(bottom = CinefinSpacing.Space3)
    ) {
        /*
         * 进度条一行（W12 终版布局）：左 = 当前时间、中 = 进度条（保留流光渐变）、右 = 总时长——三者同一条线。
         * 时间码不再单独占行，进度条也不再通栏贴底（下方还有 6 键工具行）。
         */
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier =
                Modifier.fillMaxWidth()
                    .padding(horizontal = CinefinSpacing.Space4, vertical = CinefinSpacing.Space1),
        ) {
            Text(
                text = formatTime(positionMs),
                style = CinefinType.MonoDataSmall,
                color = colors.onSurface,
            )
            Box(modifier = Modifier.weight(1f).padding(horizontal = CinefinSpacing.Space2)) {
                PlayerSeekBar(
                    positionMs = positionMs,
                    durationMs = durationMs,
                    bufferedMs = bufferedMs,
                    chapters = chapters,
                    trickplayIntervalMs = trickplayIntervalMs,
                    trickplayVersion = trickplayVersion,
                    trickplayFrameAt = trickplayFrameAt,
                    onScrubStart = onScrubStart,
                    onScrub = { onSeek(it) },
                )
            }
            Text(
                text = formatTime(durationMs),
                style = CinefinType.MonoDataSmall,
                color = colors.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(CinefinSpacing.Space1))

        /*
         * 进度条下方（W13 方案 A 起分级显示）：全屏 / 宽度充足 = 6 键（音轨 · 字幕 · 倍率 · 码率 · 解码 · 详细信息）+ 1×；
         * 非全屏窄窗 = 音轨 · 字幕 · 倍率 · 详细信息 + 1×（码率 / 解码 改从「设置 → 播放」进入）。右下恒为全屏键。
         * 行内横向可滚：窗口再窄也只是多滑一下，不会把键挤出屏幕（W12 反馈 A + W11 反馈⑧ 的兜底）。
         */
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier =
                Modifier.fillMaxWidth()
                    .padding(
                        horizontal = spec.toolRowPaddingDp.dp,
                        vertical = CinefinSpacing.Space1,
                    ),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spec.keyGapDp.dp),
                modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
            ) {
                // W17：6 键恒定齐全；showLabels 只控制图标下的小字，窄屏仍可点全部功能
                PLAYER_BOTTOM_KEY_ORDER.forEach { key ->
                    when (key) {
                        PlayerBottomKey.Audio ->
                            PlayerIconButton(
                                iconRes = CoreR.drawable.ic_speaker,
                                contentDescription =
                                    stringResource(PlayerR.string.select_audio_track),
                                onClick = onAudio,
                                size = spec.toolKeySizeDp.dp,
                                iconSize = spec.iconSizeDp.dp,
                                label =
                                    if (showLabels) {
                                        stringResource(PlayerR.string.player_controls_label_audio)
                                    } else {
                                        null
                                    },
                            )
                        PlayerBottomKey.Subtitle ->
                            PlayerIconButton(
                                iconRes = CoreR.drawable.ic_closed_caption,
                                contentDescription =
                                    stringResource(PlayerR.string.select_subtitle_track),
                                selected = subtitleEnabled,
                                onClick = onSubtitle,
                                size = spec.toolKeySizeDp.dp,
                                iconSize = spec.iconSizeDp.dp,
                                label =
                                    if (showLabels) {
                                        stringResource(
                                            PlayerR.string.player_controls_label_subtitle
                                        )
                                    } else {
                                        null
                                    },
                            )
                        // 倍率键：W13 反馈②起**只显示图标**，宽度与其它图标键一致；当前倍率由右侧的 1× 徽标显示（唯一倍速入口）
                        PlayerBottomKey.Speed ->
                            PlayerIconButton(
                                iconRes = PlayerR.drawable.ic_player_speed,
                                contentDescription =
                                    stringResource(PlayerR.string.player_controls_label_speed),
                                selected = speed != 1f,
                                onClick = onSpeed,
                                size = spec.toolKeySizeDp.dp,
                                iconSize = spec.iconSizeDp.dp,
                                label =
                                    if (showLabels) {
                                        stringResource(PlayerR.string.player_controls_label_speed)
                                    } else {
                                        null
                                    },
                            )
                        PlayerBottomKey.Bitrate ->
                            PlayerIconButton(
                                iconRes = PlayerR.drawable.ic_player_bitrate,
                                contentDescription =
                                    stringResource(PlayerR.string.player_controls_label_bitrate),
                                selected = bitrateActive,
                                onClick = onBitrate,
                                size = spec.toolKeySizeDp.dp,
                                iconSize = spec.iconSizeDp.dp,
                                label =
                                    if (showLabels) {
                                        stringResource(PlayerR.string.player_controls_label_bitrate)
                                    } else {
                                        null
                                    },
                            )
                        PlayerBottomKey.Decode ->
                            PlayerIconButton(
                                iconRes = PlayerR.drawable.ic_player_decode,
                                contentDescription =
                                    stringResource(PlayerR.string.player_controls_label_decode),
                                selected = decodeActive,
                                onClick = onDecode,
                                size = spec.toolKeySizeDp.dp,
                                iconSize = spec.iconSizeDp.dp,
                                label =
                                    if (showLabels) {
                                        stringResource(PlayerR.string.player_controls_label_decode)
                                    } else {
                                        null
                                    },
                            )
                        PlayerBottomKey.Info ->
                            PlayerIconButton(
                                iconRes = PlayerR.drawable.ic_player_info,
                                contentDescription =
                                    stringResource(PlayerR.string.player_controls_label_info),
                                onClick = onInfo,
                                size = spec.toolKeySizeDp.dp,
                                iconSize = spec.iconSizeDp.dp,
                                label =
                                    if (showLabels) {
                                        stringResource(PlayerR.string.player_controls_label_details)
                                    } else {
                                        null
                                    },
                            )
                    }
                }
                // W13 反馈③ + W14 微调：当前倍率作为**纯展示徽标**固定在「详细信息」右侧，不可点击（倍速入口只有倍率图标键）
                PlayerSpeedBadge(speed = speed)
            }
            if (sleepActive) {
                Spacer(Modifier.width(CinefinSpacing.Space1))
                Text(
                    text = stringResource(PlayerR.string.player_controls_label_sleep),
                    style = MaterialTheme.typography.labelSmall,
                    color = media.bright,
                )
            }
            Spacer(Modifier.width(CinefinSpacing.Space2))
            // 右下角全屏键：同一个键，图标随状态切换
            PlayerIconButton(
                iconRes =
                    if (isFullscreen) {
                        PlayerR.drawable.ic_player_fullscreen_exit
                    } else {
                        PlayerR.drawable.ic_player_fullscreen
                    },
                contentDescription =
                    stringResource(
                        if (isFullscreen) {
                            PlayerR.string.player_controls_fullscreen_exit
                        } else {
                            PlayerR.string.player_controls_fullscreen
                        }
                    ),
                onClick = onToggleFullscreen,
                size = spec.toolKeySizeDp.dp,
                iconSize = spec.iconSizeDp.dp,
            )
        }
    }
}

/**
 * 当前倍率徽标（W13 反馈③新增；W14 起改为**纯展示**）：固定在「详细信息」控件右侧，与顶栏清晰度徽标共用 [PlayerOverlayBadge] 的同一条样式规则（同字号 / 圆角
 * / 描边 / 内边距），不再有玻璃键框与点击行为。
 *
 * 倍速入口只有左下角的倍率图标键一个，这里只负责把当前值显示出来（随倍率变化）；语音提示仍带「倍速」前缀（如“倍速 1.5×”）。
 */
@Composable
private fun PlayerSpeedBadge(speed: Float, modifier: Modifier = Modifier) {
    val label = formatSpeed(speed)
    val entry = stringResource(PlayerR.string.player_controls_label_speed)
    Box(
        modifier =
            modifier.semantics(mergeDescendants = true) { contentDescription = "$entry $label" }
    ) {
        PlayerOverlayBadge(text = label)
    }
}

/**
 * 小窗（Compact）的工具行：小窗既没有右上角 5 键、也没有进度条下 6 键的位置， 因此用一个横向可滚的小键行把全部入口兜住——一个功能一个入口、功能不缩水，
 * 窗口再窄也只是多滑一下（W11 反馈⑧：不靠隐藏修复；W12 补 码率 / 解码 两个新入口）。
 *
 * W17 起 Compact 骨架一律**只留图标、不加文字**（键行已横向可滚）；6 键入口恒定齐全， 「码率 / 解码」不再从这一行消失（设置面板里的兜底入口已按用户确认移除）。
 * 倍率键只显示图标，当前值由「详细信息」右侧的 1× 徽标显示（纯展示，W14）。
 */
@Composable
internal fun PlayerCompactToolKeys(
    subtitleEnabled: Boolean,
    aspectActive: Boolean,
    bitrateActive: Boolean,
    decodeActive: Boolean,
    isPipSupported: Boolean,
    /** 当前倍率：1× 徽标显示的值（倍速入口只有倍率图标键） */
    speed: Float,
    onOpenSubtitle: () -> Unit,
    onOpenAudio: () -> Unit,
    onOpenAspect: () -> Unit,
    onOpenQueue: () -> Unit,
    onOpenInfo: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenBitrate: () -> Unit,
    onOpenDecode: () -> Unit,
    onSpeed: () -> Unit,
    onSleep: () -> Unit,
    onPip: () -> Unit,
    onLock: () -> Unit,
) {
    // 顺序与画面区版式同源：音轨 · 字幕 · 倍率 · 码率 · 解码 · 详细信息 + 1×，其余入口跟在后面
    PlayerIconButton(
        iconRes = CoreR.drawable.ic_speaker,
        contentDescription = stringResource(PlayerR.string.select_audio_track),
        onClick = onOpenAudio,
        size = 40.dp,
    )
    PlayerIconButton(
        iconRes = CoreR.drawable.ic_closed_caption,
        contentDescription = stringResource(PlayerR.string.select_subtitle_track),
        selected = subtitleEnabled,
        onClick = onOpenSubtitle,
        size = 40.dp,
    )
    // 倍率键：W13 反馈②起只显示图标（与其它键同宽）；当前值由「详细信息」右侧的 1× 徽标显示（唯一倍速入口）
    PlayerIconButton(
        iconRes = PlayerR.drawable.ic_player_speed,
        contentDescription = stringResource(PlayerR.string.player_controls_label_speed),
        selected = speed != 1f,
        onClick = onSpeed,
        size = 40.dp,
    )
    PlayerIconButton(
        iconRes = PlayerR.drawable.ic_player_bitrate,
        contentDescription = stringResource(PlayerR.string.player_controls_label_bitrate),
        selected = bitrateActive,
        onClick = onOpenBitrate,
        size = 40.dp,
    )
    PlayerIconButton(
        iconRes = PlayerR.drawable.ic_player_decode,
        contentDescription = stringResource(PlayerR.string.player_controls_label_decode),
        selected = decodeActive,
        onClick = onOpenDecode,
        size = 40.dp,
    )
    PlayerIconButton(
        iconRes = PlayerR.drawable.ic_player_info,
        contentDescription = stringResource(PlayerR.string.player_controls_label_info),
        onClick = onOpenInfo,
        size = 40.dp,
    )
    // 1× 徽标：贴在「详细信息」右侧（W13 反馈③；W14 起纯展示、不可点）
    PlayerSpeedBadge(speed = speed)
    PlayerIconButton(
        iconRes = PlayerR.drawable.ic_player_sleep,
        contentDescription = stringResource(PlayerR.string.player_controls_label_sleep),
        onClick = onSleep,
        size = 40.dp,
    )
    PlayerIconButton(
        iconRes = CoreR.drawable.ic_playlist,
        contentDescription = stringResource(PlayerR.string.player_controls_queue),
        onClick = onOpenQueue,
        size = 40.dp,
    )
    PlayerIconButton(
        iconRes = CoreR.drawable.ic_aspect,
        contentDescription = stringResource(PlayerR.string.player_controls_aspect),
        selected = aspectActive,
        onClick = onOpenAspect,
        size = 40.dp,
    )
    PlayerIconButton(
        iconRes = PlayerR.drawable.ic_player_settings,
        contentDescription = stringResource(PlayerR.string.player_controls_label_settings),
        onClick = onOpenSettings,
        size = 40.dp,
    )
    if (isPipSupported) {
        PlayerIconButton(
            iconRes = PlayerR.drawable.ic_player_pip,
            contentDescription = stringResource(PlayerR.string.player_controls_label_pip),
            onClick = onPip,
            size = 40.dp,
        )
    }
    PlayerIconButton(
        iconRes = CoreR.drawable.ic_lock,
        contentDescription = stringResource(PlayerR.string.player_controls_lock),
        onClick = onLock,
        size = 40.dp,
    )
}

/**
 * 已播段渐变的色标（0..1，W11 反馈④）：极光青铺满前 90%，最后 10% 才过渡到辅光蓝。
 *
 * A 稿里辅光蓝是**渐变辅助色**、用量必须 <10%（`docs/design/s1-direction-a/README.md` §2）； 抽成纯函数 + 单测，免得日后被改成"半条蓝条"。
 */
internal fun playerProgressGradientStops(): List<Float> = listOf(0f, PLAYER_PROGRESS_ACCENT_END, 1f)

/** 辅光蓝起点：0.9 = 只在末端 10% 出现第二种颜色。 */
internal const val PLAYER_PROGRESS_ACCENT_END = 0.9f

/** 已播段流光渐变（W11 反馈④）：极光青 → 末端辅光蓝；缓冲层 / 章节刻度 / 拖拽钮 / 内高光保持不变。 */
internal fun playerProgressBrush(accent: Color, accentSecondary: Color): Brush {
    val stops = playerProgressGradientStops()
    return Brush.horizontalGradient(
        colorStops =
            arrayOf(
                stops[0] to accent,
                stops[1] to accent,
                stops[2] to accentSecondary,
            )
    )
}

/**
 * 进度条（画面覆盖层 §8.7）：6dp 轨道白 16% + 已缓冲白 24% + 流光渐变的已播段 + 章节刻度 + Trickplay 预览。 视觉细、命中区 34dp——「可点目标不小于
 * 48dp」由轨道两端的工具行外扩消化。
 */
@Composable
internal fun PlayerSeekBar(
    positionMs: Long,
    durationMs: Long,
    bufferedMs: Long,
    chapters: List<PlayerChapter>,
    /**
     * W20（§1.11）：Trickplay 改为按需预览。
     *
     * [trickplayIntervalMs] = 0 表示本条目没有可用预览（服务器没生成 / 拉取失败 / 用户关闭）； [trickplayVersion]
     * 是新图到位的版本号（读它让本次重组订阅加载进度）； [trickplayFrameAt] 只做「查缓存 + 触发后台拉取」，不会阻塞拖动。
     */
    trickplayIntervalMs: Int = 0,
    trickplayVersion: Int = 0,
    trickplayFrameAt: (Long) -> Bitmap? = { null },
    onScrubStart: () -> Unit,
    onScrub: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    var scrubbing by remember { mutableStateOf(false) }
    var scrubFraction by remember { mutableFloatStateOf(0f) }
    val colors = LocalCinefinColors.current
    val media = LocalMediaColors.current
    val lumen = LocalLumenColors.current
    // 已播段流光渐变的两端色（W11 反馈④）：极光青打底、辅光蓝收尾；没有 Lumen 色板时退回当前域媒体色
    val accent = lumen?.accent ?: media.base
    val accentSecondary = lumen?.accentSecondary ?: media.bright

    val safeDuration = durationMs.coerceAtLeast(1L)
    val playedFraction =
        (if (scrubbing) scrubFraction else positionMs.toFloat() / safeDuration).coerceIn(0f, 1f)
    val bufferedFraction = (bufferedMs.toFloat() / safeDuration).coerceIn(0f, 1f)
    val previewPosition = (playedFraction * safeDuration).roundToLong()
    val previewBitmap: android.graphics.Bitmap? =
        if (scrubbing && trickplayIntervalMs > 0) {
            // 显式引用版本号：新精灵图到位（version 变化）时本段重组，把预览图刷出来
            @Suppress("UNUSED_EXPRESSION") trickplayVersion
            trickplayFrameAt(previewPosition)
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
            /*
             * A · Lumen 进度条（反馈⑦）：极光青进度 + 缓冲层 + 章节刻度 + 发丝线 + 内高光。
             * 轨道是「细管」：1dp 发丝线包边（§5.2 结构线）+ 顶部 1dp 内高光（白 5%），
             * 拖动 / 键盘焦点时轨道加粗、knob 放大并带一圈极光青描边（不做 glow，§2.6 第 3 条）。
             */
            val trackHeight = 6.dp
            val shape = CinefinShapes.TwoXs
            // 轨道底座
            Box(
                modifier =
                    Modifier.fillMaxWidth()
                        .height(trackHeight)
                        .clip(shape)
                        .background(colors.progressTrackOnImage)
                        .border(1.dp, colors.outline, shape)
            )
            // 顶部内高光（发丝层次：内容即光源，控件自己只留一条极细的亮边）
            Box(
                modifier =
                    Modifier.fillMaxWidth()
                        .height(1.dp)
                        .clip(CinefinShapes.TwoXs)
                        .background(Color.White.copy(alpha = 0.05f))
            )
            // 已缓冲
            Box(
                modifier =
                    Modifier.fillMaxWidth(bufferedFraction)
                        .height(trackHeight)
                        .clip(shape)
                        .background(colors.onSurface.copy(alpha = 0.24f))
            )
            // 已播：流光渐变（W11 反馈④）——极光青为主，末端 <10% 过渡到辅光蓝，不再是单色
            Box(
                modifier =
                    Modifier.fillMaxWidth(playedFraction)
                        .height(if (scrubbing) 8.dp else trackHeight)
                        .clip(shape)
                        .background(playerProgressBrush(accent, accentSecondary))
            )
            // 章节刻度：细白线，只做位置提示
            chapters.forEach { chapter ->
                val fraction = (chapter.startPosition.toFloat() / safeDuration).coerceIn(0f, 1f)
                Box(
                    modifier = Modifier.fillMaxWidth(fraction).height(trackHeight),
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    Box(
                        modifier =
                            Modifier.width(1.5.dp)
                                .height(14.dp)
                                .clip(CinefinShapes.TwoXs)
                                .background(colors.onSurface.copy(alpha = 0.45f))
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
                            // 拖拽 / 焦点态：极光青描边圈住 knob（无发光）
                            .then(
                                if (scrubbing) {
                                    Modifier.border(2.dp, media.base, CinefinShapes.TwoXs)
                                } else {
                                    Modifier
                                }
                            )
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
    /** 键边长：W12 反馈 A 起按键位版式收窄（刚好包住图标 + 一点边距） */
    size: Dp = 40.dp,
    /** 键内图标尺寸：与倍率键的图标同宽 */
    iconSize: Dp = 24.dp,
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
    /** W17 反馈⑤：非空时在图标下加一行 10sp 小字（工具区专用）； null = 纯图标键（中央五键 / 锁定键 / 全屏键 / 小窗工具行都用它）。 */
    label: String? = null,
) {
    val colors = LocalCinefinColors.current
    val media = LocalMediaColors.current
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val focused by interactionSource.collectIsFocusedAsState()
    val shape = CinefinShapes.Md
    val keyWidth = if (label != null) size + PLAYER_TOOL_KEY_LABEL_WIDTH_EXTRA_DP.dp else size
    val keyHeight = if (label != null) size + PLAYER_TOOL_KEY_LABEL_HEIGHT_EXTRA_DP.dp else size
    val drawnIconSize = if (label != null) iconSize * 0.82f else iconSize

    Box(
        contentAlignment = Alignment.Center,
        modifier =
            modifier
                .size(width = keyWidth, height = keyHeight)
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
                        pressed -> colors.scrim.copy(alpha = PLAYER_GLASS_PRESSED_ALPHA)
                        focused -> colors.stateHover
                        glass -> colors.scrim.copy(alpha = PLAYER_GLASS_ALPHA)
                        else -> Color.Transparent
                    }
                )
                // W12 反馈 A：所有控件外框加 1dp 描边（选中态用媒体色描边），未选中是极淡的月白
                .border(
                    1.dp,
                    when {
                        error -> colors.error.copy(alpha = 0.4f)
                        selected -> media.outline
                        glass -> colors.onSurface.copy(alpha = PLAYER_GLASS_BORDER_ALPHA)
                        else -> Color.Transparent
                    },
                    shape,
                )
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
                modifier = Modifier.size(drawnIconSize * 0.9f),
                color = colors.onSurface,
                strokeWidth = 2.dp,
            )
        } else {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    painter = painterResource(iconRes),
                    contentDescription = null,
                    tint =
                        when {
                            error -> colors.error
                            selected -> media.bright
                            else -> colors.onSurface
                        },
                    modifier = Modifier.size(drawnIconSize),
                )
                if (label != null) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = label,
                        style =
                            MaterialTheme.typography.labelSmall.copy(
                                fontSize = PLAYER_TOOL_KEY_LABEL_FONT_SIZE_SP.sp,
                                letterSpacing = 0.sp,
                            ),
                        color =
                            when {
                                error -> colors.error
                                selected -> media.bright
                                else -> colors.onSurface
                            },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
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

@Composable
private fun SkipSegmentChip(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    /*
     * W20（§6.1 提示条样式统一）：与顶栏徽标（PlayerOverlayBadge）/ 面板胶囊（PanelChip）共用同一套
     * 中性描边 token（outlineVariant）与 scrim 底，只把图标留给媒体强调色；
     * 旧实现用 media.outline 描边，在亮画面上比其它覆盖层控件更抢眼。
     */
    val colors = LocalCinefinColors.current
    val media = LocalMediaColors.current
    val shape = CinefinShapes.Full
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            modifier
                .clip(shape)
                .background(colors.scrim.copy(alpha = 0.8f))
                .border(1.dp, colors.outlineVariant, shape)
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
    /** 行尾附加动作（如队列的删除键 / 拖拽把手）；默认没有 */
    trailing: (@Composable () -> Unit)? = null,
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
        trailing?.invoke()
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
    /** 上一级面板（从「更多 / 播放设置」进来的子面板）；null = 一级面板，不画返回箭头 */
    onBack: (() -> Unit)? = null,
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
                if (onBack != null) {
                    PlayerIconButton(
                        iconRes = CoreR.drawable.ic_arrow_left,
                        contentDescription =
                            stringResource(PlayerR.string.player_controls_panel_back),
                        onClick = onBack,
                        glass = false,
                    )
                }
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
        PlayerPanel.Settings -> PlayerR.string.player_controls_settings
        PlayerPanel.Decode -> PlayerR.string.player_controls_label_decode
        PlayerPanel.Bitrate -> PlayerR.string.player_controls_label_bitrate
        PlayerPanel.None -> PlayerR.string.player_controls_settings
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
    pauseAfterItem: Boolean,
    onTogglePauseAfterItem: (Boolean) -> Unit,
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
            // 「播完暂停」（§1.7）：独立开关——当前一集播完停在片尾，不自动跳下一集
            PanelRow(
                label = stringResource(PlayerR.string.player_controls_pause_after_item),
                caption = stringResource(PlayerR.string.player_controls_pause_after_item_caption),
                selected = pauseAfterItem,
                onClick = { onTogglePauseAfterItem(!pauseAfterItem) },
            )
        }
    }
}

/**
 * 画面面板（§1.6 扩展）：比例（适应 / 裁剪 / 拉伸）+ 旋转 / 镜像 / 裁剪 / 去黑边。
 *
 * 比例沿用 `pref_player_resize_mode` 体系；几何变换写 `PlayerExtraPreferences` 并由 Activity 即时应用 （ExoPlayer
 * 走视图变换，mpv 走原生属性）。改完不自动关面板——这些项常要连着调，和字幕面板一致。
 */
@Composable
private fun AspectPanel(
    current: AspectMode,
    transform: PlayerVideoTransform,
    onTransformChange: (PlayerVideoTransform) -> Unit,
    onSelect: (AspectMode) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        PanelList {
            AspectMode.entries.forEach { mode ->
                PanelRow(
                    label = stringResource(mode.labelRes),
                    caption = stringResource(mode.captionRes),
                    selected = mode == current,
                    onClick = { onSelect(mode) },
                )
            }
            VideoTransformControls(transform = transform, onTransformChange = onTransformChange)
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
    /** W12 反馈 B：字幕模式 / 记忆选择并入本面板（唯一字幕入口），控制器由 Activity 持有 */
    controller: PlayerSettingsController,
    onSubtitleModeChanged: (String) -> Unit,
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
            // 字幕模式与「记住手动选择」（W12 反馈 B：从播放设置并入这里，全播放页只有这一个字幕入口）
            PanelTitle(stringResource(PlayerR.string.player_settings_subtitle_mode))
            val subtitleModes =
                listOf(
                    Constants.SubtitleMode.AUTO to PlayerR.string.player_settings_subtitle_auto,
                    Constants.SubtitleMode.ALWAYS to PlayerR.string.player_settings_subtitle_always,
                    Constants.SubtitleMode.OFF to PlayerR.string.player_settings_subtitle_off,
                )
            PanelChipRow(
                options =
                    subtitleModes.map { (value, labelRes) ->
                        stringResource(labelRes) to (controller.state.subtitleMode == value)
                    },
                onSelect = { index ->
                    val value = subtitleModes[index].first
                    controller.setSubtitleMode(value)
                    onSubtitleModeChanged(value)
                },
            )
            PanelSwitchRow(
                label = stringResource(PlayerR.string.player_settings_remember_track),
                checked = controller.state.rememberTrackSelection,
                onCheckedChange = { controller.setRememberTrackSelection(it) },
            )
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
internal fun PanelChip(
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
    /** W12 反馈 B：音轨语言优先级并入本面板（唯一音轨入口） */
    controller: PlayerSettingsController,
) {
    val colors = LocalCinefinColors.current
    Column(modifier = Modifier.fillMaxSize()) {
        PanelList {
            // 语言优先级（从播放设置并入）：决定自动选轨时优先挑哪条音轨
            PanelTitle(stringResource(PlayerR.string.player_settings_audio_language))
            PanelChipRow(
                options =
                    AudioLanguagePresets.map { (value, labelRes) ->
                        stringResource(labelRes) to (controller.state.audioLanguagePreset == value)
                    },
                onSelect = { index ->
                    controller.setAudioLanguage(AudioLanguagePresets[index].first)
                },
            )
            Text(
                text = stringResource(PlayerR.string.player_settings_audio_language_caption),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                modifier =
                    Modifier.padding(
                        horizontal = CinefinSpacing.Space5,
                        vertical = CinefinSpacing.Space1,
                    ),
            )
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
    player: Player,
    runtime: PlayerRuntime,
    title: String,
    sourceInfo: PlayerMediaInfo?,
) {
    /*
     * 双内核信息快照（§1.8）：媒体源元数据（Jellyfin）+ 内核实测值，内核优先。
     * mpv 的属性读取会走到 native，放到 IO 线程；ExoPlayer 读的是 currentTracks。
     * 两个内核能取到的字段不同 → 取不到统一显示「—」，不隐藏行、不崩。
     */
    var kernelInfo by remember { mutableStateOf<PlayerMediaInfo?>(null) }
    LaunchedEffect(player, runtime.currentIndex) {
        kernelInfo =
            withContext(Dispatchers.IO) { runCatching { readKernelMediaInfo(player) }.getOrNull() }
    }
    val info = mergePlayerMediaInfo(sourceInfo, kernelInfo)

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
            InfoRow(
                label = stringResource(PlayerR.string.player_controls_info_container),
                value = PlayerMediaInfoFormat.container(info?.container),
            )
            InfoRow(
                label = stringResource(PlayerR.string.player_controls_info_codec),
                value = PlayerMediaInfoFormat.codec(info?.videoCodec),
            )
            InfoRow(
                label = stringResource(PlayerR.string.player_controls_info_resolution),
                value = PlayerMediaInfoFormat.resolution(info?.width, info?.height),
            )
            InfoRow(
                label = stringResource(PlayerR.string.player_controls_info_bitrate),
                value = PlayerMediaInfoFormat.bitrate(info?.videoBitrate),
            )
            InfoRow(
                label = stringResource(PlayerR.string.player_controls_info_frame_rate),
                value = PlayerMediaInfoFormat.frameRate(info?.frameRate),
            )
            InfoRow(
                label = stringResource(PlayerR.string.player_controls_info_hdr),
                value = PlayerMediaInfoFormat.hdr(info?.hdr),
            )
            InfoRow(
                label = stringResource(PlayerR.string.player_controls_info_audio_format),
                value =
                    PlayerMediaInfoFormat.audio(
                        codec = info?.audioCodec,
                        channels = info?.audioChannels,
                        bitrate = info?.audioBitrate,
                        sampleRate = info?.audioSampleRate,
                    ),
            )
            InfoRow(
                label = stringResource(PlayerR.string.player_controls_info_file_size),
                value = PlayerMediaInfoFormat.fileSize(info?.fileSizeBytes),
            )
            InfoRow(
                label = stringResource(PlayerR.string.player_controls_info_path),
                value = PlayerMediaInfoFormat.path(info?.path),
            )
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
    onMove: (Int, Int) -> Unit,
    onRemove: (Int) -> Unit,
    onClear: () -> Unit,
) {
    val colors = LocalCinefinColors.current
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
        return
    }
    /*
     * 队列整理（反馈③）：与「选集 → 播放队列」页共用同一份实现——
     * 点行跳转 / 长按拖动排序 / 行尾删除 / 清空（保留正在播的条目）。
     */
    PlayerEpisodeQueueList(
        entries = entries,
        currentIndex = currentIndex,
        tab = PlayerContentTab.Queue,
        onSelect = onSelect,
        onMove = onMove,
        onRemove = onRemove,
        onClear = onClear,
        modifier = Modifier.fillMaxSize(),
    )
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
