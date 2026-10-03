package com.zhangwenkang.cinefin.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.zhangwenkang.cinefin.utils.Downloader
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import timber.log.Timber

/**
 * W50 自研下载引擎的宿主 worker（长时任务 + 前台服务）。
 *
 * - 开始即 `setForeground`：由 WorkManager 的 SystemForegroundService 托管常驻通知（进度 / 暂停 / 取消）；
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
            setForeground(downloader.foregroundInfo())
            val outcome = downloader.runQueue()
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
