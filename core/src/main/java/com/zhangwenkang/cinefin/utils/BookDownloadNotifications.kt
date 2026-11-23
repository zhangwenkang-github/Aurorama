package com.zhangwenkang.cinefin.utils

import android.content.Context
import com.zhangwenkang.cinefin.repository.BookDownloadNotificationSink
import com.zhangwenkang.cinefin.repository.BookDownloadStatus
import com.zhangwenkang.cinefin.repository.BookDownloadTask
import com.zhangwenkang.cinefin.settings.domain.AppPreferences

/**
 * W9-T7（F5）：书籍整本下载的完成 / 失败通知。
 *
 * 书籍链不在自研引擎内，但通知口径与引擎一致：复用 [DownloadNotifications] 的渠道、图标和文案模板，只把 [BookDownloadTask] 投影成引擎侧
 * [DownloadTask]。
 *
 * 完成通知带上 `mediaKind = BOOK`，点击目的地是 [DownloadNotificationDestination.Completed]：`MainActivity`
 * 按本地可达性直达阅读器，书已删除 / 离线库无记录时回退下载页「已完成」分组。
 */
internal class BookDownloadNotifications(
    context: Context,
    private val appPreferences: AppPreferences,
) : BookDownloadNotificationSink {
    private val notifications = DownloadNotifications(context.applicationContext)

    /** 完成通知与引擎共用「下载完成通知」开关；失败通知引擎侧无开关，这里保持一致。 */
    override fun onCompleted(task: BookDownloadTask) {
        if (!appPreferences.getValue(appPreferences.downloadCompleteNotification)) return
        notifications.notifyCompleted(bookDownloadNotificationTask(task))
    }

    override fun onFailed(task: BookDownloadTask) {
        notifications.notifyFailed(bookDownloadNotificationTask(task))
    }
}

/**
 * 书籍任务 → 引擎通知模型（纯函数）。
 *
 * 书名口径复用 [offlineBookDisplayName]（侧车书名优先 → 「离线书籍 <id 前 8 位>」占位），失败详情用任务里的错误文本， 与下载页书籍行展示同源。
 */
internal fun bookDownloadNotificationTask(task: BookDownloadTask): DownloadTask =
    DownloadTask(
        itemId = task.itemId,
        sourceId = task.canonicalKey,
        name =
            offlineBookDisplayName(
                serverName = null,
                sidecarTitle = task.title,
                itemId = task.itemId,
            ),
        path = task.targetPath.orEmpty(),
        downloadId = null,
        status =
            when (task.status) {
                BookDownloadStatus.PENDING -> DownloadTaskStatus.PENDING
                BookDownloadStatus.RUNNING -> DownloadTaskStatus.RUNNING
                BookDownloadStatus.PAUSED -> DownloadTaskStatus.PAUSED
                BookDownloadStatus.COMPLETED -> DownloadTaskStatus.COMPLETED
                BookDownloadStatus.FAILED -> DownloadTaskStatus.FAILED
            },
        failureReason = bookDownloadFailureReason(task),
        failureDetail = task.error,
        downloadedBytes = task.downloadedBytes,
        totalBytes = task.totalBytes,
        updatedAt = task.updatedAtEpochMillis,
        mediaKind = DownloadMediaKind.BOOK,
    )

/**
 * 书籍失败原因 → 引擎失败类别（决定通知里的原因文案）。
 *
 * 书籍链只落字符串原因：`CANCELLED` / `PROVENANCE_WRITE_FAILED` 可归因，其余 `ERROR` 及清理收尾类不猜具体类别， 按
 * [DownloadFailureReason.UNKNOWN] 展示通用文案，真实错误文本仍由 `failureDetail` 带出。
 */
internal fun bookDownloadFailureReason(task: BookDownloadTask): DownloadFailureReason? =
    when (task.status) {
        BookDownloadStatus.FAILED ->
            when (task.failureReason) {
                "CANCELLED" -> DownloadFailureReason.CANCELLED
                "PROVENANCE_WRITE_FAILED" -> DownloadFailureReason.FILE_ERROR
                else -> DownloadFailureReason.UNKNOWN
            }
        else -> null
    }
