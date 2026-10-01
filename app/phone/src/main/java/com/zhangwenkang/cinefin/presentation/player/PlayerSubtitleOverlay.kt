package com.zhangwenkang.cinefin.presentation.player

import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import com.zhangwenkang.cinefin.player.core.domain.models.SubtitleStyle
import com.zhangwenkang.cinefin.player.local.subtitle.AssSubtitleScript
import com.zhangwenkang.cinefin.player.local.subtitle.LibassFrame
import com.zhangwenkang.cinefin.player.local.subtitle.LibassSubtitleRenderer
import com.zhangwenkang.cinefin.player.local.subtitle.SubtitleCue
import com.zhangwenkang.cinefin.player.local.subtitle.SubtitleOverlayState
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
    val paint = remember { Paint() }

    var primaryText by remember { mutableStateOf("") }
    var secondaryText by remember { mutableStateOf("") }
    var assFrame by remember { mutableStateOf<LibassFrame?>(null) }
    var libassFailed by remember { mutableStateOf(false) }
    var videoRect by remember { mutableStateOf(Rect()) }

    val libassEnabled =
        state.primaryManaged && (state.primaryAssScript != null || state.primaryCues.isNotEmpty())

    DisposableEffect(renderer) { onDispose { renderer.release() } }

    LaunchedEffect(
        player,
        libassEnabled,
        state.primaryAssScript,
        state.primaryCues,
        state.secondaryCues,
        state.secondaryManaged,
        state.delayMs,
        state.style,
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
                if (!libassEnabled || libassFailed) {
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
            val textScale = state.style.textScale
            val key =
                "$frameWidth x $frameHeight | $storageWidth x $storageHeight | $textScale | " +
                    "${state.primaryAssScript?.length ?: -1}:${state.primaryCues.size}"
            if (key != loadedKey) {
                val script =
                    state.primaryAssScript
                        ?: AssSubtitleScript.forCues(
                            cues = state.primaryCues,
                            style = state.style,
                            playResX = storageWidth,
                            playResY = storageHeight,
                            frameHeightPx = frameHeight,
                            density = density.density,
                        )
                val ok =
                    withContext(Dispatchers.Default) {
                        renderer.load(
                            script = script,
                            fontScale = textScale,
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
            if (frame != null && (frame.changed || assFrame == null)) {
                assFrame = frame
            }
        }
    }

    val hasText = primaryText.isNotEmpty() || secondaryText.isNotEmpty()
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
                    val top = (rect.top + image.y).toFloat()
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
            val rectUsable = rect.width() >= 2 && rect.height() >= 2
            val boxModifier =
                if (rectUsable) {
                    Modifier.offset { IntOffset(rect.left, rect.top) }
                        .size(
                            width = with(density) { rect.width().toDp() },
                            height = with(density) { rect.height().toDp() },
                        )
                } else {
                    Modifier.fillMaxSize()
                }
            BoxWithConstraints(modifier = boxModifier, contentAlignment = Alignment.BottomCenter) {
                val style = state.style
                val baseSize = maxHeight * AssSubtitleScript.BASE_FONT_FRACTION
                val primarySize = with(density) { (baseSize * style.textScale).toSp() }
                val secondarySize = primarySize * 0.9f

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier =
                        Modifier.padding(
                            start = 24.dp,
                            end = 24.dp,
                            bottom = maxHeight * style.bottomFraction,
                        ),
                ) {
                    // 次字幕在主字幕上方，互不重叠
                    if (secondaryText.isNotEmpty()) {
                        SubtitleLine(
                            text = secondaryText,
                            style = style,
                            fontSize = secondarySize,
                        )
                    }
                    if (primaryText.isNotEmpty()) {
                        SubtitleLine(
                            text = primaryText,
                            style = style,
                            fontSize = primarySize,
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
) {
    val density = LocalDensity.current
    val fillColor = Color(style.textColor)
    val backgroundColor = Color(style.backgroundColor)
    val strokeWidthPx = with(density) { style.edgeWidthDp.dp.toPx() }
    val textStyle =
        TextStyle(
            fontSize = fontSize,
            lineHeight = fontSize * 1.25f,
            textAlign = TextAlign.Center,
        )

    Box(
        modifier =
            Modifier.padding(vertical = 3.dp)
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
