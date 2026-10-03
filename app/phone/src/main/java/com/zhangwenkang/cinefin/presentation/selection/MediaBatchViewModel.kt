package com.zhangwenkang.cinefin.presentation.selection

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zhangwenkang.cinefin.film.presentation.detail.DetailDownloadRules
import com.zhangwenkang.cinefin.models.FindroidEpisode
import com.zhangwenkang.cinefin.models.FindroidItem
import com.zhangwenkang.cinefin.models.FindroidMovie
import com.zhangwenkang.cinefin.models.FindroidSeason
import com.zhangwenkang.cinefin.models.FindroidShow
import com.zhangwenkang.cinefin.models.FindroidSourceType
import com.zhangwenkang.cinefin.repository.JellyfinRepository
import com.zhangwenkang.cinefin.repository.ReaderRepository
import com.zhangwenkang.cinefin.utils.Downloader
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import javax.inject.Inject
import javax.inject.Provider
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.BaseItemKind
import timber.log.Timber

/** 下载引擎的只读快照（已下载 / 队列内条目 id），供批量动作可用性判定。 */
data class MediaDownloadState(
    val downloadedItemIds: Set<UUID> = emptySet(),
    val activeItemIds: Set<UUID> = emptySet(),
    /** 阅读器离线链路（files/books 目录下的 .book 文件）已下载的书 id；书籍不走下载引擎。 */
    val localBookItemIds: Set<UUID> = emptySet(),
)

/** 批量动作结果（页面据此弹提示 / 起播 / 刷新）。 */
sealed interface MediaBatchEvent {
    data class DownloadQueued(val queued: Int, val failed: Int) : MediaBatchEvent

    data class Updated(val changed: Int, val failed: Int) : MediaBatchEvent

    data class Deleted(val deleted: Int, val failed: Int) : MediaBatchEvent

    data object PlayUnavailable : MediaBatchEvent

    data class PlayQueue(val entries: List<VideoPlaybackEntry>) : MediaBatchEvent
}

/**
 * 视频 / 书籍多选批量动作（W58b）。
 *
 * 与音乐批量一致的分工：选中集合与「已加载条目」由页面层持有（分页网格只能在那里取全）， 这里只负责跑动作——下载 / 标记已看（已读）/ 收藏 /
 * 删除本地（红线：只删本机下载）与视频播放入队解析。 仓库按当前偏好（在线 / 离线）解析，与视频页 / 书架页同一套 `Provider<JellyfinRepository>` 口径。
 */
