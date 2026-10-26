package com.zhangwenkang.cinefin.utils

import android.app.Notification
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.zhangwenkang.cinefin.database.ServerDatabaseDao
import com.zhangwenkang.cinefin.models.DownloadDeleteBatchDto
import com.zhangwenkang.cinefin.models.DownloadDeleteReceiptDto
import com.zhangwenkang.cinefin.models.FindroidEpisode
import com.zhangwenkang.cinefin.models.FindroidEpisodeDto
import com.zhangwenkang.cinefin.models.FindroidImages
import com.zhangwenkang.cinefin.models.FindroidItem
import com.zhangwenkang.cinefin.models.FindroidMediaStream
import com.zhangwenkang.cinefin.models.FindroidMediaStreamDto
import com.zhangwenkang.cinefin.models.FindroidMovie
import com.zhangwenkang.cinefin.models.FindroidMovieDto
import com.zhangwenkang.cinefin.models.FindroidSeason
import com.zhangwenkang.cinefin.models.FindroidSeasonDto
import com.zhangwenkang.cinefin.models.FindroidSegment
import com.zhangwenkang.cinefin.models.FindroidSegmentDto
import com.zhangwenkang.cinefin.models.FindroidSegmentType
import com.zhangwenkang.cinefin.models.FindroidShow
import com.zhangwenkang.cinefin.models.FindroidShowDto
import com.zhangwenkang.cinefin.models.FindroidSource
import com.zhangwenkang.cinefin.models.FindroidSourceDto
import com.zhangwenkang.cinefin.models.FindroidSourceType
import com.zhangwenkang.cinefin.models.FindroidTrickplayInfoDto
import com.zhangwenkang.cinefin.models.FindroidUserDataDto
import com.zhangwenkang.cinefin.models.toFindroidSource
import com.zhangwenkang.cinefin.repository.JellyfinRepository
import com.zhangwenkang.cinefin.repository.ReaderRepository
import com.zhangwenkang.cinefin.settings.domain.AppPreferences
import com.zhangwenkang.cinefin.work.ImagesDownloaderWorker
import java.io.File
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CopyOnWriteArraySet
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.jellyfin.sdk.model.api.MediaStreamType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import sun.misc.Unsafe
import timber.log.Timber

class DownloaderImplRecoveryTest {

    @Test
    fun `设置并发从二增到四会经公开偏好写入立即补槽`() = runBlocking {
        val fixture = Fixture()
        val sources = fixture.seedConcurrentMovies(4)
        fixture.appPreferences.setValue(fixture.appPreferences.downloadConcurrency, 2)
        fixture.transfer.holdDownloads()
        val run = launch(Dispatchers.Default) { fixture.downloader.runQueue() }
        try {
            assertTrue(
                "初始并发 2 未启动两个下载：${fixture.transfer.startedPaths()}",
                fixture.transfer.awaitStarted(2),
            )

            fixture.appPreferences.setValue(fixture.appPreferences.downloadConcurrency, 4)

            assertTrue(
                "公开设置写入 2→4 后未立即补齐到四个运行任务：${fixture.transfer.startedPaths()}",
                fixture.transfer.awaitStarted(4),
            )
            assertEquals(
                sources.take(4).map { it.source.id }.toSet(),
                fixture.transfer.startedSourceIds().toSet(),
            )
            assertTrue("提高并发不应自动暂停任务", fixture.dao.pausedSourceIds().isEmpty())
        } finally {
            fixture.transfer.release()
            withTimeoutOrNull(5_000L) { run.join() }
            run.cancel()
            fixture.close()
        }
    }

    @Test
    fun `设置并发从八降到二会经公开偏好写入暂停最新六个并保留残片`() = runBlocking {
        val fixture = Fixture()
        val sources = fixture.seedConcurrentMovies(8)
        fixture.appPreferences.setValue(fixture.appPreferences.downloadConcurrency, 8)
        fixture.transfer.holdDownloads()
        val run = launch(Dispatchers.Default) { fixture.downloader.runQueue() }
        try {
            assertTrue(
                "初始并发 8 未启动八个下载：${fixture.transfer.startedPaths()}",
                fixture.transfer.awaitStarted(8),
            )

            fixture.appPreferences.setValue(fixture.appPreferences.downloadConcurrency, 2)
            val paused =
                withTimeoutOrNull(2_000L) {
                    while (fixture.dao.pausedSourceIds().size < 6) delay(10L)
                    fixture.dao.pausedSourceIds()
                }
            assertNotNull("公开设置写入 8→2 后两秒内未暂停六个多余任务", paused)

            val retained = sources.take(2).map { it.source.id }.toSet()
            val expectedPaused = sources.drop(2).map { it.source.id }.toSet()
            assertEquals(expectedPaused, paused!!.toSet())
            assertEquals(retained, fixture.dao.runningSourceIds().toSet())
            expectedPaused.forEach { sourceId ->
                val source = fixture.dao.sourceById(sourceId)!!
                val partial = File(source.path)
                assertTrue("暂停任务残片丢失：$sourceId", partial.isFile)
                assertEquals("partial", partial.readText())
            }
        } finally {
            fixture.transfer.release()
            withTimeoutOrNull(5_000L) { run.join() }
            run.cancel()
            fixture.close()
        }
    }

