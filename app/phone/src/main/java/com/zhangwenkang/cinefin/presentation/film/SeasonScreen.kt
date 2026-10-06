package com.zhangwenkang.cinefin.presentation.film

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.zhangwenkang.cinefin.PlayerActivity
import com.zhangwenkang.cinefin.core.R as CoreR
import com.zhangwenkang.cinefin.core.presentation.components.CinefinBatchBar
import com.zhangwenkang.cinefin.core.presentation.components.CinefinIconButton
import com.zhangwenkang.cinefin.core.presentation.components.CinefinSnackbarHost
import com.zhangwenkang.cinefin.core.presentation.components.DownloadSnackbarDuration
import com.zhangwenkang.cinefin.core.presentation.components.rememberMultiSelectState
import com.zhangwenkang.cinefin.core.presentation.dummy.dummySeason
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinSpacing
import com.zhangwenkang.cinefin.core.presentation.theme.ProvideLumen
import com.zhangwenkang.cinefin.film.R as FilmR
import com.zhangwenkang.cinefin.film.presentation.detail.DetailDownloadRules
import com.zhangwenkang.cinefin.film.presentation.detail.DetailDownloadState
import com.zhangwenkang.cinefin.film.presentation.detail.DetailDownloadViewModel
import com.zhangwenkang.cinefin.film.presentation.season.SeasonAction
import com.zhangwenkang.cinefin.film.presentation.season.SeasonState
import com.zhangwenkang.cinefin.film.presentation.season.SeasonViewModel
import com.zhangwenkang.cinefin.models.FindroidItem
import com.zhangwenkang.cinefin.presentation.components.DetailSkeleton
import com.zhangwenkang.cinefin.presentation.film.components.BatchDownloadDialog
import com.zhangwenkang.cinefin.presentation.film.components.DetailHero
import com.zhangwenkang.cinefin.presentation.film.components.EpisodeCard
import com.zhangwenkang.cinefin.presentation.film.components.ItemButtonsBar
import com.zhangwenkang.cinefin.presentation.film.components.ItemTopBar
import com.zhangwenkang.cinefin.presentation.film.components.downloadEventMessage
import com.zhangwenkang.cinefin.presentation.film.components.showsViewAction
import com.zhangwenkang.cinefin.presentation.selection.MediaBatchTopBarActions
import com.zhangwenkang.cinefin.presentation.theme.CinefinTheme
import com.zhangwenkang.cinefin.presentation.theme.spacings
import com.zhangwenkang.cinefin.presentation.utils.rememberLazyScrollMemoryState
import com.zhangwenkang.cinefin.presentation.utils.rememberSafePadding
import java.util.UUID
import org.jellyfin.sdk.model.api.BaseItemKind

