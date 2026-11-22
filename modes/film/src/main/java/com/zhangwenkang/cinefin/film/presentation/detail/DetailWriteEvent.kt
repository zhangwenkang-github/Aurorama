package com.zhangwenkang.cinefin.film.presentation.detail

import com.zhangwenkang.cinefin.repository.UserDataWriteFailure
import com.zhangwenkang.cinefin.repository.UserDataWriteOutcome

/** W8-T1：详情页用户数据写（收藏 / 已看）结果事件；屏幕层据此弹 Snackbar。 */
sealed interface DetailWriteEvent {
    /** 服务器已确认（本地库同步更新），无需提示。 */
    data object Confirmed : DetailWriteEvent

    /** 网络 / 5xx：本地已保存，联网后自动同步。 */
    data object QueuedForSync : DetailWriteEvent

    /** 权限拒绝（401/403）：不可重试，本地未改动。 */
    data object PermissionDenied : DetailWriteEvent

    /** 其他不可重试失败（含本地库异常），本地未改动。 */
    data object Failed : DetailWriteEvent
}

/** 仓库结果 → 屏幕事件；null（本地库异常 = 写根本没完成）按普通失败呈现。 */
internal fun UserDataWriteOutcome?.toDetailWriteEvent(): DetailWriteEvent =
    when (this) {
        UserDataWriteOutcome.Confirmed -> DetailWriteEvent.Confirmed
        UserDataWriteOutcome.QueuedForSync -> DetailWriteEvent.QueuedForSync
        is UserDataWriteOutcome.Failed ->
            when (failure) {
                is UserDataWriteFailure.PermissionDenied -> DetailWriteEvent.PermissionDenied
                else -> DetailWriteEvent.Failed
            }
        null -> DetailWriteEvent.Failed
    }
