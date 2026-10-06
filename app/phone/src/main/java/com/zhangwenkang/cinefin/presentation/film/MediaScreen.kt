package com.zhangwenkang.cinefin.presentation.film

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
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.window.core.layout.WindowSizeClass
import com.zhangwenkang.cinefin.core.R as CoreR
import com.zhangwenkang.cinefin.core.presentation.components.CinefinPageTopBar
import com.zhangwenkang.cinefin.core.presentation.dummy.dummyCollections
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinSpacing
import com.zhangwenkang.cinefin.core.presentation.theme.LocalCinefinColors
import com.zhangwenkang.cinefin.film.R as FilmR
import com.zhangwenkang.cinefin.film.presentation.media.MediaAction
import com.zhangwenkang.cinefin.film.presentation.media.MediaState
import com.zhangwenkang.cinefin.film.presentation.media.MediaViewModel
import com.zhangwenkang.cinefin.film.presentation.search.SearchAction
import com.zhangwenkang.cinefin.film.presentation.search.SearchState
import com.zhangwenkang.cinefin.film.presentation.search.SearchViewModel
import com.zhangwenkang.cinefin.local.LocalMediaKind
import com.zhangwenkang.cinefin.models.FindroidItem
import com.zhangwenkang.cinefin.presentation.components.ErrorDialog
import com.zhangwenkang.cinefin.presentation.components.LumenSkeletonOverlay
import com.zhangwenkang.cinefin.presentation.components.MediaLibrarySkeleton
import com.zhangwenkang.cinefin.presentation.components.TopBarAction
import com.zhangwenkang.cinefin.presentation.downloads.DownloadStatusViewModel
import com.zhangwenkang.cinefin.presentation.film.components.DownloadBadgeInfo
import com.zhangwenkang.cinefin.presentation.film.components.ErrorCard
import com.zhangwenkang.cinefin.presentation.film.components.FilmSearchBar
import com.zhangwenkang.cinefin.presentation.film.components.LibraryEntryCard
import com.zhangwenkang.cinefin.presentation.film.components.SectionHeader
import com.zhangwenkang.cinefin.presentation.local.LocalLibrarySection
import com.zhangwenkang.cinefin.presentation.local.LocalSearchPlaybackViewModel
import com.zhangwenkang.cinefin.presentation.theme.CinefinTheme
import com.zhangwenkang.cinefin.presentation.utils.rememberGridGutter
import com.zhangwenkang.cinefin.presentation.utils.rememberPageGutter
import com.zhangwenkang.cinefin.presentation.utils.rememberSafePadding
import java.util.UUID

@Composable
fun MediaScreen(
    /** 抽屉入口；null = 当前形态没有抽屉（手机 Compact，W6-R6N）。 */
    onOpenDrawer: (() -> Unit)?,
    onItemClick: (FindroidItem) -> Unit,
    /** W37：打开本地媒体库详情（媒体库页常显入口）。 */
    onOpenLocalLibrary: (Long) -> Unit = {},
    /** W70：本地库集合 / 可见性变化 → 侧栏「本地媒体库」子分组只读刷新（新建库后侧轨即时跟随）。 */
    onLocalLibrariesChanged: () -> Unit = {},
    /** W43：搜索命中本地条目的打开链路（与本地库详情页一致）—— 视频 → 播放器；书籍 → 阅读器；音乐 → 现有音乐播放链路（成功后跳音乐 Tab）。 */
    onPlayLocalVideo: (UUID) -> Unit = {},
    onOpenLocalBook: (UUID, String, String) -> Unit = { _, _, _ -> },
    onLocalMusicStarted: () -> Unit = {},
    searchExpanded: Boolean,
    onSearchExpand: (Boolean) -> Unit,
    viewModel: MediaViewModel = hiltViewModel(),
    searchViewModel: SearchViewModel = hiltViewModel(),
    playbackViewModel: LocalSearchPlaybackViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val searchState by searchViewModel.state.collectAsStateWithLifecycle()
    val downloadStatusViewModel: DownloadStatusViewModel = hiltViewModel()
    val downloadBadges by downloadStatusViewModel.badges.collectAsStateWithLifecycle()

    // W73 #16：媒体页每次回到前台（RESUMED）都重拉库列表——数据源是仓库共享元数据缓存（TTL 10 分钟，
    // 缓存有效期内零网络），既修「库列表只在首进加载一次」，也与侧栏 / 视频页保持同一集合。
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { viewModel.loadData() }
    }

    MediaScreenLayout(
        onOpenDrawer = onOpenDrawer,
        state = state,
        searchState = searchState,
        searchExpanded = searchExpanded,
        onSearchExpand = onSearchExpand,
        onAction = { action ->
            when (action) {
                is MediaAction.OnItemClick -> onItemClick(action.item)
                else -> Unit
            }
            viewModel.onAction(action)
        },
        onOpenLocalLibrary = onOpenLocalLibrary,
        onLocalLibrariesChanged = onLocalLibrariesChanged,
        downloadBadges = downloadBadges,
        onSearchAction = { action ->
            when (action) {
                is SearchAction.OnItemClick -> onItemClick(action.item)
                is SearchAction.OnLocalItemClick ->
                    when (action.hit.entry.kind) {
                        LocalMediaKind.VIDEO -> onPlayLocalVideo(action.hit.entry.itemId)
                        LocalMediaKind.BOOK ->
                            onOpenLocalBook(
                                action.hit.entry.itemId,
                                action.hit.entry.displayName,
                                action.hit.entry.documentUri,
                            )
                        LocalMediaKind.MUSIC ->
                            playbackViewModel.playMusic(
                                entry = action.hit.entry,
                                onStarted = onLocalMusicStarted,
                            )
                    }
                else -> Unit
            }
            searchViewModel.onAction(action)
        },
    )
}

