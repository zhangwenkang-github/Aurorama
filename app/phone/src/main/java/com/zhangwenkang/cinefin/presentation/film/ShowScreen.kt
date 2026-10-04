package com.zhangwenkang.cinefin.presentation.film

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.window.core.layout.WindowSizeClass
import com.zhangwenkang.cinefin.PlayerActivity
import com.zhangwenkang.cinefin.core.R as CoreR
import com.zhangwenkang.cinefin.core.presentation.components.CinefinSnackbarHost
import com.zhangwenkang.cinefin.core.presentation.components.DownloadSnackbarDuration
import com.zhangwenkang.cinefin.core.presentation.dummy.dummyShow
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinShapes
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinSpacing
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinType
import com.zhangwenkang.cinefin.core.presentation.theme.LocalCinefinColors
import com.zhangwenkang.cinefin.core.presentation.theme.ProvideLumen
import com.zhangwenkang.cinefin.film.presentation.detail.DetailDownloadRules
import com.zhangwenkang.cinefin.film.presentation.detail.DetailDownloadState
import com.zhangwenkang.cinefin.film.presentation.detail.DetailDownloadViewModel
import com.zhangwenkang.cinefin.film.presentation.show.ShowAction
import com.zhangwenkang.cinefin.film.presentation.show.ShowState
import com.zhangwenkang.cinefin.film.presentation.show.ShowViewModel
import com.zhangwenkang.cinefin.models.FindroidItem
import com.zhangwenkang.cinefin.models.FindroidShow
import com.zhangwenkang.cinefin.presentation.components.DetailSkeleton
import com.zhangwenkang.cinefin.presentation.downloads.DownloadStatusViewModel
import com.zhangwenkang.cinefin.presentation.film.components.ActorsRow
import com.zhangwenkang.cinefin.presentation.film.components.BatchDownloadDialog
import com.zhangwenkang.cinefin.presentation.film.components.DetailHero
import com.zhangwenkang.cinefin.presentation.film.components.Direction
import com.zhangwenkang.cinefin.presentation.film.components.DownloadBadgeInfo
import com.zhangwenkang.cinefin.presentation.film.components.InfoText
import com.zhangwenkang.cinefin.presentation.film.components.ItemButtonsBar
import com.zhangwenkang.cinefin.presentation.film.components.ItemCard
import com.zhangwenkang.cinefin.presentation.film.components.ItemPoster
import com.zhangwenkang.cinefin.presentation.film.components.ItemTopBar
import com.zhangwenkang.cinefin.presentation.film.components.LumenInfoTable
import com.zhangwenkang.cinefin.presentation.film.components.OverviewText
import com.zhangwenkang.cinefin.presentation.film.components.SectionHeader
import com.zhangwenkang.cinefin.presentation.film.components.detailEyebrow
import com.zhangwenkang.cinefin.presentation.film.components.downloadBadgeInfo
import com.zhangwenkang.cinefin.presentation.film.components.downloadEventMessage
import com.zhangwenkang.cinefin.presentation.film.components.metaLine
import com.zhangwenkang.cinefin.presentation.film.components.showsViewAction
import com.zhangwenkang.cinefin.presentation.theme.CinefinTheme
import com.zhangwenkang.cinefin.presentation.utils.rememberPageGutter
import com.zhangwenkang.cinefin.presentation.utils.rememberSafePadding
import java.util.UUID
import org.jellyfin.sdk.model.api.BaseItemKind

