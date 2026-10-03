package com.zhangwenkang.cinefin.presentation.film

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.CombinedLoadStates
import androidx.paging.LoadState
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import com.zhangwenkang.cinefin.core.R as CoreR
import com.zhangwenkang.cinefin.core.presentation.components.CinefinBackToDefaultChip
import com.zhangwenkang.cinefin.core.presentation.components.CinefinEmptyState
import com.zhangwenkang.cinefin.core.presentation.components.CinefinPageTopBar
import com.zhangwenkang.cinefin.core.presentation.dummy.dummyMovies
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinSpacing
import com.zhangwenkang.cinefin.core.presentation.theme.LocalCinefinColors
import com.zhangwenkang.cinefin.film.R as FilmR
import com.zhangwenkang.cinefin.film.presentation.library.LibraryAction
import com.zhangwenkang.cinefin.film.presentation.library.LibraryState
import com.zhangwenkang.cinefin.film.presentation.library.LibraryTab
import com.zhangwenkang.cinefin.film.presentation.library.LibraryViewMode
import com.zhangwenkang.cinefin.film.presentation.library.LibraryViewModel
import com.zhangwenkang.cinefin.film.presentation.library.libraryCountText
import com.zhangwenkang.cinefin.film.presentation.library.libraryFilterLabelRes
import com.zhangwenkang.cinefin.film.presentation.library.libraryTabs
import com.zhangwenkang.cinefin.film.presentation.library.libraryToolbarSpec
import com.zhangwenkang.cinefin.models.CollectionType
import com.zhangwenkang.cinefin.models.FindroidItem
import com.zhangwenkang.cinefin.presentation.components.ErrorDialog
import com.zhangwenkang.cinefin.presentation.components.LibraryGridSkeleton
import com.zhangwenkang.cinefin.presentation.components.LumenSkeletonOverlay
import com.zhangwenkang.cinefin.presentation.film.components.Direction
import com.zhangwenkang.cinefin.presentation.film.components.ErrorCard
import com.zhangwenkang.cinefin.presentation.film.components.ItemCard
import com.zhangwenkang.cinefin.presentation.film.components.LibraryActiveChipRow
import com.zhangwenkang.cinefin.presentation.film.components.LibraryFilterPanel
import com.zhangwenkang.cinefin.presentation.film.components.LibraryListRow
import com.zhangwenkang.cinefin.presentation.film.components.LibraryTabRow
import com.zhangwenkang.cinefin.presentation.film.components.LibraryTagTile
import com.zhangwenkang.cinefin.presentation.film.components.LibraryToolbarRow
import com.zhangwenkang.cinefin.presentation.film.components.SortByPanel
import com.zhangwenkang.cinefin.presentation.navigation.libraryTypeLabelRes
import com.zhangwenkang.cinefin.presentation.theme.CinefinTheme
import com.zhangwenkang.cinefin.presentation.utils.GridCellsAdaptiveWithMinColumns
import com.zhangwenkang.cinefin.presentation.utils.rememberGridGutter
import com.zhangwenkang.cinefin.presentation.utils.rememberPageGutter
import com.zhangwenkang.cinefin.presentation.utils.rememberSafePadding
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * 库内容页（视频库 / 书籍库 / 书架共用同一份实现，W54-B 扩展头部）。
 *
 * 头部 = 顶部 tabs（按库类型出现）+ 工具行（条目计数 / 网格列表 / 排序 / 筛选）+ 生效中的筛选 chips； 内容区 = 分页网格（库名 tab）或单发请求的条目 /
 * 分类网格（建议 / 即将播出 / 类型 / 制片发行商 / 剧集）， 整块内容支持下拉刷新（真实重取）。
 */
