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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
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
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinShapes
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinTokens
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinType
import com.zhangwenkang.cinefin.core.presentation.theme.LocalCinefinColors
import com.zhangwenkang.cinefin.core.presentation.theme.MediaAurora
import com.zhangwenkang.cinefin.core.presentation.theme.MediaColors
import com.zhangwenkang.cinefin.core.presentation.theme.auroraGradient

/** 按钮四种变形（§8.1）：极光 / 中性色进入底、描边、文字、图标，不做独立色块。 */
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
 * 按钮色调（§8.1 + 2.0.1 定稿 §2 批② + 2.0.2 §D5 批②）：
 * - [Aurora]：**一级 CTA 唯一色调**——100° 极光渐变实底 + `onAurora` 内容 + `auroraGlow` 投影（播放 / 继续播放 / 播放全部 /
 *   传输主键）；
 * - [Media]：默认色调。2.0.2 §D5 批② 起域媒体色已退出按钮交互槽位，本色调解析为**极光实底**（`aurora1` + `onAurora`，无渐变 / 无光晕），即二级
 *   CTA 与一级 [Aurora] 同一色相、低一档权重；色调名保留只为不动既有调用点；
 * - [Inverse]：反色高对比面，月白底 + 深色内容（A 稿 `.btn.primary`），其余状态、描边、禁用逻辑与 [Media] 一致。
 *
 * [Aurora] 只在 [CinefinButtonVariant.Filled] 下生效；与 Outlined / Text / Icon 组合时回退为 [Media]
 * 行为（渐变实底不适合这些小面积形态）。
 */
