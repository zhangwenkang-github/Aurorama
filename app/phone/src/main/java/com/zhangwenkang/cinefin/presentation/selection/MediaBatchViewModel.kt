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
import com.zhangwenkang.cinefin.repository.BookDownloadEnqueueResult
import com.zhangwenkang.cinefin.repository.JellyfinRepository
import com.zhangwenkang.cinefin.repository.ReaderRepository
import com.zhangwenkang.cinefin.repository.UserDataWriteOutcome
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

/** Show / Season 展开后的确认清单；候选与作用域在确认前冻结。 */
data class MediaBatchDownloadPreparation(
    val requestId: Long,
    val requestedSelectionIds: Set<String>,
    val sourceItems: List<FindroidItem>,
    val expandedFromIds: List<UUID>,
    val scopeKey: String,
    val candidates: List<FindroidItem> = emptyList(),
    val isLoading: Boolean = true,
    val isConfirming: Boolean = false,
    val loadFailureCount: Int = 0,
    val deduplicatedCandidateCount: Int = 0,
) {
    val isReady: Boolean
        get() = !isLoading && !isConfirming && loadFailureCount == 0 && candidates.isNotEmpty()
}

/** 批量动作结果（页面据此弹提示 / 起播 / 刷新）。 */
sealed interface MediaBatchEvent {
    data class DownloadQueued(val queued: Int, val failed: Int) : MediaBatchEvent

    /** W80（#23）：所选条目都已在队列 / 已下载 —— 不能静默无反应。 */
    data object DownloadSkipped : MediaBatchEvent

    data object DownloadSelectionRequired : MediaBatchEvent

    data object DownloadSelectionRejected : MediaBatchEvent

    data class DownloadSelectionFinished(
        val manifest: MediaDownloadManifest,
        val acceptedIds: List<UUID>,
        val skippedIds: List<UUID>,
        val failedIds: List<UUID>,
    ) : MediaBatchEvent

    /** W8-T1：`queued` = 网络失败但本地已保存、等后台补同步的条数（提示文案单列）。 */
    data class Updated(val changed: Int, val failed: Int, val queued: Int = 0) : MediaBatchEvent

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

    private val _downloadPreparation = MutableStateFlow<MediaBatchDownloadPreparation?>(null)
    val downloadPreparation = _downloadPreparation.asStateFlow()
    private var downloadPreparationGeneration = 0L

    private val eventsChannel = Channel<MediaBatchEvent>(Channel.BUFFERED)
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

    /**
     * 为 Show / Season 选择准备显式剧集候选。
     *
     * 此阶段只读取并展示，不创建任务。Show 按全部季展开，Season 只取本季；候选按稳定 itemId 去重，同时绑定当前 server / user 作用域。解析或加载不完整时保持
     * fail closed。
     */
    fun prepareDownloadSelection(
        requestedSelectionIds: Set<String>,
        sourceItems: List<FindroidItem>,
    ) {
        val sourceIds = sourceItems.map { item -> item.id.toString() }
        val hasDuplicateSource = sourceIds.size != sourceIds.toSet().size
        val hasMissingSource = sourceIds.toSet() != requestedSelectionIds
        if (hasDuplicateSource || hasMissingSource || sourceItems.isEmpty()) {
            viewModelScope.launch { eventsChannel.send(MediaBatchEvent.DownloadSelectionRejected) }
            return
        }
        if (!mediaBatchNeedsEpisodeSelection(sourceItems)) {
            viewModelScope.launch { eventsChannel.send(MediaBatchEvent.DownloadSelectionRequired) }
            return
        }
        val repository = repositoryProvider.get()
        val scopeKey = runCatching {
            repository.downloadScopeKey()
        }
            .getOrElse {
                viewModelScope.launch {
                    eventsChannel.send(MediaBatchEvent.DownloadSelectionRejected)
                }
                return
            }
        val generation = ++downloadPreparationGeneration
        val expandedFromIds =
            sourceItems
                .filter { item -> item is FindroidShow || item is FindroidSeason }
                .map { item -> item.id }
        _downloadPreparation.value =
            MediaBatchDownloadPreparation(
                requestId = generation,
                requestedSelectionIds = requestedSelectionIds,
                sourceItems = sourceItems,
                expandedFromIds = expandedFromIds,
                scopeKey = scopeKey,
            )
        viewModelScope.launch {
            val result = runCatching { loadDownloadCandidates(repository, sourceItems) }
            if (_downloadPreparation.value?.requestId != generation) return@launch
            result
                .onSuccess { rawCandidates ->
                    val candidates = rawCandidates.distinctBy { item -> item.id }
                    _downloadPreparation.value =
                        _downloadPreparation.value?.copy(
                            candidates = candidates,
                            isLoading = false,
                            loadFailureCount = if (candidates.isEmpty()) 1 else 0,
                            deduplicatedCandidateCount = rawCandidates.size - candidates.size,
                        )
                }
                .onFailure { error ->
                    Timber.w(error, "展开批量下载剧集失败")
                    _downloadPreparation.value =
                        _downloadPreparation.value?.copy(
                            candidates = emptyList(),
                            isLoading = false,
                            loadFailureCount = 1,
                        )
                }
        }
    }

