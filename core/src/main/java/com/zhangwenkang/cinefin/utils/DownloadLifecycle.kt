package com.zhangwenkang.cinefin.utils

import java.io.File

/** 一次 worker claim 的身份；generation 用于拒绝该 claim 之后的迟到回调。 */
data class DownloadLifecycleClaim(val sourceId: String, val generation: Long)

data class DownloadLifecycleState(
    val status: DownloadTaskStatus,
    val generation: Long,
)

enum class DownloadLifecycleTransition {
    PROGRESS,
    FAILURE,
    COMPLETION,
    CANCELLATION,
}

enum class DownloadResumeDecision {
    RESUME,
    REUSE,
    REJECT,
}

/** 下载任务生命周期纯规则：所有持久写回都必须先过同一代次检查。 */
object DownloadLifecycleRules {
    fun claim(sourceId: String, runningGeneration: Long): DownloadLifecycleClaim =
        DownloadLifecycleClaim(sourceId = sourceId, generation = runningGeneration)

    fun beginDeletion(state: DownloadLifecycleState): DownloadLifecycleState =
        state.copy(
            status = DownloadTaskStatus.DELETING,
            generation = nextGeneration(state.generation),
        )

    fun beginCancellation(state: DownloadLifecycleState): DownloadLifecycleState =
        state.copy(
            status = DownloadTaskStatus.FAILED,
            generation = nextGeneration(state.generation),
        )

    fun beginPause(state: DownloadLifecycleState): DownloadLifecycleState =
        state.copy(
            status = DownloadTaskStatus.PAUSED,
            generation = nextGeneration(state.generation),
        )

    fun beginRetry(state: DownloadLifecycleState): DownloadLifecycleState =
        state.copy(
            status = DownloadTaskStatus.PENDING,
            generation = nextGeneration(state.generation),
        )

    fun beginResume(state: DownloadLifecycleState): DownloadLifecycleState? =
        if (state.status == DownloadTaskStatus.PAUSED) {
            state.copy(
                status = DownloadTaskStatus.PENDING,
                generation = nextGeneration(state.generation),
            )
        } else {
            null
        }

    fun resumeDecision(status: DownloadTaskStatus): DownloadResumeDecision =
        when (status) {
            DownloadTaskStatus.PAUSED -> DownloadResumeDecision.RESUME
            DownloadTaskStatus.PENDING,
            DownloadTaskStatus.RUNNING -> DownloadResumeDecision.REUSE
            else -> DownloadResumeDecision.REJECT
        }

    fun accepts(
        claim: DownloadLifecycleClaim,
        current: DownloadLifecycleState,
        transition: DownloadLifecycleTransition,
    ): Boolean {
        if (claim.generation != current.generation) return false
        if (current.status == DownloadTaskStatus.DELETING) return false
        return when (transition) {
            DownloadLifecycleTransition.PROGRESS,
            DownloadLifecycleTransition.FAILURE,
            DownloadLifecycleTransition.COMPLETION -> current.status == DownloadTaskStatus.RUNNING
            DownloadLifecycleTransition.CANCELLATION ->
                current.status == DownloadTaskStatus.PENDING ||
                    current.status == DownloadTaskStatus.RUNNING
        }
    }

    fun nextGeneration(current: Long): Long =
        if (current == Long.MAX_VALUE) 1L else (current + 1L).coerceAtLeast(1L)
}

enum class DownloadCompletionCommit {
    COMMITTED,
    FENCED,
    RENAME_FAILED,
}

/** 文件 rename 与完成状态提交配对的唯一入口；状态提交失败必须撤回最终文件。 */
object DownloadCompletionCommitRules {
    suspend fun commit(
        partial: File,
        finalFile: File,
        canStart: suspend () -> Boolean,
        commitState: suspend () -> Int,
    ): DownloadCompletionCommit {
        if (!canStart()) return DownloadCompletionCommit.FENCED
        if (finalFile.exists() && !finalFile.delete()) {
            return DownloadCompletionCommit.RENAME_FAILED
        }
        if (!partial.renameTo(finalFile)) {
            return DownloadCompletionCommit.RENAME_FAILED
        }
        if (commitState() <= 0) {
            finalFile.delete()
            return DownloadCompletionCommit.FENCED
        }
        return DownloadCompletionCommit.COMMITTED
    }
}
