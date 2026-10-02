package com.zhangwenkang.cinefin.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.zhangwenkang.cinefin.utils.Downloader
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import timber.log.Timber

/**
 * W32 网络恢复重试：带 `NetworkType.CONNECTED` 约束的唯一任务。
 *
 * 页面加载或下载失败广播时入队；断网时等待网络恢复后执行，只自动重试「网络 / 服务器」类失败任务，每个任务每个进程一次。
 */
@HiltWorker
class DownloadRetryWorker
@AssistedInject
constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val downloader: Downloader,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result =
        try {
            val retried = downloader.retryNetworkFailures()
            if (retried > 0) {
                Timber.i("网络恢复自动重试：%d 个下载任务", retried)
            }
            Result.success()
        } catch (e: Exception) {
            Timber.w(e, "网络恢复重试下载任务失败")
            Result.retry()
        }
}
