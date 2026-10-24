package com.zhangwenkang.cinefin.update

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class UpdateHttpDownloaderTest {
    private lateinit var directory: File
    private lateinit var store: UpdateStateStore

    @Before
    fun setUp() {
        directory = Files.createTempDirectory("update-download-test").toFile()
        store = UpdateStateStore(directory)
    }

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    @Test
    fun first200WritesWholeFileAndStopsAtReadyForVerification() = runBlocking {
        val release = release(assetSize = 6)
        val body = byteArrayOf(10, 11, 12, 13, 14, 15)
        val transport = ScriptedTransport {
            response(
                statusCode = 200,
                body = body,
                headers = mapOf("ETag" to "\"strong-1\"", "Content-Length" to "6"),
            )
        }
        val progress = mutableListOf<Long>()

        val result = downloader(transport).download(release) { progress.add(it) }

        val ready = result as UpdateDownloadResult.ReadyForVerification
        assertEquals(stableUpdateId(release), ready.updateId)
        assertEquals(6L, ready.bytesWritten)
        assertArrayEquals(body, ready.completeFile.readBytes())
        assertNull(transport.requests.single().rangeStart)
        assertNull(transport.requests.single().ifRange)
        assertEquals(6L, progress.last())
        val transfer =
            (store.loadTransfer() as UpdateStateReadResult.Present<UpdateTransferState>).value
        assertEquals(UpdateTransferPhase.READY_FOR_VERIFICATION, transfer.phase)
        assertEquals("\"strong-1\"", transfer.etag)
        assertFalse(store.partFile(transfer).exists())
    }

    @Test
    fun resumes206WithRangeAndStrongEtag() = runBlocking {
        val release = release(assetSize = 8)
        val transfer = prepareTransfer(release, byteArrayOf(1, 2, 3), etag = "\"strong-1\"")
        val transport = ScriptedTransport {
            response(
                statusCode = 206,
                body = byteArrayOf(4, 5, 6, 7, 8),
                headers =
                    mapOf(
                        "Content-Range" to "bytes 3-7/8",
                        "Content-Length" to "5",
                        "ETag" to "\"strong-1\"",
                    ),
            )
        }

        val result = downloader(transport).download(release)

        assertTrue(result is UpdateDownloadResult.ReadyForVerification)
        assertEquals(3L, transport.requests.single().rangeStart)
        assertEquals("\"strong-1\"", transport.requests.single().ifRange)
        assertArrayEquals(
            byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8),
            store.completeFile(transfer).readBytes(),
        )
    }

    @Test
    fun weakEtagWithoutLastModifiedIsNeverSentAsIfRange() = runBlocking {
        val release = release(assetSize = 5)
        prepareTransfer(release, byteArrayOf(1, 2), etag = "W/\"weak-1\"")
        val transport = ScriptedTransport {
            response(
                statusCode = 206,
                body = byteArrayOf(3, 4, 5),
                headers = mapOf("Content-Range" to "bytes 2-4/5"),
            )
        }

        val result = downloader(transport).download(release)

        assertTrue(result is UpdateDownloadResult.ReadyForVerification)
        assertNull(transport.requests.single().ifRange)
    }

    @Test
    fun weakEtagFallsBackToLastModifiedForIfRange() = runBlocking {
        val release = release(assetSize = 5)
        val lastModified = "Wed, 21 Oct 2015 07:28:00 GMT"
        prepareTransfer(
            release,
            byteArrayOf(1, 2),
            etag = "W/\"weak-1\"",
            lastModified = lastModified,
        )
        val transport = ScriptedTransport {
            response(
                statusCode = 206,
                body = byteArrayOf(3, 4, 5),
                headers = mapOf("Content-Range" to "bytes 2-4/5"),
            )
        }

        val result = downloader(transport).download(release)

        assertTrue(result is UpdateDownloadResult.ReadyForVerification)
        assertEquals(lastModified, transport.requests.single().ifRange)
    }

    @Test
    fun rangeIgnoredBy200TruncatesExistingPartFromZero() = runBlocking {
        val release = release(assetSize = 6)
        val transfer = prepareTransfer(release, byteArrayOf(9, 9, 9), etag = "\"strong-1\"")
        val replacement = byteArrayOf(1, 2, 3, 4, 5, 6)
        val transport = ScriptedTransport {
            response(
                statusCode = 200,
                body = replacement,
                headers = mapOf("Content-Length" to "6"),
            )
        }

        val result = downloader(transport).download(release)

        assertTrue(result is UpdateDownloadResult.ReadyForVerification)
        assertEquals(3L, transport.requests.single().rangeStart)
        assertArrayEquals(replacement, store.completeFile(transfer).readBytes())
    }

    @Test
    fun invalidContentRangeResetsOnceThenRestartsFromZero() = runBlocking {
        val release = release(assetSize = 8)
        val transfer = prepareTransfer(release, byteArrayOf(1, 2, 3), etag = "\"strong-1\"")
        val fullBody = byteArrayOf(10, 11, 12, 13, 14, 15, 16, 17)
        val transport =
            ScriptedTransport(
                responses =
                    ArrayDeque(
                        listOf(
                            {
                                response(
                                    statusCode = 206,
                                    body = byteArrayOf(99, 99, 99),
                                    headers = mapOf("Content-Range" to "bytes 0-2/8"),
                                )
                            },
                            {
                                response(
                                    statusCode = 200,
                                    body = fullBody,
                                    headers = mapOf("Content-Length" to "8"),
                                )
                            },
                        )
                    )
            )

        val result = downloader(transport).download(release)

        assertTrue(result is UpdateDownloadResult.ReadyForVerification)
        assertEquals(2, transport.requests.size)
        assertEquals(3L, transport.requests[0].rangeStart)
        assertNull(transport.requests[1].rangeStart)
        assertArrayEquals(fullBody, store.completeFile(transfer).readBytes())
    }

    @Test
    fun contentRangeTotalMismatchResetsOnceThenRestartsFromZero() = runBlocking {
        val release = release(assetSize = 8)
        val transfer = prepareTransfer(release, byteArrayOf(1, 2, 3))
        val fullBody = byteArrayOf(10, 11, 12, 13, 14, 15, 16, 17)
        val transport =
            ScriptedTransport(
                responses =
                    ArrayDeque(
                        listOf(
                            {
                                response(
                                    statusCode = 206,
                                    body = byteArrayOf(4, 5, 6, 7, 8),
                                    headers = mapOf("Content-Range" to "bytes 3-7/9"),
                                )
                            },
                            {
                                response(
                                    statusCode = 200,
                                    body = fullBody,
                                    headers = mapOf("Content-Length" to "8"),
                                )
                            },
                        )
                    )
            )

        val result = downloader(transport).download(release)

        assertTrue(result is UpdateDownloadResult.ReadyForVerification)
        assertEquals(2, transport.requests.size)
        assertNull(transport.requests[1].rangeStart)
        assertArrayEquals(fullBody, store.completeFile(transfer).readBytes())
    }

    @Test
    fun secondInvalidContentRangeFailsWithoutPretendingCompletion() = runBlocking {
        val release = release(assetSize = 8)
        val transfer = prepareTransfer(release, byteArrayOf(1, 2, 3))
        val invalidResponse = {
            response(
                statusCode = 206,
                body = byteArrayOf(7, 8),
                headers = mapOf("Content-Range" to "bytes 0-1/8"),
            )
        }
        val transport = ScriptedTransport(ArrayDeque(listOf(invalidResponse, invalidResponse)))

        val result = downloader(transport).download(release)

        assertEquals(
            UpdateFailureCode.INVALID_RESPONSE,
            (result as UpdateDownloadResult.Failed).failureCode,
        )
        assertEquals(2, transport.requests.size)
        assertFalse(store.completeFile(transfer).exists())
        assertEquals(
            UpdateTransferPhase.FAILED,
            (store.loadTransfer() as UpdateStateReadResult.Present<UpdateTransferState>)
                .value
                .phase,
        )
    }

    @Test
    fun unsatisfiedRangeResetsOnceThenRestartsFromZero() = runBlocking {
        val release = release(assetSize = 8)
        val transfer = prepareTransfer(release, byteArrayOf(1, 2, 3))
        val fullBody = byteArrayOf(10, 11, 12, 13, 14, 15, 16, 17)
        val transport =
            ScriptedTransport(
                responses =
                    ArrayDeque(
                        listOf(
                            {
                                response(
                                    statusCode = 416,
                                    headers = mapOf("Content-Range" to "bytes */8"),
                                )
                            },
                            {
                                response(
                                    statusCode = 200,
                                    body = fullBody,
                                    headers = mapOf("Content-Length" to "8"),
                                )
                            },
                        )
                    )
            )

        val result = downloader(transport).download(release)

        assertTrue(result is UpdateDownloadResult.ReadyForVerification)
        assertEquals(2, transport.requests.size)
        assertArrayEquals(fullBody, store.completeFile(transfer).readBytes())
    }

    @Test
    fun second416FailsAsRangeNotSatisfiable() = runBlocking {
        val release = release(assetSize = 8)
        prepareTransfer(release, byteArrayOf(1, 2, 3))
        val unsatisfiedResponse = {
            response(
                statusCode = 416,
                headers = mapOf("Content-Range" to "bytes */8"),
            )
        }
        val transport =
            ScriptedTransport(ArrayDeque(listOf(unsatisfiedResponse, unsatisfiedResponse)))

        val result = downloader(transport).download(release)

        assertEquals(
            UpdateFailureCode.RANGE_NOT_SATISFIABLE,
            (result as UpdateDownloadResult.Failed).failureCode,
        )
        assertEquals(2, transport.requests.size)
    }

    @Test
    fun earlyEofIsNetworkErrorAndRetainsActualPartialBytes() = runBlocking {
        val release = release(assetSize = 8)
        val transfer = prepareTransfer(release, byteArrayOf(1, 2, 3))
        val transport = ScriptedTransport {
            response(
                statusCode = 206,
                body = byteArrayOf(4, 5),
                headers =
                    mapOf(
                        "Content-Range" to "bytes 3-7/8",
                        "Content-Length" to "5",
                    ),
            )
        }

        val result = downloader(transport).download(release)

        assertEquals(
            UpdateFailureCode.NETWORK_ERROR,
            (result as UpdateDownloadResult.Failed).failureCode,
        )
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5), store.partFile(transfer).readBytes())
        assertFalse(store.completeFile(transfer).exists())
    }

    @Test
    fun excessResponseBytesNeverLeaveASizeCompletePart() = runBlocking {
        val release = release(assetSize = 3)
        assertEquals(UpdateStateWriteResult.Success, store.beginDownload(release))
        val transfer =
            (store.loadTransfer() as UpdateStateReadResult.Present<UpdateTransferState>).value
        val body =
            object : ByteArrayInputStream(byteArrayOf(1, 2, 3, 4)) {
                override fun read(
                    buffer: ByteArray,
                    offset: Int,
                    length: Int,
                ): Int = super.read(buffer, offset, minOf(length, 3))
            }
        val transport = ScriptedTransport {
            UpdateTransportResponse(
                statusCode = 200,
                headers = emptyMap(),
                body = body,
            )
        }

        val result = downloader(transport).download(release)

        assertEquals(
            UpdateFailureCode.INVALID_RESPONSE,
            (result as UpdateDownloadResult.Failed).failureCode,
        )
        assertFalse(store.completeFile(transfer).exists())
        assertTrue(!store.partFile(transfer).exists() || store.partFile(transfer).length() != 3L)
    }

    @Test
    fun server5xxHasDedicatedFailureCode() = runBlocking {
        val release = release()
        val transport = ScriptedTransport { response(statusCode = 503) }

        val result = downloader(transport).download(release)

        assertEquals(
            UpdateFailureCode.HTTP_SERVER_ERROR,
            (result as UpdateDownloadResult.Failed).failureCode,
        )
    }

    @Test
    fun http429HasDedicatedRateLimitFailureCode() = runBlocking {
        val release = release()
        val transport = ScriptedTransport { response(statusCode = 429) }

        val result = downloader(transport).download(release)

        assertEquals(
            UpdateFailureCode.RATE_LIMITED,
            (result as UpdateDownloadResult.Failed).failureCode,
        )
    }

    @Test
    fun unusablePartStoragePathHasDedicatedStorageFailureCode() = runBlocking {
        val release = release()
        assertEquals(UpdateStateWriteResult.Success, store.beginDownload(release))
        val transfer =
            (store.loadTransfer() as UpdateStateReadResult.Present<UpdateTransferState>).value
        assertTrue(store.partFile(transfer).mkdir())
        val transport = ScriptedTransport { response(statusCode = 200) }

        val result = downloader(transport).download(release)

        assertEquals(
            UpdateFailureCode.STORAGE_ERROR,
            (result as UpdateDownloadResult.Failed).failureCode,
        )
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun coroutineCancellationPropagatesAndRetainsPart() =
        runBlocking(Dispatchers.Default) {
            val release = release(assetSize = 8)
            val transfer = prepareTransfer(release, byteArrayOf(1, 2, 3), etag = "\"strong-1\"")
            val started = CompletableDeferred<Unit>()
            val transport =
                object : UpdateTransport {
                    override suspend fun execute(
                        request: UpdateTransportRequest
                    ): UpdateTransportResponse {
                        started.complete(Unit)
                        awaitCancellation()
                    }
                }

            val job = launch { downloader(transport).download(release) }
            started.await()
            job.cancelAndJoin()

            assertArrayEquals(byteArrayOf(1, 2, 3), store.partFile(transfer).readBytes())
            assertFalse(store.completeFile(transfer).exists())
            assertEquals(
                UpdateTransferPhase.DOWNLOADING,
                (store.loadTransfer() as UpdateStateReadResult.Present<UpdateTransferState>)
                    .value
                    .phase,
            )
        }

    @Test
    fun cancellationDuringBodyReadCancelsTransportAndRetainsWrittenBytes() =
        runBlocking(Dispatchers.Default) {
            val release = release(assetSize = 8)
            val transfer = prepareTransfer(release, byteArrayOf())
            val blocked = CountDownLatch(1)
            val released = CountDownLatch(1)
            val body =
                object : InputStream() {
                    private var reads = 0

                    override fun read(): Int = error("single-byte read is not used")

                    override fun read(
                        buffer: ByteArray,
                        offset: Int,
                        length: Int,
                    ): Int {
                        if (reads++ == 0) {
                            buffer[offset] = 7
                            return 1
                        }
                        blocked.countDown()
                        if (!released.await(5, TimeUnit.SECONDS)) {
                            throw IOException("cancel action was not invoked")
                        }
                        throw IOException("cancelled")
                    }

                    override fun close() {
                        released.countDown()
                    }
                }
            val response =
                UpdateTransportResponse(
                    statusCode = 200,
                    headers = mapOf("Content-Length" to "8"),
                    body = body,
                    closeAction = body::close,
                    cancelAction = released::countDown,
                )
            val transport =
                object : UpdateTransport {
                    override suspend fun execute(
                        request: UpdateTransportRequest
                    ): UpdateTransportResponse = response
                }

            val job = launch { downloader(transport).download(release) }
            assertTrue(blocked.await(5, TimeUnit.SECONDS))
            withTimeout(5_000) { job.cancelAndJoin() }

            assertTrue(job.isCancelled)
            assertArrayEquals(byteArrayOf(7), store.partFile(transfer).readBytes())
            assertFalse(store.completeFile(transfer).exists())
        }

    private fun downloader(transport: UpdateTransport): UpdateHttpDownloader =
        UpdateHttpDownloader(store = store, transport = transport)

    private fun prepareTransfer(
        release: UpdateReleaseIdentity,
        bytes: ByteArray,
        etag: String? = null,
        lastModified: String? = null,
    ): UpdateTransferState {
        assertEquals(UpdateStateWriteResult.Success, store.beginDownload(release, nowEpochMs = 100))
        var transfer =
            (store.loadTransfer() as UpdateStateReadResult.Present<UpdateTransferState>).value
        if (etag != null || lastModified != null) {
            assertEquals(
                UpdateStateWriteResult.Success,
                store.recordValidators(transfer, etag, lastModified, nowEpochMs = 110),
            )
            transfer =
                (store.loadTransfer() as UpdateStateReadResult.Present<UpdateTransferState>).value
        }
        Files.write(
            store.partFile(transfer).toPath(),
            bytes,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
        )
        return transfer
    }

    private fun response(
        statusCode: Int,
        body: ByteArray = byteArrayOf(),
        headers: Map<String, String> = emptyMap(),
    ): UpdateTransportResponse =
        UpdateTransportResponse(
            statusCode = statusCode,
            headers = headers,
            body = ByteArrayInputStream(body),
        )

    private fun release(
        version: String = "1.4.0",
        assetSize: Long = 8,
    ): UpdateReleaseIdentity {
        val assetName = "Aurorama-$version-arm64-v8a.apk"
        return UpdateReleaseIdentity(
            releaseTag = "v$version",
            version = version,
            assetName = assetName,
            assetSize = assetSize,
            assetDigest = "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            downloadUrl = releaseAssetUrl(version, assetName),
        )
    }

    private class ScriptedTransport(
        private val responses: ArrayDeque<() -> UpdateTransportResponse> =
            ArrayDeque(listOf<() -> UpdateTransportResponse>())
    ) : UpdateTransport {
        constructor(responder: () -> UpdateTransportResponse) : this(ArrayDeque(listOf(responder)))

        val requests = mutableListOf<UpdateTransportRequest>()

        override suspend fun execute(request: UpdateTransportRequest): UpdateTransportResponse {
            requests += request
            val responder = responses.removeFirstOrNull() ?: error("no scripted response")
            return responder()
        }
    }
}
