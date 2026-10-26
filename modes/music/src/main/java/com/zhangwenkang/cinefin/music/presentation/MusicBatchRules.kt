package com.zhangwenkang.cinefin.music.presentation

import com.zhangwenkang.cinefin.music.data.MusicSong
import com.zhangwenkang.cinefin.utils.DownloadCanonicalIdentity
import com.zhangwenkang.cinefin.utils.DownloadMediaKind
import com.zhangwenkang.cinefin.utils.DownloadMediaRecord
import com.zhangwenkang.cinefin.utils.DownloadMusicIntentRequest
import com.zhangwenkang.cinefin.utils.DownloadTask
import com.zhangwenkang.cinefin.utils.DownloadTaskRules
import com.zhangwenkang.cinefin.utils.DownloadTaskStatus
import com.zhangwenkang.cinefin.utils.canonicalIdentity
import com.zhangwenkang.cinefin.utils.downloadIdentity
import java.util.UUID
import kotlin.random.Random

/** W58 音乐多选批量动作。 */
enum class MusicBatchAction {
    PLAY,
    DOWNLOAD,
    FAVORITE,
    /** 仅删本地（已下载文件 / 索引）；纯服务器条目不提供。 */
    DELETE,
    /** 只出现在歌单详情：把选中曲目从该歌单移除（服务器写操作，非删除媒体）。 */
    REMOVE_FROM_PLAYLIST,
}

/**
 * 单曲的批量动作可用性（W58）。
 *
 * [downloaded] / [activeDownload] 来自 ViewModel 的 `SongDownloadState`； [fromLocalLibrary] =
 * 本地媒体库曲目（`localUri != null`，不提供删除入口）。
 */
internal data class SongBatchCaps(
    val downloaded: Boolean = false,
    val activeDownload: Boolean = false,
    val fromLocalLibrary: Boolean = false,
) {
    val canDownload: Boolean
        get() = !downloaded && !activeDownload

    /** 删除红线：只删「服务器条目的本地下载」；本地媒体库曲目的文件由用户自己管理，App 不删。 */
    val canDelete: Boolean
        get() = downloaded && !fromLocalLibrary
}

/** caps 与它所属曲目行一一绑定，禁止再经可能为 null / 冲突的 canonical key 回查。 */
internal data class MusicBatchTargetCapability(
    val song: MusicSong,
    val caps: SongBatchCaps,
)

internal fun musicSongBatchCaps(
    song: MusicSong,
    downloadedItemIds: Set<UUID>,
    activeItemIds: Set<UUID>,
): SongBatchCaps =
    SongBatchCaps(
        downloaded = song.itemId in downloadedItemIds,
        activeDownload = song.itemId in activeItemIds,
        fromLocalLibrary = song.localUri != null,
    )

/** 只有真正排队 / 下载 / 暂停 / 删除中的任务占用“不可重下”状态；失败与取消必须恢复按钮。 */
internal fun activeSongDownloadStatuses(
    tasks: List<DownloadTask>
): Map<DownloadCanonicalIdentity, DownloadTaskStatus> =
    tasks
        .asSequence()
        .filter { DownloadTaskRules.isActiveQueueStatus(it.status) }
        .associate { it.downloadIdentity() to it.status }

internal fun musicSongBatchCaps(
    song: MusicSong,
    downloadedRecords: List<DownloadMediaRecord>,
    activeTasks: List<DownloadTask>,
): SongBatchCaps {
    val identity = song.downloadIdentity()
    return SongBatchCaps(
        downloaded =
            identity != null &&
                downloadedRecords.any {
                    it.kind == DownloadMediaKind.MUSIC &&
                        it.canonicalIdentity().matchesProvenance(identity)
                },
        activeDownload =
            identity != null &&
                activeTasks.any { task ->
                    DownloadTaskRules.isActiveQueueStatus(task.status) &&
                        task.downloadIdentity().matchesProvenance(identity)
                },
        fromLocalLibrary = song.localUri != null,
    )
}

internal fun MusicSong.downloadIdentity(): DownloadCanonicalIdentity? {
    val serverId = serverId?.takeIf(String::isNotBlank) ?: return null
    val userId = userId?.takeIf(String::isNotBlank) ?: return null
    return DownloadCanonicalIdentity.of(
        mediaKind = DownloadMediaKind.MUSIC,
        itemId = itemId.toString(),
        serverId = serverId,
        userId = userId,
        sourceId = null,
    )
}

/** 批量动作条里某个动作是否可用（任一选中条目满足即可，与下载页口径一致）。 */
internal fun musicBatchActionEnabled(
    action: MusicBatchAction,
    selected: List<SongBatchCaps>,
    inPlaylist: Boolean,
): Boolean =
    when (action) {
        MusicBatchAction.PLAY -> selected.isNotEmpty()
        MusicBatchAction.DOWNLOAD -> selected.any { it.canDownload }
        MusicBatchAction.FAVORITE -> selected.isNotEmpty()
        MusicBatchAction.DELETE -> selected.any { it.canDelete }
        MusicBatchAction.REMOVE_FROM_PLAYLIST -> inPlaylist && selected.isNotEmpty()
    }

/** 批量「播放」的入队顺序：按**当前视图列表顺序**取选中曲目（不是点击顺序）， 与单曲起播「整份列表入队」的列表序语义一致。 */
internal fun batchPlayOrder(songs: List<MusicSong>, selectedIds: Set<String>): List<MusicSong> =
    songs.filter {
        it.itemId.toString() in selectedIds
    }

/**
 * W64「播放全部」首曲下标。
 *
 * 自然队列始终按视图原序入队；随机播放只随机首曲，由 ExoPlayer 生成可持久化的实际 shuffle 序。 [random] 注入以便单测。
 */
internal fun playAllStartIndex(
    size: Int,
    shuffle: Boolean,
    random: Random = Random.Default,
): Int = if (shuffle && size > 0) random.nextInt(size) else 0

/**
 * 批量「收藏」目标：任一所选条目未收藏 → 全部收藏；全部已收藏 → 全部取消（与单曲动作语义一致）。
 *
 * 返回 null = 没有选中条目（调用方不写服务器）。
 */
internal fun batchFavoriteTarget(selected: List<MusicSong>): Boolean? =
    if (selected.isEmpty()) null else selected.any { !it.isFavorite }

/** 批量「下载」目标：跳过已下载 / 已在下载队列的曲目。 */
internal fun batchDownloadTargets(capabilities: List<MusicBatchTargetCapability>): List<MusicSong> =
    capabilities
        .filter { row -> !row.caps.fromLocalLibrary && row.caps.canDownload }
        .map(MusicBatchTargetCapability::song)

/** 批量「删除」目标：只保留「已下载且非本地媒体库」的服务器条目。 */
internal fun batchDeleteTargets(capabilities: List<MusicBatchTargetCapability>): List<MusicSong> =
    capabilities
        .filter { row -> !row.caps.fromLocalLibrary && row.caps.canDelete }
        .map(MusicBatchTargetCapability::song)

internal fun musicIntentRequests(songs: List<MusicSong>): List<DownloadMusicIntentRequest> =
    songs
        .filter { song -> song.localUri == null }
        .map { song ->
            DownloadMusicIntentRequest(
                itemId = song.itemId,
                name = song.name,
                albumName = song.albumName,
                artist = song.artist,
                trackIndex = song.indexNumber ?: 0,
                serverId = song.serverId,
                userId = song.userId,
            )
        }
