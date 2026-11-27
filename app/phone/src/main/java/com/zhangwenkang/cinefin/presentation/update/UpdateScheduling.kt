package com.zhangwenkang.cinefin.presentation.update

import com.zhangwenkang.cinefin.update.UpdateCancelResult
import com.zhangwenkang.cinefin.update.UpdateRepository
import com.zhangwenkang.cinefin.update.UpdateWorkScheduling
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal fun launchAutomaticUpdateCheck(
    scope: CoroutineScope,
    scheduler: UpdateWorkScheduling,
): Job = scope.launch(start = CoroutineStart.LAZY) { scheduler.scheduleAutomaticCheck() }

internal enum class UpdateAction {
    CHECK,
    DOWNLOAD,
    CANCEL,
}

/** 派发结果：`MISSING_UPDATE_ID` 必须被 UI 上报，禁止静默吞掉点击。 */
internal enum class UpdateDispatchResult {
    SCHEDULED,
    MISSING_UPDATE_ID,
}

internal fun dispatchUpdateAction(
    action: UpdateAction,
    updateId: String?,
    scheduler: UpdateWorkScheduling,
): UpdateDispatchResult =
    when (action) {
        UpdateAction.CHECK -> {
            scheduler.scheduleManualCheck()
            UpdateDispatchResult.SCHEDULED
        }
        UpdateAction.DOWNLOAD ->
            if (updateId == null) {
                UpdateDispatchResult.MISSING_UPDATE_ID
            } else {
                scheduler.scheduleDownload(updateId)
                UpdateDispatchResult.SCHEDULED
            }
        UpdateAction.CANCEL -> {
            scheduler.cancelDownload()
            UpdateDispatchResult.SCHEDULED
        }
    }

/** 用户取消必须先由数据层确认并持久化，再允许取消当前唯一下载 Work。 */
internal suspend fun cancelUpdateDownload(
    updateId: String?,
    repository: UpdateRepository,
    scheduler: UpdateWorkScheduling,
): Boolean {
    if (updateId == null) return false
    return when (repository.cancel(updateId)) {
        UpdateCancelResult.Success -> {
            scheduler.cancelDownload()
            true
        }
        is UpdateCancelResult.Failure -> false
    }
}
