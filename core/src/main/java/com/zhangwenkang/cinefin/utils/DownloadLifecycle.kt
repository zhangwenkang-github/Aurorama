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

/**
 * 文件 rename 与完成状态提交配对的唯一入口。
 *
 * 顺序固定为 witness → 最终文件 → DB COMPLETED。进程若死在 rename 与 DB 之间，重启后可用 witness 重新提交；DB 永不记录不存在的最终文件。
 */
object DownloadCompletionCommitRules {
    suspend fun commit(
        partial: File,
        finalFile: File,
        canStart: suspend () -> Boolean,
        commitState: suspend () -> Int,
        onCommitted: suspend () -> Unit = {},
        sourceId: String = "",
        generation: Long = 0L,
    ): DownloadCompletionCommit {
        if (!canStart()) return DownloadCompletionCommit.FENCED
        val witness = witnessFile(partial = partial, finalFile = finalFile)
        val recovered =
            hasInterruptedFinalization(
                partial = partial,
                finalFile = finalFile,
                sourceId = sourceId,
                generation = generation,
            )
        val samePath = partial.canonicalFile == finalFile.canonicalFile
        if (!recovered) {
            if (!partial.isFile || finalFile.exists()) {
                return DownloadCompletionCommit.RENAME_FAILED
            }
            if (!writeWitness(witness, sourceId, generation, partial, finalFile)) {
                return DownloadCompletionCommit.RENAME_FAILED
            }
            if (!samePath && !partial.renameTo(finalFile)) {
                return DownloadCompletionCommit.RENAME_FAILED
            }
        }
        if (!finalFile.isFile) {
            return DownloadCompletionCommit.RENAME_FAILED
        }
        if (commitState() <= 0) {
            if (!recovered && !samePath && finalFile.isFile && !partial.exists()) {
                finalFile.renameTo(partial)
            }
            return DownloadCompletionCommit.FENCED
        }
        witness.delete()
        onCommitted()
        return DownloadCompletionCommit.COMMITTED
    }

    fun finalFile(partial: File): File =
        if (partial.path.endsWith(".download")) {
            File(partial.path.removeSuffix(".download"))
        } else {
            partial
        }

    fun witnessFile(partial: File, finalFile: File): File =
        File(finalFile.parentFile, ".${finalFile.name}.finalizing")

    fun hasInterruptedFinalization(
        partial: File,
        finalFile: File,
        sourceId: String,
        generation: Long,
    ): Boolean {
        if (partial.exists() || !finalFile.isFile) return false
        return witnessMatches(
            file = witnessFile(partial, finalFile),
            sourceId = sourceId,
            generation = generation,
            partial = partial,
            finalFile = finalFile,
        )
    }

    private fun writeWitness(
        file: File,
        sourceId: String,
        generation: Long,
        partial: File,
        finalFile: File,
    ): Boolean = runCatching {
        val parent = file.parentFile
        if (parent == null || (!parent.isDirectory && !parent.mkdirs())) return false
        val temp = File(parent, "${file.name}.part")
        temp.writeText(witness(sourceId, generation, partial, finalFile))
        if (file.exists() && !file.delete()) return false
        if (!temp.renameTo(file)) {
            temp.delete()
            return false
        }
        true
    }
        .getOrDefault(false)

    private fun witnessMatches(
        file: File,
        sourceId: String,
        generation: Long,
        partial: File,
        finalFile: File,
    ): Boolean =
        file.isFile &&
            runCatching { file.readLines() }
                .getOrNull()
                ?.let { lines ->
                    lines.size == 4 &&
                        lines[0] == sourceId &&
                        lines[1].toLongOrNull()?.let { it <= generation } == true &&
                        lines[2] == partial.canonicalPath &&
                        lines[3] == finalFile.canonicalPath
                } == true

    private fun witness(
        sourceId: String,
        generation: Long,
        partial: File,
        finalFile: File,
    ): String =
        listOf(
                sourceId,
                generation.toString(),
                partial.canonicalPath,
                finalFile.canonicalPath,
            )
            .joinToString("\n")
}
