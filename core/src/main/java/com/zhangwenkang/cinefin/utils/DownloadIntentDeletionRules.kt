package com.zhangwenkang.cinefin.utils

import kotlinx.coroutines.CancellationException

data class DownloadIntentDeletionResult(
    val confirmed: Boolean,
    val failure: Throwable?,
)

/**
 * intent 删除的全有或全报告协调器。
 *
 * source 清理必须逐项尝试；任一未确认或抛错都保留 intent，并把首个真实错误交还调用链。取消继续向协程传播。
 */
object DownloadIntentDeletionRules {
    suspend fun deleteAllOrReport(
        sourceIds: List<String>,
        deleteSource: suspend (String) -> Boolean,
        deleteIntent: suspend () -> Boolean,
    ): DownloadIntentDeletionResult {
        var firstFailure: Throwable? = null
        sourceIds.distinct().forEach { sourceId ->
            try {
                if (!deleteSource(sourceId) && firstFailure == null) {
                    firstFailure = IllegalStateException("下载来源未确认清理：$sourceId")
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                if (firstFailure == null) firstFailure = error
            }
        }
        if (firstFailure != null) {
            return DownloadIntentDeletionResult(confirmed = false, failure = firstFailure)
        }

        val intentDeleted =
            try {
                deleteIntent()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                return DownloadIntentDeletionResult(confirmed = false, failure = error)
            }
        return if (intentDeleted) {
            DownloadIntentDeletionResult(confirmed = true, failure = null)
        } else {
            DownloadIntentDeletionResult(
                confirmed = false,
                failure = IllegalStateException("下载意图未确认清理"),
            )
        }
    }
}
