package com.zhangwenkang.cinefin.utils

import android.app.Notification
import android.content.ContextWrapper
import android.net.ConnectivityManager
import androidx.work.WorkManager
import com.zhangwenkang.cinefin.database.ServerDatabaseDao
import com.zhangwenkang.cinefin.models.DownloadDeleteReceiptDto
import com.zhangwenkang.cinefin.models.DownloadIntentDto
import com.zhangwenkang.cinefin.models.FindroidSourceDto
import com.zhangwenkang.cinefin.models.FindroidSourceType
import com.zhangwenkang.cinefin.repository.JellyfinRepository
import com.zhangwenkang.cinefin.repository.ReaderRepository
import java.io.File
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import sun.misc.Unsafe

/**
 * P2-A 回归证据：从真实 [DownloaderImpl] 的公开取消 / 批量删除入口出发，观察 fake DAO 最终收到的 CAS 方法。
 *
 * 这里不复制生产分派 lambda。即使有人再次交换 DownloaderImpl 中的 cancellation / deletion 接线，测试也会在 DAO 方法名或 batch
 * receipt 上失败。
 */
class DownloadIntentMutationsTest {

    @Test
    fun `FAILED intent batch delete uses deletion CAS and succeeds`() = runBlocking {
        val fixture = Fixture(status = "FAILED", generation = 6L)

        val result =
            fixture.downloader.deleteDownloads(
                listOf(
                    DownloadDeleteRequest(
                        itemId = ITEM_ID,
                        sourceId = intentSourceId(ITEM_ID),
                        mediaKind = DownloadMediaKind.MUSIC,
                        serverId = "server-a",
                        userId = USER_ID.toString(),
                    )
                )
            )

        assertEquals(
            "DAO calls=${fixture.dao.calls}, detail=${result.receipts.single().detail}, " +
                "expected=${fixture.dao.lastDeleteExpectedGeneration}, " +
                "actual=${fixture.dao.lastDeleteActualGeneration}, " +
                "mutation=${fixture.dao.lastMutationReturn?.taskGeneration}/" +
                fixture.dao.lastMutationReturn?.status,
            DownloadDeleteOutcome.SUCCEEDED,
            result.receipts.single().outcome,
        )
        assertTrue("beginDownloadIntentDeletion" in fixture.dao.calls)
        assertTrue("markDownloadIntentDeletionIfCurrent" in fixture.dao.calls)
        assertTrue("deleteDownloadIntentIfCurrent" in fixture.dao.calls)
        assertFalse("cancelDownloadIntent" in fixture.dao.calls)
        assertTrue("beginDownloadDeleteBatch" in fixture.dao.calls)
        assertTrue("insertDownloadDeleteReceipt" in fixture.dao.calls)
        assertTrue("finishDownloadDeleteBatch" in fixture.dao.calls)
        assertEquals("SUCCEEDED", fixture.dao.successfulReceipt?.outcome)
        assertEquals(
            intentDeletionCasOwnershipDetail(
                generation = 7L,
                ownershipToken =
                    requireNotNull(fixture.dao.successfulReceipt).let { receipt ->
                        downloadDeleteOwnershipToken(
                            batchId = receipt.batchId,
                            canonicalKey = receipt.canonicalKey,
                        )
                    },
            ),
            fixture.dao.lastDeleteFailureDetail,
        )
        assertEquals(1, fixture.services.networkCallbackRegistrationCount)
    }

    @Test
    fun `RECEIVED intent cancel uses cancellation CAS`() = runBlocking {
        val fixture = Fixture(status = "RECEIVED", generation = 3L)

        val cancelled =
            fixture.downloader.cancelTask(
                DownloadTask(
                    itemId = ITEM_ID,
                    sourceId = intentSourceId(ITEM_ID),
                    name = "Track",
                    path = "",
                    downloadId = null,
                    status = DownloadTaskStatus.PENDING,
                    failureReason = null,
                    downloadedBytes = 0L,
                    totalBytes = 0L,
                    updatedAt = 0L,
                    mediaKind = DownloadMediaKind.MUSIC,
                    taskGeneration = 3L,
                    serverId = "server-a",
                    userId = USER_ID.toString(),
                )
            )

        assertTrue("DAO calls=${fixture.dao.calls}", cancelled)
        assertTrue("beginDownloadIntentCancellation" in fixture.dao.calls)
        assertTrue("cancelDownloadIntent" in fixture.dao.calls)
        assertFalse("markDownloadIntentDeletionIfCurrent" in fixture.dao.calls)
        assertFalse("deleteDownloadIntentIfCurrent" in fixture.dao.calls)
        assertEquals("FAILED", fixture.dao.intent?.status)
        assertEquals("CANCELLED", fixture.dao.intent?.failureReason)
        assertEquals(null, fixture.dao.intent?.failureDetail)
        assertEquals(4L, fixture.dao.intent?.taskGeneration)
        assertEquals(1, fixture.services.networkCallbackRegistrationCount)
    }

