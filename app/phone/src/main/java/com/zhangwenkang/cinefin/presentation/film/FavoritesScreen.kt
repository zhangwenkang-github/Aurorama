package com.zhangwenkang.cinefin.presentation.film

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zhangwenkang.cinefin.core.R as CoreR
import com.zhangwenkang.cinefin.core.presentation.components.CinefinButton
import com.zhangwenkang.cinefin.core.presentation.components.CinefinButtonSize
import com.zhangwenkang.cinefin.core.presentation.components.CinefinButtonVariant
import com.zhangwenkang.cinefin.core.presentation.components.CinefinEmptyState
import com.zhangwenkang.cinefin.core.presentation.components.CinefinFilterChip
import com.zhangwenkang.cinefin.core.presentation.components.CinefinPageTopBar
import com.zhangwenkang.cinefin.core.presentation.components.CinefinSnackbarHost
import com.zhangwenkang.cinefin.core.presentation.components.rememberMultiSelectState
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinSpacing
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinType
import com.zhangwenkang.cinefin.core.presentation.theme.LocalCinefinColors
import com.zhangwenkang.cinefin.film.R as FilmR
import com.zhangwenkang.cinefin.models.FindroidItem
import com.zhangwenkang.cinefin.presentation.downloads.DownloadStatusViewModel
import com.zhangwenkang.cinefin.presentation.film.components.Direction
import com.zhangwenkang.cinefin.presentation.film.components.DownloadBadgeInfo
import com.zhangwenkang.cinefin.presentation.film.components.FavoriteChangeEffect
import com.zhangwenkang.cinefin.presentation.film.components.ItemCard
import com.zhangwenkang.cinefin.presentation.selection.MediaBatchAction
import com.zhangwenkang.cinefin.presentation.selection.MediaBatchActionBar
import com.zhangwenkang.cinefin.presentation.selection.MediaBatchEvent
import com.zhangwenkang.cinefin.presentation.selection.MediaBatchMode
import com.zhangwenkang.cinefin.presentation.selection.MediaBatchTopBarActions
import com.zhangwenkang.cinefin.presentation.selection.MediaBatchViewModel
import com.zhangwenkang.cinefin.presentation.selection.mediaBatchActionEnabled
import com.zhangwenkang.cinefin.presentation.selection.mediaBatchCaps
import com.zhangwenkang.cinefin.presentation.selection.mediaBatchEventMessage
import com.zhangwenkang.cinefin.presentation.utils.GridCellsAdaptiveWithMinColumns
import com.zhangwenkang.cinefin.presentation.utils.rememberGridGutter
import com.zhangwenkang.cinefin.presentation.utils.rememberPageGutter
import com.zhangwenkang.cinefin.presentation.utils.rememberSafePadding

/**
 * 「我的收藏」（W60b，侧栏一级入口）：`filters=IsFavorite` 跨库汇总（电影 / 剧集 / 单集）+ 类型筛选 + 排序（加入日期 / 名称） + 已加载全选与批量操作（收藏
 * / 下载 / 已看）。
 *
 * 收藏状态是单一数据源（仓库层广播）：本页的批量收藏、详情页的收藏 / 取消收藏都会让版本号前进， 本页与库网格 / 首页走廊一起刷新角标。
 */
