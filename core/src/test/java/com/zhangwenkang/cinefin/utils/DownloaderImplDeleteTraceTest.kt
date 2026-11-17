package com.zhangwenkang.cinefin.utils

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import com.zhangwenkang.cinefin.di.configureDownloadHttpClient
import java.io.File
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloaderImplDeleteTraceTest {

    @Test
    fun `active delete joins within bound when response body is blocked`() = runBlocking {
        val fixture = DownloaderImplRecoveryTest.Fixture()
        val server = StalledDownloadServer()
        val engine =
            DownloadHttpEngine(
                configureDownloadHttpClient(OkHttpClient.Builder()).build(),
                elapsedRealtimeMillis = { 0L },
            )
        val downloader =
            fixture.newDownloader(
                object : DownloadTransfer {
                    override suspend fun download(
                        request: DownloadTransferRequest
                    ): DownloadAttemptResult =
                        engine.download(
                            url = server.url,
                            token = null,
                            baseUrl = server.baseUrl,
                            target = request.target,
                            existingBytes = request.existingBytes,
                            validator = request.validator,
                            expectedTotalBytes = request.expectedTotalBytes,
                            speedLimitBytesPerSecond = request.speedLimitBytesPerSecond,
                            onProgress = request.onProgress,
                        )
                }
            )
        var queueJob: Job? = null
        try {
            server.start()
            val movie = fixture.movie()
            val sourceId = movie.sources.single().id
            val (handle, error) = downloader.downloadItem(movie, sourceId, 0)
            assertTrue(handle >= 0L)
            assertNull(error)
            queueJob = launch(Dispatchers.Default) { downloader.runQueue() }
            assertTrue("stalled response did not start", server.awaitResponseHeaders(2_000L))
            // 让 OkHttp 走完 execute() 并进入首次 body read。
            delay(200L)
            val task =
                downloader.refreshDownloadTasks().single { task -> task.sourceId == sourceId }

            val deleted = withTimeoutOrNull(2_000L) { downloader.deleteTask(task) }

            assertTrue(
                "active cancel reached join but did not terminate the blocked body",
                deleted == true,
            )
            assertNull(fixture.dao.sourceById(sourceId))
        } finally {
            server.releaseBody()
            queueJob?.cancelAndJoin()
            server.close()
            fixture.close()
        }
    }

    @Test
    fun `real task deletion records D3 D4 pre-row D6 post-row and D7 in control-flow order`() =
        runBlocking {
            val fixture = DownloaderImplRecoveryTest.Fixture()
            val trace = RecordingPort()
            DownloadDeleteTraceRuntime.install(trace)
            var queueJob: kotlinx.coroutines.Job? = null
            try {
                val movie = fixture.movie()
                val sourceId = movie.sources.single().id
                fixture.transfer.holdDownloads()
                val (handle, error) = fixture.downloader.downloadItem(movie, sourceId, 0)
                assertTrue(handle >= 0L)
                assertNull(error)
                queueJob = launch(Dispatchers.Default) { fixture.downloader.runQueue() }
                assertTrue("download job did not start", fixture.transfer.awaitStarted(1))
                val task =
                    fixture.downloader.refreshDownloadTasks().single { task ->
                        task.sourceId == sourceId
                    }
                val root =
                    DownloadDeleteTraceRuntime.start(
                        listOf(
                            DownloadDeleteTraceRequest(
                                stableKey = "leaf:$sourceId",
                                sourceId = sourceId,
                                identity = { traceIdentity(movie.id, sourceId) },
                            )
                        )
                    )

                val deleted = withDownloadDeleteTrace(root) { fixture.downloader.deleteTask(task) }

                assertTrue(deleted)
                queueJob.join()
                val events = trace.handle.events.toList()
                val d3 = events.indexOfFirst {
                    it.phase == DownloadDeleteTracePhase.D3_ROOM_DELETING_COMMITTED
                }
                val d4Cancel = events.indexOfFirst {
                    it.phase == DownloadDeleteTracePhase.D4_CANCEL_BEGIN
                }
                val d4JoinBegin = events.indexOfFirst {
                    it.phase == DownloadDeleteTracePhase.D4_JOIN_BEGIN
                }
                val d4JoinEnd = events.indexOfFirst {
                    it.phase == DownloadDeleteTracePhase.D4_JOIN_END
                }
                val d5Begin = events.indexOfFirst {
                    it.phase == DownloadDeleteTracePhase.D5_FS_PRE_BEGIN && it.category == null
                }
                val d6 = events.indexOfFirst {
                    it.phase == DownloadDeleteTracePhase.D6_SOURCE_MEDIA_ROWS_REMOVED
                }
                val d5Post = events.indexOfFirst {
                    it.phase == DownloadDeleteTracePhase.D5_FS_POST_ROW_BEGIN
                }
                val d5End = events.indexOfFirst { it.phase == DownloadDeleteTracePhase.D5_FS_END }
                val d7Runtime = events.indexOfFirst {
                    it.phase == DownloadDeleteTracePhase.D7_RUNTIME_REMOVED
                }
                val d7Notification = events.indexOfFirst {
                    it.phase == DownloadDeleteTracePhase.D7_NOTIFICATION_DECISION
                }
                val d7Queue = events.indexOfFirst {
                    it.phase == DownloadDeleteTracePhase.D7_QUEUE_SIGNAL
                }

                assertTrue("D3 missing: $events", d3 >= 0)
                assertTrue("active job cancel missing: $events", d4Cancel > d3)
                assertTrue("active job join begin missing: $events", d4JoinBegin > d4Cancel)
                assertTrue("active job join end missing: $events", d4JoinEnd > d4JoinBegin)
                assertTrue("D5 pre missing: $events", d5Begin > d4JoinEnd)
                assertTrue("D6 missing: $events", d6 > d5Begin)
                assertTrue("D5 post-row missing: $events", d5Post > d6)
                assertTrue("D5 end missing: $events", d5End > d5Post)
                assertTrue("D7 runtime missing: $events", d7Runtime > d5End)
                assertTrue("D7 notification missing: $events", d7Notification > d7Runtime)
                assertTrue("D7 queue signal missing: $events", d7Queue > d7Notification)
                assertNull(fixture.dao.source)
            } finally {
                fixture.transfer.release()
                queueJob?.join()
                DownloadDeleteTraceRuntime.uninstall(trace)
                fixture.close()
            }
        }

    @Test
    fun `partial artifact failure records failed category and never fabricates D6`() = runBlocking {
        val fixture = DownloaderImplRecoveryTest.Fixture()
        val trace = RecordingPort()
        DownloadDeleteTraceRuntime.install(trace)
        try {
            val movie = fixture.movie()
            val sourceId = movie.sources.single().id
            val (handle, error) = fixture.downloader.downloadItem(movie, sourceId, 0)
            assertTrue(handle >= 0L)
            assertNull(error)
            val source = requireNotNull(fixture.dao.source)
            val blockedPath = File(source.path)
            blockedPath.delete()
            assertTrue(blockedPath.mkdirs())
            assertTrue(File(blockedPath, "holder").writeText("hold").let { true })
            val task =
                fixture.downloader.refreshDownloadTasks().single { task ->
                    task.sourceId == sourceId
                }
            val root =
                DownloadDeleteTraceRuntime.start(
                    listOf(
                        DownloadDeleteTraceRequest(
                            stableKey = "leaf:$sourceId",
                            sourceId = sourceId,
                            identity = { traceIdentity(movie.id, sourceId) },
                        )
                    )
                )

            val failure = runCatching {
                withDownloadDeleteTrace(root) { fixture.downloader.deleteTask(task) }
            }
                .exceptionOrNull()

            assertTrue("partial failure must retain the existing exception", failure != null)
            val events = trace.handle.events.toList()
            val partialBegin = events.indexOfFirst {
                it.phase == DownloadDeleteTracePhase.D5_FS_PRE_BEGIN &&
                    it.category == DownloadDeleteTraceCategory.PARTIAL
            }
            val partialEnd = events.indexOfFirst {
                it.phase == DownloadDeleteTracePhase.D5_FS_PRE_END &&
                    it.category == DownloadDeleteTraceCategory.PARTIAL
            }
            assertTrue("partial begin missing: $events", partialBegin >= 0)
            assertTrue("partial end missing: $events", partialEnd > partialBegin)
            assertTrue(
                "partial failure must be classified: $events",
                events[partialEnd].result == DownloadDeleteTraceResult.FAILED &&
                    events[partialEnd].error != DownloadDeleteTraceError.NONE,
            )
            assertFalse(
                "failed file cleanup must not fabricate D6: $events",
                events.any { it.phase == DownloadDeleteTracePhase.D6_SOURCE_MEDIA_ROWS_REMOVED },
            )
            assertTrue(File(blockedPath, "holder").delete())
            assertTrue(blockedPath.delete())
        } finally {
            fixture.transfer.release()
            DownloadDeleteTraceRuntime.uninstall(trace)
            fixture.close()
        }
    }

    private fun traceIdentity(itemId: UUID, sourceId: String) =
        DownloadDeleteTraceIdentity(
            mediaKind = DownloadMediaKind.VIDEO,
            serverId = null,
            userId = null,
            itemId = itemId.toString(),
            sourceId = sourceId,
        )

    private class RecordingPort : DownloadDeleteTracePort {
        lateinit var handle: RecordingHandle

        override fun start(requests: List<DownloadDeleteTraceRequest>): DownloadDeleteTraceRoot {
            val accepted = requests.mapIndexed { ordinal, request ->
                request.identity()
                RecordingHandle(request.stableKey).also { created ->
                    created.events +=
                        DownloadDeleteTraceEvent(
                            phase = DownloadDeleteTracePhase.D1_REQUEST_ACCEPTED,
                            result = DownloadDeleteTraceResult.ACCEPTED,
                        )
                    if (ordinal == 0) handle = created
                }
            }
            val bindings =
                requests
                    .zip(accepted)
                    .mapNotNull { (request, created) ->
                        request.sourceId?.let { sourceId ->
                            sourceId to (created as DownloadDeleteTraceHandle)
                        }
                    }
                    .toMap()
            return DownloadDeleteTraceRoot(accepted, bindings)
        }

        override fun beginCanonicalRefresh(
            trigger: DownloadDeleteTraceTrigger
        ): DownloadDeleteTraceRefresh? = null

        override fun rowTrace(stableKey: String): DownloadDeleteTraceRowHandle? = null
    }

    private class RecordingHandle(override val stableKey: String) : DownloadDeleteTraceHandle {
        val events = CopyOnWriteArrayList<DownloadDeleteTraceEvent>()

        override fun bindSource(sourceId: String, generation: Long) = Unit

        override fun record(event: DownloadDeleteTraceEvent) {
            events += event
        }

        override fun pendingCleared() = Unit
    }

    private class StalledDownloadServer {
        private val body = ByteArray(16 * 1024) { index -> index.toByte() }
        private val responseHeadersSent = CompletableDeferred<Unit>()
        private val releaseGate = CompletableDeferred<Unit>()
        private val executor = Executors.newSingleThreadExecutor()
        private val server =
            HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                createContext("/Items/item/Download") { exchange -> respond(exchange) }
                executor = this@StalledDownloadServer.executor
            }

        val baseUrl: String
            get() = "http://127.0.0.1:${server.address.port}"

        val url: String
            get() = "$baseUrl/Items/item/Download"

        fun start() {
            server.start()
        }

        suspend fun awaitResponseHeaders(timeoutMs: Long): Boolean =
            withTimeoutOrNull(timeoutMs) { responseHeadersSent.await() } != null

        fun releaseBody() {
            releaseGate.complete(Unit)
        }

        fun close() {
            server.stop(0)
            executor.shutdownNow()
        }

        private fun respond(exchange: HttpExchange) {
            try {
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { output ->
                    responseHeadersSent.complete(Unit)
                    kotlinx.coroutines.runBlocking { releaseGate.await() }
                    output.write(body)
                    output.flush()
                }
            } catch (_: Exception) {
                responseHeadersSent.complete(Unit)
            } finally {
                exchange.close()
            }
        }
    }
}
