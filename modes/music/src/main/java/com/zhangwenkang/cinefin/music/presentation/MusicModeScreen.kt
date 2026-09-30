package com.zhangwenkang.cinefin.music.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import coil3.compose.AsyncImage
import com.zhangwenkang.cinefin.core.R as CoreR
import com.zhangwenkang.cinefin.music.data.MusicAlbum
import com.zhangwenkang.cinefin.music.data.MusicArtist
import com.zhangwenkang.cinefin.music.data.MusicPlaylist
import com.zhangwenkang.cinefin.music.data.MusicSong
import com.zhangwenkang.cinefin.player.core.domain.models.MusicQueue
import java.util.UUID
import kotlin.math.roundToInt

/**
 * 音乐模式入口（W1 R2 最小闭环，W2 R2 扩到四维浏览 + 队列面板）。
 *
 * 浏览：专辑 / 艺术家 / 歌曲 / 歌单四个标签页，点歌以当前列表整份入队； 队列：底栏「队列」按钮打开面板，支持点歌跳转、长按拖拽排序、移除与「下一首播放」；
 * 通知栏与锁屏由播放会话服务承载（music → Media3 单 MediaSession）。
 *
 * 路由注册由 R3 在 `NavigationRoot.kt` 统一提交（见 [MusicModeRoute]）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicModeScreen(
    modifier: Modifier = Modifier,
    viewModel: MusicModeViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val queue by viewModel.queue.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()
    var queueSheetOpen by rememberSaveable { mutableStateOf(false) }

    // 队列被清空（停止播放）时自动收起队列面板
    LaunchedEffect(queue) { if (queue == null) queueSheetOpen = false }

    Column(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        MusicHeader(state = state, onBack = viewModel::closeDetail)
        if (state.detail == null) {
            MusicTabs(selected = state.tab, onSelect = viewModel::selectTab)
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            val error = state.errorMessage
            when {
                error != null ->
                    ErrorPane(
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
                    )
            }
        }

        NowPlayingBar(
            queue = queue,
            isPlaying = isPlaying,
            onPlayPause = viewModel::togglePlayPause,
            onNext = viewModel::skipToNext,
            onOpenQueue = { queueSheetOpen = true },
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
}

@Composable
private fun MusicHeader(state: MusicModeViewModel.UiState, onBack: () -> Unit) {
    val detail = state.detail
    Row(
        modifier = Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (detail != null) {
            IconButton(onClick = onBack) {
                Icon(
                    painter = painterResource(CoreR.drawable.ic_arrow_left),
                    contentDescription = "返回",
                )
            }
        } else {
            Spacer(modifier = Modifier.width(16.dp))
        }
        Column {
            Text(
                text = detail?.title ?: "音乐",
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text =
                    when {
                        detail != null -> "共 ${detail.songs.size} 首曲目"
                        state.tab == MusicTab.ALBUMS -> "共 ${state.albums.size} 张专辑"
                        state.tab == MusicTab.ARTISTS -> "共 ${state.artists.size} 位艺术家"
                        state.tab == MusicTab.SONGS -> "共 ${state.songs.size} 首歌曲"
                        else -> "共 ${state.playlists.size} 个歌单"
                    },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MusicTabs(selected: MusicTab, onSelect: (MusicTab) -> Unit) {
    val tabs =
        listOf(
            MusicTab.ALBUMS to "专辑",
            MusicTab.ARTISTS to "艺术家",
            MusicTab.SONGS to "歌曲",
            MusicTab.PLAYLISTS to "歌单",
        )
    PrimaryTabRow(selectedTabIndex = tabs.indexOfFirst { it.first == selected }) {
        tabs.forEach { (tab, title) ->
            Tab(
                selected = tab == selected,
                onClick = { onSelect(tab) },
                text = { Text(text = title) },
            )
        }
    }
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
) {
    if (detail is MusicDetail.Playlist && detail.loading) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }
    SongList(
        songs = detail.songs,
        currentItemId = currentItemId,
        showAlbum = detail !is MusicDetail.Album,
        onSongClick = onSongClick,
        onPlayNext = onPlayNext,
    )
}

@Composable
private fun AlbumList(albums: List<MusicAlbum>, onAlbumClick: (MusicAlbum) -> Unit) {
    if (albums.isEmpty()) {
        EmptyHint(text = "音乐库里还没有专辑")
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(items = albums, key = { album -> album.key }) { album ->
            AlbumRow(album = album, onClick = { onAlbumClick(album) })
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
        }
    }
}

@Composable
private fun ArtistList(artists: List<MusicArtist>, onArtistClick: (MusicArtist) -> Unit) {
    if (artists.isEmpty()) {
        EmptyHint(text = "音乐库里还没有艺术家")
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(items = artists, key = { artist -> artist.key }) { artist ->
            MediaRow(
                title = artist.name,
                subtitle = "${artist.songs.size} 首",
                imageUri = artist.imageUri,
                placeholder = "♪",
                onClick = { onArtistClick(artist) },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
        }
    }
}

@Composable
private fun PlaylistList(
    playlists: List<MusicPlaylist>,
    onPlaylistClick: (MusicPlaylist) -> Unit,
) {
    if (playlists.isEmpty()) {
        EmptyHint(text = "服务器上没有歌单")
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(items = playlists, key = { playlist -> playlist.id.toString() }) { playlist ->
            MediaRow(
                title = playlist.name,
                subtitle = playlist.songCount?.let { count -> "$count 首" } ?: "歌单",
                imageUri = playlist.imageUri,
                placeholder = "≡",
                onClick = { onPlaylistClick(playlist) },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
        }
    }
}

@Composable
private fun AlbumRow(album: MusicAlbum, onClick: () -> Unit) {
    MediaRow(
        title = album.name,
        subtitle =
            listOfNotNull(album.artist, "${album.songs.size} 首")
                .filter { it.isNotBlank() }
                .joinToString(" · "),
        imageUri = album.imageUri,
        placeholder = "♪",
        onClick = onClick,
    )
}

@Composable
private fun MediaRow(
    title: String,
    subtitle: String,
    imageUri: String?,
    placeholder: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            modifier = Modifier.size(48.dp),
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            if (imageUri == null) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = placeholder,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                AsyncImage(
                    model = imageUri,
                    contentDescription = title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun SongList(
    songs: List<MusicSong>,
    currentItemId: UUID?,
    showAlbum: Boolean,
    onSongClick: (MusicSong) -> Unit,
    onPlayNext: (MusicSong) -> Unit,
) {
    if (songs.isEmpty()) {
        EmptyHint(text = "这里还没有可播放的曲目")
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
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
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
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = index.toString().padStart(2, '0'),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = song.name,
                style = MaterialTheme.typography.bodyLarge,
                color =
                    if (isCurrent) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                fontWeight = if (isCurrent) FontWeight.SemiBold else null,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val line =
                listOfNotNull(subtitle, formatDuration(song.runtimeTicks))
                    .filter { it.isNotBlank() }
                    .joinToString(" · ")
            if (line.isNotBlank()) {
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Box {
            TextButton(onClick = { menuOpen = true }, contentPadding = PaddingValues(0.dp)) {
                Text(text = "⋮", style = MaterialTheme.typography.titleMedium)
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(text = "下一首播放") },
                    onClick = {
                        menuOpen = false
                        onPlayNext()
                    },
                )
            }
        }
    }
}

@Composable
private fun NowPlayingBar(
    queue: MusicQueue?,
    isPlaying: Boolean,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onOpenQueue: () -> Unit,
) {
    val item = queue?.currentItem ?: return
    Surface(tonalElevation = 3.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "队列 ${queue.currentIndex + 1}/${queue.items.size}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onOpenQueue) {
                Icon(
                    painter = painterResource(CoreR.drawable.ic_playlist),
                    contentDescription = "播放队列",
                )
            }
            IconButton(onClick = onPlayPause) {
                Icon(
                    painter =
                        painterResource(
                            if (isPlaying) CoreR.drawable.ic_pause else CoreR.drawable.ic_play
                        ),
                    contentDescription = if (isPlaying) "暂停" else "播放",
                )
            }
            IconButton(onClick = onNext) {
                Icon(
                    painter = painterResource(CoreR.drawable.ic_skip_forward),
                    contentDescription = "下一首",
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QueueSheet(
    queue: MusicQueue,
    onDismiss: () -> Unit,
    onJump: (Int) -> Unit,
    onMove: (Int, Int) -> Unit,
    onRemove: (Int) -> Unit,
) {
    val rowHeight = 56.dp
    val rowHeightPx = with(LocalDensity.current) { rowHeight.toPx() }
    var draggingIndex by remember { mutableStateOf<Int?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Text(
                text = "播放队列（${queue.items.size}）",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "长按右侧「≡」拖拽排序，点标题跳转播放，点「✕」移除",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(8.dp))
        }
        LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 440.dp)) {
            itemsIndexed(
                items = queue.items,
                key = { index, item -> "$index-${item.itemId}" },
            ) { index, item ->
                val isDragging = draggingIndex == index
                Row(
                    modifier =
                        Modifier.fillMaxWidth()
                            .height(rowHeight)
                            .zIndex(if (isDragging) 1f else 0f)
                            .graphicsLayer { translationY = if (isDragging) dragOffset else 0f }
                            .background(
                                if (isDragging) {
                                    MaterialTheme.colorScheme.surfaceVariant
                                } else {
                                    Color.Transparent
                                }
                            )
                            .clickable { onJump(index) }
                            .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = (index + 1).toString().padStart(2, '0'),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = item.name,
                            style = MaterialTheme.typography.bodyLarge,
                            color =
                                if (index == queue.currentIndex) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                            fontWeight =
                                if (index == queue.currentIndex) FontWeight.SemiBold else null,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (index == queue.currentIndex) {
                            Text(
                                text = "正在播放",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    IconButton(onClick = { onRemove(index) }) {
                        Icon(
                            painter = painterResource(CoreR.drawable.ic_close),
                            contentDescription = "从队列移除",
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
                                            val delta = (dragOffset / rowHeightPx).roundToInt()
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
                        Text(text = "≡", style = MaterialTheme.typography.titleMedium)
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun ErrorPane(message: String, onRetry: () -> Unit, onDismiss: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
        Row {
            TextButton(onClick = onRetry) { Text(text = "重试") }
            TextButton(onClick = onDismiss) { Text(text = "关闭") }
        }
    }
}

@Composable
private fun EmptyHint(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Jellyfin 的 runtimeTicks：1 tick = 100 ns，1 秒 = 10^7 ticks。 */
private fun formatDuration(runtimeTicks: Long): String {
    if (runtimeTicks <= 0L) return ""
    val totalSeconds = runtimeTicks / 10_000_000L
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}
