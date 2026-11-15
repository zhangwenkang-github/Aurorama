package com.zhangwenkang.cinefin.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.zhangwenkang.cinefin.utils.DownloadEngineForegroundGate
import com.zhangwenkang.cinefin.utils.DownloadQueueOutcome
import com.zhangwenkang.cinefin.utils.Downloader
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import timber.log.Timber

/**
 * W50 自研下载引擎的宿主 worker（长时任务 + 前台服务）。
 *
 * - 仅在本轮存在可立即领取的任务时 `setForeground`，避免空队列重建 ongoing 通知；
 * - 运行 [Downloader.runQueue] 直到没有可立即执行的任务；
 * - 退避 / 网络等待由引擎按任务级 `nextRetryAt` + `CONNECTED` 约束追加下一次 worker，不使用 WM 自身 retry
 *   （避免与任务级指数退避叠加）；仅引擎异常时用 `Result.retry()` 兜底。
 */
@HiltWorker
class DownloadEngineWorker
@AssistedInject
constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val downloader: Downloader,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result =
        try {
            val outcome =
                runDownloadEngineWork(downloader) { foregroundInfo ->
                    setForeground(foregroundInfo)
                }
            Timber.d(
                "下载引擎运行结束：执行 %d，待处理 %b（重试调度 %b）",
                outcome.ranTasks,
                outcome.hasPendingTasks,
                outcome.shouldRetry,
            )
            Result.success()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            Timber.w(error, "下载引擎运行异常，交由 WorkManager 退避重试")
            Result.retry()
        }
}

internal suspend fun runDownloadEngineWork(
    downloader: Downloader,
    setForeground: suspend (ForegroundInfo) -> Unit,
): DownloadQueueOutcome {
    val foregroundInfo =
        (downloader as? DownloadEngineForegroundGate)?.foregroundInfoForRunnableWork()
    if (foregroundInfo != null) {
        setForeground(foregroundInfo)
    }
    return downloader.runQueue()
}
