package com.zhangwenkang.cinefin.presentation.film

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
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
import com.zhangwenkang.cinefin.core.R as CoreR
import com.zhangwenkang.cinefin.core.presentation.dummy.dummyHomeSection
import com.zhangwenkang.cinefin.core.presentation.dummy.dummyHomeView
import com.zhangwenkang.cinefin.core.presentation.dummy.dummyServer
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinSpacing
import com.zhangwenkang.cinefin.core.presentation.theme.ProvideLumen
import com.zhangwenkang.cinefin.film.R as FilmR
import com.zhangwenkang.cinefin.film.presentation.home.HomeAction
import com.zhangwenkang.cinefin.film.presentation.home.HomeState
import com.zhangwenkang.cinefin.film.presentation.home.HomeViewModel
import com.zhangwenkang.cinefin.models.FindroidCollection
import com.zhangwenkang.cinefin.models.FindroidItem
import com.zhangwenkang.cinefin.models.HomeSection
import com.zhangwenkang.cinefin.presentation.components.ErrorDialog
import com.zhangwenkang.cinefin.presentation.components.HomeSkeleton
import com.zhangwenkang.cinefin.presentation.components.LumenSkeletonOverlay
import com.zhangwenkang.cinefin.presentation.downloads.DownloadStatusViewModel
import com.zhangwenkang.cinefin.presentation.film.components.DownloadBadgeInfo
import com.zhangwenkang.cinefin.presentation.film.components.FavoriteChangeEffect
import com.zhangwenkang.cinefin.presentation.film.components.HomeHero
import com.zhangwenkang.cinefin.presentation.film.components.HomeSection
import com.zhangwenkang.cinefin.presentation.film.components.HomeTopBar
import com.zhangwenkang.cinefin.presentation.film.components.HomeView
import com.zhangwenkang.cinefin.presentation.film.components.PosterItemCard
import com.zhangwenkang.cinefin.presentation.film.components.SectionHeader
import com.zhangwenkang.cinefin.presentation.film.components.lumenEntrance
import com.zhangwenkang.cinefin.presentation.local.HomeLocalMediaSection
import com.zhangwenkang.cinefin.presentation.theme.CinefinTheme
import com.zhangwenkang.cinefin.presentation.utils.rememberGridGutter
import com.zhangwenkang.cinefin.presentation.utils.rememberPageGutter
import com.zhangwenkang.cinefin.presentation.utils.rememberSafePadding
import com.zhangwenkang.cinefin.utils.BookCoverRules
import java.util.UUID

/** 版心：所有内容都对齐到这条页边线（含横屏时的刘海安全区）。 */
private val wallMinColumnWidth = 152.dp

