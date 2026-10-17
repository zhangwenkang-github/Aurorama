package com.zhangwenkang.cinefin.player.core.domain.models

/**
 * 单条字幕轨的外观：字号百分比 / 文字颜色 / 背景 / 描边。
 *
 * 主、次字幕各持有一份，互不影响；底边位置与两条字幕的间距属于共享布局，见 [SubtitleLayout]。 字号直接存百分比（50~200，5% 步进），颜色直接存
 * ARGB，避免再维护一层「档位索引」映射。
 */
data class SubtitleStyle(
    /** 字号百分比：50~200，5% 步进；100 = 基准字号 */
    val sizePercent: Int = DEFAULT_SIZE_PERCENT,
    /** 文字颜色（ARGB） */
    val textColor: Int = DEFAULT_TEXT_COLOR,
    /** 背景底色（只看 RGB；透明度由 [backgroundOpacityPercent] 决定） */
    val backgroundColor: Int = DEFAULT_BACKGROUND_COLOR,
    /** 背景不透明度：0~100%；0 = 无背景 */
    val backgroundOpacityPercent: Int = DEFAULT_BACKGROUND_OPACITY_PERCENT,
    /** 描边宽度（dp）：0~4；描边色固定黑色 */
    val edgeWidthDp: Int = DEFAULT_EDGE_WIDTH_DP,
) {
    /** 字号倍率（渲染层用） */
    val textScale: Float
        get() = sizePercent.coerceIn(MIN_SIZE_PERCENT, MAX_SIZE_PERCENT) / 100f

    val backgroundAlpha: Int
        get() =
            (backgroundOpacityPercent.coerceIn(
                MIN_BACKGROUND_OPACITY_PERCENT,
                MAX_BACKGROUND_OPACITY_PERCENT,
            ) * 255 + 50) / 100

    val hasBackground: Boolean
        get() = backgroundAlpha > 0

    /** 实际用于绘制的背景 ARGB（含透明度；无背景时为 0） */
    val backgroundArgb: Int
        get() =
            if (!hasBackground) 0 else (backgroundAlpha shl 24) or (backgroundColor and 0x00FFFFFF)

    companion object {
        const val MIN_SIZE_PERCENT = 50
        const val MAX_SIZE_PERCENT = 200
        const val SIZE_STEP_PERCENT = 5
        const val DEFAULT_SIZE_PERCENT = 100
        /** 次字幕默认比主字幕小一档，保留旧版 0.9 倍观感 */
        const val SECONDARY_DEFAULT_SIZE_PERCENT = 90

        const val DEFAULT_TEXT_COLOR = 0xFFFFFFFF.toInt()
        const val DEFAULT_BACKGROUND_COLOR = 0xFF000000.toInt()
        const val MIN_BACKGROUND_OPACITY_PERCENT = 0
        const val MAX_BACKGROUND_OPACITY_PERCENT = 100
        const val DEFAULT_BACKGROUND_OPACITY_PERCENT = 0
        /** 从「无背景」切到某个底色时给一个能看见又不压画面的默认不透明度 */
        const val BACKGROUND_OPACITY_WHEN_ENABLED = 60

        const val MIN_EDGE_WIDTH_DP = 0
        const val MAX_EDGE_WIDTH_DP = 4
        const val DEFAULT_EDGE_WIDTH_DP = 2

        /** 文字颜色预设：白 / 暖黄 / 青 / 绿 / 橙 / 粉 / 浅蓝 / 浅紫 */
        val COLORS =
            listOf(
                0xFFFFFFFF.toInt(),
                0xFFFFE082.toInt(),
                0xFF80DEEA.toInt(),
                0xFFA5D6A7.toInt(),
                0xFFFFAB91.toInt(),
                0xFFF48FB1.toInt(),
                0xFF90CAF9.toInt(),
                0xFFCE93D8.toInt(),
            )

        /** 「自定义」展开后的简单取色器（补充色相 / 灰阶） */
        val CUSTOM_COLORS =
            listOf(
                0xFFFFD54F.toInt(),
                0xFFFFF59D.toInt(),
                0xFF80CBC4.toInt(),
                0xFF9FA8DA.toInt(),
                0xFFE57373.toInt(),
                0xFFBCAAA4.toInt(),
                0xFFB0BEC5.toInt(),
                0xFF000000.toInt(),
            )

        /** 背景底色：黑 / 深灰 / 白；「无」= 不透明度 0 */
        val BACKGROUND_COLORS =
            listOf(
                0xFF000000.toInt(),
                0xFF333333.toInt(),
                0xFFFFFFFF.toInt(),
            )

        /** 把任意百分比吸附到 5% 步进并夹到合法区间 */
        fun snapSizePercent(percent: Int): Int =
            ((percent.coerceIn(MIN_SIZE_PERCENT, MAX_SIZE_PERCENT) + SIZE_STEP_PERCENT / 2) /
                SIZE_STEP_PERCENT) * SIZE_STEP_PERCENT
    }
}