@Composable
fun ShowScreen(
    showId: UUID,
    navigateBack: () -> Unit,
    navigateHome: () -> Unit,
    navigateToItem: (item: FindroidItem) -> Unit,
    navigateToPerson: (personId: UUID) -> Unit,
    /** W60b：下载反馈 Snackbar「查看」→ 下载页。 */
    onOpenDownloads: () -> Unit = {},
    viewModel: ShowViewModel = hiltViewModel(),
    detailDownloadViewModel: DetailDownloadViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current

    val state by viewModel.state.collectAsStateWithLifecycle()
    val downloadSnapshot by detailDownloadViewModel.state.collectAsStateWithLifecycle()
    val downloadStatusViewModel: DownloadStatusViewModel = hiltViewModel()
    val downloadBadges by downloadStatusViewModel.badges.collectAsStateWithLifecycle()
    val viewLabel = stringResource(CoreR.string.snackbar_view)

    val snackbarHostState = remember { SnackbarHostState() }
    var batchDialogVisible by remember { mutableStateOf(false) }

    LaunchedEffect(true) {
        viewModel.loadShow(showId = showId)
        detailDownloadViewModel.refresh()
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

    val downloadTargets = state.downloadTargets
    val showDownloadState =
        DetailDownloadRules.containerState(
            itemIds = downloadTargets?.map { episode -> episode.id }.orEmpty(),
            downloaded = downloadSnapshot.downloadedIds,
            queued = downloadSnapshot.queuedIds,
        )

    // 整剧全部已在库 / 队列：不进确认框，直接给三态 Snackbar（用户 2026-10-03 口径）。
    LaunchedEffect(downloadTargets, batchDialogVisible) {
        val targets = downloadTargets ?: return@LaunchedEffect
        if (!batchDialogVisible) return@LaunchedEffect
        val selection =
            DetailDownloadRules.selectBatch(
                itemIds = targets.map { episode -> episode.id },
                downloaded = downloadSnapshot.downloadedIds,
                queued = downloadSnapshot.queuedIds,
                limit = null,
            )
        if (selection.selected.isEmpty()) {
            batchDialogVisible = false
            detailDownloadViewModel.reportSkipped(selection)
        }
    }

    ShowScreenLayout(
        state = state,
        downloadState = showDownloadState,
        downloadBadges = downloadBadges,
        downloadBusy = state.downloadTargetsLoading,
        snackbarHostState = snackbarHostState,
        onDownloadClick = {
            if (state.downloadTargets == null) {
                viewModel.onAction(ShowAction.LoadDownloadTargets)
            }
            batchDialogVisible = true
        },
        onAction = { action ->
            when (action) {
                is ShowAction.Play -> {
                    val intent = Intent(context, PlayerActivity::class.java)
                    intent.putExtra("itemId", showId.toString())
                    intent.putExtra("itemKind", BaseItemKind.SERIES.serialName)
                    context.startActivity(intent)
                }
                is ShowAction.PlayTrailer -> {
                    try {
                        uriHandler.openUri(action.trailer)
                    } catch (e: IllegalArgumentException) {
                        Toast.makeText(context, e.localizedMessage, Toast.LENGTH_SHORT).show()
                    }
                }
                is ShowAction.OnBackClick -> navigateBack()
                is ShowAction.OnHomeClick -> navigateHome()
                is ShowAction.NavigateToItem -> navigateToItem(action.item)
                is ShowAction.NavigateToPerson -> navigateToPerson(action.personId)
                else -> Unit
            }
            viewModel.onAction(action)
        },
    )

    if (batchDialogVisible) {
        BatchDownloadDialog(
            title = stringResource(CoreR.string.detail_download_batch_title_show),
            episodes = state.downloadTargets,
            downloadedIds = downloadSnapshot.downloadedIds,
            queuedIds = downloadSnapshot.queuedIds,
            isLoading = state.downloadTargetsLoading && state.downloadTargets == null,
            loadFailed = state.downloadTargetsError != null && state.downloadTargets == null,
            onRetryLoad = { viewModel.onAction(ShowAction.LoadDownloadTargets) },
            onConfirm = { items ->
                detailDownloadViewModel.enqueueBatch(items)
                batchDialogVisible = false
            },
            onDismiss = { batchDialogVisible = false },
        )
    }
}

/** 剧集详情（Lumen）：与电影详情同一套头图 / 标题 / 信息表语言，"接下来"与"季"作为两条走廊。 */
@Composable
private fun ShowScreenLayout(
    state: ShowState,
    downloadState: DetailDownloadState,
    downloadBadges: Map<UUID, DownloadBadgeInfo> = emptyMap(),
    downloadBusy: Boolean,
    snackbarHostState: SnackbarHostState,
    onDownloadClick: () -> Unit,
    onAction: (ShowAction) -> Unit,
) {
    val safePadding = rememberSafePadding()
    val gutter = rememberPageGutter()

    val paddingStart = safePadding.start + gutter
    val paddingEnd = safePadding.end + gutter
    val paddingBottom = safePadding.bottom + gutter

    val expanded =
        currentWindowAdaptiveInfo()
            .windowSizeClass
            .isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND)

    val scrollState = rememberScrollState()

    ProvideLumen {
        Box(modifier = Modifier.fillMaxSize()) {
            state.show?.let { show ->
                Column(modifier = Modifier.fillMaxWidth().verticalScroll(scrollState)) {
                    DetailHero(
                        item = show,
                        scrollState = scrollState,
                        eyebrow = show.detailEyebrow(extra = null),
                        title = show.name,
                        originalTitle = show.originalTitle,
                        meta = show.metaLine(),
                        downloadBadge = downloadBadges[show.id] ?: downloadBadgeInfo(downloadState),
                    ) {
                        ItemButtonsBar(
                            item = show,
                            onPlayClick = { startFromBeginning ->
                                onAction(ShowAction.Play(startFromBeginning = startFromBeginning))
                            },
                            onMarkAsPlayedClick = {
                                when (show.played) {
                                    true -> onAction(ShowAction.UnmarkAsPlayed)
                                    false -> onAction(ShowAction.MarkAsPlayed)
                                }
                            },
                            onMarkAsFavoriteClick = {
                                when (show.favorite) {
                                    true -> onAction(ShowAction.UnmarkAsFavorite)
                                    false -> onAction(ShowAction.MarkAsFavorite)
                                }
                            },
                            onTrailerClick = { uri -> onAction(ShowAction.PlayTrailer(uri)) },
                            onDownloadClick = { onDownloadClick() },
                            onDownloadCancelClick = {},
                            onDownloadDeleteClick = {},
                            modifier = Modifier.fillMaxWidth(),
                            canPlay = state.seasons.isNotEmpty(),
                            downloadState = downloadState,
                            downloadBusy = downloadBusy,
                            storageSelectionEnabled = false,
                        )
                    }

                    Column(modifier = Modifier.padding(start = paddingStart, end = paddingEnd)) {
                        Spacer(Modifier.height(CinefinSpacing.Space8))
                        if (expanded) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(CinefinSpacing.Space10)
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    OverviewText(text = show.overview, maxCollapsedLines = 6)
                                }
                                LumenInfoTable(
                                    rows = showInfoRows(show = show, state = state),
                                    modifier = Modifier.width(360.dp),
                                )
                            }
                        } else {
                            OverviewText(text = show.overview, maxCollapsedLines = 4)
                            Spacer(Modifier.height(CinefinSpacing.Space6))
                            InfoText(
                                genres = show.genres,
                                director = state.director,
                                writers = state.writers,
                            )
                        }
                        Spacer(Modifier.height(CinefinSpacing.Space8))
                    }

                    state.nextUp?.let { nextUp ->
                        Column(
                            modifier = Modifier.padding(start = paddingStart, end = paddingEnd)
                        ) {
                            SectionHeader(title = stringResource(CoreR.string.next_up))
                            Spacer(Modifier.height(CinefinSpacing.Space4))
                            Column(
                                modifier =
                                    Modifier.widthIn(max = 420.dp)
                                        .clip(CinefinShapes.Md)
                                        .clickable { onAction(ShowAction.NavigateToItem(nextUp)) }
                            ) {
                                ItemPoster(
                                    item = nextUp,
                                    direction = Direction.HORIZONTAL,
                                    modifier = Modifier.clip(CinefinShapes.Md),
                                )
                                Spacer(Modifier.height(CinefinSpacing.Space3))
                                Text(
                                    text =
                                        stringResource(
                                            id = CoreR.string.episode_name_extended,
                                            nextUp.parentIndexNumber,
                                            nextUp.indexNumber,
                                            nextUp.name,
                                        ),
                                    style = CinefinType.TitleSmall,
                                    color = LocalCinefinColors.current.onSurface,
                                )
                            }
                            Spacer(Modifier.height(CinefinSpacing.Space8))
                        }
                    }

                    if (state.seasons.isNotEmpty()) {
                        Column(
                            modifier = Modifier.padding(start = paddingStart, end = paddingEnd)
                        ) {
                            SectionHeader(title = stringResource(CoreR.string.seasons))
                        }
                        Spacer(Modifier.height(CinefinSpacing.Space4))
                        LazyRow(
                            contentPadding = PaddingValues(start = paddingStart, end = paddingEnd),
                            horizontalArrangement = Arrangement.spacedBy(CinefinSpacing.Space6),
                        ) {
                            items(items = state.seasons, key = { item -> item.id }) { season ->
                                ItemCard(
                                    item = season,
                                    direction = Direction.VERTICAL,
                                    onClick = { onAction(ShowAction.NavigateToItem(season)) },
                                    // 季卡统一尺寸（W11 反馈⑨）：横排行是无界宽度，必须显式给宽度，
                                    // 否则每张卡按各自海报的固有尺寸排布 → 「一大一小」；给死后只保留横向滑动
                                    width = rememberSeasonCardWidth(),
                                )
                            }
                        }
                        Spacer(Modifier.height(CinefinSpacing.Space8))
                    }

                    if (state.actors.isNotEmpty()) {
                        ActorsRow(
                            actors = state.actors,
                            onActorClick = { personId ->
                                onAction(ShowAction.NavigateToPerson(personId))
                            },
                            contentPadding = PaddingValues(start = paddingStart, end = paddingEnd),
                        )
                    }
                    Spacer(Modifier.height(paddingBottom))
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
                onBackClick = { onAction(ShowAction.OnBackClick) },
                onHomeClick = { onAction(ShowAction.OnHomeClick) },
            )
            CinefinSnackbarHost(
                hostState = snackbarHostState,
                modifier =
                    Modifier.align(Alignment.BottomCenter).padding(bottom = CinefinSpacing.Space6),
            )
        }
    }
}

