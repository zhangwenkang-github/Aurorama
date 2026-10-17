package com.zhangwenkang.cinefin.presentation.player

/**
 * 主/次字幕「主上、次下」的统一几何规则（纯函数，便于单测）。
 *
 * Exo 与 mpv 双字幕期间的主字幕都由 libass 画布渲染，次字幕由 Compose 文本渲染；两个内核必须遵守同一约束：
 *
 * 1. 主字幕在上、次字幕在下，两者**可见边界**之间保留 4dp 间隙；
 * 2. 先按次字幕当前字号排布；空间不足时只把次字幕缩到自身字号的 80%；
 * 3. 80% 仍放不下就隐藏次字幕；主字幕不缩小、不裁切、不被覆盖；
 * 4. 次字幕没有完成测量前不绘制，避免首帧闪叠。
 *
 * 关键口径：`SubtitleLine` 的块高包含外层透明留白（3dp×2）和背景框内留白（4dp×2），这些都不是文字的可见边界； 描边还会向字形外扩出 1
 * 份宽度（不占布局高度）。排布必须先把它们算清，否则「4dp」会被放大成十几 dp （平板反馈的间距过大），或在不同字号 / 设备上变成负数（手机反馈的重叠）。
 */

/** [SubtitleLine] 最外层的透明留白（上下各 3dp）：位于背景框之外，任何档位下都不可见。 */
internal const val SECONDARY_LINE_OUTER_PADDING_DP = 3f

/** 背景框内部文字周围的留白（上下各 4dp）：背景选「无」时这部分同样不可见。 */
internal const val SECONDARY_LINE_INNER_PADDING_DP = 4f

/** 次字幕块的固定垂直内边距合计（dp）：外层 3×2 + 内层 4×2。 */
internal const val SECONDARY_LINE_VERTICAL_PADDING_DP =
    (SECONDARY_LINE_OUTER_PADDING_DP + SECONDARY_LINE_INNER_PADDING_DP) * 2f

/** 主/次字幕可见边界之间的间隙（dp） */
internal const val SUBTITLE_LINE_GAP_DP = 4f

/** 次字幕在空间不足时允许缩到的最小比例（相对当前字号）。 */
internal const val SECONDARY_MIN_SCALE = 0.8f

/**
 * 次字幕文本块整体高度（px）＝ Text 实测布局高 + 固定垂直内边距（px）。
 *
 * 与 `SubtitleLine` 的真实布局一致：描边由 Canvas 绘制，不增加 Compose 的测量高度。
 *
 * [textLayoutHeightPx] = Text.onTextLayout 拿到的文本高度（含多行 / 换行，px）。 [verticalPaddingPx] =
 * [SECONDARY_LINE_VERTICAL_PADDING_DP] 经 density 换算后的 px。
 */
internal fun secondaryBlockHeightPx(
    textLayoutHeightPx: Float,
    verticalPaddingPx: Float,
): Float = (textLayoutHeightPx + verticalPaddingPx).coerceAtLeast(0f)

/**
 * 文本块顶边 → 实际可见内容顶边的距离（px）。
 *
 * 分层顺序是「外层透明留白 → 背景框 → 内层留白 → 描边文字」：
 * - 外层留白永远透明，必须扣除；
 * - 有背景时背景框本身就是可见边界，只扣外层留白；
 * - 背景为「无」时内层留白也透明；但描边以字形路径为中心向两侧扩展，块顶一侧只外扩 [strokePx] / 2， 让可见内容比内层留白更靠近块边，所以是「外层 + 内层 − 描边 / 2」。
 *
 * 注意：块高来自 Compose 的排版盒（行高 1.25），不是字形墨迹的像素包围盒；这里保证的是布局可见边界， 真实字形 / 描边墨迹的间隙仍以真机视觉验收为准。
 */
internal fun subtitleVisibleTopInsetPx(
    outerPaddingPx: Float,
    innerPaddingPx: Float,
    strokePx: Float,
    backgroundVisible: Boolean,
): Float {
    val outer = outerPaddingPx.takeIf { it.isFinite() }?.coerceAtLeast(0f) ?: 0f
    val inner =
        if (backgroundVisible) {
            0f
        } else {
            innerPaddingPx.takeIf { it.isFinite() }?.coerceAtLeast(0f) ?: 0f
        }
    val stroke = strokePx.takeIf { it.isFinite() }?.coerceAtLeast(0f) ?: 0f
    return if (backgroundVisible) outer else (outer + inner - stroke / 2f).coerceAtLeast(0f)
}

