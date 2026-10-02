package com.zhangwenkang.cinefin.presentation.film

import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zhangwenkang.cinefin.core.R as CoreR
import com.zhangwenkang.cinefin.core.presentation.components.CinefinCard
import com.zhangwenkang.cinefin.core.presentation.components.CinefinEmptyState
import com.zhangwenkang.cinefin.core.presentation.components.CinefinFilterChip
import com.zhangwenkang.cinefin.core.presentation.components.CinefinIconButton
import com.zhangwenkang.cinefin.core.presentation.components.CinefinPageTopBar
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinSpacing
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinType
import com.zhangwenkang.cinefin.core.presentation.theme.LocalCinefinColors
import com.zhangwenkang.cinefin.core.presentation.theme.LocalMediaColors
import com.zhangwenkang.cinefin.film.presentation.downloads.CompletedDownload
import com.zhangwenkang.cinefin.film.presentation.downloads.DownloadAction
import com.zhangwenkang.cinefin.film.presentation.downloads.DownloadManagerState
import com.zhangwenkang.cinefin.film.presentation.downloads.DownloadsViewModel
import com.zhangwenkang.cinefin.film.presentation.downloads.completedKey
import com.zhangwenkang.cinefin.film.presentation.downloads.taskKey
import com.zhangwenkang.cinefin.models.FindroidItem
import com.zhangwenkang.cinefin.presentation.film.components.DeleteDownloadDialog
import com.zhangwenkang.cinefin.presentation.theme.CinefinTheme
import com.zhangwenkang.cinefin.presentation.utils.rememberPageGutter
import com.zhangwenkang.cinefin.presentation.utils.rememberSafePadding
import com.zhangwenkang.cinefin.utils.DownloadFailureReason
import com.zhangwenkang.cinefin.utils.DownloadStorageUsage
import com.zhangwenkang.cinefin.utils.DownloadTask
import com.zhangwenkang.cinefin.utils.DownloadTaskRules
import com.zhangwenkang.cinefin.utils.DownloadTaskStatus

@Composable
fun DownloadsScreen(
    /** 抽屉入口；null = 当前形态没有抽屉（手机 Compact，W6-R6N）。 */
    onOpenDrawer: (() -> Unit)?,
    onItemClick: (item: FindroidItem) -> Unit,
    viewModel: DownloadsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.start() }
    DisposableEffect(Unit) { onDispose { viewModel.stop() } }

    DownloadsScreenLayout(
        onOpenDrawer = onOpenDrawer,
        state = state,
        onAction = { action ->
            when (action) {
                is DownloadAction.Open -> onItemClick(action.item)
                else -> viewModel.onAction(action)
            }
        },
    )
}

private enum class DownloadTab {
    ACTIVE,
    COMPLETED,
    FAILED,
}

private sealed interface PendingDelete {
    data class Task(val task: DownloadTask) : PendingDelete

    data class Completed(val download: CompletedDownload) : PendingDelete

    data object Selection : PendingDelete
}

