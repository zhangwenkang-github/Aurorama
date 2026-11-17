package com.zhangwenkang.cinefin.utils

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * W50 下载通知操作接收器：前台通知上的 canonical pause / resume / delete 按钮。
 *
 * 下载引擎不再使用系统 DownloadManager，通知操作直接调用自研引擎的按 id 任务控制。
 */
@AndroidEntryPoint
class DownloadActionReceiver : BroadcastReceiver() {

    @Inject lateinit var downloader: Downloader

    override fun onReceive(context: Context, intent: Intent) {
        val sourceIds =
            intent.getStringArrayListExtra(EXTRA_SOURCE_IDS)?.filter(String::isNotBlank)?.distinct()
                ?: listOfNotNull(intent.getStringExtra(EXTRA_SOURCE_ID))
        if (sourceIds.isEmpty()) return
        val action = intent.action ?: return
        if (
            action != DownloadNotifications.ACTION_PAUSE &&
                action != DownloadNotifications.ACTION_RESUME &&
                action != DownloadNotifications.ACTION_DELETE &&
                action != DownloadNotifications.ACTION_CANCEL
        ) {
            return
        }
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val outcomes =
                    executeDownloadNotificationAction(
                        action = action,
                        sourceIds = sourceIds,
                        pause = downloader::pauseTaskById,
                        resume = downloader::resumeTaskById,
                        delete = downloader::deleteTaskById,
                        cancel = downloader::cancelTaskById,
                    )
                outcomes.forEachIndexed { index, outcome ->
                    outcome.exceptionOrNull()?.let { error ->
                        Timber.w(
                            error,
                            "下载通知单项操作失败 action=$action index=$index count=${sourceIds.size}",
                        )
                    }
                }
            } catch (e: Exception) {
                Timber.w(e, "下载通知操作失败 action=$action count=${sourceIds.size}")
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val EXTRA_SOURCE_ID = "com.zhangwenkang.cinefin.extra.DOWNLOAD_SOURCE_ID"
        const val EXTRA_SOURCE_IDS = "com.zhangwenkang.cinefin.extra.DOWNLOAD_SOURCE_IDS"
    }
}

internal suspend fun executeDownloadNotificationAction(
    action: String,
    sourceIds: List<String>,
    pause: suspend (String) -> Boolean,
    resume: suspend (String) -> Boolean,
    delete: suspend (String) -> Boolean,
    cancel: suspend (String) -> Boolean,
): List<Result<Boolean>> = sourceIds.map { sourceId ->
    try {
        Result.success(
            when (action) {
                DownloadNotifications.ACTION_PAUSE -> pause(sourceId)
                DownloadNotifications.ACTION_RESUME -> resume(sourceId)
                DownloadNotifications.ACTION_DELETE -> delete(sourceId)
                DownloadNotifications.ACTION_CANCEL -> cancel(sourceId)
                else -> error("unsupported download notification action: $action")
            }
        )
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Exception) {
        Result.failure(error)
    }
}
