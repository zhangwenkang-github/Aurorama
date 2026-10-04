package com.zhangwenkang.cinefin.music.presentation

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import coil3.compose.AsyncImage
import com.zhangwenkang.cinefin.core.R as CoreR
import com.zhangwenkang.cinefin.core.presentation.components.CinefinBackToDefaultChip
import com.zhangwenkang.cinefin.core.presentation.components.CinefinBatchBar
import com.zhangwenkang.cinefin.core.presentation.components.CinefinButton
import com.zhangwenkang.cinefin.core.presentation.components.CinefinButtonSize
import com.zhangwenkang.cinefin.core.presentation.components.CinefinButtonVariant
import com.zhangwenkang.cinefin.core.presentation.components.CinefinEmptyState
import com.zhangwenkang.cinefin.core.presentation.components.CinefinFilterChip
import com.zhangwenkang.cinefin.core.presentation.components.CinefinIconButton
import com.zhangwenkang.cinefin.core.presentation.components.CinefinListRow
import com.zhangwenkang.cinefin.core.presentation.components.CinefinPageTopBar
import com.zhangwenkang.cinefin.core.presentation.components.CinefinSegmentedControl
import com.zhangwenkang.cinefin.core.presentation.components.CinefinSleepTimerOptions
import com.zhangwenkang.cinefin.core.presentation.components.CinefinSnackbarHost
import com.zhangwenkang.cinefin.core.presentation.components.DownloadSnackbarDuration
import com.zhangwenkang.cinefin.core.presentation.components.LibrarySelectorChip
import com.zhangwenkang.cinefin.core.presentation.components.LibrarySelectorOption
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinShapes
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinSpacing
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinTheme
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinType
import com.zhangwenkang.cinefin.core.presentation.theme.ContentDomain
import com.zhangwenkang.cinefin.core.presentation.theme.LocalCinefinColors
import com.zhangwenkang.cinefin.core.presentation.theme.LocalMediaColors
import com.zhangwenkang.cinefin.core.selection.MultiSelectState
import com.zhangwenkang.cinefin.music.R
import com.zhangwenkang.cinefin.music.data.MusicAlbum
import com.zhangwenkang.cinefin.music.data.MusicArtist
import com.zhangwenkang.cinefin.music.data.MusicItemSourceFilter
import com.zhangwenkang.cinefin.music.data.MusicPlaylist
import com.zhangwenkang.cinefin.music.data.MusicSong
import com.zhangwenkang.cinefin.player.core.domain.models.MusicQueue
import com.zhangwenkang.cinefin.player.core.domain.models.SleepTimerSpec
import com.zhangwenkang.cinefin.player.local.domain.SleepTimerController
import com.zhangwenkang.cinefin.utils.DownloadTaskStatus
import java.util.UUID
import kotlin.math.roundToInt

/** 队列行高（拖动换算基准）：面板内所有行等高，拖动位移才能按整数行对齐。 */
private val QueueRowHeight = 72.dp

