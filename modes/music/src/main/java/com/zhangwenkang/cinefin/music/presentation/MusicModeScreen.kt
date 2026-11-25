package com.zhangwenkang.cinefin.music.presentation

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick as semanticsOnClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import coil3.compose.AsyncImage
import com.zhangwenkang.cinefin.core.R as CoreR
import com.zhangwenkang.cinefin.core.presentation.components.CinefinBackToDefaultChip
import com.zhangwenkang.cinefin.core.presentation.components.CinefinBatchBar
import com.zhangwenkang.cinefin.core.presentation.components.CinefinButton
import com.zhangwenkang.cinefin.core.presentation.components.CinefinButtonSize
import com.zhangwenkang.cinefin.core.presentation.components.CinefinButtonTone
import com.zhangwenkang.cinefin.core.presentation.components.CinefinButtonVariant
import com.zhangwenkang.cinefin.core.presentation.components.CinefinEmptyState
import com.zhangwenkang.cinefin.core.presentation.components.CinefinIconButton
import com.zhangwenkang.cinefin.core.presentation.components.CinefinListRow
import com.zhangwenkang.cinefin.core.presentation.components.CinefinPageTopBar
import com.zhangwenkang.cinefin.core.presentation.components.CinefinSegmentedControl
import com.zhangwenkang.cinefin.core.presentation.components.CinefinSleepTimerOptions
import com.zhangwenkang.cinefin.core.presentation.components.CinefinSnackbarHost
import com.zhangwenkang.cinefin.core.presentation.components.DownloadSnackbarDuration
import com.zhangwenkang.cinefin.core.presentation.components.LibrarySelectorChip
import com.zhangwenkang.cinefin.core.presentation.components.LibrarySelectorOption
import com.zhangwenkang.cinefin.core.presentation.downloader.DownloadPrecheckContent
import com.zhangwenkang.cinefin.core.presentation.downloader.DownloadPrecheckDialog
import com.zhangwenkang.cinefin.core.presentation.downloader.DownloadPrecheckEnvironment
import com.zhangwenkang.cinefin.core.presentation.downloader.DownloadPrecheckRules
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinMotion
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
import com.zhangwenkang.cinefin.music.data.MusicPlaylistKind
import com.zhangwenkang.cinefin.music.data.MusicSong
import com.zhangwenkang.cinefin.music.data.PlaylistExchangeAdapter
import com.zhangwenkang.cinefin.player.core.domain.models.MusicQueue
import com.zhangwenkang.cinefin.player.core.domain.models.PlayerItem
import com.zhangwenkang.cinefin.player.local.domain.SleepTimerController
import com.zhangwenkang.cinefin.utils.DownloadTaskStatus
import java.io.IOException
import java.util.UUID
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal object MusicSongActionsAccessibility {
    val contentDescriptionRes: Int = CoreR.string.music_song_actions
}

/** 队列行高（拖动换算基准）：面板内所有行等高，拖动位移才能按整数行对齐。 */
private val QueueRowHeight = 72.dp

internal data class MusicPrimaryToolbarVisibility(
    val showsSource: Boolean,
    val showsPlayAll: Boolean,
) {
    val hasToolbar: Boolean
        get() = showsSource || showsPlayAll

    companion object {
        fun forState(
            offline: Boolean,
            songCount: Int,
            detailPresent: Boolean,
            batchMode: Boolean,
        ): MusicPrimaryToolbarVisibility {
            val toolbarRequested = !detailPresent && !batchMode
            return MusicPrimaryToolbarVisibility(
                showsSource = toolbarRequested && !offline,
                showsPlayAll = toolbarRequested && songCount > 0,
            )
        }
    }
}

