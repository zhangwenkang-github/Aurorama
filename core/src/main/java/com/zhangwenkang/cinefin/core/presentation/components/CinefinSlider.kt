package com.zhangwenkang.cinefin.core.presentation.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.LayoutDirection
import com.zhangwenkang.cinefin.core.presentation.theme.LocalCinefinColors
import kotlin.math.abs
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
     * 默认配色：轨道 = `progress-track` 白 12%（暗）/ 黑 12%（浅），已填充段 = M3 `primary`（2.0.1 定稿 §2 批②：批①后即极光，
     * 交互强调不再取域媒体色、也不随 dynamic 主题混色），拇指 = `onSurface` 圆点，外圈极轻同色柔光——与播放页自绘进度条同一视觉语言。
     */
    @Composable
    fun colors(): CinefinSliderColors {
        val colors = LocalCinefinColors.current
        val primary = MaterialTheme.colorScheme.primary
        return CinefinSliderColors(
            activeTrackColor = primary,
            inactiveTrackColor = colors.progressTrack,
            thumbColor = colors.onSurface,
            glowColor = primary.copy(alpha = CinefinProgressVisuals.GlowAlpha),
        )
    }
}

/**
 * Cinefin 统一滑杆（W44）：4dp 胶囊轨道 + 18dp 圆点拇指（替代 M3 默认的"竖条拇指"）。
 *
 * 交互与 M3 `Slider` 对齐：点按（按下到抬手的位移始终在触摸阈值内）落位到抬手位置、水平拖动实时回调、抬手（或取消）回调 [onValueChangeFinished]；
 * [steps] > 0 时按档位吸附（ReplayGain 覆盖用 0.5 dB 步进）；禁用态整体降到 40% 透明度但仍可辨； 支持 RTL（最小值从右往左）。
 *
 * W2-T12 防误触（需求 #13）：只有水平位移超过触摸阈值**且严格大于**垂直位移时才接管拖拽（[cinefinSliderShouldClaimDrag]）；垂直主导的
 * 手势不消费任何事件、不改任何值，直接放行给父级滚动容器——可滚动页面里手指落在滑杆上做竖向滚动不会再误改值。
 *
 * 触摸 / 数值换算逻辑抽成 [cinefinSliderValueAt] / [cinefinSliderFraction] / [cinefinSliderShouldClaimDrag]
 * 纯函数，便于单测。
 *
 * W49 补齐键盘步进（对齐 M3 `Slider`）：方向键按档位步进（无档位时按区间 1%）、Home / End 到端点、 PageUp / PageDown 跳 10%，RTL
 * 下左右方向对调；无障碍 `setProgress` 的目标值按档位就近吸附。
 *
 * 宽度与 M3 `Slider` 同口径：**填满可用宽度**（内部 `fillMaxWidth`），调用方用 `Modifier.widthIn(max = …)` 控制上限。W49
 * 真机发现：`Canvas` 只取最小约束，若照搬 M3 时代的 `weight(1f, fill = false)`，组件宽度会是 0 （阅读器三条滑杆不可见、不可触摸，W44 回归）。
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
    require(steps >= 0) { "steps should be >= 0" }
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    // 拖动过程中不希望因父层重组换 lambda 而重启 pointerInput（会中断当前手势）。
    val currentOnValueChange by rememberUpdatedState(onValueChange)
    val currentOnValueChangeFinished by rememberUpdatedState(onValueChangeFinished)
    val coercedValue = value.coerceIn(valueRange.start, valueRange.endInclusive)
    val currentValue by rememberUpdatedState(coercedValue)
    val alpha = if (enabled) 1f else CinefinProgressVisuals.DisabledAlpha

    Canvas(
        modifier =
            modifier
                .fillMaxWidth()
                .height(CinefinProgressVisuals.TouchHeight)
                .focusable(enabled)
                .onKeyEvent { event ->
                    if (!enabled) return@onKeyEvent false
                    when (event.type) {
                        KeyEventType.KeyDown -> {
                            val target =
                                cinefinSliderKeyTarget(
                                    key = event.key,
                                    value = currentValue,
                                    valueRange = valueRange,
                                    steps = steps,
                                    isRtl = isRtl,
                                )
                            if (target == null) {
                                false
                            } else {
                                currentOnValueChange(target)
                                true
                            }
                        }
                        KeyEventType.KeyUp ->
                            if (cinefinSliderIsStepKey(event.key)) {
                                currentOnValueChangeFinished?.invoke()
                                true
                            } else {
                                false
                            }
                        else -> false
                    }
                }
                .semantics {
                    progressBarRangeInfo = ProgressBarRangeInfo(coercedValue, valueRange, steps)
                    if (enabled) {
                        setProgress { target ->
                            currentOnValueChange(
                                cinefinSliderSnappedValue(target, valueRange, steps)
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
                    val thumbRadiusPx = CinefinProgressVisuals.ThumbRadius.toPx()
                    val touchSlop = viewConfiguration.touchSlop
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
                        // 按下不消费、不落位：先判断这是点按还是水平拖拽，垂直手势整段放行给父级滚动。
                        val down = awaitFirstDown()
                        val downX = down.position.x
                        val downY = down.position.y
                        var dragging = false
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            val horizontal = change.position.x - downX
                            val vertical = change.position.y - downY
                            if (!dragging) {
                                if (cinefinSliderShouldClaimDrag(horizontal, vertical, touchSlop)) {
                                    dragging = true
                                } else if (!change.pressed) {
                                    // 全程未越过阈值 = 点按：抬手落位（垂直滚动时永远走不到这里改值）。
                                    change.consume()
                                    currentOnValueChange(valueAt(change.position.x))
                                    currentOnValueChangeFinished?.invoke()
                                    break
                                } else if (
                                    abs(horizontal) > touchSlop || abs(vertical) > touchSlop
                                ) {
                                    // 越过阈值但垂直（或斜向）主导：不消费、不改值，交给父级滚动容器。
                                    break
                                } else {
                                    continue
                                }
                            }
                            change.consume()
                            currentOnValueChange(valueAt(change.position.x))
                            if (!change.pressed) {
                                currentOnValueChangeFinished?.invoke()
                                break
                            }
                        }
                    }
                }
    ) {
        val thumbRadius = CinefinProgressVisuals.ThumbRadius.toPx()
        val trackHeight = CinefinProgressVisuals.TrackHeight.toPx()
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
                    radius = thumbRadius * CinefinProgressVisuals.GlowScale,
                ),
            radius = thumbRadius * CinefinProgressVisuals.GlowScale,
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

/**
 * 滑杆是否接管本次拖拽（W2-T12 防误触）：水平位移超过触摸阈值，且**严格大于**垂直位移。
 *
 * 判据用位移绝对值：`|dx| > touchSlop` 且 `|dx| > |dy|`。
 * - 阈值以内：不接管（留给点按与父级滚动判定）；
 * - 45° 斜向（`|dx| == |dy|`）与垂直略大：**不接管**——垂直滚动容器优先，宁可少响应滑杆也不误改值；
 * - 阈值取自 [androidx.compose.ui.platform.ViewConfiguration.touchSlop]（设备无关的触摸判定阈值）。
 */
