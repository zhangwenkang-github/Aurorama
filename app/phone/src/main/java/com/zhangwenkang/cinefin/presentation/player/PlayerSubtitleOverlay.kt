package com.zhangwenkang.cinefin.presentation.player

import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import com.zhangwenkang.cinefin.player.core.domain.models.SubtitleStyle
import com.zhangwenkang.cinefin.player.local.mpv.MPVPlayer
import com.zhangwenkang.cinefin.player.local.subtitle.AssSubtitleScript
import com.zhangwenkang.cinefin.player.local.subtitle.LibassFrame
import com.zhangwenkang.cinefin.player.local.subtitle.LibassSubtitleRenderer
import com.zhangwenkang.cinefin.player.local.subtitle.SubtitleCue
import com.zhangwenkang.cinefin.player.local.subtitle.SubtitleOverlayState
import com.zhangwenkang.cinefin.player.local.subtitle.nextLibassFrame
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * 自研字幕渲染层（§1.1；W16 起主字幕走 libass）。
 *
 * 贴在 PlayerView 之上、控制层之下：只画字幕，不处理任何触摸。之所以不用 Media3 的 SubtitleView：这里要同时显示主/次两条字幕、 要能整体做 ±0.1s
 * 时间偏移、还要字号 / 颜色 / 背景 / 描边 / 位置全可控。
 *
 * W16 渲染路由（与 mpv 内核的原生 libass 对齐）：
 * - 主字幕：ASS/SSA 原文或 SRT 生成的 ASS 交给 [LibassSubtitleRenderer]（特效 / 定位 / 字体由 libass 还原， 延迟由「播放位置 −
 *   延迟」驱动，大小档位映射成 libass 字号倍率）；
 * - 次字幕：仍走纯文本叠层（与 mpv 的 `secondary-sid` strip 语义一致）；
 * - libass 初始化 / 渲染失败：回退到既有 cue 文本渲染（[SubtitleOverlayState.primaryCues]），不崩溃、打日志。
 *
 * 时间同步：每帧读一次播放位置，和 cue 的起止时间比对——字幕切换精度与画面刷新同步； libass 侧用同一帧率驱动，渲染放到 Default 线程，避免复杂特效压住主线程。
 */
