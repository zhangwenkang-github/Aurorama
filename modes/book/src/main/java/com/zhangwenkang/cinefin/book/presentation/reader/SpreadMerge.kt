package com.zhangwenkang.cinefin.book.presentation.reader

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * 跨页对图合并（EB-4 后置项，W22）：把"被拆成两张扫描"的横版对图在双栏槽位里拼回一整幅。
 *
 * 与 W9 D19 的差别：本实现**不改页序、不改页数、不改 spread 划分**——合并只发生在当前双栏槽位的渲染 层（同一个 spread
 * 的两页画成一张位图）。因此判定失误最多影响这一屏的观感，不会让整本书的翻页配对 错位，D19 最主要的顾虑由此消解；剩下的风险是"误拼"本身，故判定取保守口径：
 * 只有中缝两侧都能取到内容、且内容曲线高度连续（相关系数高、平均差小）才合并。
 *
 * 判定与几何全部是纯函数（无 Android 依赖，只处理亮度数组），由 JVM 单测覆盖；像素采样与位图合成在 [SpreadImageCache] 里。阈值标定数据见
 * `docs/READER_PLAN.md` §7.9 / §2 D20。
 */

/** 合并位图的长边上限：与单页 [PAGE_BITMAP_MAX_SIDE_PX] 同口径（双栏下长边就是整幅宽度）。 */
internal const val SPREAD_MERGE_MAX_SIDE_PX: Int = 2048

/**
 * 判定用缩略图的长边。
 *
 * 判定只比较中缝两侧各 [SPREAD_EDGE_BAND_PX] 列的逐行亮度：小图既省内存，又能把轻微的扫描错位 / 噪点平均掉（本地标定用 256 px，真对图相关系数
 * 0.52–0.93、独立页强切 ≤0.74）。
 */
internal const val SPREAD_MERGE_THUMB_MAX_SIDE_PX: Int = 256

/** 合并位图缓存窗口：当前槽 + 邻槽两级（双栏一屏 = 一个槽位）。 */
internal const val SPREAD_MERGE_CACHE_WINDOW: Int = 2

/** 一个 spread 固定 = 两页（只在 [ReaderMode.TwoColumn] 下合并）。 */
internal const val SPREAD_MERGE_PAGES_PER_SPREAD: Int = 2

/** 两半高度允许的相对误差（同一张对图被拆两张时高度几乎一致，留扫描裁切余量）。 */
internal const val SPREAD_HALF_HEIGHT_TOLERANCE: Float = 0.02f

/** 半页宽高比区间：竖版页（A5 / B6 ≈ 0.70，留出裁切余量）。 */
internal const val SPREAD_HALF_RATIO_MIN: Float = 0.55f
internal const val SPREAD_HALF_RATIO_MAX: Float = 0.98f

/** 拼合后整幅宽高比区间：两张竖版页并排 ≈1.41（A5 / B6 对开）。 */
internal const val SPREAD_COMBINED_RATIO_MIN: Float = 1.15f
internal const val SPREAD_COMBINED_RATIO_MAX: Float = 2.05f

/** 中缝连续性下限：内缘两侧都有内容的行占比（画面确实"对穿中缝"）。 */
internal const val SPREAD_SEAM_MIN_CONTINUITY: Float = 0.05f

/** 内缘亮度曲线的最小标准差：低于此值说明是均匀色块，无法确认连续性（保守不合并）。 */
internal const val SPREAD_SEAM_MIN_VARIATION: Float = 10f

/** 内缘亮度曲线的最小相关系数（1 = 完全连续）。 */
internal const val SPREAD_SEAM_MIN_CORRELATION: Float = 0.6f

/** 去掉各自均值后的归一化平均差上限（0 = 两半完全一致，抵消折痕阴影 / 曝光差）。 */
internal const val SPREAD_SEAM_MAX_DIFFERENCE: Float = 0.18f

/** 判定"内缘这一行有内容"的亮度阈值（纸白 ≈255）。 */
internal const val SPREAD_INK_THRESHOLD: Int = 200

/** 内缘取样的列数：多取几列做平均，抵消扫描噪点。 */
internal const val SPREAD_EDGE_BAND_PX: Int = 2

