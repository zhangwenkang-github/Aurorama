package com.zhangwenkang.cinefin.book.presentation.reader

import kotlin.math.abs
import kotlin.math.hypot

/**
 * 翻页动画的**纯规则层**（W6 T3 / READER-08，定稿三 §2 + 定稿八动效清单）。
 *
 * 无 Compose / Android / Readium 依赖，可直接 JVM 单测：档位 → 渲染效果、方向镜像、松手阈值、
 * 中断接管，以及仿真卷页的多边形几何。视图层只按本文件的结果执行动画。
 *
 * 数值全部来自设计定稿，不自调：
 * - 覆盖：240ms `FastOutSlowIn` 平移盖入，旧页静止 + 8% 压暗；
 * - 仿真：拖动跟随（拖动距离驱动卷角），松手过 40% 阈值 260ms 完成、否则 180ms 弹回；
 * - 减少动态：覆盖 / 仿真降级为淡入淡出。
 */

/** 覆盖档盖入时长（定稿八：240ms FastOutSlowIn）。 */
internal const val PAGE_TURN_COVER_DURATION_MS: Int = 240

/** 仿真档过阈值后的完成时长（定稿八：260ms）。 */
internal const val PAGE_TURN_CURL_COMPLETE_DURATION_MS: Int = 260

/** 仿真档未过阈值时的弹回时长（定稿八：180ms）。 */
internal const val PAGE_TURN_CURL_CANCEL_DURATION_MS: Int = 180

/** 仿真档松手完成的进度阈值（定稿三 §2：40%）。 */
internal const val PAGE_TURN_CURL_COMMIT_THRESHOLD: Float = 0.4f

/** 覆盖档翻页过程中旧页的压暗比例（定稿八：旧页 8% 压暗）。 */
internal const val PAGE_TURN_OUTGOING_DIM_ALPHA: Float = 0.08f

/** 减少动态时的淡入淡出时长：沿用覆盖档 240ms，不新造时长。 */
internal const val PAGE_TURN_FADE_DURATION_MS: Int = PAGE_TURN_COVER_DURATION_MS

/** 视图层真正执行的效果（比设置档多一个减少动态降级出来的 [Fade]）。 */
internal enum class PageTurnEffect {
    /** 现有 `HorizontalPager` 滑动（默认）。 */
    Slide,
    /** 新页从边缘平移盖入 + 旧页 8% 压暗。 */
    Cover,
    /** 卷页角跟随拖动。 */
    Curl,
    /** 减少动态降级：纯淡入淡出。 */
    Fade,
}

/** 翻页方向的逻辑语义（与 RTL 无关；RTL 只镜像入场边与拖动方向）。 */
internal enum class PageTurnDirection {
    Forward,
    Backward,
}

/** 新页入场的水平侧（已按 RTL 镜像）。 */
internal enum class PageTurnEdge {
    Left,
    Right,
}

/** 松手判定：过阈值完成 / 未过阈值弹回。 */
internal enum class PageTurnRelease {
    Complete,
    Cancel,
}

/**
 * 设置档位 + 系统「减少动态效果」→ 实际渲染效果。
 *
 * 覆盖 / 仿真降级为淡入淡出；滑动本就是用户直接拖动的 `Pager`（不是自动播放的动画），保持不变； `None`（EPUB 专用）落在自研引擎上按滑动处理。
 */
internal fun pageTurnEffect(
    animation: ReaderPageAnimation,
    reduceMotion: Boolean,
): PageTurnEffect =
    when (animation) {
        ReaderPageAnimation.Slide,
        ReaderPageAnimation.None -> PageTurnEffect.Slide
        ReaderPageAnimation.Cover -> if (reduceMotion) PageTurnEffect.Fade else PageTurnEffect.Cover
        ReaderPageAnimation.Curl -> if (reduceMotion) PageTurnEffect.Fade else PageTurnEffect.Curl
    }

