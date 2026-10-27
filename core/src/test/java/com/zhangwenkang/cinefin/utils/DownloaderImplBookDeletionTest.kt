package com.zhangwenkang.cinefin.utils

import android.app.Notification
import android.content.ContextWrapper
import androidx.work.WorkManager
import com.zhangwenkang.cinefin.models.DownloadDeleteBatchDto
import com.zhangwenkang.cinefin.models.DownloadDeleteReceiptDto
import com.zhangwenkang.cinefin.repository.BookDownloadDeleteResult
import com.zhangwenkang.cinefin.repository.JellyfinRepository
import com.zhangwenkang.cinefin.repository.ReaderRepository
import java.io.File
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import sun.misc.Unsafe

class DownloaderImplBookDeletionTest {
    @Test
    fun `Deleted maps to success and sends the exact complete namespace canonical once`() =
        runBlocking {
            val itemId = UUID.randomUUID()
            val fixture = Fixture(mapOf(itemId to { BookDownloadDeleteResult.Deleted }))
            val request =
                bookRequest(
                    itemId = itemId,
                    serverId = SERVER_ID,
                    userId = USER_ID.toString(),
                    sourceId = "book:$itemId",
                )

            val result = fixture.downloader.deleteDownloads(listOf(request))
            val receipt = result.receipts.single()

            assertEquals(DownloadDeleteOutcome.SUCCEEDED, receipt.outcome)
            assertEquals(
                listOf(itemId to fixture.expectedCanonical(itemId)),
                fixture.reader.deleteCalls,
            )
            assertEquals(0, fixture.reader.removeCalls)
            fixture.assertPersisted(receipt)
            assertEquals(setOf(result.batchId), fixture.dao.finishedBatchIds)
        }

    @Test
    fun `AlreadySuperseded is not reported as success`() = runBlocking {
        val itemId = UUID.randomUUID()
        val fixture = Fixture(mapOf(itemId to { BookDownloadDeleteResult.AlreadySuperseded }))

        val receipt =
            fixture.downloader.deleteDownloads(listOf(bookRequest(itemId))).receipts.single()

        assertEquals(DownloadDeleteOutcome.SUPERSEDED, receipt.outcome)
        assertSingleDeleteWithoutLegacyRemove(fixture, itemId)
        fixture.assertPersisted(receipt)
    }

    @Test
    fun `CleanupFailed is a stable failed receipt`() = runBlocking {
        val itemId = UUID.randomUUID()
        val fixture = Fixture(mapOf(itemId to { BookDownloadDeleteResult.CleanupFailed }))

        val receipt =
            fixture.downloader.deleteDownloads(listOf(bookRequest(itemId))).receipts.single()

        assertEquals(DownloadDeleteOutcome.FAILED, receipt.outcome)
        assertEquals("书籍文件清理失败", receipt.detail)
        assertSingleDeleteWithoutLegacyRemove(fixture, itemId)
        fixture.assertPersisted(receipt)
        assertTrue(fixture.dao.finishedBatchIds.isEmpty())
    }

    @Test
    fun `AmbiguousOwnership is a failed receipt with an attributable detail`() = runBlocking {
        val itemId = UUID.randomUUID()
        val fixture = Fixture(mapOf(itemId to { BookDownloadDeleteResult.AmbiguousOwnership }))

        val receipt =
            fixture.downloader.deleteDownloads(listOf(bookRequest(itemId))).receipts.single()

        assertEquals(DownloadDeleteOutcome.FAILED, receipt.outcome)
        assertEquals("书籍下载归属不明确", receipt.detail)
        assertSingleDeleteWithoutLegacyRemove(fixture, itemId)
        fixture.assertPersisted(receipt)
        assertTrue(fixture.dao.finishedBatchIds.isEmpty())
    }

    @Test
    fun `OwnershipUnavailable is a failed receipt with an attributable detail`() = runBlocking {
        val itemId = UUID.randomUUID()
        val fixture = Fixture(mapOf(itemId to { BookDownloadDeleteResult.OwnershipUnavailable }))

        val receipt =
            fixture.downloader.deleteDownloads(listOf(bookRequest(itemId))).receipts.single()

        assertEquals(DownloadDeleteOutcome.FAILED, receipt.outcome)
        assertEquals("书籍下载归属不可用", receipt.detail)
        assertSingleDeleteWithoutLegacyRemove(fixture, itemId)
        fixture.assertPersisted(receipt)
        assertTrue(fixture.dao.finishedBatchIds.isEmpty())
    }

