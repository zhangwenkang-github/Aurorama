package com.zhangwenkang.cinefin.presentation.film

import android.content.Intent
import android.provider.Settings
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
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zhangwenkang.cinefin.core.R as CoreR
import com.zhangwenkang.cinefin.core.presentation.components.CinefinButton
import com.zhangwenkang.cinefin.core.presentation.components.CinefinButtonSize
import com.zhangwenkang.cinefin.core.presentation.components.CinefinButtonVariant
import com.zhangwenkang.cinefin.core.presentation.components.CinefinCard
import com.zhangwenkang.cinefin.core.presentation.components.CinefinEmptyState
import com.zhangwenkang.cinefin.core.presentation.components.CinefinFilterChip
import com.zhangwenkang.cinefin.core.presentation.components.CinefinIconButton
import com.zhangwenkang.cinefin.core.presentation.components.CinefinPageTopBar
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinShapes
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinSpacing
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinTheme
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinType
import com.zhangwenkang.cinefin.core.presentation.theme.LocalCinefinColors
import com.zhangwenkang.cinefin.film.presentation.downloads.DownloadAction
import com.zhangwenkang.cinefin.film.presentation.downloads.DownloadActionFailure
import com.zhangwenkang.cinefin.film.presentation.downloads.DownloadBookSection
import com.zhangwenkang.cinefin.film.presentation.downloads.DownloadBookSectionGroup
import com.zhangwenkang.cinefin.film.presentation.downloads.DownloadBookSectionRules
import com.zhangwenkang.cinefin.film.presentation.downloads.DownloadDeletionFailureReason
import com.zhangwenkang.cinefin.film.presentation.downloads.DownloadDetailRow
import com.zhangwenkang.cinefin.film.presentation.downloads.DownloadDrilldownRules
import com.zhangwenkang.cinefin.film.presentation.downloads.DownloadFormatRules
import com.zhangwenkang.cinefin.film.presentation.downloads.DownloadHierarchyContainer
import com.zhangwenkang.cinefin.film.presentation.downloads.DownloadHierarchyRow
import com.zhangwenkang.cinefin.film.presentation.downloads.DownloadManagerState
import com.zhangwenkang.cinefin.film.presentation.downloads.DownloadMediaFilter
import com.zhangwenkang.cinefin.film.presentation.downloads.DownloadPendingAction
import com.zhangwenkang.cinefin.film.presentation.downloads.DownloadPlaybackRequest
import com.zhangwenkang.cinefin.film.presentation.downloads.DownloadSelectionRules
import com.zhangwenkang.cinefin.film.presentation.downloads.DownloadSortMode
import com.zhangwenkang.cinefin.film.presentation.downloads.DownloadsViewModel
import com.zhangwenkang.cinefin.film.presentation.downloads.allEntries
import com.zhangwenkang.cinefin.film.presentation.downloads.deletionProjection
import com.zhangwenkang.cinefin.film.presentation.downloads.detailRows
import com.zhangwenkang.cinefin.film.presentation.downloads.shouldShowNotificationPermissionNudge
import com.zhangwenkang.cinefin.models.FindroidItem
import com.zhangwenkang.cinefin.presentation.film.downloads.DownloadBookSectionHeader
import com.zhangwenkang.cinefin.presentation.film.downloads.DownloadConfirmDialog
import com.zhangwenkang.cinefin.presentation.film.downloads.DownloadDrilldownHeader
import com.zhangwenkang.cinefin.presentation.film.downloads.DownloadGridBlock
import com.zhangwenkang.cinefin.presentation.film.downloads.DownloadGridGrouping
import com.zhangwenkang.cinefin.presentation.film.downloads.DownloadIndexReconcileDialog
import com.zhangwenkang.cinefin.presentation.film.downloads.DownloadLeafCard
import com.zhangwenkang.cinefin.presentation.film.downloads.DownloadListMetrics
import com.zhangwenkang.cinefin.presentation.film.downloads.DownloadListSkeleton
import com.zhangwenkang.cinefin.presentation.film.downloads.DownloadSeasonCard
import com.zhangwenkang.cinefin.presentation.film.downloads.DownloadSelectionActionId
import com.zhangwenkang.cinefin.presentation.film.downloads.DownloadSelectionActionSpec
import com.zhangwenkang.cinefin.presentation.film.downloads.DownloadSortMenu
import com.zhangwenkang.cinefin.presentation.film.downloads.DownloadTopLevelCard
import com.zhangwenkang.cinefin.presentation.film.downloads.DownloadTopLevelRow
import com.zhangwenkang.cinefin.presentation.film.downloads.deletionStatusText
import com.zhangwenkang.cinefin.presentation.film.downloads.downloadSelectionActionSpecs
import com.zhangwenkang.cinefin.presentation.selection.MediaBatchEvent
import com.zhangwenkang.cinefin.presentation.selection.MediaBatchViewModel
import com.zhangwenkang.cinefin.presentation.selection.startVideoQueuePlayback
import com.zhangwenkang.cinefin.presentation.utils.rememberPageGutter
import com.zhangwenkang.cinefin.presentation.utils.rememberSafePadding
import com.zhangwenkang.cinefin.utils.DownloadDeleteTraceRuntime
import com.zhangwenkang.cinefin.utils.DownloadMediaKind
import com.zhangwenkang.cinefin.utils.DownloadStorageUsage
import com.zhangwenkang.cinefin.utils.DownloadTaskGroup
import com.zhangwenkang.cinefin.utils.safeAttached
import com.zhangwenkang.cinefin.utils.safeDisposed
import java.util.UUID
import kotlinx.serialization.Serializable

