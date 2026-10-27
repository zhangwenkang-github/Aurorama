package com.zhangwenkang.cinefin.utils

import com.zhangwenkang.cinefin.models.DownloadIntentStatus
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadIntentRestartReconciliationTest {

    @Test
    fun `resolved sidecar without source is requeued or safely retryable after restart`() =
        runBlocking {
            val fixture = DownloaderImplRecoveryTest.Fixture()
            val itemId = UUID.fromString("82000000-0000-4000-8000-000000000001")
            val sourceId = "restart-resolved-without-source"
            try {
                fixture.prepareMusicOrigin(itemId, "restart-without-source", sourceId)
                fixture.seedResolvingIntentWithResolvedSidecar(
                    itemId = itemId,
                    sourceId = sourceId,
                    name = "restart-without-source",
                    sourceExists = false,
                )
                val restarted = fixture.newDownloader()

                restarted.recoverOnStartup()
                awaitRestartProgress(fixture, itemId, sourceId)

                val failed = fixture.intent(itemId)
                if (failed?.status == DownloadIntentStatus.FAILED.name) {
                    val (handle, error) =
                        restarted.retryTask(
                            DownloadTask(
                                itemId = itemId,
                                sourceId = intentSourceId(itemId),
                                name = failed.name,
                                path = "",
                                downloadId = null,
                                status = DownloadTaskStatus.FAILED,
                                failureReason = failed.failureReason.toFailureReason(),
                                downloadedBytes = 0L,
                                totalBytes = 0L,
                                updatedAt = failed.updatedAt,
                                mediaKind = DownloadMediaKind.MUSIC,
                                taskGeneration = failed.taskGeneration,
                            )
                        )
                    assertTrue("FAILED 状态必须可通过公开 retry 重试：$handle/$error", handle >= 0L)
                    awaitSourceCount(fixture, sourceId, 1)
                }

                assertNotEquals(
                    "重启恢复不得永久停在 RESOLVING",
                    DownloadIntentStatus.RESOLVING.name,
                    fixture.intent(itemId)?.status,
                )
                assertEquals(
                    "崩溃点 1 恢复后只能有一个 source：${fixture.sourceIdsSnapshot()}",
                    1,
                    fixture.sourceInsertCount(sourceId),
                )
            } finally {
                fixture.close()
            }
        }

    @Test
    fun `source inserted before source-created CAS is reconciled without duplicate source`() =
        runBlocking {
            val fixture = DownloaderImplRecoveryTest.Fixture()
            val itemId = UUID.fromString("82000000-0000-4000-8000-000000000002")
            val sourceId = "restart-source-before-terminal"
            try {
                fixture.prepareMusicOrigin(itemId, "restart-with-source", sourceId)
                fixture.seedResolvingIntentWithResolvedSidecar(
                    itemId = itemId,
                    sourceId = sourceId,
                    name = "restart-with-source",
                    sourceExists = true,
                )
                val restarted = fixture.newDownloader()

                restarted.recoverOnStartup()
                awaitCondition {
                    fixture.intent(itemId)?.let {
                        it.status != DownloadIntentStatus.RESOLVING.name
                    } != false
                }

                val reconciled = fixture.intent(itemId)
                assertTrue(
                    "崩溃点 2 必须补 CAS SOURCE_CREATED 或完成清理，实际=${reconciled?.status}",
                    reconciled == null ||
                        reconciled.status == DownloadIntentStatus.SOURCE_CREATED.name,
                )
                assertEquals(
                    "崩溃点 2 恢复不得重复插入 source：${fixture.sourceIdsSnapshot()}",
                    1,
                    fixture.sourceIdsSnapshot().count { it == sourceId },
                )
                val tasks = restarted.refreshDownloadTasks()
                assertEquals(1, tasks.count { it.sourceId == sourceId })
                assertTrue(
                    "恢复后不得继续把该 intent 投影为 PREPARING",
                    tasks.none {
                        it.sourceId.startsWith(INTENT_SOURCE_PREFIX) &&
                            it.itemId == itemId &&
                            DownloadControlPlaneRules.phase(
                                status = it.status,
                                downloadedBytes = it.downloadedBytes,
                                totalBytes = it.totalBytes,
                                failureReason = it.failureReason,
                                nextRetryAt = it.nextRetryAt,
                                nowMs = 0L,
                            ) == DownloadTaskPhase.PREPARING
                    },
                )
            } finally {
                fixture.close()
            }
        }

    private suspend fun awaitRestartProgress(
        fixture: DownloaderImplRecoveryTest.Fixture,
        itemId: UUID,
        sourceId: String,
    ) {
        awaitCondition {
            fixture.sourceInsertCount(sourceId) == 1 ||
                fixture.intent(itemId)?.status == DownloadIntentStatus.FAILED.name
        }
    }

    private suspend fun awaitSourceCount(
        fixture: DownloaderImplRecoveryTest.Fixture,
        sourceId: String,
        expected: Int,
    ) {
        awaitCondition { fixture.sourceInsertCount(sourceId) == expected }
    }

    private suspend fun awaitCondition(condition: () -> Boolean) {
        withTimeout(5_000L) { while (!condition()) delay(10L) }
    }
}
