package com.zhangwenkang.cinefin.core.presentation.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinShapes
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinTheme
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinTokens
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinType
import com.zhangwenkang.cinefin.core.presentation.theme.ContentDomain
import com.zhangwenkang.cinefin.core.presentation.theme.LocalCinefinColors
import com.zhangwenkang.cinefin.core.presentation.theme.LocalMediaColors

private val PreviewDomains = listOf(ContentDomain.Movie, ContentDomain.Music, ContentDomain.Book)

@Composable
private fun PreviewIcon(tint: Color) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        drawRoundRect(color = tint, cornerRadius = CornerRadius(size.minDimension * 0.3f))
    }
}

@Composable
private fun navIconTint(selected: Boolean): Color {
    val media = LocalMediaColors.current
    val colors = LocalCinefinColors.current
    return if (selected) media.bright else colors.onSurfaceVariant
}

private fun previewNavItems(): List<CinefinNavItem> =
    listOf(
        CinefinNavItem(
            label = "首页",
            neutral = true,
            icon = { selected -> PreviewIcon(navIconTint(selected)) },
        ),
        CinefinNavItem(label = "影视", icon = { selected -> PreviewIcon(navIconTint(selected)) }),
        CinefinNavItem(label = "音乐", icon = { selected -> PreviewIcon(navIconTint(selected)) }),
        CinefinNavItem(label = "书架", icon = { selected -> PreviewIcon(navIconTint(selected)) }),
        CinefinNavItem(
            label = "设置",
            neutral = true,
            icon = { selected -> PreviewIcon(navIconTint(selected)) },
        ),
    )

@Composable
private fun ButtonRow() {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CinefinButton(
            text = "播放",
            onClick = {},
            variant = CinefinButtonVariant.Filled,
            size = CinefinButtonSize.Small,
        )
        CinefinButton(
            text = "收藏",
            onClick = {},
            variant = CinefinButtonVariant.Outlined,
            size = CinefinButtonSize.Small,
        )
        CinefinButton(
            text = "更多",
            onClick = {},
            variant = CinefinButtonVariant.Text,
            size = CinefinButtonSize.Small,
        )
        CinefinIconButton(onClick = {}, icon = { PreviewIcon(it) })
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CinefinButton(text = "禁用", onClick = {}, enabled = false, size = CinefinButtonSize.Small)
        CinefinButton(
            text = "描边禁用",
            onClick = {},
            variant = CinefinButtonVariant.Outlined,
            enabled = false,
            size = CinefinButtonSize.Small,
        )
    }
}

@Preview(name = "按钮 · 三域 × 变形", widthDp = 460, heightDp = 460)
@Composable
private fun CinefinButtonDomainsPreview() {
    CinefinTheme(domain = ContentDomain.Neutral) {
        Column(
            modifier = Modifier.fillMaxSize().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            PreviewDomains.forEach { domain ->
                CinefinTheme(domain = domain, surfaceBackground = false) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "${domain.name} · 当前域媒体色",
                            style = CinefinType.LabelMedium,
                            color = LocalCinefinColors.current.onSurfaceVariant,
                        )
                        ButtonRow()
                    }
                }
            }
        }
    }
}

private fun stateLabel(state: CinefinInteractionState): String =
    when (state) {
        CinefinInteractionState.Default -> "默认"
        CinefinInteractionState.Hover -> "悬停"
        CinefinInteractionState.Pressed -> "按下"
        CinefinInteractionState.Focused -> "聚焦"
        CinefinInteractionState.Disabled -> "禁用"
    }

@Composable
private fun ButtonStateMatrixContent() {
    val media = LocalMediaColors.current
    val colors = LocalCinefinColors.current
    Column(
        modifier = Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = "4 变形 × 5 状态（默认 / 悬停 / 按下 / 聚焦 / 禁用）",
            style = CinefinType.LabelMedium,
            color = colors.onSurfaceVariant,
        )
        CinefinButtonVariant.entries.forEach { variant ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CinefinInteractionState.entries.forEach { state ->
                    val resolved = resolveButtonColors(variant, state, media, colors)
                    val shape = RoundedCornerShape(12.dp)
                    val focused = state == CinefinInteractionState.Focused
                    Box(
                        modifier =
                            Modifier.size(width = 88.dp, height = 44.dp)
                                .clip(shape)
                                .background(resolved.container)
                                .border(
                                    width = if (focused) 2.dp else 1.dp,
                                    color =
                                        if (focused) {
                                            media.base.copy(alpha = CinefinTokens.FocusRingAlpha)
                                        } else {
                                            resolved.border
                                        },
                                    shape = shape,
                                ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stateLabel(state),
                            style = CinefinType.LabelSmall,
                            color = resolved.content,
                        )
                    }
                }
            }
        }
    }
}

@Preview(name = "按钮 · 五状态矩阵", widthDp = 560, heightDp = 320)
@Composable
private fun CinefinButtonStateMatrixPreview() {
    CinefinTheme(domain = ContentDomain.Movie) { ButtonStateMatrixContent() }
}

