package com.zhangwenkang.cinefin.presentation.film

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zhangwenkang.cinefin.core.R as CoreR
import com.zhangwenkang.cinefin.core.presentation.components.CinefinEmptyState
import com.zhangwenkang.cinefin.core.presentation.components.CinefinFilterChip
import com.zhangwenkang.cinefin.core.presentation.components.CinefinIconButton
import com.zhangwenkang.cinefin.core.presentation.components.CinefinPageTopBar
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinSpacing
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinType
import com.zhangwenkang.cinefin.core.presentation.theme.LocalCinefinColors
import com.zhangwenkang.cinefin.film.presentation.downloads.DownloadAction
import com.zhangwenkang.cinefin.film.presentation.downloads.DownloadFormatRules
import com.zhangwenkang.cinefin.film.presentation.downloads.DownloadHierarchyContainer
import com.zhangwenkang.cinefin.film.presentation.downloads.DownloadHierarchyFlattener
import com.zhangwenkang.cinefin.film.presentation.downloads.DownloadHierarchyRow
import com.zhangwenkang.cinefin.film.presentation.downloads.DownloadManagerState
import com.zhangwenkang.cinefin.film.presentation.downloads.DownloadMediaFilter
import com.zhangwenkang.cinefin.film.presentation.downloads.DownloadsViewModel
import com.zhangwenkang.cinefin.models.FindroidItem
import com.zhangwenkang.cinefin.presentation.film.downloads.DownloadConfirmDialog
import com.zhangwenkang.cinefin.presentation.film.downloads.DownloadContainerCard
import com.zhangwenkang.cinefin.presentation.film.downloads.DownloadGridBlock
import com.zhangwenkang.cinefin.presentation.film.downloads.DownloadGridGrouping
import com.zhangwenkang.cinefin.presentation.film.downloads.DownloadLeafCard
import com.zhangwenkang.cinefin.presentation.film.downloads.DownloadListMetrics
import com.zhangwenkang.cinefin.presentation.film.downloads.DownloadListSkeleton
import com.zhangwenkang.cinefin.presentation.film.downloads.DownloadSeasonCard
import com.zhangwenkang.cinefin.presentation.film.downloads.descendantEntries
import com.zhangwenkang.cinefin.presentation.theme.CinefinTheme
import com.zhangwenkang.cinefin.presentation.utils.rememberPageGutter
import com.zhangwenkang.cinefin.presentation.utils.rememberSafePadding
import com.zhangwenkang.cinefin.utils.DownloadStorageUsage
import com.zhangwenkang.cinefin.utils.DownloadTaskGroup
import com.zhangwenkang.cinefin.utils.DownloadTaskRules

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
    val safePadding = rememberSafePadding(handleStartInsets = false)
    val pageGutter = rememberPageGutter()
    val horizontalPadding = safePadding.start + pageGutter
    val compact = LocalConfiguration.current.screenWidthDp < 600
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
                text = stringResource(CoreR.string.download_filter_all),
                selected = state.mediaFilter == DownloadMediaFilter.ALL,
                onClick = { onAction(DownloadAction.SetMediaFilter(DownloadMediaFilter.ALL)) },
                compact = true,
            )
            CinefinFilterChip(
                text = stringResource(CoreR.string.video),
                selected = state.mediaFilter == DownloadMediaFilter.VIDEO,
                onClick = { onAction(DownloadAction.SetMediaFilter(DownloadMediaFilter.VIDEO)) },
                compact = true,
            )
            CinefinFilterChip(
                text = stringResource(CoreR.string.title_music),
                selected = state.mediaFilter == DownloadMediaFilter.MUSIC,
                onClick = { onAction(DownloadAction.SetMediaFilter(DownloadMediaFilter.MUSIC)) },
                compact = true,
            )
            CinefinFilterChip(
                text = stringResource(CoreR.string.download_filter_book),
                selected = state.mediaFilter == DownloadMediaFilter.BOOK,
                onClick = { onAction(DownloadAction.SetMediaFilter(DownloadMediaFilter.BOOK)) },
                compact = true,
            )
        }

        Spacer(Modifier.height(CinefinSpacing.Space2))
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when {
                state.isLoading ->
                    DownloadListSkeleton(
                        compact = compact,
                        modifier =
                            Modifier.padding(
                                start = horizontalPadding,
                                end = horizontalPadding,
                                top = CinefinSpacing.Space3,
                            ),
                    )
                else ->
                    when (tab) {
                        DownloadTab.ACTIVE ->
                            HierarchyList(
                                containers = state.containersFor(DownloadTaskGroup.ACTIVE),
                                emptyTitle = stringResource(CoreR.string.download_empty_active),
                                emptyIconRes = CoreR.drawable.ic_download,
                                expandedKeys = state.expandedKeys,
                                selectionMode = state.selectionMode,
                                selection = state.selection,
                                pageGutter = horizontalPadding,
                                compact = compact,
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
                                emptyIconRes = CoreR.drawable.ic_alert_circle,
                                expandedKeys = state.expandedKeys,
                                selectionMode = state.selectionMode,
                                selection = state.selection,
                                pageGutter = horizontalPadding,
                                compact = compact,
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
                                emptyIconRes = CoreR.drawable.ic_check,
                                expandedKeys = state.expandedKeys,
                                selectionMode = state.selectionMode,
                                selection = state.selection,
                                pageGutter = horizontalPadding,
                                compact = compact,
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
            DownloadConfirmDialog(
                title = pending.title,
                message = stringResource(CoreR.string.delete_download_message),
                confirmText = stringResource(CoreR.string.download_action_delete),
                onConfirm = {
                    onAction(DownloadAction.DeleteEntry(pending.key))
                    pendingDelete = null
                },
                onDismiss = { pendingDelete = null },
            )
        is PendingDelete.Container ->
            DownloadConfirmDialog(
                title = pending.title,
                message = stringResource(CoreR.string.download_delete_container_message),
                confirmText = stringResource(CoreR.string.download_action_delete),
                onConfirm = {
                    onAction(DownloadAction.DeleteContainer(pending.key))
                    pendingDelete = null
                },
                onDismiss = { pendingDelete = null },
            )
        PendingDelete.Selection ->
            DownloadConfirmDialog(
                title = stringResource(CoreR.string.download_delete_tasks_title),
                message =
                    stringResource(
                        CoreR.string.download_delete_tasks_message,
                        state.selection.size,
                    ),
                confirmText = stringResource(CoreR.string.download_action_delete),
                onConfirm = {
                    onAction(DownloadAction.DeleteSelected)
                    pendingDelete = null
                },
                onDismiss = { pendingDelete = null },
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
                    DownloadFormatRules.formatBytes(storage.usedBytes + bookStorageBytes),
                    DownloadFormatRules.formatBytes(storage.availableBytes),
                ),
            style = CinefinType.BodyMedium,
            color = colors.onSurfaceVariant,
        )
        if (bookStorageBytes > 0L) {
            Spacer(Modifier.size(CinefinSpacing.Space2))
            Text(
                text =
                    stringResource(
                        CoreR.string.download_storage_books_suffix,
                        DownloadFormatRules.formatBytes(bookStorageBytes),
                    ),
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
    emptyIconRes: Int,
    expandedKeys: Set<String>,
    selectionMode: Boolean,
    selection: Set<String>,
    pageGutter: Dp,
    compact: Boolean,
    onAction: (DownloadAction) -> Unit,
    onRequestDeleteEntry: (key: String, title: String) -> Unit,
    onRequestDeleteContainer: (key: String, title: String) -> Unit,
) {
    if (containers.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize().padding(horizontal = pageGutter),
            contentAlignment = Alignment.Center,
        ) {
            CinefinEmptyState(
                title = emptyTitle,
                icon = { tint ->
                    Icon(
                        painter = painterResource(emptyIconRes),
                        contentDescription = null,
                        tint = tint,
                        modifier = Modifier.size(44.dp),
                    )
                },
            )
        }
        return
    }
    val rows =
        remember(containers, expandedKeys) {
            DownloadHierarchyFlattener.flatten(containers, expandedKeys)
        }
    val contentPadding = PaddingValues(top = CinefinSpacing.Space3, bottom = CinefinSpacing.Space8)
    val listModifier = Modifier.fillMaxSize().padding(horizontal = pageGutter)

    if (compact) {
        LazyColumn(
            modifier = listModifier,
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(CinefinSpacing.Space3),
        ) {
            items(
                items = rows,
                key = { row -> row.key },
                contentType = { row -> row.contentType() },
            ) { row ->
                DownloadRow(
                    row = row,
                    compact = true,
                    selectionMode = selectionMode,
                    selection = selection,
                    onAction = onAction,
                    onRequestDeleteEntry = onRequestDeleteEntry,
                    onRequestDeleteContainer = onRequestDeleteContainer,
                )
            }
        }
        return
    }

    // 平板（W52）：折叠的顶层容器两两成行；展开容器连同子项整宽展示。
    val blocks = remember(rows) { DownloadGridGrouping.group(rows) }
    LazyColumn(
        modifier = listModifier,
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(CinefinSpacing.Space3),
    ) {
        items(
            items = blocks,
            key = { block -> block.key },
            contentType = { block -> block.contentType() },
        ) { block ->
            when (block) {
                is DownloadGridBlock.Pair ->
                    Row(
                        // W52：两列卡片同高——平板海报 156dp 高于文本列（≈148dp），行高直接取海报高度，
                        // 不走 intrinsic 测量（避免 Coil 图片节点的 intrinsic 路径）。
                        modifier =
                            Modifier.fillMaxWidth()
                                .height(DownloadListMetrics.PosterHeightExpanded),
                        horizontalArrangement = Arrangement.spacedBy(CinefinSpacing.Space5),
                    ) {
                        val first = block.first.container
                        DownloadContainerCard(
                            container = first,
                            compact = false,
                            collapsed = true,
                            selectionMode = selectionMode,
                            selected = first.descendantEntries().any { it.key in selection },
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            onToggle = { onAction(DownloadAction.ToggleContainer(first.key)) },
                            onRequestDelete = { onRequestDeleteContainer(first.key, first.title) },
                            onSetOffline = { allow ->
                                onAction(DownloadAction.SetContainerOffline(first.key, allow))
                            },
                        )
                        val second = block.second.container
                        DownloadContainerCard(
                            container = second,
                            compact = false,
                            collapsed = true,
                            selectionMode = selectionMode,
                            selected = second.descendantEntries().any { it.key in selection },
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            onToggle = { onAction(DownloadAction.ToggleContainer(second.key)) },
                            onRequestDelete = {
                                onRequestDeleteContainer(second.key, second.title)
                            },
                            onSetOffline = { allow ->
                                onAction(DownloadAction.SetContainerOffline(second.key, allow))
                            },
                        )
                    }
                is DownloadGridBlock.Single ->
                    DownloadRow(
                        row = block.row,
                        compact = false,
                        selectionMode = selectionMode,
                        selection = selection,
                        onAction = onAction,
                        onRequestDeleteEntry = onRequestDeleteEntry,
                        onRequestDeleteContainer = onRequestDeleteContainer,
                    )
            }
        }
    }
}

@Composable
private fun DownloadRow(
    row: DownloadHierarchyRow,
    compact: Boolean,
    selectionMode: Boolean,
    selection: Set<String>,
    onAction: (DownloadAction) -> Unit,
    onRequestDeleteEntry: (key: String, title: String) -> Unit,
    onRequestDeleteContainer: (key: String, title: String) -> Unit,
) {
    when (row) {
        is DownloadHierarchyRow.ContainerRow ->
            DownloadContainerCard(
                container = row.container,
                compact = compact,
                collapsed = row.collapsed,
                selectionMode = selectionMode,
                selected = row.container.descendantEntries().any { it.key in selection },
                onToggle = { onAction(DownloadAction.ToggleContainer(row.container.key)) },
                onRequestDelete = {
                    onRequestDeleteContainer(row.container.key, row.container.title)
                },
                onSetOffline = { allow ->
                    onAction(DownloadAction.SetContainerOffline(row.container.key, allow))
                },
            )
        is DownloadHierarchyRow.ChildContainerRow ->
            DownloadSeasonCard(
                container = row.container,
                depth = row.depth,
                collapsed = row.collapsed,
                onToggle = { onAction(DownloadAction.ToggleContainer(row.container.key)) },
            )
        is DownloadHierarchyRow.ItemRow ->
            DownloadLeafCard(
                entry = row.entry,
                depth = row.depth,
                selectionMode = selectionMode,
                selected = row.entry.key in selection,
                onToggleSelection = { onAction(DownloadAction.ToggleEntry(row.entry.key)) },
                onOpen = { onAction(DownloadAction.OpenEntry(row.entry.key)) },
                onAction = onAction,
                onRequestDelete = onRequestDeleteEntry,
            )
    }
}

private fun DownloadHierarchyRow.contentType(): String =
    when (this) {
        is DownloadHierarchyRow.ContainerRow -> "download-container"
        is DownloadHierarchyRow.ChildContainerRow -> "download-season"
        is DownloadHierarchyRow.ItemRow -> "download-item"
    }

private fun DownloadGridBlock.contentType(): String =
    when (this) {
        is DownloadGridBlock.Pair -> "download-container-pair"
        is DownloadGridBlock.Single -> row.contentType()
    }

@Composable
private fun SelectionBar(
    state: DownloadManagerState,
    pageGutter: Dp,
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
