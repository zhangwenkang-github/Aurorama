package com.zhangwenkang.cinefin.utils

import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** W84：对用户可见的下载阶段。底层任务状态仍由 [DownloadTaskStatus] 负责持久化与调度。 */
enum class DownloadTaskPhase {
    PREPARING,
    CONNECTING,
    DOWNLOADING,
    VERIFYING,
    WAITING_RETRY,
    PAUSED,
    FAILED,
    COMPLETED,
    DELETING,
}

/** 等待 / 失败行的可解释信息；不包含 Android 资源，便于注入时钟单测。 */
data class DownloadTaskExplanation(
    val phase: DownloadTaskPhase,
    val reason: DownloadFailureReason?,
    val retryCount: Int,
    val nextRetryAt: Long,
)

/** 音乐批量点击时立即持久化的子意图。 */
data class DownloadMusicIntentRequest(
    val itemId: UUID,
    val name: String,
    val albumName: String?,
    val artist: String?,
    val trackIndex: Int,
)

/** 后台持久化入口；实际 Room 实现必须以单一事务写入 [saveAll]。 */
interface DownloadIntentStore {
    suspend fun existingItemIds(itemIds: Set<UUID>): Set<UUID>

    suspend fun saveAll(requests: List<DownloadMusicIntentRequest>)
}

data class DownloadBatchReceipt(
    val acceptedIds: List<UUID>,
    val alreadyAcceptedCount: Int,
    val acceptedAtMs: Long,
)

/** 批量受理门面：先确认已存在集合，再一次性持久化全部新意图。 */
class DownloadBatchInbox(
    private val store: DownloadIntentStore,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val acceptMutex = Mutex()

    suspend fun accept(requests: List<DownloadMusicIntentRequest>): DownloadBatchReceipt =
        acceptMutex.withLock {
            val normalized = DownloadControlPlaneRules.normalizeMusicBatch(requests)
            val existing = store.existingItemIds(normalized.mapTo(mutableSetOf()) { it.itemId })
            val fresh = normalized.filterNot { it.itemId in existing }
            if (fresh.isNotEmpty()) {
                store.saveAll(fresh)
            }
            DownloadBatchReceipt(
                acceptedIds = fresh.map { it.itemId },
                alreadyAcceptedCount = normalized.size - fresh.size,
                acceptedAtMs = clock(),
            )
        }
}

/**
 * W84 下载控制面纯规则。
 *
 * 下载页 / 抽屉 / 角标 / 通知都通过这里的唯一集合口径取数，避免容器数与任务数混用。
 */
object DownloadControlPlaneRules {

    fun activeUniqueIntents(
        tasks: List<DownloadTask>,
        downloadedCanonicalKeys: Set<String>,
    ): List<DownloadTask> =
        tasks
            .asSequence()
            .filter { task ->
                DownloadTaskRules.isActiveQueueStatus(task.status) &&
                    task.downloadIdentity().canonicalKey !in downloadedCanonicalKeys
            }
            .distinctBy { it.downloadIdentity() }
            .toList()

    fun retryableFailureIntents(tasks: List<DownloadTask>): List<DownloadTask> =
        tasks
            .asSequence()
            .filter { task -> DownloadTaskRules.canRetry(task.status) }
            .distinctBy { it.downloadIdentity() }
            .toList()

    fun badgeText(count: Int): String =
        when {
            count <= 0 -> ""
            count > 99 -> "99+"
            else -> count.toString()
        }

    fun phase(
        status: DownloadTaskStatus,
        downloadedBytes: Long,
        totalBytes: Long,
        failureReason: DownloadFailureReason?,
        nextRetryAt: Long,
        nowMs: Long,
    ): DownloadTaskPhase =
        when (status) {
            DownloadTaskStatus.COMPLETED -> DownloadTaskPhase.COMPLETED
            DownloadTaskStatus.FAILED -> DownloadTaskPhase.FAILED
            DownloadTaskStatus.PAUSED -> DownloadTaskPhase.PAUSED
            DownloadTaskStatus.DELETING -> DownloadTaskPhase.DELETING
            DownloadTaskStatus.PENDING ->
                if (failureReason != null || nextRetryAt > nowMs) {
                    DownloadTaskPhase.WAITING_RETRY
                } else {
                    DownloadTaskPhase.PREPARING
                }
            DownloadTaskStatus.RUNNING ->
                when {
                    totalBytes > 0L && downloadedBytes >= totalBytes -> DownloadTaskPhase.VERIFYING
                    downloadedBytes > 0L -> DownloadTaskPhase.DOWNLOADING
                    else -> DownloadTaskPhase.CONNECTING
                }
        }

    fun explanation(task: DownloadTask, nowMs: Long): DownloadTaskExplanation? {
        val currentPhase =
            phase(
                status = task.status,
                downloadedBytes = task.downloadedBytes,
                totalBytes = task.totalBytes,
                failureReason = task.failureReason,
                nextRetryAt = task.nextRetryAt,
                nowMs = nowMs,
            )
        if (
            currentPhase != DownloadTaskPhase.WAITING_RETRY &&
                currentPhase != DownloadTaskPhase.FAILED
        ) {
            return null
        }
        return DownloadTaskExplanation(
            phase = currentPhase,
            reason = task.failureReason,
            retryCount = task.retryCount,
            nextRetryAt = task.nextRetryAt,
        )
    }

    fun normalizeMusicBatch(
        requests: List<DownloadMusicIntentRequest>
    ): List<DownloadMusicIntentRequest> = requests.distinctBy { it.itemId }
}