@Composable
private fun CardPreviewContent() {
    val media = LocalMediaColors.current
    val colors = LocalCinefinColors.current
    Column(
        modifier = Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            CinefinPosterCard(
                title = "银翼杀手 2049",
                meta = "科幻 · 2017",
                modifier = Modifier.weight(1f),
            ) {
                Box(modifier = Modifier.fillMaxSize().background(media.container))
            }
            CinefinPosterCard(
                title = "海街日记",
                meta = "剧情 · 2015",
                modifier = Modifier.weight(1f),
                selected = true,
            ) {
                Box(modifier = Modifier.fillMaxSize().background(colors.surfaceContainerHigh))
            }
        }
        CinefinWideCard(title = "继续观看 · 第 4 集", progress = 0.42f) {
            Box(modifier = Modifier.fillMaxSize().background(colors.surfaceContainerHigh))
        }
        CinefinListRow(
            title = "当前播放",
            secondary = "影视域 · 琥珀",
            isCurrent = true,
            trailing = { PreviewIcon(media.bright) },
            leading = {
                Box(
                    modifier =
                        Modifier.size(46.dp).clip(CinefinShapes.Xs).background(media.container)
                )
            },
        )
        CinefinListRow(
            title = "常规行",
            secondary = "中性 · 无媒体色",
            showDivider = false,
            trailing = { PreviewIcon(colors.onSurfaceVariant) },
        )
    }
}

@Preview(name = "卡片 · 海报 / 横版 / 列表", widthDp = 520, heightDp = 660)
@Composable
private fun CinefinCardPreview() {
    CinefinTheme(domain = ContentDomain.Movie) { CardPreviewContent() }
}

@Composable
private fun NavigationPreviewContent() {
    val colors = LocalCinefinColors.current
    Row(modifier = Modifier.fillMaxSize()) {
        CinefinSideRail(
            items = previewNavItems(),
            selectedIndex = 1,
            onSelect = {},
            modifier = Modifier.height(360.dp),
            expanded = true,
        )
        Spacer(Modifier.width(16.dp))
        CinefinSideRail(
            items = previewNavItems(),
            selectedIndex = 0,
            onSelect = {},
            modifier = Modifier.height(360.dp),
            expanded = false,
        )
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "底部 Tab（Compact）",
                style = CinefinType.LabelMedium,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(12.dp),
            )
            Spacer(Modifier.weight(1f))
            CinefinBottomTab(items = previewNavItems().take(4), selectedIndex = 1, onSelect = {})
        }
    }
}

@Preview(name = "导航 · 侧轨 / 折叠 / 底部", widthDp = 680, heightDp = 400)
@Composable
private fun CinefinNavigationPreview() {
    CinefinTheme(domain = ContentDomain.Music) { NavigationPreviewContent() }
}

@Composable
private fun DrawerPreviewContent() {
    val colors = LocalCinefinColors.current
    Row(modifier = Modifier.fillMaxSize().background(colors.surface)) {
        Box(modifier = Modifier.width(320.dp).fillMaxHeight().background(colors.surfaceContainer)) {
            CinefinDrawerContent(
                header = {
                    Text(
                        text = "影阁 Cinefin",
                        style = CinefinType.TitleLarge,
                        color = colors.onSurface,
                    )
                },
                groups =
                    listOf(
                        CinefinDrawerGroup(title = "媒体", items = previewNavItems().take(3)),
                        CinefinDrawerGroup(title = "其他", items = previewNavItems().drop(3)),
                    ),
                selectedIndex = 1,
                onSelect = {},
            )
        }
    }
}

@Preview(name = "抽屉 · 320dp", widthDp = 360, heightDp = 520)
@Composable
private fun CinefinDrawerPreview() {
    CinefinTheme(domain = ContentDomain.Book) { DrawerPreviewContent() }
}

@Composable
private fun SelectionPreviewContent() {
    val colors = LocalCinefinColors.current
    var mode by remember { mutableStateOf("分页") }
    var font by remember { mutableStateOf("无衬线") }
    Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text(
            text = "分段控件（§8.2）",
            style = CinefinType.LabelSmall,
            color = colors.onSurfaceFaint,
        )
        CinefinSegmentedControl(
            items = listOf("滚动", "分页", "双栏"),
            selected = mode,
            onSelect = { mode = it },
            label = { it },
        )
        Text(
            text = "筛选 Chip（§8.3）",
            style = CinefinType.LabelSmall,
            color = colors.onSurfaceFaint,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("默认", "无衬线", "等宽").forEach { option ->
                CinefinFilterChip(
                    text = option,
                    selected = font == option,
                    onClick = { font = option },
                )
            }
            CinefinFilterChip(
                text = "紧凑禁用",
                selected = false,
                onClick = {},
                enabled = false,
                compact = true,
            )
        }
    }
}

@Preview(name = "分段 / Chip · 音乐域", widthDp = 420, heightDp = 260)
@Composable
private fun CinefinSelectionMusicPreview() {
    CinefinTheme(domain = ContentDomain.Music) { SelectionPreviewContent() }
}

@Preview(name = "分段 / Chip · 阅读域", widthDp = 420, heightDp = 260)
@Composable
private fun CinefinSelectionBookPreview() {
    CinefinTheme(domain = ContentDomain.Book) { SelectionPreviewContent() }
}

@Composable
private fun EmptyStatePreviewContent() {
    CinefinEmptyState(
        title = "音乐库里还没有专辑",
        message = "在服务器添加音乐后点「刷新」重新拉取",
        icon = { PreviewIcon(it) },
        action = { CinefinButton(text = "刷新", onClick = {}, size = CinefinButtonSize.Medium) },
        secondaryAction = {
            CinefinButton(
                text = "稍后",
                onClick = {},
                variant = CinefinButtonVariant.Text,
                size = CinefinButtonSize.Medium,
            )
        },
    )
}

@Preview(name = "空状态 · 音乐域", widthDp = 640, heightDp = 360)
@Composable
private fun CinefinEmptyStatePreview() {
    CinefinTheme(domain = ContentDomain.Music) { EmptyStatePreviewContent() }
}
