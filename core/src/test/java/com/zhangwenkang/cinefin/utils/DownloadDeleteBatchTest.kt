package com.zhangwenkang.cinefin.utils

import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadDeleteBatchTest {

    private class MemoryStore : DownloadDeleteBatchStore {
        val batches = mutableMapOf<String, List<DownloadDeleteReceipt>>()
        val receipts = mutableListOf<DownloadDeleteReceipt>()
        val finishedAt = mutableMapOf<String, Long>()

        override suspend fun begin(
            batchId: String,
            requests: List<DownloadDeleteRequest>,
            createdAtMs: Long,
        ) {
            val pending = requests.mapIndexed { ordinal, request ->
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
                    detail = null,
                    updatedAtMs = createdAtMs,
                    expectedGeneration = request.expectedGeneration,
                )
            }
            batches[batchId] = pending
            receipts += pending
        }

        override suspend fun record(receipt: DownloadDeleteReceipt) {
            receipts.removeAll {
                it.batchId == receipt.batchId && it.canonicalKey == receipt.canonicalKey
            }
            receipts += receipt
        }

        override suspend fun finish(batchId: String, completedAtMs: Long) {
            finishedAt[batchId] = completedAtMs
        }

        override suspend fun incomplete(): List<DownloadDeleteBatchSnapshot> =
            batches
                .filterKeys { it !in finishedAt }
                .map { (batchId, initial) ->
                    DownloadDeleteBatchSnapshot(
                        batchId = batchId,
                        receipts =
                            receipts.filter { it.batchId == batchId } +
                                initial.filter { pending ->
                                    receipts.none {
                                        it.batchId == pending.batchId &&
                                            it.canonicalKey == pending.canonicalKey
                                    }
                                },
                    )
                }
    }

    private fun request(
        index: Int,
        serverId: String = "server-a",
        sourceId: String = "source-$index",
    ) =
        DownloadDeleteRequest(
            itemId = UUID(0L, index.toLong()),
            sourceId = sourceId,
            mediaKind = DownloadMediaKind.MUSIC,
            serverId = serverId,
            userId = "user-a",
        )

    @Test
    fun `N 项删除中单项失败仍处理后续并逐项落 receipt`() = runBlocking {
        val store = MemoryStore()
        val processed = mutableListOf<String>()
        val requests = (1..3).map(::request)
        val processor =
            DownloadDeleteBatchProcessor(
                store = store,
                clock = { 1_000L },
                batchId = { "batch-1" },
                delete = { request, _ ->
                    processed += request.sourceId.orEmpty()
                    if (request.itemId != UUID(0L, 2L)) {
                        DownloadDeleteAttempt(DownloadDeleteOutcome.SUCCEEDED)
                    } else {
                        DownloadDeleteAttempt(
                            outcome = DownloadDeleteOutcome.FAILED,
                            detail = "删除未确认",
                        )
                    }
                },
            )

        val result =
            processor.deleteAll(requests + request(1, serverId = "server-a", sourceId = "source-1"))

        assertEquals(listOf("source-1", "source-2", "source-3"), processed)
        assertEquals(
            listOf(
                DownloadDeleteOutcome.SUCCEEDED,
                DownloadDeleteOutcome.FAILED,
                DownloadDeleteOutcome.SUCCEEDED,
            ),
            result.receipts.map { it.outcome },
        )
        assertEquals(3, store.batches.getValue("batch-1").size)
        assertEquals(3, store.receipts.size)
        assertFalse("存在 FAILED receipt 的批次必须保留为可恢复状态", "batch-1" in store.finishedAt)
    }

    @Test
    fun `启动恢复只重跑 PENDING 并保留 FAILED 供显式重试`() = runBlocking {
        val store = MemoryStore()
        val attempts = mutableListOf<Pair<String, Boolean>>()
        val firstRequest = request(1).copy(expectedGeneration = 9L)
        val secondRequest = request(2).copy(expectedGeneration = 10L)
        val processor =
            DownloadDeleteBatchProcessor(
                store = store,
                clock = { 1_000L },
                batchId = { "batch-failed" },
                delete = { current, resumed ->
                    attempts += current.sourceId.orEmpty() to resumed
                    if (current.itemId == firstRequest.itemId) {
                        DownloadDeleteAttempt(
                            outcome = DownloadDeleteOutcome.FAILED,
                            detail = "删除未确认",
                            expectedGeneration = current.expectedGeneration,
                        )
                    } else {
                        throw CancellationException("进程中断")
                    }
                },
            )

        runCatching { processor.deleteAll(listOf(firstRequest, secondRequest)) }

        assertEquals(
            listOf(DownloadDeleteOutcome.FAILED, DownloadDeleteOutcome.PENDING),
            store.receipts.sortedBy(DownloadDeleteReceipt::ordinal).map { it.outcome },
        )
        assertFalse("batch-failed" in store.finishedAt)

        val restarted =
            DownloadDeleteBatchProcessor(
                    store = store,
                    clock = { 2_000L },
                    delete = { current, resumed ->
                        attempts += current.sourceId.orEmpty() to resumed
                        DownloadDeleteAttempt(
                            outcome = DownloadDeleteOutcome.SUCCEEDED,
                            expectedGeneration = current.expectedGeneration,
                        )
                    },
                )
                .resumeIncomplete()

        assertEquals(
            listOf("source-1" to false, "source-2" to false, "source-2" to true),
            attempts,
        )
        assertEquals("source-2", restarted.single().receipts.single().sourceId)
        assertEquals(
            listOf(DownloadDeleteOutcome.FAILED, DownloadDeleteOutcome.SUCCEEDED),
            store.receipts.sortedBy(DownloadDeleteReceipt::ordinal).map { it.outcome },
        )
        assertFalse("FAILED receipt 必须保留，批次不能被自动完成", "batch-failed" in store.finishedAt)
    }

    @Test
    fun `单项 deadline 超时只失败该项并继续后续项`() = runBlocking {
        val store = MemoryStore()
        val processed = mutableListOf<String>()
        val requests = listOf(request(1), request(2))
        val processor =
            DownloadDeleteBatchProcessor(
                store = store,
                clock = { 1_000L },
                batchId = { "batch-deadline" },
                delete = { request, _ ->
                    processed += request.sourceId.orEmpty()
                    if (request.itemId == requests.first().itemId) {
                        awaitCancellation()
                    }
                    DownloadDeleteAttempt(DownloadDeleteOutcome.SUCCEEDED)
                },
            )

        val result = withTimeoutOrNull(20_000L) { processor.deleteAll(requests) }

        assertNotNull("旧实现串行等待首项，后续项永远得不到 receipt", result)
        assertEquals(listOf("source-1", "source-2"), processed)
        assertEquals(
            listOf(DownloadDeleteOutcome.FAILED, DownloadDeleteOutcome.SUCCEEDED),
            requireNotNull(result).receipts.map { it.outcome },
        )
    }

    @Test
    fun `外部取消向上传播且不写 FAILED receipt`() = runBlocking {
        val store = MemoryStore()
        val started = CompletableDeferred<Unit>()
        val processor =
            DownloadDeleteBatchProcessor(
                store = store,
                batchId = { "batch-cancel" },
                delete = { _, _ ->
                    started.complete(Unit)
                    awaitCancellation()
                },
            )

        val deletion = async { processor.deleteAll(listOf(request(1))) }
        started.await()
        deletion.cancel()
        runCatching { deletion.await() }

        assertEquals(DownloadDeleteOutcome.PENDING, store.receipts.single().outcome)
        assertFalse(store.receipts.any { it.outcome == DownloadDeleteOutcome.FAILED })
    }

    @Test
    fun `外部 timeout cancellation 不被误判为单项 deadline`() = runBlocking {
        val store = MemoryStore()
        val processor =
            DownloadDeleteBatchProcessor(
                store = store,
                batchId = { "batch-external-timeout" },
                delete = { _, _ -> awaitCancellation() },
            )

        val outcome = runCatching { withTimeout(100L) { processor.deleteAll(listOf(request(1))) } }

        assertTrue(
            "外部取消必须抛回调用方",
            outcome.exceptionOrNull() is TimeoutCancellationException,
        )
        assertEquals(DownloadDeleteOutcome.PENDING, store.receipts.single().outcome)
    }

    @Test
    fun `完整 canonical identity 跨 server 不折叠`() {
        val a = request(7, serverId = "server-a", sourceId = "source-7")
        val b = request(7, serverId = "server-b", sourceId = "source-7")
        val c =
            a.copy(
                serverId = "server-a",
                userId = "user-b",
            )

        val identities = listOf(a, b, c).map(DownloadDeletionIdentity::canonicalKey).toSet()

        assertEquals(3, identities.size)
        assertTrue(
            DownloadDeletionIdentity.canonicalKey(a) != DownloadDeletionIdentity.canonicalKey(b)
        )
        assertTrue(
            DownloadDeletionIdentity.canonicalKey(a) != DownloadDeletionIdentity.canonicalKey(c)
        )
    }

    @Test
    fun `batch 在开始前持久化全部 pending receipt 且中断后只续跑未完成项`() = runBlocking {
        val store = MemoryStore()
        val processed = mutableListOf<String>()
        val requests = (1..3).map(::request)
        val firstProcessor =
            DownloadDeleteBatchProcessor(
                store = store,
                clock = { 1_000L },
                batchId = { "batch-crash" },
                delete = { request, _ ->
                    processed += request.sourceId.orEmpty()
                    if (request.itemId == UUID(0L, 2L)) {
                        throw CancellationException("进程中断")
                    }
                    DownloadDeleteAttempt(DownloadDeleteOutcome.SUCCEEDED)
                },
            )

        runCatching { firstProcessor.deleteAll(requests) }

        assertEquals(
            listOf(
                DownloadDeleteOutcome.SUCCEEDED,
                DownloadDeleteOutcome.PENDING,
                DownloadDeleteOutcome.PENDING,
            ),
            store.receipts.sortedBy(DownloadDeleteReceipt::ordinal).map { it.outcome },
        )
        assertTrue(store.finishedAt.isEmpty())

        val resumed =
            DownloadDeleteBatchProcessor(
                    store = store,
                    clock = { 2_000L },
                    delete = { request, _ ->
                        processed += request.sourceId.orEmpty()
                        DownloadDeleteAttempt(DownloadDeleteOutcome.SUCCEEDED)
                    },
                )
                .resumeIncomplete()

        assertEquals(listOf("source-2", "source-3"), processed.drop(2))
        assertEquals(listOf("batch-crash"), resumed.map { it.batchId })
        assertEquals(2_000L, store.finishedAt["batch-crash"])
        assertTrue(store.receipts.all { it.outcome != DownloadDeleteOutcome.PENDING })
    }

    @Test
    fun `superseded receipt 是终态且不会被失败选择或再次重试`() = runBlocking {
        val store = MemoryStore()
        val requests = listOf(request(1), request(2))
        val processor =
            DownloadDeleteBatchProcessor(
                store = store,
                clock = { 1_000L },
                batchId = { "batch-superseded" },
                delete = { request, _ ->
                    if (request.itemId == UUID(0L, 1L)) {
                        DownloadDeleteAttempt(DownloadDeleteOutcome.SUPERSEDED, "新代已替换")
                    } else {
                        DownloadDeleteAttempt(DownloadDeleteOutcome.SUCCEEDED)
                    }
                },
            )

        val result = processor.deleteAll(requests)

        assertEquals(1, result.supersededKeys.size)
        assertTrue(result.failedKeys.isEmpty())
        assertTrue(store.finishedAt.containsKey("batch-superseded"))
        assertTrue(processor.resumeIncomplete().isEmpty())
    }
}
