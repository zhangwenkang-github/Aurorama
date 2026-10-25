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
        listOf(
                request.mediaKind.name,
                encode(request.serverId),
                encode(request.userId),
                request.itemId.toString(),
                encode(request.sourceId),
            )
            .joinToString("|")

    private fun encode(value: String?): String = value?.let { "v${it.length}:$it" } ?: "n"
}

interface DownloadDeleteBatchStore {
    suspend fun begin(batchId: String, canonicalKeys: List<String>, createdAtMs: Long)

    suspend fun record(receipt: DownloadDeleteReceipt)

    suspend fun finish(batchId: String, completedAtMs: Long)
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
    private val delete: suspend (DownloadDeleteRequest) -> Unit,
) {
    suspend fun deleteAll(requests: List<DownloadDeleteRequest>): DownloadDeleteBatchResult {
        val normalized = requests.distinctBy(DownloadDeletionIdentity::canonicalKey)
        val id = batchId()
        val startedAt = clock()
        store.begin(
            batchId = id,
            canonicalKeys = normalized.map(DownloadDeletionIdentity::canonicalKey),
            createdAtMs = startedAt,
        )

        val receipts = mutableListOf<DownloadDeleteReceipt>()
        for (request in normalized) {
            val outcome =
                try {
                    delete(request)
                    DownloadDeleteOutcome.SUCCEEDED to null
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: Exception) {
                    DownloadDeleteOutcome.FAILED to
                        (error.message?.takeIf(String::isNotBlank) ?: error::class.simpleName)
                }
            val receipt =
                DownloadDeleteReceipt(
                    batchId = id,
                    canonicalKey = DownloadDeletionIdentity.canonicalKey(request),
                    itemId = request.itemId,
                    sourceId = request.sourceId,
                    mediaKind = request.mediaKind,
                    outcome = outcome.first,
                    detail = outcome.second,
                    updatedAtMs = clock(),
                )
            store.record(receipt)
            receipts += receipt
        }
        store.finish(id, clock())
        return DownloadDeleteBatchResult(batchId = id, receipts = receipts)
    }
}
