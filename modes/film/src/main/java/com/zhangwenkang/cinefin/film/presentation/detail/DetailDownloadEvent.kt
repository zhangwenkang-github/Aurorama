package com.zhangwenkang.cinefin.film.presentation.detail

import com.zhangwenkang.cinefin.models.UiText

/** W51 详情页下载结果事件：屏幕层据此弹 Snackbar（三态 / 批量 / 失败）。 */
sealed interface DetailDownloadEvent {
    data object AddedToQueue : DetailDownloadEvent

    data object AlreadyQueued : DetailDownloadEvent

    data object AlreadyDownloaded : DetailDownloadEvent

    data class BatchAdded(val added: Int) : DetailDownloadEvent

    /** 批量没有可加入的集：全部已下载 / 全部在队列（或没有可下载剧集）。 */
    data class BatchSkipped(val downloaded: Int, val queued: Int) : DetailDownloadEvent

    data class Failed(val reason: UiText?) : DetailDownloadEvent
}
