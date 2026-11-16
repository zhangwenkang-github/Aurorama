package com.zhangwenkang.cinefin.repository

import android.content.SharedPreferences
import com.sun.net.httpserver.HttpServer
import com.zhangwenkang.cinefin.database.ServerDatabaseDao
import com.zhangwenkang.cinefin.identity.IdentityIngressAttempt
import com.zhangwenkang.cinefin.identity.IdentityReadLease
import com.zhangwenkang.cinefin.identity.IdentityReadLeasePort
import com.zhangwenkang.cinefin.identity.IdentityReadPort
import com.zhangwenkang.cinefin.identity.IdentitySelection
import com.zhangwenkang.cinefin.identity.IdentitySnapshot
import com.zhangwenkang.cinefin.identity.IdentityStateStatus
import com.zhangwenkang.cinefin.local.LocalLibraryRepository
import com.zhangwenkang.cinefin.models.Server
import com.zhangwenkang.cinefin.models.ServerAddress
import com.zhangwenkang.cinefin.models.ServerWithAddressAndUser
import com.zhangwenkang.cinefin.models.User
import com.zhangwenkang.cinefin.settings.domain.AppPreferences
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.DurationUnit
import kotlin.time.toDuration
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.jellyfin.sdk.api.client.HttpClientOptions
import org.jellyfin.sdk.api.okhttp.OkHttpFactory
import org.jellyfin.sdk.api.operations.MediaInfoApi
import org.jellyfin.sdk.api.operations.VideosApi
import org.jellyfin.sdk.model.ClientInfo
import org.jellyfin.sdk.model.DeviceInfo
import org.jellyfin.sdk.model.api.MediaProtocol
import org.jellyfin.sdk.model.api.MediaSourceInfo
import org.jellyfin.sdk.model.api.MediaSourceType
import org.jellyfin.sdk.model.api.MediaStreamProtocol
import org.jellyfin.sdk.model.api.PlaybackInfoResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackSourceResolutionTest {
    private val itemId = UUID.fromString("11111111-1111-4111-8111-111111111111")
    private val userId = UUID.fromString("22222222-2222-4222-8222-222222222222")
    private val addressId = UUID.fromString("33333333-3333-4333-8333-333333333333")
    private val serverId = "fixture-server"
    private val token = "fixture-token"

    @Test
    fun `real sdk call timeout terminates post header body stall with one timeout terminal`() =
        runBlocking {
            val responseBytes = playbackInfoResponseBytes()
            StalledPlaybackInfoFixture(responseBytes).use { fixture ->
                val resolver = resolver(fixture.baseUrl, callTimeoutMs = 350L, call = realSdkCall())
                val startedAt = System.nanoTime()

                val result =
                    withTimeout(4_000L) {
                        resolver.resolve(
                            itemId = itemId,
                            includePath = true,
                            startPositionTicks = 0L,
                            attempt = PlaybackSourceAttemptContext.new(),
                        )
                    }
                val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L

                assertTrue(fixture.requestReceived.await(1, TimeUnit.SECONDS))
                assertTrue(fixture.bodyAwaiting.await(1, TimeUnit.SECONDS))
                assertEquals(PlaybackSourceTerminal.TIMEOUT, result.terminal)
                assertNull(result.failure)
                assertEquals(1, result.terminalPublishCount)
                assertEquals(PlaybackEndpointSchemeBucket.HTTP, result.identity?.schemeBucket)
                assertEquals(
                    PlaybackEndpointPortBucket.NON_DEFAULT,
                    result.identity?.portBucket,
                )
                assertTrue(result.identity?.hostHash?.matches(Regex("[0-9A-F]{16}")) == true)
                assertTrue("elapsedMs=$elapsedMs", elapsedMs < 3_000L)
                assertFalse(result.toString().contains("127.0.0.1"))
                assertFalse(result.toString().contains(token))
            }
        }

    @Test
    fun `identity change after request start fails closed and never maps late response`() =
        runBlocking {
            val responseBytes = playbackInfoResponseBytes()
            StalledPlaybackInfoFixture(responseBytes).use { fixture ->
                val identityPort = RacingIdentityReadPort(readySnapshot(fixture.baseUrl))
                val capturedIdentity = AtomicReference<PlaybackSourceIdentity?>()
                val capturedRequestUserId = AtomicReference<UUID?>()
                val call = ScopedPlaybackInfoCall { identity, _, _, request ->
                    capturedIdentity.set(identity)
                    capturedRequestUserId.set(request.userId)
                    realSdkCall().execute(identity, 1_000L, itemId, request)
                }
                val resolver =
                    resolver(
                        baseUrl = fixture.baseUrl,
                        callTimeoutMs = 1_000L,
                        call = call,
                        identityReadPort = identityPort,
                    )
                val attempt = PlaybackSourceAttemptContext.new()
                val job =
                    async(Dispatchers.IO) {
                        resolver.resolve(
                            itemId = itemId,
                            includePath = true,
                            startPositionTicks = 0L,
                            attempt = attempt,
                        )
                    }

                fixture.bodyAwaiting.await(2, TimeUnit.SECONDS)
                identityPort.changeIdentity(
                    readySnapshot(
                        baseUrl = fixture.baseUrl,
                        userId = UUID.fromString("44444444-4444-4444-8444-444444444444"),
                        generation = 2L,
                    )
                )
                fixture.release.countDown()
                val result = withTimeout(4_000L) { job.await() }

                assertEquals(result.toString(), PlaybackSourceTerminal.FAILED, result.terminal)
                assertEquals(
                    result.toString(),
                    PlaybackSourceFailure.IDENTITY_CHANGED,
                    result.failure,
                )
                assertTrue(result.sources.isEmpty())
                assertEquals(1, result.terminalPublishCount)
                assertEquals(attempt.sourceIntentId, result.sourceIntentId)
                assertEquals(attempt.attemptId, result.attemptId)
                assertEquals(1L, capturedIdentity.get()?.generation)
                assertEquals(userId, capturedRequestUserId.get())
                assertFalse(result.toString().contains(token))
                assertFalse(result.toString().contains(fixture.baseUrl))
            }
        }

    @Test
    fun `identity change before timeout maps timeout to failed identity change`() = runBlocking {
        val responseBytes = playbackInfoResponseBytes()
        StalledPlaybackInfoFixture(responseBytes).use { fixture ->
            val identityPort = RacingIdentityReadPort(readySnapshot(fixture.baseUrl))
            val resolver =
                resolver(
                    baseUrl = fixture.baseUrl,
                    callTimeoutMs = 350L,
                    call = realSdkCall(),
                    identityReadPort = identityPort,
                )
            val job =
                async(Dispatchers.IO) {
                    resolver.resolve(
                        itemId = itemId,
                        includePath = true,
                        startPositionTicks = 0L,
                        attempt = PlaybackSourceAttemptContext.new(),
                    )
                }

            fixture.bodyAwaiting.await(2, TimeUnit.SECONDS)
            identityPort.changeIdentity(
                readySnapshot(
                    baseUrl = fixture.baseUrl,
                    generation = 2L,
                )
            )
            val result = withTimeout(4_000L) { job.await() }

            assertEquals(result.toString(), PlaybackSourceTerminal.FAILED, result.terminal)
            assertEquals(result.toString(), PlaybackSourceFailure.IDENTITY_CHANGED, result.failure)
            assertEquals(1, result.terminalPublishCount)
        }
    }

    @Test
    fun `request uses captured user and endpoint generation instead of mutable current identity`() =
        runBlocking {
            val identityPort =
                RacingIdentityReadPort(
                    readySnapshot(
                        baseUrl = "https://initial.example/jellyfin",
                        generation = 7L,
                    )
                )
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val capturedIdentity = AtomicReference<PlaybackSourceIdentity?>()
            val capturedRequestUserId = AtomicReference<UUID?>()
            val call = ScopedPlaybackInfoCall { identity, _, _, request ->
                capturedIdentity.set(identity)
                capturedRequestUserId.set(request.userId)
                started.complete(Unit)
                release.await()
                PlaybackInfoResponse(mediaSources = emptyList(), playSessionId = "fixture")
            }
            val resolver =
                resolver(
                    baseUrl = "https://initial.example/jellyfin",
                    callTimeoutMs = 1_000L,
                    call = call,
                    identityReadPort = identityPort,
                )
            val job =
                async(Dispatchers.IO) {
                    resolver.resolve(
                        itemId,
                        includePath = true,
                        startPositionTicks = 90_000_000L,
                        attempt = PlaybackSourceAttemptContext.new(),
                    )
                }

            started.await()
            identityPort.changeIdentity(
                readySnapshot(
                    baseUrl = "https://changed.example/jellyfin",
                    userId = UUID.fromString("55555555-5555-4555-8555-555555555555"),
                    generation = 8L,
                )
            )
            release.complete(Unit)
            val result = withTimeout(2_000L) { job.await() }

            assertEquals(7L, capturedIdentity.get()?.generation)
            assertEquals("https://initial.example/jellyfin", capturedIdentity.get()?.baseUrl)
            assertEquals(token, capturedIdentity.get()?.accessToken)
            assertEquals(userId, capturedRequestUserId.get())
            assertEquals(PlaybackSourceTerminal.FAILED, result.terminal)
            assertEquals(PlaybackSourceFailure.IDENTITY_CHANGED, result.failure)
            assertEquals(7L, result.identity?.identityGeneration)
        }

    @Test
    fun `resolved response maps through frozen endpoint and publishes once`() = runBlocking {
        val call = ScopedPlaybackInfoCall { _, _, _, _ ->
            PlaybackInfoResponse(
                mediaSources =
                    listOf(
                        MediaSourceInfo(
                            protocol = MediaProtocol.HTTP,
                            id = "source-1",
                            path = "https://initial.example/media",
                            name = "fixture",
                            type = MediaSourceType.DEFAULT,
                            isRemote = false,
                            readAtNativeFramerate = false,
                            ignoreDts = false,
                            ignoreIndex = false,
                            genPtsInput = false,
                            supportsTranscoding = false,
                            supportsDirectStream = true,
                            supportsDirectPlay = true,
                            isInfiniteStream = false,
                            requiresOpening = false,
                            requiresClosing = false,
                            requiresLooping = false,
                            supportsProbing = false,
                            transcodingSubProtocol = MediaStreamProtocol.HTTP,
                            hasSegments = false,
                            mediaStreams = emptyList(),
                        )
                    ),
                playSessionId = "fixture-session",
                errorCode = null,
            )
        }
        val resolver =
            resolver(
                baseUrl = "https://initial.example/jellyfin",
                callTimeoutMs = 1_000L,
                call = call,
            )

        val result =
            resolver.resolve(
                itemId = itemId,
                includePath = true,
                startPositionTicks = 0L,
                attempt = PlaybackSourceAttemptContext.new(),
            )

        assertEquals(PlaybackSourceTerminal.RESOLVED, result.terminal)
        assertNull(result.failure)
        assertEquals(1, result.terminalPublishCount)
        assertEquals(1, result.resolvedSourcesOrThrow().size)
        assertEquals("source-1", result.resolvedSourcesOrThrow().single().id)
        assertEquals(PlaybackEndpointSchemeBucket.HTTPS, result.identity?.schemeBucket)
        assertEquals(PlaybackEndpointPortBucket.HTTPS_DEFAULT, result.identity?.portBucket)
        assertFalse(result.toString().contains("initial.example"))
    }

    @Test
    fun `sdk stream url matches frozen endpoint mapping`() = runBlocking {
        val apiClient =
            sdkApiClient(
                baseUrl = "https://initial.example/jellyfin",
                requestedToken = token,
                callTimeoutMs = 1_000L,
            )
        val mediaSource =
            MediaSourceInfo(
                protocol = MediaProtocol.FILE,
                id = "source-1",
                path = "/fixture",
                name = "fixture",
                type = MediaSourceType.DEFAULT,
                isRemote = false,
                readAtNativeFramerate = false,
                ignoreDts = false,
                ignoreIndex = false,
                genPtsInput = false,
                supportsTranscoding = false,
                supportsDirectStream = true,
                supportsDirectPlay = true,
                isInfiniteStream = false,
                requiresOpening = false,
                requiresClosing = false,
                requiresLooping = false,
                supportsProbing = false,
                transcodingSubProtocol = MediaStreamProtocol.HTTP,
                hasSegments = false,
                mediaStreams = emptyList(),
            )

        val url =
            VideosApi(apiClient)
                .getVideoStreamUrl(
                    itemId = itemId,
                    static = true,
                    mediaSourceId = "source-1",
                )
        val call = ScopedPlaybackInfoCall { _, _, _, _ ->
            PlaybackInfoResponse(
                mediaSources = listOf(mediaSource),
                playSessionId = "fixture-session",
                errorCode = null,
            )
        }
        val resolver =
            resolver(
                baseUrl = "https://initial.example/jellyfin",
                callTimeoutMs = 1_000L,
                call = call,
            )
        val result =
            resolver.resolve(
                itemId = itemId,
                includePath = true,
                startPositionTicks = 0L,
                attempt = PlaybackSourceAttemptContext.new(),
            )

        assertEquals(url, result.resolvedSourcesOrThrow().single().path)
    }

    @Test
    fun `empty media sources publish one explicit failure`() = runBlocking {
        val call = ScopedPlaybackInfoCall { _, _, _, _ ->
            PlaybackInfoResponse(
                mediaSources = emptyList(),
                playSessionId = "fixture-session",
                errorCode = null,
            )
        }
        val resolver =
            resolver(
                baseUrl = "https://initial.example/jellyfin",
                callTimeoutMs = 1_000L,
                call = call,
            )

        val result =
            resolver.resolve(
                itemId = itemId,
                includePath = true,
                startPositionTicks = 0L,
                attempt = PlaybackSourceAttemptContext.new(),
            )

        assertEquals(PlaybackSourceTerminal.FAILED, result.terminal)
        assertEquals(PlaybackSourceFailure.NO_MEDIA_SOURCE, result.failure)
        assertEquals(1, result.terminalPublishCount)
        assertTrue(result.sources.isEmpty())
    }

    @Test
    fun `resolved failure and timeout race publishes exactly one terminal`() {
        repeat(100) {
            val lease = PlaybackSourceAttemptLease(PlaybackSourceAttemptContext.new())
            val start = CountDownLatch(1)
            val executor = Executors.newFixedThreadPool(3)
            try {
                executor.execute {
                    start.await()
                    lease.publishResolved(emptyList(), null)
                }
                executor.execute {
                    start.await()
                    lease.publishFailed(PlaybackSourceFailure.NETWORK_ERROR, null)
                }
                executor.execute {
                    start.await()
                    lease.publishTimeout(null)
                }
                start.countDown()
            } finally {
                executor.shutdown()
                assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS))
            }

            assertEquals(1, lease.publicationCount())
            assertTrue(lease.current() != null)
        }
    }

    private fun resolver(
        baseUrl: String,
        callTimeoutMs: Long,
        call: ScopedPlaybackInfoCall,
        identityReadPort: IdentityReadPort = RacingIdentityReadPort(readySnapshot(baseUrl)),
    ): PlaybackSourceResolver {
        val sharedPreferences = mockk<SharedPreferences>(relaxed = true)
        every { sharedPreferences.getString(any(), any()) } answers { secondArg() }
        every { sharedPreferences.getBoolean(any(), any()) } answers { secondArg() }
        every { sharedPreferences.getInt(any(), any()) } answers { secondArg() }
        every { sharedPreferences.getFloat(any(), any()) } answers { secondArg() }
        every { sharedPreferences.getStringSet(any(), any()) } answers { secondArg() }
        every { sharedPreferences.getLong(any(), any()) } answers
            {
                if (firstArg<String>() == "pref_network_request_timeout") {
                    callTimeoutMs
                } else {
                    secondArg()
                }
            }
        val database = mockk<ServerDatabaseDao>(relaxed = true)
        coEvery { database.getServerWithAddressAndUser(serverId) } returns relation(baseUrl)
        coEvery { database.getCompletedSource(itemId) } returns null
        coEvery { database.getSources(itemId) } returns emptyList()
        val localLibrary = mockk<LocalLibraryRepository>(relaxed = true)
        coEvery { localLibrary.syntheticSources(itemId) } returns null
        return PlaybackSourceResolver(
            database = database,
            localLibrary = localLibrary,
            appPreferences = AppPreferences(sharedPreferences),
            identityReadPort = identityReadPort,
            playbackInfoCall = call,
        )
    }

    private fun relation(baseUrl: String): ServerWithAddressAndUser {
        val address =
            ServerAddress(
                id = addressId,
                serverId = serverId,
                address = baseUrl,
            )
        return ServerWithAddressAndUser(
            server =
                Server(
                    id = serverId,
                    name = "fixture",
                    currentServerAddressId = addressId,
                    currentUserId = userId,
                ),
            address = address,
            user =
                User(
                    id = userId,
                    name = "user",
                    serverId = serverId,
                    accessToken = token,
                ),
        )
    }

    private fun readySnapshot(
        baseUrl: String,
        userId: UUID = this.userId,
        generation: Long,
    ): IdentitySnapshot =
        IdentitySnapshot(
            selection =
                IdentitySelection(
                    serverId = serverId,
                    userId = userId.toString(),
                    addressId = addressId.toString(),
                    offlineMode = false,
                ),
            identityGeneration = generation,
            status = IdentityStateStatus.READY,
        )

    private fun readySnapshot(
        baseUrl: String,
        generation: Long = 1L,
    ): IdentitySnapshot = readySnapshot(baseUrl, userId, generation)

    private fun playbackInfoResponseBytes(): ByteArray =
        Json.encodeToString(
                PlaybackInfoResponse(
                    mediaSources = emptyList(),
                    playSessionId = "fixture-session",
                    errorCode = null,
                )
            )
            .toByteArray(Charsets.UTF_8)

    private fun realSdkCall(): ScopedPlaybackInfoCall =
        ScopedPlaybackInfoCall { identity, callTimeoutMs, requestItemId, request ->
            val apiClient =
                sdkApiClient(
                    baseUrl = identity.baseUrl,
                    requestedToken = identity.accessToken,
                    callTimeoutMs = callTimeoutMs,
                )
            MediaInfoApi(apiClient).getPostedPlaybackInfo(requestItemId, request).content
        }

    private fun sdkApiClient(
        baseUrl: String,
        requestedToken: String,
        callTimeoutMs: Long,
    ) =
        OkHttpFactory(OkHttpClient()).let { factory ->
            factory.create(
                baseUrl = baseUrl,
                accessToken = requestedToken,
                clientInfo = ClientInfo(name = "AuroramaFixture", version = "1"),
                deviceInfo = DeviceInfo(id = "fixture-device", name = "fixture-device"),
                httpClientOptions =
                    HttpClientOptions(
                        requestTimeout = callTimeoutMs.toDuration(DurationUnit.MILLISECONDS),
                        connectTimeout = 1_000L.toDuration(DurationUnit.MILLISECONDS),
                        socketTimeout = 5_000L.toDuration(DurationUnit.MILLISECONDS),
                    ),
                socketConnectionFactory = factory,
            )
        }

    private class RacingIdentityReadPort(initial: IdentitySnapshot) :
        IdentityReadPort, IdentityReadLeasePort {
        @Volatile private var snapshot = initial
        @Volatile private var intent = 1L

        fun changeIdentity(next: IdentitySnapshot) {
            snapshot = next
            intent += 1L
        }

        override suspend fun hydrate(): IdentitySnapshot = snapshot

        override suspend fun current(): IdentitySnapshot = snapshot

        override suspend fun acquireReadyLease(): IdentityReadLease? =
            if (snapshot.status == IdentityStateStatus.READY) {
                IdentityReadLease(snapshot = snapshot, intent = intent)
            } else {
                null
            }

        override suspend fun <T> withReadyLease(
            lease: IdentityReadLease,
            effect: suspend (IdentitySelection) -> T,
        ): T? =
            if (isReadyLeaseCurrent(lease)) {
                effect(lease.selection)
            } else {
                null
            }

        override suspend fun isReadyLeaseCurrent(lease: IdentityReadLease): Boolean =
            lease.intent == intent &&
                lease.snapshot == snapshot &&
                snapshot.status == IdentityStateStatus.READY

        override suspend fun beginIngressForLease(
            lease: IdentityReadLease
        ): IdentityIngressAttempt? = null
    }

    private class StalledPlaybackInfoFixture(private val responseBytes: ByteArray) : AutoCloseable {
        private val executor = Executors.newSingleThreadExecutor()
        val requestReceived = CountDownLatch(1)
        val bodyAwaiting = CountDownLatch(1)
        val release = CountDownLatch(1)
        private val server =
            HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
                createContext("/") { exchange ->
                    try {
                        requestReceived.countDown()
                        exchange.responseHeaders.add("Content-Type", "application/json")
                        exchange.sendResponseHeaders(200, responseBytes.size.toLong())
                        exchange.responseBody.use { output ->
                            if (responseBytes.isNotEmpty()) {
                                output.write(responseBytes, 0, 1)
                                output.flush()
                            }
                            bodyAwaiting.countDown()
                            release.await(5, TimeUnit.SECONDS)
                            if (responseBytes.size > 1) {
                                output.write(responseBytes, 1, responseBytes.size - 1)
                                output.flush()
                            }
                        }
                    } catch (_: Exception) {
                        // Client-side call timeout normally closes the fixture stream.
                    } finally {
                        exchange.close()
                    }
                }
                executor = this@StalledPlaybackInfoFixture.executor
                start()
            }

        val baseUrl: String
            get() = "http://127.0.0.1:${server.address.port}"

        override fun close() {
            release.countDown()
            server.stop(0)
            executor.shutdownNow()
        }
    }
}