/**
 * 音乐模式入口（W1 R2 最小闭环，W2 R2 扩到四维浏览 + 队列面板，W3 R3 接入 Prism）。
 *
 * 浏览：专辑 / 艺术家 / 歌曲 / 歌单四个分段（设计系统 §8.2），点歌以当前列表整份入队； 队列：底栏「队列」按钮打开面板，支持点歌跳转、长按拖拽排序、移除与「下一首播放」；
 * 通知栏与锁屏由播放会话服务承载（music → Media3 单 MediaSession）。
 *
 * 路由注册由 R3 在 `NavigationRoot.kt` 统一提交（见 [MusicModeRoute]）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicModeScreen(
    modifier: Modifier = Modifier,
    onOpenDrawer: (() -> Unit)? = null,
    /**
     * W53 Bug B1：从侧栏 / 抽屉点**具体音乐库**进入时带上库名——顶栏标题显示该库（如「音乐测试」）； null = 音乐 Tab
     * 入口，沿用「音乐」标题。要加载哪个库由路由参数（`libraryId`）经 SavedStateHandle 传给 ViewModel，本参数只负责标题展示。
     */
    libraryName: String? = null,
    /** 临时库视图（W53 追加）：顶栏「类型 · 数量」的类型前缀（如「音乐库」；null = 不带前缀）。 */
    libraryTypeLabel: String? = null,
    /** 临时库视图的「返回默认 ×」/ 系统返回键动作（回默认音乐库）；null = 音乐 Tab 默认视图。 */
    onExitTemporaryLibrary: (() -> Unit)? = null,
    /** W56：顶层「音乐」图标再点 = 回音乐主页——数值递增触发一次收起（全屏播放 / 歌词页 + 页内详情）。 */
    reselectSignal: Int = 0,
    /** W68：外部请求打开全屏播放（锁屏 / 通知点击音乐条目）；数值递增触发一次。 */
    openNowPlayingSignal: Int = 0,
    /** W56：把「音乐页内二级层（全屏播放 / 歌词页 / 专辑·艺术家·歌单详情）是否打开」回报给导航层（决定再点图标是只收二级层还是不重复导航）。 */
    onInnerPageOpenChange: ((Boolean) -> Unit)? = null,
    /** W60b：下载反馈 Snackbar「查看」→ 下载页。 */
    onOpenDownloads: () -> Unit = {},
    viewModel: MusicModeViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val queue by viewModel.queue.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()
    val isRestored by viewModel.isRestored.collectAsState()
    val positionMs by viewModel.positionMs.collectAsState()
    val durationMs by viewModel.durationMs.collectAsState()
    val playMode by viewModel.playMode.collectAsState()
    val lyricsState by viewModel.lyricsState.collectAsState()
    val lyricsEditorState by viewModel.lyricsEditorState.collectAsState()
    val sleepState by viewModel.sleepTimerState.collectAsState()
    val lyricsOverlayState by viewModel.lyricsOverlayState.collectAsState()
    val songDownloadState by viewModel.downloadState.collectAsState()
    val batchSelection by viewModel.batchSelection.collectAsState()
    var queueSheetOpen by rememberSaveable { mutableStateOf(false) }
    var sleepSheetOpen by rememberSaveable { mutableStateOf(false) }
    var effectsSheetOpen by rememberSaveable { mutableStateOf(false) }
    var nowPlayingOpen by rememberSaveable { mutableStateOf(false) }
    // W68：外部打开请求先挂起，等队列就绪（冷启动恢复）后再展开
    var pendingOpenNowPlaying by remember { mutableStateOf(false) }
    var pendingBatchDelete by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    // W60b：下载反馈统一（歌曲 / 专辑 / 批量）——「已加入下载队列 · N 首」+「查看」跳下载页。
    LaunchedEffect(viewModel) {
        viewModel.downloadQueued.collect { count ->
            val result =
                snackbarHostState.showSnackbar(
                    message = "已加入下载队列 · $count 首",
                    actionLabel = "查看",
                    duration = DownloadSnackbarDuration,
                )
            if (result == SnackbarResult.ActionPerformed) onOpenDownloads()
        }
    }
    val effectsEqualizerEnabled by viewModel.effectsEqualizerEnabled.collectAsState()
    val effectsEqualizerPreset by viewModel.effectsEqualizerPreset.collectAsState()
    val effectsEqualizerBands by viewModel.effectsEqualizerBands.collectAsState()
    val effectsReplayGainMode by viewModel.effectsReplayGainMode.collectAsState()
    val effectsReplayGainLabel by viewModel.effectsReplayGainLabel.collectAsState()
    val effectsCrossfadeSeconds by viewModel.effectsCrossfadeSeconds.collectAsState()
    val effectsHasCurrentTrack by viewModel.effectsHasCurrentTrack.collectAsState()
    val effectsOverrideTrackDb by viewModel.effectsOverrideTrackDb.collectAsState()
    val effectsOverrideAlbumDb by viewModel.effectsOverrideAlbumDb.collectAsState()
    var overlayGuideOpen by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    // 权限页返回：复核权限，拿到了就直接开启（用户在引导弹窗里点过「去授权」）
    val overlayPermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            viewModel.refreshLyricsOverlayPermission()
            if (Settings.canDrawOverlays(context)) viewModel.enableLyricsOverlay()
        }

    // 队列被清空（停止播放）时自动收起队列面板与全屏播放页
    LaunchedEffect(queue) {
        if (queue == null) {
            queueSheetOpen = false
            nowPlayingOpen = false
        }
    }

    // W56：顶层「音乐」图标再点 = 回音乐主页——关闭全屏播放 / 歌词覆盖层与专辑等页内详情。
    LaunchedEffect(reselectSignal) {
        if (reselectSignal > 0 && (nowPlayingOpen || state.detail != null)) {
            nowPlayingOpen = false
            viewModel.closeDetail()
        }
    }

    // W68：锁屏 / 通知点击音乐条目 → 展开全屏播放（与点迷你条一致）。队列恢复是异步的（冷启动），
    // 等队列就绪后再展开；一次请求只兑现一次。
    LaunchedEffect(openNowPlayingSignal) {
        if (openNowPlayingSignal > 0) pendingOpenNowPlaying = true
    }
    LaunchedEffect(pendingOpenNowPlaying, queue) {
        if (pendingOpenNowPlaying && queue != null) {
            nowPlayingOpen = true
            pendingOpenNowPlaying = false
        }
    }
    LaunchedEffect(nowPlayingOpen, state.detail) {
        onInnerPageOpenChange?.invoke(nowPlayingOpen || state.detail != null)
    }

    // 系统返回键与左上角返回一致（W3-R3b 缺陷 1）：详情（专辑 / 艺术家 / 歌单）内先回音乐主界面，
    // 而不是直接退回首页；不在详情时交给 NavHost 正常返回。
    BackHandler(enabled = state.detail != null) { viewModel.closeDetail() }
    // 临时库视图（W53 追加）：没有详情 / 面板打开时，返回键先退出临时库（回默认音乐库）。
    // 详情打开时本 handler 禁用 → 详情返回语义优先；两者不会互相抢（BackHandler 为 LIFO）。
    BackHandler(
        enabled = onExitTemporaryLibrary != null && state.detail == null,
        onBack = { onExitTemporaryLibrary?.invoke() },
    )
    // W58：长按进入多选后，任何子层 / 详情状态变化都不自动退出（用户用顶栏 × 或全不选退出）。
    BackHandler(enabled = batchSelection.selectionMode) { viewModel.onBatchExit() }

    CinefinTheme(domain = ContentDomain.Music, surfaceBackground = false) {
        val colors = LocalCinefinColors.current
        Box(modifier = modifier.fillMaxSize().background(colors.surface)) {
            Column(modifier = Modifier.fillMaxSize()) {
                MusicHeader(
                    state = state,
                    sleepState = sleepState,
                    batchSelection = batchSelection,
                    libraryName = libraryName,
                    libraryTypeLabel = libraryTypeLabel,
                    onExitTemporaryLibrary = onExitTemporaryLibrary,
                    onBack = viewModel::closeDetail,
                    onOpenDrawer = onOpenDrawer,
                    onOpenFavorites = viewModel::openFavorites,
                    onOpenRecent = viewModel::openRecent,
                    onOpenSleep = { sleepSheetOpen = true },
                    onBatchSelectAll = viewModel::onBatchSelectAll,
                    onBatchSelectNone = viewModel::onBatchSelectNone,
                    onBatchExit = viewModel::onBatchExit,
                )
                // W36：离线模式提示——曲库只含本机已下载曲目，点击即本地文件起播。
                if (state.offline) {
                    OfflineMusicNotice(songCount = state.songs.size)
                }
                if (state.detail == null) {
                    // W66b：库选择 chip 移到 Tab 行右侧（紧凑、只显示当前库名）；顶栏恢复标题 + 三图标键。
                    MusicTabsRow(
                        selected = state.tab,
                        onSelect = viewModel::selectTab,
                        libraryLabel = state.selectedMusicLibraryName,
                        libraryOptions = state.musicLibraries,
                        librarySelectedId = state.selectedMusicLibraryId,
                        showLibrarySelector =
                            onExitTemporaryLibrary == null && state.musicLibraries.size >= 3,
                        onSelectMusicLibrary = viewModel::selectMusicLibrary,
                    )
                    // W66：来源筛选 + 播放全部 / 随机并入一行；批量多选态整行隐藏（批量条接管）。
                    if (!batchSelection.selectionMode) {
                        MusicToolbarRow(
                            state = state,
                            onSelectSource = viewModel::setSourceFilter,
                            onToggleBadge = viewModel::setShowSourceBadge,
                            onPlayAll = { viewModel.playAll() },
                            onShuffleAll = { viewModel.playAll(shuffle = true) },
                        )
                    }
                    Spacer(modifier = Modifier.height(CinefinSpacing.Space3))
                }

                // W39：四个 tab 共用真实下拉刷新；离线模式 refresh() 只重读本机索引，不发服务器请求。
                PullToRefreshBox(
                    isRefreshing = state.refreshing,
                    onRefresh = viewModel::refresh,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                ) {
                    val error = state.errorMessage
                    when {
                        error != null ->
                            ErrorPane(
                                title = state.errorTitle ?: "曲库加载失败",
                                message = error,
                                onRetry = viewModel::refresh,
                                onDismiss = viewModel::dismissError,
                            )
                        state.loading ->
                            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                        state.detail != null ->
                            DetailPane(
                                detail = state.detail!!,
                                currentItemId = queue?.currentItem?.itemId,
                                downloadState = songDownloadState,
                                showSourceBadge = state.showSourceBadge,
                                batchSelection = batchSelection,
                                onSongClick = viewModel::playSong,
                                onPlayNext = viewModel::playNext,
                                onToggleFavorite = viewModel::toggleFavorite,
                                onToggleDownload = viewModel::toggleSongDownload,
                                onBatchLongPress = viewModel::onBatchLongPress,
                                onBatchToggle = viewModel::onBatchToggle,
                                onDownloadAlbum = viewModel::downloadAlbum,
                                albumMissingCount = viewModel::albumDownloadMissingCount,
                            )
                        else ->
                            LibraryPane(
                                state = state,
                                currentItemId = queue?.currentItem?.itemId,
                                downloadState = songDownloadState,
                                showSourceBadge = state.showSourceBadge,
                                batchSelection = batchSelection,
                                onAlbumClick = viewModel::openAlbum,
                                onArtistClick = viewModel::openArtist,
                                onPlaylistClick = viewModel::openPlaylist,
                                onSongClick = viewModel::playSong,
                                onPlayNext = viewModel::playNext,
                                onToggleFavorite = viewModel::toggleFavorite,
                                onToggleDownload = viewModel::toggleSongDownload,
                                onBatchLongPress = viewModel::onBatchLongPress,
                                onBatchToggle = viewModel::onBatchToggle,
                                onAlbumBatchLongPress = viewModel::onAlbumBatchLongPress,
                                onAlbumBatchToggle = viewModel::onAlbumBatchToggle,
                            )
                    }
                }

                if (batchSelection.selectionMode) {
                    MusicBatchActionBar(
                        selectedCount = batchSelection.selectedCount,
                        isPlaylistDetail = state.detail is MusicDetail.Playlist,
                        albumsOnly = state.detail == null && state.tab == MusicTab.ALBUMS,
                        isEnabled = viewModel::batchEnabled,
                        onPlay = viewModel::playSelected,
                        onDownload = viewModel::downloadSelected,
                        onFavorite = viewModel::favoriteSelected,
                        onDelete = { pendingBatchDelete = true },
                        onRemoveFromPlaylist = viewModel::removeSelectedFromPlaylist,
                    )
                } else {
                    NowPlayingBar(
                        queue = queue,
                        isPlaying = isPlaying,
                        isRestored = isRestored,
                        positionMs = positionMs,
                        durationMs = durationMs,
                        fallbackDurationMs =
                            viewModel
                                .songMeta(queue?.currentItem?.itemId)
                                ?.runtimeTicks
                                ?.div(TICKS_PER_MS) ?: 0L,
                        sleepState = sleepState,
                        onOpenNowPlaying = { nowPlayingOpen = true },
                        onPlayPause = viewModel::togglePlayPause,
                        onNext = viewModel::skipToNext,
                        onOpenLyrics = viewModel::openLyrics,
                        onClose = viewModel::dismissNowPlayingBar,
                    )
                }

                CinefinSnackbarHost(
                    hostState = snackbarHostState,
                    modifier = Modifier.padding(horizontal = CinefinSpacing.Space4),
                )
            }

            val currentQueue = queue
            if (nowPlayingOpen && currentQueue != null) {
                MusicNowPlayingScreen(
                    queue = currentQueue,
                    lyricsState = lyricsState,
                    isPlaying = isPlaying,
                    isRestored = isRestored,
                    positionMs = positionMs,
                    durationMs = durationMs,
                    meta = viewModel.songMeta(currentQueue.currentItem?.itemId),
                    playMode = playMode,
                    lyricsOverlayEnabled = lyricsOverlayState.enabled,
                    onClose = { nowPlayingOpen = false },
                    onPlayPause = viewModel::togglePlayPause,
                    onPrevious = viewModel::skipToPrevious,
                    onNext = viewModel::skipToNext,
                    onSeek = viewModel::seekTo,
                    onCyclePlayMode = viewModel::cyclePlayMode,
                    onToggleLyricsOverlay = {
                        when {
                            lyricsOverlayState.enabled -> viewModel.toggleLyricsOverlay()
                            Settings.canDrawOverlays(context) -> viewModel.enableLyricsOverlay()
                            else -> overlayGuideOpen = true
                        }
                    },
                    onOpenEffects = { effectsSheetOpen = true },
                    onOpenQueue = { queueSheetOpen = true },
                    onToggleFavorite = viewModel::toggleFavorite,
                    onSelectLyricsLanguage = viewModel::selectLyricsLanguage,
                    onToggleLyricsBilingual = viewModel::toggleLyricsBilingual,
                    onToggleLyricsFollow = viewModel::toggleLyricsFollow,
                    onSeekLyricLine = viewModel::seekToLyricLine,
                )
            }
        }

        if (lyricsState.open && queue != null) {
            LyricsSheet(
                state = lyricsState,
                onDismiss = viewModel::closeLyrics,
                onEditLyrics = viewModel::openLyricsEditor,
                onSelectLanguage = viewModel::selectLyricsLanguage,
                onToggleBilingual = viewModel::toggleLyricsBilingual,
                onToggleFollow = viewModel::toggleLyricsFollow,
                onLineClick = viewModel::seekToLyricLine,
            )
        }

        // W25-MUSIC：本机歌词编辑 / 导入 LRC / 清除覆盖
        if (lyricsEditorState.open) {
            LyricsEditorDialog(
                state = lyricsEditorState,
                onDismiss = viewModel::closeLyricsEditor,
                onAddLine = viewModel::addLyricsEditorLine,
                onRemoveLine = viewModel::removeLyricsEditorLine,
                onSelectLine = viewModel::selectLyricsEditorLine,
                onLineTextChange = viewModel::updateLyricsEditorLineText,
                onLineTimeChange = viewModel::updateLyricsEditorLineTime,
                onShiftAllLines = viewModel::shiftLyricsEditorLines,
                onShiftSelectedLine = viewModel::shiftSelectedLyricsEditorLine,
                onOffsetTextChange = viewModel::updateLyricsEditorOffsetText,
                onStepTextChange = viewModel::updateLyricsEditorStepText,
                onApplyOffsetText = viewModel::applyLyricsEditorOffsetText,
                onApplyStepText = viewModel::applyLyricsEditorStepText,
                onSave = viewModel::saveLyricsEditor,
                onClearOverride = viewModel::clearLyricsOverride,
                onImportText = viewModel::importLyricsOverride,
                onMessage = viewModel::showLyricsEditorMessage,
            )
        }

        val currentQueue = queue
        if (queueSheetOpen && currentQueue != null) {
            QueueSheet(
                queue = currentQueue,
                onDismiss = { queueSheetOpen = false },
                onJump = viewModel::jumpToQueueItem,
                onMove = viewModel::moveQueueItem,
                onRemove = viewModel::removeQueueItem,
            )
        }

        if (pendingBatchDelete) {
            MusicBatchDeleteDialog(
                selectedCount = batchSelection.selectedCount,
                onConfirm = {
                    viewModel.deleteSelected()
                    pendingBatchDelete = false
                },
                onDismiss = { pendingBatchDelete = false },
            )
        }

        if (sleepSheetOpen) {
            SleepTimerSheet(
                state = sleepState,
                onSelect = { minutes ->
                    viewModel.selectSleepTimer(minutes)
                    sleepSheetOpen = false
                },
                onDismiss = { sleepSheetOpen = false },
            )
        }

        // W30-MUSIC-FX：音效面板（EQ / ReplayGain / 淡入淡出），入口在全屏播放页功能行。
        if (effectsSheetOpen) {
            MusicEffectsSheet(
                equalizerEnabled = effectsEqualizerEnabled,
                preset = effectsEqualizerPreset,
                bands = effectsEqualizerBands,
                replayGainMode = effectsReplayGainMode,
                replayGainLabel = effectsReplayGainLabel,
                hasCurrentTrack = effectsHasCurrentTrack,
                overrideTrackDb = effectsOverrideTrackDb,
                overrideAlbumDb = effectsOverrideAlbumDb,
                crossfadeSeconds = effectsCrossfadeSeconds,
                onDismiss = { effectsSheetOpen = false },
                onToggleEqualizer = viewModel::setEqualizerEnabled,
                onSelectPreset = viewModel::selectEqualizerPreset,
                onPreviewBand = viewModel::previewEqualizerBand,
                onCommitBands = viewModel::commitEqualizerBands,
                onSelectReplayGain = viewModel::selectReplayGainMode,
                onPreviewOverrideTrack = viewModel::previewReplayGainOverrideTrack,
                onPreviewOverrideAlbum = viewModel::previewReplayGainOverrideAlbum,
                onCommitOverride = viewModel::commitReplayGainOverride,
                onClearOverride = viewModel::clearReplayGainOverride,
                onSelectCrossfade = viewModel::selectCrossfadeSeconds,
            )
        }

        if (overlayGuideOpen) {
            LyricsOverlayPermissionDialog(
                onConfirm = {
                    overlayGuideOpen = false
                    overlayPermissionLauncher.launch(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${context.packageName}"),
                        )
                    )
                },
                onDismiss = { overlayGuideOpen = false },
            )
        }
    }
}

