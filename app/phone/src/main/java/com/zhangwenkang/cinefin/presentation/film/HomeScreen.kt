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
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
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
import com.zhangwenkang.cinefin.presentation.film.components.HomeHero
import com.zhangwenkang.cinefin.presentation.film.components.HomeSection
import com.zhangwenkang.cinefin.presentation.film.components.HomeTopBar
import com.zhangwenkang.cinefin.presentation.film.components.HomeView
import com.zhangwenkang.cinefin.presentation.film.components.PosterItemCard
import com.zhangwenkang.cinefin.presentation.film.components.SectionHeader
import com.zhangwenkang.cinefin.presentation.theme.CinefinTheme
import com.zhangwenkang.cinefin.presentation.theme.spacings
import com.zhangwenkang.cinefin.presentation.utils.rememberSafePadding

/** 版心：所有内容都对齐到这条页边线（含横屏时的刘海安全区）。 */
private val wallMinColumnWidth = 152.dp
private val wallGap = 12.dp

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

/**
 * 首页：一条主视觉 + 若干条走廊 + 面海报墙。
 *
 * 主视觉通栏出血（首页唯一允许压过页边的元素），其余内容一律对齐页边； 海报墙的列数由屏幕宽度算出，因此首末列能精确贴住页边线， 不会出现"内容比标题多出半格"的错位。
 */
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
    val gutter = MaterialTheme.spacings.default
    val gutterStart = safePadding.start + gutter
    val gutterEnd = safePadding.end + gutter
    val pagePadding = PaddingValues(start = gutterStart, end = gutterEnd)

    var showErrorDialog by rememberSaveable { mutableStateOf(false) }

    val heroItem =
        state.resumeSection?.homeSection?.items?.firstOrNull()
            ?: state.nextUpSection?.homeSection?.items?.firstOrNull()
    val resumeRail = state.resumeSection?.homeSection?.takeIf { it.items.size > 1 }
    val nextUpRail = state.nextUpSection?.homeSection?.items?.takeIf { it.isNotEmpty() }

    val wallItems =
        remember(state.views) {
            state.views.flatMap { it.view.items }.distinctBy { it.id }.take(60)
        }

    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    val columns =
        remember(screenWidthDp) {
            val available = screenWidthDp - (gutter.value * 2).toInt()
            val perColumn = (wallMinColumnWidth + wallGap).value.toInt()
            ((available + wallGap.value.toInt()) / perColumn).coerceAtLeast(2)
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
                    start = gutterStart,
                    top = safePadding.top,
                    end = gutterEnd,
                ),
        )

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            PullToRefreshBox(isRefreshing = false, onRefresh = onRetry) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(columns),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding =
                        PaddingValues(bottom = safePadding.bottom + MaterialTheme.spacings.large),
                    horizontalArrangement = Arrangement.spacedBy(wallGap),
                    verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.medium),
                ) {
                    heroItem?.let { item ->
                        item(key = "hero", span = { GridItemSpan(maxLineSpan) }) {
                            HomeHero(
                                item = item,
                                onClick = onItemClick,
                                contentPaddingHorizontal = gutterStart,
                            )
                        }
                    }

                    resumeRail?.let { section ->
                        item(key = "resume_rail", span = { GridItemSpan(maxLineSpan) }) {
                            HomeSection(
                                section = section.copy(items = section.items.drop(1)),
                                itemsPadding = pagePadding,
                                onAction = { action ->
                                    if (action is HomeAction.OnItemClick) onItemClick(action.item)
                                },
                            )
                        }
                    }

                    nextUpRail?.let { items ->
                        item(key = "next_up", span = { GridItemSpan(maxLineSpan) }) {
                            HomeSection(
                                section = state.nextUpSection!!.homeSection.copy(items = items),
                                itemsPadding = pagePadding,
                                onAction = { action ->
                                    if (action is HomeAction.OnItemClick) onItemClick(action.item)
                                },
                            )
                        }
                    }

                    state.views.take(3).forEach { view ->
                        item(key = "view_${view.id}", span = { GridItemSpan(maxLineSpan) }) {
                            HomeView(
                                view = view,
                                itemsPadding = pagePadding,
                                onAction = { action ->
                                    if (action is HomeAction.OnItemClick) onItemClick(action.item)
                                },
                            )
                        }
                    }

                    if (wallItems.isNotEmpty()) {
                        item(key = "wall_title", span = { GridItemSpan(maxLineSpan) }) {
                            SectionHeader(
                                title = stringResource(FilmR.string.recently_added),
                                modifier =
                                    Modifier.padding(
                                            start = gutterStart,
                                            end = gutterEnd,
                                            top = MaterialTheme.spacings.small,
                                        )
                                        .padding(bottom = MaterialTheme.spacings.small),
                            )
                        }

                        itemsIndexed(wallItems, key = { _, item -> item.id }) { index, item ->
                            val isFirstColumn = index % columns == 0
                            val isLastColumn = index % columns == columns - 1
                            PosterItemCard(
                                item = item,
                                onClick = onItemClick,
                                index = index,
                                modifier =
                                    Modifier.padding(
                                        start = if (isFirstColumn) gutterStart else 0.dp,
                                        end = if (isLastColumn) gutterEnd else 0.dp,
                                    ),
                            )
                        }
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
