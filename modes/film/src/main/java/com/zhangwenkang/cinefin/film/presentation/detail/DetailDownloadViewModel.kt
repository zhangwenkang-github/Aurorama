package com.zhangwenkang.cinefin.film.presentation.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zhangwenkang.cinefin.models.FindroidItem
import com.zhangwenkang.cinefin.models.UiText
import com.zhangwenkang.cinefin.utils.Downloader
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * W51 详情页（Show / Season / Episode）下载动作共用 ViewModel。
 *
 * 职责：① 维护「已下载 / 已在队列」id 快照（只读 Room，不唤醒引擎）；② 单集 / 批量入队； ③ 把结果以 [DetailDownloadEvent] 交给屏幕弹
 * Snackbar。入队幂等由 W50 引擎保证（活动队列不重置进度）。
 */
@HiltViewModel
class DetailDownloadViewModel @Inject constructor(private val downloader: Downloader) :
    ViewModel() {
    private val _state = MutableStateFlow(DetailDownloadSnapshot())
    val state = _state.asStateFlow()

    private val eventsChannel = Channel<DetailDownloadEvent>()
    val events = eventsChannel.receiveAsFlow()

    /** 页面进入 / 入队成功后刷新快照。 */
    fun refresh() {
        viewModelScope.launch { refreshSnapshot() }
    }

    /** 单集下载：按实时状态给出「已加入下载队列 / 已在队列 / 已下载」三态提示。 */
    fun enqueueItem(item: FindroidItem, storageIndex: Int = 0) {
        viewModelScope.launch {
            when (currentStateOf(item.id)) {
                DetailDownloadState.DOWNLOADED ->
                    eventsChannel.send(DetailDownloadEvent.AlreadyDownloaded)
                DetailDownloadState.IN_QUEUE ->
                    eventsChannel.send(DetailDownloadEvent.AlreadyQueued)
                DetailDownloadState.NOT_DOWNLOADED -> enqueue(listOf(item), storageIndex)
            }
        }
    }

    /** 整剧 / 全季批量入队：调用方已用 [DetailDownloadRules] 过滤缺失集与上限。 */
    fun enqueueBatch(items: List<FindroidItem>) {
        if (items.isEmpty()) return
        viewModelScope.launch { enqueue(items, storageIndex = 0) }
    }

    /** 批量没有可加入的集时，让屏幕弹三态提示（已下载 / 已在队列 / 混合）。 */
    fun reportSkipped(selection: BatchDownloadSelection) {
        viewModelScope.launch {
            eventsChannel.send(
                DetailDownloadEvent.BatchSkipped(
                    downloaded = selection.skippedDownloaded,
                    queued = selection.skippedQueued,
                )
            )
        }
    }

    private suspend fun enqueue(items: List<FindroidItem>, storageIndex: Int) {
        _state.update { it.copy(inFlight = true) }
        val addedIds = mutableListOf<UUID>()
        var lastError: UiText? = null
        for (item in items) {
            val sourceId = item.sources.firstOrNull()?.id
            if (sourceId == null) continue
            val result = runCatching {
                downloader.downloadItem(
                    item = item,
                    sourceId = sourceId,
                    storageIndex = storageIndex,
                )
            }
                .getOrNull()
            if (result == null || result.first == -1L) {
                lastError = result?.second
                continue
            }
            addedIds += item.id
        }
        _state.update { current ->
            current.copy(inFlight = false, queuedIds = current.queuedIds + addedIds)
        }
        when {
            addedIds.isNotEmpty() && items.size == 1 ->
                eventsChannel.send(DetailDownloadEvent.AddedToQueue)
            addedIds.isNotEmpty() ->
                eventsChannel.send(DetailDownloadEvent.BatchAdded(added = addedIds.size))
            else -> eventsChannel.send(DetailDownloadEvent.Failed(lastError))
        }
    }

    /** 点击时实时读取一次状态，避免页面快照过期把「已在队列」误报成「已加入」。 */
    private suspend fun currentStateOf(itemId: UUID): DetailDownloadState {
        refreshSnapshot()
        return DetailDownloadRules.stateOf(
            itemId = itemId,
            downloaded = _state.value.downloadedIds,
            queued = _state.value.queuedIds,
        )
    }

    private suspend fun refreshSnapshot() {
        val downloaded = runCatching {
            downloader.downloadedItemIds()
        }
            .getOrElse { _state.value.downloadedIds }
        val queued = runCatching { downloader.activeItemIds() }.getOrElse { _state.value.queuedIds }
        _state.update { it.copy(downloadedIds = downloaded, queuedIds = queued) }
    }
}
