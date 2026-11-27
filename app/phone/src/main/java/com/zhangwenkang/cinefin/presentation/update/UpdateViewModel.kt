package com.zhangwenkang.cinefin.presentation.update

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.zhangwenkang.cinefin.BuildConfig
import com.zhangwenkang.cinefin.update.UPDATE_AUTO_CHECK_WORK_NAME
import com.zhangwenkang.cinefin.update.UPDATE_DOWNLOAD_WORK_NAME
import com.zhangwenkang.cinefin.update.UPDATE_MANUAL_CHECK_WORK_NAME
import com.zhangwenkang.cinefin.update.UpdateInstallAttemptResult
import com.zhangwenkang.cinefin.update.UpdateInstallCoordinator
import com.zhangwenkang.cinefin.update.UpdateInstallPreparationResult
import com.zhangwenkang.cinefin.update.UpdateInstallReturnResult
import com.zhangwenkang.cinefin.update.UpdateRepository
import com.zhangwenkang.cinefin.update.UpdateStateReadResult
import com.zhangwenkang.cinefin.update.UpdateStateStore
import com.zhangwenkang.cinefin.update.UpdateTerminalResult
import com.zhangwenkang.cinefin.update.UpdateTransferPhase
import com.zhangwenkang.cinefin.update.UpdateWorkScheduler
import com.zhangwenkang.cinefin.update.classifyInstallReturn
import com.zhangwenkang.cinefin.update.updateTerminalResult
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
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
    private val installCoordinator: UpdateInstallCoordinator<Intent>,
) : ViewModel() {
    private val _state = MutableStateFlow(UpdateUiState())
    val state = _state.asStateFlow()
    private val _installLaunchRequests = MutableSharedFlow<Intent>(extraBufferCapacity = 1)
    val installLaunchRequests = _installLaunchRequests.asSharedFlow()

    private var lastBaseState = UpdateUiState()
    private var installStatus = UpdateInstallUiStatus()
    private var installBaselineVersionCode: Long? = null
    private var automaticVerificationUpdateId: String? = null
    private var requestFailure: UpdateRequestFailure? = null

    /**
     * 本会话里用户亲手点过下载的 updateId。只有它非空时才把下载 Work 的排队/失败映射到页面， 避免冷启动读到上次会话遗留的 FAILED Work
     * 就把「发现新版本」错报成「下载失败」。
     */
    private var dispatchedDownloadId: String? = null

    /** 派发那一刻已存在的 Work 代际上限。REPLACE 传播前 flow 仍会读到上一代终态， 用它做地板过滤，点击后第一帧就只能是「正在开始下载」。 */
    private var downloadDispatchGeneration: Int? = null

    init {
        viewModelScope.launch { refreshState(WorkSnapshot()) }
        viewModelScope.launch { observeWork() }
    }

    fun checkForUpdates() {
        resetInstallStatus()
        dispatchedDownloadId = null
        downloadDispatchGeneration = null
        requestFailure = null
        dispatchUpdateAction(UpdateAction.CHECK, _state.value.updateId, scheduler)
    }

    fun download() {
        resetInstallStatus()
        startDownload()
    }

    fun cancelDownload() {
        if (_state.value.phase == UpdateUiPhase.STARTING_DOWNLOAD) {
            cancelQueuedDownload(scheduler)
            dispatchedDownloadId = null
            downloadDispatchGeneration = null
            viewModelScope.launch { refreshState(lastSnapshot) }
            return
        }
        val updateId = _state.value.updateId ?: return
        viewModelScope.launch {
            if (cancelUpdateDownload(updateId, repository, scheduler)) {
                refreshState(lastSnapshot)
            }
        }
    }

    fun retryDownload() {
        resetInstallStatus()
        startDownload()
    }

    /** 点击后立刻刷新一次，让「正在开始下载」在不依赖 WorkManager 首帧的情况下就能显示； 拿不到 updateId 时把失败回写到页面，禁止静默返回。 */
    private fun startDownload() {
        val updateId = _state.value.updateId
        // 先记下派发前的代际：新 Work 的代际必然更大，旧失败码不会抢在 REPLACE 传播前出现在首帧。
        val floorGeneration = lastSnapshot.download.maxOfOrNull { it.generation }
        val dispatched = dispatchUpdateAction(UpdateAction.DOWNLOAD, updateId, scheduler)
        if (dispatched == UpdateDispatchResult.MISSING_UPDATE_ID) {
            requestFailure = UpdateRequestFailure.MISSING_UPDATE_ID
            renderInstallStatus()
            return
        }
        requestFailure = null
        downloadDispatchGeneration = floorGeneration
        dispatchedDownloadId = updateId
        viewModelScope.launch { refreshState(lastSnapshot) }
    }

    fun install() {
        val updateId = _state.value.updateId ?: return
        if (
            installStatus.phase == UpdateInstallUiPhase.VERIFYING ||
                installStatus.phase == UpdateInstallUiPhase.INSTALLER_LAUNCHED
        ) {
            return
        }
        installStatus = UpdateInstallUiStatus(UpdateInstallUiPhase.VERIFYING, updateId)
        renderInstallStatus()
        viewModelScope.launch {
            when (val result = installCoordinator.verifyAndLaunch(updateId)) {
                is UpdateInstallAttemptResult.Launched -> {
                    installBaselineVersionCode = result.baselineVersionCode
                    installStatus =
                        UpdateInstallUiStatus(
                            phase = UpdateInstallUiPhase.INSTALLER_LAUNCHED,
                            updateId = updateId,
                        )
                    renderInstallStatus()
                    _installLaunchRequests.emit(result.command)
                }
                is UpdateInstallAttemptResult.VerificationFailed -> {
                    installStatus =
                        UpdateInstallUiStatus(
                            phase = UpdateInstallUiPhase.VERIFICATION_FAILED,
                            updateId = updateId,
                            verificationFailure = result.failure,
                        )
                    refreshState(lastSnapshot)
                }
                is UpdateInstallAttemptResult.StateFailure -> setInstallResultFailed(updateId)
            }
        }
    }

    fun onInstallerReturned() {
        val baseline = installBaselineVersionCode
        val updateId = installStatus.updateId ?: _state.value.updateId
        if (baseline == null || updateId == null) {
            setInstallResultFailed(updateId)
            return
        }
        viewModelScope.launch {
            val result =
                withContext(Dispatchers.IO) {
                    classifyInstallReturn(baseline) {
                        installCoordinator.readInstalledVersionCode()
                    }
                }
            installStatus =
                UpdateInstallUiStatus(
                    phase =
                        when (result) {
                            UpdateInstallReturnResult.Installed -> UpdateInstallUiPhase.INSTALLED
                            UpdateInstallReturnResult.CancelledOrUnchanged ->
                                UpdateInstallUiPhase.CANCELLED_OR_UNCHANGED
                            UpdateInstallReturnResult.Failed -> UpdateInstallUiPhase.RESULT_FAILED
                        },
                    updateId = updateId,
                )
            renderInstallStatus()
        }
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
                download =
                    resolveDispatchedDownloadObservation(
                        samples = snapshot.download.map { it.toDownloadSample() },
                        dispatchedDownloadId = dispatchedDownloadId,
                        dispatchGeneration = downloadDispatchGeneration,
                    ),
            )
        lastBaseState = _state.value
        if (lastBaseState.phase == UpdateUiPhase.READY_FOR_VERIFICATION) {
            dispatchedDownloadId = null
            downloadDispatchGeneration = null
        }
        renderInstallStatus()
        val readyUpdateId =
            lastBaseState.updateId?.takeIf {
                lastBaseState.phase == UpdateUiPhase.READY_FOR_VERIFICATION
            }
        if (
            readyUpdateId != null &&
                installStatus.phase == UpdateInstallUiPhase.IDLE &&
                automaticVerificationUpdateId != readyUpdateId
        ) {
            startAutomaticVerification(readyUpdateId)
        }
    }

    private fun startAutomaticVerification(updateId: String) {
        automaticVerificationUpdateId = updateId
        installStatus = UpdateInstallUiStatus(UpdateInstallUiPhase.VERIFYING, updateId)
        renderInstallStatus()
        viewModelScope.launch {
            when (val result = installCoordinator.prepare(updateId)) {
                is UpdateInstallPreparationResult.Prepared ->
                    installStatus =
                        UpdateInstallUiStatus(UpdateInstallUiPhase.READY_TO_INSTALL, updateId)
                is UpdateInstallPreparationResult.VerificationFailed ->
                    installStatus =
                        UpdateInstallUiStatus(
                            phase = UpdateInstallUiPhase.VERIFICATION_FAILED,
                            updateId = updateId,
                            verificationFailure = result.failure,
                        )
                is UpdateInstallPreparationResult.StateFailure ->
                    installStatus =
                        UpdateInstallUiStatus(
                            phase = UpdateInstallUiPhase.RESULT_FAILED,
                            updateId = updateId,
                        )
            }
            if (
                installStatus.phase == UpdateInstallUiPhase.VERIFICATION_FAILED ||
                    installStatus.phase == UpdateInstallUiPhase.RESULT_FAILED
            ) {
                refreshState(lastSnapshot)
            } else {
                renderInstallStatus()
            }
        }
    }

    private fun resetInstallStatus() {
        installStatus = UpdateInstallUiStatus()
        installBaselineVersionCode = null
        automaticVerificationUpdateId = null
        renderInstallStatus()
    }

    private fun setInstallResultFailed(updateId: String?) {
        installStatus =
            UpdateInstallUiStatus(
                phase = UpdateInstallUiPhase.RESULT_FAILED,
                updateId = updateId,
            )
        renderInstallStatus()
    }

    private fun renderInstallStatus() {
        _state.value =
            applyUpdateRequestFailure(
                applyUpdateInstallUiStatus(lastBaseState, installStatus),
                requestFailure,
            )
    }

    private data class WorkSnapshot(
        val automatic: List<WorkInfo> = emptyList(),
        val manual: List<WorkInfo> = emptyList(),
        val download: List<WorkInfo> = emptyList(),
    )
}

private fun List<WorkInfo>.isRunning(): Boolean = any { it.state == WorkInfo.State.RUNNING }

/** 只有本会话亲手派发过下载时才暴露 Work 观测；没有观测到对应 Work 的一帧按 QUEUED 处理， 保证点击后立刻就有「正在开始下载」的反馈。 */
private fun WorkInfo.toDownloadSample(): DownloadWorkSample =
    DownloadWorkSample(
        generation = generation,
        status =
            when (state) {
                WorkInfo.State.ENQUEUED -> UpdateCheckWorkStatus.QUEUED
                WorkInfo.State.BLOCKED -> UpdateCheckWorkStatus.BLOCKED
                WorkInfo.State.RUNNING -> UpdateCheckWorkStatus.RUNNING
                WorkInfo.State.SUCCEEDED -> UpdateCheckWorkStatus.SUCCEEDED
                WorkInfo.State.FAILED -> UpdateCheckWorkStatus.FAILED
                WorkInfo.State.CANCELLED -> UpdateCheckWorkStatus.CANCELLED
            },
        terminal =
            updateTerminalResult(this).takeIf {
                state == WorkInfo.State.SUCCEEDED || state == WorkInfo.State.FAILED
            },
    )

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
