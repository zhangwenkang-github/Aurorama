package com.zhangwenkang.cinefin.core.presentation.components

import androidx.compose.ui.unit.dp

/**
 * 自绘进度控件共享视觉参数（W49）。
 *
 * `CinefinSlider`（EQ / ReplayGain / 阅读器）与播放页自绘进度条（`MusicProgressBar`，W23 D38）此前各写一份 光晕 / 轨道 /
 * 拇指尺寸；这里收成唯一来源（「与播放页进度条同源」），避免两处漂移。
 */
object CinefinProgressVisuals {

    /** 整条触控带高度：自消费手势，纵向滚动容器不抢。 */
    val TouchHeight = 36.dp

    /** 轨道厚度（4dp 胶囊）。 */
    val TrackHeight = 4.dp

    /** 拇指半径（18dp 圆点，取代 M3 默认竖条）。 */
    val ThumbRadius = 9.dp

    /** 光晕半径 = 拇指半径 × 该系数。 */
    const val GlowScale = 2.6f

    /** 光晕基色透明度（极轻同色柔光）。 */
    const val GlowAlpha = 0.45f

    /** 禁用态整体透明度。 */
    const val DisabledAlpha = 0.4f
}
