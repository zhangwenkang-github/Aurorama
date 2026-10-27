package com.zhangwenkang.cinefin.repository

import com.zhangwenkang.cinefin.models.BookDownloadTaskDto
import com.zhangwenkang.cinefin.utils.DownloadCanonicalIdentity
import com.zhangwenkang.cinefin.utils.DownloadMediaKind
import com.zhangwenkang.cinefin.utils.DownloadMediaSidecar
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BookDownloadFailureRecoveryTest {

    @Test
    fun `取消后旧 transfer 在新代运行后尝试 rename 不得污染最终文件`() = runBlocking {
        val root = createTempDirectory(prefix = "book-retiring-rename").toFile()
        val target = File(root, "$ITEM_ID.book")
        val partial = File(root, "$ITEM_ID.book.part")
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirstRename = CompletableDeferred<Unit>()
        val firstRenamed = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()
        val releaseSecond = CompletableDeferred<Unit>()
        val generations = CopyOnWriteArrayList<Long>()
        val transfer =
            object : BookDownloadTransfer {
                override suspend fun download(
                    request: BookDownloadTransferRequest,
                    onMetadata: suspend (String?, Long) -> Unit,
                    onProgress: suspend (Long, Long) -> Unit,
                ): BookDownloadTransferResult {
                    generations += request.taskGeneration
                    if (generations.size == 1) {
                        request.partialFile.writeBytes(byteArrayOf(1))
                        firstStarted.complete(Unit)
                        withContext(NonCancellable) {
                            releaseFirstRename.await()
                            request.partialFile.writeBytes(byteArrayOf(1))
                            atomicMove(request.partialFile, request.targetFile)
                            firstRenamed.complete(Unit)
                        }
                    } else {
                        request.partialFile.writeBytes(byteArrayOf(2))
                        secondStarted.complete(Unit)
                        withContext(NonCancellable) {
                            releaseSecond.await()
                            atomicMove(request.partialFile, request.targetFile)
                        }
                    }
                    return BookDownloadTransferResult(totalBytes = 1, validator = "\"v\"")
                }
            }
        val store = InMemoryBookDownloadTaskStore()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val manager = manager(store, transfer, target, partial, scope)
            manager.awaitRestored()
            manager.enqueueBookDownload(ITEM_ID, "书")
            withTimeout(2_000) { firstStarted.await() }

            manager.cancelBookDownload(ITEM_ID)
            withTimeout(2_000) {
                manager.tasks.first { it.singleOrNull()?.failureReason == "CANCELLED" }
            }
            manager.enqueueBookDownload(ITEM_ID, "书")
            withTimeout(2_000) { secondStarted.await() }

            releaseFirstRename.complete(Unit)
            withTimeout(2_000) { firstRenamed.await() }
            assertFalse("旧代不得把文件提交到新代可见的最终路径", target.exists())

            releaseSecond.complete(Unit)
            withTimeout(2_000) {
                manager.tasks.first { it.singleOrNull()?.status == BookDownloadStatus.COMPLETED }
            }
            assertEquals(byteArrayOf(2).toList(), target.readBytes().toList())
        } finally {
            scope.cancel()
            root.deleteRecursively()
        }
    }

    @Test
    fun `provenance 写入失败保持可重试态且重建 manager 后补齐`() = runBlocking {
        val root = createTempDirectory(prefix = "book-provenance-retry").toFile()
        val target = File(root, "$ITEM_ID.book")
        val partial = File(root, "$ITEM_ID.book.part")
        val provenanceAllowed = AtomicBoolean(false)
        val provenanceWrites = CopyOnWriteArrayList<BookDownloadOrigin>()
        val transfer = completedFileTransfer()
        val store = InMemoryBookDownloadTaskStore()
        val firstScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var secondScope: CoroutineScope? = null
        try {
            val first =
                manager(
                    store = store,
                    transfer = transfer,
                    target = target,
                    partial = partial,
                    scope = firstScope,
                    recordProvenance = {
                        if (provenanceAllowed.get()) provenanceWrites += it
                        provenanceAllowed.get()
                    },
                )
            first.awaitRestored()
            first.enqueueBookDownload(ITEM_ID, "书")
            val failed =
                withTimeout(2_000) {
                    first.tasks.first { it.singleOrNull()?.status != BookDownloadStatus.RUNNING }
                }

            assertEquals(BookDownloadStatus.FAILED, failed.single().status)
            assertEquals("PROVENANCE_WRITE_FAILED", failed.single().failureReason)
            assertTrue(target.isFile)
            assertTrue(provenanceWrites.isEmpty())
            firstScope.cancel()

            provenanceAllowed.set(true)
            val retryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            secondScope = retryScope
            val retried =
                manager(
                    store = store,
                    transfer =
                        object : BookDownloadTransfer {
                            override suspend fun download(
                                request: BookDownloadTransferRequest,
                                onMetadata: suspend (String?, Long) -> Unit,
                                onProgress: suspend (Long, Long) -> Unit,
                            ): BookDownloadTransferResult = error("完整文件已存在时只能补 provenance，不得重新下载")
                        },
                    target = target,
                    partial = partial,
                    scope = retryScope,
                    recordProvenance = {
                        provenanceWrites += it
                        provenanceAllowed.get()
                    },
                )
            retried.awaitRestored()
            val completed =
                withTimeout(2_000) {
                    retried.tasks.first {
                        it.singleOrNull()?.status == BookDownloadStatus.COMPLETED
                    }
                }

            assertEquals(BookDownloadStatus.COMPLETED, completed.single().status)
            assertEquals(1, provenanceWrites.size)
        } finally {
            firstScope.cancel()
            secondScope?.cancel()
            root.deleteRecursively()
        }
    }

    @Test
    fun `provenance 失败后同进程再次入队先补写再报告已下载`() = runBlocking {
        val root = createTempDirectory(prefix = "book-provenance-inline-retry").toFile()
        val target = File(root, "$ITEM_ID.book")
        val partial = File(root, "$ITEM_ID.book.part")
        val provenanceAllowed = AtomicBoolean(false)
        val provenanceWrites = CopyOnWriteArrayList<BookDownloadOrigin>()
        val transferCalls = CopyOnWriteArrayList<Long>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val manager =
                manager(
                    store = InMemoryBookDownloadTaskStore(),
                    transfer =
                        object : BookDownloadTransfer {
                            override suspend fun download(
                                request: BookDownloadTransferRequest,
                                onMetadata: suspend (String?, Long) -> Unit,
                                onProgress: suspend (Long, Long) -> Unit,
                            ): BookDownloadTransferResult {
                                transferCalls += request.taskGeneration
                                request.partialFile.writeBytes(byteArrayOf(7))
                                atomicMove(request.partialFile, request.targetFile)
                                return BookDownloadTransferResult(
                                    totalBytes = 1,
                                    validator = "\"v\"",
                                )
                            }
                        },
                    target = target,
                    partial = partial,
                    scope = scope,
                    recordProvenance = {
                        if (provenanceAllowed.get()) provenanceWrites += it
                        provenanceAllowed.get()
                    },
                )
            manager.awaitRestored()
            manager.enqueueBookDownload(ITEM_ID, "书")
            withTimeout(2_000) {
                manager.tasks.first {
                    it.singleOrNull()?.failureReason == "PROVENANCE_WRITE_FAILED"
                }
            }

            provenanceAllowed.set(true)
            val retried = manager.enqueueBookDownload(ITEM_ID, "书")

            assertEquals(BookDownloadEnqueueResult.AlreadyDownloaded, retried)
            assertEquals(
                BookDownloadStatus.COMPLETED,
                manager.tasks.value.single().status,
            )
            assertEquals(1, transferCalls.size)
            assertEquals(1, provenanceWrites.size)
        } finally {
            scope.cancel()
            root.deleteRecursively()
        }
    }

    @Test
    fun `真实 sidecar 写失败不得报告 provenance 成功`() {
        val root = createTempDirectory(prefix = "book-provenance-sidecar").toFile()
        try {
            val sidecar = DownloadMediaSidecar.forFile(File(root, "download_media.tsv"))
            assertTrue(File(root, "download_media.tsv.tmp").mkdirs())

            val recorded =
                recordBookDownloadProvenance(
                    sidecar = sidecar,
                    origin =
                        BookDownloadOrigin(
                            serverId = SERVER_ID,
                            userId = USER_ID,
                            itemId = ITEM_ID,
                            displayName = "书",
                        ),
                )

            assertFalse(recorded)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `delete 清理未释放时 enqueue 必须等待同一 item 生命周期`() = runBlocking {
        val root = createTempDirectory(prefix = "book-delete-enqueue-lock").toFile()
        val target = File(root, "$ITEM_ID.book")
        val partial = File(root, "$ITEM_ID.book.part")
        target.writeBytes(byteArrayOf(1, 2, 3))
        partial.writeBytes(byteArrayOf(4))
        val store =
            InMemoryBookDownloadTaskStore(listOf(task(BookDownloadStatus.COMPLETED, target)))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val deleteEntered = CountDownLatch(1)
        val releaseDelete = CountDownLatch(1)
        try {
            val manager =
                manager(
                    store = store,
                    transfer =
                        object : BookDownloadTransfer {
                            override suspend fun download(
                                request: BookDownloadTransferRequest,
                                onMetadata: suspend (String?, Long) -> Unit,
                                onProgress: suspend (Long, Long) -> Unit,
                            ): BookDownloadTransferResult = error("删除失败后不得启动网络")
                        },
                    target = target,
                    partial = partial,
                    scope = scope,
                    deleteFile = { file ->
                        if (file.absolutePath == target.absolutePath) {
                            deleteEntered.countDown()
                            releaseDelete.await()
                            false
                        } else {
                            deleteFileIfPresent(file)
                        }
                    },
                )
            manager.awaitRestored()
            val deleting = async(Dispatchers.IO) { manager.deleteBookDownload(ITEM_ID) }
            assertTrue(deleteEntered.await(2, java.util.concurrent.TimeUnit.SECONDS))

            val enqueue =
                async(Dispatchers.IO) { runCatching { manager.enqueueBookDownload(ITEM_ID, "书") } }
            assertTrue(
                "enqueue 不得在 delete 清理期间进入生命周期",
                withTimeoutOrNull(150) { enqueue.await() } == null,
            )

            releaseDelete.countDown()
            deleting.await()
            assertEquals(
                BookDownloadEnqueueResult.BlockedByDelete,
                withTimeout(2_000) { enqueue.await() }.getOrThrow(),
            )
            assertEquals("DELETE_FAILED", store.snapshot().single().failureReason)
        } finally {
            releaseDelete.countDown()
            scope.cancel()
            root.deleteRecursively()
        }
    }

    @Test
    fun `无 credentials 且无 DAO task 时直接删除本地 legacy 四类 artifact`() = runBlocking {
        val root = createTempDirectory(prefix = "book-legacy-delete").toFile()
        val target = File(root, "$ITEM_ID.book")
        val partial = File(root, "$ITEM_ID.book.part")
        val generatedPartial = File(root, "${partial.name}.7")
        val title = File(root, "$ITEM_ID.title")
        target.writeBytes(byteArrayOf(1, 2, 3))
        partial.writeBytes(byteArrayOf(4))
        generatedPartial.writeBytes(byteArrayOf(5, 6))
        title.writeText("书")
        val sidecar = DownloadMediaSidecar.forFile(File(root, "download_media.tsv"))
        assertTrue(
            recordBookDownloadProvenance(
                sidecar,
                BookDownloadOrigin(SERVER_ID, USER_ID, ITEM_ID, "书"),
            )
        )
        val store = InMemoryBookDownloadTaskStore()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val manager =
                manager(
                    store = store,
                    transfer =
                        object : BookDownloadTransfer {
                            override suspend fun download(
                                request: BookDownloadTransferRequest,
                                onMetadata: suspend (String?, Long) -> Unit,
                                onProgress: suspend (Long, Long) -> Unit,
                            ): BookDownloadTransferResult = error("legacy 删除不得启动网络")
                        },
                    target = target,
                    partial = partial,
                    scope = scope,
                    credentialsProvider = { error("legacy 删除不得依赖当前 origin") },
                    clearProvenance = { clearBookDownloadProvenance(sidecar, it) },
                    deleteTitle = { deleteFileIfPresent(title) },
                )
            manager.awaitRestored()

            manager.deleteBookDownload(ITEM_ID)

            assertTrue(store.snapshot().isEmpty())
            assertTrue(manager.tasks.value.isEmpty())
            assertTrue(!target.exists())
            assertTrue(bookPartials(partial).isEmpty())
            assertTrue(!title.exists())
            assertTrue(sidecar.records().isEmpty())
        } finally {
            scope.cancel()
            root.deleteRecursively()
        }
    }

    @Test
    fun `无 credentials 的 legacy 删除每类失败重启后自动重试`() = runBlocking {
        CleanupFailure.entries.forEach { failure ->
            val root = createTempDirectory(prefix = "book-legacy-delete-${failure.name}").toFile()
            val target = File(root, "$ITEM_ID.book")
            val partial = File(root, "$ITEM_ID.book.part")
            val title = File(root, "$ITEM_ID.title")
            target.writeBytes(byteArrayOf(1, 2, 3))
            partial.writeBytes(byteArrayOf(4, 5))
            title.writeText("书")
            val provenance = AtomicBoolean(true)
            val store = InMemoryBookDownloadTaskStore()
            val firstScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val secondScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val first =
                    manager(
                        store = store,
                        transfer =
                            object : BookDownloadTransfer {
                                override suspend fun download(
                                    request: BookDownloadTransferRequest,
                                    onMetadata: suspend (String?, Long) -> Unit,
                                    onProgress: suspend (Long, Long) -> Unit,
                                ): BookDownloadTransferResult = error("legacy 删除不得启动网络")
                            },
                        target = target,
                        partial = partial,
                        scope = firstScope,
                        credentialsProvider = { error("legacy 删除不得依赖当前 origin") },
                        clearProvenance = {
                            if (failure != CleanupFailure.PROVENANCE) provenance.set(false)
                            failure != CleanupFailure.PROVENANCE
                        },
                        deleteTitle = {
                            if (failure == CleanupFailure.TITLE) false
                            else deleteFileIfPresent(title)
                        },
                        deleteFile = { file ->
                            when {
                                failure == CleanupFailure.TARGET &&
                                    file.absolutePath == target.absolutePath -> false
                                failure == CleanupFailure.PARTIAL &&
                                    bookPartials(partial).any {
                                        it.absolutePath == file.absolutePath
                                    } -> false
                                else -> deleteFileIfPresent(file)
                            }
                        },
                    )
                first.awaitRestored()

                first.deleteBookDownload(ITEM_ID)

                assertEquals("DELETE_FAILED", store.snapshot().single().failureReason)
                assertEquals(BookDownloadStatus.FAILED, first.tasks.value.single().status)
                firstScope.cancel()

                val retried =
                    manager(
                        store = store,
                        transfer =
                            object : BookDownloadTransfer {
                                override suspend fun download(
                                    request: BookDownloadTransferRequest,
                                    onMetadata: suspend (String?, Long) -> Unit,
                                    onProgress: suspend (Long, Long) -> Unit,
                                ): BookDownloadTransferResult = error("删除恢复不得启动网络")
                            },
                        target = target,
                        partial = partial,
                        scope = secondScope,
                        credentialsProvider = { error("legacy 恢复不得依赖当前 origin") },
                        clearProvenance = {
                            provenance.set(false)
                            true
                        },
                        deleteTitle = { deleteFileIfPresent(title) },
                        deleteFile = { deleteFileIfPresent(it) },
                    )
                retried.awaitRestored()

                assertTrue(store.snapshot().isEmpty())
                assertTrue(retried.tasks.value.isEmpty())
                assertTrue(!target.exists())
                assertTrue(bookPartials(partial).isEmpty())
                assertTrue(!title.exists())
                assertFalse(provenance.get())
            } finally {
                firstScope.cancel()
                secondScope.cancel()
                root.deleteRecursively()
            }
        }
    }

    @Test
    fun `旧 legacy receipt 与新 canonical 任务共存时先终态化且保留新下载`() = runBlocking {
        val root = createTempDirectory(prefix = "book-delete-superseded").toFile()
        val target = File(root, "$ITEM_ID.book")
        val partial = File(root, "$ITEM_ID.book.part")
        target.writeBytes(byteArrayOf(8, 9))
        partial.writeBytes(byteArrayOf(1))
        val newOrigin =
            BookDownloadOrigin(
                serverId = "server-b",
                userId = OTHER_USER_ID,
                itemId = ITEM_ID,
                displayName = "新来源",
            )
        val recordedOrigins = CopyOnWriteArrayList<BookDownloadOrigin>()
        val clearedItems = CopyOnWriteArrayList<UUID>()
        val store =
            InMemoryBookDownloadTaskStore(
                listOf(
                    deleteReceipt(target = target, updatedAt = 2),
                    task(
                        status = BookDownloadStatus.COMPLETED,
                        target = target,
                        serverId = "server-b",
                        userId = OTHER_USER_ID,
                        title = "新来源",
                        taskGeneration = 7,
                        updatedAt = 3,
                    ),
                )
            )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val manager =
                manager(
                    store = store,
                    transfer =
                        object : BookDownloadTransfer {
                            override suspend fun download(
                                request: BookDownloadTransferRequest,
                                onMetadata: suspend (String?, Long) -> Unit,
                                onProgress: suspend (Long, Long) -> Unit,
                            ): BookDownloadTransferResult = error("新任务已完成，恢复不得下载或清理")
                        },
                    target = target,
                    partial = partial,
                    scope = scope,
                    recordProvenance = {
                        recordedOrigins += it
                        true
                    },
                    clearProvenance = {
                        clearedItems += it
                        true
                    },
                )

            manager.awaitRestored()

            assertEquals(byteArrayOf(8, 9).toList(), target.readBytes().toList())
            assertTrue("新 canonical provenance 必须保留", newOrigin in recordedOrigins)
            assertTrue("旧 receipt 不得清理新任务 provenance", clearedItems.isEmpty())
            val restored = store.snapshot()
            assertEquals(
                "DELETE_SUPERSEDED",
                restored.single { it.canonicalKey == localDeleteKey() }.failureReason,
            )
            assertTrue(restored.any { it.canonicalKey != localDeleteKey() })
        } finally {
            scope.cancel()
            root.deleteRecursively()
        }
    }

    @Test
    fun `未解决删除 receipt 阻止同 item enqueue 且 cleanup 后允许`() = runBlocking {
        val root = createTempDirectory(prefix = "book-delete-enqueue-fence").toFile()
        val target = File(root, "$ITEM_ID.book")
        val partial = File(root, "$ITEM_ID.book.part")
        target.writeBytes(byteArrayOf(1, 2, 3))
        partial.writeBytes(byteArrayOf(4))
        val store = InMemoryBookDownloadTaskStore(listOf(deleteReceipt(target = target)))
        val firstScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val secondScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val firstTransferCalls = AtomicBoolean()
        try {
            val first =
                manager(
                    store = store,
                    transfer =
                        object : BookDownloadTransfer {
                            override suspend fun download(
                                request: BookDownloadTransferRequest,
                                onMetadata: suspend (String?, Long) -> Unit,
                                onProgress: suspend (Long, Long) -> Unit,
                            ): BookDownloadTransferResult {
                                firstTransferCalls.set(true)
                                error("删除 receipt 未解决时不得启动下载")
                            }
                        },
                    target = target,
                    partial = partial,
                    scope = firstScope,
                    clearProvenance = { false },
                )
            first.awaitRestored()
            assertEquals("DELETE_FAILED", store.snapshot().single().failureReason)

            val blocked = first.enqueueBookDownload(ITEM_ID, "书")

            assertEquals(
                "receipt 未解决时必须返回可重试阻断",
                BookDownloadEnqueueResult.BlockedByDelete,
                blocked,
            )
            assertFalse("receipt 未解决时不能启动网络", firstTransferCalls.get())
            assertEquals(1, store.snapshot().size)
            assertEquals("DELETE_FAILED", store.snapshot().single().failureReason)
            firstScope.cancel()

            val retried =
                manager(
                    store = store,
                    transfer = completedFileTransfer(),
                    target = target,
                    partial = partial,
                    scope = secondScope,
                    clearProvenance = { true },
                )
            retried.awaitRestored()

            assertEquals(
                BookDownloadEnqueueResult.Queued,
                retried.enqueueBookDownload(ITEM_ID, "书"),
            )
            withTimeout(2_000) {
                retried.tasks.first { it.singleOrNull()?.status == BookDownloadStatus.COMPLETED }
            }
            assertTrue(target.isFile)
            assertEquals(1, store.snapshot().size)
            assertEquals(
                canonicalKey(ITEM_ID, SERVER_ID, USER_ID),
                store.snapshot().single().canonicalKey,
            )
        } finally {
            firstScope.cancel()
            secondScope.cancel()
            root.deleteRecursively()
        }
    }

    @Test
    fun `删除 receipt 只清自身 item 与 generation 不误删相邻文件`() = runBlocking {
        val root = createTempDirectory(prefix = "book-delete-ownership").toFile()
        val targetA = File(root, "$ITEM_ID.book")
        val partialA = File(root, "$ITEM_ID.book.part")
        val targetB = File(root, "$OTHER_ITEM_ID.book")
        val partialB = File(root, "$OTHER_ITEM_ID.book.part")
        val receiptGeneration = 3L
        val otherGeneration = 4L
        targetA.writeBytes(byteArrayOf(1))
        targetB.writeBytes(byteArrayOf(2))
        partialA.writeBytes(byteArrayOf(3))
        partialB.writeBytes(byteArrayOf(4))
        File(root, "${partialA.name}.$receiptGeneration").writeBytes(byteArrayOf(5))
        File(root, "${partialA.name}.$otherGeneration").writeBytes(byteArrayOf(6))
        File(root, "${targetA.name}.commit.$receiptGeneration").writeBytes(byteArrayOf(7))
        File(root, "${targetA.name}.commit.$otherGeneration").writeBytes(byteArrayOf(8))
        val store =
            InMemoryBookDownloadTaskStore(
                listOf(
                    task(
                        status = BookDownloadStatus.COMPLETED,
                        target = targetA,
                        taskGeneration = receiptGeneration,
                    )
                )
            )
        val clearedItems = CopyOnWriteArrayList<UUID>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val manager =
                BookDownloadManager(
                    store = store,
                    transfer =
                        object : BookDownloadTransfer {
                            override suspend fun download(
                                request: BookDownloadTransferRequest,
                                onMetadata: suspend (String?, Long) -> Unit,
                                onProgress: suspend (Long, Long) -> Unit,
                            ): BookDownloadTransferResult = error("删除不得启动网络")
                        },
                    credentialsProvider = { credentials() },
                    localFileProvider = { itemId ->
                        when (itemId) {
                            ITEM_ID -> targetA
                            OTHER_ITEM_ID -> targetB
                            else -> error("未知 item")
                        }
                    },
                    partialFileProvider = { itemId ->
                        when (itemId) {
                            ITEM_ID -> partialA
                            OTHER_ITEM_ID -> partialB
                            else -> error("未知 item")
                        }
                    },
                    scope = scope,
                    clearProvenance = {
                        clearedItems += it
                        true
                    },
                    deleteFile = { deleteFileIfPresent(it) },
                )
            manager.awaitRestored()

            manager.deleteBookDownload(ITEM_ID)

            assertFalse(targetA.exists())
            assertFalse(partialA.exists())
            assertFalse(File(root, "${partialA.name}.$receiptGeneration").exists())
            assertFalse(File(root, "${targetA.name}.commit.$receiptGeneration").exists())
            assertTrue(
                "其他 generation 不得被旧 receipt 删除",
                File(root, "${partialA.name}.$otherGeneration").isFile,
            )
            assertTrue(
                "其他 commit generation 不得被旧 receipt 删除",
                File(root, "${targetA.name}.commit.$otherGeneration").isFile,
            )
            assertTrue("其他 item 文件不得被删除", targetB.isFile)
            assertTrue("其他 item 断点不得被删除", partialB.isFile)
            assertEquals(listOf(ITEM_ID), clearedItems.toList())
        } finally {
            scope.cancel()
            root.deleteRecursively()
        }
    }

    @Test
    fun `每类清理失败都不得删除共存新 canonical 的目标与 provenance`() = runBlocking {
        CleanupFailure.entries.forEach { failure ->
            val root = createTempDirectory(prefix = "book-delete-coexist-${failure.name}").toFile()
            val target = File(root, "$ITEM_ID.book")
            val partial = File(root, "$ITEM_ID.book.part")
            val title = File(root, "$ITEM_ID.title")
            target.writeBytes(byteArrayOf(9))
            partial.writeBytes(byteArrayOf(1))
            title.writeText("旧标题")
            val cleanupAttempts = mutableListOf<String>()
            val recordedOrigins = mutableListOf<BookDownloadOrigin>()
            val store =
                InMemoryBookDownloadTaskStore(
                    listOf(
                        deleteReceipt(target = target, updatedAt = 2),
                        task(
                            status = BookDownloadStatus.COMPLETED,
                            target = target,
                            serverId = "server-b",
                            userId = OTHER_USER_ID,
                            title = "新来源",
                            taskGeneration = 8,
                            updatedAt = 3,
                        ),
                    )
                )
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val manager =
                    manager(
                        store = store,
                        transfer =
                            object : BookDownloadTransfer {
                                override suspend fun download(
                                    request: BookDownloadTransferRequest,
                                    onMetadata: suspend (String?, Long) -> Unit,
                                    onProgress: suspend (Long, Long) -> Unit,
                                ): BookDownloadTransferResult = error("不得启动下载")
                            },
                        target = target,
                        partial = partial,
                        scope = scope,
                        recordProvenance = {
                            recordedOrigins += it
                            true
                        },
                        clearProvenance = {
                            cleanupAttempts += "provenance"
                            failure != CleanupFailure.PROVENANCE
                        },
                        deleteTitle = {
                            cleanupAttempts += "title"
                            if (failure == CleanupFailure.TITLE) false
                            else deleteFileIfPresent(title)
                        },
                        deleteFile = { file ->
                            cleanupAttempts += "file:${file.name}"
                            when {
                                failure == CleanupFailure.TARGET &&
                                    file.absolutePath == target.absolutePath -> false
                                failure == CleanupFailure.PARTIAL &&
                                    bookPartials(partial).any {
                                        it.absolutePath == file.absolutePath
                                    } -> false
                                else -> deleteFileIfPresent(file)
                            }
                        },
                    )

                manager.awaitRestored()

                assertTrue("$failure: 新目标必须保留", target.isFile)
                assertEquals(1, recordedOrigins.size)
                assertEquals("server-b", recordedOrigins.single().serverId)
                assertTrue(
                    "$failure: 旧 receipt 不得执行任何清理，实际=$cleanupAttempts",
                    cleanupAttempts.isEmpty(),
                )
                assertEquals(
                    "DELETE_SUPERSEDED",
                    store.snapshot().single { it.canonicalKey == localDeleteKey() }.failureReason,
                )
            } finally {
                scope.cancel()
                root.deleteRecursively()
            }
        }
    }

    @Test
    fun `RUNNING 每类清理失败都保留 DELETE_FAILED receipt 且重启可重试`() = runBlocking {
        CleanupFailure.entries.forEach { failure ->
            assertCleanupFailureRetry(BookDownloadStatus.RUNNING, failure)
        }
    }

    @Test
    fun `FAILED 每类清理失败都保留 DELETE_FAILED receipt 且重启可重试`() = runBlocking {
        CleanupFailure.entries.forEach { failure ->
            assertCleanupFailureRetry(BookDownloadStatus.FAILED, failure)
        }
    }

    @Test
    fun `COMPLETED 每类清理失败都保留 DELETE_FAILED receipt 且重启可重试`() = runBlocking {
        CleanupFailure.entries.forEach { failure ->
            assertCleanupFailureRetry(BookDownloadStatus.COMPLETED, failure)
        }
    }

    private suspend fun assertCleanupFailureRetry(
        status: BookDownloadStatus,
        failure: CleanupFailure,
    ) {
        val root =
            createTempDirectory(prefix = "book-delete-${status.name}-${failure.name}").toFile()
        val target = File(root, "$ITEM_ID.book")
        val partial = File(root, "$ITEM_ID.book.part")
        val title = File(root, "$ITEM_ID.title")
        target.writeBytes(byteArrayOf(1, 2, 3))
        partial.writeBytes(byteArrayOf(4, 5))
        title.writeText("书")
        val store = InMemoryBookDownloadTaskStore(listOf(task(status, target)))
        val firstScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val secondScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val firstTake = AtomicBoolean()
        if (status == BookDownloadStatus.RUNNING) target.delete()
        try {
            val first =
                manager(
                    store = store,
                    transfer =
                        object : BookDownloadTransfer {
                            override suspend fun download(
                                request: BookDownloadTransferRequest,
                                onMetadata: suspend (String?, Long) -> Unit,
                                onProgress: suspend (Long, Long) -> Unit,
                            ): BookDownloadTransferResult {
                                firstTake.set(true)
                                target.writeBytes(byteArrayOf(1, 2, 3))
                                return CompletableDeferred<BookDownloadTransferResult>().await()
                            }
                        },
                    target = target,
                    partial = partial,
                    scope = firstScope,
                    recordProvenance = { status != BookDownloadStatus.FAILED },
                    clearProvenance = { failure != CleanupFailure.PROVENANCE },
                    deleteTitle = {
                        if (failure == CleanupFailure.TITLE) false else deleteFileIfPresent(title)
                    },
                    deleteFile = { file ->
                        when {
                            failure == CleanupFailure.TARGET &&
                                file.absolutePath == target.absolutePath -> false
                            failure == CleanupFailure.PARTIAL &&
                                (file.name == partial.name ||
                                    file.name.startsWith("${partial.name}.")) -> false
                            else -> deleteFileIfPresent(file)
                        }
                    },
                )
            first.awaitRestored()
            if (status == BookDownloadStatus.RUNNING) {
                withTimeout(2_000) { while (!firstTake.get()) kotlinx.coroutines.delay(10) }
            } else {
                partial.writeBytes(byteArrayOf(4, 5))
            }

            first.deleteBookDownload(ITEM_ID)

            val receipt = store.snapshot().single()
            assertEquals(BookDownloadStatus.FAILED.name, receipt.status)
            assertEquals("DELETE_FAILED", receipt.failureReason)
            assertEquals(BookDownloadStatus.FAILED, first.tasks.value.single().status)
            when (failure) {
                CleanupFailure.TARGET -> assertTrue(target.exists())
                CleanupFailure.PARTIAL -> assertTrue(bookPartials(partial).isNotEmpty())
                CleanupFailure.TITLE -> assertTrue(title.exists())
                CleanupFailure.PROVENANCE -> Unit
            }
            firstScope.cancel()

            val retried =
                manager(
                    store = store,
                    transfer =
                        object : BookDownloadTransfer {
                            override suspend fun download(
                                request: BookDownloadTransferRequest,
                                onMetadata: suspend (String?, Long) -> Unit,
                                onProgress: suspend (Long, Long) -> Unit,
                            ): BookDownloadTransferResult = error("删除恢复不得启动网络")
                        },
                    target = target,
                    partial = partial,
                    scope = secondScope,
                    clearProvenance = { true },
                    deleteTitle = { deleteFileIfPresent(title) },
                    deleteFile = { deleteFileIfPresent(it) },
                )
            retried.awaitRestored()
            retried.deleteBookDownload(ITEM_ID)

            assertTrue(store.snapshot().isEmpty())
            assertTrue(retried.tasks.value.isEmpty())
            assertTrue(!target.exists())
            assertTrue(
                "剩余残片：${bookPartials(partial).map { it.name }}",
                bookPartials(partial).isEmpty(),
            )
            assertTrue(!title.exists())
        } finally {
            firstScope.cancel()
            secondScope.cancel()
            root.deleteRecursively()
        }
    }

    private fun manager(
        store: InMemoryBookDownloadTaskStore,
        transfer: BookDownloadTransfer,
        target: File,
        partial: File,
        scope: CoroutineScope,
        credentialsProvider: suspend () -> BookDownloadCredentials = { credentials() },
        recordProvenance: (BookDownloadOrigin) -> Boolean = { true },
        clearProvenance: (UUID) -> Boolean = { true },
        deleteTitle: (UUID) -> Boolean = { true },
        deleteFile: (File) -> Boolean = { deleteFileIfPresent(it) },
    ): BookDownloadManager =
        BookDownloadManager(
            store = store,
            transfer = transfer,
            credentialsProvider = credentialsProvider,
            localFileProvider = { target },
            partialFileProvider = { partial },
            scope = scope,
            recordProvenance = recordProvenance,
            clearProvenance = clearProvenance,
            deleteTitle = deleteTitle,
            deleteFile = deleteFile,
        )

    private fun completedFileTransfer(): BookDownloadTransfer =
        object : BookDownloadTransfer {
            override suspend fun download(
                request: BookDownloadTransferRequest,
                onMetadata: suspend (String?, Long) -> Unit,
                onProgress: suspend (Long, Long) -> Unit,
            ): BookDownloadTransferResult {
                request.partialFile.writeBytes(byteArrayOf(7))
                atomicMove(request.partialFile, request.targetFile)
                return BookDownloadTransferResult(totalBytes = 1, validator = "\"v\"")
            }
        }

    private fun task(
        status: BookDownloadStatus,
        target: File,
        itemId: UUID = ITEM_ID,
        serverId: String = SERVER_ID,
        userId: UUID = USER_ID,
        title: String = "书",
        taskGeneration: Long = 3,
        updatedAt: Long = 1,
        failureReason: String? = null,
    ): BookDownloadTaskDto =
        BookDownloadTaskDto(
            canonicalKey = canonicalKey(itemId, serverId, userId),
            itemId = itemId,
            serverId = serverId,
            userId = userId.toString(),
            title = title,
            targetPath = target.absolutePath,
            downloadedBytes = 3,
            totalBytes = 3,
            status = status.name,
            failureReason = failureReason,
            taskGeneration = taskGeneration,
            createdAt = updatedAt,
            updatedAt = updatedAt,
        )

    private fun deleteReceipt(
        target: File,
        itemId: UUID = ITEM_ID,
        taskGeneration: Long = 3,
        failureReason: String = "DELETE_FAILED",
        updatedAt: Long = 1,
    ): BookDownloadTaskDto =
        task(
            status = BookDownloadStatus.FAILED,
            target = target,
            itemId = itemId,
            serverId = LOCAL_DELETE_SERVER_ID,
            userId = LOCAL_DELETE_USER_ID,
            taskGeneration = taskGeneration,
            updatedAt = updatedAt,
            failureReason = failureReason,
        )

    private fun canonicalKey(itemId: UUID, serverId: String, userId: UUID): String =
        DownloadCanonicalIdentity.of(
                mediaKind = DownloadMediaKind.BOOK,
                itemId = itemId.toString(),
                serverId = serverId,
                userId = userId.toString(),
                sourceId = null,
            )
            .canonicalKey

    private fun localDeleteKey(): String =
        canonicalKey(ITEM_ID, LOCAL_DELETE_SERVER_ID, LOCAL_DELETE_USER_ID)

    private fun credentials() =
        BookDownloadCredentials(
            serverId = SERVER_ID,
            userId = USER_ID,
            baseUrl = "https://server.test",
            accessToken = "token",
        )

    private fun atomicMove(source: File, target: File) {
        Files.move(
            source.toPath(),
            target.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    }

    private fun deleteFileIfPresent(file: File): Boolean = !file.exists() || file.delete()

    private fun bookPartials(basePartial: File): List<File> =
        basePartial.parentFile?.listFiles().orEmpty().filter {
            it.name == basePartial.name || it.name.startsWith("${basePartial.name}.")
        }

    private enum class CleanupFailure {
        TARGET,
        PARTIAL,
        TITLE,
        PROVENANCE,
    }

    private companion object {
        val ITEM_ID: UUID = UUID.fromString("11111111-1111-4111-8111-111111111111")
        val USER_ID: UUID = UUID.fromString("22222222-2222-4222-8222-222222222222")
        val OTHER_USER_ID: UUID = UUID.fromString("33333333-3333-4333-8333-333333333333")
        val OTHER_ITEM_ID: UUID = UUID.fromString("44444444-4444-4444-8444-444444444444")
        const val SERVER_ID = "server-a"
        const val LOCAL_DELETE_SERVER_ID = "__book_local_delete__"
        val LOCAL_DELETE_USER_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000000")
    }
}
