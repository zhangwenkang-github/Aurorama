package com.zhangwenkang.cinefin.book.presentation.reader

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * 对图合并位图缓存（双栏槽位）。
 *
 * 流程：几何门槛 → 256 px 缩略图取中缝证据 → 命中才渲染两半并合成整幅 → 放进两级 LRU（当前槽 + 邻槽）。判定不命中 / 渲染失败都返回
 * null，调用方继续走原来的两页渲染，等价于 W4 行为。
 *
 * 内存（EB-3 红线同口径）：
 * - 判定只用两张 256 px 缩略图（各 ≈0.2 MB，用完立即 recycle）；
 * - 合并位图长边 ≤ [SPREAD_MERGE_MAX_SIDE_PX]，缓存窗口 [SPREAD_MERGE_CACHE_WINDOW] 张： PDF（ARGB_8888）≤ 2×11.8
 *   MB，CBZ（RGB_565）≤ 2×5.9 MB；
 * - 合成瞬时峰值额外为两张半页（≈2×5.9 MB ARGB / ≈2×3.0 MB RGB_565，合并完即释放）。
 */
internal class SpreadImageCache(
    private val source: PageSource,
    private val rtl: Boolean,
    maxSidePx: Int,
) {
    private val maxSidePx = maxSidePx.coerceAtLeast(1)
    private val cache = LruCache<Int, Bitmap>(SPREAD_MERGE_CACHE_WINDOW)

    /** 判定结果缓存：键 = spread，值 = 命中时的拼合几何（null = 不合并）。不随位图淘汰而失效。 */
    private val decisions = HashMap<Int, SpreadMergeGeometry?>()
    private val mutex = Mutex()

    /** 合并后的整幅位图；不命中 / 渲染失败返回 null（调用方回退到两页各自渲染）。 */
    suspend fun mergedSpread(spread: Int): Bitmap? {
        if (spread < 0) return null
        val first = spread * SPREAD_MERGE_PAGES_PER_SPREAD
        val second = first + 1
        if (second >= source.pageCount) return null
        cache.get(spread)?.let {
            return it
        }
        return mutex.withLock {
            cache.get(spread)
                ?: decideMerge(spread, first, second)
                    ?.let { geometry -> composeMerged(spread, first, second, geometry) }
                    ?.also { cache.put(spread, it) }
        }
    }

    /**
     * 同步取已备好的合并位图（组合期第一帧用）：命中过一次 / 已预取的槽位直接拿到，避免先渲染两页再 换成合并图的闪动与多余渲染；没有则返回 null，由 [mergedSpread]
     * 异步构建。
     */
    fun cached(spread: Int): Bitmap? = if (spread < 0) null else cache.get(spread)

    /** 预取邻槽（停稳后调用）：命中会连判定带位图一起备好，不命中只留一条判定缓存。 */
    suspend fun prefetch(spread: Int) {
        if (spread < 0 || spread >= spreadUpperBound()) return
        mergedSpread(spread)
    }

    private fun spreadUpperBound(): Int {
        val pages = source.pageCount
        if (pages <= 0) return 0
        return (pages - 1) / SPREAD_MERGE_PAGES_PER_SPREAD + 1
    }

    private suspend fun decideMerge(
        spread: Int,
        first: Int,
        second: Int,
    ): SpreadMergeGeometry? {
        if (decisions.containsKey(spread)) return decisions[spread]
        val geometry = determineMerge(spread, first, second)
        decisions[spread] = geometry
        return geometry
    }

    private suspend fun determineMerge(
        spread: Int,
        first: Int,
        second: Int,
    ): SpreadMergeGeometry? {
        val sizeFirst = runCatching { source.pageSizePx(first) }.getOrNull() ?: return null
        val sizeSecond = runCatching { source.pageSizePx(second) }.getOrNull() ?: return null
        val geometry =
            spreadMergeGeometry(
                PagePairGeometry(
                    sizeFirst.first,
                    sizeFirst.second,
                    sizeSecond.first,
                    sizeSecond.second,
                ),
                maxSidePx,
            ) ?: return null
        val thumbFirst =
            runCatching { source.renderPage(first, SPREAD_MERGE_THUMB_MAX_SIDE_PX) }.getOrNull()
                ?: return null
        val thumbSecond = runCatching {
            source.renderPage(second, SPREAD_MERGE_THUMB_MAX_SIDE_PX)
        }
            .getOrNull()
        if (thumbSecond == null) {
            // 第二张没渲染出来：第一张缩略图要先回收，不能漏。
            recycleQuietly(thumbFirst)
            return null
        }
        val evidence =
            try {
                withContext(Dispatchers.Default) {
                    spreadSeamEvidence(
                        thumbFirst.edgeSample(spreadInnerEdge(FIRST_SLOT, rtl)),
                        thumbSecond.edgeSample(spreadInnerEdge(SECOND_SLOT, rtl)),
                    )
                }
            } catch (error: Throwable) {
                Timber.w(error, "对图判定失败 spread=%d", spread)
                null
            } finally {
                recycleQuietly(thumbFirst)
                recycleQuietly(thumbSecond)
            }
        if (evidence == null || !shouldMergeSpread(evidence)) return null
        // 真机验收的文本证据：命中才打点（不命中每屏都会出现，不记）。
        Timber.d(
            "reader spread merge spread=%d pages=%d-%d continuity=%.2f corr=%.2f diff=%.2f",
            spread,
            first + 1,
            second + 1,
            evidence.continuity,
            evidence.correlation,
            evidence.difference,
        )
        return geometry
    }

    private suspend fun composeMerged(
        spread: Int,
        first: Int,
        second: Int,
        geometry: SpreadMergeGeometry,
    ): Bitmap? {
        val halfFirst =
            runCatching { source.renderPage(first, geometry.targetHeightPx) }.getOrNull()
                ?: return null
        val halfSecond =
            runCatching { source.renderPage(second, geometry.targetHeightPx) }.getOrNull()
                ?: return null
        return try {
            // 位图合成是 CPU 密集操作，放 Default 线程，别占用主线程。
            withContext(Dispatchers.Default) {
                composeMergedBitmap(halfFirst, halfSecond, geometry, spreadPieceOrder(rtl))
            }
        } catch (error: Throwable) {
            Timber.w(error, "对图合成失败 spread=%d", spread)
            null
        } finally {
            recycleQuietly(halfFirst)
            recycleQuietly(halfSecond)
        }
    }
}

