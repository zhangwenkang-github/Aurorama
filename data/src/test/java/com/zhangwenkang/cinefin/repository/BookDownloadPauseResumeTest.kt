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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W7-T3（V2-DOWNLOAD-21/22/23/29 书籍侧）：暂停保留残片、恢复 Range 续传、FAILED 手动重试、重启恢复投影。
 *
 * 只使用 127.0.0.1 上的 `LocalRangeServer`，不接触生产服务器。
 */
class BookDownloadPauseResumeTest {

    @Test
    fun `暂停保留残片与字节偏移恢复时从偏移 Range 续传`() = runBlocking {
        val root = createTempDirectory(prefix = "book-pause-resume").toFile()
        val target = File(root, "$ITEM_ID.book")
        val partial = File(root, "$ITEM_ID.book.part")
        val prefix = "paused-prefix".toByteArray(StandardCharsets.UTF_8)
        val tail = "-tail".toByteArray(StandardCharsets.UTF_8)
        val total = (prefix.size + tail.size).toLong()
        val server = LocalRangeServer { exchange, _ ->
            exchange.responseHeaders.add(
                "Content-Range",
                "bytes ${prefix.size}-${prefix.size + tail.size - 1}/$total",
            )
            exchange.responseHeaders.add("ETag", "\"etag-v2\"")
            exchange.sendResponseHeaders(206, tail.size.toLong())
            exchange.responseBody.use { it.write(tail) }
        }
        val started = CompletableDeferred<Unit>()
        val realTransfer = BookDownloadRangeTransfer(OkHttpClient())
        val mode = AtomicReference(TransferMode.BLOCK)
        val transfer =
            object : BookDownloadTransfer {
                override suspend fun download(
                    request: BookDownloadTransferRequest,
                    onMetadata: suspend (String?, Long) -> Unit,
                    onProgress: suspend (Long, Long) -> Unit,
                ): BookDownloadTransferResult =
                    when (mode.get()) {
                        TransferMode.BLOCK -> {
                            request.partialFile.writeBytes(prefix)
                            onMetadata("\"etag-v1\"", total)
                            started.complete(Unit)
                            delay(Long.MAX_VALUE)
                            error("暂停后不应继续读取")
                        }
                        TransferMode.REAL -> realTransfer.download(request, onMetadata, onProgress)
                        TransferMode.FAIL -> throw java.io.IOException("模拟下载失败")
                    }
            }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val manager =
                newManager(
                    store = InMemoryBookDownloadTaskStore(),
                    transfer = transfer,
                    scope = scope,
                    credentials = { server.credentials() },
                    target = target,
                    partial = partial,
                )
            manager.awaitRestored()
            assertEquals(
                BookDownloadEnqueueResult.Queued,
                manager.enqueueBookDownload(ITEM_ID, "暂停测试"),
            )
            withTimeout(5_000) { started.await() }

            assertTrue("RUNNING 任务应可暂停", manager.pauseBookDownload(ITEM_ID))
            val paused = manager.tasks.value.single()
            assertEquals(BookDownloadStatus.PAUSED, paused.status)
            assertEquals(prefix.size.toLong(), paused.downloadedBytes)
            val pausedPartial = generationPartial(partial, generation = paused.taskGeneration)
            assertTrue("暂停必须保留 .part 残片", pausedPartial.isFile)
            assertEquals(prefix.size.toLong(), pausedPartial.length())
            assertTrue(!target.exists())

            mode.set(TransferMode.REAL)
            assertTrue("PAUSED 任务应可继续", manager.resumeBookDownload(ITEM_ID))

            val completed =
                withTimeout(5_000) {
                    manager.tasks.first { tasks ->
                        tasks.singleOrNull()?.status == BookDownloadStatus.COMPLETED
                    }
                }
            assertEquals(BookDownloadStatus.COMPLETED, completed.single().status)
            assertEquals("bytes=${prefix.size}-", server.requests.single().range)
            assertEquals("\"etag-v1\"", server.requests.single().ifRange)
            assertArrayEquals(prefix + tail, target.readBytes())
            assertEquals(total, completed.single().totalBytes)
        } finally {
            scope.cancel()
            server.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun `FAILED 任务可手动重试并重新落盘完整文件`() = runBlocking {
        val root = createTempDirectory(prefix = "book-retry").toFile()
        val target = File(root, "$ITEM_ID.book")
        val partial = File(root, "$ITEM_ID.book.part")
        val payload = "retried-book".toByteArray(StandardCharsets.UTF_8)
        val server = LocalRangeServer { exchange, _ ->
            exchange.sendResponseHeaders(200, payload.size.toLong())
            exchange.responseBody.use { it.write(payload) }
        }
        val realTransfer = BookDownloadRangeTransfer(OkHttpClient())
        val mode = AtomicReference(TransferMode.FAIL)
        val transfer =
            object : BookDownloadTransfer {
                override suspend fun download(
                    request: BookDownloadTransferRequest,
                    onMetadata: suspend (String?, Long) -> Unit,
                    onProgress: suspend (Long, Long) -> Unit,
                ): BookDownloadTransferResult =
                    when (mode.get()) {
                        TransferMode.FAIL -> throw java.io.IOException("模拟网络失败")
                        else -> realTransfer.download(request, onMetadata, onProgress)
                    }
            }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val manager =
                newManager(
                    store = InMemoryBookDownloadTaskStore(),
                    transfer = transfer,
                    scope = scope,
                    credentials = { server.credentials() },
                    target = target,
                    partial = partial,
                )
            manager.awaitRestored()
            manager.enqueueBookDownload(ITEM_ID, "重试测试")
            val failed =
                withTimeout(5_000) {
                    manager.tasks.first { tasks ->
                        tasks.singleOrNull()?.status == BookDownloadStatus.FAILED
                    }
                }
            assertEquals("ERROR", failed.single().failureReason)

            mode.set(TransferMode.REAL)
            assertTrue(manager.retryBookDownload(ITEM_ID))

            val completed =
                withTimeout(5_000) {
                    manager.tasks.first { tasks ->
                        tasks.singleOrNull()?.status == BookDownloadStatus.COMPLETED
                    }
                }
            assertEquals(BookDownloadStatus.COMPLETED, completed.single().status)
            assertArrayEquals(payload, target.readBytes())
        } finally {
            scope.cancel()
            server.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun `重启后 PAUSED 保持暂停不自动续传`() = runBlocking {
        val root = createTempDirectory(prefix = "book-restore-paused").toFile()
        val target = File(root, "$ITEM_ID.book")
        val partial = File(root, "$ITEM_ID.book.part")
        val prefix = "kept-paused".toByteArray(StandardCharsets.UTF_8)
        val kept = generationPartial(partial, generation = 7)
        kept.writeBytes(prefix)
        val store =
            InMemoryBookDownloadTaskStore(
                listOf(
                    persistedTask(
                        target = target,
                        status = BookDownloadStatus.PAUSED,
                        downloadedBytes = prefix.size.toLong(),
                        validator = "\"v7\"",
                        generation = 7,
                    )
                )
            )
        val invoked = CopyOnWriteArrayList<BookDownloadTransferRequest>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val manager =
                newManager(
                    store = store,
                    transfer =
                        object : BookDownloadTransfer {
                            override suspend fun download(
                                request: BookDownloadTransferRequest,
                                onMetadata: suspend (String?, Long) -> Unit,
                                onProgress: suspend (Long, Long) -> Unit,
                            ): BookDownloadTransferResult {
                                invoked += request
                                error("PAUSED 任务重启后不得自动发起网络请求")
                            }
                        },
                    scope = scope,
                    credentials = {
                        BookDownloadCredentials(
                            serverId = "server-a",
                            userId = USER_ID,
                            baseUrl = "https://server-a.test",
                            accessToken = "token-a",
                        )
                    },
                    target = target,
                    partial = partial,
                )
            manager.awaitRestored()
            delay(200)
            val restored = manager.tasks.value.single()
            assertEquals(BookDownloadStatus.PAUSED, restored.status)
            assertEquals(prefix.size.toLong(), restored.downloadedBytes)
            assertTrue("重启后残片必须保留", kept.isFile)
            assertTrue("PAUSED 是用户意图，重启不得自动续传", invoked.isEmpty())
        } finally {
            scope.cancel()
            root.deleteRecursively()
        }
    }

    @Test
    fun `重启后 PENDING 任务按残片 206 续传补齐`() = runBlocking {
        val root = createTempDirectory(prefix = "book-restore-pending").toFile()
        val target = File(root, "$ITEM_ID.book")
        val partial = File(root, "$ITEM_ID.book.part")
        val prefix = "pending-prefix".toByteArray(StandardCharsets.UTF_8)
        val tail = "-tail".toByteArray(StandardCharsets.UTF_8)
        val total = (prefix.size + tail.size).toLong()
        generationPartial(partial, generation = 3).writeBytes(prefix)
        val store =
            InMemoryBookDownloadTaskStore(
                listOf(
                    persistedTask(
                        target = target,
                        status = BookDownloadStatus.PENDING,
                        downloadedBytes = prefix.size.toLong(),
                        validator = "\"v3\"",
                        generation = 3,
                    )
                )
            )
        val server = LocalRangeServer { exchange, _ ->
            exchange.responseHeaders.add(
                "Content-Range",
                "bytes ${prefix.size}-${prefix.size + tail.size - 1}/$total",
            )
            exchange.sendResponseHeaders(206, tail.size.toLong())
            exchange.responseBody.use { it.write(tail) }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val manager =
                newManager(
                    store = store,
                    transfer = BookDownloadRangeTransfer(OkHttpClient()),
                    scope = scope,
                    credentials = { server.credentials() },
                    target = target,
                    partial = partial,
                )
            manager.awaitRestored()
            withTimeout(5_000) {
                manager.tasks.first { tasks ->
                    tasks.singleOrNull()?.status == BookDownloadStatus.COMPLETED
                }
            }
            assertEquals("bytes=${prefix.size}-", server.requests.single().range)
            assertArrayEquals(prefix + tail, target.readBytes())
        } finally {
            scope.cancel()
            server.close()
            root.deleteRecursively()
        }
    }

    private fun newManager(
        store: BookDownloadTaskStore,
        transfer: BookDownloadTransfer,
        scope: CoroutineScope,
        credentials: () -> BookDownloadCredentials,
        target: File,
        partial: File,
    ): BookDownloadManager =
        BookDownloadManager(
            store = store,
            transfer = transfer,
            credentialsProvider = { credentials() },
            localFileProvider = { target },
            partialFileProvider = { partial },
            scope = scope,
            recordProvenance = { true },
            clearProvenance = { true },
        )

    private fun persistedTask(
        target: File,
        status: BookDownloadStatus,
        downloadedBytes: Long,
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
            totalBytes = 0L,
            status = status.name,
            resumeValidator = validator,
            taskGeneration = generation,
            createdAt = 1,
            updatedAt = 1,
        )

    private fun generationPartial(basePartial: File, generation: Long): File =
        File(basePartial.parentFile, "${basePartial.name}.$generation")

    private enum class TransferMode {
        BLOCK,
        REAL,
        FAIL,
    }

    private companion object {
        val ITEM_ID: UUID = UUID.fromString("11111111-1111-4111-8111-111111111111")
        val USER_ID: UUID = UUID.fromString("22222222-2222-4222-8222-222222222222")
    }
}
