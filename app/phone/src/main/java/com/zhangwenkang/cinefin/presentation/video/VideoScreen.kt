package com.zhangwenkang.cinefin.presentation.video

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import androidx.window.core.layout.WindowSizeClass
import com.zhangwenkang.cinefin.core.R as CoreR
import com.zhangwenkang.cinefin.core.presentation.components.CinefinBackToDefaultChip
import com.zhangwenkang.cinefin.core.presentation.components.CinefinEmptyState
import com.zhangwenkang.cinefin.core.presentation.components.CinefinPageTopBar
import com.zhangwenkang.cinefin.core.presentation.components.CinefinSleepTimerOptions
import com.zhangwenkang.cinefin.core.presentation.dummy.dummyCollections
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinSpacing
import com.zhangwenkang.cinefin.core.presentation.theme.LocalCinefinColors
import com.zhangwenkang.cinefin.core.presentation.theme.LocalMediaColors
import com.zhangwenkang.cinefin.film.R as FilmR
import com.zhangwenkang.cinefin.models.FindroidItem
import com.zhangwenkang.cinefin.player.local.domain.SleepTimerController
import com.zhangwenkang.cinefin.presentation.components.BaseDialog
import com.zhangwenkang.cinefin.presentation.components.ErrorDialog
import com.zhangwenkang.cinefin.presentation.components.LibraryGridSkeleton
import com.zhangwenkang.cinefin.presentation.components.LibrarySelectorChip
import com.zhangwenkang.cinefin.presentation.components.LibrarySelectorOption
import com.zhangwenkang.cinefin.presentation.components.LumenSkeletonOverlay
import com.zhangwenkang.cinefin.presentation.components.MediaLibrarySkeleton
import com.zhangwenkang.cinefin.presentation.components.TopBarAction
import com.zhangwenkang.cinefin.presentation.film.components.Direction
import com.zhangwenkang.cinefin.presentation.film.components.ErrorCard
import com.zhangwenkang.cinefin.presentation.film.components.ItemCard
import com.zhangwenkang.cinefin.presentation.film.components.LibraryEntryCard
import com.zhangwenkang.cinefin.presentation.navigation.libraryTypeLabelRes
import com.zhangwenkang.cinefin.presentation.theme.CinefinTheme
import com.zhangwenkang.cinefin.presentation.utils.GridCellsAdaptiveWithMinColumns
import com.zhangwenkang.cinefin.presentation.utils.rememberGridGutter
import com.zhangwenkang.cinefin.presentation.utils.rememberPageGutter
import com.zhangwenkang.cinefin.presentation.utils.rememberSafePadding
import com.zhangwenkang.cinefin.settings.domain.models.VideoDisplayMode
import java.util.UUID
import kotlinx.coroutines.flow.Flow

/**
 * 视频模式页（W53，用户 2026-10-03 确认）：与首页 / 音乐 / 书架同级的顶层入口。
 *
 * 页面数据 = 服务器上 `movies` + `tvshows` 类型的全部库，两种显示方式（「客户端设置 → 媒体库 → 视频显示方式」可切换）：
 * 1. **库卡列表（默认）**：按库分组展示 16:9 `LibraryEntryCard`（封面 / 库名 / 项目数），点进库内容页；
 * 2. **聚合列表**：全部视频库的条目（电影 + 剧集）合并成一个懒加载网格，复用 `ItemCard`。
 *
 * 顶栏复用 `CinefinPageTopBar`：手机 = app 图标入口（开抽屉），平板 = 无抽屉键（W46 形态规则）。
 */
@Composable
fun VideoScreen(
    /** 抽屉入口；null = 当前形态没有抽屉（平板 | 非顶层）。 */
    onOpenDrawer: (() -> Unit)?,
    onItemClick: (FindroidItem) -> Unit,
    /** 临时库视图（W53 追加）：非空 = 侧栏点进来的视频库 id，直显该库条目网格。 */
    temporaryLibraryId: String? = null,
    /** 临时库视图的「返回默认 ×」/ 系统返回键动作（回默认视频页）。 */
    onExitTemporaryLibrary: (() -> Unit)? = null,
    viewModel: VideoViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val sleepTimerState by viewModel.sleepTimerState.collectAsStateWithLifecycle()

    LaunchedEffect(temporaryLibraryId) { viewModel.load(temporaryLibraryId) }

    // 临时库视图：返回键先退出临时库（回默认视频页），再按一次才离开（用户 2026-10-03 口径）。
    BackHandler(
        enabled = state.temporaryLibrary != null && onExitTemporaryLibrary != null,
        onBack = { onExitTemporaryLibrary?.invoke() },
    )

    VideoScreenLayout(
        onOpenDrawer = onOpenDrawer,
        state = state,
        onItemClick = onItemClick,
        onRetry = { viewModel.load(temporaryLibraryId) },
        onExitTemporaryLibrary = onExitTemporaryLibrary,
        onSelectLibrary = viewModel::selectLibrary,
        onToggleFavorite = viewModel::toggleFavorite,
        sleepTimerState = sleepTimerState,
        onSelectSleepMinutes = viewModel::selectSleepTimer,
    )
}

