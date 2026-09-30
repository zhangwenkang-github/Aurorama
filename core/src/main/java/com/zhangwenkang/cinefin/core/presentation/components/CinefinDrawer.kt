package com.zhangwenkang.cinefin.core.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DrawerState
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinSpacing
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinType
import com.zhangwenkang.cinefin.core.presentation.theme.LocalCinefinColors
import com.zhangwenkang.cinefin.core.presentation.theme.LocalMediaColors

/** 抽屉分组：`title = null` 表示不画分组标题。 */
class CinefinDrawerGroup(
    val title: String?,
    val items: List<CinefinNavItem>,
)

/**
 * Cinefin 抽屉（§8.6）：宽 320dp，底 `SurfaceContainer`，header 96dp，条目 56dp / 圆角 12dp， 分组标题 `LabelSmall +
 * OnSurfaceFaint`；无投影（`tonalElevation = 0`），遮罩用 `Scrim`。
 *
 * `selectedIndex` 按分组顺序拍平后计算；域页条目选中态为 `Media.Container + Media.Bright`。
 */
@Composable
fun CinefinModalDrawer(
    drawerState: DrawerState,
    header: @Composable () -> Unit,
    groups: List<CinefinDrawerGroup>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    gesturesEnabled: Boolean = true,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val colors = LocalCinefinColors.current
    ModalNavigationDrawer(
        drawerState = drawerState,
        modifier = modifier,
        gesturesEnabled = gesturesEnabled,
        scrimColor = colors.scrim,
        drawerContent = {
            ModalDrawerSheet(
                modifier = Modifier.width(320.dp),
                drawerShape = RectangleShape,
                drawerContainerColor = colors.surfaceContainer,
                drawerContentColor = colors.onSurface,
                drawerTonalElevation = 0.dp,
            ) {
                CinefinDrawerContent(
                    header = header,
                    groups = groups,
                    selectedIndex = selectedIndex,
                    onSelect = onSelect,
                )
            }
        },
        content = content,
    )
}

/** 抽屉内容（与 Modal 外壳解耦，供预览与自定义容器复用）。 */
@Composable
internal fun CinefinDrawerContent(
    header: @Composable () -> Unit,
    groups: List<CinefinDrawerGroup>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    val colors = LocalCinefinColors.current
    Box(
        modifier =
            Modifier.fillMaxWidth().height(96.dp).padding(horizontal = CinefinSpacing.Space5),
        contentAlignment = Alignment.CenterStart,
    ) {
        header()
    }
    var flatIndex = 0
    groups.forEach { group ->
        if (group.title != null) {
            Text(
                text = group.title,
                style = CinefinType.LabelSmall,
                color = colors.onSurfaceFaint,
                modifier =
                    Modifier.padding(
                        start = CinefinSpacing.Space5,
                        end = CinefinSpacing.Space5,
                        top = CinefinSpacing.Space3,
                        bottom = CinefinSpacing.Space2,
                    ),
            )
        }
        group.items.forEach { item ->
            val index = flatIndex++
            CinefinDrawerItem(
                item = item,
                selected = index == selectedIndex,
                onClick = { onSelect(index) },
            )
        }
    }
    Spacer(Modifier.height(CinefinSpacing.Space4))
}

/** 抽屉条目：56dp 高、圆角 12dp、左右 16dp 内边距。 */
@Composable
private fun CinefinDrawerItem(
    item: CinefinNavItem,
    selected: Boolean,
    onClick: () -> Unit,
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
            else -> colors.onSurfaceVariant
        }

    Row(
        modifier =
            Modifier.fillMaxWidth()
                .padding(horizontal = CinefinSpacing.Space3)
                .height(56.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(container)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onClick,
                )
                .padding(horizontal = CinefinSpacing.Space4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(24.dp)) { item.icon(selected) }
        Spacer(Modifier.width(CinefinSpacing.Space4))
        Text(
            text = item.label,
            style = CinefinType.NavLabel,
            color = content,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