    @Test
    fun `incomplete namespace passes null so the manager can reject ownership`() = runBlocking {
        val itemId = UUID.randomUUID()
        val fixture = Fixture(mapOf(itemId to { BookDownloadDeleteResult.AmbiguousOwnership }))
        val receipt =
            fixture.downloader
                .deleteDownloads(
                    listOf(
                        bookRequest(
                            itemId = itemId,
                            serverId = SERVER_ID,
                            userId = null,
                            sourceId = "book:$itemId",
                        )
                    )
                )
                .receipts
                .single()

        assertEquals(DownloadDeleteOutcome.FAILED, receipt.outcome)
        assertEquals(listOf(itemId to null), fixture.reader.deleteCalls)
        assertEquals(0, fixture.reader.removeCalls)
    }

    @Test
    fun `repository exception fails only the current item and continues the batch`() = runBlocking {
        val firstItemId = UUID.randomUUID()
        val secondItemId = UUID.randomUUID()
        val fixture =
            Fixture(
                mapOf(
                    firstItemId to { error("repository delete exploded") },
                    secondItemId to { BookDownloadDeleteResult.Deleted },
                )
            )

        val result =
            fixture.downloader.deleteDownloads(
                listOf(
                    bookRequest(firstItemId),
                    bookRequest(secondItemId),
                )
            )

        assertEquals(
            listOf(DownloadDeleteOutcome.FAILED, DownloadDeleteOutcome.SUCCEEDED),
            result.receipts.map { it.outcome },
        )
        assertEquals("repository delete exploded", result.receipts.first().detail)
        assertEquals(2, fixture.reader.deleteCalls.size)
        assertEquals(0, fixture.reader.removeCalls)
        result.receipts.forEach(fixture::assertPersisted)
        assertTrue(fixture.dao.finishedBatchIds.isEmpty())
    }

    private fun assertSingleDeleteWithoutLegacyRemove(
        fixture: Fixture,
        itemId: UUID,
    ) {
        assertEquals(
            listOf(itemId to fixture.expectedCanonical(itemId)),
            fixture.reader.deleteCalls,
        )
        assertEquals(0, fixture.reader.removeCalls)
    }

    private class Fixture(private val deleteResults: Map<UUID, () -> BookDownloadDeleteResult>) {
        val dao = RecordingDao()
        val reader = RecordingReader(deleteResults)
        val downloader =
            DownloaderImpl.createForTest(
                context = TestContext(),
                database = dao.proxy,
                jellyfinRepository = unusedProxy(JellyfinRepository::class.java),
                readerRepository = reader.proxy,
                appPreferences = allocate(),
                imageCacheStore = allocate(),
                workManager =
                    allocateClass(Class.forName("androidx.work.impl.WorkManagerImpl"))
                        as WorkManager,
                httpClient = allocate(),
                notifications = NoopNotificationGateway(),
                registerNetworkCallback = {},
            )

        fun expectedCanonical(itemId: UUID): String =
            DownloadCanonicalIdentity.of(
                    mediaKind = DownloadMediaKind.BOOK,
                    itemId = itemId.toString(),
                    serverId = SERVER_ID,
                    userId = USER_ID.toString(),
                    sourceId = null,
                )
                .canonicalKey

        fun assertPersisted(receipt: DownloadDeleteReceipt) {
            val persisted = dao.receipt(receipt.batchId, receipt.canonicalKey)
            assertEquals(receipt.outcome.name, persisted?.outcome)
            assertEquals(receipt.detail, persisted?.detail)
        }
    }