@Composable
fun HomeScreen(
    /** 抽屉入口；null = 当前形态没有抽屉（手机 Compact，W6-R6N）。 */
    onOpenDrawer: (() -> Unit)?,
    onSearchClick: () -> Unit,
    onItemClick: (item: FindroidItem) -> Unit,
    /** 「最新 · <库名>」右侧「全部」入口（W54-D）：进入该库内容页并默认按「最近添加」排序。 */
    onLibraryClick: (FindroidCollection) -> Unit = {},
    /** W37：首页「本地媒体」入口（开关默认关，入口在本地媒体库总览里）。 */
    onOpenLocalLibrary: (Long) -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val downloadStatusViewModel: DownloadStatusViewModel = hiltViewModel()
    val downloadBadges by downloadStatusViewModel.badges.collectAsStateWithLifecycle()
    // W64：首页书籍卡本地封面（与书架同一条 BookCoverProvider 链路）。
    val bookCovers by viewModel.bookCovers.collectAsStateWithLifecycle()

    LaunchedEffect(true) { viewModel.loadData() }
    // W60b：收藏变更后重取首页走廊，卡片收藏角标即时一致。
    FavoriteChangeEffect { viewModel.loadData() }

    HomeScreenLayout(
        state = state,
        onOpenDrawer = onOpenDrawer,
        onSearchClick = onSearchClick,
        onItemClick = onItemClick,
        onLibraryClick = onLibraryClick,
        onOpenLocalLibrary = onOpenLocalLibrary,
        onRetry = { viewModel.loadData() },
        downloadBadges = downloadBadges,
        bookCovers = bookCovers,
        onRequestBookCover = viewModel::requestBookCover,
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
    onLibraryClick: (FindroidCollection) -> Unit,
    onOpenLocalLibrary: (Long) -> Unit,
    onRetry: () -> Unit,
    downloadBadges: Map<UUID, DownloadBadgeInfo> = emptyMap(),
    /** W64：书籍卡本地封面（itemId → `files/book_covers/<id>.jpg`）。 */
    bookCovers: Map<UUID, String> = emptyMap(),
    onRequestBookCover: (UUID, String?) -> Unit = { _, _ -> },
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
    val readingRail = state.resumeReadingSection?.homeSection
    val listeningRail = state.resumeListeningSection?.homeSection
    val nextUpRail = state.nextUpSection?.homeSection?.items?.takeIf { it.isNotEmpty() }
    val wallItems = state.recentlyAddedVideos
    // W64：书籍条目（继续阅读 / 最近添加 · 书籍）本地封面覆盖 + 类型占位（与书架同源）。
    val bookCoverOverride: (FindroidItem) -> String? = { item ->
        BookCoverRules.coverOverride(item.images.primary?.toString(), bookCovers[item.id])
    }
    val bookPlaceholderIcon: (FindroidItem) -> Int? = { CoreR.drawable.ic_book }
    val requestBookCover: (FindroidItem) -> Unit = { item ->
        onRequestBookCover(item.id, item.images.primary?.toString())
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
                                    downloadBadges = downloadBadges,
                                    onAction = { action ->
                                        action.dispatch(onItemClick, onLibraryClick)
                                    },
                                )
                            }
                        }

                        readingRail?.let { section ->
                            homeRail(
                                key = "resume_reading",
                                section = section,
                                downloadBadges = downloadBadges,
                                onItemClick = onItemClick,
                                onLibraryClick = onLibraryClick,
                                imageOverrideFor = bookCoverOverride,
                                placeholderIconResFor = bookPlaceholderIcon,
                                onItemVisible = requestBookCover,
                            )
                        }

                        listeningRail?.let { section ->
                            homeRail(
                                key = "resume_listening",
                                section = section,
                                downloadBadges = downloadBadges,
                                onItemClick = onItemClick,
                                onLibraryClick = onLibraryClick,
                            )
                        }

                        nextUpRail?.let { items ->
                            item(key = "next_up", span = { GridItemSpan(maxLineSpan) }) {
                                HomeSection(
                                    section = state.nextUpSection!!.homeSection.copy(items = items),
                                    itemsPadding = PaddingValues(),
                                    downloadBadges = downloadBadges,
                                    onAction = { action ->
                                        action.dispatch(onItemClick, onLibraryClick)
                                    },
                                )
                            }
                        }

                        // W37：本地媒体（`pref_local_library_visible`，默认关；无本地库时 section 自行隐藏）。
                        item(key = "local_media", span = { GridItemSpan(maxLineSpan) }) {
                            HomeLocalMediaSection(onOpenLibrary = onOpenLocalLibrary)
                        }

                        state.views.forEach { view ->
                            item(key = "view_${view.id}", span = { GridItemSpan(maxLineSpan) }) {
                                HomeView(
                                    view = view,
                                    itemsPadding = PaddingValues(),
                                    downloadBadges = downloadBadges,
                                    onAction = { action ->
                                        action.dispatch(onItemClick, onLibraryClick)
                                    },
                                )
                            }
                        }

                        // W54-D：「最近添加」按媒体类型拆成三条（视频 = 原海报墙形态保留）。
                        homePosterWall(
                            keyPrefix = "recent_video",
                            titleRes = FilmR.string.recently_added_videos,
                            items = wallItems,
                            onItemClick = onItemClick,
                            downloadBadges = downloadBadges,
                        )
                        homePosterWall(
                            keyPrefix = "recent_book",
                            titleRes = FilmR.string.recently_added_books,
                            items = state.recentlyAddedBooks,
                            onItemClick = onItemClick,
                            downloadBadges = downloadBadges,
                            imageOverrideFor = bookCoverOverride,
                            placeholderIconResFor = bookPlaceholderIcon,
                            onItemVisible = requestBookCover,
                        )
                        homePosterWall(
                            keyPrefix = "recent_music",
                            titleRes = FilmR.string.recently_added_music,
                            items = state.recentlyAddedMusic,
                            onItemClick = onItemClick,
                            downloadBadges = downloadBadges,
                        )
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

