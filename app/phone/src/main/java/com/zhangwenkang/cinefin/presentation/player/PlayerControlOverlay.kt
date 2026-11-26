package com.zhangwenkang.cinefin.presentation.player

import android.graphics.Bitmap
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
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
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.ui.AspectRatioFrameLayout
import coil3.compose.AsyncImage
import com.zhangwenkang.cinefin.R as AppR
import com.zhangwenkang.cinefin.core.R as CoreR
import com.zhangwenkang.cinefin.core.presentation.components.CinefinProgressVisuals
import com.zhangwenkang.cinefin.core.presentation.components.CinefinSlider
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinMotion
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinShapes
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinSpacing
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinTokens
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinType
import com.zhangwenkang.cinefin.core.presentation.theme.LocalCinefinColors
import com.zhangwenkang.cinefin.core.presentation.theme.LocalMediaColors
import com.zhangwenkang.cinefin.core.presentation.theme.auroraGradient
import com.zhangwenkang.cinefin.language.LanguageMatcher
import com.zhangwenkang.cinefin.models.FindroidSegment
import com.zhangwenkang.cinefin.player.core.domain.models.PLAYER_EXTRA_EPISODE_NUMBER
import com.zhangwenkang.cinefin.player.core.domain.models.PLAYER_EXTRA_SEASON_NUMBER
import com.zhangwenkang.cinefin.player.core.domain.models.PlaybackMediaIdentity
import com.zhangwenkang.cinefin.player.core.domain.models.PlayerChapter
import com.zhangwenkang.cinefin.player.core.domain.models.PlayerMediaInfo
import com.zhangwenkang.cinefin.player.core.domain.models.SleepTimerSpec
import com.zhangwenkang.cinefin.player.core.domain.models.SubtitleAppearance
import com.zhangwenkang.cinefin.player.core.domain.models.SubtitleLayout
import com.zhangwenkang.cinefin.player.core.domain.models.SubtitleStyle
import com.zhangwenkang.cinefin.player.core.playback.DevicePlaybackCapabilities
import com.zhangwenkang.cinefin.player.core.syncplay.SyncPlayControllerState
import com.zhangwenkang.cinefin.player.core.syncplay.SyncPlayGroup as CoreSyncPlayGroup
import com.zhangwenkang.cinefin.player.local.R as PlayerR
import com.zhangwenkang.cinefin.player.local.audio.AudioDelayProcessor
import com.zhangwenkang.cinefin.player.local.domain.PlayerVideoTransform
import com.zhangwenkang.cinefin.player.local.domain.SeekFailureReason
import com.zhangwenkang.cinefin.player.local.domain.SeekState
import com.zhangwenkang.cinefin.player.local.domain.SleepTimerController
import com.zhangwenkang.cinefin.player.local.domain.playerMediaInfoSections
import com.zhangwenkang.cinefin.player.local.domain.seekDisplayPositionMs
import com.zhangwenkang.cinefin.player.local.mpv.MPVPlayer
import com.zhangwenkang.cinefin.player.local.presentation.PlayerDebugStats
import com.zhangwenkang.cinefin.player.local.presentation.PlayerViewModel
import com.zhangwenkang.cinefin.player.local.presentation.readKernelMediaInfo
import com.zhangwenkang.cinefin.player.local.session.PLAYER_ERROR_STARTUP_SOURCE_FAILED
import com.zhangwenkang.cinefin.player.local.session.PlayerErrorInfo
import com.zhangwenkang.cinefin.player.local.subtitle.PlayerSubtitleController
import com.zhangwenkang.cinefin.player.local.subtitle.SubtitleSecondaryUnavailableReason
import com.zhangwenkang.cinefin.player.local.syncplay.SyncPlaySessionCoordinator
import com.zhangwenkang.cinefin.player.local.syncplay.SyncPlayTransportGate
import com.zhangwenkang.cinefin.presentation.film.components.LumenTextShadow
import com.zhangwenkang.cinefin.presentation.film.components.lumenTextShadow
import com.zhangwenkang.cinefin.settings.domain.Constants
import com.zhangwenkang.cinefin.settings.domain.PlaybackSpeedOptions
import com.zhangwenkang.cinefin.syncplay.SyncPlayCapability
import com.zhangwenkang.cinefin.syncplay.SyncPlayConnectionState
import java.util.UUID
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/* =========================================================================
Cinefin · 播放控制层（Compose）

设计解读：私人影音库的沉浸播放层（S1 · 流光「内容即光源」+ App 主题设计系统 v1.0）。
          画面是唯一光源：控制层只在需要时浮现，面板从右侧盖在画面上而不是挤压画面。
颜色锁：  影视域媒体色（琥珀 MediaFilm）只出现在进度、进行中与激活面板上，且必须融进
          控件本体（填充 / 描边 / 图标 / 文字）；其余一律中性色（CinefinColors）。
          禁止独立色块 / 色点 / 发光，禁止任何 Color(0x…) 字面量。
形状锁：  主播放键 Xl、工具键 / chip Button、面板 Card（CinefinShapes 语义槽位，W3-T4 起）。
动效锁：  控制层 280ms 淡入 + 弹簧上浮 12dp（可中断），退场弹簧让位；面板 scale0.92→1 + 菜单项错峰；
          播放键图标 Crossfade + scale 过冲；暂停降亮 280ms。只动 transform / opacity / color。
可读性：  上下渐隐遮罩 + 覆盖层工具键玻璃底（token 化），保证任何画面上文字与图标都可读。
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

/**
 * 暂停降亮 + 四边暗角（VIDEO-30）。
 *
 * 暂停时画面叠一层 `scrim @ 22%`，同时四边径向暗角把视线收回画面中心（边缘黑 35%）；两者共用一条 [CinefinMotion.Player] /
 * [CinefinMotion.Standard] 的透明度动画，恢复播放即撤。它画在控制层下面，与 控制层是否可见完全解耦——控制层 4 秒收起后降亮仍在。
 */
@Composable
private fun PlayerPauseDim(paused: Boolean, modifier: Modifier = Modifier) {
    val scrim = LocalCinefinColors.current.scrim
    val dim by
        animateFloatAsState(
            targetValue = if (paused) 1f else 0f,
            animationSpec = tween(CinefinMotion.Player, easing = CinefinMotion.Standard),
            label = "playerPauseDim",
        )
    Canvas(modifier = modifier) {
        drawRect(color = scrim, alpha = PLAYER_PAUSE_DIM_ALPHA * dim)
        drawRect(
            brush =
                Brush.radialGradient(
                    colors =
                        listOf(
                            Color.Transparent,
                            scrim.copy(alpha = PLAYER_PAUSE_EDGE_DIM_ALPHA),
                        ),
                    center = center,
                    // 半径取半对角线：四角最暗、四边中段渐隐，形成一个圆形压边
                    radius = hypot(size.width, size.height) / 2f,
                ),
            alpha = dim,
        )
    }
}

/*
 * 覆盖层控件「玻璃框」的不透明度（W12 反馈 A）已迁入设计 token：
 * [CinefinTokens.PlayerGlassAlpha]（底 0.28）/ [CinefinTokens.PlayerGlassPressedAlpha]（按下 0.44），
 * 描边统一走 `colors.glassBorder`（原私有 0.16 常量废除，VIDEO-29）。
 */

/**
 * 控制层自动淡出时长（VIDEO-28）：4 秒无操作收起。
 *
 * W17 的 3.5 s 是估值；K1 定稿校准到 4 s，各形态共用同一个阈值，「菜单关闭后重新计时」的行为不变。
 */
private const val PLAYER_CONTROLS_AUTO_HIDE_MS = 4000L

/** 播放键图标切换过冲峰值（VIDEO-29：容器不缩放，只放大图标 1→约 1.18→1）。 */
private const val PLAYER_PLAY_KEY_OVERSHOOT = 1.18f

/** 面板菜单项错峰入场的上限项数（VIDEO-31）：第 8 项之后与控制层同时入场，不再拖长。 */
private const val PLAYER_PANEL_STAGGER_MAX_ITEMS = 8

/** 暂停降亮：画面叠 scrim 22%（VIDEO-30）。 */
private const val PLAYER_PAUSE_DIM_ALPHA = 0.22f

/** 暂停暗角：画面四边径向到黑的边缘透明度（VIDEO-30）。 */
private const val PLAYER_PAUSE_EDGE_DIM_ALPHA = 0.35f

/** 章节吸附落位时 knob 的起始缩放（VIDEO-31：0.9→1 微弹）。 */
private const val PLAYER_SEEKBAR_SNAP_KNOB_SCALE = 0.9f

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

/**
 * W67b：预计结束时刻（「18:19 结束」= 13sp MonoDataSmall 的 5 位数字 + 空格 + 2 个中文字，两侧各 8dp 间距）在 底栏宽度预算里的估宽（dp）。只用于
 * [PlayerControlSpec.bottomRowWidthDp] 的分级估算。
 */
private const val PLAYER_END_TIME_WIDTH_DP = 88f

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
    /** W4-T8e：SyncPlay 入口面板（未入组 = 创建 / 加入；已入组 = 组名 + 成员 + 离开组） */
    SyncPlay,
}