@HiltViewModel
class MediaBatchViewModel
@Inject
constructor(
    private val repositoryProvider: Provider<JellyfinRepository>,
    private val downloader: Downloader,
    private val readerRepository: ReaderRepository,
) : ViewModel() {
    private val _downloadState = MutableStateFlow(MediaDownloadState())
    val downloadState = _downloadState.asStateFlow()

    private val eventsChannel = Channel<MediaBatchEvent>()
    val events = eventsChannel.receiveAsFlow()

    /** 进入页面 / 动作完成后刷新下载态（只读 Room，不唤醒引擎）。 */
    fun refreshDownloadState() {
        viewModelScope.launch {
            runCatching {
                _downloadState.value =
                    MediaDownloadState(
                        downloadedItemIds = downloader.downloadedItemIds(),
                        activeItemIds = downloader.activeItemIds(),
                        localBookItemIds =
                            readerRepository.listLocalFiles().map { it.itemId }.toSet(),
                    )
            }
                .onFailure { Timber.w(it, "刷新批量下载态失败") }
        }
    }

    fun capsOf(items: List<FindroidItem>): Map<UUID, MediaBatchCaps> {
        val state = _downloadState.value
        return items.associate { item ->
            item.id to
                mediaBatchCaps(
                    item = item,
                    downloadedItemIds = state.downloadedItemIds + state.localBookItemIds,
                    activeItemIds = state.activeItemIds,
                )
        }
    }

    /** 批量「播放」：按列表序把选中条目解析成**具体可播放条目**（电影 → 电影；剧集 → 下一集 / 第一集； 季 → 第一集），再交给播放页起播并补队列。 */
    fun playSelected(items: List<FindroidItem>) {
        val playOrder = batchPlayOrder(items)
        if (playOrder.isEmpty()) {
            viewModelScope.launch { eventsChannel.send(MediaBatchEvent.PlayUnavailable) }
            return
        }
        viewModelScope.launch {
            val repository = repositoryProvider.get()
            val entries = mutableListOf<VideoPlaybackEntry>()
            for (item in playOrder) {
                when (item) {
                    is FindroidMovie -> entries += VideoPlaybackEntry(item.id, BaseItemKind.MOVIE)
                    is FindroidEpisode ->
                        entries += VideoPlaybackEntry(item.id, BaseItemKind.EPISODE)
                    is FindroidShow ->
                        resolveShowSeed(repository, item)?.let { episode ->
                            entries += VideoPlaybackEntry(episode.id, BaseItemKind.EPISODE)
                        }
                    is FindroidSeason ->
                        resolveSeasonSeed(repository, item)?.let { episode ->
                            entries += VideoPlaybackEntry(episode.id, BaseItemKind.EPISODE)
                        }
                    else -> Unit
                }
            }
            if (entries.isEmpty()) eventsChannel.send(MediaBatchEvent.PlayUnavailable)
            else eventsChannel.send(MediaBatchEvent.PlayQueue(entries))
        }
    }

    /** 批量下载：跳过已下载 / 已在队列的条目（目标由 [batchDownloadTargets] 过滤）。 */
    fun downloadSelected(items: List<FindroidItem>) {
        val targets = batchDownloadTargets(items, capsOf(items))
        if (targets.isEmpty()) return
        viewModelScope.launch {
            val repository = repositoryProvider.get()
            val state = _downloadState.value
            var queued = 0
            var failed = 0
            // 书籍走阅读器离线文件链路（与下载页「书籍」分组同源），其余走下载引擎。
            val books = targets.filter { mediaItemIsBook(it) }
            for (book in books) {
                runCatching { readerRepository.downloadLocalFile(book.id) }
                    .onSuccess { queued++ }
                    .onFailure { failed++ }
            }
            for (item in targets - books.toSet()) {
                val (queuedNow, failedNow) =
                    enqueueEngineTargets(
                        repository = repository,
                        item = item,
                        downloadedIds = state.downloadedItemIds,
                        queuedIds = state.activeItemIds,
                    )
                queued += queuedNow
                failed += failedNow
            }
            refreshDownloadState()
            eventsChannel.send(MediaBatchEvent.DownloadQueued(queued = queued, failed = failed))
        }
    }

    /** 批量标记已看 / 已读（双向：任一未完看 → 全部标记完看；全部完看 → 全部取消）。 */
    fun markPlayedSelected(items: List<FindroidItem>) {
        val played = batchPlayedTarget(items) ?: return
        viewModelScope.launch {
            val repository = repositoryProvider.get()
            var changed = 0
            var failed = 0
            for (item in items) {
                runCatching {
                    if (played) repository.markAsPlayed(item.id)
                    else repository.markAsUnplayed(item.id)
                }
                    .onSuccess { changed++ }
                    .onFailure { failed++ }
            }
            eventsChannel.send(MediaBatchEvent.Updated(changed = changed, failed = failed))
        }
    }

    /** 批量收藏（双向：任一所选未收藏 → 全部收藏；全部已收藏 → 全部取消）。 */
    fun favoriteSelected(items: List<FindroidItem>) {
        val favorite = batchFavoriteTarget(items) ?: return
        viewModelScope.launch {
            val repository = repositoryProvider.get()
            var changed = 0
            var failed = 0
            for (item in items) {
                runCatching {
                    if (favorite) repository.markAsFavorite(item.id)
                    else repository.unmarkAsFavorite(item.id)
                }
                    .onSuccess { changed++ }
                    .onFailure { failed++ }
            }
            eventsChannel.send(MediaBatchEvent.Updated(changed = changed, failed = failed))
        }
    }

    /** 批量删除：**只删本地**——已下载服务器条目逐个删除本机文件与索引；纯服务器 / 本地媒体库条目不在目标内。 */
    fun deleteSelected(items: List<FindroidItem>) {
        val targets = batchDeleteTargets(items, capsOf(items))
        if (targets.isEmpty()) return
        viewModelScope.launch {
            val repository = repositoryProvider.get()
            var deleted = 0
            var failed = 0
            for (item in targets) {
                val detail = runCatching { repository.getItem(item.id) }.getOrNull()
                val source =
                    detail?.sources?.firstOrNull { candidate ->
                        candidate.type == FindroidSourceType.LOCAL &&
                            !candidate.path.endsWith(".download")
                    }
                if (detail == null || source == null) {
                    failed++
                    continue
                }
                runCatching { downloader.deleteItem(detail, source) }
                    .onSuccess { deleted++ }
                    .onFailure { failed++ }
            }
            refreshDownloadState()
            eventsChannel.send(MediaBatchEvent.Deleted(deleted = deleted, failed = failed))
        }
    }

    /** 剧集 → 下一集（没有下一集记录时退回首季第一集）。 */
    private suspend fun resolveShowSeed(
        repository: JellyfinRepository,
        show: FindroidShow,
    ): FindroidEpisode? {
        runCatching { repository.getNextUp(show.id) }
            .getOrNull()
            ?.firstOrNull()
            ?.let {
                return it
            }
        val season =
            runCatching { repository.getSeasons(show.id).minByOrNull { it.indexNumber } }
                .getOrNull() ?: return null
        return firstEpisodeOfSeason(repository, seriesId = show.id, seasonId = season.id)
    }

    private suspend fun resolveSeasonSeed(
        repository: JellyfinRepository,
        season: FindroidSeason,
    ): FindroidEpisode? =
        firstEpisodeOfSeason(repository, seriesId = season.seriesId, seasonId = season.id)

    private suspend fun firstEpisodeOfSeason(
        repository: JellyfinRepository,
        seriesId: UUID,
        seasonId: UUID,
    ): FindroidEpisode? = runCatching {
        repository
            .getEpisodes(seriesId = seriesId, seasonId = seasonId)
            .filter { !it.missing }
            .minByOrNull { it.indexNumber }
    }
        .getOrNull()

    /**
     * 单条目入队（下载引擎）：电影 / 单集直接入队；**剧集 / 季按「补齐缺失集」展开** （与详情页「整剧 / 全季下载」同口径，复用 `DetailDownloadRules`
     * 的取集字段 / 目标筛选 / 上限 / 去重）。
     *
     * 返回 (成功入队数, 失败数)。
     */
    private suspend fun enqueueEngineTargets(
        repository: JellyfinRepository,
        item: FindroidItem,
        downloadedIds: Set<UUID>,
        queuedIds: Set<UUID>,
    ): Pair<Int, Int> {
        val episodes =
            when (item) {
                is FindroidShow -> loadSeriesEpisodes(repository, item.id)
                is FindroidSeason -> loadSeasonEpisodes(repository, item.seriesId, item.id)
                else -> return enqueueEngineItem(repository, item)
            }
        val targets = DetailDownloadRules.downloadTargets(episodes)
        val selection =
            DetailDownloadRules.selectBatch(
                itemIds = targets.map { it.id },
                downloaded = downloadedIds,
                queued = queuedIds,
            )
        val byId = targets.associateBy { it.id }
        var queued = 0
        var failed = 0
        for (id in selection.selected) {
            val episode = byId[id] ?: continue
            val (queuedNow, failedNow) = enqueueEngineItem(repository, episode)
            queued += queuedNow
            failed += failedNow
        }
        return queued to failed
    }

    /** 单条目入队（下载引擎）；返回 (成功入队数, 失败数)。 */
    private suspend fun enqueueEngineItem(
        repository: JellyfinRepository,
        item: FindroidItem,
    ): Pair<Int, Int> {
        val sourceId =
            item.sources.firstOrNull()?.id
                ?: runCatching { repository.getMediaSources(item.id, true) }
                    .getOrNull()
                    ?.firstOrNull()
                    ?.id
        if (sourceId == null) return 0 to 1
        val detail =
            if (item.sources.isNotEmpty()) item
            else runCatching { repository.getItem(item.id) }.getOrNull() ?: return 0 to 1
        return runCatching {
                downloader.downloadItem(item = detail, sourceId = sourceId, storageIndex = 0)
            }
            .fold(
                onSuccess = { (downloadId, _) -> if (downloadId >= 0) 1 to 0 else 0 to 1 },
                onFailure = { 0 to 1 },
            )
    }

    /** 整剧集数（全部季，按季号 → 集号排序；取集字段与详情页一致）。 */
    private suspend fun loadSeriesEpisodes(
        repository: JellyfinRepository,
        seriesId: UUID,
    ): List<FindroidEpisode> = runCatching {
        repository
            .getSeasons(seriesId)
            .sortedBy { it.indexNumber }
            .flatMap { season -> loadSeasonEpisodes(repository, seriesId, season.id) }
    }
        .getOrElse { emptyList() }

    private suspend fun loadSeasonEpisodes(
        repository: JellyfinRepository,
        seriesId: UUID,
        seasonId: UUID,
    ): List<FindroidEpisode> = runCatching {
        repository
            .getEpisodes(
                seriesId = seriesId,
                seasonId = seasonId,
                fields = DetailDownloadRules.EPISODE_FETCH_FIELDS,
            )
            .sortedBy { it.indexNumber }
    }
        .getOrElse { emptyList() }
}
