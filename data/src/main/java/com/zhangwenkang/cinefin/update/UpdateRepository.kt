package com.zhangwenkang.cinefin.update

interface UpdateRepository {
    suspend fun check(mode: UpdateCheckMode): UpdateCheckResult

    suspend fun download(
        updateId: String,
        onProgress: (Long) -> Unit = {},
    ): UpdateDownloadResult
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
            when (val loaded = store.loadTransfer()) {
                is UpdateStateReadResult.Present -> loaded.value.takeIf { it.updateId == updateId }
                is UpdateStateReadResult.Invalid,
                UpdateStateReadResult.Absent -> null
            }
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
}
