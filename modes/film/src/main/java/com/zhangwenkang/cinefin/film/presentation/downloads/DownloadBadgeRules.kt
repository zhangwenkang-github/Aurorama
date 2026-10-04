package com.zhangwenkang.cinefin.film.presentation.downloads

import java.util.UUID

/**
 * W63 侧栏 / 抽屉 / 底栏「下载」角标计数（纯函数，单测覆盖）。
 *
 * 口径（用户 2026-10-04 全检缺陷）：
 * - 只算**活动队列**（排队 / 下载中 / 暂停），同一 `itemId` 无论命中几条来源都只计 **1**；
 * - 已落盘完成（`downloadedItemIds`）的条目即使残留在活动快照里也**不计**——避免「只下 1 集却显示 2」。
 */
object DownloadBadgeRules {

    fun activeBadgeCount(
        activeItemIds: Set<UUID>,
        downloadedItemIds: Set<UUID>,
    ): Int = activeItemIds.asSequence().filterNot { it in downloadedItemIds }.distinct().count()
}
