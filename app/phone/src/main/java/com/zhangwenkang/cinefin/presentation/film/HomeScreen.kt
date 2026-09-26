package com.zhangwenkang.cinefin.presentation.film

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zhangwenkang.cinefin.core.presentation.dummy.dummyHomeSection
import com.zhangwenkang.cinefin.core.presentation.dummy.dummyHomeSuggestions
import com.zhangwenkang.cinefin.core.presentation.dummy.dummyHomeView
import com.zhangwenkang.cinefin.core.presentation.dummy.dummyServer
import com.zhangwenkang.cinefin.core.R as CoreR
import com.zhangwenkang.cinefin.film.presentation.home.HomeAction
import com.zhangwenkang.cinefin.film.presentation.home.HomeState
import com.zhangwenkang.cinefin.film.presentation.home.HomeViewModel
import com.zhangwenkang.cinefin.models.FindroidCollection
import com.zhangwenkang.cinefin.models.FindroidItem
import com.zhangwenkang.cinefin.presentation.components.ErrorDialog
import com.zhangwenkang.cinefin.presentation.film.components.HomeCarousel
import com.zhangwenkang.cinefin.presentation.film.components.HomeHeader
import com.zhangwenkang.cinefin.presentation.film.components.HomeSection
import com.zhangwenkang.cinefin.presentation.film.components.HomeTabRow
import com.zhangwenkang.cinefin.presentation.film.components.HomeView
import com.zhangwenkang.cinefin.presentation.film.components.ServerSelectionBottomSheet
import com.zhangwenkang.cinefin.presentation.theme.CinefinTheme
import com.zhangwenkang.cinefin.presentation.theme.spacings
import com.zhangwenkang.cinefin.presentation.utils.rememberSafePadding
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(
    onLibraryClick: (library: FindroidCollection) -> Unit,
    onSearchClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onManageServers: () -> Unit,
    onItemClick: (item: FindroidItem) -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(true) { viewModel.loadData() }

    HomeScreenLayout(
        state = state,
        onAction = { action ->
            when (action) {
                is HomeAction.OnItemClick -> onItemClick(action.item)
                is HomeAction.OnLibraryClick -> onLibraryClick(action.library)
                is HomeAction.OnSearchClick -> onSearchClick()
                is HomeAction.OnSettingsClick -> onSettingsClick()
                is HomeAction.OnManageServers -> onManageServers()
                else -> Unit
            }
            viewModel.onAction(action)
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreenLayout(state: HomeState, onAction: (HomeAction) -> Unit) {
    val scope = rememberCoroutineScope()
    val safePadding = rememberSafePadding(handleStartInsets = false)

    val paddingStart = safePadding.start + MaterialTheme.spacings.default
    val paddingTop = safePadding.top + MaterialTheme.spacings.small
    val paddingEnd = safePadding.end + MaterialTheme.spacings.default
    val paddingBottom = safePadding.bottom + MaterialTheme.spacings.default

    val itemsPadding = PaddingValues(start = paddingStart, end = paddingEnd)

    var showErrorDialog by rememberSaveable { mutableStateOf(false) }
    val showServerSelectionSheetState = rememberModalBottomSheetState()
    var showServerSelectionBottomSheet by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()
    var selectedTab by rememberSaveable { mutableStateOf(0) }

    // 顶部标签 = 首页 + 各媒体库，点击后平滑滚动到对应内容行
    val tabs = buildList {
        add(stringResource(CoreR.string.title_home))
        state.views.forEach { add(it.view.name) }
    }
    val tabTargets = buildList {
        var index = 0
        add(index)
        if (state.suggestionsSection != null) index++
        if (state.resumeSection != null) index++
        if (state.nextUpSection != null) index++
        state.views.forEach {
            add(index)
            index++
        }
    }

    Column(modifier = Modifier.fillMaxSize().semantics { isTraversalGroup = true }) {
        HomeHeader(
            serverName = state.server?.name ?: "",
            isLoading = state.isLoading,
            isError = state.error != null,
            onServerClick = { showServerSelectionBottomSheet = true },
            onErrorClick = { showErrorDialog = true },
            onRetryClick = { onAction(HomeAction.OnRetryClick) },
            onSearchClick = { onAction(HomeAction.OnSearchClick) },
            onUserClick = { onAction(HomeAction.OnSettingsClick) },
            modifier = Modifier.padding(start = paddingStart, top = paddingTop, end = paddingEnd),
        )
        Spacer(modifier = Modifier.height(MaterialTheme.spacings.medium))
        HomeTabRow(
            tabs = tabs,
            selectedIndex = selectedTab,
            onSelect = { index ->
                selectedTab = index
                scope.launch { listState.animateScrollToItem(tabTargets.getOrElse(index) { 0 }) }
            },
        )
        Spacer(modifier = Modifier.height(MaterialTheme.spacings.small))

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
        PullToRefreshBox(isRefreshing = false, onRefresh = { onAction(HomeAction.OnRetryClick) }) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().semantics { traversalIndex = 1f },
                contentPadding = PaddingValues(bottom = paddingBottom),
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.medium),
            ) {
                state.suggestionsSection?.let { section ->
                    item(key = section.id) {
                        HomeCarousel(
                            items = section.items,
                            itemsPadding = itemsPadding,
                            onAction = onAction,
                        )
                    }
                }
                state.resumeSection?.let { section ->
                    item(key = section.id) {
                        HomeSection(
                            section = section.homeSection,
                            itemsPadding = itemsPadding,
                            onAction = onAction,
                            modifier = Modifier.animateItem(),
                        )
                    }
                }
                state.nextUpSection?.let { section ->
                    item(key = section.id) {
                        HomeSection(
                            section = section.homeSection,
                            itemsPadding = itemsPadding,
                            onAction = onAction,
                            modifier = Modifier.animateItem(),
                        )
                    }
                }
                items(state.views, key = { it.id }) { view ->
                    HomeView(
                        view = view,
                        itemsPadding = itemsPadding,
                        onAction = onAction,
                        modifier = Modifier.animateItem(),
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

    if (showServerSelectionBottomSheet) {
        ServerSelectionBottomSheet(
            currentServerId = state.server?.id ?: "",
            onUpdate = {
                onAction(HomeAction.OnRetryClick)
                scope
                    .launch { showServerSelectionSheetState.hide() }
                    .invokeOnCompletion {
                        if (!showServerSelectionSheetState.isVisible) {
                            showServerSelectionBottomSheet = false
                        }
                    }
            },
            onManage = {
                onAction(HomeAction.OnManageServers)
                scope.launch { showServerSelectionSheetState.hide() }
            },
            onDismissRequest = { showServerSelectionBottomSheet = false },
            sheetState = showServerSelectionSheetState,
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
                    suggestionsSection = dummyHomeSuggestions,
                    resumeSection = dummyHomeSection,
                    views = listOf(dummyHomeView),
                    error = Exception("Failed to load data"),
                ),
            onAction = {},
        )
    }
}
