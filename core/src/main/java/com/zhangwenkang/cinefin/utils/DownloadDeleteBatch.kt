package com.zhangwenkang.cinefin.utils

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException

data class DownloadDeleteRequest(
    val itemId: UUID,
    val sourceId: String?,
    val mediaKind: DownloadMediaKind,
    val serverId: String?,
    val userId: String?,
    val expectedGeneration: Long? = null,
)

enum class DownloadDeleteOutcome {
    PENDING,
    SUCCEEDED,
    FAILED,
    SUPERSEDED,
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
    val expectedGeneration: Long? = null,
)

data class DownloadDeleteAttempt(
    val outcome: DownloadDeleteOutcome,
    val detail: String? = null,
    val expectedGeneration: Long? = null,
) {
    init {
        require(outcome != DownloadDeleteOutcome.PENDING)
    }
}

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

    val supersededKeys: Set<String>
        get() =
            receipts
                .filter { it.outcome == DownloadDeleteOutcome.SUPERSEDED }
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
    private val delete: suspend (DownloadDeleteRequest, Boolean) -> DownloadDeleteAttempt,
) {
    private val trustedGenerations = ConcurrentHashMap<ReceiptIdentity, Long>()

    suspend fun deleteAll(requests: List<DownloadDeleteRequest>): DownloadDeleteBatchResult {
        val normalized = requests.distinctBy(DownloadDeletionIdentity::canonicalKey)
        val id = batchId()
        val startedAt = clock()
        val registered = normalized.mapNotNull { request ->
            request.expectedGeneration?.let { generation ->
                ReceiptIdentity(
                    batchId = id,
                    canonicalKey = DownloadDeletionIdentity.canonicalKey(request),
                ) to generation
            }
        }
        trustedGenerations.putAll(registered.toMap())
        try {
            store.begin(
                batchId = id,
                requests = normalized,
                createdAtMs = startedAt,
            )
        } catch (error: Throwable) {
            registered.forEach { (identity, _) -> trustedGenerations.remove(identity) }
            throw error
        }
        return process(
            id,
            normalized.mapIndexed { ordinal, request ->
                RetryEntry(request = request, ordinal = ordinal, resumed = false)
            },
        )
    }

    /** 重跑 PENDING / FAILED receipt；成功落库后再次恢复会跳过该项。 */
    suspend fun resumeIncomplete(): List<DownloadDeleteBatchResult> =
        store.incomplete().mapNotNull { snapshot ->
            val retryable =
                snapshot.receipts
                    .filter {
                        it.outcome == DownloadDeleteOutcome.PENDING ||
                            it.outcome == DownloadDeleteOutcome.FAILED
                    }
                    .sortedBy(DownloadDeleteReceipt::ordinal)
            if (retryable.isEmpty()) {
                forgetBatch(snapshot.batchId)
                store.finish(snapshot.batchId, clock())
                null
            } else {
                process(
                    batchId = snapshot.batchId,
                    requests =
                        retryable.map { receipt ->
                            RetryEntry(
                                request =
                                    DownloadDeleteRequest(
                                        itemId = receipt.itemId,
                                        sourceId = receipt.sourceId,
                                        mediaKind = receipt.mediaKind,
                                        serverId = receipt.serverId,
                                        userId = receipt.userId,
                                        expectedGeneration =
                                            trustedGeneration(
                                                batchId = snapshot.batchId,
                                                canonicalKey = receipt.canonicalKey,
                                            ),
                                    ),
                                ordinal = receipt.ordinal,
                                resumed = true,
                            )
                        },
                )
            }
        }

    private suspend fun process(
        batchId: String,
        requests: List<RetryEntry>,
    ): DownloadDeleteBatchResult {
        val receipts = mutableListOf<DownloadDeleteReceipt>()
        for ((request, ordinal, resumed) in requests) {
            val attempt =
                try {
                    delete(request, resumed)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: Exception) {
                    DownloadDeleteAttempt(
                        outcome = DownloadDeleteOutcome.FAILED,
                        detail =
                            error.message?.takeIf(String::isNotBlank) ?: error::class.simpleName,
                        expectedGeneration = request.expectedGeneration,
                    )
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
                    outcome = attempt.outcome,
                    detail = attempt.detail,
                    updatedAtMs = clock(),
                    expectedGeneration = attempt.expectedGeneration ?: request.expectedGeneration,
                )
            store.record(receipt)
            when (receipt.outcome) {
                DownloadDeleteOutcome.PENDING -> Unit
                DownloadDeleteOutcome.FAILED ->
                    receipt.expectedGeneration?.let {
                        trustedGenerations.put(
                            ReceiptIdentity(batchId, receipt.canonicalKey),
                            it,
                        )
                    }
                DownloadDeleteOutcome.SUCCEEDED,
                DownloadDeleteOutcome.SUPERSEDED ->
                    trustedGenerations.remove(ReceiptIdentity(batchId, receipt.canonicalKey))
            }
            receipts += receipt
        }
        if (
            receipts.all {
                it.outcome == DownloadDeleteOutcome.SUCCEEDED ||
                    it.outcome == DownloadDeleteOutcome.SUPERSEDED
            }
        ) {
            store.finish(batchId, clock())
        }
        return DownloadDeleteBatchResult(batchId = batchId, receipts = receipts)
    }

    private fun trustedGeneration(
        batchId: String,
        canonicalKey: String,
    ): Long? = trustedGenerations[ReceiptIdentity(batchId, canonicalKey)]

    private fun forgetBatch(batchId: String) {
        trustedGenerations.keys.removeIf { it.batchId == batchId }
    }

    private data class ReceiptIdentity(
        val batchId: String,
        val canonicalKey: String,
    )

    private data class RetryEntry(
        val request: DownloadDeleteRequest,
        val ordinal: Int,
        val resumed: Boolean,
    )
}
