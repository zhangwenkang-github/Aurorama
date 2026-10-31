package com.zhangwenkang.cinefin.core.presentation.downloader

import android.app.DownloadManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zhangwenkang.cinefin.core.R as CoreR
import com.zhangwenkang.cinefin.models.FindroidItem
import com.zhangwenkang.cinefin.models.FindroidSourceType
import com.zhangwenkang.cinefin.models.UiText
import com.zhangwenkang.cinefin.utils.DownloadTaskStatus
import com.zhangwenkang.cinefin.utils.Downloader
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@HiltViewModel
class DownloaderViewModel @Inject constructor(private val downloader: Downloader) : ViewModel() {
    private val _state = MutableStateFlow(DownloaderState())
    val state = _state.asStateFlow()

    private val eventsChannel = Channel<DownloaderEvent>()
    val events = eventsChannel.receiveAsFlow()

    var downloadId: Long? = null

    private var downloadJob: Job? = null
    private var pollJob: Job? = null
    private var cancelJob: Job? = null
    private var requestGeneration = 0L
    private val canceledDownloadIds = mutableSetOf<Long>()

    fun update(item: FindroidItem) {
        val source =
            item.sources.firstOrNull { source ->
                source.type == FindroidSourceType.LOCAL &&
                    source.path.endsWith(".download") &&
                    (source.downloadTaskStatus == DownloadTaskStatus.PENDING.name ||
                        source.downloadTaskStatus == DownloadTaskStatus.RUNNING.name)
            } ?: return
        val sourceDownloadId = source.downloadId ?: return
        if (sourceDownloadId in canceledDownloadIds) return

        viewModelScope.launch {
            if (sourceDownloadId in canceledDownloadIds) return@launch
            val generation = beginRequest()
            downloadId = sourceDownloadId
            pollDownloadProgress(generation, sourceDownloadId)
        }
    }

    private fun download(item: FindroidItem, storageIndex: Int = 0) {
        val generation = beginRequest()
        val cancellationBarrier = cancelJob
        downloadJob = viewModelScope.launch {
            var acceptedDownloadId: Long? = null
            try {
                if (!isCurrentRequest(generation)) return@launch
                downloadId = null
                val source = item.sources.firstOrNull()
                if (source == null) {
                    publishDownloadFailure(generation)
                    return@launch
                }

                _state.emit(DownloaderState(status = DownloadManager.STATUS_PENDING))
                cancellationBarrier?.join()
                if (!isCurrentRequest(generation)) return@launch
                val (queuedDownloadId, uiText) =
                    downloader.downloadItem(
                        item = item,
                        sourceId = source.id,
                        storageIndex = storageIndex,
                    )
                if (!isCurrentRequest(generation)) return@launch
                if (queuedDownloadId == -1L) {
                    publishDownloadFailure(generation, uiText)
                } else {
                    acceptedDownloadId = queuedDownloadId
                    downloadId = queuedDownloadId
                    canceledDownloadIds.remove(queuedDownloadId)
                }
            } catch (cancellation: CancellationException) {
                if (generation == requestGeneration) {
                    downloadId = null
                    _state.value = DownloaderState()
                }
                throw cancellation
            } catch (_: Exception) {
                publishDownloadFailure(generation)
            } finally {
                if (
                    acceptedDownloadId == null &&
                        _state.value.status == DownloadManager.STATUS_PENDING
                ) {
                    publishDownloadFailure(generation)
                }
            }

            if (!isCurrentRequest(generation)) return@launch
            acceptedDownloadId?.let { queuedDownloadId ->
                eventsChannel.trySend(DownloaderEvent.Queued)
                pollDownloadProgress(generation, queuedDownloadId)
            }
        }
    }

    private suspend fun publishDownloadFailure(generation: Long, uiText: UiText? = null) {
        if (!isCurrentRequest(generation)) return
        downloadId = null
        _state.emit(
            DownloaderState(
                status = DownloadManager.STATUS_FAILED,
                errorText = uiText ?: UiText.StringResource(CoreR.string.detail_download_failed),
            )
        )
    }

    private fun cancelDownload(item: FindroidItem) {
        beginRequest()
        val handle = downloadId
        downloadId = null
        _state.value = DownloaderState()
        if (handle == null) return
        canceledDownloadIds += handle
        val previousCancellation = cancelJob
        cancelJob = viewModelScope.launch {
            previousCancellation?.join()
            try {
                downloader.cancelDownload(item = item, downloadId = handle)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                // Cancel is best-effort; its outcome must not overwrite the new request.
            }
        }
    }

    private fun deleteDownload(item: FindroidItem) {
        viewModelScope.launch {
            downloader.deleteItem(
                item = item,
                source = item.sources.first { it.type == FindroidSourceType.LOCAL },
            )
            eventsChannel.send(DownloaderEvent.Deleted)
        }
    }

    private fun pollDownloadProgress(generation: Long, downloadId: Long?) {
        if (generation != requestGeneration) return
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            while (isCurrentRequest(generation)) {
                val (status, progress) = downloader.getProgress(downloadId)
                if (!isCurrentRequest(generation)) return@launch
                _state.emit(
                    DownloaderState(
                        status = status,
                        progress = progress.coerceAtLeast(0) / 100f,
                    )
                )
                if (!isCurrentRequest(generation)) return@launch
                if (status == DownloadManager.STATUS_SUCCESSFUL) {
                    eventsChannel.trySend(DownloaderEvent.Successful)
                }
                if (!isCurrentRequest(generation)) return@launch
                if (!_state.value.isDownloading) return@launch
                delay(PROGRESS_POLL_INTERVAL_MS)
            }
        }
    }

    private fun beginRequest(): Long {
        requestGeneration += 1
        downloadJob?.cancel()
        downloadJob = null
        pollJob?.cancel()
        pollJob = null
        return requestGeneration
    }

    private suspend fun isCurrentRequest(generation: Long): Boolean =
        generation == requestGeneration && currentCoroutineContext().isActive

    fun onAction(action: DownloaderAction) {
        when (action) {
            is DownloaderAction.Download -> download(action.item, action.storageIndex)
            is DownloaderAction.DeleteDownload -> deleteDownload(action.item)
            is DownloaderAction.CancelDownload -> cancelDownload(action.item)
        }
    }

    private companion object {
        const val PROGRESS_POLL_INTERVAL_MS = 1_000L
    }
}
