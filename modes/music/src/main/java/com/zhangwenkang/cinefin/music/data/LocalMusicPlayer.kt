package com.zhangwenkang.cinefin.music.data

import com.zhangwenkang.cinefin.local.LocalLibraryEntry
import com.zhangwenkang.cinefin.local.LocalMediaKind
import com.zhangwenkang.cinefin.player.core.domain.models.MusicQueue
import com.zhangwenkang.cinefin.player.core.domain.models.QueueSource
import com.zhangwenkang.cinefin.player.local.domain.MusicPlaybackController
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * W37：本地媒体库曲目 → 音乐播放链路（复用既有 [MusicPlaybackController]，不新增播放器路径）。
 *
 * 从本地媒体库页点歌时使用：队列 = 同一文件夹里的全部本地曲目（`待播顺序 = 路径顺序`）。
 */
@Singleton
class LocalMusicPlayer
@Inject
constructor(
    private val trackResolver: MusicTrackResolver,
    private val playbackController: MusicPlaybackController,
) {
    suspend fun play(entries: List<LocalLibraryEntry>, startItemId: UUID): Boolean {
        val songs =
            entries
                .filter { it.kind == LocalMediaKind.MUSIC }
                .sortedWith(compareBy({ it.relativePath.lowercase() }, { it.name.lowercase() }))
                .map { it.toLocalMusicSong() }
        val startIndex = songs.indexOfFirst { it.itemId == startItemId }
        if (startIndex < 0) return false
        val items = songs.map { song -> trackResolver.toPlayerItem(song) }
        playbackController.setQueue(
            MusicQueue(
                items = items,
                currentIndex = startIndex,
                source = QueueSource.MANUAL,
            ),
            startIndex = startIndex,
        )
        return true
    }
}

/** 本地库条目 → 音乐曲目（专辑 / 艺人取内嵌标签；缺失回退「本地文件」分组）。 */
fun LocalLibraryEntry.toLocalMusicSong(): MusicSong =
    MusicSong(
        itemId = itemId,
        name = displayName,
        albumName = album?.takeIf { it.isNotBlank() } ?: "本地文件",
        artist = artist,
        indexNumber = trackIndex.takeIf { it > 0 },
        runtimeTicks = durationMs * TICKS_PER_MS,
        imageUri = coverUri,
        resumePositionMs = 0L,
        source = MusicItemSource.LOCAL,
        localUri = documentUri,
    )

private const val TICKS_PER_MS = 10_000L