    fun retryDownloadSelection() {
        val current = _downloadPreparation.value ?: return
        prepareDownloadSelection(
            requestedSelectionIds = current.requestedSelectionIds,
            sourceItems = current.sourceItems,
        )
    }

    fun clearDownloadSelection() {
        downloadPreparationGeneration += 1
        _downloadPreparation.value = null
    }

    /**
     * 用户确认后冻结显式叶子 manifest，再逐 ID 执行。
     *
     * 这里不会读取 Show / Season children 来补集；唯一可执行集合来自确认时的候选交集。
     */
    fun downloadPreparedSelection(
        requestId: Long,
        confirmedIds: Set<UUID>,
    ) {
        val preparation = _downloadPreparation.value ?: return
        if (preparation.requestId != requestId || !preparation.isReady) return
        val candidateIds = preparation.candidates.map { item -> item.id }
        val confirmedOrderedIds =
            candidateIds.filter { id -> id in confirmedIds } +
                (confirmedIds - candidateIds.toSet()).sortedBy(UUID::toString)
        val manifestResult =
            buildMediaDownloadManifest(
                requestedIds = confirmedOrderedIds,
                candidates = preparation.candidates,
                expandedFromIds = preparation.expandedFromIds,
                scopeKey = preparation.scopeKey,
            )
        val manifest = manifestResult.manifest
        if (manifest == null) {
            clearDownloadSelection()
            viewModelScope.launch { eventsChannel.send(MediaBatchEvent.DownloadSelectionRejected) }
            return
        }
        _downloadPreparation.value = preparation.copy(isConfirming = true)
        viewModelScope.launch {
            val repository = repositoryProvider.get()
            val currentScope = runCatching {
                repository.downloadScopeKey()
            }
                .getOrElse {
                    clearDownloadSelection()
                    eventsChannel.send(MediaBatchEvent.DownloadSelectionRejected)
                    return@launch
                }
            if (currentScope != manifest.scopeKey) {
                clearDownloadSelection()
                eventsChannel.send(MediaBatchEvent.DownloadSelectionRejected)
                return@launch
            }
            val downloaded = runCatching {
                downloader.downloadedItemIds()
            }
                .getOrElse {
                    clearDownloadSelection()
                    eventsChannel.send(MediaBatchEvent.DownloadSelectionRejected)
                    return@launch
                }
            val queued = runCatching {
                downloader.activeItemIds()
            }
                .getOrElse {
                    clearDownloadSelection()
                    eventsChannel.send(MediaBatchEvent.DownloadSelectionRejected)
                    return@launch
                }
            val selection =
                DetailDownloadRules.selectBatch(
                    itemIds = manifest.plannedIds,
                    downloaded = downloaded,
                    queued = queued,
                    limit = null,
                )
            val candidatesById = preparation.candidates.associateBy { item -> item.id }
            val accepted = mutableListOf<UUID>()
            val failed = mutableListOf<UUID>()
            for (id in selection.selected) {
                val item = candidatesById[id]
                if (item == null) {
                    failed += id
                    continue
                }
                val (queuedNow, failedNow) = enqueueEngineItem(repository, item)
                when {
                    queuedNow == 1 -> accepted += id
                    failedNow > 0 -> failed += id
                    else -> failed += id
                }
            }
            val skipped = manifest.plannedIds.filter { id -> id in downloaded || id in queued }
            val execution =
                mediaDownloadExecutionIds(
                    plannedIds = manifest.plannedIds,
                    acceptedIds = accepted,
                    skippedIds = skipped,
                    failedIds = failed,
                )
            if (!execution.isComplete) {
                clearDownloadSelection()
                eventsChannel.send(MediaBatchEvent.DownloadSelectionRejected)
                return@launch
            }
            clearDownloadSelection()
            refreshDownloadState()
            eventsChannel.send(
                MediaBatchEvent.DownloadSelectionFinished(
                    manifest = manifest,
                    acceptedIds = accepted,
                    skippedIds = skipped,
                    failedIds = failed,
                )
            )
        }
    }

