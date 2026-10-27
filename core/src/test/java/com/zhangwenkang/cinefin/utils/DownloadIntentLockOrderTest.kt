package com.zhangwenkang.cinefin.utils

import com.zhangwenkang.cinefin.models.UiText
import java.util.UUID
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun `cancel snapshots old source before a same-item retry generation can advance`() =
        runBlocking {
            val fixture = DownloaderImplRecoveryTest.Fixture()
            val itemId = UUID.fromString("81000000-0000-4000-8000-000000000003")
            val sourceId = "snapshot-cancel-old"
            val replacementSourceId = sourceId
            var cancellation: kotlinx.coroutines.Deferred<Boolean>? = null
            var retry: kotlinx.coroutines.Deferred<Pair<Long, UiText?>>? = null
            try {
                fixture.prepareMusicOrigin(itemId, "snapshot-cancel", sourceId)
                fixture.repository.putOriginMediaSource(
                    itemId,
                    com.zhangwenkang.cinefin.repository.JellyfinOrigin(
                        "server-a",
                        UUID(0L, 12L),
                    ),
                    com.zhangwenkang.cinefin.models.FindroidSource(
                        id = "snapshot-cancel-new",
                        name = "snapshot-cancel",
                        type = com.zhangwenkang.cinefin.models.FindroidSourceType.REMOTE,
                        path = "https://media.test/movie",
                        size = "media-payload".toByteArray().size.toLong(),
                        mediaStreams = fixture.repository.mediaStreams,
                        container = "mp3",
                    ),
                )
                fixture.seedSourceCreatedIntentWithSource(
                    itemId = itemId,
                    sourceId = sourceId,
                    name = "snapshot-cancel",
                )
                fixture.armIntentSourceSnapshotGate(itemId, replacementSourceId)

                cancellation =
                    async(kotlinx.coroutines.Dispatchers.IO) {
                        fixture.downloader.cancelTaskById(intentSourceId(itemId))
                    }
                fixture.awaitIntentSourceSnapshot()
                val failedIntent = requireNotNull(fixture.intent(itemId))
                retry =
                    async(kotlinx.coroutines.Dispatchers.IO) {
                        fixture.downloader.retryTask(
                            DownloadTask(
                                itemId = itemId,
                                sourceId = intentSourceId(itemId),
                                name = failedIntent.name,
                                path = "",
                                downloadId = null,
                                status = DownloadTaskStatus.FAILED,
                                failureReason = DownloadFailureReason.CANCELLED,
                                downloadedBytes = 0L,
                                totalBytes = 0L,
                                updatedAt = failedIntent.updatedAt,
                                mediaKind = DownloadMediaKind.MUSIC,
                                taskGeneration = failedIntent.taskGeneration,
                            )
                        )
                    }

                val retryAdvancedBeforeSnapshot =
                    withTimeoutOrNull(300L) {
                        retry.await()
                        true
                    } ?: false
                assertFalse(
                    "old cancel 未完成 source snapshot 时，新 retry 代不得先推进",
                    retryAdvancedBeforeSnapshot,
                )
                fixture.releaseIntentSourceSnapshot()
                assertFalse(
                    "旧 cancel 遇到同 ID 新代时必须 fail-closed，不能取消新代",
                    withTimeout(5_000L) { requireNotNull(cancellation).await() },
                )
                withTimeout(5_000L) { requireNotNull(retry).await() }
                assertEquals(
                    "旧 cancel 快照只允许命中旧 source，新代 source 必须保留",
                    DownloadTaskStatus.PENDING,
                    fixture.dao.sourceById(replacementSourceId)?.taskStatus.toTaskStatus(),
                )
            } finally {
                fixture.releaseIntentSourceSnapshot()
                cancellation?.let { withTimeoutOrNull(5_000L) { it.await() } }
                retry?.let { withTimeoutOrNull(5_000L) { it.await() } }
                fixture.close()
            }
        }

    @Test
    fun `delete snapshots old source before a same-item retry generation can advance`() =
        runBlocking {
            val fixture = DownloaderImplRecoveryTest.Fixture()
            val itemId = UUID.fromString("81000000-0000-4000-8000-000000000004")
            val sourceId = "snapshot-delete-old"
            val replacementSourceId = sourceId
            var deletion: kotlinx.coroutines.Deferred<Boolean>? = null
            var retry: kotlinx.coroutines.Deferred<Pair<Long, UiText?>>? = null
            try {
                fixture.prepareMusicOrigin(itemId, "snapshot-delete", sourceId)
                fixture.seedSourceCreatedIntentWithSource(
                    itemId = itemId,
                    sourceId = sourceId,
                    name = "snapshot-delete",
                )
                fixture.armIntentSourceSnapshotGate(itemId, replacementSourceId)

                deletion =
                    async(kotlinx.coroutines.Dispatchers.IO) {
                        fixture.downloader.deleteTaskById(intentSourceId(itemId))
                    }
                fixture.awaitIntentSourceSnapshot()
                val failedIntent = requireNotNull(fixture.intent(itemId))
                retry =
                    async(kotlinx.coroutines.Dispatchers.IO) {
                        fixture.downloader.retryTask(
                            DownloadTask(
                                itemId = itemId,
                                sourceId = intentSourceId(itemId),
                                name = failedIntent.name,
                                path = "",
                                downloadId = null,
                                status = DownloadTaskStatus.FAILED,
                                failureReason = DownloadFailureReason.CANCELLED,
                                downloadedBytes = 0L,
                                totalBytes = 0L,
                                updatedAt = failedIntent.updatedAt,
                                mediaKind = DownloadMediaKind.MUSIC,
                                taskGeneration = failedIntent.taskGeneration,
                            )
                        )
                    }

                val retryAdvancedBeforeSnapshot =
                    withTimeoutOrNull(300L) {
                        retry.await()
                        true
                    } ?: false
                assertFalse(
                    "old delete 未完成 source snapshot 时，新 retry 代不得先推进",
                    retryAdvancedBeforeSnapshot,
                )
                fixture.releaseIntentSourceSnapshot()
                withTimeout(5_000L) { requireNotNull(deletion).await() }
                withTimeout(5_000L) { requireNotNull(retry).await() }
                assertEquals(
                    "旧 delete 快照只允许清理旧 source，新代 source 必须保留",
                    DownloadTaskStatus.PENDING,
                    fixture.dao.sourceById(replacementSourceId)?.taskStatus.toTaskStatus(),
                )
            } finally {
                fixture.releaseIntentSourceSnapshot()
                deletion?.let { withTimeoutOrNull(5_000L) { it.await() } }
                retry?.let { withTimeoutOrNull(5_000L) { it.await() } }
                fixture.close()
            }
        }
}