@Composable
fun PlayerSubtitleOverlay(
    player: Player,
    state: SubtitleOverlayState,
    modifier: Modifier = Modifier,
    /**
     * 画面显示区（视频实际显示的像素矩形，相对本覆盖层坐标系）。
     *
     * libass 的渲染分辨率即这块矩形：字幕定位跟视频走，而不是跟整窗走（竖屏 / 折叠形态下尤其明显）。
     */
    videoRectProvider: () -> Rect = { Rect() },
) {
    val density = LocalDensity.current
    val renderer = remember { LibassSubtitleRenderer() }
    val textMeasurer = rememberTextMeasurer()
    val paint = remember { Paint() }
    val isMpv = player is MPVPlayer
    /*
     * 双字幕期间的渲染路由：
     * - Exo：主字幕始终走 App 侧 libass；
     * - mpv：单字幕仍由内核原生渲染（行为不变）；进入双字幕且主字幕源是文本
     *   （[SubtitleOverlayState.primaryTextRenderable]）后改由 App 侧 libass 接管，与 Exo 共用同一套可见边界几何，
     *   同时临时隐藏原生字幕，避免同一句出现两份。图形字幕没有可交给 libass 的文本，继续走原生兜底。
     */
    val appPrimaryPlanned =
        !isMpv ||
            (state.secondaryManaged && state.primaryTextRenderable && !state.primaryRenderFailed)
    val appPrimaryActive = !isMpv || (appPrimaryPlanned && state.primaryManaged)
    val nativePrimaryHidden = isMpv && appPrimaryPlanned

    var primaryText by remember { mutableStateOf("") }
    var secondaryText by remember { mutableStateOf("") }
    var assFrame by remember { mutableStateOf<LibassFrame?>(null) }
    var libassFailed by remember { mutableStateOf(false) }
    var videoRect by remember { mutableStateOf(Rect()) }
    var appliedReservationKey by remember { mutableStateOf<String?>(null) }

    val libassEnabled =
        appPrimaryActive &&
            state.primaryManaged &&
            (state.primaryAssScript != null || state.primaryCues.isNotEmpty())

    DisposableEffect(renderer) { onDispose { renderer.release() } }

    /*
     * mpv 双字幕由 App 侧接管主字幕时，原生字幕必须整体停用；退出双字幕 / 离开播放页时恢复进入前的可见性。
     * 接管意图由「双字幕 + 文本主字幕源」决定，不等 libass 首帧：宁可短暂空一帧，也不允许原生与 App 两份同屏。
     */
    LaunchedEffect(player, nativePrimaryHidden) {
        (player as? MPVPlayer)?.applyNativePrimarySubtitleVisible(!nativePrimaryHidden)
    }

    DisposableEffect(player) {
        onDispose {
            // 离开播放页 / 切换内核时清掉为次字幕预留的 mpv 原生字幕位置。
            (player as? MPVPlayer)?.applySecondarySubtitleReservation(null)
            (player as? MPVPlayer)?.applyNativePrimarySubtitleVisible(true)
        }
    }

    LaunchedEffect(
        player,
        libassEnabled,
        state.primaryAssScript,
        state.primaryCues,
        state.secondaryCues,
        state.secondaryManaged,
        state.delayMs,
        state.primaryStyle,
        state.layout,
        density,
    ) {
        if (!libassEnabled) {
            assFrame = null
            primaryText = ""
        }
        // 载入 / 重载标记：脚本来源 + 渲染分辨率 + 视频像素尺寸 + 字号档位
        var loadedKey: String? = null
        var loggedFailure = false

        while (isActive) {
            withFrameNanos {}
            val positionMs = player.currentPosition
            if (positionMs < 0L) continue

            // ---- 文本层（次字幕；libass 失败时也接管主字幕）----
            val nextSecondary =
                if (state.secondaryManaged) {
                    visibleText(state.secondaryCues, positionMs, state.delayMs)
                } else {
                    ""
                }
            val nextPrimary =
                if (appPrimaryActive && (!libassEnabled || libassFailed)) {
                    visibleText(state.primaryCues, positionMs, state.delayMs)
                } else {
                    ""
                }
            if (nextPrimary != primaryText) primaryText = nextPrimary
            if (nextSecondary != secondaryText) secondaryText = nextSecondary

            if (!libassEnabled || libassFailed) continue

            // ---- libass 层：画面矩形 / 视频像素尺寸变化时重排脚本 ----
            val rect = videoRectProvider()
            if (rect.width() < 2 || rect.height() < 2) continue
            if (rect != videoRect) videoRect = rect
            val videoSize = player.videoSize
            if (videoSize.width <= 0 || videoSize.height <= 0) continue
            val storageWidth = videoSize.width
            val storageHeight =
                (videoSize.height * videoSize.pixelWidthHeightRatio.coerceAtLeast(0.01f))
                    .roundToInt()
            val frameWidth = rect.width()
            val frameHeight = rect.height()
            val textScale = state.primaryStyle.textScale
            val usesOriginalAss = state.primaryAssScript != null
            /*
             * SRT / VTT 生成的脚本已经把字号百分比写进 Style，libass 的 fontScale 不能再乘一次；
             * 原始 ASS/SSA 无法改写作者的 Style，才用 fontScale 套用面板字号。
             */
            val fontScale = if (usesOriginalAss) textScale else 1f
            val key =
                "$frameWidth x $frameHeight | $storageWidth x $storageHeight | $fontScale | " +
                    "${state.primaryIndex}:${state.primaryAssScript?.length ?: -1}:" +
                    "${state.primaryCues.size}"
            if (key != loadedKey) {
                val script =
                    state.primaryAssScript
                        ?: AssSubtitleScript.forCues(
                            cues = state.primaryCues,
                            style = state.primaryStyle,
                            layout = state.layout,
                            playResX = storageWidth,
                            playResY = storageHeight,
                            frameHeightPx = frameHeight,
                            density = density.density,
                        )
                val ok =
                    withContext(Dispatchers.Default) {
                        renderer.load(
                            script = script,
                            fontScale = fontScale,
                            storageWidth = storageWidth,
                            storageHeight = storageHeight,
                            frameWidth = frameWidth,
                            frameHeight = frameHeight,
                        )
                    }
                loadedKey = if (ok) key else null
                if (!ok) {
                    libassFailed = true
                    if (!loggedFailure) {
                        loggedFailure = true
                        Timber.w(
                            "libass 渲染不可用（%s），回退文本渲染",
                            renderer.failureReason.orEmpty(),
                        )
                    }
                } else {
                    // 验收证据：libass 就绪（脚本来源 / 字节数 / 渲染分辨率 / 字号倍率）
                    Timber.i(
                        "libass 字幕就绪：%s，script=%d bytes，frame=%dx%d，storage=%dx%d，fontScale=%.2f",
                        if (state.primaryAssScript != null) "ASS 原文" else "SRT 生成脚本",
                        script.length,
                        frameWidth,
                        frameHeight,
                        storageWidth,
                        storageHeight,
                        textScale,
                    )
                }
                continue
            }

            val frame =
                withContext(Dispatchers.Default) {
                    renderer.renderFrame((positionMs - state.delayMs).coerceAtLeast(0L))
                }
            // W74 #15：句末那一帧 libass 会回报「没有图元」的空帧，必须真的把它画出去（清屏），
            // 否则上一句会一直挂到下一句（旧实现的真机缺陷）。
            val next = nextLibassFrame(assFrame, frame)
            if (next !== assFrame) {
                if (assFrame?.images?.isNotEmpty() == true && next?.images?.isEmpty() == true) {
                    Timber.d("libass 句末清屏: position=%dms", positionMs)
                }
                assFrame = next
            }
        }
    }

    val primaryStyle = state.primaryStyle
    val secondaryStyle = state.secondaryStyle
    val appearanceLayout = state.layout
    val rectUsable = videoRect.width() >= 2 && videoRect.height() >= 2
    val videoWidthPx = videoRect.width().toFloat()
    val videoHeightPx = videoRect.height().toFloat()
    val baseSize = with(density) { videoHeightPx.toDp() } * AssSubtitleScript.BASE_FONT_FRACTION
    val primarySize = with(density) { (baseSize * primaryStyle.textScale).toSp() }
    val secondaryBaseSize = with(density) { (baseSize * secondaryStyle.textScale).toSp() }
    val verticalPaddingPx = with(density) { SECONDARY_LINE_VERTICAL_PADDING_DP.dp.toPx() }
    val gapPx = with(density) { appearanceLayout.gapDp.dp.toPx() }
    val primaryStrokePx = with(density) { primaryStyle.edgeWidthDp.dp.toPx() }
    val secondaryStrokePx = with(density) { secondaryStyle.edgeWidthDp.dp.toPx() }
    // 块顶 / 块底到可见内容边界的距离：外层透明留白 +（无背景时）内层留白 − 描边外扩。
    val primaryVisibleInsetPx =
        subtitleVisibleTopInsetPx(
            outerPaddingPx = with(density) { SECONDARY_LINE_OUTER_PADDING_DP.dp.toPx() },
            innerPaddingPx = with(density) { SECONDARY_LINE_INNER_PADDING_DP.dp.toPx() },
            strokePx = primaryStrokePx,
            backgroundVisible = primaryStyle.hasBackground,
        )
    val secondaryVisibleInsetPx =
        subtitleVisibleTopInsetPx(
            outerPaddingPx = with(density) { SECONDARY_LINE_OUTER_PADDING_DP.dp.toPx() },
            innerPaddingPx = with(density) { SECONDARY_LINE_INNER_PADDING_DP.dp.toPx() },
            strokePx = secondaryStrokePx,
            backgroundVisible = secondaryStyle.hasBackground,
        )
    // 68dp = 外层左右 24dp + 单行字幕内层左右 10dp；TextMeasurer 与 SubtitleLine 使用同一上限。
    val textMaxWidthPx =
        (videoWidthPx - with(density) { 68.dp.toPx() }).roundToInt().coerceAtLeast(1)

    val secondaryFullHeightPx =
        if (rectUsable && secondaryText.isNotEmpty()) {
            subtitleTextBlockHeightPx(
                measurer = textMeasurer,
                text = secondaryText,
                fontSize = secondaryBaseSize,
                maxWidthPx = textMaxWidthPx,
                verticalPaddingPx = verticalPaddingPx,
            )
        } else {
            0f
        }
    val secondaryMinHeightPx =
        if (rectUsable && secondaryText.isNotEmpty()) {
            subtitleTextBlockHeightPx(
                measurer = textMeasurer,
                text = secondaryText,
                fontSize = secondaryBaseSize * SECONDARY_MIN_SCALE,
                maxWidthPx = textMaxWidthPx,
                verticalPaddingPx = verticalPaddingPx,
            )
        } else {
            0f
        }
    val primaryTextHeightPx =
        if (rectUsable && appPrimaryActive && primaryText.isNotEmpty()) {
            subtitleTextBlockHeightPx(
                measurer = textMeasurer,
                text = primaryText,
                fontSize = primarySize,
                maxWidthPx = textMaxWidthPx,
                verticalPaddingPx = verticalPaddingPx,
            )
        } else {
            0f
        }
    val primaryVisibleHeightPx = subtitleVisibleHeightPx(primaryTextHeightPx, primaryVisibleInsetPx)
    val primaryExtent =
        when {
            libassEnabled && !libassFailed -> assFrame?.subtitleExtent()
            appPrimaryActive && primaryText.isNotEmpty() ->
                PrimarySubtitleExtent.Composed(
                    heightPx = primaryVisibleHeightPx,
                    visibleBottomInsetPx = primaryVisibleInsetPx,
                )
            // mpv 有原生主字幕但映射不到源（或图形字幕）时仍要预留；`sid=no` 才是真的没有主字幕。
            // App 侧已接管时不走这条，避免 cue 之间的空帧误触发预留 / 隐藏。
            isMpv && state.nativePrimaryActive && !appPrimaryPlanned ->
                // 原生字幕无法逐帧拿到图元盒；按两行保守预留，避免大字号双行主字幕被顶部裁切。
                PrimarySubtitleExtent.Native(
                    with(density) { (baseSize * primaryStyle.textScale * 2f * 1.25f).toPx() }
                )
            else -> null
        }
    val layout =
        if (rectUsable) {
            planDualSubtitleLayout(
                videoHeightPx = videoHeightPx,
                bottomInsetPx = videoHeightPx * appearanceLayout.bottomFraction,
                gapPx = gapPx,
                primary = primaryExtent,
                secondaryFullHeightPx = secondaryFullHeightPx,
                secondaryMinHeightPx = secondaryMinHeightPx,
                secondaryVisibleTopInsetPx = secondaryVisibleInsetPx,
                secondaryVisibleBottomInsetPx = secondaryVisibleInsetPx,
            )
        } else {
            DualSubtitleLayout.Hidden
        }
    val reservationKey =
        if (!isMpv || appPrimaryPlanned || !layout.showSecondary) {
            "none"
        } else {
            "reserve:${layout.secondaryReservedFraction}"
        }

    LaunchedEffect(reservationKey, player) {
        val reservation =
            if (isMpv && !appPrimaryPlanned && layout.showSecondary) {
                layout.secondaryReservedFraction
            } else {
                null
            }
        (player as? MPVPlayer)?.applySecondarySubtitleReservation(reservation)
        appliedReservationKey = reservationKey
    }

    val showPrimaryText = appPrimaryActive && primaryText.isNotEmpty()
    val showSecondaryText =
        layout.showSecondary &&
            (!isMpv || appliedReservationKey == reservationKey) &&
            (!appPrimaryPlanned || appPrimaryActive)
    val hasText = showPrimaryText || showSecondaryText
    val hasAss = libassEnabled && !libassFailed && assFrame != null
    if (!hasText && !hasAss) return

    Box(modifier = modifier.fillMaxSize()) {
        if (hasAss) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val rect = videoRect
                assFrame?.images?.forEach { image ->
                    val bitmap = image.bitmap
                    paint.color = image.color
                    val left = (rect.left + image.x).toFloat()
                    val top = rect.top + image.y - layout.primaryLiftPx
                    drawIntoCanvas { canvas ->
                        canvas.nativeCanvas.drawBitmap(
                            bitmap,
                            null,
                            RectF(left, top, left + bitmap.width, top + bitmap.height),
                            paint,
                        )
                    }
                }
            }
        }

        if (hasText) {
            val rect = videoRect
            val boxModifier =
                Modifier.offset { IntOffset(rect.left, rect.top) }
                    .size(
                        width = with(density) { rect.width().toDp() },
                        height = with(density) { rect.height().toDp() },
                    )
            Box(modifier = boxModifier, contentAlignment = Alignment.BottomCenter) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier =
                        Modifier.fillMaxWidth()
                            .padding(
                                start = 24.dp,
                                end = 24.dp,
                                bottom =
                                    with(density) {
                                        (videoHeightPx * appearanceLayout.bottomFraction).toDp()
                                    },
                            ),
                ) {
                    if (showPrimaryText) {
                        SubtitleLine(
                            text = primaryText,
                            style = primaryStyle,
                            fontSize = primarySize,
                            // 两条 Compose 行相邻时，块内透明留白已贡献一部分视觉间距，这里把差额压回透明区。
                            modifier =
                                Modifier.offset {
                                    IntOffset(0, layout.primaryOffsetPx.roundToInt())
                                },
                        )
                    }
                    if (showSecondaryText) {
                        SubtitleLine(
                            text = secondaryText,
                            style = secondaryStyle,
                            fontSize = secondaryBaseSize * layout.secondaryScale,
                        )
                    }
                }
            }
        }
    }
}