fun cinefinSliderShouldClaimDrag(
    horizontalPx: Float,
    verticalPx: Float,
    touchSlopPx: Float,
): Boolean = abs(horizontalPx) > touchSlopPx && abs(horizontalPx) > abs(verticalPx)

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

/**
 * 目标值按档位就近吸附（`steps <= 0` 时只做夹紧）——无障碍 `setProgress` 与键盘步进共用。
 *
 * 与 M3 `Slider` 的 `setProgress` 同一口径：目标落在两个档位之间时取最近的档位值。
 */
fun cinefinSliderSnappedValue(
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
): Float {
    val span = range.endInclusive - range.start
    if (span <= 0f) return range.start
    val clamped = value.coerceIn(range.start, range.endInclusive)
    return range.start + snapSliderRatio(cinefinSliderFraction(clamped, range), steps) * span
}

/** 键盘 / 无障碍的单步增量（M3 同口径）：有档位时 = 区间长度 / (steps + 1)； 无档位（连续滑杆）键盘按区间 1% 调整——连续拖动仍不受限。 */
fun cinefinSliderStepSize(range: ClosedFloatingPointRange<Float>, steps: Int): Float {
    val span = range.endInclusive - range.start
    if (span <= 0f) return 0f
    return span / if (steps > 0) (steps + 1).toFloat() else 100f
}

/** 方向键单步：先吸附到档位网格再 ± 一个步长，端点夹紧（`steps > 0` 时正好落到相邻档位）。 */
fun cinefinSliderSteppedValue(
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    increase: Boolean,
): Float = cinefinSliderPageSteps(value, range, steps, increase, pages = 1)

/** PageUp / PageDown：一次跳 `clamp((steps + 1) / 10, 1, 10)` 个步长（无档位 = 10% 区间）。 */
fun cinefinSliderPagedValue(
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    increase: Boolean,
): Float {
    val intervals = if (steps > 0) steps + 1 else 100
    val pages = (intervals / 10).coerceIn(1, 10)
    return cinefinSliderPageSteps(value, range, steps, increase, pages)
}

private fun cinefinSliderPageSteps(
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    increase: Boolean,
    pages: Int,
): Float {
    val span = range.endInclusive - range.start
    if (span <= 0f) return range.start
    val base = cinefinSliderSnappedValue(value, range, steps)
    val delta = pages * cinefinSliderStepSize(range, steps)
    val target = if (increase) base + delta else base - delta
    return target.coerceIn(range.start, range.endInclusive)
}

/** 方向键 / Home / End / PageUp / PageDown → 目标值；返回 null = 该键不参与步进。 */
private fun cinefinSliderKeyTarget(
    key: Key,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    isRtl: Boolean,
): Float? =
    when (key) {
        // RTL 下左右方向对调（视觉左 = 数值增大），与 M3 的 reverseDirection 一致。
        Key.DirectionLeft -> cinefinSliderSteppedValue(value, valueRange, steps, increase = isRtl)
        Key.DirectionRight -> cinefinSliderSteppedValue(value, valueRange, steps, increase = !isRtl)
        Key.DirectionUp -> cinefinSliderSteppedValue(value, valueRange, steps, increase = true)
        Key.DirectionDown -> cinefinSliderSteppedValue(value, valueRange, steps, increase = false)
        Key.MoveHome -> valueRange.start
        Key.MoveEnd -> valueRange.endInclusive
        Key.PageUp -> cinefinSliderPagedValue(value, valueRange, steps, increase = false)
        Key.PageDown -> cinefinSliderPagedValue(value, valueRange, steps, increase = true)
        else -> null
    }

/** KeyUp 时需要回调 `onValueChangeFinished` 的键（与 [cinefinSliderKeyTarget] 的键集合一致）。 */
private fun cinefinSliderIsStepKey(key: Key): Boolean =
    key == Key.DirectionLeft ||
        key == Key.DirectionRight ||
        key == Key.DirectionUp ||
        key == Key.DirectionDown ||
        key == Key.MoveHome ||
        key == Key.MoveEnd ||
        key == Key.PageUp ||
        key == Key.PageDown
