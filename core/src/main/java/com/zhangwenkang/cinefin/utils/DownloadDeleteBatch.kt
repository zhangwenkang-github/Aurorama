package com.zhangwenkang.cinefin.utils

import java.util.UUID
import kotlinx.coroutines.CancellationException

data class DownloadDeleteRequest(
    val itemId: UUID,
    val sourceId: String?,
    val mediaKind: DownloadMediaKind,
    val serverId: String?,
    val userId: String?,
)

enum class DownloadDeleteOutcome {
    PENDING,
    SUCCEEDED,
    FAILED,
}

data class DownloadDeleteReceipt(
    val batchId: String,
    val canonicalKey: String,
    val itemId: UUID,
    val sourceId: String?,
    val mediaKind: DownloadMediaKind,
    val serverId: String?,
    val userId: String?,
    val ordinal: Int,
    val outcome: DownloadDeleteOutcome,
    val detail: String?,
    val updatedAtMs: Long,
)

data class DownloadDeleteBatchResult(
    val batchId: String,
    val receipts: List<DownloadDeleteReceipt>,
) {
    val succeededKeys: Set<String>
        get() =
            receipts
                .filter { it.outcome == DownloadDeleteOutcome.SUCCEEDED }
                .mapTo(mutableSetOf()) { it.canonicalKey }

    val failedKeys: Set<String>
        get() =
            receipts
                .filter { it.outcome == DownloadDeleteOutcome.FAILED }
                .mapTo(mutableSetOf()) { it.canonicalKey }
}

object DownloadDeletionIdentity {
    fun canonicalKey(request: DownloadDeleteRequest): String =
        DownloadCanonicalIdentity.of(
                mediaKind = request.mediaKind,
                itemId = request.itemId.toString(),
                serverId = request.serverId,
                userId = request.userId,
                sourceId = request.sourceId,
            )
            .canonicalKey
}

data class DownloadDeleteBatchSnapshot(
    val batchId: String,
    val receipts: List<DownloadDeleteReceipt>,
)

interface DownloadDeleteBatchStore {
    /** 必须在同一事务内建立 batch 与全部 PENDING receipts。 */
    suspend fun begin(
        batchId: String,
        requests: List<DownloadDeleteRequest>,
        createdAtMs: Long,
    )

    suspend fun record(receipt: DownloadDeleteReceipt)

    suspend fun finish(batchId: String, completedAtMs: Long)

    suspend fun incomplete(): List<DownloadDeleteBatchSnapshot>
}

/**
 * 一个用户动作 = 一个持久 batch；每项独立执行并立即写 receipt。
 *
 * 单项异常只落该条 FAILED，之后仍继续；协程取消不属于项失败，必须向调用方传播。
 */
class DownloadDeleteBatchProcessor(
    private val store: DownloadDeleteBatchStore,
    private val clock: () -> Long = System::currentTimeMillis,
    private val batchId: () -> String = { UUID.randomUUID().toString() },
    private val delete: suspend (DownloadDeleteRequest) -> Boolean,
) {
    suspend fun deleteAll(requests: List<DownloadDeleteRequest>): DownloadDeleteBatchResult {
        val normalized = requests.distinctBy(DownloadDeletionIdentity::canonicalKey)
        val id = batchId()
        val startedAt = clock()
        store.begin(
            batchId = id,
            requests = normalized,
            createdAtMs = startedAt,
        )
        return process(id, normalized.mapIndexed { ordinal, request -> request to ordinal })
    }

    /** 只重跑仍为 PENDING 的 receipt；成功落库后再次恢复会跳过该项。 */
    suspend fun resumeIncomplete(): List<DownloadDeleteBatchResult> =
        store.incomplete().mapNotNull { snapshot ->
            val pending =
                snapshot.receipts
                    .filter { it.outcome == DownloadDeleteOutcome.PENDING }
                    .sortedBy(DownloadDeleteReceipt::ordinal)
            if (pending.isEmpty()) {
                store.finish(snapshot.batchId, clock())
                null
            } else {
                process(
                    batchId = snapshot.batchId,
                    requests =
                        pending.map { receipt ->
                            DownloadDeleteRequest(
                                itemId = receipt.itemId,
                                sourceId = receipt.sourceId,
                                mediaKind = receipt.mediaKind,
                                serverId = receipt.serverId,
                                userId = receipt.userId,
                            ) to receipt.ordinal
                        },
                )
            }
        }

    private suspend fun process(
        batchId: String,
        requests: List<Pair<DownloadDeleteRequest, Int>>,
    ): DownloadDeleteBatchResult {
        val receipts = mutableListOf<DownloadDeleteReceipt>()
        for ((request, ordinal) in requests) {
            val outcome =
                try {
                    if (delete(request)) {
                        DownloadDeleteOutcome.SUCCEEDED to null
                    } else {
                        DownloadDeleteOutcome.FAILED to "删除未确认"
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: Exception) {
                    DownloadDeleteOutcome.FAILED to
                        (error.message?.takeIf(String::isNotBlank) ?: error::class.simpleName)
                }
            val receipt =
                DownloadDeleteReceipt(
                    batchId = batchId,
                    canonicalKey = DownloadDeletionIdentity.canonicalKey(request),
                    itemId = request.itemId,
                    sourceId = request.sourceId,
                    mediaKind = request.mediaKind,
                    serverId = request.serverId,
                    userId = request.userId,
                    ordinal = ordinal,
                    outcome = outcome.first,
                    detail = outcome.second,
                    updatedAtMs = clock(),
                )
            store.record(receipt)
            receipts += receipt
        }
        store.finish(batchId, clock())
        return DownloadDeleteBatchResult(batchId = batchId, receipts = receipts)
    }
}