    @Test
    fun `RECEIVED intent batch delete uses deletion CAS and succeeds`() = runBlocking {
        val fixture = Fixture(status = "RECEIVED", generation = 2L)

        val result =
            fixture.downloader.deleteDownloads(
                listOf(
                    DownloadDeleteRequest(
                        itemId = ITEM_ID,
                        sourceId = intentSourceId(ITEM_ID),
                        mediaKind = DownloadMediaKind.MUSIC,
                        serverId = "server-a",
                        userId = USER_ID.toString(),
                    )
                )
            )

        assertEquals(
            "DAO calls=${fixture.dao.calls}, detail=${result.receipts.single().detail}, " +
                "expected=${fixture.dao.lastDeleteExpectedGeneration}, " +
                "actual=${fixture.dao.lastDeleteActualGeneration}, " +
                "mutation=${fixture.dao.lastMutationReturn?.taskGeneration}/" +
                fixture.dao.lastMutationReturn?.status,
            DownloadDeleteOutcome.SUCCEEDED,
            result.receipts.single().outcome,
        )
        assertTrue("beginDownloadIntentDeletion" in fixture.dao.calls)
        assertTrue("markDownloadIntentDeletionIfCurrent" in fixture.dao.calls)
        assertTrue("deleteDownloadIntentIfCurrent" in fixture.dao.calls)
        assertFalse("cancelDownloadIntent" in fixture.dao.calls)
        assertEquals("SUCCEEDED", fixture.dao.successfulReceipt?.outcome)
        assertEquals(1, fixture.services.networkCallbackRegistrationCount)
    }

    @Test
    fun `cancel matrix covers received resolving and source created with monotonic generation`() =
        runBlocking {
            listOf("RECEIVED", "RESOLVING", "SOURCE_CREATED").forEachIndexed { index, status ->
                val generation = 10L + index
                val fixture = Fixture(status = status, generation = generation)

                val cancelled =
                    fixture.downloader.cancelTask(
                        DownloadTask(
                            itemId = ITEM_ID,
                            sourceId = intentSourceId(ITEM_ID),
                            name = "Track",
                            path = "",
                            downloadId = null,
                            status = DownloadTaskStatus.PENDING,
                            failureReason = null,
                            downloadedBytes = 0L,
                            totalBytes = 0L,
                            updatedAt = 0L,
                            mediaKind = DownloadMediaKind.MUSIC,
                            taskGeneration = generation,
                        )
                    )

                assertTrue("$status cancel 必须成功：calls=${fixture.dao.calls}", cancelled)
                assertEquals("FAILED", fixture.dao.intent?.status)
                assertEquals("CANCELLED", fixture.dao.intent?.failureReason)
                assertEquals(generation + 1L, fixture.dao.intent?.taskGeneration)
                assertFalse(
                    "cancel 后旧代迟到完成不得复活",
                    fixture.dao.acceptLateSourceCreated(ITEM_ID, generation),
                )
                assertEquals("FAILED", fixture.dao.intent?.status)
                assertEquals(generation + 1L, fixture.dao.intent?.taskGeneration)
            }
        }

    @Test
    fun `delete matrix covers received resolving and source created`() = runBlocking {
        listOf("RECEIVED", "RESOLVING", "SOURCE_CREATED").forEachIndexed { index, status ->
            val generation = 20L + index
            val fixture = Fixture(status = status, generation = generation)

            val result =
                fixture.downloader.deleteDownloads(
                    listOf(
                        DownloadDeleteRequest(
                            itemId = ITEM_ID,
                            sourceId = intentSourceId(ITEM_ID),
                            mediaKind = DownloadMediaKind.MUSIC,
                            serverId = "server-a",
                            userId = USER_ID.toString(),
                        )
                    )
                )

            assertEquals(
                "$status delete 必须成功：calls=${fixture.dao.calls}, " +
                    "detail=${result.receipts.single().detail}",
                DownloadDeleteOutcome.SUCCEEDED,
                result.receipts.single().outcome,
            )
            assertEquals(null, fixture.dao.intent)
            assertEquals(generation + 1L, fixture.dao.lastDeleteExpectedGeneration)
        }
    }