/**
 * 音乐模式入口（W1 R2 最小闭环，W2 R2 扩到四维浏览 + 队列面板，W3 R3 接入 App 主题）。
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
    /** W5-T1 返修：App 全局主题（四变体）由宿主解析后传入；音乐页是普通内容页，直接跟随全局色板， 不再在这里钉死 aurora。默认值只服务预览 / 测试。 */
    theme: MusicDomainTheme = MusicDomainTheme(),
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
    /** W82 #25：搜索结果打开曲目并起播（路由参数；null = 普通音乐页）。 */
    initialSongId: String? = null,
    /** W1-T08：搜索结果条目类型（`BaseItemKind` 名称）与标题——专辑 / 艺术家 / 歌单结果据此进对应详情层。 */
    initialItemKind: String? = null,
    initialItemTitle: String? = null,
    initialQueueItemIds: String? = null,
    /** W1-T08：本页由搜索结果打开时的返回动作——详情层打开时系统返回 = 关详情并直接回搜索结果页（不停在音乐主页）。 null = 普通音乐页（详情返回只关详情）。 */
    onExitRoute: (() -> Unit)? = null,
    /** W56：把「音乐页内二级层（全屏播放 / 歌词页 / 专辑·艺术家·歌单详情）是否打开」回报给导航层（决定再点图标是只收二级层还是不重复导航）。 */
    onInnerPageOpenChange: ((Boolean) -> Unit)? = null,
    /** W60b：下载反馈 Snackbar「查看」→ 下载页。 */
    onOpenDownloads: () -> Unit = {},
    /** W75 #13：顶栏搜索入口（复用媒体库页现有搜索流程，导航由调用方负责）。 */
    onSearchClick: () -> Unit = {},
    viewModel: MusicModeViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val queue by viewModel.queue.collectAsState()
    val optimisticCurrentItem by viewModel.optimisticCurrentItem.collectAsState()
    val displayCurrentItem by viewModel.displayCurrentItem.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()
    val isRestored by viewModel.isRestored.collectAsState()
    val positionMs by viewModel.positionMs.collectAsState()
    val durationMs by viewModel.durationMs.collectAsState()
    val isBuffering by viewModel.isBuffering.collectAsState()
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
    var createPlaylistDialogOpen by rememberSaveable { mutableStateOf(false) }
    var createPlaylistRequestId by rememberSaveable { mutableStateOf<String?>(null) }
    var createServerPlaylistDialogOpen by rememberSaveable { mutableStateOf(false) }
    var renamePlaylistDialogId by rememberSaveable { mutableStateOf<String?>(null) }
    var renamePlaylistDialogName by rememberSaveable { mutableStateOf("") }
    var deletePlaylistDialogId by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingPlaylistSongs by remember { mutableStateOf<List<MusicSong>>(emptyList()) }
    // W5 T1（MUSIC-47）：长按曲目 = 上下文菜单 bottom sheet（多选改由工具栏「多选」入口进入）
    var contextSong by remember { mutableStateOf<MusicSong?>(null) }
    // W5 T6（MUSIC-34）：音频信息面板（长按菜单「音频信息」/ 音效面板「输出信息」共用）
    var audioInfo by remember { mutableStateOf<MusicAudioInfo?>(null) }
    // W7-T1：下载前置检查提示层（单曲 / 专辑批量）；内容只用本地快照，先于任何网络请求出现。
    var songPrecheck by remember { mutableStateOf<MusicSong?>(null) }
    var albumPrecheckContent by remember { mutableStateOf<DownloadPrecheckContent?>(null) }
    // W7-T2：歌单 / 曲库 / 专辑多选批量下载同样先过前置检查提示层。
    var batchPrecheck by remember { mutableStateOf<MusicBatchPrecheck?>(null) }

    /**
     * 单曲下载入口（W9-T2）：按菜单动作分派到三个独立入口。
     *
     * 已下载只走显式「删除下载」；队列中只走「取消下载」；下载动作先过前置检查提示层， 命中已完成 / 队列中时由 ViewModel 只发提示（不删文件、不重复入队）。
     */
    val requestSongDownload: (MusicSong) -> Unit = { song ->
        when (
            songDownloadMenuAction(
                downloaded = songDownloadState.isDownloaded(song),
                activeStatus = songDownloadState.activeStatus(song),
                fromLocalLibrary = song.localUri != null,
            )
        ) {
            SongDownloadMenuAction.DELETE -> viewModel.deleteSong(song)
            SongDownloadMenuAction.CANCEL -> viewModel.cancelSongDownload(song)
            SongDownloadMenuAction.DOWNLOAD -> songPrecheck = song
            SongDownloadMenuAction.NONE -> Unit
        }
    }
    val requestAlbumDownload: () -> Unit = {
        val content = viewModel.albumPrecheckContent()
        if (content == null) viewModel.downloadAlbum() else albumPrecheckContent = content
    }
    val requestBatchDownload: () -> Unit = {
        val precheck = viewModel.batchPrecheck()
        if (precheck == null) viewModel.downloadSelected() else batchPrecheck = precheck
    }
    val snackbarHostState = remember { SnackbarHostState() }

    // W60b：单曲 / 专辑下载反馈——「已受理下载 · N 首」+「查看」跳下载页（批量走下面 W7-T2 的三态反馈）。
    LaunchedEffect(viewModel) {
        viewModel.downloadQueued.collect { count ->
            val result =
                snackbarHostState.showSnackbar(
                    message = "已受理下载 · $count 首",
                    actionLabel = "查看",
                    duration = DownloadSnackbarDuration,
                )
            if (result == SnackbarResult.ActionPerformed) onOpenDownloads()
        }
    }

    // W7-T2：批量下载三态反馈（已加入 / 已在队列 / 已下载 + 超限推迟）；成功入队带「查看」。
    LaunchedEffect(viewModel) {
        viewModel.batchDownloadFeedback.collect { feedback ->
            val message = musicBatchDownloadFeedbackMessage(feedback)
            val result =
                if (feedback.added > 0) {
                    snackbarHostState.showSnackbar(
                        message = message,
                        actionLabel = "查看",
                        duration = DownloadSnackbarDuration,
                    )
                } else {
                    snackbarHostState.showSnackbar(message)
                }
            if (result == SnackbarResult.ActionPerformed) onOpenDownloads()
        }
    }

    // W5-T8（MUSIC-48）：播放失败非阻塞提示（自动重试 1 次仍失败 / 本地文件不可用 → 已跳下一首或停止）。
    LaunchedEffect(viewModel) {
        viewModel.playbackFailures.collect { failure ->
            snackbarHostState.showSnackbar(musicPlaybackFailureMessage(failure))
        }
    }

    // W9-T4：收藏写排队（网络 / 5xx 已落本地待同步）→ 与影视详情页同口径的非阻塞提示。
    LaunchedEffect(viewModel) {
        viewModel.favoriteWriteNotices.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    // W5-T12（MUSIC-14 Auto DJ）：连续自动续播 3 批后的刹车提示（非阻塞；用户任一传输操作后继续）。
    LaunchedEffect(viewModel) {
        viewModel.autoDjBatchLimit.collect {
            snackbarHostState.showSnackbar("Auto DJ 已续播 3 批，点击播放可继续")
        }
    }
    val effectsEqualizerEnabled by viewModel.effectsEqualizerEnabled.collectAsState()
    val effectsEqualizerPreset by viewModel.effectsEqualizerPreset.collectAsState()
    val effectsEqualizerBands by viewModel.effectsEqualizerBands.collectAsState()
    val effectsReplayGainMode by viewModel.effectsReplayGainMode.collectAsState()
    val effectsReplayGainLabel by viewModel.effectsReplayGainLabel.collectAsState()
    val effectsCrossfadeSeconds by viewModel.effectsCrossfadeSeconds.collectAsState()
    val effectsPlaybackSpeed by viewModel.effectsPlaybackSpeed.collectAsState()
    val effectsHasCurrentTrack by viewModel.effectsHasCurrentTrack.collectAsState()
    val effectsOverrideTrackDb by viewModel.effectsOverrideTrackDb.collectAsState()
    val effectsOverrideAlbumDb by viewModel.effectsOverrideAlbumDb.collectAsState()
    var overlayGuideOpen by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val exchangeScope = rememberCoroutineScope()
    var pendingExport by remember { mutableStateOf<PlaylistExportReady?>(null) }
    // 权限页返回：复核权限，拿到了就直接开启（用户在引导弹窗里点过「去授权」）
    val overlayPermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            viewModel.refreshLyricsOverlayPermission()
            if (Settings.canDrawOverlays(context)) viewModel.enableLyricsOverlay()
        }

    val importPlaylistLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) {
                exchangeScope.launch { snackbarHostState.showSnackbar("已取消导入歌单") }
            } else {
                exchangeScope.launch {
                    try {
                        val content =
                            withContext(Dispatchers.IO) {
                                context.contentResolver.openInputStream(uri)?.use { input ->
                                    PlaylistExchangeAdapter.readBoundedUtf8(input)
                                } ?: throw IOException("无法打开所选歌单文件")
                            }
                        viewModel.importLocalPlaylist(content)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        snackbarHostState.showSnackbar(
                            error.message?.takeIf { it.isNotBlank() } ?: "读取歌单文件失败"
                        )
                    }
                }
            }
        }

    fun finishExport(uri: Uri?) {
        val export = pendingExport
        pendingExport = null
        exchangeScope.launch {
            if (uri == null || export == null) {
                snackbarHostState.showSnackbar("已取消导出歌单")
                return@launch
            }
            try {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri, "wt")?.use { output ->
                        output.write(export.content.toByteArray(Charsets.UTF_8))
                        output.flush()
                    } ?: throw IOException("无法创建歌单文件")
                }
                snackbarHostState.showSnackbar("已导出「${export.fileName}」")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                snackbarHostState.showSnackbar(
                    error.message?.takeIf { it.isNotBlank() } ?: "写出歌单文件失败"
                )
            }
        }
    }

    val exportJsonLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument(PlaylistExchangeFormat.JSON.mimeType)
        ) { uri ->
            finishExport(uri)
        }
    val exportM3u8Launcher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument(PlaylistExchangeFormat.M3U8.mimeType)
        ) { uri ->
            finishExport(uri)
        }

    LaunchedEffect(viewModel) {
        viewModel.playlistExchangeFeedback.collect { feedback ->
            snackbarHostState.showSnackbar(feedback.message)
        }
    }
    LaunchedEffect(viewModel) {
        viewModel.playlistExportReady.collect { export ->
            pendingExport = export
            try {
                when (export.format) {
                    PlaylistExchangeFormat.JSON -> exportJsonLauncher.launch(export.fileName)
                    PlaylistExchangeFormat.M3U8 -> exportM3u8Launcher.launch(export.fileName)
                }
            } catch (error: Exception) {
                pendingExport = null
                snackbarHostState.showSnackbar(
                    error.message?.takeIf { it.isNotBlank() } ?: "无法打开系统文件保存器"
                )
            }
        }
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
    LaunchedEffect(initialSongId, initialItemKind, initialItemTitle, initialQueueItemIds) {
        initialSongId?.let { itemId ->
            viewModel.openItemFromRoute(
                rawItemId = itemId,
                kind = initialItemKind,
                title = initialItemTitle,
                rawQueueItemIds = initialQueueItemIds,
            )
        }
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
    // W1-T08：搜索结果打开的那一页没有「留在音乐主页」的语义——关详情后直接回搜索结果页。
    BackHandler(enabled = state.detail != null) {
        viewModel.closeDetail()
        onExitRoute?.invoke()
    }
    // 临时库视图（W53 追加）：没有详情 / 面板打开时，返回键先退出临时库（回默认音乐库）。
    // 详情打开时本 handler 禁用 → 详情返回语义优先；两者不会互相抢（BackHandler 为 LIFO）。
    BackHandler(
        enabled = onExitTemporaryLibrary != null && state.detail == null,
        onBack = { onExitTemporaryLibrary?.invoke() },
    )
    // W58：长按进入多选后，任何子层 / 详情状态变化都不自动退出（用户用顶栏 × 或全不选退出）。
    BackHandler(enabled = batchSelection.selectionMode) { viewModel.onBatchExit() }

    CinefinTheme(
        // 媒体色板仍是「音乐域」语义；色板 id / 明暗 / 动态取色跟随 App 全局主题
        domain = ContentDomain.Music,
        themeId = theme.themeId,
        darkTheme = theme.darkTheme,
        dynamicColor = theme.dynamicColor,
        surfaceBackground = false,
    ) {
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
                    onSearchClick = onSearchClick,
                    onBatchEnter = viewModel::enterBatchSelection,
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
                    val toolbarVisibility =
                        MusicPrimaryToolbarVisibility.forState(
                            offline = state.offline,
                            songCount = state.songs.size,
                            detailPresent = false,
                            batchMode = batchSelection.selectionMode,
                        )
                    MusicPrimaryToolbarSection(
                        showToolbar = toolbarVisibility.hasToolbar,
                        tabs = {
                            MusicTabsRow(
                                selected = state.tab,
                                onSelect = viewModel::selectTab,
                                libraryLabel = state.selectedMusicLibraryName,
                                libraryOptions = state.musicLibraries,
                                librarySelectedId = state.selectedMusicLibraryId,
                                showLibrarySelector =
                                    onExitTemporaryLibrary == null &&
                                        state.musicLibraries.size >= 3,
                                onSelectMusicLibrary = viewModel::selectMusicLibrary,
                            )
                        },
                        toolbar = {
                            MusicToolbarRow(
                                state = state,
                                visibility = toolbarVisibility,
                                onSelectSource = viewModel::setSourceFilter,
                                onToggleBadge = viewModel::setShowSourceBadge,
                                onSelectSortKey = viewModel::setSongSortKey,
                                onSetSortAscending = viewModel::setSongSortAscending,
                                onToggleFavoritesOnly = viewModel::setSongsFavoritesOnly,
                                onSelectPageSize = viewModel::setSongPageSize,
                                onPlayAll = { viewModel.playAll() },
                                onShuffleAll = { viewModel.playAll(shuffle = true) },
                                onBatchEnter = viewModel::enterBatchSelection,
                            )
                        },
                    )
                    Spacer(modifier = Modifier.height(CinefinSpacing.Space3))
                }

                val loadErrorMessage = state.loadErrorMessage
                val operationErrorMessage = state.errorMessage
                val hasRenderableContent = state.hasRenderableMusicContent()
                if (loadErrorMessage != null && hasRenderableContent) {
                    MusicSourceLoadErrorNotice(
                        title = state.loadErrorTitle ?: "音乐内容加载失败",
                        message = loadErrorMessage,
                        onRetry = viewModel::refresh,
                        onDismiss = viewModel::dismissError,
                    )
                }
                val shouldBlockLoadError =
                    shouldShowBlockingMusicError(
                        errorMessage = loadErrorMessage,
                        hasRenderableContent = hasRenderableContent,
                    )

                // W39：四个 tab 共用真实下拉刷新；离线模式 refresh() 只重读本机索引，不发服务器请求。
                PullToRefreshBox(
                    isRefreshing = state.refreshing,
                    onRefresh = viewModel::refresh,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                ) {
                    when {
                        shouldBlockLoadError && loadErrorMessage != null ->
                            ErrorPane(
                                title = state.loadErrorTitle ?: "曲库加载失败",
                                message = loadErrorMessage,
                                onRetry = viewModel::refresh,
                                onDismiss = viewModel::dismissError,
                            )
                        operationErrorMessage != null ->
                            ErrorPane(
                                title = state.errorTitle ?: "曲库加载失败",
                                message = operationErrorMessage,
                                onRetry = viewModel::refresh,
                                onDismiss = viewModel::dismissError,
                            )
                        state.loading ->
                            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                        state.detail != null ->
                            DetailPane(
                                detail = state.detail!!,
                                currentItemId = displayCurrentItem?.itemId,
                                downloadState = songDownloadState,
                                showSourceBadge = state.showSourceBadge,
                                batchSelection = batchSelection,
                                onSongClick = viewModel::playSong,
                                onPlayNext = viewModel::playNext,
                                onToggleFavorite = viewModel::toggleFavorite,
                                onToggleDownload = requestSongDownload,
                                onSongLongPress = { song -> contextSong = song },
                                onBatchToggle = viewModel::onBatchToggle,
                                onDownloadAlbum = requestAlbumDownload,
                                albumMissingCount = viewModel::albumDownloadMissingCount,
                                onAddToLocalPlaylist = { song ->
                                    pendingPlaylistSongs = listOf(song)
                                },
                                onMoveLocalEntry = { song, targetPosition ->
                                    val detail = state.detail as? MusicDetail.Playlist
                                    val playlistId = detail?.playlist?.id
                                    val entryId = song.playlistEntryId
                                    if (
                                        playlistId != null &&
                                            detail.playlist.kind == MusicPlaylistKind.LOCAL &&
                                            entryId != null
                                    ) {
                                        viewModel.moveLocalPlaylistEntry(
                                            playlistId,
                                            entryId,
                                            targetPosition,
                                        )
                                    }
                                },
                                onExportLocalPlaylist = viewModel::exportLocalPlaylist,
                            )
                        else ->
                            LibraryPane(
                                state = state,
                                currentItemId = displayCurrentItem?.itemId,
                                downloadState = songDownloadState,
                                showSourceBadge = state.showSourceBadge,
                                batchSelection = batchSelection,
                                onAlbumClick = viewModel::openAlbum,
                                onArtistClick = viewModel::openArtist,
                                onPlaylistClick = viewModel::openPlaylist,
                                onSongClick = viewModel::playSong,
                                onPlayNext = viewModel::playNext,
                                onToggleFavorite = viewModel::toggleFavorite,
                                onToggleDownload = requestSongDownload,
                                onSongLongPress = { song -> contextSong = song },
                                onBatchToggle = viewModel::onBatchToggle,
                                onAlbumBatchLongPress = viewModel::onAlbumBatchLongPress,
                                onAlbumBatchToggle = viewModel::onAlbumBatchToggle,
                                onAddToLocalPlaylist = { song ->
                                    pendingPlaylistSongs = listOf(song)
                                },
                                onCreateLocalPlaylist = {
                                    createPlaylistRequestId = UUID.randomUUID().toString()
                                    createPlaylistDialogOpen = true
                                },
                                onCreateServerPlaylist = { createServerPlaylistDialogOpen = true },
                                onRenameLocalPlaylist = { playlist ->
                                    renamePlaylistDialogId = playlist.id.toString()
                                    renamePlaylistDialogName = playlist.name
                                },
                                onDeleteLocalPlaylist = { playlist ->
                                    deletePlaylistDialogId = playlist.id.toString()
                                },
                                onImportLocalPlaylist = {
                                    try {
                                        importPlaylistLauncher.launch(
                                            PlaylistExchangeFileTypes.importMimeTypes
                                        )
                                    } catch (error: Exception) {
                                        exchangeScope.launch {
                                            snackbarHostState.showSnackbar(
                                                error.message?.takeIf { it.isNotBlank() }
                                                    ?: "无法打开系统文件选择器"
                                            )
                                        }
                                    }
                                },
                                onSongPageChange = viewModel::setSongPage,
                            )
                    }
                }

                if (batchSelection.selectionMode) {
                    MusicBatchActionBar(
                        selectedCount = batchSelection.selectedCount,
                        playPending = optimisticCurrentItem != null,
                        isPlaylistDetail = state.detail is MusicDetail.Playlist,
                        albumsOnly = state.detail == null && state.tab == MusicTab.ALBUMS,
                        isEnabled = viewModel::batchEnabled,
                        onPlay = viewModel::playSelected,
                        onDownload = requestBatchDownload,
                        onFavorite = viewModel::favoriteSelected,
                        onDelete = { pendingBatchDelete = true },
                        onAddToPlaylist = { pendingPlaylistSongs = viewModel.batchSelectedSongs() },
                        onRemoveFromPlaylist = viewModel::removeSelectedFromPlaylist,
                    )
                } else {
                    val liveQueue = queue
                    val barMediaColors =
                        MusicCoverPalette.mediaColors(
                            itemId = displayCurrentItem?.itemId?.toString(),
                            imageUri = displayCurrentItem?.thumbnailUri,
                        )
                    // W5-T3（MUSIC-39）：展开全屏时迷你条同时 fadeOut(150)；全屏收起后重现 scaleIn(0.96) + fadeIn(200)
                    val reduceMotion = CinefinMotion.reduceMotion()
                    CompositionLocalProvider(
                        LocalMediaColors provides (barMediaColors ?: LocalMediaColors.current)
                    ) {
                        AnimatedVisibility(
                            visible = !nowPlayingOpen,
                            enter =
                                if (reduceMotion) {
                                    fadeIn(tween(CinefinMotion.ReducedMotionMillis))
                                } else {
                                    scaleIn(
                                        initialScale = MusicMiniBarReappearScale,
                                        animationSpec = CinefinMotion.SpringCollapse,
                                    ) + fadeIn(tween(CinefinMotion.Fast))
                                },
                            exit =
                                fadeOut(
                                    tween(
                                        if (reduceMotion) CinefinMotion.ReducedMotionMillis
                                        else MusicMiniBarExpandFadeOutMillis
                                    )
                                ),
                        ) {
                            NowPlayingBar(
                                item = displayCurrentItem,
                                isPlaying = isPlaying,
                                isBuffering = isBuffering,
                                isRestored = isRestored && optimisticCurrentItem == null,
                                positionMs = if (optimisticCurrentItem == null) positionMs else 0L,
                                durationMs = if (optimisticCurrentItem == null) durationMs else 0L,
                                fallbackDurationMs =
                                    viewModel
                                        .songMeta(displayCurrentItem?.itemId)
                                        ?.runtimeTicks
                                        ?.div(TICKS_PER_MS) ?: 0L,
                                // W5-T2（MUSIC-23）：左右滑切歌的邻曲封面（探入动画只用封面，不读邻曲元数据）。
                                previousArtworkUri = liveQueue?.previousArtworkUri(),
                                nextArtworkUri = liveQueue?.nextArtworkUri(),
                                onOpenNowPlaying = { nowPlayingOpen = true },
                                onPlayPause = viewModel::togglePlayPause,
                                onPrevious = viewModel::skipToPrevious,
                                onNext = viewModel::skipToNext,
                                onClose = viewModel::dismissNowPlayingBar,
                            )
                        }
                    }
                }

                CinefinSnackbarHost(
                    hostState = snackbarHostState,
                    modifier = Modifier.padding(horizontal = CinefinSpacing.Space4),
                )
            }

            val currentQueue = queue
            // W5-T3（MUSIC-39）：容器变换——全屏页 scaleIn(0.94, SpringExpand) + fadeIn(280)，收起 SpringCollapse
            // + fadeOut(200)。
            // 不做真共享元素（Kimi 定稿二 §2：用「全屏整体弹簧展开 + 迷你条同时淡出」近似）。
            val playerReduceMotion = CinefinMotion.reduceMotion()
            AnimatedVisibility(
                visible = nowPlayingOpen && currentQueue != null,
                enter =
                    if (playerReduceMotion) {
                        fadeIn(tween(CinefinMotion.ReducedMotionMillis))
                    } else {
                        scaleIn(
                            initialScale = MusicFullScreenExpandScale,
                            animationSpec = CinefinMotion.SpringExpand,
                        ) + fadeIn(tween(CinefinMotion.Player))
                    },
                exit =
                    if (playerReduceMotion) {
                        fadeOut(tween(CinefinMotion.ReducedMotionMillis))
                    } else {
                        scaleOut(
                            targetScale = MusicFullScreenExpandScale,
                            animationSpec = CinefinMotion.SpringCollapse,
                        ) + fadeOut(tween(CinefinMotion.Fast))
                    },
            ) {
                if (currentQueue != null) {
                    MusicNowPlayingScreen(
                        queue = currentQueue,
                        lyricsState = lyricsState,
                        isPlaying = isPlaying,
                        isBuffering = isBuffering,
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
                        onOpenSleepTimer = { sleepSheetOpen = true },
                        onOpenArtist = { artistName ->
                            nowPlayingOpen = false
                            viewModel.openArtistByName(artistName)
                        },
                        onJumpQueueItem = viewModel::jumpToQueueItem,
                        onJumpUpNextItem = viewModel::jumpToUpNextItem,
                        onMoveQueueItem = viewModel::moveQueueItem,
                        onRemoveQueueItem = viewModel::removeQueueItem,
                        onUndoQueueItem = viewModel::undoRemoveQueueItem,
                        onRemoveUpNextItem = viewModel::removeUpNextItem,
                        onClearUpNext = viewModel::clearUpNext,
                        onToggleFavorite = viewModel::toggleFavorite,
                        onSelectLyricsLanguage = viewModel::selectLyricsLanguage,
                        onToggleLyricsBilingual = viewModel::toggleLyricsBilingual,
                        onToggleLyricsFollow = viewModel::toggleLyricsFollow,
                        onSelectLyricsFontScale = viewModel::selectLyricsFontScale,
                        onSelectLyricsLineSpacing = viewModel::selectLyricsLineSpacing,
                        onSeekLyricLine = viewModel::seekToLyricLine,
                    )
                }
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
                onSelectFontScale = viewModel::selectLyricsFontScale,
                onSelectLineSpacing = viewModel::selectLyricsLineSpacing,
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
                onJumpUpNext = viewModel::jumpToUpNextItem,
                onMove = viewModel::moveQueueItem,
                onRemove = viewModel::removeQueueItem,
                onRemoveUpNext = viewModel::removeUpNextItem,
                onClearUpNext = viewModel::clearUpNext,
                onUndoRemove = viewModel::undoRemoveQueueItem,
            )
        }

        val contextSheetSong = contextSong
        if (contextSheetSong != null) {
            val contextDownloadStatus = songDownloadState.activeStatus(contextSheetSong)
            SongContextSheet(
                song = contextSheetSong,
                downloadAction =
                    songDownloadMenuAction(
                        downloaded = songDownloadState.isDownloaded(contextSheetSong),
                        activeStatus = contextDownloadStatus,
                        fromLocalLibrary = contextSheetSong.localUri != null,
                    ),
                downloadStatus = contextDownloadStatus,
                onDismiss = { contextSong = null },
                onPlayNext = {
                    contextSong = null
                    viewModel.playNext(contextSheetSong)
                },
                onEnqueueAtEnd = {
                    contextSong = null
                    viewModel.enqueueSongAtEnd(contextSheetSong)
                },
                onAddToPlaylist = {
                    contextSong = null
                    pendingPlaylistSongs = listOf(contextSheetSong)
                },
                onDownload = {
                    contextSong = null
                    requestSongDownload(contextSheetSong)
                },
                // 音频信息（MUSIC-34）：W5-T6 起启用（设计定稿一 §1）
                onAudioInfo = {
                    contextSong = null
                    audioInfo = viewModel.audioInfoFor(contextSheetSong)
                },
            )
        }

        val precheckSong = songPrecheck
        if (precheckSong != null) {
            DownloadPrecheckDialog(
                content = viewModel.songPrecheckContent(precheckSong),
                space =
                    DownloadPrecheckRules.spaceStatus(
                        requiredBytes = null,
                        availableBytes = DownloadPrecheckEnvironment.availableBytes(context, 0),
                    ),
                networkPrompt = DownloadPrecheckEnvironment.networkPrompt(context),
                onConfirm = {
                    songPrecheck = null
                    viewModel.downloadSong(precheckSong)
                },
                onWifiOnly = {
                    songPrecheck = null
                    viewModel.downloadSong(precheckSong)
                },
                onDismiss = { songPrecheck = null },
            )
        }
        val precheckAlbum = albumPrecheckContent
        if (precheckAlbum != null) {
            DownloadPrecheckDialog(
                content = precheckAlbum,
                space =
                    DownloadPrecheckRules.spaceStatus(
                        requiredBytes = null,
                        availableBytes = DownloadPrecheckEnvironment.availableBytes(context, 0),
                    ),
                networkPrompt = DownloadPrecheckEnvironment.networkPrompt(context),
                onConfirm = {
                    albumPrecheckContent = null
                    viewModel.downloadAlbum()
                },
                onWifiOnly = {
                    albumPrecheckContent = null
                    viewModel.downloadAlbum()
                },
                onDismiss = { albumPrecheckContent = null },
            )
        }
        val precheckBatch = batchPrecheck
        if (precheckBatch != null) {
            DownloadPrecheckDialog(
                content = precheckBatch.content,
                space =
                    DownloadPrecheckRules.spaceStatus(
                        requiredBytes = null,
                        availableBytes = DownloadPrecheckEnvironment.availableBytes(context, 0),
                    ),
                networkPrompt = DownloadPrecheckEnvironment.networkPrompt(context),
                deferNote =
                    precheckBatch.deferredByLimit
                        .takeIf { it > 0 }
                        ?.let { deferred -> "超出单次上限的 $deferred 首未加入" },
                onConfirm = {
                    batchPrecheck = null
                    viewModel.downloadSelected()
                },
                onWifiOnly = {
                    batchPrecheck = null
                    viewModel.downloadSelected()
                },
                onDismiss = { batchPrecheck = null },
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
                onStopAfterCurrentTrack = {
                    viewModel.stopAfterCurrentTrack()
                    sleepSheetOpen = false
                },
                onDismiss = { sleepSheetOpen = false },
            )
        }

        // W30-MUSIC-FX：音效面板（EQ / ReplayGain / 淡入淡出 + W5-T6 倍速 / 输出信息），入口在全屏播放页功能行。
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
                playbackSpeed = effectsPlaybackSpeed,
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
                onSelectPlaybackSpeed = viewModel::selectPlaybackSpeed,
                onOpenAudioInfo = {
                    effectsSheetOpen = false
                    audioInfo = viewModel.audioInfoForCurrent()
                },
            )
        }

        // W5-T6 · MUSIC-34：音频输出信息面板
        audioInfo?.let { info ->
            MusicAudioInfoSheet(info = info, onDismiss = { audioInfo = null })
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

        if (createPlaylistDialogOpen) {
            PlaylistNameDialog(
                title = "新建本地歌单",
                initialName = "",
                confirmLabel = "创建",
                onConfirm = { name ->
                    createPlaylistRequestId?.let { id ->
                        viewModel.createLocalPlaylist(name, UUID.fromString(id))
                    }
                    createPlaylistRequestId = null
                    createPlaylistDialogOpen = false
                },
                onDismiss = {
                    createPlaylistDialogOpen = false
                    createPlaylistRequestId = null
                },
            )
        }

        if (createServerPlaylistDialogOpen) {
            PlaylistNameDialog(
                title = "新建服务器歌单",
                initialName = "",
                confirmLabel = "创建",
                onConfirm = { name ->
                    viewModel.createServerPlaylist(name)
                    createServerPlaylistDialogOpen = false
                },
                onDismiss = { createServerPlaylistDialogOpen = false },
            )
        }

        renamePlaylistDialogId?.let { playlistId ->
            PlaylistNameDialog(
                title = "重命名本地歌单",
                initialName = renamePlaylistDialogName,
                confirmLabel = "保存",
                onConfirm = { name ->
                    UUID.fromString(playlistId).let { id ->
                        viewModel.renameLocalPlaylist(id, name)
                    }
                    renamePlaylistDialogId = null
                },
                onDismiss = { renamePlaylistDialogId = null },
            )
        }

        deletePlaylistDialogId?.let { playlistId ->
            DeleteLocalPlaylistDialog(
                onConfirm = {
                    UUID.fromString(playlistId).let { id -> viewModel.deleteLocalPlaylist(id) }
                    deletePlaylistDialogId = null
                },
                onDismiss = { deletePlaylistDialogId = null },
            )
        }

        if (pendingPlaylistSongs.isNotEmpty()) {
            AddToPlaylistDialog(
                playlists = state.playlists,
                songs = pendingPlaylistSongs,
                onSelect = { playlist ->
                    if (playlist.kind == MusicPlaylistKind.SERVER) {
                        viewModel.addSongsToServerPlaylist(playlist.id, pendingPlaylistSongs)
                    } else {
                        viewModel.addSongsToLocalPlaylist(playlist.id, pendingPlaylistSongs)
                    }
                    pendingPlaylistSongs = emptyList()
                },
                onDismiss = { pendingPlaylistSongs = emptyList() },
            )
        }
    }
}

