package com.zhangwenkang.cinefin.music.presentation

import com.zhangwenkang.cinefin.repository.UserDataWriteOutcome

/** W9-T4：收藏写给用户看的「已排队」文案，与影视详情页 snackbar（`write_saved_queued`）同一口径。 */
internal const val FAVORITE_QUEUED_NOTICE: String = "已保存，联网后自动同步"

/**
 * W9-T4：音乐收藏写结果 → 非阻塞提示。
 *
 * 只有 [UserDataWriteOutcome.QueuedForSync]（网络 / 5xx 已落本地并排队）需要提示；[UserDataWriteOutcome.Confirmed]
 * 静默成功，`Failed` / null 走既有错误弹窗（按可重试性区分文案），不在这里重复。
 */
internal fun favoriteWriteNotice(outcome: UserDataWriteOutcome?): String? =
    when (outcome) {
        UserDataWriteOutcome.QueuedForSync -> FAVORITE_QUEUED_NOTICE
        UserDataWriteOutcome.Confirmed,
        is UserDataWriteOutcome.Failed,
        null -> null
    }
