package com.zhangwenkang.cinefin.music.presentation

import com.zhangwenkang.cinefin.music.data.MusicItemSource
import com.zhangwenkang.cinefin.music.data.MusicPlaylist
import com.zhangwenkang.cinefin.music.data.MusicQueueSnapshot
import com.zhangwenkang.cinefin.music.data.MusicSong
import com.zhangwenkang.cinefin.player.core.domain.models.PlayerItem
import com.zhangwenkang.cinefin.player.core.domain.models.QueueSource
import java.util.UUID

/** 同一歌单的磁盘队列快照可直接投影为详情首屏，不依赖网络。 */
internal fun playlistSnapshotSongs(
    playlist: MusicPlaylist,
    snapshot: MusicQueueSnapshot?,
): List<MusicSong> {
    val queue = snapshot?.queue ?: return emptyList()
    if (queue.source != QueueSource.PLAYLIST || queue.sourceId != playlist.id.toString()) {
        return emptyList()
    }
    return queue.items.map { item -> item.toSnapshotMusicSong() }
}

/** 点击目标曲时优先复用快照里的可播放地址；没有目标或 URI 为空则返回 null。 */
internal fun snapshotPlaybackItem(snapshot: MusicQueueSnapshot?, itemId: UUID): PlayerItem? =
    snapshot?.queue?.items?.firstOrNull { item ->
        item.itemId == itemId && item.mediaSourceUri.isNotBlank()
    }

/** 同一歌单快照按曲目 id 提供可播放条目；补齐批次优先复用这里，失败的目标才走网络解析。 */
internal fun playlistSnapshotPlaybackItems(
    playlist: MusicPlaylist,
    snapshot: MusicQueueSnapshot?,
): Map<UUID, PlayerItem> {
    val queue = snapshot?.queue ?: return emptyMap()
    if (queue.source != QueueSource.PLAYLIST || queue.sourceId != playlist.id.toString()) {
        return emptyMap()
    }
    return queue.items
        .filter { item -> item.mediaSourceUri.isNotBlank() }
        .associateBy { item -> item.itemId }
}

internal fun isCurrentPlaybackRequest(requestId: Long, latestRequestId: Long): Boolean =
    requestId > 0L && requestId == latestRequestId

private fun PlayerItem.toSnapshotMusicSong(): MusicSong {
    val localUri = mediaSourceUri.takeIf { uri -> uri.startsWith("content://") }
    return MusicSong(
        itemId = itemId,
        name = name,
        albumName = "",
        artist = artist,
        indexNumber = indexNumber,
        runtimeTicks = 0L,
        imageUri = thumbnailUri,
        resumePositionMs = playbackPosition.coerceAtLeast(0L),
        localUri = localUri,
        source = if (localUri == null) MusicItemSource.SERVER else MusicItemSource.LOCAL,
    )
}