@Composable
private fun VideoScreenLayout(
    onOpenDrawer: (() -> Unit)?,
    state: VideoState,
    onItemClick: (FindroidItem) -> Unit,
    onRetry: () -> Unit,
    onExitTemporaryLibrary: (() -> Unit)? = null,
    onSelectLibrary: (UUID?) -> Unit = {},
    onToggleFavorite: (UUID) -> Unit = {},
    sleepTimerState: SleepTimerController.State = SleepTimerController.State(),
    onSelectSleepMinutes: (Int?) -> Unit = {},
) {
    val safePadding = rememberSafePadding(handleStartInsets = false)
    val pageGutter = rememberPageGutter()
    val gridGutter = rememberGridGutter()
    val paddingStart = safePadding.start + pageGutter
    val paddingEnd = safePadding.end + pageGutter
    val paddingBottom = safePadding.bottom + pageGutter
    val contentPadding =
        PaddingValues(
            start = paddingStart,
            top = CinefinSpacing.Space4,
            end = paddingEnd,
            bottom = paddingBottom + CinefinSpacing.Space8,
        )

    val windowSizeClass = currentWindowAdaptiveInfo().windowSizeClass
    val expanded =
        windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND)
    // 库卡列宽沿用媒体库总览的四档（D19）：手机 300 / 平板 320 · 380 / 桌面 420dp。
    val cardMinColumnSize =
        when {
            windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_LARGE_LOWER_BOUND) ->
                420.dp
            expanded -> 380.dp
            windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND) ->
                320.dp
            else -> 300.dp
        }

    var showErrorDialog by rememberSaveable { mutableStateOf(false) }
    var showSleepTimer by rememberSaveable { mutableStateOf(false) }
    val temporaryLibrary = state.temporaryLibrary
    // 「库选择」落到实处的库（用于 chip 文案与收藏目标）；临时库视图优先显示路由指定的库。
    val selectedLibrary = state.allLibraries.firstOrNull { it.id == state.selectedLibraryId }
    val favoriteLibrary = temporaryLibrary ?: selectedLibrary

    Column(modifier = Modifier.fillMaxSize()) {
        CinefinPageTopBar(
            // 临时库视图：真实库名 + 类型 + 项目数（库卡总览不出现）。
            title = temporaryLibrary?.name ?: stringResource(CoreR.string.title_video),
            subtitle =
                if (temporaryLibrary != null || selectedLibrary != null) {
                    val library = temporaryLibrary ?: selectedLibrary!!
                    listOfNotNull(
                            stringResource(libraryTypeLabelRes(library.type)),
                            library.itemCount?.let {
                                stringResource(FilmR.string.library_item_count, it)
                            },
                        )
                        .joinToString(" · ")
                } else if (state.allLibraries.isNotEmpty()) {
                    stringResource(FilmR.string.library_count, state.allLibraries.size)
                } else {
                    null
                },
            onOpenDrawer = onOpenDrawer,
            modifier = Modifier.padding(start = safePadding.start),
            actions = {
                if (temporaryLibrary != null && onExitTemporaryLibrary != null) {
                    CinefinBackToDefaultChip(onClick = onExitTemporaryLibrary)
                }
                // 库选择（W54-C）：库卡模式 = 过滤显示哪些库卡；聚合模式 = 只显示所选库内容。
                // 服务器上只有一个视频库时没有可选项；临时库视图只显示那一个库，不出现选择器。
                if (temporaryLibrary == null && state.allLibraries.size >= 2) {
                    val allLibrariesLabel = stringResource(FilmR.string.video_library_all)
                    val allLibrariesDetail =
                        stringResource(FilmR.string.library_count, state.allLibraries.size)
                    LibrarySelectorChip(
                        label = selectedLibrary?.name ?: allLibrariesLabel,
                        options =
                            listOf(
                                LibrarySelectorOption(
                                    id = null,
                                    label = allLibrariesLabel,
                                    detail = allLibrariesDetail,
                                )
                            ) +
                                state.allLibraries.map { library ->
                                    LibrarySelectorOption(
                                        id = library.id,
                                        label = library.name,
                                        detail =
                                            library.itemCount?.let {
                                                stringResource(FilmR.string.library_item_count, it)
                                            },
                                    )
                                },
                        selectedId = state.selectedLibraryId,
                        onSelect = onSelectLibrary,
                    )
                }
                // 收藏（W54-C）：收藏 / 取消收藏当前显示的那个库；「全部库」没有单一目标，不显示。
                favoriteLibrary?.let { library ->
                    TopBarAction(
                        icon =
                            if (library.favorite) CoreR.drawable.ic_heart_filled
                            else CoreR.drawable.ic_heart,
                        contentDescription = stringResource(FilmR.string.library_favorite),
                        onClick = { onToggleFavorite(library.id) },
                    )
                }
                // 睡眠定时（W55 正式落地）：与音乐 / 播放器共享同一状态源；激活时点亮。
                TopBarAction(
                    icon = FilmR.drawable.ic_video_sleep,
                    contentDescription = stringResource(FilmR.string.video_sleep_timer),
                    tint = if (sleepTimerState.active) LocalMediaColors.current.bright else null,
                    onClick = { showSleepTimer = true },
                )
            },
        )
        Spacer(Modifier.height(CinefinSpacing.Space2))

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (
                temporaryLibrary != null ||
                    (state.displayMode == VideoDisplayMode.Aggregated &&
                        state.libraries.isNotEmpty())
            ) {
                AggregatedVideoGrid(
                    items = state.aggregateItems,
                    onItemClick = onItemClick,
                    contentPadding = contentPadding,
                    gridGutter = gridGutter,
                    paddingStart = paddingStart,
                    paddingEnd = paddingEnd,
                )
            } else {
                VideoLibraryGrid(
                    state = state,
                    expanded = expanded,
                    minColumnSize = cardMinColumnSize,
                    contentPadding = contentPadding,
                    gridGutter = gridGutter,
                    paddingStart = paddingStart,
                    paddingEnd = paddingEnd,
                    onItemClick = onItemClick,
                )
            }

            // 空态：服务器上确实没有 movies / tvshows 库（拉取失败走下面的 ErrorCard）。
            if (
                state.loaded && !state.isLoading && state.error == null && state.libraries.isEmpty()
            ) {
                CinefinEmptyState(
                    title = stringResource(FilmR.string.video_empty_title),
                    message = stringResource(FilmR.string.video_empty_message),
                    modifier = Modifier.align(Alignment.Center).padding(horizontal = paddingStart),
                )
            }

            if (state.error != null) {
                ErrorCard(
                    onShowStacktrace = { showErrorDialog = true },
                    onRetryClick = onRetry,
                    modifier =
                        Modifier.fillMaxWidth()
                            .padding(
                                start = paddingStart,
                                top = CinefinSpacing.Space4,
                                end = paddingEnd,
                            ),
                )
            }
        }
    }

    val error = state.error
    if (showErrorDialog && error != null) {
        ErrorDialog(exception = error, onDismissRequest = { showErrorDialog = false })
    }
    if (showSleepTimer) {
        SleepTimerDialog(
            state = sleepTimerState,
            onSelect = { minutes ->
                onSelectSleepMinutes(minutes)
                showSleepTimer = false
            },
            onDismiss = { showSleepTimer = false },
        )
    }
}

