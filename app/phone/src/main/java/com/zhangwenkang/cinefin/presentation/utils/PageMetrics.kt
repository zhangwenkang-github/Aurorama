package com.zhangwenkang.cinefin.presentation.utils

import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.window.core.layout.WindowSizeClass

/** 页面边距（`UI_DESIGN_SYSTEM` §4.2 / §4.3）：手机 20 / Medium 24 / Expanded 32 / Large+ 48dp。 */
@Composable
fun rememberPageGutter(): Dp {
    val sizeClass = currentWindowAdaptiveInfo().windowSizeClass
    return when {
        sizeClass.isWidthAtLeastBreakpoint(1200) -> 48.dp
        sizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND) -> 32.dp
        sizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND) -> 24.dp
        else -> 20.dp
    }
}

/** 卡片 / 海报墙间距：手机 16dp；平板 26dp（§4.2 4 列海报墙 gutter）。 */
@Composable
fun rememberGridGutter(): Dp {
    val sizeClass = currentWindowAdaptiveInfo().windowSizeClass
    return if (sizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND)) {
        26.dp
    } else {
        16.dp
    }
}