@Composable
private fun DownloadsScreenLayout(
    onOpenDrawer: (() -> Unit)?,
    state: DownloadManagerState,
    onAction: (DownloadAction) -> Unit,
) {
    val colors = LocalCinefinColors.current
    val context = LocalContext.current
    val safePadding = rememberSafePadding(handleStartInsets = false)
    val pageGutter = rememberPageGutter()
    val horizontalPadding = safePadding.start + pageGutter
    var tab by rememberSaveable { mutableStateOf(DownloadTab.ACTIVE) }
    var pendingDelete by remember { mutableStateOf<PendingDelete?>(null) }

    Column(modifier = Modifier.fillMaxSize().background(colors.surface)) {
        CinefinPageTopBar(
            title = stringResource(CoreR.string.title_download),
            subtitle =
                stringResource(
                    CoreR.string.download_section_summary,
                    state.activeCount,
                    state.completedCount,
                    state.failedCount,
                ),
            onOpenDrawer = onOpenDrawer,
            modifier = Modifier.padding(start = safePadding.start),
            actions = {
                when {
                    state.selectionMode ->
                        CinefinIconButton(onClick = { onAction(DownloadAction.ClearSelection) }) {
                            tint ->
                            Icon(
                                painter = painterResource(CoreR.drawable.ic_close),
                                contentDescription = stringResource(CoreR.string.cancel),
                                tint = tint,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    state.activeCount + state.completedCount + state.failedCount > 0 ->
                        CinefinIconButton(
                            onClick = { onAction(DownloadAction.ToggleSelectionMode) }
                        ) { tint ->
                            Icon(
                                painter = painterResource(CoreR.drawable.ic_check),
                                contentDescription =
                                    stringResource(CoreR.string.download_action_select),
                                tint = tint,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                }
            },
        )

        Spacer(Modifier.height(CinefinSpacing.Space3))
        StorageSummary(
            storage = state.storage,
            modifier = Modifier.padding(horizontal = horizontalPadding),
        )
        Spacer(Modifier.height(CinefinSpacing.Space3))

        Row(
            modifier =
                Modifier.fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = horizontalPadding),
            horizontalArrangement = Arrangement.spacedBy(CinefinSpacing.Space2),
        ) {
            CinefinFilterChip(
                text = stringResource(CoreR.string.download_tasks_active) + " ${state.activeCount}",
                selected = tab == DownloadTab.ACTIVE,
                onClick = { tab = DownloadTab.ACTIVE },
                compact = true,
            )
            CinefinFilterChip(
                text =
                    stringResource(CoreR.string.download_tasks_completed) +
                        " ${state.completedCount}",
                selected = tab == DownloadTab.COMPLETED,
                onClick = { tab = DownloadTab.COMPLETED },
                compact = true,
            )
            CinefinFilterChip(
                text = stringResource(CoreR.string.download_tasks_failed) + " ${state.failedCount}",
                selected = tab == DownloadTab.FAILED,
                onClick = { tab = DownloadTab.FAILED },
                compact = true,
            )
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when (tab) {
                DownloadTab.ACTIVE ->
                    TaskList(
                        tasks = state.activeTasks,
                        emptyTitle = stringResource(CoreR.string.download_empty_active),
                        selectionMode = state.selectionMode,
                        selection = state.selection,
                        pageGutter = horizontalPadding,
                        onAction = onAction,
                        onRequestDelete = { pendingDelete = PendingDelete.Task(it) },
                    )
                DownloadTab.FAILED ->
                    TaskList(
                        tasks = state.failedTasks,
                        emptyTitle = stringResource(CoreR.string.download_empty_failed),
                        selectionMode = state.selectionMode,
                        selection = state.selection,
                        pageGutter = horizontalPadding,
                        onAction = onAction,
                        onRequestDelete = { pendingDelete = PendingDelete.Task(it) },
                    )
                DownloadTab.COMPLETED ->
                    CompletedList(
                        downloads = state.completed,
                        selectionMode = state.selectionMode,
                        selection = state.selection,
                        pageGutter = horizontalPadding,
                        onAction = onAction,
                        onRequestDelete = { pendingDelete = PendingDelete.Completed(it) },
                    )
            }
        }

        if (state.selectionMode) {
            SelectionBar(
                state = state,
                pageGutter = horizontalPadding,
                onAction = onAction,
                onRequestDelete = { pendingDelete = PendingDelete.Selection },
            )
        }
    }

    when (val pending = pendingDelete) {
        is PendingDelete.Task ->
            DeleteDownloadDialog(
                onDelete = {
                    onAction(DownloadAction.DeleteTask(pending.task))
                    pendingDelete = null
                },
                onDismiss = { pendingDelete = null },
            )
        is PendingDelete.Completed ->
            DeleteDownloadDialog(
                onDelete = {
                    onAction(DownloadAction.DeleteCompleted(pending.download))
                    pendingDelete = null
                },
                onDismiss = { pendingDelete = null },
            )
        PendingDelete.Selection ->
            AlertDialog(
                title = { Text(stringResource(CoreR.string.download_delete_tasks_title)) },
                text = {
                    Text(
                        stringResource(
                            CoreR.string.download_delete_tasks_message,
                            state.selection.size,
                        )
                    )
                },
                onDismissRequest = { pendingDelete = null },
                confirmButton = {
                    TextButton(
                        onClick = {
                            onAction(DownloadAction.DeleteSelected)
                            pendingDelete = null
                        }
                    ) {
                        Text(stringResource(CoreR.string.download_action_delete))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { pendingDelete = null }) {
                        Text(stringResource(CoreR.string.cancel))
                    }
                },
            )
        null -> Unit
    }
}

@Composable
private fun StorageSummary(storage: DownloadStorageUsage, modifier: Modifier = Modifier) {
    val colors = LocalCinefinColors.current
    val context = LocalContext.current
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(
            painter = painterResource(CoreR.drawable.ic_database),
            contentDescription = null,
            tint = colors.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.size(CinefinSpacing.Space2))
        Text(
            text =
                stringResource(
                    CoreR.string.download_storage_usage,
                    Formatter.formatFileSize(context, storage.usedBytes),
                    Formatter.formatFileSize(context, storage.availableBytes),
                ),
            style = CinefinType.BodyMedium,
            color = colors.onSurfaceVariant,
        )
    }
}

@Composable
private fun TaskList(
    tasks: List<DownloadTask>,
    emptyTitle: String,
    selectionMode: Boolean,
    selection: Set<String>,
    pageGutter: androidx.compose.ui.unit.Dp,
    onAction: (DownloadAction) -> Unit,
    onRequestDelete: (DownloadTask) -> Unit,
) {
    if (tasks.isEmpty()) {
        CinefinEmptyState(
            title = emptyTitle,
            modifier = Modifier.fillMaxSize().padding(horizontal = pageGutter),
        )
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = pageGutter),
        contentPadding = PaddingValues(top = CinefinSpacing.Space3, bottom = CinefinSpacing.Space8),
        verticalArrangement = Arrangement.spacedBy(CinefinSpacing.Space3),
    ) {
        items(tasks, key = { taskKey(it) }) { task ->
            DownloadTaskCard(
                task = task,
                selectionMode = selectionMode,
                selected = taskKey(task) in selection,
                onAction = onAction,
                onRequestDelete = onRequestDelete,
            )
        }
    }
}

@Composable
private fun DownloadTaskCard(
    task: DownloadTask,
    selectionMode: Boolean,
    selected: Boolean,
    onAction: (DownloadAction) -> Unit,
    onRequestDelete: (DownloadTask) -> Unit,
) {
    val colors = LocalCinefinColors.current
    val media = LocalMediaColors.current
    val context = LocalContext.current

    CinefinCard(
        onClick =
            if (selectionMode) ({ onAction(DownloadAction.ToggleSelection(taskKey(task))) })
            else null,
        selected = selected,
        contentPadding = PaddingValues(CinefinSpacing.Space4),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (selectionMode) {
                Checkbox(
                    checked = selected,
                    onCheckedChange = { onAction(DownloadAction.ToggleSelection(taskKey(task))) },
                )
                Spacer(Modifier.size(CinefinSpacing.Space2))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = task.name,
                    style = CinefinType.BodyLarge,
                    color = colors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(CinefinSpacing.Space1))
                TaskStatusText(task = task)
                if (task.status == DownloadTaskStatus.RUNNING) {
                    Spacer(Modifier.height(CinefinSpacing.Space2))
                    LinearProgressIndicator(
                        progress = { task.progress },
                        modifier = Modifier.fillMaxWidth().height(4.dp),
                        color = media.base,
                        trackColor = colors.progressTrack,
                    )
                    Spacer(Modifier.height(CinefinSpacing.Space1))
                    Text(
                        text =
                            "${(task.progress * 100).toInt()}% · " +
                                Formatter.formatFileSize(context, task.downloadedBytes),
                        style = CinefinType.BodySmall,
                        color = colors.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.size(CinefinSpacing.Space2))
            Row(horizontalArrangement = Arrangement.spacedBy(CinefinSpacing.Space1)) {
                when {
                    DownloadTaskRules.canPause(task.status) ->
                        CinefinIconButton(onClick = { onAction(DownloadAction.Pause(task)) }) { tint
                            ->
                            Icon(
                                painter = painterResource(CoreR.drawable.ic_pause),
                                contentDescription =
                                    stringResource(CoreR.string.download_action_pause),
                                tint = tint,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    DownloadTaskRules.canResume(task.status) ->
                        CinefinIconButton(onClick = { onAction(DownloadAction.Resume(task)) }) {
                            tint ->
                            Icon(
                                painter = painterResource(CoreR.drawable.ic_play),
                                contentDescription =
                                    stringResource(CoreR.string.download_action_resume),
                                tint = tint,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    DownloadTaskRules.canRetry(task.status) ->
                        CinefinIconButton(onClick = { onAction(DownloadAction.Retry(task)) }) { tint
                            ->
                            Icon(
                                painter = painterResource(CoreR.drawable.ic_rotate_ccw),
                                contentDescription =
                                    stringResource(CoreR.string.download_action_retry),
                                tint = tint,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                }
                CinefinIconButton(onClick = { onRequestDelete(task) }) { tint ->
                    Icon(
                        painter = painterResource(CoreR.drawable.ic_trash),
                        contentDescription = stringResource(CoreR.string.download_action_delete),
                        tint = tint,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun TaskStatusText(task: DownloadTask) {
    val colors = LocalCinefinColors.current
    val text =
        when (task.status) {
            DownloadTaskStatus.PENDING -> stringResource(CoreR.string.download_pending)
            DownloadTaskStatus.RUNNING -> stringResource(CoreR.string.download_downloading)
            DownloadTaskStatus.PAUSED ->
                if (task.failureReason == DownloadFailureReason.NETWORK_UNAVAILABLE) {
                    stringResource(CoreR.string.download_waiting_network)
                } else {
                    stringResource(CoreR.string.download_paused)
                }
            DownloadTaskStatus.FAILED -> failureLabel(task.failureReason)
            DownloadTaskStatus.COMPLETED -> stringResource(CoreR.string.download_tasks_completed)
        }
    val color =
        if (task.status == DownloadTaskStatus.FAILED) colors.error else colors.onSurfaceVariant
    Text(text = text, style = CinefinType.BodySmall, color = color)
}

@Composable
private fun failureLabel(reason: DownloadFailureReason?): String =
    when (reason) {
        DownloadFailureReason.STORAGE_INSUFFICIENT ->
            stringResource(CoreR.string.download_failure_storage)
        DownloadFailureReason.NETWORK_UNAVAILABLE ->
            stringResource(CoreR.string.download_failure_network)
        DownloadFailureReason.SERVER_ERROR -> stringResource(CoreR.string.download_failure_server)
        DownloadFailureReason.CANNOT_RESUME ->
            stringResource(CoreR.string.download_failure_cannot_resume)
        DownloadFailureReason.FILE_ERROR -> stringResource(CoreR.string.download_failure_file)
        DownloadFailureReason.UNKNOWN,
        null -> stringResource(CoreR.string.download_failure_unknown)
    }

@Composable
private fun CompletedList(
    downloads: List<CompletedDownload>,
    selectionMode: Boolean,
    selection: Set<String>,
    pageGutter: androidx.compose.ui.unit.Dp,
    onAction: (DownloadAction) -> Unit,
    onRequestDelete: (CompletedDownload) -> Unit,
) {
    if (downloads.isEmpty()) {
        CinefinEmptyState(
            title = stringResource(CoreR.string.no_downloads),
            modifier = Modifier.fillMaxSize().padding(horizontal = pageGutter),
        )
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = pageGutter),
        contentPadding = PaddingValues(top = CinefinSpacing.Space3, bottom = CinefinSpacing.Space8),
        verticalArrangement = Arrangement.spacedBy(CinefinSpacing.Space3),
    ) {
        items(downloads, key = { completedKey(it.item) }) { download ->
            CompletedDownloadCard(
                download = download,
                selectionMode = selectionMode,
                selected = completedKey(download.item) in selection,
                onAction = onAction,
                onRequestDelete = onRequestDelete,
            )
        }
    }
}

@Composable
private fun CompletedDownloadCard(
    download: CompletedDownload,
    selectionMode: Boolean,
    selected: Boolean,
    onAction: (DownloadAction) -> Unit,
    onRequestDelete: (CompletedDownload) -> Unit,
) {
    val colors = LocalCinefinColors.current
    val context = LocalContext.current
    CinefinCard(
        onClick = {
            if (selectionMode) {
                if (download.canDelete) {
                    onAction(DownloadAction.ToggleSelection(completedKey(download.item)))
                }
            } else {
                onAction(DownloadAction.Open(download.item))
            }
        },
        selected = selected,
        contentPadding = PaddingValues(CinefinSpacing.Space4),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (selectionMode) {
                Checkbox(
                    checked = selected,
                    enabled = download.canDelete,
                    onCheckedChange = {
                        onAction(DownloadAction.ToggleSelection(completedKey(download.item)))
                    },
                )
                Spacer(Modifier.size(CinefinSpacing.Space2))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = download.item.name,
                    style = CinefinType.BodyLarge,
                    color = colors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (download.canDelete) {
                    Spacer(Modifier.height(CinefinSpacing.Space1))
                    Text(
                        text = Formatter.formatFileSize(context, download.sizeBytes),
                        style = CinefinType.BodySmall,
                        color = colors.onSurfaceVariant,
                    )
                }
            }
            if (!selectionMode && download.canDelete) {
                CinefinIconButton(onClick = { onRequestDelete(download) }) { tint ->
                    Icon(
                        painter = painterResource(CoreR.drawable.ic_trash),
                        contentDescription = stringResource(CoreR.string.download_action_delete),
                        tint = tint,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun SelectionBar(
    state: DownloadManagerState,
    pageGutter: androidx.compose.ui.unit.Dp,
    onAction: (DownloadAction) -> Unit,
    onRequestDelete: () -> Unit,
) {
    val colors = LocalCinefinColors.current
    val selectedTasks =
        (state.activeTasks + state.failedTasks).filter { taskKey(it) in state.selection }
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .background(colors.surfaceContainer)
                .navigationBarsPadding()
                .padding(horizontal = pageGutter, vertical = CinefinSpacing.Space2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = stringResource(CoreR.string.download_selected_count, state.selection.size),
            style = CinefinType.BodyMedium,
            color = colors.onSurface,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(CinefinSpacing.Space1)) {
            CinefinIconButton(
                enabled = selectedTasks.any { DownloadTaskRules.canPause(it.status) },
                onClick = { onAction(DownloadAction.PauseSelected) },
            ) { tint ->
                Icon(
                    painter = painterResource(CoreR.drawable.ic_pause),
                    contentDescription = stringResource(CoreR.string.download_action_pause),
                    tint = tint,
                    modifier = Modifier.size(20.dp),
                )
            }
            CinefinIconButton(
                enabled = selectedTasks.any { DownloadTaskRules.canResume(it.status) },
                onClick = { onAction(DownloadAction.ResumeSelected) },
            ) { tint ->
                Icon(
                    painter = painterResource(CoreR.drawable.ic_play),
                    contentDescription = stringResource(CoreR.string.download_action_resume),
                    tint = tint,
                    modifier = Modifier.size(20.dp),
                )
            }
            CinefinIconButton(
                enabled = selectedTasks.any { DownloadTaskRules.canRetry(it.status) },
                onClick = { onAction(DownloadAction.RetrySelected) },
            ) { tint ->
                Icon(
                    painter = painterResource(CoreR.drawable.ic_rotate_ccw),
                    contentDescription = stringResource(CoreR.string.download_action_retry),
                    tint = tint,
                    modifier = Modifier.size(20.dp),
                )
            }
            CinefinIconButton(
                enabled = state.hasSelection,
                onClick = onRequestDelete,
            ) { tint ->
                Icon(
                    painter = painterResource(CoreR.drawable.ic_trash),
                    contentDescription = stringResource(CoreR.string.download_action_delete),
                    tint = tint,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@PreviewScreenSizes
@Composable
private fun DownloadsScreenLayoutPreview() {
    CinefinTheme {
        DownloadsScreenLayout(
            onOpenDrawer = {},
            state = DownloadManagerState(isLoading = false),
            onAction = {},
        )
    }
}