/** 睡眠定时（W55）：与音乐 / 播放器共享状态源与 core 选择组件（预设 + 自定义 1–240 分钟）。 */
@Composable
private fun SleepTimerDialog(
    state: SleepTimerController.State,
    onSelect: (Int?) -> Unit,
    onDismiss: () -> Unit,
) {
    BaseDialog(
        title = stringResource(CoreR.string.sleep_timer_title),
        onDismiss = onDismiss,
        negativeButton = {},
        positiveButton = {
            TextButton(onClick = onDismiss) { Text(text = stringResource(CoreR.string.close)) }
        },
    ) { contentPadding ->
        Column(modifier = Modifier.padding(contentPadding).verticalScroll(rememberScrollState())) {
            CinefinSleepTimerOptions(
                activeMinutes = state.minutes,
                remainingMs = state.remainingMs,
                onSelect = onSelect,
            )
        }
    }
}

/** 库卡列表（默认显示方式）：16:9 大卡 + 库名 + 项目数，点进库内容页。 */
@Composable
private fun VideoLibraryGrid(
    state: VideoState,
    expanded: Boolean,
    minColumnSize: Dp,
    contentPadding: PaddingValues,
    gridGutter: Dp,
    paddingStart: Dp,
    paddingEnd: Dp,
    onItemClick: (FindroidItem) -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = minColumnSize),
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding,
            horizontalArrangement = Arrangement.spacedBy(gridGutter),
            verticalArrangement = Arrangement.spacedBy(CinefinSpacing.Space6),
        ) {
            itemsIndexed(state.libraries, key = { _, library -> library.id }) { index, library ->
                LibraryEntryCard(
                    item = library,
                    onClick = { onItemClick(library) },
                    index = index,
                )
            }
        }
        // 库列表到达前用大卡骨架占位（与媒体库总览同一过渡语言）。
        LumenSkeletonOverlay(visible = state.isLoading && state.libraries.isEmpty()) {
            MediaLibrarySkeleton(
                gutterStart = paddingStart,
                gutterEnd = paddingEnd,
                columns = if (expanded) 2 else 1,
                modifier =
                    Modifier.fillMaxSize()
                        .padding(top = CinefinSpacing.Space4)
                        .background(LocalCinefinColors.current.surface),
            )
        }
    }
}