    @Test
    fun `source cancellation refusal surfaces FAILED for the intent action`() = runBlocking {
        val fixture =
            Fixture(
                status = "SOURCE_CREATED",
                generation = 3L,
                withSource = true,
                denySourceCancellation = true,
            )
        fixture.dao.seedAdditionalSource("mutation-source-2")

        val cancelled =
            fixture.downloader.cancelTask(
                DownloadTask(
                    itemId = ITEM_ID,
                    sourceId = intentSourceId(ITEM_ID),
                    name = "Track",
                    path = "",
                    downloadId = null,
                    status = DownloadTaskStatus.PENDING,
                    failureReason = null,
                    downloadedBytes = 0L,
                    totalBytes = 0L,
                    updatedAt = 0L,
                    mediaKind = DownloadMediaKind.MUSIC,
                    taskGeneration = 3L,
                )
            )

        assertFalse(
            "任一 source 取消未确认时不得把 intent cancel 报成成功：${fixture.dao.calls}",
            cancelled,
        )
        assertEquals(
            listOf(SOURCE_ID, "mutation-source-2"),
            fixture.dao.sourceCancellationAttempts,
        )
        assertEquals(DownloadTaskStatus.PENDING.name, fixture.dao.source?.taskStatus)
        assertEquals(3L, fixture.dao.source?.taskGeneration)
        assertEquals(
            DownloadTaskStatus.FAILED.name,
            fixture.dao.sourceById("mutation-source-2")?.taskStatus,
        )
    }

    @Test
    fun `source cancellation success keeps the intent action confirmed`() = runBlocking {
        val fixture =
            Fixture(
                status = "SOURCE_CREATED",
                generation = 3L,
                withSource = true,
            )

        val cancelled =
            fixture.downloader.cancelTask(
                DownloadTask(
                    itemId = ITEM_ID,
                    sourceId = intentSourceId(ITEM_ID),
                    name = "Track",
                    path = "",
                    downloadId = null,
                    status = DownloadTaskStatus.PENDING,
                    failureReason = null,
                    downloadedBytes = 0L,
                    totalBytes = 0L,
                    updatedAt = 0L,
                    mediaKind = DownloadMediaKind.MUSIC,
                    taskGeneration = 3L,
                )
            )

        assertTrue("确认取消全部旧 source 后 intent action 必须成功", cancelled)
        assertEquals(DownloadTaskStatus.FAILED.name, fixture.dao.source?.taskStatus)
        assertEquals(4L, fixture.dao.source?.taskGeneration)
    }

    private class Fixture(
        status: String,
        generation: Long,
        withSource: Boolean = false,
        denySourceCancellation: Boolean = false,
    ) {
        val dao =
            RecordingDao(
                status = status,
                generation = generation,
                withSource = withSource,
                denySourceCancellation = denySourceCancellation,
            )
        val services = FakeDownloadSystemServices()
        val downloader =
            DownloaderImpl.createForTest(
                context = TestContext(),
                database = dao.proxy,
                jellyfinRepository = unusedProxy(JellyfinRepository::class.java),
                readerRepository = unusedProxy(ReaderRepository::class.java),
                appPreferences = allocate(),
                imageCacheStore = allocate(),
                workManager =
                    allocateClass(Class.forName("androidx.work.impl.WorkManagerImpl"))
                        as WorkManager,
                httpClient = allocate(),
                notifications = services.notifications,
                registerNetworkCallback = services::registerNetworkCallback,
            )
    }

    private class FakeDownloadSystemServices {
        val notifications = FakeNotificationGateway()
        var networkCallbackRegistrationCount = 0

        fun registerNetworkCallback(callback: ConnectivityManager.NetworkCallback) {
            networkCallbackRegistrationCount += 1
        }
    }

    private class FakeNotificationGateway : DownloadNotificationGateway {
        override fun buildForeground(task: DownloadTask?, activeCount: Int): Notification =
            allocate()

        override fun notifyForeground(task: DownloadTask?, activeCount: Int) = Unit

        override fun notifyCompleted(task: DownloadTask) = Unit

        override fun notifyFailed(task: DownloadTask) = Unit
    }

