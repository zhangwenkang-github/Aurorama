package com.zhangwenkang.cinefin.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

interface UpdateRepository {
    suspend fun check(mode: UpdateCheckMode): UpdateCheckResult

    suspend fun download(
        updateId: String,
        onProgress: (Long) -> Unit = {},
    ): UpdateDownloadResult

    suspend fun cancel(updateId: String): UpdateCancelResult
}

sealed interface UpdateCancelResult {
    data object Success : UpdateCancelResult

    data class Failure(val reason: String) : UpdateCancelResult
}

fun interface UpdateDownloadExecutor {
    suspend fun download(
        release: UpdateReleaseIdentity,
        onProgress: (Long) -> Unit,
    ): UpdateDownloadResult
}

class UpdateRepositoryImpl(
    private val coordinator: UpdateCheckCoordinator,
    private val store: UpdateStateStore,
    private val downloader: UpdateDownloadExecutor,
) : UpdateRepository {
    override suspend fun check(mode: UpdateCheckMode): UpdateCheckResult = coordinator.check(mode)

    override suspend fun download(
        updateId: String,
        onProgress: (Long) -> Unit,
    ): UpdateDownloadResult {
        val transfer =
            persistedTransfer(updateId)
                ?: return UpdateDownloadResult.Failed(
                    UpdateFailureCode.STATE_ERROR,
                    "transfer state is absent or does not match updateId",
                )

        if (transfer.phase == UpdateTransferPhase.READY_FOR_VERIFICATION) {
            val complete = store.completeFile(transfer)
            if (complete.isFile && complete.length() == transfer.assetSize) {
                return UpdateDownloadResult.ReadyForVerification(
                    updateId = transfer.updateId,
                    completeFile = complete,
                    bytesWritten = transfer.assetSize,
                )
            }
        }
        return downloader.download(transfer.release, onProgress)
    }

    /**
     * 下载器只在 [store.beginDownload] 之后才能工作，而 beginDownload 的唯一其他调用点就在下载器内部。 因此这里必须能从已持久化的 release
     * 状态重建 transfer，否则「检查更新 → 下载」必然死锁。
     */
    private fun persistedTransfer(updateId: String): UpdateTransferState? {
        when (val loaded = store.loadTransfer()) {
            is UpdateStateReadResult.Present ->
                loaded.value
                    .takeIf { it.updateId == updateId }
                    ?.let {
                        return it
                    }
            is UpdateStateReadResult.Invalid,
            UpdateStateReadResult.Absent -> Unit
        }
        val release =
            when (val loaded = store.loadDiscoveredRelease()) {
                is UpdateStateReadResult.Present ->
                    loaded.value.release.takeIf { stableUpdateId(it) == updateId }
                is UpdateStateReadResult.Invalid,
                UpdateStateReadResult.Absent -> null
            } ?: return null
        if (store.beginDownload(release) is UpdateStateWriteResult.Failure) {
            return null
        }
        return when (val reloaded = store.loadTransfer()) {
            is UpdateStateReadResult.Present -> reloaded.value.takeIf { it.updateId == updateId }
            is UpdateStateReadResult.Invalid,
            UpdateStateReadResult.Absent -> null
        }
    }

    override suspend fun cancel(updateId: String): UpdateCancelResult =
        withContext(Dispatchers.IO) {
            when (val result = store.cancelDownload(updateId)) {
                UpdateStateWriteResult.Success -> UpdateCancelResult.Success
                is UpdateStateWriteResult.Failure -> UpdateCancelResult.Failure(result.reason)
            }
        }
}