/** 文本块的可见高度（px）＝ 块高 − 上下两份可见内缩。 */
internal fun subtitleVisibleHeightPx(
    blockHeightPx: Float,
    visibleTopInsetPx: Float,
): Float = (blockHeightPx - 2f * visibleTopInsetPx.coerceAtLeast(0f)).coerceAtLeast(0f)

/**
 * 当前主字幕的几何范围；用于把 libass 图元、Compose 文本或 mpv 原生字幕统一成同一套排布输入。
 *
 * - [Libass]：给出本帧所有图元的实际上下边界（相对视频显示区）；
 * - [Composed]：主字幕也由 Compose 文本渲染，给出**可见**高度与可见底边到块底的距离；
 * - [Native]：主字幕由 mpv 原生渲染（图形字幕兜底），App 只能按保守块高预留空间。
 */
internal sealed interface PrimarySubtitleExtent {
    data class Libass(val topPx: Float, val bottomPx: Float) : PrimarySubtitleExtent {
        val heightPx: Float
            get() = (bottomPx - topPx).coerceAtLeast(0f)
    }

    data class Composed(val heightPx: Float, val visibleBottomInsetPx: Float) :
        PrimarySubtitleExtent

    data class Native(val heightPx: Float) : PrimarySubtitleExtent
}

/**
 * 一次双字幕排布结果。
 *
 * [primaryLiftPx] 只对 [PrimarySubtitleExtent.Libass] 有意义：libass 画布整体上移量。 [primaryOffsetPx] 只对
 * [PrimarySubtitleExtent.Composed] 有意义：主字幕块向下偏移量，用来抵掉两块各自的透明留白， 使两条 Compose 字幕的可见间距正好 4dp（偏移只落在透明
 * padding 里，不会压住文字 / 背景框）。 [secondaryReservedFraction] 只对 mpv
 * 原生主字幕兜底（图形字幕）有意义：需要从原生字幕底部位置里预留的视频高度比例。
 */
internal data class DualSubtitleLayout(
    val showSecondary: Boolean,
    val secondaryScale: Float,
    val secondaryHeightPx: Float,
    val primaryLiftPx: Float,
    val primaryOffsetPx: Float,
    val secondaryReservedFraction: Float,
) {
    companion object {
        val Hidden =
            DualSubtitleLayout(
                showSecondary = false,
                secondaryScale = 1f,
                secondaryHeightPx = 0f,
                primaryLiftPx = 0f,
                primaryOffsetPx = 0f,
                secondaryReservedFraction = 0f,
            )
    }
}

/**
 * 计算主/次字幕的最终排布。
 *
 * 输入均为视频显示区内的像素坐标。[secondaryFullHeightPx] / [secondaryMinHeightPx] 是 TextMeasurer 量到的
 * **块高**；[secondaryVisibleTopInsetPx] / [secondaryVisibleBottomInsetPx] 是块顶 / 块底到可见边界的距离。 先尝试次字幕
 * 100%，放不下再尝试 [SECONDARY_MIN_SCALE]；两种比例都放不下时返回 [DualSubtitleLayout.Hidden]。 主字幕永远不缩小；libass
 * 主字幕的上移如果会让顶部越过安全区，同样视为放不下，避免「为了保住次字幕而裁掉主字幕」。
 *
 * 主字幕已经在次字幕上方更远处时只保证「至少 gap」，不会把作者的固定位置往下拉；需要避让时才精确落到 gap。
 */