@Composable
fun FavoritesScreen(
    /** 抽屉入口；null = 当前形态没有抽屉（平板走常显侧轨）。 */
    onOpenDrawer: (() -> Unit)?,
    onItemClick: (item: FindroidItem) -> Unit,
    /** 下载 Snackbar「查看」动作：跳下载页（W60b 下载反馈统一）。 */
    onOpenDownloads: () -> Unit = {},
    viewModel: FavoritesItemsViewModel = hiltViewModel(),
    batchViewModel: MediaBatchViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val downloadState by batchViewModel.downloadState.collectAsStateWithLifecycle()
    val downloadStatusViewModel: DownloadStatusViewModel = hiltViewModel()
    val downloadBadges by downloadStatusViewModel.badges.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val viewLabel = stringResource(CoreR.string.snackbar_view)
    var selection by rememberMultiSelectState()

    LaunchedEffect(true) { viewModel.load() }
    // 详情页 / 其它入口的收藏变更：回到本页或停留在本页都补一次刷新。
    FavoriteChangeEffect { viewModel.load() }

    val visibleItems = state.visibleItems
    val loadedIds = remember(visibleItems) { visibleItems.map { it.id.toString() } }
    val selectedItems =
        remember(visibleItems, selection.selectedIds) {
            visibleItems.filter { it.id.toString() in selection.selectedIds }
        }
    val selectedCaps =
        remember(selectedItems, downloadState) {
            selectedItems.map { item ->
                mediaBatchCaps(
                    item = item,
                    downloadedItemIds = downloadState.downloadedItemIds,
                    activeItemIds = downloadState.activeItemIds,
                )
            }
        }

    LaunchedEffect(loadedIds) { selection = selection.retain(loadedIds.toSet()) }
    // 换筛选 / 换排序 = 换了一份列表：退出多选，避免选中集合与视图不一致。
    LaunchedEffect(state.filter, state.sort) { selection = selection.clear() }

    LaunchedEffect(batchViewModel) {
        batchViewModel.refreshDownloadState()
        batchViewModel.events.collect { event ->
            val message = mediaBatchEventMessage(context, event)
            if (message != null) {
                val result =
                    snackbarHostState.showSnackbar(
                        message = message,
                        actionLabel =
                            if (event is MediaBatchEvent.DownloadQueued) viewLabel else null,
                    )
                if (result == SnackbarResult.ActionPerformed) onOpenDownloads()
            }
            when (event) {
                is MediaBatchEvent.Updated -> {
                    // 批量取消收藏会让条目从本页消失；批量已看也让卡片状态跟着走。
                    if (event.changed > 0) viewModel.load()
                    selection = selection.clear()
                }
                else -> Unit
            }
        }
    }

    BackHandler(enabled = selection.selectionMode) { selection = selection.clear() }

    val safePadding = rememberSafePadding(handleStartInsets = false)
    val pageGutter = rememberPageGutter()
    val gridGutter = rememberGridGutter()
    val paddingStart = safePadding.start + pageGutter
    val paddingEnd = safePadding.end + pageGutter
    val contentPadding =
        PaddingValues(
            start = paddingStart,
            top = CinefinSpacing.Space4,
            end = paddingEnd,
            bottom = safePadding.bottom + CinefinSpacing.Space8,
        )

    Column(modifier = Modifier.fillMaxSize()) {
        CinefinPageTopBar(
            title =
                if (selection.selectionMode) {
                    stringResource(CoreR.string.download_selected_count, selection.selectedCount)
                } else {
                    stringResource(CoreR.string.title_my_favorites)
                },
            subtitle =
                if (!selection.selectionMode && !state.isLoading && state.error == null) {
                    stringResource(FilmR.string.library_item_count, visibleItems.size)
                } else {
                    null
                },
            onOpenDrawer = if (selection.selectionMode) null else onOpenDrawer,
            modifier = Modifier.padding(start = safePadding.start),
            actions = {
                if (selection.selectionMode) {
                    MediaBatchTopBarActions(
                        selectedCount = selection.selectedCount,
                        visibleCount = loadedIds.size,
                        onSelectAll = { selection = selection.selectAll(loadedIds) },
                        onSelectNone = { selection = selection.selectNone(loadedIds) },
                        onExit = { selection = selection.clear() },
                    )
                }
            },
        )

        if (!selection.selectionMode) {
            Spacer(Modifier.height(CinefinSpacing.Space3))
            Row(
                modifier =
                    Modifier.fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = paddingStart),
                horizontalArrangement = Arrangement.spacedBy(CinefinSpacing.Space2),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FavoriteTypeFilter.entries.forEach { filter ->
                    CinefinFilterChip(
                        text = filter.label(),
                        selected = state.filter == filter,
                        onClick = { viewModel.setFilter(filter) },
                        compact = true,
                    )
                }
            }
            Spacer(Modifier.height(CinefinSpacing.Space2))
            Row(
                modifier =
                    Modifier.fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = paddingStart),
                horizontalArrangement = Arrangement.spacedBy(CinefinSpacing.Space2),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(CoreR.string.favorites_sort_label),
                    style = CinefinType.LabelSmall,
                    color = LocalCinefinColors.current.onSurfaceFaint,
                )
                FavoriteSort.entries.forEach { sort ->
                    CinefinFilterChip(
                        text = sort.label(),
                        selected = state.sort == sort,
                        onClick = { viewModel.setSort(sort) },
                        compact = true,
                    )
                }
            }
            Spacer(Modifier.height(CinefinSpacing.Space1))
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when {
                state.error != null -> {
                    CinefinEmptyState(
                        title = stringResource(CoreR.string.favorites_load_failed),
                        modifier =
                            Modifier.align(Alignment.Center).padding(horizontal = paddingStart),
                        icon = { tint ->
                            Icon(
                                painter = painterResource(CoreR.drawable.ic_alert_circle),
                                contentDescription = null,
                                tint = tint,
                                modifier = Modifier.size(44.dp),
                            )
                        },
                        action = {
                            CinefinButton(
                                text = stringResource(CoreR.string.retry),
                                onClick = { viewModel.load() },
                                size = CinefinButtonSize.Medium,
                                variant = CinefinButtonVariant.Filled,
                            )
                        },
                    )
                }
                !state.isLoading && visibleItems.isEmpty() -> {
                    CinefinEmptyState(
                        title = stringResource(CoreR.string.favorites_empty_title),
                        message = stringResource(CoreR.string.favorites_empty_message),
                        modifier =
                            Modifier.align(Alignment.Center).padding(horizontal = paddingStart),
                        icon = { tint ->
                            Icon(
                                painter = painterResource(CoreR.drawable.ic_bookmark),
                                contentDescription = null,
                                tint = tint,
                                modifier = Modifier.size(44.dp),
                            )
                        },
                    )
                }
                else -> {
                    LazyVerticalGrid(
                        columns = GridCellsAdaptiveWithMinColumns(minSize = 176.dp, minColumns = 2),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = contentPadding,
                        horizontalArrangement = Arrangement.spacedBy(gridGutter),
                        verticalArrangement = Arrangement.spacedBy(CinefinSpacing.Space6),
                    ) {
                        items(items = visibleItems, key = { item -> item.id }) { item ->
                            ItemCard(
                                item = item,
                                direction = Direction.VERTICAL,
                                onClick = {
                                    if (selection.selectionMode) {
                                        selection =
                                            selection
                                                .retain(loadedIds.toSet())
                                                .toggle(item.id.toString())
                                    } else {
                                        onItemClick(item)
                                    }
                                },
                                modifier = Modifier.animateItem(),
                                selectionMode = selection.selectionMode,
                                selected = selection.isSelected(item.id.toString()),
                                onLongClick = {
                                    selection =
                                        selection
                                            .retain(loadedIds.toSet())
                                            .longPress(item.id.toString())
                                },
                                downloadBadge = downloadBadges[item.id] ?: DownloadBadgeInfo(),
                            )
                        }
                    }
                }
            }
        }

        CinefinSnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.padding(horizontal = paddingStart),
        )

        if (selection.selectionMode) {
            MediaBatchActionBar(
                mode = MediaBatchMode.FAVORITES,
                selectedCount = selection.selectedCount,
                isEnabled = { action -> mediaBatchActionEnabled(action, selectedCaps) },
                onAction = { action ->
                    when (action) {
                        MediaBatchAction.FAVORITE -> batchViewModel.favoriteSelected(selectedItems)
                        MediaBatchAction.DOWNLOAD -> batchViewModel.downloadSelected(selectedItems)
                        MediaBatchAction.MARK_PLAYED ->
                            batchViewModel.markPlayedSelected(selectedItems)
                        else -> Unit
                    }
                },
            )
        }
    }
}

