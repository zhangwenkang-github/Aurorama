package com.zhangwenkang.cinefin.presentation.film.downloads

import com.zhangwenkang.cinefin.core.R as CoreR
import com.zhangwenkang.cinefin.film.presentation.downloads.DownloadManagerState
import com.zhangwenkang.cinefin.film.presentation.downloads.DownloadPendingAction
import com.zhangwenkang.cinefin.film.presentation.downloads.DownloadSelectionRules
import com.zhangwenkang.cinefin.utils.DownloadTaskGroup
import com.zhangwenkang.cinefin.utils.DownloadTaskRules

internal enum class DownloadSelectionActionId {
    PLAY_SELECTED,
    PAUSE_SELECTED,
    RESUME_SELECTED,
    RETRY_SELECTED,
    DELETE_SELECTED,
}

@JvmInline internal value class DownloadSelectionLabel(val resourceId: Int)

internal data class DownloadSelectionActionSpec(
    val actionId: DownloadSelectionActionId,
    val label: DownloadSelectionLabel,
    val iconRes: Int,
    val enabled: Boolean,
    val pending: Boolean,
)

internal fun downloadSelectionActionSpecs(
    state: DownloadManagerState,
    group: DownloadTaskGroup,
): List<DownloadSelectionActionSpec> {
    val selectedTasks = state.selectedTasks
    val pendingActions = state.selectedEntries.mapNotNull { state.pendingActions[it.key] }.toSet()
    val canStart = DownloadSelectionRules.canStartSelectionAction(state)
    val canRetrySelected =
        DownloadSelectionRules.retryableTasks(state).isNotEmpty() ||
            DownloadSelectionRules.retryableBookEntries(state).isNotEmpty()
    return buildList {
        if (
            group == DownloadTaskGroup.COMPLETED &&
                DownloadSelectionRules.playbackPlan(state) != null
        ) {
            add(
                DownloadSelectionActionSpec(
                    actionId = DownloadSelectionActionId.PLAY_SELECTED,
                    label = DownloadSelectionLabel(CoreR.string.download_action_play_selected),
                    iconRes = CoreR.drawable.ic_play,
                    enabled = canStart,
                    pending = DownloadPendingAction.PLAY in pendingActions,
                )
            )
        }
        if (group == DownloadTaskGroup.ACTIVE && selectedTasks.any(DownloadTaskRules::canPause)) {
            add(
                DownloadSelectionActionSpec(
                    actionId = DownloadSelectionActionId.PAUSE_SELECTED,
                    label = DownloadSelectionLabel(CoreR.string.download_action_pause),
                    iconRes = CoreR.drawable.ic_pause,
                    enabled = canStart,
                    pending = DownloadPendingAction.PAUSE in pendingActions,
                )
            )
        }
        if (
            group == DownloadTaskGroup.ACTIVE &&
                selectedTasks.any { DownloadTaskRules.canResume(it.status) }
        ) {
            add(
                DownloadSelectionActionSpec(
                    actionId = DownloadSelectionActionId.RESUME_SELECTED,
                    label = DownloadSelectionLabel(CoreR.string.download_action_resume_selected),
                    iconRes = CoreR.drawable.ic_download,
                    enabled = canStart,
                    pending = DownloadPendingAction.RESUME in pendingActions,
                )
            )
        }
        if (group == DownloadTaskGroup.FAILED && canRetrySelected) {
            add(
                DownloadSelectionActionSpec(
                    actionId = DownloadSelectionActionId.RETRY_SELECTED,
                    label = DownloadSelectionLabel(CoreR.string.download_action_retry),
                    iconRes = CoreR.drawable.ic_rotate_ccw,
                    enabled = canStart,
                    pending = DownloadPendingAction.RETRY in pendingActions,
                )
            )
        }
        if (state.hasSelection) {
            add(
                DownloadSelectionActionSpec(
                    actionId = DownloadSelectionActionId.DELETE_SELECTED,
                    label = DownloadSelectionLabel(CoreR.string.download_action_delete),
                    iconRes = CoreR.drawable.ic_trash,
                    enabled = canStart,
                    pending = DownloadPendingAction.DELETE in pendingActions,
                )
            )
        }
    }
}
