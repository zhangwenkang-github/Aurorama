package com.zhangwenkang.cinefin.music.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import coil3.compose.AsyncImage
import com.zhangwenkang.cinefin.core.R as CoreR
import com.zhangwenkang.cinefin.music.data.MusicAlbum
import com.zhangwenkang.cinefin.music.data.MusicSong
import com.zhangwenkang.cinefin.player.core.domain.models.MusicQueue
import java.util.UUID

/**
 * 音乐模式入口（W1 R2）。
 *
 * 最小闭环：专辑列表 → 专辑曲目 → 点击歌曲起播（整张专辑入队）； 底部播放条提供播放 / 暂停 / 下一首，通知栏与锁屏由播放会话服务承载。
 *
 * 路由注册由 R3 在 `NavigationRoot.kt` 统一提交（见 [MusicModeRoute]）。
 */
@Composable
fun MusicModeScreen(
    modifier: Modifier = Modifier,
    viewModel: MusicModeViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val queue by viewModel.queue.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()

    Column(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        MusicHeader(
            album = state.openedAlbum,
            albumCount = state.albums.size,
            songCount = state.songs.size,
            onBack = viewModel::closeAlbum,
        )

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            val error = state.errorMessage
            when {
                state.loading ->
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                error != null ->
                    ErrorPane(
                        message = error,
                        onRetry = viewModel::refreshAlbums,
                        onDismiss = viewModel::dismissError,
                    )
                state.openedAlbum == null ->
                    AlbumList(albums = state.albums, onAlbumClick = viewModel::openAlbum)
                else ->
                    SongList(
                        songs = state.songs,
                        currentItemId = queue?.currentItem?.itemId,
                        onSongClick = viewModel::playSong,
                    )
            }
        }

        NowPlayingBar(
            queue = queue,
            isPlaying = isPlaying,
            onPlayPause = viewModel::togglePlayPause,
            onNext = viewModel::playNext,
        )
    }
}

@Composable
private fun MusicHeader(
    album: MusicAlbum?,
    albumCount: Int,
    songCount: Int,
    onBack: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (album != null) {
            IconButton(onClick = onBack) {
                Icon(
                    painter = painterResource(CoreR.drawable.ic_arrow_left),
                    contentDescription = "返回专辑列表",
                )
            }
        } else {
            Spacer(modifier = Modifier.width(16.dp))
        }
        Column {
            Text(
                text = album?.name ?: "音乐",
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = if (album == null) "共 $albumCount 张专辑" else "共 $songCount 首曲目",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
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
private fun AlbumRow(album: MusicAlbum, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AlbumArt(
            model = album.imageUri,
            name = album.name,
            modifier = Modifier.size(48.dp),
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = album.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text =
                    listOfNotNull(album.artist, "${album.songs.size} 首")
                        .filter { it.isNotBlank() }
                        .joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun AlbumArt(model: String?, name: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        if (model == null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "♪",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            AsyncImage(
                model = model,
                contentDescription = name,
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
    onSongClick: (MusicSong) -> Unit,
) {
    if (songs.isEmpty()) {
        EmptyHint(text = "这张专辑里没有可播放的曲目")
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        itemsIndexed(items = songs, key = { _, song -> song.itemId.toString() }) { index, song ->
            SongRow(
                index = index + 1,
                song = song,
                isCurrent = song.itemId == currentItemId,
                onClick = { onSongClick(song) },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
        }
    }
}

@Composable
private fun SongRow(
    index: Int,
    song: MusicSong,
    isCurrent: Boolean,
    onClick: () -> Unit,
) {
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
        Text(
            text = song.name,
            modifier = Modifier.weight(1f),
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
        val duration = formatDuration(song.runtimeTicks)
        if (duration.isNotEmpty()) {
            Text(
                text = duration,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun NowPlayingBar(
    queue: MusicQueue?,
    isPlaying: Boolean,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
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
