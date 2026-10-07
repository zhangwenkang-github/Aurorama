package com.zhangwenkang.cinefin.presentation.film

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
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
import androidx.compose.ui.platform.LocalContext
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
import com.zhangwenkang.cinefin.core.presentation.components.CinefinSnackbarHost
import com.zhangwenkang.cinefin.core.presentation.components.DownloadSnackbarDuration
import com.zhangwenkang.cinefin.core.presentation.components.rememberMultiSelectState
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
import com.zhangwenkang.cinefin.models.SortBy
import com.zhangwenkang.cinefin.models.SortOrder
import com.zhangwenkang.cinefin.models.bookSourcePath
import com.zhangwenkang.cinefin.presentation.components.ErrorDialog
import com.zhangwenkang.cinefin.presentation.components.LibraryGridSkeleton
import com.zhangwenkang.cinefin.presentation.components.LumenSkeletonOverlay
import com.zhangwenkang.cinefin.presentation.downloads.DownloadStatusViewModel
import com.zhangwenkang.cinefin.presentation.film.components.Direction
import com.zhangwenkang.cinefin.presentation.film.components.DownloadBadgeInfo
import com.zhangwenkang.cinefin.presentation.film.components.ErrorCard
import com.zhangwenkang.cinefin.presentation.film.components.FavoriteChangeEffect
import com.zhangwenkang.cinefin.presentation.film.components.ItemCard
import com.zhangwenkang.cinefin.presentation.film.components.LibraryActiveChipRow
import com.zhangwenkang.cinefin.presentation.film.components.LibraryFilterPanel
import com.zhangwenkang.cinefin.presentation.film.components.LibraryListRow
import com.zhangwenkang.cinefin.presentation.film.components.LibraryTabRow
import com.zhangwenkang.cinefin.presentation.film.components.LibraryTagTile
import com.zhangwenkang.cinefin.presentation.film.components.LibraryToolbarRow
import com.zhangwenkang.cinefin.presentation.film.components.SortByPanel
import com.zhangwenkang.cinefin.presentation.navigation.libraryIconRes
import com.zhangwenkang.cinefin.presentation.navigation.libraryTypeLabelRes
import com.zhangwenkang.cinefin.presentation.selection.MediaBatchAction
import com.zhangwenkang.cinefin.presentation.selection.MediaBatchActionBar
import com.zhangwenkang.cinefin.presentation.selection.MediaBatchDeleteDialog
import com.zhangwenkang.cinefin.presentation.selection.MediaBatchEvent
import com.zhangwenkang.cinefin.presentation.selection.MediaBatchMode
import com.zhangwenkang.cinefin.presentation.selection.MediaBatchTopBarActions
import com.zhangwenkang.cinefin.presentation.selection.MediaBatchViewModel
import com.zhangwenkang.cinefin.presentation.selection.mediaBatchActionEnabled
import com.zhangwenkang.cinefin.presentation.selection.mediaBatchCaps
import com.zhangwenkang.cinefin.presentation.selection.mediaBatchEventMessage
import com.zhangwenkang.cinefin.presentation.selection.mediaBatchMode
import com.zhangwenkang.cinefin.presentation.selection.startVideoQueuePlayback
import com.zhangwenkang.cinefin.presentation.theme.CinefinTheme
import com.zhangwenkang.cinefin.presentation.utils.GridCellsAdaptiveWithMinColumns
import com.zhangwenkang.cinefin.presentation.utils.rememberGridGutter
import com.zhangwenkang.cinefin.presentation.utils.rememberLazyGridScrollMemoryState
import com.zhangwenkang.cinefin.presentation.utils.rememberLazyScrollMemoryState
import com.zhangwenkang.cinefin.presentation.utils.rememberPageGutter
import com.zhangwenkang.cinefin.presentation.utils.rememberSafePadding
import com.zhangwenkang.cinefin.utils.BookCoverRules
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** W69b：进入页面时预取详情的卡片数（只取前几张可见卡，避免放大请求量）。 */
private const val DETAIL_PREFETCH_COUNT = 6

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
    /** 顶层页顶栏动作（W54-C 追加）：书架页注入「库选择 / 收藏」，排在「返回默认 ×」之后。 视频页与书架页共用同一顶栏组件，动作区由调用方注入，避免在库内容页里堆分支。 */
    topBarActions: @Composable RowScope.() -> Unit = {},
    /** W54-D：首页「全部」入口带「最近添加」初始排序；null = 沿用全局排序偏好（既有入口不变）。 */
    initialSortBy: SortBy? = null,
    initialSortOrder: SortOrder? = null,
    /** W60b：下载反馈 Snackbar「查看」→ 下载页。 */
    onOpenDownloads: () -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // W59：书籍封面自动生成结果（itemId → 本地绝对路径）。
    val bookCovers by viewModel.bookCovers.collectAsStateWithLifecycle()

    LaunchedEffect(true) {
        viewModel.setup(
            parentId = libraryId,
            libraryType = libraryType,
            initialSortBy = initialSortBy,
            initialSortOrder = initialSortOrder,
        )
        // W69：缓存优先——ViewModel 自己判断"已有一份分页列表且 TTL 内"就直接复用，
        // 进入页面 / 从详情返回都不会重建 Pager（列表不闪空、骨架不盖海报）。
        viewModel.loadItems()
    }

    LibraryScreenLayout(
        libraryName = libraryName,
        libraryType = libraryType,
        topLevel = topLevel,
        onOpenDrawer = onOpenDrawer,
        onBackToDefault = onBackToDefault,
        topBarActions = topBarActions,
        state = state,
        bookCovers = bookCovers,
        onRequestBookCover = viewModel::requestBookCover,
        onRequestBookCoverFallback = viewModel::requestBookCoverFallback,
        onPrefetchItem = viewModel::prefetchItemDetail,
        onOpenDownloads = onOpenDownloads,
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
    topBarActions: @Composable RowScope.() -> Unit = {},
    state: LibraryState,
    /** W59：书籍封面自动生成结果（itemId → 本地绝对路径）。 */
    bookCovers: Map<UUID, String> = emptyMap(),
    /** W77-5：第三个参数 = 服务器元数据判定该书是 PDF（未下载时远端封面直接占位）。 */
    onRequestBookCover: (UUID, String?, Boolean) -> Unit = { _, _, _ -> },
    /** W69b：服务器图加载失败 → 忽略 URL 走本地生成（书籍卡回落）；W77-5：远端 PDF 直接占位。 */
    onRequestBookCoverFallback: (UUID, Boolean) -> Unit = { _, _ -> },
    /** W69b：可见条目详情预取（前几张卡）。 */
    onPrefetchItem: (FindroidItem) -> Unit = {},
    /** W60b：下载反馈 Snackbar「查看」→ 下载页。 */
    onOpenDownloads: () -> Unit = {},
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
    // W69：TTL 外重进页面时 ViewModel 只发静默重取信号——刷新保留已上屏的条目与海报，不闪空。
    LaunchedEffect(state.refreshSignal) { if (state.refreshSignal > 0) items.refresh() }
    // W69b：服务器图 404 / 取图失败 → 书籍卡回落本地生成（同一 BookCoverProvider 链路）。
    // W77-5：远端（未下载）PDF 不做生成，直接类型占位。
    val coverFallback: (FindroidItem) -> Unit = { item ->
        onRequestBookCoverFallback(item.id, BookCoverRules.isPdfPath(item.bookSourcePath))
    }
    val tabs = remember(libraryType, state.tabs) { state.tabs.ifEmpty { libraryTabs(libraryType) } }
    val toolbarSpec = remember(state.tab) { libraryToolbarSpec(state.tab) }

    // ---- W58b：长按多选 + 批量操作（视频库 / 书籍库共用；删除仅本地） ----
    val batchMode = mediaBatchMode(libraryType)
    val batchViewModel: MediaBatchViewModel = hiltViewModel()
    val downloadStatusViewModel: DownloadStatusViewModel = hiltViewModel()
    val downloadState by batchViewModel.downloadState.collectAsStateWithLifecycle()
    val downloadBadges by downloadStatusViewModel.badges.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val viewLabel = stringResource(CoreR.string.snackbar_view)
    var batchSelection by rememberMultiSelectState()
    var showBatchDeleteDialog by rememberSaveable { mutableStateOf(false) }
    val selectionMode = batchMode != MediaBatchMode.NONE && batchSelection.selectionMode
    // 「已加载条目」快照：分页只取已加载部分（不拉全库），翻页 / 刷新后由 retain 求交集。
    val loadedItems =
        remember(items.itemCount) { (0 until items.itemCount).mapNotNull { index -> items[index] } }
    val loadedIds = remember(loadedItems) { loadedItems.map { it.id.toString() } }
    val selectedItems =
        remember(loadedItems, batchSelection.selectedIds) {
            loadedItems.filter { it.id.toString() in batchSelection.selectedIds }
        }
    val selectedCaps =
        remember(selectedItems, downloadState) {
            selectedItems.map { item ->
                mediaBatchCaps(
                    item = item,
                    // 书籍走阅读器离线链路，已下载状态与下载引擎分开存；这里合并成一个判定集合。
                    downloadedItemIds =
                        downloadState.downloadedItemIds + downloadState.localBookItemIds,
                    activeItemIds = downloadState.activeItemIds,
                )
            }
        }

    LaunchedEffect(loadedIds) { batchSelection = batchSelection.retain(loadedIds.toSet()) }
    // 切 tab / 换排序 / 换筛选 = 换了一份列表：退出多选，避免选中集合与视图不一致。
    LaunchedEffect(
        state.tab,
        state.filter,
        state.genre,
        state.studio,
        state.sortBy,
        state.sortOrder,
    ) {
        batchSelection = batchSelection.clear()
    }
    LaunchedEffect(batchViewModel) {
        batchViewModel.refreshDownloadState()
        batchViewModel.events.collect { event ->
            mediaBatchEventMessage(context, event)?.let { message ->
                val result =
                    snackbarHostState.showSnackbar(
                        message = message,
                        actionLabel =
                            if (event is MediaBatchEvent.DownloadQueued) viewLabel else null,
                        duration = DownloadSnackbarDuration,
                    )
                if (result == SnackbarResult.ActionPerformed) onOpenDownloads()
            }
            when (event) {
                is MediaBatchEvent.PlayQueue -> context.startVideoQueuePlayback(event.entries)
                // 标记已看 / 已读 / 收藏后重取一次已加载页，让卡片上的状态跟着走。
                is MediaBatchEvent.Updated -> if (event.changed > 0) items.refresh()
                else -> Unit
            }
            if (event is MediaBatchEvent.PlayQueue || event is MediaBatchEvent.Deleted) {
                batchSelection = batchSelection.clear()
            }
        }
    }

    // W60b：详情页 / 其它入口的收藏变更后刷新已加载页，卡片收藏角标即时一致。
    FavoriteChangeEffect { items.refresh() }

    BackHandler(enabled = selectionMode) { batchSelection = batchSelection.clear() }

    var showSortPanel by remember { mutableStateOf(false) }
    var showFilterPanel by remember { mutableStateOf(false) }
    var showTabErrorDialog by rememberSaveable { mutableStateOf(false) }

    val isTagTab = state.tab == LibraryTab.Genres || state.tab == LibraryTab.Studios
    val tabItems = state.tabItems
    // W75 #5：库内容列表 / 网格的滚动位置按导航条目记忆——打开条目详情再返回时保持原位置（不置顶）。
    val libraryListState = rememberLazyScrollMemoryState("library-list:$libraryName")
    val libraryGridState = rememberLazyGridScrollMemoryState("library-grid:$libraryName")
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
                if (selectionMode) {
                    stringResource(
                        CoreR.string.download_selected_count,
                        batchSelection.selectedCount,
                    )
                } else if (topLevel && onBackToDefault == null) {
                    stringResource(CoreR.string.title_book_shelf)
                } else {
                    libraryName
                },
            subtitle =
                if (selectionMode) null
                else onBackToDefault?.let { stringResource(libraryTypeLabelRes(libraryType)) },
            onOpenDrawer = if (topLevel && !selectionMode) onOpenDrawer else null,
            onBack =
                if (topLevel || selectionMode) null else ({ onAction(LibraryAction.OnBackClick) }),
            modifier = Modifier.padding(start = safePadding.start),
            actions = {
                if (selectionMode) {
                    MediaBatchTopBarActions(
                        selectedCount = batchSelection.selectedCount,
                        visibleCount = loadedIds.size,
                        onSelectAll = { batchSelection = batchSelection.selectAll(loadedIds) },
                        onSelectNone = { batchSelection = batchSelection.selectNone(loadedIds) },
                        onExit = { batchSelection = batchSelection.clear() },
                    )
                    return@CinefinPageTopBar
                }
                if (onBackToDefault != null) {
                    CinefinBackToDefaultChip(onClick = onBackToDefault)
                }
                topBarActions()
            },
        )

        // 多选态隐藏头部 tabs / 工具行 / 筛选 chips：选中作用域就是当前列表，避免中途换列表。
        if (!selectionMode) {
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
        }

        // W54-B：库内容页统一下拉刷新——分页列表走 LazyPagingItems.refresh()（真实重发请求），
        // 计数与当前 tab 由 ViewModel 重取。
        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = {
                // W69：先同步失效会话缓存（ViewModel.refresh 内），再让 Paging 重取——
                // 保证下拉刷新拿到的是服务器新值，而不是缓存里的旧页片。
                onAction(LibraryAction.Refresh)
                items.refresh()
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
                        state = libraryListState,
                        contentPadding = contentPadding,
                        verticalArrangement = Arrangement.spacedBy(CinefinSpacing.Space3),
                    ) {
                        items(count = items.itemCount, key = items.itemKey { it.id }) { index ->
                            val item = items[index]
                            item?.let { loadedItem ->
                                val cover = bookCovers[loadedItem.id]
                                LaunchedEffect(loadedItem.id) {
                                    if (libraryType == CollectionType.Books) {
                                        onRequestBookCover(
                                            loadedItem.id,
                                            loadedItem.images.primary?.toString(),
                                            BookCoverRules.isPdfPath(loadedItem.bookSourcePath),
                                        )
                                    }
                                    // W69b：只预取前几张可见卡片的详情（每次进入一次，条数上限 6）。
                                    if (index < DETAIL_PREFETCH_COUNT) onPrefetchItem(loadedItem)
                                }
                                LibraryListRow(
                                    item = loadedItem,
                                    onClick = {
                                        if (selectionMode) {
                                            batchSelection =
                                                batchSelection
                                                    .retain(loadedIds.toSet())
                                                    .toggle(loadedItem.id.toString())
                                        } else {
                                            onAction(LibraryAction.OnItemClick(loadedItem))
                                        }
                                    },
                                    modifier = Modifier.animateItem(),
                                    selectionMode = selectionMode,
                                    selected = batchSelection.isSelected(loadedItem.id.toString()),
                                    imageOverride =
                                        BookCoverRules.coverOverride(
                                            loadedItem.images.primary?.toString(),
                                            cover,
                                        ),
                                    // W74（#17）：无缩略图条目不再出黑卡——占位图标与库类型同源
                                    // （书籍 = 书、播放列表 = 列表、合集 = 星标、其余 = 通用库）。
                                    placeholderIconRes = libraryIconRes(libraryType),
                                    onServerImageFailed = {
                                        if (libraryType == CollectionType.Books) {
                                            coverFallback(loadedItem)
                                        }
                                    },
                                    onLongClick =
                                        if (batchMode != MediaBatchMode.NONE) {
                                            {
                                                batchSelection =
                                                    batchSelection
                                                        .retain(loadedIds.toSet())
                                                        .longPress(loadedItem.id.toString())
                                            }
                                        } else {
                                            null
                                        },
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
                        state = libraryGridState,
                        contentPadding = contentPadding,
                        horizontalArrangement = Arrangement.spacedBy(gridGutter),
                        verticalArrangement = Arrangement.spacedBy(CinefinSpacing.Space6),
                    ) {
                        items(count = items.itemCount, key = items.itemKey { it.id }) { index ->
                            val item = items[index]
                            item?.let { loadedItem ->
                                val cover = bookCovers[loadedItem.id]
                                LaunchedEffect(loadedItem.id) {
                                    if (libraryType == CollectionType.Books) {
                                        onRequestBookCover(
                                            loadedItem.id,
                                            loadedItem.images.primary?.toString(),
                                            BookCoverRules.isPdfPath(loadedItem.bookSourcePath),
                                        )
                                    }
                                    // W69b：只预取前几张可见卡片的详情（每次进入一次，条数上限 6）。
                                    if (index < DETAIL_PREFETCH_COUNT) onPrefetchItem(loadedItem)
                                }
                                ItemCard(
                                    item = loadedItem,
                                    direction = direction,
                                    onClick = {
                                        if (selectionMode) {
                                            batchSelection =
                                                batchSelection
                                                    .retain(loadedIds.toSet())
                                                    .toggle(loadedItem.id.toString())
                                        } else {
                                            onAction(LibraryAction.OnItemClick(loadedItem))
                                        }
                                    },
                                    modifier = Modifier.animateItem(),
                                    selectionMode = selectionMode,
                                    selected = batchSelection.isSelected(loadedItem.id.toString()),
                                    imageOverride =
                                        BookCoverRules.coverOverride(
                                            loadedItem.images.primary?.toString(),
                                            cover,
                                        ),
                                    // W74（#17）：同库 tab 网格——无缩略图条目露出域色占位。
                                    placeholderIconRes = libraryIconRes(libraryType),
                                    onServerImageFailed = {
                                        if (libraryType == CollectionType.Books) {
                                            coverFallback(loadedItem)
                                        }
                                    },
                                    onLongClick =
                                        if (batchMode != MediaBatchMode.NONE) {
                                            {
                                                batchSelection =
                                                    batchSelection
                                                        .retain(loadedIds.toSet())
                                                        .longPress(loadedItem.id.toString())
                                            }
                                        } else {
                                            null
                                        },
                                    downloadBadge =
                                        downloadBadges[loadedItem.id] ?: DownloadBadgeInfo(),
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
                            // W69b：Tab 列表也预取前几张卡片的详情（与库 tab 同口径）。
                            LaunchedEffect(item.id) {
                                if (index < DETAIL_PREFETCH_COUNT) onPrefetchItem(item)
                            }
                            LibraryListRow(
                                item = item,
                                onClick = { onAction(LibraryAction.OnItemClick(item)) },
                                modifier = Modifier.animateItem(),
                                // W74（#17）：列表形态同样露域色占位，不出现黑条。
                                placeholderIconRes = libraryIconRes(libraryType),
                                onServerImageFailed = {
                                    if (libraryType == CollectionType.Books) coverFallback(item)
                                },
                                downloadBadge = downloadBadges[item.id] ?: DownloadBadgeInfo(),
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
                            // W69b：Tab 网格也预取前几张卡片的详情（与库 tab 同口径）。
                            LaunchedEffect(item.id) {
                                if (index < DETAIL_PREFETCH_COUNT) onPrefetchItem(item)
                            }
                            ItemCard(
                                item = item,
                                direction = direction,
                                onClick = { onAction(LibraryAction.OnItemClick(item)) },
                                modifier = Modifier.animateItem(),
                                // W74（#17）：同库 tab 网格——无缩略图条目露出域色占位。
                                placeholderIconRes = libraryIconRes(libraryType),
                                onServerImageFailed = {
                                    if (libraryType == CollectionType.Books) coverFallback(item)
                                },
                                downloadBadge = downloadBadges[item.id] ?: DownloadBadgeInfo(),
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

        CinefinSnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.padding(horizontal = paddingStart),
        )

        if (selectionMode) {
            MediaBatchActionBar(
                mode = batchMode,
                selectedCount = batchSelection.selectedCount,
                isEnabled = { action -> mediaBatchActionEnabled(action, selectedCaps) },
                onAction = { action ->
                    when (action) {
                        MediaBatchAction.PLAY -> batchViewModel.playSelected(selectedItems)
                        MediaBatchAction.DOWNLOAD -> batchViewModel.downloadSelected(selectedItems)
                        MediaBatchAction.MARK_PLAYED ->
                            batchViewModel.markPlayedSelected(selectedItems)
                        MediaBatchAction.FAVORITE -> batchViewModel.favoriteSelected(selectedItems)
                        MediaBatchAction.DELETE -> showBatchDeleteDialog = true
                    }
                },
            )
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

    if (showBatchDeleteDialog) {
        MediaBatchDeleteDialog(
            selectedCount = selectedItems.size,
            onConfirm = {
                batchViewModel.deleteSelected(selectedItems)
                showBatchDeleteDialog = false
            },
            onDismiss = { showBatchDeleteDialog = false },
        )
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
