package com.zhangwenkang.cinefin.core.presentation.downloader

import android.app.DownloadManager
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zhangwenkang.cinefin.core.R as CoreR
import com.zhangwenkang.cinefin.models.FindroidItem
import com.zhangwenkang.cinefin.models.FindroidSourceType
import com.zhangwenkang.cinefin.models.UiText
import com.zhangwenkang.cinefin.models.isDownloading
import com.zhangwenkang.cinefin.utils.Downloader
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

@HiltViewModel
class DownloaderViewModel @Inject constructor(private val downloader: Downloader) : ViewModel() {
    private val _state = MutableStateFlow(DownloaderState())
    val state = _state.asStateFlow()

    private val eventsChannel = Channel<DownloaderEvent>()
    val events = eventsChannel.receiveAsFlow()

    var downloadId: Long? = null

    private val handler by lazy { Handler(Looper.getMainLooper()) }

    fun update(item: FindroidItem) {
        viewModelScope.launch {
            if (item.isDownloading()) {
                val source =
                    item.sources.firstOrNull { it.type == FindroidSourceType.LOCAL }
                        ?: return@launch
                this@DownloaderViewModel.downloadId = source.downloadId
                pollDownloadProgress(source.downloadId)
            }
        }
    }

    private fun download(item: FindroidItem, storageIndex: Int = 0) {
        viewModelScope.launch {
            var acceptedDownloadId: Long? = null
            downloadId = null
            try {
                val source = item.sources.firstOrNull()
                if (source == null) {
                    publishDownloadFailure()
                    return@launch
                }

                _state.emit(DownloaderState(status = DownloadManager.STATUS_PENDING))
                val (queuedDownloadId, uiText) =
                    downloader.downloadItem(
                        item = item,
                        sourceId = source.id,
                        storageIndex = storageIndex,
                    )
                if (queuedDownloadId == -1L) {
                    publishDownloadFailure(uiText)
                } else {
                    acceptedDownloadId = queuedDownloadId
                    downloadId = queuedDownloadId
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                publishDownloadFailure()
            } finally {
                if (
                    acceptedDownloadId == null &&
                        _state.value.status == DownloadManager.STATUS_PENDING
                ) {
                    publishDownloadFailure()
                }
            }

            acceptedDownloadId?.let { queuedDownloadId ->
                eventsChannel.trySend(DownloaderEvent.Queued)
                pollDownloadProgress(queuedDownloadId)
            }
        }
    }

    private suspend fun publishDownloadFailure(uiText: UiText? = null) {
        downloadId = null
        _state.emit(
            DownloaderState(
                status = DownloadManager.STATUS_FAILED,
                errorText = uiText ?: UiText.StringResource(CoreR.string.detail_download_failed),
            )
        )
    }

    private fun cancelDownload(item: FindroidItem) {
        viewModelScope.launch {
            // Stop progress polling
            handler.removeCallbacksAndMessages(null)

            // Cancel the download
            downloadId?.let { downloader.cancelDownload(item = item, downloadId = it) }

            // Emit empty DownloadState
            _state.emit(DownloaderState())
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

    private fun pollDownloadProgress(downloadId: Long?) {
        handler.removeCallbacksAndMessages(null)
        val downloadProgressRunnable =
            object : Runnable {
                override fun run() {
                    viewModelScope.launch {
                        val (status, progress) = downloader.getProgress(downloadId)
                        _state.emit(
                            DownloaderState(
                                status = status,
                                progress = progress.coerceAtLeast(0) / 100f,
                            )
                        )
                    }

                    if (_state.value.status == DownloadManager.STATUS_SUCCESSFUL) {
                        eventsChannel.trySend(DownloaderEvent.Successful)
                    }

                    if (_state.value.isDownloading) {
                        handler.postDelayed(this, 1000L)
                    }
                }
            }
        handler.post(downloadProgressRunnable)
    }

    fun onAction(action: DownloaderAction) {
        when (action) {
            is DownloaderAction.Download -> download(action.item, action.storageIndex)
            is DownloaderAction.DeleteDownload -> deleteDownload(action.item)
            is DownloaderAction.CancelDownload -> cancelDownload(action.item)
        }
    }

    override fun onCleared() {
        super.onCleared()
        handler.removeCallbacksAndMessages(null)
    }
}
