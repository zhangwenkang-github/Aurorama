package com.zhangwenkang.cinefin.music.presentation

import androidx.compose.ui.unit.dp
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinSpacing

internal object MusicPrimaryToolbarLayout {
    val tabsToToolbarGap = CinefinSpacing.Space3
    val playAllMinInteractiveHeight = 48.dp
}

internal data class MusicPrimaryToolbarGeometry(
    val toolbarTopPx: Int,
    val totalHeightPx: Int,
)

internal fun musicPrimaryToolbarGeometry(
    tabsHeightPx: Int,
    toolbarHeightPx: Int,
    gapPx: Int,
): MusicPrimaryToolbarGeometry {
    val tabsHeight = tabsHeightPx.coerceAtLeast(0)
    val toolbarHeight = toolbarHeightPx.coerceAtLeast(0)
    val gap = gapPx.coerceAtLeast(0)
    val toolbarTop = tabsHeight + gap
    return MusicPrimaryToolbarGeometry(
        toolbarTopPx = toolbarTop,
        totalHeightPx = toolbarTop + toolbarHeight,
    )
}

internal fun musicPlayAllInteractiveHeight(contentHeightPx: Int, minimumPx: Int): Int =
    maxOf(contentHeightPx.coerceAtLeast(0), minimumPx.coerceAtLeast(0))