private const val FIRST_SLOT = 0
private const val SECOND_SLOT = 1

/**
 * 把两半合成为一整幅：统一高度（不放大）、左右相接（RTL 时先读的一页在右）。
 *
 * 色彩配置沿用两半：CBZ 两半都是 RGB_565 时合并图也用 565（内存减半），PDF 保留 ARGB_8888 （矢量文字锐利优先）。
 */
private fun composeMergedBitmap(
    halfFirst: Bitmap,
    halfSecond: Bitmap,
    geometry: SpreadMergeGeometry,
    pieceOrder: List<Int>,
): Bitmap? {
    if (pieceOrder.size < 2) return null
    val halves = listOf(halfFirst, halfSecond)
    val left = halves[pieceOrder[0].coerceIn(0, halves.lastIndex)]
    val right = halves[pieceOrder[1].coerceIn(0, halves.lastIndex)]
    val height = mergedSpreadHeight(geometry, halves.map { it.height })
    val leftWidth = scaledHalfWidth(left.width, left.height, height)
    val rightWidth = scaledHalfWidth(right.width, right.height, height)
    if (height <= 0 || leftWidth <= 0 || rightWidth <= 0) return null
    val config =
        if (left.config == Bitmap.Config.RGB_565 && right.config == Bitmap.Config.RGB_565) {
            Bitmap.Config.RGB_565
        } else {
            Bitmap.Config.ARGB_8888
        }
    val merged = Bitmap.createBitmap(leftWidth + rightWidth, height, config)
    val canvas = Canvas(merged)
    val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    canvas.drawBitmap(
        left,
        Rect(0, 0, left.width, left.height),
        Rect(0, 0, leftWidth, height),
        paint,
    )
    canvas.drawBitmap(
        right,
        Rect(0, 0, right.width, right.height),
        Rect(leftWidth, 0, leftWidth + rightWidth, height),
        paint,
    )
    return merged
}

/** 缩略图的内缘取样：整张小图读进 IntArray（256 px 长边 ≈0.2 MB），用完即可回收。 */
private fun Bitmap.edgeSample(edge: SpreadEdge): SpreadEdgeSample {
    if (width <= 0 || height <= 0) return SpreadEdgeSample(IntArray(0), width)
    val pixels = IntArray(width * height)
    getPixels(pixels, 0, width, 0, 0, width, height)
    return spreadEdgeSample(pixels, width, height, edge)
}

private fun recycleQuietly(bitmap: Bitmap) {
    if (!bitmap.isRecycled) bitmap.recycle()
}