@Composable
fun LibraryScreen(
    libraryId: UUID,
    libraryName: String,
    libraryType: CollectionType,
    onItemClick: (item: FindroidItem) -> Unit,
    navigateBack: () -> Unit,
    viewModel: LibraryViewModel = hiltViewModel(),
    /** 顶层模式（书架 Tab 落点，W8-R3）：顶栏显示侧栏入口 +「书架」，**不显示返回箭头与库名**； false = 二级库内容页（从媒体库点库卡进入）：返回箭头 + 库名。 */
    topLevel: Boolean = false,
    /** 侧栏入口（仅顶层模式使用）。 */
    onOpenDrawer: (() -> Unit)? = null,
    /** 临时库视图（W53 追加）：非空 = 顶栏显示「返回默认 ×」胶囊（一键回默认书架 / 库）。 */
    onBackToDefault: (() -> Unit)? = null,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    var initialLoad by rememberSaveable { mutableStateOf(true) }

    LaunchedEffect(true) {
        viewModel.setup(parentId = libraryId, libraryType = libraryType)
        if (initialLoad) {
            viewModel.loadItems()
            initialLoad = false
        }
    }

    LibraryScreenLayout(
        libraryName = libraryName,
        libraryType = libraryType,
        topLevel = topLevel,
        onOpenDrawer = onOpenDrawer,
        onBackToDefault = onBackToDefault,
        state = state,
        onAction = { action ->
            when (action) {
                is LibraryAction.OnItemClick -> onItemClick(action.item)
                is LibraryAction.OnBackClick -> navigateBack()
                else -> Unit
            }
            viewModel.onAction(action)
        },
    )
}

