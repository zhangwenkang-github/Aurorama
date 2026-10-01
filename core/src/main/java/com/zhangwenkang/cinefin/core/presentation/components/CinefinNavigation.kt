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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinShapes
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinSpacing
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinType
import com.zhangwenkang.cinefin.core.presentation.theme.LocalCinefinColors
import com.zhangwenkang.cinefin.core.presentation.theme.LocalMediaColors

/**
 * 导航条目（§8.6）。
 *
 * [neutral] 为 true 表示首页 / 设置等中性项：选中态用 `SurfaceContainerHigh + OnSurface`； 域页项选中态用
 * `Media.Container + Media.Bright`，不出现独立色点 / 色条（底部 Tab 的 24×3dp 指示条是导航指示器，属规范允许的例外）。
 */
class CinefinNavItem(
    val label: String,
    val neutral: Boolean = false,
    val icon: @Composable (selected: Boolean) -> Unit,
)

/** 侧导航（Large / ExtraLarge ≥1200dp：164dp 展开；840–1199dp：88dp 折叠轨）。 */
@Composable
fun CinefinSideRail(
    items: List<CinefinNavItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    expanded: Boolean = true,
) {
    val colors = LocalCinefinColors.current
    Column(
        modifier =
            modifier
                .width(if (expanded) 164.dp else 88.dp)
                .fillMaxHeight()
                .background(colors.navSurface)
                .drawBehind {
                    val stroke = 1.dp.toPx()
                    drawRect(
                        color = colors.outline,
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
) {
    val media = LocalMediaColors.current
    val colors = LocalCinefinColors.current
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val pressed by interactionSource.collectIsPressedAsState()
    val container =
        when {
            pressed && (selected || !item.neutral) -> media.containerPressed
            pressed -> colors.statePressed
            selected && item.neutral -> colors.surfaceContainerHigh
            selected -> media.container
            hovered -> colors.stateHover
            else -> Color.Transparent
        }
    val content =
        when {
            selected && item.neutral -> colors.onSurface
            selected -> media.bright
            pressed -> colors.onSurface
            else -> colors.onSurfaceVariant
        }

    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .height(if (compact) 44.dp else if (expanded) 54.dp else 56.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(container)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onClick,
                )
                .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (expanded) Arrangement.Start else Arrangement.Center,
    ) {
        Box(modifier = Modifier.size(22.dp)) { item.icon(selected) }
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
    Row(
        modifier = modifier.fillMaxWidth().height(64.dp).background(colors.surface),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items.forEachIndexed { index, item ->
            val selected = index == selectedIndex
            val content = if (selected) media.bright else colors.onSurfaceVariant
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
                            .background(if (selected) media.base else Color.Transparent)
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
