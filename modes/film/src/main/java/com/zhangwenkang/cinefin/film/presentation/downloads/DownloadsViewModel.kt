package com.zhangwenkang.cinefin.film.presentation.downloads

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zhangwenkang.cinefin.database.DownloadedEpisodeHierarchy
import com.zhangwenkang.cinefin.models.FindroidEpisode
import com.zhangwenkang.cinefin.models.FindroidItem
import com.zhangwenkang.cinefin.models.FindroidMovie
import com.zhangwenkang.cinefin.models.FindroidShow
import com.zhangwenkang.cinefin.models.FindroidSourceType
import com.zhangwenkang.cinefin.repository.BookDownloadStatus
import com.zhangwenkang.cinefin.repository.BookDownloadTask
import com.zhangwenkang.cinefin.repository.JellyfinRepository
import com.zhangwenkang.cinefin.repository.LocalBookFile
import com.zhangwenkang.cinefin.repository.MusicTrackMetadata
import com.zhangwenkang.cinefin.repository.ReaderRepository
import com.zhangwenkang.cinefin.utils.BookCoverProvider
import com.zhangwenkang.cinefin.utils.DownloadControlPlaneRules
import com.zhangwenkang.cinefin.utils.DownloadMediaKind
import com.zhangwenkang.cinefin.utils.DownloadMediaRecord
import com.zhangwenkang.cinefin.utils.DownloadStorageUsage
import com.zhangwenkang.cinefin.utils.DownloadTask
import com.zhangwenkang.cinefin.utils.DownloadTaskGroup
import com.zhangwenkang.cinefin.utils.DownloadTaskRules
import com.zhangwenkang.cinefin.utils.DownloadTaskStatus
import com.zhangwenkang.cinefin.utils.Downloader
import com.zhangwenkang.cinefin.utils.ImageCacheRules
import com.zhangwenkang.cinefin.utils.ImageCacheStore
import com.zhangwenkang.cinefin.utils.OfflineMediaRepository
import com.zhangwenkang.cinefin.utils.canonicalIdentity
import com.zhangwenkang.cinefin.utils.downloadIdentity
import com.zhangwenkang.cinefin.utils.offlineBookDisplayName
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import timber.log.Timber

/**
 * W32 下载管理页 ViewModel。
 *
 * 数据来源：Downloader（进行中 / 暂停 / 失败任务 + 存储占用）+ JellyfinRepository（已完成条目）。页面可见时每 1.5s 对账一次， 进程重启后由
 * DownloadManager 中的任务与 sources 表持久化状态恢复列表。
 *
 * W34：把任务与已完成条目归一化成 [DownloadHierarchyEntry]，交给纯函数 [DownloadHierarchyBuilder] 组织成「视频：节目 → 季 → 剧集 /
 * 音乐：专辑 → 曲目 / 书籍：封面卡片」的层级容器。
 */
