package com.zhangwenkang.cinefin.presentation.player

import kotlin.math.roundToInt

/**
 * W74 #20：主/次字幕「上下两行」（主上、次下）的几何计算（纯函数，便于单测）。
 *
 * 背景：Exo 自研管线里主字幕由 libass 画布渲染（贴底），次字幕由 Compose 文本渲染（贴底）， 两者底边对齐 → 视觉叠加。修法 = 次字幕保持贴底，libass
 * 画布整体上移「次字幕块高度 + 间隙」， 使主字幕落到次字幕上方；无次字幕时上移量为 0（单字幕零回归）。
 */

/** 次字幕行的固定垂直内边距（dp）：[SubtitleLine] 外层 padding(vertical=3) + 内层 padding(vertical=4) 上下合计 */
internal const val SECONDARY_LINE_VERTICAL_PADDING_DP = 14f

/** 主/次字幕之间的间隙（dp） */
internal const val SUBTITLE_LINE_GAP_DP = 4f

/**
 * 次字幕文本块整体高度（px）＝ Text 实测布局高 + 固定垂直内边距（px）。
 *
 * [textLayoutHeightPx] = Text.onTextLayout 拿到的文本高度（含多行 / 换行，px）。 [verticalPaddingPx] =
 * [SECONDARY_LINE_VERTICAL_PADDING_DP] 经 density 换算后的 px。
 */
internal fun secondaryBlockHeightPx(
    textLayoutHeightPx: Float,
    verticalPaddingPx: Float,
): Float = (textLayoutHeightPx + verticalPaddingPx).coerceAtLeast(0f)

/**
 * libass 主字幕画布的上移量（px）。
 *
 * 主/次字幕都贴底：主字幕挪到次字幕上方需上移 [secondaryBlockHeightPx] + [gapPx]。 [topmostImageY] = 本帧 libass 最上方图元的
 * y（相对渲染区；null = 无图元）。上移后不能让 主字幕越过渲染区顶部，因此 clamp 到 [0, topmostImageY]。
 */
internal fun libassLiftPx(
    secondaryBlockHeightPx: Float,
    gapPx: Float,
    topmostImageY: Int?,
): Int {
    val raw = (secondaryBlockHeightPx + gapPx).roundToInt().coerceAtLeast(0)
    if (topmostImageY == null) return raw
    return raw.coerceAtMost(topmostImageY.coerceAtLeast(0))
}
