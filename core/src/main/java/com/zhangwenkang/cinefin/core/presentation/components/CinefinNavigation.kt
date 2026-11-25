package com.zhangwenkang.cinefin.core.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinColors
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinShapes
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinSpacing
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinType
import com.zhangwenkang.cinefin.core.presentation.theme.LocalCinefinColors
import com.zhangwenkang.cinefin.core.presentation.theme.LocalMediaColors
import com.zhangwenkang.cinefin.core.presentation.theme.MediaColors
import com.zhangwenkang.cinefin.core.presentation.theme.auroraGradient
import com.zhangwenkang.cinefin.utils.DownloadControlPlaneRules

/**
 * 导航条目（§8.6 + K1）。
 *
 * [neutral] 保留给按压态取色（中性项走 `statePressed`），选中态不再区分中性 / 域页：三形态容器统一用 K1 极光 pill（见
 * [navItemContainerColor]）。
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
    /**
     * 图标右上角角标槽位（W51：下载入口的活动任务数）。
     *
     * 侧轨 / 抽屉 / 底栏三处共用同一份定义，避免"侧轨有角标、抽屉没有"的不一致。
     */
    val badge: (@Composable () -> Unit)? = null,
)

/**
 * 侧导航（W42 改版：折叠 72dp；W46 展开 150 → 168dp；W3-T2 换 K1 皮肤）。
 *
 * 底色取页面底 `surface`（bg1，实底、不做实时 blur）+ 右缘 1dp `glassBorder` 发丝线；分组之间用 [groupBreaks] 插入 12dp 空隙与细分隔线，
 * 末尾 [pinnedTailCount] 条（客户端设置区）用发丝线与导航区分隔。
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
    Column(
        modifier =
            modifier
                .width(if (expanded) 168.dp else 72.dp)
                .fillMaxHeight()
                .background(colors.surface)
                .drawBehind {
                    val stroke = 1.dp.toPx()
                    drawRect(
                        color = colors.glassBorder,
                        topLeft = Offset(size.width - stroke, 0f),
                        size = Size(stroke, size.height),
                    )
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
    /** 条目圆角（与外壳一致；K1 定稿 `CinefinShapes.Button`）。 */
    shape: Shape = CinefinShapes.Button,
) {
    val media = LocalMediaColors.current
    val colors = LocalCinefinColors.current
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
        )
    val content =
        navItemContentColor(
            selected = selected,
            pressed = pressed,
            colors = colors,
        )

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
                    .selectionOutline(colors = colors, selected = selected, shape = shape)
                    .clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        onClick = onClick,
                    )
                    .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = if (expanded) Arrangement.Start else Arrangement.Center,
        ) {
            NavIconWithBadge(item = item, selected = selected)
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
    val colors = LocalCinefinColors.current
    Box(
        modifier =
            Modifier.fillMaxWidth().height(10.dp).drawBehind {
                val stroke = 1.dp.toPx()
                val inset = if (expanded) 0f else 12.dp.toPx()
                drawRect(
                    color = colors.outlineVariant,
                    topLeft = Offset(inset, size.height - stroke),
                    size = Size((size.width - inset * 2f).coerceAtLeast(0f), stroke),
                )
            }
    )
}

/** 底部设置区与导航区之间的发丝线（W42）。 */
@Composable
fun CinefinRailSectionDivider() {
    val colors = LocalCinefinColors.current
    Box(
        modifier =
            Modifier.fillMaxWidth().height(9.dp).drawBehind {
                val stroke = 1.dp.toPx()
                drawRect(
                    color = colors.outlineVariant,
                    topLeft = Offset(0f, size.height - stroke),
                    size = Size(size.width, stroke),
                )
            }
    )
}

/** 选中条目的 1dp `glassBorder` 描边（K1：整行 pill 的极光淡色玻璃轮廓）。 */
private fun Modifier.selectionOutline(
    colors: CinefinColors,
    selected: Boolean,
    shape: Shape,
): Modifier = if (selected) border(1.dp, colors.glassBorder, shape) else this

/**
 * 选中 pill 填充（K1 定稿）：`aurora1` 12%。
 *
 * 浅色主题下 12% 在近白底上几乎不可辨，按 W3-T2 任务卡授权只把浅色底提到 16%（判定依据 = 页面底 `surface` 的明度）。
 *
 * 2.0.1 定稿 §2 批② 起这是**全 App 唯一一套选中语言**：`CinefinFilterChip` / `CinefinSegmentedControl` /
 * `CinefinCard` 的选中态都复用它（容器 = 本函数，描边 = `aurora1`，内容 = [navSelectedContent]），单测在
 * `CinefinNavigationSelectionTest`。
 */
internal fun navSelectedFill(colors: CinefinColors): Color =
    colors.aurora1.copy(alpha = if (colors.surface.luminance() > 0.5f) 0.16f else 0.12f)

