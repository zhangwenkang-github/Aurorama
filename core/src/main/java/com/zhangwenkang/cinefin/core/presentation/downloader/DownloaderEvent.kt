package com.zhangwenkang.cinefin.core.presentation.downloader

sealed interface DownloaderEvent {
    /** W60b：已成功入队（旧 DownloadManager 语义的下载引擎任务已创建），详情页据此弹「已加入下载队列」。 */
    data object Queued : DownloaderEvent

    data object Successful : DownloaderEvent

    data object Deleted : DownloaderEvent
}
