package com.zhangwenkang.cinefin.utils

import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloaderImplDeleteReceiptRecoveryTest {

    @Test
    fun `batch begin writes pending verifiedGeneration and terminal record keeps it`() =
        runBlocking {
            val fixture = DownloaderImplRecoveryTest.Fixture()
            try {
                val movie = fixture.movie()
                val (_, downloadError) = fixture.downloader.downloadItem(movie, SOURCE_ID, 0)
                assertNull(downloadError)
                val source = requireNotNull(fixture.dao.source)
                val request =
                    DownloadDeleteRequest(
                        itemId = source.itemId,
                        sourceId = source.id,
                        mediaKind = DownloadMediaKind.VIDEO,
                        serverId = SERVER_ID,
                        userId = USER_ID.toString(),
                        expectedGeneration = source.taskGeneration,
                    )
                fixture.dao.armSourceDeletionGate()

                val deletion =
                    async(Dispatchers.IO) { fixture.downloader.deleteDownloads(listOf(request)) }
                try {
                    withTimeout(5_000L) { fixture.dao.sourceDeleteStarted.await() }
                    val pending = fixture.dao.pendingDeleteReceipts().single()

                    assertEquals(DownloadDeleteOutcome.PENDING.name, pending.outcome)
                    assertEquals(
                        "batch begin 必须在同一持久 receipt 中写入 validated generation",
                        source.taskGeneration,
                        pending.verifiedGeneration,
                    )
                } finally {
                    fixture.dao.releaseSourceDelete.complete(Unit)
                }

                val result = deletion.await()
                val terminal =
                    requireNotNull(
                        fixture.dao.deleteReceipt(
                            result.batchId,
                            result.receipts.single().canonicalKey,
                        )
                    )
                assertEquals(DownloadDeleteOutcome.SUCCEEDED.name, terminal.outcome)
                assertEquals(source.taskGeneration, terminal.verifiedGeneration)
            } finally {
                fixture.close()
            }
        }

    @Test
    fun `new instance resumes intent receipt only for the verified generation`() = runBlocking {
        val fixture = DownloaderImplRecoveryTest.Fixture()
        try {
            val itemId = UUID(0L, 901L)
            val intent =
                fixture.seedReceivedIntentWithPendingSidecar(
                    itemId = itemId,
                    sourceId = intentSourceId(itemId),
                    name = "same-generation",
                )
            fixture.dao.seedPendingDeleteBatch(
                batchId = "intent-same-generation",
                itemId = itemId,
                sourceId = intentSourceId(itemId),
                expectedGeneration = intent.taskGeneration,
                mediaKind = DownloadMediaKind.MUSIC,
            )

            val recovered = fixture.newDownloader().resumePendingDeleteBatches()
            val receipt = fixture.dao.deleteReceipts("intent-same-generation").single()

            assertEquals("receipt=${receipt.outcome}/${receipt.detail}", 1, recovered)
            assertNull("同代 intent receipt 必须继续删除", fixture.dao.downloadIntent(itemId))
            assertEquals(
                DownloadDeleteOutcome.SUCCEEDED.name,
                receipt.outcome,
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `new instance supersedes changed intent generation with no side effects`() = runBlocking {
        val fixture = DownloaderImplRecoveryTest.Fixture()
        try {
            val itemId = UUID(0L, 902L)
            val intent =
                fixture.seedReceivedIntentWithPendingSidecar(
                    itemId = itemId,
                    sourceId = intentSourceId(itemId),
                    name = "changed-generation",
                )
            fixture.dao.seedPendingDeleteBatch(
                batchId = "intent-changed-generation",
                itemId = itemId,
                sourceId = intentSourceId(itemId),
                expectedGeneration = intent.taskGeneration,
                mediaKind = DownloadMediaKind.MUSIC,
            )
            fixture.dao.putDownloadIntent(
                intent.copy(taskGeneration = intent.taskGeneration + 1L, updatedAt = 2L)
            )

            assertEquals(0, fixture.newDownloader().resumePendingDeleteBatches())

            assertEquals(
                intent.taskGeneration + 1L,
                fixture.dao.downloadIntent(itemId)?.taskGeneration,
            )
            assertEquals(
                DownloadDeleteOutcome.SUPERSEDED.name,
                fixture.dao.deleteReceipts("intent-changed-generation").single().outcome,
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `new instance resumes source receipt while verified generation is still DELETING`() =
        runBlocking {
            val fixture = DownloaderImplRecoveryTest.Fixture()
            try {
                val deleting = fixture.seedDeletingSource()
                fixture.dao.seedPendingDeleteBatch(
                    batchId = "source-same-generation",
                    itemId = deleting.itemId,
                    sourceId = deleting.id,
                    expectedGeneration = deleting.taskGeneration,
                )

                val recovered = fixture.newDownloader().resumePendingDeleteBatches()
                val receipt = fixture.dao.deleteReceipts("source-same-generation").single()

                assertEquals("receipt=${receipt.outcome}/${receipt.detail}", 1, recovered)
                assertNull(fixture.dao.sourceById(deleting.id))
                assertEquals(
                    DownloadDeleteOutcome.SUCCEEDED.name,
                    receipt.outcome,
                )
            } finally {
                fixture.close()
            }
        }

    @Test
    fun `new instance supersedes source receipt at a newer generation with zero effects`() =
        runBlocking {
            val fixture = DownloaderImplRecoveryTest.Fixture()
            try {
                val deleting = fixture.seedDeletingSource()
                fixture.dao.seedPendingDeleteBatch(
                    batchId = "source-changed-generation",
                    itemId = deleting.itemId,
                    sourceId = deleting.id,
                    expectedGeneration = deleting.taskGeneration,
                )
                fixture.dao.putSource(
                    deleting.copy(
                        taskStatus = DownloadTaskStatus.RUNNING.name,
                        taskGeneration = deleting.taskGeneration + 1L,
                    )
                )
                fixture.dao.sourceEvents.clear()

                assertEquals(0, fixture.newDownloader().resumePendingDeleteBatches())

                val retained = requireNotNull(fixture.dao.sourceById(deleting.id))
                assertEquals(deleting.taskGeneration + 1L, retained.taskGeneration)
                assertTrue(fixture.dao.sourceEvents.isEmpty())
                assertEquals(
                    DownloadDeleteOutcome.SUPERSEDED.name,
                    fixture.dao.deleteReceipts("source-changed-generation").single().outcome,
                )
            } finally {
                fixture.close()
            }
        }

    private companion object {
        const val SOURCE_ID = "source-recovery"
        const val SERVER_ID = "server-a"
        val USER_ID: UUID = UUID(0L, 12L)
    }
}
