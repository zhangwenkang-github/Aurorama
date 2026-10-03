package com.zhangwenkang.cinefin.core.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinColors
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinShapes
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinSpacing
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinTokens
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinType
import com.zhangwenkang.cinefin.core.presentation.theme.LocalCinefinColors
import com.zhangwenkang.cinefin.core.presentation.theme.LocalLumenColors
import com.zhangwenkang.cinefin.core.presentation.theme.LocalMediaColors
import com.zhangwenkang.cinefin.core.presentation.theme.LumenColors
import com.zhangwenkang.cinefin.core.presentation.theme.MediaColors

/**
 * 导航条目（§8.6）。
 *
 * [neutral] 为 true 表示首页 / 设置等中性项：选中态用 `SurfaceContainerHigh + OnSurface`； 域页项选中态用
 * `Media.Container + Media.Bright`，不出现独立色点 / 色条（底部 Tab 的 24×3dp 指示条是导航指示器，属规范允许的例外）。
 *
 * Lumen 区域（W6-VIS）：条目改走「石墨 / 雾灰层次 + 发丝线 + 顶部内高光」——选中 = 雾灰底 + 白 8.5% 细线 + 月白标签 +
 * 极光青图标（唯一强调色落在"当前焦点"），未选中 = 次级灰，悬停 = 白 7% 幽灵底。
 */
class CinefinNavItem(
    val label: String,
    val neutral: Boolean = false,
    val icon: @Composable (selected: Boolean) -> Unit,
    /** 二级分组子项（媒体库 → 具体库）：抽屉里用缩进 + 紧凑行，与侧轨的子项层级一致。 */
    val nested: Boolean = false,
    /**
     * 尾部槽位（展开式二级分组的折叠箭头等）。侧轨由 `CinefinNavigationItem` 渲染， 抽屉由 `CinefinDrawerItem`
     * 渲染——两侧共用同一份条目定义，避免"侧轨有箭头、抽屉没有"的不一致（W8-R3）。
     */
    val trailing: (@Composable () -> Unit)? = null,
)

/**
 * 侧导航（W42 改版：折叠 72dp；W46 展开 150 → 168dp）。
 *
 * 底色改半透明石墨（[CinefinTokens.RailTranslucency]，W46 起 74%）+ 右缘 1dp 发丝线 + 顶缘内高光；分组之间用 [groupBreaks] 插入
 * 12dp 空隙与细分隔线，末尾 [pinnedTailCount] 条（客户端设置区）用发丝线与导航区分隔。
 */
@Composable
fun CinefinSideRail(
    items: List<CinefinNavItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    expanded: Boolean = true,
    /** 每组**最后一项**的索引：其后插入分组空隙 + 细分隔线（W42）。 */
    groupBreaks: Set<Int> = emptySet(),
    /** 末尾固定到底部的条目数（客户端设置区），与导航区之间用发丝线分隔（W42）。 */
    pinnedTailCount: Int = 0,
) {
    val colors = LocalCinefinColors.current
    val lumen = LocalLumenColors.current
    Column(
        modifier =
            modifier
                .width(if (expanded) 168.dp else 72.dp)
                .fillMaxHeight()
                // 半透明石墨需要确定的衬底：先铺页底（曜石黑）再叠 74% 面板，否则侧柜容器的同色底
                // 会把透明度"吃掉"，真机像素采样看不到透出。
                .background(lumen?.background ?: colors.surface)
                .background(
                    lumen?.panel?.copy(alpha = CinefinTokens.RailTranslucency) ?: colors.navSurface
                )
                .drawBehind {
                    val stroke = 1.dp.toPx()
                    drawRect(
                        color = lumen?.line ?: colors.outline,
                        topLeft = Offset(size.width - stroke, 0f),
                        size = Size(stroke, size.height),
                    )
                    if (lumen != null) {
                        // 顶部内高光：侧轨上缘被光扫过一条 1px 细线，避免"整块纯色板"的单调
                        drawRect(
                            brush = lumenEdgeHighlight(lumen),
                            size = Size(size.width, 1.dp.toPx()),
                        )
                    }
                }
                .padding(
                    horizontal = if (expanded) 10.dp else 12.dp,
                    vertical = CinefinSpacing.Space3,
                ),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items.forEachIndexed { index, item ->
            if (index > 0 && groupBreaks.contains(index - 1)) {
                CinefinRailGroupDivider(expanded = expanded)
            }
            if (pinnedTailCount > 0 && index == items.size - pinnedTailCount) {
                CinefinRailSectionDivider()
            }
            CinefinNavigationItem(
                item = item,
                selected = index == selectedIndex,
                expanded = expanded,
                onClick = { onSelect(index) },
            )
        }
    }
}

