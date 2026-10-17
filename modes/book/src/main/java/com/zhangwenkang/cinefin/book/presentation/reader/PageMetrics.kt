package com.zhangwenkang.cinefin.book.presentation.reader

import androidx.compose.ui.geometry.Offset
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * 单页位图长边上限（ARCHITECTURE §3.4：按屏幕降采样，长边截断）。
 *
 * 取值参考 Pad 5（2560×1600）：A4 竖版渲染到 2048 px 长边 ≈ 屏幕高度的 1.4 倍， 既能覆盖 1×–1.4× 缩放，也不会把内存拉爆（ARGB_8888 单页 ≈
 * 12 MB）。
 */
const val PAGE_BITMAP_MAX_SIDE_PX: Int = 2048

/** 同时驻留内存的页面位图数量：当前页 ± 1（ARCHITECTURE §3.4 硬约束）。 */
const val PAGE_BITMAP_WINDOW: Int = 3

/** 位图缩放上限（EB-3「缩放基础」）。 */
const val PAGE_MAX_ZOOM: Float = 4f

/**
 * 全本缩放的按钮档位（READER_PLAN §9 2026-10-18）：25% 步进，200% 之后跨到 250% / 300% / 400%。
 *
 * 双指缩放可以落在任意连续值上，按钮从当前值出发命中「下一个 / 上一个」档位，保证每一击都精确落档。
 */
internal val PAGE_ZOOM_STEPS: List<Float> = listOf(1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f, 4f)

/** 浮点比较容差：双指缩放后的 1.9999 仍应算「已在 200% 档」。 */
private const val ZOOM_STEP_EPSILON = 1e-4f

/** 当前倍率 → 下一个按钮档位；已在 4× 时保持 4×。 */
internal fun nextPageZoomStep(scale: Float): Float {
    val current = if (scale.isFinite()) scale.coerceIn(1f, PAGE_MAX_ZOOM) else 1f
    return PAGE_ZOOM_STEPS.firstOrNull { it > current + ZOOM_STEP_EPSILON } ?: PAGE_MAX_ZOOM
}

/** 当前倍率 → 上一个按钮档位；已在 1× 时保持 1×。 */
internal fun previousPageZoomStep(scale: Float): Float {
    val current = if (scale.isFinite()) scale.coerceIn(1f, PAGE_MAX_ZOOM) else 1f
    return PAGE_ZOOM_STEPS.lastOrNull { it < current - ZOOM_STEP_EPSILON } ?: 1f
}

/** 倍率 → 工具栏百分比（连续双指值四舍五入到整数）。 */
internal fun zoomPercent(scale: Float): Int =
    if (scale.isFinite()) (scale.coerceIn(1f, PAGE_MAX_ZOOM) * 100f).roundToInt() else 100

/** 「适宽」目标：当前页宽高比撑满当前页槽；宽高比 / 槽尺寸未就绪时返回 null。 */
internal fun fitWidthScaleInSlot(
    pageAspect: Float?,
    slotWidth: Int,
    slotHeight: Int,
): Float? {
    if (pageAspect == null || !pageAspect.isFinite() || pageAspect <= 0f) return null
    if (slotWidth <= 0 || slotHeight <= 0) return null
    val slotAspect = slotWidth.toFloat() / slotHeight.toFloat()
    if (!slotAspect.isFinite() || slotAspect <= 0f) return null
    return fitWidthScale(pageAspect, slotAspect)
}

/** 分页 / 横版整页：页槽 = 整个阅读区。 */
internal fun pagedFitWidthScale(
    pageAspect: Float?,
    viewportWidth: Int,
    viewportHeight: Int,
): Float? = fitWidthScaleInSlot(pageAspect, viewportWidth, viewportHeight)

/** 双栏逐页：页槽 = 半屏宽、整屏高。 */
internal fun twoColumnFitWidthScale(
    pageAspect: Float?,
    viewportWidth: Int,
    viewportHeight: Int,
): Float? = fitWidthScaleInSlot(pageAspect, viewportWidth / 2, viewportHeight)

/** 滚动档页面在 1× 时本来就满宽。 */
internal fun scrollFitWidthScale(): Float = 1f

/** 合并对图：按合并位图的实际宽高比撑满整屏槽，不用单页宽高比。 */
internal fun mergedFitWidthScale(
    imageWidth: Int,
    imageHeight: Int,
    viewportWidth: Int,
    viewportHeight: Int,
): Float? {
    if (imageWidth <= 0 || imageHeight <= 0) return null
    return fitWidthScaleInSlot(
        imageWidth.toFloat() / imageHeight.toFloat(),
        viewportWidth,
        viewportHeight,
    )
}

