package com.zhangwenkang.cinefin.player.core.domain.models

/**
 * 字幕外观（大小 / 颜色 / 背景 / 描边 / 位置）。
 *
 * 存的是「档位索引」而不是浮点值：偏好里只存 Int，跨版本不会因为浮点精度出现 「明明选的是同一档，UI 却对不上」的尴尬；真正的数值由 [SIZES] / [COLORS] 等换算。
 */
data class SubtitleStyle(
    /** 字号档位：见 [SIZES] */
    val sizeIndex: Int = SIZE_DEFAULT_INDEX,
    /** 文字颜色档位：见 [COLORS] */
    val colorIndex: Int = COLOR_DEFAULT_INDEX,
    /** 背景档位：见 [BACKGROUNDS] */
    val backgroundIndex: Int = BACKGROUND_DEFAULT_INDEX,
    /** 描边档位：见 [EDGE_WIDTHS]（0dp = 无描边） */
    val edgeIndex: Int = EDGE_DEFAULT_INDEX,
    /** 位置档位：见 [BOTTOM_FRACTIONS]（距画面底部占画面高度的比例） */
    val positionIndex: Int = POSITION_DEFAULT_INDEX,
) {
    val textScale: Float
        get() = SIZES[sizeIndex.coerceIn(SIZES.indices)]

    val textColor: Int
        get() = COLORS[colorIndex.coerceIn(COLORS.indices)]

    val backgroundColor: Int
        get() = BACKGROUNDS[backgroundIndex.coerceIn(BACKGROUNDS.indices)]

    /** 描边宽度（dp）；0 表示不描边 */
    val edgeWidthDp: Float
        get() = EDGE_WIDTHS[edgeIndex.coerceIn(EDGE_WIDTHS.indices)]

    /** 字幕基线距画面底部的比例（0~1） */
    val bottomFraction: Float
        get() = BOTTOM_FRACTIONS[positionIndex.coerceIn(BOTTOM_FRACTIONS.indices)]

    companion object {
        /** 字号倍率：基础字号 = 画面高度的一定比例，这里只放大缩小 */
        val SIZES = listOf(0.75f, 1f, 1.25f, 1.5f, 2f)
        const val SIZE_DEFAULT_INDEX = 1

        /** 文字颜色：纸白 / 暖黄 / 青 / 绿 / 橙（与影院风壁纸都合得来） */
        val COLORS =
            listOf(
                0xFFFFFFFF.toInt(),
                0xFFFFE082.toInt(),
                0xFF80DEEA.toInt(),
                0xFFA5D6A7.toInt(),
                0xFFFFAB91.toInt(),
            )
        const val COLOR_DEFAULT_INDEX = 0

        /** 背景：无 / 轻纱 / 半透明 / 实底（ARGB） */
        val BACKGROUNDS =
            listOf(
                0x00000000,
                0x66000000,
                0xB3000000.toInt(),
                0xE6000000.toInt(),
            )
        /** 默认「无」：字幕背景只在用户显式选择后才出现（2026-10-01 用户反馈） */
        const val BACKGROUND_DEFAULT_INDEX = 0

        /** 描边宽度（dp）：无 / 细 / 粗（描边色固定黑，保证任何画面上都可读） */
        val EDGE_WIDTHS = listOf(0f, 2f, 4f)
        const val EDGE_DEFAULT_INDEX = 1

        /** 位置：距画面底部的高度比例（低 → 高） */
        val BOTTOM_FRACTIONS = listOf(0.03f, 0.08f, 0.15f, 0.25f, 0.35f)
        /** 默认「较低」：贴底（0.03）在横屏下会被控制栏压住一部分 */
        const val POSITION_DEFAULT_INDEX = 1
    }
}
