package com.zhangwenkang.cinefin.presentation.utils

import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.window.core.layout.WindowSizeClass

/**
 * 页面边距（`UI_DESIGN_SYSTEM` §4.2 / §4.3 + R1 对齐）：Compact(手机) 16 / Medium 24 / Expanded 32 / Large+
 * 48dp。
 *
 * 2.0.1 定稿 §2 批③（AUDIT-13）：Compact 档由 20 收到 16，与 R1 的紧凑页边距对齐。
 */
@Composable
fun rememberPageGutter(): Dp {
    val sizeClass = currentWindowAdaptiveInfo().windowSizeClass
    return when {
        sizeClass.isWidthAtLeastBreakpoint(1200) -> 48.dp
        sizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND) -> 32.dp
        sizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND) -> 24.dp
        else -> 16.dp
    }
}

/**
 * 卡片 / 海报墙间距：Compact 12dp；平板 26dp（§4.2 4 列海报墙 gutter）。
 *
 * 2.0.1 定稿 §2 批③（AUDIT-13）：Compact 档由 16 收到 12。
 */
@Composable
fun rememberGridGutter(): Dp {
    val sizeClass = currentWindowAdaptiveInfo().windowSizeClass
    return if (sizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND)) {
        26.dp
    } else {
        12.dp
    }
}
