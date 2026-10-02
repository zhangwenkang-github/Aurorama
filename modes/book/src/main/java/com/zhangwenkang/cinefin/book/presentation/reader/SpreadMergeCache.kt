package com.zhangwenkang.cinefin.book.presentation.reader

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.util.LruCache
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * 对图合并位图缓存（双栏槽位）。
 *
 * 流程：几何门槛 → 256 px 缩略图取中缝证据（W26 起支持内缘纸边裁剪路径）→ 命中才渲染两半并合成整幅 （合成时按同一纸边判据裁剪内缘）→ 放进两级 LRU（当前槽 +
 * 邻槽）。判定不命中 / 渲染失败都返回 null，调用方继续走原来的两页渲染，等价于 W4 行为。
 *
 * 键 = 拼合页对的**首页索引**（W26：横版整页独占后槽位与页对的 `2k / 2k+1` 映射不再成立；两页槽一定由 相邻两页组成，首页即可唯一标识）。
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

    /** 判定结果缓存：键 = 首页索引，值 = 命中时的决策（null = 不合并）。不随位图淘汰而失效。 */
    private val decisions = HashMap<Int, SpreadMergeDecision?>()
    private val mutex = Mutex()

    /** 合并后的整幅位图；不命中 / 渲染失败返回 null（调用方回退到两页各自渲染）。 */
    suspend fun mergedSpread(firstPage: Int): Bitmap? {
        if (firstPage < 0 || firstPage + 1 >= source.pageCount) return null
        cache.get(firstPage)?.let {
            return it
        }
        return mutex.withLock {
            cache.get(firstPage)
                ?: decideMerge(firstPage)
                    ?.let { decision -> composeMerged(firstPage, decision) }
                    ?.also { cache.put(firstPage, it) }
        }
    }

    /**
     * 同步取已备好的合并位图（组合期第一帧用）：命中过一次 / 已预取的槽位直接拿到，避免先渲染两页再 换成合并图的闪动与多余渲染；没有则返回 null，由 [mergedSpread]
     * 异步构建。
     */
    fun cached(firstPage: Int): Bitmap? = if (firstPage < 0) null else cache.get(firstPage)

    /** 预取邻槽（停稳后调用）：命中会连判定带位图一起备好，不命中只留一条判定缓存。 */
    suspend fun prefetch(firstPage: Int) {
        if (firstPage < 0 || firstPage + 1 >= source.pageCount) return
        mergedSpread(firstPage)
    }

    private suspend fun decideMerge(firstPage: Int): SpreadMergeDecision? {
        if (decisions.containsKey(firstPage)) return decisions[firstPage]
        val decision = determineMerge(firstPage)
        decisions[firstPage] = decision
        return decision
    }

    private suspend fun determineMerge(firstPage: Int): SpreadMergeDecision? {
        val secondPage = firstPage + 1
        val sizeFirst = runCatching { source.pageSizePx(firstPage) }.getOrNull() ?: return null
        val sizeSecond = runCatching { source.pageSizePx(secondPage) }.getOrNull() ?: return null
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
            runCatching { source.renderPage(firstPage, SPREAD_MERGE_THUMB_MAX_SIDE_PX) }.getOrNull()
                ?: return null
        val thumbSecond = runCatching {
            source.renderPage(secondPage, SPREAD_MERGE_THUMB_MAX_SIDE_PX)
        }
            .getOrNull()
        if (thumbSecond == null) {
            // 第二张没渲染出来：第一张缩略图要先回收，不能漏。
            recycleQuietly(thumbFirst)
            return null
        }
        val decision =
            try {
                withContext(Dispatchers.Default) {
                    spreadMergeDecision(
                        firstPixels = thumbFirst.toPixels(),
                        firstWidthPx = thumbFirst.width,
                        firstHeightPx = thumbFirst.height,
                        firstEdge = spreadInnerEdge(FIRST_SLOT, rtl),
                        secondPixels = thumbSecond.toPixels(),
                        secondWidthPx = thumbSecond.width,
                        secondHeightPx = thumbSecond.height,
                        secondEdge = spreadInnerEdge(SECOND_SLOT, rtl),
                        geometry = geometry,
                    )
                }
            } catch (error: Throwable) {
                Timber.w(error, "对图判定失败 first=%d", firstPage)
                null
            } finally {
                recycleQuietly(thumbFirst)
                recycleQuietly(thumbSecond)
            }
        if (decision == null) return null
        // 真机验收的文本证据：命中才打点（不命中每屏都会出现，不记）。
        val evidence = decision.evidence
        Timber.d(
            "reader spread merge spread=%d pages=%d-%d continuity=%.2f corr=%.2f diff=%.2f trim=%d/%d",
            firstPage,
            firstPage + 1,
            secondPage + 1,
            evidence.continuity,
            evidence.correlation,
            evidence.difference,
            decision.trimFirstPx,
            decision.trimSecondPx,
        )
        return decision
    }

    private suspend fun composeMerged(
        firstPage: Int,
        decision: SpreadMergeDecision,
    ): Bitmap? {
        val secondPage = firstPage + 1
        val halfFirst =
            runCatching { source.renderPage(firstPage, decision.geometry.targetHeightPx) }
                .getOrNull() ?: return null
        val halfSecond = runCatching {
            source.renderPage(secondPage, decision.geometry.targetHeightPx)
        }
            .getOrNull()
        if (halfSecond == null) {
            recycleQuietly(halfFirst)
            return null
        }
        Timber.d(
            "reader spread compose first=%d halves=%dx%d/%dx%d trim=%d/%d ratio=%.4f/%.4f",
            firstPage,
            halfFirst.width,
            halfFirst.height,
            halfSecond.width,
            halfSecond.height,
            decision.trimFirstPx,
            decision.trimSecondPx,
            decision.trimFirstRatio,
            decision.trimSecondRatio,
        )
        return try {
            // 位图合成是 CPU 密集操作，放 Default 线程，别占用主线程。
            withContext(Dispatchers.Default) {
                composeMergedBitmap(
                    halfFirst = halfFirst,
                    halfSecond = halfSecond,
                    geometry = decision.geometry,
                    pieceOrder = spreadPieceOrder(rtl),
                    trimFirstRatio = decision.trimFirstRatio,
                    trimSecondRatio = decision.trimSecondRatio,
                )
            }
        } catch (error: Throwable) {
            Timber.w(error, "对图合成失败 first=%d", firstPage)
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
 * W26 纸边裁剪：左侧半页的内缘在其右缘、右侧半页的内缘在其左缘（LTR / RTL 都成立，与判定相位一致）； 合成前按全分辨率重新检测纸边，检测不到时用判定阶段（缩略图）的裁剪比例兜底。
 *
 * 色彩配置沿用两半：CBZ 两半都是 RGB_565 时合并图也用 565（内存减半），PDF 保留 ARGB_8888 （矢量文字锐利优先）。
 */
private fun composeMergedBitmap(
    halfFirst: Bitmap,
    halfSecond: Bitmap,
    geometry: SpreadMergeGeometry,
    pieceOrder: List<Int>,
    trimFirstRatio: Float,
    trimSecondRatio: Float,
): Bitmap? {
    if (pieceOrder.size < 2) return null
    val halves = listOf(halfFirst, halfSecond)
    val trimRatios = listOf(trimFirstRatio, trimSecondRatio)
    val leftIndex = pieceOrder[0].coerceIn(0, halves.lastIndex)
    val rightIndex = pieceOrder[1].coerceIn(0, halves.lastIndex)
    val left = halves[leftIndex]
    val right = halves[rightIndex]
    val leftTrim = resolveEdgeTrim(left, SpreadEdge.Right, trimRatios[leftIndex])
    val rightTrim = resolveEdgeTrim(right, SpreadEdge.Left, trimRatios[rightIndex])
    Timber.d(
        "reader spread compose trims=%d/%d left=%dx%d right=%dx%d",
        leftTrim,
        rightTrim,
        left.width,
        left.height,
        right.width,
        right.height,
    )
    val height = mergedSpreadHeight(geometry, halves.map { it.height })
    // 左半裁内缘（右缘）、右半裁内缘（左缘）：源范围由纯函数给出，避免左右方向写反。
    val leftRange = spreadHalfSourceRange(left.width, SpreadEdge.Right, leftTrim)
    val rightRange = spreadHalfSourceRange(right.width, SpreadEdge.Left, rightTrim)
    val leftWidth = scaledHalfWidth(leftRange.widthPx, left.height, height)
    val rightWidth = scaledHalfWidth(rightRange.widthPx, right.height, height)
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
        Rect(leftRange.startPx, 0, leftRange.endExclusivePx, left.height),
        Rect(0, 0, leftWidth, height),
        paint,
    )
    canvas.drawBitmap(
        right,
        Rect(rightRange.startPx, 0, rightRange.endExclusivePx, right.height),
        Rect(leftWidth, 0, leftWidth + rightWidth, height),
        paint,
    )
    return merged
}

/** 合成时的内缘裁剪像素：优先在全分辨率位图上重新检测纸边（列均值 / 列内标准差）， 检测不到而判定阶段裁过时按缩略图比例映射兜底。 */
private fun resolveEdgeTrim(bitmap: Bitmap, edge: SpreadEdge, fallbackRatio: Float): Int {
    val detected = bitmap.trimInsetPx(edge)
    if (detected != null && detected > 0) return detected
    if (fallbackRatio <= 0f) return 0
    val maxTrim = (bitmap.width * SPREAD_MAX_EDGE_TRIM_RATIO).toInt().coerceAtLeast(1)
    return (bitmap.width * fallbackRatio).roundToInt().coerceIn(0, maxTrim)
}

/** 只读内缘窄带（宽度上限 [SPREAD_MAX_EDGE_TRIM_RATIO]）做纸边检测，避免整幅 getPixels 的瞬时内存。 */
private fun Bitmap.trimInsetPx(edge: SpreadEdge): Int? {
    if (width <= 0 || height <= 0) return null
    val maxTrim = (width * SPREAD_MAX_EDGE_TRIM_RATIO).toInt().coerceAtLeast(2)
    val band = minOf(maxTrim, width)
    if (band <= 0) return null
    val x = if (edge == SpreadEdge.Left) 0 else width - band
    val pixels = IntArray(band * height)
    getPixels(pixels, 0, band, x, 0, band, height)
    val stats = spreadEdgeColumnStats(pixels, band, height, edge, band) ?: return null
    return paperTrimInset(stats)
}

/** 缩略图整幅像素（判定用；256 px 长边 ≈0.2 MB，用完随缩略图一起回收）。 */
private fun Bitmap.toPixels(): IntArray {
    val pixels = IntArray(width * height)
    getPixels(pixels, 0, width, 0, 0, width, height)
    return pixels
}

private fun recycleQuietly(bitmap: Bitmap) {
    if (!bitmap.isRecycled) bitmap.recycle()
}