/**
 * EPUB 的翻页动画开关（Readium `go*`/`go(locator, animated)` 的 `animated` 参数）。
 *
 * EPUB 只有滑动 / 无动画两档；存的是 PDF/CBZ 的覆盖 / 仿真时回落为「有动画」（Readium 只支持滑动）。 系统减少动态时 Readium 无法淡入淡出，降级为无动画。
 */
internal fun epubPageTurnAnimated(
    animation: ReaderPageAnimation,
    reduceMotion: Boolean,
): Boolean {
    if (animation == ReaderPageAnimation.None) return false
    return !reduceMotion
}

/** 新页从哪一侧进入：LTR 前进 = 右、后退 = 左；RTL 镜像（定稿三 §2「RTL 下方向镜像」）。 */
internal fun pageTurnEntryEdge(forward: Boolean, rtl: Boolean): PageTurnEdge =
    when {
        forward && !rtl -> PageTurnEdge.Right
        !forward && rtl -> PageTurnEdge.Right
        else -> PageTurnEdge.Left
    }

/** 横向位移 → 翻页方向；位移不足或含非法值时返回 null（不翻页）。 */
internal fun pageTurnDragDirection(dx: Float, rtl: Boolean): PageTurnDirection? {
    if (!dx.isFinite() || dx == 0f) return null
    val forward = if (rtl) dx > 0f else dx < 0f
    return if (forward) PageTurnDirection.Forward else PageTurnDirection.Backward
}

/**
 * 拖动位移增量 → 进度增量（0..1 量纲，调用方先把 px 除以屏宽）。
 *
 * `dxFraction` = 手指水平位移 / 屏宽（左移为负）。前进方向的手势（LTR 左移 / RTL 右移）给正增量， 后退方向给负增量，所以往回拖可以把翻页退回去。
 */
internal fun pageTurnDragProgress(
    dxFraction: Float,
    direction: PageTurnDirection,
    rtl: Boolean,
): Float {
    if (!dxFraction.isFinite() || dxFraction == 0f) return 0f
    val forwardDelta = if (rtl) dxFraction else -dxFraction
    return if (direction == PageTurnDirection.Forward) forwardDelta else -forwardDelta
}

/** 松手后的动画时长：覆盖 240ms 双向；仿真完成 260ms / 弹回 180ms；淡入淡出 240ms。 */
internal fun pageTurnDurationMs(effect: PageTurnEffect, release: PageTurnRelease): Int =
    when (effect) {
        PageTurnEffect.Cover -> PAGE_TURN_COVER_DURATION_MS
        PageTurnEffect.Curl ->
            if (release == PageTurnRelease.Complete) {
                PAGE_TURN_CURL_COMPLETE_DURATION_MS
            } else {
                PAGE_TURN_CURL_CANCEL_DURATION_MS
            }
        PageTurnEffect.Fade -> PAGE_TURN_FADE_DURATION_MS
        PageTurnEffect.Slide -> 0
    }

/**
 * 仿真拖动状态机：拖动推进、阈值判定、中断接管（新手势可从进行中的进度续上）。
 *
 * 视图层每帧把 [progress] 画成卷角；松手用 [release] 决定完成还是弹回。
 */
internal class PageTurnDragState(
    private val commitThreshold: Float = PAGE_TURN_CURL_COMMIT_THRESHOLD
) {
    /** 当前转向；null = 没有进行中的拖动。 */
    var direction: PageTurnDirection? = null
        private set

    /** 已完成比例 0..1：0 = 仍停在本页，1 = 新页完全盖住。 */
    var progress: Float = 0f
        private set

    val active: Boolean
        get() = direction != null

    /** 起手或接管：方向取新判定，进度可从上一段动画的当前位置续上。 */
    fun begin(direction: PageTurnDirection, carryOverProgress: Float = 0f) {
        this.direction = direction
        progress = carryOverProgress.coerceIn(0f, 1f)
    }

    /** 推进进度（[deltaFraction] 已按 RTL / 方向归一，为正表示朝目标推进）。 */
    fun drag(deltaFraction: Float): Float {
        if (direction == null || !deltaFraction.isFinite()) return progress
        progress = (progress + deltaFraction).coerceIn(0f, 1f)
        return progress
    }

    /** 松手判定；没有进行中的拖动返回 null。 */
    fun release(): PageTurnRelease? {
        if (!active) return null
        return if (progress >= commitThreshold) {
            PageTurnRelease.Complete
        } else {
            PageTurnRelease.Cancel
        }
    }

    fun reset() {
        direction = null
        progress = 0f
    }
}

