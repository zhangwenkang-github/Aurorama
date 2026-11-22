package com.zhangwenkang.cinefin.presentation.film

import android.content.Intent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zhangwenkang.cinefin.PlayerActivity
import com.zhangwenkang.cinefin.core.R as CoreR
import com.zhangwenkang.cinefin.core.presentation.components.CinefinSnackbarHost
import com.zhangwenkang.cinefin.core.presentation.components.DownloadSnackbarDuration
import com.zhangwenkang.cinefin.core.presentation.downloader.DownloaderAction
import com.zhangwenkang.cinefin.core.presentation.downloader.DownloaderEvent
import com.zhangwenkang.cinefin.core.presentation.downloader.DownloaderState
import com.zhangwenkang.cinefin.core.presentation.downloader.DownloaderViewModel
import com.zhangwenkang.cinefin.core.presentation.dummy.dummyEpisode
import com.zhangwenkang.cinefin.core.presentation.dummy.dummyVideoMetadata
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinTheme
import com.zhangwenkang.cinefin.core.presentation.theme.spacings
import com.zhangwenkang.cinefin.film.presentation.detail.DetailDeleteRules
import com.zhangwenkang.cinefin.film.presentation.detail.DetailDownloadEvent
import com.zhangwenkang.cinefin.film.presentation.detail.DetailDownloadRules
import com.zhangwenkang.cinefin.film.presentation.detail.DetailDownloadState
import com.zhangwenkang.cinefin.film.presentation.detail.DetailDownloadViewModel
import com.zhangwenkang.cinefin.film.presentation.detail.DetailEditRules
import com.zhangwenkang.cinefin.film.presentation.detail.DetailWriteEvent
import com.zhangwenkang.cinefin.film.presentation.episode.EpisodeAction
import com.zhangwenkang.cinefin.film.presentation.episode.EpisodeState
import com.zhangwenkang.cinefin.film.presentation.episode.EpisodeViewModel
import com.zhangwenkang.cinefin.models.FindroidEpisode
import com.zhangwenkang.cinefin.presentation.components.DetailSkeleton
import com.zhangwenkang.cinefin.presentation.film.components.ActorsRow
import com.zhangwenkang.cinefin.presentation.film.components.DeleteServerFileButton
import com.zhangwenkang.cinefin.presentation.film.components.DeleteServerFileDialog
import com.zhangwenkang.cinefin.presentation.film.components.DetailBodyMode
import com.zhangwenkang.cinefin.presentation.film.components.DetailHero
import com.zhangwenkang.cinefin.presentation.film.components.DetailLoadError
import com.zhangwenkang.cinefin.presentation.film.components.EditMetadataDialog
import com.zhangwenkang.cinefin.presentation.film.components.EditServerMetadataButton
import com.zhangwenkang.cinefin.presentation.film.components.ExtraInfoText
import com.zhangwenkang.cinefin.presentation.film.components.ItemButtonsBar
import com.zhangwenkang.cinefin.presentation.film.components.ItemTopBar
import com.zhangwenkang.cinefin.presentation.film.components.OverviewText
import com.zhangwenkang.cinefin.presentation.film.components.VideoMetadataBar
import com.zhangwenkang.cinefin.presentation.film.components.communityRatingText
import com.zhangwenkang.cinefin.presentation.film.components.detailBodyMode
import com.zhangwenkang.cinefin.presentation.film.components.detailWriteEventMessage
import com.zhangwenkang.cinefin.presentation.film.components.downloadEventMessage
import com.zhangwenkang.cinefin.presentation.film.components.showsViewAction
import com.zhangwenkang.cinefin.presentation.utils.LocalOfflineMode
import com.zhangwenkang.cinefin.presentation.utils.rememberSafePadding
import com.zhangwenkang.cinefin.presentation.utils.rememberScrollMemoryState
import com.zhangwenkang.cinefin.repository.ItemMetadataSnapshot
import com.zhangwenkang.cinefin.utils.ObserveAsEvents
import com.zhangwenkang.cinefin.utils.format
import java.util.UUID
import org.jellyfin.sdk.model.api.BaseItemKind