@HiltViewModel
class DownloadsViewModel
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val downloader: Downloader,
    private val repository: JellyfinRepository,
    private val readerRepository: ReaderRepository,
    private val offlineMediaRepository: OfflineMediaRepository,
    /** W59：书籍封面自动生成（下载页书籍条目 / 书架共用）。 */
    private val bookCoverProvider: BookCoverProvider,
    /** W66：本地图片缓存（`files/images/<id>/primary` + `.meta`，与 worker 同一落点与判定规则）。 */
    private val imageCacheStore: ImageCacheStore,
) : ViewModel() {

    private val _state = MutableStateFlow(DownloadManagerState())
    val state = _state.asStateFlow()

    private val _playbackRequests =
        MutableSharedFlow<DownloadPlaybackRequest>(extraBufferCapacity = 1)
    val playbackRequests = _playbackRequests.asSharedFlow()

    private var pollingJob: Job? = null
    private var refreshing = false
    private val cachedBookMetadata = mutableMapOf<UUID, BookMetadata>()
    /** W59：服务器主图 URL 会话级缓存（只在本地图缺失时查询，避免逐条打接口）。 */
    private val remoteImageCache = mutableMapOf<UUID, String?>()
    /** W59：点击打开时按需解析的条目缓存（书籍没有 `sources` 行）。 */
    private val resolvedItemCache = mutableMapOf<UUID, FindroidItem>()
    /** W36：「允许离线模式观看」开关状态（itemId → allow），refresh 时重读。 */
    private var offlineAllowMap: Map<UUID, Boolean> = emptyMap()
    /** W66：已完成一次「缺图 / 过期图后台补齐」决策的条目（避免 1.5s 轮询重复尝试）。 */
    private val artworkBackfillAttempted = mutableSetOf<UUID>()
    private var artworkBackfillJob: Job? = null
    private var completedMediaRecords: List<DownloadMediaRecord> = emptyList()

    /** 页面进入：立即刷新 + 启动轮询；重复调用无副作用。 */
    fun start() {
        if (pollingJob != null) return
        viewModelScope.launch { refresh(showLoading = true) }
        pollingJob = viewModelScope.launch {
            while (isActive) {
                delay(POLL_INTERVAL_MS)
                refresh(showLoading = false)
            }
        }
    }

    /** 页面离开：停止轮询（下载任务本身不受影响）。 */
    fun stop() {
        pollingJob?.cancel()
        pollingJob = null
    }

    /** W34：层级条目对应的媒体条目（用于打开详情 / 阅读器）。 */
    fun itemForEntry(key: String): FindroidItem? {
        val entry = _state.value.findEntry(key) ?: return null
        return _state.value.completed.firstOrNull { it.item.id == entry.itemId }?.item
    }

    /**
     * W59：按需解析条目对象并回调（下载页点击完成条目 / 书籍行）。
     *
     * 书籍走阅读器离线链路、没有 `sources` 行，[itemForEntry] 找不到；这里优先用已完成缓存， 未命中时拉取一次条目并缓存（仅用户点击时触发，不参与轮询）。
     */
    fun resolveEntryItem(key: String, onReady: (FindroidItem) -> Unit) {
        val entry = _state.value.findEntry(key) ?: return
        _state.value.completed
            .firstOrNull { it.item.id == entry.itemId }
            ?.item
            ?.let {
                onReady(it)
                return
            }
        resolvedItemCache[entry.itemId]?.let {
            onReady(it)
            return
        }
        viewModelScope.launch {
            val item = runCatching { repository.getItem(entry.itemId) }.getOrNull() ?: return@launch
            resolvedItemCache[entry.itemId] = item
            onReady(item)
        }
    }

    fun onAction(action: DownloadAction) {
        when (action) {
            is DownloadAction.ToggleSelection -> toggleSelection(action.key)
            is DownloadAction.ToggleEntry -> toggleSelection(action.key)
            is DownloadAction.ToggleContainer ->
                _state.update { current ->
                    val expanded =
                        if (action.key in current.expandedKeys) {
                            current.expandedKeys - action.key
                        } else {
                            current.expandedKeys + action.key
                        }
                    current.copy(expandedKeys = expanded)
                }
            is DownloadAction.SetMediaFilter ->
                _state.update { it.copy(mediaFilter = action.filter) }
            is DownloadAction.DeleteContainer -> deleteContainer(action.key)
            is DownloadAction.DeleteEntry -> deleteEntry(action.key)
            is DownloadAction.ToggleOffline -> setOfflineAllowed(action.key)
            is DownloadAction.SetContainerOffline -> setContainerOffline(action.key, action.allow)
            is DownloadAction.PauseContainer ->
                runForContainerTasks(
                    action.key,
                    DownloadPendingAction.PAUSE,
                    DownloadTaskRules::canPause,
                ) {
                    downloader.pauseTask(it)
                }
            is DownloadAction.ResumeContainer ->
                runForContainerTasks(
                    action.key,
                    DownloadPendingAction.RESUME,
                    DownloadTaskRules::canResume,
                ) {
                    downloader.resumeTask(it)
                }
            is DownloadAction.EnsureBookCover -> ensureBookCover(action.itemId)
            DownloadAction.ToggleSelectionMode -> toggleSelectionMode()
            DownloadAction.ClearSelection -> clearSelection()
            DownloadAction.PauseSelected ->
                runForSelectedTasks(
                    DownloadPendingAction.PAUSE,
                    DownloadTaskRules::canPause,
                ) {
                    downloader.pauseTask(it)
                }
            DownloadAction.ResumeSelected ->
                runForSelectedTasks(
                    DownloadPendingAction.RESUME,
                    DownloadTaskRules::canResume,
                ) {
                    downloader.resumeTask(it)
                }
            DownloadAction.RetrySelected -> retrySelected()
            DownloadAction.RetryAllFailures -> retryAllFailures()
            DownloadAction.PlaySelected -> playSelected()
            is DownloadAction.SelectKeys ->
                _state.update { current ->
                    current.copy(
                        selectionMode = action.keys.isNotEmpty(),
                        selection = action.keys,
                        actionFailure = null,
                        deletionRecoveryEntries = emptyList(),
                    )
                }
            DownloadAction.DeleteSelected -> deleteSelected()
            is DownloadAction.Pause ->
                runForTask(action.task, DownloadPendingAction.PAUSE) { downloader.pauseTask(it) }
            is DownloadAction.Resume ->
                runForTask(action.task, DownloadPendingAction.RESUME) { downloader.resumeTask(it) }
            is DownloadAction.Retry ->
                runForTask(action.task, DownloadPendingAction.RETRY) { downloader.retryTask(it) }
            is DownloadAction.DeleteTask ->
                runForTask(action.task, DownloadPendingAction.DELETE) { downloader.deleteTask(it) }
            is DownloadAction.DeleteCompleted -> runCompletedDeletion(action.download)
            is DownloadAction.Open -> Unit
            is DownloadAction.OpenEntry -> Unit
        }
    }

    /** W36：切换单个层级条目的「允许离线模式观看」。 */
    private fun setOfflineAllowed(key: String) {
        viewModelScope.launch {
            val entry = _state.value.findEntry(key) ?: return@launch
            offlineMediaRepository.setAllowOffline(entry.itemId, !entry.allowOffline)
            refresh(showLoading = false)
        }
    }

    /** W36：容器级批量切换（节目 / 季 / 专辑 / 书籍容器）——对容器内全部条目生效。 */
    private fun setContainerOffline(key: String, allow: Boolean) {
        viewModelScope.launch {
            val container =
                _state.value.allContainers().firstOrNull { it.key == key } ?: return@launch
            container.descendantItemIds().forEach { itemId ->
                runCatching { offlineMediaRepository.setAllowOffline(itemId, allow) }
                    .onFailure { Timber.w(it, "切换离线开关失败：$itemId") }
            }
            refresh(showLoading = false)
        }
    }

    private fun DownloadHierarchyContainer.descendantItemIds(): List<UUID> =
        children.flatMap { child ->
            when (child) {
                is DownloadHierarchyLeaf -> listOf(child.entry.itemId)
                is DownloadHierarchySubContainer -> child.children.map { it.entry.itemId }
            }
        }

    /** W34：删除层级里的单个条目（任务 / 已完成媒体 / 阅读器离线书籍）。 */
    private fun deleteEntry(key: String) {
        _state.update { it.beginPending(DownloadPendingAction.DELETE, setOf(key)) }
        viewModelScope.launch {
            try {
                val entry = _state.value.findEntry(key) ?: return@launch
                when {
                    entry.task != null -> downloader.deleteTask(entry.task)
                    entry.mediaKind == DownloadMediaKind.BOOK -> {
                        // W80（#23）：进行中的书籍下载先取消，否则 `.part` 会继续写并重新落盘。
                        // W80b（D2）：失败任务没有 job 可取消，只取消不会让条目消失——必须连任务记录一起移除，
                        // 否则「失败」分组会一直渲染到进程重启（QA 实测的"删不掉"）。
                        if (
                            readerRepository.bookDownloads.value.any { it.itemId == entry.itemId }
                        ) {
                            readerRepository.removeBookDownload(entry.itemId)
                        }
                        readerRepository.deleteLocalFile(entry.itemId)
                    }
                    else -> {
                        val completed =
                            _state.value.completed.firstOrNull { it.item.id == entry.itemId }
                        completed?.source?.let { downloader.deleteItem(completed.item, it) }
                    }
                }
                refresh(showLoading = false)
            } finally {
                _state.update { it.finishPending(setOf(key)) }
            }
        }
    }

    private fun toggleSelectionMode() {
        _state.update { current ->
            if (current.selectionMode) {
                current.copy(
                    selectionMode = false,
                    selection = emptyList(),
                    actionFailure = null,
                    deletionRecoveryEntries = emptyList(),
                )
            } else {
                current.copy(
                    selectionMode = true,
                    actionFailure = null,
                    deletionRecoveryEntries = emptyList(),
                )
            }
        }
    }

    private fun toggleSelection(key: String) {
        _state.update { current ->
            val selection = DownloadSelectionRules.toggleSelection(current, key)
            current.copy(
                selection = selection,
                selectionMode = selection.isNotEmpty(),
                actionFailure = null,
                deletionRecoveryEntries = emptyList(),
            )
        }
    }

    private fun clearSelection() {
        _state.update {
            it.copy(
                selection = emptyList(),
                selectionMode = false,
                actionFailure = null,
                deletionRecoveryEntries = emptyList(),
            )
        }
    }

    private fun playSelected() {
        val current = _state.value
        if (!DownloadSelectionRules.canStartSelectionAction(current)) return
        val plan = DownloadSelectionRules.playbackPlan(current) ?: return
        val keys = current.selectedEntries.map { it.key }.toSet()
        _state.update { it.beginPending(DownloadPendingAction.PLAY, keys) }
        viewModelScope.launch {
            try {
                val request =
                    when (plan) {
                        is DownloadPlaybackPlan.Music -> DownloadPlaybackRequest.Music(plan.itemIds)
                        is DownloadPlaybackPlan.Video -> {
                            val byId = _state.value.completed.associateBy { it.item.id }
                            val items = plan.itemIds.mapNotNull { byId[it]?.item }
                            if (items.size != plan.itemIds.size) return@launch
                            DownloadPlaybackRequest.Video(items)
                        }
                    }
                _playbackRequests.emit(request)
                clearSelection()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                failSelectionAction(DownloadPendingAction.PLAY, keys, error)
            } finally {
                _state.update { it.finishPending(keys) }
            }
        }
    }

    private fun runForTask(
        task: DownloadTask,
        pendingAction: DownloadPendingAction,
        block: suspend (DownloadTask) -> Any?,
    ) {
        val key = taskKey(task)
        _state.update { it.beginPending(pendingAction, setOf(key)) }
        viewModelScope.launch {
            try {
                block(task)
                refresh(showLoading = false)
            } finally {
                _state.update { it.finishPending(setOf(key)) }
            }
        }
    }

    private fun runForSelectedTasks(
        pendingAction: DownloadPendingAction,
        predicate: (DownloadTaskStatus) -> Boolean = { true },
        block: suspend (DownloadTask) -> Any?,
    ) {
        if (!DownloadSelectionRules.canStartSelectionAction(_state.value)) return
        val selected = _state.value.selectedTasks.filter { predicate(it.status) }
        val keys = selected.map(::taskKey).toSet()
        _state.update { it.beginPending(pendingAction, keys) }
        viewModelScope.launch {
            try {
                for (task in selected) {
                    block(task)
                }
                refresh(showLoading = false)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                failSelectionAction(pendingAction, keys, error)
            } finally {
                _state.update { it.finishPending(keys) }
            }
        }
    }

    /** 失败页「重试全部失败」：目标与全选失败使用同一纯函数集合。 */
    private fun retryAllFailures() {
        if (!DownloadSelectionRules.canStartSelectionAction(_state.value)) return
        val selectableKeys = DownloadSelectionRules.keysFor(_state.value, DownloadTaskGroup.FAILED)
        val entries =
            _state.value.allContainers
                .flatMap { container ->
                    container.children.flatMap { child ->
                        when (child) {
                            is DownloadHierarchyLeaf -> listOf(child.entry)
                            is DownloadHierarchySubContainer -> child.children.map { it.entry }
                        }
                    }
                }
                .filter { it.key in selectableKeys }
        val tasks =
            entries
                .mapNotNull { it.task }
                .filter { DownloadTaskRules.canRetry(it.status) }
                .distinctBy { it.downloadIdentity() }
        val books =
            entries
                .filter { entry ->
                    entry.mediaKind == DownloadMediaKind.BOOK &&
                        entry.status == DownloadTaskStatus.FAILED
                }
                .distinctBy { it.key }
        val keys = selectableKeys.toSet()
        _state.update { it.beginPending(DownloadPendingAction.RETRY, keys) }
        clearSelection()
        viewModelScope.launch {
            try {
                for (task in tasks) {
                    downloader.retryTask(task)
                }
                for (book in books) {
                    readerRepository.enqueueBookDownload(book.itemId, book.name)
                }
                refresh(showLoading = false)
            } finally {
                _state.update { it.finishPending(keys) }
            }
        }
    }

    private fun retrySelected() {
        if (!DownloadSelectionRules.canStartSelectionAction(_state.value)) return
        val tasks =
            _state.value
                .selectedTasks()
                .filter { DownloadTaskRules.canRetry(it.status) }
                .distinctBy { it.downloadIdentity() }
        val books = DownloadSelectionRules.retryableBookEntries(_state.value)
        if (tasks.isEmpty() && books.isEmpty()) return
        val keys = tasks.map(::taskKey).toSet() + books.map { it.key }
        _state.update { it.beginPending(DownloadPendingAction.RETRY, keys) }
        viewModelScope.launch {
            try {
                for (task in tasks) {
                    downloader.retryTask(task)
                }
                for (book in books) {
                    readerRepository.enqueueBookDownload(book.itemId, book.name)
                }
                refresh(showLoading = false)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                failSelectionAction(DownloadPendingAction.RETRY, keys, error)
            } finally {
                _state.update { it.finishPending(keys) }
            }
        }
    }

    private fun runCompletedDeletion(download: CompletedDownload) {
        val key =
            _state.value
                .allContainers()
                .flatMap(::descendantEntries)
                .firstOrNull { entry ->
                    entry.itemId == download.item.id &&
                        (entry.sourceId == null ||
                            download.source?.id == null ||
                            entry.sourceId == download.source.id)
                }
                ?.key ?: itemKey(download.item.id)
        _state.update { it.beginPending(DownloadPendingAction.DELETE, setOf(key)) }
        viewModelScope.launch {
            try {
                download.source?.let { downloader.deleteItem(download.item, it) }
                refresh(showLoading = false)
            } finally {
                _state.update { it.finishPending(setOf(key)) }
            }
        }
    }

    /** W59 详情页「全部暂停 / 全部继续」：对容器内所有可操作任务逐个执行。 */
    private fun runForContainerTasks(
        containerKey: String,
        pendingAction: DownloadPendingAction,
        predicate: (DownloadTaskStatus) -> Boolean,
        block: suspend (DownloadTask) -> Any?,
    ) {
        val container = _state.value.containerFor(containerKey)
        val tasks =
            container
                ?.allEntries()
                ?.mapNotNull { it.task }
                ?.filter { predicate(it.status) }
                .orEmpty()
        val keys = tasks.map(::taskKey).toSet()
        _state.update { it.beginPending(pendingAction, keys) }
        viewModelScope.launch {
            try {
                for (task in tasks) {
                    block(task)
                }
                if (tasks.isNotEmpty()) refresh(showLoading = false)
            } finally {
                _state.update { it.finishPending(keys) }
            }
        }
    }

    /** W59：书籍行可见时懒生成封面（已下载书籍走本地文件；成功后刷新列表出图）。 */
    private fun ensureBookCover(itemId: UUID) {
        if (bookCoverProvider.cached(itemId) != null) return
        viewModelScope.launch {
            if (bookCoverProvider.ensureCover(itemId) != null) {
                refresh(showLoading = false)
            }
        }
    }

    private fun deleteSelected() {
        val current = _state.value
        if (!DownloadSelectionRules.canStartSelectionAction(current)) return
        val entries = current.selectedEntries
        val keys = entries.map { it.key }.toSet()
        _state.update { DownloadSelectionRules.beginDeletion(it, entries) }
        viewModelScope.launch {
            try {
                val failedKeys = deleteEntries(entries)
                _state.update { DownloadSelectionRules.completeDeletion(it, failedKeys) }
                refresh(showLoading = false)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                failSelectionAction(DownloadPendingAction.DELETE, keys, error)
            } finally {
                _state.update { it.finishPending(keys) }
            }
        }
    }

    private suspend fun failSelectionAction(
        action: DownloadPendingAction,
        keys: Set<String>,
        error: Exception,
    ) {
        Timber.e(error, "下载批次动作失败：%s", action)
        _state.update { it.failPending(action, keys) }
        refresh(showLoading = false)
    }

    /** 删除一组层级条目：优先走任务删除，其次走已完成条目删除。 */
    private suspend fun deleteEntries(entries: List<DownloadHierarchyEntry>): Set<String> {
        val candidates = DownloadDeletionRules.candidates(entries, completedMediaRecords)
        if (candidates.isEmpty()) return entries.mapTo(mutableSetOf()) { it.key }
        val result = downloader.deleteDownloads(candidates.map { it.request })
        return candidates
            .filter { it.canonicalKey in result.failedKeys }
            .mapTo(mutableSetOf()) { it.entryKey }
    }

    /** W34：删除容器 = 删除容器内所有可删条目（进行中 / 失败任务 + 单个已完成条目）。 */
    private fun deleteContainer(containerKey: String) {
        val container = _state.value.containerFor(containerKey)
        val entries = container?.let(::descendantEntries).orEmpty()
        val keys = entries.map { it.key }.toSet()
        _state.update { it.beginPending(DownloadPendingAction.DELETE, keys) }
        viewModelScope.launch {
            try {
                val failedKeys = deleteEntries(entries)
                _state.update {
                    it.copy(
                        expandedKeys =
                            if (failedKeys.isEmpty()) it.expandedKeys - containerKey
                            else it.expandedKeys,
                        actionFailure =
                            if (failedKeys.isNotEmpty()) DownloadActionFailure.DELETE else null,
                    )
                }
                refresh(showLoading = false)
            } finally {
                _state.update { it.finishPending(keys) }
            }
        }
    }

    private suspend fun refresh(showLoading: Boolean) {
        if (refreshing) return
        refreshing = true
        try {
            if (
                showLoading &&
                    _state.value.activeTasks.isEmpty() &&
                    _state.value.completed.isEmpty()
            ) {
                _state.update { it.copy(isLoading = true) }
            }

            // W34：每个数据源单独兜底——曲库 / 书籍元数据拉取失败时不能把下载任务整页清空。
            // W36：「允许离线模式观看」开关状态（sources / 书籍偏好），供条目行与容器开关显示。
            offlineAllowMap = loadOfflineAllowMap()
            // W37 遗留修复（W36 §12）：取消（快速切页）不能被吞掉，否则会把页面刷成 0 条后靠下一次轮询自愈。
            val tasks =
                try {
                    downloader.refreshDownloadTasks()
                } catch (cancellation: kotlinx.coroutines.CancellationException) {
                    throw cancellation
                } catch (error: Exception) {
                    Timber.w(error, "刷新下载任务失败")
                    emptyList()
                }
            completedMediaRecords =
                runCatching { downloader.downloadedMediaRecords() }.getOrElse { emptyList() }
            val completedCanonicalKeys =
                completedMediaRecords.mapTo(mutableSetOf()) { record ->
                    record.canonicalIdentity().canonicalKey
                }
            val activeEngineTasks =
                DownloadControlPlaneRules.activeUniqueIntents(tasks, completedCanonicalKeys)
            val failedEngineTasks = DownloadControlPlaneRules.retryableFailureIntents(tasks)
            val episodeHierarchy = loadEpisodeHierarchy()
            val bookFiles = loadBookFiles()
            val completedBookIds = bookFiles.map { it.itemId }.toSet()
            // W80（#23）：书籍整本下载任务（书架批量 / 阅读页「下载」共用）——进行中 / 失败的条目
            // 与下载引擎任务一起进「未完成 / 失败」分组，书籍下载不再"点了看不见"。
            val bookTasks = readerRepository.bookDownloads.value
            val runningBookTasks = bookTasks.filter { it.status == BookDownloadStatus.RUNNING }
            val failedBookTasks = bookTasks.filter { it.status == BookDownloadStatus.FAILED }
            val storage = runCatching {
                downloader.getStorageUsage()
            }
                .getOrElse { DownloadStorageUsage(0L, 0L, 0L) }
            val bookStorageBytes = bookFiles.sumOf { it.sizeBytes }

            // ——— W63 阶段 1：本地数据（Room / 文件）先上屏，首帧不等待任何服务器接口 ———
            val localActiveEntries = activeEngineTasks.map { task ->
                task.toHierarchyEntry(
                    episodeHierarchy = episodeHierarchy,
                    musicById = emptyMap(),
                    allowRemoteImages = false,
                )
            }
            val localFailedEntries = failedEngineTasks.map { task ->
                task.toHierarchyEntry(
                    episodeHierarchy = episodeHierarchy,
                    musicById = emptyMap(),
                    allowRemoteImages = false,
                )
            }
            val localBookEntries = bookFiles.map { file -> file.toBookEntry(serverMetadata = null) }
            val localRunningBookEntries = runningBookTasks.map { task ->
                task.toActiveBookEntry(serverName = null)
            }
            val localFailedBookEntries = failedBookTasks.map { task ->
                task.toActiveBookEntry(serverName = null)
            }
            val localKeys =
                (localActiveEntries +
                        localFailedEntries +
                        localBookEntries +
                        localRunningBookEntries +
                        localFailedBookEntries)
                    .map { it.key }
                    .toSet()
            _state.update { current ->
                current.copy(
                    isLoading = false,
                    activeTasks = activeEngineTasks,
                    failedTasks = failedEngineTasks,
                    activeContainers =
                        DownloadHierarchyBuilder.build(
                            localActiveEntries + localRunningBookEntries
                        ),
                    failedContainers =
                        DownloadHierarchyBuilder.build(localFailedEntries + localFailedBookEntries),
                    bookStorageBytes = bookStorageBytes,
                    storage = storage,
                    selection =
                        DownloadSelectionRules.selectionAfterRefresh(
                            state = current,
                            validKeys = localKeys,
                            currentEntries =
                                localActiveEntries +
                                    localFailedEntries +
                                    localBookEntries +
                                    localRunningBookEntries +
                                    localFailedBookEntries,
                        ),
                )
            }

            // ——— W63 阶段 2：服务器元数据异步补齐（已完成条目 / 曲库 / 书籍名与封面 / 远程兜底图） ———
            val completed = runCatching {
                loadCompleted(episodeHierarchy)
            }
                .getOrElse { emptyList() }
            val musicById = loadMusicLibrary()
            val bookMetadata =
                resolveBookMetadata(completedBookIds + runningBookTasks.map { it.itemId })

            val activeEntries =
                activeEngineTasks.mapBounded(REMOTE_PREFETCH_CONCURRENCY) { task ->
                    task.toHierarchyEntry(
                        episodeHierarchy = episodeHierarchy,
                        musicById = musicById,
                        // W66：阶段 2 不再拉远程兜底图；缺图走阶段 3 本地补齐。
                        allowRemoteImages = false,
                    )
                }
            val failedEntries =
                failedEngineTasks.mapBounded(REMOTE_PREFETCH_CONCURRENCY) { task ->
                    task.toHierarchyEntry(
                        episodeHierarchy = episodeHierarchy,
                        musicById = musicById,
                        allowRemoteImages = false,
                    )
                }
            val completedEntries =
                completed
                    .mapBounded(REMOTE_PREFETCH_CONCURRENCY) { download ->
                        download.toHierarchyEntry(
                            musicById = musicById,
                            bookMetadata = bookMetadata,
                            bookFiles = bookFiles,
                            allowRemoteImages = false,
                        )
                    }
                    .filterNotNull()
            val bookEntries =
                bookFiles
                    .filter { file -> completedBookIds.contains(file.itemId) }
                    .map { file -> file.toBookEntry(serverMetadata = bookMetadata[file.itemId]) }
            val runningBookEntries = runningBookTasks.map { task ->
                task.toActiveBookEntry(bookMetadata[task.itemId]?.name)
            }
            val failedBookEntries = failedBookTasks.map { task ->
                task.toActiveBookEntry(bookMetadata[task.itemId]?.name)
            }

            val validKeys =
                (activeEntries +
                        failedEntries +
                        completedEntries +
                        bookEntries +
                        runningBookEntries +
                        failedBookEntries)
                    .map { it.key }
                    .toSet()

            _state.update { current ->
                current.copy(
                    isLoading = false,
                    activeTasks = activeEngineTasks,
                    failedTasks = failedEngineTasks,
                    completed = completed,
                    activeContainers =
                        DownloadHierarchyBuilder.build(activeEntries + runningBookEntries),
                    completedContainers =
                        DownloadHierarchyBuilder.build(completedEntries + bookEntries),
                    failedContainers =
                        DownloadHierarchyBuilder.build(failedEntries + failedBookEntries),
                    bookStorageBytes = bookStorageBytes,
                    storage = storage,
                    selection =
                        DownloadSelectionRules.selectionAfterRefresh(
                            state = current,
                            validKeys = validKeys,
                            currentEntries =
                                activeEntries +
                                    failedEntries +
                                    completedEntries +
                                    bookEntries +
                                    runningBookEntries +
                                    failedBookEntries,
                        ),
                )
            }

            // ——— W66 阶段 3：缺图 / 过期图后台补齐（并发上限 4；完成后自动刷新一次，无需重进页面） ———
            scheduleArtworkBackfill(
                tasks = activeEngineTasks + failedEngineTasks,
                completed = completed,
                episodeHierarchy = episodeHierarchy,
                musicById = musicById,
                bookIds = completedBookIds,
            )
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            // 取消（离开页面）直接向上传播：不再走"保留上一次状态 + 清 loading"的失败路径。
            throw cancellation
        } catch (error: Exception) {
            // W34 调试：刷新失败不再静默，打印后保留上一次可用状态，避免整页变空。
            Timber.e(error, "下载页刷新失败")
            _state.update { it.copy(isLoading = false) }
        } finally {
            refreshing = false
        }
    }

    private suspend fun loadCompleted(
        episodeHierarchy: Map<UUID, DownloadedEpisodeHierarchy>
    ): List<CompletedDownload> {
        return repository.getDownloads().mapNotNull { item ->
            val localSources = item.sources.filter { it.type == FindroidSourceType.LOCAL }
            val completedSource = localSources.firstOrNull { !it.path.endsWith(".download") }
            // 只有进行中 / 失败 source 的电影不放进「已完成」。
            if (localSources.isNotEmpty() && completedSource == null) return@mapNotNull null
            // W34：剧集由 getCompletedEpisodeHierarchy() 单独提供（带节目 / 季归属）；
            // 空的剧集容器（show，无 LOCAL source）跳过，避免出现重复的节目卡片。
            if (item is FindroidShow && completedSource == null) {
                return@mapNotNull null
            }
            CompletedDownload(
                item = item,
                source = completedSource,
                sizeBytes = completedSource?.let { source -> File(source.path).length() } ?: 0L,
                episode = episodeHierarchy[item.id],
            )
        }
    }

    /**
     * W34：本地剧集的节目 / 季归属（纯本地查询，离线也能组织层级）。
     *
     * W59：改用「有来源即纳入」的口径——进行中 / 暂停 / 失败任务也有归属，Show 卡与详情页 才能在下载中就被正确分组（旧口径只认完成文件，进行中剧集会被当成电影平条）。
     */
    private suspend fun loadEpisodeHierarchy(): Map<UUID, DownloadedEpisodeHierarchy> =
        runCatching {
            repository.getEpisodeHierarchyWithSources().associateBy { it.episodeId }
        }
        .getOrElse { emptyMap() }

    /** W34：主库曲目快照（专辑 / 艺人 / 封面）。 */
    private suspend fun loadMusicLibrary(): Map<UUID, MusicTrackMetadata> = runCatching {
        repository.getMusicTrackMetadata()
    }
        .getOrElse { emptyMap() }

    private suspend fun loadBookFiles(): List<LocalBookFile> = runCatching {
        readerRepository.listLocalFiles()
    }
        .onFailure { Timber.w(it, "下载页书籍离线列表读取失败") }
        .getOrElse { emptyList() }

    /** W36：离线开关状态（视频 / 音乐在 sources 表，书籍在偏好里）；失败按「全部允许」。 */
    private suspend fun loadOfflineAllowMap(): Map<UUID, Boolean> = runCatching {
        offlineMediaRepository.listEntries().associate { it.itemId to it.allowOffline }
    }
        .onFailure { Timber.w(it, "读取离线开关状态失败") }
        .getOrElse { emptyMap() }

    /** 书籍元数据（名字 / 封面）：会话级缓存，离线拿不到时给占位名。 */
    private suspend fun resolveBookMetadata(bookIds: Set<UUID>): Map<UUID, BookMetadata> {
        if (bookIds.isEmpty()) return emptyMap()
        for (id in bookIds) {
            if (cachedBookMetadata.containsKey(id)) continue
            val name = runCatching {
                repository.getItem(id)?.name
            }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
            val imageUrl = runCatching { repository.getPrimaryImageUrl(id) }.getOrNull()
            if (name != null || imageUrl != null) {
                cachedBookMetadata[id] = BookMetadata(name = name, imageUrl = imageUrl)
            }
        }
        return cachedBookMetadata.filterKeys { it in bookIds }
    }

    private suspend fun DownloadTask.toHierarchyEntry(
        episodeHierarchy: Map<UUID, DownloadedEpisodeHierarchy>,
        musicById: Map<UUID, MusicTrackMetadata>,
        allowRemoteImages: Boolean = true,
    ): DownloadHierarchyEntry {
        val episode = episodeHierarchy[itemId]
        val song = musicById[itemId]
        val isMusic = mediaKind == DownloadMediaKind.MUSIC || song != null
        return DownloadHierarchyEntry(
            itemId = itemId,
            name = name,
            mediaKind =
                when {
                    isMusic -> DownloadMediaKind.MUSIC
                    mediaKind == DownloadMediaKind.BOOK -> DownloadMediaKind.BOOK
                    else -> DownloadMediaKind.VIDEO
                },
            status = status,
            sourceId = sourceId,
            serverId = serverId,
            userId = userId,
            task = this,
            downloadId = downloadId,
            sizeBytes = if (status == DownloadTaskStatus.COMPLETED) downloadedBytes else totalBytes,
            downloadedBytes = downloadedBytes,
            totalBytes = totalBytes,
            updatedAt = updatedAt,
            seriesId = episode?.seriesId,
            seasonId = episode?.seasonId,
            seriesName = episode?.seriesName,
            seasonName = episode?.seasonName,
            episodeIndex = episode?.episodeIndex ?: episodeIndex,
            seasonIndex = episode?.seasonIndex ?: seasonIndex,
            albumName = song?.albumName ?: albumName,
            artist = song?.artist ?: artist,
            trackIndex = song?.indexNumber ?: 0,
            imageUri =
                when {
                    // W66：只认本地缓存图；缺图 → 类型图标占位（后台补齐后再显示，不拉远程慢图）。
                    song != null -> localImage(itemId)
                    mediaKind == DownloadMediaKind.BOOK ->
                        localImage(itemId) ?: bookCoverProvider.cached(itemId)
                    else -> videoImageUri(episode != null, itemId, allowRemoteImages)
                },
            showImageUri =
                DownloadArtworkRules.videoArtwork(
                    DownloadArtworkRules.Level.SHOW,
                    localImage(episode?.seriesId),
                    remotePrimaryImageIfAllowed(allowRemoteImages, episode?.seriesId),
                ),
            seasonImageUri =
                DownloadArtworkRules.videoArtwork(
                    DownloadArtworkRules.Level.SEASON,
                    localImage(episode?.seasonId),
                    remotePrimaryImageIfAllowed(allowRemoteImages, episode?.seasonId),
                ),
            allowOffline = offlineAllowMap[itemId] ?: true,
        )
    }

    private suspend fun CompletedDownload.toHierarchyEntry(
        musicById: Map<UUID, MusicTrackMetadata>,
        bookMetadata: Map<UUID, BookMetadata>,
        bookFiles: List<LocalBookFile>,
        allowRemoteImages: Boolean = true,
    ): DownloadHierarchyEntry {
        val song = musicById[item.id]
        val book = bookFiles.firstOrNull { it.itemId == item.id }
        val mediaKind =
            when {
                song != null -> DownloadMediaKind.MUSIC
                book != null -> DownloadMediaKind.BOOK
                else -> DownloadMediaKind.VIDEO
            }
        val mediaRecord = completedMediaRecords.firstOrNull { record ->
            record.itemId == item.id.toString() &&
                (source?.id == null || record.sourceId == source.id) &&
                record.kind == mediaKind
        }
        return DownloadHierarchyEntry(
            itemId = item.id,
            name = item.name,
            mediaKind = mediaKind,
            status = DownloadTaskStatus.COMPLETED,
            sourceId = source?.id,
            serverId = mediaRecord?.serverId,
            userId = mediaRecord?.userId,
            sizeBytes = sizeBytes,
            updatedAt = 0L,
            seriesId = episode?.seriesId,
            seasonId = episode?.seasonId,
            seriesName = episode?.seriesName,
            seasonName = episode?.seasonName,
            episodeIndex = episode?.episodeIndex ?: 0,
            seasonIndex = episode?.seasonIndex ?: 0,
            imageUri =
                when {
                    // W66：只认本地缓存图（音乐曲目图同样由后台补齐落盘）。
                    song != null -> localImage(item.id)
                    mediaKind == DownloadMediaKind.BOOK ->
                        localImage(item.id) ?: bookCoverProvider.cached(item.id)
                    else ->
                        DownloadArtworkRules.videoArtwork(
                            if (episode != null) DownloadArtworkRules.Level.EPISODE
                            else DownloadArtworkRules.Level.MOVIE,
                            localImage(item.id),
                            remotePrimaryImageIfAllowed(allowRemoteImages, item.id),
                        )
                },
            showImageUri =
                DownloadArtworkRules.videoArtwork(
                    DownloadArtworkRules.Level.SHOW,
                    localImage(episode?.seriesId),
                    remotePrimaryImageIfAllowed(allowRemoteImages, episode?.seriesId),
                ),
            seasonImageUri =
                DownloadArtworkRules.videoArtwork(
                    DownloadArtworkRules.Level.SEASON,
                    localImage(episode?.seasonId),
                    remotePrimaryImageIfAllowed(allowRemoteImages, episode?.seasonId),
                ),
            runtimeTicks =
                when (item) {
                    is FindroidEpisode -> item.runtimeTicks
                    is FindroidMovie -> item.runtimeTicks
                    else -> 0L
                },
            albumName = song?.albumName,
            artist = song?.artist,
            trackIndex = song?.indexNumber ?: 0,
            allowOffline = offlineAllowMap[item.id] ?: true,
        )
    }

    /** W59 视频条目封面（严格同级，本地优先）： 电影 = 自身主图；剧集 = **自身缩略图（帧图）**——季 / 节目海报一律不作为回退（缺图即类型占位，防串图）。 */
    private suspend fun videoImageUri(
        isEpisode: Boolean,
        itemId: UUID,
        allowRemote: Boolean = true,
    ): String? {
        val local = localImage(itemId)
        return DownloadArtworkRules.videoArtwork(
            level =
                if (isEpisode) DownloadArtworkRules.Level.EPISODE
                else DownloadArtworkRules.Level.MOVIE,
            ownLocal = local,
            // W63：本地图已存在时不再打服务器接口；首帧阶段完全不请求远程。
            ownRemote = if (allowRemote && local == null) remotePrimaryImage(itemId) else null,
        )
    }

    /** W63：本地图存在或本阶段不允许远程兜底时返回 null（避免无谓的服务器往返）。 */
    private suspend fun remotePrimaryImageIfAllowed(
        allowRemote: Boolean,
        itemId: UUID?,
    ): String? {
        if (!allowRemote || itemId == null) return null
        if (localImage(itemId) != null) return null
        return remotePrimaryImage(itemId)
    }

    /** W59：服务器主图 URL（会话级缓存；只在本地图缺失时调用，避免逐条打接口）。 */
    private suspend fun remotePrimaryImage(itemId: UUID?): String? {
        if (itemId == null) return null
        remoteImageCache[itemId]?.let {
            return it
        }
        if (remoteImageCache.containsKey(itemId)) return null
        val url = runCatching { repository.getPrimaryImageUrl(itemId) }.getOrNull()
        remoteImageCache[itemId] = url
        return url
    }

    /** W36：本地图片缓存是否存在（下载时由 ImagesDownloaderWorker 落盘；不存在返回 null → 图标占位）。 */
    private fun localImage(itemId: UUID?): String? {
        if (itemId == null) return null
        return imageCacheStore.cachedImageFile(itemId)?.absolutePath
    }

    /** 层级容器内的全部条目（含子容器）。 */
    private fun descendantEntries(
        container: DownloadHierarchyContainer
    ): List<DownloadHierarchyEntry> =
        container.children.flatMap { child ->
            when (child) {
                is DownloadHierarchyLeaf -> listOf(child.entry)
                is DownloadHierarchySubContainer -> child.children.map { it.entry }
            }
        }

    private fun DownloadManagerState.selectedEntries(): List<DownloadHierarchyEntry> =
        allContainers()
            .flatMap { container -> descendantEntries(container) }
            .filter { it.key in selection }
            .distinctBy { it.key }

    private fun DownloadManagerState.findEntry(key: String): DownloadHierarchyEntry? =
        allContainers()
            .flatMap { container -> descendantEntries(container) }
            .firstOrNull { it.key == key }

    private fun DownloadManagerState.selectedTasks(): List<DownloadTask> =
        selectedEntries().mapNotNull { it.task }

    private fun DownloadManagerState.allContainers(): List<DownloadHierarchyContainer> =
        activeContainers + completedContainers + failedContainers

    /** W63：本地书籍文件 → 层级条目（服务器名 / 封面可缺失，缺失时用侧车标题与生成封面）。 */
    private fun LocalBookFile.toBookEntry(serverMetadata: BookMetadata?): DownloadHierarchyEntry =
        DownloadHierarchyEntry(
            itemId = itemId,
            // W62：与离线书架统一显示名口径（侧车优先），避免离线时退化成占位名导致两处清单"看着不一致"。
            name =
                offlineBookDisplayName(
                    serverName = serverMetadata?.name,
                    sidecarTitle = title,
                    itemId = itemId,
                ),
            mediaKind = DownloadMediaKind.BOOK,
            status = DownloadTaskStatus.COMPLETED,
            sizeBytes = sizeBytes,
            imageUri = localImage(itemId) ?: bookCoverProvider.cached(itemId),
            allowOffline = offlineAllowMap[itemId] ?: true,
        )

    /**
     * W80（#23）：进行中 / 失败的**书籍整本下载任务** → 下载页条目（与已完成书籍同一种 BOOK 形态）。
     *
     * 完成态仍由 [LocalBookFile.toBookEntry]（列出 `files/books` 下的 `.book` 文件）负责， 避免"任务已完成但文件未落盘"的空窗。
     */
    private fun BookDownloadTask.toActiveBookEntry(serverName: String?): DownloadHierarchyEntry {
        val entryStatus =
            if (status == BookDownloadStatus.FAILED) DownloadTaskStatus.FAILED
            else DownloadTaskStatus.RUNNING
        return DownloadHierarchyEntry(
            itemId = itemId,
            name =
                offlineBookDisplayName(
                    serverName = serverName,
                    sidecarTitle = title,
                    itemId = itemId,
                ),
            mediaKind = DownloadMediaKind.BOOK,
            status = entryStatus,
            sizeBytes = totalBytes,
            downloadedBytes = downloadedBytes,
            totalBytes = totalBytes,
            updatedAt = updatedAtEpochMillis,
            imageUri = bookCoverProvider.cached(itemId),
            bookDetail = error,
            allowOffline = offlineAllowMap[itemId] ?: true,
        )
    }

    /** W63：带并发上限的 map（服务器元数据补齐阶段用，避免一次性打出几十个请求）。 */
    private suspend fun <T, R> List<T>.mapBounded(
        concurrency: Int,
        block: suspend (T) -> R,
    ): List<R> {
        if (isEmpty()) return emptyList()
        val semaphore = Semaphore(concurrency.coerceAtLeast(1))
        return coroutineScope {
            map { item -> async { semaphore.withPermit { block(item) } } }.awaitAll()
        }
    }

    // ---- W66：下载页缩略图本地缓存补齐 / 过期替换 ----

    /** 补齐候选：条目自身 + 剧集的节目 / 季海报（音乐条目直接用歌曲主图 URL）。 */
    private data class ArtworkCandidate(val itemId: UUID, val knownUrl: String?)

    /**
     * 打开下载页后对「缺图 / 元数据缺失 / URL 变化 / 超 TTL」条目后台补齐一次（每个条目本 ViewModel 会话只尝试一次）。
     *
     * 补齐在独立协程里跑（不阻塞 1.5s 轮询）；完成后若确有图被落盘 / 替换，重扫一轮让 UI 从类型占位切到本地图。
     */
    private fun scheduleArtworkBackfill(
        tasks: List<DownloadTask>,
        completed: List<CompletedDownload>,
        episodeHierarchy: Map<UUID, DownloadedEpisodeHierarchy>,
        musicById: Map<UUID, MusicTrackMetadata>,
        bookIds: Set<UUID>,
    ) {
        if (artworkBackfillJob?.isActive == true) return
        val ids = linkedSetOf<UUID>()
        tasks.forEach { task ->
            ids.add(task.itemId)
            episodeHierarchy[task.itemId]?.let { episode ->
                ids.add(episode.seriesId)
                ids.add(episode.seasonId)
            }
        }
        completed.forEach { entry ->
            ids.add(entry.item.id)
            entry.episode?.let { episode ->
                ids.add(episode.seriesId)
                ids.add(episode.seasonId)
            }
        }
        val candidates =
            ids.filter { id -> id !in bookIds && id !in artworkBackfillAttempted }
                .map { id -> ArtworkCandidate(itemId = id, knownUrl = musicById[id]?.imageUri) }
        if (candidates.isEmpty()) return
        artworkBackfillAttempted.addAll(candidates.map { candidate -> candidate.itemId })
        artworkBackfillJob = viewModelScope.launch {
            val changed = backfillArtwork(candidates)
            if (changed) {
                refresh(showLoading = false)
            }
        }
    }

    /** 并发上限 4 的补齐：先本地判定，需要重拉才联网（URL 未知时补一次查询），原子替换后写 `.meta`。 */
    private suspend fun backfillArtwork(candidates: List<ArtworkCandidate>): Boolean {
        val now = System.currentTimeMillis()
        val results =
            candidates.mapBounded(REMOTE_PREFETCH_CONCURRENCY) { candidate ->
                val decision =
                    imageCacheStore.decide(
                        itemId = candidate.itemId,
                        currentSourceUrl = candidate.knownUrl,
                        nowMillis = now,
                    )
                if (!ImageCacheRules.shouldRefetch(decision)) return@mapBounded false
                val url =
                    candidate.knownUrl?.takeIf { value -> value.isNotBlank() }
                        ?: remotePrimaryImage(candidate.itemId)
                if (url.isNullOrBlank()) return@mapBounded false
                runCatching {
                    imageCacheStore.fetch(itemId = candidate.itemId, url = url)
                }
                    .getOrElse { error ->
                        Timber.w(error, "下载页缩略图补齐失败（%s）", candidate.itemId)
                        ImageCacheStore.FetchOutcome(changed = false, transientFailure = true)
                    }
                    .changed
            }
        return results.any { changed -> changed }
    }

    private data class BookMetadata(val name: String?, val imageUrl: String?)

    override fun onCleared() {
        super.onCleared()
        stop()
    }

    private companion object {
        const val POLL_INTERVAL_MS = 1500L

        /** W63：服务器元数据补齐阶段的并发上限。 */
        const val REMOTE_PREFETCH_CONCURRENCY = 4
    }
}