@Composable
private fun MusicHeader(
    state: MusicModeViewModel.UiState,
    sleepState: SleepTimerController.State,
    batchSelection: MultiSelectState,
    /** W53 Bug B1：侧栏点具体音乐库时的顶栏标题（null = 「音乐」）。 */
    libraryName: String?,
    /** W53 追加：临时库视图顶栏的类型前缀（如「音乐库」）。 */
    libraryTypeLabel: String?,
    /** W53 追加：临时库视图的「返回默认 ×」；null = 默认视图不显示。 */
    onExitTemporaryLibrary: (() -> Unit)?,
    onBack: () -> Unit,
    onOpenDrawer: (() -> Unit)?,
    onOpenFavorites: () -> Unit,
    onOpenRecent: () -> Unit,
    onOpenSleep: () -> Unit,
    onBatchSelectAll: () -> Unit,
    onBatchSelectNone: () -> Unit,
    onBatchExit: () -> Unit,
) {
    val detail = state.detail
    val media = LocalMediaColors.current
    // W59：专辑 Tab 的多选计数以专辑卡数为准（与 VM 的专辑多选作用域一致）。
    val visibleSongCount =
        when {
            detail != null -> detail.songs.size
            state.tab == MusicTab.ALBUMS -> state.albums.size
            else -> state.songs.size
        }
    val batchMode = batchSelection.selectionMode
    // W8-R3：与媒体库 / 书架共用 `CinefinPageTopBar`（56dp + statusBarsPadding + 左侧 ic_menu「打开侧栏」），
    // 修掉旧版 72dp 无 inset 导致的"按钮被状态栏压住"。详情（专辑 / 艺术家 / 歌单）改回返回键 + 详情标题。
    CinefinPageTopBar(
        title =
            if (batchMode) "已选 ${batchSelection.selectedCount} 项"
            else
                detail?.title
                    ?: libraryName?.takeIf { it.isNotBlank() }
                    ?: state.selectedMusicLibraryName?.takeIf { it.isNotBlank() }
                    ?: "音乐",
        subtitle =
            when {
                batchMode -> null
                detail != null -> "共 ${detail.songs.size} 首曲目"
                else -> {
                    val count =
                        when (state.tab) {
                            MusicTab.ALBUMS -> state.albums.size
                            MusicTab.ARTISTS -> state.artists.size
                            MusicTab.SONGS -> state.songs.size
                            MusicTab.PLAYLISTS -> state.playlists.size
                        }
                    // W39：筛选「本地 / 服务器」时计数副题补来源前缀（口径与列表一致）。
                    listOfNotNull(
                            libraryTypeLabel,
                            musicLibrarySubtitle(
                                tab = state.tab,
                                filter = state.sourceFilter,
                                count = count,
                                offline = state.offline,
                            ),
                        )
                        .joinToString(" · ")
                }
            },
        onOpenDrawer = onOpenDrawer,
        onBack = if (detail != null && !batchMode) onBack else null,
        actions = {
            if (batchMode) {
                val allSelected =
                    visibleSongCount > 0 && batchSelection.selectedCount >= visibleSongCount
                CinefinButton(
                    text = if (allSelected) "取消全选" else "全选",
                    onClick = { if (allSelected) onBatchSelectNone() else onBatchSelectAll() },
                    size = CinefinButtonSize.Small,
                    variant = CinefinButtonVariant.Text,
                )
                CinefinIconButton(onClick = onBatchExit) { tint ->
                    Icon(
                        painter = painterResource(CoreR.drawable.ic_close),
                        contentDescription = "退出多选",
                        tint = tint,
                        modifier = Modifier.size(20.dp),
                    )
                }
                return@CinefinPageTopBar
            }
            if (onExitTemporaryLibrary != null && detail == null) {
                CinefinBackToDefaultChip(onClick = onExitTemporaryLibrary)
            }
            CinefinIconButton(onClick = onOpenFavorites) { tint ->
                Icon(
                    painter =
                        painterResource(
                            if (detail is MusicDetail.Favorites) {
                                CoreR.drawable.ic_bookmark_filled
                            } else {
                                CoreR.drawable.ic_bookmark
                            }
                        ),
                    contentDescription = "收藏",
                    tint = tint,
                    modifier = Modifier.size(22.dp),
                )
            }
            CinefinIconButton(onClick = onOpenRecent) { tint ->
                Icon(
                    painter = painterResource(R.drawable.ic_music_recent),
                    contentDescription = "最近播放",
                    tint = tint,
                    modifier = Modifier.size(22.dp),
                )
            }
            CinefinIconButton(onClick = onOpenSleep) { tint ->
                Icon(
                    painter = painterResource(R.drawable.ic_music_sleep),
                    contentDescription = "睡眠定时",
                    tint = if (sleepState.active) media.bright else tint,
                    modifier = Modifier.size(22.dp),
                )
            }
        },
    )
}