    private class RecordingDao(
        status: String,
        generation: Long,
        withSource: Boolean,
        private val denySourceCancellation: Boolean,
    ) {
        val calls = mutableListOf<String>()
        var intent: DownloadIntentDto? = intent(status, generation)
        var source: FindroidSourceDto? =
            FindroidSourceDto(
                    id = SOURCE_ID,
                    itemId = ITEM_ID,
                    name = "Track",
                    type = FindroidSourceType.LOCAL,
                    path = "",
                    downloadId = null,
                    taskStatus = DownloadTaskStatus.PENDING.name,
                    failureReason = null,
                    updatedAt = 0L,
                    downloadedBytes = 0L,
                    retryCount = 0,
                    nextRetryAt = 0L,
                    engineVersion = 1,
                    allowOffline = true,
                    taskGeneration = generation,
                )
                .takeIf { withSource }
        private val additionalSources = linkedMapOf<String, FindroidSourceDto>()
        val sourceCancellationAttempts = mutableListOf<String>()
        var successfulReceipt: DownloadDeleteReceiptDto? = null
        var lastDeleteExpectedGeneration: Long? = null
        var lastDeleteActualGeneration: Long? = null
        var lastMutationReturn: DownloadIntentDto? = null
        var lastDeleteFailureDetail: String? = null

        val proxy: ServerDatabaseDao =
            Proxy.newProxyInstance(
                ServerDatabaseDao::class.java.classLoader,
                arrayOf(ServerDatabaseDao::class.java),
                InvocationHandler { _, method, rawArgs ->
                    calls += method.name
                    val args = rawArgs ?: emptyArray()
                    when (method.name) {
                        "beginDownloadIntentCancellation" ->
                            cancelIntent(args[0] as UUID, args[1] as Long, args[2] as Long).also {
                                lastMutationReturn = it
                            }
                        "beginDownloadIntentDeletion" ->
                            markDeletion(
                                    args[0] as UUID,
                                    args[1] as Long,
                                    args[2] as Long,
                                    args[3] as String?,
                                )
                                .also { lastMutationReturn = it }
                        "cancelDownloadIntent" -> cancelSql(args[0] as UUID, args[1] as Long)
                        "markDownloadIntentDeletionIfCurrent" ->
                            markDeletionSql(
                                args[0] as UUID,
                                args[1] as Long,
                                args[2] as Long,
                                args[3] as String?,
                            )
                        "getDownloadIntent" -> intent.takeIf { it?.itemId == args[0] }
                        "getSources" ->
                            (listOfNotNull(source) + additionalSources.values).filter {
                                it.itemId == args[0]
                            }
                        "getSource" -> sourceById(args[0] as String)
                        "beginSourceCancellation" -> cancelSource(args)
                        "deleteDownloadIntentIfCurrent" ->
                            deleteIntentSql(args[0] as UUID, args[1] as Long)
                        "beginDownloadDeleteBatch" -> Unit
                        "insertDownloadDeleteReceipt" -> {
                            successfulReceipt = rawArgs?.firstOrNull() as? DownloadDeleteReceiptDto
                            Unit
                        }
                        "finishDownloadDeleteBatch" -> Unit
                        else -> error("unexpected DAO call in P2-A evidence test: ${method.name}")
                    }
                },
            ) as ServerDatabaseDao

        private fun cancelIntent(
            itemId: UUID,
            expectedGeneration: Long,
            updatedAt: Long,
        ): DownloadIntentDto? {
            calls += "cancelDownloadIntent"
            val current =
                intent.takeIf {
                    it?.itemId == itemId &&
                        it.taskGeneration == expectedGeneration &&
                        it.status in setOf("RECEIVED", "RESOLVING", "SOURCE_CREATED")
                } ?: return null
            return current
                .copy(
                    status = "FAILED",
                    failureReason = "CANCELLED",
                    failureDetail = null,
                    taskGeneration = current.taskGeneration + 1,
                    updatedAt = updatedAt,
                )
                .also { intent = it }
        }

        private fun cancelSource(args: Array<Any?>): FindroidSourceDto? {
            val sourceId = args[0] as String
            sourceCancellationAttempts += sourceId
            if (denySourceCancellation && sourceId == SOURCE_ID) return null
            val current = sourceById(sourceId) ?: return null
            if (
                current.id != args[0] ||
                    current.taskGeneration != args[1] ||
                    current.taskStatus !in
                        setOf(
                            DownloadTaskStatus.PENDING.name,
                            DownloadTaskStatus.RUNNING.name,
                        )
            ) {
                return null
            }
            return current
                .copy(
                    taskStatus = DownloadTaskStatus.FAILED.name,
                    failureReason = DownloadFailureReason.CANCELLED.name,
                    taskGeneration = current.taskGeneration + 1L,
                )
                .also { updated ->
                    if (updated.id == SOURCE_ID) {
                        source = updated
                    } else {
                        additionalSources[updated.id] = updated
                    }
                }
        }

        fun seedAdditionalSource(sourceId: String) {
            val primary = requireNotNull(source)
            additionalSources[sourceId] =
                primary.copy(
                    id = sourceId,
                    path = primary.path.replace(SOURCE_ID, sourceId),
                )
        }

        fun sourceById(sourceId: String): FindroidSourceDto? =
            source?.takeIf { it.id == sourceId } ?: additionalSources[sourceId]

        private fun markDeletion(
            itemId: UUID,
            expectedGeneration: Long,
            updatedAt: Long,
            failureDetail: String?,
        ): DownloadIntentDto? {
            calls += "markDownloadIntentDeletionIfCurrent"
            lastDeleteFailureDetail = failureDetail
            val current =
                intent.takeIf {
                    it?.itemId == itemId &&
                        it.taskGeneration == expectedGeneration &&
                        it.status in setOf("RECEIVED", "RESOLVING", "SOURCE_CREATED", "FAILED")
                } ?: return null
            return current
                .copy(
                    status = "FAILED",
                    failureReason = "CANCELLED",
                    failureDetail = failureDetail,
                    taskGeneration = current.taskGeneration + 1,
                    updatedAt = updatedAt,
                )
                .also { intent = it }
        }

        private fun cancelSql(itemId: UUID, expectedGeneration: Long): Int =
            if (
                intent?.itemId == itemId &&
                    intent?.taskGeneration == expectedGeneration &&
                    intent?.status in setOf("RECEIVED", "RESOLVING", "SOURCE_CREATED")
            ) {
                1
            } else {
                0
            }

        private fun markDeletionSql(
            itemId: UUID,
            expectedGeneration: Long,
            updatedAt: Long,
            failureDetail: String?,
        ): Int =
            if (
                intent?.itemId == itemId &&
                    intent?.taskGeneration == expectedGeneration &&
                    intent?.status in setOf("RECEIVED", "RESOLVING", "SOURCE_CREATED", "FAILED")
            ) {
                intent =
                    intent?.copy(
                        status = "FAILED",
                        failureDetail = failureDetail,
                        updatedAt = updatedAt,
                    )
                1
            } else {
                0
            }

        private fun deleteIntentSql(itemId: UUID, expectedGeneration: Long): Int = run {
            lastDeleteExpectedGeneration = expectedGeneration
            lastDeleteActualGeneration = intent?.taskGeneration
            if (intent?.itemId == itemId && intent?.taskGeneration == expectedGeneration) {
                intent = null
                1
            } else {
                0
            }
        }

        fun acceptLateSourceCreated(itemId: UUID, generation: Long): Boolean {
            val current = intent ?: return false
            if (current.itemId != itemId || current.taskGeneration != generation) return false
            if (current.status != "RESOLVING") return false
            intent = current.copy(status = "SOURCE_CREATED")
            return true
        }
    }