enum class CinefinButtonTone {
    Media,
    Inverse,
    Aurora,
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

/** 按钮圆角（K1：`CinefinShapes.Button` 的 16dp）；焦点环走 drawBehind，需要 Dp 值。 */
private val CinefinButtonCorner = 16.dp

/** 图标按钮触控面（§8.1 Icon：44dp 方圆形）；批量条动作行按它算最小内容宽，不得压缩。 */
internal val CinefinIconButtonSize = 44.dp

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

/**
 * 按钮颜色解析（§8.1 五态 × 四变形 × 三色调）。
 *
 * 2.0.2 §D5 批② 起域媒体色退出按钮交互槽位：填充底 / 按下层 / 描边 / 内容一律归极光（`aurora1` / `navSelectedContent`）。 [media]
 * 形参已不参与解析，保留默认值只为不动既有（含守卫测试的命名实参）调用点。
 */
internal fun resolveButtonColors(
    variant: CinefinButtonVariant,
    state: CinefinInteractionState,
    @Suppress("UNUSED_PARAMETER") media: MediaColors = MediaAurora,
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
        // 反色填充：月白底 + 深色内容；按下 / 悬停用深色状态层轻轻压暗，保持同一色相
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

    if (tone == CinefinButtonTone.Aurora && variant == CinefinButtonVariant.Filled) {
        // 一级 CTA：极光渐变实底由组件按 drawBehind 绘制（见 CinefinButton），这里只给代表色 + onAurora 内容；
        // 按下 / 悬停由组件在渐变上叠 `statePressed` / `stateHover` 状态层。
        return CinefinButtonColors(colors.aurora1, colors.onAurora, Color.Transparent)
    }

    val selectedFill = colors.aurora1.copy(alpha = CinefinTokens.MediaContainerAlpha)
    val selectedOutline = colors.aurora1.copy(alpha = CinefinTokens.MediaOutlineAlpha)
    return when (variant) {
        CinefinButtonVariant.Filled -> {
            // 2.0.2 §D5 批②：填充底归极光实色（与 Aurora tone 同色相、无渐变），悬停 / 按下叠状态层。
            val container =
                when (state) {
                    CinefinInteractionState.Pressed ->
                        colors.statePressed.compositeOver(colors.aurora1)
                    CinefinInteractionState.Hover -> colors.stateHover.compositeOver(colors.aurora1)
                    else -> colors.aurora1
                }
            CinefinButtonColors(container, colors.onAurora, Color.Transparent)
        }
        CinefinButtonVariant.Outlined -> {
            val container =
                when (state) {
                    CinefinInteractionState.Hover -> colors.stateHover
                    CinefinInteractionState.Pressed -> selectedFill
                    else -> Color.Transparent
                }
            val border =
                when (state) {
                    CinefinInteractionState.Hover,
                    CinefinInteractionState.Pressed -> colors.aurora1
                    else -> selectedOutline
                }
            CinefinButtonColors(container, navSelectedContent(colors), border)
        }
        CinefinButtonVariant.Text -> {
            val container =
                when (state) {
                    CinefinInteractionState.Hover -> colors.stateHover
                    CinefinInteractionState.Pressed -> selectedFill
                    else -> Color.Transparent
                }
            CinefinButtonColors(container, navSelectedContent(colors), Color.Transparent)
        }
        CinefinButtonVariant.Icon -> {
            val container =
                when (state) {
                    CinefinInteractionState.Hover -> colors.stateHover
                    CinefinInteractionState.Pressed -> selectedFill
                    else -> Color.Transparent
                }
            val content =
                if (state == CinefinInteractionState.Pressed) {
                    colors.aurora1
                } else {
                    navSelectedContent(colors)
                }
            CinefinButtonColors(container, content, Color.Transparent)
        }
    }
}

/**
 * Cinefin 基础按钮（§8.1 + 2.0.1 定稿 §2 批② + 2.0.2 §D5 批②）。
 *
 * 默认（[CinefinButtonTone.Media]）色调全走极光：Filled 用 `aurora1` 实底 + `onAurora` 内容（按下叠 `statePressed` 层），
 * Outlined 用 `aurora1@45%` 描边 + [navSelectedContent] 内容，Text / Icon 用 [navSelectedContent] 着色、按下铺
 * `aurora1@16%`，聚焦环恒 `aurora1@60%`，禁用统一 38% 不透明度；域媒体色不再进入按钮本体。
 *
 * 一级 CTA 走 [CinefinButtonTone.Aurora]（仅 Filled）：100° 极光渐变实底 + `onAurora` 内容 + `auroraGlow`
 * 投影（6dp、aurora1 28%），按下 / 悬停直接在渐变上叠状态层；除该投影外组件无其它投影、无独立色块。
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
    val shape = CinefinShapes.Button
    val target = resolveButtonColors(variant = variant, state = state, colors = colors, tone = tone)
    val spec = tween<Color>(durationMillis = CinefinMotion.Fast, easing = CinefinMotion.Standard)
    // 极光渐变实底只用于「Aurora + Filled + 可用」；禁用态仍走中性禁用色板（resolveButtonColors 的 Disabled 分支）。
    val auroraFill =
        tone == CinefinButtonTone.Aurora &&
            variant == CinefinButtonVariant.Filled &&
            state != CinefinInteractionState.Disabled
    val auroraStateTarget =
        when {
            !auroraFill -> Color.Transparent
            state == CinefinInteractionState.Pressed -> colors.statePressed
            state == CinefinInteractionState.Hover -> colors.stateHover
            else -> Color.Transparent
        }
    val auroraStateColor by
        animateColorAsState(
            targetValue = auroraStateTarget,
            animationSpec = spec,
            label = "buttonStateLayer",
        )
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
            // 交互强调（含焦点环）归极光：任何色调都用 `aurora1` 外环。
            colors.aurora1.copy(alpha = CinefinTokens.FocusRingAlpha)
        } else {
            Color.Transparent
        }

    Box(
        modifier = modifier.defaultMinSize(minHeight = 48.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier =
                Modifier.height(if (isIconOnly) CinefinIconButtonSize else size.height)
                    .then(
                        if (isIconOnly) Modifier.defaultMinSize(minWidth = CinefinIconButtonSize)
                        else Modifier
                    )
                    .then(
                        if (auroraFill) {
                            // clip = false：投影层不裁掉画在边界外的 2dp 焦点环（圆角仍由下面的 clip(shape) 负责）。
                            Modifier.shadow(
                                elevation = CinefinTokens.AuroraGlowElevationDp.dp,
                                shape = shape,
                                clip = false,
                                ambientColor =
                                    colors.aurora1.copy(alpha = CinefinTokens.AuroraGlowAlpha),
                                spotColor =
                                    colors.aurora1.copy(alpha = CinefinTokens.AuroraGlowAlpha),
                            )
                        } else {
                            Modifier
                        }
                    )
                    .cinefinFocusRing(
                        color = focusRing,
                        cornerRadius = CinefinButtonCorner,
                    )
                    .clip(shape)
                    .then(
                        if (auroraFill) {
                            Modifier.auroraGradient(colors).background(auroraStateColor)
                        } else {
                            Modifier.background(containerColor)
                        }
                    )
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

/** 焦点环：2dp 外环 `aurora1@60%` + 1dp 间隙（§5.2；2.0.2 §D5 批② 起交互强调归极光）。 */
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
