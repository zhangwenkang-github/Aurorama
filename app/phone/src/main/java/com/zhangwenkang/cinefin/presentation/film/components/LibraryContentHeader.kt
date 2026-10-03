package com.zhangwenkang.cinefin.presentation.film.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zhangwenkang.cinefin.core.R as CoreR
import com.zhangwenkang.cinefin.core.presentation.components.CinefinFilterChip
import com.zhangwenkang.cinefin.core.presentation.components.CinefinSelectIndicator
import com.zhangwenkang.cinefin.core.presentation.components.cinefinClickable
import com.zhangwenkang.cinefin.core.presentation.components.cinefinSelectable
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinShapes
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinSpacing
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinType
import com.zhangwenkang.cinefin.core.presentation.theme.LocalCinefinColors
import com.zhangwenkang.cinefin.core.presentation.theme.LocalMediaColors
import com.zhangwenkang.cinefin.film.R as FilmR
import com.zhangwenkang.cinefin.film.presentation.library.LibraryFilter
import com.zhangwenkang.cinefin.film.presentation.library.LibraryTab
import com.zhangwenkang.cinefin.film.presentation.library.LibraryToolbarSpec
import com.zhangwenkang.cinefin.film.presentation.library.LibraryViewMode
import com.zhangwenkang.cinefin.film.presentation.library.libraryFilterLabelRes
import com.zhangwenkang.cinefin.film.presentation.library.libraryFilters
import com.zhangwenkang.cinefin.models.CollectionType
import com.zhangwenkang.cinefin.models.FindroidEpisode
import com.zhangwenkang.cinefin.models.FindroidItem
import com.zhangwenkang.cinefin.models.FindroidTag
import com.zhangwenkang.cinefin.models.isDownloaded
import com.zhangwenkang.cinefin.presentation.components.TopBarAction

/**
 * 库内容页头部（W54-B）：视频库 / 书籍库 / 书架三处入口**共用这一份实现**。
 *
 * 结构 = 顶部 tabs（按库类型出现）→ 工具行（条目计数 / 网格列表 / 排序 / 筛选）→ 生效中的筛选 chips。 只使用既有语义 token（`CinefinSpacing` /
 * `CinefinType` / 当前域媒体色）与既有组件，不新增配色 / 字体 / 位图。
 */

/** tab 文案：「库名」tab 直接用库名，其余走 film 模块的固定文案。 */
@Composable
fun libraryTabLabel(tab: LibraryTab, libraryName: String): String =
    when (tab) {
        LibraryTab.Library -> libraryName
        LibraryTab.Suggestions -> stringResource(FilmR.string.library_tab_suggestions)
        LibraryTab.Upcoming -> stringResource(FilmR.string.library_tab_upcoming)
        LibraryTab.Genres -> stringResource(FilmR.string.library_tab_genres)
        LibraryTab.Studios -> stringResource(FilmR.string.library_tab_studios)
        LibraryTab.Episodes -> stringResource(FilmR.string.library_tab_episodes)
    }

/** 顶部 tabs：横向可滚动的 chip 行（库名 / 建议 / 即将播出 / 类型 / 制片发行商 / 剧集），下方一条弱分隔线。 */
@Composable
fun LibraryTabRow(
    tabs: List<LibraryTab>,
    selected: LibraryTab,
    libraryName: String,
    onSelect: (LibraryTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalCinefinColors.current
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier =
                Modifier.fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(end = CinefinSpacing.Space5),
            horizontalArrangement = Arrangement.spacedBy(CinefinSpacing.Space2),
        ) {
            tabs.forEach { tab ->
                CinefinFilterChip(
                    text = libraryTabLabel(tab, libraryName),
                    selected = tab == selected,
                    onClick = { onSelect(tab) },
                    compact = true,
                )
            }
        }
        Spacer(Modifier.height(CinefinSpacing.Space2))
        HorizontalDivider(color = colors.outlineVariant, thickness = 1.dp)
    }
}

