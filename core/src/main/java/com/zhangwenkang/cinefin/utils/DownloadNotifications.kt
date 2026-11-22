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
import java.net.URI
import java.util.UUID

/**
 * 通知点击目的地。
 *
 * W7-T5（需求 27）：[Completed] 把「条目 + 媒体类型」交给 [com.zhangwenkang.cinefin.MainActivity]， 由它按本地可达性直达播放 /
 * 阅读，或回退下载页「已完成」分组。
 */
internal sealed interface DownloadNotificationDestination {
    data object Home : DownloadNotificationDestination

    data object Active : DownloadNotificationDestination

    data object Failed : DownloadNotificationDestination

    data class Completed(
        val itemId: UUID,
        val mediaKind: DownloadMediaKind,
    ) : DownloadNotificationDestination
}

internal data class DownloadCompletionDeepLink(
    val itemId: String,
    val mediaKind: DownloadMediaKind,
)

internal data class DownloadNotificationTarget(
    val requestCode: Int,
    val openDownloads: Boolean,
    val tab: String?,
    /** 非空 = 完成通知直达播放 / 阅读（不打开下载页）。 */
    val completion: DownloadCompletionDeepLink? = null,
)

internal data class DownloadFailureNotificationContent(
    val text: String,
    val expandedText: String,
)

internal data class DownloadNotificationAggregate(
    val task: DownloadTask?,
    val activeCount: Int,
    val samplingEpochMs: Long,
    val identityKeys: List<String>,
    val projection: CanonicalActiveTaskProjection,
    val actionSourceIds: Map<CanonicalDownloadAction, List<String>>,
)

/**
 * Projects one canonical active-task snapshot into the single foreground notification payload.
 *
 * This consumer never filters, sorts or deduplicates task rows. Those decisions already happened
 * once in [CanonicalActiveTaskSnapshotOwner].
 */
internal fun aggregateDownloadNotification(
    snapshot: CanonicalActiveTaskSnapshot
): DownloadNotificationAggregate {
    val canonicalTasks = snapshot.activeTasks
    val actionSourceIds =
        snapshot.projection.actionEligibleIdentities.mapValues { (_, identityKeys) ->
            val sourceIdByIdentity =
                snapshot.tasks.associate { leaf ->
                    leaf.identity.canonicalKey to leaf.task.sourceId
                }
            identityKeys.mapNotNull(sourceIdByIdentity::get)
        }
    if (canonicalTasks.isEmpty()) {
        return DownloadNotificationAggregate(
            task = null,
            activeCount = 0,
            samplingEpochMs = snapshot.samplingEpochMs,
            identityKeys = snapshot.sortedIdentityKeys,
            projection = snapshot.projection,
            actionSourceIds = emptyMap(),
        )
    }

    val totalBytes = snapshot.totalBytes
    val downloadedBytes = snapshot.downloadedBytes
    val speedBytesPerSecond = canonicalTasks.sumOf { task ->
        task.speedBytesPerSecond.coerceAtLeast(0L)
    }
    val remainingBytes = (totalBytes - downloadedBytes).coerceAtLeast(0L)
    val etaSeconds =
        when {
            totalBytes <= 0L -> null
            remainingBytes == 0L -> 0L
            speedBytesPerSecond <= 0L -> null
            else ->
                remainingBytes / speedBytesPerSecond +
                    if (remainingBytes % speedBytesPerSecond > 0L) 1L else 0L
        }
    val representative =
        canonicalTasks
            .first()
            .copy(
                downloadedBytes = downloadedBytes,
                totalBytes = totalBytes,
                updatedAt = canonicalTasks.maxOf(DownloadTask::updatedAt),
                speedBytesPerSecond = speedBytesPerSecond,
                etaSeconds = etaSeconds,
            )
    return DownloadNotificationAggregate(
        task = representative,
        activeCount = canonicalTasks.size,
        samplingEpochMs = snapshot.samplingEpochMs,
        identityKeys = snapshot.sortedIdentityKeys,
        projection = snapshot.projection,
        actionSourceIds = actionSourceIds,
    )
}

internal fun downloadNotificationPercent(downloadedBytes: Long, totalBytes: Long): Int =
    if (totalBytes > 0L) {
        (downloadedBytes.coerceAtLeast(0L) * 100L / totalBytes).coerceIn(0L, 100L).toInt()
    } else {
        0
    }