@Composable
private fun PlaylistNameDialog(
    title: String,
    initialName: String,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable(initialName) { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("歌单名") },
                singleLine = true,
            )
        },
        confirmButton = {
            CinefinButton(
                text = confirmLabel,
                onClick = { onConfirm(name) },
                enabled = name.isNotBlank(),
                size = CinefinButtonSize.Small,
            )
        },
        dismissButton = {
            CinefinButton(
                text = "取消",
                onClick = onDismiss,
                variant = CinefinButtonVariant.Text,
                size = CinefinButtonSize.Small,
            )
        },
    )
}

@Composable
private fun DeleteLocalPlaylistDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("删除本地歌单") },
        text = { Text("歌单条目会一并删除，音乐文件和服务器歌单不受影响。") },
        confirmButton = {
            CinefinButton(
                text = "删除",
                onClick = onConfirm,
                size = CinefinButtonSize.Small,
            )
        },
        dismissButton = {
            CinefinButton(
                text = "取消",
                onClick = onDismiss,
                variant = CinefinButtonVariant.Text,
                size = CinefinButtonSize.Small,
            )
        },
    )
}

@Composable
private fun AddToPlaylistDialog(
    playlists: List<MusicPlaylist>,
    songs: List<MusicSong>,
    onSelect: (MusicPlaylist) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("加入歌单") },
        text = {
            if (playlists.isEmpty()) {
                Text("还没有歌单，请先在「歌单」页新建。")
            } else {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    playlists.forEach { playlist ->
                        CinefinButton(
                            text = playlistAddRowLabel(playlist),
                            onClick = { onSelect(playlist) },
                            enabled =
                                playlist.kind == MusicPlaylistKind.LOCAL ||
                                    canAddToServerPlaylist(playlist, songs),
                            variant = CinefinButtonVariant.Text,
                            size = CinefinButtonSize.Small,
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            CinefinButton(
                text = "取消",
                onClick = onDismiss,
                variant = CinefinButtonVariant.Text,
                size = CinefinButtonSize.Small,
            )
        },
    )
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
    /** W75 #13：顶栏搜索入口（复用媒体库页现有搜索流程）。 */
    onSearchClick: () -> Unit,
    /** W5 T1：详情页「多选」入口——长按曲目改开上下文菜单后，多选从这里进。 */
    onBatchEnter: () -> Unit,
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
                            MusicTab.SUGGESTIONS ->
                                state.suggestionSections.sumOf { section -> section.songs.size }
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
            // W5 T1：详情页也保留多选入口（该槽在批量态本就放「全选」文字键，口径一致）
            if (detail != null && detail.songs.isNotEmpty()) {
                CinefinButton(
                    text = "多选",
                    onClick = onBatchEnter,
                    size = CinefinButtonSize.Small,
                    variant = CinefinButtonVariant.Text,
                )
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
            // W75 #13：全页搜索入口（与视频页 / 书架 / 库内容页同口径）。
            CinefinIconButton(onClick = onSearchClick) { tint ->
                Icon(
                    painter = painterResource(CoreR.drawable.ic_search),
                    contentDescription = "搜索",
                    tint = tint,
                    modifier = Modifier.size(22.dp),
                )
            }
        },
    )
}