@Composable
fun SeasonScreen(
    seasonId: UUID,
    navigateBack: () -> Unit,
    navigateHome: () -> Unit,
    navigateToItem: (item: FindroidItem) -> Unit,
    navigateToSeries: (seriesId: UUID) -> Unit,
    /** W60b：下载反馈 Snackbar「查看」→ 下载页。 */
    onOpenDownloads: () -> Unit = {},
    viewModel: SeasonViewModel = hiltViewModel(),
    detailDownloadViewModel: DetailDownloadViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val downloadSnapshot by detailDownloadViewModel.state.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    val viewLabel = stringResource(CoreR.string.snackbar_view)
    var batchDialogVisible by remember { mutableStateOf(false) }
    /** W63：季详情集列表长按多选（复用 W58 core 框架，与库网格同一交互）。 */
    var batchSelection by rememberMultiSelectState()

    val loadedEpisodeIds = state.episodes.map { episode -> episode.id.toString() }
    LaunchedEffect(loadedEpisodeIds) {
        batchSelection = batchSelection.retain(loadedEpisodeIds.toSet())
    }
    BackHandler(enabled = batchSelection.selectionMode) { batchSelection = batchSelection.clear() }

    LaunchedEffect(true) { detailDownloadViewModel.refresh() }

    // W73（#4）：播放器是独立 Activity，从播放器返回时本页组合不会重建——在 RESUMED 时重取一次，
    // 配合「播放停止上报即失效元数据缓存」保证集列表的已看勾 / 进度在一次返回后就刷新。
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            viewModel.loadSeason(seasonId = seasonId)
        }
    }

    LaunchedEffect(Unit) {
        detailDownloadViewModel.events.collect { event ->
            val result =
                snackbarHostState.showSnackbar(
                    message = downloadEventMessage(context, event),
                    actionLabel = if (event.showsViewAction()) viewLabel else null,
                    duration = DownloadSnackbarDuration,
                )
            if (result == SnackbarResult.ActionPerformed) onOpenDownloads()
        }
    }

    val downloadTargets = DetailDownloadRules.downloadTargets(state.episodes)
    val seasonDownloadState =
        DetailDownloadRules.containerState(
            itemIds = downloadTargets.map { episode -> episode.id },
            downloaded = downloadSnapshot.downloadedIds,
            queued = downloadSnapshot.queuedIds,
        )

    SeasonScreenLayout(
        state = state,
        // W75 #5：滚动位置按季 id 记忆——进集 / 返回时保持原位置（不置顶）。
        scrollKey = "season:$seasonId",
        downloadState = seasonDownloadState,
        snackbarHostState = snackbarHostState,
        selectionMode = batchSelection.selectionMode,
        selectedCount = batchSelection.selectedCount,
        selectedIds = batchSelection.selectedIds,
        onSelectAll = { batchSelection = batchSelection.selectAll(loadedEpisodeIds) },
        onSelectNone = { batchSelection = batchSelection.selectNone(loadedEpisodeIds) },
        onExitSelection = { batchSelection = batchSelection.clear() },
        onToggleEpisode = { key -> batchSelection = batchSelection.toggle(key) },
        onLongPressEpisode = { key -> batchSelection = batchSelection.longPress(key) },
        onBatchDownload = {
            val selectedEpisodes =
                state.episodes.filter { episode ->
                    episode.id.toString() in batchSelection.selectedIds
                }
            val selection =
                DetailDownloadRules.selectBatch(
                    itemIds = selectedEpisodes.map { episode -> episode.id },
                    downloaded = downloadSnapshot.downloadedIds,
                    queued = downloadSnapshot.queuedIds,
                    limit = null,
                )
            val targets = selectedEpisodes.filter { episode ->
                episode.id in selection.selected.toSet()
            }
            if (targets.isEmpty()) {
                detailDownloadViewModel.reportSkipped(selection)
            } else {
                detailDownloadViewModel.enqueueBatch(targets)
            }
            batchSelection = batchSelection.clear()
        },
        onDownloadClick = {
            val selection =
                DetailDownloadRules.selectBatch(
                    itemIds = downloadTargets.map { episode -> episode.id },
                    downloaded = downloadSnapshot.downloadedIds,
                    queued = downloadSnapshot.queuedIds,
                    limit = null,
                )
            if (selection.selected.isEmpty()) {
                detailDownloadViewModel.reportSkipped(selection)
            } else {
                batchDialogVisible = true
            }
        },
        onAction = { action ->
            when (action) {
                is SeasonAction.Play -> {
                    val intent = Intent(context, PlayerActivity::class.java)
                    intent.putExtra("itemId", seasonId.toString())
                    intent.putExtra("itemKind", BaseItemKind.SEASON.serialName)
                    context.startActivity(intent)
                }
                is SeasonAction.OnBackClick -> navigateBack()
                is SeasonAction.OnHomeClick -> navigateHome()
                is SeasonAction.NavigateToItem -> navigateToItem(action.item)
                is SeasonAction.NavigateToSeries -> navigateToSeries(action.seriesId)
                else -> Unit
            }
            viewModel.onAction(action)
        },
    )

    if (batchDialogVisible) {
        BatchDownloadDialog(
            title = stringResource(CoreR.string.detail_download_batch_title_season),
            episodes = downloadTargets,
            downloadedIds = downloadSnapshot.downloadedIds,
            queuedIds = downloadSnapshot.queuedIds,
            isLoading = false,
            loadFailed = false,
            onRetryLoad = {},
            onConfirm = { items ->
                detailDownloadViewModel.enqueueBatch(items)
                batchDialogVisible = false
            },
            onDismiss = { batchDialogVisible = false },
        )
    }
}

