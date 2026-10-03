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
import com.zhangwenkang.cinefin.repository.JellyfinRepository
import com.zhangwenkang.cinefin.repository.LocalBookFile
import com.zhangwenkang.cinefin.repository.MusicTrackMetadata
import com.zhangwenkang.cinefin.repository.ReaderRepository
import com.zhangwenkang.cinefin.utils.DownloadMediaKind
import com.zhangwenkang.cinefin.utils.DownloadStorageUsage
import com.zhangwenkang.cinefin.utils.DownloadTask
import com.zhangwenkang.cinefin.utils.DownloadTaskStatus
import com.zhangwenkang.cinefin.utils.Downloader
import com.zhangwenkang.cinefin.utils.OfflineMediaRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
) : ViewModel() {

    private val _state = MutableStateFlow(DownloadManagerState())
    val state = _state.asStateFlow()

    private var pollingJob: Job? = null
    private var refreshing = false
    private val cachedBookMetadata = mutableMapOf<UUID, BookMetadata>()
    /** W36：「允许离线模式观看」开关状态（itemId → allow），refresh 时重读。 */
    private var offlineAllowMap: Map<UUID, Boolean> = emptyMap()

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
            DownloadAction.ToggleSelectionMode -> toggleSelectionMode()
            DownloadAction.ClearSelection -> clearSelection()
            DownloadAction.PauseSelected -> runForSelectedTasks { downloader.pauseTask(it) }
            DownloadAction.ResumeSelected -> runForSelectedTasks { downloader.resumeTask(it) }
            DownloadAction.RetrySelected -> runForSelectedTasks { downloader.retryTask(it) }
            DownloadAction.DeleteSelected -> deleteSelected()
            is DownloadAction.Pause -> runForTask(action.task) { downloader.pauseTask(it) }
            is DownloadAction.Resume -> runForTask(action.task) { downloader.resumeTask(it) }
            is DownloadAction.Retry -> runForTask(action.task) { downloader.retryTask(it) }
            is DownloadAction.DeleteTask ->
                viewModelScope.launch {
                    downloader.deleteTask(action.task)
                    refresh(showLoading = false)
                }
            is DownloadAction.DeleteCompleted ->
                viewModelScope.launch {
                    action.download.source?.let { downloader.deleteItem(action.download.item, it) }
                    refresh(showLoading = false)
                }
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
        viewModelScope.launch {
            val entry = _state.value.findEntry(key) ?: return@launch
            when {
                entry.task != null -> downloader.deleteTask(entry.task)
                entry.mediaKind == DownloadMediaKind.BOOK ->
                    readerRepository.deleteLocalFile(entry.itemId)
                else -> {
                    val completed =
                        _state.value.completed.firstOrNull { it.item.id == entry.itemId }
                    completed?.source?.let { downloader.deleteItem(completed.item, it) }
                }
            }
            refresh(showLoading = false)
        }
    }

    private fun toggleSelectionMode() {
        _state.update { current ->
            if (current.selectionMode) {
                current.copy(selectionMode = false, selection = emptySet())
            } else {
                current.copy(selectionMode = true)
            }
        }
    }

    private fun toggleSelection(key: String) {
        _state.update { current ->
            val selection =
                if (key in current.selection) current.selection - key else current.selection + key
            current.copy(selection = selection, selectionMode = true)
        }
    }

    private fun clearSelection() {
        _state.update { it.copy(selection = emptySet(), selectionMode = false) }
    }

    private fun runForTask(task: DownloadTask, block: suspend (DownloadTask) -> Any?) {
        viewModelScope.launch {
            block(task)
            refresh(showLoading = false)
        }
    }

    private fun runForSelectedTasks(block: suspend (DownloadTask) -> Any?) {
        viewModelScope.launch {
            val selected = _state.value.selectedTasks()
            for (task in selected) {
                block(task)
            }
            refresh(showLoading = false)
        }
    }

    private fun deleteSelected() {
        viewModelScope.launch {
            val current = _state.value
            deleteEntries(current.selectedEntries())
            clearSelection()
            refresh(showLoading = false)
        }
    }

    /** 删除一组层级条目：优先走任务删除，其次走已完成条目删除。 */
    private suspend fun deleteEntries(entries: List<DownloadHierarchyEntry>) {
        for (entry in entries) {
            val task = entry.task
            if (task != null) {
                downloader.deleteTask(task)
                continue
            }
            val completed = _state.value.completed.firstOrNull { it.item.id == entry.itemId }
            completed?.source?.let { downloader.deleteItem(completed.item, it) }
        }
    }

    /** W34：删除容器 = 删除容器内所有可删条目（进行中 / 失败任务 + 单个已完成条目）。 */
    private fun deleteContainer(containerKey: String) {
        viewModelScope.launch {
            val container =
                (_state.value.activeContainers +
                        _state.value.completedContainers +
                        _state.value.failedContainers)
                    .firstOrNull { it.key == containerKey } ?: return@launch
            deleteEntries(descendantEntries(container))
            _state.update { it.copy(expandedKeys = it.expandedKeys - containerKey) }
            refresh(showLoading = false)
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
            val episodeHierarchy = loadEpisodeHierarchy()
            val completed = runCatching {
                loadCompleted(episodeHierarchy)
            }
                .getOrElse { emptyList() }
            val musicById = loadMusicLibrary()
            val bookFiles = loadBookFiles()
            val completedBookIds = bookFiles.map { it.itemId }.toSet()
            val bookMetadata = resolveBookMetadata(completedBookIds)
            val storage = runCatching {
                downloader.getStorageUsage()
            }
                .getOrElse { DownloadStorageUsage(0L, 0L, 0L) }
            val bookStorageBytes = bookFiles.sumOf { it.sizeBytes }

            val activeEntries =
                tasks
                    .filter { it.status != DownloadTaskStatus.FAILED }
                    .map { task -> task.toHierarchyEntry(episodeHierarchy, musicById) }
            val failedEntries =
                tasks
                    .filter { it.status == DownloadTaskStatus.FAILED }
                    .map { task -> task.toHierarchyEntry(episodeHierarchy, musicById) }
            val completedEntries =
                completed
                    .map { download ->
                        download.toHierarchyEntry(musicById, bookMetadata, bookFiles)
                    }
                    .filterNotNull()
            val bookEntries =
                bookFiles
                    .filter { file -> completedBookIds.contains(file.itemId) }
                    .map { file ->
                        DownloadHierarchyEntry(
                            itemId = file.itemId,
                            name =
                                bookMetadata[file.itemId]?.name
                                    ?: "离线书籍 ${file.itemId.toString().take(8)}",
                            mediaKind = DownloadMediaKind.BOOK,
                            status = DownloadTaskStatus.COMPLETED,
                            sizeBytes = file.sizeBytes,
                            imageUri =
                                DownloadArtworkRules.resolve(
                                    localImage(file.itemId),
                                    bookMetadata[file.itemId]?.imageUrl,
                                ),
                            allowOffline = offlineAllowMap[file.itemId] ?: true,
                        )
                    }

            val validKeys =
                (activeEntries + failedEntries + completedEntries + bookEntries)
                    .map { it.key }
                    .toSet()

            _state.update { current ->
                current.copy(
                    isLoading = false,
                    activeTasks = tasks.filter { it.status != DownloadTaskStatus.FAILED },
                    failedTasks = tasks.filter { it.status == DownloadTaskStatus.FAILED },
                    completed = completed,
                    activeContainers = DownloadHierarchyBuilder.build(activeEntries),
                    completedContainers =
                        DownloadHierarchyBuilder.build(completedEntries + bookEntries),
                    failedContainers = DownloadHierarchyBuilder.build(failedEntries),
                    bookStorageBytes = bookStorageBytes,
                    storage = storage,
                    selection = current.selection.intersect(validKeys),
                )
            }
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

    /** W34：本地已下载剧集的节目 / 季归属（纯本地查询，离线也能组织层级）。 */
    private suspend fun loadEpisodeHierarchy(): Map<UUID, DownloadedEpisodeHierarchy> =
        runCatching {
            repository.getDownloadedEpisodeHierarchy().associateBy { it.episodeId }
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
                    song != null -> DownloadArtworkRules.resolve(localImage(itemId), song.imageUri)
                    else -> videoImageUri(episode != null, episode, itemId)
                },
            showImageUri = localImage(episode?.seriesId),
            seasonImageUri = localImage(episode?.seasonId),
            allowOffline = offlineAllowMap[itemId] ?: true,
        )
    }

    private suspend fun CompletedDownload.toHierarchyEntry(
        musicById: Map<UUID, MusicTrackMetadata>,
        bookMetadata: Map<UUID, BookMetadata>,
        bookFiles: List<LocalBookFile>,
    ): DownloadHierarchyEntry {
        val song = musicById[item.id]
        val book = bookFiles.firstOrNull { it.itemId == item.id }
        val mediaKind =
            when {
                song != null -> DownloadMediaKind.MUSIC
                book != null -> DownloadMediaKind.BOOK
                else -> DownloadMediaKind.VIDEO
            }
        return DownloadHierarchyEntry(
            itemId = item.id,
            name = item.name,
            mediaKind = mediaKind,
            status = DownloadTaskStatus.COMPLETED,
            sourceId = source?.id,
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
                    song != null -> DownloadArtworkRules.resolve(localImage(item.id), song.imageUri)
                    mediaKind == DownloadMediaKind.BOOK ->
                        DownloadArtworkRules.resolve(
                            localImage(item.id),
                            bookMetadata[item.id]?.imageUrl,
                        )
                    else ->
                        DownloadArtworkRules.resolve(
                            localImage(item.id),
                            runCatching { repository.getPrimaryImageUrl(item.id) }.getOrNull(),
                        )
                },
            showImageUri = localImage(episode?.seriesId),
            seasonImageUri = localImage(episode?.seasonId),
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

    /** W36 视频条目封面（本地优先，离线可见）： 剧集 = 剧集缩略图 → 季海报 → 节目海报 → 服务器节目海报；电影 = 本地主图。 */
    private suspend fun videoImageUri(
        isEpisode: Boolean,
        episode: DownloadedEpisodeHierarchy?,
        itemId: UUID,
    ): String? {
        if (!isEpisode) return DownloadArtworkRules.resolve(localImage(itemId), null)
        return DownloadArtworkRules.videoFallback(
            localItem = localImage(itemId),
            localSeason = localImage(episode?.seasonId),
            localSeries = localImage(episode?.seriesId),
            remoteFallback =
                episode?.seriesId?.let { seriesId ->
                    runCatching { repository.getPrimaryImageUrl(seriesId) }.getOrNull()
                },
        )
    }

    /** W36：本地图片缓存是否存在（下载时由 ImagesDownloaderWorker 落盘；不存在返回 null → 图标占位）。 */
    private fun localImage(itemId: UUID?): String? {
        if (itemId == null) return null
        val file = File(context.filesDir, "images/$itemId/primary")
        return if (file.isFile && file.length() > 0L) file.absolutePath else null
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

    private data class BookMetadata(val name: String?, val imageUrl: String?)

    override fun onCleared() {
        super.onCleared()
        stop()
    }

    private companion object {
        const val POLL_INTERVAL_MS = 1500L
    }
}