/** 纸边裁剪路径（W26「带纸边对图」）的额外门槛：比直接路径更严，避免两张独立页的纸白边 各自对裁后误拼。 */
internal const val SPREAD_TRIM_MIN_CORRELATION: Float = 0.75f
internal const val SPREAD_TRIM_MAX_DIFFERENCE: Float = 0.15f

/** 内缘裁剪上限：相对半页宽度的比例；纸白带超过此宽度视为无法定位内容边界（保守不合并）。 */
internal const val SPREAD_MAX_EDGE_TRIM_RATIO: Float = 0.18f

/** 纸白列判据：列平均亮度 ≥ [SPREAD_PAPER_COLUMN_MIN_BRIGHTNESS] 且列内标准差 ≤ [SPREAD_PAPER_COLUMN_MAX_STD]。 */
internal const val SPREAD_PAPER_COLUMN_MIN_BRIGHTNESS: Float = 215f
internal const val SPREAD_PAPER_COLUMN_MAX_STD: Float = 20f

/** 内缘所在的一侧（拼接缝那一侧）。 */
internal enum class SpreadEdge {
    Left,
    Right,
}

/** 一页的自然尺寸（像素）。 */
internal data class PagePairGeometry(
    val widthA: Int,
    val heightA: Int,
    val widthB: Int,
    val heightB: Int,
)

/** 拼合几何：两半统一渲染到 [targetHeightPx]，拼出的整幅宽高比 = [combinedAspect]。 */
internal data class SpreadMergeGeometry(val targetHeightPx: Int, val combinedAspect: Float)

/**
 * 几何门槛：两半都是竖版、高度接近、并排后是横幅对开比例。
 *
 * 返回 null 表示这一对不可能是"被拆开的对图"（横版整页、尺寸差异过大、拼出来不像对开），直接走原来的 两页渲染。合并位图的长边钉在 [maxSidePx]，由 `targetHeight
 * × combinedAspect` 反推目标高度。
 */
internal fun spreadMergeGeometry(
    pair: PagePairGeometry,
    maxSidePx: Int,
): SpreadMergeGeometry? {
    if (pair.widthA <= 0 || pair.heightA <= 0 || pair.widthB <= 0 || pair.heightB <= 0) return null
    if (maxSidePx <= 0) return null
    val ratioA = pair.widthA.toFloat() / pair.heightA
    val ratioB = pair.widthB.toFloat() / pair.heightB
    if (ratioA < SPREAD_HALF_RATIO_MIN || ratioA > SPREAD_HALF_RATIO_MAX) return null
    if (ratioB < SPREAD_HALF_RATIO_MIN || ratioB > SPREAD_HALF_RATIO_MAX) return null
    val tallest = maxOf(pair.heightA, pair.heightB).toFloat()
    if (abs(pair.heightA - pair.heightB) / tallest > SPREAD_HALF_HEIGHT_TOLERANCE) return null
    val combinedAspect =
        (pair.widthA + pair.widthB).toFloat() / ((pair.heightA + pair.heightB) / 2f)
    if (combinedAspect < SPREAD_COMBINED_RATIO_MIN) return null
    if (combinedAspect > SPREAD_COMBINED_RATIO_MAX) return null
    val targetHeight = floor(maxSidePx / combinedAspect).toInt().coerceAtLeast(1)
    return SpreadMergeGeometry(targetHeightPx = targetHeight, combinedAspect = combinedAspect)
}

/** 拼合时两半统一渲染到的高度：取目标高与两半自然高的最小值（CBZ 的 2 的幂降采样可能给出更小的图， 不放大、不超预算）。 */
internal fun mergedSpreadHeight(geometry: SpreadMergeGeometry, halfHeights: List<Int>): Int {
    val natural = halfHeights.filter { it > 0 }.minOrNull() ?: geometry.targetHeightPx
    return minOf(geometry.targetHeightPx, natural).coerceAtLeast(1)
}

