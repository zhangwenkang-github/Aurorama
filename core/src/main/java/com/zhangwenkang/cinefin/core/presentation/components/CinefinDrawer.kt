package com.zhangwenkang.cinefin.core.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DrawerState
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinSpacing
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinTokens
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinType
import com.zhangwenkang.cinefin.core.presentation.theme.LocalCinefinColors
import com.zhangwenkang.cinefin.core.presentation.theme.LocalLumenColors
import com.zhangwenkang.cinefin.core.presentation.theme.LocalMediaColors

/** 抽屉分组：`title = null` 表示不画分组标题。 */
class CinefinDrawerGroup(
    val title: String?,
    val items: List<CinefinNavItem>,
)

/**
 * Cinefin 抽屉（§8.6）：宽 320dp，底 = 页底衬底 + 石墨面板 @74%（W46，与平板侧轨同一透明度），header 96dp，条目 56dp / 圆角 12dp， 分组标题
 * `LabelSmall + OnSurfaceFaint`；无投影（`tonalElevation = 0`），遮罩用 `Scrim`。
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
    /**
     * 抽屉内容的皮肤注入点（W6-VIS）：影视域页面用 `ProvideLumenColors` 包一层，音乐 / 阅读域保持 Prism。
     *
     * 只作用于抽屉本身，不影响 `content`（页面内容），因此抽屉的皮肤能与当前页面一致而不"污染"音乐 / 阅读页面。
     */
    drawerSkin: @Composable (@Composable () -> Unit) -> Unit = { it() },
    content: @Composable () -> Unit,
) {
    val colors = LocalCinefinColors.current
    ModalNavigationDrawer(
        drawerState = drawerState,
        modifier = modifier,
        gesturesEnabled = gesturesEnabled,
        scrimColor = colors.scrim,
        drawerContent = {
            drawerSkin {
                val skin = LocalLumenColors.current
                ModalDrawerSheet(
                    modifier =
                        Modifier.width(320.dp)
                            // W46：与侧轨同款 74% 半透明——先铺页底衬底再叠面板，否则透明度会被抽屉壳的
                            // 同色底吃掉（踩坑 57）。
                            .background(skin?.background ?: colors.surface)
                            .background(
                                skin?.panel?.copy(alpha = CinefinTokens.RailTranslucency)
                                    ?: colors.surfaceContainer
                            ),
                    drawerShape = RectangleShape,
                    // 底色由上一条 modifier 的两层 background 提供（容器色必须是透明，否则会盖住半透明层）。
                    drawerContainerColor = Color.Transparent,
                    drawerContentColor = skin?.text ?: colors.onSurface,
                    drawerTonalElevation = 0.dp,
                ) {
                    CinefinDrawerContent(
                        header = header,
                        groups = groups,
                        selectedIndex = selectedIndex,
                        onSelect = onSelect,
                    )
                }
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
    val lumen = LocalLumenColors.current
    Column(modifier = Modifier.fillMaxHeight()) {
        Box(
            modifier =
                Modifier.fillMaxWidth()
                    .height(96.dp)
                    .drawBehind {
                        if (lumen != null) {
                            val stroke = 1.dp.toPx()
                            drawRect(
                                color = lumen.line,
                                topLeft = Offset(0f, size.height - stroke),
                                size = Size(size.width, stroke),
                            )
                        }
                    }
                    .padding(horizontal = CinefinSpacing.Space5),
            contentAlignment = Alignment.CenterStart,
        ) {
            header()
        }
        // 条目区可滚动：媒体库默认展开后抽屉条目变多（库列表跟在「媒体库」之后），
        // 矮屏（横屏手机）不能把底部条目裁掉。
        Column(modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
            var flatIndex = 0
            groups.forEach { group ->
                if (group.title != null) {
                    Text(
                        text = group.title,
                        style = CinefinType.LabelSmall,
                        color = lumen?.textFaint ?: colors.onSurfaceFaint,
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
    }
}

/** 抽屉条目：56dp 高、圆角 12dp、左右 16dp 内边距；二级子项（[CinefinNavItem.nested]）48dp + 缩进。 */
@Composable
private fun CinefinDrawerItem(
    item: CinefinNavItem,
    selected: Boolean,
    onClick: () -> Unit,
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
    val shape = RoundedCornerShape(12.dp)

    CompositionLocalProvider(
        LocalNavItemInteraction provides
            NavItemInteractionState(selected = selected, hovered = hovered, pressed = pressed)
    ) {
        Row(
            modifier =
                Modifier.fillMaxWidth()
                    .padding(
                        start =
                            CinefinSpacing.Space3 +
                                if (item.nested) CinefinSpacing.Space4 else 0.dp,
                        end = CinefinSpacing.Space3,
                    )
                    .height(if (item.nested) 48.dp else 56.dp)
                    .clip(shape)
                    .background(container)
                    .lumenItemFrame(lumen, selected, 12.dp)
                    .clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        onClick = onClick,
                    )
                    .padding(horizontal = CinefinSpacing.Space4),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.size(24.dp)) {
                item.icon(selected)
                item.badge?.let { badge ->
                    Box(modifier = Modifier.align(Alignment.TopEnd).offset(x = 7.dp, y = (-7).dp)) {
                        badge()
                    }
                }
            }
            Spacer(Modifier.width(CinefinSpacing.Space4))
            Text(
                text = item.label,
                style = CinefinType.NavLabel,
                color = content,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = if (item.trailing != null) Modifier.weight(1f) else Modifier,
            )
            item.trailing?.invoke()
        }
    }
}
