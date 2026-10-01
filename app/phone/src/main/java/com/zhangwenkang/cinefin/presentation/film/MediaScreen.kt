package com.zhangwenkang.cinefin.presentation.film

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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.window.core.layout.WindowSizeClass
import com.zhangwenkang.cinefin.core.R as CoreR
import com.zhangwenkang.cinefin.core.presentation.dummy.dummyCollections
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinSpacing
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinType
import com.zhangwenkang.cinefin.core.presentation.theme.LocalCinefinColors
import com.zhangwenkang.cinefin.film.R as FilmR
import com.zhangwenkang.cinefin.film.presentation.media.MediaAction
import com.zhangwenkang.cinefin.film.presentation.media.MediaState
import com.zhangwenkang.cinefin.film.presentation.media.MediaViewModel
import com.zhangwenkang.cinefin.film.presentation.search.SearchAction
import com.zhangwenkang.cinefin.film.presentation.search.SearchState
import com.zhangwenkang.cinefin.film.presentation.search.SearchViewModel
import com.zhangwenkang.cinefin.models.FindroidItem
import com.zhangwenkang.cinefin.presentation.components.ErrorDialog
import com.zhangwenkang.cinefin.presentation.components.TopBarAction
import com.zhangwenkang.cinefin.presentation.film.components.ErrorCard
import com.zhangwenkang.cinefin.presentation.film.components.FavoritesCard
import com.zhangwenkang.cinefin.presentation.film.components.FilmSearchBar
import com.zhangwenkang.cinefin.presentation.film.components.LibraryEntryCard
import com.zhangwenkang.cinefin.presentation.theme.CinefinTheme
import com.zhangwenkang.cinefin.presentation.utils.rememberGridGutter
import com.zhangwenkang.cinefin.presentation.utils.rememberPageGutter
import com.zhangwenkang.cinefin.presentation.utils.rememberSafePadding

@Composable
fun MediaScreen(
    /** 抽屉入口；null = 当前形态没有抽屉（手机 Compact，W6-R6N）。 */
    onOpenDrawer: (() -> Unit)?,
    onItemClick: (FindroidItem) -> Unit,
    onFavoritesClick: () -> Unit,
    searchExpanded: Boolean,
    onSearchExpand: (Boolean) -> Unit,
    viewModel: MediaViewModel = hiltViewModel(),
    searchViewModel: SearchViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val searchState by searchViewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(true) { viewModel.loadData() }

    MediaScreenLayout(
        onOpenDrawer = onOpenDrawer,
        state = state,
        searchState = searchState,
        searchExpanded = searchExpanded,
        onSearchExpand = onSearchExpand,
        onAction = { action ->
            when (action) {
                is MediaAction.OnItemClick -> onItemClick(action.item)
                is MediaAction.OnFavoritesClick -> onFavoritesClick()
                else -> Unit
            }
            viewModel.onAction(action)
        },
        onSearchAction = { action ->
            when (action) {
                is SearchAction.OnItemClick -> onItemClick(action.item)
                else -> Unit
            }
            searchViewModel.onAction(action)
        },
    )
}

/**
 * 媒体库总览（Lumen + 去拥挤）：
 *
 * 1. **层级**：顶栏只留抽屉入口，标题升级为大标题 + 计数副题，页面第一眼就知道"这是哪一层、有多少库"；
 * 2. **栅格**：库卡从"固定 260dp + 自适应列"改为"整列宽 16:9 大卡"，手机一列、平板 2 列—— 旧写法在手机上会挤出屏幕、在平板上留不规则空档，这是"拥挤感"的主因；
 * 3. **间距**：列距 16 / 26dp（随窗口），行距 24dp，区块上下留白 32dp。
 */