/** 首页 action 统一分发（W54-D 修 bug ①：`OnLibraryClick` 不能再被丢掉）。 */
private fun HomeAction.dispatch(
    onItemClick: (FindroidItem) -> Unit,
    onLibraryClick: (FindroidCollection) -> Unit,
) {
    when (this) {
        is HomeAction.OnItemClick -> onItemClick(item)
        is HomeAction.OnLibraryClick -> onLibraryClick(library)
        else -> Unit
    }
}

/** 首页走廊：标题 + 一排横版卡（继续阅读 / 继续收听用）。 */
private fun LazyGridScope.homeRail(
    key: String,
    section: HomeSection,
    downloadBadges: Map<UUID, DownloadBadgeInfo>,
    onItemClick: (FindroidItem) -> Unit,
    onLibraryClick: (FindroidCollection) -> Unit,
    imageOverrideFor: (FindroidItem) -> String? = { null },
    @DrawableRes placeholderIconResFor: (FindroidItem) -> Int? = { null },
    onItemVisible: (FindroidItem) -> Unit = {},
) {
    item(key = key, span = { GridItemSpan(maxLineSpan) }) {
        HomeSection(
            section = section,
            itemsPadding = PaddingValues(),
            downloadBadges = downloadBadges,
            imageOverrideFor = imageOverrideFor,
            placeholderIconResFor = placeholderIconResFor,
            onItemVisible = onItemVisible,
            onAction = { action -> action.dispatch(onItemClick, onLibraryClick) },
        )
    }
}

/** 首页海报墙分区：标题（通栏）+ 竖版海报卡（最近添加三条共用）。 */
private fun LazyGridScope.homePosterWall(
    keyPrefix: String,
    @StringRes titleRes: Int,
    items: List<FindroidItem>,
    onItemClick: (FindroidItem) -> Unit,
    downloadBadges: Map<UUID, DownloadBadgeInfo>,
    imageOverrideFor: (FindroidItem) -> String? = { null },
    @DrawableRes placeholderIconResFor: (FindroidItem) -> Int? = { null },
    onItemVisible: (FindroidItem) -> Unit = {},
) {
    if (items.isEmpty()) return
    item(key = "${keyPrefix}_title", span = { GridItemSpan(maxLineSpan) }) {
        SectionHeader(
            title = stringResource(titleRes),
            modifier =
                Modifier.padding(top = CinefinSpacing.Space2)
                    .padding(bottom = CinefinSpacing.Space2),
        )
    }
    itemsIndexed(items, key = { _, item -> "${keyPrefix}_${item.id}" }) { index, item ->
        LaunchedEffect(item.id) { onItemVisible(item) }
        PosterItemCard(
            item = item,
            onClick = onItemClick,
            index = index,
            downloadBadge = downloadBadges[item.id] ?: DownloadBadgeInfo(),
            imageOverride = imageOverrideFor(item),
            placeholderIconRes = placeholderIconResFor(item),
        )
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
            onLibraryClick = {},
            onOpenLocalLibrary = {},
            onRetry = {},
        )
    }
}