/** 统一高度后某一半的宽度（按原始比例缩放；参数非法返回 0，调用方回退）。 */
internal fun scaledHalfWidth(widthPx: Int, heightPx: Int, targetHeightPx: Int): Int {
    if (widthPx <= 0 || heightPx <= 0 || targetHeightPx <= 0) return 0
    return (widthPx.toLong() * targetHeightPx / heightPx).toInt().coerceAtLeast(1)
}

/** 合并只作用于双栏（横屏双栏 / 平板双栏）；分页 / 滚动保持 W4 行为。 */
internal fun spreadMergeEnabled(mode: ReaderMode): Boolean = mode == ReaderMode.TwoColumn

/**
 * 双栏槽位里左右两半的顺序：LTR 先读的在左；RTL（右起）先读的在右（与实体漫画一致）。
 *
 * 返回值是相对两页的序号（0 = 先读的那页），拼接位图按返回顺序从左往右铺。
 */
internal fun spreadPieceOrder(rtl: Boolean): List<Int> = if (rtl) listOf(1, 0) else listOf(0, 1)

/** 某一页在拼接缝上的一侧：LTR 时先读的页缝在右、后读的页缝在左；RTL 相反（先读的页在右，缝在左）。 */
internal fun spreadInnerEdge(slot: Int, rtl: Boolean): SpreadEdge =
    when {
        slot == 0 -> if (rtl) SpreadEdge.Left else SpreadEdge.Right
        else -> if (rtl) SpreadEdge.Right else SpreadEdge.Left
    }

/** 一页内缘的逐行亮度取样（内缘 [SPREAD_EDGE_BAND_PX] 列的平均值）。 */
internal data class SpreadEdgeSample(val brightness: IntArray, val widthPx: Int)

/** 拼图半页的源列范围（[startPx], [endExclusivePx)）。 */
internal data class SpreadHalfSourceRange(val startPx: Int, val endExclusivePx: Int) {
    val widthPx: Int
        get() = (endExclusivePx - startPx).coerceAtLeast(0)
}

/**
 * 拼图半页的裁剪源范围：裁掉**内缘**一侧的纸边。
 *
 * 拼图左半的内缘在其右缘、右半的内缘在其左缘（LTR / RTL 都成立）；[edge] 传该半页的内缘。 例：704 宽、纸边 18 → 左半取 [0, 686)、右半取 [18, 704)。
 */
internal fun spreadHalfSourceRange(
    widthPx: Int,
    edge: SpreadEdge,
    trimPx: Int,
): SpreadHalfSourceRange {
    if (widthPx <= 0) return SpreadHalfSourceRange(0, 0)
    val trim = trimPx.coerceIn(0, widthPx - 1)
    return if (edge == SpreadEdge.Right) {
        SpreadHalfSourceRange(0, widthPx - trim)
    } else {
        SpreadHalfSourceRange(trim, widthPx)
    }
}

/**
 * 从 ARGB 像素里抽取内缘逐行亮度：只读内缘 [bandPx] 列，逐行取平均。
 *
 * [insetPx] 是相对内缘的列偏移（W26 纸边裁剪路径：跳过内缘纸白带后重新取样）。 入参是**缩略图**的像素（判定用），不是整幅页面位图；[widthPx] / [heightPx]
 * 不合法时返回空样本。
 */
internal fun spreadEdgeSample(
    pixels: IntArray,
    widthPx: Int,
    heightPx: Int,
    edge: SpreadEdge,
    bandPx: Int = SPREAD_EDGE_BAND_PX,
    insetPx: Int = 0,
): SpreadEdgeSample {
    if (widthPx <= 0 || heightPx <= 0) return SpreadEdgeSample(IntArray(0), widthPx)
    if (pixels.size < widthPx * heightPx) return SpreadEdgeSample(IntArray(0), widthPx)
    val start = insetPx.coerceIn(0, widthPx - 1)
    val band = bandPx.coerceIn(1, widthPx - start)
    val out = IntArray(heightPx)
    for (y in 0 until heightPx) {
        var sum = 0
        val rowStart = y * widthPx
        for (i in 0 until band) {
            val x = if (edge == SpreadEdge.Left) start + i else widthPx - 1 - start - i
            sum += luminance(pixels[rowStart + x])
        }
        out[y] = sum / band
    }
    return SpreadEdgeSample(brightness = out, widthPx = widthPx)
}

