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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinColors
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinMotion
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinShapes
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinSpacing
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinTokens
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinType
import com.zhangwenkang.cinefin.core.presentation.theme.LocalCinefinColors
import com.zhangwenkang.cinefin.core.presentation.theme.LocalMediaColors
import com.zhangwenkang.cinefin.core.presentation.theme.MediaColors

internal data class CinefinCardColors(
    val container: Color,
    val border: Color,
)

internal fun resolveCardColors(
    state: CinefinInteractionState,
    selected: Boolean,
    media: MediaColors,
    colors: CinefinColors,
): CinefinCardColors {
    val container =
        if (state == CinefinInteractionState.Pressed) media.container else colors.surfaceContainer
    val border =
        when {
            selected -> media.outline
            state == CinefinInteractionState.Hover ||
                state == CinefinInteractionState.Focused ||
                state == CinefinInteractionState.Pressed -> media.outline
            else -> colors.outline
        }
    return CinefinCardColors(container, border)
}

/**
 * Cinefin 基础卡片（§8.4）：1dp 描边 + 顶部内高光，圆角 16dp，`elevation = 0`。
 *
 * 卡片本身不铺媒体色底；选中 / 悬停只改变描边，按下用 `Media.Container` 状态层。
 */
@Composable
fun CinefinCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    selected: Boolean = false,
    shape: Shape = CinefinShapes.Md,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val media = LocalMediaColors.current
    val colors = LocalCinefinColors.current
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val focused by interactionSource.collectIsFocusedAsState()
    val pressed by interactionSource.collectIsPressedAsState()
    val state =
        when {
            pressed -> CinefinInteractionState.Pressed
            focused -> CinefinInteractionState.Focused
            hovered -> CinefinInteractionState.Hover
            else -> CinefinInteractionState.Default
        }
    val target = resolveCardColors(state, selected, media, colors)
    val spec = tween<Color>(durationMillis = CinefinMotion.Fast, easing = CinefinMotion.Standard)
    val containerColor by
        animateColorAsState(
            targetValue = target.container,
            animationSpec = spec,
            label = "cardContainer",
        )
    val borderColor by
        animateColorAsState(targetValue = target.border, animationSpec = spec, label = "cardBorder")

    Column(
        modifier =
            modifier
                .clip(shape)
                .background(containerColor)
                .cinefinTopHighlight(colors.topHighlight)
                .border(1.dp, borderColor, shape)
                .then(
                    if (onClick != null) {
                        Modifier.clickable(
                            interactionSource = interactionSource,
                            indication = null,
                            onClick = onClick,
                        )
                    } else {
                        Modifier
                    }
                )
                .padding(contentPadding),
        content = content,
    )
}