/**
 * 媒体库总览（W39 两段式，Lumen + 去拥挤）：
 *
 * 1. **顶栏**：侧栏入口 +「媒体库」+ 计数副题 + 右侧动作（收藏星形 / 搜索）同排；页内不再常显大搜索框， 点搜索图标才展开搜索浮层（原有搜索流程不变）；
 * 2. **两段式**：①本地媒体库（标题行右侧「＋ 新建」；无库时一行空态）→ ②服务器媒体库（标题行 + 16:9 大卡网格，
 *    位置上移、首屏尽量露出）；旧版整行「收藏」大卡与整块本地说明文案均已下线；
 * 3. **间距**：列距 16 / 26dp（随窗口），行距 24dp，区块间距 24–32dp。
 */
@Composable
private fun MediaScreenLayout(
    onOpenDrawer: (() -> Unit)?,
    state: MediaState,
    searchState: SearchState,
    searchExpanded: Boolean,
    onSearchExpand: (Boolean) -> Unit,
    onAction: (MediaAction) -> Unit,
    onOpenLocalLibrary: (Long) -> Unit,
    onLocalLibrariesChanged: () -> Unit,
    onSearchAction: (SearchAction) -> Unit,
    downloadBadges: Map<UUID, DownloadBadgeInfo> = emptyMap(),
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
        // 顶栏（W8-R3 统一）：侧栏入口 +「媒体库」+ 计数副题在同一行（修复旧版"按钮独占一块、
        // 标题在下一块"的两段式），右侧是页面动作（搜索）。与音乐 / 书架的顶栏同一尺寸与内边距。
        CinefinPageTopBar(
            title = stringResource(CoreR.string.title_media),
            subtitle =
                if (state.libraries.isNotEmpty()) {
                    stringResource(FilmR.string.library_count, state.libraries.size)
                } else {
                    null
                },
            onOpenDrawer = onOpenDrawer,
            modifier = Modifier.padding(start = safePadding.start),
            actions = {
                TopBarAction(
                    icon = CoreR.drawable.ic_search,
                    onClick = { onSearchExpand(true) },
                    contentDescription = stringResource(CoreR.string.search),
                )
            },
        )
        Spacer(Modifier.height(CinefinSpacing.Space2))

        // W39：页内不再常显大搜索框（只保留顶栏图标入口）；点图标后才展开搜索浮层，
        // 原有搜索流程（SearchBar + SearchViewModel）不变，关闭后回到两段式总览。
        if (searchExpanded) {
            FilmSearchBar(
                state = searchState,
                downloadBadges = downloadBadges,
                expanded = true,
                onExpand = onSearchExpand,
                onAction = onSearchAction,
                // 不要给 SearchBar 传 weight / 固定高度约束：M3 展开态要求父布局不限制尺寸
                // （约束固定 min=max 会把输入框撑满、结果区压成 0 高）。它自己会占满剩余空间。
                modifier = Modifier.fillMaxWidth(),
                paddingStart = paddingStart,
                paddingEnd = paddingEnd,
            )
        } else {
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
                    // 两段式①：本地媒体库（标题行自带「＋ 新建」；无库时只留一行空态）。
                    item(span = { GridItemSpan(maxLineSpan) }, key = "local_library") {
                        LocalLibrarySection(
                            onOpenLibrary = onOpenLocalLibrary,
                            onLibrariesChanged = onLocalLibrariesChanged,
                        )
                    }
                    // 两段式②：服务器媒体库（标题行 + 16:9 大卡，位置上移、首屏尽量露出）。
                    if (state.libraries.isNotEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }, key = "server_library_header") {
                            SectionHeader(
                                title = "服务器媒体库",
                                modifier = Modifier.padding(top = CinefinSpacing.Space2),
                            )
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
                }
                // 媒体库加载过渡（W6-VIS D24）：库列表到达前用大卡骨架占位，到达后淡出
                LumenSkeletonOverlay(visible = state.isLoading && state.libraries.isEmpty()) {
                    MediaLibrarySkeleton(
                        gutterStart = paddingStart,
                        gutterEnd = paddingEnd,
                        columns = if (expanded) 2 else 1,
                        modifier =
                            Modifier.fillMaxSize()
                                .padding(top = CinefinSpacing.Space6)
                                .background(LocalCinefinColors.current.surface),
                    )
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
            onOpenLocalLibrary = {},
            onLocalLibrariesChanged = {},
            onSearchAction = {},
        )
    }
}