internal fun downloadNotificationTarget(
    destination: DownloadNotificationDestination
): DownloadNotificationTarget =
    when (destination) {
        DownloadNotificationDestination.Home ->
            DownloadNotificationTarget(
                requestCode = REQUEST_OPEN_HOME,
                openDownloads = false,
                tab = null,
            )
        DownloadNotificationDestination.Active ->
            DownloadNotificationTarget(
                requestCode = REQUEST_OPEN_ACTIVE_DOWNLOADS,
                openDownloads = true,
                tab = DOWNLOADS_TAB_ACTIVE,
            )
        DownloadNotificationDestination.Failed ->
            DownloadNotificationTarget(
                requestCode = REQUEST_OPEN_FAILED_DOWNLOADS,
                openDownloads = true,
                tab = DOWNLOADS_TAB_FAILED,
            )
        is DownloadNotificationDestination.Completed ->
            DownloadNotificationTarget(
                requestCode = REQUEST_OPEN_COMPLETED + (destination.itemId.hashCode() and 0xfff),
                openDownloads = false,
                tab = null,
                completion =
                    DownloadCompletionDeepLink(
                        itemId = destination.itemId.toString(),
                        mediaKind = destination.mediaKind,
                    ),
            )
    }

/** 完成通知 extra 的媒体类型解析；未知 / 缺失返回 null（调用方回退下载页）。 */
fun parseDownloadCompletionMediaKind(raw: String?): DownloadMediaKind? =
    DownloadMediaKind.entries.firstOrNull { it.name == raw }

internal fun failureNotificationContent(
    taskName: String,
    localizedReason: String,
    detail: String?,
): DownloadFailureNotificationContent {
    val safeName = taskName.ifBlank { localizedReason }
    val safeDetail = sanitizeDownloadFailureDetail(detail)
    return DownloadFailureNotificationContent(
        text = "$safeName · $localizedReason",
        expandedText =
            listOf(safeName, localizedReason, safeDetail)
                .filter(String::isNotBlank)
                .joinToString("\n"),
    )
}

internal fun sanitizeDownloadFailureDetail(rawDetail: String?): String {
    val singleLine = collapseToSingleLine(rawDetail.orEmpty())
    val withoutUrlSecrets =
        httpUrlPattern.replace(singleLine) { match -> sanitizeHttpUrl(match.value) }
    val collapsed = collapseToSingleLine(withoutUrlSecrets)
    return if (collapsed.length <= MAX_FAILURE_DETAIL_LENGTH) {
        collapsed
    } else {
        collapsed.take(MAX_FAILURE_DETAIL_LENGTH - 1).trimEnd() + "…"
    }
}

private fun collapseToSingleLine(value: String): String =
    buildString(value.length) {
        var pendingSeparator = false
        for (character in value) {
            val separator =
                character.isISOControl() ||
                    character.isWhitespace() ||
                    character == '\u2028' ||
                    character == '\u2029'
            if (separator) {
                pendingSeparator = isNotEmpty()
            } else {
                if (pendingSeparator) append(' ')
                append(character)
                pendingSeparator = false
            }
        }
    }

private fun sanitizeHttpUrl(rawUrl: String): String {
    val parsed = runCatching { URI(rawUrl) }.getOrNull()
    val scheme = parsed?.scheme?.lowercase() ?: "https"
    val host = parsed?.host?.takeIf(String::isNotBlank) ?: return "$scheme://[redacted]"
    val port = parsed.port.takeIf { it >= 0 }?.let { ":$it" }.orEmpty()
    val path = parsed.rawPath.orEmpty()
    return "$scheme://$host$port$path"
}

internal fun downloadFailureReasonLabelRes(reason: DownloadFailureReason?): Int =
    reason.localizedLabelRes()

private fun DownloadFailureReason?.localizedLabelRes(): Int =
    when (this) {
        DownloadFailureReason.STORAGE_INSUFFICIENT -> CoreR.string.download_failure_storage
        DownloadFailureReason.NETWORK_UNAVAILABLE -> CoreR.string.download_failure_network
        DownloadFailureReason.SERVER_ERROR -> CoreR.string.download_failure_server
        DownloadFailureReason.AUTHENTICATION -> CoreR.string.download_failure_authentication
        DownloadFailureReason.CANNOT_RESUME -> CoreR.string.download_failure_cannot_resume
        DownloadFailureReason.FILE_ERROR -> CoreR.string.download_failure_file
        DownloadFailureReason.CANCELLED -> CoreR.string.download_failure_cancelled
        DownloadFailureReason.UNKNOWN,
        null -> CoreR.string.download_failure_unknown
    }