/** 聚合列表：全部视频库的条目（电影 + 剧集）合并成一个懒加载网格。 */
@Composable
private fun AggregatedVideoGrid(
    items: Flow<PagingData<FindroidItem>>,
    onItemClick: (FindroidItem) -> Unit,
    contentPadding: PaddingValues,
    gridGutter: Dp,
    paddingStart: Dp,
    paddingEnd: Dp,
) {
    val pagingItems = items.collectAsLazyPagingItems()
    // 骨架屏列数与库内容页保持同一量级（骨架只是"格子的节奏"，不追求逐像素一致）。
    val skeletonColumns =
        when (val widthDp = LocalConfiguration.current.screenWidthDp) {
            in 0..599 -> 2
            in 600..839 -> 3
            in 840..1199 -> 4
            else -> if (widthDp >= 1600) 6 else 5
        }
    var showPagingErrorDialog by rememberSaveable { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCellsAdaptiveWithMinColumns(minSize = 176.dp, minColumns = 2),
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding,
            horizontalArrangement = Arrangement.spacedBy(gridGutter),
            verticalArrangement = Arrangement.spacedBy(CinefinSpacing.Space6),
        ) {
            items(count = pagingItems.itemCount, key = pagingItems.itemKey { it.id }) { index ->
                val item = pagingItems[index]
                item?.let { loadedItem ->
                    ItemCard(
                        item = loadedItem,
                        direction = Direction.VERTICAL,
                        onClick = { onItemClick(loadedItem) },
                        modifier = Modifier.animateItem(),
                    )
                }
            }
        }

        // 视频库都为空（库卡模式仍会显示 0 项目的库卡，聚合模式则给空态）。
        if (pagingItems.itemCount == 0 && pagingItems.loadState.refresh is LoadState.NotLoading) {
            CinefinEmptyState(
                title = stringResource(FilmR.string.video_empty_title),
                message = stringResource(FilmR.string.video_empty_message),
                modifier = Modifier.align(Alignment.Center).padding(horizontal = paddingStart),
            )
        }

        (pagingItems.loadState.refresh as? LoadState.Error)?.let { errorState ->
            ErrorCard(
                onShowStacktrace = { showPagingErrorDialog = true },
                onRetryClick = { pagingItems.retry() },
                modifier =
                    Modifier.fillMaxWidth()
                        .padding(
                            start = paddingStart,
                            top = CinefinSpacing.Space4,
                            end = paddingEnd,
                        ),
            )
            if (showPagingErrorDialog) {
                ErrorDialog(
                    exception = errorState.error,
                    onDismissRequest = { showPagingErrorDialog = false },
                )
            }
        }

        LumenSkeletonOverlay(
            visible =
                pagingItems.loadState.refresh is LoadState.Loading && pagingItems.itemCount == 0
        ) {
            LibraryGridSkeleton(
                columns = skeletonColumns,
                tileHeight = 264.dp,
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

@PreviewScreenSizes
@Composable
private fun VideoScreenLayoutPreview() {
    CinefinTheme {
        VideoScreenLayout(
            onOpenDrawer = {},
            state = VideoState(libraries = dummyCollections, loaded = true),
            onItemClick = {},
            onRetry = {},
        )
    }
}
