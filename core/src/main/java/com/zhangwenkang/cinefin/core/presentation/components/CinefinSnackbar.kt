package com.zhangwenkang.cinefin.core.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.SnackbarDuration
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
 * W63：下载反馈 Snackbar 的统一显式时长。
 *
 * Material3 的 `showSnackbar` 在带 `actionLabel`（如「查看」）时默认 `Indefinite`——不点不消失（真机 P1 缺陷根因）。 所有下载 /
 * 批量反馈必须显式传本常量（`Long` ≈ 10s，够看清又自动消失）。
 */
val DownloadSnackbarDuration: SnackbarDuration = SnackbarDuration.Long

/**
 * Cinefin Snackbar（设计系统 §8.12）：反色底 + 反色文字、圆角 12dp、高 ≥52dp、宽 ≤480dp、无投影。
 *
 * 页面只需在自身 `Box` 里放一个 `Modifier.align(Alignment.BottomCenter)` 的实例；Lumen 区域会自动跟随（LocalCinefinColors
 * 被覆盖）。W60b：支持 32dp 动作文字（`showSnackbar(actionLabel = …)`，例如下载反馈的「查看」），点击走 `performAction()`。
 */
@Composable
fun CinefinSnackbarHost(hostState: SnackbarHostState, modifier: Modifier = Modifier) {
    SnackbarHost(hostState = hostState, modifier = modifier) { data ->
        val colors = LocalCinefinColors.current
        Row(
            modifier =
                Modifier.widthIn(max = 480.dp)
                    .heightIn(min = 52.dp)
                    .clip(CinefinShapes.Sm)
                    .background(colors.inverseSurface)
                    .padding(
                        horizontal = CinefinSpacing.Space5,
                        vertical = CinefinSpacing.Space3,
                    ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(CinefinSpacing.Space4),
        ) {
            Text(
                text = data.visuals.message,
                style = CinefinType.BodyMedium,
                color = colors.inverseOnSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            data.visuals.actionLabel?.let { actionLabel ->
                Text(
                    text = actionLabel,
                    style = CinefinType.LabelLarge,
                    color = colors.inverseOnSurface,
                    maxLines = 1,
                    modifier =
                        Modifier.clip(CinefinShapes.Sm)
                            .cinefinClickable(onClick = { data.performAction() })
                            .padding(
                                horizontal = CinefinSpacing.Space3,
                                vertical = CinefinSpacing.Space2,
                            ),
                )
            }
        }
    }
}
