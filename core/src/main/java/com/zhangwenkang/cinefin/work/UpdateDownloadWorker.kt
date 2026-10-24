package com.zhangwenkang.cinefin.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.zhangwenkang.cinefin.update.UpdateDownloadResult
import com.zhangwenkang.cinefin.update.UpdateRepository
import com.zhangwenkang.cinefin.update.UpdateTerminalResult
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import timber.log.Timber

/**
 * 更新包下载 Worker。前台服务从开始覆盖到 B2 downloader 返回；offset / validator 始终由 downloader 从 私有状态与 `.part`
 * 实际长度恢复，本 Worker 的输入和输出不承载传输细节。
 */
@HiltWorker
class UpdateDownloadWorker
@AssistedInject
constructor(
    @Assisted private val appContext: Context,
    @Assisted params: WorkerParameters,
    private val repository: UpdateRepository,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val updateId = UpdateWorkData.parseUpdateId(inputData)
        if (updateId == null) {
            return Result.failure(UpdateWorkData.resultOutput(UpdateTerminalResult.STATE_ERROR))
        }

        return try {
            setForeground(foregroundInfo())
            when (val downloaded = repository.download(updateId)) {
                is UpdateDownloadResult.ReadyForVerification -> {
                    val outcome = UpdateResultMapping.forDownload(downloaded)
                    Result.success(terminalResultData(outcome, updateId))
                }
                is UpdateDownloadResult.Failed -> {
                    val outcome = UpdateResultMapping.forDownload(downloaded)
                    Result.failure(terminalResultData(outcome, updateId))
                }
            }
        } catch (cancellation: CancellationException) {
            // WorkManager cancellation must preserve B2's `.part`; do not mark it terminal failure.
            throw cancellation
        } catch (error: Exception) {
            Timber.w(error, "更新包下载异常")
            Result.failure(UpdateWorkData.resultOutput(UpdateTerminalResult.STATE_ERROR, updateId))
        }
    }

    private fun foregroundInfo(): ForegroundInfo {
        ensureNotificationChannel()
        val notification =
            NotificationCompat.Builder(appContext, UPDATE_NOTIFICATION_CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("下载更新")
                .setContentText("正在下载更新包")
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                UPDATE_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(UPDATE_NOTIFICATION_ID, notification)
        }
    }

    private fun ensureNotificationChannel() {
        val manager = appContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                UPDATE_NOTIFICATION_CHANNEL,
                "更新下载",
                NotificationManager.IMPORTANCE_LOW,
            )
        )
    }

    private companion object {
        const val UPDATE_NOTIFICATION_CHANNEL = "update_downloads"
        const val UPDATE_NOTIFICATION_ID = 0x8903
    }
}
