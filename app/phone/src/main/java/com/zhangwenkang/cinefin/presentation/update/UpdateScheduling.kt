package com.zhangwenkang.cinefin.presentation.update

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

internal fun dispatchUpdateAction(
    action: UpdateAction,
    updateId: String?,
    scheduler: UpdateWorkScheduling,
) {
    when (action) {
        UpdateAction.CHECK -> scheduler.scheduleManualCheck()
        UpdateAction.DOWNLOAD -> updateId?.let(scheduler::scheduleDownload)
        UpdateAction.CANCEL -> scheduler.cancelDownload()
    }
}