    /** 直接叶子批量下载；Show / Season 必须改走显式候选确认。 */
    fun downloadSelected(items: List<FindroidItem>) {
        if (mediaBatchNeedsEpisodeSelection(items)) {
            viewModelScope.launch { eventsChannel.send(MediaBatchEvent.DownloadSelectionRequired) }
            return
        }
        val targets = batchDownloadTargets(items, capsOf(items))
        viewModelScope.launch {
            // W80（#23）：以前这里直接 return，用户点「下载」看起来毫无反应；
            // 现在无论能不能入队都给一条反馈（下发 DownloadSkipped / DownloadQueued）。
            if (targets.isEmpty()) {
                eventsChannel.send(MediaBatchEvent.DownloadSkipped)
                return@launch
            }
            val repository = repositoryProvider.get()
            var queued = 0
            var failed = 0
            // 书籍走阅读器离线文件链路（与下载页「书籍」分组同源），其余走下载引擎。
            val books = targets.filter { mediaItemIsBook(it) }
            for (book in books) {
                // W80（#23）：改为登记**后台任务**（立即返回 + 下载页可见进度），
                // 不再在页面协程里同步下完整本书（大书要几分钟，期间毫无反馈）。
                runCatching { readerRepository.enqueueBookDownload(book.id, book.name) }
                    .onSuccess { result ->
                        if (result == BookDownloadEnqueueResult.Queued) queued++
                    }
                    .onFailure { failed++ }
            }
            for (item in targets - books.toSet()) {
                val (queuedNow, failedNow) = enqueueEngineItem(repository, item)
                queued += queuedNow
                failed += failedNow
            }
            refreshDownloadState()
            eventsChannel.send(
                if (queued == 0 && failed == 0) MediaBatchEvent.DownloadSkipped
                else MediaBatchEvent.DownloadQueued(queued = queued, failed = failed)
            )
        }
    }

    /** 批量标记已看 / 已读（双向：任一未完看 → 全部标记完看；全部完看 → 全部取消）。 */
    fun markPlayedSelected(items: List<FindroidItem>) {
        val played = batchPlayedTarget(items) ?: return
        viewModelScope.launch {
            val repository = repositoryProvider.get()
            val tally = UserDataWriteTally()
            for (item in items) {
                tally.count(
                    runCatching {
                        if (played) repository.markAsPlayed(item.id)
                        else repository.markAsUnplayed(item.id)
                    }
                        .getOrNull()
                )
            }
            eventsChannel.send(tally.toEvent())
        }
    }

    /** 批量收藏（双向：任一所选未收藏 → 全部收藏；全部已收藏 → 全部取消）。 */
    fun favoriteSelected(items: List<FindroidItem>) {
        val favorite = batchFavoriteTarget(items) ?: return
        viewModelScope.launch {
            val repository = repositoryProvider.get()
            val tally = UserDataWriteTally()
            for (item in items) {
                tally.count(
                    runCatching {
                        if (favorite) repository.markAsFavorite(item.id)
                        else repository.unmarkAsFavorite(item.id)
                    }
                        .getOrNull()
                )
            }
            eventsChannel.send(tally.toEvent())
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

    private fun JellyfinRepository.downloadScopeKey(): String =
        "${getBaseUrl().trimEnd('/')}|${getUserId()}"

    /**
     * 展开候选时严格传播失败：任一季读取异常都会终止整次动作，不把异常吞成“空候选”。
     *
     * Show 使用当前 repository 的 server / user 作用域遍历全部季；Season 只请求自身。最终统一按 itemId 去重由调用方完成，冲突不会进入
     * Downloader。
     */
    private suspend fun loadDownloadCandidates(
        repository: JellyfinRepository,
        sourceItems: List<FindroidItem>,
    ): List<FindroidItem> {
        val candidates = mutableListOf<FindroidItem>()
        for (item in sourceItems) {
            when (item) {
                is FindroidShow -> {
                    val seasons = repository.getSeasons(item.id).sortedBy { it.indexNumber }
                    for (season in seasons) {
                        candidates +=
                            DetailDownloadRules.downloadTargets(
                                repository
                                    .getEpisodes(
                                        seriesId = item.id,
                                        seasonId = season.id,
                                        fields = DetailDownloadRules.EPISODE_FETCH_FIELDS,
                                    )
                                    .sortedBy { it.indexNumber }
                            )
                    }
                }
                is FindroidSeason -> {
                    candidates +=
                        DetailDownloadRules.downloadTargets(
                            repository
                                .getEpisodes(
                                    seriesId = item.seriesId,
                                    seasonId = item.id,
                                    fields = DetailDownloadRules.EPISODE_FETCH_FIELDS,
                                )
                                .sortedBy { it.indexNumber }
                        )
                }
                else -> candidates += item
            }
        }
        return candidates
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
}

/** W8-T1：批量用户数据写的结果计数（排队待同步单列，提示里与「已更新」区分）。 */
private class UserDataWriteTally {
    private var changed = 0
    private var queued = 0
    private var failed = 0

    fun count(outcome: UserDataWriteOutcome?) {
        when (outcome) {
            UserDataWriteOutcome.Confirmed -> changed++
            UserDataWriteOutcome.QueuedForSync -> queued++
            is UserDataWriteOutcome.Failed,
            null -> failed++
        }
    }

    fun toEvent(): MediaBatchEvent = MediaBatchEvent.Updated(changed, failed, queued)
}
