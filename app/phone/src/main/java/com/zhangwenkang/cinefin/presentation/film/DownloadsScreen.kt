package com.zhangwenkang.cinefin.presentation.film

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.runtime.withFrameNanos
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
import com.zhangwenkang.cinefin.film.presentation.downloads.DownloadDetailRow
import com.zhangwenkang.cinefin.film.presentation.downloads.DownloadDrilldownRules
import com.zhangwenkang.cinefin.film.presentation.downloads.DownloadFormatRules
import com.zhangwenkang.cinefin.film.presentation.downloads.DownloadHierarchyContainer
import com.zhangwenkang.cinefin.film.presentation.downloads.DownloadHierarchyRow
import com.zhangwenkang.cinefin.film.presentation.downloads.DownloadManagerState
import com.zhangwenkang.cinefin.film.presentation.downloads.DownloadMediaFilter
import com.zhangwenkang.cinefin.film.presentation.downloads.DownloadsViewModel
import com.zhangwenkang.cinefin.film.presentation.downloads.allEntries
import com.zhangwenkang.cinefin.film.presentation.downloads.detailRows
import com.zhangwenkang.cinefin.models.FindroidItem
import com.zhangwenkang.cinefin.presentation.film.downloads.DownloadConfirmDialog
import com.zhangwenkang.cinefin.presentation.film.downloads.DownloadDrilldownHeader
import com.zhangwenkang.cinefin.presentation.film.downloads.DownloadGridBlock
import com.zhangwenkang.cinefin.presentation.film.downloads.DownloadGridGrouping
import com.zhangwenkang.cinefin.presentation.film.downloads.DownloadLeafCard
import com.zhangwenkang.cinefin.presentation.film.downloads.DownloadListMetrics
import com.zhangwenkang.cinefin.presentation.film.downloads.DownloadListSkeleton
import com.zhangwenkang.cinefin.presentation.film.downloads.DownloadSeasonCard
import com.zhangwenkang.cinefin.presentation.film.downloads.DownloadTopLevelCard
import com.zhangwenkang.cinefin.presentation.film.downloads.DownloadTopLevelRow
import com.zhangwenkang.cinefin.presentation.theme.CinefinTheme
import com.zhangwenkang.cinefin.presentation.utils.rememberPageGutter
import com.zhangwenkang.cinefin.presentation.utils.rememberSafePadding
import com.zhangwenkang.cinefin.utils.DownloadMediaKind
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
                is DownloadAction.OpenEntry -> viewModel.resolveEntryItem(action.key, onItemClick)
                else -> viewModel.onAction(action)
            }
        },
    )
}

/** W59 页签顺序 = 用户口径「进行中 → 失败 → 已完成」。 */
private enum class DownloadTab {
    ACTIVE,
    FAILED,
    COMPLETED,
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
    /** W59：钻取详情页的容器 key；null = 顶层列表。 */
    var drilldownKey by rememberSaveable { mutableStateOf<String?>(null) }
    val drilldown = drilldownKey?.let { key -> state.containerFor(key) }

