package com.zhangwenkang.cinefin.utils

import androidx.media3.common.Player
import kotlin.math.abs
import kotlin.math.hypot

/**
 * W67c / W81：播放页手势的识别、分区与 seek 落点规则（纯函数）。
 *
 * 背景（真机取证）：加载中（媒体尚未 prepared）时 `player.currentPosition` 仍按 0 计算、`duration` 未知；旧实现 把「0 + 15s」当落点直接
 * seekTo，随后播放器自己的恢复位置 seek 会把它覆盖（双击被吞）；横向滑动更会在 `duration = 0` 时被 `coerceIn(0, 0)` 夹成 0。
 *
 * 规则：**未就绪的 seek 不丢弃**——按「相对增量」排队（[accumulatePendingSeek]，±6 小时饱和），就绪后以 「就绪时的真实位置 +
 * 累计增量」一次落点；已就绪则立即落点（时长未知时不做上界收敛，交给播放器自行夹取）。 触摸反馈（双击涟漪 / 滑动 HUD）由调用方照常展示，不受排队影响。
 *
 * W81（#27/#28）：几何判定一律使用**当前 PlayerView 的局部宽高**。构造期缓存系统显示尺寸会在竖屏 → 横屏后留下右侧死区；这里不接收、 也不读取任何全局
 * displayMetrics。
 */

/** 排队增量的饱和上限（±6 小时）：连点 / 长滑到极端也不会溢出。 */
internal const val GESTURE_PENDING_SEEK_MAX_ABS_MS = 6L * 60L * 60L * 1000L

/** 双击 / 长按的 2:1:2 分区；[EDGE_OUTSIDE] 表示落点不在当前视图宽内。 */
internal enum class TapGestureZone {
    REWIND,
    TOGGLE,
    FORWARD,
    EDGE_OUTSIDE,
}

/** 竖滑落在画面左半还是右半。 */
internal enum class VerticalGestureSide {
    BRIGHTNESS,
    VOLUME,
}

/**
 * W4-T5（VIDEO-20）：双击连击窗口与倍率。
 *
 * 基准步长取设置值；窗口内第 2 次双击 ×2、第 3 次起 ×3 封顶。换方向（快退↔快进）另起一串。
 */
internal const val TAP_COMBO_WINDOW_MS = 800L

/** 连击倍率封顶：第 3 次起恒为 ×3。 */
internal const val TAP_COMBO_MAX_MULTIPLIER = 3

/** 连击串状态。窗口超时 / 换方向 / 松手后清零由 [nextTapCombo] 与调用方共同保证。 */
internal data class TapComboState(
    /** 本串第几次双击（0 = 无串，下次从 ×1 起步） */
    val count: Int = 0,
    /** 本串累计位移（含符号），HUD 显示这个值 */
    val accumulatedMs: Long = 0L,
    /** 本串方向：+1 快进 / -1 快退 / 0 未定 */
    val direction: Int = 0,
    val lastTapAtMs: Long = Long.MIN_VALUE,
)

/** 一次双击的判定结果：本次提交的增量 + 本串累计（HUD 用）。 */
internal data class TapComboStep(
    val state: TapComboState,
    val deltaMs: Long,
    val accumulatedMs: Long,
)

/**
 * 双击连击递增：把一次双击换算成「本次相对增量」。
 *
 * @param baseStepMs 设置里的基准步长（取绝对值，方向由 [direction] 决定）
 * @param direction +1 快进 / -1 快退
 * @param nowMs `SystemClock.elapsedRealtime()`
 */
internal fun nextTapCombo(
    state: TapComboState,
    baseStepMs: Long,
    direction: Int,
    nowMs: Long,
    windowMs: Long = TAP_COMBO_WINDOW_MS,
): TapComboStep {
    val dir = if (direction >= 0) 1 else -1
    val elapsed = nowMs - state.lastTapAtMs
    val continues =
        state.count > 0 && state.direction == dir && elapsed >= 0L && elapsed <= windowMs
    val count = if (continues) state.count + 1 else 1
    val previous = if (continues) state.accumulatedMs else 0L
    val magnitude = abs(baseStepMs).coerceAtMost(Long.MAX_VALUE / TAP_COMBO_MAX_MULTIPLIER)
    val deltaMs = dir * magnitude * count.coerceAtMost(TAP_COMBO_MAX_MULTIPLIER)
    val accumulatedMs = previous + deltaMs
    return TapComboStep(
        state = TapComboState(count, accumulatedMs, dir, nowMs),
        deltaMs = deltaMs,
        accumulatedMs = accumulatedMs,
    )
}

/**
 * W4-T5（VIDEO-26）：方向锁阈值（vp）。
 *
 * 首次位移不足 10vp 不锁定、也不给任何手势反馈；达锁后按主轴定一次，本次手势不再换向。
 */