/**
 * 浅色主题的选中图文色（K1 修正值，2026-11-18）：`aurora1` 加深派生。
 *
 * `aurora1`（#0C9E82）在近白底上只有 3.2:1，浅色主题的选中 pill 里读不清；本值与 [navSelectedFill] 的浅色分支配套（对页面底 #FAFAFB
 * 5.05:1）。
 */
internal val LightNavSelectedContent = Color(0xFF0A6B5A)

/**
 * 选中图文色（K1）：深色主题（aurora / amoled / lumen）= `aurora1`，浅色主题 = [LightNavSelectedContent]。
 *
 * 判据与 [navSelectedFill] 完全一致（页面底 `surface` 的明度），保证三形态容器的选中 pill 与图文永远配套。公开给 app 侧
 * 图标槽（`navIconTint`）复用，避免出现「文字换色、图标没换」的偏差。
 */
fun navSelectedContent(colors: CinefinColors): Color =
    if (colors.surface.luminance() > 0.5f) LightNavSelectedContent else colors.aurora1

/**
 * 底部 Tab（Compact <600dp）：K1 悬浮玻璃条——`glass` 底（半透明、不做实时 blur）+ 1dp `glassBorder` 描边 + 圆角
 * `CinefinShapes.Glass`（32dp），高 64dp。
 *
 * 选中项：图标 + 文字提亮 `textPrimary`，文字下方 20×3dp 极光渐变指示小条；未选中项 `textSecondary`。条目数量与 IA 不变。
 */
@Composable
fun CinefinBottomTab(
    items: List<CinefinNavItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalCinefinColors.current
    val shape = CinefinShapes.Glass
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .height(64.dp)
                .clip(shape)
                .background(colors.glass)
                .border(1.dp, colors.glassBorder, shape),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items.forEachIndexed { index, item ->
            val selected = index == selectedIndex
            val content = if (selected) colors.onSurface else colors.onSurfaceVariant
            Column(
                modifier = Modifier.weight(1f).fillMaxHeight().cinefinClickable { onSelect(index) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                NavIconWithBadge(item = item, selected = selected)
                Spacer(Modifier.height(2.dp))
                Text(
                    text = item.label,
                    style = CinefinType.LabelSmall,
                    color = content,
                    maxLines = 1,
                )
                Spacer(Modifier.height(3.dp))
                // 指示条槽位常驻（未选中不绘制），避免选中切换时整列内容跳动。
                Box(
                    modifier =
                        Modifier.width(20.dp)
                            .height(3.dp)
                            // 底栏选中指示小条（K1 `auroraGradient`）：100° `au1→au2→au3`，3dp 高全圆角。
                            .auroraGradient(
                                colors = colors,
                                cornerRadius = 1.5.dp,
                                visible = selected,
                            )
                )
            }
        }
    }
}

/** 导航图标槽：24dp 图标 + 可选右上角角标（W51 下载活动任务数）。 */
@Composable
private fun NavIconWithBadge(item: CinefinNavItem, selected: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        item.icon(selected)
        item.badge?.let { badge -> Box(modifier = Modifier.padding(start = 4.dp)) { badge() } }
    }
}

/**
 * W51 导航角标：活动任务数（>99 收敛 `99+`）。
 *
 * 中性配色（`OnSurface` 底 / `Surface` 字），不占用媒体色；0 兜底不渲染（调用方仍负责 0 隐藏）。
 */
@Composable
fun CinefinCountBadge(
    count: Int,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    if (count <= 0) return
    val colors = LocalCinefinColors.current
    val semanticsModifier =
        if (contentDescription != null) {
            Modifier.semantics { this.contentDescription = contentDescription }
        } else {
            Modifier
        }
    Box(
        modifier =
            modifier
                .then(semanticsModifier)
                .defaultMinSize(minWidth = 18.dp, minHeight = 18.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(colors.onSurface)
                .padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = DownloadControlPlaneRules.badgeText(count),
            style = CinefinType.LabelSmall,
            color = colors.surface,
            maxLines = 1,
        )
    }
}

/** 导航条目底色：K1 离散选中语言（选中 = `aurora1` 低透明度 pill）；按压 / 悬停沿用既有状态层。 */
@Composable
internal fun navItemContainerColor(
    selected: Boolean,
    neutral: Boolean,
    hovered: Boolean,
    pressed: Boolean,
    media: MediaColors = LocalMediaColors.current,
    colors: CinefinColors = LocalCinefinColors.current,
): Color =
    when {
        pressed && (selected || !neutral) -> media.containerPressed
        pressed -> colors.statePressed
        selected -> navSelectedFill(colors)
        hovered -> colors.stateHover
        else -> Color.Transparent
    }

/** 导航条目内容色（K1）：选中 [navSelectedContent]（图标与文字同色），按压回 `OnSurface`，其余 `textSecondary`。 */
@Composable
internal fun navItemContentColor(
    selected: Boolean,
    pressed: Boolean,
    colors: CinefinColors = LocalCinefinColors.current,
): Color =
    when {
        selected -> navSelectedContent(colors)
        pressed -> colors.onSurface
        else -> colors.onSurfaceVariant
    }