    // 容器被删除 / 刷新后消失：自动回顶层列表，避免停在空详情。
    LaunchedEffect(drilldownKey, drilldown) {
        if (drilldownKey != null && drilldown == null) drilldownKey = null
    }
    BackHandler(enabled = state.selectionMode || drilldownKey != null) {
        when {
            state.selectionMode -> onAction(DownloadAction.ClearSelection)
            else -> drilldownKey = null
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(colors.surface)) {
        CinefinPageTopBar(
            title = drilldown?.title ?: stringResource(CoreR.string.title_download),
            subtitle =
                drilldown?.let { container ->
                    stringResource(
                        if (container.mediaKind == DownloadMediaKind.MUSIC) {
                            CoreR.string.download_count_tracks
                        } else {
                            CoreR.string.download_count_episodes
                        },
                        container.completedCount,
                        container.totalCount,
                    )
                }
                    ?: stringResource(
                        CoreR.string.download_section_summary,
                        state.activeCount,
                        state.completedCount,
                        state.failedCount,
                    ),
            onOpenDrawer = if (drilldown == null) onOpenDrawer else null,
            onBack = if (drilldown != null) ({ drilldownKey = null }) else null,
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

        if (drilldown == null) {
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
                    text =
                        stringResource(CoreR.string.download_tasks_active) +
                            " ${state.activeCount}",
                    selected = tab == DownloadTab.ACTIVE,
                    onClick = { tab = DownloadTab.ACTIVE },
                    compact = true,
                )
                CinefinFilterChip(
                    text =
                        stringResource(CoreR.string.download_tasks_failed) +
                            " ${state.failedCount}",
                    selected = tab == DownloadTab.FAILED,
                    onClick = { tab = DownloadTab.FAILED },
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
                    onClick = {
                        onAction(DownloadAction.SetMediaFilter(DownloadMediaFilter.VIDEO))
                    },
                    compact = true,
                )
                CinefinFilterChip(
                    text = stringResource(CoreR.string.title_music),
                    selected = state.mediaFilter == DownloadMediaFilter.MUSIC,
                    onClick = {
                        onAction(DownloadAction.SetMediaFilter(DownloadMediaFilter.MUSIC))
                    },
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
                        OverviewList(
                            containers = state.containersFor(tab.group),
                            emptyTitle =
                                stringResource(
                                    when (tab) {
                                        DownloadTab.ACTIVE -> CoreR.string.download_empty_active
                                        DownloadTab.FAILED -> CoreR.string.download_empty_failed
                                        DownloadTab.COMPLETED -> CoreR.string.no_downloads
                                    }
                                ),
                            emptyIconRes =
                                when (tab) {
                                    DownloadTab.ACTIVE -> CoreR.drawable.ic_download
                                    DownloadTab.FAILED -> CoreR.drawable.ic_alert_circle
                                    DownloadTab.COMPLETED -> CoreR.drawable.ic_check
                                },
                            selectionMode = state.selectionMode,
                            selection = state.selection,
                            pageGutter = horizontalPadding,
                            compact = compact,
                            onAction = onAction,
                            onOpenDetail = { key -> drilldownKey = key },
                            onRequestDeleteEntry = { key, title ->
                                pendingDelete = PendingDelete.Entry(key, title)
                            },
                            onRequestDeleteContainer = { key, title ->
                                pendingDelete = PendingDelete.Container(key, title)
                            },
                        )
                }
            }
        } else {
            DownloadDetailPane(
                container = drilldown,
                pageGutter = horizontalPadding,
                selectionMode = state.selectionMode,
                selection = state.selection,
                onAction = onAction,
                onRequestDeleteEntry = { key, title ->
                    pendingDelete = PendingDelete.Entry(key, title)
                },
                onRequestDeleteContainer = { key, title ->
                    pendingDelete = PendingDelete.Container(key, title)
                },
            )
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

private val DownloadTab.group: DownloadTaskGroup
    get() =
        when (this) {
            DownloadTab.ACTIVE -> DownloadTaskGroup.ACTIVE
            DownloadTab.FAILED -> DownloadTaskGroup.FAILED
            DownloadTab.COMPLETED -> DownloadTaskGroup.COMPLETED
        }

@Composable
private fun DownloadDetailPane(
    container: DownloadHierarchyContainer,
    pageGutter: Dp,
    selectionMode: Boolean,
    selection: Set<String>,
    onAction: (DownloadAction) -> Unit,
    onRequestDeleteEntry: (key: String, title: String) -> Unit,
    onRequestDeleteContainer: (key: String, title: String) -> Unit,
) {
    var expandedSeasons by remember(container.key) { mutableStateOf<Set<String>>(emptySet()) }
    val listState = rememberLazyListState()
    val rows =
        remember(container.key, container, expandedSeasons) {
            detailRows(container, expandedSeasons)
        }

    // W59：进入详情自动展开第一个进行中的季（没有进行中 → 第一个未完成），并滚到该季可见。
    LaunchedEffect(container.key) {
        val auto = DownloadDrilldownRules.autoExpandSeasonKey(container) ?: return@LaunchedEffect
        if (auto in expandedSeasons) return@LaunchedEffect
        val updated = expandedSeasons + auto
        expandedSeasons = updated
        val index = detailRows(container, updated).indexOfFirst { it.key == auto }
        if (index >= 0) {
            withFrameNanos {}
            listState.animateScrollToItem(index + 1)
        }
    }

    val contentPadding = PaddingValues(top = CinefinSpacing.Space3, bottom = CinefinSpacing.Space8)
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().padding(horizontal = pageGutter),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(CinefinSpacing.Space3),
    ) {
        item(key = "detail-header") {
            DownloadDrilldownHeader(
                container = container,
                onPauseAll = { onAction(DownloadAction.PauseContainer(container.key)) },
                onResumeAll = { onAction(DownloadAction.ResumeContainer(container.key)) },
                onRequestDelete = { onRequestDeleteContainer(container.key, container.title) },
            )
        }
        items(items = rows, key = { row -> row.key }) { row ->
            when (row) {
                is DownloadDetailRow.Season -> {
                    val seasonKeys = row.season.children.map { it.key }.toSet()
                    DownloadSeasonCard(
                        season = row.season,
                        expanded = row.expanded,
                        selectionMode = selectionMode,
                        selected = seasonKeys.any { it in selection },
                        onClick = {
                            when {
                                selectionMode ->
                                    onAction(DownloadAction.ToggleSelection(row.season.key))
                                row.expanded -> expandedSeasons = expandedSeasons - row.season.key
                                else -> expandedSeasons = expandedSeasons + row.season.key
                            }
                        },
                    )
                }
                is DownloadDetailRow.Item -> {
                    val entry = row.entry
                    DownloadLeafCard(
                        entry = entry,
                        depth = if (entry.seriesId != null) 1 else 0,
                        selectionMode = selectionMode,
                        selected = entry.key in selection,
                        onToggleSelection = { onAction(DownloadAction.ToggleEntry(entry.key)) },
                        onOpen = { onAction(DownloadAction.OpenEntry(entry.key)) },
                        onAction = onAction,
                        onRequestDelete = onRequestDeleteEntry,
                    )
                }
            }
        }
    }
}

/** 顶层列表：只显示 Show 卡 / 专辑卡 / 电影条目 / 书籍条目（平板折叠顶层项两两并排）。 */
@Composable
private fun OverviewList(
    containers: List<DownloadHierarchyContainer>,
    emptyTitle: String,
    emptyIconRes: Int,
    selectionMode: Boolean,
    selection: Set<String>,
    pageGutter: Dp,
    compact: Boolean,
    onAction: (DownloadAction) -> Unit,
    onOpenDetail: (String) -> Unit,
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
        remember(containers) {
            // W59：顶层排序 进行中 → 失败 → 已完成（同组按最近更新时间，再按标题）。
            DownloadDrilldownRules.sortForDisplay(containers).map { container ->
                DownloadHierarchyRow.ContainerRow(
                    key = container.key,
                    container = container,
                    depth = 0,
                    collapsed = true,
                )
            }
        }
    val contentPadding = PaddingValues(top = CinefinSpacing.Space3, bottom = CinefinSpacing.Space8)
    val listModifier = Modifier.fillMaxSize().padding(horizontal = pageGutter)

    if (compact) {
        LazyColumn(
            modifier = listModifier,
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(CinefinSpacing.Space3),
        ) {
            items(items = rows, key = { row -> row.key }) { row ->
                OverviewItem(
                    container = row.container,
                    compact = true,
                    selectionMode = selectionMode,
                    selection = selection,
                    onAction = onAction,
                    onOpenDetail = onOpenDetail,
                    onRequestDeleteEntry = onRequestDeleteEntry,
                    onRequestDeleteContainer = onRequestDeleteContainer,
                )
            }
        }
        return
    }

    // 平板（W52 口径保留）：折叠顶层项两两并排；专辑与海报同高取整行高度。
    val blocks = remember(rows) { DownloadGridGrouping.group(rows) }
    LazyColumn(
        modifier = listModifier,
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(CinefinSpacing.Space3),
    ) {
        items(items = blocks, key = { block -> block.key }) { block ->
            when (block) {
                is DownloadGridBlock.Pair ->
                    Row(
                        modifier =
                            Modifier.fillMaxWidth()
                                .height(DownloadListMetrics.PosterHeightExpanded),
                        horizontalArrangement = Arrangement.spacedBy(CinefinSpacing.Space5),
                    ) {
                        OverviewItem(
                            container = block.first.container,
                            compact = false,
                            selectionMode = selectionMode,
                            selection = selection,
                            onAction = onAction,
                            onOpenDetail = onOpenDetail,
                            onRequestDeleteEntry = onRequestDeleteEntry,
                            onRequestDeleteContainer = onRequestDeleteContainer,
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                        )
                        OverviewItem(
                            container = block.second.container,
                            compact = false,
                            selectionMode = selectionMode,
                            selection = selection,
                            onAction = onAction,
                            onOpenDetail = onOpenDetail,
                            onRequestDeleteEntry = onRequestDeleteEntry,
                            onRequestDeleteContainer = onRequestDeleteContainer,
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                        )
                    }
                is DownloadGridBlock.Single ->
                    (block.row as? DownloadHierarchyRow.ContainerRow)?.let { containerRow ->
                        OverviewItem(
                            container = containerRow.container,
                            compact = false,
                            selectionMode = selectionMode,
                            selection = selection,
                            onAction = onAction,
                            onOpenDetail = onOpenDetail,
                            onRequestDeleteEntry = onRequestDeleteEntry,
                            onRequestDeleteContainer = onRequestDeleteContainer,
                        )
                    }
            }
        }
    }
}

@Composable
private fun OverviewItem(
    container: DownloadHierarchyContainer,
    compact: Boolean,
    selectionMode: Boolean,
    selection: Set<String>,
    onAction: (DownloadAction) -> Unit,
    onOpenDetail: (String) -> Unit,
    onRequestDeleteEntry: (key: String, title: String) -> Unit,
    onRequestDeleteContainer: (key: String, title: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val drillable = DownloadDrilldownRules.isDrilldown(container)
    val entries = container.allEntries()
    val selected = entries.any { it.key in selection }
    val onClick = {
        when {
            selectionMode -> onAction(DownloadAction.ToggleSelection(container.key))
            drillable -> onOpenDetail(container.key)
            else -> {
                val first = entries.firstOrNull()
                if (first != null) onAction(DownloadAction.OpenEntry(first.key))
            }
        }
    }
    if (drillable) {
        DownloadTopLevelCard(
            container = container,
            compact = compact,
            selectionMode = selectionMode,
            selected = selected,
            modifier = modifier,
            onClick = onClick,
        )
    } else {
        DownloadTopLevelRow(
            container = container,
            selectionMode = selectionMode,
            selected = selected,
            modifier = modifier,
            onClick = onClick,
            onAction = onAction,
            onRequestDelete = onRequestDeleteEntry,
        )
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
