package com.zhangwenkang.cinefin.core.presentation.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Cinefin 形状 token（UI_DESIGN_SYSTEM §5.1）：同心递减 28 / 22 / 16 / 12 / 8 / 4。
 *
 * 同心规则：内层半径 = 外层半径 − 包边厚度（双包边 22/21、28/27）。全系统无投影，层次靠 表面色差 + 1dp 描边 + 顶部内高光（§5.2 / §5.3）。
 */
object CinefinShapes {
    /** 对话框、大面板、手机封面、播放键。 */
    val Xl = RoundedCornerShape(28.dp)

    /** 侧板（字幕 / 队列）、阅读排版面板、书架大卡。 */
    val Lg = RoundedCornerShape(22.dp)

    /** 卡片、海报、横版卡、播放器工具键。 */
    val Md = RoundedCornerShape(16.dp)

    /** 按钮、chip、输入框、分段控件、Toast。 */
    val Sm = RoundedCornerShape(12.dp)

    /** 徽标、缩略图、图标底、小方块。 */
    val Xs = RoundedCornerShape(8.dp)

    /** 进度 knob、指示条、色带端点。 */
    val TwoXs = RoundedCornerShape(4.dp)

    /** 头像、圆形播放键、圆点指示。 */
    val Full = CircleShape

    /** M3 `Shapes` 槽位映射（extraSmall 4 / small 8 / medium 12 / large 16 / extraLarge 28）。 */
    val M3 =
        Shapes(
            extraSmall = TwoXs,
            small = Xs,
            medium = Sm,
            large = Md,
            extraLarge = Xl,
        )
}
