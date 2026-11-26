package com.zhangwenkang.cinefin.core.presentation.utils

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.window.core.layout.WindowSizeClass

/** 四边安全区（dp），由 `rememberSafePadding` 计算，横屏 / 侧挖孔页面按边取值。 */
data class SafePadding(val start: Dp, val top: Dp, val end: Dp, val bottom: Dp)

/**
 * 四边安全区（2.0.2 D1：由 `app:phone` 下沉到 core，供 modes 复用）。
 *
 * [handleStartInsets] = false 时，Medium / Expanded 宽度交给导航栏自身处理 start 内边距，避免双层避让。
 */
@Composable
fun rememberSafePadding(handleStartInsets: Boolean = true): SafePadding {
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current

    val windowSizeClass = currentWindowAdaptiveInfo().windowSizeClass

    val safePaddingStart =
        if (handleStartInsets) {
            with(density) { WindowInsets.safeDrawing.getLeft(this, layoutDirection).toDp() }
        } else {
            // Navigation rail handles safe drawing inset in medium and expanded width
            when {
                windowSizeClass.isWidthAtLeastBreakpoint(
                    WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND
                ) -> 0.dp
                windowSizeClass.isWidthAtLeastBreakpoint(
                    WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND
                ) -> 0.dp
                else ->
                    with(density) { WindowInsets.safeDrawing.getLeft(this, layoutDirection).toDp() }
            }
        }

    val safePaddingTop = with(density) { WindowInsets.safeDrawing.getTop(this).toDp() }
    val safePaddingEnd =
        with(density) { WindowInsets.safeDrawing.getRight(this, layoutDirection).toDp() }
    val safePaddingBottom = with(density) { WindowInsets.safeDrawing.getBottom(this).toDp() }

    return SafePadding(
        start = safePaddingStart,
        top = safePaddingTop,
        end = safePaddingEnd,
        bottom = safePaddingBottom,
    )
}

/**
 * 顶部安全区（2.0.2 D1）：`safeDrawing` 的 Top 边，即 **状态栏 ∪ 挖孔** 的统一口径。
 *
 * 不同于 `statusBarsPadding()`（只含状态栏），挖孔比状态栏高时内容仍会被压住；横屏 / 侧挖孔的左 / 右避让由 `rememberSafePadding()` 覆盖。
 */
@Composable
fun Modifier.cinefinSafeTop(): Modifier =
    this.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))

/**
 * 水平安全区（2.0.2 D1）：`safeDrawing` 的 Start + End 边，供贴窗口左右边缘的组件自吃。
 *
 * 横屏 ROT90 / ROT270 下侧挖孔分别落在右 / 左，两条边都要避让。与 `rememberSafePadding()` 同源（同为 `safeDrawing`）， 差别是它按
 * inset 方式**消费**：嵌套子组件再读水平 inset 时为 0，组件与页面各自避让时不会叠成双层留白。
 */
@Composable
fun Modifier.cinefinSafeHorizontal(): Modifier =
    this.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))

/** 起始边安全区（2.0.2 D1）：`safeDrawing` 的 Start 边，供侧轨等贴左缘容器避让侧挖孔。 */
@Composable
fun Modifier.cinefinSafeStart(): Modifier =
    this.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Start))
