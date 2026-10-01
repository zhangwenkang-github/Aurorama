package com.zhangwenkang.cinefin.presentation.film

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.style.TextOverflow
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
import com.zhangwenkang.cinefin.core.presentation.components.CinefinEmptyState
import com.zhangwenkang.cinefin.core.presentation.dummy.dummyMovies
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinSpacing
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinType
import com.zhangwenkang.cinefin.core.presentation.theme.LocalCinefinColors
import com.zhangwenkang.cinefin.film.R as FilmR
import com.zhangwenkang.cinefin.film.presentation.library.LibraryAction
import com.zhangwenkang.cinefin.film.presentation.library.LibraryState
import com.zhangwenkang.cinefin.film.presentation.library.LibraryViewModel
import com.zhangwenkang.cinefin.models.CollectionType
import com.zhangwenkang.cinefin.models.FindroidItem
import com.zhangwenkang.cinefin.presentation.components.ErrorDialog
import com.zhangwenkang.cinefin.presentation.components.LibraryGridSkeleton
import com.zhangwenkang.cinefin.presentation.components.LumenSkeletonOverlay
import com.zhangwenkang.cinefin.presentation.components.TopBarAction
import com.zhangwenkang.cinefin.presentation.film.components.Direction
import com.zhangwenkang.cinefin.presentation.film.components.ErrorCard
import com.zhangwenkang.cinefin.presentation.film.components.ItemCard
import com.zhangwenkang.cinefin.presentation.film.components.SortByDialog
import com.zhangwenkang.cinefin.presentation.theme.CinefinTheme
import com.zhangwenkang.cinefin.presentation.utils.GridCellsAdaptiveWithMinColumns
import com.zhangwenkang.cinefin.presentation.utils.plus
import com.zhangwenkang.cinefin.presentation.utils.rememberGridGutter
import com.zhangwenkang.cinefin.presentation.utils.rememberPageGutter
import com.zhangwenkang.cinefin.presentation.utils.rememberSafePadding
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

@Composable
fun LibraryScreen(
    libraryId: UUID,
    libraryName: String,
    libraryType: CollectionType,
    onItemClick: (item: FindroidItem) -> Unit,
    navigateBack: () -> Unit,
    viewModel: LibraryViewModel = hiltViewModel(),
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
 * 库内容页（Lumen 去拥挤）：标题区升级为"库名 + 数量"，栅格整体放大一档、行距 24dp。
 *
 * 手法与数值见 `UI_PLAN.md` D19：竖版海报至少 176dp 宽（手机仍是 2 列但每张更大）、横版卡 300dp、 方形专辑 200dp；列距保持 16 / 26dp
 * 的既有栅格，行距从 16dp 提到 24dp，底部留白 32dp。
 */
@Composable
private fun LibraryScreenLayout(
    libraryName: String,
    libraryType: CollectionType,
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

    var showSortByDialog by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier =
                Modifier.fillMaxWidth()
                    .padding(start = paddingStart, top = safePadding.top, end = paddingEnd)
                    .height(56.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TopBarAction(
                icon = CoreR.drawable.ic_arrow_left,
                onClick = { onAction(LibraryAction.OnBackClick) },
            )
            Spacer(Modifier.width(CinefinSpacing.Space2))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = libraryName,
                    style = CinefinType.HeadlineSmall,
                    color = LocalCinefinColors.current.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (items.itemCount > 0) {
                    Text(
                        text = stringResource(FilmR.string.library_item_count, items.itemCount),
                        style = CinefinType.BodySmall,
                        color = LocalCinefinColors.current.onSurfaceVariant,
                    )
                }
            }
            TopBarAction(
                icon = CoreR.drawable.ic_arrow_down_up,
                onClick = { showSortByDialog = true },
            )
        }

        Spacer(Modifier.height(CinefinSpacing.Space4))

        ErrorGroup(
            loadStates = items.loadState,
            onRefresh = { items.refresh() },
            modifier =
                Modifier.fillMaxWidth()
                    .padding(horizontal = paddingStart)
                    .padding(bottom = CinefinSpacing.Space4),
        )

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            LazyVerticalGrid(
                columns = GridCellsAdaptiveWithMinColumns(minSize = minColumnSize, minColumns = 2),
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
            // 空库不再是一块空白：加载完成且 0 条时给空态（书架解析到空壳书库时也走这里）
            if (
                !state.isLoading &&
                    items.itemCount == 0 &&
                    items.loadState.refresh is LoadState.NotLoading
            ) {
                CinefinEmptyState(
                    title = stringResource(FilmR.string.library_empty_title),
                    message = stringResource(FilmR.string.library_empty_message),
                    modifier = Modifier.align(Alignment.Center).padding(horizontal = paddingStart),
                )
            }

            // 库内容加载过渡（W6-VIS D24）：首页数据到达前铺同版式的骨架格，避免"黑板直出"
            LumenSkeletonOverlay(
                visible = items.loadState.refresh is LoadState.Loading && items.itemCount == 0
            ) {
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

    if (showSortByDialog) {
        SortByDialog(
            currentSortBy = state.sortBy,
            currentSortOrder = state.sortOrder,
            onUpdate = { sortBy, sortOrder ->
                onAction(LibraryAction.ChangeSorting(sortBy, sortOrder))
            },
            onDismissRequest = { showSortByDialog = false },
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
            state = LibraryState(items = items),
            onAction = {},
        )
    }
}
