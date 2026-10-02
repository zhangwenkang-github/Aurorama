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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import coil3.compose.AsyncImage
import com.zhangwenkang.cinefin.core.R as CoreR
import com.zhangwenkang.cinefin.core.presentation.components.CinefinButton
import com.zhangwenkang.cinefin.core.presentation.components.CinefinButtonSize
import com.zhangwenkang.cinefin.core.presentation.components.CinefinButtonVariant
import com.zhangwenkang.cinefin.core.presentation.components.CinefinEmptyState
import com.zhangwenkang.cinefin.core.presentation.components.CinefinIconButton
import com.zhangwenkang.cinefin.core.presentation.components.CinefinListRow
import com.zhangwenkang.cinefin.core.presentation.components.CinefinPageTopBar
import com.zhangwenkang.cinefin.core.presentation.components.CinefinSegmentedControl
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinShapes
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinSpacing
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinTheme
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinType
import com.zhangwenkang.cinefin.core.presentation.theme.ContentDomain
import com.zhangwenkang.cinefin.core.presentation.theme.LocalCinefinColors
import com.zhangwenkang.cinefin.core.presentation.theme.LocalMediaColors
import com.zhangwenkang.cinefin.music.R
import com.zhangwenkang.cinefin.music.data.MusicAlbum
import com.zhangwenkang.cinefin.music.data.MusicArtist
import com.zhangwenkang.cinefin.music.data.MusicPlaylist
import com.zhangwenkang.cinefin.music.data.MusicSleepTimer
import com.zhangwenkang.cinefin.music.data.MusicSong
import com.zhangwenkang.cinefin.music.data.formatSleepRemaining
import com.zhangwenkang.cinefin.player.core.domain.models.MusicQueue
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
    val sleepState by viewModel.sleepTimerState.collectAsState()
    val lyricsOverlayState by viewModel.lyricsOverlayState.collectAsState()
    var queueSheetOpen by rememberSaveable { mutableStateOf(false) }
    var sleepSheetOpen by rememberSaveable { mutableStateOf(false) }
    var nowPlayingOpen by rememberSaveable { mutableStateOf(false) }
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

    // 系统返回键与左上角返回一致（W3-R3b 缺陷 1）：详情（专辑 / 艺术家 / 歌单）内先回音乐主界面，
    // 而不是直接退回首页；不在详情时交给 NavHost 正常返回。
    BackHandler(enabled = state.detail != null) { viewModel.closeDetail() }

    CinefinTheme(domain = ContentDomain.Music, surfaceBackground = false) {
        val colors = LocalCinefinColors.current
        Box(modifier = modifier.fillMaxSize().background(colors.surface)) {
            Column(modifier = Modifier.fillMaxSize()) {
                MusicHeader(
                    state = state,
                    sleepState = sleepState,
                    onBack = viewModel::closeDetail,
                    onOpenDrawer = onOpenDrawer,
                    onOpenFavorites = viewModel::openFavorites,
                    onOpenRecent = viewModel::openRecent,
                    onOpenSleep = { sleepSheetOpen = true },
                )
                if (state.detail == null) {
                    MusicTabs(selected = state.tab, onSelect = viewModel::selectTab)
                    Spacer(modifier = Modifier.height(CinefinSpacing.Space3))
                }

                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
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
                                onSongClick = viewModel::playSong,
                                onPlayNext = viewModel::playNext,
                                onToggleFavorite = viewModel::toggleFavorite,
                            )
                        else ->
                            LibraryPane(
                                state = state,
                                currentItemId = queue?.currentItem?.itemId,
                                onAlbumClick = viewModel::openAlbum,
                                onArtistClick = viewModel::openArtist,
                                onPlaylistClick = viewModel::openPlaylist,
                                onSongClick = viewModel::playSong,
                                onPlayNext = viewModel::playNext,
                                onToggleFavorite = viewModel::toggleFavorite,
                            )
                    }
                }

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
                    onPrevious = viewModel::skipToPrevious,
                    onPlayPause = viewModel::togglePlayPause,
                    onNext = viewModel::skipToNext,
                    onOpenLyrics = viewModel::openLyrics,
                    onOpenQueue = { queueSheetOpen = true },
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
                onSelectLanguage = viewModel::selectLyricsLanguage,
                onToggleBilingual = viewModel::toggleLyricsBilingual,
                onToggleFollow = viewModel::toggleLyricsFollow,
                onLineClick = viewModel::seekToLyricLine,
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
    sleepState: MusicSleepTimer.State,
    onBack: () -> Unit,
    onOpenDrawer: (() -> Unit)?,
    onOpenFavorites: () -> Unit,
    onOpenRecent: () -> Unit,
    onOpenSleep: () -> Unit,
) {
    val detail = state.detail
    val media = LocalMediaColors.current
    // W8-R3：与媒体库 / 书架共用 `CinefinPageTopBar`（56dp + statusBarsPadding + 左侧 ic_menu「打开侧栏」），
    // 修掉旧版 72dp 无 inset 导致的"按钮被状态栏压住"。详情（专辑 / 艺术家 / 歌单）改回返回键 + 详情标题。
    CinefinPageTopBar(
        title = detail?.title ?: "音乐",
        subtitle =
            when {
                detail != null -> "共 ${detail.songs.size} 首曲目"
                state.tab == MusicTab.ALBUMS -> "共 ${state.albums.size} 张专辑"
                state.tab == MusicTab.ARTISTS -> "共 ${state.artists.size} 位艺术家"
                state.tab == MusicTab.SONGS -> "共 ${state.songs.size} 首歌曲"
                else -> "共 ${state.playlists.size} 个歌单"
            },
        onOpenDrawer = onOpenDrawer,
        onBack = if (detail != null) onBack else null,
        actions = {
            CinefinIconButton(onClick = onOpenFavorites) { tint ->
                Icon(
                    painter =
                        painterResource(
                            if (detail is MusicDetail.Favorites) {
                                CoreR.drawable.ic_heart_filled
                            } else {
                                CoreR.drawable.ic_heart
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
private fun MusicTabs(selected: MusicTab, onSelect: (MusicTab) -> Unit) {
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
        modifier =
            Modifier.padding(horizontal = CinefinSpacing.Space4)
                .widthIn(max = 640.dp)
                .fillMaxWidth(),
    )
}

@Composable
private fun LibraryPane(
    state: MusicModeViewModel.UiState,
    currentItemId: UUID?,
    onAlbumClick: (MusicAlbum) -> Unit,
    onArtistClick: (MusicArtist) -> Unit,
    onPlaylistClick: (MusicPlaylist) -> Unit,
    onSongClick: (MusicSong) -> Unit,
    onPlayNext: (MusicSong) -> Unit,
    onToggleFavorite: (MusicSong) -> Unit,
) {
    when (state.tab) {
        MusicTab.ALBUMS -> AlbumList(albums = state.albums, onAlbumClick = onAlbumClick)
        MusicTab.ARTISTS -> ArtistList(artists = state.artists, onArtistClick = onArtistClick)
        MusicTab.SONGS ->
            SongList(
                songs = state.songs,
                currentItemId = currentItemId,
                showAlbum = true,
                onSongClick = onSongClick,
                onPlayNext = onPlayNext,
                onToggleFavorite = onToggleFavorite,
            )
        MusicTab.PLAYLISTS ->
            PlaylistList(playlists = state.playlists, onPlaylistClick = onPlaylistClick)
    }
}

@Composable
private fun DetailPane(
    detail: MusicDetail,
    currentItemId: UUID?,
    onSongClick: (MusicSong) -> Unit,
    onPlayNext: (MusicSong) -> Unit,
    onToggleFavorite: (MusicSong) -> Unit,
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
        emptyTitle =
            when (detail) {
                is MusicDetail.Favorites -> "还没有收藏的曲目"
                is MusicDetail.Recent -> "还没有最近播放"
                else -> "这里还没有可播放的曲目"
            },
        onSongClick = onSongClick,
        onPlayNext = onPlayNext,
        onToggleFavorite = onToggleFavorite,
    )
}

@Composable
private fun AlbumList(albums: List<MusicAlbum>, onAlbumClick: (MusicAlbum) -> Unit) {
    if (albums.isEmpty()) {
        EmptyHint(title = "音乐库里还没有专辑", message = "在服务器添加音乐后点「刷新」重新拉取")
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(items = albums, key = { album -> album.key }) { album ->
            CinefinListRow(
                title = album.name,
                secondary =
                    listOfNotNull(album.artist, "${album.songs.size} 首")
                        .filter { it.isNotBlank() }
                        .joinToString(" · "),
                onClick = { onAlbumClick(album) },
                leading = {
                    ArtworkThumb(imageUri = album.imageUri, placeholder = "♪", title = album.name)
                },
            )
        }
    }
}

@Composable
private fun ArtistList(artists: List<MusicArtist>, onArtistClick: (MusicArtist) -> Unit) {
    if (artists.isEmpty()) {
        EmptyHint(title = "音乐库里还没有艺术家")
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(items = artists, key = { artist -> artist.key }) { artist ->
            CinefinListRow(
                title = artist.name,
                secondary = "${artist.songs.size} 首",
                onClick = { onArtistClick(artist) },
                leading = {
                    ArtworkThumb(
                        imageUri = artist.imageUri,
                        placeholder = "♪",
                        title = artist.name,
                    )
                },
            )
        }
    }
}

@Composable
private fun PlaylistList(
    playlists: List<MusicPlaylist>,
    onPlaylistClick: (MusicPlaylist) -> Unit,
) {
    if (playlists.isEmpty()) {
        EmptyHint(title = "服务器上没有歌单")
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(items = playlists, key = { playlist -> playlist.id.toString() }) { playlist ->
            CinefinListRow(
                title = playlist.name,
                secondary = playlist.songCount?.let { count -> "$count 首" } ?: "歌单",
                onClick = { onPlaylistClick(playlist) },
                leading = {
                    ArtworkThumb(
                        imageUri = playlist.imageUri,
                        placeholder = "≡",
                        title = playlist.name,
                    )
                },
            )
        }
    }
}

/** 46–56dp 缩略图（§8.4：圆角 8dp）；无图时用字符占位，不引入额外色块。 */
@Composable
private fun ArtworkThumb(imageUri: String?, placeholder: String, title: String) {
    val colors = LocalCinefinColors.current
    Box(
        modifier =
            Modifier.size(48.dp).clip(CinefinShapes.Xs).background(colors.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        if (imageUri == null) {
            Text(
                text = placeholder,
                style = CinefinType.TitleMedium,
                color = colors.onSurfaceVariant,
            )
        } else {
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
    emptyTitle: String = "这里还没有可播放的曲目",
    onSongClick: (MusicSong) -> Unit,
    onPlayNext: (MusicSong) -> Unit,
    onToggleFavorite: (MusicSong) -> Unit,
) {
    if (songs.isEmpty()) {
        EmptyHint(title = emptyTitle)
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        // 歌单允许同一首曲目出现多次，key 里带序号避免重复 key 崩溃
        itemsIndexed(items = songs, key = { index, song -> "$index-${song.itemId}" }) { index, song
            ->
            SongRow(
                index = index + 1,
                song = song,
                subtitle = if (showAlbum) song.albumName else null,
                isCurrent = song.itemId == currentItemId,
                onClick = { onSongClick(song) },
                onPlayNext = { onPlayNext(song) },
                onToggleFavorite = { onToggleFavorite(song) },
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
    onClick: () -> Unit,
    onPlayNext: () -> Unit,
    onToggleFavorite: () -> Unit,
) {
    val colors = LocalCinefinColors.current
    val media = LocalMediaColors.current
    var menuOpen by remember { mutableStateOf(false) }
    CinefinListRow(
        title = song.name,
        secondary =
            listOfNotNull(subtitle, formatDuration(song.runtimeTicks))
                .filter { it.isNotBlank() }
                .joinToString(" · ")
                .ifBlank { null },
        isCurrent = isCurrent,
        onClick = onClick,
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
    sleepState: MusicSleepTimer.State,
    onOpenNowPlaying: () -> Unit,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onOpenLyrics: () -> Unit,
    onOpenQueue: () -> Unit,
) {
    val item = queue?.currentItem ?: return
    val colors = LocalCinefinColors.current
    // 恢复态（重启后未点播放）没有播放器会话，位置与时长回落到快照 / 曲库元数据
    val currentMs = if (isRestored) item.playbackPosition else positionMs
    val totalMs = durationMs.takeIf { it > 0L } ?: fallbackDurationMs
    val timeText = buildString {
        append(formatPositionMs(currentMs))
        append(" / ")
        append(if (totalMs > 0L) formatPositionMs(totalMs) else "--:--")
    }
    val statusText = buildString {
        if (isRestored) append("上次播放") else append(if (isPlaying) "正在播放" else "已暂停")
        if (sleepState.active) append(" · 睡眠 ${formatSleepRemaining(sleepState.remainingMs)}")
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
                    placeholder = item.name,
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
                    Text(
                        text = "$timeText · $statusText",
                        style = CinefinType.MonoDataSmall,
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            CinefinIconButton(onClick = onOpenQueue) { tint ->
                Icon(
                    painter = painterResource(CoreR.drawable.ic_playlist),
                    contentDescription = "播放队列",
                    tint = tint,
                    modifier = Modifier.size(20.dp),
                )
            }
            CinefinIconButton(onClick = onOpenLyrics) { tint ->
                Text(text = "词", style = CinefinType.LabelLarge, color = tint)
            }
            CinefinIconButton(onClick = onPrevious) { tint ->
                Icon(
                    painter = painterResource(CoreR.drawable.ic_skip_back),
                    contentDescription = "上一曲",
                    tint = tint,
                    modifier = Modifier.size(20.dp),
                )
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
        }
    }
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
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CinefinEmptyState(title = title, message = message)
    }
}

/** 睡眠定时面板（W21-R2）：档位与视频侧一致（10 / 20 / 30 / 60 分钟 + 关闭）， 选中即生效并关闭面板；进行中再打开会显示剩余时间。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SleepTimerSheet(
    state: MusicSleepTimer.State,
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
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = CinefinSpacing.Space5)) {
            Text(text = "睡眠定时", style = CinefinType.TitleMedium, color = colors.onSurface)
            Text(
                text =
                    if (state.active) {
                        "到点自动暂停音乐 · 剩余 ${formatSleepRemaining(state.remainingMs)}"
                    } else {
                        "到点自动暂停音乐，与视频播放页的定时互不影响"
                    },
                style = CinefinType.BodySmall,
                color = colors.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(CinefinSpacing.Space3))
            CinefinListRow(
                title = "关闭",
                isCurrent = !state.active,
                onClick = { onSelect(null) },
            )
            listOf(10, 20, 30, 60).forEachIndexed { index, minutes ->
                CinefinListRow(
                    title = "$minutes 分钟",
                    isCurrent = state.minutes == minutes,
                    showDivider = index != 3,
                    onClick = { onSelect(minutes) },
                )
            }
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