/**
 * 内缘起 [maxPx] 列的逐列统计（判定缩略图与合成位图共用）。
 *
 * - 均值：该列所有行的平均亮度；
 * - 标准差：该列行方向的亮度起伏（纸白边接近 0，内容列明显更大）。
 *
 * 返回 null 表示参数非法（尺寸 / 像素数组不足）。
 */
internal class SpreadEdgeColumnStats(val means: FloatArray, val standardDeviations: FloatArray) {
    val size: Int
        get() = means.size
}

internal fun spreadEdgeColumnStats(
    pixels: IntArray,
    widthPx: Int,
    heightPx: Int,
    edge: SpreadEdge,
    maxPx: Int,
): SpreadEdgeColumnStats? {
    if (widthPx <= 0 || heightPx <= 0 || maxPx <= 0) return null
    if (pixels.size < widthPx * heightPx) return null
    val columns = minOf(maxPx, widthPx)
    val means = FloatArray(columns)
    val deviations = FloatArray(columns)
    for (index in 0 until columns) {
        val x = if (edge == SpreadEdge.Left) index else widthPx - 1 - index
        var sum = 0.0
        var sumSquares = 0.0
        for (y in 0 until heightPx) {
            val value = luminance(pixels[y * widthPx + x]).toDouble()
            sum += value
            sumSquares += value * value
        }
        val mean = sum / heightPx
        val variance = (sumSquares / heightPx - mean * mean).coerceAtLeast(0.0)
        means[index] = mean.toFloat()
        deviations[index] = sqrt(variance).toFloat()
    }
    return SpreadEdgeColumnStats(means, deviations)
}

/**
 * 找内缘纸边的裁剪量：从内缘向内跳过连续的纸白列，返回第一列「内容」的偏移。
 *
 * - 返回 0：内缘第一列就是内容（没有纸边可裁）；
 * - 返回 >0：裁掉这么多列后到达内容边界；
 * - 返回 null：整段扫描范围都是纸白（无法定位）或参数异常 → 放弃裁剪（保守）。
 *
 * 纸白列判据只接受「亮且低信息」的列：均匀深色带（黑边）不算纸白，避免把黑边页错裁 （READER_PLAN §8 坑 23）。
 */
internal fun paperTrimInset(stats: SpreadEdgeColumnStats): Int? {
    if (stats.size <= 0) return null
    var paperColumns = 0
    for (index in 0 until stats.size) {
        val isPaper =
            stats.means[index] >= SPREAD_PAPER_COLUMN_MIN_BRIGHTNESS &&
                stats.standardDeviations[index] <= SPREAD_PAPER_COLUMN_MAX_STD
        if (!isPaper) return if (paperColumns > 0) index else 0
        paperColumns += 1
    }
    return null
}

/** 中缝证据：只在"两侧内缘都有内容"的行上比较，纸白行不构成对图证据。 */
internal data class SpreadSeamEvidence(
    /** 两侧都有内容的行占比（0–1）。 */
    val continuity: Float,
    /** 这些行上两侧亮度曲线的较小标准差（0–255）；过低说明是均匀色块，无法确认连续性。 */
    val variation: Float,
    /** 两条亮度曲线的相关系数（-1–1）；1 = 完全连续。 */
    val correlation: Float,
    /** 去掉各自均值后的归一化平均差（0–1）；抵消折痕阴影 / 曝光差。 */
    val difference: Float,
)

/**
 * 计算中缝证据。
 *
 * 采样向量是"内缘列亮度随行号变化"的曲线：真对图被切开后两半在中缝处高度连续，曲线形状一致（相关 系数高、去掉均值后的平均差小）；两张互不相干的页各自把内容顶到中缝时，曲线形状不相关。
 */
