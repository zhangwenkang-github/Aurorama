package com.zhangwenkang.cinefin.book.presentation.reader

import timber.log.Timber

/**
 * 双栏「横版整页独占」版式层（W26，EB-4 延伸）：把逻辑页序划分成双栏槽位（[SpreadSlot]）。
 *
 * 背景（READER_PLAN §7.9 / §9 遗留①）：金田一原画这类素材每页本身就是一幅横版整页（对开图）， 双栏下若仍按「相邻两页一屏」会把两幅整页并排成一屏 4 页。本层把宽高比 ≥
 * [LANDSCAPE_FULL_PAGE_MIN_ASPECT] 的横版整页单独放进一个槽位（整屏独占），其余竖版页维持 两页并排与 [SpreadImageCache] 的对图拼合。
 *
 * 与 [SpreadMerge] 的优先级（判定顺序固定为「先版式、后合并」）：
 * 1. 版式层先执行：横版整页 → 独占槽（1 页），不参与任何配对；
 * 2. 合并层只在「两页槽」内判定；两页槽里的页一定不是横版整页（本层不变量，单测锁定）， 几何门槛仍要求两个半页是竖版（0.55–0.98）；
 * 3. 页号 / progression 语义不变：槽位首页仍是进度锚点（[progressionForPage] 按逻辑页索引换算）； 独占页翻页步进 = 1 页，普通槽 = 2 页。
 *
 * RTL：槽位划分只依赖逻辑页序，与 RTL 无关；RTL 只改变槽位内部的左右相位 （[visualSlotPages]，与 W9 `spreadPageSlots` 同语义）。
 */

/**
 * 「横版整页」判定阈值（宽 / 高）。
 *
 * 取值依据（READER_PLAN §7.9 / D20 标定）：金田一原画横版整页 1440×1012 ≈ 1.423、竖版半页 1031×1440 ≈ 0.716；1.15
 * 与对开比例下限共用，保证「可合并对图」（两半并排 ≥1.15）与「横版独占」在边界 上口径一致。0.98–1.15 的近似方形页维持 W22 行为（配对渲染、不参与合并）。
 */
internal const val LANDSCAPE_FULL_PAGE_MIN_ASPECT: Float = 1.15f

/** 一个双栏槽位：[pages] 是槽位内按逻辑阅读顺序排列的页索引（1–2 页）， [fullscreen] 表示单页需要整屏独占（横版整页 / 分页模式的单页）。 */
internal data class SpreadSlot(val pages: List<Int>, val fullscreen: Boolean)

/** 单页是否按「横版整页」独占一屏（尺寸缺失 / 非有限值按否处理，保持 W22 配对行为）。 */
internal fun isLandscapeFullPage(aspectRatio: Float?): Boolean =
    aspectRatio != null && aspectRatio.isFinite() && aspectRatio >= LANDSCAPE_FULL_PAGE_MIN_ASPECT

/**
 * 双栏槽位划分：横版整页独占，其余竖版页两两成组（尾页 / 被横版页隔断的一页单独成槽）。
 *
 * 传入空或全 null 的 [pageAspects] 时结果与 W22 的「每屏固定两页」（`spreadCount` / `spreadPageSlots`）
 * 完全一致——扫描完成前的占位版式就是旧行为。
 */
internal fun twoColumnSlots(pageAspects: List<Float?>, pageCount: Int): List<SpreadSlot> {
    if (pageCount <= 0) return emptyList()
    val slots = ArrayList<SpreadSlot>((pageCount + 1) / 2)
    var index = 0
    while (index < pageCount) {
        when {
            isLandscapeFullPage(pageAspects.getOrNull(index)) -> {
                slots += SpreadSlot(pages = listOf(index), fullscreen = true)
                index += 1
            }

            index + 1 < pageCount && !isLandscapeFullPage(pageAspects.getOrNull(index + 1)) -> {
                slots += SpreadSlot(pages = listOf(index, index + 1), fullscreen = false)
                index += 2
            }

            else -> {
                slots += SpreadSlot(pages = listOf(index), fullscreen = false)
                index += 1
            }
        }
    }
    return slots
}

