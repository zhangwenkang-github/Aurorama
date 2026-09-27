package com.zhangwenkang.cinefin.presentation.film

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zhangwenkang.cinefin.core.presentation.dummy.dummyHomeSection
import com.zhangwenkang.cinefin.core.presentation.dummy.dummyHomeView
import com.zhangwenkang.cinefin.core.presentation.dummy.dummyServer
import com.zhangwenkang.cinefin.film.R as FilmR
import com.zhangwenkang.cinefin.film.presentation.home.HomeAction
import com.zhangwenkang.cinefin.film.presentation.home.HomeState
import com.zhangwenkang.cinefin.film.presentation.home.HomeViewModel
import com.zhangwenkang.cinefin.models.FindroidItem
import com.zhangwenkang.cinefin.presentation.components.ErrorDialog
import com.zhangwenkang.cinefin.presentation.film.components.HomeSection
import com.zhangwenkang.cinefin.presentation.film.components.HomeTopBar
import com.zhangwenkang.cinefin.presentation.film.components.PosterItemCard
import com.zhangwenkang.cinefin.presentation.theme.CinefinTheme
import com.zhangwenkang.cinefin.presentation.theme.spacings
import com.zhangwenkang.cinefin.presentation.utils.rememberSafePadding

@Composable
fun HomeScreen(
    onOpenDrawer: () -> Unit,
    onSearchClick: () -> Unit,
    onItemClick: (item: FindroidItem) -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(true) { viewModel.loadData() }

    HomeScreenLayout(
        state = state,
        onOpenDrawer = onOpenDrawer,
        onSearchClick = onSearchClick,
        onItemClick = onItemClick,
        onRetry = { viewModel.loadData() },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreenLayout(
    state: HomeState,
    onOpenDrawer: () -> Unit,
    onSearchClick: () -> Unit,
    onItemClick: (FindroidItem) -> Unit,
    onRetry: () -> Unit,
) {
    val safePadding = rememberSafePadding(handleStartInsets = false)

    val paddingStart = safePadding.start + MaterialTheme.spacings.default
    val paddingEnd = safePadding.end + MaterialTheme.spacings.default
    val paddingBottom = safePadding.bottom + MaterialTheme.spacings.large

    var showErrorDialog by rememberSaveable { mutableStateOf(false) }

    // “继续观看”优先；没有播放记录时退而展示“接下来”
    val resumeSection = state.resumeSection?.homeSection ?: state.nextUpSection?.homeSection

    // 海报墙：合并各媒体库的最新条目，去重后取前 60 个
    val wallItems =
        remember(state.views) {
            state.views.flatMap { it.view.items }.distinctBy { it.id }.take(60)
        }

    Column(modifier = Modifier.fillMaxSize().semantics { isTraversalGroup = true }) {
        HomeTopBar(
            onOpenDrawer = onOpenDrawer,
            onSearchClick = onSearchClick,
            isLoading = state.isLoading,
            isError = state.error != null,
            onErrorClick = { showErrorDialog = true },
            onRetryClick = onRetry,
            modifier =
                Modifier.padding(
                    start = paddingStart,
                    top = safePadding.top + MaterialTheme.spacings.small,
                    end = paddingEnd,
                ),
        )

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            PullToRefreshBox(isRefreshing = false, onRefresh = onRetry) {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 168.dp),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding =
                        PaddingValues(
                            start = paddingStart,
                            end = paddingEnd,
                            bottom = paddingBottom,
                        ),
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.medium),
                    verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.medium),
                ) {
                    resumeSection?.let { section ->
                        item(key = "resume", span = { GridItemSpan(maxLineSpan) }) {
                            HomeSection(
                                section = section,
                                itemsPadding = PaddingValues(0.dp),
                                onAction = { action ->
                                    if (action is HomeAction.OnItemClick) onItemClick(action.item)
                                },
                            )
                        }
                    }

                    item(key = "wall_title", span = { GridItemSpan(maxLineSpan) }) {
                        Text(
                            text = stringResource(FilmR.string.recently_added),
                            style = MaterialTheme.typography.titleLarge,
                            modifier =
                                Modifier.padding(
                                    top =
                                        if (resumeSection == null) {
                                            MaterialTheme.spacings.small
                                        } else {
                                            MaterialTheme.spacings.medium
                                        }
                                ),
                        )
                    }

                    itemsIndexed(wallItems, key = { _, item -> item.id }) { index, item ->
                        PosterItemCard(
                            item = item,
                            onClick = onItemClick,
                            index = index,
                        )
                    }
                }
            }

            if (state.error != null && showErrorDialog) {
                ErrorDialog(
                    exception = state.error!!,
                    onDismissRequest = { showErrorDialog = false },
                )
            }
        }
    }
}

@PreviewScreenSizes
@Composable
private fun HomeScreenLayoutPreview() {
    CinefinTheme {
        HomeScreenLayout(
            state =
                HomeState(
                    server = dummyServer,
                    resumeSection = dummyHomeSection,
                    views = listOf(dummyHomeView),
                ),
            onOpenDrawer = {},
            onSearchClick = {},
            onItemClick = {},
            onRetry = {},
        )
    }
}