internal const val GESTURE_DIRECTION_LOCK_DP = 10f

/** 本次手势锁定到的主轴。 */
internal enum class GestureAxis {
    NONE,
    HORIZONTAL,
    VERTICAL,
}

/**
 * 方向锁仲裁：已锁定则原样返回（本次手势不换向）；未锁定看累计位移是否达 [thresholdPx]， 未达返回 [GestureAxis.NONE]（不反馈），达锁按主轴（|dx| ≥ |dy|
 * 为横向）定一次。
 *
 * 斜滑（夹角 30°–60°）不会在横向/纵向之间来回切换，因此不会出现 HUD 跳变。
 */
internal fun resolveGestureAxis(
    current: GestureAxis,
    dxPx: Float,
    dyPx: Float,
    thresholdPx: Float,
): GestureAxis {
    if (current != GestureAxis.NONE) return current
    val threshold = thresholdPx.coerceAtLeast(0f)
    if (hypot(dxPx, dyPx) < threshold) return GestureAxis.NONE
    return if (abs(dxPx) >= abs(dyPx)) GestureAxis.HORIZONTAL else GestureAxis.VERTICAL
}

/** W4-T5（VIDEO-24）：捏合缩放区间与「进入平移」阈值。 */
internal const val ZOOM_MIN_SCALE = 1f
internal const val ZOOM_MAX_SCALE = 3f

/** >1.05x 时单指拖动 = 平移；≤1.05x 恢复单指 seek / 亮度 / 音量语义。 */
internal const val ZOOM_PAN_THRESHOLD = 1.05f

/** 捏合缩放夹取到 1.0–3.0x（NaN 视为 1.0x，避免画面消失）。 */
internal fun clampZoomScale(scale: Float): Float =
    if (scale.isNaN()) ZOOM_MIN_SCALE else scale.coerceIn(ZOOM_MIN_SCALE, ZOOM_MAX_SCALE)

/** 当前缩放是否进入「单指拖动 = 平移」区间。 */
internal fun zoomPanEnabled(scale: Float): Boolean = scale > ZOOM_PAN_THRESHOLD

/**
 * 平移边界：缩放后内容边缘不得离开画面（scale = 1 时恒为 0）。
 *
 * @param viewSizePx 画面区宽/高（px）
 */
internal fun clampZoomTranslation(translationPx: Float, scale: Float, viewSizePx: Int): Float {
    val max = (clampZoomScale(scale) - ZOOM_MIN_SCALE) / 2f * viewSizePx.coerceAtLeast(0)
    return translationPx.coerceIn(-max, max)
}

/**
 * 锚点跟随：缩放比从 [previousScale] 变到 [newScale] 时，为让 [focalOffsetPx]（双指中心相对画面中心的偏移，父容器坐标）下的像素保持不动，
 * 平移量需要的新值。
 *
 * 推导：画面点 p 映射到 `center + t + s·(p − center)`；令焦点处内容点在缩放前后落在同一屏幕位置即得 `t₁ = f − (s₁/s₀)·(f − t₀)`。
 */
internal fun zoomTranslationAfterScale(
    translationPx: Float,
    focalOffsetPx: Float,
    previousScale: Float,
    newScale: Float,
): Float {
    if (previousScale <= 0f) return translationPx
    return focalOffsetPx - (newScale / previousScale) * (focalOffsetPx - translationPx)
}

/** W4-T5（VIDEO-23）：长按「越久越快」每 2s 升一档，基础档 → +1x → +2x，封顶 4x。 */
internal const val SPEED_RAMP_INTERVAL_MS = 2_000L
internal const val SPEED_RAMP_STEP = 1f
internal const val SPEED_RAMP_MAX = 4f

/** 长按已持续 [elapsedMs] 时应处的倍速；关闭开关时调用方直接用基础档，不走这里。 */
internal fun rampedPlaybackSpeed(baseSpeed: Float, elapsedMs: Long): Float {
    if (baseSpeed <= 0f) return baseSpeed
    val steps = (elapsedMs.coerceAtLeast(0L) / SPEED_RAMP_INTERVAL_MS).toInt()
    return (baseSpeed + steps * SPEED_RAMP_STEP).coerceAtMost(SPEED_RAMP_MAX)
}

/** 双击 / 长按分区：左 2/5 快退、中间 1/5 播放暂停、右 2/5 快进。 */
internal fun tapGestureZone(x: Float, viewWidth: Int): TapGestureZone {
    if (viewWidth <= 0 || x < 0f || x >= viewWidth.toFloat()) return TapGestureZone.EDGE_OUTSIDE
    val fifth = viewWidth / 5f
    return when {
        x < fifth * 2f -> TapGestureZone.REWIND
        x < fifth * 3f -> TapGestureZone.TOGGLE
        else -> TapGestureZone.FORWARD
    }
}

