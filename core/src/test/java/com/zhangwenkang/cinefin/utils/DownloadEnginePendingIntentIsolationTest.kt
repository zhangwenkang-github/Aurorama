package com.zhangwenkang.cinefin.utils

import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadEnginePendingIntentIsolationTest {

    @Test
    fun `intent only refresh never starts engine and keeps intent feedback moving`() = runBlocking {
        val fixture = DownloaderImplRecoveryTest.Fixture()
        try {
            fixture.dao.seedDownloadIntent(INTENT_ONLY_ITEM_ID)

            repeat(2) { fixture.downloader.refreshDownloadTasks() }
            delay(10_000L)

            val requests = fixture.workGateway.uniqueRequests
            val engineRequests = requests.filter {
                it.third.workerClassContains("DownloadEngineWorker")
            }
            assertEquals(
                "intent-only refresh 两次不得 enqueue Engine：${requests.map { it.first }}",
                0,
                engineRequests.size,
            )
            assertTrue(
                "intent-only refresh 必须把解析推进交给 IntentWorker",
                requests.any { it.third.workerClassContains("DownloadIntentWorker") },
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `failed intent is not retried by passive refresh`() = runBlocking {
        val fixture = DownloaderImplRecoveryTest.Fixture()
        try {
            fixture.dao.seedDownloadIntent(FAILED_ITEM_ID, status = "FAILED")
            fixture.workGateway.uniqueRequests.clear()

            fixture.downloader.refreshDownloadTasks()

            val requests = fixture.workGateway.uniqueRequests
            assertEquals(
                "失败 intent 只能由显式重试推进",
                0,
                requests.count { it.third.workerClassContains("DownloadIntentWorker") },
            )
            assertEquals(
                "失败 intent 不得启动 Engine",
                0,
                requests.count { it.third.workerClassContains("DownloadEngineWorker") },
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `runnable pending source still starts engine immediately`() = runBlocking {
        val fixture = DownloaderImplRecoveryTest.Fixture()
        try {
            fixture.workGateway.uniqueRequests.clear()
            fixture.seedPendingSource(itemId = SOURCE_ITEM_ID, sourceId = "runnable-source")

            fixture.downloader.refreshDownloadTasks()

            val requests = fixture.workGateway.uniqueRequests
            assertEquals(
                "真实 PENDING source 必须立即调度 Engine：${requests.map { it.first }}",
                1,
                requests.count { it.third.workerClassContains("DownloadEngineWorker") },
            )
            assertEquals(
                "真实 source 不应绕行 intent worker",
                0,
                requests.count { it.third.workerClassContains("DownloadIntentWorker") },
            )
        } finally {
            fixture.close()
        }
    }

    private fun androidx.work.OneTimeWorkRequest.workerClassContains(name: String): Boolean =
        workSpec.workerClassName.substringAfterLast('.').contains(name)

    private companion object {
        val INTENT_ONLY_ITEM_ID: UUID = UUID(0L, 801L)
        val SOURCE_ITEM_ID: UUID = UUID(0L, 802L)
        val FAILED_ITEM_ID: UUID = UUID(0L, 803L)
    }
}
