package com.zhangwenkang.cinefin.utils

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.text.format.Formatter
import androidx.core.app.NotificationCompat
import com.zhangwenkang.cinefin.core.R as CoreR

/**
 * W50 下载通知中心。
 *
 * - 前台通知：常驻，显示当前任务进度 + 暂停 / 取消操作（WorkManager `setForeground` 托管）；
 * - 完成 / 失败通知：单任务一条，自动消失，点击打开应用。
 *
 * W52：操作按钮仍只对「当前任务」生效；**多任务**时点击通知本体直接进下载页（不额外做多按钮）。
 */
internal class DownloadNotifications(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    init {
        createChannel()
    }

    fun buildForeground(task: DownloadTask?, activeCount: Int): Notification {
        val title =
            if (task != null && activeCount > 1) {
                context.getString(CoreR.string.download_notification_active_multi, activeCount)
            } else {
                task?.name ?: context.getString(CoreR.string.download_tasks_active)
            }
        val text = task?.notificationText() ?: ""
        val builder =
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(title)
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setContentIntent(openAppIntent(openDownloads = activeCount > 1))
                .setProgress(100, task?.progressPercent() ?: 0, task?.totalBytes == 0L)
        if (task != null) {
            builder
                .addAction(
                    0,
                    context.getString(CoreR.string.download_notification_pause),
                    actionIntent(ACTION_PAUSE, task.sourceId),
                )
                .addAction(
                    0,
                    context.getString(CoreR.string.download_notification_cancel),
                    actionIntent(ACTION_CANCEL, task.sourceId),
                )
        }
        return builder.build()
    }

    fun notifyCompleted(task: DownloadTask) {
        val notification =
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(context.getString(CoreR.string.download_notification_completed))
                .setContentText(task.name)
                .setAutoCancel(true)
                .setContentIntent(openAppIntent())
                .build()
        manager.notify(stateNotificationId(task.sourceId), notification)
    }

    fun notifyFailed(task: DownloadTask) {
        val notification =
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle(context.getString(CoreR.string.download_notification_failed))
                .setContentText(task.name)
                .setAutoCancel(true)
                .setContentIntent(openAppIntent())
                .build()
        manager.notify(stateNotificationId(task.sourceId), notification)
    }

    private fun DownloadTask.notificationText(): String {
        val downloaded = Formatter.formatFileSize(context, downloadedBytes)
        return when {
            totalBytes > 0L -> {
                val total = Formatter.formatFileSize(context, totalBytes)
                val eta = etaSeconds?.let { " · ${it.asEtaText()}" }.orEmpty()
                "$downloaded / $total · ${progressPercent()}%$eta"
            }
            else -> downloaded
        }
    }

    private fun DownloadTask.progressPercent(): Int =
        if (totalBytes > 0L) {
            (downloadedBytes * 100L / totalBytes).coerceIn(0L, 100L).toInt()
        } else {
            0
        }

    private fun Long.asEtaText(): String {
        val seconds = coerceAtLeast(0L)
        val hours = seconds / 3600
        val minutes = seconds % 3600 / 60
        val rest = seconds % 60
        return when {
            hours > 0 -> "%d:%02d:%02d".format(hours, minutes, rest)
            else -> "%d:%02d".format(minutes, rest)
        }
    }

    private fun openAppIntent(openDownloads: Boolean = false): PendingIntent {
        val launch =
            context.packageManager.getLaunchIntentForPackage(context.packageName)
                ?: Intent(Intent.ACTION_MAIN).setPackage(context.packageName)
        // W52：单实例 + SINGLE_TOP，进程活着时把「进下载页」的请求交给 onNewIntent（冷启动走 onCreate）。
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (openDownloads) launch.putExtra(EXTRA_OPEN_DOWNLOADS, true)
        return PendingIntent.getActivity(
            context,
            REQUEST_OPEN_APP,
            launch,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun actionIntent(action: String, sourceId: String): PendingIntent {
        val intent =
            Intent(context, DownloadActionReceiver::class.java)
                .setAction(action)
                .putExtra(DownloadActionReceiver.EXTRA_SOURCE_ID, sourceId)
        return PendingIntent.getBroadcast(
            context,
            sourceId.hashCode() xor action.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun stateNotificationId(sourceId: String): Int =
        STATE_NOTIFICATION_BASE + (sourceId.hashCode() and 0xfff)

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel =
            NotificationChannel(
                CHANNEL_ID,
                context.getString(CoreR.string.download_notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            )
        channel.setShowBadge(false)
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID = "downloads"

        /** 前台服务通知 id（WorkManager SystemForegroundService 与本实现共用）。 */
        const val FOREGROUND_NOTIFICATION_ID = 4201

        const val ACTION_PAUSE = "com.zhangwenkang.cinefin.action.DOWNLOAD_PAUSE"
        const val ACTION_CANCEL = "com.zhangwenkang.cinefin.action.DOWNLOAD_CANCEL"

        private const val STATE_NOTIFICATION_BASE = 4500
        private const val REQUEST_OPEN_APP = 100
    }
}

/** W52：多任务前台通知点击时，把「打开下载页」意图经启动 Intent 传给 [com.zhangwenkang.cinefin.MainActivity]。 */
const val EXTRA_OPEN_DOWNLOADS = "com.zhangwenkang.cinefin.extra.OPEN_DOWNLOADS"
