package com.zhangwenkang.cinefin.book.presentation.reader

import androidx.compose.ui.geometry.Offset
import kotlin.math.floor

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
): String {
    val suffix = if (isRtlPaging(mode, rtl)) " · 右起" else ""
    if (pageCount <= 0) return "${mode.label} · 0/0$suffix"
    val first = (pageIndex + 1).coerceIn(1, pageCount)
    val base =
        when (mode) {
            ReaderMode.TwoColumn -> {
                val last = (first + 1).coerceAtMost(pageCount)
                "${mode.label} · $first-$last/$pageCount"
            }

            else -> "${mode.label} · $first/$pageCount"
        }
    return base + suffix
}

/**
 * 缩放状态（分页 / 双栏 / 滚动三种模式共用同一套数学，保证范围一致：1×–[PAGE_MAX_ZOOM]）。
 *
 * 真机踩坑 18 的结论是"手势必须由页项消费、单指留给容器滚动"；本状态机只负责把每次双指事件 推进为新的缩放 / 平移，与手势来源解耦，可单测（READER_PLAN §7.7）。
 */
internal data class PageZoom(val scale: Float = 1f, val offset: Offset = Offset.Zero) {

    /** 推进一次双指手势：倍数夹取到 1×–[PAGE_MAX_ZOOM]，平移按放大后的可移动范围夹取（缩放回 1× 自动归零、居中）。 */
    fun transform(zoom: Float, pan: Offset, width: Int, height: Int): PageZoom {
        val safeZoom = if (zoom.isFinite() && zoom > 0f) zoom else 1f
        val product = scale * safeZoom
        val nextScale = (if (product.isFinite()) product else scale).coerceIn(1f, PAGE_MAX_ZOOM)
        if (nextScale <= 1f) return PageZoom()
        val safePan = if (pan.x.isFinite() && pan.y.isFinite()) pan else Offset.Zero
        return PageZoom(nextScale, clampPageOffset(offset + safePan, nextScale, width, height))
    }
}

/** 缩放后的平移夹取：放大 [scale] 倍时，最多平移到放大出来的边缘（避免把页面拖出视野）。 缩放回到 1× 时平移自动归零。 */
internal fun clampPageOffset(
    offset: Offset,
    scale: Float,
    width: Int,
    height: Int,
): Offset {
    if (scale <= 1f) return Offset.Zero
    val maxX = width * (scale - 1f) / 2f
    val maxY = height * (scale - 1f) / 2f
    return Offset(offset.x.coerceIn(-maxX, maxX), offset.y.coerceIn(-maxY, maxY))
}
