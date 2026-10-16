package com.zhangwenkang.cinefin.book.presentation.reader

import android.os.SystemClock
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import kotlin.math.abs
import timber.log.Timber

/**
 * W80（#32）阅读器手势的**纯规则层**（可单测，不含 Compose / Readium 依赖）。
 *
 * 三份格式共用同一套判定：
 * - 双击：屏幕三等分 —— 左 = 上一页、中 = 适宽切换、右 = 下一页；
 * - 纵向拖动：亮度（分页 / 双栏全屏可调；滚动档只在左侧窄带，避免与纵向滚动抢手势）。
 */

/** 双击命中目标。 */
internal enum class ReaderTapTarget {
    Previous,
    ToggleFit,
    Next,
}

/** 左右双击区占屏宽比例（中间 = 1 − 2×本值）。 */
internal const val READER_TAP_EDGE_RATIO: Float = 1f / 3f

/** 双击分界容差：正好落在 1/3、2/3 上时算「中间（适宽）」，避免浮点误差把分界线判成翻页。 */
private const val READER_TAP_EDGE_EPSILON: Float = 1e-4f

/** 亮度可用区间（与播放器一致：不放到全黑，避免"看起来像黑屏"）。 */
internal val ReaderBrightnessRange: ClosedFloatingPointRange<Float> = 0.05f..1f

/** 滚动档：亮度手势只认屏幕左侧这一比例内的纵向拖动（其余留给滚动）。 */
internal const val READER_BRIGHTNESS_ZONE_RATIO: Float = 0.25f

/** 双击位置 → 目标（宽度非法时退化为中间「适宽」）。 */
internal fun readerTapTarget(x: Float, width: Float): ReaderTapTarget {
    if (!width.isFinite() || width <= 0f || !x.isFinite()) return ReaderTapTarget.ToggleFit
    val ratio = (x / width).coerceIn(0f, 1f)
    return when {
        ratio < READER_TAP_EDGE_RATIO - READER_TAP_EDGE_EPSILON -> ReaderTapTarget.Previous
        ratio > 1f - READER_TAP_EDGE_RATIO + READER_TAP_EDGE_EPSILON -> ReaderTapTarget.Next
        else -> ReaderTapTarget.ToggleFit
    }
}

/** 纵向拖动 → 新亮度。`dyRatio` = 拖动位移 / 屏高（向下为正）：向上拖变亮（与播放器同向）， 一次满屏拖动 = 全量程。 */
internal fun readerBrightnessAfterDrag(
    start: Float,
    dyRatio: Float,
): Float {
    if (!dyRatio.isFinite()) return start.coerceIn(ReaderBrightnessRange)
    val base = if (start.isFinite()) start else 1f
    return (base - dyRatio).coerceIn(ReaderBrightnessRange)
}

/** 亮度手势是否允许从 [startX] 起手：分页 / 双栏全屏；滚动档只认左侧窄带。 */
internal fun readerBrightnessDragAllowed(
    scrollMode: Boolean,
    startX: Float,
    width: Float,
): Boolean {
    if (!scrollMode) return true
    if (!width.isFinite() || width <= 0f || !startX.isFinite()) return false
    return startX <= width * READER_BRIGHTNESS_ZONE_RATIO
}

/**
 * 「适宽」倍数：位图按 Fit 渲染时，把页面宽度撑满容器还需要放大的倍数。
 *
 * 设页面宽高比 `a`（= 宽 / 高）、容器宽高比 `b`：`Fit` 后页面宽度 = 容器高度 × a × min(b/a, 1)， 因此 `a ≥ b`（横向页面）本来就满宽（倍数
 * 1）；`a < b`（竖向页面）倍数为 `b / a`。
 */
internal fun fitWidthScale(
    pageAspect: Float?,
    boxAspect: Float,
): Float {
    if (pageAspect == null || !pageAspect.isFinite() || pageAspect <= 0f) return 1f
    if (!boxAspect.isFinite() || boxAspect <= 0f) return 1f
    if (pageAspect >= boxAspect) return 1f
    return (boxAspect / pageAspect).coerceIn(1f, PAGE_MAX_ZOOM)
}

/**
 * EPUB「双栏」兜底判据：Readium 的 `--USER__colCount` 只在 `min-width:60em` 或 36–47em 横屏生效
 * （`ReadiumCSS-after.css` 媒体查询），平板竖屏会被静默丢成单栏。宽度达到平板级（≥ [EPUB_TWO_COLUMN_MIN_WIDTH_DP]） 时由 App 侧直接压
 * `--RS__colCount`，让双栏档真的两列。
 */
internal const val EPUB_TWO_COLUMN_MIN_WIDTH_DP: Int = 640

internal fun shouldForceEpubTwoColumns(widthDp: Int): Boolean =
    widthDp >= EPUB_TWO_COLUMN_MIN_WIDTH_DP

/** 判定「滚到资源末尾继续拖」的容差：资源内 progression 没变才认为是拖不动了。 */
private const val CHAIN_PROGRESSION_EPSILON = 0.002

/** 触发章节衔接所需的最小纵向位移（屏高比例）。 */
private const val CHAIN_MIN_DRAG_RATIO = 0.12f