@Composable
private fun MusicPrimaryToolbarSection(
    showToolbar: Boolean,
    tabs: @Composable () -> Unit,
    toolbar: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    Layout(
        content = {
            tabs()
            if (showToolbar) {
                toolbar()
            }
        },
        modifier = Modifier.fillMaxWidth(),
    ) { measurables, constraints ->
        val tabsPlaceable = measurables.first().measure(constraints.copy(minHeight = 0))
        val toolbarPlaceable =
            if (showToolbar) {
                measurables.getOrNull(1)?.measure(constraints.copy(minHeight = 0))
            } else {
                null
            }
        val plan =
            MusicPrimaryToolbarLayout.measurePrimary(
                tabs = MusicMeasuredBox(tabsPlaceable.width, tabsPlaceable.height),
                toolbar =
                    toolbarPlaceable?.let { placeable ->
                        MusicMeasuredBox(placeable.width, placeable.height)
                    },
                density = density,
            )
        layout(plan.widthPx, plan.heightPx) {
            tabsPlaceable.placeRelative(plan.tabsPlacement.xPx, plan.tabsPlacement.yPx)
            val toolbarPlacement = plan.toolbarPlacement
            if (toolbarPlaceable != null && toolbarPlacement != null) {
                toolbarPlaceable.placeRelative(toolbarPlacement.xPx, toolbarPlacement.yPx)
            }
        }
    }
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
                    MusicTab.SUGGESTIONS -> "建议"
                    MusicTab.SONGS -> "歌曲"
                    MusicTab.ALBUMS -> "专辑"
                    MusicTab.ARTISTS -> "艺术家"
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
 * W3-T14：音乐页工具行（一层，§4.1）——左「⋮」溢出菜单（来源筛选 / 来源徽标 / 排序 / 筛选（收藏）/ 每页数量）， 右「播放全部」一级 CTA（`CinefinButton`
 * Aurora tone 渐变）+「随机」图标键（仅歌曲 Tab、列表非空时）。
 *
 * 批量多选态由调用方整行隐藏（批量条接管）；离线时不显示来源筛选项（单一本地来源），排序 / 每页数量仍可用。
 */
@Composable
private fun MusicToolbarRow(
    state: MusicModeViewModel.UiState,
    visibility: MusicPrimaryToolbarVisibility,
    onSelectSource: (MusicItemSourceFilter) -> Unit,
    onToggleBadge: (Boolean) -> Unit,
    onSelectSortKey: (MusicSongSortKey) -> Unit,
    onSetSortAscending: (Boolean) -> Unit,
    onToggleFavoritesOnly: (Boolean) -> Unit,
    onSelectPageSize: (Int) -> Unit,
    onPlayAll: () -> Unit,
    onShuffleAll: () -> Unit,
    onBatchEnter: () -> Unit,
) {
    Row(
        modifier =
            Modifier.padding(horizontal = CinefinSpacing.Space4)
                .widthIn(max = 640.dp)
                .fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CinefinSpacing.Space2),
    ) {
        MusicToolbarOverflowMenu(
            sourceFilter = state.sourceFilter,
            showSourceBadge = state.showSourceBadge,
            showsSourceOptions = visibility.showsSource,
            sortKey = state.songSortKey,
            sortAscending = state.songSortAscending,
            favoritesOnly = state.songsFavoritesOnly,
            pageSize = state.songPageSize,
            showsSongOptions = state.tab == MusicTab.SONGS,
            onSelectSource = onSelectSource,
            onToggleBadge = onToggleBadge,
            onSelectSortKey = onSelectSortKey,
            onSetSortAscending = onSetSortAscending,
            onToggleFavoritesOnly = onToggleFavoritesOnly,
            onSelectPageSize = onSelectPageSize,
        )
        Spacer(modifier = Modifier.weight(1f))
        // W5 T1 · MUSIC-47：多选改由工具栏入口进入（长按曲目 = 上下文菜单）；仅歌曲 / 专辑 Tab 且有内容时显示
        val batchScopeAvailable =
            when (state.tab) {
                MusicTab.SONGS -> state.songs.isNotEmpty()
                MusicTab.ALBUMS -> state.albums.isNotEmpty()
                else -> false
            }
        if (batchScopeAvailable) {
            CinefinButton(
                text = "多选",
                onClick = onBatchEnter,
                size = CinefinButtonSize.Small,
                variant = CinefinButtonVariant.Text,
            )
        }
        if (visibility.showsPlayAll) {
            // W3-T14 · §4.1：一级 CTA 用 T09 批② 的 Aurora tone（极光渐变实底 + onAurora 内容 + glow）
            CinefinButton(
                text = "播放全部",
                onClick = onPlayAll,
                size = CinefinButtonSize.Small,
                variant = CinefinButtonVariant.Filled,
                tone = CinefinButtonTone.Aurora,
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

/** W3-T14 工具行溢出菜单的分页层级（单层菜单里放不下全部条目，用子页代替嵌套菜单）。 */
private enum class MusicToolbarMenuPage {
    ROOT,
    SOURCE,
    SORT,
    FILTER,
    PAGE_SIZE,
}

@Composable
private fun MusicToolbarMenuItem(
    text: String,
    onClick: () -> Unit,
    trailingText: String? = null,
    checked: Boolean = false,
) {
    val colors = LocalCinefinColors.current
    val media = LocalMediaColors.current
    DropdownMenuItem(
        text = {
            Text(
                text = text,
                style = CinefinType.BodyMedium,
                color = colors.onSurface,
            )
        },
        trailingIcon = {
            when {
                checked ->
                    Icon(
                        painter = painterResource(CoreR.drawable.ic_check),
                        contentDescription = null,
                        tint = media.bright,
                        modifier = Modifier.size(20.dp),
                    )
                trailingText != null ->
                    Text(
                        text = trailingText,
                        style = CinefinType.BodySmall,
                        color = colors.onSurfaceVariant,
                    )
            }
        },
        onClick = onClick,
    )
}

/**
 * W3-T14：工具行左侧「⋮」溢出菜单（§4.1）。
 *
 * 根页 = 来源筛选 / 来源徽标 / 排序 / 筛选（收藏）/ 每页数量；子页承载各自的单选值。排序、筛选与每页数量只在歌曲 Tab 显示 （其余 Tab 列表不分页也不排序，登记
 * LATER）。
 */
@Composable
private fun MusicToolbarOverflowMenu(
    sourceFilter: MusicItemSourceFilter,
    showSourceBadge: Boolean,
    showsSourceOptions: Boolean,
    sortKey: MusicSongSortKey,
    sortAscending: Boolean,
    favoritesOnly: Boolean,
    pageSize: Int,
    showsSongOptions: Boolean,
    onSelectSource: (MusicItemSourceFilter) -> Unit,
    onToggleBadge: (Boolean) -> Unit,
    onSelectSortKey: (MusicSongSortKey) -> Unit,
    onSetSortAscending: (Boolean) -> Unit,
    onToggleFavoritesOnly: (Boolean) -> Unit,
    onSelectPageSize: (Int) -> Unit,
) {
    val colors = LocalCinefinColors.current
    var expanded by remember { mutableStateOf(false) }
    var page by remember { mutableStateOf(MusicToolbarMenuPage.ROOT) }
    val closeMenu: () -> Unit = { expanded = false }
    Box {
        CinefinIconButton(
            onClick = {
                page = MusicToolbarMenuPage.ROOT
                expanded = true
            }
        ) { tint ->
            Box(modifier = Modifier.clearAndSetSemantics { contentDescription = "更多选项" }) {
                Text(text = "⋮", style = CinefinType.TitleMedium, color = tint)
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = closeMenu,
            shape = CinefinShapes.Card,
            containerColor = colors.surfaceContainerHighest,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
        ) {
            when (page) {
                MusicToolbarMenuPage.ROOT -> {
                    if (showsSourceOptions) {
                        MusicToolbarMenuItem(
                            text = "来源筛选",
                            trailingText = sourceFilter.label,
                            onClick = { page = MusicToolbarMenuPage.SOURCE },
                        )
                        MusicToolbarMenuItem(
                            text = "来源徽标",
                            checked = showSourceBadge,
                            onClick = {
                                closeMenu()
                                onToggleBadge(!showSourceBadge)
                            },
                        )
                    }
                    if (showsSongOptions) {
                        MusicToolbarMenuItem(
                            text = "排序",
                            trailingText =
                                "${sortKey.label} ${musicSongSortDirectionLabel(sortAscending)}",
                            onClick = { page = MusicToolbarMenuPage.SORT },
                        )
                        MusicToolbarMenuItem(
                            text = "筛选",
                            trailingText = if (favoritesOnly) "收藏" else null,
                            onClick = { page = MusicToolbarMenuPage.FILTER },
                        )
                        MusicToolbarMenuItem(
                            text = "每页数量",
                            trailingText = "$pageSize",
                            onClick = { page = MusicToolbarMenuPage.PAGE_SIZE },
                        )
                    }
                }
                MusicToolbarMenuPage.SOURCE -> {
                    MusicToolbarMenuItem(
                        text = "← 来源筛选",
                        onClick = { page = MusicToolbarMenuPage.ROOT },
                    )
                    MusicItemSourceFilter.entries.forEach { option ->
                        MusicToolbarMenuItem(
                            text = option.label,
                            checked = option == sourceFilter,
                            onClick = {
                                closeMenu()
                                onSelectSource(option)
                            },
                        )
                    }
                }
                MusicToolbarMenuPage.SORT -> {
                    MusicToolbarMenuItem(
                        text = "← 排序",
                        onClick = { page = MusicToolbarMenuPage.ROOT },
                    )
                    MusicSongSortKey.entries.forEach { option ->
                        MusicToolbarMenuItem(
                            text = option.label,
                            checked = option == sortKey,
                            onClick = {
                                closeMenu()
                                onSelectSortKey(option)
                            },
                        )
                    }
                    HorizontalDivider(color = colors.outline)
                    listOf(true, false).forEach { ascending ->
                        MusicToolbarMenuItem(
                            text = musicSongSortDirectionLabel(ascending),
                            checked = ascending == sortAscending,
                            onClick = {
                                closeMenu()
                                onSetSortAscending(ascending)
                            },
                        )
                    }
                }
                MusicToolbarMenuPage.FILTER -> {
                    MusicToolbarMenuItem(
                        text = "← 筛选",
                        onClick = { page = MusicToolbarMenuPage.ROOT },
                    )
                    MusicToolbarMenuItem(
                        text = "仅收藏",
                        checked = favoritesOnly,
                        onClick = {
                            closeMenu()
                            onToggleFavoritesOnly(!favoritesOnly)
                        },
                    )
                }
                MusicToolbarMenuPage.PAGE_SIZE -> {
                    MusicToolbarMenuItem(
                        text = "← 每页数量",
                        onClick = { page = MusicToolbarMenuPage.ROOT },
                    )
                    MUSIC_SONG_PAGE_SIZES.forEach { size ->
                        MusicToolbarMenuItem(
                            text = "$size",
                            checked = size == pageSize,
                            onClick = {
                                closeMenu()
                                onSelectPageSize(size)
                            },
                        )
                    }
                }
            }
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
    onBatchToggle: (MusicSong) -> Unit,
    onSongLongPress: (MusicSong) -> Unit = {},
    onAlbumBatchLongPress: (MusicAlbum) -> Unit = {},
    onAlbumBatchToggle: (MusicAlbum) -> Unit = {},
    onAddToLocalPlaylist: ((MusicSong) -> Unit)? = null,
    onCreateLocalPlaylist: () -> Unit = {},
    onCreateServerPlaylist: () -> Unit = {},
    onRenameLocalPlaylist: (MusicPlaylist) -> Unit = {},
    onDeleteLocalPlaylist: (MusicPlaylist) -> Unit = {},
    onImportLocalPlaylist: () -> Unit = {},
    onSongPageChange: (Int) -> Unit = {},
) {
    // W79b · #33：服务器个别曲目没有 Primary 图时，用同专辑封面兜底（只读已加载的曲库快照）。
    val albumArtwork = remember(state.albums) { albumArtworkUris(state.albums) }
    // W39：空态按来源分支（本地 / 服务器 / 全部 / 离线），与下拉刷新提示一致。
    val emptyCopy =
        musicEmptyCopy(tab = state.tab, filter = state.sourceFilter, offline = state.offline)
    when (state.tab) {
        MusicTab.SUGGESTIONS ->
            SuggestionPane(
                sections = state.suggestionSections,
                currentItemId = currentItemId,
                albumArtwork = albumArtwork,
                downloadState = downloadState,
                showSourceBadge = showSourceBadge,
                batchSelection = batchSelection,
                onSongClick = onSongClick,
                onPlayNext = onPlayNext,
                onToggleFavorite = onToggleFavorite,
                onToggleDownload = onToggleDownload,
                onBatchToggle = onBatchToggle,
                onSongLongPress = onSongLongPress,
                onAddToLocalPlaylist = onAddToLocalPlaylist,
            )
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
            // W3-T14 · §4.1：仅歌曲 Tab 分页——底部页码栏（上一页 / 页码指示 / 下一页），每页数量按设置。
            Column(modifier = Modifier.fillMaxSize()) {
                Box(modifier = Modifier.weight(1f)) {
                    // 翻页重置滚动位置：按页码重建列表状态。
                    key(state.songPage) {
                        SongList(
                            songs = state.songPageSongs,
                            currentItemId = currentItemId,
                            albumArtwork = albumArtwork,
                            autoLocateCurrent = true,
                            showAlbum = true,
                            emptyTitle =
                                if (state.songsFavoritesOnly) "没有收藏的曲目" else emptyCopy.title,
                            emptyMessage = emptyCopy.message,
                            downloadState = downloadState,
                            showSourceBadge = showSourceBadge,
                            batchSelection = batchSelection,
                            onSongClick = onSongClick,
                            onPlayNext = onPlayNext,
                            onToggleFavorite = onToggleFavorite,
                            onToggleDownload = onToggleDownload,
                            onSongLongPress = onSongLongPress,
                            onBatchToggle = onBatchToggle,
                            onAddToLocalPlaylist = onAddToLocalPlaylist,
                        )
                    }
                }
                if (state.songs.isNotEmpty() && state.songPageCount > 1) {
                    MusicSongPageBar(
                        indicator = musicSongPageIndicator(state.songPage, state.songPageCount),
                        canGoPrevious = state.songPage > 0,
                        canGoNext = state.songPage < state.songPageCount - 1,
                        onPrevious = { onSongPageChange(state.songPage - 1) },
                        onNext = { onSongPageChange(state.songPage + 1) },
                    )
                }
            }
        MusicTab.PLAYLISTS ->
            PlaylistList(
                playlists = state.playlists,
                emptyCopy = emptyCopy,
                onPlaylistClick = onPlaylistClick,
                onCreateLocalPlaylist = onCreateLocalPlaylist,
                onCreateServerPlaylist = onCreateServerPlaylist,
                onImportLocalPlaylist = onImportLocalPlaylist,
                onRenameLocalPlaylist = onRenameLocalPlaylist,
                onDeleteLocalPlaylist = onDeleteLocalPlaylist,
            )
    }
}

/** W3-T14 · §4.1：「建议」Tab——纵向三段（最近添加 / 最常播放 / 收藏），复用歌曲行组件，空段已在数据层隐藏。 */
@Composable
private fun SuggestionPane(
    sections: List<MusicSuggestionSection>,
    currentItemId: UUID?,
    albumArtwork: Map<String, String>,
    downloadState: MusicModeViewModel.SongDownloadState,
    showSourceBadge: Boolean,
    batchSelection: MultiSelectState,
    onSongClick: (MusicSong) -> Unit,
    onPlayNext: (MusicSong) -> Unit,
    onToggleFavorite: (MusicSong) -> Unit,
    onToggleDownload: (MusicSong) -> Unit,
    onBatchToggle: (MusicSong) -> Unit,
    onSongLongPress: (MusicSong) -> Unit,
    onAddToLocalPlaylist: ((MusicSong) -> Unit)?,
) {
    if (sections.isEmpty()) {
        EmptyHint(title = "还没有可推荐的内容", message = "先播放或收藏一些曲目后再回来")
        return
    }
    val colors = LocalCinefinColors.current
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        sections.forEach { section ->
            item(key = "suggestion-header-${section.title}") {
                Text(
                    text = section.title,
                    style = CinefinType.TitleSmall,
                    color = colors.onSurface,
                    modifier =
                        Modifier.fillMaxWidth()
                            .padding(
                                start = CinefinSpacing.Space4,
                                end = CinefinSpacing.Space4,
                                top = CinefinSpacing.Space3,
                                bottom = CinefinSpacing.Space1,
                            ),
                )
            }
            itemsIndexed(
                items = section.songs,
                key = { index, song -> "${section.title}-$index-${song.itemId}" },
            ) { index, song ->
                SongListRow(
                    song = song,
                    index = index,
                    lastIndex = section.songs.lastIndex,
                    currentItemId = currentItemId,
                    albumArtwork = albumArtwork,
                    showAlbum = true,
                    downloadState = downloadState,
                    showSourceBadge = showSourceBadge,
                    batchSelection = batchSelection,
                    onSongClick = onSongClick,
                    onPlayNext = onPlayNext,
                    onToggleFavorite = onToggleFavorite,
                    onToggleDownload = onToggleDownload,
                    onBatchToggle = onBatchToggle,
                    onSongLongPress = onSongLongPress,
                    onAddToLocalPlaylist = onAddToLocalPlaylist,
                    onMoveUp = null,
                    onMoveDown = null,
                )
            }
        }
    }
}

/** W3-T14：歌曲 Tab 底部分页栏（上一页 / 页码指示 / 下一页）。 */
@Composable
private fun MusicSongPageBar(
    indicator: String,
    canGoPrevious: Boolean,
    canGoNext: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
) {
    val colors = LocalCinefinColors.current
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .padding(horizontal = CinefinSpacing.Space4, vertical = CinefinSpacing.Space1),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        CinefinIconButton(onClick = onPrevious, enabled = canGoPrevious) { tint ->
            Icon(
                painter = painterResource(CoreR.drawable.ic_arrow_left),
                contentDescription = "上一页",
                tint = tint,
                modifier = Modifier.size(20.dp),
            )
        }
        Text(
            text = indicator,
            style = CinefinType.LabelMedium,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = CinefinSpacing.Space4),
        )
        CinefinIconButton(onClick = onNext, enabled = canGoNext) { tint ->
            Icon(
                painter = painterResource(CoreR.drawable.ic_arrow_right),
                contentDescription = "下一页",
                tint = tint,
                modifier = Modifier.size(20.dp),
            )
        }
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
    onBatchToggle: (MusicSong) -> Unit,
    onSongLongPress: (MusicSong) -> Unit = {},
    onDownloadAlbum: () -> Unit = {},
    albumMissingCount: () -> Int? = { null },
    onAddToLocalPlaylist: ((MusicSong) -> Unit)? = null,
    onMoveLocalEntry: (MusicSong, Int) -> Unit = { _, _ -> },
    onExportLocalPlaylist: (UUID, PlaylistExchangeFormat) -> Unit = { _, _ -> },
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
        onBatchToggle = onBatchToggle,
        onSongLongPress = onSongLongPress,
        onAddToLocalPlaylist = onAddToLocalPlaylist,
        onMoveUp =
            if (detail is MusicDetail.Playlist && detail.playlist.kind == MusicPlaylistKind.LOCAL) {
                { song, index -> onMoveLocalEntry(song, index - 1) }
            } else {
                null
            },
        onMoveDown =
            if (detail is MusicDetail.Playlist && detail.playlist.kind == MusicPlaylistKind.LOCAL) {
                { song, index -> onMoveLocalEntry(song, index + 1) }
            } else {
                null
            },
        header =
            when {
                detail is MusicDetail.Album -> {
                    {
                        AlbumDetailHeader(
                            album = detail.album,
                            missingCount = albumMissingCount(),
                            onDownloadAlbum = onDownloadAlbum,
                        )
                    }
                }
                detail is MusicDetail.Playlist &&
                    detail.playlist.kind == MusicPlaylistKind.LOCAL -> {
                    {
                        LocalPlaylistExchangeHeader(
                            onExportJson = {
                                onExportLocalPlaylist(
                                    detail.playlist.id,
                                    PlaylistExchangeFormat.JSON,
                                )
                            },
                            onExportM3u8 = {
                                onExportLocalPlaylist(
                                    detail.playlist.id,
                                    PlaylistExchangeFormat.M3U8,
                                )
                            },
                        )
                    }
                }
                else -> null
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
    onCreateLocalPlaylist: () -> Unit,
    onCreateServerPlaylist: () -> Unit,
    onImportLocalPlaylist: () -> Unit,
    onRenameLocalPlaylist: (MusicPlaylist) -> Unit,
    onDeleteLocalPlaylist: (MusicPlaylist) -> Unit,
) {
    if (playlists.isEmpty()) {
        Column(modifier = Modifier.fillMaxSize()) {
            PlaylistCreateAction(
                onCreateLocalPlaylist = onCreateLocalPlaylist,
                onCreateServerPlaylist = onCreateServerPlaylist,
                onImportLocalPlaylist = onImportLocalPlaylist,
            )
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                EmptyHint(title = emptyCopy.title, message = emptyCopy.message)
            }
        }
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item(key = "local-playlist-create") {
            PlaylistCreateAction(
                onCreateLocalPlaylist = onCreateLocalPlaylist,
                onCreateServerPlaylist = onCreateServerPlaylist,
                onImportLocalPlaylist = onImportLocalPlaylist,
            )
        }
        items(items = playlists, key = { playlist -> playlist.stableKey() }) { playlist ->
            var menuOpen by remember { mutableStateOf(false) }
            CinefinListRow(
                title = playlist.name,
                secondary = playlist.songCount?.let { count -> "$count 首" } ?: "歌单",
                onClick = { onPlaylistClick(playlist) },
                leading = { ArtworkThumb(imageUri = playlist.imageUri, title = playlist.name) },
                badge = "本地".takeIf { playlist.kind == MusicPlaylistKind.LOCAL },
                trailing =
                    if (playlist.kind == MusicPlaylistKind.LOCAL) {
                        {
                            Box {
                                CinefinIconButton(onClick = { menuOpen = true }) { tint ->
                                    Text(
                                        text = "⋮",
                                        style = CinefinType.TitleMedium,
                                        color = tint,
                                    )
                                }
                                DropdownMenu(
                                    expanded = menuOpen,
                                    onDismissRequest = { menuOpen = false },
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("重命名") },
                                        onClick = {
                                            menuOpen = false
                                            onRenameLocalPlaylist(playlist)
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("删除") },
                                        onClick = {
                                            menuOpen = false
                                            onDeleteLocalPlaylist(playlist)
                                        },
                                    )
                                }
                            }
                        }
                    } else {
                        null
                    },
            )
        }
    }
}

@Composable
private fun PlaylistCreateAction(
    onCreateLocalPlaylist: () -> Unit,
    onCreateServerPlaylist: () -> Unit,
    onImportLocalPlaylist: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(CinefinSpacing.Space3),
        horizontalArrangement = Arrangement.End,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(CinefinSpacing.Space2)) {
            CinefinButton(
                text = "导入 JSON/M3U8",
                onClick = onImportLocalPlaylist,
                size = CinefinButtonSize.Small,
                variant = CinefinButtonVariant.Outlined,
            )
            CinefinButton(
                text = "新建本地歌单",
                onClick = onCreateLocalPlaylist,
                size = CinefinButtonSize.Small,
                variant = CinefinButtonVariant.Outlined,
            )
            CinefinButton(
                text = "新建服务器歌单",
                onClick = onCreateServerPlaylist,
                size = CinefinButtonSize.Small,
                variant = CinefinButtonVariant.Outlined,
            )
        }
    }
}

@Composable
private fun LocalPlaylistExchangeHeader(
    onExportJson: () -> Unit,
    onExportM3u8: () -> Unit,
) {
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .padding(horizontal = CinefinSpacing.Space4, vertical = CinefinSpacing.Space2),
        horizontalArrangement = Arrangement.spacedBy(CinefinSpacing.Space2, Alignment.End),
    ) {
        CinefinButton(
            text = "导出 JSON",
            onClick = onExportJson,
            size = CinefinButtonSize.Small,
            variant = CinefinButtonVariant.Outlined,
        )
        CinefinButton(
            text = "导出 M3U8",
            onClick = onExportM3u8,
            size = CinefinButtonSize.Small,
            variant = CinefinButtonVariant.Outlined,
        )
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
    /** W79b · #33：专辑名 → 封面兜底表（空 = 只用曲目自身封面）。 */
    albumArtwork: Map<String, String> = emptyMap(),
    /** W79b · #34：仅在库内「歌曲」Tab 启用——进入时定位一次，切歌跟随；手动滚动后本次不再拉回。 */
    autoLocateCurrent: Boolean = false,
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
    onBatchToggle: (MusicSong) -> Unit = {},
    onSongLongPress: (MusicSong) -> Unit = {},
    onAddToLocalPlaylist: ((MusicSong) -> Unit)? = null,
    onMoveUp: ((MusicSong, Int) -> Unit)? = null,
    onMoveDown: ((MusicSong, Int) -> Unit)? = null,
) {
    if (songs.isEmpty()) {
        EmptyHint(title = emptyTitle, message = emptyMessage)
        return
    }
    val listState = rememberLazyListState()
    // 用户主动滚动（拖动 / 惯性）后，本次进入不再做一次性居中；可见行补位 / 不可见目标贴边仍保留。
    val manualScrolled = remember { mutableStateOf(false) }
    // 程序化居中也会走 scrollable，用这个标记把自身滚动排除在「用户滚动」之外。
    val autoScrolling = remember { mutableStateOf(false) }
    val nestedScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && !autoScrolling.value) {
                    manualScrolled.value = true
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (!autoScrolling.value) manualScrolled.value = true
                return Velocity.Zero
            }
        }
    }
    val headerCount = if (header != null) 1 else 0
    val targetIndex = songListAutoLocateIndex(songs, currentItemId, headerCount)
    // 进入歌曲 Tab 只居中一次；之后点击可见行零位移，只有目标被裁切 / 不在视口时才做最小补位。
    val hasLocatedOnce = remember { mutableStateOf(false) }
    LaunchedEffect(autoLocateCurrent, currentItemId, targetIndex) {
        if (!autoLocateCurrent) return@LaunchedEffect
        if (targetIndex < 0) {
            // 没有可定位目标时同样消费一次性居中，避免首次点击完整可见行时误走居中分支。
            hasLocatedOnce.value = true
            return@LaunchedEffect
        }
        if (!hasLocatedOnce.value && manualScrolled.value) {
            // 用户已手动滚动：只放弃本次进入的一次性居中，后续切歌仍要补位。
            hasLocatedOnce.value = true
            return@LaunchedEffect
        }
        // 曲库是异步加载的：等目标行进入 LazyList 布局后再定位（只等一次，不轮询）。
        snapshotFlow { listState.layoutInfo }
            .first { info ->
                info.totalItemsCount > targetIndex && info.visibleItemsInfo.isNotEmpty()
            }
        withFrameNanos {}
        val layout = listState.layoutInfo
        val viewportHeight = layout.viewportEndOffset - layout.viewportStartOffset
        val songRowHeight =
            layout.visibleItemsInfo.firstOrNull { item -> item.index >= headerCount }?.size
                ?: layout.visibleItemsInfo.firstOrNull()?.size
                ?: 1
        val centerOnEntry =
            !hasLocatedOnce.value &&
                shouldAutoLocateSong(autoLocateCurrent, targetIndex, manualScrolled.value)
        if (!centerOnEntry && !hasLocatedOnce.value) {
            hasLocatedOnce.value = true
            return@LaunchedEffect
        }
        autoScrolling.value = true
        try {
            if (centerOnEntry) {
                hasLocatedOnce.value = true
                listState.animateScrollToItem(
                    index = targetIndex,
                    scrollOffset =
                        songListCenteredScrollOffset(
                            viewportHeight = viewportHeight,
                            itemHeight = songRowHeight,
                        ),
                )
            } else {
                val visibleTarget =
                    layout.visibleItemsInfo.firstOrNull { item -> item.index == targetIndex }
                if (visibleTarget != null) {
                    val delta =
                        songListMinimalRevealDelta(
                            viewportStart = layout.viewportStartOffset,
                            viewportEnd = layout.viewportEndOffset,
                            itemOffset = visibleTarget.offset,
                            itemSize = visibleTarget.size,
                        )
                    if (delta != 0) {
                        listState.animateScrollBy(
                            delta.toFloat(),
                            animationSpec =
                                tween(
                                    durationMillis = 240,
                                    easing = FastOutSlowInEasing,
                                ),
                        )
                    }
                } else {
                    val firstVisibleIndex = layout.visibleItemsInfo.first().index
                    val lastVisibleIndex = layout.visibleItemsInfo.last().index
                    listState.animateScrollToItem(
                        index = targetIndex,
                        scrollOffset =
                            songListRevealScrollOffset(
                                targetIndex = targetIndex,
                                firstVisibleIndex = firstVisibleIndex,
                                lastVisibleIndex = lastVisibleIndex,
                                viewportHeight = viewportHeight,
                                itemHeight = songRowHeight,
                            ),
                    )
                }
            }
        } finally {
            autoScrolling.value = false
        }
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize().nestedScroll(nestedScrollConnection),
        state = listState,
    ) {
        if (header != null) {
            item(key = "song-list-header") { header() }
        }
        // 歌单允许同一首曲目出现多次，key 里带序号避免重复 key 崩溃
        itemsIndexed(items = songs, key = { index, song -> "$index-${song.itemId}" }) { index, song
            ->
            SongListRow(
                song = song,
                index = index,
                lastIndex = songs.lastIndex,
                currentItemId = currentItemId,
                albumArtwork = albumArtwork,
                showAlbum = showAlbum,
                downloadState = downloadState,
                showSourceBadge = showSourceBadge,
                batchSelection = batchSelection,
                onSongClick = onSongClick,
                onPlayNext = onPlayNext,
                onToggleFavorite = onToggleFavorite,
                onToggleDownload = onToggleDownload,
                onBatchToggle = onBatchToggle,
                onSongLongPress = onSongLongPress,
                onAddToLocalPlaylist = onAddToLocalPlaylist,
                onMoveUp = onMoveUp,
                onMoveDown = onMoveDown,
            )
        }
    }
}

