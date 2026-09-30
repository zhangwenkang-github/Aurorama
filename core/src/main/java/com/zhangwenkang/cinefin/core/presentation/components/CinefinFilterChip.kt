package com.zhangwenkang.cinefin.core.presentation.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinMotion
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinShapes
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinType
import com.zhangwenkang.cinefin.core.presentation.theme.LocalCinefinColors
import com.zhangwenkang.cinefin.core.presentation.theme.LocalMediaColors

/**
 * 筛选 Chip（§8.3）：标准高 40dp / 紧凑 32dp，圆角 12dp，文字 LabelMedium。
 *
 * 未选中 = 中性色（`SurfaceContainer` + `Outline`）；选中 = 当前域媒体色融入本体（`Media.Container` + `Media.Outline` +
 * `Media.Bright`）；按下用 `Media.ContainerPressed` + `Media.Base` 描边；禁用统一 38%。 可选前置 16dp 图标，**不带任何色点 /
 * 小色块**。
 */
@Composable
fun CinefinFilterChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    compact: Boolean = false,
    icon: (@Composable (tint: Color) -> Unit)? = null,
) {
    val media = LocalMediaColors.current
    val colors = LocalCinefinColors.current
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val focused by interactionSource.collectIsFocusedAsState()
    val pressed by interactionSource.collectIsPressedAsState()

    val disabledAlpha = colors.disabledAlpha
    val targetContainer =
        when {
            !enabled -> if (selected) media.container else colors.surfaceContainer
            pressed -> media.containerPressed
            selected -> media.container
            hovered -> colors.stateHover
            else -> colors.surfaceContainer
        }
    val targetBorder =
        when {
            !enabled -> if (selected) media.outline else colors.outline
            pressed -> media.base
            selected -> media.outline
            else -> colors.outline
        }
    val targetContent =
        when {
            !enabled -> colors.onSurfaceVariant.copy(alpha = disabledAlpha)
            selected -> media.bright
            else -> colors.onSurfaceVariant
        }
    val spec = tween<Color>(durationMillis = CinefinMotion.Fast, easing = CinefinMotion.Standard)
    val containerColor by
        animateColorAsState(targetValue = targetContainer, animationSpec = spec, label = "chipBg")
    val borderColor by
        animateColorAsState(targetValue = targetBorder, animationSpec = spec, label = "chipBorder")
    val contentColor by
        animateColorAsState(targetValue = targetContent, animationSpec = spec, label = "chipText")
    val focusRing = if (focused && enabled) media.base.copy(alpha = 0.6f) else Color.Transparent

    // 视觉高度 32/40dp，触控热区外扩到 48dp（§8.3 的最小触控高）
    Box(
        modifier = modifier.defaultMinSize(minHeight = 48.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier =
                Modifier.height(if (compact) 32.dp else 40.dp)
                    .clip(CinefinShapes.Sm)
                    .background(containerColor)
                    .border(1.dp, borderColor, CinefinShapes.Sm)
                    .cinefinInnerFocusRing(color = focusRing, cornerRadius = 12.dp)
                    .cinefinClickable(
                        enabled = enabled,
                        interactionSource = interactionSource,
                        onClick = onClick,
                    )
                    .padding(horizontal = if (compact) 16.dp else 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (icon != null) {
                Row(modifier = Modifier.size(16.dp)) { icon(contentColor) }
            }
            Text(
                text = text,
                style = CinefinType.LabelMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