/** 滚动档「章节间自然衔接」判据：同资源内拖完位置**没变**（说明已经到底 / 到顶）， 且位移足以判定方向 → 返回 +1（下一章）/ −1（上一章）/ 0（不衔接）。 */
internal fun readerScrollChainStep(
    beforeHref: String?,
    beforeProgression: Double,
    afterHref: String?,
    afterProgression: Double,
    dragDyPx: Float,
    viewportHeightPx: Int,
): Int {
    if (beforeHref == null || beforeHref != afterHref) return 0
    if (viewportHeightPx <= 0 || !dragDyPx.isFinite()) return 0
    if (!beforeProgression.isFinite() || !afterProgression.isFinite()) return 0
    if (abs(afterProgression - beforeProgression) > CHAIN_PROGRESSION_EPSILON) return 0
    val threshold = viewportHeightPx * CHAIN_MIN_DRAG_RATIO
    return when {
        dragDyPx <= -threshold -> 1
        dragDyPx >= threshold -> -1
        else -> 0
    }
}

/**
 * 阅读器手势层（PDF / CBZ / EPUB 共用同一套行为）：
 *
 * - **双击**：[onDoubleTap] 拿到落点（左 / 中 / 右由调用方用 [readerTapTarget] 判定）。
 * - **单指纵向拖动**：调亮度。滚动档只有左侧窄带（[readerBrightnessDragAllowed]）认，其余位移留给容器滚动； 分页 /
 *   双栏整屏可调。拖动态一旦成立就消费事件，避免同一次手势既调亮度又翻页 / 滚动。
 * - 双指缩放与子项点击（批注）不受影响：双指 / 已被子项消费的事件直接放过。
 */
@Composable
internal fun ReaderGestureArea(
    enabled: Boolean,
    scrollMode: Boolean,
    brightnessProvider: () -> Float,
    onBrightness: (Float) -> Unit,
    onDoubleTap: (x: Float, width: Float) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val currentScrollMode = rememberUpdatedState(scrollMode)
    val currentBrightnessProvider = rememberUpdatedState(brightnessProvider)
    val currentOnBrightness = rememberUpdatedState(onBrightness)
    val currentOnDoubleTap = rememberUpdatedState(onDoubleTap)
    var lastTapAt by remember { mutableLongStateOf(0L) }
    var lastTapPosition by remember { mutableStateOf(Offset.Zero) }

    Box(
        modifier =
            modifier.pointerInput(enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    // Initial 阶段：先于子项（LazyColumn 滚动 / Pager 翻页 / 页内缩放）拿到事件，
                    // 这样滚动档左侧窄带的亮度手势才不会被 LazyColumn 先吃掉。
                    val down =
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    val startPosition = down.position
                    val startWidth = size.width.toFloat()
                    val startHeight = size.height
                    val brightnessAllowed =
                        readerBrightnessDragAllowed(
                            scrollMode = currentScrollMode.value,
                            startX = startPosition.x,
                            width = startWidth,
                        )
                    var dragging = false
                    var moved = false
                    var brightnessStart: Float? = null
                    var lastBrightnessLogged = -1f
                    val touchSlop = viewConfiguration.touchSlop
                    val doubleTapTimeout = viewConfiguration.doubleTapTimeoutMillis
                    // 真机验收用的最小轨迹：手势层确实拿到事件 / 亮度拖动态确立（不逐帧刷屏）。
                    Timber.d(
                        "reader gesture down x=%.0f y=%.0f scroll=%b brightnessZone=%b slop=%.0f",
                        startPosition.x,
                        startPosition.y,
                        currentScrollMode.value,
                        brightnessAllowed,
                        touchSlop,
                    )
                    val downUptime = down.uptimeMillis

                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        // 只处理**单指**手势：第二根手指落下（双指缩放）时退出，不再消费，交回子项按 ZoomablePage 逻辑处理。
                        val change = event.changes.singleOrNull() ?: break
                        if (!change.pressed && change.id != down.id) break

                        if (change.pressed) {
                            if (!moved) {
                                val delta = change.position - startPosition
                                if (delta.getDistance() > touchSlop) {
                                    moved = true
                                    dragging =
                                        brightnessAllowed &&
                                            event.changes.count { it.pressed } == 1 &&
                                            abs(delta.y) > abs(delta.x) * 1.4f
                                    if (dragging) {
                                        Timber.d("reader gesture brightness drag start")
                                    }
                                }
                            }
                            if (dragging) {
                                val start =
                                    brightnessStart
                                        ?: currentBrightnessProvider.value().also {
                                            brightnessStart = it
                                        }
                                val next =
                                    readerBrightnessAfterDrag(
                                        start = start,
                                        dyRatio =
                                            (change.position.y - startPosition.y) /
                                                startHeight.coerceAtLeast(1),
                                    )
                                if (next != lastBrightnessLogged) {
                                    lastBrightnessLogged = next
                                    Timber.d("reader gesture brightness %.2f", next)
                                }
                                currentOnBrightness.value(next)
                                if (change.positionChanged()) change.consume()
                            }
                            continue
                        }

                        // 抬手：未拖动 → 双击候选（单击不消费，留给子项）。
                        val upTime = SystemClock.uptimeMillis()
                        val isTap = !moved && (upTime - downUptime) <= touchSlopTapTimeoutMs
                        if (dragging) change.consume()
                        if (isTap) {
                            val isDouble =
                                lastTapAt > 0L &&
                                    (upTime - lastTapAt) <= doubleTapTimeout &&
                                    (startPosition - lastTapPosition).getDistance() <=
                                        touchSlop * 2f
                            if (isDouble) {
                                lastTapAt = 0L
                                currentOnDoubleTap.value(startPosition.x, startWidth)
                            } else {
                                lastTapAt = upTime
                                lastTapPosition = startPosition
                            }
                        }
                        break
                    }
                }
            }
    ) {
        content()
    }
}

/** 单击 / 双击判定用的最长按压时间（与系统 `DoubleTapTimeout` 同量级）。 */
private const val touchSlopTapTimeoutMs = 400L
