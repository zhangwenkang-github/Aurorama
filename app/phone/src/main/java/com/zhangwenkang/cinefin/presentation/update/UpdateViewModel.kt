package com.zhangwenkang.cinefin.presentation.update

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.zhangwenkang.cinefin.BuildConfig
import com.zhangwenkang.cinefin.update.UPDATE_AUTO_CHECK_WORK_NAME
import com.zhangwenkang.cinefin.update.UPDATE_DOWNLOAD_WORK_NAME
import com.zhangwenkang.cinefin.update.UPDATE_MANUAL_CHECK_WORK_NAME
import com.zhangwenkang.cinefin.update.UpdateRepository
import com.zhangwenkang.cinefin.update.UpdateStateReadResult
import com.zhangwenkang.cinefin.update.UpdateStateStore
import com.zhangwenkang.cinefin.update.UpdateTerminalResult
import com.zhangwenkang.cinefin.update.UpdateTransferPhase
import com.zhangwenkang.cinefin.update.UpdateWorkScheduler
import com.zhangwenkang.cinefin.update.updateTerminalResult
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@HiltViewModel
class UpdateViewModel
@Inject
constructor(
    private val stateStore: UpdateStateStore,
    private val repository: UpdateRepository,
    private val scheduler: UpdateWorkScheduler,
    private val workManager: WorkManager,
) : ViewModel() {
    private val _state = MutableStateFlow(UpdateUiState())
    val state = _state.asStateFlow()

    init {
        viewModelScope.launch { refreshState(WorkSnapshot()) }
        viewModelScope.launch { observeWork() }
    }

    fun checkForUpdates() {
        dispatchUpdateAction(UpdateAction.CHECK, _state.value.updateId, scheduler)
    }

    fun download() {
        dispatchUpdateAction(UpdateAction.DOWNLOAD, _state.value.updateId, scheduler)
    }

    fun cancelDownload() {
        val updateId = _state.value.updateId ?: return
        viewModelScope.launch {
            if (cancelUpdateDownload(updateId, repository, scheduler)) {
                refreshState(lastSnapshot)
            }
        }
    }

    fun retryDownload() {
        dispatchUpdateAction(UpdateAction.DOWNLOAD, _state.value.updateId, scheduler)
    }

    fun refresh() {
        viewModelScope.launch { refreshState(lastSnapshot) }
    }

    private var lastSnapshot = WorkSnapshot()

    private suspend fun observeWork() {
        combine(
                workManager.getWorkInfosForUniqueWorkFlow(UPDATE_AUTO_CHECK_WORK_NAME),
                workManager.getWorkInfosForUniqueWorkFlow(UPDATE_MANUAL_CHECK_WORK_NAME),
                workManager.getWorkInfosForUniqueWorkFlow(UPDATE_DOWNLOAD_WORK_NAME),
            ) { automatic, manual, download ->
                WorkSnapshot(automatic = automatic, manual = manual, download = download)
            }
            .collectLatest { snapshot ->
                lastSnapshot = snapshot
                refreshState(snapshot)
                if (snapshot.download.isRunning()) {
                    while (currentCoroutineContext().isActive) {
                        delay(1_000L)
                        refreshState(snapshot)
                    }
                }
            }
    }

    private suspend fun refreshState(snapshot: WorkSnapshot) {
        val loaded =
            withContext(Dispatchers.IO) {
                val release =
                    (stateStore.loadDiscoveredRelease() as? UpdateStateReadResult.Present)?.value
                val transfer = (stateStore.loadTransfer() as? UpdateStateReadResult.Present)?.value
                val downloaded =
                    transfer
                        ?.takeIf {
                            it.phase == UpdateTransferPhase.DOWNLOADING ||
                                it.phase == UpdateTransferPhase.FAILED ||
                                it.phase == UpdateTransferPhase.CANCELLED
                        }
                        ?.let { stateStore.partFile(it).length().coerceAtLeast(0L) } ?: 0L
                Triple(release, transfer, downloaded)
            }
        val check =
            resolveUpdateCheckWork(
                automatic = snapshot.automatic.latestCheckObservation(),
                manual = snapshot.manual.latestCheckObservation(),
            )
        _state.value =
            buildUpdateUiState(
                currentVersion = BuildConfig.VERSION_NAME,
                release = loaded.first,
                transfer = loaded.second,
                downloadedBytes = loaded.third,
                checkRunning = check.running,
                checkTerminal = check.terminal,
            )
    }

    private data class WorkSnapshot(
        val automatic: List<WorkInfo> = emptyList(),
        val manual: List<WorkInfo> = emptyList(),
        val download: List<WorkInfo> = emptyList(),
    )
}

private fun List<WorkInfo>.isRunning(): Boolean = any { it.state == WorkInfo.State.RUNNING }

private fun List<WorkInfo>.latestCheckObservation(): UpdateCheckWorkObservation {
    val latest = maxByOrNull { it.generation } ?: return UpdateCheckWorkObservation()
    return when (latest.state) {
        WorkInfo.State.ENQUEUED -> UpdateCheckWorkObservation(UpdateCheckWorkStatus.QUEUED)
        WorkInfo.State.BLOCKED -> UpdateCheckWorkObservation(UpdateCheckWorkStatus.BLOCKED)
        WorkInfo.State.RUNNING -> UpdateCheckWorkObservation(UpdateCheckWorkStatus.RUNNING)
        WorkInfo.State.SUCCEEDED ->
            UpdateCheckWorkObservation(
                status = UpdateCheckWorkStatus.SUCCEEDED,
                terminal = updateTerminalResult(latest) ?: UpdateTerminalResult.STATE_ERROR,
            )
        WorkInfo.State.FAILED ->
            UpdateCheckWorkObservation(
                status = UpdateCheckWorkStatus.FAILED,
                terminal = updateTerminalResult(latest) ?: UpdateTerminalResult.STATE_ERROR,
            )
        WorkInfo.State.CANCELLED -> UpdateCheckWorkObservation(UpdateCheckWorkStatus.CANCELLED)
    }
}
