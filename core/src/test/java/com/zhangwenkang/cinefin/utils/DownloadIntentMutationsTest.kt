package com.zhangwenkang.cinefin.utils

import android.content.ContextWrapper
import androidx.work.WorkManager
import com.zhangwenkang.cinefin.database.ServerDatabaseDao
import com.zhangwenkang.cinefin.models.DownloadDeleteReceiptDto
import com.zhangwenkang.cinefin.models.DownloadIntentDto
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
        assertEquals(4L, fixture.dao.intent?.taskGeneration)
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
    }

    private class Fixture(status: String, generation: Long) {
        val dao = RecordingDao(status = status, generation = generation)
        val downloader =
            DownloaderImpl(
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
            )
    }

    private class RecordingDao(status: String, generation: Long) {
        val calls = mutableListOf<String>()
        var intent: DownloadIntentDto? = intent(status, generation)
        var successfulReceipt: DownloadDeleteReceiptDto? = null
        var lastDeleteExpectedGeneration: Long? = null
        var lastDeleteActualGeneration: Long? = null
        var lastMutationReturn: DownloadIntentDto? = null

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
                            markDeletion(args[0] as UUID, args[1] as Long, args[2] as Long).also {
                                lastMutationReturn = it
                            }
                        "cancelDownloadIntent" -> cancelSql(args[0] as UUID, args[1] as Long)
                        "markDownloadIntentDeletionIfCurrent" ->
                            markDeletionSql(args[0] as UUID, args[1] as Long, args[2] as Long)
                        "getDownloadIntent" -> intent.takeIf { it?.itemId == args[0] }
                        "getSources" -> emptyList<Any>()
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
                        it.status == "RECEIVED"
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

        private fun markDeletion(
            itemId: UUID,
            expectedGeneration: Long,
            updatedAt: Long,
        ): DownloadIntentDto? {
            calls += "markDownloadIntentDeletionIfCurrent"
            val current =
                intent.takeIf {
                    it?.itemId == itemId &&
                        it.taskGeneration == expectedGeneration &&
                        it.status in setOf("RECEIVED", "FAILED")
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

        private fun cancelSql(itemId: UUID, expectedGeneration: Long): Int =
            if (
                intent?.itemId == itemId &&
                    intent?.taskGeneration == expectedGeneration &&
                    intent?.status == "RECEIVED"
            ) {
                1
            } else {
                0
            }

        private fun markDeletionSql(itemId: UUID, expectedGeneration: Long, updatedAt: Long): Int =
            if (
                intent?.itemId == itemId &&
                    intent?.taskGeneration == expectedGeneration &&
                    intent?.status in setOf("RECEIVED", "FAILED")
            ) {
                intent = intent?.copy(status = "FAILED", updatedAt = updatedAt)
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
    }

    private class TestContext : ContextWrapper(null) {
        private val files = Files.createTempDirectory("download-intent-test").toFile()

        override fun getFilesDir(): File = files

        override fun getSystemService(name: String): Any? = null
    }

    private companion object {
        val ITEM_ID: UUID = UUID(0L, 1L)
        val USER_ID: UUID = UUID(0L, 2L)

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
