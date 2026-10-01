package com.zhangwenkang.cinefin.core.presentation.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinColors
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinMotion
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinTokens
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinType
import com.zhangwenkang.cinefin.core.presentation.theme.LocalCinefinColors
import com.zhangwenkang.cinefin.core.presentation.theme.LocalMediaColors
import com.zhangwenkang.cinefin.core.presentation.theme.MediaColors

/** 按钮四种变形（§8.1）：媒体色融入底 / 描边 / 文字 / 图标，不做独立色块。 */
enum class CinefinButtonVariant {
    Filled,
    Outlined,
    Text,
    Icon,
}

/** 按钮尺寸（§8.1）。 */
enum class CinefinButtonSize {
    Large,
    Medium,
    Small,
}

/**
 * 按钮色调（§8.1 本轮扩展）：[Media] = 当前域媒体色（默认）；[Inverse] = 反色高对比面。
 *
 * [Inverse] 用于 Lumen（S1 A 稿）区域的主行动：月白底 + 深色内容（A 稿 `.btn.primary`）， 其余状态、描边、禁用逻辑与 [Media] 一致。非 Lumen
 * 组件不得使用。
 */
enum class CinefinButtonTone {
    Media,
    Inverse,
}

private val CinefinButtonSize.height: Dp
    get() =
        when (this) {
            CinefinButtonSize.Large -> 56.dp
            CinefinButtonSize.Medium -> 46.dp
            CinefinButtonSize.Small -> 38.dp
        }

private val CinefinButtonSize.horizontalPadding: Dp
    get() =
        when (this) {
            CinefinButtonSize.Large -> 26.dp
            CinefinButtonSize.Medium -> 20.dp
            CinefinButtonSize.Small -> 16.dp
        }

private val CinefinButtonSize.corner: Dp
    get() = 12.dp

private val CinefinButtonSize.textStyle: TextStyle
    get() =
        when (this) {
            CinefinButtonSize.Large,
            CinefinButtonSize.Medium -> CinefinType.LabelLarge
            CinefinButtonSize.Small -> CinefinType.LabelMedium
        }

/** 组件交互状态（§8.1 五态）。 */
internal enum class CinefinInteractionState {
    Default,
    Hover,
    Pressed,
    Focused,
    Disabled,
}

/** 解析后的按钮颜色；预览状态板与单测直接复用，保证组件与规范同源。 */
internal data class CinefinButtonColors(
    val container: Color,
    val content: Color,
    val border: Color,
)

internal fun resolveButtonColors(
    variant: CinefinButtonVariant,
    state: CinefinInteractionState,
    media: MediaColors,
    colors: CinefinColors,
    tone: CinefinButtonTone = CinefinButtonTone.Media,
): CinefinButtonColors {
    val disabledContent = colors.onSurfaceFaint.copy(alpha = colors.disabledAlpha)
    if (state == CinefinInteractionState.Disabled) {
        return when (variant) {
            CinefinButtonVariant.Filled ->
                CinefinButtonColors(colors.surfaceContainerHigh, disabledContent, Color.Transparent)
            CinefinButtonVariant.Outlined ->
                CinefinButtonColors(Color.Transparent, disabledContent, colors.outline)
            CinefinButtonVariant.Text,
            CinefinButtonVariant.Icon ->
                CinefinButtonColors(Color.Transparent, disabledContent, Color.Transparent)
        }
    }

    if (tone == CinefinButtonTone.Inverse && variant == CinefinButtonVariant.Filled) {
        // 反色填充（Lumen 主行动）：月白底 + 深色内容；按下 / 悬停用深色状态层轻轻压暗，保持同一色相
        val container =
            when (state) {
                CinefinInteractionState.Hover ->
                    colors.inverseOnSurface
                        .copy(alpha = CinefinTokens.StateHoverAlpha)
                        .compositeOver(colors.inverseSurface)
                CinefinInteractionState.Pressed ->
                    colors.inverseOnSurface
                        .copy(alpha = CinefinTokens.StatePressedAlpha)
                        .compositeOver(colors.inverseSurface)
                else -> colors.inverseSurface
            }
        return CinefinButtonColors(container, colors.inverseOnSurface, Color.Transparent)
    }

    return when (variant) {
        CinefinButtonVariant.Filled -> {
            val base = if (state == CinefinInteractionState.Pressed) media.dim else media.base
            val container =
                if (state == CinefinInteractionState.Hover) {
                    colors.stateHover.compositeOver(base)
                } else {
                    base
                }
            CinefinButtonColors(container, media.onBase, Color.Transparent)
        }
        CinefinButtonVariant.Outlined -> {
            val container =
                when (state) {
                    CinefinInteractionState.Hover -> colors.stateHover
                    CinefinInteractionState.Pressed -> media.container
                    else -> Color.Transparent
                }
            val border =
                when (state) {
                    CinefinInteractionState.Hover,
                    CinefinInteractionState.Pressed -> media.base
                    else -> media.outline
                }
            CinefinButtonColors(container, media.bright, border)
        }
        CinefinButtonVariant.Text -> {
            val container =
                when (state) {
                    CinefinInteractionState.Hover -> colors.stateHover
                    CinefinInteractionState.Pressed -> media.container
                    else -> Color.Transparent
                }
            CinefinButtonColors(container, media.bright, Color.Transparent)
        }
        CinefinButtonVariant.Icon -> {
            val container =
                when (state) {
                    CinefinInteractionState.Hover -> colors.stateHover
                    CinefinInteractionState.Pressed -> media.container
                    else -> Color.Transparent
                }
            val content = if (state == CinefinInteractionState.Pressed) media.base else media.bright
            CinefinButtonColors(container, content, Color.Transparent)
        }
    }
}