/** 当前时刻应显示的字幕文本（同一时刻多条 cue 按顺序叠成多行） */
private fun visibleText(
    cues: List<SubtitleCue>,
    positionMs: Long,
    delayMs: Long,
): String =
    cues.filter { it.isVisibleAt(positionMs, delayMs) }.joinToString(separator = "\n") { it.text }

/** libass 本帧所有图元的实际包围盒（相对视频显示区），用于精确计算主字幕需要上移多少。 */
private fun LibassFrame.subtitleExtent(): PrimarySubtitleExtent.Libass? {
    val frameImages = images.takeIf { it.isNotEmpty() } ?: return null
    return PrimarySubtitleExtent.Libass(
        topPx = frameImages.minOf { it.y }.toFloat(),
        bottomPx = frameImages.maxOf { it.y + it.bitmap.height }.toFloat(),
    )
}

/** 用 TextMeasurer 先算出与 SubtitleLine 同口径的文本块高度（含多行、描边安全边距和内边距）。 */
private fun subtitleTextBlockHeightPx(
    measurer: TextMeasurer,
    text: String,
    fontSize: TextUnit,
    maxWidthPx: Int,
    verticalPaddingPx: Float,
): Float {
    if (text.isEmpty()) return 0f
    val measured =
        measurer.measure(
            text = AnnotatedString(text),
            style =
                TextStyle(
                    fontSize = fontSize,
                    lineHeight = fontSize * 1.25f,
                    textAlign = TextAlign.Center,
                ),
            constraints = Constraints(maxWidth = maxWidthPx.coerceAtLeast(1)),
        )
    return secondaryBlockHeightPx(
        textLayoutHeightPx = measured.size.height.toFloat(),
        verticalPaddingPx = verticalPaddingPx,
    )
}

/**
 * 一行字幕：先描边（黑）再填色，做出「任何画面都读得清」的字幕效果。
 *
 * Compose 的 Text 没有原生描边参数，标准做法是用同一个文本绘制两次： 第一次用 [Stroke] 描边，第二次用填充色盖在上面。
 */
@Composable
private fun SubtitleLine(
    text: String,
    style: SubtitleStyle,
    fontSize: androidx.compose.ui.unit.TextUnit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val fillColor = Color(style.textColor)
    val backgroundColor = Color(style.backgroundArgb)
    val strokeWidthPx = with(density) { style.edgeWidthDp.dp.toPx() }
    val textStyle =
        TextStyle(
            fontSize = fontSize,
            lineHeight = fontSize * 1.25f,
            textAlign = TextAlign.Center,
        )

    Box(
        modifier =
            modifier
                .padding(vertical = 3.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(backgroundColor)
                .padding(horizontal = 10.dp, vertical = 4.dp)
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
            )
        }
        Text(
            text = text,
            style = textStyle.copy(color = fillColor),
            textAlign = TextAlign.Center,
        )
    }
}