@Composable
private fun SeasonScreenLayout(
    state: SeasonState,
    downloadState: DetailDownloadState,
    snackbarHostState: SnackbarHostState,
    selectionMode: Boolean = false,
    selectedCount: Int = 0,
    onSelectAll: () -> Unit = {},
    onSelectNone: () -> Unit = {},
    onExitSelection: () -> Unit = {},
    onBatchDownload: () -> Unit = {},
    selectedIds: Set<String> = emptySet(),
    onToggleEpisode: (String) -> Unit = {},
    onLongPressEpisode: (String) -> Unit = {},
    onDownloadClick: () -> Unit,
    onAction: (SeasonAction) -> Unit,
    /** W75 #5：滚动位置的记忆键（真实入口传 `season:<id>`，预览用默认值）。 */
    scrollKey: String = "season:preview",
) {
    val safePadding = rememberSafePadding()

    val paddingStart = safePadding.start + MaterialTheme.spacings.default
    val paddingEnd = safePadding.end + MaterialTheme.spacings.default
    val paddingBottom = safePadding.bottom + MaterialTheme.spacings.default

    val lazyListState = rememberLazyScrollMemoryState(scrollKey)

    ProvideLumen {
        Box(modifier = Modifier.fillMaxSize()) {
            state.season?.let { season ->
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    state = lazyListState,
                    contentPadding = PaddingValues(bottom = paddingBottom),
                    verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.default),
                ) {
                    if (selectionMode) {
                        item(key = "selection-header") {
                            Row(
                                modifier =
                                    Modifier.fillMaxWidth()
                                        .padding(
                                            start = paddingStart,
                                            end = paddingEnd,
                                            top = safePadding.top + 64.dp,
                                        ),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text =
                                        stringResource(
                                            CoreR.string.download_selected_count,
                                            selectedCount,
                                        ),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = Color.White,
                                    modifier = Modifier.weight(1f),
                                )
                                MediaBatchTopBarActions(
                                    selectedCount = selectedCount,
                                    visibleCount = state.episodes.size,
                                    onSelectAll = onSelectAll,
                                    onSelectNone = onSelectNone,
                                    onExit = onExitSelection,
                                )
                            }
                        }
                    }
                    item {
                        DetailHero(
                            item = season,
                            lazyListState = lazyListState,
                            eyebrow = season.seriesName,
                            title = season.name,
                            meta =
                                if (state.episodes.isNotEmpty()) {
                                    stringResource(CoreR.string.episodes_label) +
                                        " · " +
                                        state.episodes.size
                                } else {
                                    null
                                },
                        ) { heroLayout ->
                            ItemButtonsBar(
                                item = season,
                                onPlayClick = { startFromBeginning ->
                                    onAction(
                                        SeasonAction.Play(startFromBeginning = startFromBeginning)
                                    )
                                },
                                onMarkAsPlayedClick = {
                                    when (season.played) {
                                        true -> onAction(SeasonAction.UnmarkAsPlayed)
                                        false -> onAction(SeasonAction.MarkAsPlayed)
                                    }
                                },
                                onMarkAsFavoriteClick = {
                                    when (season.favorite) {
                                        true -> onAction(SeasonAction.UnmarkAsFavorite)
                                        false -> onAction(SeasonAction.MarkAsFavorite)
                                    }
                                },
                                onTrailerClick = {},
                                onDownloadClick = { onDownloadClick() },
                                onDownloadCancelClick = {},
                                onDownloadDeleteClick = {},
                                modifier = Modifier.fillMaxWidth(),
                                canPlay = state.episodes.isNotEmpty(),
                                downloadState = downloadState,
                                storageSelectionEnabled = false,
                                heroLayout = heroLayout,
                            )
                        }
                    }
                    items(items = state.episodes, key = { episode -> episode.id }) { episode ->
                        val episodeKey = episode.id.toString()
                        EpisodeCard(
                            episode = episode,
                            selectionMode = selectionMode,
                            selected = episodeKey in selectedIds,
                            onClick = {
                                if (selectionMode) onToggleEpisode(episodeKey)
                                else onAction(SeasonAction.NavigateToItem(episode))
                            },
                            onLongClick = { onLongPressEpisode(episodeKey) },
                            modifier = Modifier.padding(start = paddingStart, end = paddingEnd),
                        )
                    }
                }
            }
                ?: run {
                    DetailSkeleton(
                        gutterStart = paddingStart,
                        gutterEnd = paddingEnd,
                        modifier = Modifier.fillMaxSize().padding(top = 72.dp),
                    )
                }

            ItemTopBar(
                hasBackButton = true,
                hasHomeButton = true,
                onBackClick = { onAction(SeasonAction.OnBackClick) },
                onHomeClick = { onAction(SeasonAction.OnHomeClick) },
            )
            CinefinSnackbarHost(
                hostState = snackbarHostState,
                modifier =
                    Modifier.align(Alignment.BottomCenter).padding(bottom = CinefinSpacing.Space6),
            )
            if (selectionMode) {
                CinefinBatchBar(
                    selectedCount = selectedCount,
                    modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding(),
                ) {
                    CinefinIconButton(onClick = onBatchDownload) { tint ->
                        Icon(
                            painter = painterResource(CoreR.drawable.ic_download),
                            contentDescription = stringResource(FilmR.string.batch_action_download),
                            tint = tint,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }
    }
}

@PreviewScreenSizes
@Composable
private fun SeasonScreenLayoutPreview() {
    CinefinTheme {
        SeasonScreenLayout(
            state = SeasonState(season = dummySeason),
            downloadState = DetailDownloadState.NOT_DOWNLOADED,
            snackbarHostState = remember { SnackbarHostState() },
            onDownloadClick = {},
            onAction = {},
        )
    }
}
