package com.zhangwenkang.cinefin.core.presentation.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Cinefin 形状 token（K1 §3.2）：海报 14 / 卡片 20 / 按钮 16 / 玻璃容器 32。
 *
 * 语义槽位：[Poster] = 海报与封面图；[Card] = 通用卡片与面板（横版卡、书架大卡、侧板、阅读排版面板）； [Button] = 按钮 / chip / 输入框 / 分段控件 /
 * Toast / 导航选中 pill；[Glass] = 悬浮玻璃容器（底栏 / 迷你播放器 / 悬浮顶栏）； [Xl] 留给对话框与大面板，[Xs] / [TwoXs] / [Full]
 * 不变。同页不混用两套圆角体系；嵌套容器保持同心（内层半径 = 外层半径 − 内缩）， 全系统无投影，层次靠 表面色差 + 1dp 描边 + 顶部内高光（§5.2 / §5.3）。
 */
object CinefinShapes {
    /** 悬浮玻璃容器：底栏、迷你播放器、悬浮顶栏。 */
    val Glass = RoundedCornerShape(32.dp)

    /** 对话框、大面板、手机封面大图、播放键。 */
    val Xl = RoundedCornerShape(28.dp)

    /** 通用卡片、横版卡、书架大卡、侧板（字幕 / 队列）、阅读排版面板。 */
    val Card = RoundedCornerShape(20.dp)

    /** 按钮、chip、输入框、分段控件、Toast、导航选中 pill。 */
    val Button = RoundedCornerShape(16.dp)

    /** 海报、封面图。 */
    val Poster = RoundedCornerShape(14.dp)

    /** 徽标、缩略图、图标底、小方块。 */
    val Xs = RoundedCornerShape(8.dp)

    /** 进度 knob、指示条、色带端点。 */
    val TwoXs = RoundedCornerShape(4.dp)

    /** 头像、圆形播放键、圆点指示。 */
    val Full = CircleShape

    /** M3 `Shapes` 槽位映射（extraSmall 4 / small 8 / medium 16 / large 20 / extraLarge 28）。 */
    val M3 =
        Shapes(
            extraSmall = TwoXs,
            small = Xs,
            medium = Button,
            large = Card,
            extraLarge = Xl,
        )
}
