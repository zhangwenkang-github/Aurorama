package com.zhangwenkang.cinefin.music.presentation

import androidx.compose.ui.unit.dp
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinSpacing

internal data class MusicMeasuredBox(
    val widthPx: Int,
    val heightPx: Int,
)

internal data class MusicPlacement(
    val xPx: Int,
    val yPx: Int,
)

internal enum class MusicInteractionRole {
    BUTTON
}

internal data class MusicInteractionSpec(
    val role: MusicInteractionRole,
    val semanticsLabel: String,
    val singleSemanticsNode: Boolean,
)

internal data class MusicPrimaryToolbarPlan(
    val widthPx: Int,
    val heightPx: Int,
    val tabsPlacement: MusicPlacement,
    val toolbarPlacement: MusicPlacement?,
)

internal data class MusicPlayAllPlan(
    val widthPx: Int,
    val heightPx: Int,
    val contentPlacement: MusicPlacement,
    val interaction: MusicInteractionSpec,
)

internal object MusicPrimaryToolbarLayout {
    val tabsToToolbarGap = CinefinSpacing.Space3
    val playAllMinInteractiveHeight = 48.dp

    val playAllInteraction =
        MusicInteractionSpec(
            role = MusicInteractionRole.BUTTON,
            semanticsLabel = "播放全部",
            singleSemanticsNode = true,
        )

    fun measurePrimary(
        tabs: MusicMeasuredBox,
        toolbar: MusicMeasuredBox?,
        gapPx: Int,
    ): MusicPrimaryToolbarPlan {
        val tabsWidth = tabs.widthPx.coerceAtLeast(0)
        val tabsHeight = tabs.heightPx.coerceAtLeast(0)
        val toolbarWidth = toolbar?.widthPx?.coerceAtLeast(0) ?: tabsWidth
        val toolbarHeight = toolbar?.heightPx?.coerceAtLeast(0) ?: 0
        val gap = if (toolbar == null) 0 else gapPx.coerceAtLeast(0)
        val toolbarTop = tabsHeight + gap
        return MusicPrimaryToolbarPlan(
            widthPx = maxOf(tabsWidth, toolbarWidth),
            heightPx = toolbarTop + toolbarHeight,
            tabsPlacement = MusicPlacement(xPx = 0, yPx = 0),
            toolbarPlacement = toolbar?.let { MusicPlacement(xPx = 0, yPx = toolbarTop) },
        )
    }

    fun measurePlayAll(content: MusicMeasuredBox, minimumPx: Int): MusicPlayAllPlan {
        val width = content.widthPx.coerceAtLeast(0)
        val contentHeight = content.heightPx.coerceAtLeast(0)
        val interactiveHeight = maxOf(contentHeight, minimumPx.coerceAtLeast(0))
        return MusicPlayAllPlan(
            widthPx = width,
            heightPx = interactiveHeight,
            contentPlacement =
                MusicPlacement(
                    xPx = 0,
                    yPx = (interactiveHeight - contentHeight) / 2,
                ),
            interaction = playAllInteraction,
        )
    }
}
