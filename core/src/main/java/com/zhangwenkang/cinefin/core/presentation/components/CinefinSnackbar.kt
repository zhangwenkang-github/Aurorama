package com.zhangwenkang.cinefin.core.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinShapes
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinSpacing
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinType
import com.zhangwenkang.cinefin.core.presentation.theme.LocalCinefinColors

/**
 * Cinefin Snackbar（设计系统 §8.12）：反色底 + 反色文字、圆角 12dp、高 ≥52dp、宽 ≤480dp、无投影。
 *
 * 页面只需在自身 `Box` 里放一个 `Modifier.align(Alignment.BottomCenter)` 的实例；Lumen 区域会自动跟随（LocalCinefinColors
 * 被覆盖）。
 */
@Composable
fun CinefinSnackbarHost(hostState: SnackbarHostState, modifier: Modifier = Modifier) {
    SnackbarHost(hostState = hostState, modifier = modifier) { data ->
        val colors = LocalCinefinColors.current
        Box(
            modifier =
                Modifier.widthIn(max = 480.dp)
                    .heightIn(min = 52.dp)
                    .clip(CinefinShapes.Sm)
                    .background(colors.inverseSurface)
                    .padding(
                        horizontal = CinefinSpacing.Space5,
                        vertical = CinefinSpacing.Space3,
                    ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = data.visuals.message,
                style = CinefinType.BodyMedium,
                color = colors.inverseOnSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
