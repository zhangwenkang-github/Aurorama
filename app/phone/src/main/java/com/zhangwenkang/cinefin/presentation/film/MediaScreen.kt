package com.zhangwenkang.cinefin.presentation.film

import androidx.compose.animation.core.animateDpAsState
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.window.core.layout.WindowSizeClass
import com.zhangwenkang.cinefin.core.R as CoreR
import com.zhangwenkang.cinefin.core.presentation.dummy.dummyCollections
import com.zhangwenkang.cinefin.film.presentation.media.MediaAction
import com.zhangwenkang.cinefin.film.presentation.media.MediaState
import com.zhangwenkang.cinefin.film.presentation.media.MediaViewModel
import com.zhangwenkang.cinefin.film.presentation.search.SearchAction
import com.zhangwenkang.cinefin.film.presentation.search.SearchState
import com.zhangwenkang.cinefin.film.presentation.search.SearchViewModel
import com.zhangwenkang.cinefin.models.FindroidItem
import com.zhangwenkang.cinefin.presentation.components.ErrorDialog
import com.zhangwenkang.cinefin.presentation.film.components.Direction
import com.zhangwenkang.cinefin.presentation.film.components.ErrorCard
import com.zhangwenkang.cinefin.presentation.film.components.FavoritesCard
import com.zhangwenkang.cinefin.presentation.film.components.FilmSearchBar
import com.zhangwenkang.cinefin.presentation.film.components.ItemCard
import com.zhangwenkang.cinefin.presentation.theme.CinefinTheme
import com.zhangwenkang.cinefin.presentation.theme.spacings
import com.zhangwenkang.cinefin.presentation.utils.rememberSafePadding

@Composable
fun MediaScreen(
    onOpenDrawer: () -> Unit,
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

@Composable
private fun MediaScreenLayout(
    onOpenDrawer: () -> Unit,
    state: MediaState,
    searchState: SearchState,
    searchExpanded: Boolean,
    onSearchExpand: (Boolean) -> Unit,
    onAction: (MediaAction) -> Unit,
    onSearchAction: (SearchAction) -> Unit,
) {
    val safePadding = rememberSafePadding(handleStartInsets = false)

    val paddingStart = safePadding.start + MaterialTheme.spacings.default
    val paddingEnd = safePadding.end + MaterialTheme.spacings.default
    val paddingBottom = safePadding.bottom + MaterialTheme.spacings.default

    val contentPaddingTop by
        animateDpAsState(
            targetValue =
                if (state.error != null) {
                    144.dp
                } else {
                    88.dp
                },
            label = "content_padding",
        )

    var showErrorDialog by rememberSaveable { mutableStateOf(false) }

    val windowSizeClass = currentWindowAdaptiveInfo().windowSizeClass
    val minColumnSize =
        when {
            windowSizeClass.isWidthAtLeastBreakpoint(
                WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND
            ) -> 320.dp
            windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND) ->
                240.dp
            else -> 160.dp
        }

    Column(modifier = Modifier.fillMaxSize()) {
        // 媒体库顶栏：抽屉入口必须一直在，否则进了这一页就再也回不去菜单
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
            IconButton(onClick = onOpenDrawer) {
                Icon(
                    painter = painterResource(CoreR.drawable.ic_menu),
                    contentDescription = stringResource(CoreR.string.title_media),
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = stringResource(CoreR.string.title_media),
                style = MaterialTheme.typography.titleLarge,
            )
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            FilmSearchBar(
                state = searchState,
                expanded = searchExpanded,
                onExpand = onSearchExpand,
                onAction = onSearchAction,
                modifier = Modifier.fillMaxWidth(),
                paddingStart = paddingStart,
                paddingEnd = paddingEnd,
            )
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = minColumnSize),
                modifier = Modifier.fillMaxSize(),
                contentPadding =
                    PaddingValues(
                        start = paddingStart,
                        top = contentPaddingTop,
                        end = paddingEnd,
                        bottom = paddingBottom,
                    ),
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.default),
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.default),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    FavoritesCard(onClick = { onAction(MediaAction.OnFavoritesClick) })
                }
                items(state.libraries, key = { it.id }) { library ->
                    ItemCard(
                        item = library,
                        direction = Direction.HORIZONTAL,
                        onClick = { onAction(MediaAction.OnItemClick(library)) },
                        modifier = Modifier.animateItem(),
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
                                top = 80.dp,
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

@PreviewScreenSizes
@Composable
private fun MediaScreenLayoutPreview() {
    CinefinTheme {
        MediaScreenLayout(
            onOpenDrawer = {},
            state =
                MediaState(libraries = dummyCollections, error = Exception("Failed to load data")),
            searchState = SearchState(),
            searchExpanded = false,
            onSearchExpand = {},
            onAction = {},
            onSearchAction = {},
        )
    }
}
