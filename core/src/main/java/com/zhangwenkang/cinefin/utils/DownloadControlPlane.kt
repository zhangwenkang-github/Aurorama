package com.zhangwenkang.cinefin.utils

import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** W84：对用户可见的下载阶段。底层任务状态仍由 [DownloadTaskStatus] 负责持久化与调度。 */
enum class DownloadTaskPhase {
    QUEUED,
    RESOLVING,
    SOURCE_PENDING,
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
    val serverId: String? = null,
    val userId: String? = null,
    val sourceId: String? = null,
    /** 容器位置只随意图传递，不参与 canonical identity / 去重。 */
    val placement: DownloadPlacement = DownloadPlacement.LEGACY,
) {
    val canonicalIdentity: DownloadCanonicalIdentity
        get() =
            DownloadCanonicalIdentity.of(
                mediaKind = DownloadMediaKind.MUSIC,
                itemId = itemId.toString(),
                serverId = serverId,
                userId = userId,
                sourceId = sourceId,
            )

    /** 持久化查重键；来源未知时只允许显式 legacy itemId 回退。 */
    val acceptanceKey: String
        get() = canonicalIdentity.foldKey ?: "legacy|${DownloadMediaKind.MUSIC.name}|$itemId"
}

/** 后台持久化入口；生产实现必须先原子写 sidecar，再同步 legacy Room 行。 */
interface DownloadIntentStore {
    suspend fun existingCanonicalKeys(requests: List<DownloadMusicIntentRequest>): Set<String>

    suspend fun saveAll(requests: List<DownloadMusicIntentRequest>)
}

data class DownloadBatchReceipt(
    val acceptedIds: List<UUID>,
    val acceptedKeys: List<String>,
    val acceptedRequests: List<DownloadMusicIntentRequest>,
    val alreadyAcceptedCount: Int,
    val acceptedAtMs: Long,
)

/** Canonical active-task leaf. Identity and raw byte values are frozen at publication time. */
data class CanonicalActiveTask(
    val identity: DownloadCanonicalIdentity,
    val task: DownloadTask,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val actionCapabilities: Set<CanonicalDownloadAction>,
)

data class CanonicalActiveTaskProjection(
    val samplingEpochMs: Long,
    val identityKeys: List<String>,
    val activeCount: Int,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val actionEligibleIdentities: Map<CanonicalDownloadAction, Set<String>>,
) {
    val identitySet: Set<String>
        get() = identityKeys.toSet()
}

/**
 * The single immutable active-task snapshot consumed by the downloads page and foreground
 * notification.
 */
data class CanonicalActiveTaskSnapshot(
    val samplingEpochMs: Long,
    val tasks: List<CanonicalActiveTask>,
    val downloadedBytes: Long,
    val totalBytes: Long,
) {
    val activeTasks: List<DownloadTask>
        get() = tasks.map(CanonicalActiveTask::task)

    val sortedIdentityKeys: List<String>
        get() = tasks.map { leaf -> leaf.identity.canonicalKey }

    val activeCount: Int
        get() = tasks.size

    val projection: CanonicalActiveTaskProjection
        get() =
            CanonicalActiveTaskProjection(
                samplingEpochMs = samplingEpochMs,
                identityKeys = sortedIdentityKeys,
                activeCount = activeCount,
                downloadedBytes = downloadedBytes,
                totalBytes = totalBytes,
                actionEligibleIdentities =
                    CanonicalDownloadAction.entries
                        .mapNotNull { action ->
                            tasks
                                .asSequence()
                                .filter { leaf -> action in leaf.actionCapabilities }
                                .map { leaf -> leaf.identity.canonicalKey }
                                .toSet()
                                .takeIf(Set<String>::isNotEmpty)
                                ?.let { identities -> action to identities }
                        }
                        .toMap(),
            )

    companion object {
        fun empty(samplingEpochMs: Long): CanonicalActiveTaskSnapshot =
            CanonicalActiveTaskSnapshot(
                samplingEpochMs = samplingEpochMs,
                tasks = emptyList(),
                downloadedBytes = 0L,
                totalBytes = 0L,
            )
    }
}

enum class CanonicalDownloadAction {
    PAUSE,
    RESUME,
    DELETE,
}

fun DownloadTask.canonicalActionCapabilities(): Set<CanonicalDownloadAction> = buildSet {
    if (DownloadTaskRules.canPause(this@canonicalActionCapabilities)) {
        add(CanonicalDownloadAction.PAUSE)
    }
    if (DownloadTaskRules.canResume(this@canonicalActionCapabilities)) {
        add(CanonicalDownloadAction.RESUME)
    }
    if (status != DownloadTaskStatus.DELETING) {
        add(CanonicalDownloadAction.DELETE)
    }
}

/** App-scope source of the one latest canonical active-task snapshot. */
interface CanonicalActiveTaskSnapshotSource {
    suspend fun sampleCanonicalActiveTaskSnapshot(): CanonicalActiveTaskSnapshot

    fun latestCanonicalActiveTaskSnapshot(): CanonicalActiveTaskSnapshot
}

/**
 * Single-flight canonical snapshot sampler.
 *
 * Consumers request a fresh snapshot; only the owner performs the asynchronous read. Requests that
 * arrive while a sample is in flight coalesce into the next serialized sample. A request that loses
 * the coalescing race still gets a subsequent sample, so it never has to consume an older fallback.
 */
class CanonicalActiveTaskSnapshotOwner(private val clock: () -> Long = System::currentTimeMillis) {
    private val samplingMutex = Mutex()
    private var highestRequestId = 0L
    private var completedRequestId = 0L
    private var latest = CanonicalActiveTaskSnapshot.empty(samplingEpochMs = 0L)