/**
 * 卷页角的水平侧：LTR 前进从右下角起卷、后退从左下角；RTL 镜像。
 *
 * 与 [pageTurnEntryEdge] 同一套镜像规则（新页从哪边进来，旧页就从那一边卷走）。
 */
internal fun curlCornerFromRight(forward: Boolean, rtl: Boolean): Boolean =
    pageTurnEntryEdge(forward = forward, rtl = rtl) == PageTurnEdge.Right

/** 卷页几何用的二维点（纯 Kotlin，便于 JVM 单测）。 */
internal data class PageTurnPoint(val x: Float, val y: Float)

/** 一次卷页的几何快照：剩下的页身、翻折过来的页角，以及折线与页面的两个交点。 */
internal data class CurlGeometry(
    val page: List<PageTurnPoint>,
    val flap: List<PageTurnPoint>,
    val foldStart: PageTurnPoint?,
    val foldEnd: PageTurnPoint?,
)

/**
 * 仿真卷页的多边形近似：从 [cornerFromRight] 指定的下角起卷，沿对角线朝对角推进。
 *
 * [progress] 0 = 整页平放（无折角），1 = 页身完全卷走。折角（页背面）是折线外侧区域的镜像，
 * 面积随拖动距离增大——对应用户看到的「卷角半径」。真曲面（含柱面光照）不在本卡范围，这里用三角折面 + 渐变近似。
 */
internal fun curlGeometry(
    width: Float,
    height: Float,
    progress: Float,
    cornerFromRight: Boolean,
): CurlGeometry {
    if (!width.isFinite() || !height.isFinite() || width <= 0f || height <= 0f) {
        return CurlGeometry(emptyList(), emptyList(), null, null)
    }
    val corner =
        if (cornerFromRight) {
            PageTurnPoint(width, height)
        } else {
            PageTurnPoint(0f, height)
        }
    val opposite =
        if (cornerFromRight) {
            PageTurnPoint(0f, 0f)
        } else {
            PageTurnPoint(width, 0f)
        }
    val diagonal = hypot(opposite.x - corner.x, opposite.y - corner.y)
    if (!diagonal.isFinite() || diagonal <= 0f) {
        return CurlGeometry(emptyList(), emptyList(), null, null)
    }
    val axisX = (opposite.x - corner.x) / diagonal
    val axisY = (opposite.y - corner.y) / diagonal
    fun along(point: PageTurnPoint): Float =
        (point.x - corner.x) * axisX + (point.y - corner.y) * axisY
    val fold = progress.coerceIn(0f, 1f) * diagonal
    val rect =
        listOf(
            PageTurnPoint(0f, 0f),
            PageTurnPoint(width, 0f),
            PageTurnPoint(width, height),
            PageTurnPoint(0f, height),
        )
    val page = clipAlong(rect, fold, keepAbove = true, along = ::along)
    val lifted = clipAlong(rect, fold, keepAbove = false, along = ::along)
    // 页角沿折线镜像：p' = p - 2·((p - corner)·axis - fold)·axis
    val flap = lifted.map { point ->
        val distance = along(point) - fold
        PageTurnPoint(point.x - 2f * distance * axisX, point.y - 2f * distance * axisY)
    }
    val crossings =
        rect.indices
            .mapNotNull { index ->
                foldCrossing(rect[index], rect[(index + 1) % rect.size], fold) { along(it) }
            }
            .distinct()
    return CurlGeometry(
        page = page,
        flap = flap,
        foldStart = crossings.getOrNull(0),
        foldEnd = crossings.getOrNull(1),
    )
}