/** 歌曲行（W3-T14 抽出公共调用点）：歌曲 Tab 与「建议」Tab 共用同一行渲染，`SongRow` 本体不动。 */
@Composable
private fun SongListRow(
    song: MusicSong,
    index: Int,
    lastIndex: Int,
    currentItemId: UUID?,
    albumArtwork: Map<String, String>,
    showAlbum: Boolean,
    downloadState: MusicModeViewModel.SongDownloadState,
    showSourceBadge: Boolean,
    batchSelection: MultiSelectState,
    onSongClick: (MusicSong) -> Unit,
    onPlayNext: (MusicSong) -> Unit,
    onToggleFavorite: (MusicSong) -> Unit,
    onToggleDownload: (MusicSong) -> Unit,
    onBatchToggle: (MusicSong) -> Unit,
    onSongLongPress: (MusicSong) -> Unit,
    onAddToLocalPlaylist: ((MusicSong) -> Unit)?,
    onMoveUp: ((MusicSong, Int) -> Unit)?,
    onMoveDown: ((MusicSong, Int) -> Unit)?,
) {
    val downloadStatus = downloadState.activeStatus(song)
    val downloadAction =
        songDownloadMenuAction(
            downloaded = downloadState.isDownloaded(song),
            activeStatus = downloadStatus,
            fromLocalLibrary = song.localUri != null,
        )
    SongRow(
        index = index + 1,
        song = song,
        artworkUri =
            resolveSongArtworkUri(
                imageUri = song.imageUri,
                albumName = song.albumName,
                albumArtwork = albumArtwork,
            ),
        subtitle = if (showAlbum) song.albumName else null,
        isCurrent = song.itemId == currentItemId,
        downloadLabel = songDownloadStatusLabel(downloadAction, downloadStatus),
        downloadMenuItem = songDownloadMenuLabel(downloadAction),
        sourceBadge =
            when {
                song.isPlaylistPlaceholder -> "不可用"
                // MUSIC-36：APE / DSD 引擎解不了 → 曲目行弱提示，不静默失败
                song.formatUnsupported -> "不支持"
                showSourceBadge -> song.source.label
                else -> null
            },
        selectionMode = batchSelection.selectionMode,
        selected = batchSelection.isSelected(song.playlistSelectionKey()),
        onClick = { if (batchSelection.selectionMode) onBatchToggle(song) else onSongClick(song) },
        onLongClick = {
            // W5 T1 · MUSIC-47：长按 = 上下文菜单；多选态内仍按切换选中处理
            if (batchSelection.selectionMode) onBatchToggle(song) else onSongLongPress(song)
        },
        onPlayNext = { onPlayNext(song) },
        onToggleFavorite = { onToggleFavorite(song) },
        onToggleDownload = { onToggleDownload(song) },
        onAddToLocalPlaylist = onAddToLocalPlaylist?.let { callback -> { callback(song) } },
        onMoveUp = onMoveUp?.takeIf { index > 0 }?.let { callback -> { callback(song, index) } },
        onMoveDown =
            onMoveDown?.takeIf { index < lastIndex }?.let { callback -> { callback(song, index) } },
    )
}