@Composable
fun DownloadsScreen(
    /** 抽屉入口；null = 当前形态没有抽屉（手机 Compact，W6-R6N）。 */
    onOpenDrawer: (() -> Unit)?,
    onItemClick: (item: FindroidItem) -> Unit,
    onPlayMusicItemIds: (List<UUID>) -> Unit = {},
    initialTab: DownloadTab = DownloadTab.ACTIVE,
    requestId: Long = 0L,
    viewModel: DownloadsViewModel = hiltViewModel(),
    batchViewModel: MediaBatchViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(Unit) { viewModel.start() }
    DisposableEffect(Unit) { onDispose { viewModel.stop() } }
    // W7-T5（需求 09）：从系统设置返回后重查通知权限，已授权则引导条自动消失。
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refreshNotificationPermission() }
    LaunchedEffect(viewModel) {
        viewModel.playbackRequests.collect { request ->
            when (request) {
                is DownloadPlaybackRequest.Music -> onPlayMusicItemIds(request.itemIds)
                is DownloadPlaybackRequest.Video -> batchViewModel.playSelected(request.items)
            }
        }
    }
    LaunchedEffect(batchViewModel) {
        batchViewModel.events.collect { event ->
            if (event is MediaBatchEvent.PlayQueue) {
                context.startVideoQueuePlayback(event.entries)
            }
        }
    }

    DownloadsScreenLayout(
        onOpenDrawer = onOpenDrawer,
        state = state,
        initialTab = initialTab,
        requestId = requestId,
        onAction = { action ->
            when (action) {
                is DownloadAction.Open -> onItemClick(action.item)
                // W9-T5（F4）：已完成音乐单击 = 本地播放（不发 Items）；其余条目维持既有详情解析。
                is DownloadAction.OpenEntry ->
                    if (!viewModel.playMusicEntry(action.key)) {
                        viewModel.resolveEntryItem(action.key, onItemClick)
                    }
                else -> viewModel.onAction(action)
            }
        },
        onPlayMusicEntry = { key -> viewModel.playMusicEntry(key) },
        onOpenNotificationSettings = {
            viewModel.onNotificationPermissionNudgeOpened()
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                )
            }
        },
    )
}

