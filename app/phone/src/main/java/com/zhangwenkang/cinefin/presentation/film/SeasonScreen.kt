package com.zhangwenkang.cinefin.presentation.film

import android.content.Intent
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zhangwenkang.cinefin.PlayerActivity
import com.zhangwenkang.cinefin.core.R as CoreR
import com.zhangwenkang.cinefin.core.presentation.components.CinefinSnackbarHost
import com.zhangwenkang.cinefin.core.presentation.dummy.dummySeason
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinSpacing
import com.zhangwenkang.cinefin.core.presentation.theme.ProvideLumen
import com.zhangwenkang.cinefin.film.presentation.detail.DetailDownloadRules
import com.zhangwenkang.cinefin.film.presentation.detail.DetailDownloadState
import com.zhangwenkang.cinefin.film.presentation.detail.DetailDownloadViewModel
import com.zhangwenkang.cinefin.film.presentation.season.SeasonAction
import com.zhangwenkang.cinefin.film.presentation.season.SeasonState
import com.zhangwenkang.cinefin.film.presentation.season.SeasonViewModel
import com.zhangwenkang.cinefin.models.FindroidItem
import com.zhangwenkang.cinefin.presentation.components.DetailSkeleton
import com.zhangwenkang.cinefin.presentation.film.components.BatchDownloadDialog
import com.zhangwenkang.cinefin.presentation.film.components.Direction
import com.zhangwenkang.cinefin.presentation.film.components.EpisodeCard
import com.zhangwenkang.cinefin.presentation.film.components.ItemButtonsBar
import com.zhangwenkang.cinefin.presentation.film.components.ItemHeader
import com.zhangwenkang.cinefin.presentation.film.components.ItemPoster
import com.zhangwenkang.cinefin.presentation.film.components.ItemTopBar
import com.zhangwenkang.cinefin.presentation.film.components.LumenTextShadow
import com.zhangwenkang.cinefin.presentation.film.components.downloadEventMessage
import com.zhangwenkang.cinefin.presentation.film.components.lumenTextShadow
import com.zhangwenkang.cinefin.presentation.theme.CinefinTheme
import com.zhangwenkang.cinefin.presentation.theme.spacings
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
    viewModel: SeasonViewModel = hiltViewModel(),
    detailDownloadViewModel: DetailDownloadViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val downloadSnapshot by detailDownloadViewModel.state.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    var batchDialogVisible by remember { mutableStateOf(false) }

    LaunchedEffect(true) {
        viewModel.loadSeason(seasonId = seasonId)
        detailDownloadViewModel.refresh()
    }

    LaunchedEffect(Unit) {
        detailDownloadViewModel.events.collect { event ->
            snackbarHostState.showSnackbar(downloadEventMessage(context, event))
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
        downloadState = seasonDownloadState,
        snackbarHostState = snackbarHostState,
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
    onDownloadClick: () -> Unit,
    onAction: (SeasonAction) -> Unit,
) {
    val safePadding = rememberSafePadding()

    val paddingStart = safePadding.start + MaterialTheme.spacings.default
    val paddingEnd = safePadding.end + MaterialTheme.spacings.default
    val paddingBottom = safePadding.bottom + MaterialTheme.spacings.default

    val lazyListState = rememberLazyListState()

    ProvideLumen {
        Box(modifier = Modifier.fillMaxSize()) {
            state.season?.let { season ->
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    state = lazyListState,
                    contentPadding = PaddingValues(bottom = paddingBottom),
                    verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.default),
                ) {
                    item {
                        ItemHeader(
                            item = season,
                            lazyListState = lazyListState,
                            content = {
                                Row(
                                    modifier =
                                        Modifier.align(Alignment.BottomStart)
                                            .padding(start = paddingStart, end = paddingEnd),
                                    verticalAlignment = Alignment.Bottom,
                                ) {
                                    ItemPoster(
                                        item = season,
                                        direction = Direction.VERTICAL,
                                        modifier =
                                            Modifier.width(120.dp).clip(MaterialTheme.shapes.small),
                                    )
                                    Spacer(Modifier.width(MaterialTheme.spacings.medium))
                                    Column(modifier = Modifier) {
                                        Text(
                                            text = season.seriesName,
                                            overflow = TextOverflow.Ellipsis,
                                            maxLines = 1,
                                            style =
                                                MaterialTheme.typography.bodyLarge.lumenTextShadow(
                                                    LumenTextShadow.Meta
                                                ),
                                        )
                                        Text(
                                            text = season.name,
                                            overflow = TextOverflow.Ellipsis,
                                            maxLines = 3,
                                            style =
                                                MaterialTheme.typography.headlineMedium
                                                    .lumenTextShadow(LumenTextShadow.Title),
                                        )
                                    }
                                }
                            },
                        )
                        Spacer(Modifier.height(MaterialTheme.spacings.default.div(2)))
                        ItemButtonsBar(
                            item = season,
                            onPlayClick = { startFromBeginning ->
                                onAction(SeasonAction.Play(startFromBeginning = startFromBeginning))
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
                            modifier =
                                Modifier.padding(start = paddingStart, end = paddingEnd)
                                    .fillMaxWidth(),
                            canPlay = state.episodes.isNotEmpty(),
                            downloadState = downloadState,
                            storageSelectionEnabled = false,
                        )
                    }
                    items(items = state.episodes, key = { episode -> episode.id }) { episode ->
                        EpisodeCard(
                            episode = episode,
                            onClick = { onAction(SeasonAction.NavigateToItem(episode)) },
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
            ) {
                Spacer(modifier = Modifier.width(4.dp))
                state.season?.let { season ->
                    Button(
                        onClick = { onAction(SeasonAction.NavigateToSeries(season.seriesId)) },
                        modifier = Modifier.alpha(0.7f),
                        colors =
                            ButtonDefaults.buttonColors(
                                containerColor = Color.Black,
                                contentColor = Color.White,
                            ),
                    ) {
                        Text(
                            text = season.seriesName,
                            overflow = TextOverflow.Ellipsis,
                            maxLines = 1,
                        )
                    }
                }
            }
            CinefinSnackbarHost(
                hostState = snackbarHostState,
                modifier =
                    Modifier.align(Alignment.BottomCenter).padding(bottom = CinefinSpacing.Space6),
            )
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
