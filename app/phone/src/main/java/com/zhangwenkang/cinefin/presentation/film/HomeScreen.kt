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
import com.zhangwenkang.cinefin.models.CollectionType
import com.zhangwenkang.cinefin.models.FindroidCollection
import com.zhangwenkang.cinefin.models.FindroidFolder
import com.zhangwenkang.cinefin.models.FindroidItem
import com.zhangwenkang.cinefin.models.HomeSection
import com.zhangwenkang.cinefin.models.bookSourcePath
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
import com.zhangwenkang.cinefin.presentation.film.components.libraryPlaceholderIconRes
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
        onRetry = { viewModel.loadData(force = true) },
        downloadBadges = downloadBadges,
        bookCovers = bookCovers,
        onRequestBookCover = viewModel::requestBookCover,
        onRequestBookCoverFallback = viewModel::requestBookCoverFallback,
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
    /** W77-5：第三个参数 = 服务器元数据判定该书是 PDF（未下载时远端封面直接占位）。 */
    onRequestBookCover: (UUID, String?, Boolean) -> Unit = { _, _, _ -> },
    /** W69b：服务器图加载失败 → 忽略 URL 走本地生成（书籍卡回落）；W77-5：远端 PDF 直接占位。 */
    onRequestBookCoverFallback: (UUID, Boolean) -> Unit = { _, _ -> },
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
    // W64（用户第 12 条）：书籍条目（继续阅读 / 最近添加 · 书籍）传本地封面，卡片按
    // 「服务器图优先 → 本地封面 → 风格化类型占位」渲染；服务器图失败（离线）自动回落本地。
    val bookLocalCover: (FindroidItem) -> String? = { item -> bookCovers[item.id] }
    val bookPlaceholderIcon: (FindroidItem) -> Int? = { CoreR.drawable.ic_book }
    // W69c：音乐卡无图时的通用占位（音符 + 媒体色底，与 W59 下载页 / W68 媒体会话同口径）。
    val musicPlaceholderIcon: (FindroidItem) -> Int? = { CoreR.drawable.ic_music }
    // W69c：书籍才触发封面生成 / 回落（音乐 / 视频条目不做书籍解析，避免多余请求与失败标记）。
    val bookOnly: (FindroidItem) -> Boolean = { item -> item is FindroidFolder }
    val requestBookCover: (FindroidItem) -> Unit = { item ->
        if (bookOnly(item)) {
            onRequestBookCover(
                item.id,
                item.images.primary?.toString(),
                BookCoverRules.isPdfPath(item.bookSourcePath),
            )
        }
    }
    val requestBookCoverFallback: (FindroidItem) -> Unit = { item ->
        if (bookOnly(item)) {
            onRequestBookCoverFallback(item.id, BookCoverRules.isPdfPath(item.bookSourcePath))
        }
    }
    // W69c：按库类型给「最新 · <库名>」走廊选占位（纯函数 `libraryPlaceholderIconRes`，单测覆盖）。
    val libraryPlaceholderIcon: (CollectionType) -> (FindroidItem) -> Int? = { type ->
        { _ -> libraryPlaceholderIconRes(type) }
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
                                imageOverrideFor = bookLocalCover,
                                placeholderIconResFor = bookPlaceholderIcon,
                                onItemVisible = requestBookCover,
                                onCoverFallback = requestBookCoverFallback,
                            )
                        }

                        listeningRail?.let { section ->
                            homeRail(
                                key = "resume_listening",
                                section = section,
                                downloadBadges = downloadBadges,
                                onItemClick = onItemClick,
                                onLibraryClick = onLibraryClick,
                                placeholderIconResFor = musicPlaceholderIcon,
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
                                    imageOverrideFor = bookLocalCover,
                                    placeholderIconResFor = libraryPlaceholderIcon(view.view.type),
                                    onItemVisible = requestBookCover,
                                    onServerImageFailed = requestBookCoverFallback,
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
                            imageOverrideFor = bookLocalCover,
                            placeholderIconResFor = bookPlaceholderIcon,
                            onItemVisible = requestBookCover,
                            onCoverFallback = requestBookCoverFallback,
                        )
                        homePosterWall(
                            keyPrefix = "recent_music",
                            titleRes = FilmR.string.recently_added_music,
                            items = state.recentlyAddedMusic,
                            onItemClick = onItemClick,
                            downloadBadges = downloadBadges,
                            placeholderIconResFor = musicPlaceholderIcon,
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
                // W69：骨架只在"真的没有任何可渲染内容"时铺（缓存优先）——已上屏的走廊 / 海报墙 /
                // 库行绝不被骨架盖住；刷新在有内容时是静默的，卡片原地更新。
                val hasRenderableContent =
                    heroItem != null ||
                        resumeRail != null ||
                        readingRail != null ||
                        listeningRail != null ||
                        nextUpRail != null ||
                        wallItems.isNotEmpty() ||
                        state.recentlyAddedBooks.isNotEmpty() ||
                        state.recentlyAddedMusic.isNotEmpty() ||
                        state.views.isNotEmpty()
                LumenSkeletonOverlay(visible = state.isLoading && !hasRenderableContent) {
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
    /** W69b：服务器图加载失败 → 本地生成回落。 */
    onCoverFallback: (FindroidItem) -> Unit = {},
) {
    item(key = key, span = { GridItemSpan(maxLineSpan) }) {
        HomeSection(
            section = section,
            itemsPadding = PaddingValues(),
            downloadBadges = downloadBadges,
            imageOverrideFor = imageOverrideFor,
            placeholderIconResFor = placeholderIconResFor,
            onItemVisible = onItemVisible,
            onServerImageFailed = onCoverFallback,
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
    /** W69b：服务器图加载失败 → 本地生成回落。 */
    onCoverFallback: (FindroidItem) -> Unit = {},
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
            onServerImageFailed = { onCoverFallback(item) },
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