@Composable
fun EpisodeScreen(
    episodeId: UUID,
    navigateBack: () -> Unit,
    navigateHome: () -> Unit,
    navigateToPerson: (personId: UUID) -> Unit,
    navigateToSeason: (seasonId: UUID) -> Unit,
    /** W60b：下载反馈 Snackbar「查看」→ 下载页。 */
    onOpenDownloads: () -> Unit = {},
    viewModel: EpisodeViewModel = hiltViewModel(),
    downloaderViewModel: DownloaderViewModel = hiltViewModel(),
    detailDownloadViewModel: DetailDownloadViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val isOfflineMode = LocalOfflineMode.current

    val state by viewModel.state.collectAsStateWithLifecycle()
    val downloaderState by downloaderViewModel.state.collectAsStateWithLifecycle()
    val downloadSnapshot by detailDownloadViewModel.state.collectAsStateWithLifecycle()
    val canEditMetadata by viewModel.canEditMetadata.collectAsStateWithLifecycle()
    val metadataSnapshot by viewModel.metadataSnapshot.collectAsStateWithLifecycle()
    val metadataSnapshotLoading by viewModel.metadataSnapshotLoading.collectAsStateWithLifecycle()
    val metadataSaving by viewModel.metadataSaving.collectAsStateWithLifecycle()
    var editMetadataDialogOpen by remember { mutableStateOf(false) }
    var editMetadataRequested by remember { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }
    val viewLabel = stringResource(CoreR.string.snackbar_view)

    LaunchedEffect(true) {
        viewModel.loadEpisode(episodeId = episodeId)
        detailDownloadViewModel.refresh()
    }

    LaunchedEffect(state.episode) {
        state.episode?.let { episode -> downloaderViewModel.update(episode) }
    }

    LaunchedEffect(Unit) {
        detailDownloadViewModel.events.collect { event ->
            // 入队成功后刷新条目，让进度卡（DownloaderCard）开始轮询新任务。
            if (event == DetailDownloadEvent.AddedToQueue) {
                viewModel.loadEpisode(episodeId = episodeId)
            }
            val result =
                snackbarHostState.showSnackbar(
                    message = downloadEventMessage(context, event),
                    actionLabel = if (event.showsViewAction()) viewLabel else null,
                    duration = DownloadSnackbarDuration,
                )
            if (result == SnackbarResult.ActionPerformed) onOpenDownloads()
        }
    }

    // W8-T1：收藏 / 已看写失败（权限拒绝）或已排队待同步都必须让用户看到。
    LaunchedEffect(Unit) {
        viewModel.writeEvents.collect { event ->
            detailWriteEventMessage(context, event)?.let { message ->
                snackbarHostState.showSnackbar(message = message)
            }
        }
    }

    // W8-T5：删除服务器文件成功后关闭详情页；权限拒绝 / 失败走同一条 Snackbar 通道。
    LaunchedEffect(Unit) {
        viewModel.deleteEvents.collect { event ->
            if (event == DetailWriteEvent.Confirmed) {
                navigateBack()
            } else {
                detailWriteEventMessage(context, event)?.let { message ->
                    snackbarHostState.showSnackbar(message = message)
                }
            }
        }
    }

    // W8-T6：编辑元数据成功关闭对话框（详情由 VM 重载）；失败 / 权限拒绝走同一条 Snackbar 通道。
    LaunchedEffect(Unit) {
        viewModel.metadataEvents.collect { event ->
            if (event == DetailWriteEvent.Confirmed) editMetadataDialogOpen = false
            detailWriteEventMessage(context, event)?.let { message ->
                snackbarHostState.showSnackbar(message = message)
            }
        }
    }

    // W8-T6：快照读取完成后才展示对话框（取不到也展示，字段禁用）。
    LaunchedEffect(editMetadataRequested, metadataSnapshotLoading) {
        if (editMetadataRequested && !metadataSnapshotLoading) {
            editMetadataDialogOpen = true
            editMetadataRequested = false
        }
    }

    ObserveAsEvents(downloaderViewModel.events) { event ->
        when (event) {
            // W60b：单集下载的入队反馈由 DetailDownloadViewModel 的三态 Snackbar 承担（含「查看」动作）。
            is DownloaderEvent.Queued -> Unit
            is DownloaderEvent.Successful -> {
                viewModel.loadEpisode(episodeId = episodeId)
            }
            is DownloaderEvent.Deleted -> {
                if (isOfflineMode) {
                    navigateBack()
                } else {
                    viewModel.loadEpisode(episodeId = episodeId)
                }
            }
        }
    }

    EpisodeScreenLayout(
        state = state,
        scrollKey = "episode:$episodeId",
        downloaderState = downloaderState,
        downloadState =
            DetailDownloadRules.stateOf(
                itemId = episodeId,
                downloaded = downloadSnapshot.downloadedIds,
                queued = downloadSnapshot.queuedIds,
            ),
        snackbarHostState = snackbarHostState,
        onAction = { action ->
            when (action) {
                is EpisodeAction.Play -> {
                    val intent = Intent(context, PlayerActivity::class.java)
                    intent.putExtra("itemId", episodeId.toString())
                    intent.putExtra("itemKind", BaseItemKind.EPISODE.serialName)
                    intent.putExtra("startFromBeginning", action.startFromBeginning)
                    context.startActivity(intent)
                }
                is EpisodeAction.OnBackClick -> navigateBack()
                is EpisodeAction.OnHomeClick -> navigateHome()
                is EpisodeAction.NavigateToPerson -> navigateToPerson(action.personId)
                is EpisodeAction.NavigateToSeason -> navigateToSeason(action.seasonId)
                else -> Unit
            }
            viewModel.onAction(action)
        },
        onDownloaderAction = { action -> downloaderViewModel.onAction(action) },
        onDownloadClick = { episode, storageIndex ->
            detailDownloadViewModel.enqueueItem(item = episode, storageIndex = storageIndex)
        },
        onRetry = { viewModel.loadEpisode(episodeId = episodeId) },
        offlineMode = isOfflineMode,
        onDeleteConfirmed = { viewModel.deleteItem() },
        canEditMetadata = canEditMetadata,
        editMetadataSnapshot = metadataSnapshot,
        editMetadataDialogOpen = editMetadataDialogOpen,
        editMetadataSaving = metadataSaving,
        onEditMetadataClick = {
            editMetadataRequested = true
            viewModel.loadMetadataSnapshot()
        },
        onSaveMetadata = { name, overview, sortName ->
            viewModel.saveMetadata(name = name, overview = overview, sortName = sortName)
        },
        onDismissEditMetadata = { editMetadataDialogOpen = false },
    )
}