/**
 * 主/次字幕共享的布局：两条字幕的可见间距与整块的底部位置。
 *
 * 底边位置按画面高度百分比存（0~30，1% 步进），0 = 贴底；不提供主/次各自的底边距， 两条字幕始终按同一垂直堆叠排布，避免重新产生重叠。
 */
data class SubtitleLayout(
    /** 主/次字幕可见边界之间的间距（dp）：0~24，1dp 步进 */
    val gapDp: Int = DEFAULT_GAP_DP,
    /** 字幕块底部距画面底部的比例（%）：0~30，1% 步进 */
    val bottomPercent: Int = DEFAULT_BOTTOM_PERCENT,
) {
    val bottomFraction: Float
        get() = bottomPercent.coerceIn(MIN_BOTTOM_PERCENT, MAX_BOTTOM_PERCENT) / 100f

    companion object {
        const val MIN_GAP_DP = 0
        const val MAX_GAP_DP = 24
        const val DEFAULT_GAP_DP = 4

        const val MIN_BOTTOM_PERCENT = 0
        const val MAX_BOTTOM_PERCENT = 30
        const val DEFAULT_BOTTOM_PERCENT = 8
    }
}

/** 播放器字幕外观的完整快照：主字幕、次字幕、共享布局。 */
data class SubtitleAppearance(
    val primary: SubtitleStyle = SubtitleStyle(),
    val secondary: SubtitleStyle =
        SubtitleStyle(sizePercent = SubtitleStyle.SECONDARY_DEFAULT_SIZE_PERCENT),
    val layout: SubtitleLayout = SubtitleLayout(),
) {
    companion object {
        val Default = SubtitleAppearance()

        /** 标准：主 100% 白/2dp、次 90% 白/2dp、无背景、间距 4dp、底部 8% */
        val Standard = Default

        /** 影院：主暖黄 / 次浅青、轻黑背景、间距 6dp、底部 6% */
        val Cinema =
            SubtitleAppearance(
                primary =
                    SubtitleStyle(
                        sizePercent = 100,
                        textColor = 0xFFFFE082.toInt(),
                        backgroundColor = 0xFF000000.toInt(),
                        backgroundOpacityPercent = 40,
                        edgeWidthDp = 2,
                    ),
                secondary =
                    SubtitleStyle(
                        sizePercent = 90,
                        textColor = 0xFF80DEEA.toInt(),
                        backgroundColor = 0xFF000000.toInt(),
                        backgroundOpacityPercent = 40,
                        edgeWidthDp = 2,
                    ),
                layout = SubtitleLayout(gapDp = 6, bottomPercent = 6),
            )

        /** 贴底：保持当前两条字幕样式，间距恢复 4dp、底部 0% */
        fun stickToBottom(current: SubtitleAppearance): SubtitleAppearance =
            current.copy(
                layout =
                    current.layout.copy(
                        gapDp = SubtitleLayout.DEFAULT_GAP_DP,
                        bottomPercent = SubtitleLayout.MIN_BOTTOM_PERCENT,
                    )
            )
    }
}
