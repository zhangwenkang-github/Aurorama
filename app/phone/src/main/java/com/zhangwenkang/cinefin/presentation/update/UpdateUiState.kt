package com.zhangwenkang.cinefin.presentation.update

import com.zhangwenkang.cinefin.update.AppVersion
import com.zhangwenkang.cinefin.update.StoredReleaseState
import com.zhangwenkang.cinefin.update.UpdateFailureCode
import com.zhangwenkang.cinefin.update.UpdateTerminalResult
import com.zhangwenkang.cinefin.update.UpdateTransferPhase
import com.zhangwenkang.cinefin.update.UpdateTransferState
import com.zhangwenkang.cinefin.update.stableUpdateId

enum class UpdateUiPhase {
    IDLE,
    CHECKING,
    UP_TO_DATE,
    AVAILABLE,
    DOWNLOADING,
    READY_FOR_VERIFICATION,
    FAILED,
    CANCELLED,
}

enum class UpdatePrimaryAction {
    NONE,
    DOWNLOAD,
    CANCEL,
    RETRY,
    RETRY_CHECK,
}

data class UpdateUiState(
    val phase: UpdateUiPhase = UpdateUiPhase.IDLE,
    val availableVersion: String? = null,
    val updateId: String? = null,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val failureCode: UpdateFailureCode? = null,
    val checkFailure: UpdateTerminalResult? = null,
    val primaryAction: UpdatePrimaryAction = UpdatePrimaryAction.NONE,
    val canCheck: Boolean = true,
) {
    val progress: Float?
        get() =
            if (totalBytes > 0L) {
                (downloadedBytes.toDouble() / totalBytes.toDouble()).toFloat().coerceIn(0f, 1f)
            } else {
                null
            }
}

internal enum class UpdateCheckWorkStatus {
    ABSENT,
    QUEUED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    BLOCKED,
}

internal data class UpdateCheckWorkObservation(
    val status: UpdateCheckWorkStatus = UpdateCheckWorkStatus.ABSENT,
    val terminal: UpdateTerminalResult? = null,
)

internal data class UpdateCheckWorkSelection(
    val running: Boolean = false,
    val terminal: UpdateTerminalResult? = null,
)

/** 每个 unique work 名称先各自收敛，MANUAL 的最终意图优先于自动检查。 */
internal fun resolveUpdateCheckWork(
    automatic: UpdateCheckWorkObservation,
    manual: UpdateCheckWorkObservation,
): UpdateCheckWorkSelection {
    if (
        manual.status == UpdateCheckWorkStatus.QUEUED ||
            manual.status == UpdateCheckWorkStatus.RUNNING ||
            manual.status == UpdateCheckWorkStatus.BLOCKED
    ) {
        return UpdateCheckWorkSelection(running = true)
    }
    manual.terminal?.let {
        return UpdateCheckWorkSelection(terminal = it)
    }
    if (automatic.status == UpdateCheckWorkStatus.RUNNING) {
        return UpdateCheckWorkSelection(running = true)
    }
    automatic.terminal?.let {
        return UpdateCheckWorkSelection(terminal = it)
    }
    return UpdateCheckWorkSelection()
}

/**
 * 把持久 release / transfer 与 WorkManager 小结果映射为页面唯一状态。
 *
 * 完成下载只映射为 `READY_FOR_VERIFICATION`；这里没有安装动作，也不推断“已校验”或“可安装”。
 */
