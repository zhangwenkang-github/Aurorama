package com.zhangwenkang.cinefin.presentation.player

/**
 * 主/次字幕「主上、次下」的统一几何规则（纯函数，便于单测）。
 *
 * Exo 的主字幕由 libass 画布渲染，次字幕由 Compose 文本渲染；mpv 的主字幕由内核原生渲染，次字幕仍由 Compose 渲染。两个内核都必须遵守同一约束：
 *
 * 1. 主字幕在上、次字幕在下，任意时刻至少保留 4dp 间隙；
 * 2. 先按次字幕当前字号排布；空间不足时只把次字幕缩到自身字号的 80%；
 * 3. 80% 仍放不下就隐藏次字幕；主字幕不缩小、不裁切、不被覆盖；
 * 4. 次字幕没有完成测量前不绘制，避免首帧闪叠。
 */

/** 次字幕行的固定垂直内边距（dp）：[SubtitleLine] 外层 padding(vertical=3) + 内层 padding(vertical=4) 上下合计 */
internal const val SECONDARY_LINE_VERTICAL_PADDING_DP = 14f

/** 主/次字幕之间的间隙（dp） */
internal const val SUBTITLE_LINE_GAP_DP = 4f

/** 次字幕在空间不足时允许缩到的最小比例（相对当前字号）。 */
internal const val SECONDARY_MIN_SCALE = 0.8f

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
 * 当前主字幕的几何范围；用于把 libass 图元、Compose 文本或 mpv 原生字幕统一成同一套排布输入。
 *
 * - [Libass]：给出本帧所有图元的实际上下边界（相对视频显示区）；
 * - [Composed]：主字幕也由 Compose 文本渲染，只需要块高；
 * - [Native]：主字幕由 mpv 原生渲染，App 只能按保守块高预留空间。
 */
internal sealed interface PrimarySubtitleExtent {
    data class Libass(val topPx: Float, val bottomPx: Float) : PrimarySubtitleExtent {
        val heightPx: Float
            get() = (bottomPx - topPx).coerceAtLeast(0f)
    }

    data class Composed(val heightPx: Float) : PrimarySubtitleExtent

    data class Native(val heightPx: Float) : PrimarySubtitleExtent
}

/**
 * 一次双字幕排布结果。
 *
 * [primaryLiftPx] 只对 [PrimarySubtitleExtent.Libass] 有意义：libass 画布整体上移量。[secondaryReservedFraction]
 * 只对 mpv 原生主字幕有意义： 需要从原生字幕底部位置里预留的视频高度比例，换算成 `sub-pos` 的互补值。
 */
internal data class DualSubtitleLayout(
    val showSecondary: Boolean,
    val secondaryScale: Float,
    val secondaryHeightPx: Float,
    val primaryLiftPx: Float,
    val secondaryReservedFraction: Float,
) {
    companion object {
        val Hidden =
            DualSubtitleLayout(
                showSecondary = false,
                secondaryScale = 1f,
                secondaryHeightPx = 0f,
                primaryLiftPx = 0f,
                secondaryReservedFraction = 0f,
            )
    }
}

/**
 * 计算主/次字幕的最终排布。
 *
 * 输入均为视频显示区内的像素坐标。先尝试次字幕 100%，放不下再尝试 [SECONDARY_MIN_SCALE]；两种比例都放不下时返回 [DualSubtitleLayout.Hidden]。
 * 主字幕永远不缩小；libass 主字幕的上移如果会让顶部越过安全区，同样视为放不下，避免「为了保住次字幕而裁掉主字幕」。
 */
internal fun planDualSubtitleLayout(
    videoHeightPx: Float,
    bottomInsetPx: Float,
    gapPx: Float,
    primary: PrimarySubtitleExtent?,
    secondaryFullHeightPx: Float,
    secondaryMinHeightPx: Float,
): DualSubtitleLayout {
    if (!videoHeightPx.isFinite() || videoHeightPx <= 0f) return DualSubtitleLayout.Hidden
    if (!secondaryFullHeightPx.isFinite() || secondaryFullHeightPx <= 0f) {
        return DualSubtitleLayout.Hidden
    }

    val safeBottom = (videoHeightPx - bottomInsetPx.coerceAtLeast(0f)).coerceIn(0f, videoHeightPx)
    val safeGap = gapPx.takeIf { it.isFinite() }?.coerceAtLeast(0f) ?: 0f
    val minHeight =
        secondaryMinHeightPx
            .takeIf { it.isFinite() && it > 0f }
            ?.coerceAtMost(secondaryFullHeightPx) ?: secondaryFullHeightPx

    val candidates = buildList {
        add(1f to secondaryFullHeightPx)
        if (minHeight < secondaryFullHeightPx) add(SECONDARY_MIN_SCALE to minHeight)
    }

    for ((scale, height) in candidates) {
        val secondaryTop = safeBottom - height
        if (secondaryTop < 0f) continue

        when (primary) {
            null ->
                return layout(
                    scale = scale,
                    height = height,
                    lift = 0f,
                    reservedFraction = 0f,
                )

            is PrimarySubtitleExtent.Libass -> {
                val primaryLimit = secondaryTop - safeGap
                val lift = (primary.bottomPx - primaryLimit).coerceAtLeast(0f)
                val liftedTop = primary.topPx - lift
                if (liftedTop >= 0f && primary.bottomPx - lift <= primaryLimit + 0.01f) {
                    return layout(
                        scale = scale,
                        height = height,
                        lift = lift,
                        reservedFraction = 0f,
                    )
                }
            }

            is PrimarySubtitleExtent.Composed -> {
                if (primary.heightPx + safeGap <= secondaryTop) {
                    return layout(
                        scale = scale,
                        height = height,
                        lift = 0f,
                        reservedFraction = 0f,
                    )
                }
            }

            is PrimarySubtitleExtent.Native -> {
                if (primary.heightPx + safeGap <= secondaryTop) {
                    return layout(
                        scale = scale,
                        height = height,
                        lift = 0f,
                        reservedFraction = (height + safeGap) / videoHeightPx,
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
    reservedFraction: Float,
): DualSubtitleLayout =
    DualSubtitleLayout(
        showSecondary = true,
        secondaryScale = scale,
        secondaryHeightPx = height,
        primaryLiftPx = lift,
        secondaryReservedFraction = reservedFraction.coerceIn(0f, 1f),
    )