    @Test
    fun `删除同条目单个来源经生产入口保留另一来源共享恢复数据`() = runBlocking {
        val fixture = Fixture()
        try {
            val movie = fixture.movie()
            fixture.downloader.downloadItem(movie, SOURCE_ID, 0)
            val second = fixture.seedAdditionalSource(index = 1)
            fixture.persistVideoOrigin(second.source.id)
            val imageMarker = fixture.markerFile("images/$ITEM_ID/cover")

            assertTrue(fixture.downloader.deleteTaskById(SOURCE_ID))

            assertEquals(listOf(second.source.id), fixture.dao.sourceIds())
            assertTrue(
                fixture.downloader.mediaSidecar().allRecords().any {
                    it.itemId == ITEM_ID.toString() && it.sourceId == second.source.id
                }
            )
            assertFalse(
                fixture.downloader.mediaSidecar().allRecords().any {
                    it.itemId == ITEM_ID.toString() && it.sourceId == SOURCE_ID
                }
            )
            assertEquals(
                "删除一个 source 不得清理共享 movie 快照",
                listOf(ITEM_ID),
                fixture.dao.snapshotIds(),
            )
            assertFalse(
                "删除一个 source 不得清理共享 userData",
                ITEM_ID in fixture.dao.deletedUserDataIds(),
            )
            assertTrue("删除一个 source 不得清理共享图片", imageMarker.isFile)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `删除同条目最后一个来源经生产入口清理共享数据`() = runBlocking {
        val fixture = Fixture()
        try {
            val movie = fixture.movie()
            fixture.downloader.downloadItem(movie, SOURCE_ID, 0)
            val second = fixture.seedAdditionalSource(index = 1)
            fixture.persistVideoOrigin(second.source.id)
            val imageMarker = fixture.markerFile("images/$ITEM_ID/cover")

            assertTrue(fixture.downloader.deleteTaskById(SOURCE_ID))
            assertTrue(fixture.downloader.deleteTaskById(second.source.id))

            assertTrue(fixture.dao.sourceIds().isEmpty())
            assertTrue(fixture.dao.snapshotIds().isEmpty())
            assertTrue(ITEM_ID in fixture.dao.deletedUserDataIds())
            assertFalse(imageMarker.exists())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `删除中并发同来源入队不会让旧清理删除新代`() = runBlocking {
        val fixture = Fixture()
        try {
            val movie = fixture.movie()
            fixture.downloader.downloadItem(movie, SOURCE_ID, 0)
            fixture.dao.armSourceDeletionGate()
            val insertCountBefore = fixture.dao.sourceInsertCount(SOURCE_ID)

            val deletion = async(Dispatchers.IO) { fixture.downloader.deleteTaskById(SOURCE_ID) }
            withTimeout(5_000) { fixture.dao.sourceDeleteStarted.await() }
            val deletingGeneration = fixture.dao.sourceDeletionGeneration

            val concurrentEnqueue =
                async(Dispatchers.IO) { fixture.downloader.downloadItem(movie, SOURCE_ID, 0) }
            val insertedWhileDeleting =
                withTimeoutOrNull(500) {
                    fixture.dao.concurrentSourceInserted.await()
                    true
                }
            assertFalse("新 source 不应在删除操作锁释放前插入", insertedWhileDeleting == true)
            assertEquals(
                "删除持锁期间 insert 调用次数必须保持为零",
                insertCountBefore,
                fixture.dao.sourceInsertCount(SOURCE_ID),
            )
            fixture.dao.releaseSourceDelete.complete(Unit)

            assertTrue(deletion.await())
            val (firstHandle, firstError) = concurrentEnqueue.await()
            if (firstHandle >= 0L) {
                assertTrue("等待删除后入队应成功且无错误", firstError == null)
                val inserted = requireNotNull(fixture.dao.lastInsertedSource(SOURCE_ID))
                assertEquals(DownloadTaskStatus.PENDING.name, inserted.taskStatus)
                assertTrue(
                    "新 source 代次必须晚于删除代次：inserted=${inserted.taskGeneration} deleting=$deletingGeneration",
                    inserted.taskGeneration > deletingGeneration,
                )
                assertNotNull("新 generation source 被旧清理删除", fixture.dao.sourceById(SOURCE_ID))
                assertTrue(
                    "新 generation sidecar 被旧清理删除",
                    fixture.downloader.mediaSidecar().allRecords().any {
                        it.itemId == ITEM_ID.toString() && it.sourceId == SOURCE_ID
                    },
                )
                val insertedAt =
                    fixture.dao.sourceEvents.indexOf("INSERT:$SOURCE_ID:${inserted.taskGeneration}")
                val lastMediaDeleteAt =
                    fixture.dao.sourceEvents.indexOfLast { it == "DELETE_MEDIA:$SOURCE_ID" }
                assertTrue(
                    "旧 generation 的 media 清理不得发生在新 source 插入之后",
                    insertedAt > lastMediaDeleteAt,
                )
            } else {
                assertTrue("删除中拒绝入队必须向生产 UI 返回可重试错误", firstError != null)
                assertTrue(fixture.dao.sourceById(SOURCE_ID) == null)

                val (retryHandle, retryError) = fixture.downloader.downloadItem(movie, SOURCE_ID, 0)
                assertTrue("删除完成后的重试应成功", retryHandle >= 0L && retryError == null)
                val retried = requireNotNull(fixture.dao.lastInsertedSource(SOURCE_ID))
                assertEquals(DownloadTaskStatus.PENDING.name, retried.taskStatus)
                assertTrue(retried.taskGeneration > deletingGeneration)
                assertNotNull(fixture.dao.sourceById(SOURCE_ID))
                assertTrue(
                    fixture.downloader.mediaSidecar().allRecords().any {
                        it.itemId == ITEM_ID.toString() && it.sourceId == SOURCE_ID
                    }
                )
            }
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `幂等入队读取与成功返回不得被删除提前穿插`() = runBlocking {
        val fixture = Fixture()
        try {
            val movie = fixture.movie()
            fixture.downloader.downloadItem(movie, SOURCE_ID, 0)
            fixture.dao.armSourceReadGate()
            val completionOrder = CopyOnWriteArrayList<String>()

            val enqueue =
                async(Dispatchers.IO) {
                    fixture.downloader.downloadItem(movie, SOURCE_ID, 0).also {
                        completionOrder += "enqueue"
                    }
                }
            withTimeout(5_000) { fixture.dao.sourceReadStarted.await() }
            val deletion =
                async(Dispatchers.IO) {
                    fixture.downloader.deleteTaskById(SOURCE_ID).also {
                        completionOrder += "delete"
                    }
                }
            try {
                val deletedBeforeEnqueueReturned =
                    withTimeoutOrNull(500) {
                        deletion.join()
                        true
                    }
                assertFalse(
                    "删除不得在幂等入队读取 / 成功返回前完成：$completionOrder",
                    deletedBeforeEnqueueReturned == true,
                )
            } finally {
                fixture.dao.releaseSourceRead.complete(Unit)
            }

            val (handle, error) = enqueue.await()
            assertTrue("幂等入队应返回存活任务", handle >= 0L)
            assertTrue("幂等入队不应报错", error == null)
            assertTrue(deletion.await())
            assertEquals(
                "删除必须在 enqueue 完成线性化之后才返回",
                listOf("enqueue", "delete"),
                completionOrder,
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `legacy公开删除旧source身份不得删除同名新代`() = runBlocking {
        val fixture = Fixture()
        try {
            val movie = fixture.movie()
            fixture.downloader.downloadItem(movie, SOURCE_ID, 0)
            val first = requireNotNull(fixture.dao.source)
            val staleSource = first.toFindroidSource(emptyList())
            fixture.dao.putSource(
                first.copy(
                    taskStatus = DownloadTaskStatus.FAILED.name,
                    taskGeneration = first.taskGeneration + 1,
                )
            )

            fixture.downloader.downloadItem(movie, SOURCE_ID, 0)
            val current = requireNotNull(fixture.dao.source)
            assertTrue(current.taskGeneration > first.taskGeneration)
            assertTrue(current.path != staleSource.path)

            val failure = runCatching {
                fixture.downloader.deleteItem(
                    item = movie,
                    source = staleSource,
                )
            }
                .exceptionOrNull()

            assertNotNull("旧 source 身份必须 fail-closed", failure)
            assertEquals(
                "旧 UI 调用不得删除同名新 generation",
                current,
                fixture.dao.source,
            )
            assertTrue(
                "新 generation sidecar 不得被旧 UI 调用删除",
                fixture.downloader.mediaSidecar().allRecords().any {
                    it.itemId == ITEM_ID.toString() && it.sourceId == SOURCE_ID
                },
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `legacy公开删除blank sidecar必须失败关闭`() = runBlocking {
        val fixture = Fixture()
        try {
            val movie = fixture.movie()
            fixture.downloader.downloadItem(movie, SOURCE_ID, 0)
            val source = requireNotNull(fixture.dao.source).toFindroidSource(emptyList())
            val sidecar = fixture.downloader.mediaSidecar()
            sidecar.allRecords().forEach { sidecar.remove(it.canonicalIdentity()) }
            sidecar.put(
                DownloadMediaRecord(
                    itemId = ITEM_ID.toString(),
                    kind = DownloadMediaKind.VIDEO,
                    sourceId = null,
                    displayName = ITEM_NAME,
                )
            )

            val failure = runCatching {
                fixture.downloader.deleteItem(movie, source)
            }
                .exceptionOrNull()

            assertNotNull("blank sidecar 无法证明来源归属时必须失败关闭", failure)
            assertNotNull("失败关闭不得删除 source", fixture.dao.source)
            assertEquals(
                "失败关闭不得删除 blank sidecar",
                1,
                sidecar.allRecords().count {
                    it.itemId == ITEM_ID.toString() && it.sourceId.isNullOrBlank()
                },
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `legacy公开删除no generation必须失败关闭`() = runBlocking {
        val fixture = Fixture()
        try {
            val movie = fixture.movie()
            fixture.downloader.downloadItem(movie, SOURCE_ID, 0)
            val current = requireNotNull(fixture.dao.source)
            val source = current.toFindroidSource(emptyList())
            fixture.dao.putSource(current.copy(taskGeneration = 0L))

            val failure = runCatching {
                fixture.downloader.deleteItem(movie, source)
            }
                .exceptionOrNull()

            assertNotNull("缺 generation 的身份必须失败关闭", failure)
            assertEquals(
                "缺 generation 不得删除 source",
                0L,
                requireNotNull(fixture.dao.source).taskGeneration,
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `DELETING清理抛错后新实例启动扫描可幂等续删`() = runBlocking {
        val fixture = Fixture()
        try {
            val movie = fixture.movie()
            fixture.downloader.downloadItem(movie, SOURCE_ID, 0)
            val blockedPath = File(requireNotNull(fixture.dao.source).path)
            blockedPath.delete()
            assertTrue(blockedPath.mkdirs())
            File(blockedPath, "blocked").writeText("hold")

            val cleanupFailure = runCatching {
                fixture.downloader.deleteTaskById(SOURCE_ID)
            }
                .exceptionOrNull()
            assertNotNull("清理阻塞必须向调用方暴露失败", cleanupFailure)
            val deleting = requireNotNull(fixture.dao.source)
            assertEquals(DownloadTaskStatus.DELETING.name, deleting.taskStatus)

            assertTrue(File(deleting.path, "blocked").delete())
            assertTrue(File(deleting.path).delete())
            val restarted = fixture.newDownloader()

            assertTrue("重启后应续完当前 DELETING 代次", restarted.recoverOnStartup() > 0)
            assertTrue("续删成功后 source 行必须移除", fixture.dao.source == null)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `DELETING清理抛错后pending receipt可续删并成功`() = runBlocking {
        val fixture = Fixture()
        try {
            val movie = fixture.movie()
            fixture.downloader.downloadItem(movie, SOURCE_ID, 0)
            val blockedPath = File(requireNotNull(fixture.dao.source).path)
            blockedPath.delete()
            assertTrue(blockedPath.mkdirs())
            File(blockedPath, "blocked").writeText("hold")
            assertNotNull(
                runCatching { fixture.downloader.deleteTaskById(SOURCE_ID) }.exceptionOrNull()
            )
            val deleting = requireNotNull(fixture.dao.source)
            assertEquals(DownloadTaskStatus.DELETING.name, deleting.taskStatus)
            fixture.dao.seedPendingDeleteBatch(
                batchId = "delete-batch-$SOURCE_ID",
                itemId = ITEM_ID,
                sourceId = SOURCE_ID,
                expectedGeneration = deleting.taskGeneration,
            )

            assertTrue(File(deleting.path, "blocked").delete())
            assertTrue(File(deleting.path).delete())
            val restarted = fixture.newDownloader()

            assertEquals(1, restarted.resumePendingDeleteBatches())
            assertTrue(fixture.dao.source == null)
            assertEquals(
                DownloadDeleteOutcome.SUCCEEDED.name,
                fixture.dao.deleteReceipts("delete-batch-$SOURCE_ID").single().outcome,
            )
            assertNotNull(fixture.dao.deleteBatch("delete-batch-$SOURCE_ID")?.completedAt)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `DELETING receipt失败后startup成功删行会将receipt收敛为成功`() = runBlocking {
        val fixture = Fixture()
        try {
            val movie = fixture.movie()
            fixture.downloader.downloadItem(movie, SOURCE_ID, 0)
            val blockedPath = File(requireNotNull(fixture.dao.source).path)
            blockedPath.delete()
            assertTrue(blockedPath.mkdirs())
            File(blockedPath, "blocked").writeText("hold")

            val first =
                fixture.downloader.deleteDownloads(
                    listOf(
                        DownloadDeleteRequest(
                            itemId = ITEM_ID,
                            sourceId = SOURCE_ID,
                            mediaKind = DownloadMediaKind.VIDEO,
                            serverId = SERVER_ID,
                            userId = USER_ID.toString(),
                        )
                    )
                )
            val failed = first.receipts.single()
            assertEquals(DownloadDeleteOutcome.FAILED, failed.outcome)
            assertEquals(
                DownloadTaskStatus.DELETING.name,
                requireNotNull(fixture.dao.source).taskStatus,
            )

            assertTrue(File(blockedPath, "blocked").delete())
            assertTrue(blockedPath.delete())
            fixture.newDownloader().recoverOnStartup()

            assertEquals("续删成功后 source 行必须移除", null, fixture.dao.source)
            assertEquals(
                "source 行已删除时对应 receipt 不得永久停留在 FAILED",
                DownloadDeleteOutcome.SUCCEEDED.name,
                requireNotNull(fixture.dao.deleteReceipt(first.batchId, failed.canonicalKey))
                    .outcome,
            )
            assertNotNull(fixture.dao.deleteBatch(first.batchId)?.completedAt)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `startup旧DELETING快照不得删除锁内已建立的新代`() = runBlocking {
        val fixture = Fixture()
        try {
            val movie = fixture.movie()
            fixture.downloader.downloadItem(movie, SOURCE_ID, 0)
            val blockedPath = File(requireNotNull(fixture.dao.source).path)
            blockedPath.delete()
            assertTrue(blockedPath.mkdirs())
            File(blockedPath, "blocked").writeText("hold")
            assertNotNull(
                runCatching { fixture.downloader.deleteTaskById(SOURCE_ID) }.exceptionOrNull()
            )
            val deleting = requireNotNull(fixture.dao.source)
            assertEquals(DownloadTaskStatus.DELETING.name, deleting.taskStatus)
            assertTrue(File(blockedPath, "blocked").delete())
            assertTrue(blockedPath.delete())

            fixture.dao.armPendingSourcesSnapshotGate()
            val startup = async(Dispatchers.Default) { fixture.newDownloader().recoverOnStartup() }
            assertTrue(
                "startup 未取得旧 DELETING 快照",
                withTimeoutOrNull(5_000L) {
                    fixture.dao.pendingSourcesSnapshotStarted.await()
                    true
                } == true,
            )

            val (_, failure) = fixture.downloader.downloadItem(movie, SOURCE_ID, 0)
            assertEquals(null, failure)
            val pending = requireNotNull(fixture.dao.source)
            assertEquals(DownloadTaskStatus.PENDING.name, pending.taskStatus)
            assertTrue(pending.taskGeneration > deleting.taskGeneration)

            fixture.dao.releasePendingSourcesSnapshot.complete(Unit)
            withTimeout(5_000L) { startup.await() }

            val retained = requireNotNull(fixture.dao.source)
            assertEquals(DownloadTaskStatus.PENDING.name, retained.taskStatus)
            assertEquals(pending.taskGeneration, retained.taskGeneration)
        } finally {
            if (!fixture.dao.releasePendingSourcesSnapshot.isCompleted) {
                fixture.dao.releasePendingSourcesSnapshot.complete(Unit)
            }
            fixture.close()
        }
    }

    @Test
    fun `startup旧FAILED receipt不得删除已REPLACE的新代`() = runBlocking {
        val fixture = Fixture()
        try {
            val movie = fixture.movie()
            fixture.downloader.downloadItem(movie, SOURCE_ID, 0)
            val blockedPath = File(requireNotNull(fixture.dao.source).path)
            blockedPath.delete()
            assertTrue(blockedPath.mkdirs())
            File(blockedPath, "blocked").writeText("hold")

            val first =
                fixture.downloader.deleteDownloads(
                    listOf(
                        DownloadDeleteRequest(
                            itemId = ITEM_ID,
                            sourceId = SOURCE_ID,
                            mediaKind = DownloadMediaKind.VIDEO,
                            serverId = SERVER_ID,
                            userId = USER_ID.toString(),
                        )
                    )
                )
            val failed = first.receipts.single()
            assertEquals(DownloadDeleteOutcome.FAILED, failed.outcome)
            val deleting = requireNotNull(fixture.dao.source)
            assertEquals(DownloadTaskStatus.DELETING.name, deleting.taskStatus)

            assertTrue(File(blockedPath, "blocked").delete())
            assertTrue(blockedPath.delete())
            val (_, enqueueFailure) = fixture.downloader.downloadItem(movie, SOURCE_ID, 0)
            assertEquals(null, enqueueFailure)
            val pending = requireNotNull(fixture.dao.source)
            assertEquals(DownloadTaskStatus.PENDING.name, pending.taskStatus)
            assertTrue(pending.taskGeneration > deleting.taskGeneration)

            fixture.persistVideoOrigin(SOURCE_ID)
            val external = fixture.seedStaleExternal()
            val imageMarker = fixture.markerFile("images/$ITEM_ID/cover")
            fixture.dao.sourceEvents.clear()

            fixture.newDownloader().recoverOnStartup()

            val retainedRow = fixture.dao.sourceById(SOURCE_ID)
            assertNotNull("旧 receipt 不得删除已 REPLACE 的新 generation source", retainedRow)
            val retained = requireNotNull(retainedRow)
            assertEquals(pending.taskGeneration, retained.taskGeneration)
            assertTrue(
                "旧 receipt 不得把新代推进到删除：${retained.taskStatus}",
                retained.taskStatus != DownloadTaskStatus.DELETING.name,
            )
            assertTrue(
                "旧 receipt 不得清理新 source",
                fixture.dao.sourceEvents.none { it == "DELETE_SOURCE:$SOURCE_ID" },
            )
            assertTrue(
                "旧 receipt 不得清理新 source 的 media",
                fixture.dao.sourceEvents.none { it == "DELETE_MEDIA:$SOURCE_ID" },
            )
            assertTrue(
                "旧 receipt 不得清理新 generation 的 sidecar",
                fixture.downloader.mediaSidecar().allRecords().any {
                    it.itemId == ITEM_ID.toString() && it.sourceId == SOURCE_ID
                },
            )
            assertTrue("旧 receipt 不得清理新 generation 的外部 media", external.isFile)
            assertTrue(
                "旧 receipt 不得清理新 generation 的 media row",
                fixture.dao.externalMediaStreams().any { it.path == external.path },
            )
            assertTrue("旧 receipt 不得清理共享 movie 快照", ITEM_ID in fixture.dao.snapshotIds())
            assertFalse("旧 receipt 不得清理共享 userData", ITEM_ID in fixture.dao.deletedUserDataIds())
            assertTrue("旧 receipt 不得清理共享图片", imageMarker.isFile)
            assertEquals(
                "superseded receipt 必须成为终态",
                "SUPERSEDED",
                requireNotNull(fixture.dao.deleteReceipt(first.batchId, failed.canonicalKey))
                    .outcome,
            )
            assertNotNull(
                "superseded receipt 不得让 batch 永久卡在未完成",
                fixture.dao.deleteBatch(first.batchId)?.completedAt,
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `无generation旧receipt必须等待startup扫描验证代次`() = runBlocking {
        val fixture = Fixture()
        try {
            val movie = fixture.movie()
            fixture.downloader.downloadItem(movie, SOURCE_ID, 0)
            val blockedPath = File(requireNotNull(fixture.dao.source).path)
            blockedPath.delete()
            assertTrue(blockedPath.mkdirs())
            File(blockedPath, "blocked").writeText("hold")
            assertNotNull(
                runCatching { fixture.downloader.deleteTaskById(SOURCE_ID) }.exceptionOrNull()
            )
            val deleting = requireNotNull(fixture.dao.source)
            assertEquals(DownloadTaskStatus.DELETING.name, deleting.taskStatus)
            fixture.dao.seedPendingDeleteBatch(
                batchId = "legacy-receipt-$SOURCE_ID",
                itemId = ITEM_ID,
                sourceId = SOURCE_ID,
                expectedGeneration = null,
            )

            assertTrue(File(blockedPath, "blocked").delete())
            assertTrue(blockedPath.delete())
            fixture.dao.sourceEvents.clear()

            assertEquals(0, fixture.newDownloader().resumePendingDeleteBatches())
            val retained = requireNotNull(fixture.dao.sourceById(SOURCE_ID))
            assertEquals(deleting.taskGeneration, retained.taskGeneration)
            assertEquals(DownloadTaskStatus.DELETING.name, retained.taskStatus)
            assertTrue(
                "无 generation 的旧 receipt 不得清理当前代",
                fixture.dao.sourceEvents.none {
                    it == "DELETE_SOURCE:$SOURCE_ID" || it == "DELETE_MEDIA:$SOURCE_ID"
                },
            )
            assertEquals(
                DownloadDeleteOutcome.FAILED.name,
                requireNotNull(
                        fixture.dao.deleteReceipt(
                            "legacy-receipt-$SOURCE_ID",
                            DownloadDeletionIdentity.canonicalKey(
                                DownloadDeleteRequest(
                                    itemId = ITEM_ID,
                                    sourceId = SOURCE_ID,
                                    mediaKind = DownloadMediaKind.VIDEO,
                                    serverId = SERVER_ID,
                                    userId = USER_ID.toString(),
                                )
                            ),
                        )
                    )
                    .outcome,
            )

            fixture.newDownloader().recoverOnStartup()
            assertTrue("startup scan 应完成已验证代次的删除", fixture.dao.sourceById(SOURCE_ID) == null)
            assertEquals(
                "source 行移除后 legacy receipt 必须收敛为成功",
                DownloadDeleteOutcome.SUCCEEDED.name,
                fixture.dao.deleteReceipts("legacy-receipt-$SOURCE_ID").single().outcome,
            )
            assertNotNull(fixture.dao.deleteBatch("legacy-receipt-$SOURCE_ID")?.completedAt)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `删除剧集单个来源保留另一来源快照和父级层级`() = runBlocking {
        val fixture = Fixture()
        try {
            fixture.dao.trackParentRows = true
            val episode = fixture.episode()
            fixture.downloader.downloadItem(episode, SOURCE_ID, 0)
            val second =
                fixture.seedAdditionalSource(
                    index = 1,
                    sourceId = "episode-second-source",
                )
            fixture.persistEpisodeOrigin(second.source.id)
            val imageMarker = fixture.markerFile("images/$ITEM_ID/cover")

            assertTrue(fixture.downloader.deleteTaskById(SOURCE_ID))

            assertEquals(listOf(second.source.id), fixture.dao.sourceIds())
            assertNotNull("删除一个 source 不得清理共享 episode 快照", fixture.dao.episodeSnapshot)
            assertTrue(fixture.dao.hasEpisode(SEASON_ID, ITEM_ID))
            assertTrue(fixture.dao.hasSeason(SHOW_ID, SEASON_ID))
            assertFalse(ITEM_ID in fixture.dao.deletedUserDataIds())
            assertTrue(imageMarker.isFile)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `剧集入队为节目和季调度父级图片`() = runBlocking {
        val fixture = Fixture()
        try {
            val episode = fixture.episode()

            val (handle, error) =
                fixture.downloader.downloadItem(
                    item = episode,
                    sourceId = SOURCE_ID,
                    storageIndex = 0,
                )

            assertTrue(error == null)
            assertTrue(handle > 0L)
            assertEquals(
                setOf(SHOW_ID, SEASON_ID),
                fixture.imageScheduler.parentItemIds.toSet(),
            )
            assertEquals(
                setOf(
                    ImageDownloadParentOwner(
                        kind = ImageDownloadParentKind.SHOW,
                        itemId = SHOW_ID,
                        serverId = SERVER_ID,
                        userId = USER_ID.toString(),
                    ),
                    ImageDownloadParentOwner(
                        kind = ImageDownloadParentKind.SEASON,
                        itemId = SEASON_ID,
                        serverId = SERVER_ID,
                        userId = USER_ID.toString(),
                    ),
                ),
                fixture.imageScheduler.parentOwners.toSet(),
            )
            assertEquals(SERVER_ID, fixture.dao.showSnapshot?.serverId)
            assertEquals(SHOW_ID, fixture.dao.seasonSnapshot?.seriesId)
            assertEquals(SEASON_ID, fixture.dao.seasonSnapshot?.id)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `父图调度器写入完整WorkManager输入`() = runBlocking {
        val fixture = Fixture()
        try {
            val gateway = RecordingWorkGateway()
            val scheduler = WorkManagerImageFinalizationScheduler(gateway)
            val owner =
                ImageDownloadParentOwner(
                    kind = ImageDownloadParentKind.SEASON,
                    itemId = SEASON_ID,
                    serverId = SERVER_ID,
                    userId = USER_ID.toString(),
                )

            scheduler.scheduleParent(fixture.season(), owner)

            assertEquals(
                listOf(ImageDownloadWorkRules.parentWorkName(owner)),
                gateway.uniqueRequests.map { it.first },
            )
            val data = gateway.uniqueRequests.single().third.workSpec.input
            assertEquals(SEASON_ID.toString(), data.getString(ImagesDownloaderWorker.KEY_ITEM_ID))
            assertEquals(
                ImageDownloadParentKind.SEASON.name,
                data.getString(ImagesDownloaderWorker.KEY_PARENT_KIND),
            )
            assertEquals(SERVER_ID, data.getString(ImagesDownloaderWorker.KEY_SERVER_ID))
            assertEquals(
                USER_ID.toString(),
                data.getString(ImagesDownloaderWorker.KEY_USER_ID),
            )

            scheduler.cancelParent(owner)
            assertEquals(
                listOf(ImageDownloadWorkRules.parentWorkName(owner)),
                gateway.cancelledNames,
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `删除最后一个剧集取消节目和季父级图片`() = runBlocking {
        val fixture = Fixture()
        try {
            val episode = fixture.episode()
            val source = fixture.episodeSource()
            fixture.seedCompletedSource(episode, source)
            fixture.dao.remainingEpisodes = emptyList()
            fixture.dao.remainingSeasons = emptyList()

            fixture.downloader.deleteItem(
                item = episode,
                source = source,
            )

            assertEquals(
                setOf(SEASON_ID, SHOW_ID),
                fixture.imageScheduler.cancelledParentItemIds.toSet(),
            )
            assertEquals(
                setOf(ImageDownloadParentKind.SEASON, ImageDownloadParentKind.SHOW),
                fixture.imageScheduler.cancelledParentOwners.map { it.kind }.toSet(),
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `删除仍有兄弟剧集时不取消同级父图`() = runBlocking {
        val fixture = Fixture()
        try {
            val episode = fixture.episode()
            val source = fixture.episodeSource()
            fixture.seedCompletedSource(episode, source)
            fixture.dao.remainingEpisodes = listOf(Any())
            fixture.dao.remainingSeasons = listOf(Any())

            fixture.downloader.deleteItem(
                item = episode,
                source = source,
            )

            assertTrue(fixture.imageScheduler.cancelledParentItemIds.isEmpty())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `完成恢复缺少sidecar时不写当前namespace快照或父图`() = runBlocking {
        val fixture = Fixture()
        try {
            val episode = fixture.episode()
            val (handle, error) =
                fixture.downloader.downloadItem(
                    item = episode,
                    sourceId = SOURCE_ID,
                    storageIndex = 0,
                )
            assertTrue(error == null)
            assertTrue(handle > 0L)
            val sidecar = fixture.downloader.mediaSidecar()
            sidecar.allRecords().forEach { sidecar.remove(it.canonicalIdentity()) }
            fixture.imageScheduler.parentOwners.clear()
            fixture.imageScheduler.parentItemIds.clear()
            fixture.resetEffectCalls()
            fixture.preferences.currentServer = "server-b"
            fixture.preferences.currentServerReads = 0
            fixture.repository.userId = OTHER_USER_ID
            fixture.repository.userIdReads = 0
            fixture.repository.showReads = 0
            fixture.repository.seasonReads = 0

            fixture.downloader.runQueue()

            assertEquals(0, fixture.dao.showSnapshotWrites)
            assertEquals(0, fixture.dao.seasonSnapshotWrites)
            assertEquals(0, fixture.dao.episodeSnapshotWrites)
            assertEquals(0, fixture.dao.userDataWrites)
            assertEquals(0, fixture.preferences.currentServerReads)
            assertEquals(0, fixture.repository.userIdReads)
            assertEquals(0, fixture.repository.showReads)
            assertEquals(0, fixture.repository.seasonReads)
            assertTrue(
                "sidecar 缺失不得借用当前 server/user：${fixture.imageScheduler.parentOwners}",
                fixture.imageScheduler.parentOwners.isEmpty(),
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `完成恢复sidecar身份歧义时失败关闭`() = runBlocking {
        val fixture = Fixture()
        try {
            val episode = fixture.episode()
            val (handle, error) =
                fixture.downloader.downloadItem(
                    item = episode,
                    sourceId = SOURCE_ID,
                    storageIndex = 0,
                )
            assertTrue(error == null)
            assertTrue(handle > 0L)
            fixture.persistEpisodeOrigin(
                sourceId = SOURCE_ID,
                serverId = "server-b",
                userId = OTHER_USER_ID,
            )
            fixture.imageScheduler.resetParentObservations()
            fixture.resetEffectCalls()
            fixture.preferences.currentServer = "server-c"
            fixture.preferences.currentServerReads = 0
            fixture.repository.userId = OTHER_USER_ID
            fixture.repository.userIdReads = 0

            fixture.downloader.runQueue()

            assertEquals(0, fixture.dao.showSnapshotWrites)
            assertEquals(0, fixture.dao.seasonSnapshotWrites)
            assertEquals(0, fixture.dao.episodeSnapshotWrites)
            assertEquals(0, fixture.dao.userDataWrites)
            assertEquals(0, fixture.preferences.currentServerReads)
            assertEquals(0, fixture.repository.userIdReads)
            assertTrue(fixture.imageScheduler.parentOwners.isEmpty())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `完成恢复完整sidecar使用持久namespace且仍调度父图`() = runBlocking {
        val fixture = Fixture()
        try {
            val episode = fixture.episode()
            val (handle, error) =
                fixture.downloader.downloadItem(
                    item = episode,
                    sourceId = SOURCE_ID,
                    storageIndex = 0,
                )
            assertTrue(error == null)
            assertTrue(handle > 0L)
            fixture.imageScheduler.parentOwners.clear()
            fixture.imageScheduler.parentItemIds.clear()
            fixture.resetEffectCalls()
            fixture.preferences.currentServer = "server-b"
            fixture.preferences.currentServerReads = 0
            fixture.repository.userId = OTHER_USER_ID
            fixture.repository.userIdReads = 0

            fixture.downloader.runQueue()

            assertEquals(
                "source=${fixture.dao.source}, sidecar=${fixture.downloader.mediaSidecar().allRecords()}, logs=${fixture.logs}",
                setOf(
                    ImageDownloadParentOwner(
                        kind = ImageDownloadParentKind.SHOW,
                        itemId = SHOW_ID,
                        serverId = SERVER_ID,
                        userId = USER_ID.toString(),
                    ),
                    ImageDownloadParentOwner(
                        kind = ImageDownloadParentKind.SEASON,
                        itemId = SEASON_ID,
                        serverId = SERVER_ID,
                        userId = USER_ID.toString(),
                    ),
                ),
                fixture.imageScheduler.parentOwners.toSet(),
            )
            assertEquals(USER_ID, fixture.dao.userDataSnapshot?.userId)
            assertEquals(0, fixture.preferences.currentServerReads)
            assertEquals(0, fixture.repository.userIdReads)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `最后剧集删除与新兄弟入队具备父级串行边界`() = runBlocking {
        val fixture = Fixture()
        try {
            fixture.dao.trackParentRows = true
            val deletingEpisode = fixture.episode()
            val deletingQueueSource = fixture.episodeSource()
            val (handle, error) =
                fixture.downloader.downloadItem(
                    item = deletingEpisode,
                    sourceId = deletingQueueSource.id,
                    storageIndex = 0,
                )
            assertTrue(error == null)
            assertTrue(handle > 0L)
            val deletingSource =
                requireNotNull(fixture.dao.sourceById(deletingQueueSource.id))
                    .toFindroidSource(emptyList())
            fixture.imageScheduler.resetParentObservations()
            fixture.dao.armSeasonDeleteGate()

            val deletion =
                async(Dispatchers.IO) {
                    fixture.downloader.deleteItem(deletingEpisode, deletingSource)
                }
            withTimeout(5_000) { fixture.dao.seasonDeleteStarted.await() }

            val siblingEpisode = fixture.episode(itemId = SIBLING_ITEM_ID)
            val siblingSource = fixture.episodeSource(id = SIBLING_SOURCE_ID)
            val siblingEnqueue =
                async(Dispatchers.IO) {
                    fixture.downloader.downloadItem(
                        item = siblingEpisode,
                        sourceId = siblingSource.id,
                        storageIndex = 0,
                    )
                }
            // 旧实现会在删除门内完成 sibling 调度；新串行实现则让 sibling 等待，
            // 因此这里的超时只负责在新实现上释放仍由删除持有的父级锁。
            withTimeoutOrNull(2_000) { fixture.imageScheduler.parentScheduled.await() }
            fixture.dao.releaseSeasonDelete.complete(Unit)
            deletion.await()
            val (siblingHandle, siblingError) = siblingEnqueue.await()

            assertTrue(siblingError == null)
            assertTrue(siblingHandle > 0L)
            assertTrue("新兄弟 episode 被删除", fixture.dao.hasEpisode(SEASON_ID, SIBLING_ITEM_ID))
            assertTrue("新兄弟所属 season 被删除", fixture.dao.hasSeason(SHOW_ID, SEASON_ID))
            assertEquals(
                "新兄弟入队后的父图任务必须保留",
                setOf(SHOW_ID, SEASON_ID),
                fixture.imageScheduler.activeParentItemIds.toSet(),
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `删除父图按item和source拒绝同source的错误身份`() = runBlocking {
        val fixture = Fixture()
        try {
            val episode = fixture.episode()
            val source = fixture.episodeSource()
            val sidecar = fixture.downloader.mediaSidecar()
            sidecar.put(
                DownloadMediaRecord(
                    itemId = ITEM_ID.toString(),
                    kind = DownloadMediaKind.VIDEO,
                    serverId = SERVER_ID,
                    userId = USER_ID.toString(),
                    sourceId = source.id,
                )
            )
            sidecar.put(
                DownloadMediaRecord(
                    itemId = OTHER_ITEM_ID.toString(),
                    kind = DownloadMediaKind.VIDEO,
                    serverId = "server-b",
                    userId = OTHER_USER_ID.toString(),
                    sourceId = source.id,
                )
            )
            fixture.dao.remainingEpisodes = emptyList()
            fixture.dao.remainingSeasons = emptyList()
            fixture.seedCompletedSource(episode, source, persistOrigin = false)

            fixture.downloader.deleteItem(item = episode, source = source)

            assertEquals(
                setOf(SERVER_ID),
                fixture.imageScheduler.cancelledParentOwners.map { it.serverId }.toSet(),
            )
            assertEquals(
                setOf(USER_ID.toString()),
                fixture.imageScheduler.cancelledParentOwners.map { it.userId }.toSet(),
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `删除父图在完整身份歧义时失败关闭`() = runBlocking {
        val fixture = Fixture()
        try {
            val episode = fixture.episode()
            val source = fixture.episodeSource()
            val sidecar = fixture.downloader.mediaSidecar()
            sidecar.put(
                DownloadMediaRecord(
                    itemId = ITEM_ID.toString(),
                    kind = DownloadMediaKind.VIDEO,
                    serverId = SERVER_ID,
                    userId = USER_ID.toString(),
                    sourceId = source.id,
                )
            )
            sidecar.put(
                DownloadMediaRecord(
                    itemId = ITEM_ID.toString(),
                    kind = DownloadMediaKind.VIDEO,
                    serverId = "server-b",
                    userId = OTHER_USER_ID.toString(),
                    sourceId = source.id,
                )
            )
            fixture.dao.remainingEpisodes = emptyList()
            fixture.dao.remainingSeasons = emptyList()
            fixture.seedCompletedSource(episode, source, persistOrigin = false)

            val failure = runCatching {
                fixture.downloader.deleteItem(item = episode, source = source)
            }
                .exceptionOrNull()

            assertTrue(
                "歧义身份必须以删除失败暴露：${fixture.imageScheduler.cancelledParentOwners}",
                failure != null,
            )
            assertTrue(
                "歧义身份不得猜来源或清理父图",
                fixture.imageScheduler.cancelledParentOwners.isEmpty(),
            )
            assertNotNull(
                "歧义身份不得删除同名 source",
                fixture.dao.sourceById(source.id),
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `在线入口按代次提交并真实执行六项副作用`() = runBlocking {
        val fixture = Fixture()
        try {
            val (handle, error) =
                fixture.downloader.downloadItem(
                    item = fixture.movie(),
                    sourceId = SOURCE_ID,
                    storageIndex = 0,
                )

            assertTrue(error == null)
            assertTrue(handle > 0L)
            val queued =
                DownloadCompletionCommitRules.downloadTargetFiles(
                    directory = fixture.downloads,
                    itemId = ITEM_ID.toString(),
                    sourceId = SOURCE_ID,
                    generation = 1L,
                )
            assertEquals(queued.first.path, fixture.dao.source?.path)

            fixture.resetEffectCalls()
            fixture.downloader.runQueue()

            assertEquals(
                "source=${fixture.dao.source}, logs=${fixture.logs}",
                "COMPLETED",
                fixture.dao.source?.taskStatus,
            )
            fixture.assertSixEffects(
                EffectCalls(
                    snapshotWrites = 1,
                    internalMediaStreamWrites = 1,
                    externalMediaStreamWrites = 1,
                    externalDownloads = 1,
                    segmentReads = 1,
                    segmentWrites = 1,
                    trickplayReads = 2,
                    trickplayWrites = 1,
                    imageAttempts = 1,
                )
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `恢复前置失败后的手动 retry 保持 witness 代次并完成 finalizer`() = runBlocking {
        val fixture = Fixture()
        try {
            fixture.prepareInterruptedCompletion()
            fixture.dao.itemAvailable = false

            fixture.downloader.recoverOnStartup()
            fixture.downloader.runQueue()

            assertEquals(DownloadTaskStatus.FAILED.name, fixture.dao.source?.taskStatus)
            assertEquals(GENERATION, fixture.dao.source?.taskGeneration)
            assertTrue(fixture.witness().isFile)

            fixture.dao.itemAvailable = true
            val (handle, error) =
                fixture.downloader.retryTask(
                    DownloadTask(
                        itemId = ITEM_ID,
                        sourceId = SOURCE_ID,
                        name = ITEM_NAME,
                        path = fixture.dao.source!!.path,
                        downloadId = downloadTaskHandle(SOURCE_ID),
                        status = DownloadTaskStatus.FAILED,
                        failureReason = DownloadFailureReason.FILE_ERROR,
                        downloadedBytes = MEDIA_BYTES.size.toLong(),
                        totalBytes = MEDIA_BYTES.size.toLong(),
                        updatedAt = 0L,
                        mediaKind = DownloadMediaKind.VIDEO,
                        taskGeneration = GENERATION,
                    )
                )

            assertTrue(error == null)
            assertTrue(handle > 0L)
            assertEquals(
                "可恢复 witness 的手动 retry 不得推进完成代次",
                GENERATION,
                fixture.dao.source?.taskGeneration,
            )
            assertTrue(fixture.witness().isFile)

            fixture.downloader.runQueue()

            assertEquals("COMPLETED", fixture.dao.source?.taskStatus)
            assertFalse(fixture.witness().exists())
            fixture.assertSixEffects(
                EffectCalls(
                    snapshotWrites = 1,
                    internalMediaStreamWrites = 1,
                    externalMediaStreamWrites = 1,
                    externalDownloads = 1,
                    segmentReads = 1,
                    segmentWrites = 1,
                    trickplayReads = 2,
                    trickplayWrites = 1,
                    imageAttempts = 1,
                )
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `旧 final 不阻塞旧路径上的新代次残片提交`() = runBlocking {
        val fixture = Fixture()
        try {
            val oldEvidence = fixture.prepareLegacyTargetConflict()
            fixture.persistVideoOrigin(SOURCE_ID)
            fixture.dao.source =
                FindroidSourceDto(
                    id = SOURCE_ID,
                    itemId = ITEM_ID,
                    name = ITEM_NAME,
                    type = FindroidSourceType.LOCAL,
                    path =
                        File(
                                fixture.downloads,
                                "${ITEM_ID}.${SOURCE_ID}.download",
                            )
                            .path,
                    taskStatus = DownloadTaskStatus.PENDING.name,
                    updatedAt = 0L,
                    downloadedBytes = MEDIA_BYTES.size.toLong(),
                    totalBytes = MEDIA_BYTES.size.toLong(),
                    engineVersion = DownloaderImpl.ENGINE_VERSION,
                    taskGeneration = GENERATION,
                )

            fixture.downloader.runQueue()

            val current =
                DownloadCompletionCommitRules.downloadTargetFiles(
                    directory = fixture.downloads,
                    itemId = ITEM_ID.toString(),
                    sourceId = SOURCE_ID,
                    generation = GENERATION,
                )
            assertEquals(
                "source=${fixture.dao.source}, legacy=${oldEvidence.legacyFinal}, logs=${fixture.logs}",
                "COMPLETED",
                fixture.dao.source?.taskStatus,
            )
            assertTrue(current.second.isFile)
            assertFalse(oldEvidence.legacyFinal.canonicalFile == current.second.canonicalFile)
            assertTrue(oldEvidence.legacyFinal.isFile)
            assertEquals("old payload", oldEvidence.legacyFinal.readText())
            assertTrue(oldEvidence.legacyWitness.isFile)
            fixture.assertSixEffects(
                EffectCalls(
                    snapshotWrites = 1,
                    internalMediaStreamWrites = 1,
                    externalMediaStreamWrites = 1,
                    externalDownloads = 1,
                    segmentReads = 1,
                    segmentWrites = 1,
                    trickplayReads = 2,
                    trickplayWrites = 1,
                    imageAttempts = 1,
                )
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `重复恢复复用外置流且失败状态先落库`() = runBlocking {
        val fixture = Fixture()
        try {
            fixture.prepareInterruptedCompletion()
            val staleExternal = fixture.seedStaleExternal()
            fixture.repository.segmentFailuresRemaining = 1

            fixture.downloader.recoverOnStartup()
            fixture.downloader.runQueue()

            assertEquals(
                "source=${fixture.dao.source}, external=${fixture.transfer.externalDownloads}, logs=${fixture.logs}",
                "PENDING",
                fixture.dao.source?.taskStatus,
            )
            assertEquals(DownloadFailureReason.SERVER_ERROR.name, fixture.dao.source?.failureReason)
            assertTrue(fixture.dao.source!!.retryCount > 0)
            assertTrue(fixture.witness().isFile)
            assertFalse("新代次成功落盘后应安全清理旧外置流", staleExternal.isFile)
            assertEquals(1, fixture.transfer.externalDownloads)
            val externalPath = fixture.dao.externalStream()!!.path
            assertTrue(File(externalPath).isFile)
            val expectedExternalId =
                DownloadPostCommitRules.externalMediaStreamId(
                    sourceId = SOURCE_ID,
                    generation = GENERATION,
                    streamIndex = 1,
                    ordinal = 0,
                )
            assertTrue(
                "外置流目标必须按当前代次隔离：$externalPath",
                externalPath.endsWith(expectedExternalId.toString()),
            )
            fixture.assertSixEffects(
                EffectCalls(
                    snapshotWrites = 1,
                    internalMediaStreamWrites = 1,
                    externalMediaStreamWrites = 1,
                    externalDownloads = 1,
                    segmentReads = 1,
                    segmentWrites = 0,
                    trickplayReads = 2,
                    trickplayWrites = 1,
                    imageAttempts = 1,
                ),
                segmentsPersisted = false,
            )

            fixture.dao.source =
                fixture.dao.source!!.copy(
                    nextRetryAt = 0L,
                    updatedAt = System.currentTimeMillis(),
                )
            fixture.downloader.runQueue()

            assertEquals("COMPLETED", fixture.dao.source?.taskStatus)
            assertFalse(fixture.witness().exists())
            assertEquals("重复恢复不得重新下载同一代外置流", 1, fixture.transfer.externalDownloads)
            assertEquals(externalPath, fixture.dao.externalStream()?.path)
            fixture.assertSixEffects(
                EffectCalls(
                    snapshotWrites = 2,
                    internalMediaStreamWrites = 2,
                    externalMediaStreamWrites = 1,
                    externalDownloads = 1,
                    segmentReads = 2,
                    segmentWrites = 1,
                    trickplayReads = 4,
                    trickplayWrites = 2,
                    imageAttempts = 2,
                )
            )
            val completedArtifacts = fixture.artifacts()

            fixture.leaveCompletedWitness()
            fixture.downloader.recoverOnStartup()
            fixture.downloader.runQueue()

            assertEquals("COMPLETED", fixture.dao.source?.taskStatus)
            assertFalse(fixture.witness().exists())
            fixture.assertSixEffects(
                EffectCalls(
                    snapshotWrites = 3,
                    internalMediaStreamWrites = 3,
                    externalMediaStreamWrites = 1,
                    externalDownloads = 1,
                    segmentReads = 3,
                    segmentWrites = 2,
                    trickplayReads = 6,
                    trickplayWrites = 3,
                    imageAttempts = 3,
                )
            )
            assertEquals(
                "重复恢复不得增加快照 / 流 / 分段 / Trickplay / 图片 / 外置文件产物",
                completedArtifacts,
                fixture.artifacts(),
            )
        } finally {
            fixture.close()
        }
    }

    private data class EffectCalls(
        val snapshotWrites: Int,
        val internalMediaStreamWrites: Int,
        val externalMediaStreamWrites: Int,
        val externalDownloads: Int,
        val segmentReads: Int,
        val segmentWrites: Int,
        val trickplayReads: Int,
        val trickplayWrites: Int,
        val imageAttempts: Int,
    )

    private data class EffectArtifacts(
        val movieIds: List<UUID>,
        val internalMediaStreams: List<Pair<UUID, String>>,
        val externalMediaStreams: List<Pair<UUID, String>>,
        val segments: List<FindroidSegmentDto>,
        val trickplay: List<FindroidTrickplayInfoDto>,
        val files: List<Triple<String, Int, String>>,
        val imageRequests: List<Pair<String, Long>>,
    )

    private fun Fixture.assertSixEffects(
        expected: EffectCalls,
        segmentsPersisted: Boolean = true,
    ) {
        val calls =
            EffectCalls(
                snapshotWrites = dao.snapshotWrites,
                internalMediaStreamWrites = dao.internalMediaStreamWrites,
                externalMediaStreamWrites = dao.externalMediaStreamWrites,
                externalDownloads = transfer.externalDownloads,
                segmentReads = repository.segmentReads,
                segmentWrites = dao.segmentWrites,
                trickplayReads = repository.trickplayReads,
                trickplayWrites = dao.trickplayWrites,
                imageAttempts = imageScheduler.attempts,
            )
        assertEquals("六项副作用调用次数", expected, calls)

        val artifacts = artifacts()
        assertEquals("SNAPSHOT 产物", listOf(ITEM_ID), artifacts.movieIds)
        assertEquals(
            "MEDIA_STREAMS 产物",
            listOf(
                UUID.nameUUIDFromBytes("$SOURCE_ID|VIDEO|index=0".toByteArray()) to
                    checkNotNull(dao.source).path
            ),
            artifacts.internalMediaStreams,
        )
        assertEquals(
            "主媒体终稿内容",
            MEDIA_BYTES.toList(),
            File(checkNotNull(dao.source).path).readBytes().toList(),
        )
        val generation = checkNotNull(dao.source).taskGeneration
        val expectedExternalId =
            DownloadPostCommitRules.externalMediaStreamId(
                sourceId = SOURCE_ID,
                generation = generation,
                streamIndex = 1,
                ordinal = 0,
            )
        assertEquals(
            "EXTERNAL_STREAMS 产物",
            listOf(expectedExternalId to dao.externalStream()!!.path),
            artifacts.externalMediaStreams,
        )
        assertEquals(
            "外置流文件内容",
            SUBTITLE_BYTES.toList(),
            File(dao.externalStream()!!.path).readBytes().toList(),
        )
        assertEquals(
            "SEGMENTS 产物",
            if (segmentsPersisted) {
                listOf(
                    FindroidSegmentDto(
                        itemId = ITEM_ID,
                        type = FindroidSegmentType.INTRO,
                        startTicks = 0L,
                        endTicks = 10L,
                    )
                )
            } else {
                emptyList()
            },
            artifacts.segments,
        )
        assertEquals(
            "TRICKPLAY 产物",
            listOf(
                FindroidTrickplayInfoDto(
                    sourceId = SOURCE_ID,
                    width = 80,
                    height = 45,
                    tileWidth = 1,
                    tileHeight = 1,
                    thumbnailCount = 1,
                    interval = 1_000,
                    bandwidth = 1,
                )
            ),
            artifacts.trickplay,
        )
        val trickplayFiles = trickplayFiles()
        assertEquals("Trickplay 文件数", 2, trickplayFiles.size)
        assertEquals(listOf("0", "1"), trickplayFiles.map { it.name })
        trickplayFiles.forEach { file ->
            assertEquals(
                "Trickplay 文件内容：${file.name}",
                listOf(1.toByte()),
                file.readBytes().toList(),
            )
        }
        assertEquals(
            "IMAGES 产物",
            listOf(SOURCE_ID to generation),
            artifacts.imageRequests,
        )
    }

    private class Fixture {
        private val root = Files.createTempDirectory("downloader-recovery").toFile()
        private val context = TestContext(root)
        val logs = java.util.Collections.synchronizedList(mutableListOf<String>())
        private val tree =
            object : Timber.Tree() {
                override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                    logs += "$message ${t?.javaClass?.simpleName}:${t?.message}"
                }
            }
        val downloads = File(root, "downloads").apply { mkdirs() }
        val dao = RecordingDao()
        val repository = RecordingRepository()
        val transfer = RecordingTransfer()
        val workGateway = RecordingWorkGateway()
        val imageScheduler = RecordingImageScheduler()
        val preferences = FakePreferences()
        val appPreferences = AppPreferences(preferences)
        val downloader = newDownloader()

        init {
            Timber.plant(tree)
            transfer.sourceIdForPath = dao::sourceIdByPath
        }

        fun newDownloader(): DownloaderImpl =
            DownloaderImpl.createForTest(
                context = context,
                database = dao.proxy,
                jellyfinRepository = repository.proxy,
                readerRepository = unusedProxy(ReaderRepository::class.java),
                appPreferences = appPreferences,
                imageCacheStore = testImageCacheStore(context),
                workManager =
                    allocateClass(Class.forName("androidx.work.impl.WorkManagerImpl"))
                        as WorkManager,
                httpClient = allocate(),
                notifications = FakeNotificationGateway(),
                registerNetworkCallback = {},
                externalStorageState = { "mounted" },
                availableBytes = { Long.MAX_VALUE },
                workGateway = workGateway,
                downloadTransfer = transfer,
                imageFinalizationScheduler = imageScheduler,
            )

        suspend fun seedConcurrentMovies(count: Int): List<SeededSource> =
            (0 until count).map { index ->
                val itemId = UUID(1_000L, index.toLong() + 1L)
                val sourceId = "concurrency-$index"
                val source = sourceDto(itemId = itemId, sourceId = sourceId, index = index)
                val repositorySource =
                    FindroidSource(
                        id = sourceId,
                        name = "Concurrent $index",
                        type = FindroidSourceType.REMOTE,
                        path = "https://media.test/$sourceId",
                        size = MEDIA_BYTES.size.toLong(),
                        mediaStreams = repository.mediaStreams,
                        container = "mp4",
                    )
                dao.putSource(source)
                dao.seedMovie(itemId)
                repository.putMediaSource(itemId, repositorySource)
                persistVideoOrigin(
                    sourceId = sourceId,
                    itemId = itemId,
                )
                SeededSource(itemId = itemId, source = source)
            }

        fun seedAdditionalSource(
            index: Int,
            itemId: UUID = ITEM_ID,
            sourceId: String = "source-second",
        ): SeededSource {
            val source = sourceDto(itemId = itemId, sourceId = sourceId, index = index)
            dao.putSource(source)
            return SeededSource(itemId = itemId, source = source)
        }

        fun markerFile(relativePath: String): File =
            File(root, relativePath).apply {
                parentFile?.mkdirs()
                writeText("shared")
            }

        private fun sourceDto(
            itemId: UUID,
            sourceId: String,
            index: Int,
        ): FindroidSourceDto {
            val generation = 1L
            val partial =
                DownloadCompletionCommitRules.downloadTargetFiles(
                        directory = downloads,
                        itemId = itemId.toString(),
                        sourceId = sourceId,
                        generation = generation,
                    )
                    .first
            return FindroidSourceDto(
                id = sourceId,
                itemId = itemId,
                name = "Concurrent $index",
                type = FindroidSourceType.LOCAL,
                path = partial.path,
                taskStatus = DownloadTaskStatus.PENDING.name,
                updatedAt = index.toLong() + 1L,
                downloadedBytes = 0L,
                totalBytes = MEDIA_BYTES.size.toLong(),
                engineVersion = DownloaderImpl.ENGINE_VERSION,
                container = "mp4",
                taskGeneration = generation,
            )
        }

        fun movie(): FindroidMovie {
            val source =
                FindroidSource(
                    id = SOURCE_ID,
                    name = SOURCE_NAME,
                    type = FindroidSourceType.REMOTE,
                    path = MEDIA_URL,
                    size = MEDIA_BYTES.size.toLong(),
                    mediaStreams = repository.mediaStreams,
                    container = "mp4",
                )
            return FindroidMovie(
                id = ITEM_ID,
                name = ITEM_NAME,
                originalTitle = null,
                overview = "",
                sources = listOf(source),
                played = false,
                favorite = false,
                canPlay = true,
                canDownload = true,
                runtimeTicks = 0L,
                playbackPositionTicks = 0L,
                premiereDate = null,
                people = emptyList(),
                genres = emptyList(),
                communityRating = null,
                officialRating = null,
                status = "Released",
                productionYear = null,
                endDate = null,
                trailer = null,
                images = FindroidImages(),
                chapters = emptyList(),
                trickplayInfo =
                    mapOf(
                        SOURCE_ID to
                            com.zhangwenkang.cinefin.models.FindroidTrickplayInfo(
                                width = 80,
                                height = 45,
                                tileWidth = 1,
                                tileHeight = 1,
                                thumbnailCount = 1,
                                interval = 1_000,
                                bandwidth = 1,
                            )
                    ),
                kind = "MOVIE",
            )
        }

        fun episode(
            itemId: UUID = ITEM_ID,
            sourceId: String = SOURCE_ID,
        ): FindroidEpisode =
            FindroidEpisode(
                id = itemId,
                name = ITEM_NAME,
                originalTitle = null,
                overview = "",
                indexNumber = 1,
                indexNumberEnd = null,
                parentIndexNumber = 1,
                sources =
                    listOf(
                        FindroidSource(
                            id = sourceId,
                            name = SOURCE_NAME,
                            type = FindroidSourceType.REMOTE,
                            path = MEDIA_URL,
                            size = MEDIA_BYTES.size.toLong(),
                            mediaStreams = repository.mediaStreams,
                            container = "mp4",
                        )
                    ),
                played = false,
                favorite = false,
                canPlay = true,
                canDownload = true,
                runtimeTicks = 0L,
                playbackPositionTicks = 0L,
                premiereDate = null,
                seriesId = SHOW_ID,
                seriesName = SHOW_NAME,
                seasonId = SEASON_ID,
                seasonName = SEASON_NAME,
                communityRating = null,
                people = emptyList(),
                images = FindroidImages(),
                chapters = emptyList(),
                trickplayInfo = emptyMap(),
            )

        fun episodeSource(id: String = "episode-local-source"): FindroidSource =
            FindroidSource(
                id = id,
                name = "Episode",
                type = FindroidSourceType.LOCAL,
                path = File(root, id).path,
                size = 1L,
                mediaStreams = emptyList(),
                container = "mp4",
            )

        fun persistEpisodeOrigin(
            sourceId: String,
            itemId: UUID = ITEM_ID,
            serverId: String = SERVER_ID,
            userId: UUID = USER_ID,
        ) {
            persistVideoOrigin(sourceId, itemId, serverId, userId)
        }

        fun seedCompletedSource(
            item: FindroidItem,
            source: FindroidSource,
            persistOrigin: Boolean = true,
        ) {
            dao.putSource(
                FindroidSourceDto(
                    id = source.id,
                    itemId = item.id,
                    name = source.name,
                    type = FindroidSourceType.LOCAL,
                    path = source.path,
                    taskStatus = DownloadTaskStatus.COMPLETED.name,
                    updatedAt = 1L,
                    engineVersion = DownloaderImpl.ENGINE_VERSION,
                    container = source.container,
                    taskGeneration = 1L,
                )
            )
            if (persistOrigin) {
                persistEpisodeOrigin(sourceId = source.id, itemId = item.id)
            }
        }

        fun persistVideoOrigin(
            sourceId: String,
            itemId: UUID = ITEM_ID,
            serverId: String = SERVER_ID,
            userId: UUID = USER_ID,
        ) {
            downloader
                .mediaSidecar()
                .put(
                    DownloadMediaRecord(
                        itemId = itemId.toString(),
                        kind = DownloadMediaKind.VIDEO,
                        serverId = serverId,
                        userId = userId.toString(),
                        sourceId = sourceId,
                    )
                )
        }

        fun show(): FindroidShow =
            FindroidShow(
                id = SHOW_ID,
                name = SHOW_NAME,
                originalTitle = null,
                overview = "",
                sources = emptyList(),
                seasons = emptyList(),
                played = false,
                favorite = false,
                canPlay = true,
                canDownload = false,
                unplayedItemCount = null,
                genres = emptyList(),
                people = emptyList(),
                runtimeTicks = 0L,
                communityRating = null,
                officialRating = null,
                status = "Continuing",
                productionYear = null,
                endDate = null,
                trailer = null,
                images = FindroidImages(),
            )

        fun season(): FindroidSeason =
            FindroidSeason(
                id = SEASON_ID,
                name = SEASON_NAME,
                seriesId = SHOW_ID,
                seriesName = SHOW_NAME,
                originalTitle = null,
                overview = "",
                sources = emptyList(),
                indexNumber = 1,
                episodes = emptyList(),
                played = false,
                favorite = false,
                canPlay = true,
                canDownload = false,
                unplayedItemCount = null,
                images = FindroidImages(),
            )

        suspend fun prepareInterruptedCompletion() {
            persistVideoOrigin(SOURCE_ID)
            val target =
                DownloadCompletionCommitRules.downloadTargetFiles(
                    directory = downloads,
                    itemId = ITEM_ID.toString(),
                    sourceId = SOURCE_ID,
                    generation = GENERATION,
                )
            target.first.writeBytes(MEDIA_BYTES)
            runCatching {
                DownloadCompletionCommitRules.commit(
                    partial = target.first,
                    finalFile = target.second,
                    canStart = { true },
                    commitState = { error("模拟 rename 后进程中断") },
                    sourceId = SOURCE_ID,
                    generation = GENERATION,
                )
            }
            dao.source =
                FindroidSourceDto(
                    id = SOURCE_ID,
                    itemId = ITEM_ID,
                    name = ITEM_NAME,
                    type = FindroidSourceType.LOCAL,
                    path = target.second.path,
                    taskStatus = DownloadTaskStatus.COMPLETED.name,
                    updatedAt = 0L,
                    downloadedBytes = MEDIA_BYTES.size.toLong(),
                    totalBytes = MEDIA_BYTES.size.toLong(),
                    engineVersion = DownloaderImpl.ENGINE_VERSION,
                    taskGeneration = GENERATION,
                )
        }

        fun prepareLegacyTargetConflict(): LegacyTargetEvidence {
            val legacyFinal =
                File(downloads, "${ITEM_ID}.${SOURCE_ID}").apply { writeText("old payload") }
            val legacyPartial =
                File(downloads, "${ITEM_ID}.${SOURCE_ID}.download").apply {
                    writeBytes(MEDIA_BYTES)
                }
            val legacyWitness =
                DownloadCompletionCommitRules.witnessFile(legacyPartial, legacyFinal).apply {
                    parentFile?.mkdirs()
                    writeText(
                        listOf(
                                SOURCE_ID,
                                (GENERATION - 1).toString(),
                                legacyPartial.canonicalPath,
                                legacyFinal.canonicalPath,
                            )
                            .joinToString("\n")
                    )
                }
            return LegacyTargetEvidence(legacyFinal, legacyWitness)
        }

        fun seedStaleExternal(): File {
            val staleId =
                DownloadPostCommitRules.externalMediaStreamId(
                    sourceId = SOURCE_ID,
                    generation = GENERATION - 1,
                    streamIndex = 1,
                    ordinal = 0,
                )
            val stale =
                File(downloads, "${ITEM_ID}.${SOURCE_ID}.$staleId").apply {
                    writeText("stale subtitle")
                }
            dao.putMediaStream(
                FindroidMediaStreamDto(
                    id = staleId,
                    sourceId = SOURCE_ID,
                    title = "English",
                    displayTitle = null,
                    language = "eng",
                    type = MediaStreamType.SUBTITLE,
                    codec = "srt",
                    isExternal = true,
                    path = stale.path,
                    channelLayout = null,
                    videoRangeType = null,
                    height = null,
                    width = null,
                    videoDoViTitle = null,
                )
            )
            return stale
        }

        fun witness(): File =
            DownloadCompletionCommitRules.witnessFile(
                partial =
                    DownloadCompletionCommitRules.downloadTargetFiles(
                            directory = downloads,
                            itemId = ITEM_ID.toString(),
                            sourceId = SOURCE_ID,
                            generation = GENERATION,
                        )
                        .first,
                finalFile = File(checkNotNull(dao.source).path),
            )

        fun downloadTarget(): Pair<File, File> =
            DownloadCompletionCommitRules.downloadTargetFiles(
                directory = downloads,
                itemId = ITEM_ID.toString(),
                sourceId = SOURCE_ID,
                generation = GENERATION,
            )

        fun leaveCompletedWitness() {
            val target = downloadTarget()
            dao.source =
                checkNotNull(dao.source)
                    .copy(
                        path = target.second.path,
                        taskStatus = DownloadTaskStatus.COMPLETED.name,
                        failureReason = null,
                        nextRetryAt = 0L,
                    )
            witness()
                .writeText(
                    listOf(
                            SOURCE_ID,
                            GENERATION.toString(),
                            target.first.canonicalPath,
                            target.second.canonicalPath,
                        )
                        .joinToString("\n")
                )
        }

        fun resetEffectCalls() {
            dao.resetEffectCalls()
            repository.resetEffectCalls()
            transfer.externalDownloads = 0
            imageScheduler.attempts = 0
        }

        fun trickplayFiles(): List<File> =
            File(root, "trickplay/$ITEM_ID/$SOURCE_ID").listFiles().orEmpty().sortedBy { it.name }

        fun artifacts(): EffectArtifacts =
            EffectArtifacts(
                movieIds = dao.snapshotIds().sortedBy(UUID::toString),
                internalMediaStreams =
                    dao.internalMediaStreams()
                        .map { stream -> stream.id to stream.path }
                        .sortedBy { it.first.toString() },
                externalMediaStreams =
                    dao.externalMediaStreams()
                        .map { stream -> stream.id to stream.path }
                        .sortedBy { it.first.toString() },
                segments = dao.segments().sortedWith(compareBy({ it.itemId }, { it.type })),
                trickplay = dao.trickplayInfos().sortedBy { it.sourceId },
                files = buildList {
                        dao.externalMediaStreams()
                            .map { File(it.path) }
                            .plus(File(root, "trickplay/$ITEM_ID/$SOURCE_ID").listFiles().orEmpty())
                            .filter { it.isFile }
                            .sortedBy { it.path }
                            .forEach { file ->
                                add(
                                    Triple(
                                        file.relativeTo(root).invariantSeparatorsPath,
                                        file.length().toInt(),
                                        file.readBytes().joinToString("") { byte ->
                                            "%02x".format(byte)
                                        },
                                    )
                                )
                            }
                    }
                        .sortedBy { it.first },
                imageRequests = imageScheduler.requests.toList(),
            )

        fun close() {
            Timber.uproot(tree)
            root.deleteRecursively()
        }
    }

    private data class SeededSource(
        val itemId: UUID,
        val source: FindroidSourceDto,
    )

    private data class LegacyTargetEvidence(val legacyFinal: File, val legacyWitness: File)

    private class RecordingDao {
        private val sources = linkedMapOf<String, FindroidSourceDto>()
        private val deleteBatches = linkedMapOf<String, DownloadDeleteBatchDto>()
        private val deleteReceipts = linkedMapOf<Pair<String, String>, DownloadDeleteReceiptDto>()
        var source: FindroidSourceDto?
            get() = sources[SOURCE_ID]
            set(value) {
                if (value == null) sources.remove(SOURCE_ID) else sources[value.id] = value
            }

        var itemAvailable = true
        var trackParentRows = false
        var remainingEpisodes: List<Any> = emptyList()
        var remainingSeasons: List<Any> = emptyList()
        var seasonDeleteStarted = CompletableDeferred<Unit>()
        var releaseSeasonDelete = CompletableDeferred<Unit>()
        private var blockSeasonDelete = false
        var sourceDeleteStarted = CompletableDeferred<Unit>()
        var releaseSourceDelete = CompletableDeferred<Unit>()
        var concurrentSourceInserted = CompletableDeferred<Unit>()
        var sourceDeletionGeneration = 0L
        private var blockSourceDelete = false
        var sourceReadStarted = CompletableDeferred<Unit>()
        var releaseSourceRead = CompletableDeferred<Unit>()
        private var blockSourceRead = false
        var pendingSourcesSnapshotStarted = CompletableDeferred<Unit>()
        var releasePendingSourcesSnapshot = CompletableDeferred<Unit>()
        private var blockPendingSourcesSnapshot = false
        private val insertedSources = mutableListOf<FindroidSourceDto>()
        val sourceEvents = mutableListOf<String>()
        private val episodeIdsBySeason = linkedMapOf<UUID, MutableSet<UUID>>()
        private val seasonIdsByShow = linkedMapOf<UUID, MutableSet<UUID>>()
        var snapshotWrites = 0
        var showSnapshotWrites = 0
        var seasonSnapshotWrites = 0
        var episodeSnapshotWrites = 0
        var userDataWrites = 0
        var showSnapshot: FindroidShowDto? = null
        var seasonSnapshot: FindroidSeasonDto? = null
        var episodeSnapshot: FindroidEpisodeDto? = null
        var userDataSnapshot: FindroidUserDataDto? = null
        var internalMediaStreamWrites = 0
        var externalMediaStreamWrites = 0
        var segmentWrites = 0
        var trickplayWrites = 0
        private val snapshots = linkedMapOf<UUID, FindroidMovieDto>()
        private val removedUserDataIds = linkedSetOf<UUID>()
        private val mediaStreams = linkedMapOf<UUID, FindroidMediaStreamDto>()
        private val segments = linkedMapOf<Pair<UUID, FindroidSegmentType>, FindroidSegmentDto>()
        private val trickplayInfos = linkedMapOf<String, FindroidTrickplayInfoDto>()
        private val movie =
            FindroidMovieDto(
                id = ITEM_ID,
                serverId = SERVER_ID,
                name = ITEM_NAME,
                originalTitle = null,
                overview = "",
                runtimeTicks = 0L,
                premiereDate = null,
                communityRating = null,
                officialRating = null,
                status = "Released",
                productionYear = null,
                endDate = null,
                chapters = emptyList(),
            )
        private val trickplay =
            FindroidTrickplayInfoDto(
                sourceId = SOURCE_ID,
                width = 80,
                height = 45,
                tileWidth = 1,
                tileHeight = 1,
                thumbnailCount = 1,
                interval = 1_000,
                bandwidth = 1,
            )
        private val userData =
            FindroidUserDataDto(
                userId = USER_ID,
                itemId = ITEM_ID,
                played = false,
                favorite = false,
                playbackPositionTicks = 0L,
            )

        val proxy: ServerDatabaseDao =
            Proxy.newProxyInstance(
                ServerDatabaseDao::class.java.classLoader,
                arrayOf(ServerDatabaseDao::class.java),
                InvocationHandler { _, method, rawArgs ->
                    val args = rawArgs ?: emptyArray()
                    when (method.name) {
                        "getPendingSources" -> {
                            val snapshot =
                                sources.values.filter {
                                    it.taskStatus != "COMPLETED" || it.path.endsWith(".download")
                                }
                            if (blockPendingSourcesSnapshot) {
                                blockPendingSourcesSnapshot = false
                                pendingSourcesSnapshotStarted.complete(Unit)
                                runBlocking { releasePendingSourcesSnapshot.await() }
                            }
                            snapshot
                        }
                        "getAllSources" -> sources.values.toList()
                        "getSource" -> {
                            if (blockSourceRead) {
                                blockSourceRead = false
                                sourceReadStarted.complete(Unit)
                                runBlocking { releaseSourceRead.await() }
                            }
                            sources[args[0] as String]
                        }
                        "insertSource" -> {
                            val inserted = args[0] as FindroidSourceDto
                            insertedSources += inserted
                            sources[inserted.id] = inserted
                            sourceEvents += "INSERT:${inserted.id}:${inserted.taskGeneration}"
                            concurrentSourceInserted.complete(Unit)
                            Unit
                        }
                        "setSourcePath" -> {
                            val id = args[0] as String
                            sources[id]?.let { sources[id] = it.copy(path = args[1] as String) }
                            Unit
                        }
                        "claimSourceRunning" ->
                            updateSource(args) {
                                it.copy(taskStatus = "RUNNING", failureReason = null)
                            }
                        "beginSourcePause" ->
                            updateSourceRow(args) {
                                it.copy(
                                    taskStatus = "PAUSED",
                                    failureReason = null,
                                    taskGeneration = it.taskGeneration + 1,
                                )
                            }
                        "beginSourceDeletion" -> beginSourceDeletion(args)
                        "beginSourceRetry" -> retrySource(args)
                        "setSourceProgressIfCurrent" ->
                            updateSource(args) {
                                it.copy(
                                    downloadedBytes = args[2] as Long,
                                    totalBytes = args[3] as Long,
                                )
                            }
                        "setSourceTaskStatusIfCurrent" ->
                            updateSource(args) {
                                it.copy(
                                    taskStatus = args[2] as String?,
                                    failureReason = args[3] as String?,
                                )
                            }
                        "setSourceRetryIfCurrent" ->
                            updateSource(args) {
                                it.copy(
                                    retryCount = args[2] as Int,
                                    nextRetryAt = args[3] as Long,
                                )
                            }
                        "completeSourceIfCurrent" ->
                            updateSource(args, requireRunning = true) {
                                it.copy(
                                    path = args[2] as String,
                                    taskStatus = "COMPLETED",
                                    failureReason = null,
                                    downloadedBytes = args[3] as Long,
                                    totalBytes = args[4] as Long,
                                    resumeValidator = args[5] as String?,
                                    retryCount = 0,
                                    nextRetryAt = 0L,
                                )
                            }
                        "getMovie" ->
                            (snapshots[args[0] as UUID] ?: movie.takeIf { args[0] == ITEM_ID })
                                ?.takeIf { itemAvailable && episodeSnapshot == null }
                        "getEpisode" ->
                            episodeSnapshot?.takeIf { itemAvailable && it.id == args[0] as UUID }
                        "insertShow" -> {
                            val show = args[0] as FindroidShowDto
                            showSnapshotWrites += 1
                            if (showSnapshot == null) showSnapshot = show
                            Unit
                        }
                        "insertSeason" -> {
                            val season = args[0] as FindroidSeasonDto
                            seasonSnapshotWrites += 1
                            if (seasonSnapshot == null) seasonSnapshot = season
                            if (trackParentRows) {
                                seasonIdsByShow
                                    .getOrPut(season.seriesId, ::linkedSetOf)
                                    .add(season.id)
                            }
                            Unit
                        }
                        "insertEpisode" -> {
                            val episode = args[0] as FindroidEpisodeDto
                            episodeSnapshotWrites += 1
                            episodeSnapshot = episode
                            if (trackParentRows) {
                                episodeIdsBySeason
                                    .getOrPut(episode.seasonId, ::linkedSetOf)
                                    .add(episode.id)
                            }
                            Unit
                        }
                        "getEpisodesBySeasonId" ->
                            if (trackParentRows) {
                                episodeIdsBySeason[args[0] as UUID].orEmpty().map { Any() }
                            } else {
                                remainingEpisodes
                            }
                        "getSeasonsByShowId" ->
                            if (trackParentRows) {
                                seasonIdsByShow[args[0] as UUID].orEmpty().map { Any() }
                            } else {
                                remainingSeasons
                            }
                        "deleteEpisode" -> {
                            if (episodeSnapshot?.id == args[0] as UUID) episodeSnapshot = null
                            if (trackParentRows) {
                                val episodeId = args[0] as UUID
                                episodeIdsBySeason.values.forEach { it.remove(episodeId) }
                            }
                            Unit
                        }
                        "deleteSeason" -> {
                            if (blockSeasonDelete) {
                                blockSeasonDelete = false
                                seasonDeleteStarted.complete(Unit)
                                runBlocking { releaseSeasonDelete.await() }
                            }
                            if (trackParentRows) {
                                val seasonId = args[0] as UUID
                                seasonIdsByShow.values.forEach { it.remove(seasonId) }
                                episodeIdsBySeason.remove(seasonId)
                            }
                            Unit
                        }
                        "deleteShow" -> {
                            if (trackParentRows) {
                                seasonIdsByShow.remove(args[0] as UUID)
                            }
                            Unit
                        }
                        "deleteMovie" -> {
                            snapshots.remove(args[0] as UUID)
                            Unit
                        }
                        "deleteMediaStreamsBySourceId" -> {
                            val sourceId = args[0] as String
                            mediaStreams.entries.removeIf { (_, stream) ->
                                stream.sourceId == sourceId
                            }
                            sourceEvents += "DELETE_MEDIA:$sourceId"
                            Unit
                        }
                        "deleteSource" -> {
                            val sourceId = args[0] as String
                            sources.remove(sourceId)
                            sourceEvents += "DELETE_SOURCE:$sourceId"
                            Unit
                        }
                        "deleteUserData" -> {
                            removedUserDataIds += args[0] as UUID
                            Unit
                        }
                        "getUserDataOrCreateNew" -> userData
                        "getSources" -> sources.values.filter { it.itemId == args[0] as UUID }
                        "getTrickplayInfo" -> trickplay.takeIf { args[0] == SOURCE_ID }
                        "insertMovie" -> {
                            snapshotWrites += 1
                            val snapshot = args[0] as FindroidMovieDto
                            snapshots.putIfAbsent(snapshot.id, snapshot)
                            Unit
                        }
                        "insertUserData" -> {
                            userDataWrites += 1
                            userDataSnapshot = args[0] as FindroidUserDataDto
                            Unit
                        }
                        "insertMediaStream" -> {
                            val stream = args[0] as FindroidMediaStreamDto
                            if (stream.isExternal) {
                                externalMediaStreamWrites += 1
                            } else {
                                internalMediaStreamWrites += 1
                            }
                            mediaStreams[stream.id] = stream
                            Unit
                        }
                        "getMediaStreamsBySourceId" ->
                            mediaStreams.values.filter { it.sourceId == args[0] }
                        "setMediaStreamPath" -> {
                            val id = args[0] as UUID
                            mediaStreams[id]?.let {
                                mediaStreams[id] = it.copy(path = args[1] as String)
                            }
                            Unit
                        }
                        "deleteMediaStream" -> {
                            mediaStreams.remove(args[0] as UUID)
                            Unit
                        }
                        "insertSegment" -> {
                            val segment = args[0] as FindroidSegmentDto
                            segmentWrites += 1
                            segments[segment.itemId to segment.type] = segment
                            Unit
                        }
                        "insertTrickplayInfo" -> {
                            val info = args[0] as FindroidTrickplayInfoDto
                            trickplayWrites += 1
                            trickplayInfos[info.sourceId] = info
                            Unit
                        }
                        "beginDownloadDeleteBatch" -> {
                            val batch = args[0] as DownloadDeleteBatchDto
                            deleteBatches[batch.id] = batch
                            (args[1] as List<*>)
                                .map { it as DownloadDeleteReceiptDto }
                                .forEach { receipt ->
                                    deleteReceipts[receipt.batchId to receipt.canonicalKey] =
                                        receipt
                                }
                            Unit
                        }
                        "insertDownloadDeleteReceipt" -> {
                            val receipt = args[0] as DownloadDeleteReceiptDto
                            deleteReceipts[receipt.batchId to receipt.canonicalKey] = receipt
                            Unit
                        }
                        "finishDownloadDeleteBatch" -> {
                            val batchId = args[0] as String
                            deleteBatches[batchId]?.let {
                                deleteBatches[batchId] = it.copy(completedAt = args[1] as Long)
                            }
                            Unit
                        }
                        "getIncompleteDownloadDeleteBatches" ->
                            deleteBatches.values.filter { it.completedAt == null }
                        "getDownloadDeleteReceipts" ->
                            deleteReceipts.values
                                .filter { it.batchId == args[0] as String }
                                .sortedBy { it.ordinal }
                        "getReceivedDownloadIntents" -> emptyList<Any>()
                        else -> error("unexpected DAO call: ${method.name}")
                    }
                },
            ) as ServerDatabaseDao

        fun externalStream(): FindroidMediaStreamDto? =
            mediaStreams.values.firstOrNull { it.isExternal }

        fun armSeasonDeleteGate() {
            blockSeasonDelete = true
            seasonDeleteStarted = CompletableDeferred()
            releaseSeasonDelete = CompletableDeferred()
        }

        fun armSourceDeletionGate() {
            blockSourceDelete = true
            sourceDeleteStarted = CompletableDeferred()
            releaseSourceDelete = CompletableDeferred()
            concurrentSourceInserted = CompletableDeferred()
            sourceDeletionGeneration = 0L
        }

        fun armSourceReadGate() {
            blockSourceRead = true
            sourceReadStarted = CompletableDeferred()
            releaseSourceRead = CompletableDeferred()
        }

        fun armPendingSourcesSnapshotGate() {
            blockPendingSourcesSnapshot = true
            pendingSourcesSnapshotStarted = CompletableDeferred()
            releasePendingSourcesSnapshot = CompletableDeferred()
        }

        fun lastInsertedSource(sourceId: String): FindroidSourceDto? = insertedSources.lastOrNull {
            it.id == sourceId
        }

        fun sourceInsertCount(sourceId: String): Int = insertedSources.count { it.id == sourceId }

        fun hasEpisode(
            seasonId: UUID,
            episodeId: UUID,
        ): Boolean = episodeIdsBySeason[seasonId].orEmpty().contains(episodeId)

        fun hasSeason(
            showId: UUID,
            seasonId: UUID,
        ): Boolean = seasonIdsByShow[showId].orEmpty().contains(seasonId)

        fun snapshotIds(): List<UUID> = snapshots.keys.toList()

        fun sourceIds(): List<String> = sources.keys.toList()

        fun sourceById(id: String): FindroidSourceDto? = sources[id]

        fun sourceIdByPath(path: String): String? =
            sources.values.firstOrNull { it.path == path }?.id

        fun pausedSourceIds(): List<String> =
            sources.values.filter { it.taskStatus == "PAUSED" }.map { it.id }

        fun runningSourceIds(): List<String> =
            sources.values.filter { it.taskStatus == "RUNNING" }.map { it.id }

        fun deletedUserDataIds(): Set<UUID> = removedUserDataIds

        fun putSource(source: FindroidSourceDto) {
            sources[source.id] = source
        }

        fun seedPendingDeleteBatch(
            batchId: String,
            itemId: UUID,
            sourceId: String,
            expectedGeneration: Long?,
        ) {
            deleteBatches[batchId] =
                DownloadDeleteBatchDto(
                    id = batchId,
                    requestedCount = 1,
                    createdAt = 1L,
                )
            val key =
                DownloadDeletionIdentity.canonicalKey(
                    DownloadDeleteRequest(
                        itemId = itemId,
                        sourceId = sourceId,
                        mediaKind = DownloadMediaKind.VIDEO,
                        serverId = SERVER_ID,
                        userId = USER_ID.toString(),
                    )
                )
            deleteReceipts[batchId to key] =
                DownloadDeleteReceiptDto(
                    batchId = batchId,
                    canonicalKey = key,
                    itemId = itemId,
                    sourceId = sourceId,
                    serverId = SERVER_ID,
                    userId = USER_ID.toString(),
                    ordinal = 0,
                    mediaKind = DownloadMediaKind.VIDEO.name,
                    outcome = DownloadDeleteOutcome.PENDING.name,
                    detail = expectedGeneration?.let { "generation=$it" },
                    updatedAt = 1L,
                )
        }

        fun deleteBatch(batchId: String): DownloadDeleteBatchDto? = deleteBatches[batchId]

        fun deleteReceipt(
            batchId: String,
            canonicalKey: String,
        ): DownloadDeleteReceiptDto? = deleteReceipts[batchId to canonicalKey]

        fun deleteReceipts(batchId: String): List<DownloadDeleteReceiptDto> =
            deleteReceipts.values.filter { it.batchId == batchId }

        fun seedMovie(itemId: UUID) {
            snapshots[itemId] = movie.copy(id = itemId, name = "Concurrent $itemId")
        }

        fun internalMediaStreams(): List<FindroidMediaStreamDto> =
            mediaStreams.values.filterNot { it.isExternal }

        fun externalMediaStreams(): List<FindroidMediaStreamDto> =
            mediaStreams.values.filter { it.isExternal }

        fun segments(): List<FindroidSegmentDto> = segments.values.toList()

        fun trickplayInfos(): List<FindroidTrickplayInfoDto> = trickplayInfos.values.toList()

        fun resetEffectCalls() {
            snapshotWrites = 0
            showSnapshotWrites = 0
            seasonSnapshotWrites = 0
            episodeSnapshotWrites = 0
            userDataWrites = 0
            internalMediaStreamWrites = 0
            externalMediaStreamWrites = 0
            segmentWrites = 0
            trickplayWrites = 0
        }

        fun putMediaStream(stream: FindroidMediaStreamDto) {
            mediaStreams[stream.id] = stream
        }

        private fun beginSourceDeletion(args: Array<Any?>): FindroidSourceDto? {
            val sourceId = args[0] as String
            if (sources[sourceId]?.taskStatus == DownloadTaskStatus.DELETING.name) return null
            val deleting =
                updateSourceRow(args) {
                    it.copy(
                        taskStatus = "DELETING",
                        failureReason = null,
                        taskGeneration = it.taskGeneration + 1,
                    )
                } ?: return null
            if (blockSourceDelete) {
                blockSourceDelete = false
                sourceDeletionGeneration = deleting.taskGeneration
                sourceDeleteStarted.complete(Unit)
                runBlocking { releaseSourceDelete.await() }
            }
            return deleting
        }

        private fun retrySource(args: Array<Any?>): FindroidSourceDto? {
            val current = sources[args[0] as String] ?: return null
            if (current.taskGeneration != args[1] || current.taskStatus != "FAILED") {
                return null
            }
            return current
                .copy(
                    taskStatus = "PENDING",
                    failureReason = null,
                    retryCount = 0,
                    nextRetryAt = 0L,
                    taskGeneration = current.taskGeneration + 1,
                )
                .also { sources[it.id] = it }
        }

        private fun updateSource(
            args: Array<Any?>,
            requireRunning: Boolean = false,
            transform: (FindroidSourceDto) -> FindroidSourceDto,
        ): Int {
            val current = sources[args[0] as String] ?: return 0
            if (
                current.taskGeneration != args[1] ||
                    (requireRunning && current.taskStatus != "RUNNING")
            ) {
                return 0
            }
            sources[current.id] = transform(current)
            return 1
        }

        private fun updateSourceRow(
            args: Array<Any?>,
            transform: (FindroidSourceDto) -> FindroidSourceDto,
        ): FindroidSourceDto? {
            val current = sources[args[0] as String] ?: return null
            if (current.taskGeneration != args[1]) return null
            return transform(current).also { sources[it.id] = it }
        }
    }

    private class RecordingRepository {
        val mediaStreams =
            listOf(
                FindroidMediaStream(
                    title = "Main",
                    displayTitle = null,
                    language = "und",
                    type = MediaStreamType.VIDEO,
                    codec = "h264",
                    isExternal = false,
                    path = null,
                    channelLayout = null,
                    videoRangeType = null,
                    height = 720,
                    width = 1_280,
                    videoDoViTitle = null,
                    index = 0,
                ),
                FindroidMediaStream(
                    title = "English",
                    displayTitle = null,
                    language = "eng",
                    type = MediaStreamType.SUBTITLE,
                    codec = "srt",
                    isExternal = true,
                    path = SUBTITLE_URL,
                    channelLayout = null,
                    videoRangeType = null,
                    height = null,
                    width = null,
                    videoDoViTitle = null,
                    index = 1,
                ),
            )
        var segmentFailuresRemaining = 0
        var segmentReads = 0
        var trickplayReads = 0
        var userId = USER_ID
        var userIdReads = 0
        var showReads = 0
        var seasonReads = 0
        private val mediaSourcesByItemId = linkedMapOf<UUID, FindroidSource>()

        fun resetEffectCalls() {
            segmentReads = 0
            trickplayReads = 0
        }

        fun putMediaSource(itemId: UUID, source: FindroidSource) {
            mediaSourcesByItemId[itemId] = source
        }

        val proxy: JellyfinRepository =
            Proxy.newProxyInstance(
                JellyfinRepository::class.java.classLoader,
                arrayOf(JellyfinRepository::class.java),
                InvocationHandler { _, method, args ->
                    when (method.name) {
                        "getMediaSources" ->
                            listOf(
                                mediaSourcesByItemId[args[0] as UUID]
                                    ?: FindroidSource(
                                        id = SOURCE_ID,
                                        name = SOURCE_NAME,
                                        type = FindroidSourceType.REMOTE,
                                        path = MEDIA_URL,
                                        size = MEDIA_BYTES.size.toLong(),
                                        mediaStreams = mediaStreams,
                                        container = "mp4",
                                    )
                            )
                        "getSegments" -> {
                            segmentReads += 1
                            if (segmentFailuresRemaining > 0) {
                                segmentFailuresRemaining -= 1
                                error("segments unavailable")
                            }
                            listOf(FindroidSegment(FindroidSegmentType.INTRO, 0L, 10L))
                        }
                        "getTrickplayData" -> {
                            trickplayReads += 1
                            byteArrayOf(1)
                        }
                        "getBaseUrl" -> "https://example.test"
                        "getAccessToken" -> "token"
                        "getUserId" -> {
                            userIdReads += 1
                            userId
                        }
                        "getShow" -> {
                            showReads += 1
                            FindroidShow(
                                id = SHOW_ID,
                                name = SHOW_NAME,
                                originalTitle = null,
                                overview = "",
                                sources = emptyList(),
                                seasons = emptyList(),
                                played = false,
                                favorite = false,
                                canPlay = true,
                                canDownload = false,
                                unplayedItemCount = null,
                                genres = emptyList(),
                                people = emptyList(),
                                runtimeTicks = 0L,
                                communityRating = null,
                                officialRating = null,
                                status = "Continuing",
                                productionYear = null,
                                endDate = null,
                                trailer = null,
                                images = FindroidImages(),
                            )
                        }
                        "getSeason" -> {
                            seasonReads += 1
                            FindroidSeason(
                                id = SEASON_ID,
                                name = SEASON_NAME,
                                seriesId = SHOW_ID,
                                seriesName = SHOW_NAME,
                                originalTitle = null,
                                overview = "",
                                sources = emptyList(),
                                indexNumber = 1,
                                episodes = emptyList(),
                                played = false,
                                favorite = false,
                                canPlay = true,
                                canDownload = false,
                                unplayedItemCount = null,
                                images = FindroidImages(),
                            )
                        }
                        else -> error("unexpected repository call: ${method.name}")
                    }
                },
            ) as JellyfinRepository
    }

    private class RecordingTransfer : DownloadTransfer {
        var externalDownloads = 0
        var sourceIdForPath: (String) -> String? = { null }
        private val started = CopyOnWriteArrayList<String>()
        private var held = false
        private var releaseGate = CompletableDeferred<Unit>()

        fun holdDownloads() {
            held = true
            releaseGate = CompletableDeferred()
        }

        fun release() {
            held = false
            releaseGate.complete(Unit)
        }

        fun startedPaths(): List<String> = started.toList()

        fun startedSourceIds(): List<String> = started.mapNotNull { path -> sourceIdForPath(path) }

        suspend fun awaitStarted(count: Int): Boolean =
            withTimeoutOrNull(2_000L) {
                while (started.size < count) delay(10L)
                true
            } ?: false

        override suspend fun download(request: DownloadTransferRequest): DownloadAttemptResult {
            val bytes =
                if (request.url == SUBTITLE_URL) {
                    externalDownloads += 1
                    SUBTITLE_BYTES
                } else {
                    started += request.target.path
                    request.target.parentFile?.mkdirs()
                    request.target.writeText("partial")
                    MEDIA_BYTES
                }
            if (held) releaseGate.await()
            request.target.parentFile?.mkdirs()
            request.target.writeBytes(bytes)
            return DownloadAttemptResult(
                downloadedBytes = bytes.size.toLong(),
                totalBytes = bytes.size.toLong(),
                validator = "etag",
            )
        }
    }

    private class RecordingWorkGateway : DownloadWorkGateway {
        val enqueuedNames = mutableListOf<String>()
        val cancelledNames = mutableListOf<String>()
        val uniqueRequests = mutableListOf<Triple<String, ExistingWorkPolicy, OneTimeWorkRequest>>()

        override suspend fun uniqueWorkInfos(name: String): List<WorkInfo> = emptyList()

        override fun enqueueUnique(
            name: String,
            policy: ExistingWorkPolicy,
            request: OneTimeWorkRequest,
        ) {
            enqueuedNames += name
            uniqueRequests += Triple(name, policy, request)
        }

        override fun cancelUnique(name: String) {
            cancelledNames += name
        }

        override fun enqueue(request: OneTimeWorkRequest) {
            enqueuedNames += request.id.toString()
        }
    }

    private class RecordingImageScheduler : DownloadImageFinalizationScheduler {
        var attempts = 0
        val requests = mutableListOf<Pair<String, Long>>()
        val parentItemIds = mutableListOf<UUID>()
        val parentOwners = mutableListOf<ImageDownloadParentOwner>()
        val cancelledParentItemIds = mutableListOf<UUID>()
        val cancelledParentOwners = mutableListOf<ImageDownloadParentOwner>()
        val activeParentOwners = CopyOnWriteArraySet<ImageDownloadParentOwner>()
        val activeParentItemIds: Set<UUID>
            get() = activeParentOwners.mapTo(mutableSetOf()) { it.itemId }

        var parentScheduled = CompletableDeferred<Unit>()
        private val scheduled = mutableSetOf<String>()

        override suspend fun schedule(item: FindroidItem, sourceId: String, generation: Long) {
            attempts += 1
            if (!scheduled.add("$sourceId|$generation")) return
            requests += sourceId to generation
        }

        override suspend fun scheduleParent(
            item: FindroidItem,
            owner: ImageDownloadParentOwner,
        ) {
            parentItemIds += item.id
            parentOwners += owner
            activeParentOwners += owner
            parentScheduled.complete(Unit)
        }

        override suspend fun cancelParent(owner: ImageDownloadParentOwner) {
            cancelledParentItemIds += owner.itemId
            cancelledParentOwners += owner
            activeParentOwners -= owner
        }

        fun resetParentObservations() {
            parentItemIds.clear()
            parentOwners.clear()
            cancelledParentItemIds.clear()
            cancelledParentOwners.clear()
            activeParentOwners.clear()
            parentScheduled = CompletableDeferred()
        }
    }

    private class FakeNotificationGateway : DownloadNotificationGateway {
        override fun buildForeground(task: DownloadTask?, activeCount: Int): Notification =
            allocate()

        override fun notifyForeground(task: DownloadTask?, activeCount: Int) = Unit

        override fun notifyCompleted(task: DownloadTask) = Unit

        override fun notifyFailed(task: DownloadTask) = Unit
    }

    private class FakePreferences : SharedPreferences {
        var currentServer = SERVER_ID
        var currentServerReads = 0
        private val values = linkedMapOf<String, Any?>()
        private val listeners =
            CopyOnWriteArraySet<SharedPreferences.OnSharedPreferenceChangeListener>()

        override fun getAll(): MutableMap<String, *> = LinkedHashMap(values)

        override fun getString(key: String?, defValue: String?): String? =
            if (key == "pref_current_server") {
                currentServerReads += 1
                currentServer
            } else {
                defValue
            }

        override fun getStringSet(
            key: String?,
            defValues: MutableSet<String>?,
        ): MutableSet<String>? =
            @Suppress("UNCHECKED_CAST") (values[key] as? MutableSet<String>) ?: defValues

        override fun getInt(key: String?, defValue: Int): Int = (values[key] as? Int) ?: defValue

        override fun getLong(key: String?, defValue: Long): Long =
            (values[key] as? Long) ?: defValue

        override fun getFloat(key: String?, defValue: Float): Float =
            (values[key] as? Float) ?: defValue

        override fun getBoolean(key: String?, defValue: Boolean): Boolean =
            (values[key] as? Boolean) ?: defValue

        override fun contains(key: String?): Boolean = values.containsKey(key)

        override fun edit(): SharedPreferences.Editor = FakeEditor(this)

        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?
        ) {
            if (listener != null) listeners += listener
        }

        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?
        ) {
            if (listener != null) listeners -= listener
        }

        fun applyEdits(
            updated: Map<String, Any?>,
            removed: Set<String>,
            clear: Boolean,
        ) {
            val changed = linkedSetOf<String>()
            if (clear) {
                changed += values.keys
                values.clear()
            }
            removed.forEach { key -> if (values.remove(key) != null) changed += key }
            updated.forEach { (key, value) ->
                values[key] = value
                changed += key
            }
            changed.forEach { key -> listeners.forEach { it.onSharedPreferenceChanged(this, key) } }
        }
    }

    private class FakeEditor(private val preferences: FakePreferences) : SharedPreferences.Editor {
        private val updated = linkedMapOf<String, Any?>()
        private val removed = linkedSetOf<String>()
        private var clear = false

        override fun putString(key: String?, value: String?): SharedPreferences.Editor {
            key?.let { updated[it] = value }
            return this
        }

        override fun putStringSet(
            key: String?,
            values: MutableSet<String>?,
        ): SharedPreferences.Editor {
            key?.let { updated[it] = values }
            return this
        }

        override fun putInt(key: String?, value: Int): SharedPreferences.Editor {
            key?.let { updated[it] = value }
            return this
        }

        override fun putLong(key: String?, value: Long): SharedPreferences.Editor {
            key?.let { updated[it] = value }
            return this
        }

        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor {
            key?.let { updated[it] = value }
            return this
        }

        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor {
            key?.let { updated[it] = value }
            return this
        }

        override fun remove(key: String?): SharedPreferences.Editor {
            key?.let { removed += it }
            return this
        }

        override fun clear(): SharedPreferences.Editor {
            clear = true
            return this
        }

        override fun commit(): Boolean {
            preferences.applyEdits(updated, removed, clear)
            updated.clear()
            removed.clear()
            clear = false
            return true
        }

        override fun apply() {
            commit()
        }
    }

    private class TestContext(private val root: File) : ContextWrapper(null) {
        override fun getFilesDir(): File = root

        override fun getExternalFilesDirs(type: String?): Array<File> = arrayOf(root)
    }

    private companion object {
        val ITEM_ID: UUID = UUID(0L, 11L)
        val OTHER_ITEM_ID: UUID = UUID(0L, 15L)
        val SIBLING_ITEM_ID: UUID = UUID(0L, 17L)
        val USER_ID: UUID = UUID(0L, 12L)
        val OTHER_USER_ID: UUID = UUID(0L, 16L)
        val SHOW_ID: UUID = UUID(0L, 13L)
        val SEASON_ID: UUID = UUID(0L, 14L)
        const val SOURCE_ID = "source-recovery"
        const val SIBLING_SOURCE_ID = "source-sibling"
        const val SOURCE_NAME = "Recovery Source"
        const val ITEM_NAME = "Recovery Movie"
        const val SHOW_NAME = "Recovery Show"
        const val SEASON_NAME = "Recovery Season"
        const val SERVER_ID = "server-a"
        const val GENERATION = 5L
        const val MEDIA_URL = "https://media.test/movie"
        const val SUBTITLE_URL = "https://media.test/subtitle"
        val MEDIA_BYTES = "media-payload".toByteArray()
        val SUBTITLE_BYTES = "subtitle-payload".toByteArray()

        fun <T : Any> unusedProxy(type: Class<T>): T =
            type.cast(
                Proxy.newProxyInstance(
                    type.classLoader,
                    arrayOf(type),
                    InvocationHandler { _, method, _ ->
                        error("unexpected ${type.simpleName}.${method.name}")
                    },
                )
            )!!

        val unsafe: Unsafe =
            Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
                as Unsafe

        inline fun <reified T : Any> allocate(): T = unsafe.allocateInstance(T::class.java) as T

        fun allocateClass(type: Class<*>): Any = unsafe.allocateInstance(type)

        fun testImageCacheStore(context: ContextWrapper): ImageCacheStore {
            val constructor =
                ImageCacheStore::class.java.declaredConstructors.first { it.parameterCount == 2 }
            constructor.isAccessible = true
            return constructor.newInstance(context, { null }) as ImageCacheStore
        }
    }
}