@Composable
private fun FavoriteTypeFilter.label(): String =
    stringResource(
        when (this) {
            FavoriteTypeFilter.ALL -> CoreR.string.favorites_filter_all
            FavoriteTypeFilter.MOVIES -> CoreR.string.favorites_filter_movies
            FavoriteTypeFilter.SHOWS -> CoreR.string.favorites_filter_shows
            FavoriteTypeFilter.EPISODES -> CoreR.string.favorites_filter_episodes
        }
    )

@Composable
private fun FavoriteSort.label(): String =
    stringResource(
        when (this) {
            FavoriteSort.DATE_ADDED -> CoreR.string.favorites_sort_date_added
            FavoriteSort.NAME -> CoreR.string.favorites_sort_name
        }
    )

/** 预览壳：真实页面需要 Hilt ViewModel / 下载引擎，预览只验证顶栏 + 空态排版。 */
@PreviewScreenSizes
@Composable
private fun FavoritesScreenPreview() {
    Column(modifier = Modifier.fillMaxSize()) {
        CinefinPageTopBar(
            title = stringResource(CoreR.string.title_my_favorites),
            onOpenDrawer = {},
        )
        CinefinEmptyState(
            title = stringResource(CoreR.string.favorites_empty_title),
            message = stringResource(CoreR.string.favorites_empty_message),
            modifier = Modifier.weight(1f),
        )
    }
}
