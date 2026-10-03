package com.zhangwenkang.cinefin.film.presentation.detail

import java.util.UUID

/** W51 详情页下载状态快照：已下载 / 活动队列 id 集合 + 入队进行中标记。 */
data class DetailDownloadSnapshot(
    val downloadedIds: Set<UUID> = emptySet(),
    val queuedIds: Set<UUID> = emptySet(),
    val inFlight: Boolean = false,
)