/** 工具行：条目计数「1-94 / 94」+ 网格 / 列表 + 排序 + 筛选（按 tab 决定显示哪几个动作）。 */
@Composable
fun LibraryToolbarRow(
    countText: String,
    viewMode: LibraryViewMode,
    spec: LibraryToolbarSpec,
    filterActive: Boolean,
    onViewModeChange: (LibraryViewMode) -> Unit,
    onSortClick: () -> Unit,
    onFilterClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalCinefinColors.current
    val media = LocalMediaColors.current
    Row(
        modifier = modifier.fillMaxWidth().heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = countText,
            style = CinefinType.LabelMedium,
            color = colors.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.weight(1f))
        if (spec.showViewMode) {
            LibraryIconToggle(
                icon = CoreR.drawable.ic_view_grid,
                selected = viewMode == LibraryViewMode.Grid,
                contentDescription = stringResource(FilmR.string.library_view_grid),
                onClick = { onViewModeChange(LibraryViewMode.Grid) },
            )
            LibraryIconToggle(
                icon = CoreR.drawable.ic_view_list,
                selected = viewMode == LibraryViewMode.List,
                contentDescription = stringResource(FilmR.string.library_view_list),
                onClick = { onViewModeChange(LibraryViewMode.List) },
            )
            Spacer(Modifier.width(CinefinSpacing.Space1))
        }
        if (spec.showSort) {
            TopBarAction(
                icon = CoreR.drawable.ic_arrow_down_up,
                onClick = onSortClick,
                contentDescription = stringResource(FilmR.string.library_sort),
            )
        }
        if (spec.showFilter) {
            TopBarAction(
                icon = CoreR.drawable.ic_filter,
                onClick = onFilterClick,
                contentDescription = stringResource(FilmR.string.library_filter),
                tint = if (filterActive) media.bright else null,
            )
        }
    }
}

/** 工具行里的图标开关：选中 = `SurfaceContainerHigh` 底 + 媒体色图标（§2.6 第 9 条：未选中不着色）。 */
@Composable
private fun LibraryIconToggle(
    icon: Int,
    selected: Boolean,
    contentDescription: String,
    onClick: () -> Unit,
) {
    val colors = LocalCinefinColors.current
    val media = LocalMediaColors.current
    Box(
        modifier =
            Modifier.size(44.dp)
                .clip(CinefinShapes.Sm)
                .then(
                    if (selected) {
                        Modifier.background(colors.surfaceContainerHigh)
                    } else {
                        Modifier
                    }
                )
                .cinefinClickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = contentDescription,
            tint = if (selected) media.bright else colors.onSurfaceVariant,
        )
    }
}

/** 生效中的筛选 / 库内过滤 chips（点一下即清除）；为空时不占高度。 */
@Composable
fun LibraryActiveChipRow(chips: List<Pair<String, () -> Unit>>, modifier: Modifier = Modifier) {
    if (chips.isEmpty()) return
    Row(
        modifier = modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(CinefinSpacing.Space2),
    ) {
        chips.forEach { (label, onClear) ->
            CinefinFilterChip(
                text = label,
                selected = true,
                onClick = onClear,
                compact = true,
                icon = { tint ->
                    Icon(
                        painter = painterResource(CoreR.drawable.ic_close),
                        contentDescription = null,
                        tint = tint,
                        modifier = Modifier.size(14.dp),
                    )
                },
            )
        }
    }
}