@Composable
private fun MusicTabsRow(
    selected: MusicTab,
    onSelect: (MusicTab) -> Unit,
    libraryLabel: String?,
    libraryOptions: List<LibrarySelectorOption>,
    librarySelectedId: UUID?,
    showLibrarySelector: Boolean,
    onSelectMusicLibrary: (UUID?) -> Unit,
) {
    Row(
        modifier =
            Modifier.padding(horizontal = CinefinSpacing.Space4)
                .widthIn(max = 640.dp)
                .fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CinefinSpacing.Space2),
    ) {
        CinefinSegmentedControl(
            items = MusicTab.entries,
            selected = selected,
            onSelect = onSelect,
            label = { tab ->
                when (tab) {
                    MusicTab.ALBUMS -> "专辑"
                    MusicTab.ARTISTS -> "艺术家"
                    MusicTab.SONGS -> "歌曲"
                    MusicTab.PLAYLISTS -> "歌单"
                }
            },
            modifier = Modifier.weight(1f),
        )
        // W66b：库选择 chip 放 Tab 行右侧（紧凑、只显示当前库名、超长省略）；单库 / 临时库视图隐藏。
        if (showLibrarySelector) {
            LibrarySelectorChip(
                label = libraryLabel ?: ALL_MUSIC_LIBRARIES_LABEL,
                options = libraryOptions,
                selectedId = librarySelectedId,
                onSelect = onSelectMusicLibrary,
                maxWidth = 132.dp,
            )
        }
    }
}

/**
 * W66：音乐页工具行（一层）——左「来源▾」下拉 chip（全部 / 服务器 / 本地；来源徽标开关收进菜单项）， 右「播放全部」主键 + 「随机」图标键（仅歌曲 Tab、列表非空时）。
 *
 * 批量多选态由调用方整行隐藏（批量条接管）；离线时不显示来源筛选（单一本地来源）。
 */