/** 适宽目标及其所属页；翻页后页号不匹配的旧目标必须丢弃。 */
internal data class FitWidthTarget(
    val pageIndex: Int,
    val slotKey: Any,
    val scale: Float,
)

internal fun FitWidthTarget.scaleForCurrentPage(currentPage: Int): Float? = scale.takeIf {
    pageIndex == currentPage && it.isFinite() && it > 0f
}

private const val FIT_WIDTH_TOGGLE_EPSILON = 0.02f

/** 双击适宽：已命中目标则回 1×；目标未就绪 / 非法时返回 null（调用方保持原倍率）。 */
internal fun fitWidthToggleScale(
    currentScale: Float,
    target: Float?,
): Float? {
    if (target == null || !target.isFinite() || target <= 0f) return null
    val safeCurrent = if (currentScale.isFinite()) currentScale.coerceIn(1f, PAGE_MAX_ZOOM) else 1f
    val fitted = target > 1f && kotlin.math.abs(safeCurrent - target) <= FIT_WIDTH_TOGGLE_EPSILON
    return if (fitted) 1f else target.coerceIn(1f, PAGE_MAX_ZOOM)
}

/**
 * 以 [focus]（视口中心相对当前页槽中心的偏移）为锚点缩放：焦点下的页面内容点在缩放前后保持不动。
 *
 * [offset] 是当前页槽的平移量（像素）；`focus = Offset.Zero` 只在页槽与视口重合时表示视口中心。
 */
internal fun anchorPageOffset(
    offset: Offset,
    scale: Float,
    nextScale: Float,
    focus: Offset,
): Offset {
    if (nextScale <= 1f) return Offset.Zero
    if (!scale.isFinite() || scale <= 0f || !nextScale.isFinite()) return offset
    if (!offset.x.isFinite() || !offset.y.isFinite()) return Offset.Zero
    if (!focus.x.isFinite() || !focus.y.isFinite()) return offset
    val ratio = nextScale / scale
    return offset * ratio + focus * (1f - ratio)
}

/**
 * BitmapFactory 降采样系数（2 的幂），保证长边不超过 [maxSide]。
 *
 * 例：4000×3000 的扫描页在 2048 上限下取 2（输出 2000×1500）。
 */
internal fun bitmapSampleSize(width: Int, height: Int, maxSide: Int): Int {
    if (width <= 0 || height <= 0 || maxSide <= 0) return 1
    val longSide = maxOf(width, height)
    var sample = 1
    while (longSide / sample > maxSide) {
        sample *= 2
    }
    return sample
}

/** PDF 页面渲染比例：位图长边 = [maxSide]（矢量按比例缩放，允许放大以保持文字锐利）。 */
internal fun pdfRenderScale(pageWidth: Int, pageHeight: Int, maxSide: Int): Float {
    if (pageWidth <= 0 || pageHeight <= 0 || maxSide <= 0) return 1f
    return maxSide.toFloat() / maxOf(pageWidth, pageHeight).toFloat()
}

/** 页索引 → 整书 progression（页起点；与 EPUB `totalProgression` 的 0–1 语义一致）。 */
internal fun progressionForPage(pageIndex: Int, pageCount: Int): Double {
    if (pageCount <= 0) return 0.0
    return pageIndex.coerceIn(0, pageCount - 1).toDouble() / pageCount
}

/**
 * 整书 progression → 页索引（恢复上次阅读位置）。
 *
 * 用 floor + 微小 epsilon：`index / count` 往返换算会有 ±1 ulp 误差（如 `1/210*210` 略小于 1）， 直接取整会退到上一页。
 */
internal fun pageIndexForProgression(progression: Double, pageCount: Int): Int {
    if (pageCount <= 0) return 0
    val value = if (progression.isFinite()) progression.coerceIn(0.0, 1.0) else 0.0
    return floor(value * pageCount + PROGRESSION_EPSILON).toInt().coerceIn(0, pageCount - 1)
}

private const val PROGRESSION_EPSILON = 1e-6

/**
 * 页指示文案：滚动 / 分页 / 双栏三档文字不同，既是阅读体验的一部分，也是真机验收时 `uiautomator dump` 判断"模式确实生效"的可见证据（READER_PLAN §7）。
 *
 * RTL 生效（分页 / 双栏 + 右起开关）时追加「右起」后缀：既是给用户的提示，也是真机验收 里判断开关状态与翻页方向的文本证据（READER_PLAN §7.7）。
 */