    private class TestContext : ContextWrapper(null) {
        private val files = Files.createTempDirectory("download-intent-test").toFile()

        override fun getFilesDir(): File = files
    }

    private companion object {
        val ITEM_ID: UUID = UUID(0L, 1L)
        val USER_ID: UUID = UUID(0L, 2L)
        const val SOURCE_ID = "mutation-source"

        fun intent(status: String, generation: Long) =
            DownloadIntentDto(
                itemId = ITEM_ID,
                name = "Track",
                albumName = null,
                artist = null,
                trackIndex = 0,
                status = status,
                failureReason = if (status == "FAILED") "CANCELLED" else null,
                createdAt = 0L,
                updatedAt = 0L,
                taskGeneration = generation,
            )

        fun <T : Any> unusedProxy(type: Class<T>): T =
            type.cast(
                Proxy.newProxyInstance(
                    type.classLoader,
                    arrayOf(type),
                    InvocationHandler { _, method, _ ->
                        error("unexpected ${type.simpleName}.${method.name} in P2-A evidence test")
                    },
                )
            )!!

        val unsafe: Unsafe =
            Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
                as Unsafe

        inline fun <reified T : Any> allocate(): T = unsafe.allocateInstance(T::class.java) as T

        fun allocateClass(type: Class<*>): Any = unsafe.allocateInstance(type)
    }
}
