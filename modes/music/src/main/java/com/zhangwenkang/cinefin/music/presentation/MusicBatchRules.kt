package com.zhangwenkang.cinefin.music.presentation

import com.zhangwenkang.cinefin.music.data.MusicSong
import java.util.UUID

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
 * 批量「收藏」目标：任一所选条目未收藏 → 全部收藏；全部已收藏 → 全部取消（与单曲动作语义一致）。
 *
 * 返回 null = 没有选中条目（调用方不写服务器）。
 */
internal fun batchFavoriteTarget(selected: List<MusicSong>): Boolean? =
    if (selected.isEmpty()) null else selected.any { !it.isFavorite }

/** 批量「下载」目标：跳过已下载 / 已在下载队列的曲目。 */
internal fun batchDownloadTargets(
    selected: List<MusicSong>,
    caps: Map<UUID, SongBatchCaps>,
): List<MusicSong> = selected.filter { caps[it.itemId]?.canDownload == true }

/** 批量「删除」目标：只保留「已下载且非本地媒体库」的服务器条目。 */
internal fun batchDeleteTargets(
    selected: List<MusicSong>,
    caps: Map<UUID, SongBatchCaps>,
): List<MusicSong> = selected.filter { caps[it.itemId]?.canDelete == true }
