package com.zhangwenkang.cinefin.book.presentation.reader

/**
 * RTL（右起翻页）页序层（EB-4）：把「逻辑页序」映射到一屏的视觉槽位（左 → 右）。
 *
 * 逻辑页序永远是 1, 2, 3…——进度（[progressionForPage]）、书签语义与页指示都按逻辑页走； RTL 只改变视觉呈现与翻页方向，不改写任何页号：
 * - LTR：spread k = 左 2k+1、右 2k+2；
 * - RTL：右 2k+1、左 2k+2（阅读顺序自右向左，与实体漫画一致）；
 * - 尾页单张时留空槽在另一侧（LTR 空右，RTL 空左）——RTL 尾页贴在右侧；
 * - 分页模式（`pagesPerSpread` = 1）两向结果完全一致；
 * - 滚动模式不走本函数：纵向连续阅读顺序与 RTL 无关。
 */
internal fun spreadPageSlots(
    spread: Int,
    pageCount: Int,
    pagesPerSpread: Int,
    rtl: Boolean,
): List<Int?> {
    if (pagesPerSpread <= 0) return emptyList()
    val slots = MutableList<Int?>(pagesPerSpread) { null }
    if (pageCount <= 0 || spread < 0) return slots
    val base = spread.toLong() * pagesPerSpread
    for (offset in 0 until pagesPerSpread) {
        val index = base + offset
        if (index < pageCount) slots[offset] = index.toInt()
    }
    return if (rtl) slots.asReversed() else slots
}

/** 一屏 `pagesPerSpread` 页时需要的 spread 总数（页数不能整除时最后一张单页独占一屏）。 */
internal fun spreadCount(pageCount: Int, pagesPerSpread: Int): Int {
    if (pageCount <= 0 || pagesPerSpread <= 0) return 0
    return (pageCount + pagesPerSpread - 1) / pagesPerSpread
}

/**
 * RTL 只对横向翻页（分页 / 双栏）生效。
 *
 * 滚动模式是纵向连续阅读，右起开关没有可翻转的横向轴，保持 1 → 2 → 3 自上而下（真机口径见 READER_PLAN §7.7）。
 */
internal fun isRtlPaging(mode: ReaderMode, rtl: Boolean): Boolean = rtl && mode != ReaderMode.Scroll