fun buildUpdateUiState(
    currentVersion: String,
    release: StoredReleaseState?,
    transfer: UpdateTransferState? = null,
    downloadedBytes: Long = 0L,
    checkRunning: Boolean = false,
    checkTerminal: UpdateTerminalResult? = null,
): UpdateUiState {
    val current = AppVersion.parseCurrentVersionName(currentVersion)
    val availableRelease = release?.takeIf { stored ->
        val candidate = AppVersion.parseCurrentVersionName(stored.version)
        current == null || (candidate != null && candidate > current)
    }
    val applicableTransfer = transfer?.takeIf { stored ->
        val candidate = AppVersion.parseCurrentVersionName(stored.version)
        current == null || (candidate != null && candidate > current)
    }
    val updateId =
        applicableTransfer?.updateId ?: availableRelease?.let { stableUpdateId(it.release) }

    if (applicableTransfer != null) {
        val total = applicableTransfer.assetSize.coerceAtLeast(0L)
        val downloaded = downloadedBytes.coerceIn(0L, total)
        when (applicableTransfer.phase) {
            UpdateTransferPhase.DOWNLOADING ->
                return UpdateUiState(
                    phase = UpdateUiPhase.DOWNLOADING,
                    availableVersion = applicableTransfer.version,
                    updateId = updateId,
                    downloadedBytes = downloaded,
                    totalBytes = total,
                    primaryAction = UpdatePrimaryAction.CANCEL,
                )
            UpdateTransferPhase.READY_FOR_VERIFICATION ->
                return UpdateUiState(
                    phase = UpdateUiPhase.READY_FOR_VERIFICATION,
                    availableVersion = applicableTransfer.version,
                    updateId = updateId,
                    downloadedBytes = total,
                    totalBytes = total,
                    primaryAction = UpdatePrimaryAction.NONE,
                )
            UpdateTransferPhase.FAILED ->
                return UpdateUiState(
                    phase = UpdateUiPhase.FAILED,
                    availableVersion = applicableTransfer.version,
                    updateId = updateId,
                    downloadedBytes = downloaded,
                    totalBytes = total,
                    failureCode = applicableTransfer.failureCode,
                    primaryAction = UpdatePrimaryAction.RETRY,
                )
            UpdateTransferPhase.CANCELLED ->
                return UpdateUiState(
                    phase = UpdateUiPhase.CANCELLED,
                    availableVersion = applicableTransfer.version,
                    updateId = updateId,
                    downloadedBytes = downloaded,
                    totalBytes = total,
                    primaryAction = UpdatePrimaryAction.RETRY,
                )
        }
    }

    if (checkRunning || checkTerminal == UpdateTerminalResult.CHECK_IN_PROGRESS) {
        return UpdateUiState(
            phase = UpdateUiPhase.CHECKING,
            availableVersion = availableRelease?.version,
            updateId = updateId,
            primaryAction = UpdatePrimaryAction.NONE,
            canCheck = false,
        )
    }

    if (checkTerminal != null && checkTerminal.isFailure()) {
        return UpdateUiState(
            phase = UpdateUiPhase.FAILED,
            availableVersion = availableRelease?.version,
            updateId = updateId,
            checkFailure = checkTerminal,
            primaryAction = UpdatePrimaryAction.RETRY_CHECK,
        )
    }

    if (availableRelease != null) {
        return UpdateUiState(
            phase = UpdateUiPhase.AVAILABLE,
            availableVersion = availableRelease.version,
            updateId = updateId,
            totalBytes = availableRelease.assetSize,
            primaryAction = UpdatePrimaryAction.DOWNLOAD,
        )
    }

    if (checkTerminal == UpdateTerminalResult.UP_TO_DATE) {
        return UpdateUiState(phase = UpdateUiPhase.UP_TO_DATE)
    }

    return UpdateUiState()
}

private fun UpdateTerminalResult.isFailure(): Boolean =
    when (this) {
        UpdateTerminalResult.NETWORK_ERROR,
        UpdateTerminalResult.STORAGE_ERROR,
        UpdateTerminalResult.INVALID_RELEASE,
        UpdateTerminalResult.NO_COMPATIBLE_ASSET,
        UpdateTerminalResult.STATE_ERROR -> true
        UpdateTerminalResult.UP_TO_DATE,
        UpdateTerminalResult.AVAILABLE,
        UpdateTerminalResult.READY_FOR_VERIFICATION,
        UpdateTerminalResult.CHECK_GATED,
        UpdateTerminalResult.CHECK_IN_PROGRESS,
        UpdateTerminalResult.CANCELED -> false
    }