@Composable
private fun MusicToolbarRow(
    state: MusicModeViewModel.UiState,
    onSelectSource: (MusicItemSourceFilter) -> Unit,
    onToggleBadge: (Boolean) -> Unit,
    onPlayAll: () -> Unit,
    onShuffleAll: () -> Unit,
) {
    val showSource = !state.offline
    // W66b：播放全部扩展到所有 Tab（语义 = 按当前筛选与当前列表顺序播放整库曲目）。
    val showPlayAll = state.songs.isNotEmpty()
    if (!showSource && !showPlayAll) return
    Row(
        modifier =
            Modifier.padding(horizontal = CinefinSpacing.Space4)
                .widthIn(max = 640.dp)
                .fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CinefinSpacing.Space2),
    ) {
        if (showSource) {
            MusicSourceFilterMenuChip(
                filter = state.sourceFilter,
                showBadge = state.showSourceBadge,
                onSelect = onSelectSource,
                onToggleBadge = onToggleBadge,
            )
        }
        Spacer(modifier = Modifier.weight(1f))
        if (showPlayAll) {
            CinefinButton(
                text = "播放全部",
                onClick = onPlayAll,
                variant = CinefinButtonVariant.Filled,
                size = CinefinButtonSize.Small,
            )
            CinefinIconButton(onClick = onShuffleAll) { tint ->
                Icon(
                    painter = painterResource(R.drawable.ic_music_shuffle),
                    contentDescription = "随机播放",
                    tint = tint,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

/** W66：来源筛选下拉 chip（当前值常显 + ▾；菜单项 = 全部 / 服务器 / 本地 + 来源徽标开关）。 */
@Composable
private fun MusicSourceFilterMenuChip(
    filter: MusicItemSourceFilter,
    showBadge: Boolean,
    onSelect: (MusicItemSourceFilter) -> Unit,
    onToggleBadge: (Boolean) -> Unit,
) {
    val colors = LocalCinefinColors.current
    val media = LocalMediaColors.current
    var expanded by rememberSaveable { mutableStateOf(false) }
    Box {
        CinefinFilterChip(
            text = "来源 · ${filter.label}",
            selected = filter != MusicItemSourceFilter.ALL,
            compact = true,
            onClick = { expanded = true },
            icon = { tint ->
                Icon(
                    painter = painterResource(CoreR.drawable.ic_chevron_down),
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(16.dp),
                )
            },
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            shape = CinefinShapes.Sm,
            containerColor = colors.surfaceContainerHighest,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
        ) {
            MusicItemSourceFilter.entries.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = option.label,
                            style = CinefinType.BodyMedium,
                            color = colors.onSurface,
                        )
                    },
                    trailingIcon = {
                        if (option == filter) {
                            Icon(
                                painter = painterResource(CoreR.drawable.ic_check),
                                contentDescription = null,
                                tint = media.bright,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    },
                    onClick = {
                        expanded = false
                        onSelect(option)
                    },
                )
            }
            HorizontalDivider(color = colors.outline)
            DropdownMenuItem(
                text = {
                    Text(
                        text = "来源徽标",
                        style = CinefinType.BodyMedium,
                        color = colors.onSurface,
                    )
                },
                trailingIcon = {
                    if (showBadge) {
                        Icon(
                            painter = painterResource(CoreR.drawable.ic_check),
                            contentDescription = null,
                            tint = media.bright,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                },
                onClick = {
                    expanded = false
                    onToggleBadge(!showBadge)
                },
            )
        }
    }
}

@Composable
private fun LibraryPane(
    state: MusicModeViewModel.UiState,
    currentItemId: UUID?,
    downloadState: MusicModeViewModel.SongDownloadState,
    showSourceBadge: Boolean,
    batchSelection: MultiSelectState,
    onAlbumClick: (MusicAlbum) -> Unit,
    onArtistClick: (MusicArtist) -> Unit,
    onPlaylistClick: (MusicPlaylist) -> Unit,
    onSongClick: (MusicSong) -> Unit,
    onPlayNext: (MusicSong) -> Unit,
    onToggleFavorite: (MusicSong) -> Unit,
    onToggleDownload: (MusicSong) -> Unit,
    onBatchLongPress: (MusicSong) -> Unit,
    onBatchToggle: (MusicSong) -> Unit,
    onAlbumBatchLongPress: (MusicAlbum) -> Unit = {},
    onAlbumBatchToggle: (MusicAlbum) -> Unit = {},
) {
    // W39：空态按来源分支（本地 / 服务器 / 全部 / 离线），与下拉刷新提示一致。
    val emptyCopy =
        musicEmptyCopy(tab = state.tab, filter = state.sourceFilter, offline = state.offline)
    when (state.tab) {
        MusicTab.ALBUMS ->
            AlbumList(
                albums = state.albums,
                emptyCopy = emptyCopy,
                onAlbumClick = onAlbumClick,
                selectionMode = batchSelection.selectionMode,
                selectedIds = batchSelection.selectedIds,
                onAlbumLongPress = onAlbumBatchLongPress,
                onAlbumToggle = onAlbumBatchToggle,
            )
        MusicTab.ARTISTS ->
            ArtistList(
                artists = state.artists,
                emptyCopy = emptyCopy,
                onArtistClick = onArtistClick,
            )
        MusicTab.SONGS ->
            SongList(
                songs = state.songs,
                currentItemId = currentItemId,
                showAlbum = true,
                emptyTitle = emptyCopy.title,
                emptyMessage = emptyCopy.message,
                downloadState = downloadState,
                showSourceBadge = showSourceBadge,
                batchSelection = batchSelection,
                onSongClick = onSongClick,
                onPlayNext = onPlayNext,
                onToggleFavorite = onToggleFavorite,
                onToggleDownload = onToggleDownload,
                onBatchLongPress = onBatchLongPress,
                onBatchToggle = onBatchToggle,
            )
        MusicTab.PLAYLISTS ->
            PlaylistList(
                playlists = state.playlists,
                emptyCopy = emptyCopy,
                onPlaylistClick = onPlaylistClick,
            )
    }
}

@Composable
private fun DetailPane(
    detail: MusicDetail,
    currentItemId: UUID?,
    downloadState: MusicModeViewModel.SongDownloadState,
    showSourceBadge: Boolean,
    batchSelection: MultiSelectState,
    onSongClick: (MusicSong) -> Unit,
    onPlayNext: (MusicSong) -> Unit,
    onToggleFavorite: (MusicSong) -> Unit,
    onToggleDownload: (MusicSong) -> Unit,
    onBatchLongPress: (MusicSong) -> Unit,
    onBatchToggle: (MusicSong) -> Unit,
    onDownloadAlbum: () -> Unit = {},
    albumMissingCount: () -> Int? = { null },
) {
    if (
        (detail is MusicDetail.Playlist && detail.loading) ||
            (detail is MusicDetail.Favorites && detail.loading)
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }
    SongList(
        songs = detail.songs,
        currentItemId = currentItemId,
        showAlbum = detail !is MusicDetail.Album,
        downloadState = downloadState,
        showSourceBadge = showSourceBadge,
        batchSelection = batchSelection,
        emptyTitle =
            when (detail) {
                is MusicDetail.Favorites -> "还没有收藏的曲目"
                is MusicDetail.Recent -> "还没有最近播放"
                else -> "这里还没有可播放的曲目"
            },
        onSongClick = onSongClick,
        onPlayNext = onPlayNext,
        onToggleFavorite = onToggleFavorite,
        onToggleDownload = onToggleDownload,
        onBatchLongPress = onBatchLongPress,
        onBatchToggle = onBatchToggle,
        header =
            (detail as? MusicDetail.Album)?.let { albumDetail ->
                {
                    AlbumDetailHeader(
                        album = albumDetail.album,
                        missingCount = albumMissingCount(),
                        onDownloadAlbum = onDownloadAlbum,
                    )
                }
            },
    )
}

@Composable
private fun AlbumList(
    albums: List<MusicAlbum>,
    emptyCopy: MusicEmptyCopy,
    onAlbumClick: (MusicAlbum) -> Unit,
    selectionMode: Boolean = false,
    selectedIds: Set<String> = emptySet(),
    onAlbumLongPress: (MusicAlbum) -> Unit = {},
    onAlbumToggle: (MusicAlbum) -> Unit = {},
) {
    if (albums.isEmpty()) {
        EmptyHint(title = emptyCopy.title, message = emptyCopy.message)
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(items = albums, key = { album -> album.key }) { album ->
            val selected = album.key in selectedIds
            CinefinListRow(
                title = album.name,
                secondary =
                    listOfNotNull(album.artist, "${album.songs.size} 首")
                        .filter { it.isNotBlank() }
                        .joinToString(" · "),
                onClick = { if (selectionMode) onAlbumToggle(album) else onAlbumClick(album) },
                onLongClick = { onAlbumLongPress(album) },
                selectionMode = selectionMode,
                selected = selected,
                leading = { ArtworkThumb(imageUri = album.imageUri, title = album.name) },
            )
        }
    }
}

/**
 * W59 专辑详情头部：封面 + 专辑信息 + 「下载专辑」按钮。
 *
 * 按钮只补齐缺失（已下载 / 队列内跳过）+ 单次 100 首上限（与视频详情页整剧下载同口径）； 无缺失时置灰显示「已全部下载」。
 */
@Composable
private fun AlbumDetailHeader(
    album: MusicAlbum,
    missingCount: Int?,
    onDownloadAlbum: () -> Unit,
) {
    val colors = LocalCinefinColors.current
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .padding(horizontal = CinefinSpacing.Space4, vertical = CinefinSpacing.Space3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ArtworkThumb(imageUri = album.imageUri, title = album.name, size = 64.dp)
        Spacer(Modifier.width(CinefinSpacing.Space3))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = album.name,
                style = CinefinType.TitleSmall,
                color = colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text =
                    listOfNotNull(album.artist, "${album.songs.size} 首")
                        .filter { it.isNotBlank() }
                        .joinToString(" · "),
                style = CinefinType.BodySmall,
                color = colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(CinefinSpacing.Space3))
        val targets = missingCount ?: 0
        CinefinButton(
            text =
                if (targets > 0) {
                    stringResource(CoreR.string.music_download_album_count, targets)
                } else {
                    stringResource(CoreR.string.music_album_all_downloaded)
                },
            onClick = onDownloadAlbum,
            variant = CinefinButtonVariant.Outlined,
            size = CinefinButtonSize.Small,
            enabled = targets > 0,
        )
    }
}

@Composable
private fun ArtistList(
    artists: List<MusicArtist>,
    emptyCopy: MusicEmptyCopy,
    onArtistClick: (MusicArtist) -> Unit,
) {
    if (artists.isEmpty()) {
        EmptyHint(title = emptyCopy.title, message = emptyCopy.message)
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(items = artists, key = { artist -> artist.key }) { artist ->
            CinefinListRow(
                title = artist.name,
                secondary = "${artist.songs.size} 首",
                onClick = { onArtistClick(artist) },
                leading = { ArtworkThumb(imageUri = artist.imageUri, title = artist.name) },
            )
        }
    }
}

@Composable
private fun PlaylistList(
    playlists: List<MusicPlaylist>,
    emptyCopy: MusicEmptyCopy,
    onPlaylistClick: (MusicPlaylist) -> Unit,
) {
    if (playlists.isEmpty()) {
        EmptyHint(title = emptyCopy.title, message = emptyCopy.message)
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(items = playlists, key = { playlist -> playlist.id.toString() }) { playlist ->
            CinefinListRow(
                title = playlist.name,
                secondary = playlist.songCount?.let { count -> "$count 首" } ?: "歌单",
                onClick = { onPlaylistClick(playlist) },
                leading = { ArtworkThumb(imageUri = playlist.imageUri, title = playlist.name) },
            )
        }
    }
}

/**
 * W59：46–64dp 缩略图（§8.4：圆角 8dp）；**无图时 = 通用音乐占位**—— 现有音符矢量（`ic_music`）+ 当前域媒体色底，不再用黑底 / 字符占位（不新增位图）。
 */
@Composable
private fun ArtworkThumb(imageUri: String?, title: String, size: Dp = 48.dp) {
    val colors = LocalCinefinColors.current
    val media = LocalMediaColors.current
    Box(
        modifier = Modifier.size(size).clip(CinefinShapes.Xs).background(media.container),
        contentAlignment = Alignment.Center,
    ) {
        // W59：音符占位常驻在图下层——无图、加载中与加载失败都露出「音符 + 媒体色底」，不会出现黑块空白。
        Icon(
            painter = painterResource(CoreR.drawable.ic_music),
            contentDescription = null,
            tint = media.bright,
            modifier = Modifier.size(size * 0.5f),
        )
        if (imageUri != null) {
            AsyncImage(
                model = imageUri,
                contentDescription = title,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
    }
}

@Composable
private fun SongList(
    songs: List<MusicSong>,
    currentItemId: UUID?,
    showAlbum: Boolean,
    /** W59：列表头部插槽（专辑详情用它放「下载专辑」头部）。 */
    header: (@Composable () -> Unit)? = null,
    emptyTitle: String = "这里还没有可播放的曲目",
    emptyMessage: String? = null,
    downloadState: MusicModeViewModel.SongDownloadState = MusicModeViewModel.SongDownloadState(),
    showSourceBadge: Boolean = true,
    batchSelection: MultiSelectState = MultiSelectState(),
    onSongClick: (MusicSong) -> Unit,
    onPlayNext: (MusicSong) -> Unit,
    onToggleFavorite: (MusicSong) -> Unit,
    onToggleDownload: (MusicSong) -> Unit = {},
    onBatchLongPress: (MusicSong) -> Unit = {},
    onBatchToggle: (MusicSong) -> Unit = {},
) {
    if (songs.isEmpty()) {
        EmptyHint(title = emptyTitle, message = emptyMessage)
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        if (header != null) {
            item(key = "song-list-header") { header() }
        }
        // 歌单允许同一首曲目出现多次，key 里带序号避免重复 key 崩溃
        itemsIndexed(items = songs, key = { index, song -> "$index-${song.itemId}" }) { index, song
            ->
            SongRow(
                index = index + 1,
                song = song,
                subtitle = if (showAlbum) song.albumName else null,
                isCurrent = song.itemId == currentItemId,
                downloadLabel =
                    when {
                        downloadState.isDownloaded(song.itemId) -> "已下载"
                        downloadState.activeStatus(song.itemId) == DownloadTaskStatus.PAUSED ->
                            "下载已暂停"
                        downloadState.isActive(song.itemId) -> "下载中"
                        else -> null
                    },
                downloadMenuItem =
                    when {
                        song.localUri != null -> null
                        downloadState.isDownloaded(song.itemId) -> "删除下载"
                        downloadState.isActive(song.itemId) -> "取消下载"
                        else -> "下载"
                    },
                sourceBadge = song.source.label.takeIf { showSourceBadge },
                selectionMode = batchSelection.selectionMode,
                selected = batchSelection.isSelected(song.itemId.toString()),
                onClick = {
                    if (batchSelection.selectionMode) onBatchToggle(song) else onSongClick(song)
                },
                onLongClick = { onBatchLongPress(song) },
                onPlayNext = { onPlayNext(song) },
                onToggleFavorite = { onToggleFavorite(song) },
                onToggleDownload = { onToggleDownload(song) },
            )
        }
    }
}

@Composable
private fun SongRow(
    index: Int,
    song: MusicSong,
    subtitle: String?,
    isCurrent: Boolean,
    downloadLabel: String?,
    downloadMenuItem: String?,
    sourceBadge: String?,
    selectionMode: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onPlayNext: () -> Unit,
    onToggleFavorite: () -> Unit,
    onToggleDownload: () -> Unit,
) {
    val colors = LocalCinefinColors.current
    val media = LocalMediaColors.current
    var menuOpen by remember { mutableStateOf(false) }
    CinefinListRow(
        title = song.name,
        badge = sourceBadge,
        secondary =
            listOfNotNull(subtitle, downloadLabel, formatDuration(song.runtimeTicks))
                .filter { it.isNotBlank() }
                .joinToString(" · ")
                .ifBlank { null },
        isCurrent = isCurrent,
        selectionMode = selectionMode,
        selected = selected,
        onClick = onClick,
        onLongClick = onLongClick,
        leading = {
            Box(modifier = Modifier.width(28.dp), contentAlignment = Alignment.CenterStart) {
                Text(
                    text = index.toString().padStart(2, '0'),
                    style = CinefinType.MonoDataSmall,
                    color = if (isCurrent) media.bright else colors.onSurfaceFaint,
                )
            }
        },
        trailing = {
            Box {
                CinefinIconButton(onClick = { menuOpen = true }) { tint ->
                    Text(text = "⋮", style = CinefinType.TitleMedium, color = tint)
                }
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                    shape = CinefinShapes.Sm,
                    containerColor = colors.surfaceContainerHighest,
                    tonalElevation = 0.dp,
                    shadowElevation = 0.dp,
                ) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = "下一首播放",
                                style = CinefinType.BodyMedium,
                                color = colors.onSurface,
                            )
                        },
                        onClick = {
                            menuOpen = false
                            onPlayNext()
                        },
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = if (song.isFavorite) "取消收藏" else "收藏",
                                style = CinefinType.BodyMedium,
                                color = colors.onSurface,
                            )
                        },
                        onClick = {
                            menuOpen = false
                            onToggleFavorite()
                        },
                    )
                    if (downloadMenuItem != null) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = downloadMenuItem,
                                    style = CinefinType.BodyMedium,
                                    color = colors.onSurface,
                                )
                            },
                            onClick = {
                                menuOpen = false
                                onToggleDownload()
                            },
                        )
                    }
                }
            }
        },
    )
}

