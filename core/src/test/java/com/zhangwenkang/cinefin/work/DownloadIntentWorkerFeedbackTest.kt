package com.zhangwenkang.cinefin.work

import androidx.work.ListenableWorker
import com.zhangwenkang.cinefin.utils.DownloaderImplRecoveryTest
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadIntentWorkerFeedbackTest {

    @Test
    fun `worker success only ends intent pass and never writes completion or starts transfer`() =
        runBlocking {
            val fixture = DownloaderImplRecoveryTest.Fixture()
            try {
                fixture.dao.seedDownloadIntent(ITEM_ID)

                val result = runDownloadIntentWorkerAttempt { fixture.downloader.runIntentQueue() }

                assertEquals(
                    ListenableWorker.Result.success().toString(),
                    result.toString(),
                )
                assertNotEquals(
                    "IntentWorker success 不得把 intent 写成 COMPLETED",
                    "COMPLETED",
                    fixture.dao.downloadIntent(ITEM_ID)?.status,
                )
                assertTrue(
                    "IntentWorker success 不得触发成功传输或成功通知路径",
                    fixture.transfer.startedSourceIds().isEmpty(),
                )
                assertTrue(
                    "IntentWorker 不得入队前台 Engine",
                    fixture.workGateway.uniqueRequests.none {
                        it.third.workSpec.workerClassName.endsWith("DownloadEngineWorker")
                    },
                )
            } finally {
                fixture.close()
            }
        }

    @Test
    fun `bounded feedback requests another intent round only when snapshot has more work`() =
        runBlocking {
            val fixture = DownloaderImplRecoveryTest.Fixture()
            try {
                repeat(65) { index -> fixture.dao.seedDownloadIntent(UUID(0L, 900L + index)) }

                val outcome = fixture.downloader.runIntentQueue()

                assertEquals(BOUNDED_BATCH_SIZE, outcome.processedIntents)
                assertTrue(outcome.hasPendingIntents)
                assertEquals(
                    "仍有 intent 时必须补一轮 IntentWorker",
                    1,
                    fixture.workGateway.uniqueRequests.count {
                        it.third.workSpec.workerClassName.endsWith("DownloadIntentWorker")
                    },
                )
            } finally {
                fixture.close()
            }
        }

    @Test
    fun `acceptance while resolver is blocked registers intent worker but no engine`() =
        runBlocking {
            val fixture = DownloaderImplRecoveryTest.Fixture()
            try {
                fixture.prepareMusicOrigin(
                    itemId = ITEM_ID,
                    name = "accepted",
                    sourceId = "accepted-source",
                )
                fixture.repository.blockItemLookup(ITEM_ID)
                fixture.workGateway.uniqueRequests.clear()

                fixture.downloader.receiveMusicBatch(
                    listOf(fixture.completeMusicRequest(ITEM_ID, "accepted"))
                )

                val requests = fixture.workGateway.uniqueRequests.toList()
                assertTrue(fixture.repository.awaitItemLookup(ITEM_ID))
                fixture.repository.releaseAllItemLookups()
                assertTrue(fixture.dao.awaitInsertedSourceCount(1))
                assertEquals(
                    "已受理但仍解析时只能推进 IntentWorker",
                    1,
                    requests.count { it.third.isIntentWorker() },
                )
                assertEquals(
                    "已受理但没有 runnable source 时不得启动 Engine",
                    0,
                    requests.count { it.third.isEngineWorker() },
                )
            } finally {
                fixture.close()
            }
        }

    @Test
    fun `startup recovery and intent retry register only intent worker`() = runBlocking {
        val fixture = DownloaderImplRecoveryTest.Fixture()
        try {
            fixture.dao.seedDownloadIntent(ITEM_ID)
            fixture.workGateway.uniqueRequests.clear()

            fixture.downloader.recoverOnStartup()
            assertTrue(fixture.awaitIntentStatus(ITEM_ID, "FAILED"))

            assertTrue(fixture.workGateway.uniqueRequests.any { it.third.isIntentWorker() })
            assertEquals(
                0,
                fixture.workGateway.uniqueRequests.count { it.third.isEngineWorker() },
            )

            val retryItemId = UUID(0L, 812L)
            fixture.dao.seedDownloadIntent(retryItemId, status = "FAILED")
            fixture.workGateway.uniqueRequests.clear()

            fixture.retryIntent(retryItemId)
            assertTrue(fixture.awaitIntentStatus(retryItemId, "FAILED"))

            assertTrue(fixture.workGateway.uniqueRequests.any { it.third.isIntentWorker() })
            assertEquals(
                0,
                fixture.workGateway.uniqueRequests.count { it.third.isEngineWorker() },
            )
        } finally {
            fixture.close()
        }
    }

    private fun androidx.work.OneTimeWorkRequest.isIntentWorker(): Boolean =
        workSpec.workerClassName.endsWith("DownloadIntentWorker")

    private fun androidx.work.OneTimeWorkRequest.isEngineWorker(): Boolean =
        workSpec.workerClassName.endsWith("DownloadEngineWorker")

    private companion object {
        val ITEM_ID: UUID = UUID(0L, 811L)
        val BOUNDED_BATCH_SIZE = 64
    }
}