/** 分页模式槽位：每页独占一屏（不扫描尺寸，行为与 W4 完全一致）。 */
internal fun pagedSlots(pageCount: Int): List<SpreadSlot> =
    if (pageCount <= 0) emptyList()
    else List(pageCount) { index -> SpreadSlot(pages = listOf(index), fullscreen = true) }

/** 逻辑页 → 槽位索引（恢复位置 / 版式重排后重建 Pager 用）；空表或越界返回安全值。 */
internal fun slotIndexForPage(slots: List<SpreadSlot>, page: Int): Int {
    if (slots.isEmpty()) return 0
    for (index in slots.indices) {
        val slot = slots[index]
        if (page < slot.pages.first()) return (index - 1).coerceAtLeast(0)
        if (page <= slot.pages.last()) return index
    }
    return slots.lastIndex
}

/**
 * 槽位在屏幕上的视觉页序列（左 → 右；null = 空半格），视觉顺序与 W9 完全一致：
 * - 两页槽：LTR 先读在左、RTL 先读在右；
 * - 竖版单张（尾页 / 被横版页隔断）：LTR 贴左半格、RTL 贴右半格；
 * - 横版独占：单页整屏（调用方按 [SpreadSlot.fullscreen] 用 fillMaxSize 渲染，不走 Row）。
 */
internal fun visualSlotPages(slot: SpreadSlot, rtl: Boolean): List<Int?> =
    when {
        slot.fullscreen -> listOf(slot.pages.first())
        slot.pages.size >= 2 -> if (rtl) slot.pages.reversed() else slot.pages
        rtl -> listOf(null, slot.pages.first())
        else -> listOf(slot.pages.first(), null)
    }

/**
 * 全书宽高比读取（异常 / 取不到 → null）；由双栏版式在后台线程调用一次，结果缓存于会话内。
 *
 * 优先走数据源的批量元数据路径（PDF = PdfBox 只读页树，W33）；不可用时才回退逐页 [PageSource.pageAspectRatio]（CBZ 的
 * `inJustDecodeBounds`，或 PDF 元数据读取失败时的兜底）。
 */
internal suspend fun PageSource.collectPageAspectRatios(): List<Float?> {
    val count = pageCount
    if (count <= 0) return emptyList()
    pageAspectRatios()?.let { batch -> if (batch.size == count) return batch }
    if (!allowPerPageLayoutScan(count, perPageAspectScanMaxPages)) {
        Timber.w(
            "reader spread layout skip-fallback pages=%d max=%d",
            count,
            perPageAspectScanMaxPages,
        )
        return List(count) { null }
    }
    return List(count) { index -> runCatching { pageAspectRatio(index) }.getOrNull() }
}

/**
 * 逐页版式扫描回退的页数上限（W48 / D-W47-1）。
 *
 * PDF 的逐页回退走 `PdfRenderer.openPage`，其 native 内存不随 `Page.close()` 回收（W33 踩坑 29：3649 页 抬升 ≈690 MB；W47
 * 金田一 5006 页抬到 1.59 GB 后进程被杀）。批量元数据路径不可用（fd 不可 seek / PdfBox 解析失败）时，页数超此上限直接采用安全默认（全 null =
 * 竖版两页一屏，即 W26 之前的配对口径），牺牲横版独占的 正确性换取不发生 native 爆增；小书仍逐页回退保正确性。
 *
 * 取值 1500：按实测 0.19–0.31 MB/页，1500 页最坏 ≈0.3–0.5 GB 峰值，已接近杀进程线，不再批准更大的逐页扫描。
 */
internal const val PER_PAGE_LAYOUT_SCAN_MAX_PAGES: Int = 1500

/** 是否允许逐页回退扫描（纯函数，单测锁定阈值边界）。 */
internal fun allowPerPageLayoutScan(
    pageCount: Int,
    maxPages: Int = PER_PAGE_LAYOUT_SCAN_MAX_PAGES,
): Boolean = pageCount <= maxPages
