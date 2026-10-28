package com.zhangwenkang.cinefin.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import com.zhangwenkang.cinefin.utils.DownloadIntentQueueOutcome
import com.zhangwenkang.cinefin.utils.Downloader
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import timber.log.Timber

/**
 * 下载意图解析 worker。
 *
 * 这是无前台通知的一次有界反馈轮次；`Result.success()` 只表示本轮解析已结束，绝不代表下载完成。
 */
@HiltWorker
class DownloadIntentWorker
@AssistedInject
constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val downloader: Downloader,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): ListenableWorker.Result =
        runDownloadIntentWorkerAttempt(downloader::runIntentQueue)
}

internal suspend fun runDownloadIntentWorkerAttempt(
    runIntentQueue: suspend () -> DownloadIntentQueueOutcome
): ListenableWorker.Result =
    try {
        val outcome = runIntentQueue()
        Timber.d(
            "下载意图解析轮次结束：处理 %d，仍有后续 %b",
            outcome.processedIntents,
            outcome.hasPendingIntents,
        )
        ListenableWorker.Result.success()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Exception) {
        Timber.w(error, "下载意图解析异常，交由 WorkManager 退避重试")
        ListenableWorker.Result.retry()
    }