    private class RecordingReader(
        private val deleteResults: Map<UUID, () -> BookDownloadDeleteResult>
    ) {
        val deleteCalls = CopyOnWriteArrayList<Pair<UUID, String?>>()
        var removeCalls = 0

        val proxy: ReaderRepository =
            Proxy.newProxyInstance(
                ReaderRepository::class.java.classLoader,
                arrayOf(ReaderRepository::class.java),
                InvocationHandler { _, method, rawArgs ->
                    val args = rawArgs ?: emptyArray()
                    when (method.name) {
                        "deleteLocalFile" -> {
                            val itemId = args[0] as UUID
                            val canonicalKey = args[1] as String?
                            deleteCalls += itemId to canonicalKey
                            deleteResults[itemId]?.invoke()
                                ?: error("unexpected book delete: $itemId")
                        }
                        "removeBookDownload" -> {
                            removeCalls += 1
                            Unit
                        }
                        "toString" -> "RecordingReader"
                        "hashCode" -> System.identityHashCode(this)
                        "equals" -> false
                        else -> error("unexpected ReaderRepository call: ${method.name}")
                    }
                },
            ) as ReaderRepository
    }

    private class RecordingDao {
        private val batches = linkedMapOf<String, DownloadDeleteBatchDto>()
        private val receipts = linkedMapOf<Pair<String, String>, DownloadDeleteReceiptDto>()
        val finishedBatchIds = mutableSetOf<String>()

        val proxy: com.zhangwenkang.cinefin.database.ServerDatabaseDao =
            Proxy.newProxyInstance(
                com.zhangwenkang.cinefin.database.ServerDatabaseDao::class.java.classLoader,
                arrayOf(com.zhangwenkang.cinefin.database.ServerDatabaseDao::class.java),
                InvocationHandler { _, method, rawArgs ->
                    val args = rawArgs ?: emptyArray()
                    when (method.name) {
                        "beginDownloadDeleteBatch" -> {
                            val batch = args[0] as DownloadDeleteBatchDto
                            batches[batch.id] = batch
                            @Suppress("UNCHECKED_CAST")
                            (args[1] as List<DownloadDeleteReceiptDto>).forEach { receipt ->
                                receipts[receipt.batchId to receipt.canonicalKey] = receipt
                            }
                            Unit
                        }
                        "insertDownloadDeleteReceipt" -> {
                            val receipt = args[0] as DownloadDeleteReceiptDto
                            receipts[receipt.batchId to receipt.canonicalKey] = receipt
                            Unit
                        }
                        "finishDownloadDeleteBatch" -> {
                            finishedBatchIds += args[0] as String
                            Unit
                        }
                        "getIncompleteDownloadDeleteBatches" -> emptyList<DownloadDeleteBatchDto>()
                        "getDownloadDeleteReceipts" ->
                            receipts.values
                                .filter { it.batchId == args[0] as String }
                                .sortedBy(DownloadDeleteReceiptDto::ordinal)
                        "toString" -> "RecordingDao"
                        "hashCode" -> System.identityHashCode(this)
                        "equals" -> false
                        else -> error("unexpected ServerDatabaseDao call: ${method.name}")
                    }
                },
            ) as com.zhangwenkang.cinefin.database.ServerDatabaseDao

        fun receipt(
            batchId: String,
            canonicalKey: String,
        ): DownloadDeleteReceiptDto? = receipts[batchId to canonicalKey]
    }

    private class NoopNotificationGateway : DownloadNotificationGateway {
        override fun buildForeground(task: DownloadTask?, activeCount: Int): Notification =
            allocate()

        override fun notifyForeground(task: DownloadTask?, activeCount: Int) = Unit

        override fun notifyCompleted(task: DownloadTask) = Unit

        override fun notifyFailed(task: DownloadTask) = Unit
    }

    private class TestContext : ContextWrapper(null) {
        private val files = Files.createTempDirectory("downloader-book-delete").toFile()

        override fun getFilesDir(): File = files

        override fun getExternalFilesDirs(type: String?): Array<File> = arrayOf(files)
    }

    private companion object {
        const val SERVER_ID = "server-book-delete"
        val USER_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000505")

        fun bookRequest(
            itemId: UUID,
            serverId: String? = SERVER_ID,
            userId: String? = USER_ID.toString(),
            sourceId: String? = "book:$itemId",
        ): DownloadDeleteRequest =
            DownloadDeleteRequest(
                itemId = itemId,
                sourceId = sourceId,
                mediaKind = DownloadMediaKind.BOOK,
                serverId = serverId,
                userId = userId,
            )

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
    }
}