@Composable
private fun SongRow(
    index: Int,
    song: MusicSong,
    artworkUri: String?,
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
    onAddToLocalPlaylist: (() -> Unit)?,
    onMoveUp: (() -> Unit)?,
    onMoveDown: (() -> Unit)?,
) {
    val colors = LocalCinefinColors.current
    val media = LocalMediaColors.current
    var menuOpen by remember { mutableStateOf(false) }
    val songActionsLabel = stringResource(MusicSongActionsAccessibility.contentDescriptionRes)
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                ArtworkThumb(imageUri = artworkUri, title = song.name, size = 40.dp)
                Spacer(modifier = Modifier.width(CinefinSpacing.Space2))
                Text(
                    text = songRowIndexLabel(index),
                    style = CinefinType.MonoDataSmall,
                    color = if (isCurrent) media.bright else colors.onSurfaceFaint,
                    modifier = Modifier.widthIn(min = 22.dp),
                    maxLines = 1,
                    softWrap = false,
                    textAlign = TextAlign.End,
                )
            }
        },
        trailing = {
            Box {
                CinefinIconButton(onClick = { menuOpen = true }) { tint ->
                    Box(
                        modifier =
                            Modifier.clearAndSetSemantics { contentDescription = songActionsLabel }
                    ) {
                        Text(text = "⋮", style = CinefinType.TitleMedium, color = tint)
                    }
                }
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                    shape = CinefinShapes.Button,
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
                    if (onAddToLocalPlaylist != null) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = "加入歌单",
                                    style = CinefinType.BodyMedium,
                                    color = colors.onSurface,
                                )
                            },
                            onClick = {
                                menuOpen = false
                                onAddToLocalPlaylist()
                            },
                        )
                    }
                    if (onMoveUp != null) {
                        DropdownMenuItem(
                            text = { Text("上移") },
                            onClick = {
                                menuOpen = false
                                onMoveUp()
                            },
                        )
                    }
                    if (onMoveDown != null) {
                        DropdownMenuItem(
                            text = { Text("下移") },
                            onClick = {
                                menuOpen = false
                                onMoveDown()
                            },
                        )
                    }
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

/** 迷你条尺寸与玻璃参数（W5-T2 定稿一 §2，写死，不得自调）。 */
private val MusicMiniBarHeight = 64.dp
private val MusicMiniBarArtworkSize = 40.dp
private val MusicMiniBarProgressHeight = 2.dp
private val MusicMiniBarTopHighlightHeight = 0.5.dp
private const val MusicMiniBarTopHighlightAlpha = 0.06f
private const val MusicMiniBarMarqueeRepeatDelayMillis = 1_200
private val MusicMiniBarMarqueeVelocity = 30.dp
private val MusicMiniBarWideBreakpoint = 840.dp

/** 暂停时迷你条进度线 alpha（定稿一 §2 / MUSIC-40 后半：40% 静态）。 */
private const val MusicMiniBarPausedProgressAlpha = 0.4f

/** W5-T8（MUSIC-49）：缓冲中进度线 indeterminate 扫过的周期与高亮段宽占比。 */
private const val MusicMiniBarBufferingCycleMillis = 1_200
private const val MusicMiniBarBufferingSegmentFraction = 0.3f
private val MusicMiniBarArtistStyle =
    CinefinType.BodySmall.copy(fontSize = 12.sp, lineHeight = 16.sp)

/** 手势结果（`awaitPointerEventScope` 里只负责采样，动画与判定回到普通 suspend 上下文做）。 */
private data class MusicMiniBarGesture(
    val totalX: Float,
    val totalY: Float,
    val velocityX: Float,
    val velocityY: Float,
    val horizontal: Boolean,
)

/**
 * W5-T2（MUSIC-17/23）：玻璃迷你播放器。
 *
 * 布局（定稿 §2）：水平外边距 `Space3`、底部悬浮 `Space2`、高 64dp、`CinefinShapes.Glass`；底 `colors.glass` + 1dp
 * `colors.glassBorder` + 顶部 0.5dp 白 6% 内高光（与 W4 玻璃容器同法）。宽 ≥840dp 退化为底部通栏（同一组件、外边距 0、圆角 0）。
 *
 * 组成：40dp 封面（`TwoXs`）→ 标题 marquee（首尾停顿 1.2s、30dp/s）→ 艺术家 12sp `onSurfaceVariant` → 播放/暂停 44dp → 下一首
 * 44dp → 关闭 ×（语义不变：停播清内存队列、存档保留）；贴条上缘 2dp 进度线（`media.base`，暂停 40% alpha）。
 *
 * 手势：左右滑 ≥56dp 且同向速度 ≥800dp/s 切歌（左右各 16dp 边缘返回安全区不响应）；上滑 ≥48dp 或向上速度 ≥600dp/s 展开全屏；点击展开保留；未达阈值
 * `SpringSnap` 回位。滑动中条目随指位移、邻曲封面从反侧探入。
 */