/** W59 页签顺序 = 用户口径「进行中 → 失败 → 已完成」。 */
@Serializable
enum class DownloadTab {
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
    initialTab: DownloadTab,
    requestId: Long,
    onAction: (DownloadAction) -> Unit,
    onPlayMusicEntry: (String) -> Unit,
    onOpenNotificationSettings: () -> Unit,
) {
    val colors = LocalCinefinColors.current
    val safePadding = rememberSafePadding(handleStartInsets = false)
    val pageGutter = rememberPageGutter()
    val horizontalPadding = safePadding.start + pageGutter
    val compact = LocalConfiguration.current.screenWidthDp < 600
    var tab by rememberSaveable { mutableStateOf(DownloadTab.ACTIVE) }
    var pendingDelete by remember { mutableStateOf<PendingDelete?>(null) }
    /** W9：下载索引对账对话框；当前页面没有设置入口，按任务卡落在下载页。 */
    var showIndexReconcile by remember { mutableStateOf(false) }
    /** W59：钻取详情页的容器 key；null = 顶层列表。 */
    var drilldownKey by rememberSaveable { mutableStateOf<String?>(null) }
    val drilldown = drilldownKey?.let { key -> state.containerFor(key) }
    val selectableKeys = DownloadSelectionRules.keysFor(state, tab.group)
    val allVisibleSelected =
        selectableKeys.isNotEmpty() && state.selection.containsAll(selectableKeys)

    LaunchedEffect(requestId, initialTab) {
        tab = initialTab
        drilldownKey = null
        pendingDelete = null
        showIndexReconcile = false
        onAction(DownloadAction.ClearSelection)
    }

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
                    state.selectionMode -> {
                        CinefinIconButton(
                            onClick = {
                                onAction(
                                    DownloadAction.SelectKeys(
                                        if (allVisibleSelected) emptyList() else selectableKeys
                                    )
                                )
                            }
                        ) { tint ->
                            Icon(
                                painter = painterResource(CoreR.drawable.ic_check),
                                contentDescription =
                                    stringResource(
                                        if (tab == DownloadTab.FAILED) {
                                            CoreR.string.download_select_all_failed
                                        } else {
                                            CoreR.string.download_select_all
                                        }
                                    ),
                                tint = tint,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                        CinefinIconButton(onClick = { onAction(DownloadAction.ClearSelection) }) {
                            tint ->
                            Icon(
                                painter = painterResource(CoreR.drawable.ic_close),
                                contentDescription = stringResource(CoreR.string.cancel),
                                tint = tint,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                    state.activeCount + state.completedCount + state.failedCount > 0 -> {
                        if (tab == DownloadTab.FAILED && state.failedCount > 0) {
                            CinefinIconButton(
                                onClick = { onAction(DownloadAction.RetryAllFailures) }
                            ) { tint ->
                                Icon(
                                    painter = painterResource(CoreR.drawable.ic_rotate_ccw),
                                    contentDescription =
                                        stringResource(CoreR.string.download_retry_all_failed),
                                    tint = tint,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
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
                }
            },
        )

        if (drilldown == null) {
            // W7-T5（需求 09）：无通知权限且有进行中任务 → 顶部非阻断引导，每会话一次。
            if (
                shouldShowNotificationPermissionNudge(
                    activeCount = state.activeCount,
                    notificationsEnabled = state.notificationsEnabled,
                    dismissedThisSession = state.notificationPermissionNudgeDismissed,
                )
            ) {
                NotificationPermissionNudge(
                    onOpenSettings = onOpenNotificationSettings,
                    modifier =
                        Modifier.padding(
                            start = horizontalPadding,
                            end = horizontalPadding,
                            top = CinefinSpacing.Space3,
                        ),
                )
            }
            Spacer(Modifier.height(CinefinSpacing.Space3))
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = horizontalPadding),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StorageSummary(
                    storage = state.storage,
                    bookStorageBytes = state.bookStorageBytes,
                    modifier = Modifier.weight(1f),
                )
                CinefinButton(
                    text = stringResource(CoreR.string.download_index_reconcile),
                    onClick = {
                        onAction(DownloadAction.ReconcileIndex)
                        showIndexReconcile = true
                    },
                    variant = CinefinButtonVariant.Text,
                    size = CinefinButtonSize.Small,
                )
            }
            Spacer(Modifier.height(CinefinSpacing.Space3))

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = horizontalPadding),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
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
                // W7-T4（需求 51）：排序入口固定在页签行右侧，作用范围 = 当前页签 + 当前筛选的同级节点。
                Spacer(Modifier.size(CinefinSpacing.Space2))
                DownloadSortMenu(
                    selected = state.sortMode,
                    onSelect = { mode -> onAction(DownloadAction.SetSortMode(mode)) },
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
            if (state.refreshFailure != null) {
                Text(
                    text = stringResource(CoreR.string.download_failed),
                    style = CinefinType.BodyMedium,
                    color = colors.error,
                    modifier = Modifier.padding(horizontal = horizontalPadding),
                )
                Spacer(Modifier.height(CinefinSpacing.Space2))
            }
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
                            deletionState = state,
                            containers = state.containersFor(tab.group),
                            mediaFilter = state.mediaFilter,
                            sortMode = state.sortMode,
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
                            pendingActions = state.pendingActions,
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
                deletionState = state,
                container = drilldown,
                sortMode = state.sortMode,
                pageGutter = horizontalPadding,
                selectionMode = state.selectionMode,
                selection = state.selection,
                pendingActions = state.pendingActions,
                onAction = onAction,
                onPlayMusicEntry = onPlayMusicEntry,
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
                group = tab.group,
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

    if (showIndexReconcile) {
        DownloadIndexReconcileDialog(
            state = state.indexReconciliation,
            onDelete = { relativePaths ->
                onAction(DownloadAction.DeleteOrphanIndexFiles(relativePaths))
            },
            onRetry = { onAction(DownloadAction.ReconcileIndex) },
            onDismiss = { showIndexReconcile = false },
        )
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
    deletionState: DownloadManagerState,
    container: DownloadHierarchyContainer,
    sortMode: DownloadSortMode,
    pageGutter: Dp,
    selectionMode: Boolean,
    selection: List<String>,
    pendingActions: Map<String, DownloadPendingAction>,
    onAction: (DownloadAction) -> Unit,
    onPlayMusicEntry: (String) -> Unit,
    onRequestDeleteEntry: (key: String, title: String) -> Unit,
    onRequestDeleteContainer: (key: String, title: String) -> Unit,
) {
    val containerDeletion = deletionState.deletionProjection(container)
    val containerSummary =
        deletionState.deletionSummary?.takeIf { summary -> summary.containerKey == container.key }
    var expandedSeasons by remember(container.key) { mutableStateOf<Set<String>>(emptySet()) }
    val listState = rememberLazyListState()
    val rows =
        remember(container.key, container, expandedSeasons, sortMode) {
            detailRows(container, expandedSeasons, sortMode)
        }

    // W59：进入详情自动展开第一个进行中的季（没有进行中 → 第一个未完成），并滚到该季可见。
    LaunchedEffect(container.key) {
        val auto = DownloadDrilldownRules.autoExpandSeasonKey(container) ?: return@LaunchedEffect
        if (auto in expandedSeasons) return@LaunchedEffect
        val updated = expandedSeasons + auto
        expandedSeasons = updated
        val index = detailRows(container, updated, sortMode).indexOfFirst { it.key == auto }
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
                deletionProjection = containerDeletion,
                deletionSummary = containerSummary,
                onPauseAll = { eligibleTaskKeys ->
                    onAction(DownloadAction.PauseContainer(container.key, eligibleTaskKeys))
                },
                onResumeAll = { eligibleTaskKeys ->
                    onAction(DownloadAction.ResumeContainer(container.key, eligibleTaskKeys))
                },
                onRequestDelete = { onRequestDeleteContainer(container.key, container.title) },
            )
        }
        items(items = rows, key = { row -> row.key }) { row ->
            when (row) {
                is DownloadDetailRow.Season -> {
                    val seasonKeys = row.season.children.map { it.key }.toSet()
                    val seasonDeletion = deletionState.deletionProjection(row.season)
                    val seasonSummary = containerSummary?.takeIf { seasonDeletion.failedCount > 0 }
                    DownloadSeasonCard(
                        season = row.season,
                        deletionProjection = seasonDeletion,
                        deletionSummary = seasonSummary,
                        expanded = row.expanded,
                        selectionMode = selectionMode,
                        selected = seasonKeys.any { it in selection },
                        onLongPress = { onAction(DownloadAction.ToggleSelection(row.season.key)) },
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
                    val entryDeletion = deletionState.deletionProjection(listOf(entry))
                    val traceRow =
                        remember(entry.key) { DownloadDeleteTraceRuntime.rowTrace(entry.key) }
                    if (traceRow != null) {
                        DisposableEffect(traceRow) {
                            traceRow.safeAttached()
                            onDispose { traceRow.safeDisposed() }
                        }
                    }
                    DownloadLeafCard(
                        entry = entry,
                        deletionProjection = entryDeletion,
                        depth = if (entry.seriesId != null) 1 else 0,
                        selectionMode = selectionMode,
                        selected = entry.key in selection,
                        onToggleSelection = { onAction(DownloadAction.ToggleEntry(entry.key)) },
                        onLongPress = { onAction(DownloadAction.ToggleEntry(entry.key)) },
                        onOpen = { onAction(DownloadAction.OpenEntry(entry.key)) },
                        onPlay = { onPlayMusicEntry(entry.key) },
                        onAction = onAction,
                        onRequestDelete = onRequestDeleteEntry,
                        pendingAction = pendingActions[entry.key],
                    )
                }
            }
        }
    }
}

/** 顶层列表：只显示 Show 卡 / 专辑卡 / 电影条目 / 书籍条目（平板折叠顶层项两两并排）。 */
@Composable
private fun OverviewList(
    deletionState: DownloadManagerState,
    containers: List<DownloadHierarchyContainer>,
    mediaFilter: DownloadMediaFilter,
    sortMode: DownloadSortMode,
    emptyTitle: String,
    emptyIconRes: Int,
    selectionMode: Boolean,
    selection: List<String>,
    pendingActions: Map<String, DownloadPendingAction>,
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
        remember(containers, sortMode) {
            // W59：顶层排序 进行中 → 失败 → 已完成（同组按最近更新时间，再按标题）；W7-T4 支持用户排序。
            DownloadDrilldownRules.sortForDisplay(containers, sortMode).map { container ->
                DownloadHierarchyRow.ContainerRow(
                    key = container.key,
                    container = container,
                    depth = 0,
                    collapsed = true,
                )
            }
        }

    // W7-T4（需求 44）：书籍筛选下按类型分区（电子书 / 有声书 / 漫画），分区头可折叠。
    if (mediaFilter == DownloadMediaFilter.BOOK) {
        BookSectionList(
            deletionState = deletionState,
            groups =
                remember(containers, sortMode) {
                    DownloadBookSectionRules.group(
                        containers = DownloadDrilldownRules.sortForDisplay(containers, sortMode),
                        sortMode = sortMode,
                    )
                },
            selectionMode = selectionMode,
            selection = selection,
            pendingActions = pendingActions,
            pageGutter = pageGutter,
            onAction = onAction,
            onOpenDetail = onOpenDetail,
            onRequestDeleteEntry = onRequestDeleteEntry,
            onRequestDeleteContainer = onRequestDeleteContainer,
        )
        return
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
                    deletionState = deletionState,
                    container = row.container,
                    compact = true,
                    selectionMode = selectionMode,
                    selection = selection,
                    pendingActions = pendingActions,
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
                            deletionState = deletionState,
                            container = block.first.container,
                            compact = false,
                            selectionMode = selectionMode,
                            selection = selection,
                            pendingActions = pendingActions,
                            onAction = onAction,
                            onOpenDetail = onOpenDetail,
                            onRequestDeleteEntry = onRequestDeleteEntry,
                            onRequestDeleteContainer = onRequestDeleteContainer,
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                        )
                        OverviewItem(
                            deletionState = deletionState,
                            container = block.second.container,
                            compact = false,
                            selectionMode = selectionMode,
                            selection = selection,
                            pendingActions = pendingActions,
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
                            deletionState = deletionState,
                            container = containerRow.container,
                            compact = false,
                            selectionMode = selectionMode,
                            selection = selection,
                            pendingActions = pendingActions,
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

/** W7-T4（需求 44）：书籍区按类型分区渲染；分区折叠状态只在本次页面会话内记忆（不跨媒体类型串）。 */
@Composable
private fun BookSectionList(
    deletionState: DownloadManagerState,
    groups: List<DownloadBookSectionGroup>,
    selectionMode: Boolean,
    selection: List<String>,
    pendingActions: Map<String, DownloadPendingAction>,
    pageGutter: Dp,
    onAction: (DownloadAction) -> Unit,
    onOpenDetail: (String) -> Unit,
    onRequestDeleteEntry: (key: String, title: String) -> Unit,
    onRequestDeleteContainer: (key: String, title: String) -> Unit,
) {
    var collapsedSections by remember { mutableStateOf(emptySet<DownloadBookSection>()) }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = pageGutter),
        contentPadding = PaddingValues(top = CinefinSpacing.Space3, bottom = CinefinSpacing.Space8),
        verticalArrangement = Arrangement.spacedBy(CinefinSpacing.Space3),
    ) {
        groups.forEach { group ->
            item(key = "book-section:${group.section.name}") {
                DownloadBookSectionHeader(
                    section = group.section,
                    count = group.containers.size,
                    collapsed = group.section in collapsedSections,
                    onToggle = {
                        collapsedSections =
                            if (group.section in collapsedSections) {
                                collapsedSections - group.section
                            } else {
                                collapsedSections + group.section
                            }
                    },
                )
            }
            if (group.section !in collapsedSections) {
                items(items = group.containers, key = { container -> container.key }) { container ->
                    OverviewItem(
                        deletionState = deletionState,
                        container = container,
                        compact = true,
                        selectionMode = selectionMode,
                        selection = selection,
                        pendingActions = pendingActions,
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
    deletionState: DownloadManagerState,
    container: DownloadHierarchyContainer,
    compact: Boolean,
    selectionMode: Boolean,
    selection: List<String>,
    pendingActions: Map<String, DownloadPendingAction>,
    onAction: (DownloadAction) -> Unit,
    onOpenDetail: (String) -> Unit,
    onRequestDeleteEntry: (key: String, title: String) -> Unit,
    onRequestDeleteContainer: (key: String, title: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val drillable = DownloadDrilldownRules.isDrilldown(container)
    val entries = container.allEntries()
    val containerDeletion = deletionState.deletionProjection(container)
    val containerSummary =
        deletionState.deletionSummary?.takeIf { summary -> summary.containerKey == container.key }
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
            deletionProjection = containerDeletion,
            deletionSummary = containerSummary,
            compact = compact,
            selectionMode = selectionMode,
            selected = selected,
            modifier = modifier,
            onClick = onClick,
            onLongPress = { onAction(DownloadAction.ToggleSelection(container.key)) },
            onAction = onAction,
            onRequestDelete = { onRequestDeleteContainer(container.key, container.title) },
            pendingAction = entries.firstNotNullOfOrNull { entry -> pendingActions[entry.key] },
        )
    } else {
        DownloadTopLevelRow(
            container = container,
            deletionProjection = containerDeletion,
            deletionSummary = containerSummary,
            selectionMode = selectionMode,
            selected = selected,
            modifier = modifier,
            onClick = onClick,
            onLongPress = { onAction(DownloadAction.ToggleSelection(container.key)) },
            onAction = onAction,
            onRequestDelete = onRequestDeleteEntry,
            pendingAction = entries.firstOrNull()?.key?.let(pendingActions::get),
        )
    }
}

/**
 * W7-T5（需求 09）：通知权限引导长条。
 *
 * 复用 Lumen 面板语言（[CinefinCard] + 描边次级按钮），不引入新配色 / 图标；非阻断，只解释与跳转。
 */
@Composable
private fun NotificationPermissionNudge(
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalCinefinColors.current
    CinefinCard(
        modifier = modifier.fillMaxWidth(),
        shape = CinefinShapes.Card,
        contentPadding =
            PaddingValues(
                horizontal = CinefinSpacing.Space4,
                vertical = CinefinSpacing.Space3,
            ),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(CoreR.string.download_notification_permission_nudge),
                style = CinefinType.BodyMedium,
                color = colors.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.size(CinefinSpacing.Space3))
            CinefinButton(
                text = stringResource(CoreR.string.download_notification_permission_nudge_action),
                onClick = onOpenSettings,
                variant = CinefinButtonVariant.Outlined,
                size = CinefinButtonSize.Small,
            )
        }
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
    group: DownloadTaskGroup,
    pageGutter: Dp,
    onAction: (DownloadAction) -> Unit,
    onRequestDelete: () -> Unit,
) {
    val colors = LocalCinefinColors.current
    val actions = downloadSelectionActionSpecs(state, group)
    val selectedDeletion = state.deletionProjection(state.selectedEntries)
    val selectedDeletionText = deletionStatusText(selectedDeletion, state.deletionSummary)
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
            text =
                when {
                    state.actionFailure == DownloadActionFailure.DELETE &&
                        state.deletionFailureReason == DownloadDeletionFailureReason.SUPERSEDED ->
                        stringResource(CoreR.string.download_delete_generation_superseded)
                    state.actionFailure == DownloadActionFailure.DELETE &&
                        selectedDeletionText != null -> selectedDeletionText
                    selectedDeletion.deleting -> stringResource(CoreR.string.download_deleting)
                    state.actionFailure != null -> stringResource(CoreR.string.download_failed)
                    else ->
                        stringResource(CoreR.string.download_selected_count, state.selection.size)
                },
            style = CinefinType.BodyMedium,
            color = if (state.actionFailure != null) colors.error else colors.onSurface,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(CinefinSpacing.Space1)) {
            actions.forEach { action ->
                SelectionActionIcon(
                    pending = action.pending,
                    enabled = action.enabled,
                    description = stringResource(action.label.resourceId),
                    iconRes = action.iconRes,
                    onClick = {
                        val downloadAction = action.toDownloadAction()
                        if (downloadAction == null) onRequestDelete() else onAction(downloadAction)
                    },
                )
            }
        }
    }
}

private fun DownloadSelectionActionSpec.toDownloadAction(): DownloadAction? =
    when (actionId) {
        DownloadSelectionActionId.PLAY_SELECTED -> DownloadAction.PlaySelected
        DownloadSelectionActionId.PAUSE_SELECTED -> DownloadAction.PauseSelected
        DownloadSelectionActionId.RESUME_SELECTED -> DownloadAction.ResumeSelected
        DownloadSelectionActionId.RETRY_SELECTED -> DownloadAction.RetrySelected
        DownloadSelectionActionId.DELETE_SELECTED -> null
    }

@Composable
private fun SelectionActionIcon(
    pending: Boolean,
    enabled: Boolean,
    description: String,
    iconRes: Int,
    onClick: () -> Unit,
) {
    CinefinIconButton(enabled = enabled, onClick = onClick) { tint ->
        if (pending) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = tint,
            )
        } else {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = description,
                tint = tint,
                modifier = Modifier.size(20.dp),
            )
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
            initialTab = DownloadTab.ACTIVE,
            requestId = 0L,
            onAction = {},
            onPlayMusicEntry = {},
            onOpenNotificationSettings = {},
        )
    }
}
