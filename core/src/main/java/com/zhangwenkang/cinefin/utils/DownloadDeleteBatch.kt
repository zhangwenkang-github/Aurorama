package com.zhangwenkang.cinefin.utils

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout

enum class DownloadDeleteOwnedGeneration {
    SOURCE,
    INTENT,
}

data class DownloadDeleteRequest(
    val itemId: UUID,
    val sourceId: String?,
    val mediaKind: DownloadMediaKind,
    val serverId: String?,
    val userId: String?,
    val expectedGeneration: Long? = null,
    val expectedGenerationOwnership: DownloadDeleteOwnedGeneration? = null,
    internal val ownershipToken: String? = null,
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

internal fun sourceDeletionOwnershipDetail(generation: Long): String =
    "w90-source-delete-owned:$generation"

internal fun intentDeletionOwnershipDetail(generation: Long): String =
    "w90-intent-delete-owned:$generation"

internal fun intentDeletionOwnershipDetail(
    generation: Long,
    ownershipToken: String,
): String = "${intentDeletionOwnershipDetail(generation)}:$ownershipToken"

internal fun intentDeletionCasOwnershipDetail(
    generation: Long,
    ownershipToken: String,
): String = "w90-intent-delete-cas:$generation:$ownershipToken"

internal fun downloadDeleteOwnershipToken(
    batchId: String,
    canonicalKey: String,
): String = "$batchId:$canonicalKey"

internal fun isInternalIntentDeletionDetail(detail: String?): Boolean =
    detail?.startsWith("w90-intent-delete-owned:") == true ||
        detail?.startsWith("w90-intent-delete-cas:") == true

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
    private val itemTimeoutMs: Long = 15_000L,
    private val delete:
        suspend (
            DownloadDeleteRequest,
            Boolean,
            suspend (DownloadDeleteOwnedGeneration, Long) -> Unit,
        ) -> DownloadDeleteAttempt,
) {
    init {
        require(itemTimeoutMs > 0L) { "删除单项 deadline 必须大于 0" }
    }

    private val trustedGenerations = ConcurrentHashMap<ReceiptIdentity, Long>()

    suspend fun deleteAll(requests: List<DownloadDeleteRequest>): DownloadDeleteBatchResult {
        val normalized = requests.distinctBy(DownloadDeletionIdentity::canonicalKey)
        val id = batchId()
        val ownedRequests = normalized.map { request ->
            request.copy(
                ownershipToken =
                    downloadDeleteOwnershipToken(
                        batchId = id,
                        canonicalKey = DownloadDeletionIdentity.canonicalKey(request),
                    )
            )
        }
        val startedAt = clock()
        val registered = ownedRequests.mapNotNull { request ->
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
                requests = ownedRequests,
                createdAtMs = startedAt,
            )
        } catch (error: Throwable) {
            registered.forEach { (identity, _) -> trustedGenerations.remove(identity) }
            throw error
        }
        return process(
            id,
            ownedRequests.mapIndexed { ordinal, request ->
                RetryEntry(request = request, ordinal = ordinal, resumed = false)
            },
        )
    }

    /** 启动恢复只重跑 PENDING；FAILED 保留给用户显式重试。 */
    suspend fun resumeIncomplete(): List<DownloadDeleteBatchResult> =
        store.incomplete().mapNotNull { snapshot ->
            val retryable =
                snapshot.receipts
                    .filter { it.outcome == DownloadDeleteOutcome.PENDING }
                    .sortedBy(DownloadDeleteReceipt::ordinal)
            val hasFailed = snapshot.receipts.any { it.outcome == DownloadDeleteOutcome.FAILED }
            if (retryable.isEmpty()) {
                if (!hasFailed) {
                    forgetBatch(snapshot.batchId)
                    store.finish(snapshot.batchId, clock())
                }
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
                                            receipt.expectedGeneration
                                                ?: trustedGeneration(
                                                    batchId = snapshot.batchId,
                                                    canonicalKey = receipt.canonicalKey,
                                                ),
                                        expectedGenerationOwnership = ownedGeneration(receipt),
                                        ownershipToken =
                                            downloadDeleteOwnershipToken(
                                                batchId = snapshot.batchId,
                                                canonicalKey = receipt.canonicalKey,
                                            ),
                                    ),
                                ordinal = receipt.ordinal,
                                resumed = true,
                            )
                        },
                    finishIfNoRetryableFailures = !hasFailed,
                )
            }
        }

    private suspend fun process(
        batchId: String,
        requests: List<RetryEntry>,
        finishIfNoRetryableFailures: Boolean = true,
    ): DownloadDeleteBatchResult {
        val receipts = mutableListOf<DownloadDeleteReceipt>()
        for ((request, ordinal, resumed) in requests) {
            var ownedGeneration = request.expectedGeneration
            val claimGeneration: suspend (DownloadDeleteOwnedGeneration, Long) -> Unit =
                { ownership, generation ->
                    ownedGeneration = generation
                    store.record(
                        DownloadDeleteReceipt(
                            batchId = batchId,
                            canonicalKey = DownloadDeletionIdentity.canonicalKey(request),
                            itemId = request.itemId,
                            sourceId = request.sourceId,
                            mediaKind = request.mediaKind,
                            serverId = request.serverId,
                            userId = request.userId,
                            ordinal = ordinal,
                            outcome = DownloadDeleteOutcome.PENDING,
                            detail =
                                when (ownership) {
                                    DownloadDeleteOwnedGeneration.SOURCE ->
                                        sourceDeletionOwnershipDetail(generation)
                                    DownloadDeleteOwnedGeneration.INTENT ->
                                        intentDeletionOwnershipDetail(
                                            generation,
                                            requireNotNull(request.ownershipToken),
                                        )
                                },
                            updatedAtMs = clock(),
                            expectedGeneration = generation,
                        )
                    )
                    trustedGenerations.put(
                        ReceiptIdentity(batchId, DownloadDeletionIdentity.canonicalKey(request)),
                        generation,
                    )
                }
            val attempt =
                try {
                    withTimeout(itemTimeoutMs) { delete(request, resumed, claimGeneration) }
                } catch (timeout: TimeoutCancellationException) {
                    currentCoroutineContext().ensureActive()
                    DownloadDeleteAttempt(
                        outcome = DownloadDeleteOutcome.FAILED,
                        detail = "删除超过单一项 ${itemTimeoutMs}ms 期限",
                        expectedGeneration = ownedGeneration,
                    )
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: Exception) {
                    DownloadDeleteAttempt(
                        outcome = DownloadDeleteOutcome.FAILED,
                        detail =
                            error.message?.takeIf(String::isNotBlank) ?: error::class.simpleName,
                        expectedGeneration = ownedGeneration,
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
                    expectedGeneration = attempt.expectedGeneration ?: ownedGeneration,
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
            finishIfNoRetryableFailures &&
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

    private fun ownedGeneration(receipt: DownloadDeleteReceipt): DownloadDeleteOwnedGeneration? =
        receipt.expectedGeneration?.let { generation ->
            when (receipt.detail) {
                sourceDeletionOwnershipDetail(generation) -> DownloadDeleteOwnedGeneration.SOURCE
                intentDeletionOwnershipDetail(generation),
                intentDeletionOwnershipDetail(
                    generation,
                    downloadDeleteOwnershipToken(
                        batchId = receipt.batchId,
                        canonicalKey = receipt.canonicalKey,
                    ),
                ) -> DownloadDeleteOwnedGeneration.INTENT
                else -> null
            }
        }

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