@Composable
private fun EpisodeScreenLayout(
    state: EpisodeState,
    scrollKey: String = "episode:preview",
    downloaderState: DownloaderState,
    downloadState: DetailDownloadState,
    snackbarHostState: SnackbarHostState,
    onAction: (EpisodeAction) -> Unit,
    onDownloaderAction: (DownloaderAction) -> Unit,
    onDownloadClick: (FindroidEpisode, Int) -> Unit,
    onRetry: () -> Unit = {},
    offlineMode: Boolean = false,
    onDeleteConfirmed: () -> Unit = {},
    canEditMetadata: Boolean = false,
    editMetadataSnapshot: ItemMetadataSnapshot? = null,
    editMetadataDialogOpen: Boolean = false,
    editMetadataSaving: Boolean = false,
    onEditMetadataClick: () -> Unit = {},
    onSaveMetadata: (name: String, overview: String?, sortName: String?) -> Unit = { _, _, _ -> },
    onDismissEditMetadata: () -> Unit = {},
) {
    val safePadding = rememberSafePadding()

    val paddingStart = safePadding.start + MaterialTheme.spacings.default
    val paddingEnd = safePadding.end + MaterialTheme.spacings.default
    val paddingBottom = safePadding.bottom + MaterialTheme.spacings.default

    val scrollState = rememberScrollMemoryState(scrollKey)
    val bodyMode =
        detailBodyMode(hasContent = state.episode != null, hasError = state.error != null)
    var deleteServerDialogOpen by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        state.episode
            ?.takeIf { bodyMode != DetailBodyMode.Error }
            ?.let { episode ->
                Column(modifier = Modifier.fillMaxWidth().verticalScroll(scrollState)) {
                    DetailHero(
                        item = episode,
                        scrollState = scrollState,
                        eyebrow = episodeHeroEyebrow(episode),
                        title = episode.name,
                        meta = episodeHeroMeta(episode),
                        // W76-Q1：点眉标「第 X 季 - 第 Y 集」回到所属季（W66 删掉的顶栏入口不在顶栏重建）。
                        onEyebrowClick = {
                            onAction(EpisodeAction.NavigateToSeason(episode.seasonId))
                        },
                    ) { heroLayout ->
                        ItemButtonsBar(
                            item = episode,
                            downloaderState = downloaderState,
                            onPlayClick = { startFromBeginning ->
                                onAction(
                                    EpisodeAction.Play(startFromBeginning = startFromBeginning)
                                )
                            },
                            onMarkAsPlayedClick = {
                                when (episode.played) {
                                    true -> onAction(EpisodeAction.UnmarkAsPlayed)
                                    false -> onAction(EpisodeAction.MarkAsPlayed)
                                }
                            },
                            onMarkAsFavoriteClick = {
                                when (episode.favorite) {
                                    true -> onAction(EpisodeAction.UnmarkAsFavorite)
                                    false -> onAction(EpisodeAction.MarkAsFavorite)
                                }
                            },
                            onTrailerClick = {},
                            onDownloadClick = { storageIndex ->
                                onDownloadClick(episode, storageIndex)
                            },
                            onDownloadCancelClick = {
                                onDownloaderAction(DownloaderAction.CancelDownload(episode))
                            },
                            onDownloadDeleteClick = {
                                onDownloaderAction(DownloaderAction.DeleteDownload(episode))
                            },
                            modifier = Modifier.fillMaxWidth(),
                            downloadState = downloadState,
                            downloadEnabled = episode.canDownload,
                            heroLayout = heroLayout,
                        )
                        if (
                            DetailEditRules.shouldShowEditAction(
                                canEditMetadata = canEditMetadata,
                                offlineMode = offlineMode,
                            )
                        ) {
                            EditServerMetadataButton(
                                onClick = onEditMetadataClick,
                                modifier = Modifier.padding(top = MaterialTheme.spacings.small),
                            )
                        }
                        if (
                            DetailDeleteRules.shouldShowDeleteAction(
                                canDelete = episode.canDelete,
                                offlineMode = offlineMode,
                            )
                        ) {
                            DeleteServerFileButton(
                                onClick = { deleteServerDialogOpen = true },
                                modifier = Modifier.padding(top = MaterialTheme.spacings.small),
                            )
                        }
                    }
                    Column(modifier = Modifier.padding(start = paddingStart, end = paddingEnd)) {
                        Spacer(Modifier.height(MaterialTheme.spacings.small))
                        state.videoMetadata?.let { videoMetadata ->
                            VideoMetadataBar(videoMetadata)
                            Spacer(Modifier.height(MaterialTheme.spacings.small))
                        }
                        if (state.displayExtraInfo && state.videoMetadata != null) {
                            ExtraInfoText(videoMetadata = state.videoMetadata!!)
                            Spacer(Modifier.height(MaterialTheme.spacings.medium))
                        }
                        OverviewText(text = episode.overview)
                        Spacer(Modifier.height(MaterialTheme.spacings.medium))
                    }
                    if (state.actors.isNotEmpty()) {
                        ActorsRow(
                            actors = state.actors,
                            onActorClick = { personId ->
                                onAction(EpisodeAction.NavigateToPerson(personId))
                            },
                            contentPadding = PaddingValues(start = paddingStart, end = paddingEnd),
                        )
                    }
                    Spacer(Modifier.height(paddingBottom))
                }
            }
            ?: run {
                if (bodyMode == DetailBodyMode.Error) {
                    state.error?.let { error ->
                        DetailLoadError(
                            exception = error,
                            onRetryClick = onRetry,
                            modifier =
                                Modifier.fillMaxSize()
                                    .padding(
                                        start = paddingStart,
                                        top = 88.dp,
                                        end = paddingEnd,
                                        bottom = paddingBottom,
                                    ),
                        )
                    }
                } else {
                    DetailSkeleton(
                        gutterStart = paddingStart,
                        gutterEnd = paddingEnd,
                        modifier = Modifier.fillMaxSize().padding(top = 72.dp),
                    )
                }
            }

        ItemTopBar(
            hasBackButton = true,
            hasHomeButton = true,
            onBackClick = { onAction(EpisodeAction.OnBackClick) },
            onHomeClick = { onAction(EpisodeAction.OnHomeClick) },
        )

        if (deleteServerDialogOpen) {
            state.episode?.let { episode ->
                DeleteServerFileDialog(
                    itemName = episode.name,
                    onDelete = {
                        deleteServerDialogOpen = false
                        onDeleteConfirmed()
                    },
                    onDismiss = { deleteServerDialogOpen = false },
                )
            }
        }

        if (editMetadataDialogOpen) {
            EditMetadataDialog(
                snapshot = editMetadataSnapshot,
                saving = editMetadataSaving,
                onSave = onSaveMetadata,
                onDismiss = onDismissEditMetadata,
            )
        }

        CinefinSnackbarHost(
            hostState = snackbarHostState,
            modifier =
                Modifier.align(Alignment.BottomCenter)
                    .padding(bottom = MaterialTheme.spacings.medium),
        )
    }
}

