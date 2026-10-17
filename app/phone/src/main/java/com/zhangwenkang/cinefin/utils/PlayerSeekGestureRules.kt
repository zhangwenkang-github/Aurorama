package com.zhangwenkang.cinefin.utils

import androidx.media3.common.Player

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

/** 横向滑动判定：沿用现有「|dy| < 2|dx|」阈值，零位移不算横向。 */
internal fun isHorizontalScroll(distanceX: Float, distanceY: Float): Boolean =
    distanceX != 0f && kotlin.math.abs(distanceY) < kotlin.math.abs(distanceX) * 2f

/** 纵向滑动判定：沿用现有「|dy| >= 2|dx|」阈值，零位移不算纵向。 */
internal fun isVerticalScroll(distanceX: Float, distanceY: Float): Boolean =
    distanceY != 0f && kotlin.math.abs(distanceY) >= kotlin.math.abs(distanceX) * 2f

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