@Composable
private fun NowPlayingBar(
    item: PlayerItem?,
    isPlaying: Boolean,
    isBuffering: Boolean,
    isRestored: Boolean,
    positionMs: Long,
    durationMs: Long,
    fallbackDurationMs: Long,
    previousArtworkUri: String?,
    nextArtworkUri: String?,
    onOpenNowPlaying: () -> Unit,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit,
) {
    val currentItem = item ?: return
    val colors = LocalCinefinColors.current
    val media = LocalMediaColors.current
    // 恢复态（重启后未点播放）没有播放器会话，位置与时长回落到快照 / 曲库元数据
    val currentMs = if (isRestored) currentItem.playbackPosition else positionMs
    val totalMs = durationMs.takeIf { it > 0L } ?: fallbackDurationMs
    val timeText = musicMiniBarTimeText(currentMs = currentMs, totalMs = totalMs)
    val progress = musicMiniBarProgressFraction(currentMs = currentMs, totalMs = totalMs)
    val density = LocalDensity.current
    var dragX by remember(currentItem.itemId) { mutableFloatStateOf(0f) }

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        // 平板形态（≥840dp）= 底部通栏：外边距 0、圆角 0（定稿 §2）
        val wide = maxWidth >= MusicMiniBarWideBreakpoint
        val shape = if (wide) RectangleShape else CinefinShapes.Glass
        val thumbPx = with(density) { MusicMiniBarArtworkSize.toPx() }
        Box(
            modifier =
                Modifier.fillMaxWidth()
                    .padding(
                        start = if (wide) 0.dp else CinefinSpacing.Space3,
                        end = if (wide) 0.dp else CinefinSpacing.Space3,
                        bottom = if (wide) 0.dp else CinefinSpacing.Space2,
                    )
                    .height(MusicMiniBarHeight)
                    .clip(shape)
                    .background(colors.glass)
                    .musicMiniBarTopHighlight()
                    .border(1.dp, colors.glassBorder, shape)
                    .pointerInput(currentItem.itemId, previousArtworkUri, nextArtworkUri) {
                        val touchSlop = viewConfiguration.touchSlop
                        val densityScale = density.density
                        while (true) {
                            val gesture =
                                awaitPointerEventScope {
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    // 左右各 16dp 是系统边缘返回安全区：整次手势不响应、不消费
                                    if (
                                        musicMiniBarEdgeGuardHit(
                                            startXDp = down.position.x / densityScale,
                                            widthDp = size.width / densityScale,
                                        )
                                    ) {
                                        return@awaitPointerEventScope null
                                    }
                                    dragX = 0f
                                    val tracker = VelocityTracker()
                                    tracker.addPosition(down.uptimeMillis, down.position)
                                    var totalX = 0f
                                    var totalY = 0f
                                    var horizontal = false
                                    drag(down.id) { change ->
                                        val delta = change.positionChange()
                                        totalX += delta.x
                                        totalY += delta.y
                                        tracker.addPosition(change.uptimeMillis, change.position)
                                        if (
                                            !horizontal &&
                                                abs(totalX) > touchSlop &&
                                                abs(totalX) > abs(totalY)
                                        ) {
                                            horizontal = true
                                        }
                                        if (horizontal) {
                                            change.consume()
                                            dragX = totalX
                                        }
                                    }
                                    val velocity = tracker.calculateVelocity()
                                    MusicMiniBarGesture(
                                        totalX = totalX,
                                        totalY = totalY,
                                        velocityX = velocity.x,
                                        velocityY = velocity.y,
                                        horizontal = horizontal,
                                    )
                                } ?: continue
                            if (gesture.horizontal) {
                                val target =
                                    musicMiniBarSwipeTarget(
                                        displacementDp = gesture.totalX / densityScale,
                                        velocityDpPerSec = gesture.velocityX / densityScale,
                                    )
                                when (target) {
                                    MusicMiniBarSwipe.Previous -> {
                                        dragX = 0f
                                        onPrevious()
                                    }
                                    MusicMiniBarSwipe.Next -> {
                                        dragX = 0f
                                        onNext()
                                    }
                                    // 未达阈值：SpringSnap 回位（逐帧写回同一状态，单一数据源）
                                    null -> {
                                        animate(
                                            initialValue = dragX,
                                            targetValue = 0f,
                                            initialVelocity = gesture.velocityX,
                                            animationSpec = CinefinMotion.SpringSnap,
                                        ) { value, _ ->
                                            dragX = value
                                        }
                                        dragX = 0f
                                    }
                                }
                            } else if (
                                musicMiniBarExpands(
                                    dragYDp = gesture.totalY / densityScale,
                                    velocityYDpPerSec = gesture.velocityY / densityScale,
                                )
                            ) {
                                onOpenNowPlaying()
                            }
                        }
                    }
        ) {
            // 邻曲封面从反侧探入：位移 = 手指位移（左滑露下一首、右滑露上一首）
            previousArtworkUri?.let { uri ->
                MusicMiniBarNeighborArtwork(
                    imageUri = uri,
                    modifier =
                        Modifier.align(Alignment.CenterStart).offset {
                            IntOffset((-thumbPx + dragX).roundToInt(), 0)
                        },
                )
            }
            nextArtworkUri?.let { uri ->
                MusicMiniBarNeighborArtwork(
                    imageUri = uri,
                    modifier =
                        Modifier.align(Alignment.CenterEnd).offset {
                            IntOffset((thumbPx + dragX).roundToInt(), 0)
                        },
                )
            }
            Row(
                modifier =
                    Modifier.fillMaxSize()
                        .graphicsLayer { translationX = dragX }
                        .padding(start = CinefinSpacing.Space3, end = CinefinSpacing.Space1),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 封面 + 标题 + 艺术家：整块可点，进入全屏播放界面（各按钮各自生效，不触发全屏）
                Row(
                    modifier =
                        Modifier.weight(1f)
                            .clip(CinefinShapes.Button)
                            .clickable(onClick = onOpenNowPlaying)
                            .padding(
                                horizontal = CinefinSpacing.Space1,
                                vertical = CinefinSpacing.Space1,
                            ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MusicMiniBarArtwork(
                        imageUri = currentItem.thumbnailUri,
                        title = currentItem.name,
                    )
                    Spacer(modifier = Modifier.width(CinefinSpacing.Space3))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = currentItem.name,
                            style = CinefinType.TitleSmall,
                            color = colors.onSurface,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Clip,
                            modifier =
                                Modifier.basicMarquee(
                                    repeatDelayMillis = MusicMiniBarMarqueeRepeatDelayMillis,
                                    velocity = MusicMiniBarMarqueeVelocity,
                                ),
                        )
                        currentItem.artist
                            ?.takeIf { it.isNotBlank() }
                            ?.let { artist ->
                                Text(
                                    text = artist,
                                    style = MusicMiniBarArtistStyle,
                                    color = colors.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                    }
                }
                // W5-T3（MUSIC-40）：迷你条播放键与全屏大键同一脉冲规格（1 → 0.92 → 1）
                val playPulse = rememberPlayPulseScale(isPlaying)
                Box(
                    modifier =
                        Modifier.graphicsLayer {
                            scaleX = playPulse.value
                            scaleY = playPulse.value
                        }
                ) {
                    CinefinIconButton(onClick = onPlayPause) { tint ->
                        Icon(
                            painter =
                                painterResource(
                                    if (isPlaying) CoreR.drawable.ic_pause
                                    else CoreR.drawable.ic_play
                                ),
                            contentDescription = if (isPlaying) "暂停" else "播放",
                            tint = tint,
                            modifier = Modifier.size(20.dp),
                        )
                    }
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
            // 极细进度线：贴条上缘 2dp，media.base（调色板覆盖后随封面色），暂停时 alpha 降到 40%（tween(Fast) 过渡）
            // W5-T8（MUSIC-49）：缓冲中进度线转 indeterminate（一段 30% 宽的高亮匀速扫过），播放键仍是暂停态。
            val pausedAlpha = if (isPlaying) 1f else MusicMiniBarPausedProgressAlpha
            val progressAlpha =
                animateFloatAsState(
                    targetValue = pausedAlpha,
                    animationSpec = tween(CinefinMotion.Fast),
                    label = "musicMiniBarProgressAlpha",
                )
            Box(
                modifier =
                    Modifier.align(Alignment.TopStart)
                        .fillMaxWidth()
                        .height(MusicMiniBarProgressHeight)
                        .semantics {
                            contentDescription =
                                if (isBuffering) "缓冲中 $timeText" else "播放进度 $timeText"
                        }
            ) {
                if (isBuffering) {
                    val reduceMotion = CinefinMotion.reduceMotion()
                    val transition = rememberInfiniteTransition(label = "musicMiniBarBuffering")
                    val phase by
                        transition.animateFloat(
                            initialValue = 0f,
                            targetValue = 1f,
                            animationSpec =
                                infiniteRepeatable(
                                    tween(
                                        durationMillis = MusicMiniBarBufferingCycleMillis,
                                        easing = LinearEasing,
                                    )
                                ),
                            label = "musicMiniBarBufferingPhase",
                        )
                    val bufferingPhase = if (reduceMotion) 0.35f else phase
                    Box(
                        modifier =
                            Modifier.fillMaxSize()
                                .graphicsLayer { alpha = progressAlpha.value }
                                .drawBehind {
                                    val segment = size.width * MusicMiniBarBufferingSegmentFraction
                                    val left = (size.width + segment) * bufferingPhase - segment
                                    drawRect(
                                        color = media.base,
                                        topLeft = Offset(left, 0f),
                                        size = Size(segment, size.height),
                                    )
                                }
                    )
                } else {
                    Box(
                        modifier =
                            Modifier.fillMaxHeight()
                                .fillMaxWidth(progress)
                                .graphicsLayer { alpha = progressAlpha.value }
                                .background(media.base)
                    )
                }
            }
        }
    }
}

/** 40dp 封面缩略（`TwoXs` 圆角；无图时露出域/封面色的音符占位，不会出现黑块）。 */
@Composable
private fun MusicMiniBarArtwork(imageUri: String?, title: String) {
    val media = LocalMediaColors.current
    Box(
        modifier =
            Modifier.size(MusicMiniBarArtworkSize)
                .clip(CinefinShapes.TwoXs)
                .background(media.container),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(CoreR.drawable.ic_music),
            contentDescription = null,
            tint = media.bright,
            modifier = Modifier.size(MusicMiniBarArtworkSize * 0.5f),
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

/** 滑切歌时从反侧探入的邻曲封面（同 40dp / `TwoXs`，只用封面取色与位图）。 */
@Composable
private fun MusicMiniBarNeighborArtwork(imageUri: String, modifier: Modifier) {
    AsyncImage(
        model = imageUri,
        contentDescription = null,
        modifier = modifier.size(MusicMiniBarArtworkSize).clip(CinefinShapes.TwoXs),
        contentScale = ContentScale.Crop,
    )
}

/** 顶部内高光：0.5dp 白 6%（定稿 §2；与 W4 玻璃容器同一手法——`drawBehind` 贴顶画一条内线）。 */
private fun Modifier.musicMiniBarTopHighlight(): Modifier = drawBehind {
    drawRect(
        color = Color.White.copy(alpha = MusicMiniBarTopHighlightAlpha),
        size = Size(size.width, MusicMiniBarTopHighlightHeight.toPx()),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MusicBatchActionBar(
    selectedCount: Int,
    playPending: Boolean,
    isPlaylistDetail: Boolean,
    /** W59：专辑多选（专辑列表 Tab）= 只提供「下载整张」。 */
    albumsOnly: Boolean = false,
    isEnabled: (MusicBatchAction) -> Boolean,
    onPlay: () -> Unit,
    onDownload: () -> Unit,
    onFavorite: () -> Unit,
    onAddToPlaylist: () -> Unit,
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
            enabled = !playPending && isEnabled(MusicBatchAction.PLAY),
            label = "播放",
            iconRes = CoreR.drawable.ic_play,
            loading = playPending,
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
            enabled = isEnabled(MusicBatchAction.ADD_TO_PLAYLIST),
            label = "加入歌单",
            iconRes = CoreR.drawable.ic_plus,
            onClick = onAddToPlaylist,
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
    loading: Boolean = false,
    onClick: () -> Unit,
) {
    val accessibility =
        musicBatchActionAccessibility(
            label = label,
            enabled = enabled,
            loading = loading,
        )
    CinefinIconButton(
        enabled = enabled,
        onClick = onClick,
        modifier =
            Modifier.clearAndSetSemantics {
                role = Role.Button
                contentDescription = accessibility.contentDescription
                accessibility.stateDescription?.let { description ->
                    stateDescription = description
                }
                if (accessibility.enabled) {
                    semanticsOnClick(label = accessibility.contentDescription) {
                        onClick()
                        true
                    }
                } else {
                    disabled()
                }
            },
    ) { tint ->
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                color = tint,
                strokeWidth = 2.dp,
            )
        } else {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = label,
                tint = tint,
                modifier = Modifier.size(20.dp),
            )
        }
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

/**
 * 曲目长按上下文菜单（W5 T1 · MUSIC-47）：`ModalBottomSheet` 容器与队列面板同参数（`surfaceContainer` + 拖拽柄）。
 *
 * 五动作：播放下一首 / 加到队尾 / 加入播放列表 / 音频信息（W5-T6 起启用，[onAudioInfo] 传 null 时不渲染）/ 下载 ·
 * 离线。多选批量模式改由工具栏「多选」入口进入，长按不再进多选。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SongContextSheet(
    song: MusicSong,
    downloadAction: SongDownloadMenuAction,
    downloadStatus: DownloadTaskStatus?,
    onDismiss: () -> Unit,
    onPlayNext: () -> Unit,
    onEnqueueAtEnd: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onDownload: () -> Unit,
    onAudioInfo: (() -> Unit)? = null,
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
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = CinefinSpacing.Space4)) {
            Column(modifier = Modifier.padding(horizontal = CinefinSpacing.Space5)) {
                Text(
                    text = song.name,
                    style = CinefinType.TitleMedium,
                    color = colors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val subtitle =
                    listOfNotNull(
                            song.artist?.takeIf { it.isNotBlank() },
                            song.albumName.takeIf { it.isNotBlank() },
                        )
                        .joinToString(" · ")
                if (subtitle.isNotBlank()) {
                    Text(
                        text = subtitle,
                        style = CinefinType.BodySmall,
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(modifier = Modifier.height(CinefinSpacing.Space3))
            SongContextAction(
                icon = CoreR.drawable.ic_skip_forward,
                label = "播放下一首",
                onClick = onPlayNext,
            )
            SongContextAction(
                icon = CoreR.drawable.ic_playlist,
                label = "加到队尾",
                onClick = onEnqueueAtEnd,
            )
            SongContextAction(
                icon = CoreR.drawable.ic_plus,
                label = "加入歌单",
                onClick = onAddToPlaylist,
            )
            // 音频信息（MUSIC-34）：W5-T6 起启用
            if (onAudioInfo != null) {
                SongContextAction(
                    icon = CoreR.drawable.ic_info,
                    label = "音频信息",
                    onClick = onAudioInfo,
                )
            }
            val contextDownloadLabel = songContextDownloadLabel(downloadAction)
            if (contextDownloadLabel != null) {
                SongContextAction(
                    icon = CoreR.drawable.ic_download,
                    label = contextDownloadLabel,
                    trailing = songContextDownloadTrailing(downloadAction, downloadStatus),
                    onClick = onDownload,
                )
            }
        }
    }
}

@Composable
private fun SongContextAction(
    icon: Int,
    label: String,
    onClick: () -> Unit,
    trailing: String? = null,
) {
    val colors = LocalCinefinColors.current
    val media = LocalMediaColors.current
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = CinefinSpacing.Space5)
                .height(52.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            tint = colors.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Spacer(modifier = Modifier.width(CinefinSpacing.Space3))
        Text(
            text = label,
            style = CinefinType.BodyMedium,
            color = colors.onSurface,
            modifier = Modifier.weight(1f),
            maxLines = 1,
        )
        if (trailing != null) {
            Text(
                text = trailing,
                style = CinefinType.LabelSmall,
                color = media.bright,
                maxLines = 1,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun QueueSheet(
    queue: MusicQueue,
    onDismiss: () -> Unit,
    onJump: (Int) -> Unit,
    onJumpUpNext: (Int) -> Unit,
    onMove: (Int, Int) -> Unit,
    onRemove: (Int) -> Unit,
    onRemoveUpNext: (Int) -> Unit,
    onClearUpNext: () -> Unit,
    /** W5-T3：左滑移除的撤销（原位插回）。 */
    onUndoRemove: ((Int, PlayerItem) -> Unit)? = null,
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
                text = "「接下来播放」先于主队列消费；左滑或点「✕」移除，长按「≡」拖拽排序",
                style = CinefinType.BodySmall,
                color = colors.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(CinefinSpacing.Space3))
            QueueListBody(
                queue = queue,
                onJump = onJump,
                onJumpUpNext = onJumpUpNext,
                onMove = onMove,
                onRemove = onRemove,
                onRemoveUpNext = onRemoveUpNext,
                onClearUpNext = onClearUpNext,
                onUndoRemove = onUndoRemove,
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
            Spacer(modifier = Modifier.height(CinefinSpacing.Space4))
        }
    }
}

/**
 * 队列编辑列表体（W5 T1；W5 T5 双分区；W5 T3 动效）：队列 sheet 与宽屏播放页右栏「队列」Tab 共用，行为与视觉同一份。
 *
 * 分区：「接下来播放」（手动插入、优先消费、播完即出）在上，主队列在下。 主队列：长按右侧「≡」拖拽排序、点行跳转、左滑或 ✕ 移除；接下来播放：更轻视觉权重 + 左滑移除。两种行共用 72dp
 * 阈值与「未过阈值回位」口径。
 *
 * W5-T3（MUSIC-42）：拖起条目 scale 1.02（`SpringControlIn`）+ 6dp 等效阴影 + `zIndex` 1，其余条目让位由 `animateItem` 的
 * `SpringControlInOffset` 落位弹簧承担；左滑越过阈值先滑出再删除，主队列行给 3s「撤销」（原位插回）。
 */
@Composable
internal fun QueueListBody(
    queue: MusicQueue,
    onJump: (Int) -> Unit,
    onMove: (Int, Int) -> Unit,
    onRemove: (Int) -> Unit,
    modifier: Modifier = Modifier,
    onJumpUpNext: ((Int) -> Unit)? = null,
    onRemoveUpNext: ((Int) -> Unit)? = null,
    onClearUpNext: (() -> Unit)? = null,
    /** W5-T3：左滑移除的撤销（原位插回）；null = 不提供撤销（只滑出 + 删除）。 */
    onUndoRemove: ((Int, PlayerItem) -> Unit)? = null,
) {
    val colors = LocalCinefinColors.current
    val media = LocalMediaColors.current
    val density = LocalDensity.current
    val rowHeightPx = with(density) { QueueRowHeight.toPx() }
    val swipeThresholdPx = with(density) { QueueSwipeRemoveThreshold.toPx() }
    val liftElevationPx = with(density) { MusicQueueLiftElevationDp.dp.toPx() }
    var draggingIndex by remember(queue) { mutableStateOf<Int?>(null) }
    var dragOffset by remember(queue) { mutableFloatStateOf(0f) }
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    fun removeWithUndo(index: Int, item: PlayerItem) {
        onRemove(index)
        if (onUndoRemove == null) return
        scope.launch {
            // 定稿二 §8：撤销窗口 3s（超时即消失，撤销 = 原位插回）
            val result =
                withTimeoutOrNull(MusicQueueSwipeUndoMillis) {
                    snackbarHostState.showSnackbar(
                        message = "已移除「${item.name}」",
                        actionLabel = "撤销",
                        duration = SnackbarDuration.Indefinite,
                    )
                }
            if (result == SnackbarResult.ActionPerformed) onUndoRemove(index, item)
        }
    }

    Box(modifier = modifier) {
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            if (queue.upNext.isNotEmpty() || queue.upNextPlaying != null) {
                item(key = "up-next-header") {
                    UpNextSectionHeader(count = queue.upNext.size, onClear = onClearUpNext)
                }
                // 正在播放的「接下来播放」条目已出列，但仍在时间线上：按「正在播放」展示，避免用户在队列里找不到当前曲
                val playing = queue.upNextPlaying
                if (playing != null) {
                    item(key = "up-next-playing") {
                        UpNextRow(
                            title = playing.name,
                            position = -1,
                            showDivider = true,
                            enabled = false,
                            isCurrent = true,
                            unavailable = playing.unavailable,
                            onJump = {},
                            onRemove = {},
                        )
                    }
                }
                itemsIndexed(
                    items = queue.upNext,
                    key = { index, item -> "up-next-$index-${item.itemId}" },
                ) { index, item ->
                    UpNextRow(
                        title = item.name,
                        position = index,
                        showDivider = index != queue.upNext.lastIndex,
                        enabled = onRemoveUpNext != null,
                        unavailable = item.unavailable,
                        onJump = { onJumpUpNext?.invoke(index) },
                        onRemove = { onRemoveUpNext?.invoke(index) },
                    )
                }
                item(key = "main-queue-header") {
                    Text(
                        text = "播放队列",
                        style = CinefinType.LabelMedium,
                        color = colors.onSurfaceVariant,
                        modifier =
                            Modifier.fillMaxWidth()
                                .padding(
                                    top = CinefinSpacing.Space4,
                                    bottom = CinefinSpacing.Space1,
                                ),
                    )
                }
            }
            itemsIndexed(
                items = queue.items,
                key = { index, item -> "main-$index-${item.itemId}" },
            ) { index, item ->
                val isDragging = draggingIndex == index
                val isCurrent =
                    (index == queue.currentIndex && queue.upNextPlaying == null) ||
                        item.itemId == queue.upNextPlaying?.itemId
                var swipeOffsetX by remember(item.itemId) { mutableFloatStateOf(0f) }
                var rowWidthPx by remember(item.itemId) { mutableIntStateOf(0) }
                // 定稿二 §8：抬升 SpringControlIn、落位 SpringSnap
                val liftScale =
                    animateFloatAsState(
                        targetValue = if (isDragging) MusicQueueLiftScale else 1f,
                        animationSpec =
                            if (isDragging) CinefinMotion.SpringControlIn
                            else CinefinMotion.SpringSnap,
                        label = "queueLiftScale",
                    )
                CinefinListRow(
                    title = item.name,
                    // W5-T8（MUSIC-49）：本地文件缺失 → 标灰 + 「不可用」徽标；条目保留，仍可 ✕ / 左滑移除。
                    badge = if (item.unavailable) "不可用" else null,
                    isCurrent = isCurrent,
                    showDivider = index != queue.items.lastIndex,
                    onClick = { onJump(index) },
                    modifier =
                        Modifier.animateItem(placementSpec = CinefinMotion.SpringControlInOffset)
                            .zIndex(if (isDragging) 1f else 0f)
                            .onSizeChanged { rowWidthPx = it.width }
                            .graphicsLayer {
                                translationY = if (isDragging) dragOffset else 0f
                                translationX = swipeOffsetX
                                scaleX = liftScale.value
                                scaleY = liftScale.value
                                alpha = if (item.unavailable) QueueUnavailableRowAlpha else 1f
                                if (isDragging) {
                                    shape = RectangleShape
                                    shadowElevation = liftElevationPx
                                }
                            }
                            // W5 T5r：主队列行左滑移除，阈值 / 回位口径与「接下来播放」行一致。
                            // 正在播放行同样可滑（与点「✕」等价：移除后播放推进到下一时间线条目）。
                            // W5 T3：越过阈值先滑出再删除；未过阈值 SpringSnap 回位。
                            .pointerInput(index, item.itemId, queue.items.size) {
                                detectHorizontalDragGestures(
                                    onDragEnd = {
                                        if (
                                            musicQueueSwipeRemoves(
                                                offsetPx = swipeOffsetX,
                                                thresholdPx = swipeThresholdPx,
                                            )
                                        ) {
                                            scope.launch {
                                                animate(
                                                    initialValue = swipeOffsetX,
                                                    targetValue = -rowWidthPx.toFloat(),
                                                    animationSpec =
                                                        tween(
                                                            CinefinMotion.Fast,
                                                            easing = CinefinMotion.Accelerate,
                                                        ),
                                                ) { value, _ ->
                                                    swipeOffsetX = value
                                                }
                                                removeWithUndo(index, item)
                                            }
                                        } else {
                                            scope.launch {
                                                animate(
                                                    initialValue = swipeOffsetX,
                                                    targetValue = 0f,
                                                    animationSpec = CinefinMotion.SpringSnap,
                                                ) { value, _ ->
                                                    swipeOffsetX = value
                                                }
                                            }
                                        }
                                    },
                                    onDragCancel = {
                                        scope.launch {
                                            animate(
                                                initialValue = swipeOffsetX,
                                                targetValue = 0f,
                                                animationSpec = CinefinMotion.SpringSnap,
                                            ) { value, _ ->
                                                swipeOffsetX = value
                                            }
                                        }
                                    },
                                    onHorizontalDrag = { change, amount ->
                                        change.consume()
                                        swipeOffsetX =
                                            (swipeOffsetX + amount).coerceIn(
                                                -swipeThresholdPx * 2,
                                                0f,
                                            )
                                    },
                                )
                            }
                            .background(
                                if (isDragging) colors.surfaceContainerHigh else Color.Transparent
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
                                                    val to =
                                                        musicQueueDropTarget(
                                                            fromIndex = from,
                                                            dragOffsetPx = dragOffset,
                                                            rowHeightPx = rowHeightPx,
                                                            lastIndex = queue.items.lastIndex,
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
        CinefinSnackbarHost(
            hostState = snackbarHostState,
            modifier =
                Modifier.align(Alignment.BottomCenter).padding(horizontal = CinefinSpacing.Space4),
        )
    }
}

/** 「接下来播放」分区标题：更轻权重（LabelMedium + onSurfaceVariant），右侧「清空」只清这一个分区。 */
@Composable
private fun UpNextSectionHeader(count: Int, onClear: (() -> Unit)?) {
    val colors = LocalCinefinColors.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = CinefinSpacing.Space1),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "接下来播放（$count）",
            style = CinefinType.LabelMedium,
            color = colors.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (onClear != null) {
            Text(
                text = "清空",
                style = CinefinType.LabelMedium,
                color = colors.onSurfaceVariant.copy(alpha = 0.72f),
                modifier = Modifier.clickable(onClick = onClear).padding(CinefinSpacing.Space2),
            )
        }
    }
}

/** 左滑移除阈值（72dp）：超过即删条目，未超过直接回位（动效 token 归 T3；主队列行与「接下来播放」行共用）。 */
private val QueueSwipeRemoveThreshold = 72.dp

/** W5-T8（MUSIC-49）：队列里「不可用」条目的整体透明度（标灰，仍可操作）。 */
private const val QueueUnavailableRowAlpha = 0.55f

/** 「接下来播放」行（W5 T5）：次要字号 + `onSurfaceVariant` 的轻视觉权重；左滑移除，✕ 按钮兜底可访问性。 */
@Composable
private fun UpNextRow(
    title: String,
    position: Int,
    showDivider: Boolean,
    enabled: Boolean,
    onJump: () -> Unit,
    onRemove: () -> Unit,
    isCurrent: Boolean = false,
    /** W5-T8（MUSIC-49）：本地文件缺失，标灰 + 「不可用」。 */
    unavailable: Boolean = false,
) {
    val colors = LocalCinefinColors.current
    val media = LocalMediaColors.current
    val thresholdPx = with(LocalDensity.current) { QueueSwipeRemoveThreshold.toPx() }
    var translationX by remember(title, position) { mutableFloatStateOf(0f) }
    var rowWidthPx by remember(title, position) { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    Column {
        Row(
            modifier =
                Modifier.fillMaxWidth()
                    .onSizeChanged { rowWidthPx = it.width }
                    .offset { IntOffset(translationX.roundToInt(), 0) }
                    .pointerInput(title, position, enabled) {
                        if (!enabled) return@pointerInput
                        detectHorizontalDragGestures(
                            onDragEnd = {
                                if (
                                    musicQueueSwipeRemoves(
                                        offsetPx = translationX,
                                        thresholdPx = thresholdPx,
                                    )
                                ) {
                                    // W5-T3（MUSIC-42）：越过阈值先滑出再删除
                                    scope.launch {
                                        animate(
                                            initialValue = translationX,
                                            targetValue = -rowWidthPx.toFloat(),
                                            animationSpec =
                                                tween(
                                                    CinefinMotion.Fast,
                                                    easing = CinefinMotion.Accelerate,
                                                ),
                                        ) { value, _ ->
                                            translationX = value
                                        }
                                        onRemove()
                                    }
                                } else {
                                    scope.launch {
                                        animate(
                                            initialValue = translationX,
                                            targetValue = 0f,
                                            animationSpec = CinefinMotion.SpringSnap,
                                        ) { value, _ ->
                                            translationX = value
                                        }
                                    }
                                }
                            },
                            onDragCancel = {
                                scope.launch {
                                    animate(
                                        initialValue = translationX,
                                        targetValue = 0f,
                                        animationSpec = CinefinMotion.SpringSnap,
                                    ) { value, _ ->
                                        translationX = value
                                    }
                                }
                            },
                            onHorizontalDrag = { change, amount ->
                                change.consume()
                                translationX =
                                    (translationX + amount).coerceIn(-thresholdPx * 2, 0f)
                            },
                        )
                    }
                    .clickable(onClick = onJump)
                    .height(48.dp)
                    .padding(start = CinefinSpacing.Space5, end = CinefinSpacing.Space2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "↳",
                style = CinefinType.BodySmall,
                color = colors.onSurfaceFaint,
                modifier = Modifier.width(28.dp),
            )
            Text(
                text = title,
                style = CinefinType.BodySmall,
                color =
                    when {
                        unavailable -> colors.onSurfaceFaint
                        isCurrent -> media.bright
                        else -> colors.onSurfaceVariant
                    },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (unavailable && !isCurrent) {
                Text(
                    text = "不可用",
                    style = CinefinType.LabelSmall,
                    color = colors.onSurfaceFaint,
                )
            }
            if (isCurrent) {
                Text(
                    text = "正在播放",
                    style = CinefinType.BodySmall,
                    color = media.bright,
                )
            } else if (enabled) {
                CinefinIconButton(onClick = onRemove) { tint ->
                    Icon(
                        painter = painterResource(CoreR.drawable.ic_close),
                        contentDescription = "从接下来播放移除",
                        tint = tint,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
        if (showDivider) {
            HorizontalDivider(
                color = colors.onSurfaceFaint.copy(alpha = 0.12f),
                modifier = Modifier.padding(start = CinefinSpacing.Space5),
            )
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
private fun MusicSourceLoadErrorNotice(
    title: String,
    message: String,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalCinefinColors.current
    val media = LocalMediaColors.current
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .background(colors.surfaceContainer)
                .padding(horizontal = CinefinSpacing.Space4, vertical = CinefinSpacing.Space2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = CinefinType.LabelLarge, color = colors.onSurface)
            Text(
                text = message,
                style = CinefinType.BodySmall,
                color = colors.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = "重试",
            style = CinefinType.LabelMedium,
            color = media.bright,
            modifier = Modifier.clickable(onClick = onRetry).padding(CinefinSpacing.Space2),
        )
        Text(
            text = "关闭",
            style = CinefinType.LabelMedium,
            color = colors.onSurfaceVariant,
            modifier = Modifier.clickable(onClick = onDismiss).padding(CinefinSpacing.Space2),
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

/**
 * 睡眠定时面板（W55 统一）：与视频 / 播放器共享 core 选择组件与进程级状态源；选中即生效并关闭面板。
 *
 * W5-T6：档位改 15/30/45/60/90（core 组件同源），并在下方补音乐专属「播完当前曲目停止」。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SleepTimerSheet(
    state: SleepTimerController.State,
    onSelect: (Int?) -> Unit,
    onStopAfterCurrentTrack: () -> Unit,
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
            Spacer(modifier = Modifier.height(CinefinSpacing.Space2))
            // W5-T6 · MUSIC-33：音乐专属「播完当前曲目停止」（视频页不适用该语义，故不放进 core 共享组件）
            CinefinListRow(
                title = "播完当前曲目停止",
                secondary = if (state.stopAfterCurrentTrack) "已设置 · 播完本曲后暂停" else "不打断当前曲目，播完这首再暂停",
                isCurrent = state.stopAfterCurrentTrack,
                onClick = onStopAfterCurrentTrack,
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