/**
 * 库内容页版式：标题区「库名」+ 头部（tabs / 工具行）+ 内容区（网格 / 列表 / 分类 / 空态 / 骨架）。
 *
 * 手法与数值见 `UI_PLAN.md` D19：竖版海报至少 176dp 宽（手机仍是 2 列但每张更大）、横版卡 300dp、 方形专辑 200dp；列距保持 16 / 26dp
 * 的既有栅格，行距 24dp，底部留白 32dp。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryScreenLayout(
    libraryName: String,
    libraryType: CollectionType,
    topLevel: Boolean,
    onOpenDrawer: (() -> Unit)?,
    onBackToDefault: (() -> Unit)? = null,
    state: LibraryState,
    onAction: (LibraryAction) -> Unit,
) {
    val safePadding = rememberSafePadding()
    val pageGutter = rememberPageGutter()
    val gridGutter = rememberGridGutter()

    val paddingStart = safePadding.start + pageGutter
    val paddingEnd = safePadding.end + pageGutter
    val paddingBottom = safePadding.bottom + pageGutter
    val contentPadding =
        PaddingValues(
            start = paddingStart,
            end = paddingEnd,
            bottom = paddingBottom + CinefinSpacing.Space8,
        )

    /**
     * 按库类型换版式：
     * - 音乐：方形专辑封面（1:1），一屏能看到更多张
     * - 家庭视频 / 播放列表：横版卡片，符合「一段影像 / 一串列表」的直觉
     * - 电影 / 剧集 / 图书 / 混合：竖版海报
     */
    val direction =
        when (libraryType) {
            CollectionType.HomeVideos,
            CollectionType.Playlists -> Direction.HORIZONTAL
            CollectionType.Music -> Direction.SQUARE
            else -> Direction.VERTICAL
        }
    val minColumnSize =
        when (libraryType) {
            CollectionType.Music -> 200.dp
            CollectionType.HomeVideos,
            CollectionType.Playlists -> 300.dp
            else -> 176.dp
        }
    // 骨架屏列数：与真实栅格的窗口分级保持同一量级（骨架只是"格子的节奏"，不追求逐像素一致）
    val skeletonColumns =
        when (val widthDp = LocalConfiguration.current.screenWidthDp) {
            in 0..599 -> 2
            in 600..839 -> 3
            in 840..1199 -> 4
            else -> if (widthDp >= 1600) 6 else 5
        }

    val items = state.items.collectAsLazyPagingItems()
    val tabs = remember(libraryType, state.tabs) { state.tabs.ifEmpty { libraryTabs(libraryType) } }
    val toolbarSpec = remember(state.tab) { libraryToolbarSpec(state.tab) }

    var showSortPanel by remember { mutableStateOf(false) }
    var showFilterPanel by remember { mutableStateOf(false) }
    var showTabErrorDialog by rememberSaveable { mutableStateOf(false) }

    val isTagTab = state.tab == LibraryTab.Genres || state.tab == LibraryTab.Studios
    val tabItems = state.tabItems
    val countText =
        if (state.tab == LibraryTab.Library) {
            libraryCountText(loadedCount = items.itemCount, totalCount = state.totalCount)
        } else {
            libraryCountText(loadedCount = tabItems.size, totalCount = tabItems.size)
        }

    // 生效中的筛选：funnel 选中的常用筛选 + 从分类 tab 点进来的库内过滤（点一下即清除）
    val activeChips = mutableListOf<Pair<String, () -> Unit>>()
    state.filter?.let { filter ->
        activeChips +=
            stringResource(libraryFilterLabelRes(libraryType, filter)) to
                {
                    onAction(LibraryAction.SelectFilter(null))
                }
    }
    state.genre?.let { genre -> activeChips += genre to { onAction(LibraryAction.ClearTag) } }
    state.studio?.let { studio -> activeChips += studio to { onAction(LibraryAction.ClearTag) } }

    // 顶栏（W8-R3 统一）：顶层 = 侧栏入口 +「书架」；二级 = 返回键 + 库名。
    // W54-B：计数移入工具行（「1-94 / 94」），顶栏副题只保留临时库视图的类型前缀。
    Column(modifier = Modifier.fillMaxSize()) {
        CinefinPageTopBar(
            title =
                if (topLevel && onBackToDefault == null) {
                    stringResource(CoreR.string.title_book_shelf)
                } else {
                    libraryName
                },
            subtitle = onBackToDefault?.let { stringResource(libraryTypeLabelRes(libraryType)) },
            onOpenDrawer = if (topLevel) onOpenDrawer else null,
            onBack = if (topLevel) null else ({ onAction(LibraryAction.OnBackClick) }),
            modifier = Modifier.padding(start = safePadding.start),
            actions = {
                if (onBackToDefault != null) {
                    CinefinBackToDefaultChip(onClick = onBackToDefault)
                }
            },
        )

        Spacer(Modifier.height(CinefinSpacing.Space3))

        LibraryTabRow(
            tabs = tabs,
            selected = state.tab,
            libraryName = libraryName,
            onSelect = { onAction(LibraryAction.SelectTab(it)) },
            modifier = Modifier.padding(start = paddingStart, end = paddingEnd),
        )

        Spacer(Modifier.height(CinefinSpacing.Space1))

        LibraryToolbarRow(
            countText = countText,
            viewMode = state.viewMode,
            spec = toolbarSpec,
            filterActive = state.filter != null,
            onViewModeChange = { onAction(LibraryAction.SelectViewMode(it)) },
            onSortClick = { showSortPanel = true },
            onFilterClick = { showFilterPanel = true },
            modifier = Modifier.padding(start = paddingStart, end = paddingEnd),
        )

        LibraryActiveChipRow(
            chips = activeChips,
            modifier = Modifier.padding(start = paddingStart, end = paddingEnd),
        )

        Spacer(Modifier.height(CinefinSpacing.Space1))

        // W54-B：库内容页统一下拉刷新——分页列表走 LazyPagingItems.refresh()（真实重发请求），
        // 计数与当前 tab 由 ViewModel 重取。
        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = {
                items.refresh()
                onAction(LibraryAction.Refresh)
            },
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) {
            when {
                state.tabError != null -> {
                    ErrorCard(
                        onShowStacktrace = { showTabErrorDialog = true },
                        onRetryClick = { onAction(LibraryAction.SelectTab(state.tab)) },
                        modifier =
                            Modifier.align(Alignment.TopCenter)
                                .padding(horizontal = paddingStart)
                                .padding(top = CinefinSpacing.Space2),
                    )
                }
                state.tab == LibraryTab.Library && state.viewMode == LibraryViewMode.List -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = contentPadding,
                        verticalArrangement = Arrangement.spacedBy(CinefinSpacing.Space3),
                    ) {
                        items(count = items.itemCount, key = items.itemKey { it.id }) { index ->
                            val item = items[index]
                            item?.let { loadedItem ->
                                LibraryListRow(
                                    item = loadedItem,
                                    onClick = { onAction(LibraryAction.OnItemClick(loadedItem)) },
                                    modifier = Modifier.animateItem(),
                                )
                            }
                        }
                    }
                }
                state.tab == LibraryTab.Library -> {
                    LazyVerticalGrid(
                        columns =
                            GridCellsAdaptiveWithMinColumns(
                                minSize = minColumnSize,
                                minColumns = 2,
                            ),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = contentPadding,
                        horizontalArrangement = Arrangement.spacedBy(gridGutter),
                        verticalArrangement = Arrangement.spacedBy(CinefinSpacing.Space6),
                    ) {
                        items(count = items.itemCount, key = items.itemKey { it.id }) { index ->
                            val item = items[index]
                            item?.let { loadedItem ->
                                ItemCard(
                                    item = loadedItem,
                                    direction = direction,
                                    onClick = { onAction(LibraryAction.OnItemClick(loadedItem)) },
                                    modifier = Modifier.animateItem(),
                                )
                            }
                        }
                    }
                }
                isTagTab -> {
                    LazyVerticalGrid(
                        columns = GridCellsAdaptiveWithMinColumns(minSize = 160.dp, minColumns = 2),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = contentPadding,
                        horizontalArrangement = Arrangement.spacedBy(gridGutter),
                        verticalArrangement = Arrangement.spacedBy(CinefinSpacing.Space3),
                    ) {
                        items(count = state.tags.size, key = { index -> state.tags[index].id }) {
                            index ->
                            val tag = state.tags[index]
                            LibraryTagTile(
                                tag = tag,
                                onClick = { onAction(LibraryAction.SelectTag(tag)) },
                                modifier = Modifier.animateItem(),
                            )
                        }
                    }
                }
                state.viewMode == LibraryViewMode.List -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = contentPadding,
                        verticalArrangement = Arrangement.spacedBy(CinefinSpacing.Space3),
                    ) {
                        items(count = tabItems.size, key = { index -> tabItems[index].id }) { index
                            ->
                            val item = tabItems[index]
                            LibraryListRow(
                                item = item,
                                onClick = { onAction(LibraryAction.OnItemClick(item)) },
                                modifier = Modifier.animateItem(),
                            )
                        }
                    }
                }
                else -> {
                    LazyVerticalGrid(
                        columns =
                            GridCellsAdaptiveWithMinColumns(
                                minSize = minColumnSize,
                                minColumns = 2,
                            ),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = contentPadding,
                        horizontalArrangement = Arrangement.spacedBy(gridGutter),
                        verticalArrangement = Arrangement.spacedBy(CinefinSpacing.Space6),
                    ) {
                        items(count = tabItems.size, key = { index -> tabItems[index].id }) { index
                            ->
                            val item = tabItems[index]
                            ItemCard(
                                item = item,
                                direction = direction,
                                onClick = { onAction(LibraryAction.OnItemClick(item)) },
                                modifier = Modifier.animateItem(),
                            )
                        }
                    }
                }
            }

            ErrorGroup(
                loadStates = items.loadState,
                onRefresh = { items.refresh() },
                modifier =
                    Modifier.align(Alignment.TopCenter)
                        .padding(horizontal = paddingStart)
                        .padding(top = CinefinSpacing.Space2),
            )

            // 空库不再是一块空白：加载完成且 0 条时给空态（书架解析到空壳书库时也走这里）
            val isEmpty =
                when {
                    isTagTab -> !state.tabLoading && state.tabError == null && state.tags.isEmpty()
                    state.tab == LibraryTab.Library ->
                        !state.isLoading &&
                            items.itemCount == 0 &&
                            items.loadState.refresh is LoadState.NotLoading
                    else -> !state.tabLoading && state.tabError == null && tabItems.isEmpty()
                }
            if (isEmpty) {
                CinefinEmptyState(
                    title =
                        if (state.tab == LibraryTab.Library) {
                            stringResource(FilmR.string.library_empty_title)
                        } else {
                            stringResource(FilmR.string.library_tab_empty_title)
                        },
                    message =
                        if (state.tab == LibraryTab.Library) {
                            stringResource(FilmR.string.library_empty_message)
                        } else {
                            stringResource(FilmR.string.library_tab_empty_message)
                        },
                    modifier = Modifier.align(Alignment.Center).padding(horizontal = paddingStart),
                )
            }

            // 内容加载过渡（W6-VIS D24）：数据到达前铺同版式的骨架格，避免"黑板直出"
            val showSkeleton =
                when {
                    isTagTab -> state.tabLoading
                    state.tab == LibraryTab.Library ->
                        items.loadState.refresh is LoadState.Loading && items.itemCount == 0
                    else -> state.tabLoading
                }
            LumenSkeletonOverlay(visible = showSkeleton) {
                LibraryGridSkeleton(
                    columns = skeletonColumns,
                    tileHeight =
                        when (direction) {
                            Direction.HORIZONTAL -> 170.dp
                            Direction.SQUARE -> 200.dp
                            else -> 264.dp
                        },
                    gutterStart = paddingStart,
                    gutterEnd = paddingEnd,
                    modifier =
                        Modifier.fillMaxSize()
                            .padding(top = CinefinSpacing.Space4)
                            .background(LocalCinefinColors.current.surface),
                )
            }
        }
    }

    if (showSortPanel) {
        SortByPanel(
            currentSortBy = state.sortBy,
            currentSortOrder = state.sortOrder,
            onUpdate = { sortBy, sortOrder ->
                onAction(LibraryAction.ChangeSorting(sortBy, sortOrder))
            },
            onDismissRequest = { showSortPanel = false },
        )
    }

    if (showFilterPanel) {
        LibraryFilterPanel(
            libraryType = libraryType,
            selected = state.filter,
            onSelect = { filter ->
                onAction(LibraryAction.SelectFilter(filter))
                showFilterPanel = false
            },
            onDismissRequest = { showFilterPanel = false },
        )
    }

    if (showTabErrorDialog) {
        state.tabError?.let { error ->
            ErrorDialog(exception = error, onDismissRequest = { showTabErrorDialog = false })
        }
    }
}