internal fun planDualSubtitleLayout(
    videoHeightPx: Float,
    bottomInsetPx: Float,
    gapPx: Float,
    primary: PrimarySubtitleExtent?,
    secondaryFullHeightPx: Float,
    secondaryMinHeightPx: Float,
    secondaryVisibleTopInsetPx: Float,
    secondaryVisibleBottomInsetPx: Float,
): DualSubtitleLayout {
    if (!videoHeightPx.isFinite() || videoHeightPx <= 0f) return DualSubtitleLayout.Hidden
    if (!secondaryFullHeightPx.isFinite() || secondaryFullHeightPx <= 0f) {
        return DualSubtitleLayout.Hidden
    }

    val safeBottom = (videoHeightPx - bottomInsetPx.coerceAtLeast(0f)).coerceIn(0f, videoHeightPx)
    val safeGap = gapPx.takeIf { it.isFinite() }?.coerceAtLeast(0f) ?: 0f
    val topInset = secondaryVisibleTopInsetPx.takeIf { it.isFinite() }?.coerceAtLeast(0f) ?: 0f
    val bottomInset =
        secondaryVisibleBottomInsetPx.takeIf { it.isFinite() && it >= 0f }?.coerceAtLeast(0f)
            ?: topInset
    val minHeight =
        secondaryMinHeightPx
            .takeIf { it.isFinite() && it > 0f }
            ?.coerceAtMost(secondaryFullHeightPx) ?: secondaryFullHeightPx

    val candidates = buildList {
        add(1f to secondaryFullHeightPx)
        if (minHeight < secondaryFullHeightPx) add(SECONDARY_MIN_SCALE to minHeight)
    }

    for ((scale, height) in candidates) {
        val blockTop = safeBottom - height
        val visibleTop = blockTop + topInset
        val visibleHeight = height - topInset - bottomInset
        if (visibleHeight <= 0f || visibleTop < 0f) continue

        when (primary) {
            null ->
                return layout(
                    scale = scale,
                    height = height,
                    lift = 0f,
                    offset = 0f,
                    reservedFraction = 0f,
                )

            is PrimarySubtitleExtent.Libass -> {
                // 主字幕可见底边停在次字幕可见顶边上方 gapPx；只上移，不把作者已放高的字幕往下拉。
                val primaryLimit = visibleTop - safeGap
                val lift = (primary.bottomPx - primaryLimit).coerceAtLeast(0f)
                val liftedTop = primary.topPx - lift
                if (liftedTop >= 0f) {
                    return layout(
                        scale = scale,
                        height = height,
                        lift = lift,
                        offset = 0f,
                        reservedFraction = 0f,
                    )
                }
            }

            is PrimarySubtitleExtent.Composed -> {
                // 两条 Compose 行自下而上堆叠：次字幕块底 = safeBottom，主字幕块底 = 次字幕块顶。
                // 两块各自的透明留白已经贡献了一部分视觉间距，把主字幕块整体下移差额（只压透明 padding）。
                val primaryBottomInset = primary.visibleBottomInsetPx.coerceAtLeast(0f)
                val offset = (topInset + primaryBottomInset - safeGap).coerceAtLeast(0f)
                val primaryBlockBottom = blockTop + offset
                val primaryVisibleTop = primaryBlockBottom - primaryBottomInset - primary.heightPx
                if (primaryVisibleTop >= 0f) {
                    return layout(
                        scale = scale,
                        height = height,
                        lift = 0f,
                        offset = offset,
                        reservedFraction = 0f,
                    )
                }
            }

            is PrimarySubtitleExtent.Native -> {
                // 原生字幕的可见边界不可观测，只能保证「预留区覆盖次字幕可见顶边 + gap」。
                val requiredHeight = (height - topInset + safeGap).coerceAtLeast(0f)
                if (primary.heightPx + safeGap <= visibleTop) {
                    return layout(
                        scale = scale,
                        height = height,
                        lift = 0f,
                        offset = 0f,
                        reservedFraction = requiredHeight / videoHeightPx,
                    )
                }
            }
        }
    }

    return DualSubtitleLayout.Hidden
}

private fun layout(
    scale: Float,
    height: Float,
    lift: Float,
    offset: Float,
    reservedFraction: Float,
): DualSubtitleLayout =
    DualSubtitleLayout(
        showSecondary = true,
        secondaryScale = scale,
        secondaryHeightPx = height,
        primaryLiftPx = lift,
        primaryOffsetPx = offset,
        secondaryReservedFraction = reservedFraction.coerceIn(0f, 1f),
    )