/**
 * W50 下载通知中心。
 *
 * - 前台通知：常驻，显示当前任务进度 + canonical pause / resume / delete 操作 （WorkManager `setForeground` 托管）；
 * - 完成 / 失败通知：单任务一条，自动消失，点击打开应用。
 *
 * W52：多任务时点击通知本体直接进下载页；操作按钮的可用性与 eligible source 由同一 canonical active-task projection 发布。
 */
internal interface DownloadNotificationGateway {
    fun buildForeground(aggregate: DownloadNotificationAggregate): Notification

    fun notifyForeground(aggregate: DownloadNotificationAggregate)

    fun notifyCompleted(task: DownloadTask)

    fun notifyFailed(task: DownloadTask)
}

internal class DownloadNotifications(private val context: Context) : DownloadNotificationGateway {
    private val manager = context.getSystemService(NotificationManager::class.java)

    init {
        createChannel()
    }

    override fun buildForeground(aggregate: DownloadNotificationAggregate): Notification {
        val task = aggregate.task
        val activeCount = aggregate.activeCount
        val title =
            if (activeCount > 1) {
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
                .setContentIntent(
                    openAppIntent(
                        if (activeCount > 1) {
                            DownloadNotificationDestination.Active
                        } else {
                            DownloadNotificationDestination.Home
                        }
                    )
                )
                .setProgress(
                    100,
                    downloadNotificationPercent(
                        downloadedBytes = task?.downloadedBytes ?: 0L,
                        totalBytes = task?.totalBytes ?: 0L,
                    ),
                    task?.totalBytes == 0L,
                )
        CanonicalDownloadAction.entries.forEach { capability ->
            val sourceIds = aggregate.actionSourceIds[capability].orEmpty()
            if (sourceIds.isEmpty()) return@forEach
            val action =
                when (capability) {
                    CanonicalDownloadAction.PAUSE -> ACTION_PAUSE
                    CanonicalDownloadAction.RESUME -> ACTION_RESUME
                    CanonicalDownloadAction.DELETE -> ACTION_DELETE
                }
            val label =
                when (capability) {
                    CanonicalDownloadAction.PAUSE -> CoreR.string.download_notification_pause
                    CanonicalDownloadAction.RESUME -> CoreR.string.download_action_resume
                    CanonicalDownloadAction.DELETE -> CoreR.string.download_action_delete
                }
            builder.addAction(0, context.getString(label), actionIntent(action, sourceIds))
        }
        return builder.build()
    }

    override fun notifyForeground(aggregate: DownloadNotificationAggregate) {
        manager.notify(FOREGROUND_NOTIFICATION_ID, buildForeground(aggregate))
    }

    override fun notifyCompleted(task: DownloadTask) {
        val notification =
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(context.getString(CoreR.string.download_notification_completed))
                .setContentText(task.name)
                .setAutoCancel(true)
                // W7-T5（需求 27）：完成通知点击直达对应播放 / 阅读界面。
                .setContentIntent(
                    openAppIntent(
                        DownloadNotificationDestination.Completed(
                            itemId = task.itemId,
                            mediaKind = task.mediaKind,
                        )
                    )
                )
                .build()
        manager.notify(stateNotificationId(task.sourceId), notification)
    }

    override fun notifyFailed(task: DownloadTask) {
        val content =
            failureNotificationContent(
                taskName = task.name,
                localizedReason = context.getString(task.failureReason.localizedLabelRes()),
                detail = task.failureDetail,
            )
        val notification =
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle(context.getString(CoreR.string.download_notification_failed))
                .setContentText(content.text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(content.expandedText))
                .setAutoCancel(true)
                .setContentIntent(openAppIntent(DownloadNotificationDestination.Failed))
                .build()
        manager.notify(stateNotificationId(task.sourceId), notification)
    }

    private fun DownloadTask.notificationText(): String {
        val downloaded = Formatter.formatFileSize(context, downloadedBytes)
        return when {
            totalBytes > 0L -> {
                val total = Formatter.formatFileSize(context, totalBytes)
                val eta = etaSeconds?.let { " · ${it.asEtaText()}" }.orEmpty()
                "$downloaded / $total · ${downloadNotificationPercent(downloadedBytes, totalBytes)}%$eta"
            }
            else -> downloaded
        }
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

    private fun openAppIntent(destination: DownloadNotificationDestination): PendingIntent {
        val target = downloadNotificationTarget(destination)
        val launch =
            context.packageManager.getLaunchIntentForPackage(context.packageName)
                ?: Intent(Intent.ACTION_MAIN).setPackage(context.packageName)
        // CLEAR_TOP 覆盖 playback Activity 仍在 MainActivity 之上的栈形。
        launch.addFlags(downloadNotificationLaunchFlags)
        if (target.openDownloads) {
            launch.putExtra(EXTRA_OPEN_DOWNLOADS, true)
            target.tab?.let { launch.putExtra(EXTRA_DOWNLOADS_TAB, it) }
        }
        target.completion?.let { completion ->
            launch.putExtra(EXTRA_DOWNLOAD_COMPLETION_ITEM_ID, completion.itemId)
            launch.putExtra(EXTRA_DOWNLOAD_COMPLETION_MEDIA_KIND, completion.mediaKind.name)
        }
        return PendingIntent.getActivity(
            context,
            target.requestCode,
            launch,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun actionIntent(action: String, sourceIds: Collection<String>): PendingIntent {
        val distinctSourceIds = sourceIds.distinct().sorted()
        val intent =
            Intent(context, DownloadActionReceiver::class.java)
                .setAction(action)
                .putStringArrayListExtra(
                    DownloadActionReceiver.EXTRA_SOURCE_IDS,
                    ArrayList(distinctSourceIds),
                )
                .putExtra(
                    DownloadActionReceiver.EXTRA_SOURCE_ID,
                    distinctSourceIds.firstOrNull(),
                )
        return PendingIntent.getBroadcast(
            context,
            distinctSourceIds.hashCode() xor action.hashCode(),
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
        const val ACTION_RESUME = "com.zhangwenkang.cinefin.action.DOWNLOAD_RESUME"
        const val ACTION_DELETE = "com.zhangwenkang.cinefin.action.DOWNLOAD_DELETE"
        const val ACTION_CANCEL = "com.zhangwenkang.cinefin.action.DOWNLOAD_CANCEL"

        private const val STATE_NOTIFICATION_BASE = 4500
    }
}

/** W52：多任务前台通知点击时，把「打开下载页」意图经启动 Intent 传给 [com.zhangwenkang.cinefin.MainActivity]。 */
const val EXTRA_OPEN_DOWNLOADS = "com.zhangwenkang.cinefin.extra.OPEN_DOWNLOADS"
const val EXTRA_DOWNLOADS_TAB = "com.zhangwenkang.cinefin.extra.DOWNLOADS_TAB"
const val DOWNLOADS_TAB_ACTIVE = "ACTIVE"
const val DOWNLOADS_TAB_FAILED = "FAILED"

/** W7-T5：完成通知直达播放 / 阅读的条目身份；由 MainActivity 消费并分派。 */
const val EXTRA_DOWNLOAD_COMPLETION_ITEM_ID =
    "com.zhangwenkang.cinefin.extra.DOWNLOAD_COMPLETION_ITEM_ID"
const val EXTRA_DOWNLOAD_COMPLETION_MEDIA_KIND =
    "com.zhangwenkang.cinefin.extra.DOWNLOAD_COMPLETION_MEDIA_KIND"

/** W7-T5：本地条目不可达时回退的下载页分组。 */
const val DOWNLOADS_TAB_COMPLETED = "COMPLETED"

private const val REQUEST_OPEN_HOME = 100
private const val REQUEST_OPEN_ACTIVE_DOWNLOADS = 101
private const val REQUEST_OPEN_FAILED_DOWNLOADS = 102

/** 完成通知按条目 hash 分离请求码，避免多条完成通知互相覆盖 PendingIntent。 */
private const val REQUEST_OPEN_COMPLETED = 10_000
private const val MAX_FAILURE_DETAIL_LENGTH = 180
private val httpUrlPattern = Regex("""https?://[^\s]+""", RegexOption.IGNORE_CASE)
internal val downloadNotificationLaunchFlags =
    Intent.FLAG_ACTIVITY_NEW_TASK or
        Intent.FLAG_ACTIVITY_SINGLE_TOP or
        Intent.FLAG_ACTIVITY_CLEAR_TOP
