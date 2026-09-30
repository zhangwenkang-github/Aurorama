package com.zhangwenkang.cinefin.core.presentation.theme

import androidx.compose.ui.unit.dp

/**
 * Cinefin 间距刻度（UI_DESIGN_SYSTEM §4.1，4 / 8 基数）。
 *
 * 历史 [Spacings] 是迁移前的 6 档简表，仅供存量页面桥接；新组件与页面用本对象。
 */
object CinefinSpacing {
    val Space1 = 4.dp
    val Space2 = 8.dp
    val Space3 = 12.dp
    val Space4 = 16.dp
    val Space5 = 20.dp
    val Space6 = 24.dp

    /** 栅格 gutter（专用，平板）。 */
    val Space7 = 26.dp
    val Space8 = 32.dp
    val Space10 = 40.dp

    /** 平板页面边距、大区块间距。 */
    val Space12 = 48.dp

    /** 沉浸区留白、空状态上下留白。 */
    val Space16 = 64.dp
}
