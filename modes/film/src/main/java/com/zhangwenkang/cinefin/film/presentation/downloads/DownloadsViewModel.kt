package com.zhangwenkang.cinefin.film.presentation.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zhangwenkang.cinefin.models.FindroidSourceType
import com.zhangwenkang.cinefin.repository.JellyfinRepository
import com.zhangwenkang.cinefin.utils.DownloadTask
import com.zhangwenkang.cinefin.utils.DownloadTaskStatus
import com.zhangwenkang.cinefin.utils.Downloader
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * W32 下载管理页 ViewModel。
 *
 * 数据来源：Downloader（进行中 / 暂停 / 失败任务 + 存储占用）+ JellyfinRepository（已完成条目）。页面可见时每 1.5s 对账一次， 进程重启后由
 * DownloadManager 中的任务与 sources 表持久化状态恢复列表。
 */
@HiltViewModel
class DownloadsViewModel
@Inject
constructor(
    private val downloader: Downloader,
    private val repository: JellyfinRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(DownloadManagerState())
    val state = _state.asStateFlow()

    private var pollingJob: Job? = null
    private var refreshing = false

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

    fun onAction(action: DownloadAction) {
        when (action) {
            is DownloadAction.ToggleSelection -> toggleSelection(action.key)
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
            for (task in current.selectedTasks()) {
                downloader.deleteTask(task)
            }
            for (download in current.selectedCompleted()) {
                download.source?.let { downloader.deleteItem(download.item, it) }
            }
            clearSelection()
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

            val tasks = downloader.refreshDownloadTasks()
            val completed = loadCompleted()
            val storage = downloader.getStorageUsage()
            val validKeys =
                tasks.map(::taskKey).toSet() + completed.map { completedKey(it.item) }.toSet()

            _state.update { current ->
                current.copy(
                    isLoading = false,
                    activeTasks = tasks.filter { it.status != DownloadTaskStatus.FAILED },
                    failedTasks = tasks.filter { it.status == DownloadTaskStatus.FAILED },
                    completed = completed,
                    storage = storage,
                    selection = current.selection.intersect(validKeys),
                )
            }
        } catch (_: Exception) {
            _state.update { it.copy(isLoading = false) }
        } finally {
            refreshing = false
        }
    }

    private suspend fun loadCompleted(): List<CompletedDownload> {
        return repository.getDownloads().mapNotNull { item ->
            val localSources = item.sources.filter { it.type == FindroidSourceType.LOCAL }
            val completedSource = localSources.firstOrNull { !it.path.endsWith(".download") }
            // 只有进行中 / 失败 source 的电影不放进「已完成」；剧集容器（无 LOCAL source）保留入口。
            if (localSources.isNotEmpty() && completedSource == null) return@mapNotNull null
            CompletedDownload(
                item = item,
                source = completedSource,
                sizeBytes = completedSource?.let { source -> File(source.path).length() } ?: 0L,
            )
        }
    }

    private fun DownloadManagerState.selectedTasks(): List<DownloadTask> {
        return (activeTasks + failedTasks).filter { taskKey(it) in selection }
    }

    private fun DownloadManagerState.selectedCompleted(): List<CompletedDownload> {
        return completed.filter { completedKey(it.item) in selection }
    }

    override fun onCleared() {
        super.onCleared()
        stop()
    }

    private companion object {
        const val POLL_INTERVAL_MS = 1500L
    }
}