/** 筛选面板（funnel）：常用筛选集合（全部 / 未看 / 已看 / 收藏，书籍库读作未读 / 已读）。 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun LibraryFilterPanel(
    libraryType: CollectionType,
    selected: LibraryFilter?,
    onSelect: (LibraryFilter?) -> Unit,
    onDismissRequest: () -> Unit,
    sheetState: SheetState = rememberModalBottomSheetState(),
) {
    val colors = LocalCinefinColors.current
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        containerColor = colors.surfaceContainerHigh,
    ) {
        Column(
            modifier =
                Modifier.fillMaxWidth()
                    .padding(horizontal = CinefinSpacing.Space6)
                    .padding(bottom = CinefinSpacing.Space8)
        ) {
            Text(
                text = stringResource(FilmR.string.library_filter),
                style = CinefinType.HeadlineSmall,
                color = colors.onSurface,
            )
            Spacer(Modifier.height(CinefinSpacing.Space2))
            Text(
                text = stringResource(FilmR.string.library_filter_panel_hint),
                style = CinefinType.BodySmall,
                color = colors.onSurfaceVariant,
            )
            Spacer(Modifier.height(CinefinSpacing.Space4))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(CinefinSpacing.Space2),
                verticalArrangement = Arrangement.spacedBy(CinefinSpacing.Space1),
            ) {
                CinefinFilterChip(
                    text = stringResource(FilmR.string.library_filter_all),
                    selected = selected == null,
                    onClick = { onSelect(null) },
                )
                libraryFilters(libraryType).forEach { filter ->
                    CinefinFilterChip(
                        text = stringResource(libraryFilterLabelRes(libraryType, filter)),
                        selected = selected == filter,
                        onClick = { onSelect(filter) },
                    )
                }
            }
        }
    }
}

/**
 * 列表视图行（W54-B）：56dp 宽竖版缩略图 + 标题 + 一行元信息，徽标贴在缩略图右上角。
 *
 * W58b：多选态在行首加 20dp 勾选指示（§8.5），长按经 [onLongClick] 进入多选（默认 null = 不参与多选）。
 */
@Composable
fun LibraryListRow(
    item: FindroidItem,
    onClick: (FindroidItem) -> Unit,
    modifier: Modifier = Modifier,
    selectionMode: Boolean = false,
    selected: Boolean = false,
    onLongClick: (() -> Unit)? = null,
) {
    val colors = LocalCinefinColors.current
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = 88.dp)
                .then(
                    if (onLongClick != null) {
                        Modifier.cinefinSelectable(
                            selected = selected,
                            onClick = { onClick(item) },
                            onLongPress = onLongClick,
                        )
                    } else {
                        Modifier.cinefinClickable { onClick(item) }
                    }
                )
                .padding(vertical = CinefinSpacing.Space2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selectionMode) {
            CinefinSelectIndicator(selected = selected)
            Spacer(Modifier.width(CinefinSpacing.Space3))
        }
        Box(modifier = Modifier.width(56.dp).clip(CinefinShapes.Xs)) {
            ItemPoster(
                item = item,
                direction = Direction.VERTICAL,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier.align(Alignment.TopEnd).padding(CinefinSpacing.Space1),
                horizontalArrangement = Arrangement.spacedBy(CinefinSpacing.Space1),
            ) {
                if (item.isDownloaded()) DownloadedBadge()
                ItemStatusBadge(item)
            }
        }
        Spacer(Modifier.width(CinefinSpacing.Space4))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (item is FindroidEpisode) item.seriesName else item.name,
                style = CinefinType.TitleSmall,
                color = colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            item.metaLine()?.let { meta ->
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
}

/** 分类（类型 / 制片发行商）tile：整块可点，点了回到库名 tab 并带上库内过滤。 */
@Composable
fun LibraryTagTile(
    tag: FindroidTag,
    onClick: (FindroidTag) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalCinefinColors.current
    Box(modifier = modifier.cinefinClickable { onClick(tag) }) {
        LumenCardFrame(
            modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp),
            container = colors.surfaceContainerHigh,
        ) {
            Text(
                text = tag.name,
                style = CinefinType.TitleSmall,
                color = colors.onSurface,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.Center).padding(CinefinSpacing.Space4),
            )
        }
    }
}