/**
 * 手势起点是否落在当前窗口的真实系统边缘 insets 内。
 *
 * 坐标与尺寸必须同系（PlayerView 局部坐标）；不读取物理屏宽 / 屏高。边界本身放行，越界才拒绝；非法尺寸时不拦截。
 */
internal fun gestureStartExcluded(
    x: Float,
    y: Float,
    viewWidth: Int,
    viewHeight: Int,
    insetLeft: Int,
    insetTop: Int,
    insetRight: Int,
    insetBottom: Int,
): Boolean {
    if (viewWidth <= 0 || viewHeight <= 0) return false
    val left = insetLeft.coerceIn(0, viewWidth)
    val top = insetTop.coerceIn(0, viewHeight)
    val right = insetRight.coerceIn(0, viewWidth)
    val bottom = insetBottom.coerceIn(0, viewHeight)
    return x < left || x > (viewWidth - right) || y < top || y > (viewHeight - bottom)
}

/** 竖滑以画面中线分左右：左亮度 / 右音量；中线归左（与原 `x > measuredWidth / 2` 一致）。 */
internal fun verticalGestureSide(x: Float, viewWidth: Int): VerticalGestureSide =
    if (x > viewWidth / 2f) VerticalGestureSide.VOLUME else VerticalGestureSide.BRIGHTNESS

/**
 * 播放器是否已「可立即落点」（位置可信）。
 *
 * `durationMs >= 0` 用来区分未知时长（Media3 的 `C.TIME_UNSET` 是负数）；`playbackState != STATE_IDLE` 排除「条目已设置但还没
 * prepare」的窗口——这段窗口里的 `currentPosition` 还是 0，落点会错。
 *
 * 真机补充：prepare 刚完成的瞬间「时长已知、但续播位还没生效」时 `currentPosition` 仍是 0（真机实测双击落到了 0+15s、丢了恢复位置）， 因此要求
 * `currentPosition > 0` 或已 `STATE_READY` 才算就绪：从头播（续播位 = 0）会等到 READY 再落点；缓冲中（位置 > 0）仍可立即 seek。
 */
internal fun isGestureSeekReady(
    playerAttached: Boolean,
    durationMs: Long,
    playbackState: Int,
    currentPositionMs: Long,
): Boolean =
    playerAttached &&
        durationMs >= 0L &&
        playbackState != Player.STATE_IDLE &&
        (currentPositionMs > 0L || playbackState == Player.STATE_READY)

/**
 * 相对 seek 的落点 = 基准位置 + 增量。
 *
 * 时长已知（> 0）时收敛到 `[0, 时长]`；时长未知时不设上界（交给播放器按真实时长夹取），下界恒为 0。 极端值用饱和加法，避免溢出。
 */
internal fun gestureSeekTarget(basePositionMs: Long, deltaMs: Long, durationMs: Long): Long {
    val target = saturatingAdd(basePositionMs, deltaMs)
    return target.coerceIn(0L, if (durationMs > 0L) durationMs else Long.MAX_VALUE)
}

/** 排队增量的累加：饱和加法 + ±[maxAbsMs] 截断。 */
internal fun accumulatePendingSeek(
    pendingMs: Long,
    deltaMs: Long,
    maxAbsMs: Long = GESTURE_PENDING_SEEK_MAX_ABS_MS,
): Long = saturatingAdd(pendingMs, deltaMs).coerceIn(-maxAbsMs, maxAbsMs)

/** 一次 seek 请求的判定结果。 */
internal data class GestureSeekDecision(
    /** true = 立即落点（[targetMs]）；false = 排队（[pendingMs] 累计），[targetMs] 仅供 HUD / 反馈显示。 */
    val applyNow: Boolean,
    val targetMs: Long,
    val pendingMs: Long,
)

/** 手势 seek 的统一判定：就绪 → 立即落点（并把之前排队的增量一并补上）；未就绪 → 只累计增量，不丢弃。 */
internal fun decideGestureSeek(
    basePositionMs: Long,
    durationMs: Long,
    deltaMs: Long,
    pendingMs: Long,
    playerReady: Boolean,
): GestureSeekDecision {
    val totalDelta = accumulatePendingSeek(pendingMs, deltaMs)
    val target = gestureSeekTarget(basePositionMs, totalDelta, durationMs)
    return if (playerReady) {
        GestureSeekDecision(applyNow = true, targetMs = target, pendingMs = 0L)
    } else {
        GestureSeekDecision(applyNow = false, targetMs = target, pendingMs = totalDelta)
    }
}

private fun saturatingAdd(a: Long, b: Long): Long =
    when {
        b > 0L && a > Long.MAX_VALUE - b -> Long.MAX_VALUE
        b < 0L && a < Long.MIN_VALUE - b -> Long.MIN_VALUE
        else -> a + b
    }
