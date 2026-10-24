package com.zhangwenkang.cinefin.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import com.zhangwenkang.cinefin.update.UpdateCheckFailure
import com.zhangwenkang.cinefin.update.UpdateCheckMode
import com.zhangwenkang.cinefin.update.UpdateCheckResult
import com.zhangwenkang.cinefin.update.UpdateDownloadResult
import com.zhangwenkang.cinefin.update.UpdateFailureCode
import com.zhangwenkang.cinefin.update.UpdateRepository
import com.zhangwenkang.cinefin.update.UpdateTerminalResult
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import timber.log.Timber

internal object UpdateWorkData {
    const val KEY_MODE = "mode"
    const val KEY_UPDATE_ID = "updateId"
    const val KEY_RESULT = "result"

    private val UPDATE_ID_PATTERN = Regex("^[0-9a-f]{64}$")

    fun checkInput(mode: UpdateCheckMode): Data =
        Data.Builder().putString(KEY_MODE, mode.name).build()

    fun downloadInput(updateId: String): Data {
        require(UPDATE_ID_PATTERN.matches(updateId)) { "invalid updateId" }
        return Data.Builder().putString(KEY_UPDATE_ID, updateId).build()
    }

    fun resultOutput(
        result: UpdateTerminalResult,
        updateId: String? = null,
    ): Data {
        val builder = Data.Builder().putString(KEY_RESULT, result.name)
        if (updateId != null) {
            require(UPDATE_ID_PATTERN.matches(updateId)) { "invalid updateId" }
            builder.putString(KEY_UPDATE_ID, updateId)
        }
        return builder.build()
    }

    fun parseCheckMode(data: Data): UpdateCheckMode? =
        data.getString(KEY_MODE)?.let { raw ->
            UpdateCheckMode.entries.firstOrNull { it.name == raw }
        }

    fun parseUpdateId(data: Data): String? =
        data.getString(KEY_UPDATE_ID)?.takeIf(UPDATE_ID_PATTERN::matches)

    fun parseResult(data: Data): UpdateTerminalResult? =
        data.getString(KEY_RESULT)?.let { raw ->
            UpdateTerminalResult.entries.firstOrNull { it.name == raw }
        }
}

internal data class UpdateWorkerOutcome(
    val terminal: UpdateTerminalResult,
    val isSuccess: Boolean,
)

internal object UpdateResultMapping {
    fun forCheck(result: UpdateCheckResult): UpdateWorkerOutcome =
        when (result) {
            is UpdateCheckResult.UpToDate ->
                UpdateWorkerOutcome(UpdateTerminalResult.UP_TO_DATE, isSuccess = true)
            is UpdateCheckResult.Available ->
                UpdateWorkerOutcome(UpdateTerminalResult.AVAILABLE, isSuccess = true)
            UpdateCheckResult.Gated ->
                UpdateWorkerOutcome(UpdateTerminalResult.CHECK_GATED, isSuccess = true)
            UpdateCheckResult.AlreadyRunning ->
                UpdateWorkerOutcome(UpdateTerminalResult.CHECK_IN_PROGRESS, isSuccess = true)
            is UpdateCheckResult.Failed ->
                UpdateWorkerOutcome(
                    terminal =
                        when (result.failure) {
                            UpdateCheckFailure.NETWORK_ERROR -> UpdateTerminalResult.NETWORK_ERROR
                            UpdateCheckFailure.INVALID_RELEASE ->
                                UpdateTerminalResult.INVALID_RELEASE
                            UpdateCheckFailure.NO_COMPATIBLE_ASSET ->
                                UpdateTerminalResult.NO_COMPATIBLE_ASSET
                            UpdateCheckFailure.STORAGE_ERROR -> UpdateTerminalResult.STORAGE_ERROR
                            UpdateCheckFailure.STATE_ERROR -> UpdateTerminalResult.STATE_ERROR
                        },
                    isSuccess = false,
                )
        }

    fun forDownload(result: UpdateDownloadResult): UpdateWorkerOutcome =
        when (result) {
            is UpdateDownloadResult.ReadyForVerification ->
                UpdateWorkerOutcome(UpdateTerminalResult.READY_FOR_VERIFICATION, isSuccess = true)
            is UpdateDownloadResult.Failed ->
                UpdateWorkerOutcome(
                    terminal =
                        when (result.failureCode) {
                            UpdateFailureCode.NETWORK_ERROR,
                            UpdateFailureCode.HTTP_SERVER_ERROR,
                            UpdateFailureCode.RATE_LIMITED -> UpdateTerminalResult.NETWORK_ERROR
                            UpdateFailureCode.STORAGE_ERROR -> UpdateTerminalResult.STORAGE_ERROR
                            UpdateFailureCode.INVALID_RESPONSE,
                            UpdateFailureCode.RANGE_NOT_SATISFIABLE ->
                                UpdateTerminalResult.INVALID_RELEASE
                            UpdateFailureCode.STATE_ERROR -> UpdateTerminalResult.STATE_ERROR
                        },
                    isSuccess = false,
                )
        }
}

internal fun terminalResultData(outcome: UpdateWorkerOutcome, updateId: String? = null): Data =
    UpdateWorkData.resultOutput(outcome.terminal, updateId)

@HiltWorker
class UpdateCheckWorker
@AssistedInject
constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val repository: UpdateRepository,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val mode = UpdateWorkData.parseCheckMode(inputData)
        if (mode == null) {
            return Result.failure(
                terminalResultData(
                    UpdateWorkerOutcome(UpdateTerminalResult.STATE_ERROR, isSuccess = false)
                )
            )
        }
        return try {
            val result = repository.check(mode)
            val outcome = UpdateResultMapping.forCheck(result)
            val updateId = (result as? UpdateCheckResult.Available)?.updateId
            if (outcome.isSuccess) {
                Result.success(terminalResultData(outcome, updateId))
            } else {
                Result.failure(terminalResultData(outcome, updateId))
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            Timber.w(error, "更新检查异常")
            Result.failure(
                terminalResultData(
                    UpdateWorkerOutcome(UpdateTerminalResult.STATE_ERROR, isSuccess = false)
                )
            )
        }
    }
}
