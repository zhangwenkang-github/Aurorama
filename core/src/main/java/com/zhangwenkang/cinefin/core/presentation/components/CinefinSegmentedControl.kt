package com.zhangwenkang.cinefin.core.presentation.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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

/** 分段控件容器高度（§8.2）。 */
private val SegmentedHeight = 44.dp

/** 选中段内缩 2dp：容器圆角 12 − 内缩 2 = 10dp。 */
private val SegmentedItemCorner = 10.dp

/**
 * 分段控件（§8.2）：高 44dp、底 `SurfaceContainerHigh`、1dp `Outline`、圆角 12dp。
 *
 * 选中段用 `Media.Container` 底 + `Media.Bright` 600 文字 + 1dp `Media.Outline` 内描边； 未选中段透明底 +
 * `OnSurfaceVariant`；按下用 `Media.ContainerPressed`（未选中段用白 8% 状态层）；聚焦画 2dp `Media.Base@60%` 内环（容器
 * `clip` 会裁掉外环）；禁用统一 38%。
 */
@Composable
fun <T> CinefinSegmentedControl(
    items: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: (T) -> String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = LocalCinefinColors.current
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .height(SegmentedHeight)
                .clip(CinefinShapes.Sm)
                .background(colors.surfaceContainerHigh)
                .border(1.dp, colors.outline, CinefinShapes.Sm)
                .padding(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items.forEach { item ->
            CinefinSegment(
                text = label(item),
                selected = item == selected,
                enabled = enabled,
                onClick = { onSelect(item) },
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
        }
    }
}

@Composable
private fun CinefinSegment(
    text: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val media = LocalMediaColors.current
    val colors = LocalCinefinColors.current
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val focused by interactionSource.collectIsFocusedAsState()
    val pressed by interactionSource.collectIsPressedAsState()

    val targetContainer =
        when {
            !enabled -> Color.Transparent
            pressed -> if (selected) media.containerPressed else colors.stateHover
            selected -> media.container
            hovered -> colors.stateHover
            else -> Color.Transparent
        }
    val targetBorder =
        when {
            !enabled -> Color.Transparent
            selected -> media.outline
            pressed -> media.base
            else -> Color.Transparent
        }
    val targetContent =
        when {
            !enabled -> colors.onSurfaceFaint.copy(alpha = colors.disabledAlpha)
            selected -> media.bright
            else -> colors.onSurfaceVariant
        }
    val spec = tween<Color>(durationMillis = CinefinMotion.Fast, easing = CinefinMotion.Standard)
    val containerColor by
        animateColorAsState(
            targetValue = targetContainer,
            animationSpec = spec,
            label = "segmentBg",
        )
    val borderColor by
        animateColorAsState(
            targetValue = targetBorder,
            animationSpec = spec,
            label = "segmentBorder",
        )
    val contentColor by
        animateColorAsState(
            targetValue = targetContent,
            animationSpec = spec,
            label = "segmentText",
        )
    val focusRing = if (focused && enabled) media.base.copy(alpha = 0.6f) else Color.Transparent
    val itemShape = RoundedCornerShape(SegmentedItemCorner)

    Box(
        modifier =
            modifier
                .clip(itemShape)
                .background(containerColor)
                .border(1.dp, borderColor, itemShape)
                .cinefinInnerFocusRing(color = focusRing, cornerRadius = SegmentedItemCorner)
                .cinefinClickable(
                    enabled = enabled,
                    onClick = onClick,
                    interactionSource = interactionSource,
                ),
        contentAlignment = Alignment.Center,
    ) {
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
