package com.zhangwenkang.cinefin.presentation.film.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.zhangwenkang.cinefin.core.R as CoreR
import com.zhangwenkang.cinefin.core.presentation.components.cinefinClickable
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinShapes
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinSpacing
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinTheme
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinTokens
import com.zhangwenkang.cinefin.core.presentation.theme.LocalCinefinColors
import com.zhangwenkang.cinefin.presentation.utils.rememberSafePadding

@Composable
fun ItemTopBar(
    hasBackButton: Boolean,
    hasHomeButton: Boolean,
    onBackClick: () -> Unit = {},
    onHomeClick: () -> Unit = {},
    content: @Composable (RowScope.() -> Unit) = {},
) {
    val safePadding = rememberSafePadding()

    Row(
        modifier =
            Modifier.fillMaxWidth()
                .padding(
                    start = safePadding.start + CinefinSpacing.Space3,
                    top = safePadding.top + CinefinSpacing.Space3,
                    end = safePadding.end + CinefinSpacing.Space3,
                )
    ) {
        if (hasBackButton) {
            OverlayIconAction(icon = CoreR.drawable.ic_arrow_left, onClick = onBackClick)
        }
        if (hasHomeButton) {
            OverlayIconAction(icon = CoreR.drawable.ic_home, onClick = onHomeClick)
        }
        content()
    }
}

/**
 * 压在剧照上的覆盖层图标键（§5.2 / §8.7 覆盖层）：纯图标控件垫层 = scrim 28%（`PlayerGlassAlpha` 口径）+ `glassBorder` 细描边 + 方圆形
 * 12dp + 白图标（2.0.2 D4 减重裁定）。
 */
@Composable
private fun OverlayIconAction(icon: Int, onClick: () -> Unit) {
    val colors = LocalCinefinColors.current
    Box(
        modifier =
            Modifier.padding(end = CinefinSpacing.Space2)
                .size(44.dp)
                .clip(CinefinShapes.Button)
                .background(colors.scrim.copy(alpha = CinefinTokens.PlayerGlassAlpha))
                .border(1.dp, colors.glassBorder, CinefinShapes.Button)
                .cinefinClickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            tint = Color.White,
        )
    }
}

@Composable
@Preview(showBackground = true)
private fun ItemTopBarPreview() {
    CinefinTheme { ItemTopBar(hasBackButton = true, hasHomeButton = true) }
}
