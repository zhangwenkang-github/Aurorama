package com.zhangwenkang.cinefin.utils

import com.zhangwenkang.cinefin.models.DownloadIntentStatus
import java.io.File
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
    fun `ordinary cancel wins after intent delete claim before CAS`() = runBlocking {
        val fixture = DownloaderImplRecoveryTest.Fixture()
        try {
            val itemId = UUID(0L, 899L)
            val intent =
                fixture.seedReceivedIntentWithPendingSidecar(
                    itemId = itemId,
                    sourceId = intentSourceId(itemId),
                    name = "intent-cancel-wins",
                )
            val child = fixture.seedPendingSource(itemId, "cancel-wins-child")
            val childFile =
                File(child.path).apply {
                    parentFile?.mkdirs()
                    writeText("child payload")
                }
            assertTrue(
                fixture.putMusicSidecar(
                    itemId = itemId,
                    sourceId = child.id,
                    serverId = SERVER_ID,
                    userId = USER_ID.toString(),
                    displayName = "cancel-wins-child",
                )
            )
            fixture.dao.armIntentDeletionInterruptionBeforeCommit()

            val deletion =
                async(Dispatchers.IO) {
                    fixture.downloader.deleteDownloads(
                        listOf(
                            DownloadDeleteRequest(
                                itemId = itemId,
                                sourceId = intentSourceId(itemId),
                                mediaKind = DownloadMediaKind.MUSIC,
                                serverId = SERVER_ID,
                                userId = USER_ID.toString(),
                            )
                        )
                    )
                }
            withTimeout(5_000L) { fixture.dao.awaitIntentDeletion() }
            assertTrue(
                runCatching { deletion.await() }.exceptionOrNull()
                    is kotlinx.coroutines.CancellationException
            )

            val pending = fixture.dao.pendingDeleteReceipts().single()
            assertEquals(intent.taskGeneration + 1L, pending.verifiedGeneration)
            assertEquals(
                intentDeletionOwnershipDetail(
                    intent.taskGeneration + 1L,
                    downloadDeleteOwnershipToken(
                        batchId = pending.batchId,
                        canonicalKey = pending.canonicalKey,
                    ),
                ),
                pending.detail,
            )

            fixture.cancelIntent(itemId)
            val cancelled = requireNotNull(fixture.dao.downloadIntent(itemId))
            assertEquals(intent.taskGeneration + 1L, cancelled.taskGeneration)
            assertEquals(DownloadIntentStatus.FAILED.name, cancelled.status)
            assertEquals("CANCELLED", cancelled.failureReason)
            assertNull(cancelled.failureDetail)
            assertEquals(
                pending,
                fixture.dao.pendingDeleteReceipts().single(),
            )

            val sourceBefore = requireNotNull(fixture.dao.sourceById(child.id))
            val sourceIdsBefore = fixture.dao.sourceIds()
            val sidecarBefore = fixture.downloader.mediaSidecar().allRecords()
            val fileBefore = childFile.readBytes()
            val batchId = pending.batchId

            assertEquals(0, fixture.newDownloader().recoverOnStartup())

            assertEquals(cancelled, fixture.dao.downloadIntent(itemId))
            assertEquals(sourceBefore, fixture.dao.sourceById(child.id))
            assertEquals(sourceIdsBefore, fixture.dao.sourceIds())
            assertEquals(sidecarBefore, fixture.downloader.mediaSidecar().allRecords())
            assertTrue(childFile.isFile)
            assertTrue(fileBefore.contentEquals(childFile.readBytes()))
            val receipt = fixture.dao.deleteReceipts(batchId).single()
            assertEquals(DownloadDeleteOutcome.SUPERSEDED.name, receipt.outcome)
            assertTrue(receipt.detail?.contains("w90-") != true)
        } finally {
            fixture.close()
        }
    }

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
                        "source 删除栅栏前必须持久化该 delete 拥有的目标 generation",
                        source.taskGeneration + 1L,
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
                assertEquals(source.taskGeneration + 1L, terminal.verifiedGeneration)
            } finally {
                fixture.close()
            }
        }

    @Test
    fun `process interruption after source fence resumes its owned generation`() = runBlocking {
        val fixture = DownloaderImplRecoveryTest.Fixture()
        try {
            val movie = fixture.movie()
            val (_, downloadError) = fixture.downloader.downloadItem(movie, SOURCE_ID, 0)
            assertNull(downloadError)
            val original = requireNotNull(fixture.dao.source)
            val request =
                DownloadDeleteRequest(
                    itemId = original.itemId,
                    sourceId = original.id,
                    mediaKind = DownloadMediaKind.VIDEO,
                    serverId = SERVER_ID,
                    userId = USER_ID.toString(),
                    expectedGeneration = original.taskGeneration,
                )
            fixture.dao.armSourceDeletionInterruption()

            val interrupted =
                async(Dispatchers.IO) {
                    runCatching { fixture.downloader.deleteDownloads(listOf(request)) }
                }
            try {
                withTimeout(5_000L) { fixture.dao.sourceDeleteStarted.await() }
                val fenced = requireNotNull(fixture.dao.source)
                assertEquals(DownloadTaskStatus.DELETING.name, fenced.taskStatus)
                assertEquals(original.taskGeneration + 1L, fenced.taskGeneration)
            } finally {
                fixture.dao.releaseSourceDelete.complete(Unit)
            }
            val interruption = interrupted.await().exceptionOrNull()
            assertTrue(interruption is kotlinx.coroutines.CancellationException)
            val pending = fixture.dao.pendingDeleteReceipts().single()
            assertEquals(DownloadDeleteOutcome.PENDING.name, pending.outcome)
            val batchId = pending.batchId

            val recovered = fixture.newDownloader().recoverOnStartup()
            val receipt = fixture.dao.deleteReceipts(batchId).single()

            assertEquals(
                "同一删除拥有的 ${original.taskGeneration + 1L} 代必须续删到真实终态；" +
                    "receipt=${receipt.outcome}/${receipt.detail}",
                DownloadDeleteOutcome.SUCCEEDED.name,
                receipt.outcome,
            )
            assertEquals(original.taskGeneration + 1L, receipt.verifiedGeneration)
            assertNull(fixture.dao.sourceById(SOURCE_ID))
            assertEquals(1, recovered)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `owned receipt does not absorb a later deleting generation`() = runBlocking {
        val fixture = DownloaderImplRecoveryTest.Fixture()
        try {
            val movie = fixture.movie()
            val (_, downloadError) = fixture.downloader.downloadItem(movie, SOURCE_ID, 0)
            assertNull(downloadError)
            val original = requireNotNull(fixture.dao.source)
            val request =
                DownloadDeleteRequest(
                    itemId = original.itemId,
                    sourceId = original.id,
                    mediaKind = DownloadMediaKind.VIDEO,
                    serverId = SERVER_ID,
                    userId = USER_ID.toString(),
                    expectedGeneration = original.taskGeneration,
                )
            fixture.dao.armSourceDeletionInterruption()
            val interrupted =
                async(Dispatchers.IO) {
                    runCatching { fixture.downloader.deleteDownloads(listOf(request)) }
                }
            try {
                withTimeout(5_000L) { fixture.dao.sourceDeleteStarted.await() }
            } finally {
                fixture.dao.releaseSourceDelete.complete(Unit)
            }
            assertTrue(
                interrupted.await().exceptionOrNull() is kotlinx.coroutines.CancellationException
            )
            val owned = requireNotNull(fixture.dao.source)
            assertEquals(original.taskGeneration + 1L, owned.taskGeneration)
            assertEquals(DownloadTaskStatus.DELETING.name, owned.taskStatus)
            val batchId = fixture.dao.pendingDeleteReceipts().single().batchId
            fixture.dao.putSource(owned.copy(taskGeneration = owned.taskGeneration + 1L))
            fixture.dao.sourceEvents.clear()

            assertEquals(0, fixture.newDownloader().resumePendingDeleteBatches())

            val retained = requireNotNull(fixture.dao.sourceById(SOURCE_ID))
            assertEquals(owned.taskGeneration + 1L, retained.taskGeneration)
            assertEquals(DownloadTaskStatus.DELETING.name, retained.taskStatus)
            assertTrue("后续删除代次不得被旧 receipt 清理", fixture.dao.sourceEvents.isEmpty())
            assertEquals(
                DownloadDeleteOutcome.SUPERSEDED.name,
                fixture.dao.deleteReceipts(batchId).single().outcome,
            )
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
    fun `process interruption after intent delete CAS resumes its owned generation`() =
        runBlocking {
            val fixture = DownloaderImplRecoveryTest.Fixture()
            try {
                val itemId = UUID(0L, 903L)
                val intent =
                    fixture.seedReceivedIntentWithPendingSidecar(
                        itemId = itemId,
                        sourceId = intentSourceId(itemId),
                        name = "intent-post-cas",
                    )
                fixture.dao.armIntentDeletionInterruptionAfterCommit()

                val deletion =
                    async(Dispatchers.IO) {
                        fixture.downloader.deleteDownloads(
                            listOf(
                                DownloadDeleteRequest(
                                    itemId = itemId,
                                    sourceId = intentSourceId(itemId),
                                    mediaKind = DownloadMediaKind.MUSIC,
                                    serverId = SERVER_ID,
                                    userId = USER_ID.toString(),
                                )
                            )
                        )
                    }
                withTimeout(5_000L) { fixture.dao.awaitIntentDeletionCommitted() }
                val fenced = requireNotNull(fixture.dao.downloadIntent(itemId))
                assertEquals(intent.taskGeneration + 1L, fenced.taskGeneration)
                assertEquals(DownloadIntentStatus.FAILED.name, fenced.status)
                assertTrue(
                    runCatching { deletion.await() }.exceptionOrNull()
                        is kotlinx.coroutines.CancellationException
                )

                val pending = fixture.dao.pendingDeleteReceipts().single()
                assertEquals(DownloadDeleteOutcome.PENDING.name, pending.outcome)
                assertEquals(
                    intentDeletionOwnershipDetail(
                        intent.taskGeneration + 1L,
                        downloadDeleteOwnershipToken(
                            batchId = pending.batchId,
                            canonicalKey = pending.canonicalKey,
                        ),
                    ),
                    pending.detail,
                )
                assertEquals(
                    intentDeletionCasOwnershipDetail(
                        intent.taskGeneration + 1L,
                        downloadDeleteOwnershipToken(
                            batchId = pending.batchId,
                            canonicalKey = pending.canonicalKey,
                        ),
                    ),
                    fenced.failureDetail,
                )
                val projected =
                    fixture.newDownloader().refreshDownloadTasks().single {
                        it.sourceId == intentSourceId(itemId)
                    }
                assertEquals(DownloadFailureReason.CANCELLED, projected.failureReason)
                assertNull(projected.failureDetail)
                val batchId = pending.batchId

                val recovered = fixture.newDownloader().recoverOnStartup()
                val receipt = fixture.dao.deleteReceipts(batchId).single()

                assertEquals(
                    "同一 intent 删除拥有的 ${intent.taskGeneration + 1L} 代必须续删到真实终态；" +
                        "receipt=${receipt.outcome}/${receipt.detail}",
                    DownloadDeleteOutcome.SUCCEEDED.name,
                    receipt.outcome,
                )
                assertEquals(intent.taskGeneration + 1L, receipt.verifiedGeneration)
                assertNull(receipt.detail)
                assertNull(fixture.dao.downloadIntent(itemId))
                assertEquals(1, recovered)
            } finally {
                fixture.close()
            }
        }

    @Test
    fun `process interruption after intent claim retries the owned CAS`() = runBlocking {
        val fixture = DownloaderImplRecoveryTest.Fixture()
        try {
            val itemId = UUID(0L, 905L)
            val intent =
                fixture.seedReceivedIntentWithPendingSidecar(
                    itemId = itemId,
                    sourceId = intentSourceId(itemId),
                    name = "intent-pre-cas",
                )
            fixture.dao.armIntentDeletionInterruptionBeforeCommit()

            val deletion =
                async(Dispatchers.IO) {
                    fixture.downloader.deleteDownloads(
                        listOf(
                            DownloadDeleteRequest(
                                itemId = itemId,
                                sourceId = intentSourceId(itemId),
                                mediaKind = DownloadMediaKind.MUSIC,
                                serverId = SERVER_ID,
                                userId = USER_ID.toString(),
                            )
                        )
                    )
                }
            withTimeout(5_000L) { fixture.dao.awaitIntentDeletion() }
            assertTrue(
                runCatching { deletion.await() }.exceptionOrNull()
                    is kotlinx.coroutines.CancellationException
            )

            assertEquals(intent, fixture.dao.downloadIntent(itemId))
            val pending = fixture.dao.pendingDeleteReceipts().single()
            assertEquals(intent.taskGeneration + 1L, pending.verifiedGeneration)
            assertEquals(
                intentDeletionOwnershipDetail(
                    intent.taskGeneration + 1L,
                    downloadDeleteOwnershipToken(
                        batchId = pending.batchId,
                        canonicalKey = pending.canonicalKey,
                    ),
                ),
                pending.detail,
            )
            val batchId = pending.batchId

            val recovered = fixture.newDownloader().recoverOnStartup()
            val receipt = fixture.dao.deleteReceipts(batchId).single()

            assertEquals(DownloadDeleteOutcome.SUCCEEDED.name, receipt.outcome)
            assertEquals(intent.taskGeneration + 1L, receipt.verifiedGeneration)
            assertNull(receipt.detail)
            assertNull(fixture.dao.downloadIntent(itemId))
            assertEquals(1, recovered)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `owned intent receipt does not absorb a later generation`() = runBlocking {
        val fixture = DownloaderImplRecoveryTest.Fixture()
        try {
            val itemId = UUID(0L, 904L)
            val intent =
                fixture.seedReceivedIntentWithPendingSidecar(
                    itemId = itemId,
                    sourceId = intentSourceId(itemId),
                    name = "intent-later-generation",
                )
            fixture.dao.armIntentDeletionInterruptionAfterCommit()

            val deletion =
                async(Dispatchers.IO) {
                    fixture.downloader.deleteDownloads(
                        listOf(
                            DownloadDeleteRequest(
                                itemId = itemId,
                                sourceId = intentSourceId(itemId),
                                mediaKind = DownloadMediaKind.MUSIC,
                                serverId = SERVER_ID,
                                userId = USER_ID.toString(),
                            )
                        )
                    )
                }
            withTimeout(5_000L) { fixture.dao.awaitIntentDeletionCommitted() }
            assertTrue(
                runCatching { deletion.await() }.exceptionOrNull()
                    is kotlinx.coroutines.CancellationException
            )

            val owned = requireNotNull(fixture.dao.downloadIntent(itemId))
            val later =
                owned.copy(
                    status = DownloadIntentStatus.RECEIVED.name,
                    failureReason = null,
                    failureDetail = null,
                    taskGeneration = owned.taskGeneration + 1L,
                    updatedAt = owned.updatedAt + 1L,
                )
            fixture.dao.putDownloadIntent(later)
            val sidecarBefore = fixture.downloader.mediaSidecar().allRecords()
            val sourcesBefore = fixture.dao.sourceIds()
            val batchId = fixture.dao.pendingDeleteReceipts().single().batchId

            assertEquals(0, fixture.newDownloader().resumePendingDeleteBatches())

            assertEquals(later, fixture.dao.downloadIntent(itemId))
            assertEquals(sidecarBefore, fixture.downloader.mediaSidecar().allRecords())
            assertEquals(sourcesBefore, fixture.dao.sourceIds())
            assertEquals(
                DownloadDeleteOutcome.SUPERSEDED.name,
                fixture.dao.deleteReceipts(batchId).single().outcome,
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `owned intent receipt does not absorb another action at the same generation`() =
        runBlocking {
            val fixture = DownloaderImplRecoveryTest.Fixture()
            try {
                val itemId = UUID(0L, 906L)
                fixture.seedReceivedIntentWithPendingSidecar(
                    itemId = itemId,
                    sourceId = intentSourceId(itemId),
                    name = "intent-other-owner-same-generation",
                )
                fixture.dao.armIntentDeletionInterruptionAfterCommit()

                val deletion =
                    async(Dispatchers.IO) {
                        fixture.downloader.deleteDownloads(
                            listOf(
                                DownloadDeleteRequest(
                                    itemId = itemId,
                                    sourceId = intentSourceId(itemId),
                                    mediaKind = DownloadMediaKind.MUSIC,
                                    serverId = SERVER_ID,
                                    userId = USER_ID.toString(),
                                )
                            )
                        )
                    }
                withTimeout(5_000L) { fixture.dao.awaitIntentDeletionCommitted() }
                assertTrue(
                    runCatching { deletion.await() }.exceptionOrNull()
                        is kotlinx.coroutines.CancellationException
                )

                val owned = requireNotNull(fixture.dao.downloadIntent(itemId))
                val otherOwner =
                    owned.copy(
                        status = DownloadIntentStatus.RECEIVED.name,
                        failureReason = null,
                        failureDetail = null,
                        updatedAt = owned.updatedAt + 1L,
                    )
                fixture.dao.putDownloadIntent(otherOwner)
                val sidecarBefore = fixture.downloader.mediaSidecar().allRecords()
                val sourcesBefore = fixture.dao.sourceIds()
                val batchId = fixture.dao.pendingDeleteReceipts().single().batchId

                assertEquals(0, fixture.newDownloader().resumePendingDeleteBatches())

                assertEquals(otherOwner, fixture.dao.downloadIntent(itemId))
                assertEquals(sidecarBefore, fixture.downloader.mediaSidecar().allRecords())
                assertEquals(sourcesBefore, fixture.dao.sourceIds())
                assertEquals(
                    DownloadDeleteOutcome.SUPERSEDED.name,
                    fixture.dao.deleteReceipts(batchId).single().outcome,
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

    @Test
    fun `source ownership marker cannot claim an intent deletion`() = runBlocking {
        val fixture = DownloaderImplRecoveryTest.Fixture()
        try {
            val itemId = UUID(0L, 907L)
            val intent =
                fixture.seedReceivedIntentWithPendingSidecar(
                    itemId = itemId,
                    sourceId = intentSourceId(itemId),
                    name = "source-marker-isolation",
                )
            val expected = intent.taskGeneration + 1L
            fixture.dao.seedPendingDeleteBatch(
                batchId = "intent-source-marker",
                itemId = itemId,
                sourceId = intentSourceId(itemId),
                expectedGeneration = expected,
                mediaKind = DownloadMediaKind.MUSIC,
                detail = sourceDeletionOwnershipDetail(expected),
            )
            val sidecarBefore = fixture.downloader.mediaSidecar().allRecords()

            assertEquals(0, fixture.newDownloader().resumePendingDeleteBatches())

            assertEquals(intent, fixture.dao.downloadIntent(itemId))
            assertEquals(sidecarBefore, fixture.downloader.mediaSidecar().allRecords())
            assertTrue(fixture.dao.sourceIds().isEmpty())
            val receipt = fixture.dao.deleteReceipts("intent-source-marker").single()
            assertEquals(DownloadDeleteOutcome.SUPERSEDED.name, receipt.outcome)
            assertTrue(receipt.detail?.contains("w90-") != true)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `intent ownership marker cannot claim a source deletion`() = runBlocking {
        val fixture = DownloaderImplRecoveryTest.Fixture()
        try {
            val deleting = fixture.seedDeletingSource()
            fixture.dao.seedPendingDeleteBatch(
                batchId = "source-intent-marker",
                itemId = deleting.itemId,
                sourceId = deleting.id,
                expectedGeneration = deleting.taskGeneration,
                detail = intentDeletionOwnershipDetail(deleting.taskGeneration),
            )
            fixture.dao.sourceEvents.clear()

            assertEquals(0, fixture.newDownloader().resumePendingDeleteBatches())

            assertEquals(deleting, fixture.dao.sourceById(deleting.id))
            assertTrue(fixture.dao.sourceEvents.isEmpty())
            val receipt = fixture.dao.deleteReceipts("source-intent-marker").single()
            assertEquals(DownloadDeleteOutcome.SUPERSEDED.name, receipt.outcome)
            assertTrue(receipt.detail?.contains("w90-") != true)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `intent terminal and new batch clear the internal ownership marker`() = runBlocking {
        val fixture = DownloaderImplRecoveryTest.Fixture()
        try {
            val itemId = UUID(0L, 908L)
            fixture.seedReceivedIntentWithPendingSidecar(
                itemId = itemId,
                sourceId = intentSourceId(itemId),
                name = "marker-lifecycle",
            )
            val request =
                DownloadDeleteRequest(
                    itemId = itemId,
                    sourceId = intentSourceId(itemId),
                    mediaKind = DownloadMediaKind.MUSIC,
                    serverId = SERVER_ID,
                    userId = USER_ID.toString(),
                )
            val blockedReplace = fixture.blockSidecarReplace()

            val failedBatch = fixture.downloader.deleteDownloads(listOf(request))
            val failedReceipt = failedBatch.receipts.single()

            assertEquals(DownloadDeleteOutcome.FAILED, failedReceipt.outcome)
            assertTrue(failedReceipt.detail?.isNotBlank() == true)
            assertTrue(failedReceipt.detail?.contains("w90-") != true)
            assertEquals(
                DownloadIntentStatus.FAILED.name,
                fixture.dao.downloadIntent(itemId)?.status,
            )

            assertTrue(blockedReplace.deleteRecursively())
            val retriedBatch = fixture.newDownloader().deleteDownloads(listOf(request))
            val retriedReceipt = retriedBatch.receipts.single()

            assertEquals(DownloadDeleteOutcome.SUCCEEDED, retriedReceipt.outcome)
            assertNull(retriedReceipt.detail)
            assertNull(fixture.dao.downloadIntent(itemId))
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
