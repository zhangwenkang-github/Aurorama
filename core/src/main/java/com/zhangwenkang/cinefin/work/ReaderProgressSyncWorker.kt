package com.zhangwenkang.cinefin.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.zhangwenkang.cinefin.repository.ReaderRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import timber.log.Timber

/**
 * 阅读进度离线队列回传（EB-9）。
 *
 * 阅读页在离线时把进度先落到本地 `progress.json`（`pendingSync = true`），本 Worker 在联网约束下 调用
 * `ReaderRepository.flushPendingProgress()` 回传。没有待同步记录时不产生任何网络请求。
 */
@HiltWorker
class ReaderProgressSyncWorker
@AssistedInject
constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val readerRepository: ReaderRepository,
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result = runCatching {
        readerRepository.flushPendingProgress()
    }
        .fold(
            onSuccess = { synced -> Result.success(workDataOf(SYNCED_COUNT to synced)) },
            onFailure = {
                Timber.w(it, "阅读进度离线队列回传失败")
                if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
            },
        )

    companion object {
        const val SYNCED_COUNT = "readerProgressSyncedCount"
        private const val MAX_ATTEMPTS = 3
    }
}