internal fun spreadSeamEvidence(
    first: SpreadEdgeSample,
    second: SpreadEdgeSample,
    inkThreshold: Int = SPREAD_INK_THRESHOLD,
    minVariation: Float = SPREAD_SEAM_MIN_VARIATION,
): SpreadSeamEvidence {
    val rows = minOf(first.brightness.size, second.brightness.size)
    if (rows < MIN_SEAM_ROWS) return SpreadSeamEvidence(0f, 0f, 0f, 1f)
    var contentRows = 0
    var sumFirst = 0.0
    var sumSecond = 0.0
    val firstValues = ArrayList<Double>(rows)
    val secondValues = ArrayList<Double>(rows)
    for (i in 0 until rows) {
        val a = first.brightness[i]
        val b = second.brightness[i]
        if (a < inkThreshold && b < inkThreshold) {
            contentRows++
            sumFirst += a
            sumSecond += b
            firstValues.add(a.toDouble())
            secondValues.add(b.toDouble())
        }
    }
    if (contentRows < MIN_SEAM_ROWS) {
        return SpreadSeamEvidence(
            continuity = contentRows.toFloat() / rows,
            variation = 0f,
            correlation = 0f,
            difference = 1f,
        )
    }
    val meanFirst = sumFirst / contentRows
    val meanSecond = sumSecond / contentRows
    var varianceFirst = 0.0
    var varianceSecond = 0.0
    var covariance = 0.0
    var differenceSum = 0.0
    for (i in 0 until contentRows) {
        val a = firstValues[i] - meanFirst
        val b = secondValues[i] - meanSecond
        varianceFirst += a * a
        varianceSecond += b * b
        covariance += a * b
        differenceSum += abs(a - b)
    }
    val deviationFirst = sqrt(varianceFirst / contentRows).toFloat()
    val deviationSecond = sqrt(varianceSecond / contentRows).toFloat()
    val variation = minOf(deviationFirst, deviationSecond)
    val correlation =
        if (variation < minVariation || varianceFirst <= 0.0 || varianceSecond <= 0.0) {
            0f
        } else {
            (covariance / sqrt(varianceFirst * varianceSecond)).toFloat().coerceIn(-1f, 1f)
        }
    return SpreadSeamEvidence(
        continuity = contentRows.toFloat() / rows,
        variation = variation,
        correlation = correlation,
        difference = (differenceSum / contentRows / 255.0).toFloat().coerceIn(0f, 1f),
    )
}

/** 判定是否合并：四条门槛全过才拼（保守口径，宁可漏拼也不误拼）。 */
internal fun shouldMergeSpread(evidence: SpreadSeamEvidence): Boolean =
    evidence.continuity >= SPREAD_SEAM_MIN_CONTINUITY &&
        evidence.variation >= SPREAD_SEAM_MIN_VARIATION &&
        evidence.correlation >= SPREAD_SEAM_MIN_CORRELATION &&
        evidence.difference <= SPREAD_SEAM_MAX_DIFFERENCE

/** 合并决策：命中时给出拼合几何 + 两侧纸边裁剪（判定缩略图尺度的列数）。 */
internal data class SpreadMergeDecision(
    val geometry: SpreadMergeGeometry,
    val trimFirstPx: Int,
    val trimSecondPx: Int,
    val thumbWidthFirstPx: Int,
    val thumbWidthSecondPx: Int,
    val evidence: SpreadSeamEvidence,
) {
    /** 是否走了纸边裁剪路径（真机日志 / 单测用）。 */
    val trimmed: Boolean
        get() = trimFirstPx > 0 || trimSecondPx > 0

    /** 裁剪比例（裁剪列数 / 缩略图宽度），合成时映射到全分辨率位图。 */
    val trimFirstRatio: Float
        get() = if (thumbWidthFirstPx > 0) trimFirstPx / thumbWidthFirstPx.toFloat() else 0f

    val trimSecondRatio: Float
        get() = if (thumbWidthSecondPx > 0) trimSecondPx / thumbWidthSecondPx.toFloat() else 0f
}