internal fun pageIndicatorText(
    mode: ReaderMode,
    pageIndex: Int,
    pageCount: Int,
    rtl: Boolean = false,
    lastPageIndex: Int = pageIndex + 1,
): String {
    val suffix = if (isRtlPaging(mode, rtl)) " · 右起" else ""
    if (pageCount <= 0) return "${mode.label} · 0/0$suffix"
    val first = (pageIndex + 1).coerceIn(1, pageCount)
    val base =
        when (mode) {
            ReaderMode.TwoColumn -> {
                // W26：双栏槽位可能是 1 页（横版整页独占 / 竖版单张）或 2 页，按实际页范围显示；
                // lastPageIndex 与 pageIndex 同为 0-based 页索引。
                val last = (lastPageIndex + 1).coerceIn(first, pageCount)
                if (last > first) "${mode.label} · $first-$last/$pageCount"
                else "${mode.label} · $first/$pageCount"
            }

            else -> "${mode.label} · $first/$pageCount"
        }
    return base + suffix
}

/** 缩放后的平移夹取：放大 [scale] 倍时，最多平移到放大出来的边缘（避免把页面拖出视野）。 缩放回到 1× 时平移自动归零。 */
internal fun clampPageOffset(
    offset: Offset,
    scale: Float,
    width: Int,
    height: Int,
): Offset {
    if (!scale.isFinite() || scale <= 1f) return Offset.Zero
    if (width <= 0 || height <= 0) return Offset.Zero
    if (!offset.x.isFinite() || !offset.y.isFinite()) return Offset.Zero
    val maxX = width * (scale - 1f) / 2f
    val maxY = height * (scale - 1f) / 2f
    return Offset(offset.x.coerceIn(-maxX, maxX), offset.y.coerceIn(-maxY, maxY))
}

/**
 * 滚动档当前页只部分可见时，视口中心相对该页槽中心的焦点。
 *
 * 列表 item 的 [itemOffset] 是它相对视口顶部的偏移，item 中心 = offset + size / 2；结果为负表示视口中心在页槽中心上方。
 */
internal fun scrollViewportFocus(
    viewportHeight: Int,
    itemOffset: Int,
    itemSize: Int,
): Offset? {
    if (viewportHeight <= 0 || itemSize <= 0) return null
    val itemCenter = itemOffset.toFloat() + itemSize / 2f
    return Offset(0f, viewportHeight / 2f - itemCenter)
}

/** 双栏当前页在等宽槽中的位置：视口中心相对该页槽中心的水平焦点。 */
internal fun spreadPageViewportFocus(
    viewportWidth: Int,
    visualIndex: Int,
    visualCount: Int,
): Offset? {
    if (viewportWidth <= 0 || visualCount <= 0 || visualIndex !in 0 until visualCount) return null
    val slotWidth = viewportWidth.toFloat() / visualCount
    val slotCenter = (visualIndex + 0.5f) * slotWidth
    return Offset(viewportWidth / 2f - slotCenter, 0f)
}

/** 双栏跨页捏合是否成立：两根以上手指分别落在左右两半（同半页捏合仍由页槽自己处理）。 */
internal fun spreadPinchSpansPages(
    xPositions: List<Float>,
    pageWidth: Float,
): Boolean {
    if (pageWidth <= 0f || xPositions.size < 2) return false
    return xPositions.any { it < pageWidth } && xPositions.any { it >= pageWidth }
}

/**
 * 双栏跨页捏合的手指焦点：整屏坐标换算成当前页槽的局部焦点（相对页槽中心）。
 *
 * 与 [ZoomableSlot] 内单槽手势的焦点口径一致：`centroid - 页槽中心`，供 `BookZoomState.transform` 锚定缩放。
 */
internal fun spreadPinchFocus(
    centroid: Offset,
    viewportWidth: Int,
    viewportHeight: Int,
    visualIndex: Int,
    visualCount: Int,
): Offset? {
    if (viewportWidth <= 0 || viewportHeight <= 0 || visualCount <= 0) return null
    if (visualIndex !in 0 until visualCount) return null
    if (!centroid.x.isFinite() || !centroid.y.isFinite()) return null
    val slotWidth = viewportWidth.toFloat() / visualCount
    val slotCenterX = (visualIndex + 0.5f) * slotWidth
    return Offset(centroid.x - slotCenterX, centroid.y - viewportHeight / 2f)
}