/** 侧导航 / 抽屉通用条目（54dp / 56dp 两种高度的样式由调用方 modifier 控制）。 */
@Composable
fun CinefinNavigationItem(
    item: CinefinNavItem,
    selected: Boolean,
    expanded: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** 展开式条目（二级分组父项）的尾部槽位：折叠箭头等。 */
    trailing: (@Composable () -> Unit)? = null,
    /** 二级分组子项用 44dp 紧凑行，避免「父项 + 全部库」把侧轨撑到需要滚动。 */
    compact: Boolean = false,
    /** 条目圆角（与外壳一致，Lumen 选中态的发丝线按同一形状内缩 0.5dp 描边）。 */
    cornerRadius: Dp = 14.dp,
) {
    val media = LocalMediaColors.current
    val colors = LocalCinefinColors.current
    val lumen = LocalLumenColors.current
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val pressed by interactionSource.collectIsPressedAsState()
    val container =
        navItemContainerColor(
            selected = selected,
            neutral = item.neutral,
            hovered = hovered,
            pressed = pressed,
            media = media,
            colors = colors,
            lumen = lumen,
        )
    val content =
        navItemContentColor(
            selected = selected,
            neutral = item.neutral,
            pressed = pressed,
            media = media,
            colors = colors,
            lumen = lumen,
        )
    val shape = RoundedCornerShape(cornerRadius)

    CompositionLocalProvider(
        LocalNavItemInteraction provides
            NavItemInteractionState(selected = selected, hovered = hovered, pressed = pressed)
    ) {
        Row(
            modifier =
                modifier
                    .fillMaxWidth()
                    .height(if (compact) 44.dp else 48.dp)
                    .clip(shape)
                    .background(container)
                    .railSelectionIndicator(
                        color = if (lumen != null) lumen.accent else media.base,
                        selected = selected,
                    )
                    .lumenItemFrame(lumen, selected, cornerRadius)
                    .clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        onClick = onClick,
                    )
                    .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = if (expanded) Arrangement.Start else Arrangement.Center,
        ) {
            Box(modifier = Modifier.size(24.dp)) { item.icon(selected) }
            if (expanded) {
                Spacer(Modifier.width(CinefinSpacing.Space3))
                Text(
                    text = item.label,
                    style = CinefinType.NavLabel,
                    color = content,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = if (trailing != null) Modifier.weight(1f) else Modifier,
                )
                trailing?.invoke()
            }
        }
    }
}

/** 侧栏条目交互状态（W42）：图标槽按选中 / 悬停 / 按下提亮，与容器状态层同步。 */
data class NavItemInteractionState(
    val selected: Boolean = false,
    val hovered: Boolean = false,
    val pressed: Boolean = false,
)

/** 由 [CinefinNavigationItem] / 抽屉条目提供；图标槽（app 侧 `navIcon`）读取它决定着色。 */
val LocalNavItemInteraction = compositionLocalOf { NavItemInteractionState() }

/** 分组之间的 12dp 空隙 + 细分隔线（W42；折叠态两侧内缩，避免 72dp 窄轨上是一条通栏线）。 */
@Composable
fun CinefinRailGroupDivider(expanded: Boolean) {
    val lumen = LocalLumenColors.current
    val colors = LocalCinefinColors.current
    Box(
        modifier =
            Modifier.fillMaxWidth().height(10.dp).drawBehind {
                val stroke = 1.dp.toPx()
                val inset = if (expanded) 0f else 12.dp.toPx()
                drawRect(
                    color = lumen?.lineSoft ?: colors.outlineVariant,
                    topLeft = Offset(inset, size.height - stroke),
                    size = Size((size.width - inset * 2f).coerceAtLeast(0f), stroke),
                )
            }
    )
}

/** 底部设置区与导航区之间的发丝线（W42）。 */
@Composable
fun CinefinRailSectionDivider() {
    val lumen = LocalLumenColors.current
    val colors = LocalCinefinColors.current
    Box(
        modifier =
            Modifier.fillMaxWidth().height(9.dp).drawBehind {
                val stroke = 1.dp.toPx()
                drawRect(
                    color = lumen?.lineSoft ?: colors.outlineVariant,
                    topLeft = Offset(0f, size.height - stroke),
                    size = Size(size.width, stroke),
                )
            }
    )
}

/** 选中条目的 3dp 极光青左缘指示条（W42：行内垂直居中 24dp、两端圆角）。 */
private fun Modifier.railSelectionIndicator(color: Color, selected: Boolean): Modifier {
    if (!selected) return this
    return drawWithContent {
        drawContent()
        val barWidth = 3.dp.toPx()
        val barHeight = 24.dp.toPx()
        drawRoundRect(
            color = color,
            topLeft = Offset(0f, (size.height - barHeight) / 2f),
            size = Size(barWidth, barHeight),
            cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f),
        )
    }
}