@Composable
private fun MediaScreenLayout(
    onOpenDrawer: (() -> Unit)?,
    state: MediaState,
    searchState: SearchState,
    searchExpanded: Boolean,
    onSearchExpand: (Boolean) -> Unit,
    onAction: (MediaAction) -> Unit,
    onSearchAction: (SearchAction) -> Unit,
) {
    val safePadding = rememberSafePadding(handleStartInsets = false)
    val colors = LocalCinefinColors.current

    val pageGutter = rememberPageGutter()
    val gridGutter = rememberGridGutter()
    val paddingStart = safePadding.start + pageGutter
    val paddingEnd = safePadding.end + pageGutter
    val paddingBottom = safePadding.bottom + pageGutter

    var showErrorDialog by rememberSaveable { mutableStateOf(false) }

    val windowSizeClass = currentWindowAdaptiveInfo().windowSizeClass
    val expanded =
        windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND)
    val minColumnSize =
        when {
            windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_LARGE_LOWER_BOUND) ->
                420.dp
            expanded -> 380.dp
            windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND) ->
                320.dp
            else -> 300.dp
        }

    Column(modifier = Modifier.fillMaxSize()) {
        // 媒体库顶栏：有抽屉的形态（平板）保留入口；手机 Compact 无抽屉，这里保持高度让标题对齐。
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier =
                Modifier.fillMaxWidth()
                    .padding(
                        start = paddingStart,
                        top = safePadding.top,
                        end = paddingEnd,
                    )
                    .height(56.dp),
        ) {
            if (onOpenDrawer != null) {
                TopBarAction(
                    icon = CoreR.drawable.ic_menu,
                    onClick = onOpenDrawer,
                    contentDescription = stringResource(CoreR.string.title_media),
                )
            }
        }

        // 标题块：大标题 + 计数副题（层级）
        Column(
            modifier = Modifier.fillMaxWidth().padding(start = paddingStart, end = paddingEnd),
            verticalArrangement = Arrangement.spacedBy(CinefinSpacing.Space1),
        ) {
            Text(
                text = stringResource(CoreR.string.title_media),
                style = if (expanded) CinefinType.HeadlineLarge else CinefinType.HeadlineMedium,
                color = colors.onSurface,
            )
            if (state.libraries.isNotEmpty()) {
                Text(
                    text = stringResource(FilmR.string.library_count, state.libraries.size),
                    style = CinefinType.BodyMedium,
                    color = colors.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(CinefinSpacing.Space6))
        }

        // Lumen 修正（2026-10-01 验收缺陷）：搜索框不再"悬浮在滚动内容之上"——
        // 之前它和栅格同处一个 Box，上滑时卡片会钻到搜索框底下被挡住。现在改为列布局：
        // 搜索框是栅格上方的独立表头，栅格被裁剪在自己的区域里，内容永远不会滑到搜索框下面。
        Column(modifier = Modifier.weight(1f).fillMaxWidth()) {
            FilmSearchBar(
                state = searchState,
                expanded = searchExpanded,
                onExpand = onSearchExpand,
                onAction = onSearchAction,
                modifier = Modifier.fillMaxWidth(),
                paddingStart = paddingStart,
                paddingEnd = paddingEnd,
            )
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = minColumnSize),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding =
                        PaddingValues(
                            start = paddingStart,
                            top = CinefinSpacing.Space6,
                            end = paddingEnd,
                            bottom = paddingBottom + CinefinSpacing.Space8,
                        ),
                    horizontalArrangement = Arrangement.spacedBy(gridGutter),
                    verticalArrangement = Arrangement.spacedBy(CinefinSpacing.Space6),
                ) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        FavoritesCard(onClick = { onAction(MediaAction.OnFavoritesClick) })
                    }
                    itemsIndexed(state.libraries, key = { _, library -> library.id }) {
                        index,
                        library ->
                        LibraryEntryCard(
                            item = library,
                            onClick = { onAction(MediaAction.OnItemClick(library)) },
                            index = index,
                        )
                    }
                }
                if (state.error != null) {
                    ErrorCard(
                        onShowStacktrace = { showErrorDialog = true },
                        onRetryClick = { onAction(MediaAction.OnRetryClick) },
                        modifier =
                            Modifier.fillMaxWidth()
                                .padding(
                                    start = paddingStart,
                                    top = CinefinSpacing.Space4,
                                    end = paddingEnd,
                                ),
                    )
                    if (showErrorDialog) {
                        ErrorDialog(
                            exception = state.error!!,
                            onDismissRequest = { showErrorDialog = false },
                        )
                    }
                }
            }
        }
    }
}

@PreviewScreenSizes
@Composable
private fun MediaScreenLayoutPreview() {
    CinefinTheme {
        MediaScreenLayout(
            onOpenDrawer = {},
            state = MediaState(libraries = dummyCollections, error = null),
            searchState = SearchState(),
            searchExpanded = false,
            onSearchExpand = {},
            onAction = {},
            onSearchAction = {},
        )
    }
}