/**
 * Cinefin 基础按钮（§8.1）。
 *
 * 媒体色直接进入按钮本体：Filled 用 `Media.Base` 底 + `Media.OnBase` 内容，Outlined 用 `Media.Outline` 描边 +
 * `Media.Bright` 内容，Text / Icon 用 `Media.Bright` 着色； 按下用 `Media.Dim` / `Media.Container`，聚焦为 2dp
 * 外环，禁用统一 38% 不透明度。 全组件无投影、无独立色块。
 */
@Composable
fun CinefinButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: CinefinButtonVariant = CinefinButtonVariant.Filled,
    size: CinefinButtonSize = CinefinButtonSize.Large,
    tone: CinefinButtonTone = CinefinButtonTone.Media,
    enabled: Boolean = true,
    icon: (@Composable (tint: Color) -> Unit)? = null,
) {
    val media = LocalMediaColors.current
    val colors = LocalCinefinColors.current
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val focused by interactionSource.collectIsFocusedAsState()
    val pressed by interactionSource.collectIsPressedAsState()
    val state =
        when {
            !enabled -> CinefinInteractionState.Disabled
            pressed -> CinefinInteractionState.Pressed
            focused -> CinefinInteractionState.Focused
            hovered -> CinefinInteractionState.Hover
            else -> CinefinInteractionState.Default
        }

    val isIconOnly = variant == CinefinButtonVariant.Icon
    val shape = RoundedCornerShape(if (isIconOnly) 12.dp else size.corner)
    val target = resolveButtonColors(variant, state, media, colors, tone)
    val spec = tween<Color>(durationMillis = CinefinMotion.Fast, easing = CinefinMotion.Standard)
    val containerColor by
        animateColorAsState(
            targetValue = target.container,
            animationSpec = spec,
            label = "buttonContainer",
        )
    val contentColor by
        animateColorAsState(
            targetValue = target.content,
            animationSpec = spec,
            label = "buttonContent",
        )
    val borderColor by
        animateColorAsState(
            targetValue = target.border,
            animationSpec = spec,
            label = "buttonBorder",
        )
    val focusRing =
        if (state == CinefinInteractionState.Focused) {
            media.base.copy(alpha = CinefinTokens.FocusRingAlpha)
        } else {
            Color.Transparent
        }

    Box(
        modifier = modifier.defaultMinSize(minHeight = 48.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier =
                Modifier.height(if (isIconOnly) 44.dp else size.height)
                    .then(if (isIconOnly) Modifier.defaultMinSize(minWidth = 44.dp) else Modifier)
                    .cinefinFocusRing(
                        color = focusRing,
                        cornerRadius = if (isIconOnly) 12.dp else size.corner,
                    )
                    .clip(shape)
                    .background(containerColor)
                    .border(1.dp, borderColor, shape)
                    .clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        enabled = enabled,
                        onClick = onClick,
                    )
                    .padding(horizontal = if (isIconOnly) 12.dp else size.horizontalPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            if (icon != null) {
                icon(contentColor)
            }
            if (!isIconOnly && text.isNotEmpty()) {
                Text(
                    text = text,
                    style = size.textStyle,
                    color = contentColor,
                    maxLines = 1,
                )
            }
        }
    }
}

/** 图标变形便捷入口（§8.1 Icon：44dp 方圆形）。 */
@Composable
fun CinefinIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: @Composable (tint: Color) -> Unit,
) {
    CinefinButton(
        text = "",
        onClick = onClick,
        modifier = modifier,
        variant = CinefinButtonVariant.Icon,
        enabled = enabled,
        icon = icon,
    )
}

/** 焦点环：2dp 外环 `Media.Base@60%` + 1dp 间隙（§5.2）。环绘制在布局边界之外，不参与测量。 */
private fun Modifier.cinefinFocusRing(color: Color, cornerRadius: Dp): Modifier = drawBehind {
    if (color.alpha == 0f) return@drawBehind
    val strokePx = 2.dp.toPx()
    val gapPx = 1.dp.toPx()
    val inset = gapPx + strokePx / 2f
    drawRoundRect(
        color = color,
        topLeft = Offset(-inset, -inset),
        size = Size(size.width + inset * 2f, size.height + inset * 2f),
        cornerRadius = CornerRadius(cornerRadius.toPx() + inset),
        style = Stroke(width = strokePx),
    )
}
