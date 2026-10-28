package com.zhangwenkang.cinefin.utils

import com.zhangwenkang.cinefin.models.DownloadIntentStatus
import com.zhangwenkang.cinefin.repository.JellyfinOrigin
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadIntentWorkerFeedbackTest {

    @Test
    fun `second intent round consumes item after first 64 sidecars fail`() = runBlocking {
        val fixture =
            DownloaderImplRecoveryTest.Fixture(resolutionDispatcher = Dispatchers.Unconfined)
        try {
            val itemIds = (0 until 65).map { index -> UUID(0L, 20_000L + index) }
            itemIds.forEachIndexed { index, itemId ->
                val sourceId = "bounded-source-$index"
                fixture.prepareMusicOrigin(itemId, "bounded-$index", sourceId)
                fixture.seedReceivedIntentWithPendingSidecar(
                    itemId = itemId,
                    sourceId = sourceId,
                    name = "bounded-$index",
                )
            }
            val origin = JellyfinOrigin("server-a", UUID(0L, 12L))
            repeat(64) { fixture.repository.failNextOriginItem(origin) }

            val first = fixture.downloader.runIntentQueue()

            assertEquals(64, first.processedIntents)
            assertTrue(first.hasPendingIntents)
            assertTrue(fixture.repository.awaitOriginItemLookup(64))
            assertEquals(
                DownloadIntentStatus.RECEIVED.name,
                fixture.dao.downloadIntent(itemIds.last())?.status,
            )

            val second = fixture.downloader.runIntentQueue()

            assertEquals("第二轮必须只消费剩余第 65 项", 1, second.processedIntents)
            assertFalse(second.hasPendingIntents)
            assertTrue(
                "第 65 项必须真实进入 resolver，而不是只续登记 Worker",
                fixture.repository.awaitOriginItemLookup(65),
            )
            assertTrue(
                "第 65 项必须推进到 source",
                awaitSource(fixture, "bounded-source-64"),
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `explicit retry makes a failed sidecar intent eligible again`() = runBlocking {
        val fixture =
            DownloaderImplRecoveryTest.Fixture(resolutionDispatcher = Dispatchers.Unconfined)
        try {
            val itemId = UUID(0L, 21_000L)
            val sourceId = "retry-sidecar-source"
            fixture.prepareMusicOrigin(itemId, "retry-sidecar", sourceId)
            fixture.seedReceivedIntentWithPendingSidecar(
                itemId = itemId,
                sourceId = sourceId,
                name = "retry-sidecar",
            )
            fixture.repository.failNextOriginItem(JellyfinOrigin("server-a", UUID(0L, 12L)))

            assertEquals(1, fixture.downloader.runIntentQueue().processedIntents)
            assertEquals(
                DownloadIntentStatus.FAILED.name,
                fixture.dao.downloadIntent(itemId)?.status,
            )
            val failed = checkNotNull(fixture.dao.downloadIntent(itemId))

            assertEquals(
                1,
                fixture.dao.proxy.retryDownloadIntent(
                    itemId = itemId,
                    expectedGeneration = failed.taskGeneration,
                    updatedAt = failed.updatedAt + 1L,
                ),
            )

            assertTrue(
                "显式 retry 后必须再次进入 resolver",
                fixture.downloader.runIntentQueue().processedIntents == 1,
            )
            assertTrue("显式 retry 后必须推进到 source", awaitSource(fixture, sourceId))
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `unassignable sidecars do not occupy the bounded round before eligible tail`() =
        runBlocking {
            val fixture =
                DownloaderImplRecoveryTest.Fixture(resolutionDispatcher = Dispatchers.Unconfined)
            try {
                val itemIds = (0 until 65).map { index -> UUID(0L, 22_000L + index) }
                itemIds.forEachIndexed { index, itemId ->
                    val sourceId = "superseded-source-$index"
                    fixture.prepareMusicOrigin(itemId, "superseded-$index", sourceId)
                    val intent =
                        fixture.seedReceivedIntentWithPendingSidecar(
                            itemId = itemId,
                            sourceId = sourceId,
                            name = "superseded-$index",
                        )
                    if (index < 64) {
                        fixture.dao.putDownloadIntent(
                            intent.copy(status = DownloadIntentStatus.SOURCE_CREATED.name)
                        )
                    }
                }

                val outcome = fixture.downloader.runIntentQueue()

                assertEquals("不可接管项不得占据 64 项 head", 1, outcome.processedIntents)
                assertFalse(outcome.hasPendingIntents)
                assertTrue(
                    "合格尾项必须在首轮直接进入 resolver",
                    fixture.repository.awaitOriginItemLookup(1),
                )
                assertTrue(
                    "合格尾项必须推进到 source",
                    awaitSource(fixture, "superseded-source-64"),
                )
            } finally {
                fixture.close()
            }
        }

    private suspend fun awaitSource(
        fixture: DownloaderImplRecoveryTest.Fixture,
        sourceId: String,
    ): Boolean =
        withTimeoutOrNull(5_000L) {
            while (sourceId !in fixture.sourceIdsSnapshot()) delay(10L)
            true
        } ?: false
}
