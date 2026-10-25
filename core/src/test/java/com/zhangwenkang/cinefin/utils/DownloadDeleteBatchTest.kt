package com.zhangwenkang.cinefin.utils

import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadDeleteBatchTest {

    private class MemoryStore : DownloadDeleteBatchStore {
        val batches = mutableMapOf<String, List<String>>()
        val receipts = mutableListOf<DownloadDeleteReceipt>()
        var finishedAt: Long? = null

        override suspend fun begin(
            batchId: String,
            canonicalKeys: List<String>,
            createdAtMs: Long,
        ) {
            batches[batchId] = canonicalKeys
        }

        override suspend fun record(receipt: DownloadDeleteReceipt) {
            receipts += receipt
        }

        override suspend fun finish(batchId: String, completedAtMs: Long) {
            finishedAt = completedAtMs
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
                delete = { request ->
                    processed += request.sourceId.orEmpty()
                    if (request.itemId == UUID(0L, 2L)) error("磁盘忙")
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
        assertEquals(1_000L, store.finishedAt)
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
}