/**
 * Sutherland–Hodgman：把凸多边形裁到 [along] ≥ / ≤ [threshold] 的半平面内，交点线性插值。
 *
 * 卷页的折线就是这条半平面边界；同一次裁剪分别保留外侧（页身）与里侧（待翻折区域）。
 */
private fun clipAlong(
    polygon: List<PageTurnPoint>,
    threshold: Float,
    keepAbove: Boolean,
    along: (PageTurnPoint) -> Float,
): List<PageTurnPoint> {
    if (polygon.isEmpty()) return emptyList()
    fun inside(point: PageTurnPoint): Boolean =
        if (keepAbove) along(point) >= threshold else along(point) <= threshold
    val output = mutableListOf<PageTurnPoint>()
    for (index in polygon.indices) {
        val current = polygon[index]
        val previous = polygon[(index + polygon.size - 1) % polygon.size]
        val currentInside = inside(current)
        val previousInside = inside(previous)
        when {
            currentInside && previousInside -> output += current
            currentInside && !previousInside -> {
                boundaryCrossing(previous, current, threshold, along)?.let(output::add)
                output += current
            }
            !currentInside && previousInside -> {
                boundaryCrossing(previous, current, threshold, along)?.let(output::add)
            }
        }
    }
    return output
}

/** 线段穿过半平面边界时的交点（按 [along] 线性插值；退化时返回 null）。 */
private fun boundaryCrossing(
    from: PageTurnPoint,
    to: PageTurnPoint,
    threshold: Float,
    along: (PageTurnPoint) -> Float,
): PageTurnPoint? {
    val fromDistance = along(from) - threshold
    val toDistance = along(to) - threshold
    val denominator = fromDistance - toDistance
    if (denominator == 0f || !denominator.isFinite()) return null
    val ratio = (fromDistance / denominator).coerceIn(0f, 1f)
    return PageTurnPoint(
        x = from.x + (to.x - from.x) * ratio,
        y = from.y + (to.y - from.y) * ratio,
    )
}

/** 折线与矩形边的交点；端点正好落在折线上时跳过（卷起 0 / 1 的退化态）。 */
private fun foldCrossing(
    from: PageTurnPoint,
    to: PageTurnPoint,
    fold: Float,
    along: (PageTurnPoint) -> Float,
): PageTurnPoint? {
    val fromDistance = along(from) - fold
    val toDistance = along(to) - fold
    if (fromDistance == 0f || toDistance == 0f) return null
    if (fromDistance > 0f == toDistance > 0f) return null
    val ratio = fromDistance / (fromDistance - toDistance)
    if (!ratio.isFinite()) return null
    return PageTurnPoint(
        x = from.x + (to.x - from.x) * ratio,
        y = from.y + (to.y - from.y) * ratio,
    )
}

/** 折角（页背面）的基础压暗比例：仿真卷页是近似渲染，这是观感参数而非设计档位数值。 */
internal const val PAGE_TURN_CURL_FLAP_SHADE: Float = 0.12f

/** 折角（页背面）的观感：越接近完全卷走，压暗越深（只用 0–1 的比例，不含颜色决策）。 */
internal fun curlFlapShade(progress: Float): Float {
    val p = if (progress.isFinite()) progress.coerceIn(0f, 1f) else 0f
    return PAGE_TURN_CURL_FLAP_SHADE + 0.10f * p
}

/** 拖动是否足以开始翻页：横向进度增量非零且没有到页首 / 页尾。 */
internal fun pageTurnCanTurn(
    slotIndex: Int,
    slotCount: Int,
    direction: PageTurnDirection,
): Boolean {
    if (slotCount <= 0) return false
    val target =
        when (direction) {
            PageTurnDirection.Forward -> slotIndex + 1
            PageTurnDirection.Backward -> slotIndex - 1
        }
    return target in 0 until slotCount
}

/** 进度是否已经足够「看起来翻过去了」（渲染时用于省略最后一帧的无效绘制）。 */
internal fun pageTurnFullyTurned(progress: Float): Boolean = abs(progress - 1f) < 1e-4f
