package com.zhangwenkang.cinefin.repository

import com.zhangwenkang.cinefin.models.BookDownloadTaskDto
import com.zhangwenkang.cinefin.utils.DownloadCanonicalIdentity
import com.zhangwenkang.cinefin.utils.DownloadMediaKind
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BookDownloadRecoveryTest {

    @Test
    fun `全新实例从持久 RUNNING 任务恢复并使用 206 续传补齐 part`() = runBlocking {
        val root = createTempDirectory(prefix = "book-recovery").toFile()
        val target = File(root, "$ITEM_ID.book")
        val partial = File(root, "$ITEM_ID.book.part")
        val prefix = "persisted-prefix".toByteArray(StandardCharsets.UTF_8)
        partial.writeBytes(prefix)
        val tail = "-tail".toByteArray(StandardCharsets.UTF_8)
        val store =
            InMemoryBookDownloadTaskStore(
                listOf(
                    persistedTask(
                        target = target,
                        downloadedBytes = prefix.size.toLong(),
                        totalBytes = (prefix.size + tail.size).toLong(),
                        validator = "\"persisted\"",
                        generation = 41,
                    )
                )
            )
        val provenances = CopyOnWriteArrayList<BookDownloadOrigin>()
        val server = LocalRangeServer { exchange, _ ->
            exchange.responseHeaders.add(
                "Content-Range",
                "bytes ${prefix.size}-${prefix.size + tail.size - 1}/${prefix.size + tail.size}",
            )
            exchange.sendResponseHeaders(206, tail.size.toLong())
            exchange.responseBody.use { it.write(tail) }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val manager =
                BookDownloadManager(
                    store = store,
                    transfer = BookDownloadRangeTransfer(OkHttpClient()),
                    credentialsProvider = { server.credentials() },
                    localFileProvider = { target },
                    partialFileProvider = { partial },
                    scope = scope,
                    recordProvenance = { provenances += it },
                )
            manager.awaitRestored()
            val completed =
                withTimeout(5_000) {
                    manager.tasks.first { tasks ->
                        tasks.singleOrNull()?.status == BookDownloadStatus.COMPLETED
                    }
                }

            assertEquals("bytes=${prefix.size}-", server.requests.single().range)
            assertTrue(target.isFile)
            assertEquals(prefix.size + tail.size, target.length().toInt())
            assertEquals(BookDownloadStatus.COMPLETED, completed.single().status)
            assertTrue(completed.single().taskGeneration > 41)
            assertEquals(1, provenances.size)
        } finally {
            scope.cancel()
            server.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun `下载途中切换当前账号仍使用入队时冻结的 origin`() = runBlocking {
        val root = createTempDirectory(prefix = "book-origin-freeze").toFile()
        val target = File(root, "$ITEM_ID.book")
        val partial = File(root, "$ITEM_ID.book.part")
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val captured = AtomicReference<BookDownloadCredentials>()
        val mutable =
            AtomicReference(
                BookDownloadCredentials(
                    serverId = "server-a",
                    userId = USER_ID,
                    baseUrl = "https://server-a.test",
                    accessToken = "token-a",
                )
            )
        val transfer =
            object : BookDownloadTransfer {
                override suspend fun download(
                    request: BookDownloadTransferRequest,
                    onMetadata: suspend (String?, Long) -> Unit,
                    onProgress: suspend (Long, Long) -> Unit,
                ): BookDownloadTransferResult {
                    captured.set(request.credentials)
                    partial.writeBytes(byteArrayOf(1, 2, 3))
                    started.complete(Unit)
                    release.await()
                    target.writeBytes(byteArrayOf(1, 2, 3))
                    return BookDownloadTransferResult(
                        totalBytes = 3,
                        validator = "\"frozen\"",
                    )
                }
            }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val manager =
                BookDownloadManager(
                    store = InMemoryBookDownloadTaskStore(),
                    transfer = transfer,
                    credentialsProvider = { mutable.get() },
                    localFileProvider = { target },
                    partialFileProvider = { partial },
                    scope = scope,
                )
            manager.awaitRestored()
            manager.enqueueBookDownload(ITEM_ID, "书")
            withTimeout(2_000) { started.await() }
            mutable.set(
                BookDownloadCredentials(
                    serverId = "server-b",
                    userId = UUID.fromString("33333333-3333-4333-8333-333333333333"),
                    baseUrl = "https://server-b.test",
                    accessToken = "token-b",
                )
            )
            release.complete(Unit)
            withTimeout(2_000) {
                manager.tasks.first { it.singleOrNull()?.status != BookDownloadStatus.RUNNING }
            }

            assertEquals("server-a", captured.get().serverId)
            assertEquals("token-a", captured.get().accessToken)
        } finally {
            scope.cancel()
            root.deleteRecursively()
        }
    }

    @Test
    fun `取消旧 generation 后迟到结果不得覆盖新任务`() = runBlocking {
        val root = createTempDirectory(prefix = "book-generation").toFile()
        val target = File(root, "$ITEM_ID.book")
        val partial = File(root, "$ITEM_ID.book.part")
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()
        val releaseSecond = CompletableDeferred<Unit>()
        val invocations = CopyOnWriteArrayList<Long>()
        val transfer =
            object : BookDownloadTransfer {
                override suspend fun download(
                    request: BookDownloadTransferRequest,
                    onMetadata: suspend (String?, Long) -> Unit,
                    onProgress: suspend (Long, Long) -> Unit,
                ): BookDownloadTransferResult {
                    invocations += request.taskGeneration
                    partial.writeBytes(byteArrayOf(request.taskGeneration.toByte()))
                    if (invocations.size == 1) {
                        firstStarted.complete(Unit)
                        withContext(NonCancellable) { releaseFirst.await() }
                    } else {
                        secondStarted.complete(Unit)
                        releaseSecond.await()
                        target.writeBytes(byteArrayOf(1))
                    }
                    return BookDownloadTransferResult(totalBytes = 1, validator = "\"v\"")
                }
            }
        val store = InMemoryBookDownloadTaskStore()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val manager =
                BookDownloadManager(
                    store = store,
                    transfer = transfer,
                    credentialsProvider = {
                        BookDownloadCredentials(
                            serverId = "server-a",
                            userId = USER_ID,
                            baseUrl = "https://server-a.test",
                            accessToken = "token-a",
                        )
                    },
                    localFileProvider = { target },
                    partialFileProvider = { partial },
                    scope = scope,
                )
            manager.awaitRestored()
            manager.enqueueBookDownload(ITEM_ID, "书")
            withTimeout(2_000) { firstStarted.await() }
            val firstGeneration = invocations.single()
            manager.cancelBookDownload(ITEM_ID)
            withTimeout(2_000) {
                manager.tasks.first { it.singleOrNull()?.failureReason == "CANCELLED" }
            }

            manager.enqueueBookDownload(ITEM_ID, "书")
            withTimeout(2_000) { secondStarted.await() }
            val secondGeneration = invocations.last()
            assertTrue(secondGeneration > firstGeneration)

            releaseFirst.complete(Unit)
            Thread.sleep(100)
            val duringSecond = manager.tasks.value.single { it.itemId == ITEM_ID }
            assertEquals(secondGeneration, duringSecond.taskGeneration)
            assertEquals(BookDownloadStatus.RUNNING, duringSecond.status)
            assertTrue(partial.exists())

            releaseSecond.complete(Unit)
            val completed =
                withTimeout(2_000) {
                    manager.tasks.first {
                        it.singleOrNull()?.status == BookDownloadStatus.COMPLETED
                    }
                }
            assertEquals(secondGeneration, completed.single().taskGeneration)
            assertEquals(secondGeneration, store.snapshot().single().taskGeneration)
        } finally {
            scope.cancel()
            root.deleteRecursively()
        }
    }

    @Test
    fun `删除书籍任务同时清理 book part title 与 provenance`() = runBlocking {
        val root = createTempDirectory(prefix = "book-delete").toFile()
        val target = File(root, "$ITEM_ID.book")
        val partial = File(root, "$ITEM_ID.book.part")
        val title = File(root, "$ITEM_ID.title")
        target.writeBytes(byteArrayOf(1, 2, 3))
        partial.writeBytes(byteArrayOf(4, 5))
        title.writeText("书名")
        val persisted =
            persistedTask(
                    target = target,
                    downloadedBytes = 3,
                    totalBytes = 3,
                    validator = "\"complete\"",
                    generation = 8,
                )
                .copy(status = BookDownloadStatus.COMPLETED.name)
        val store = InMemoryBookDownloadTaskStore(listOf(persisted))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var provenanceCleared = 0
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
                            ): BookDownloadTransferResult = error("已完成任务删除不得启动网络")
                        },
                    credentialsProvider = {
                        BookDownloadCredentials(
                            serverId = "server-a",
                            userId = USER_ID,
                            baseUrl = "https://server-a.test",
                            accessToken = "token-a",
                        )
                    },
                    localFileProvider = { target },
                    partialFileProvider = { partial },
                    scope = scope,
                    clearProvenance = { provenanceCleared++ },
                    deleteTitle = { title.delete() },
                )
            manager.awaitRestored()
            manager.deleteBookDownload(ITEM_ID)

            assertTrue(store.snapshot().isEmpty())
            assertTrue(manager.tasks.value.isEmpty())
            assertTrue(!target.exists())
            assertTrue(!partial.exists())
            assertTrue(!title.exists())
            assertEquals(1, provenanceCleared)
        } finally {
            scope.cancel()
            root.deleteRecursively()
        }
    }

    @Test
    fun `transfer 未真正提交完整文件时不得写 provenance 或完成`() = runBlocking {
        val root = createTempDirectory(prefix = "book-missing-commit").toFile()
        val target = File(root, "$ITEM_ID.book")
        val partial = File(root, "$ITEM_ID.book.part")
        partial.writeBytes(byteArrayOf(1, 2, 3))
        val store = InMemoryBookDownloadTaskStore()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var provenanceWrites = 0
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
                            ): BookDownloadTransferResult =
                                BookDownloadTransferResult(totalBytes = 3, validator = "\"v\"")
                        },
                    credentialsProvider = {
                        BookDownloadCredentials(
                            serverId = "server-a",
                            userId = USER_ID,
                            baseUrl = "https://server-a.test",
                            accessToken = "token-a",
                        )
                    },
                    localFileProvider = { target },
                    partialFileProvider = { partial },
                    scope = scope,
                    recordProvenance = { provenanceWrites++ },
                )
            manager.awaitRestored()
            manager.enqueueBookDownload(ITEM_ID, "书")

            val failed =
                withTimeout(2_000) {
                    manager.tasks.first { it.singleOrNull()?.status != BookDownloadStatus.RUNNING }
                }

            assertEquals(BookDownloadStatus.FAILED, failed.single().status)
            assertEquals(0, provenanceWrites)
            assertTrue(partial.exists())
            assertTrue(!target.exists())
        } finally {
            scope.cancel()
            root.deleteRecursively()
        }
    }

    @Test
    fun `恢复时若完整文件已原子出现则补写 provenance 并收敛完成`() = runBlocking {
        val root = createTempDirectory(prefix = "book-recover-post-rename").toFile()
        val target = File(root, "$ITEM_ID.book")
        val partial = File(root, "$ITEM_ID.book.part")
        target.writeBytes(byteArrayOf(1, 2, 3))
        val persisted =
            persistedTask(
                target = target,
                downloadedBytes = 3,
                totalBytes = 3,
                validator = "\"renamed\"",
                generation = 12,
            )
        val store = InMemoryBookDownloadTaskStore(listOf(persisted))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val provenances = CopyOnWriteArrayList<BookDownloadOrigin>()
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
                            ): BookDownloadTransferResult = error("完整文件已存在时不得重新请求网络")
                        },
                    credentialsProvider = {
                        BookDownloadCredentials(
                            serverId = "server-a",
                            userId = USER_ID,
                            baseUrl = "https://server-a.test",
                            accessToken = "token-a",
                        )
                    },
                    localFileProvider = { target },
                    partialFileProvider = { partial },
                    scope = scope,
                    recordProvenance = { provenances += it },
                )
            manager.awaitRestored()

            val completed =
                withTimeout(2_000) {
                    manager.tasks.first {
                        it.singleOrNull()?.status == BookDownloadStatus.COMPLETED
                    }
                }
            assertEquals(1, provenances.size)
            assertEquals("server-a", provenances.single().serverId)
            assertEquals(BookDownloadStatus.COMPLETED, completed.single().status)
            assertTrue(!partial.exists())
        } finally {
            scope.cancel()
            root.deleteRecursively()
        }
    }

    private fun persistedTask(
        target: File,
        downloadedBytes: Long,
        totalBytes: Long,
        validator: String?,
        generation: Long,
    ) =
        BookDownloadTaskDto(
            canonicalKey =
                DownloadCanonicalIdentity.of(
                        mediaKind = DownloadMediaKind.BOOK,
                        itemId = ITEM_ID.toString(),
                        serverId = "server-a",
                        userId = USER_ID.toString(),
                        sourceId = null,
                    )
                    .canonicalKey,
            itemId = ITEM_ID,
            serverId = "server-a",
            userId = USER_ID.toString(),
            title = "持久任务",
            targetPath = target.absolutePath,
            downloadedBytes = downloadedBytes,
            totalBytes = totalBytes,
            status = BookDownloadStatus.RUNNING.name,
            resumeValidator = validator,
            taskGeneration = generation,
            createdAt = 1,
            updatedAt = 1,
        )

    private companion object {
        val ITEM_ID: UUID = UUID.fromString("11111111-1111-4111-8111-111111111111")
        val USER_ID: UUID = UUID.fromString("22222222-2222-4222-8222-222222222222")
    }
}

internal class InMemoryBookDownloadTaskStore(initial: List<BookDownloadTaskDto> = emptyList()) :
    BookDownloadTaskStore {
    private val tasks = linkedMapOf<String, BookDownloadTaskDto>()

    init {
        initial.forEach { tasks[it.canonicalKey] = it }
    }

    override suspend fun load(): List<BookDownloadTaskDto> =
        synchronized(tasks) { tasks.values.toList() }

    override suspend fun save(task: BookDownloadTaskDto) {
        synchronized(tasks) { tasks[task.canonicalKey] = task }
    }

    override suspend fun delete(canonicalKey: String) {
        synchronized(tasks) { tasks.remove(canonicalKey) }
    }

    fun snapshot(): List<BookDownloadTaskDto> = synchronized(tasks) { tasks.values.toList() }
}