/** 底部 Tab（Compact <600dp）：高 64dp；选中指示条 24×3dp 位于图标上方 4dp。 */
@Composable
fun CinefinBottomTab(
    items: List<CinefinNavItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val media = LocalMediaColors.current
    val colors = LocalCinefinColors.current
    val lumen = LocalLumenColors.current
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .height(64.dp)
                .background(lumen?.background ?: colors.surface)
                .background(
                    lumen?.panel?.copy(alpha = CinefinTokens.ChromeTranslucency) ?: colors.surface
                )
                .drawBehind {
                    if (lumen != null) {
                        drawRect(
                            brush = lumenEdgeHighlight(lumen),
                            size = Size(size.width, 1.dp.toPx()),
                        )
                    }
                },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items.forEachIndexed { index, item ->
            val selected = index == selectedIndex
            val content =
                if (lumen != null) {
                    if (selected) lumen.text else lumen.textSecondary
                } else if (selected) {
                    media.bright
                } else {
                    colors.onSurfaceVariant
                }
            Column(
                modifier = Modifier.weight(1f).fillMaxHeight().cinefinClickable { onSelect(index) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Box(
                    modifier =
                        Modifier.width(24.dp)
                            .height(3.dp)
                            .clip(CinefinShapes.TwoXs)
                            .background(
                                if (!selected) {
                                    Color.Transparent
                                } else if (lumen != null) {
                                    lumen.accent
                                } else {
                                    media.base
                                }
                            )
                )
                Spacer(Modifier.height(CinefinSpacing.Space1))
                Box(modifier = Modifier.size(24.dp)) { item.icon(selected) }
                Spacer(Modifier.height(2.dp))
                Text(
                    text = item.label,
                    style = CinefinType.LabelSmall,
                    color = content,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * 侧轨 / 底栏上缘的 1px 内高光（Lumen）：中段最亮、两端收光，读作"边缘被光扫过"。
 *
 * 只画在固定层上，纯白低透明度——不引入媒体色发光（§2.6 / §5.3）。
 */
fun lumenEdgeHighlight(lumen: LumenColors): Brush =
    Brush.horizontalGradient(
        colorStops =
            arrayOf(
                0f to Color.Transparent,
                0.12f to Color.White.copy(alpha = lumen.line.alpha * 1.4f),
                0.5f to Color.White.copy(alpha = lumen.line.alpha * 2.2f),
                0.88f to Color.White.copy(alpha = lumen.line.alpha * 1.4f),
                1f to Color.Transparent,
            )
    )

/** 导航条目底色：Lumen = 雾灰层次（选中雾灰 / 悬停幽灵白 / 按下更实），Prism = 既有状态层。 */
@Composable
internal fun navItemContainerColor(
    selected: Boolean,
    neutral: Boolean,
    hovered: Boolean,
    pressed: Boolean,
    media: MediaColors = LocalMediaColors.current,
    colors: CinefinColors = LocalCinefinColors.current,
    lumen: LumenColors? = LocalLumenColors.current,
): Color =
    if (lumen != null) {
        when {
            // W46：选中容器与侧栏底同 alpha（74%）——不透明会"吃掉"底层的透出，选中行变成一块实心板。
            selected -> lumen.panelElevated.copy(alpha = CinefinTokens.RailTranslucency)
            pressed -> lumen.ghost.copy(alpha = CinefinTokens.StatePressedAlpha)
            hovered -> lumen.ghost
            else -> Color.Transparent
        }
    } else {
        when {
            pressed && (selected || !neutral) -> media.containerPressed
            pressed -> colors.statePressed
            selected && neutral -> colors.surfaceContainerHigh
            selected -> media.container
            hovered -> colors.stateHover
            else -> Color.Transparent
        }
    }

/** 导航条目内容色：Lumen = 月白（选中）/ 次级灰（未选中），Prism = 既有域色语义。 */
@Composable
internal fun navItemContentColor(
    selected: Boolean,
    neutral: Boolean,
    pressed: Boolean,
    media: MediaColors = LocalMediaColors.current,
    colors: CinefinColors = LocalCinefinColors.current,
    lumen: LumenColors? = LocalLumenColors.current,
): Color =
    when {
        lumen != null -> if (selected) lumen.text else lumen.textSecondary
        selected && neutral -> colors.onSurface
        selected -> media.bright
        pressed -> colors.onSurface
        else -> colors.onSurfaceVariant
    }

/** 选中条目的"双层嵌套"外壳：1dp 白 8.5% 发丝线 + 顶部 1px 内高光（A 稿 `.nav .item.active`）。 */
internal fun Modifier.lumenItemFrame(
    lumen: LumenColors?,
    selected: Boolean,
    cornerRadius: Dp,
): Modifier {
    if (lumen == null || !selected) return this
    return this.drawWithContent {
        drawContent()
        drawRect(
            brush = lumenEdgeHighlight(lumen),
            size = Size(size.width, 1.dp.toPx()),
        )
        val stroke = 1.dp.toPx()
        drawRoundRect(
            color = lumen.line,
            topLeft = Offset(stroke / 2f, stroke / 2f),
            size = Size(size.width - stroke, size.height - stroke),
            cornerRadius = CornerRadius(cornerRadius.toPx()),
            style = Stroke(width = stroke),
        )
    }
}
