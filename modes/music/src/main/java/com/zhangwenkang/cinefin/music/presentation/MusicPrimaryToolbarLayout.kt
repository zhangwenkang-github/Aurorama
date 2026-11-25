package com.zhangwenkang.cinefin.music.presentation

import androidx.compose.ui.unit.Density
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinSpacing

internal data class MusicMeasuredBox(
    val widthPx: Int,
    val heightPx: Int,
)

internal data class MusicPlacement(
    val xPx: Int,
    val yPx: Int,
)

internal data class MusicPrimaryToolbarPlan(
    val widthPx: Int,
    val heightPx: Int,
    val tabsPlacement: MusicPlacement,
    val toolbarPlacement: MusicPlacement?,
)

internal data class MusicBatchActionAccessibility(
    val contentDescription: String,
    val stateDescription: String?,
    val enabled: Boolean,
)

internal fun musicBatchActionAccessibility(
    label: String,
    enabled: Boolean,
    loading: Boolean,
): MusicBatchActionAccessibility =
    MusicBatchActionAccessibility(
        contentDescription = label,
        stateDescription =
            when {
                loading -> "正在处理"
                !enabled -> "不可用"
                else -> null
            },
        enabled = enabled && !loading,
    )

internal object MusicPrimaryToolbarLayout {
    val tabsToToolbarGap = CinefinSpacing.Space3

    fun measurePrimary(
        tabs: MusicMeasuredBox,
        toolbar: MusicMeasuredBox?,
        density: Density,
    ): MusicPrimaryToolbarPlan {
        val tabsWidth = tabs.widthPx.coerceAtLeast(0)
        val tabsHeight = tabs.heightPx.coerceAtLeast(0)
        val toolbarWidth = toolbar?.widthPx?.coerceAtLeast(0) ?: tabsWidth
        val toolbarHeight = toolbar?.heightPx?.coerceAtLeast(0) ?: 0
        val gapPx = with(density) { tabsToToolbarGap.roundToPx() }
        val gap = if (toolbar == null) 0 else gapPx.coerceAtLeast(0)
        val toolbarTop = tabsHeight + gap
        return MusicPrimaryToolbarPlan(
            widthPx = maxOf(tabsWidth, toolbarWidth),
            heightPx = toolbarTop + toolbarHeight,
            tabsPlacement = MusicPlacement(xPx = 0, yPx = 0),
            toolbarPlacement = toolbar?.let { MusicPlacement(xPx = 0, yPx = toolbarTop) },
        )
    }
}
