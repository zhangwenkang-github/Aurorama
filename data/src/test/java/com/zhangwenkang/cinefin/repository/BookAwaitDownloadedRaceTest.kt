package com.zhangwenkang.cinefin.repository

import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BookAwaitDownloadedRaceTest {

    @Test
    fun `AlreadyDownloaded 在 await 初检后才出现时于 deadline 返回实际文件`() = runBlocking {
        val root = createTempDirectory(prefix = "book-await-race").toFile()
        val target = File(root, "$ITEM_ID.book")
        val payload = byteArrayOf(4, 5, 6)
        val resolutions = AtomicInteger()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val manager =
                BookDownloadManager(
                    store = InMemoryBookDownloadTaskStore(),
                    transfer =
                        object : BookDownloadTransfer {
                            override suspend fun download(
                                request: BookDownloadTransferRequest,
                                onMetadata: suspend (String?, Long) -> Unit,
                                onProgress: suspend (Long, Long) -> Unit,
                            ): BookDownloadTransferResult = error("本次竞态不应启动新的网络下载")
                        },
                    credentialsProvider = {
                        BookDownloadCredentials(
                            serverId = "server-a",
                            userId = USER_ID,
                            baseUrl = "https://server-a.test",
                            accessToken = "token-a",
                        )
                    },
                    localFileProvider = {
                        if (resolutions.incrementAndGet() == 2) {
                            target.writeBytes(payload)
                        }
                        target
                    },
                    partialFileProvider = { File(root, "$ITEM_ID.book.part") },
                    scope = scope,
                    alreadyDownloadedDeadlineMs = 500,
                )
            manager.awaitRestored()

            val file = withTimeout(1_500) { manager.awaitBookDownload(ITEM_ID, "已在另一入口完成") }

            assertEquals(target, file)
            assertArrayEquals(payload, file.readBytes())
        } finally {
            scope.cancel()
            root.deleteRecursively()
        }
    }

    @Test
    fun `已有完整文件时不依赖当前凭据也不启动 network`() = runBlocking {
        val root = createTempDirectory(prefix = "book-already-offline").toFile()
        val target = File(root, "$ITEM_ID.book")
        val payload = byteArrayOf(8, 9)
        target.writeBytes(payload)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var transferCalls = 0
        try {
            val manager =
                BookDownloadManager(
                    store = InMemoryBookDownloadTaskStore(),
                    transfer =
                        object : BookDownloadTransfer {
                            override suspend fun download(
                                request: BookDownloadTransferRequest,
                                onMetadata: suspend (String?, Long) -> Unit,
                                onProgress: suspend (Long, Long) -> Unit,
                            ): BookDownloadTransferResult {
                                transferCalls++
                                error("已有文件不得启动 network")
                            }
                        },
                    credentialsProvider = { error("已有文件不得读取当前凭据") },
                    localFileProvider = { target },
                    partialFileProvider = { File(root, "$ITEM_ID.book.part") },
                    scope = scope,
                )
            manager.awaitRestored()

            val enqueue = withTimeout(1_000) { manager.enqueueBookDownload(ITEM_ID, "离线任务") }
            val file = withTimeout(1_000) { manager.awaitBookDownload(ITEM_ID, "离线任务") }

            assertEquals(BookDownloadEnqueueResult.AlreadyDownloaded, enqueue)
            assertTrue(file == target)
            assertArrayEquals(payload, file.readBytes())
            assertEquals(0, transferCalls)
        } finally {
            scope.cancel()
            root.deleteRecursively()
        }
    }

    private companion object {
        val ITEM_ID: UUID = UUID.fromString("11111111-1111-4111-8111-111111111111")
        val USER_ID: UUID = UUID.fromString("22222222-2222-4222-8222-222222222222")
    }
}
