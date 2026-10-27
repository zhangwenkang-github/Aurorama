package com.zhangwenkang.cinefin.utils

import java.util.UUID
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadIntentLockOrderTest {

    @Test
    fun `resolver holding source operation cannot deadlock with intent delete`() = runBlocking {
        val fixture = DownloaderImplRecoveryTest.Fixture()
        val itemId = UUID.fromString("81000000-0000-4000-8000-000000000001")
        val sourceId = "lock-source-resolver-first"
        var deletion: kotlinx.coroutines.Deferred<Boolean>? = null
        try {
            fixture.prepareMusicOrigin(itemId, "resolver-first", sourceId)
            fixture.seedReceivedIntentWithPendingSidecar(itemId, sourceId, "resolver-first")
            fixture.armIntentSourceReadGate(sourceId)
            fixture.armIntentDeletionGate()

            fixture.downloader.recoverOnStartup()
            fixture.awaitIntentSourceRead()

            deletion =
                async(kotlinx.coroutines.Dispatchers.IO) {
                    fixture.downloader.deleteTaskById(intentSourceId(itemId))
                }
            fixture.awaitIntentDeletion()
            fixture.releaseIntentSourceRead()
            fixture.releaseIntentDeletion()

            val deleted = withTimeoutOrNull(3_000L) { deletion?.await() }
            assertNotNull("resolver 持 source-operation、delete 持 item lock 时必须结束", deleted)
            assertTrue("intent delete 必须确认成功", deleted == true)
            assertTrue(
                "同一逻辑意图最多创建一个 source：${fixture.sourceIdsSnapshot()}",
                fixture.sourceInsertCount(sourceId) <= 1,
            )
            assertTrue(
                "delete 必须推进 generation：${fixture.lastIntentDeletionGeneration()}",
                (fixture.lastIntentDeletionGeneration() ?: 0L) > 4L,
            )
            assertEquals(
                "旧 generation 在 deletion CAS 后不得再插入 source",
                0,
                fixture.sourceInsertCount(sourceId),
            )
        } finally {
            fixture.releaseIntentSourceRead()
            fixture.releaseIntentDeletion()
            deletion?.cancel()
            fixture.close()
        }
    }

    @Test
    fun `delete holding item lock cannot deadlock when resolver reaches commit`() = runBlocking {
        val fixture = DownloaderImplRecoveryTest.Fixture()
        val itemId = UUID.fromString("81000000-0000-4000-8000-000000000002")
        val sourceId = "lock-source-delete-first"
        var deletion: kotlinx.coroutines.Deferred<Boolean>? = null
        try {
            fixture.prepareMusicOrigin(itemId, "delete-first", sourceId)
            fixture.seedReceivedIntentWithPendingSidecar(itemId, sourceId, "delete-first")
            fixture.armIntentDeletionGate()

            deletion =
                async(kotlinx.coroutines.Dispatchers.IO) {
                    fixture.downloader.deleteTaskById(intentSourceId(itemId))
                }
            fixture.awaitIntentDeletion()
            fixture.observeIntentSourceRead(sourceId)
            fixture.downloader.recoverOnStartup()
            fixture.awaitIntentSourceRead()
            fixture.releaseIntentDeletion()

            val deleted = withTimeoutOrNull(3_000L) { deletion?.await() }
            assertNotNull("delete 持 item lock、resolver 已持 source-operation 时必须结束", deleted)
            assertTrue("intent delete 必须确认成功", deleted == true)
            assertTrue(
                "同一逻辑意图最多创建一个 source：${fixture.sourceIdsSnapshot()}",
                fixture.sourceInsertCount(sourceId) <= 1,
            )
            assertTrue(
                "delete 必须推进 generation：${fixture.lastIntentDeletionGeneration()}",
                (fixture.lastIntentDeletionGeneration() ?: 0L) > 4L,
            )
            assertEquals(
                "delete 先推进代次后 resolver 不得插入 source",
                0,
                fixture.sourceInsertCount(sourceId),
            )
        } finally {
            fixture.releaseIntentSourceRead()
            fixture.releaseIntentDeletion()
            deletion?.cancel()
            fixture.close()
        }
    }
}
