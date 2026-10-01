package com.zhangwenkang.cinefin.presentation.film

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
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
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinSpacing
import com.zhangwenkang.cinefin.core.presentation.theme.ProvideLumen
import com.zhangwenkang.cinefin.film.R as FilmR
import com.zhangwenkang.cinefin.film.presentation.home.HomeAction
import com.zhangwenkang.cinefin.film.presentation.home.HomeState
import com.zhangwenkang.cinefin.film.presentation.home.HomeViewModel
import com.zhangwenkang.cinefin.models.FindroidItem
import com.zhangwenkang.cinefin.presentation.components.ErrorDialog
import com.zhangwenkang.cinefin.presentation.components.HomeSkeleton
import com.zhangwenkang.cinefin.presentation.components.LumenSkeletonOverlay
import com.zhangwenkang.cinefin.presentation.film.components.HomeHero
import com.zhangwenkang.cinefin.presentation.film.components.HomeSection
import com.zhangwenkang.cinefin.presentation.film.components.HomeTopBar
import com.zhangwenkang.cinefin.presentation.film.components.HomeView
import com.zhangwenkang.cinefin.presentation.film.components.PosterItemCard
import com.zhangwenkang.cinefin.presentation.film.components.SectionHeader
import com.zhangwenkang.cinefin.presentation.film.components.lumenEntrance
import com.zhangwenkang.cinefin.presentation.theme.CinefinTheme
import com.zhangwenkang.cinefin.presentation.utils.rememberGridGutter
import com.zhangwenkang.cinefin.presentation.utils.rememberPageGutter
import com.zhangwenkang.cinefin.presentation.utils.rememberSafePadding

/** 版心：所有内容都对齐到这条页边线（含横屏时的刘海安全区）。 */
private val wallMinColumnWidth = 152.dp

@Composable
fun HomeScreen(
    /** 抽屉入口；null = 当前形态没有抽屉（手机 Compact，W6-R6N）。 */
    onOpenDrawer: (() -> Unit)?,
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
    onOpenDrawer: (() -> Unit)?,
    onSearchClick: () -> Unit,
    onItemClick: (FindroidItem) -> Unit,
    onRetry: () -> Unit,
) {
    val safePadding = rememberSafePadding(handleStartInsets = false)
    val gutter = rememberPageGutter()
    val wallGap = rememberGridGutter()
    val gutterStart = safePadding.start + gutter
    val gutterEnd = safePadding.end + gutter

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

    ProvideLumen {
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

            BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
                // 列数按网格实际可用宽度算（已排除侧轨与左右页边距）：按窗口宽度算会在
                // 平板（侧轨占宽）上多出一列，把卡片压到最小列宽以下；页边距由网格
                // `contentPadding` 统一承担，保证同一行所有卡片完全等宽。
                val columns =
                    remember(maxWidth, gutterStart, gutterEnd, wallGap) {
                        val available = maxWidth - gutterStart - gutterEnd
                        ((available + wallGap) / (wallMinColumnWidth + wallGap))
                            .toInt()
                            .coerceAtLeast(2)
                    }
                PullToRefreshBox(isRefreshing = false, onRefresh = onRetry) {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(columns),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding =
                            PaddingValues(
                                start = gutterStart,
                                end = gutterEnd,
                                bottom = safePadding.bottom + CinefinSpacing.Space8,
                            ),
                        horizontalArrangement = Arrangement.spacedBy(wallGap),
                        // Lumen 节奏：区块之间 32dp（旧稿 16dp），页面因此有"幕布"般的呼吸感
                        verticalArrangement = Arrangement.spacedBy(CinefinSpacing.Space8),
                    ) {
                        heroItem?.let { item ->
                            item(key = "hero", span = { GridItemSpan(maxLineSpan) }) {
                                HomeHero(
                                    item = item,
                                    onClick = onItemClick,
                                    modifier = Modifier.lumenEntrance(),
                                )
                            }
                        }

                        resumeRail?.let { section ->
                            item(key = "resume_rail", span = { GridItemSpan(maxLineSpan) }) {
                                HomeSection(
                                    section = section.copy(items = section.items.drop(1)),
                                    itemsPadding = PaddingValues(),
                                    onAction = { action ->
                                        if (action is HomeAction.OnItemClick)
                                            onItemClick(action.item)
                                    },
                                )
                            }
                        }

                        nextUpRail?.let { items ->
                            item(key = "next_up", span = { GridItemSpan(maxLineSpan) }) {
                                HomeSection(
                                    section = state.nextUpSection!!.homeSection.copy(items = items),
                                    itemsPadding = PaddingValues(),
                                    onAction = { action ->
                                        if (action is HomeAction.OnItemClick)
                                            onItemClick(action.item)
                                    },
                                )
                            }
                        }

                        state.views.take(3).forEach { view ->
                            item(key = "view_${view.id}", span = { GridItemSpan(maxLineSpan) }) {
                                HomeView(
                                    view = view,
                                    itemsPadding = PaddingValues(),
                                    onAction = { action ->
                                        if (action is HomeAction.OnItemClick)
                                            onItemClick(action.item)
                                    },
                                )
                            }
                        }

                        if (wallItems.isNotEmpty()) {
                            item(key = "wall_title", span = { GridItemSpan(maxLineSpan) }) {
                                SectionHeader(
                                    title = stringResource(FilmR.string.recently_added),
                                    modifier =
                                        Modifier.padding(top = CinefinSpacing.Space2)
                                            .padding(bottom = CinefinSpacing.Space2),
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
                }

                if (state.error != null && showErrorDialog) {
                    ErrorDialog(
                        exception = state.error!!,
                        onDismissRequest = { showErrorDialog = false },
                    )
                }

                // 首屏加载过渡（W6-VIS D24）：数据到达前先铺骨架屏，就绪后骨架 220ms 淡出、卡片按
                // `lumenEntrance` 错峰入场——不再出现"一片黑板直出"。
                LumenSkeletonOverlay(
                    visible = state.isLoading && heroItem == null && wallItems.isEmpty()
                ) {
                    HomeSkeleton(
                        columns = columns,
                        gutterStart = gutterStart,
                        gutterEnd = gutterEnd,
                        modifier = Modifier.fillMaxSize().padding(top = CinefinSpacing.Space4),
                    )
                }
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
