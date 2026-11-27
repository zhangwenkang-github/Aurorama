package com.zhangwenkang.cinefin.presentation.film.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zhangwenkang.cinefin.core.R as CoreR
import com.zhangwenkang.cinefin.core.presentation.components.CinefinFilterChip
import com.zhangwenkang.cinefin.core.presentation.components.CinefinLibraryTabs
import com.zhangwenkang.cinefin.core.presentation.components.CinefinMoreVertIcon
import com.zhangwenkang.cinefin.core.presentation.components.CinefinSelectIndicator
import com.zhangwenkang.cinefin.core.presentation.components.cinefinClickable
import com.zhangwenkang.cinefin.core.presentation.components.cinefinSelectable
import com.zhangwenkang.cinefin.core.presentation.components.navSelectedContent
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinShapes
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinSpacing
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinType
import com.zhangwenkang.cinefin.core.presentation.theme.LocalCinefinColors
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

/**
 * 库内容页头部（W54-B）：视频库 / 书籍库 / 书架三处入口**共用这一份实现**。
 *
 * 2.0.2 D2 起结构收敛为两行：①`CinefinPageTopBar` 顶栏；②标签行 = [CinefinLibraryTabs] + 行尾弱化计数 + 排序 / 视图切换 /
 * 筛选图标键， 生效中的筛选 chips 保留一行横滑 compact（原独立工具行删除）。只使用既有语义 token（`CinefinSpacing` / `CinefinType` /
 * 当前域媒体色）与既有组件， 不新增配色 / 字体 / 位图。
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

/** 2.0.2 D2：标签行行高 40dp（与 [CinefinLibraryTabs] 同高，顶栏下只留这一行）。 */
private val LibraryTabsRowHeight = 40.dp

/** 2.0.2 D2：标签行右侧图标键可点区域 40dp（图标 24dp，正好吃满行高）。 */
private val LibraryHeaderIconKeySize = 40.dp

/**
 * 2.0.2 D2：库页第二行 = 轻量文本标签行（[CinefinLibraryTabs]）+ 行尾弱化计数 + 排序 / 视图切换 2 个图标键 + ⋮（收纳筛选）。
 *
 * 替代原来的「chip 行 + 独立工具行」两层：计数不再单独占一行（并入标签行尾、字号色值同原工具行计数）；视图切换合成**一个**图标键 （图标 =
 * 点一下会切到的目标视图）。窄屏标签靠横滑兜底，视频库 / 书籍库 / 书架共用同一行。
 */
@Composable
fun LibraryTabsRow(
    tabs: List<LibraryTab>,
    selected: LibraryTab,
    libraryName: String,
    countText: String,
    viewMode: LibraryViewMode,
    spec: LibraryToolbarSpec,
    filterActive: Boolean,
    onSelectTab: (LibraryTab) -> Unit,
    onViewModeChange: (LibraryViewMode) -> Unit,
    onSortClick: () -> Unit,
    onFilterClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalCinefinColors.current
    Row(
        modifier = modifier.fillMaxWidth().height(LibraryTabsRowHeight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 页边距由调用方给（安全区 + pageGutter），标签自身不再叠一层 gutter。
        CinefinLibraryTabs(
            items = tabs,
            selected = selected,
            onSelect = onSelectTab,
            label = { tab -> libraryTabLabel(tab, libraryName) },
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(0.dp),
        )
        if (countText.isNotEmpty()) {
            Text(
                text = countText,
                style = CinefinType.LabelMedium,
                color = colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = CinefinSpacing.Space2),
            )
        }
        if (spec.showViewMode) {
            val switchToList = viewMode == LibraryViewMode.Grid
            LibraryHeaderIconKey(
                icon =
                    if (switchToList) CoreR.drawable.ic_view_list else CoreR.drawable.ic_view_grid,
                contentDescription =
                    stringResource(
                        if (switchToList) FilmR.string.library_view_list
                        else FilmR.string.library_view_grid
                    ),
                onClick = {
                    onViewModeChange(
                        if (switchToList) LibraryViewMode.List else LibraryViewMode.Grid
                    )
                },
            )
        }
        if (spec.showSort) {
            LibraryHeaderIconKey(
                icon = CoreR.drawable.ic_arrow_down_up,
                contentDescription = stringResource(FilmR.string.library_sort),
                onClick = onSortClick,
            )
        }
        if (spec.showFilter) {
            // ⋮ 收纳筛选：一键直达既有筛选面板，筛选生效时换强调色（行为与原工具行同）。
            LibraryOverflowKey(
                contentDescription = stringResource(FilmR.string.library_filter),
                tint = if (filterActive) navSelectedContent(colors) else colors.onSurfaceVariant,
                onClick = onFilterClick,
            )
        }
    }
}

/** 标签行右侧图标键：40dp 可点区域 + 24dp 图标，未选中 `onSurfaceVariant`（与原工具行同一套图标语言）。 */
@Composable
private fun LibraryHeaderIconKey(
    @DrawableRes icon: Int,
    contentDescription: String,
    onClick: () -> Unit,
) {
    val colors = LocalCinefinColors.current
    Box(
        modifier =
            Modifier.size(LibraryHeaderIconKeySize)
                .clip(CinefinShapes.Button)
                .cinefinClickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = contentDescription,
            tint = colors.onSurfaceVariant,
            modifier = Modifier.size(24.dp),
        )
    }
}

/** 标签行右侧「⋮」键（2.0.3 起统一走共享 24dp 矢量图标 CinefinMoreVertIcon）。 */
@Composable
private fun LibraryOverflowKey(
    contentDescription: String,
    tint: Color,
    onClick: () -> Unit,
) {
    Box(
        modifier =
            Modifier.size(LibraryHeaderIconKeySize)
                .clip(CinefinShapes.Button)
                .cinefinClickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier =
                Modifier.clearAndSetSemantics { this.contentDescription = contentDescription }
        ) {
            CinefinMoreVertIcon(contentDescription = contentDescription, tint = tint)
        }
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
    /** W59：书籍封面自动生成结果（本地绝对路径）；null = 服务器图 / 占位。 */
    imageOverride: String? = null,
    /** W59：无图时的类型占位图标（书籍库传 `ic_book`）；null = 保持原有空白底。 */
    @DrawableRes placeholderIconRes: Int? = null,
    /** W60b：下载状态角标；默认无角标，既有调用零改动。 */
    downloadBadge: DownloadBadgeInfo = DownloadBadgeInfo(),
    /** W69b：服务器图加载失败回调（书籍库 / 书架据此触发本地生成回落）。 */
    onServerImageFailed: (() -> Unit)? = null,
    /** W6-T10 书架增强：元信息行覆盖（`作者 · 42%` / `未读`）；null = 沿用 [metaLine]。 */
    metaLineOverride: String? = null,
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
                imageOverride = imageOverride,
                placeholderIconRes = placeholderIconRes,
                onServerImageFailed = onServerImageFailed,
            )
            CardBadgeOverlay(
                item = item,
                downloadBadge = downloadBadge,
                cornerPadding = CinefinSpacing.Space1,
            )
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
            (metaLineOverride ?: item.metaLine())?.let { meta ->
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