/**
 * 控制层版式规格（W12 终版布局 + 反馈 A 控件样式统一）。
 *
 * 终版（用户确认，勿再变动）：进度条一行 = 当前时间 · 进度条 · 总时长；进度条下方左侧 5 键 = 音轨 / 字幕 / 倍率 / 解码 / 详细信息，右侧 = 全屏键；右上角 5 键 =
 * 画中画 / 睡眠 / 选集 / 画面 / 设置；锁定键贴画面区右缘垂直居中；中央五键居中。
 *
 * 窄屏（<600dp，手机 / 分屏 / 小窗）整体收一档，保证工具行 + 全屏键一行不越界（极窄窗由横向滚动兜底）。
 *
 * W17 起工具行**5 键恒定齐全**（见 [playerToolLabelsVisible]）；W4-T23 起「码率」随面板迁到设置页：全屏 / 宽度充足 = 图标下加小字； 窄屏 /
 * Compact = 只留图标。倍率键只显示图标，当前值由 1× 徽标显示。
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
     * 底栏一行 = 5 个工具键 + 1× 徽标 + 右下全屏键 + 7 个间距（含全屏键前的留白，W13 反馈③新增 1×）。 1× 徽标 W14
     * 起按文本自适应（不再占一个键框），按最宽档估值计；极端窄窗由工具行的横向滚动兜底。
     */
    val bottomRowWidthDp: Float
        get() = bottomRowWidthDp(showLabels = false)

    /**
     * W17：底栏一行宽度预算。[showLabels] = true 时 5 个工具键按「图标 + 小字」的加宽键框计 （全屏键恒为纯图标，不参与加宽）。
     *
     * W67b：预计结束时刻移到这一行后，[withEndTime] = true 时再加「1× 徽标 + 结束时间文本 + 两侧 8dp 间距」， 供
     * [playerBottomKeysForWidth] 做 5 键 / 4 键分级。
     */
    fun bottomRowWidthDp(showLabels: Boolean, withEndTime: Boolean = false): Float {
        val toolKeyWidth =
            if (showLabels) {
                toolKeySizeDp + PLAYER_TOOL_KEY_LABEL_WIDTH_EXTRA_DP
            } else {
                toolKeySizeDp
            }
        val base =
            toolKeyWidth * 5f +
                toolKeySizeDp +
                maxOf(toolKeyWidth, PLAYER_SPEED_BADGE_WIDTH_DP) +
                keyGapDp * 7f
        return if (withEndTime) base + PLAYER_END_TIME_WIDTH_DP else base
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
 * 进度条下方左侧 5 键的**固定顺序**（W12 终版布局，勿再变动；W4-T23 删码率键）：音轨 · 字幕 · 倍率 · 解码 · 详细信息。
 *
 * 抽成有序表是为了让「顺序」可被单测钉住——这一版布局是多轮确认的最终版，改顺序就是回归。
 */
internal enum class PlayerBottomKey {
    Audio,
    Subtitle,
    Speed,
    Decode,
    Info,
}

internal val PLAYER_BOTTOM_KEY_ORDER: List<PlayerBottomKey> =
    listOf(
        PlayerBottomKey.Audio,
        PlayerBottomKey.Subtitle,
        PlayerBottomKey.Speed,
        PlayerBottomKey.Decode,
        PlayerBottomKey.Info,
    )

/**
 * 工具区「宽度充足」的阈值（W17 沿用 W13 的 600dp）：与 [playerControlSpec] 的窄屏档、 `PlayerFormFactor` 的 Phone → Tablet
 * 分档同源。低于它的非全屏窗口 = 「窄屏」，工具键只留图标； 达到它（或全屏 / 平板 / 折叠）才在图标下加文字。
 */
internal const val PLAYER_TOOL_ROW_WIDE_WIDTH_DP = 600f

/**
 * 工具区（右上 5 键 + 左下 5 键）是否在图标下显示小字（W17 用户确认）。
 *
 * 判据：Compact（自由窗口 / 分屏窄宽）一律只留图标；全屏（`PlayerActivity.fullscreenMode`）一律显示； 非全屏时宽度 ≥ 600dp 或平板 /
 * 折叠展开形态显示。非全屏手机形态（窄屏）只留图标—— 38dp 键框放不下文字，硬塞会撑破底栏。
 *
 * 键本身（5 键）永远都在，文字只是可读性增强：W17 起「解码」不再从工具行隐藏，因此设置面板里 W13 加的兜底入口已删除（用户确认不留兜底入口）； 码率入口 W4-T23 起只在设置页。
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

/**
 * W67b：左下工具行的键集分级。
 *
 * 预计结束时刻移到该行后，窄窗优先保证结束时间完整：行宽预算（键 + 1× 徽标 + 结束时刻 + 右下全屏键 + 间距 + 左右留白，见
 * [PlayerControlSpec.bottomRowWidthDp]）放不下 5 键时，省略「解码」键—— 只剩 音轨 · 字幕 · 倍率 · 详细信息 + 1× + 结束时刻；宽窗 /
 * 全屏 / 平板照旧全量 5 键。 返回恒为 [PLAYER_BOTTOM_KEY_ORDER] 的子序列（顺序不变）。
 */
internal fun playerBottomKeysForWidth(
    widthDp: Float,
    showLabels: Boolean,
    /** 预计结束时刻是否存在（时长未知 / 直播 → false，不占位，也就不需要为它让宽） */
    hasEndTime: Boolean,
    spec: PlayerControlSpec,
): List<PlayerBottomKey> {
    val budget = spec.bottomRowWidthDp(showLabels, hasEndTime) + spec.toolRowPaddingDp * 2f
    if (budget <= widthDp) return PLAYER_BOTTOM_KEY_ORDER
    return PLAYER_BOTTOM_KEY_ORDER.filter { it != PlayerBottomKey.Decode }
}

/** 右上角 5 键的**固定顺序**（W12 终版布局，勿再变动）：画中画 · 睡眠 · 选集 · 画面 · 设置。 */
internal enum class PlayerTopKey {
    Pip,
    Sleep,
    Episode,
    Aspect,
    /** W4-T8e：SyncPlay 入口（定稿位置：画面之后、设置之前） */
    SyncPlay,
    Settings,
}

internal val PLAYER_TOP_KEY_ORDER: List<PlayerTopKey> =
    listOf(
        PlayerTopKey.Pip,
        PlayerTopKey.Sleep,
        PlayerTopKey.Episode,
        PlayerTopKey.Aspect,
        PlayerTopKey.SyncPlay,
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
    var position by mutableLongStateOf(C.TIME_UNSET)
    var duration by mutableLongStateOf(0L)
    var buffered by mutableLongStateOf(C.TIME_UNSET)
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

    fun sync(player: Player, lastConfirmedPositionMs: Long?) {
        val confirmedPosition = PlayerViewModel.confirmedPosition(player) ?: lastConfirmedPositionMs
        position = confirmedPosition ?: C.TIME_UNSET
        duration = player.duration.let { if (it > 0L) it else 0L }
        buffered =
            PlayerViewModel.confirmedBufferedPosition(player, confirmedPosition) ?: C.TIME_UNSET
        isPlaying = player.isPlaying
        /*
         * W76-B11b：除了「正在缓冲」，**还没有任何可播条目的准备窗口**也算加载中。
         *
         * 进播放页到第一个媒体项交给播放器之间（系列 / 季 / 集级入口要先解析起播集），播放器是 IDLE、没有媒体项，
         * 以前这段只有 `00:00/00:00` + 队列为空 + 无提示，用户以为点了没反应。全屏唯一的加载图标就在播放键上
         * （W11 反馈⑤），这里把它扩到准备窗口。
         */
        isBuffering = player.playbackState == Player.STATE_BUFFERING || player.mediaItemCount == 0
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
private fun rememberPlayerRuntime(
    player: Player,
    lastConfirmedPositionMs: Long?,
): PlayerRuntime {
    val runtime = remember { PlayerRuntime() }
    val latestLastConfirmedPositionMs = rememberUpdatedState(lastConfirmedPositionMs)
    DisposableEffect(player) {
        val listener =
            object : Player.Listener {
                override fun onEvents(p: Player, events: Player.Events) {
                    runtime.sync(p, latestLastConfirmedPositionMs.value)
                }
            }
        player.addListener(listener)
        runtime.sync(player, latestLastConfirmedPositionMs.value)
        onDispose { player.removeListener(listener) }
    }
    LaunchedEffect(player) {
        while (true) {
            runtime.sync(player, latestLastConfirmedPositionMs.value)
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
    /** W4-T8d SyncPlay 闸门：入组期间播放键只发组命令；null = 未装配（按原有直驱路径）。 */
    syncPlayGate: SyncPlayTransportGate? = null,
    /** W4-T8e：SyncPlay 会话源（入口面板 / 状态胶囊）；null = 未装配，入口不出现。 */
    syncPlay: SyncPlaySessionCoordinator? = null,
    /** W4-T8e：非阻断提示条文案（宿主统一计时，到点置空）。 */
    syncPlayNoticeText: String? = null,
    /** W4-T8e：面板动作由宿主执行（建组要先暂停本地并把队列推给服务器）。 */
    onCreateSyncPlayGroup: () -> Unit = {},
    onJoinSyncPlayGroup: (UUID) -> Unit = {},
    onLeaveSyncPlayGroup: () -> Unit = {},
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
    /** 单列表「主」chip：Exo 为源序号，mpv 为原生 sid */
    onAssignPrimarySubtitle: (Int) -> Unit,
    /** 单列表「次」chip：两个内核都为 Jellyfin 源序号 */
    onAssignSecondarySubtitle: (Int) -> Unit,
    onClearPrimarySubtitle: () -> Unit,
    onClearSecondarySubtitle: () -> Unit,
    onSwapSubtitles: () -> Unit,
    onAutoSelectSubtitles: () -> Unit,
    /** 字幕延迟步进（±50ms，见 `PlayerSubtitleController.DELAY_STEP_MS`） */
    onAdjustSubtitleDelay: (Long) -> Unit,
    /** 字幕延迟归零 */
    onResetSubtitleDelay: () -> Unit,
    /** 字幕外观调整（主 / 次 / 共享布局，一次提交并自动保存） */
    onUpdateSubtitleAppearance: (SubtitleAppearance) -> Unit,
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
    /** W2-T13（需求 #2）：设置面板顶部「全部播放设置 →」，跳设置页播放组 */
    onOpenAllSettings: () -> Unit = {},
    /** 画面调整（§1.6）当前状态：旋转 / 镜像 / 裁剪 / 去黑边 */
    videoTransform: PlayerVideoTransform,
    /** 解码策略（硬解优先 / 仅软解）：由 Activity 落偏好并重启播放页让两个内核重新创建实例（W12 反馈 B） */
    onSelectDecodeMode: (String) -> Unit = {},
    /** 失败自动回退开关（W19）：开 = 既有回退链；关 = 强制所选内核，失败只提示错误 */
    onAutoFallbackChange: (Boolean) -> Unit = {},
    /** mpv 换硬件解码：即时写 mpv 属性 */
    onSelectMpvHwdec: (String) -> Unit,
    /** W4-T3b：设备播放能力快照（进程级只探测一次，由宿主注入）；解码面板「设备能力」区数据源 */
    deviceCapabilities: DevicePlaybackCapabilities = DevicePlaybackCapabilities.Unknown,
    /** W4-T3b：色调映射档位切换（仅 mpv 生效）：偏好由面板写，宿主收到回调后即时写 mpv 运行时属性 */
    onSelectToneMapping: (String) -> Unit = {},
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
    /** W27 侧载字幕：打开系统文件选择器导入（.srt / .ass / .vtt） */
    onImportSubtitle: () -> Unit = {},
    /** W27 侧载字幕：移除一条（id 与字幕选择回调同一套 id） */
    onRemoveSideloadedSubtitle: (Int) -> Unit = {},
    /** W27 PlayerDebugOverlay：实时数据源（内部在 IO 线程读取） */
    debugStatsProvider: suspend () -> PlayerDebugStats = { PlayerDebugStats() },
    /** 播放失败后的「重试」：清错误并从当前进度重新拉流 */
    onRetry: () -> Unit,
    /** 「换内核」：ExoPlayer ⇄ mpv（由 Activity 写偏好并重启播放页生效） */
    onSwitchBackend: (String) -> Unit,
    /** VIDEO-50：本地库源失效时错误卡的「重新扫描媒体库」（宿主触发本地库重扫）。 */
    onRescanLocalLibrary: () -> Unit = {},
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
            debugOverlayVisible: Boolean,
        ) -> Unit =
        { _, _, _, _, _, _, _ ->
        },
    /** 实测顶栏 / 底栏高度（px）回传给命中区：控件高度一变，触摸分区跟着变（§9 踩坑） */
    onTopBarHeight: (Int) -> Unit = {},
    onBottomBarHeight: (Int) -> Unit = {},
    /** 实测中央簇尺寸（px）回传给命中区：窄窗收尺寸后命中块跟着收，画面其余部分仍留给手势 */
    onCenterClusterSize: (Int, Int) -> Unit = { _, _ -> },
    /**
     * 锁定键 / 解锁键实测矩形（px，Compose 根坐标）回传给命中区。
     *
     * 锁定键尺寸随版式规格收放、解锁键又落在画面区（不是整个容器）垂直中点上，命中区必须和视觉同源（W4-T22）。
     */
    onLockKeyRect: (left: Float, top: Float, right: Float, bottom: Float) -> Unit = { _, _, _, _ ->
    },
    onUnlockKeyRect: (left: Float, top: Float, right: Float, bottom: Float) -> Unit =
        { _, _, _, _ ->
        },
    /** 睡眠定时状态（W55 统一状态源：音乐 / 视频共用同一计时器） */
    sleepState: SleepTimerController.State = SleepTimerController.State(),
    /** 选择睡眠定时分钟；null = 取消 */
    onSelectSleepMinutes: (Int?) -> Unit = {},
    /** W67b：竖屏内容区（选集 / 队列）收起 —— 由内容区右上角 × 触发，画面区铺满整窗 */
    onCollapseBottomContent: () -> Unit = {},
    /** W67b：竖屏内容区再次展开 —— 由顶栏「选集」键在收起态触发 */
    onExpandBottomContent: () -> Unit = {},
    /** 全屏（W11 反馈⑥）：收起常驻内容栏 + 强制横屏；同一个键按状态换图标 */
    isFullscreen: Boolean = false,
    onToggleFullscreen: () -> Unit = {},
    /** W86-C：seek 意图只交给 ViewModel reducer；控制层不再直接落点。 */
    onSeekRequest: (Long) -> Unit = {},
    /** W73（#7）：进度条在「时长未知」窗口的拖动比例（就绪后按真实时长换算落点）。 */
    onSeekFractionRequest: (Float) -> Unit = {},
    /** W73（#7）：±键 / 快进快退的相对增量（未就绪排队 + 转码超窗重开会话）。 */
    onSeekRelativeRequest: (Long) -> Unit = {},
    onPreviousMediaItem: () -> Unit = {},
    onNextMediaItem: () -> Unit = {},
    onSelectMediaItem: (Int) -> Unit = {},
    onRetrySeek: () -> Unit = {},
) {
    val playbackSession = uiState.playbackSession
    val runtime =
        rememberPlayerRuntime(
            player,
            playbackSession.lastConfirmedPositionMs?.takeIf { player !is MPVPlayer },
        )
    val displayPositionMs =
        if (runtime.position < 0L && uiState.seekState !is SeekState.Seeking) C.TIME_UNSET
        else
            seekDisplayPositionMs(
                uiState.seekState,
                runtime.position,
                SystemClock.elapsedRealtime(),
            )
    val seekFailed =
        (uiState.seekState as? SeekState.Failed)?.takeIf { playbackSession.showSeekFailure }
    val showPlayerError = uiState.playerError != null && playbackSession.showPlayerError
    val progressRecovery = playbackSession.progressRecovery
    val progressStatusText = progressRecovery?.let {
        stringResource(resolvePlaybackRecoveryTitle(it.kind).stringRes())
    }
    val recoveryPresentation =
        playbackRecoveryPresentation(
            showPlayerError = showPlayerError,
            seekFailed = seekFailed != null,
            progressRecovery = progressRecovery != null,
        )
    val pausesPlayback = playbackSession.pauseWhenReady
    /*
     * W4-T8d SyncPlay 闸门：入组期间播放键只发组命令（不直驱本地引擎），出组恢复原有直驱路径。
     * 读 `ownsTransport.value` 放在点击回调里（不参与组合阶段的重组判定）。
     */
    val requestPlayPause: (Boolean) -> Unit = { play ->
        val gate = syncPlayGate
        if (gate?.ownsTransport?.value == true) {
            gate.requestPlay(play)
        } else if (play) {
            player.play()
        } else {
            player.pause()
        }
    }
    val seekPending = uiState.seekState is SeekState.Seeking

    /*
     * W4-T8e：SyncPlay 面板与胶囊的会话面。全部收集都无条件调用（syncPlay 为空时退到本地兜底流），
     * 避免「条件式 collect」在组合阶段换订阅。入口可见性先探一次能力：服务器不支持就整颗键隐藏（定稿：不置灰）。
     */
    val fallbackSyncPlayState = remember { MutableStateFlow(SyncPlayControllerState.NotInGroup) }
    val fallbackSyncPlayGroup = remember { MutableStateFlow<CoreSyncPlayGroup?>(null) }
    val fallbackSyncPlayConnection = remember { MutableStateFlow<SyncPlayConnectionState?>(null) }
    val syncPlayState by (syncPlay?.state ?: fallbackSyncPlayState).collectAsStateWithLifecycle()
    val syncPlayGroup by
        (syncPlay?.activeGroup ?: fallbackSyncPlayGroup).collectAsStateWithLifecycle()
    val syncPlayConnection by
        (syncPlay?.connectionState ?: fallbackSyncPlayConnection).collectAsStateWithLifecycle()
    var syncPlayEntryAvailable by remember { mutableStateOf(false) }
    LaunchedEffect(syncPlay) {
        syncPlayEntryAvailable = syncPlay?.probeCapability() is SyncPlayCapability.Available
    }
    val syncPlayCapsule = resolveSyncPlayCapsule(syncPlayState, syncPlayConnection)
    var lastSyncPlayNotice by remember { mutableStateOf("") }
    LaunchedEffect(syncPlayNoticeText) { syncPlayNoticeText?.let { lastSyncPlayNotice = it } }
    /*
     * W67 补充：预计结束时刻。播放中每秒推进「当前时钟」→ 结束时刻稳定跟随播放推进；暂停后时钟冻结在暂停点
     * （自洽口径：若就此继续，将在此刻结束），跳转 / 拖动后按冻结时钟重算。
     */
    var clockNowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(runtime.isPlaying) {
        clockNowMs = System.currentTimeMillis()
        while (runtime.isPlaying) {
            delay(1000L)
            clockNowMs = System.currentTimeMillis()
        }
    }
    val context = LocalContext.current
    val clockStyle =
        remember(settingsController.state.clockFormat) {
            resolveClockStyle(
                settingsController.state.clockFormat,
                Settings.System.getString(context.contentResolver, Settings.System.TIME_12_24),
            )
        }
    val endTimeText =
        if (runtime.duration > 0L && displayPositionMs >= 0L) {
            stringResource(
                AppR.string.player_end_time_label,
                formatClockTime(
                    estimatedEndTimeMs(clockNowMs, displayPositionMs, runtime.duration),
                    clockStyle,
                ),
            )
        } else {
            null
        }
    var panel by remember { mutableStateOf(PlayerPanel.None) }
    // 抽屉退场动画期间保留最后一个面板的内容，避免「滑走的是一块空板」
    var lastPanel by remember { mutableStateOf(PlayerPanel.Speed) }
    // 从「更多」进的子面板给一个返回箭头，回到「更多」；从主界面工具键进的没有上一级
    var panelBackTarget by remember { mutableStateOf<PlayerPanel?>(null) }
    LaunchedEffect(panel) { if (panel != PlayerPanel.None) lastPanel = panel }
    // 打开期间直接用当前面板（首帧不闪「更多」）；关闭后交给 lastPanel 走退场动画
    val drawerPanel = if (panel == PlayerPanel.None) lastPanel else panel
    var aspect by remember { mutableStateOf(AspectMode.of(initialResizeMode)) }
    var skipChipVisible by remember { mutableStateOf(true) }
    // W27 PlayerDebugOverlay：长按标题打开，面板上的关闭键关掉
    var debugOverlayVisible by remember { mutableStateOf(false) }

    LaunchedEffect(
        controls.visible,
        panel,
        controls.locked,
        showPlayerError,
        seekFailed,
        seekPending,
        playbackSession.loading,
        skipChipVisible,
        debugOverlayVisible,
    ) {
        onRegionsChanged(
            controls.visible,
            panel != PlayerPanel.None,
            controls.locked,
            showPlayerError,
            // 缓冲期间承载视图要保持合成：控件整层隐藏时还要画那一个独立的缓冲圈（反馈⑤）
            playbackSession.loading,
            // W20（§6.1）：跳过提示条独立于控制层，可见时承载视图必须保持合成
            skipChipVisible && uiState.currentSegment != null && !controls.locked,
            // W27：调试面板独立于控制层，打开期间承载视图必须保持合成
            debugOverlayVisible,
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
    LaunchedEffect(showPlayerError, seekFailed, recoveryPresentation.forceControlsVisible) {
        if (recoveryPresentation.forceControlsVisible) {
            controls.show()
        }
    }

    // 4 秒无操作自动淡出（VIDEO-28，[PLAYER_CONTROLS_AUTO_HIDE_MS]）：播放中、没有面板、没有错误时才淡出；
    // 小窗与折叠半开的控制区是常驻操作面（画面小、手势难落），不参与淡出
    LaunchedEffect(
        controls.visible,
        runtime.isPlaying,
        panel,
        showPlayerError,
        seekFailed,
        seekPending,
        layout.chrome,
    ) {
        if (
            controls.visible &&
                panel == PlayerPanel.None &&
                runtime.isPlaying &&
                recoveryPresentation.canAutoHideControls &&
                !seekPending &&
                !layout.isCompact &&
                layout.chrome != PlayerChromeLayout.FoldHalfOpen
        ) {
            delay(PLAYER_CONTROLS_AUTO_HIDE_MS)
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
     * W17 反馈⑤：工具区（右上 5 键 + 左下 5 键）是否在图标下加小字。
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
                positionMs = displayPositionMs,
                durationMs = runtime.duration,
                bufferedMs = runtime.buffered,
                endTimeText = endTimeText,
                chapters = if (showChapterMarkers) uiState.currentChapters else emptyList(),
                trickplayIntervalMs = uiState.trickplayIntervalMs,
                trickplayVersion = uiState.trickplayVersion,
                trickplayFrameAt = trickplayFrameAt,
                speed = runtime.speed,
                sleepActive = sleepState.active,
                progressStatusText = progressStatusText,
                subtitleEnabled = hasSelectedTrack(runtime.tracks, C.TRACK_TYPE_TEXT),
                decodeActive =
                    settingsController.state.decodeMode == PlayerViewModel.DECODE_MODE_SOFTWARE ||
                        settingsController.state.backend == PlayerViewModel.PLAYER_BACKEND_MPV,
                spec = spec,
                // W67b：结束时间进了工具行——窄窗按宽度预算省略解码键，优先保证 1× + 结束时间完整
                bottomKeys =
                    playerBottomKeysForWidth(
                        widthDp = (layout.windowWidthDp - sidePanelInset.value).coerceAtLeast(0f),
                        showLabels = toolLabelsVisible,
                        hasEndTime = endTimeText != null,
                        spec = spec,
                    ),
                isFullscreen = isFullscreen,
                // W17：5 键恒定齐全；文字只在窄屏以外的形态出现
                showLabels = toolLabelsVisible,
                onSeek = onSeekRequest,
                onSeekFraction = onSeekFractionRequest,
                onScrubStart = { controls.show() },
                onAudio = { navigatePanel(PlayerPanel.Audio) },
                onSubtitle = { navigatePanel(PlayerPanel.Subtitle) },
                onSpeed = { navigatePanel(PlayerPanel.Speed) },
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
     * 右上角 5 键 = 画中画 · 睡眠 · 选集 · 画面 · 设置；进度条下一行 = 左 5 键
     * （音轨 · 字幕 · 倍率 · 解码 · 详细信息）+ 右下全屏键；中央五键；锁定键贴画面区右缘垂直居中。
     */
    val toolCluster: @Composable () -> Unit = {
        PlayerToolCluster(
            isPipSupported = isPipSupported,
            // 画面键的激活态：比例不是「适应屏幕」，或做过旋转 / 镜像 / 裁剪 / 去黑边
            aspectActive =
                aspect.resizeMode != AspectMode.Fit.resizeMode || videoTransform.hasAdjustments,
            queueActive = hasSidePanel && sidePanelExpanded,
            sleepActive = sleepState.active,
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
                when {
                    hasSidePanel -> onToggleSidePanel
                    // W67b：竖屏内容区被 × 收起后，选集键先把内容区展开回来（再按一次才进队列面板）
                    layout.chrome == PlayerChromeLayout.SplitPortrait &&
                        !layout.bottomContentExpanded -> onExpandBottomContent
                    else -> {
                        { navigatePanel(PlayerPanel.Queue) }
                    }
                },
            onOpenSettings = { navigatePanel(PlayerPanel.Settings) },
            // W4-T8e：SyncPlay 入口（画面之后、设置之前；服务器不支持时整颗键隐藏）
            syncPlayAvailable = syncPlayEntryAvailable,
            onOpenSyncPlay = { navigatePanel(PlayerPanel.SyncPlay) },
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
        // 竖屏窗口：画面区顶边即窗口顶边，顶栏要让出状态栏 / 摄像头挖孔
        val protectTopInset = maxWidth < maxHeight
        // 面板抽屉宽度：手机 / 窄窗整宽；宽屏取 46%（360–560dp）。抽屉盖在画面上，不改画面布局
        val drawerWidth =
            if (maxWidth < 420.dp) maxWidth else (maxWidth * 0.46f).coerceIn(360.dp, 560.dp)

        if (layout.isCompact) {
            // 小窗：标题行 + 工具行 + 细进度条。「更多」取消后，工具行横向可滚，兜住全部入口（不缩水也不越界）
            PlayerCompactBar(
                isPlaying = pausesPlayback,
                buffering = playbackSession.loading,
                title = uiState.currentItemTitle.ifEmpty { runtime.title },
                positionMs = displayPositionMs,
                durationMs = runtime.duration,
                bufferedMs = runtime.buffered,
                chapters = if (showChapterMarkers) uiState.currentChapters else emptyList(),
                trickplayIntervalMs = uiState.trickplayIntervalMs,
                trickplayVersion = uiState.trickplayVersion,
                trickplayFrameAt = trickplayFrameAt,
                isFullscreen = isFullscreen,
                onPlayPause = { requestPlayPause(!pausesPlayback) },
                onPrevious = onPreviousMediaItem,
                // W74 U6：步进用时现读（设置页改完立即生效），不用实例创建时的快照
                onRewind = { onSeekRelativeRequest(-settingsController.seekBackIncrementMs) },
                onForward = { onSeekRelativeRequest(settingsController.seekForwardIncrementMs) },
                onNext = onNextMediaItem,
                onSeek = onSeekRequest,
                onSeekFraction = onSeekFractionRequest,
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
                        onOpenDecode = { navigatePanel(PlayerPanel.Decode) },
                        decodeActive =
                            settingsController.state.decodeMode ==
                                PlayerViewModel.DECODE_MODE_SOFTWARE ||
                                settingsController.state.backend ==
                                    PlayerViewModel.PLAYER_BACKEND_MPV,
                        onSpeed = { navigatePanel(PlayerPanel.Speed) },
                        onSleep = { navigatePanel(PlayerPanel.Sleep) },
                        onPip = onPip,
                        onLock = { controls.setLock(true) },
                        // W4-T8e：SyncPlay 入口（与画面区同序：画面之后、设置之前）
                        syncPlayAvailable = syncPlayEntryAvailable,
                        onOpenSyncPlay = { navigatePanel(PlayerPanel.SyncPlay) },
                    )
                },
                modifier = Modifier.align(Alignment.BottomCenter),
                onHeightChanged = onBottomBarHeight,
            )
        } else {
            Box(
                modifier = Modifier.width(videoWidth).height(videoHeight).align(Alignment.TopStart)
            ) {
                // 暂停降亮 + 暗角（VIDEO-30）：画面上的一层，与控制层可见性解耦，控制层收起后仍在
                PlayerPauseDim(
                    paused = !runtime.isPlaying,
                    modifier = Modifier.fillMaxSize(),
                )
                AnimatedVisibility(
                    visible = controls.visible,
                    /*
                     * 流光：控制层淡入 200ms + 上浮 12dp 走可中断弹簧（VIDEO-28/30）；
                     * 退场淡出 150ms 快速让位、位移临界阻尼弹簧一次到位。全部走 AnimatedVisibility，
                     * 连点显隐时新过渡直接接管旧过渡，不会闪跳。
                     */
                    enter =
                        fadeIn(tween(CinefinMotion.Fast, easing = CinefinMotion.Standard)) +
                            slideInVertically(
                                animationSpec = CinefinMotion.SpringControlInOffset,
                                initialOffsetY = { with(density) { 12.dp.roundToPx() } },
                            ),
                    exit =
                        fadeOut(tween(150, easing = CinefinMotion.Accelerate)) +
                            slideOutVertically(
                                animationSpec = CinefinMotion.SpringControlOutOffset
                            ),
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        PlayerTopBar(
                            title =
                                uiState.currentItemTitle.ifEmpty {
                                    runtime.title.ifEmpty { stringResource(CoreR.string.app_name) }
                                },
                            qualityLabel = videoQualityLabel(runtime.tracks),
                            onBack = onBack,
                            // W27 PlayerDebugOverlay：长按标题打开实时信息
                            onTitleLongPress = {
                                controls.show()
                                debugOverlayVisible = true
                            },
                            // 右上角 5 键：画中画 · 睡眠 · 选集 · 画面 · 设置（W12 终版）
                            showQualityLabel = !spec.narrow,
                            // W4-T8e：标题右侧的同步状态胶囊（点击打开 SyncPlay 面板）
                            syncPlayCapsule = syncPlayCapsule,
                            onOpenSyncPlay = { navigatePanel(PlayerPanel.SyncPlay) },
                            tools = toolCluster,
                            modifier = Modifier.align(Alignment.TopCenter),
                            onHeightChanged = onTopBarHeight,
                            // 竖屏窗口（SplitPortrait / 平板竖屏）的画面区从窗口顶边开始，顶栏要让开挖孔
                            protectTopInset = protectTopInset,
                        )

                        val playerError = uiState.playerError
                        if (showPlayerError && playerError != null) {
                            // 出错时中央大控件让位给错误卡片，避免「点了没反应」的假按钮
                            PlayerErrorCard(
                                error = playerError,
                                onRetry = onRetry,
                                onSwitchBackend = onSwitchBackend,
                                onRescanLocalLibrary = onRescanLocalLibrary,
                                modifier = Modifier.align(Alignment.Center),
                            )
                        } else {
                            // 中央播放簇（W11 反馈③）：播放 / 快进 / 播放下一个 回到屏幕正中
                            PlayerCenterCluster(
                                showsPauseAction = pausesPlayback,
                                // 缓冲中把播放键的图标换成转圈（反馈⑤：全屏只留一个加载图标）
                                buffering = playbackSession.loading,
                                // 中央簇随画面区宽度收放：窄窗不跟右缘锁定键打架（反馈⑧）
                                spec = playerCenterSpec(videoWidthDp.toFloat()),
                                onPlayPause = { requestPlayPause(!pausesPlayback) },
                                onPrevious = onPreviousMediaItem,
                                onRewind = {
                                    onSeekRelativeRequest(-settingsController.seekBackIncrementMs)
                                },
                                onForward = {
                                    onSeekRelativeRequest(settingsController.seekForwardIncrementMs)
                                },
                                onNext = onNextMediaItem,
                                modifier = Modifier.align(Alignment.Center),
                                onSizeChanged = onCenterClusterSize,
                            )
                        }

                        // 锁定键（W11 反馈②）：屏幕右缘垂直居中、贴边，样式与其它覆盖键一致
                        // W4-T22：键尺寸跟版式规格走（窄窗 38dp / 宽屏 44dp），与解锁键同一规格
                        PlayerIconButton(
                            iconRes = CoreR.drawable.ic_lock,
                            contentDescription =
                                stringResource(PlayerR.string.player_controls_lock),
                            onClick = { controls.setLock(true) },
                            size = spec.toolKeySizeDp.dp,
                            iconSize = spec.iconSizeDp.dp,
                            modifier =
                                Modifier.align(Alignment.CenterEnd)
                                    .padding(end = CinefinSpacing.Space3)
                                    .onGloballyPositioned { coordinates ->
                                        val rect = coordinates.boundsInRoot()
                                        onLockKeyRect(rect.left, rect.top, rect.right, rect.bottom)
                                    },
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
                if (seekFailed != null && controls.visible) {
                    SeekFailureCard(
                        failure = seekFailed,
                        onRetry = onRetrySeek,
                        modifier =
                            Modifier.align(Alignment.BottomCenter)
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }

                if (playbackSession.loading && !controls.visible) {
                    PlayerBufferingIndicator(
                        visible = true,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }

                // 锁屏态：只留一个解锁按钮，其余触摸交给手势层吞掉
                if (controls.locked) {
                    LockedOverlay(
                        onUnlock = { controls.setLock(false) },
                        size = spec.toolKeySizeDp.dp,
                        iconSize = spec.iconSizeDp.dp,
                        onRectChanged = onUnlockKeyRect,
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
         * 打开时画面区**不变**（不挤压、不右移），只在右缘盖一层 320dp 悬浮玻璃容器（VIDEO-29：
         * `colors.glass` + 1dp `glassBorder` + `CinefinShapes.Glass`）；
         * 它比控制层后绘制，所以盖在控件之上；触摸命中由 PlayerOverlayContainer.sidePanelOpen 单独接管。
         */
        if (layout.hasSideContent && sidePanelExpanded) {
            Box(
                modifier =
                    Modifier.width(layout.sidePanelWidthDp.dp)
                        .fillMaxHeight()
                        .clip(CinefinShapes.Glass)
                        .background(colors.glass)
                        .border(1.dp, colors.glassBorder, CinefinShapes.Glass)
                        .align(Alignment.TopEnd)
            ) {
                PlayerSideContent(
                    entries = runtime.queueEntries,
                    currentIndex = runtime.currentIndex,
                    onSelect = { index ->
                        onSelectMediaItem(index)
                        controls.show()
                    },
                    onCollapse = onToggleSidePanel,
                    onQueueMove = onQueueMove,
                    onQueueRemove = onQueueRemove,
                    onQueueClear = onQueueClear,
                    // 玻璃底由外层 Box 负责，内容区自身保持透明，避免叠两层半透明
                    containerColor = Color.Transparent,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        // 手机竖屏：画面下方的常驻内容区（选集 / 队列）
        if (layout.hasBottomContent) {
            PlayerBottomContent(
                entries = runtime.queueEntries,
                currentIndex = runtime.currentIndex,
                onSelect = onSelectMediaItem,
                onQueueMove = onQueueMove,
                onQueueRemove = onQueueRemove,
                onQueueClear = onQueueClear,
                // W67b：× 收起内容区（画面区铺满；顶栏「选集」键再展开）
                onClose = onCollapseBottomContent,
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
                    onSelect = onSelectMediaItem,
                    onQueueMove = onQueueMove,
                    onQueueRemove = onQueueRemove,
                    onQueueClear = onQueueClear,
                    showHandle = false,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        // W27 PlayerDebugOverlay：长按标题打开的实时信息面板（独立于控制层淡出，可单独关闭）
        if (debugOverlayVisible) {
            PlayerDebugOverlay(
                statsProvider = debugStatsProvider,
                onClose = { debugOverlayVisible = false },
                modifier = Modifier.align(Alignment.TopStart).padding(start = 12.dp, top = 76.dp),
            )
        }

        /* ---------- W4-T8e：SyncPlay 非阻断提示条 ----------
         * 换集拦截（2s）/ 建组·入组失败（3s）/ 被移出组（3s）共用一条，文案由宿主拼好、到点置空；
         * 与控件层可见性解耦（拦截发生在控制层收起时也要看得见）。
         */
        AnimatedVisibility(
            visible = syncPlayNoticeText != null,
            enter = fadeIn(tween(CinefinMotion.Fast, easing = CinefinMotion.Standard)),
            exit = fadeOut(tween(CinefinMotion.Fast, easing = CinefinMotion.Accelerate)),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp),
        ) {
            SyncPlayNoticeBar(text = lastSyncPlayNotice)
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
            /*
             * 「圆形揭示」的 M3 Expressive 等价做法（VIDEO-31）：从抽屉锚点（右上角）scale 0.92→1 走可中断弹簧，
             * 同时 200ms 淡入。退场保留既有的 420ms 右滑让位——定稿只改入场。
             */
            enter =
                scaleIn(
                    animationSpec = CinefinMotion.SpringControlIn,
                    initialScale = 0.92f,
                    transformOrigin = TransformOrigin(1f, 0f),
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
                            // W27：循环面板里同一开关改成正向的「自动下一集」（与设置面板同一个偏好）
                            autoNextEpisode = settingsController.state.autoNextEpisode,
                            onToggleAutoNextEpisode = { enabled ->
                                settingsController.setAutoNextEpisode(enabled)
                            },
                            onSelect = { mode, shuffle ->
                                player.repeatMode = mode
                                // mpv 后端的 setShuffleModeEnabled 还是 TODO，能力不足时不碰它
                                val shuffleSupported =
                                    player.availableCommands.contains(
                                        Player.COMMAND_SET_SHUFFLE_MODE
                                    )
                                if (shuffleSupported) {
                                    player.shuffleModeEnabled = shuffle
                                }
                                /*
                                 * W74 U2-B（D-F8）：面板改档同时落盘（新键见 PlayerExtraPreferences），
                                 * 切内核 / 重开播放页 / 冷启动后由 PlayerViewModel.applySavedPlaybackMode() 套用。
                                 */
                                settingsController.setRepeatMode(mode)
                                settingsController.setShuffleEnabled(shuffle)
                            },
                        )
                    PlayerPanel.Subtitle ->
                        SubtitlePanel(
                            state = subtitlePanelState,
                            onAssignPrimary = onAssignPrimarySubtitle,
                            onAssignSecondary = onAssignSecondarySubtitle,
                            onClearPrimary = onClearPrimarySubtitle,
                            onClearSecondary = onClearSecondarySubtitle,
                            onSwap = onSwapSubtitles,
                            onAutoSelect = onAutoSelectSubtitles,
                            onAdjustDelay = onAdjustSubtitleDelay,
                            onResetDelay = onResetSubtitleDelay,
                            onUpdateAppearance = onUpdateSubtitleAppearance,
                            // W12 反馈 B：字幕模式 / 记忆选择并入主字幕面板，全播放页只有这一个字幕入口
                            controller = settingsController,
                            onSubtitleModeChanged = onSubtitleModeChanged,
                            // W27：侧载字幕导入 / 移除
                            onImport = onImportSubtitle,
                            onRemoveSideloaded = onRemoveSideloadedSubtitle,
                            // VIDEO-11：图形 / ASS 字幕的非阻断提示条里换内核，复用错误卡的同一路径
                            onSwitchBackend = onSwitchBackend,
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
                            onOpenAllSettings = onOpenAllSettings,
                        )
                    PlayerPanel.Decode ->
                        PlayerDecodePanel(
                            controller = settingsController,
                            deviceCapabilities = deviceCapabilities,
                            onSelectDecodeMode = onSelectDecodeMode,
                            onSelectToneMapping = onSelectToneMapping,
                            onAutoFallbackChange = onAutoFallbackChange,
                        )
                    PlayerPanel.Info ->
                        InfoPanel(
                            player = player,
                            runtime = runtime,
                            title = uiState.currentItemTitle,
                            sourceInfo = uiState.currentMediaInfo,
                            currentStreamInfo = uiState.currentStreamMediaInfo,
                            mediaIdentity = uiState.playbackMediaIdentity,
                            generation = uiState.playbackRequestGeneration,
                        )
                    PlayerPanel.Queue ->
                        QueuePanel(
                            entries = runtime.queueEntries,
                            currentIndex = runtime.currentIndex,
                            // 反馈④：跳转不关面板，换集后仍可继续整理队列
                            onSelect = onSelectMediaItem,
                            onMove = onQueueMove,
                            onRemove = onQueueRemove,
                            onClear = onQueueClear,
                        )
                    PlayerPanel.Sleep ->
                        SleepPanel(
                            state = sleepState,
                            onSelect = onSelectSleepMinutes,
                        )
                    // W4-T8e：SyncPlay 面板（组能力 / 组列表由协调器探针提供，建组 / 入组 / 退组由宿主执行）
                    PlayerPanel.SyncPlay ->
                        if (syncPlay != null) {
                            SyncPlayPanel(
                                syncPlay = syncPlay,
                                activeGroup = syncPlayGroup,
                                onCreateGroup = onCreateSyncPlayGroup,
                                onJoinGroup = onJoinSyncPlayGroup,
                                onLeaveGroup = onLeaveSyncPlayGroup,
                            )
                        }
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
    /** W27：长按标题打开 PlayerDebugOverlay */
    onTitleLongPress: () -> Unit,
    /** 窄屏：清晰度徽标不占位，把宽度让给标题与 5 个工具键 */
    showQualityLabel: Boolean,
    /** W4-T8e：标题右侧的 SyncPlay 状态胶囊；[SyncPlayCapsuleVisual.Hidden] = 不占位 */
    syncPlayCapsule: SyncPlayCapsuleVisual = SyncPlayCapsuleVisual.Hidden,
    onOpenSyncPlay: () -> Unit = {},
    /** 右上角 5 键（画中画 / 睡眠 / 选集 / 画面 / 设置），由调用方注入保持单一入口与固定顺序 */
    tools: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    onHeightChanged: (Int) -> Unit = {},
    /** 竖屏顶栏贴着屏幕最上缘：让出状态栏 / 摄像头挖孔，控件不落进挖孔区 */
    protectTopInset: Boolean = false,
) {
    val colors = LocalCinefinColors.current
    /*
     * 竖屏（画面区顶边即窗口顶边）时按 safeDrawing 顶部让出状态栏 / 挖孔高度；
     * 横屏沉浸式下 statusBars 隐藏、挖孔在短边，顶部 inset = 0，不会引入多余内边距。
     */
    val topInset =
        if (protectTopInset) {
            WindowInsets.safeDrawing.only(WindowInsetsSides.Top)
        } else {
            WindowInsets(0, 0, 0, 0)
        }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            modifier
                .fillMaxWidth()
                .windowInsetsPadding(topInset)
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
            modifier =
                Modifier.weight(1f).pointerInput(onTitleLongPress) {
                    detectTapGestures(onLongPress = { onTitleLongPress() })
                },
        )
        if (qualityLabel != null && showQualityLabel) {
            Spacer(Modifier.width(CinefinSpacing.Space2))
            PlayerOverlayBadge(text = qualityLabel)
        }
        // W4-T8e：SyncPlay 状态胶囊贴在标题右侧（只在入组期间出现，随控制层一起进出）
        if (syncPlayCapsule != SyncPlayCapsuleVisual.Hidden) {
            Spacer(Modifier.width(CinefinSpacing.Space2))
            SyncPlayCapsule(visual = syncPlayCapsule, onClick = onOpenSyncPlay)
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
                    .background(colors.scrim.copy(alpha = CinefinTokens.PlayerGlassAlpha))
                    .border(1.dp, colors.glassBorder, CinefinShapes.Full),
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
 * 「玻璃底（[CinefinTokens.PlayerGlassAlpha]）+ 1dp `glassBorder` 描边 + OnSurface 图标」，只靠尺寸（56/70dp）与圆角（Xl）
 * 保持主行动辨识度。中央五键与右缘锁定键一样**保持纯图标，不加文字**。
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
    /*
     * 图标切换过冲（VIDEO-29）：Crossfade 负责换图，图标自身 1→约 1.18→1 弹一下，容器不缩放。
     * 首帧不弹（进播放页时状态没变过），快速来回切换时新动画直接接管旧动画，不会卡在半截。
     */
    val iconScale = remember { Animatable(1f) }
    var firstPlayState by remember { mutableStateOf(true) }
    LaunchedEffect(isPlaying) {
        if (firstPlayState) {
            firstPlayState = false
            return@LaunchedEffect
        }
        iconScale.snapTo(1f)
        iconScale.animateTo(PLAYER_PLAY_KEY_OVERSHOOT, CinefinMotion.SpringOvershoot)
        iconScale.animateTo(1f, CinefinMotion.SpringOvershoot)
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier.size(size)
                .graphicsLayer {
                    val scale = if (pressed) 0.94f else 1f
                    scaleX = scale
                    scaleY = scale
                }
                .clip(CinefinShapes.Xl)
                .background(
                    colors.scrim.copy(
                        alpha =
                            if (pressed) CinefinTokens.PlayerGlassPressedAlpha
                            else CinefinTokens.PlayerGlassAlpha
                    )
                )
                .border(
                    1.dp,
                    colors.glassBorder,
                    CinefinShapes.Xl,
                )
                .then(
                    if (focused) {
                        Modifier.border(2.dp, colors.onSurface.copy(alpha = 0.6f), CinefinShapes.Xl)
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
            Crossfade(
                targetState = isPlaying,
                modifier = Modifier.size(size * 0.45f),
                animationSpec = tween(150),
                label = "playerPlayIcon",
            ) { playing ->
                Icon(
                    painter =
                        painterResource(
                            if (playing) CoreR.drawable.ic_pause else CoreR.drawable.ic_play
                        ),
                    contentDescription = null,
                    tint = colors.onSurface,
                    modifier =
                        Modifier.fillMaxSize().graphicsLayer {
                            scaleX = iconScale.value
                            scaleY = iconScale.value
                        },
                )
            }
        }
    }
}

/**
 * 中央播放簇（W11 反馈③）：上一个 · 快退 −10s · 播放 / 暂停 · 快进 +10s · 下一个——五键居中， 与上一版的传输簇一致。传输键只在中央出现（右上工具簇 /
 * 左下工具行都不再重复）； 簇之外的触摸区域照旧交给手势层（命中带见 [PlayerOverlayContainer]）。
 */
@Composable
private fun PlayerCenterCluster(
    showsPauseAction: Boolean,
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
            isPlaying = showsPauseAction,
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
                        alpha =
                            if (pressed) CinefinTokens.PlayerGlassPressedAlpha
                            else CinefinTokens.PlayerGlassAlpha
                    )
                )
                .border(
                    1.dp,
                    colors.glassBorder,
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
    /** W4-T8e：SyncPlay 入口是否出现（能力探针不支持时整颗键隐藏） */
    syncPlayAvailable: Boolean = false,
    onOpenSyncPlay: () -> Unit = {},
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
                PlayerTopKey.SyncPlay ->
                    if (syncPlayAvailable) {
                        PlayerIconButton(
                            // 定稿：暂用最相近的同步类图标（全仓无 ic_sync / ic_group），待图标批次补 K1 重绘
                            iconRes = CoreR.drawable.ic_repeat,
                            contentDescription = stringResource(PlayerR.string.player_sync_label),
                            onClick = onOpenSyncPlay,
                            label =
                                if (showLabels) {
                                    stringResource(PlayerR.string.player_sync_label)
                                } else {
                                    null
                                },
                        )
                    }
            }
        }
    }
}

@Composable
private fun PlaybackRecoveryBar(
    title: String,
    detail: String,
    onRetry: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val colors = LocalCinefinColors.current
    val shape = CinefinShapes.Card
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            modifier
                .widthIn(max = 560.dp)
                .clip(shape)
                .background(colors.surfaceContainer.copy(alpha = 0.96f))
                .border(1.dp, colors.outline, shape)
                .padding(horizontal = CinefinSpacing.Space4, vertical = CinefinSpacing.Space2),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = colors.onSurface,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = detail,
                color = colors.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (onRetry != null) {
            Spacer(modifier = Modifier.width(CinefinSpacing.Space3))
            ErrorActionButton(
                text = stringResource(PlayerR.string.player_controls_error_retry),
                primary = true,
                onClick = onRetry,
            )
        }
    }
}

@Composable
private fun SeekFailureCard(
    failure: SeekState.Failed,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PlaybackRecoveryBar(
        title =
            stringResource(
                if (failure.reason == SeekFailureReason.TIMEOUT) {
                    PlayerR.string.player_seek_timeout_title
                } else {
                    PlayerR.string.player_seek_failed_title
                }
            ),
        detail = stringResource(PlayerR.string.player_seek_failure_message),
        onRetry = onRetry.takeIf { failure.retryable },
        modifier = modifier,
    )
}

/**
 * 播放失败卡片：说明 + 重试 + 换内核。
 *
 * 只在 ExoPlayer 内核会走到（mpv 目前不上报错误，见 docs/PLAYER_PLAN.md）； 说明最多三行，按钮命中区 ≥48dp，配色沿用墨底 + 发丝线，不用阴影。
 */
@Composable
private fun PlayerErrorCard(
    error: PlayerErrorInfo,
    onRetry: () -> Unit,
    onSwitchBackend: (String) -> Unit,
    onRescanLocalLibrary: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    /*
     * VIDEO-50：本地库（SAF content://）起播失败多半是文件被移动 / 树授权失效——换内核救不了，
     * 文案改「检查媒体库」，主按钮改成重扫媒体库；在线条目的文案与动作保持原样。
     */
    val safUnavailable = error.isLocalSource && error.codeName == PLAYER_ERROR_STARTUP_SOURCE_FAILED
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
    val shape = CinefinShapes.Card

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
            text =
                if (safUnavailable) {
                    stringResource(PlayerR.string.player_source_saf_unavailable)
                } else {
                    error.message
                },
            color = colors.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(modifier = Modifier.height(CinefinSpacing.Space5))
        Row(horizontalArrangement = Arrangement.spacedBy(CinefinSpacing.Space3)) {
            ErrorActionButton(
                text =
                    stringResource(
                        if (safUnavailable) {
                            PlayerR.string.player_error_rescan_library
                        } else {
                            PlayerR.string.player_controls_error_retry
                        }
                    ),
                primary = true,
                onClick = if (safUnavailable) onRescanLocalLibrary else onRetry,
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
    val shape = CinefinShapes.Button
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
 * 左下工具行（W17 用户确认，W4-T23 删码率键）：**5 键恒定齐全**（音轨 · 字幕 · 倍速 · 解码 · 详细信息）+ 「详细信息」右侧的 1× 徽标；[showLabels] =
 * true（全屏 / 宽度充足 / 平板）时在图标下加 10sp 小字， 窄屏与 Compact 只留图标。倍速键只显示图标，当前值由 1× 徽标（纯展示、不可点击）显示。
 * 右下角全屏键**保持纯图标**，与工具行不重叠。
 */
@Composable
private fun PlayerBottomBar(
    positionMs: Long,
    durationMs: Long,
    bufferedMs: Long,
    /** W67 补充：预计结束时刻（如「23:45 结束」）；时长未知 / 直播时为 null，不占位。 */
    endTimeText: String?,
    chapters: List<PlayerChapter>,
    trickplayIntervalMs: Int,
    trickplayVersion: Int,
    trickplayFrameAt: (Long) -> Bitmap?,
    speed: Float,
    sleepActive: Boolean,
    progressStatusText: String?,
    /** 字幕键激活态（改在进度条下方这一行，W12 终版布局） */
    subtitleEnabled: Boolean,
    /** 解码键激活态：软解或 mpv 内核 */
    decodeActive: Boolean,
    /** 版式规格（W12 反馈 A）：窄屏整体收一档 */
    spec: PlayerControlSpec,
    /** W67b：本行实际渲染的左下工具键（窄窗省略解码键，给 1× + 预计结束时刻让位） */
    bottomKeys: List<PlayerBottomKey>,
    isFullscreen: Boolean,
    /** W17 反馈⑤：是否在 5 键图标下加小字（窄屏 / Compact 只留图标） */
    showLabels: Boolean = false,
    onSeek: (Long) -> Unit,
    /** W73（#7）：时长未知窗口的拖动比例（进度条就绪后按真实时长换算落点） */
    onSeekFraction: (Float) -> Unit = {},
    onScrubStart: () -> Unit,
    onAudio: () -> Unit,
    onSubtitle: () -> Unit,
    onSpeed: () -> Unit,
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
    // W67b：结束时刻压在亮画面上——按 Lumen Meta 阴影描边保证可读（§8.7 播放器控件）
    val endTimeStyle = CinefinType.MonoDataSmall.lumenTextShadow(LumenTextShadow.Meta)
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
         * 时间码不再单独占行，进度条也不再通栏贴底（下方还有 5 键工具行）。
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
                    onScrubFraction = onSeekFraction,
                )
            }
            Text(
                text = formatTime(durationMs),
                style = CinefinType.MonoDataSmall,
                color = colors.onSurfaceVariant,
            )
        }

        if (progressStatusText != null) {
            Text(
                text = progressStatusText,
                color = colors.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().padding(horizontal = CinefinSpacing.Space4),
            )
        }

        Spacer(Modifier.height(CinefinSpacing.Space1))

        /*
         * 进度条下方（W13 方案 A 起分级显示）：全屏 / 宽度充足 = 5 键（音轨 · 字幕 · 倍率 · 解码 · 详细信息）+ 1×；
         * 非全屏窄窗 = 音轨 · 字幕 · 倍率 · 详细信息 + 1×（解码改从「设置 → 播放」进入；码率在设置页）。右下恒为全屏键。
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
                // W17：宽窗 5 键齐全（showLabels 只控制图标下的小字）；W67b：窄窗按宽度预算省略解码键，
                // 把宽度让给右侧固定的「1× · 预计结束时刻」；行内仍可横滑，不挤不重叠。
                bottomKeys.forEach { key ->
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
            }
            /*
             * W13 反馈③ + W14 微调：当前倍率作为**纯展示徽标**固定在键行右侧，不可点击（倍速入口只有倍率图标键）。
             * W67b：1× 徽标与预计结束时刻**固定在键行外**（不参与横滑）——两个一起保证结束时间在最窄窗口也完整可见。
             */
            Spacer(Modifier.width(CinefinSpacing.Space2))
            PlayerSpeedBadge(speed = speed)
            if (endTimeText != null) {
                Spacer(Modifier.width(CinefinSpacing.Space2))
                Text(
                    text = endTimeText,
                    style = endTimeStyle,
                    color = colors.onSurface,
                    maxLines = 1,
                    softWrap = false,
                )
            }
            if (sleepActive) {
                Spacer(Modifier.width(CinefinSpacing.Space1))
                Text(
                    text = stringResource(PlayerR.string.player_controls_label_sleep),
                    style = MaterialTheme.typography.labelSmall,
                    // 2.0.2 D5 批②：睡眠定时属激活态强调，归极光（与视频页睡眠定时图标同口径）。
                    color = colors.aurora1,
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
 * 小窗（Compact）的工具行：小窗既没有右上角 5 键、也没有进度条下 5 键的位置， 因此用一个横向可滚的小键行把全部入口兜住——一个功能一个入口、功能不缩水，
 * 窗口再窄也只是多滑一下（W11 反馈⑧：不靠隐藏修复；W12 补 解码 新入口；码率 W4-T23 起只在设置页）。
 *
 * W17 起 Compact 骨架一律**只留图标、不加文字**（键行已横向可滚）；入口恒定齐全，「解码」不再从这一行消失（设置面板里的兜底入口已按用户确认移除）。
 * 倍率键只显示图标，当前值由「详细信息」右侧的 1× 徽标显示（纯展示，W14）。
 */
@Composable
internal fun PlayerCompactToolKeys(
    subtitleEnabled: Boolean,
    aspectActive: Boolean,
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
    onOpenDecode: () -> Unit,
    onSpeed: () -> Unit,
    onSleep: () -> Unit,
    onPip: () -> Unit,
    onLock: () -> Unit,
    /** W4-T8e：SyncPlay 入口（能力探针不支持时隐藏） */
    syncPlayAvailable: Boolean = false,
    onOpenSyncPlay: () -> Unit = {},
) {
    // 顺序与画面区版式同源：音轨 · 字幕 · 倍率 · 解码 · 详细信息 + 1×，其余入口跟在后面
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
    // W4-T8e：小窗工具行与画面区同序——SyncPlay 在画面之后、设置之前
    if (syncPlayAvailable) {
        PlayerIconButton(
            iconRes = CoreR.drawable.ic_repeat,
            contentDescription = stringResource(PlayerR.string.player_sync_label),
            onClick = onOpenSyncPlay,
            size = 40.dp,
        )
    }
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
    /** W73（#7）：时长未知（进度条还没有有效 duration）时的拖动 / 点击比例，交给调用方排队。 */
    onScrubFraction: (Float) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var scrubbing by remember { mutableStateOf(false) }
    var scrubFraction by remember { mutableFloatStateOf(0f) }
    /*
     * W67 章节吸附：snapIndex = 本次拖动当前吸附的章节（-1 = 未吸附）；tapSnapIndex = 点击（tap）吸附后气泡的短暂展示。
     * 每次手势开始时清零，滞回（±3 s）只在同一次拖动内生效，松手后重新判定。
     */
    var snapIndex by remember { mutableIntStateOf(CHAPTER_SNAP_NONE) }
    var tapSnapIndex by remember { mutableIntStateOf(CHAPTER_SNAP_NONE) }
    var tapSnapTicket by remember { mutableIntStateOf(0) }
    val haptics = LocalHapticFeedback.current
    val colors = LocalCinefinColors.current
    // 进度强调色（2.0.1 定稿 §2 批②）：已播段走极光 100° 渐变（见 fill），拖拽钮柔光与吸附刻度取 aurora1。
    val accent = colors.aurora1
    // 章节吸附落位瞬间的 knob 微弹（VIDEO-31）：0.9→1，只动缩放，不改落点
    val knobScale = remember { Animatable(1f) }
    val snapScope = rememberCoroutineScope()

    val safeDuration = durationMs.coerceAtLeast(1L)
    /*
     * 进度条比例动画（VIDEO-31）：
     * - 非拖拽：played / buffered 走 tween(Fast, Standard)，播放推进与缓冲增长都柔和；
     * - 拖拽中：动画关闭（snapTo 跟手），手指到哪条到哪里；
     * - 松手后：从 scrub 值回到实际位置走 SpringSnap，knob 允许一点微过冲。
     */
    val rawPlayedFraction =
        (if (scrubbing) scrubFraction else positionMs.toFloat() / safeDuration).coerceIn(0f, 1f)
    val rawBufferedFraction = (bufferedMs.toFloat() / safeDuration).coerceIn(0f, 1f)
    val playedFraction = remember { Animatable(rawPlayedFraction) }
    val bufferedFraction = remember { Animatable(rawBufferedFraction) }
    // 松手标记：只在一次拖拽刚结束时用 SpringSnap，之后的播放推进回到 tween
    var releasedFromScrub by remember { mutableStateOf(false) }
    LaunchedEffect(rawPlayedFraction, scrubbing) {
        when {
            scrubbing -> playedFraction.snapTo(rawPlayedFraction)
            releasedFromScrub -> {
                playedFraction.animateTo(rawPlayedFraction, CinefinMotion.SpringSnap)
                releasedFromScrub = false
            }
            else ->
                playedFraction.animateTo(
                    rawPlayedFraction,
                    tween(CinefinMotion.Fast, easing = CinefinMotion.Standard),
                )
        }
    }
    LaunchedEffect(rawBufferedFraction) {
        bufferedFraction.animateTo(
            rawBufferedFraction,
            tween(CinefinMotion.Fast, easing = CinefinMotion.Standard),
        )
    }
    // 弹簧可能微过冲到 [0,1] 之外，喂给 fillMaxWidth 前夹住
    val playedValue = playedFraction.value.coerceIn(0f, 1f)
    val bufferedValue = bufferedFraction.value.coerceIn(0f, 1f)
    val previewPosition = (playedValue * safeDuration).roundToLong()
    val previewBitmap: android.graphics.Bitmap? =
        if (scrubbing && trickplayIntervalMs > 0) {
            // 显式引用版本号：新精灵图到位（version 变化）时本段重组，把预览图刷出来
            @Suppress("UNUSED_EXPRESSION") trickplayVersion
            trickplayFrameAt(previewPosition)
        } else {
            null
        }

    /**
     * W75（#1）吸附窗口按片长派生：写死的 ±2 s 在 K60 竖屏（≈1.5 s/px）只有 ≈ ±1.3px，手指落不进去。 现在 = 片长的 2% / 3%（下限 ±2 s /
     * ±3 s），24 分钟片 → ±29 s / ±44 s（屏上 ≈ ±6dp / ±10dp）。
     */
    val snapEnterMs = chapterSnapEnterMs(safeDuration)
    val snapExitMs = chapterSnapExitMs(safeDuration)

    /**
     * 手指位置 → 最终落点：统一走 [resolveChapterSnap]（[snapEnterMs] 进入 / [snapExitMs] 滞回退出）。 chapters
     * 为空（没有章节数据 / 关掉章节刻度开关）时纯函数原样返回，行为与旧版纯比例拖动一致。 吸附到新的章节时触发一次触觉反馈（W75 起用更强的
     * [HapticFeedbackType.ContextClick]，原来的 TextHandleMove 太轻、用户「感觉不到」）。
     *
     * 吸附逻辑与滞回参数一行不动；VIDEO-31 只在落位瞬间加一个 knob 0.9→1 的缩放微弹。
     */
    fun snapTarget(rawMs: Long): Long {
        val result = resolveChapterSnap(rawMs, chapters, snapIndex, snapEnterMs, snapExitMs)
        if (result.chapterIndex != CHAPTER_SNAP_NONE && result.chapterIndex != snapIndex) {
            haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
            snapScope.launch {
                knobScale.snapTo(PLAYER_SEEKBAR_SNAP_KNOB_SCALE)
                knobScale.animateTo(1f, CinefinMotion.SpringSnap)
            }
        }
        snapIndex = result.chapterIndex
        return result.positionMs
    }

    val durationKnown = durationMs > 0L && durationMs != C.TIME_UNSET

    /**
     * W73（#7）：时长未知（媒体还没 prepared）时把拖动 / 点击位置按比例上报，由 ViewModel 排队、就绪后按真实时长换算落点； 时长已知保持 既有「章节吸附 +
     * 绝对毫秒落点」行为。
     */
    fun emitScrub(rawFraction: Float) {
        val fraction = rawFraction.coerceIn(0f, 1f)
        if (!durationKnown) {
            onScrubFraction(fraction)
            return
        }
        onScrub(snapTarget((fraction * safeDuration).roundToLong()))
    }

    // 吸附到章节时气泡显示「第 N 章 · 章节名（mm:ss）」（无名字 → 「第 N 章（mm:ss）」）；mm:ss = 吸附落点，否则维持原来的时间提示
    val snapLabel = chapterSnapLabel(chapters, if (scrubbing) snapIndex else tapSnapIndex)
    val chapterBubbleText = snapLabel?.let { label ->
        chapterSnapBubbleText(
            label = label,
            timeText = formatTime(label.startMs),
            unnamedFormat = stringResource(AppR.string.player_chapter_snap),
            namedFormat = stringResource(AppR.string.player_chapter_snap_named),
        )
    }
    // 点击吸附后的气泡展示 1.5 s 再收起（拖动中的气泡本身跟随手势）
    LaunchedEffect(tapSnapTicket) {
        if (tapSnapTicket > 0) {
            delay(1500)
            tapSnapIndex = CHAPTER_SNAP_NONE
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        if (previewBitmap != null || chapterBubbleText != null) {
            Row(
                verticalAlignment = Alignment.Bottom,
                modifier = Modifier.fillMaxWidth().padding(bottom = CinefinSpacing.Space2),
            ) {
                if (previewBitmap != null) {
                    /*
                     * Trickplay 预览入场（VIDEO-31）：fadeIn 150ms + 上浮 8dp 走 SpringControlIn。
                     * 拖动中每次都拿到新帧时不会重播——动画只在预览从无到有时跑一次。
                     */
                    val previewFade = remember { Animatable(0f) }
                    val previewRise = remember { Animatable(1f) }
                    LaunchedEffect(Unit) {
                        previewFade.animateTo(1f, tween(150, easing = CinefinMotion.Standard))
                    }
                    LaunchedEffect(Unit) {
                        previewRise.animateTo(0f, CinefinMotion.SpringControlIn)
                    }
                    Image(
                        bitmap = previewBitmap.asImageBitmap(),
                        contentDescription = null,
                        modifier =
                            Modifier.width(160.dp)
                                .graphicsLayer {
                                    alpha = previewFade.value.coerceIn(0f, 1f)
                                    translationY = previewRise.value * 8.dp.toPx()
                                }
                                // 玻璃容器小件（VIDEO-29）：glass 底 + glassBorder 描边 + Xs 圆角
                                .clip(CinefinShapes.Xs)
                                .background(colors.glass)
                                .border(1.dp, colors.glassBorder, CinefinShapes.Xs),
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    )
                    Spacer(Modifier.width(CinefinSpacing.Space3))
                }
                Text(
                    text = chapterBubbleText ?: formatTime(previewPosition),
                    style = CinefinType.MonoData,
                    color = colors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier =
                        Modifier.widthIn(max = 260.dp)
                            .clip(CinefinShapes.Xs)
                            // 章节气泡同属玻璃小件：glass 底 + glassBorder 描边
                            .background(colors.glass)
                            .border(1.dp, colors.glassBorder, CinefinShapes.Xs)
                            .padding(horizontal = CinefinSpacing.Space2, vertical = 2.dp),
                )
            }
        }

        Box(
            modifier =
                Modifier.fillMaxWidth()
                    .height(34.dp)
                    .pointerInput(chapters, safeDuration, durationKnown) {
                        detectTapGestures { offset ->
                            val fraction = (offset.x / size.width).coerceIn(0f, 1f)
                            snapIndex = CHAPTER_SNAP_NONE
                            onScrubStart()
                            emitScrub(fraction)
                            tapSnapIndex = snapIndex
                            if (durationKnown) tapSnapTicket++
                        }
                    }
                    .pointerInput(chapters, safeDuration, durationKnown) {
                        detectDragGestures(
                            onDragStart = { offset ->
                                scrubbing = true
                                snapIndex = CHAPTER_SNAP_NONE
                                tapSnapIndex = CHAPTER_SNAP_NONE
                                scrubFraction = (offset.x / size.width).coerceIn(0f, 1f)
                                onScrubStart()
                            },
                            onDrag = { change, _ ->
                                val fraction = (change.position.x / size.width).coerceIn(0f, 1f)
                                if (durationKnown) {
                                    val target = snapTarget((fraction * safeDuration).roundToLong())
                                    scrubFraction =
                                        (target.toFloat() / safeDuration).coerceIn(0f, 1f)
                                    onScrub(target)
                                } else {
                                    scrubFraction = fraction
                                    onScrubFraction(fraction)
                                }
                            },
                            onDragEnd = {
                                // 松手落点 = 吸附后的位置
                                if (durationKnown) {
                                    onScrub(
                                        snapTarget((scrubFraction * safeDuration).roundToLong())
                                    )
                                } else {
                                    onScrubFraction(scrubFraction)
                                }
                                scrubbing = false
                                snapIndex = CHAPTER_SNAP_NONE
                                // 回实际位置走 SpringSnap（VIDEO-31）
                                releasedFromScrub = true
                            },
                            onDragCancel = {
                                scrubbing = false
                                snapIndex = CHAPTER_SNAP_NONE
                                releasedFromScrub = true
                            },
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
                        .background(colors.topHighlight)
            )
            // 已缓冲
            Box(
                modifier =
                    Modifier.fillMaxWidth(bufferedValue)
                        .height(trackHeight)
                        .clip(shape)
                        .background(colors.onSurface.copy(alpha = 0.24f))
            )
            // 已播：极光 100° 渐变（2.0.1 定稿 §2 批②：与 CTA / 导航指示同一套 `auroraGradient`）
            Box(
                modifier =
                    Modifier.fillMaxWidth(playedValue)
                        .height(if (scrubbing) 8.dp else trackHeight)
                        .clip(shape)
                        .auroraGradient(colors)
            )
            /*
             * 拖动手柄（W67b：与音乐播放器同款圆形语言）。
             *
             * 9dp 白圆点 + 同色柔光（`aurora1` 径向渐变，取 [CinefinProgressVisuals] 的**收敛档**参数：
             * 半径与透明度都比音乐页小）；旧样式的「拖动放大到 20dp + 2dp 极光青描边圈」下线——那圈描边正是用户说的
             * "光晕"。拖动时不再放大，仍能靠吸附刻度 / 气泡 / 触觉表达状态。
             */
            val thumbRadius = CinefinProgressVisuals.ThumbRadius
            Box(
                modifier = Modifier.fillMaxWidth(playedValue),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Box(
                    modifier =
                        Modifier.size(thumbRadius * 2)
                            // 章节吸附落位微弹（VIDEO-31）：knob 0.9→1，键位不变
                            .graphicsLayer {
                                scaleX = knobScale.value
                                scaleY = knobScale.value
                            }
                            .drawBehind {
                                val glowRadius =
                                    size.minDimension / 2f * CinefinProgressVisuals.VideoGlowScale
                                drawCircle(
                                    brush =
                                        Brush.radialGradient(
                                            colors =
                                                listOf(
                                                    accent.copy(
                                                        alpha =
                                                            CinefinProgressVisuals.VideoGlowAlpha
                                                    ),
                                                    Color.Transparent,
                                                ),
                                            center = center,
                                            radius = glowRadius,
                                        ),
                                    radius = glowRadius,
                                    center = center,
                                )
                            }
                            .clip(CircleShape)
                            .background(colors.onSurface)
                )
            }
            /*
             * 章节刻度（W67 增强 / W67b 收敛高度 / W75 #1 配色回白）：2dp 宽、**与轨道同高（6dp）**的白 85% 发丝线。
             *
             * W75（#1）：**已播过的章节刻度也回到白 85%**（推翻 D54 / D62 的「已播用媒体色」）——已播刻度与极光青
             * 进度条同色，实际读不出来。现在只有**吸附中**那条加粗到 3dp 并取极光青满亮，作为唯一的强调色
             * （刻度与拇指同高，白上加白读不出「被吸附」，故高亮色保留）；仍不加光晕（§2.6 第 3 条）。
             */
            chapters.forEachIndexed { index, chapter ->
                val fraction = chapterTickFraction(chapter.startPosition, safeDuration)
                val snapped = scrubbing && index == snapIndex
                val tickColor =
                    when {
                        // 只有吸附中那条（3dp）用高亮色；已播 / 未播一律白 85%
                        snapped -> accent
                        else -> colors.onSurface.copy(alpha = 0.85f)
                    }
                Box(
                    // 只按比例定位右缘（外层不加高度约束，刻度高度 = 轨道高度，W67b 起不再上下探出）
                    modifier = Modifier.fillMaxWidth(fraction),
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    Box(
                        modifier =
                            Modifier.width(if (snapped) 3.dp else 2.dp)
                                .height(trackHeight)
                                .clip(CinefinShapes.TwoXs)
                                .background(tickColor)
                    )
                }
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
    val shape = CinefinShapes.Button
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
                        pressed -> colors.scrim.copy(alpha = CinefinTokens.PlayerGlassPressedAlpha)
                        focused -> colors.stateHover
                        glass -> colors.scrim.copy(alpha = CinefinTokens.PlayerGlassAlpha)
                        else -> Color.Transparent
                    }
                )
                // W12 反馈 A：所有控件外框加 1dp 描边（选中态用媒体色描边），未选中是极淡的月白
                .border(
                    1.dp,
                    when {
                        error -> colors.error.copy(alpha = 0.4f)
                        selected -> media.outline
                        glass -> colors.glassBorder
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
     * 中性描边 token 与玻璃底（2.0.2 D4：图上控件唯一垫层 = `colors.glass` + `glassBorder`，含文字 chip 维持 58% 填充），
     * 图标归极光（2.0.2 D5 批②：跳过片头属交互控件，域媒体色退出）；旧实现用 media.outline 描边，
     * 在亮画面上比其它覆盖层控件更抢眼。
     */
    val colors = LocalCinefinColors.current
    val shape = CinefinShapes.Full
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            modifier
                .clip(shape)
                .background(colors.glass)
                .border(1.dp, colors.glassBorder, shape)
                .clickable(onClick = onClick)
                .padding(horizontal = CinefinSpacing.Space4, vertical = CinefinSpacing.Space3),
    ) {
        Icon(
            painter = painterResource(CoreR.drawable.ic_skip_forward),
            contentDescription = null,
            tint = colors.aurora1,
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
    size: Dp,
    iconSize: Dp,
    onRectChanged: (left: Float, top: Float, right: Float, bottom: Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalCinefinColors.current
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            modifier
                // 与未锁定态的锁定键同一边距（Space3），锁定 / 解锁时按钮不横移、尺寸不跳（W4-T22）
                .padding(end = CinefinSpacing.Space3)
                .size(size)
                .clip(CinefinShapes.Xl)
                .background(colors.aurora1.copy(alpha = CinefinTokens.MediaContainerAlpha))
                .border(
                    1.dp,
                    colors.aurora1.copy(alpha = CinefinTokens.MediaOutlineAlpha),
                    CinefinShapes.Xl,
                )
                .onGloballyPositioned { coordinates ->
                    val rect = coordinates.boundsInRoot()
                    onRectChanged(rect.left, rect.top, rect.right, rect.bottom)
                }
                .clickable(onClick = onUnlock),
    ) {
        Icon(
            painter = painterResource(CoreR.drawable.ic_unlock),
            contentDescription = stringResource(PlayerR.string.player_controls_unlock),
            // 2.0.2 D5 批②：解锁键属交互控件，整块（底 / 描边 / 图标）归极光。
            tint = colors.aurora1,
            modifier = Modifier.size(iconSize),
        )
    }
}

// ---------- 工具 ----------

internal fun formatTime(millis: Long): String {
    if (millis < 0L) return "—"
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
    // W78：档位文案与设置页「长按倍速」共用同一份实现（PlaybackSpeedOptions.label）
    return PlaybackSpeedOptions.label(speed)
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

/**
 * 面板菜单项错峰入场的序号发号器（VIDEO-31）。
 *
 * [PanelList] 每次出场新建一个发号器；面板退场后再打开，内容重新组合、序号从 0 重新发， 错峰不会「接着上次的数」继续拖。
 */
private class PanelStaggerCounter {
    var next = 0
}

private val LocalPanelStaggerCounter = staticCompositionLocalOf<PanelStaggerCounter?> { null }

/** 本行的错峰延迟：序号 × [CinefinMotion.Stagger]，第 [PLAYER_PANEL_STAGGER_MAX_ITEMS] 项封顶；不在面板列表里则为 0。 */
@Composable
private fun rememberPanelStaggerDelayMs(): Int {
    val counter = LocalPanelStaggerCounter.current ?: return 0
    val index = remember { counter.next++ }
    return minOf(index, PLAYER_PANEL_STAGGER_MAX_ITEMS) * CinefinMotion.Stagger
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
    val shape = CinefinShapes.Button
    /*
     * 错峰入场（VIDEO-31）：每项 fadeIn 150ms + 上浮 8dp，delay = 序号 × 40ms（上限 8 项）。
     * 走 graphicsLayer 只动绘制层，不参与测量，列表不会因为入场动画抖高度。
     */
    val staggerDelayMs = rememberPanelStaggerDelayMs()
    var staggerEntered by remember { mutableStateOf(false) }
    LaunchedEffect(staggerDelayMs) {
        if (staggerDelayMs > 0) delay(staggerDelayMs.toLong())
        staggerEntered = true
    }
    val staggerProgress by
        animateFloatAsState(
            targetValue = if (staggerEntered) 1f else 0f,
            animationSpec = tween(150, easing = CinefinMotion.Standard),
            label = "panelRowStagger",
        )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier.fillMaxWidth()
                .graphicsLayer {
                    alpha = staggerProgress
                    translationY = (1f - staggerProgress) * 8.dp.toPx()
                }
                .padding(horizontal = CinefinSpacing.Space3, vertical = 2.dp)
                .heightIn(min = 52.dp)
                .clip(shape)
                .background(
                    if (selected && enabled) {
                        colors.aurora1.copy(alpha = CinefinTokens.MediaContainerAlpha)
                    } else {
                        colors.surfaceContainerLow
                    }
                )
                .border(
                    1.dp,
                    if (selected && enabled) {
                        colors.aurora1.copy(alpha = CinefinTokens.MediaOutlineAlpha)
                    } else {
                        colors.outlineVariant
                    },
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
                        selected -> colors.aurora1
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
                tint = colors.aurora1,
                modifier = Modifier.size(20.dp),
            )
        }
        trailing?.invoke()
    }
}

@Composable
internal fun ColumnScope.PanelList(content: @Composable () -> Unit) {
    // 每次面板出场重新发号（VIDEO-31），子项按组合顺序拿到 0、1、2… 的错峰序号
    val staggerCounter = remember { PanelStaggerCounter() }
    Column(
        modifier =
            Modifier.fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(bottom = CinefinSpacing.Space5)
    ) {
        CompositionLocalProvider(LocalPanelStaggerCounter provides staggerCounter) { content() }
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
                // 悬浮玻璃容器（VIDEO-29）：玻璃底 + 1dp glassBorder，左缘结构线由描边承担，不再叠一条实线
                .clip(CinefinShapes.Glass)
                .background(colors.glass)
                .border(1.dp, colors.glassBorder, CinefinShapes.Glass)
                // 抽屉本体吞掉空白点击：用 pointerInput 而不是 clickable，避免给整块面板叠一个"按钮"语义
                .pointerInput(Unit) { detectTapGestures {} }
                .semantics { paneTitle = title }
    ) {
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
        PlayerPanel.SyncPlay -> PlayerR.string.player_sync_panel_title
        PlayerPanel.None -> PlayerR.string.player_controls_settings
    }

@Composable
private fun SpeedPanel(
    current: Float,
    onSelect: (Float) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        PanelList {
            PlaybackSpeedOptions.PLAYBACK.forEach { speed ->
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
    /** W27：自动下一集（循环模式之外的独立开关；关 = 当前一集播完停在结束帧） */
    autoNextEpisode: Boolean,
    onToggleAutoNextEpisode: (Boolean) -> Unit,
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
            // W27：「自动下一集」独立开关——关掉后当前一集播完停在结束帧（旧「播完暂停」语义）
            PanelRow(
                label = stringResource(PlayerR.string.player_settings_auto_next),
                caption = stringResource(PlayerR.string.player_settings_auto_next_caption),
                selected = autoNextEpisode,
                onClick = { onToggleAutoNextEpisode(!autoNextEpisode) },
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
 * 字幕面板：顶部固定 `轨道 | 外观`，两页各自只有一个纵向滚动容器。
 *
 * 轨道清单由 ViewModel 汇总成单一列表；每行明确区分「主路动作 id」与「次路动作 id」， 面板只消费动作 id，不自行猜测 mpv sid / 源序号。
 */
@Composable
private fun SubtitlePanel(
    state: PlayerViewModel.SubtitlePanelState,
    onAssignPrimary: (Int) -> Unit,
    onAssignSecondary: (Int) -> Unit,
    onClearPrimary: () -> Unit,
    onClearSecondary: () -> Unit,
    onSwap: () -> Unit,
    onAutoSelect: () -> Unit,
    onAdjustDelay: (Long) -> Unit,
    onResetDelay: () -> Unit,
    onUpdateAppearance: (SubtitleAppearance) -> Unit,
    /** W12 反馈 B：字幕模式 / 记忆选择并入本面板（唯一字幕入口），控制器由 Activity 持有 */
    controller: PlayerSettingsController,
    onSubtitleModeChanged: (String) -> Unit,
    /** W27：打开系统文件选择器导入侧载字幕 */
    onImport: () -> Unit,
    /** W27：移除一条侧载字幕（id 与行身份同一套 id） */
    onRemoveSideloaded: (Int) -> Unit,
    /** VIDEO-11：图形字幕提示条里的「切换内核」 */
    onSwitchBackend: (String) -> Unit,
) {
    // 用 Int 而不是 enum：rememberSaveable 的自动 Saver 对基本类型最稳。
    var tab by rememberSaveable { mutableStateOf(0) }
    Column(modifier = Modifier.fillMaxSize()) {
        PanelChipRow(
            options =
                listOf(
                    stringResource(PlayerR.string.player_subtitle_tab_tracks) to (tab == 0),
                    stringResource(PlayerR.string.player_subtitle_tab_appearance) to (tab == 1),
                ),
            onSelect = { index -> tab = index },
        )
        when (tab) {
            0 ->
                SubtitleTracksPage(
                    state = state,
                    onAssignPrimary = onAssignPrimary,
                    onAssignSecondary = onAssignSecondary,
                    onClearPrimary = onClearPrimary,
                    onClearSecondary = onClearSecondary,
                    onSwap = onSwap,
                    onAutoSelect = onAutoSelect,
                    onAdjustDelay = onAdjustDelay,
                    onResetDelay = onResetDelay,
                    controller = controller,
                    onSubtitleModeChanged = onSubtitleModeChanged,
                    onImport = onImport,
                    onRemoveSideloaded = onRemoveSideloaded,
                    onSwitchBackend = onSwitchBackend,
                )
            else -> SubtitleAppearancePage(state = state, onUpdateAppearance = onUpdateAppearance)
        }
    }
}

@Composable
private fun SubtitleTracksPage(
    state: PlayerViewModel.SubtitlePanelState,
    onAssignPrimary: (Int) -> Unit,
    onAssignSecondary: (Int) -> Unit,
    onClearPrimary: () -> Unit,
    onClearSecondary: () -> Unit,
    onSwap: () -> Unit,
    onAutoSelect: () -> Unit,
    onAdjustDelay: (Long) -> Unit,
    onResetDelay: () -> Unit,
    controller: PlayerSettingsController,
    onSubtitleModeChanged: (String) -> Unit,
    onImport: () -> Unit,
    onRemoveSideloaded: (Int) -> Unit,
    onSwitchBackend: (String) -> Unit,
) {
    val colors = LocalCinefinColors.current
    val primary =
        state.tracks.firstOrNull {
            it.assignment == PlayerViewModel.SubtitleAssignment.Primary ||
                it.assignment == PlayerViewModel.SubtitleAssignment.Native
        }
    val secondary =
        state.tracks.firstOrNull { it.assignment == PlayerViewModel.SubtitleAssignment.Secondary }
    var optionsExpanded by rememberSaveable { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        PanelList {
            if (state.mpvSuggestedForPrimary) {
                SubtitleBackendHint(
                    onSwitchBackend = { onSwitchBackend(PlayerViewModel.PLAYER_BACKEND_MPV) }
                )
            }
            SubtitleCurrentCombination(
                state = state,
                primary = primary,
                secondary = secondary,
                onClearPrimary = onClearPrimary,
                onClearSecondary = onClearSecondary,
                onSwap = onSwap,
                onAutoSelect = onAutoSelect,
                onAdjustDelay = onAdjustDelay,
                onResetDelay = onResetDelay,
            )
            if (state.assSecondaryHint) {
                Text(
                    text = stringResource(PlayerR.string.player_subtitle_ass_primary_only),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    modifier =
                        Modifier.padding(
                            horizontal = CinefinSpacing.Space5,
                            vertical = CinefinSpacing.Space1,
                        ),
                )
            }
            if (state.secondaryAssignmentBlocked) {
                Text(
                    text = stringResource(PlayerR.string.player_subtitle_secondary_native_primary),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    modifier =
                        Modifier.padding(
                            horizontal = CinefinSpacing.Space5,
                            vertical = CinefinSpacing.Space1,
                        ),
                )
            }
            state.primaryUnavailableReason?.let { reason ->
                Text(
                    text = subtitlePrimaryUnavailableReason(reason),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.error,
                    modifier =
                        Modifier.padding(
                            horizontal = CinefinSpacing.Space5,
                            vertical = CinefinSpacing.Space1,
                        ),
                )
            }
            state.secondaryUnavailableReason?.let { reason ->
                Column(
                    modifier =
                        Modifier.padding(
                            horizontal = CinefinSpacing.Space5,
                            vertical = CinefinSpacing.Space1,
                        )
                ) {
                    Text(
                        text = subtitleSecondaryUnavailableReason(reason),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.error,
                    )
                    if (reason.isRetryable()) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(CinefinSpacing.Space2),
                            verticalArrangement = Arrangement.spacedBy(CinefinSpacing.Space1),
                            modifier = Modifier.padding(top = CinefinSpacing.Space1),
                        ) {
                            secondary?.secondaryActionId?.let { actionId ->
                                PanelChip(
                                    label =
                                        stringResource(PlayerR.string.player_controls_error_retry),
                                    selected = false,
                                    onClick = { onAssignSecondary(actionId) },
                                )
                            }
                            PanelChip(
                                label = stringResource(PlayerR.string.player_subtitle_close),
                                selected = false,
                                onClick = onClearSecondary,
                            )
                        }
                    }
                }
            }

            PanelTitle(stringResource(PlayerR.string.player_subtitle_all_tracks))
            if (state.tracks.isEmpty()) {
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
                // VIDEO-11：按语言分组，组内内封在前、外挂在后（稳定排序保留原标题序）。
                val groups = remember(state.tracks) { groupSubtitleTracksByLanguage(state.tracks) }
                groups.forEach { group ->
                    PanelTitle(subtitleLanguageGroupLabel(group.language))
                    group.options.forEach { option ->
                        SubtitleTrackRow(
                            option = option,
                            onAssignPrimary = onAssignPrimary,
                            onAssignSecondary = onAssignSecondary,
                            onRemoveSideloaded = onRemoveSideloaded,
                        )
                    }
                }
            }

            SubtitleExpandableSection(
                title = stringResource(PlayerR.string.player_subtitle_options),
                expanded = optionsExpanded,
                onToggle = { optionsExpanded = !optionsExpanded },
            ) {
                SubtitleTrackOptions(
                    controller = controller,
                    onSubtitleModeChanged = onSubtitleModeChanged,
                    onImport = onImport,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SubtitleCurrentCombination(
    state: PlayerViewModel.SubtitlePanelState,
    primary: PlayerViewModel.SubtitleOption?,
    secondary: PlayerViewModel.SubtitleOption?,
    onClearPrimary: () -> Unit,
    onClearSecondary: () -> Unit,
    onSwap: () -> Unit,
    onAutoSelect: () -> Unit,
    onAdjustDelay: (Long) -> Unit,
    onResetDelay: () -> Unit,
) {
    val colors = LocalCinefinColors.current
    val shape = CinefinShapes.Card
    Column(
        modifier =
            Modifier.fillMaxWidth()
                .padding(horizontal = CinefinSpacing.Space3, vertical = CinefinSpacing.Space2)
                .clip(shape)
                .background(colors.surfaceContainerLow)
                .border(1.dp, colors.outlineVariant, shape)
                .padding(horizontal = CinefinSpacing.Space3, vertical = CinefinSpacing.Space2)
    ) {
        Text(
            text = stringResource(PlayerR.string.player_subtitle_current_combo),
            style = MaterialTheme.typography.labelSmall,
            color = colors.onSurfaceFaint,
        )
        SubtitleCombinationLine(
            label = stringResource(PlayerR.string.player_subtitle_primary),
            option = primary,
            onClear = onClearPrimary,
        )
        SubtitleCombinationLine(
            label = stringResource(PlayerR.string.player_subtitle_secondary),
            option = secondary,
            onClear = onClearSecondary,
        )
        if (state.loading) {
            Text(
                text = stringResource(PlayerR.string.player_subtitle_loading),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
            )
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(CinefinSpacing.Space2),
            verticalArrangement = Arrangement.spacedBy(CinefinSpacing.Space1),
            modifier = Modifier.fillMaxWidth().padding(top = CinefinSpacing.Space1),
        ) {
            PanelChip(
                label = stringResource(PlayerR.string.player_subtitle_swap),
                selected = false,
                enabled = state.swapBlockedReason == null,
                onClick = onSwap,
            )
            PanelChip(
                label = stringResource(PlayerR.string.player_subtitle_auto_select),
                selected = false,
                onClick = onAutoSelect,
            )
        }
        state.swapBlockedReason?.let { reason ->
            Text(
                text = subtitleSwapBlockReason(reason),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(top = CinefinSpacing.Space1),
            )
        }
        SubtitleDelayRow(
            delayMs = state.delayMs,
            onAdjust = onAdjustDelay,
            onReset = onResetDelay,
        )
    }
}

@Composable
private fun SubtitleCombinationLine(
    label: String,
    option: PlayerViewModel.SubtitleOption?,
    onClear: () -> Unit,
) {
    val colors = LocalCinefinColors.current
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceFaint,
            )
            Text(
                text = option?.label ?: stringResource(PlayerR.string.player_subtitle_unassigned),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            option?.let { selected ->
                Text(
                    text = subtitleTrackMeta(selected),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        option?.assignment?.let { assignment -> SubtitleAssignmentBadge(assignment) }
        PanelChip(
            label = stringResource(PlayerR.string.player_subtitle_close),
            selected = false,
            enabled = option != null,
            onClick = onClear,
        )
    }
}

@Composable
private fun SubtitleTrackOptions(
    controller: PlayerSettingsController,
    onSubtitleModeChanged: (String) -> Unit,
    onImport: () -> Unit,
) {
    val colors = LocalCinefinColors.current
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
    // W2-T13（需求 #2）：记住轨道选择 / 首选字幕语言属「配置一次长期有效」，只保留在设置页（语言与字幕子页）。
    // W27 侧载字幕：文件选择器导入，只保存在本机（轨道行尾可直接移除）
    PanelTitle(stringResource(PlayerR.string.player_subtitle_sideload))
    PanelRow(
        label = stringResource(PlayerR.string.player_subtitle_import),
        caption = stringResource(PlayerR.string.player_subtitle_import_caption),
        selected = false,
        onClick = onImport,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SubtitleTrackRow(
    option: PlayerViewModel.SubtitleOption,
    onAssignPrimary: (Int) -> Unit,
    onAssignSecondary: (Int) -> Unit,
    onRemoveSideloaded: (Int) -> Unit,
) {
    val colors = LocalCinefinColors.current
    val shape = CinefinShapes.Card
    Column(
        modifier =
            Modifier.fillMaxWidth()
                .padding(horizontal = CinefinSpacing.Space3, vertical = 2.dp)
                .clip(shape)
                .background(colors.surfaceContainerLow)
                .border(1.dp, colors.outlineVariant, shape)
                .padding(horizontal = CinefinSpacing.Space3, vertical = CinefinSpacing.Space2)
    ) {
        Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = option.label,
                        style = MaterialTheme.typography.bodyLarge,
                        color = colors.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (!option.textAssignable) {
                        Spacer(Modifier.width(CinefinSpacing.Space2))
                        SubtitleTextBadge(
                            stringResource(PlayerR.string.player_subtitle_native_only)
                        )
                    }
                }
                Text(
                    text = subtitleTrackRowMeta(option),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(CinefinSpacing.Space2))
            SubtitleTrackTag(subtitleTrackTag(option))
            option.assignment?.let { assignment -> SubtitleAssignmentBadge(assignment) }
            if (option.sideloaded) {
                SideloadRemoveKey(id = option.id, onRemove = onRemoveSideloaded)
            }
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(CinefinSpacing.Space2),
            verticalArrangement = Arrangement.spacedBy(CinefinSpacing.Space1),
            modifier = Modifier.fillMaxWidth().padding(top = CinefinSpacing.Space1),
        ) {
            if (option.textAssignable) {
                option.primaryActionId?.let { primaryActionId ->
                    PanelChip(
                        label = stringResource(PlayerR.string.player_subtitle_assignment_primary),
                        selected = option.assignment == PlayerViewModel.SubtitleAssignment.Primary,
                        onClick = { onAssignPrimary(primaryActionId) },
                    )
                }
                    ?: SubtitleTextBadge(
                        stringResource(PlayerR.string.player_subtitle_secondary_only)
                    )
                option.secondaryActionId?.let { secondaryActionId ->
                    PanelChip(
                        label = stringResource(PlayerR.string.player_subtitle_assignment_secondary),
                        selected =
                            option.assignment == PlayerViewModel.SubtitleAssignment.Secondary,
                        onClick = { onAssignSecondary(secondaryActionId) },
                    )
                }
            } else {
                option.primaryActionId?.let { primaryActionId ->
                    PanelChip(
                        label = stringResource(PlayerR.string.player_subtitle_native),
                        selected = option.assignment == PlayerViewModel.SubtitleAssignment.Native,
                        onClick = { onAssignPrimary(primaryActionId) },
                    )
                }
            }
        }
        if (option.textAssignable && option.primaryActionId == null) {
            Text(
                text = stringResource(PlayerR.string.player_subtitle_primary_not_injected),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
            )
        }
        if (option.graphic) {
            Text(
                text = stringResource(PlayerR.string.player_subtitle_graphic_no_secondary),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
            )
        } else if (!option.textAssignable) {
            Text(
                text = stringResource(PlayerR.string.player_subtitle_secondary_delivery_missing),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun subtitleSecondaryUnavailableReason(reason: SubtitleSecondaryUnavailableReason): String =
    stringResource(
        when (reason) {
            SubtitleSecondaryUnavailableReason.GraphicOnly ->
                PlayerR.string.player_subtitle_graphic_no_secondary
            SubtitleSecondaryUnavailableReason.DeliveryUrlMissing ->
                PlayerR.string.player_subtitle_secondary_delivery_missing
            SubtitleSecondaryUnavailableReason.StaleTrack ->
                PlayerR.string.player_subtitle_secondary_stale
            SubtitleSecondaryUnavailableReason.DownloadFailed ->
                PlayerR.string.player_subtitle_secondary_download_failed
            SubtitleSecondaryUnavailableReason.InvalidContent ->
                PlayerR.string.player_subtitle_secondary_invalid_content
        }
    )

@Composable
private fun subtitlePrimaryUnavailableReason(
    reason: PlayerViewModel.SubtitlePrimaryUnavailableReason
): String =
    stringResource(
        when (reason) {
            PlayerViewModel.SubtitlePrimaryUnavailableReason.NativeSourceIdentityUnavailable ->
                PlayerR.string.player_subtitle_primary_identity_unavailable
        }
    )

private fun SubtitleSecondaryUnavailableReason.isRetryable(): Boolean =
    this == SubtitleSecondaryUnavailableReason.DownloadFailed ||
        this == SubtitleSecondaryUnavailableReason.InvalidContent

@Composable
private fun SubtitleTextBadge(label: String) {
    val colors = LocalCinefinColors.current
    val shape = CinefinShapes.Xs
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = colors.onSurfaceVariant,
        modifier =
            Modifier.clip(shape)
                .border(1.dp, colors.outlineVariant, shape)
                .padding(horizontal = CinefinSpacing.Space2, vertical = 1.dp),
    )
}

@Composable
private fun SubtitleAssignmentBadge(assignment: PlayerViewModel.SubtitleAssignment) {
    // 2.0.2 D6 裁定：纯静态信息徽标（主 / 次 / 原生轨标，无选中或激活态），按 CinefinSourceBadge 先例保留域媒体色。
    val media = LocalMediaColors.current
    val label =
        when (assignment) {
            PlayerViewModel.SubtitleAssignment.Primary ->
                stringResource(PlayerR.string.player_subtitle_assignment_primary)
            PlayerViewModel.SubtitleAssignment.Secondary ->
                stringResource(PlayerR.string.player_subtitle_assignment_secondary)
            PlayerViewModel.SubtitleAssignment.Native ->
                stringResource(PlayerR.string.player_subtitle_native)
        }
    val shape = CinefinShapes.Xs
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = media.bright,
        modifier =
            Modifier.padding(end = CinefinSpacing.Space2)
                .clip(shape)
                .background(media.container)
                .border(1.dp, media.outline, shape)
                .padding(horizontal = CinefinSpacing.Space2, vertical = 1.dp),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SubtitleExpandableSection(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit,
) {
    val colors = LocalCinefinColors.current
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier =
                Modifier.fillMaxWidth()
                    .clickable(onClick = onToggle)
                    .padding(
                        start = CinefinSpacing.Space5,
                        end = CinefinSpacing.Space5,
                        top = CinefinSpacing.Space3,
                        bottom = CinefinSpacing.Space2,
                    ),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceFaint,
                modifier = Modifier.weight(1f),
            )
            Text(
                text =
                    stringResource(
                        if (expanded) PlayerR.string.player_subtitle_collapse
                        else PlayerR.string.player_subtitle_expand
                    ),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
            )
        }
        if (expanded) content()
    }
}

@Composable
private fun subtitleTrackMeta(option: PlayerViewModel.SubtitleOption): String {
    val origin =
        stringResource(
            when (option.origin) {
                PlayerViewModel.SubtitleOrigin.Embedded -> PlayerR.string.player_subtitle_embedded
                PlayerViewModel.SubtitleOrigin.External -> PlayerR.string.player_subtitle_external
                PlayerViewModel.SubtitleOrigin.Sideloaded ->
                    PlayerR.string.player_subtitle_origin_sideload
            }
        )
    val assignment =
        option.assignment?.let { value ->
            stringResource(
                when (value) {
                    PlayerViewModel.SubtitleAssignment.Primary ->
                        PlayerR.string.player_subtitle_primary
                    PlayerViewModel.SubtitleAssignment.Secondary ->
                        PlayerR.string.player_subtitle_secondary
                    PlayerViewModel.SubtitleAssignment.Native ->
                        PlayerR.string.player_subtitle_native_only
                }
            )
        }
    return listOfNotNull(
            option.language.takeIf { it.isNotBlank() },
            option.format.takeIf { it.isNotBlank() },
            origin,
            assignment,
        )
        .joinToString(" · ")
}

/** 轨道行副标题：语言进组头、来源进行尾标注，这里只留格式与落点，避免同一信息写三遍。 */
@Composable
private fun subtitleTrackRowMeta(option: PlayerViewModel.SubtitleOption): String =
    listOfNotNull(
            option.format.takeIf { it.isNotBlank() },
            option.assignment?.let { subtitleAssignmentLabel(it) },
        )
        .joinToString(" · ")

@Composable
private fun subtitleAssignmentLabel(assignment: PlayerViewModel.SubtitleAssignment): String =
    stringResource(
        when (assignment) {
            PlayerViewModel.SubtitleAssignment.Primary -> PlayerR.string.player_subtitle_primary
            PlayerViewModel.SubtitleAssignment.Secondary -> PlayerR.string.player_subtitle_secondary
            PlayerViewModel.SubtitleAssignment.Native -> PlayerR.string.player_subtitle_native_only
        }
    )

/**
 * 行尾 12sp 标注（VIDEO-11）：内封 / 外挂；图形字幕追加「· 图形」。
 *
 * 侧载字幕本质是外挂文件，同样标「外挂」，本机身份由行尾移除键体现。
 */
@Composable
private fun subtitleTrackTag(option: PlayerViewModel.SubtitleOption): String {
    val origin =
        stringResource(
            when (option.origin) {
                PlayerViewModel.SubtitleOrigin.Embedded ->
                    PlayerR.string.player_subtitle_tag_embedded
                PlayerViewModel.SubtitleOrigin.External,
                PlayerViewModel.SubtitleOrigin.Sideloaded ->
                    PlayerR.string.player_subtitle_tag_external
            }
        )
    return if (option.graphic) {
        "$origin · ${stringResource(PlayerR.string.player_subtitle_tag_graphic)}"
    } else {
        origin
    }
}

@Composable
private fun SubtitleTrackTag(text: String) {
    Text(
        text = text,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        color = LocalCinefinColors.current.onSurfaceVariant,
        maxLines = 1,
    )
}

@Composable
private fun subtitleLanguageGroupLabel(language: String): String =
    if (language.isBlank()) {
        stringResource(PlayerR.string.player_subtitle_language_unknown)
    } else {
        LanguageMatcher.displayName(language)
    }

internal data class SubtitleLanguageGroup(
    val language: String,
    val options: List<PlayerViewModel.SubtitleOption>,
)

/**
 * 按语言分组（VIDEO-11）。
 *
 * 组序 = 语言在清单里首次出现的位置；组内内封在前、外挂（含侧载）在后，`sortedBy` 稳定，同来源保留原标题序。
 */
internal fun groupSubtitleTracksByLanguage(
    tracks: List<PlayerViewModel.SubtitleOption>
): List<SubtitleLanguageGroup> =
    tracks
        .groupBy { it.language.trim() }
        .map { (language, options) ->
            SubtitleLanguageGroup(
                language = language,
                options =
                    options.sortedBy {
                        if (it.origin == PlayerViewModel.SubtitleOrigin.Embedded) 0 else 1
                    },
            )
        }

/** VIDEO-11 非阻断提示条：玻璃底 + 发丝线 + Xs 圆角，只给动作，不拦选轨。 */
@Composable
private fun SubtitleBackendHint(onSwitchBackend: () -> Unit) {
    val colors = LocalCinefinColors.current
    val shape = CinefinShapes.Xs
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier.fillMaxWidth()
                .padding(horizontal = CinefinSpacing.Space3, vertical = CinefinSpacing.Space1)
                .clip(shape)
                .background(colors.glass)
                .border(1.dp, colors.glassBorder, shape)
                .padding(horizontal = CinefinSpacing.Space3, vertical = CinefinSpacing.Space2),
    ) {
        Text(
            text = stringResource(PlayerR.string.player_subtitle_graphic_backend_hint),
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(CinefinSpacing.Space2))
        PanelChip(
            label = stringResource(PlayerR.string.player_subtitle_graphic_backend_switch),
            selected = false,
            onClick = onSwitchBackend,
        )
    }
}

@Composable
private fun subtitleSwapBlockReason(reason: PlayerViewModel.SubtitleSwapBlock): String =
    stringResource(
        when (reason) {
            PlayerViewModel.SubtitleSwapBlock.NoPrimary,
            PlayerViewModel.SubtitleSwapBlock.PrimaryNativeOnly ->
                PlayerR.string.player_subtitle_swap_no_primary
            PlayerViewModel.SubtitleSwapBlock.NoSecondary,
            PlayerViewModel.SubtitleSwapBlock.SecondaryNativeOnly ->
                PlayerR.string.player_subtitle_swap_no_secondary
            PlayerViewModel.SubtitleSwapBlock.SameTrack -> PlayerR.string.player_subtitle_swap_same
        }
    )

/** 延迟一行：−0.05s / 当前值（点击归零）/ +0.05s；步长取 `PlayerSubtitleController.DELAY_STEP_MS` */
@Composable
private fun SubtitleDelayRow(
    delayMs: Long,
    onAdjust: (Long) -> Unit,
    onReset: () -> Unit,
) {
    val stepLabel = formatSubtitleDelayStep(PlayerSubtitleController.DELAY_STEP_MS)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
    ) {
        PanelChip(
            label = "−$stepLabel",
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
            label = "+$stepLabel",
            selected = false,
            contentDescription = stringResource(PlayerR.string.player_subtitle_delay_later),
            onClick = { onAdjust(PlayerSubtitleController.DELAY_STEP_MS) },
        )
    }
}

/** W27 侧载字幕行尾的移除键（删除本机文件；正在使用的轨会同时被移除） */
@Composable
private fun SideloadRemoveKey(
    id: Int,
    onRemove: (Int) -> Unit,
) {
    PlayerIconButton(
        iconRes = CoreR.drawable.ic_trash,
        contentDescription = stringResource(PlayerR.string.player_subtitle_remove_sideload),
        onClick = { onRemove(id) },
        size = 36.dp,
        iconSize = 20.dp,
        glass = false,
    )
}

/**
 * 字幕外观页：实时预览固定在页签下方，主 / 次 / 布局编辑与快捷方案在单一滚动区。
 *
 * 所有改动都通过 [onChange] 即时提交（ViewModel 落盘并应用），没有「应用」按钮。
 */
@Composable
private fun SubtitleAppearancePage(
    state: PlayerViewModel.SubtitlePanelState,
    onUpdateAppearance: (SubtitleAppearance) -> Unit,
) {
    var quickActionsExpanded by rememberSaveable { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxSize()) {
        // 预览在滚动区外：窄屏编辑时仍能实时看到字号 / 颜色 / 间距变化。
        SubtitleAppearancePreview(appearance = state.appearance)
        PanelList {
            SubtitleAppearanceEditor(
                appearance = state.appearance,
                onChange = onUpdateAppearance,
            )
            SubtitleExpandableSection(
                title = stringResource(PlayerR.string.player_subtitle_quick_actions),
                expanded = quickActionsExpanded,
                onToggle = { quickActionsExpanded = !quickActionsExpanded },
            ) {
                SubtitleAppearanceActions(
                    appearance = state.appearance,
                    onChange = onUpdateAppearance,
                )
            }
        }
    }
}

/** 主 / 次 / 布局分段编辑；预设等快捷动作由外层折叠区承载。 */
@Composable
private fun SubtitleAppearanceEditor(
    appearance: SubtitleAppearance,
    onChange: (SubtitleAppearance) -> Unit,
) {
    val colors = LocalCinefinColors.current
    val shape = CinefinShapes.Card
    var tabIndex by rememberSaveable { mutableStateOf(0) }
    Column(
        modifier =
            Modifier.fillMaxWidth()
                .padding(horizontal = CinefinSpacing.Space3, vertical = CinefinSpacing.Space2)
                .clip(shape)
                .background(colors.surfaceContainerLow)
                .border(1.dp, colors.outlineVariant, shape)
                .padding(vertical = CinefinSpacing.Space2)
    ) {
        PanelChipRow(
            options =
                listOf(
                    stringResource(PlayerR.string.player_subtitle_tab_primary) to (tabIndex == 0),
                    stringResource(PlayerR.string.player_subtitle_tab_secondary) to (tabIndex == 1),
                    stringResource(PlayerR.string.player_subtitle_tab_layout) to (tabIndex == 2),
                ),
            onSelect = { index -> tabIndex = index },
        )
        when (tabIndex) {
            0 ->
                SubtitleStyleEditor(style = appearance.primary) { style ->
                    onChange(appearance.copy(primary = style))
                }
            1 ->
                SubtitleStyleEditor(style = appearance.secondary) { style ->
                    onChange(appearance.copy(secondary = style))
                }
            else ->
                SubtitleLayoutEditor(layout = appearance.layout) { layout ->
                    onChange(appearance.copy(layout = layout))
                }
        }
    }
}

/** 两行实时预览：字号 / 颜色 / 背景 / 描边 / 间距 / 底部位置都直接反映当前设置。 */
@Composable
private fun SubtitleAppearancePreview(
    appearance: SubtitleAppearance,
    modifier: Modifier = Modifier,
) {
    val colors = LocalCinefinColors.current
    val shape = CinefinShapes.Card
    val previewHeight = 150.dp
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = CinefinSpacing.Space3, vertical = CinefinSpacing.Space2)
                .height(previewHeight)
                .clip(shape)
                // 预览画布底：表面系 token 的纵深渐变（2.0.1 定稿 §2 批③ AUDIT-22，原写死 #2B2F36→#15171B）
                .background(
                    Brush.verticalGradient(
                        listOf(colors.surfaceContainerHighest, colors.surfaceContainerLowest)
                    )
                )
                .border(1.dp, colors.outlineVariant, shape)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier =
                Modifier.align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .padding(bottom = previewHeight * appearance.layout.bottomFraction),
        ) {
            SubtitlePreviewLine(
                text = stringResource(PlayerR.string.player_subtitle_preview_primary),
                style = appearance.primary,
                fontSize = 11.sp * appearance.primary.textScale,
            )
            Spacer(Modifier.height(appearance.layout.gapDp.dp))
            SubtitlePreviewLine(
                text = stringResource(PlayerR.string.player_subtitle_preview_secondary),
                style = appearance.secondary,
                fontSize = 11.sp * appearance.secondary.textScale,
            )
        }
        if (appearance.layout.gapDp == 0) {
            Text(
                text = stringResource(PlayerR.string.player_subtitle_gap_zero_hint),
                style = MaterialTheme.typography.bodySmall,
                // 高对比反色对：浅 / 深主题下都 ≥13:1（原写死 #E6E6E6 文字 + #66000000 底）
                color = colors.inverseOnSurface,
                modifier =
                    Modifier.align(Alignment.TopCenter)
                        .padding(top = CinefinSpacing.Space2)
                        .clip(CinefinShapes.Xs)
                        .background(colors.inverseSurface)
                        .padding(horizontal = CinefinSpacing.Space2, vertical = 2.dp),
            )
        }
    }
}

/** 预览里的一行：与播放覆盖层同口径的「黑描边 + 填充 + 背景框」，去掉外层透明留白让间距可读。 */
@Composable
private fun SubtitlePreviewLine(
    text: String,
    style: SubtitleStyle,
    fontSize: TextUnit,
) {
    val density = LocalDensity.current
    val strokeWidthPx = with(density) { style.edgeWidthDp.dp.toPx() }
    val textStyle =
        TextStyle(
            fontSize = fontSize,
            lineHeight = fontSize * 1.25f,
            textAlign = TextAlign.Center,
        )
    Box(
        modifier =
            Modifier.clip(CinefinShapes.TwoXs)
                .background(Color(style.backgroundArgb))
                .padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        if (strokeWidthPx > 0f) {
            Text(
                text = text,
                style =
                    textStyle.copy(
                        color = Color.Black,
                        drawStyle = Stroke(width = strokeWidthPx),
                    ),
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
        }
        Text(
            text = text,
            style = textStyle.copy(color = Color(style.textColor)),
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}

/** 主 / 次字幕各自的字号、颜色、背景、描边。 */
@Composable
private fun SubtitleStyleEditor(
    style: SubtitleStyle,
    onChange: (SubtitleStyle) -> Unit,
) {
    SubtitleSliderRow(
        label = stringResource(PlayerR.string.player_subtitle_size_percent, style.sizePercent),
        value = style.sizePercent.toFloat(),
        valueRange =
            SubtitleStyle.MIN_SIZE_PERCENT.toFloat()..SubtitleStyle.MAX_SIZE_PERCENT.toFloat(),
        steps = 29,
        onValueChange = { value ->
            onChange(style.copy(sizePercent = SubtitleStyle.snapSizePercent(value.roundToInt())))
        },
    )

    SubtitleSectionLabel(stringResource(PlayerR.string.player_subtitle_color))
    SubtitleColorPicker(
        selectedColor = style.textColor,
        onSelect = { color -> onChange(style.copy(textColor = color)) },
    )

    SubtitleSectionLabel(stringResource(PlayerR.string.player_subtitle_background))
    SubtitleBackgroundPicker(
        style = style,
        onChange = onChange,
    )
    SubtitleSliderRow(
        label =
            stringResource(
                PlayerR.string.player_subtitle_background_opacity,
                style.backgroundOpacityPercent,
            ),
        value = style.backgroundOpacityPercent.toFloat(),
        valueRange =
            SubtitleStyle.MIN_BACKGROUND_OPACITY_PERCENT.toFloat()..SubtitleStyle
                    .MAX_BACKGROUND_OPACITY_PERCENT
                    .toFloat(),
        steps = 19,
        enabled = style.hasBackground,
        onValueChange = { value ->
            onChange(
                style.copy(backgroundOpacityPercent = (value / 5f).roundToInt().coerceIn(0, 20) * 5)
            )
        },
    )

    SubtitleSectionLabel(stringResource(PlayerR.string.player_subtitle_edge))
    PanelChipRow(
        options =
            (SubtitleStyle.MIN_EDGE_WIDTH_DP..SubtitleStyle.MAX_EDGE_WIDTH_DP).map { dp ->
                stringResource(PlayerR.string.player_subtitle_edge_value, dp) to
                    (style.edgeWidthDp == dp)
            },
        onSelect = { index -> onChange(style.copy(edgeWidthDp = index)) },
    )
}

/** 共享布局：主次间距 + 底部位置。 */
@Composable
private fun SubtitleLayoutEditor(
    layout: SubtitleLayout,
    onChange: (SubtitleLayout) -> Unit,
) {
    val colors = LocalCinefinColors.current
    SubtitleSliderRow(
        label = stringResource(PlayerR.string.player_subtitle_gap, layout.gapDp),
        value = layout.gapDp.toFloat(),
        valueRange = SubtitleLayout.MIN_GAP_DP.toFloat()..SubtitleLayout.MAX_GAP_DP.toFloat(),
        steps = 23,
        onValueChange = { value ->
            onChange(layout.copy(gapDp = value.roundToInt().coerceIn(0, 24)))
        },
    )
    if (layout.gapDp == 0) {
        Text(
            text = stringResource(PlayerR.string.player_subtitle_gap_zero_hint),
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
            modifier =
                Modifier.padding(
                    start = CinefinSpacing.Space5,
                    end = CinefinSpacing.Space5,
                    bottom = CinefinSpacing.Space1,
                ),
        )
    }
    SubtitleSliderRow(
        label = stringResource(PlayerR.string.player_subtitle_bottom, layout.bottomPercent),
        value = layout.bottomPercent.toFloat(),
        valueRange =
            SubtitleLayout.MIN_BOTTOM_PERCENT.toFloat()..SubtitleLayout.MAX_BOTTOM_PERCENT
                    .toFloat(),
        steps = 29,
        onValueChange = { value ->
            onChange(layout.copy(bottomPercent = value.roundToInt().coerceIn(0, 30)))
        },
    )
    Text(
        text = stringResource(PlayerR.string.player_subtitle_bottom_hint),
        style = MaterialTheme.typography.bodySmall,
        color = colors.onSurfaceVariant,
        modifier =
            Modifier.padding(
                start = CinefinSpacing.Space5,
                end = CinefinSpacing.Space5,
                bottom = CinefinSpacing.Space1,
            ),
    )
}

@Composable
private fun SubtitleSliderRow(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    enabled: Boolean = true,
    onValueChange: (Float) -> Unit,
) {
    val colors = LocalCinefinColors.current
    Column(modifier = Modifier.fillMaxWidth().padding(top = CinefinSpacing.Space1)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant,
            modifier =
                Modifier.padding(
                    start = CinefinSpacing.Space5,
                    end = CinefinSpacing.Space5,
                    bottom = CinefinSpacing.Space1,
                ),
        )
        CinefinSlider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
            enabled = enabled,
            modifier = Modifier.padding(horizontal = CinefinSpacing.Space5),
        )
    }
}

@Composable
private fun SubtitleSectionLabel(text: String) {
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
                bottom = CinefinSpacing.Space1,
            ),
    )
}

/** 8 个文字颜色预设 + 「自定义」简单取色器。 */
@Composable
private fun SubtitleColorPicker(
    selectedColor: Int,
    onSelect: (Int) -> Unit,
) {
    var customExpanded by remember { mutableStateOf(false) }
    val presetLabels =
        listOf(
                PlayerR.string.player_subtitle_color_white,
                PlayerR.string.player_subtitle_color_yellow,
                PlayerR.string.player_subtitle_color_cyan,
                PlayerR.string.player_subtitle_color_green,
                PlayerR.string.player_subtitle_color_orange,
                PlayerR.string.player_subtitle_color_pink,
                PlayerR.string.player_subtitle_color_light_blue,
                PlayerR.string.player_subtitle_color_light_purple,
            )
            .map { stringResource(it) }
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier =
            Modifier.fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 2.dp),
    ) {
        SubtitleStyle.COLORS.forEachIndexed { index, color ->
            SubtitleColorChip(
                label = presetLabels[index],
                color = color,
                selected = selectedColor == color,
                onClick = {
                    customExpanded = false
                    onSelect(color)
                },
            )
        }
        val isCustom = selectedColor !in SubtitleStyle.COLORS
        SubtitleColorChip(
            label = stringResource(PlayerR.string.player_subtitle_custom_color),
            color = selectedColor,
            selected = isCustom || customExpanded,
            onClick = { customExpanded = !customExpanded },
        )
    }
    if (customExpanded) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier =
                Modifier.fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 2.dp),
        ) {
            SubtitleStyle.CUSTOM_COLORS.forEach { color ->
                SubtitleColorSwatch(
                    color = color,
                    selected = selectedColor == color,
                    onClick = { onSelect(color) },
                )
            }
        }
        Text(
            text =
                stringResource(
                    PlayerR.string.player_subtitle_custom_color_value,
                    hexColor(selectedColor),
                ),
            style = MaterialTheme.typography.bodySmall,
            color = LocalCinefinColors.current.onSurfaceVariant,
            modifier =
                Modifier.padding(
                    start = CinefinSpacing.Space5,
                    end = CinefinSpacing.Space5,
                    top = CinefinSpacing.Space1,
                ),
        )
    }
}

@Composable
private fun SubtitleColorChip(
    label: String,
    color: Int,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalCinefinColors.current
    val media = LocalMediaColors.current
    val shape = CinefinShapes.Button
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier.clip(shape)
                .background(if (selected) media.container else colors.surfaceContainer)
                .border(1.dp, if (selected) media.outline else colors.outline, shape)
                .clickable(onClick = onClick)
                .semantics { this.selected = selected }
                .padding(horizontal = CinefinSpacing.Space3, vertical = CinefinSpacing.Space2),
    ) {
        Box(
            modifier =
                Modifier.size(12.dp)
                    .clip(CircleShape)
                    .background(Color(color))
                    .border(1.dp, colors.outline, CircleShape)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) media.bright else colors.onSurfaceVariant,
        )
    }
}

@Composable
private fun SubtitleColorSwatch(
    color: Int,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalCinefinColors.current
    val media = LocalMediaColors.current
    Box(
        modifier =
            Modifier.size(28.dp)
                .clip(CircleShape)
                .background(Color(color))
                .border(
                    width = if (selected) 2.dp else 1.dp,
                    color = if (selected) media.bright else colors.outline,
                    shape = CircleShape,
                )
                .clickable(onClick = onClick)
                .semantics {
                    this.selected = selected
                    contentDescription = hexColor(color)
                }
    )
}

private fun hexColor(color: Int): String = String.format("#%06X", color and 0x00FFFFFF)

/** 背景：无 / 黑 / 深灰 / 白；从不透明度 0 切到某个底色时自动给一个可见默认值。 */
@Composable
private fun SubtitleBackgroundPicker(
    style: SubtitleStyle,
    onChange: (SubtitleStyle) -> Unit,
) {
    val colorIndex =
        if (!style.hasBackground) {
            0
        } else {
            SubtitleStyle.BACKGROUND_COLORS.indexOf(style.backgroundColor)
                .takeIf { it >= 0 }
                ?.plus(1) ?: 0
        }
    PanelChipRow(
        options =
            listOf(
                    stringResource(PlayerR.string.player_subtitle_background_none),
                    stringResource(PlayerR.string.player_subtitle_background_black),
                    stringResource(PlayerR.string.player_subtitle_background_dark_gray),
                    stringResource(PlayerR.string.player_subtitle_background_white),
                )
                .mapIndexed { index, label -> label to (index == colorIndex) },
        onSelect = { index ->
            if (index == 0) {
                onChange(style.copy(backgroundOpacityPercent = 0))
            } else {
                onChange(
                    style.copy(
                        backgroundColor = SubtitleStyle.BACKGROUND_COLORS[index - 1],
                        backgroundOpacityPercent =
                            style.backgroundOpacityPercent.takeIf { it > 0 }
                                ?: SubtitleStyle.BACKGROUND_OPACITY_WHEN_ENABLED,
                    )
                )
            }
        },
    )
}

/** 一键预设 / 同步样式 / 恢复默认。 */
@Composable
private fun SubtitleAppearanceActions(
    appearance: SubtitleAppearance,
    onChange: (SubtitleAppearance) -> Unit,
) {
    SubtitleSectionLabel(stringResource(PlayerR.string.player_subtitle_presets))
    PanelChipRow(
        options =
            listOf(
                stringResource(PlayerR.string.player_subtitle_preset_standard) to false,
                stringResource(PlayerR.string.player_subtitle_preset_cinema) to false,
                stringResource(PlayerR.string.player_subtitle_preset_bottom) to false,
            ),
        onSelect = { index ->
            when (index) {
                0 -> onChange(SubtitleAppearance.Standard)
                1 -> onChange(SubtitleAppearance.Cinema)
                else -> onChange(SubtitleAppearance.stickToBottom(appearance))
            }
        },
    )
    SubtitleSectionLabel(stringResource(PlayerR.string.player_subtitle_sync))
    PanelChipRow(
        options =
            listOf(
                stringResource(PlayerR.string.player_subtitle_sync_primary_to_secondary) to false,
                stringResource(PlayerR.string.player_subtitle_sync_secondary_to_primary) to false,
            ),
        onSelect = { index ->
            if (index == 0) {
                onChange(appearance.copy(secondary = appearance.primary))
            } else {
                onChange(appearance.copy(primary = appearance.secondary))
            }
        },
    )
    SubtitleSectionLabel(stringResource(PlayerR.string.player_subtitle_reset))
    PanelChipRow(
        options =
            listOf(
                stringResource(PlayerR.string.player_subtitle_reset_primary) to false,
                stringResource(PlayerR.string.player_subtitle_reset_secondary) to false,
                stringResource(PlayerR.string.player_subtitle_reset_layout) to false,
            ),
        onSelect = { index ->
            when (index) {
                0 -> onChange(appearance.copy(primary = SubtitleAppearance.Default.primary))
                1 -> onChange(appearance.copy(secondary = SubtitleAppearance.Default.secondary))
                else -> onChange(appearance.copy(layout = SubtitleAppearance.Default.layout))
            }
        },
    )
}

/** 面板里的小胶囊按钮（§8.3 Chip）：未选中中性底 + 描边，选中态媒体色融入控件本体。 */
@Composable
internal fun PanelChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    contentDescription: String? = null,
    enabled: Boolean = true,
) {
    val colors = LocalCinefinColors.current
    val media = LocalMediaColors.current
    val shape = CinefinShapes.Button
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier.clip(shape)
                .background(
                    when {
                        !enabled -> colors.surfaceContainerLow
                        selected -> media.container
                        else -> colors.surfaceContainer
                    }
                )
                .border(
                    1.dp,
                    if (selected && enabled) media.outline else colors.outlineVariant,
                    shape,
                )
                .clickable(enabled = enabled, onClick = onClick)
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
            color =
                when {
                    !enabled -> colors.onSurfaceFaint
                    selected -> media.bright
                    else -> colors.onSurfaceVariant
                },
        )
    }
}

/** 延迟显示：+0.30s / −0.05s / 0.00s（两位小数，50ms 步进在 UI 上可见） */
internal fun formatSubtitleDelay(delayMs: Long): String {
    val seconds = kotlin.math.abs(delayMs) / 1000.0
    return when {
        delayMs > 0L -> "+%.2fs".format(seconds)
        delayMs < 0L -> "−%.2fs".format(seconds)
        else -> "0.00s"
    }
}

/** 步长标签（不带符号）：0.05s；与 [PlayerSubtitleController.DELAY_STEP_MS] 同步，不写死文案 */
internal fun formatSubtitleDelayStep(stepMs: Long): String = "%.2fs".format(stepMs / 1000.0)

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
            // W2-T13（需求 #2）：首选音轨语言属「配置一次长期有效」，只保留在设置页（语言与字幕子页）。
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
    currentStreamInfo: PlayerMediaInfo?,
    mediaIdentity: PlaybackMediaIdentity?,
    generation: Long,
) {
    /*
     * 源媒体与当前流严格分区。内核值只写 current 区，绝不回退 source；
     * 轨道集从 null → ready、选轨、换源或 generation 变化时先清旧值再重读。
     */
    var kernelInfo by
        remember(player, mediaIdentity, generation) { mutableStateOf<PlayerMediaInfo?>(null) }
    LaunchedEffect(player, runtime.tracks, mediaIdentity, generation) {
        kernelInfo = null
        kernelInfo =
            if (player is MPVPlayer) {
                withContext(Dispatchers.IO) {
                    runCatching { readKernelMediaInfo(player) }.getOrNull()
                }
            } else {
                // ExoPlayer.currentTracks 是主线程状态，必须在 Compose effect 的 Main 上读取。
                runCatching { readKernelMediaInfo(player) }.getOrNull()
            }
    }
    val currentInfo = kernelInfo ?: currentStreamInfo
    val sections = playerMediaInfoSections(sourceInfo, currentInfo)

    val audioCount =
        runtime.tracks?.groups?.count { it.type == C.TRACK_TYPE_AUDIO && it.isSupported } ?: 0
    val subtitleCount =
        runtime.tracks?.groups?.count { it.type == C.TRACK_TYPE_TEXT && it.isSupported } ?: 0

    Column(modifier = Modifier.fillMaxSize()) {
        PanelList {
            InfoSectionHeader(stringResource(PlayerR.string.player_controls_info_source_section))
            InfoRow(
                label = stringResource(PlayerR.string.player_controls_info_title),
                value = title.ifEmpty { runtime.title },
            )
            InfoRow(
                label = stringResource(PlayerR.string.player_controls_info_container),
                value = sections.sourceContainer,
            )
            InfoRow(
                label = stringResource(PlayerR.string.player_controls_info_codec),
                value = sections.sourceCodec,
            )
            InfoRow(
                label = stringResource(PlayerR.string.player_controls_info_resolution),
                value = sections.sourceResolution,
            )
            InfoRow(
                label = stringResource(PlayerR.string.player_controls_info_bitrate),
                value = sections.sourceBitrate,
            )
            InfoRow(
                label = stringResource(PlayerR.string.player_controls_info_frame_rate),
                value = sections.sourceFrameRate,
            )
            InfoRow(
                label = stringResource(PlayerR.string.player_controls_info_hdr),
                value = sections.sourceHdr,
            )
            InfoRow(
                label = stringResource(PlayerR.string.player_controls_info_audio_format),
                value = sections.sourceAudio,
            )
            InfoRow(
                label = stringResource(PlayerR.string.player_controls_info_file_size),
                value = sections.sourceFileSize,
            )
            InfoRow(
                label = stringResource(PlayerR.string.player_controls_info_path),
                value = sections.sourcePath,
            )

            InfoSectionHeader(stringResource(PlayerR.string.player_controls_info_current_section))
            InfoRow(
                label = stringResource(PlayerR.string.player_controls_info_container),
                value = sections.currentContainer,
            )
            InfoRow(
                label = stringResource(PlayerR.string.player_controls_info_codec),
                value = sections.currentCodec,
            )
            InfoRow(
                label = stringResource(PlayerR.string.player_controls_info_resolution),
                value = sections.currentResolution,
            )
            InfoRow(
                label = stringResource(PlayerR.string.player_controls_info_bitrate),
                value = sections.currentBitrate,
            )
            InfoRow(
                label = stringResource(PlayerR.string.player_controls_info_frame_rate),
                value = sections.currentFrameRate,
            )
            InfoRow(
                label = stringResource(PlayerR.string.player_controls_info_hdr),
                value = sections.currentHdr,
            )
            InfoRow(
                label = stringResource(PlayerR.string.player_controls_info_audio_format),
                value = sections.currentAudio,
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
private fun InfoSectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier =
            Modifier.fillMaxWidth()
                .padding(
                    horizontal = CinefinSpacing.Space5,
                    vertical = CinefinSpacing.Space3,
                ),
    )
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
    state: SleepTimerController.State,
    onSelect: (Int?) -> Unit,
) {
    val colors = LocalCinefinColors.current
    val activeMinutes = state.minutes
    val customActiveMinutes = activeMinutes?.takeIf { it !in SleepTimerSpec.PRESET_MINUTES }
    val isCustomActive = customActiveMinutes != null
    var customExpanded by remember { mutableStateOf(false) }
    var customMinutes by remember { mutableIntStateOf(customActiveMinutes ?: 30) }
    Column(modifier = Modifier.fillMaxSize()) {
        PanelList {
            if (state.active) {
                PanelTitle(
                    stringResource(
                        CoreR.string.sleep_timer_subtitle_active,
                        SleepTimerSpec.formatRemaining(state.remainingMs),
                    )
                )
            } else {
                PanelTitle(stringResource(CoreR.string.sleep_timer_subtitle))
            }
            PanelRow(
                label =
                    stringResource(
                        if (state.active) CoreR.string.sleep_timer_cancel
                        else CoreR.string.sleep_timer_off
                    ),
                selected = !state.active,
                onClick = { onSelect(null) },
            )
            SleepTimerSpec.PRESET_MINUTES.forEach { minutes ->
                PanelRow(
                    label = stringResource(CoreR.string.sleep_timer_minutes, minutes),
                    selected = activeMinutes == minutes,
                    onClick = { onSelect(minutes) },
                )
            }
            PanelRow(
                label = stringResource(CoreR.string.sleep_timer_custom),
                caption =
                    when {
                        customExpanded -> stringResource(CoreR.string.sleep_timer_custom_hint)
                        customActiveMinutes != null ->
                            stringResource(
                                CoreR.string.sleep_timer_custom_current,
                                customActiveMinutes,
                            )
                        else -> null
                    },
                selected = isCustomActive,
                onClick = {
                    if (!customExpanded && customActiveMinutes != null) {
                        customMinutes = customActiveMinutes
                    }
                    customExpanded = !customExpanded
                },
            )
            if (customExpanded) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier =
                        Modifier.fillMaxWidth()
                            .padding(
                                horizontal = CinefinSpacing.Space5,
                                vertical = CinefinSpacing.Space2,
                            ),
                ) {
                    Text(
                        text = stringResource(CoreR.string.sleep_timer_custom_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = stringResource(CoreR.string.sleep_timer_minutes, customMinutes),
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.onSurface,
                    )
                }
                CinefinSlider(
                    value = customMinutes.toFloat(),
                    onValueChange = { value ->
                        customMinutes =
                            value
                                .roundToInt()
                                .coerceIn(
                                    SleepTimerSpec.MIN_MINUTES,
                                    SleepTimerSpec.MAX_MINUTES,
                                )
                    },
                    valueRange =
                        SleepTimerSpec.MIN_MINUTES.toFloat()..SleepTimerSpec.MAX_MINUTES.toFloat(),
                    steps = SleepTimerSpec.MAX_MINUTES - SleepTimerSpec.MIN_MINUTES - 1,
                    modifier = Modifier.padding(horizontal = CinefinSpacing.Space5),
                )
                PanelRow(
                    label = stringResource(CoreR.string.sleep_timer_custom_start, customMinutes),
                    selected = false,
                    onClick = { onSelect(customMinutes) },
                )
            }
        }
    }
}