/** W66：剧集详情头图眉标（季名 / 剧名 + 集号）。W76-Q1：整块可点 → 回到所属季（发射 `EpisodeAction.NavigateToSeason`）。 */
@Composable
private fun episodeHeroEyebrow(episode: FindroidEpisode): String {
    val seasonName =
        episode.seasonName ?: stringResource(CoreR.string.season_number, episode.parentIndexNumber)
    return "$seasonName - " + stringResource(id = CoreR.string.episode_number, episode.indexNumber)
}

/** W66：剧集详情头图元信息（日期 · 时长 · 评分），由原头图下方的元数据行收敛而来。 */
@Composable
private fun episodeHeroMeta(episode: FindroidEpisode): String = buildList {
    episode.premiereDate?.let { add(it.format()) }
    add(stringResource(CoreR.string.runtime_minutes, episode.runtimeTicks.div(600000000)))
    communityRatingText(episode.communityRating)?.let { add(it) }
}
    .joinToString(" · ")

@PreviewScreenSizes
@Composable
private fun EpisodeScreenLayoutPreview() {
    CinefinTheme {
        EpisodeScreenLayout(
            state = EpisodeState(episode = dummyEpisode, videoMetadata = dummyVideoMetadata),
            downloaderState = DownloaderState(),
            downloadState = DetailDownloadState.NOT_DOWNLOADED,
            snackbarHostState = remember { SnackbarHostState() },
            onAction = {},
            onDownloaderAction = {},
            onDownloadClick = { _, _ -> },
        )
    }
}