/**
 * 季卡宽度（W11 反馈⑨）：一列季卡**共用同一个宽度**，靠横向滑动观看，不再一大一小。
 *
 * 抽成纯函数便于单测——只要所有季卡取同一个返回值，尺寸就不可能不一致。
 */
internal fun seasonCardWidthDp(screenWidthDp: Float): Float =
    when {
        screenWidthDp >= 1400f -> 208f
        screenWidthDp >= 1000f -> 184f
        screenWidthDp >= 700f -> 168f
        else -> 150f
    }

/** 读取当前窗口宽度换算成季卡宽度（与 [seasonCardWidthDp] 同源）。 */
@Composable
private fun rememberSeasonCardWidth(): Dp =
    seasonCardWidthDp(LocalConfiguration.current.screenWidthDp.toFloat()).dp

/** 剧集制作信息表：季数 / 类型 / 导演 / 编剧（年份与类型首项已经在头图眉标里出现过，这里不重复）。 */
@Composable
private fun showInfoRows(show: FindroidShow, state: ShowState): List<Pair<String, String>> {
    return buildList {
        if (state.seasons.isNotEmpty()) {
            add(stringResource(CoreR.string.seasons) to state.seasons.size.toString())
        }
        if (show.genres.isNotEmpty()) {
            add(stringResource(CoreR.string.genres) to show.genres.joinToString(" / "))
        }
        state.director?.let { add(stringResource(CoreR.string.director) to it.name) }
        if (state.writers.isNotEmpty()) {
            add(
                stringResource(CoreR.string.writers) to
                    state.writers.joinToString(" / ") { writer -> writer.name }
            )
        }
    }
}

@PreviewScreenSizes
@Composable
private fun ShowScreenLayoutPreview() {
    CinefinTheme {
        ShowScreenLayout(
            state = ShowState(show = dummyShow),
            downloadState = DetailDownloadState.NOT_DOWNLOADED,
            downloadBusy = false,
            snackbarHostState = remember { SnackbarHostState() },
            onDownloadClick = {},
            onAction = {},
        )
    }
}
