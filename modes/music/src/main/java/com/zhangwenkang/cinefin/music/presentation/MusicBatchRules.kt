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
    /** W8-T4：把选中曲目加入歌单（本地 / 服务器歌单在对话框里选择）。 */
    ADD_TO_PLAYLIST,
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

/**
 * W9-T2：单曲下载入口的动作。
 *
 * 下载、取消、删除是三个**独立入口**：下载入口永远不带删除语义，删除只能来自 [DELETE]。
 */
internal enum class SongDownloadMenuAction {
    DOWNLOAD,
    CANCEL,
    DELETE,
    /** 不提供入口：本地媒体库曲目（文件由用户自己管理）与删除中的条目。 */
    NONE,
}

/** W9-T2：曲目行 / 长按菜单的下载项动作（[downloaded] 与 [activeStatus] 来自 `SongDownloadState`）。 */
internal fun songDownloadMenuAction(
    downloaded: Boolean,
    activeStatus: DownloadTaskStatus?,
    fromLocalLibrary: Boolean,
): SongDownloadMenuAction =
    when {
        fromLocalLibrary -> SongDownloadMenuAction.NONE
        activeStatus == DownloadTaskStatus.DELETING -> SongDownloadMenuAction.NONE
        downloaded -> SongDownloadMenuAction.DELETE
        activeStatus != null -> SongDownloadMenuAction.CANCEL
        else -> SongDownloadMenuAction.DOWNLOAD
    }

/** W9-T2：曲目行菜单项文案（null = 不渲染该项）。 */
internal fun songDownloadMenuLabel(action: SongDownloadMenuAction): String? =
    when (action) {
        SongDownloadMenuAction.DOWNLOAD -> "下载"
        SongDownloadMenuAction.CANCEL -> "取消下载"
        SongDownloadMenuAction.DELETE -> "删除下载"
        SongDownloadMenuAction.NONE -> null
    }

/** W9-T2：曲目行副标题里的下载状态（null = 不显示）。 */
internal fun songDownloadStatusLabel(
    action: SongDownloadMenuAction,
    activeStatus: DownloadTaskStatus?,
): String? =
    when {
        action == SongDownloadMenuAction.DELETE -> "已下载"
        activeStatus == DownloadTaskStatus.DELETING -> "正在删除下载"
        activeStatus == DownloadTaskStatus.PAUSED -> "下载已暂停"
        action == SongDownloadMenuAction.CANCEL -> "下载中"
        else -> null
    }

/** W9-T2：长按 sheet 的下载项文案（null = 不渲染该项）。 */
internal fun songContextDownloadLabel(action: SongDownloadMenuAction): String? =
    when (action) {
        SongDownloadMenuAction.DOWNLOAD,
        SongDownloadMenuAction.CANCEL -> "下载 · 离线"
        SongDownloadMenuAction.DELETE -> "删除下载"
        SongDownloadMenuAction.NONE -> null
    }

/** W9-T2：长按 sheet 下载项右侧状态。 */
internal fun songContextDownloadTrailing(
    action: SongDownloadMenuAction,
    activeStatus: DownloadTaskStatus?,
): String? =
    when (action) {
        SongDownloadMenuAction.DELETE -> "已下载"
        SongDownloadMenuAction.CANCEL ->
            if (activeStatus == DownloadTaskStatus.PAUSED) "下载已暂停" else "下载中"
        SongDownloadMenuAction.NONE ->
            if (activeStatus == DownloadTaskStatus.DELETING) "正在删除下载" else null
        SongDownloadMenuAction.DOWNLOAD -> null
    }

/** W9-T2：单曲下载入口的去重结论。 */
internal enum class SongDownloadRequestOutcome {
    ENQUEUE,
    ALREADY_DOWNLOADED,
    ALREADY_QUEUED,
}

/**
 * W9-T2：单曲下载入口保底规则（纯函数）。
 *
 * 命中已完成下载（sources 里有非 `.download` 的完成文件，或下载态已判定为已下载）→ 只提示； 队列中 → 只提示。两种情况都**不删除、不重复入队**。
 */
internal fun songDownloadRequestOutcome(
    hasCompletedSource: Boolean,
    downloaded: Boolean,
    queued: Boolean,
): SongDownloadRequestOutcome =
    when {
        hasCompletedSource || downloaded -> SongDownloadRequestOutcome.ALREADY_DOWNLOADED
        queued -> SongDownloadRequestOutcome.ALREADY_QUEUED
        else -> SongDownloadRequestOutcome.ENQUEUE
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
        MusicBatchAction.ADD_TO_PLAYLIST -> selected.isNotEmpty()
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