/**
 * 中缝判定（两条路径，优先级固定）：
 * 1. **直接路径**（W22 标定）：内缘两侧直接对穿 → 原四门槛（无纸边对图的判定与 W22 完全一致）；
 * 2. **纸边裁剪路径**（W26）：内缘两侧是纸白 / 低信息带时，向内找到第一列内容（[paperTrimInset]）， 用内移后的采样带重算证据。该路径要求相关性 /
 *    平均差更严（[SPREAD_TRIM_MIN_CORRELATION] / [SPREAD_TRIM_MAX_DIFFERENCE]）， 且裁剪量不超过 [maxTrimFirstPx]
 *    / [maxTrimSecondPx] （调用方按 [SPREAD_MAX_EDGE_TRIM_RATIO] 计算）。
 *
 * 返回 null 表示不合并（保守口径：宁可漏拼也不误拼）。
 */
internal fun spreadMergeDecision(
    firstPixels: IntArray,
    firstWidthPx: Int,
    firstHeightPx: Int,
    firstEdge: SpreadEdge,
    secondPixels: IntArray,
    secondWidthPx: Int,
    secondHeightPx: Int,
    secondEdge: SpreadEdge,
    geometry: SpreadMergeGeometry,
    maxTrimFirstPx: Int = (firstWidthPx * SPREAD_MAX_EDGE_TRIM_RATIO).toInt().coerceAtLeast(1),
    maxTrimSecondPx: Int = (secondWidthPx * SPREAD_MAX_EDGE_TRIM_RATIO).toInt().coerceAtLeast(1),
): SpreadMergeDecision? {
    val firstBase = spreadEdgeSample(firstPixels, firstWidthPx, firstHeightPx, firstEdge)
    val secondBase = spreadEdgeSample(secondPixels, secondWidthPx, secondHeightPx, secondEdge)
    val baseEvidence = spreadSeamEvidence(firstBase, secondBase)
    if (shouldMergeSpread(baseEvidence)) {
        return SpreadMergeDecision(
            geometry = geometry,
            trimFirstPx = 0,
            trimSecondPx = 0,
            thumbWidthFirstPx = firstWidthPx,
            thumbWidthSecondPx = secondWidthPx,
            evidence = baseEvidence,
        )
    }
    if (maxTrimFirstPx <= 0 || maxTrimSecondPx <= 0) return null
    val firstStats =
        spreadEdgeColumnStats(firstPixels, firstWidthPx, firstHeightPx, firstEdge, maxTrimFirstPx)
            ?: return null
    val secondStats =
        spreadEdgeColumnStats(
            secondPixels,
            secondWidthPx,
            secondHeightPx,
            secondEdge,
            maxTrimSecondPx,
        ) ?: return null
    val firstTrim = paperTrimInset(firstStats) ?: return null
    val secondTrim = paperTrimInset(secondStats) ?: return null
    // 两侧都没有纸边：这条路径不适用（保持 W22 的「内缘有内容但不连续 → 不合并」）。
    if (firstTrim <= 0 && secondTrim <= 0) return null
    val firstTrimmed =
        spreadEdgeSample(
            firstPixels,
            firstWidthPx,
            firstHeightPx,
            firstEdge,
            insetPx = firstTrim,
        )
    val secondTrimmed =
        spreadEdgeSample(
            secondPixels,
            secondWidthPx,
            secondHeightPx,
            secondEdge,
            insetPx = secondTrim,
        )
    val evidence = spreadSeamEvidence(firstTrimmed, secondTrimmed)
    if (!shouldMergeSpread(evidence)) return null
    if (evidence.correlation < SPREAD_TRIM_MIN_CORRELATION) return null
    if (evidence.difference > SPREAD_TRIM_MAX_DIFFERENCE) return null
    return SpreadMergeDecision(
        geometry = geometry,
        trimFirstPx = firstTrim,
        trimSecondPx = secondTrim,
        thumbWidthFirstPx = firstWidthPx,
        thumbWidthSecondPx = secondWidthPx,
        evidence = evidence,
    )
}

/** 中缝比较所需的最少行数：太少（缩略图异常 / 边缘几乎全白）直接判定为不可合并。 */
private const val MIN_SEAM_ROWS = 8

/** ARGB → 亮度（BT.601 整数近似），不依赖 android.graphics，便于 JVM 单测。 */
private fun luminance(argb: Int): Int {
    val r = (argb shr 16) and 0xFF
    val g = (argb shr 8) and 0xFF
    val b = argb and 0xFF
    return (r * 77 + g * 151 + b * 28) shr 8
}
