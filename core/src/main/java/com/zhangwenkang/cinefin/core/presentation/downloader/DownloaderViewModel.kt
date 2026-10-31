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
import java.util.UUID
import java.util.WeakHashMap
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

@HiltViewModel
class DownloaderViewModel @Inject constructor(private val downloader: Downloader) : ViewModel() {
    private val _state = MutableStateFlow(DownloaderState())
    val state = _state.asStateFlow()

    private val eventsChannel = Channel<DownloaderEvent>()
    val events = eventsChannel.receiveAsFlow()

    var downloadId: Long? = null

    private var downloadJob: Job? = null
    private var pollJob: Job? = null
    private var requestGeneration = 0L
    private val cancellationOwner = DownloadCancellationOwner.forDownloader(downloader)

    fun update(item: FindroidItem) {
        val source =
            item.sources.firstOrNull { source ->
                source.type == FindroidSourceType.LOCAL &&
                    source.path.endsWith(".download") &&
                    (source.downloadTaskStatus == DownloadTaskStatus.PENDING.name ||
                        source.downloadTaskStatus == DownloadTaskStatus.RUNNING.name)
            } ?: return
        val sourceDownloadId = source.downloadId ?: return
        if (cancellationOwner.isFenced(item.id, sourceDownloadId)) return

        viewModelScope.launch {
            if (cancellationOwner.isFenced(item.id, sourceDownloadId)) return@launch
            val generation = beginRequest()
            downloadId = sourceDownloadId
            pollDownloadProgress(generation, sourceDownloadId)
        }
    }

    private fun download(item: FindroidItem, storageIndex: Int = 0) {
        val generation = beginRequest()
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
                if (!cancellationOwner.awaitCancellation(item.id)) {
                    publishDownloadFailure(generation)
                    return@launch
                }
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
                    cancellationOwner.allowNewGeneration(item.id, queuedDownloadId)
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
        cancellationOwner.cancel(downloader = downloader, item = item, handle = handle)
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

/**
 * Cancellation that has already been accepted must outlive the ViewModel which requested it. The
 * owner is scoped to the process-wide [Downloader] and keeps an item / handle fence until a later
 * download is explicitly accepted.
 */
private class DownloadCancellationOwner private constructor() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val pendingByItem = mutableMapOf<UUID, CompletableDeferred<Unit>>()
    private val fencedItems = mutableSetOf<UUID>()
    private val fencedHandles = mutableSetOf<Long>()

    fun isFenced(itemId: UUID, handle: Long): Boolean =
        synchronized(this) {
            itemId in fencedItems || handle in fencedHandles || pendingByItem.containsKey(itemId)
        }

    fun cancel(downloader: Downloader, item: FindroidItem, handle: Long) {
        val completion = CompletableDeferred<Unit>()
        synchronized(this) {
            fencedItems += item.id
            fencedHandles += handle
            if (pendingByItem.putIfAbsent(item.id, completion) != null) return
        }
        scope.launch {
            try {
                downloader.cancelDownload(item = item, downloadId = handle)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                // Cancel is best-effort; a failed attempt still completes the bounded barrier.
            } finally {
                synchronized(this@DownloadCancellationOwner) {
                    pendingByItem.remove(item.id, completion)
                }
                completion.complete(Unit)
            }
        }
    }

    suspend fun awaitCancellation(itemId: UUID): Boolean {
        val completion = synchronized(this) { pendingByItem[itemId] } ?: return true
        return withTimeoutOrNull(CANCELLATION_BARRIER_TIMEOUT_MS) {
            completion.await()
            true
        } ?: false
    }

    fun allowNewGeneration(itemId: UUID, handle: Long) {
        synchronized(this) {
            check(!pendingByItem.containsKey(itemId))
            fencedItems -= itemId
            fencedHandles -= handle
        }
    }

    companion object {
        const val CANCELLATION_BARRIER_TIMEOUT_MS = 5_000L
        val owners = WeakHashMap<Downloader, DownloadCancellationOwner>()

        fun forDownloader(downloader: Downloader): DownloadCancellationOwner =
            synchronized(owners) { owners.getOrPut(downloader) { DownloadCancellationOwner() } }
    }
}