    @Synchronized
    private fun allocateRequestId(): Long {
        highestRequestId += 1L
        return highestRequestId
    }

    @Synchronized
    private fun nextSamplingEpoch(): Long =
        maxOf(clock().coerceAtLeast(0L), latest.samplingEpochMs + 1L)

    suspend fun sample(
        loader: suspend (samplingEpochMs: Long) -> CanonicalActiveTaskSnapshot
    ): CanonicalActiveTaskSnapshot {
        val requestId = allocateRequestId()
        samplingMutex.withLock {
            if (synchronized(this) { completedRequestId >= requestId }) {
                return latest()
            }

            val targetRequestId = synchronized(this) { highestRequestId }
            val sampled = loader(nextSamplingEpoch())
            return synchronized(this) {
                if (sampled.samplingEpochMs > latest.samplingEpochMs) {
                    latest = sampled
                }
                completedRequestId = maxOf(completedRequestId, targetRequestId)
                latest
            }
        }
    }

    @Synchronized fun latest(): CanonicalActiveTaskSnapshot = latest
}

/** 批量受理门面：先确认已存在集合，再一次性持久化全部新意图。 */
class DownloadBatchInbox(
    private val store: DownloadIntentStore,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val acceptMutex = Mutex()

    suspend fun accept(requests: List<DownloadMusicIntentRequest>): DownloadBatchReceipt =
        acceptMutex.withLock {
            val normalized = DownloadControlPlaneRules.normalizeMusicBatch(requests)
            val existing = store.existingCanonicalKeys(normalized)
            val fresh = normalized.filter { request -> request.acceptanceKey !in existing }
            if (fresh.isNotEmpty()) {
                store.saveAll(fresh)
            }
            DownloadBatchReceipt(
                acceptedIds = fresh.map { it.itemId },
                acceptedKeys = fresh.mapNotNull { it.canonicalIdentity.foldKey },
                acceptedRequests = fresh,
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

    /** 只有非 intent-only 的 PENDING task 才允许启动传输 Engine。 */
    fun hasRunnableEngineTasks(tasks: List<DownloadTask>): Boolean = tasks.any { task ->
        task.status == DownloadTaskStatus.PENDING && !task.sourceId.startsWith(INTENT_SOURCE_PREFIX)
    }

    fun activeUniqueIntents(
        tasks: List<DownloadTask>,
        downloadedCanonicalKeys: Set<String>,
    ): List<DownloadTask> =
        canonicalActiveTaskSnapshot(
                tasks = tasks,
                downloadedCanonicalKeys = downloadedCanonicalKeys,
                samplingEpochMs = 0L,
            )
            .activeTasks

    /**
     * Applies active filtering, completed-key exclusion, full identity deduplication and
     * deterministic ordering exactly once.
     */
    fun canonicalActiveTaskSnapshot(
        tasks: List<DownloadTask>,
        downloadedCanonicalKeys: Set<String>,
        samplingEpochMs: Long,
    ): CanonicalActiveTaskSnapshot {
        val representatives =
            tasks
                .asSequence()
                .filter { task ->
                    DownloadTaskRules.isActiveQueueStatus(task.status) &&
                        task.downloadIdentity().canonicalKey !in downloadedCanonicalKeys
                }
                .groupBy { task -> task.downloadIdentity() }
                .values
                .map { duplicates ->
                    duplicates.maxWithOrNull(
                        compareBy<DownloadTask> { task -> task.taskGeneration }
                            .thenBy { task -> task.updatedAt }
                            .thenBy { task -> task.sourceId }
                    ) ?: return@map duplicates.first()
                }
                .sortedBy { task -> task.downloadIdentity().canonicalKey }

        val canonicalTasks = representatives.map { task ->
            CanonicalActiveTask(
                identity = task.downloadIdentity(),
                task = task,
                downloadedBytes = task.downloadedBytes.coerceAtLeast(0L),
                totalBytes = task.totalBytes.coerceAtLeast(0L),
                actionCapabilities = task.canonicalActionCapabilities(),
            )
        }
        return CanonicalActiveTaskSnapshot(
            samplingEpochMs = samplingEpochMs.coerceAtLeast(0L),
            tasks = canonicalTasks.toList(),
            downloadedBytes = canonicalTasks.sumOf(CanonicalActiveTask::downloadedBytes),
            totalBytes = canonicalTasks.sumOf(CanonicalActiveTask::totalBytes),
        )
    }

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
        intentPhase: DownloadIntentPhase? = null,
    ): DownloadTaskPhase =
        when (status) {
            DownloadTaskStatus.COMPLETED -> DownloadTaskPhase.COMPLETED
            DownloadTaskStatus.FAILED -> DownloadTaskPhase.FAILED
            DownloadTaskStatus.PAUSED -> DownloadTaskPhase.PAUSED
            DownloadTaskStatus.DELETING -> DownloadTaskPhase.DELETING
            DownloadTaskStatus.PENDING ->
                when {
                    intentPhase == DownloadIntentPhase.QUEUED -> DownloadTaskPhase.QUEUED
                    intentPhase == DownloadIntentPhase.RESOLVING -> DownloadTaskPhase.RESOLVING
                    intentPhase == DownloadIntentPhase.SOURCE_PENDING ->
                        DownloadTaskPhase.SOURCE_PENDING
                    failureReason != null || nextRetryAt > nowMs -> DownloadTaskPhase.WAITING_RETRY
                    else -> DownloadTaskPhase.PREPARING
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
                intentPhase = task.intentPhase,
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
    ): List<DownloadMusicIntentRequest> {
        val seenCompleteNamespaces = mutableSetOf<String>()
        return requests.filter { request ->
            val key = request.canonicalIdentity.foldKey ?: return@filter true
            seenCompleteNamespaces.add(key)
        }
    }
}
