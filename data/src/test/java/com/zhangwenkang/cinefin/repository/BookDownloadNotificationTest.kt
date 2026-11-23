package com.zhangwenkang.cinefin.repository

import com.zhangwenkang.cinefin.models.BookDownloadTaskDto
import com.zhangwenkang.cinefin.utils.DownloadCanonicalIdentity
import com.zhangwenkang.cinefin.utils.DownloadMediaKind
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W9-T7（F5）：书籍下载链的完成 / 失败通知接入点。
 *
 * 契约：整本下载真正跑完的一次尝试回调一次（成功 → [BookDownloadNotificationSink.onCompleted]，失败 →
 * [BookDownloadNotificationSink.onFailed]）；用户暂停 / 取消与冷启动恢复已完成文件都不发通知。
 */
class BookDownloadNotificationTest {

    private val itemId = UUID.fromString("11111111-1111-4111-8111-111111111111")
    private val userId = UUID.fromString("22222222-2222-4222-8222-222222222222")

    @Test
    fun `整本下载完成回调一次完成通知`() = runBlocking {
        val root = createTempDirectory(prefix = "book-notify-complete").toFile()
        val target = File(root, "$itemId.book")
        val partial = File(root, "$itemId.book.part")
        val sink = RecordingNotificationSink()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val manager = manager(sink, target, partial, scope, completedFileTransfer())
            manager.awaitRestored()

            manager.enqueueBookDownload(itemId, "海边的书")
            val notified = withTimeout(2_000) { sink.completedSignal.await() }

            assertEquals(BookDownloadStatus.COMPLETED, manager.tasks.value.single().status)
            assertEquals(1, sink.completed.size)
            assertEquals(0, sink.failed.size)
            assertEquals(itemId, notified.itemId)
            assertEquals("海边的书", notified.title)
            assertTrue(target.isFile)
        } finally {
            scope.cancel()
            root.deleteRecursively()
        }
    }

    @Test
    fun `传输失败回调一次失败通知`() = runBlocking {
        val root = createTempDirectory(prefix = "book-notify-failure").toFile()
        val target = File(root, "$itemId.book")
        val partial = File(root, "$itemId.book.part")
        val sink = RecordingNotificationSink()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val attempted = CompletableDeferred<Unit>()
        val transfer =
            object : BookDownloadTransfer {
                override suspend fun download(
                    request: BookDownloadTransferRequest,
                    onMetadata: suspend (String?, Long) -> Unit,
                    onProgress: suspend (Long, Long) -> Unit,
                ): BookDownloadTransferResult {
                    attempted.complete(Unit)
                    throw IOException("下载书籍失败：HTTP 503（离线时请先联网下载）")
                }
            }
        try {
            val manager = manager(sink, target, partial, scope, transfer)
            manager.awaitRestored()

            manager.enqueueBookDownload(itemId, "海边的书")
            withTimeout(2_000) { attempted.await() }
            val notified = withTimeout(2_000) { sink.failedSignal.await() }

            assertEquals("ERROR", manager.tasks.value.single().failureReason)
            assertEquals(0, sink.completed.size)
            assertEquals(1, sink.failed.size)
            assertEquals("ERROR", notified.failureReason)
            assertEquals("下载书籍失败：HTTP 503（离线时请先联网下载）", notified.error)
        } finally {
            scope.cancel()
            root.deleteRecursively()
        }
    }

    @Test
    fun `用户取消下载不发通知`() = runBlocking {
        val root = createTempDirectory(prefix = "book-notify-cancel").toFile()
        val target = File(root, "$itemId.book")
        val partial = File(root, "$itemId.book.part")
        val sink = RecordingNotificationSink()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val started = CompletableDeferred<Unit>()
        val transfer =
            object : BookDownloadTransfer {
                override suspend fun download(
                    request: BookDownloadTransferRequest,
                    onMetadata: suspend (String?, Long) -> Unit,
                    onProgress: suspend (Long, Long) -> Unit,
                ): BookDownloadTransferResult {
                    request.partialFile.writeBytes(byteArrayOf(1))
                    started.complete(Unit)
                    // 阻塞到用户取消把协程置为取消态（transfer 随后抛 CancellationException）。
                    kotlinx.coroutines.awaitCancellation()
                }
            }
        try {
            val manager = manager(sink, target, partial, scope, transfer)
            manager.awaitRestored()

            manager.enqueueBookDownload(itemId, "海边的书")
            withTimeout(2_000) { started.await() }
            manager.cancelBookDownload(itemId)
            withTimeout(2_000) {
                manager.tasks.first { it.singleOrNull()?.failureReason == "CANCELLED" }
            }

            assertNull(withTimeoutOrNull(200) { sink.completedSignal.await() })
            assertNull(withTimeoutOrNull(200) { sink.failedSignal.await() })
            assertEquals(0, sink.completed.size)
            assertEquals(0, sink.failed.size)
        } finally {
            scope.cancel()
            root.deleteRecursively()
        }
    }

    @Test
    fun `冷启动恢复已完成文件不发通知`() = runBlocking {
        val root = createTempDirectory(prefix = "book-notify-restore").toFile()
        val target = File(root, "$itemId.book")
        val partial = File(root, "$itemId.book.part")
        target.writeBytes(byteArrayOf(7))
        val store = InMemoryBookDownloadTaskStore(listOf(runningTaskDto(target)))
        val sink = RecordingNotificationSink()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val manager = manager(sink, target, partial, scope, completedFileTransfer(), store)
            manager.awaitRestored()
            withTimeout(2_000) {
                manager.tasks.first { it.singleOrNull()?.status == BookDownloadStatus.COMPLETED }
            }

            assertNull(withTimeoutOrNull(200) { sink.completedSignal.await() })
            assertNull(withTimeoutOrNull(200) { sink.failedSignal.await() })
            assertEquals(0, sink.completed.size)
            assertEquals(0, sink.failed.size)
        } finally {
            scope.cancel()
            root.deleteRecursively()
        }
    }

    private fun manager(
        sink: BookDownloadNotificationSink,
        target: File,
        partial: File,
        scope: CoroutineScope,
        transfer: BookDownloadTransfer,
        store: InMemoryBookDownloadTaskStore = InMemoryBookDownloadTaskStore(),
    ): BookDownloadManager =
        BookDownloadManager(
            store = store,
            transfer = transfer,
            credentialsProvider = {
                BookDownloadCredentials(
                    serverId = SERVER_ID,
                    userId = userId,
                    baseUrl = "https://server.test",
                    accessToken = "token",
                )
            },
            localFileProvider = { target },
            partialFileProvider = { partial },
            scope = scope,
            notificationSink = sink,
        )

    private fun completedFileTransfer(): BookDownloadTransfer =
        object : BookDownloadTransfer {
            override suspend fun download(
                request: BookDownloadTransferRequest,
                onMetadata: suspend (String?, Long) -> Unit,
                onProgress: suspend (Long, Long) -> Unit,
            ): BookDownloadTransferResult {
                request.partialFile.writeBytes(byteArrayOf(7))
                Files.move(
                    request.partialFile.toPath(),
                    request.targetFile.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
                return BookDownloadTransferResult(totalBytes = 1, validator = "\"v\"")
            }
        }

    private fun runningTaskDto(target: File): BookDownloadTaskDto {
        val canonicalKey =
            DownloadCanonicalIdentity.of(
                    mediaKind = DownloadMediaKind.BOOK,
                    itemId = itemId.toString(),
                    serverId = SERVER_ID,
                    userId = userId.toString(),
                    sourceId = null,
                )
                .canonicalKey
        return BookDownloadTaskDto(
            canonicalKey = canonicalKey,
            itemId = itemId,
            serverId = SERVER_ID,
            userId = userId.toString(),
            title = "海边的书",
            targetPath = target.absolutePath,
            downloadedBytes = 1,
            totalBytes = 1,
            status = BookDownloadStatus.RUNNING.name,
            taskGeneration = 1,
            createdAt = 1,
            updatedAt = 1,
        )
    }

    private class RecordingNotificationSink : BookDownloadNotificationSink {
        val completed = CopyOnWriteArrayList<BookDownloadTask>()
        val failed = CopyOnWriteArrayList<BookDownloadTask>()
        val completedSignal = CompletableDeferred<BookDownloadTask>()
        val failedSignal = CompletableDeferred<BookDownloadTask>()

        override fun onCompleted(task: BookDownloadTask) {
            completed += task
            completedSignal.complete(task)
        }

        override fun onFailed(task: BookDownloadTask) {
            failed += task
            failedSignal.complete(task)
        }
    }

    private companion object {
        const val SERVER_ID = "server-a"
    }
}