@Composable
private fun ErrorGroup(
    loadStates: CombinedLoadStates,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showErrorDialog by rememberSaveable { mutableStateOf(false) }

    val loadStateError =
        when {
            loadStates.refresh is LoadState.Error -> {
                loadStates.refresh as LoadState.Error
            }
            loadStates.prepend is LoadState.Error -> {
                loadStates.prepend as LoadState.Error
            }
            loadStates.append is LoadState.Error -> {
                loadStates.append as LoadState.Error
            }
            else -> null
        }

    loadStateError?.let {
        ErrorCard(
            onShowStacktrace = { showErrorDialog = true },
            onRetryClick = onRefresh,
            modifier = modifier,
        )
        if (showErrorDialog) {
            ErrorDialog(exception = it.error, onDismissRequest = { showErrorDialog = false })
        }
    }
}

@PreviewScreenSizes
@Composable
private fun LibraryScreenLayoutPreview() {
    val items: Flow<PagingData<FindroidItem>> = flowOf(PagingData.from(dummyMovies))
    CinefinTheme {
        LibraryScreenLayout(
            libraryName = "Movies",
            libraryType = CollectionType.Movies,
            topLevel = false,
            onOpenDrawer = null,
            state = LibraryState(items = items, tabs = libraryTabs(CollectionType.Movies)),
            onAction = {},
        )
    }
}