/** 海报卡（2:3 + 标题 + 元信息），图片槽位由调用方提供（core 不依赖图片加载库）。 */
@Composable
fun CinefinPosterCard(
    title: String,
    modifier: Modifier = Modifier,
    meta: String? = null,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
    poster: @Composable () -> Unit,
) {
    val colors = LocalCinefinColors.current
    CinefinCard(modifier = modifier, onClick = onClick, selected = selected) {
        Box(
            modifier =
                Modifier.fillMaxWidth()
                    .aspectRatio(2f / 3f)
                    .clip(CinefinShapes.Md)
                    .background(colors.surfaceContainerHigh)
        ) {
            poster()
        }
        Spacer(Modifier.height(CinefinSpacing.Space3))
        Text(
            text = title,
            style = CinefinType.TitleSmall,
            color = colors.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (meta != null) {
            Spacer(Modifier.height(CinefinSpacing.Space1))
            Text(
                text = meta,
                style = CinefinType.BodySmall,
                color = colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 横版卡（16:9 + 底部 96dp 渐隐 + 标题 + 3dp 进度）。 */
@Composable
fun CinefinWideCard(
    title: String,
    modifier: Modifier = Modifier,
    progress: Float? = null,
    onClick: (() -> Unit)? = null,
    image: @Composable () -> Unit,
) {
    val media = LocalMediaColors.current
    val colors = LocalCinefinColors.current
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(CinefinShapes.Md)
                .background(colors.surfaceContainerHigh)
                .then(
                    if (onClick != null) Modifier.cinefinClickable(onClick = onClick) else Modifier
                )
    ) {
        image()
        Box(
            modifier =
                Modifier.fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            0.55f to Color.Transparent,
                            1f to CinefinTokens.OverlayGlass,
                        )
                    )
        )
        Column(
            modifier =
                Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(CinefinSpacing.Space4)
        ) {
            Text(
                text = title,
                style = CinefinType.WideCardTitle,
                color = colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (progress != null) {
                Spacer(Modifier.height(CinefinSpacing.Space2))
                Box(
                    modifier =
                        Modifier.fillMaxWidth()
                            .height(3.dp)
                            .clip(CinefinShapes.TwoXs)
                            .background(colors.progressTrackOnImage)
                ) {
                    Box(
                        modifier =
                            Modifier.fillMaxWidth(progress.coerceIn(0f, 1f))
                                .height(3.dp)
                                .clip(CinefinShapes.TwoXs)
                                .background(media.base)
                    )
                }
            }
        }
    }
}

/** 列表行（§8.4 / §8.5）：双行 88dp / 单行 72dp；当前行用 `Media.Container` 横向渐变， 序号与主文字保持中性，不引入独立色块。 */
@Composable
fun CinefinListRow(
    title: String,
    modifier: Modifier = Modifier,
    secondary: String? = null,
    isCurrent: Boolean = false,
    showDivider: Boolean = true,
    onClick: (() -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    /** W37 在线融合：标题后的来源徽标（如「本地」）；null = 不显示。 */
    badge: String? = null,
) {
    val media = LocalMediaColors.current
    val colors = LocalCinefinColors.current
    val brush =
        if (isCurrent) {
            Brush.horizontalGradient(
                0f to media.container,
                1f to media.container.copy(alpha = 0f),
            )
        } else {
            null
        }
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .height(if (secondary != null) 88.dp else 72.dp)
                .then(if (brush != null) Modifier.background(brush) else Modifier)
                .then(
                    if (onClick != null) Modifier.cinefinClickable(onClick = onClick) else Modifier
                )
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = CinefinSpacing.Space4),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leading != null) {
                leading()
                Spacer(Modifier.width(CinefinSpacing.Space4))
            }
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = title,
                        style = CinefinType.TitleMedium,
                        color = colors.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (!badge.isNullOrBlank()) {
                        Spacer(Modifier.width(CinefinSpacing.Space2))
                        CinefinSourceBadge(text = badge)
                    }
                }
                if (secondary != null) {
                    Spacer(Modifier.height(CinefinSpacing.Space1))
                    Text(
                        text = secondary,
                        style = CinefinType.BodySmall,
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (trailing != null) {
                Spacer(Modifier.width(CinefinSpacing.Space3))
                trailing()
            }
        }
        if (showDivider) {
            Box(
                modifier =
                    Modifier.align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(colors.outlineVariant)
            )
        }
    }
}

/**
 * W37 来源徽标：1dp 媒体色描边 + `Media.Container` 底的小标签（不引入第 4 种颜色）。
 *
 * 用于音乐曲库「全部」来源下区分本地 / 服务器曲目；可用偏好关闭（关闭后调用方传 null）。
 */
@Composable
fun CinefinSourceBadge(text: String, modifier: Modifier = Modifier) {
    val media = LocalMediaColors.current
    val colors = LocalCinefinColors.current
    Box(
        modifier =
            modifier
                .clip(CinefinShapes.TwoXs)
                .background(media.container)
                .border(1.dp, media.outline, CinefinShapes.TwoXs)
                .padding(horizontal = 6.dp, vertical = 1.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = CinefinType.LabelSmall,
            color = media.bright,
            maxLines = 1,
        )
    }
}

/** 卡片顶部内高光：`inset 0 1px 0 rgba(255,255,255,.05)`（§5.2）。 */
private fun Modifier.cinefinTopHighlight(color: Color): Modifier = drawBehind {
    drawRect(
        color = color,
        size = Size(size.width, 1.dp.toPx()),
    )
}