@Composable
private fun NowPlayingBar(
    queue: MusicQueue?,
    isPlaying: Boolean,
    isRestored: Boolean,
    positionMs: Long,
    durationMs: Long,
    fallbackDurationMs: Long,
    sleepState: SleepTimerController.State,
    onOpenNowPlaying: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onOpenLyrics: () -> Unit,
    onClose: () -> Unit,
) {
    val item = queue?.currentItem ?: return
    val colors = LocalCinefinColors.current
    // 恢复态（重启后未点播放）没有播放器会话，位置与时长回落到快照 / 曲库元数据
    val currentMs = if (isRestored) item.playbackPosition else positionMs
    val totalMs = durationMs.takeIf { it > 0L } ?: fallbackDurationMs
    // W66b：时间文本抽纯函数（单测覆盖）——不再与状态挤同一行被截断。
    val timeText = musicMiniBarTimeText(currentMs = currentMs, totalMs = totalMs)
    val statusText = buildString {
        if (isRestored) append("上次播放") else append(if (isPlaying) "正在播放" else "已暂停")
        if (sleepState.active) {
            append(" · 睡眠 ${SleepTimerSpec.formatRemaining(sleepState.remainingMs)}")
        }
    }
    Column(modifier = Modifier.fillMaxWidth().background(colors.surfaceContainerHigh)) {
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(colors.outlineVariant))
        Row(
            modifier =
                Modifier.fillMaxWidth().height(72.dp).padding(horizontal = CinefinSpacing.Space2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 封面 + 标题 + 时间：整块可点，进入全屏播放界面（各按钮各自生效，不触发全屏）
            Row(
                modifier =
                    Modifier.weight(1f)
                        .clip(CinefinShapes.Sm)
                        .clickable(onClick = onOpenNowPlaying)
                        .padding(
                            horizontal = CinefinSpacing.Space1,
                            vertical = CinefinSpacing.Space1,
                        ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ArtworkThumb(
                    imageUri = item.thumbnailUri,
                    title = item.name,
                )
                Spacer(modifier = Modifier.width(CinefinSpacing.Space3))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.name,
                        style = CinefinType.TitleSmall,
                        color = colors.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = timeText,
                            style = CinefinType.MonoDataSmall,
                            color = colors.onSurfaceVariant,
                            maxLines = 1,
                            softWrap = false,
                        )
                        // 状态（正在播放 / 已暂停 / 上次播放 [· 睡眠]）同行展示，放不下时省略——
                        // 时间永不被截断（W66b 用户口径）。
                        Spacer(modifier = Modifier.width(CinefinSpacing.Space1))
                        Text(
                            text = statusText,
                            style = CinefinType.MonoDataSmall,
                            color = colors.onSurfaceFaint,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                    }
                }
            }
            CinefinIconButton(onClick = onOpenLyrics) { tint ->
                Text(text = "词", style = CinefinType.LabelLarge, color = tint)
            }
            CinefinIconButton(onClick = onPlayPause) { tint ->
                Icon(
                    painter =
                        painterResource(
                            if (isPlaying) CoreR.drawable.ic_pause else CoreR.drawable.ic_play
                        ),
                    contentDescription = if (isPlaying) "暂停" else "播放",
                    tint = tint,
                    modifier = Modifier.size(20.dp),
                )
            }
            CinefinIconButton(onClick = onNext) { tint ->
                Icon(
                    painter = painterResource(CoreR.drawable.ic_skip_forward),
                    contentDescription = "下一首",
                    tint = tint,
                    modifier = Modifier.size(20.dp),
                )
            }
            // W56：关闭面板 = 停止播放并收起迷你条（队列存档保留，再次选择曲目即可恢复）。
            CinefinIconButton(onClick = onClose) { tint ->
                Icon(
                    painter = painterResource(CoreR.drawable.ic_close),
                    contentDescription = "关闭面板",
                    tint = tint,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MusicBatchActionBar(
    selectedCount: Int,
    isPlaylistDetail: Boolean,
    /** W59：专辑多选（专辑列表 Tab）= 只提供「下载整张」。 */
    albumsOnly: Boolean = false,
    isEnabled: (MusicBatchAction) -> Boolean,
    onPlay: () -> Unit,
    onDownload: () -> Unit,
    onFavorite: () -> Unit,
    onDelete: () -> Unit,
    onRemoveFromPlaylist: () -> Unit,
) {
    CinefinBatchBar(selectedCount = selectedCount) {
        if (albumsOnly) {
            BatchActionButton(
                enabled = isEnabled(MusicBatchAction.DOWNLOAD),
                label = "下载整张",
                iconRes = CoreR.drawable.ic_download,
                onClick = onDownload,
            )
            return@CinefinBatchBar
        }
        BatchActionButton(
            enabled = isEnabled(MusicBatchAction.PLAY),
            label = "播放",
            iconRes = CoreR.drawable.ic_play,
            onClick = onPlay,
        )
        BatchActionButton(
            enabled = isEnabled(MusicBatchAction.DOWNLOAD),
            label = "下载",
            iconRes = CoreR.drawable.ic_download,
            onClick = onDownload,
        )
        BatchActionButton(
            enabled = isEnabled(MusicBatchAction.FAVORITE),
            label = "收藏",
            iconRes = CoreR.drawable.ic_bookmark,
            onClick = onFavorite,
        )
        BatchActionButton(
            enabled = isEnabled(MusicBatchAction.DELETE),
            label = "删除",
            iconRes = CoreR.drawable.ic_trash,
            onClick = onDelete,
        )
        if (isPlaylistDetail) {
            BatchActionButton(
                enabled = isEnabled(MusicBatchAction.REMOVE_FROM_PLAYLIST),
                label = "移出歌单",
                iconRes = CoreR.drawable.ic_playlist,
                onClick = onRemoveFromPlaylist,
            )
        }
    }
}

@Composable
private fun BatchActionButton(
    enabled: Boolean,
    label: String,
    iconRes: Int,
    onClick: () -> Unit,
) {
    CinefinIconButton(enabled = enabled, onClick = onClick) { tint ->
        Icon(
            painter = painterResource(iconRes),
            contentDescription = label,
            tint = tint,
            modifier = Modifier.size(20.dp),
        )
    }
}

/** 批量删除确认（红线：只删本机下载；纯服务器条目不在目标内）。 */
@Composable
private fun MusicBatchDeleteDialog(
    selectedCount: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalCinefinColors.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surfaceContainerHighest,
        shape = CinefinShapes.Xl,
        title = {
            Text(text = "删除本地下载", style = CinefinType.HeadlineSmall, color = colors.onSurface)
        },
        text = {
            Text(
                text = "确定删除选中的 $selectedCount 项本机下载吗？只删除本机文件与索引，服务器上的媒体不受影响。",
                style = CinefinType.BodyMedium,
                color = colors.onSurfaceVariant,
            )
        },
        confirmButton = {
            CinefinButton(
                text = "删除",
                onClick = onConfirm,
                size = CinefinButtonSize.Medium,
                variant = CinefinButtonVariant.Filled,
            )
        },
        dismissButton = {
            CinefinButton(
                text = "取消",
                onClick = onDismiss,
                size = CinefinButtonSize.Medium,
                variant = CinefinButtonVariant.Text,
            )
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun QueueSheet(
    queue: MusicQueue,
    onDismiss: () -> Unit,
    onJump: (Int) -> Unit,
    onMove: (Int, Int) -> Unit,
    onRemove: (Int) -> Unit,
) {
    val colors = LocalCinefinColors.current
    val media = LocalMediaColors.current
    val rowHeightPx = with(LocalDensity.current) { QueueRowHeight.toPx() }
    var draggingIndex by remember { mutableStateOf<Int?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colors.surfaceContainer,
        contentColor = colors.onSurface,
        dragHandle = {
            Box(
                modifier =
                    Modifier.padding(top = CinefinSpacing.Space3)
                        .size(width = 32.dp, height = 4.dp)
                        .clip(CinefinShapes.TwoXs)
                        .background(colors.onSurfaceVariant.copy(alpha = 0.24f))
            )
        },
    ) {
        // W24 · C10：底部半屏面板（图标 / 左滑两种入口共用同一份队列编辑）
        Column(
            modifier =
                Modifier.fillMaxWidth()
                    .fillMaxHeight(0.5f)
                    .padding(horizontal = CinefinSpacing.Space5)
        ) {
            Text(
                text = "播放队列（${queue.items.size}）",
                style = CinefinType.TitleMedium,
                color = colors.onSurface,
            )
            Text(
                text = "长按右侧「≡」拖拽排序，点标题跳转播放，点「✕」移除",
                style = CinefinType.BodySmall,
                color = colors.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(CinefinSpacing.Space3))
            LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
                itemsIndexed(
                    items = queue.items,
                    key = { index, item -> "$index-${item.itemId}" },
                ) { index, item ->
                    val isDragging = draggingIndex == index
                    val isCurrent = index == queue.currentIndex
                    CinefinListRow(
                        title = item.name,
                        isCurrent = isCurrent,
                        showDivider = index != queue.items.lastIndex,
                        onClick = { onJump(index) },
                        modifier =
                            Modifier.zIndex(if (isDragging) 1f else 0f)
                                .graphicsLayer { translationY = if (isDragging) dragOffset else 0f }
                                .background(
                                    if (isDragging) colors.surfaceContainerHigh
                                    else Color.Transparent
                                ),
                        leading = {
                            Box(
                                modifier = Modifier.width(28.dp),
                                contentAlignment = Alignment.CenterStart,
                            ) {
                                Text(
                                    text = (index + 1).toString().padStart(2, '0'),
                                    style = CinefinType.MonoDataSmall,
                                    color = if (isCurrent) media.bright else colors.onSurfaceFaint,
                                )
                            }
                        },
                        trailing = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (isCurrent) {
                                    Text(
                                        text = "正在播放",
                                        style = CinefinType.BodySmall,
                                        color = media.bright,
                                    )
                                    Spacer(modifier = Modifier.width(CinefinSpacing.Space2))
                                }
                                CinefinIconButton(onClick = { onRemove(index) }) { tint ->
                                    Icon(
                                        painter = painterResource(CoreR.drawable.ic_close),
                                        contentDescription = "从队列移除",
                                        tint = tint,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                                Box(
                                    modifier =
                                        Modifier.size(48.dp).pointerInput(index, queue.items.size) {
                                            detectDragGesturesAfterLongPress(
                                                onDragStart = {
                                                    draggingIndex = index
                                                    dragOffset = 0f
                                                },
                                                onDrag = { change, amount ->
                                                    change.consume()
                                                    dragOffset += amount.y
                                                },
                                                onDragEnd = {
                                                    val from = draggingIndex
                                                    if (from != null) {
                                                        val delta =
                                                            (dragOffset / rowHeightPx).roundToInt()
                                                        val to =
                                                            (from + delta).coerceIn(
                                                                0,
                                                                queue.items.lastIndex,
                                                            )
                                                        if (to != from) onMove(from, to)
                                                    }
                                                    draggingIndex = null
                                                    dragOffset = 0f
                                                },
                                                onDragCancel = {
                                                    draggingIndex = null
                                                    dragOffset = 0f
                                                },
                                            )
                                        },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = "≡",
                                        style = CinefinType.TitleMedium,
                                        color = colors.onSurfaceVariant,
                                    )
                                }
                            }
                        },
                    )
                }
            }
            Spacer(modifier = Modifier.height(CinefinSpacing.Space4))
        }
    }
}

@Composable
private fun ErrorPane(
    title: String,
    message: String,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CinefinEmptyState(
            title = title,
            message = message,
            action = {
                CinefinButton(text = "重试", onClick = onRetry, size = CinefinButtonSize.Medium)
            },
            secondaryAction = {
                CinefinButton(
                    text = "关闭",
                    onClick = onDismiss,
                    variant = CinefinButtonVariant.Text,
                    size = CinefinButtonSize.Medium,
                )
            },
        )
    }
}

@Composable
private fun EmptyHint(title: String, message: String? = null) {
    // 可滚动容器：让空态也能触发外层 PullToRefreshBox 的下拉手势（W39）。
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CinefinEmptyState(title = title, message = message)
    }
}

/** 睡眠定时面板（W55 统一）：与视频 / 播放器共享 core 选择组件与进程级状态源；选中即生效并关闭面板。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SleepTimerSheet(
    state: SleepTimerController.State,
    onSelect: (Int?) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalCinefinColors.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colors.surfaceContainer,
        contentColor = colors.onSurface,
        dragHandle = {
            Box(
                modifier =
                    Modifier.padding(top = CinefinSpacing.Space3)
                        .size(width = 32.dp, height = 4.dp)
                        .clip(CinefinShapes.TwoXs)
                        .background(colors.onSurfaceVariant.copy(alpha = 0.24f))
            )
        },
    ) {
        Column(
            modifier =
                Modifier.fillMaxWidth()
                    .padding(horizontal = CinefinSpacing.Space5)
                    .verticalScroll(rememberScrollState())
        ) {
            Text(
                text = stringResource(CoreR.string.sleep_timer_title),
                style = CinefinType.TitleMedium,
                color = colors.onSurface,
            )
            Spacer(modifier = Modifier.height(CinefinSpacing.Space3))
            CinefinSleepTimerOptions(
                activeMinutes = state.minutes,
                remainingMs = state.remainingMs,
                onSelect = onSelect,
            )
            Spacer(modifier = Modifier.height(CinefinSpacing.Space4))
        }
    }
}

/** Jellyfin 的 runtimeTicks：1 tick = 100 ns，1 秒 = 10^7 ticks。 */
private fun formatDuration(runtimeTicks: Long): String {
    if (runtimeTicks <= 0L) return ""
    val totalSeconds = runtimeTicks / 10_000_000L
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}

/** 毫秒 → m:ss（底栏时间与全屏播放页共用）。 */
internal fun formatPositionMs(positionMs: Long): String {
    val totalSeconds = positionMs.coerceAtLeast(0L) / 1_000L
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}

/** W36 离线音乐提示条：说明当前曲库只含本机已下载曲目（空库时提示去下载）。 */
@Composable
private fun OfflineMusicNotice(songCount: Int) {
    val colors = LocalCinefinColors.current
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .background(colors.surfaceContainer)
                .padding(horizontal = CinefinSpacing.Space4, vertical = CinefinSpacing.Space3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(CoreR.drawable.ic_music),
            contentDescription = null,
            tint = colors.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(CinefinSpacing.Space2))
        Text(
            text =
                if (songCount > 0) "离线模式 · 仅显示本机已下载的 $songCount 首曲目"
                else "离线模式 · 还没有下载的音乐，联网后在曲目菜单点「下载」",
            style = CinefinType.BodySmall,
            color = colors.onSurfaceVariant,
        )
    }
}
