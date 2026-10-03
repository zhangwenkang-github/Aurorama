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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.zhangwenkang.cinefin.core.R as CoreR
import com.zhangwenkang.cinefin.core.presentation.components.CinefinCard
import com.zhangwenkang.cinefin.core.presentation.components.CinefinEmptyState
import com.zhangwenkang.cinefin.core.presentation.components.CinefinFilterChip
import com.zhangwenkang.cinefin.core.presentation.components.CinefinIconButton
import com.zhangwenkang.cinefin.core.presentation.components.CinefinPageTopBar
import com.zhangwenkang.cinefin.core.presentation.components.CinefinSwitch
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinShapes
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinSpacing
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinType
import com.zhangwenkang.cinefin.core.presentation.theme.LocalCinefinColors
import com.zhangwenkang.cinefin.core.presentation.theme.LocalMediaColors
import com.zhangwenkang.cinefin.film.presentation.downloads.DownloadAction
import com.zhangwenkang.cinefin.film.presentation.downloads.DownloadHierarchyChild
import com.zhangwenkang.cinefin.film.presentation.downloads.DownloadHierarchyContainer
import com.zhangwenkang.cinefin.film.presentation.downloads.DownloadHierarchyEntry
import com.zhangwenkang.cinefin.film.presentation.downloads.DownloadHierarchyFlattener
import com.zhangwenkang.cinefin.film.presentation.downloads.DownloadHierarchyLeaf
import com.zhangwenkang.cinefin.film.presentation.downloads.DownloadHierarchyRow
import com.zhangwenkang.cinefin.film.presentation.downloads.DownloadHierarchyStatus
import com.zhangwenkang.cinefin.film.presentation.downloads.DownloadHierarchySubContainer
import com.zhangwenkang.cinefin.film.presentation.downloads.DownloadManagerState
import com.zhangwenkang.cinefin.film.presentation.downloads.DownloadMediaFilter
import com.zhangwenkang.cinefin.film.presentation.downloads.DownloadsViewModel
import com.zhangwenkang.cinefin.models.FindroidItem
import com.zhangwenkang.cinefin.presentation.theme.CinefinTheme
import com.zhangwenkang.cinefin.presentation.utils.rememberPageGutter
import com.zhangwenkang.cinefin.presentation.utils.rememberSafePadding
import com.zhangwenkang.cinefin.utils.DownloadFailureReason
import com.zhangwenkang.cinefin.utils.DownloadMediaKind
import com.zhangwenkang.cinefin.utils.DownloadStorageUsage
import com.zhangwenkang.cinefin.utils.DownloadTaskGroup
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
                is DownloadAction.OpenEntry -> viewModel.itemForEntry(action.key)?.let(onItemClick)
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
    data class Entry(val key: String, val title: String) : PendingDelete

    data class Container(val key: String, val title: String) : PendingDelete

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
            bookStorageBytes = state.bookStorageBytes,
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

        Spacer(Modifier.height(CinefinSpacing.Space2))
        Row(
            modifier =
                Modifier.fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = horizontalPadding),
            horizontalArrangement = Arrangement.spacedBy(CinefinSpacing.Space2),
        ) {
            CinefinFilterChip(
                text = "全部",
                selected = state.mediaFilter == DownloadMediaFilter.ALL,
                onClick = { onAction(DownloadAction.SetMediaFilter(DownloadMediaFilter.ALL)) },
                compact = true,
            )
            CinefinFilterChip(
                text = "视频",
                selected = state.mediaFilter == DownloadMediaFilter.VIDEO,
                onClick = { onAction(DownloadAction.SetMediaFilter(DownloadMediaFilter.VIDEO)) },
                compact = true,
            )
            CinefinFilterChip(
                text = "音乐",
                selected = state.mediaFilter == DownloadMediaFilter.MUSIC,
                onClick = { onAction(DownloadAction.SetMediaFilter(DownloadMediaFilter.MUSIC)) },
                compact = true,
            )
            CinefinFilterChip(
                text = "书籍",
                selected = state.mediaFilter == DownloadMediaFilter.BOOK,
                onClick = { onAction(DownloadAction.SetMediaFilter(DownloadMediaFilter.BOOK)) },
                compact = true,
            )
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when {
                state.isLoading ->
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = colors.onSurfaceVariant)
                    }
                else ->
                    when (tab) {
                        DownloadTab.ACTIVE ->
                            HierarchyList(
                                containers = state.containersFor(DownloadTaskGroup.ACTIVE),
                                emptyTitle = stringResource(CoreR.string.download_empty_active),
                                expandedKeys = state.expandedKeys,
                                selectionMode = state.selectionMode,
                                selection = state.selection,
                                pageGutter = horizontalPadding,
                                onAction = onAction,
                                onRequestDeleteEntry = { key, title ->
                                    pendingDelete = PendingDelete.Entry(key, title)
                                },
                                onRequestDeleteContainer = { key, title ->
                                    pendingDelete = PendingDelete.Container(key, title)
                                },
                            )
                        DownloadTab.FAILED ->
                            HierarchyList(
                                containers = state.containersFor(DownloadTaskGroup.FAILED),
                                emptyTitle = stringResource(CoreR.string.download_empty_failed),
                                expandedKeys = state.expandedKeys,
                                selectionMode = state.selectionMode,
                                selection = state.selection,
                                pageGutter = horizontalPadding,
                                onAction = onAction,
                                onRequestDeleteEntry = { key, title ->
                                    pendingDelete = PendingDelete.Entry(key, title)
                                },
                                onRequestDeleteContainer = { key, title ->
                                    pendingDelete = PendingDelete.Container(key, title)
                                },
                            )
                        DownloadTab.COMPLETED ->
                            HierarchyList(
                                containers = state.containersFor(DownloadTaskGroup.COMPLETED),
                                emptyTitle = stringResource(CoreR.string.no_downloads),
                                expandedKeys = state.expandedKeys,
                                selectionMode = state.selectionMode,
                                selection = state.selection,
                                pageGutter = horizontalPadding,
                                onAction = onAction,
                                onRequestDeleteEntry = { key, title ->
                                    pendingDelete = PendingDelete.Entry(key, title)
                                },
                                onRequestDeleteContainer = { key, title ->
                                    pendingDelete = PendingDelete.Container(key, title)
                                },
                            )
                    }
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
        is PendingDelete.Entry ->
            AlertDialog(
                title = { Text(pending.title) },
                text = { Text(stringResource(CoreR.string.delete_download_message)) },
                onDismissRequest = { pendingDelete = null },
                confirmButton = {
                    TextButton(
                        onClick = {
                            onAction(DownloadAction.DeleteEntry(pending.key))
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
        is PendingDelete.Container ->
            AlertDialog(
                title = { Text(pending.title) },
                text = { Text("删除该下载分组下的全部内容？") },
                onDismissRequest = { pendingDelete = null },
                confirmButton = {
                    TextButton(
                        onClick = {
                            onAction(DownloadAction.DeleteContainer(pending.key))
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
private fun StorageSummary(
    storage: DownloadStorageUsage,
    bookStorageBytes: Long,
    modifier: Modifier = Modifier,
) {
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
                    Formatter.formatFileSize(context, storage.usedBytes + bookStorageBytes),
                    Formatter.formatFileSize(context, storage.availableBytes),
                ),
            style = CinefinType.BodyMedium,
            color = colors.onSurfaceVariant,
        )
        if (bookStorageBytes > 0L) {
            Spacer(Modifier.size(CinefinSpacing.Space2))
            Text(
                text = "（含书籍 ${Formatter.formatFileSize(context, bookStorageBytes)}）",
                style = CinefinType.BodySmall,
                color = colors.onSurfaceFaint,
            )
        }
    }
}

@Composable
private fun HierarchyList(
    containers: List<DownloadHierarchyContainer>,
    emptyTitle: String,
    expandedKeys: Set<String>,
    selectionMode: Boolean,
    selection: Set<String>,
    pageGutter: androidx.compose.ui.unit.Dp,
    onAction: (DownloadAction) -> Unit,
    onRequestDeleteEntry: (key: String, title: String) -> Unit,
    onRequestDeleteContainer: (key: String, title: String) -> Unit,
) {
    if (containers.isEmpty()) {
        CinefinEmptyState(
            title = emptyTitle,
            modifier = Modifier.fillMaxSize().padding(horizontal = pageGutter),
        )
        return
    }
    val rows = DownloadHierarchyFlattener.flatten(containers, expandedKeys)
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = pageGutter),
        contentPadding = PaddingValues(top = CinefinSpacing.Space3, bottom = CinefinSpacing.Space8),
        verticalArrangement = Arrangement.spacedBy(CinefinSpacing.Space3),
    ) {
        items(rows, key = { row -> row.key }) { row ->
            when (row) {
                is DownloadHierarchyRow.ContainerRow ->
                    HierarchyContainerCard(
                        container = row.container,
                        depth = row.depth,
                        collapsed = row.collapsed,
                        selectionMode = selectionMode,
                        selection = selection,
                        onAction = onAction,
                        onRequestDeleteContainer = onRequestDeleteContainer,
                    )
                is DownloadHierarchyRow.ChildContainerRow ->
                    HierarchySubContainerCard(
                        container = row.container,
                        depth = row.depth,
                        collapsed = row.collapsed,
                        onAction = onAction,
                    )
                is DownloadHierarchyRow.ItemRow ->
                    HierarchyLeafCard(
                        entry = row.entry,
                        depth = row.depth,
                        selectionMode = selectionMode,
                        selected = row.entry.key in selection,
                        onAction = onAction,
                        onRequestDelete = onRequestDeleteEntry,
                    )
            }
        }
    }
}

/** W34：层级容器卡（节目 / 专辑 / 电影 / 书籍）。 */
@Composable
private fun HierarchyContainerCard(
    container: DownloadHierarchyContainer,
    depth: Int,
    collapsed: Boolean,
    selectionMode: Boolean,
    selection: Set<String>,
    onAction: (DownloadAction) -> Unit,
    onRequestDeleteContainer: (key: String, title: String) -> Unit,
) {
    val colors = LocalCinefinColors.current
    val media = LocalMediaColors.current

    CinefinCard(
        onClick = { onAction(DownloadAction.ToggleContainer(container.key)) },
        selected =
            container.children.any { child -> childSelectionKeys(child).any { it in selection } },
        contentPadding = PaddingValues(CinefinSpacing.Space4),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.width((depth * 8).dp))
            ArtworkThumb(
                imageUri = container.imageUri,
                kind = container.mediaKind,
                size = 44.dp,
            )
            Spacer(Modifier.size(CinefinSpacing.Space3))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = container.title,
                    style = CinefinType.BodyLarge,
                    color = colors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(CinefinSpacing.Space1))
                Text(
                    text = containerDetail(container),
                    style = CinefinType.BodySmall,
                    color =
                        if (container.status == DownloadHierarchyStatus.FAILED) colors.error
                        else colors.onSurfaceVariant,
                )
                if (container.status == DownloadHierarchyStatus.RUNNING) {
                    Spacer(Modifier.height(CinefinSpacing.Space2))
                    LinearProgressIndicator(
                        progress = { container.progress },
                        modifier = Modifier.fillMaxWidth().height(4.dp),
                        color = media.base,
                        trackColor = colors.progressTrack,
                    )
                }
            }
            Spacer(Modifier.size(CinefinSpacing.Space2))
            Icon(
                painter =
                    painterResource(
                        if (collapsed) CoreR.drawable.ic_chevron_down
                        else CoreR.drawable.ic_chevron_up
                    ),
                contentDescription = if (collapsed) "展开" else "折叠",
                tint = colors.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            // W36：容器级「允许离线模式观看」（已完成容器：节目 / 专辑 / 书籍卡片）。
            if (!selectionMode && container.status == DownloadHierarchyStatus.COMPLETED) {
                CinefinSwitch(
                    checked = container.descendantEntries().all { it.allowOffline },
                    onCheckedChange = { allow ->
                        onAction(DownloadAction.SetContainerOffline(container.key, allow))
                    },
                )
            }
            if (!selectionMode && container.canDelete) {
                Spacer(Modifier.size(CinefinSpacing.Space1))
                CinefinIconButton(
                    onClick = { onRequestDeleteContainer(container.key, container.title) }
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
}

/** W34：季容器（节目下的第二级，只展开 / 折叠，不带删除）。 */
@Composable
private fun HierarchySubContainerCard(
    container: DownloadHierarchySubContainer,
    depth: Int,
    collapsed: Boolean,
    onAction: (DownloadAction) -> Unit,
) {
    val colors = LocalCinefinColors.current
    CinefinCard(
        onClick = { onAction(DownloadAction.ToggleContainer(container.key)) },
        contentPadding = PaddingValues(CinefinSpacing.Space3),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.width((depth * 8).dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = container.title,
                    style = CinefinType.BodyLarge,
                    color = colors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(CinefinSpacing.Space1))
                Text(
                    text = container.detail.orEmpty(),
                    style = CinefinType.BodySmall,
                    color =
                        if (container.status == DownloadHierarchyStatus.FAILED) colors.error
                        else colors.onSurfaceVariant,
                )
            }
            Icon(
                painter =
                    painterResource(
                        if (collapsed) CoreR.drawable.ic_chevron_down
                        else CoreR.drawable.ic_chevron_up
                    ),
                contentDescription = if (collapsed) "展开" else "折叠",
                tint = colors.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** W34：层级里的单个条目（剧集 / 曲目 / 电影 / 书籍卡片）。 */
@Composable
private fun HierarchyLeafCard(
    entry: DownloadHierarchyEntry,
    depth: Int,
    selectionMode: Boolean,
    selected: Boolean,
    onAction: (DownloadAction) -> Unit,
    onRequestDelete: (key: String, title: String) -> Unit,
) {
    val colors = LocalCinefinColors.current
    val media = LocalMediaColors.current
    val context = LocalContext.current
    val task = entry.task

    CinefinCard(
        onClick = {
            when {
                selectionMode -> onAction(DownloadAction.ToggleEntry(entry.key))
                entry.mediaKind == DownloadMediaKind.BOOK ||
                    entry.status == DownloadTaskStatus.COMPLETED ->
                    onAction(DownloadAction.OpenEntry(entry.key))
            }
        },
        selected = selected,
        contentPadding = PaddingValues(CinefinSpacing.Space3),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (selectionMode) {
                Checkbox(
                    checked = selected,
                    onCheckedChange = { onAction(DownloadAction.ToggleEntry(entry.key)) },
                )
                Spacer(Modifier.size(CinefinSpacing.Space1))
            }
            Spacer(Modifier.width((depth * 8).dp))
            ArtworkThumb(imageUri = entry.imageUri, kind = entry.mediaKind, size = 40.dp)
            Spacer(Modifier.size(CinefinSpacing.Space3))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.name,
                    style = CinefinType.BodyMedium,
                    color = colors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(CinefinSpacing.Space1))
                Text(
                    text = leafDetail(entry),
                    style = CinefinType.BodySmall,
                    color =
                        if (entry.status == DownloadTaskStatus.FAILED) colors.error
                        else colors.onSurfaceVariant,
                )
                if (task != null && task.status == DownloadTaskStatus.RUNNING) {
                    Spacer(Modifier.height(CinefinSpacing.Space1))
                    LinearProgressIndicator(
                        progress = { task.progress },
                        modifier = Modifier.fillMaxWidth().height(3.dp),
                        color = media.base,
                        trackColor = colors.progressTrack,
                    )
                }
            }
            if (!selectionMode) {
                Spacer(Modifier.size(CinefinSpacing.Space1))
                Row(horizontalArrangement = Arrangement.spacedBy(CinefinSpacing.Space1)) {
                    // W36：已完成条目的「允许离线模式观看」开关（默认开；关闭后离线媒体库隐藏）。
                    if (entry.status == DownloadTaskStatus.COMPLETED) {
                        CinefinSwitch(
                            checked = entry.allowOffline,
                            onCheckedChange = { onAction(DownloadAction.ToggleOffline(entry.key)) },
                        )
                    }
                    if (task != null) {
                        when {
                            DownloadTaskRules.canPause(task.status) ->
                                CinefinIconButton(
                                    onClick = { onAction(DownloadAction.Pause(task)) }
                                ) { tint ->
                                    Icon(
                                        painter = painterResource(CoreR.drawable.ic_pause),
                                        contentDescription =
                                            stringResource(CoreR.string.download_action_pause),
                                        tint = tint,
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                            DownloadTaskRules.canResume(task.status) ->
                                CinefinIconButton(
                                    onClick = { onAction(DownloadAction.Resume(task)) }
                                ) { tint ->
                                    Icon(
                                        painter = painterResource(CoreR.drawable.ic_play),
                                        contentDescription =
                                            stringResource(CoreR.string.download_action_resume),
                                        tint = tint,
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                            DownloadTaskRules.canRetry(task.status) ->
                                CinefinIconButton(
                                    onClick = { onAction(DownloadAction.Retry(task)) }
                                ) { tint ->
                                    Icon(
                                        painter = painterResource(CoreR.drawable.ic_rotate_ccw),
                                        contentDescription =
                                            stringResource(CoreR.string.download_action_retry),
                                        tint = tint,
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                        }
                    }
                    if (entry.canDelete) {
                        CinefinIconButton(onClick = { onRequestDelete(entry.key, entry.name) }) {
                            tint ->
                            Icon(
                                painter = painterResource(CoreR.drawable.ic_trash),
                                contentDescription =
                                    stringResource(CoreR.string.download_action_delete),
                                tint = tint,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** W34：条目状态行（进度 / 体积 / 失败原因）。 */
@Composable
private fun leafDetail(entry: DownloadHierarchyEntry): String {
    val context = LocalContext.current
    val sizeText =
        when {
            entry.downloadedBytes > 0L && entry.totalBytes > 0L ->
                "${Formatter.formatFileSize(context, entry.downloadedBytes)} / " +
                    Formatter.formatFileSize(context, entry.totalBytes)
            entry.sizeBytes > 0L -> Formatter.formatFileSize(context, entry.sizeBytes)
            else -> ""
        }
    val statusText =
        when (entry.status) {
            DownloadTaskStatus.PENDING -> stringResource(CoreR.string.download_pending)
            DownloadTaskStatus.RUNNING ->
                "${stringResource(CoreR.string.download_downloading)} " +
                    "${((entry.task?.progress ?: 0f) * 100).toInt()}%"
            DownloadTaskStatus.PAUSED ->
                if (entry.task?.failureReason == DownloadFailureReason.NETWORK_UNAVAILABLE) {
                    stringResource(CoreR.string.download_waiting_network)
                } else {
                    stringResource(CoreR.string.download_paused)
                }
            DownloadTaskStatus.FAILED -> failureLabel(entry.task?.failureReason)
            DownloadTaskStatus.COMPLETED -> stringResource(CoreR.string.download_tasks_completed)
        }
    return listOf(statusText, sizeText).filter { it.isNotBlank() }.joinToString(" · ")
}

/** W34：容器状态行（聚合进度 + 状态）。 */
@Composable
private fun containerDetail(container: DownloadHierarchyContainer): String {
    val context = LocalContext.current
    val statusText =
        when (container.status) {
            DownloadHierarchyStatus.FAILED -> "失败"
            DownloadHierarchyStatus.RUNNING -> "下载中"
            DownloadHierarchyStatus.PENDING -> stringResource(CoreR.string.download_pending)
            DownloadHierarchyStatus.PAUSED -> stringResource(CoreR.string.download_paused)
            DownloadHierarchyStatus.COMPLETED -> "已完成"
        }
    val sizeText =
        if (container.sizeBytes > 0L) Formatter.formatFileSize(context, container.sizeBytes) else ""
    return listOfNotNull(container.detail, sizeText)
        .filter { it.isNotBlank() }
        .distinct()
        .joinToString(" · ")
}

/** W34：封面 / 缩略图；无图时按媒体类型用图标占位（不新增位图资源）。 */
@Composable
private fun ArtworkThumb(
    imageUri: String?,
    kind: DownloadMediaKind,
    size: androidx.compose.ui.unit.Dp,
) {
    val colors = LocalCinefinColors.current
    val context = LocalContext.current
    val resolvedUri = imageUri?.let { raw ->
        // W36：服务器 URL / 本地绝对路径原样使用；相对路径（images/…）补成绝对路径，离线也能加载。
        when {
            raw.contains("://") -> raw
            raw.startsWith("/") -> raw
            else -> "${context.filesDir}/$raw"
        }
    }
    Box(
        modifier =
            Modifier.size(size).clip(CinefinShapes.Xs).background(colors.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        when {
            resolvedUri != null ->
                AsyncImage(
                    model = resolvedUri,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            else ->
                Icon(
                    painter =
                        painterResource(
                            when (kind) {
                                DownloadMediaKind.VIDEO -> CoreR.drawable.ic_film
                                DownloadMediaKind.MUSIC -> CoreR.drawable.ic_music
                                DownloadMediaKind.BOOK -> CoreR.drawable.ic_book
                            }
                        ),
                    contentDescription = null,
                    tint = colors.onSurfaceFaint,
                    modifier = Modifier.size(size / 2),
                )
        }
    }
}

private fun childSelectionKeys(child: DownloadHierarchyChild): List<String> =
    when (child) {
        is DownloadHierarchyLeaf -> listOf(child.key)
        is DownloadHierarchySubContainer -> child.children.map { it.key }
    }

/** W36：容器内的全部条目（容器级离线开关的聚合口径）。 */
private fun DownloadHierarchyContainer.descendantEntries(): List<DownloadHierarchyEntry> =
    children.flatMap { child ->
        when (child) {
            is DownloadHierarchyLeaf -> listOf(child.entry)
            is DownloadHierarchySubContainer -> child.children.map { it.entry }
        }
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
private fun SelectionBar(
    state: DownloadManagerState,
    pageGutter: androidx.compose.ui.unit.Dp,
    onAction: (DownloadAction) -> Unit,
    onRequestDelete: () -> Unit,
) {
    val colors = LocalCinefinColors.current
    val selectedTasks = state.selectedTasks
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
