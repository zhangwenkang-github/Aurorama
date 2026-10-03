package com.zhangwenkang.cinefin.core.presentation.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.zhangwenkang.cinefin.core.presentation.theme.LocalCinefinColors
import com.zhangwenkang.cinefin.core.presentation.theme.LocalMediaColors
import kotlin.math.roundToInt

/**
 * 滑杆配色：默认全部来自现有语义 token，特殊底色面板（阅读器纸色）可显式覆盖。
 *
 * @param glowColor 拇指柔光基色，只做低透明度光晕；传 `Color.Transparent` 可关闭发光。
 */
@Immutable
data class CinefinSliderColors(
    val activeTrackColor: Color,
    val inactiveTrackColor: Color,
    val thumbColor: Color,
    val glowColor: Color = activeTrackColor.copy(alpha = 0.45f),
)

object CinefinSliderDefaults {

    /**
     * 默认配色：轨道 = `progress-track` 白 12%（暗）/ 黑 12%（浅），已填充段 = 当前域媒体色，拇指 = `onSurface`
     * 圆点，外圈极轻同色柔光——与播放页自绘进度条（W23 D38）同一视觉语言。
     */
    @Composable
    fun colors(): CinefinSliderColors {
        val colors = LocalCinefinColors.current
        val media = LocalMediaColors.current
        return CinefinSliderColors(
            activeTrackColor = media.base,
            inactiveTrackColor = colors.progressTrack,
            thumbColor = colors.onSurface,
            glowColor = media.base.copy(alpha = GlowAlpha),
        )
    }
}

/**
 * Cinefin 统一滑杆（W44）：4dp 胶囊轨道 + 18dp 圆点拇指（替代 M3 默认的"竖条拇指"）。
 *
 * 交互与 M3 `Slider` 对齐：按下即跳到该位置、拖动实时回调、抬手（或取消）回调 [onValueChangeFinished]； [steps] > 0
 * 时按档位吸附（ReplayGain 覆盖用 0.5 dB 步进）；禁用态整体降到 40% 透明度但仍可辨； 支持 RTL（最小值从右往左）；整条 36dp
 * 触控带消费自己的手势，纵向滚动容器不会抢走滑动。
 *
 * 触摸 / 数值换算逻辑抽成 [cinefinSliderValueAt] / [cinefinSliderFraction] 纯函数，便于单测。
 */
@Composable
fun CinefinSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    onValueChangeFinished: (() -> Unit)? = null,
    colors: CinefinSliderColors = CinefinSliderDefaults.colors(),
) {
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    // 拖动过程中不希望因父层重组换 lambda 而重启 pointerInput（会中断当前手势）。
    val currentOnValueChange by rememberUpdatedState(onValueChange)
    val currentOnValueChangeFinished by rememberUpdatedState(onValueChangeFinished)
    val coercedValue = value.coerceIn(valueRange.start, valueRange.endInclusive)
    val alpha = if (enabled) 1f else DisabledAlpha

    Canvas(
        modifier =
            modifier
                .height(TouchHeight)
                .semantics {
                    progressBarRangeInfo = ProgressBarRangeInfo(coercedValue, valueRange, steps)
                    if (enabled) {
                        setProgress { target ->
                            currentOnValueChange(
                                target.coerceIn(valueRange.start, valueRange.endInclusive)
                            )
                            currentOnValueChangeFinished?.invoke()
                            true
                        }
                    } else {
                        disabled()
                    }
                }
                .pointerInput(enabled, valueRange, steps, isRtl) {
                    if (!enabled) return@pointerInput
                    val thumbRadiusPx = ThumbRadius.toPx()
                    fun valueAt(x: Float): Float {
                        val position = if (isRtl) size.width - x else x
                        return cinefinSliderValueAt(
                            x = position,
                            widthPx = size.width.toFloat(),
                            thumbRadiusPx = thumbRadiusPx,
                            range = valueRange,
                            steps = steps,
                        )
                    }
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        down.consume()
                        currentOnValueChange(valueAt(down.position.x))
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) {
                                change.consume()
                                currentOnValueChangeFinished?.invoke()
                                break
                            }
                            currentOnValueChange(valueAt(change.position.x))
                            change.consume()
                        }
                    }
                }
    ) {
        val thumbRadius = ThumbRadius.toPx()
        val trackHeight = TrackHeight.toPx()
        val startX = thumbRadius
        val endX = size.width - thumbRadius
        if (endX <= startX) return@Canvas
        val centerY = size.height / 2f
        val fraction = cinefinSliderFraction(coercedValue, valueRange)
        val thumbX =
            if (isRtl) {
                size.width - (startX + (endX - startX) * fraction)
            } else {
                startX + (endX - startX) * fraction
            }
        val trackStartX = if (isRtl) endX else startX
        val trackEndX = if (isRtl) startX else endX
        // 极轻柔光：与播放页进度条同款 radial glow（同色、低透明度）。
        drawCircle(
            brush =
                Brush.radialGradient(
                    colors =
                        listOf(
                            colors.glowColor.copy(alpha = colors.glowColor.alpha * alpha),
                            Color.Transparent,
                        ),
                    center = Offset(thumbX, centerY),
                    radius = thumbRadius * GlowScale,
                ),
            radius = thumbRadius * GlowScale,
            center = Offset(thumbX, centerY),
        )
        drawLine(
            color = colors.inactiveTrackColor.copy(alpha = colors.inactiveTrackColor.alpha * alpha),
            start = Offset(trackStartX, centerY),
            end = Offset(trackEndX, centerY),
            strokeWidth = trackHeight,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = colors.activeTrackColor.copy(alpha = colors.activeTrackColor.alpha * alpha),
            start = Offset(trackStartX, centerY),
            end = Offset(thumbX, centerY),
            strokeWidth = trackHeight,
            cap = StrokeCap.Round,
        )
        drawCircle(
            color = colors.thumbColor.copy(alpha = colors.thumbColor.alpha * alpha),
            radius = thumbRadius,
            center = Offset(thumbX, centerY),
        )
    }
}

/** 滑杆值 → 0..1 比例（超界夹紧，退化区间返回 0）。 */
fun cinefinSliderFraction(value: Float, range: ClosedFloatingPointRange<Float>): Float {
    val span = range.endInclusive - range.start
    if (span <= 0f) return 0f
    return ((value - range.start) / span).coerceIn(0f, 1f)
}

/**
 * 触控像素位置 → 滑杆值：拇指圆心可达区间 = `[radius, width - radius]`；[steps] > 0 时吸附到档位。
 *
 * 位置超界夹紧到区间端点；宽度不足（`width - 2 * radius <= 0`）时返回区间起点。
 */
fun cinefinSliderValueAt(
    x: Float,
    widthPx: Float,
    thumbRadiusPx: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
): Float {
    val span = range.endInclusive - range.start
    if (span <= 0f) return range.start
    val startX = thumbRadiusPx
    val endX = widthPx - thumbRadiusPx
    if (endX <= startX) return range.start
    val ratio = ((x - startX) / (endX - startX)).coerceIn(0f, 1f)
    return range.start + snapSliderRatio(ratio, steps) * span
}

private fun snapSliderRatio(ratio: Float, steps: Int): Float {
    if (steps <= 0) return ratio
    val intervals = (steps + 1).toFloat()
    return ((ratio * intervals).roundToInt() / intervals).coerceIn(0f, 1f)
}

private val TouchHeight = 36.dp
private val TrackHeight = 4.dp
private val ThumbRadius = 9.dp
private const val GlowScale = 2.6f
private const val GlowAlpha = 0.45f
private const val DisabledAlpha = 0.4f
