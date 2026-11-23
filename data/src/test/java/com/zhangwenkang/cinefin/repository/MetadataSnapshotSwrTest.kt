package com.zhangwenkang.cinefin.repository

import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataSnapshotSwrTest {

    @Test
    fun `disk hit returns stale value before background refresh completes`() = runBlocking {
        withSwr { fixture ->
            fixture.seedSnapshot("views", "old", fixture.now - 11 * 60 * 1000L)
            val network = CompletableDeferred<String>()
            var fetchCalls = 0

            val value =
                fixture.read("views") {
                    fetchCalls += 1
                    network.await()
                }

            assertEquals("old", value)
            waitUntil { fetchCalls == 1 }
            assertEquals("old", fixture.cache.get<String>("views")?.value)

            network.complete("new")
            waitUntil { fixture.cache.get<String>("views")?.value == "new" }
        }
    }

    @Test
    fun `stale snapshot starts only one background refresh`() = runBlocking {
        withSwr { fixture ->
            fixture.seedSnapshot("views", "old", fixture.now - 11 * 60 * 1000L)
            val network = CompletableDeferred<String>()
            var fetchCalls = 0

            val first =
                fixture.read("views") {
                    fetchCalls += 1
                    network.await()
                }
            val second =
                fixture.read("views") {
                    fetchCalls += 1
                    network.await()
                }

            assertEquals("old", first)
            assertEquals("old", second)
            waitUntil { fetchCalls == 1 }
            delay(50)
            assertEquals(1, fetchCalls)

            network.complete("new")
            waitUntil { fixture.cache.get<String>("views")?.value == "new" }
        }
    }

    @Test
    fun `different keys do not share an in-flight refresh`() = runBlocking {
        withSwr { fixture ->
            val staleAt = fixture.now - 11 * 60 * 1000L
            fixture.seedSnapshot("views", "old-views", staleAt)
            fixture.seedSnapshot("suggestions", "old-suggestions", staleAt)
            val viewsNetwork = CompletableDeferred<String>()
            val suggestionsNetwork = CompletableDeferred<String>()

            assertEquals(
                "old-views",
                fixture.read("views") { viewsNetwork.await() },
            )
            assertEquals(
                "old-suggestions",
                fixture.read("suggestions") { suggestionsNetwork.await() },
            )
            waitUntil { fixture.coordinator.size() == 2 }

            viewsNetwork.complete("new-views")
            waitUntil { fixture.cache.get<String>("views")?.value == "new-views" }
            assertEquals("old-suggestions", fixture.cache.get<String>("suggestions")?.value)

            suggestionsNetwork.complete("new-suggestions")
            waitUntil { fixture.cache.get<String>("suggestions")?.value == "new-suggestions" }
        }
    }

    @Test
    fun `failed background refresh keeps stale snapshot available`() = runBlocking {
        withSwr { fixture ->
            fixture.seedSnapshot("views", "old", fixture.now - 11 * 60 * 1000L)
            val network = CompletableDeferred<String>()

            assertEquals("old", fixture.read("views") { network.await() })
            network.completeExceptionally(IllegalStateException("offline"))
            delay(50)

            assertEquals("old", fixture.read("views") { error("unexpected foreground retry") })
            assertEquals("old", fixture.cache.get<String>("views")?.value)
        }
    }

    @Test
    fun `without a valid snapshot read waits for network`() = runBlocking {
        withSwr { fixture ->
            var fetchCalls = 0

            val value =
                fixture.read("views") {
                    fetchCalls += 1
                    "network"
                }

            assertEquals("network", value)
            assertEquals(1, fetchCalls)
            assertEquals("network", fixture.cache.get<String>("views")?.value)
        }
    }

    @Test
    fun `fresh and stale reads expose explicit snapshot freshness and observed time`() =
        runBlocking {
            withSwr { fixture ->
                fixture.seedSnapshot("views", "fresh", fixture.now)
                val fresh = fixture.readWithMetadata("views") { error("fresh hit must not fetch") }

                val staleAt = fixture.now - 11 * 60 * 1000L
                fixture.seedSnapshot("search:sample", "old", staleAt)
                val refresh = CompletableDeferred<String>()
                val stale = fixture.readWithMetadata("search:sample") { refresh.await() }

                assertEquals(MetadataSnapshotFreshness.FRESH, fresh.freshness)
                assertEquals(fixture.now, fresh.observedAtEpochMs)
                assertEquals(MetadataSnapshotFreshness.STALE, stale.freshness)
                assertEquals(staleAt, stale.observedAtEpochMs)

                refresh.complete("new")
                waitUntil { fixture.cache.get<String>("search:sample")?.value == "new" }
            }
        }

    @Test
    fun `scope validator prevents a late refresh from committing after switch`() = runBlocking {
        withSwr { fixture ->
            fixture.seedSnapshot("views", "old", fixture.now - 11 * 60 * 1000L)
            var scopeCurrent = true
            val network = CompletableDeferred<String>()

            val read =
                fixture.readWithMetadata(
                    key = "views",
                    validateFence = { scopeCurrent },
                ) {
                    network.await()
                }

            assertEquals(MetadataSnapshotFreshness.STALE, read.freshness)
            waitUntil { fixture.coordinator.size() == 1 }
            scopeCurrent = false
            network.complete("late-old-scope")
            waitUntil { fixture.coordinator.size() == 0 }

            assertEquals("old", fixture.cache.get<String>("views")?.value)
        }
    }

    @Test
    fun `scope validator runs before memory snapshot materialization`() = runBlocking {
        withSwr { fixture ->
            val fence = fixture.cache.currentFence()!!
            assertTrue(fixture.cache.putIfCurrent(fence, "views", "cached"))
            var validationCalls = 0
            var materializeCalls = 0

            val failure = runCatching {
                fixture.swr.readWithMetadata(
                    fence = fence,
                    key = "views",
                    policy = MetadataSnapshotTtlPolicy.forKey("views"),
                    decodeSnapshot = { it as? String },
                    fetch = { error("fresh snapshot must not fetch") },
                    materialize = { value ->
                        materializeCalls += 1
                        value
                    },
                    validateFence = {
                        validationCalls += 1
                        validationCalls == 1
                    },
                )
            }
                .exceptionOrNull()

            assertTrue(failure is MetadataSnapshotFenceChangedException)
            assertEquals(0, materializeCalls)
        }
    }

    @Test
    fun `old generation cannot write after user invalidation`() = runBlocking {
        withSwr { fixture ->
            fixture.seedSnapshot("views", "old", fixture.now - 11 * 60 * 1000L)
            val network = CompletableDeferred<String>()

            assertEquals("old", fixture.read("views") { network.await() })
            waitUntil { fixture.coordinator.size() == 1 }
            fixture.swr.invalidateAll()
            network.complete("late-old-generation")
            delay(50)

            val fence = fixture.cache.currentFence()!!
            assertTrue(
                fixture.store.read(
                    fence,
                    "views",
                    MetadataSnapshotTtlPolicy.forKey("views").maxAgeMs,
                ) is MetadataSnapshotReadResult.Miss
            )
            assertNull(fixture.cache.get<String>("views"))
        }
    }

    @Test
    fun `invalidation removes only current namespace snapshots`() = runBlocking {
        withSwr { fixture ->
            val namespaceA = MetadataNamespace("server-a", "user-a")
            val namespaceB = MetadataNamespace("server-b", "user-b")
            fixture.write(namespaceA, "views", "a")
            fixture.write(namespaceB, "views", "b")
            fixture.namespace = namespaceA
            fixture.swr.invalidateAll()

            assertTrue(
                fixture.store.read(
                    MetadataFence(namespaceA, 0L),
                    "views",
                    MetadataSnapshotTtlPolicy.forKey("views").maxAgeMs,
                ) is MetadataSnapshotReadResult.Miss
            )
            fixture.namespace = namespaceB
            assertEquals("b", fixture.read("views") { error("namespace B must stay on disk") })
        }
    }

    @Test
    fun `cold start reads the snapshot left by the previous process generation`() = runBlocking {
        withSwr { fixture ->
            val previousSession = MetadataFence(fixture.namespace, contentGeneration = 3L)
            assertTrue(
                fixture.store.write(previousSession, "views", "cached-before-restart")
                    is MetadataSnapshotWriteResult.Success
            )

            // 重启后当前代次 = 0；断网（fetch 必失败）也必须返回上次缓存，而不是抛错给页面。
            val value = fixture.read("views") { error("network must not be needed") }

            assertEquals("cached-before-restart", value)
        }
    }

    @Test
    fun `browse snapshot older than the refresh window still serves offline inside the read window`() =
        runBlocking {
            withSwr(startNow = 40L * 24 * 60 * 60 * 1000L) { fixture ->
                val twentyDaysAgo = fixture.now - 20L * 24 * 60 * 60 * 1000L
                fixture.seedSnapshot("views", "offline-views", twentyDaysAgo)

                val read = fixture.readWithMetadata("views") { error("network must not be needed") }

                assertEquals("offline-views", read.value)
                assertEquals(MetadataSnapshotFreshness.STALE, read.freshness)
                assertEquals(twentyDaysAgo, read.observedAtEpochMs)
            }
        }

    @Test
    fun `search snapshots keep the shorter offline window`() {
        val searchPolicy = MetadataSnapshotTtlPolicy.forKey("search:sample")
        val browsePolicy = MetadataSnapshotTtlPolicy.forKey("views")

        assertEquals(searchPolicy.maxAgeMs, searchPolicy.readMaxAgeMs)
        assertTrue(browsePolicy.readMaxAgeMs > browsePolicy.maxAgeMs)
    }

    @Test
    fun `without any snapshot the network failure still reaches the page`() = runBlocking {
        withSwr { fixture ->
            val failure = runCatching {
                fixture.read("views") { throw IOException("offline") }
            }
                .exceptionOrNull()

            assertTrue(failure is IOException)
        }
    }

    @Test
    fun `search snapshot outside its window is not served`() = runBlocking {
        withSwr { fixture ->
            val twoDaysAgo = fixture.now - 2L * 24 * 60 * 60 * 1000L
            fixture.seedSnapshot("search:sample", "old-hits", twoDaysAgo)

            val failure = runCatching {
                fixture.read("search:sample") { throw IOException("offline") }
            }
                .exceptionOrNull()

            assertTrue(failure is IOException)
        }
    }

    private suspend fun withSwr(
        startNow: Long = 10_000_000L,
        block: suspend (Fixture) -> Unit,
    ) {
        val directory = Files.createTempDirectory("metadata-swr-test").toFile()
        try {
            val namespace = MetadataNamespace("server-a", "user-a")
            var now = startNow
            lateinit var fixture: Fixture
            val cache = MetadataCache(namespace = { fixture.namespace }, clock = { now })
            val store = MetadataSnapshotStore(directory = directory, clock = { now })
            val coordinator = MetadataRequestCoordinator()
            val swr =
                MetadataSnapshotSwr(
                    cache,
                    store,
                    coordinator,
                    backgroundScope = CoroutineScope(currentCoroutineContext()),
                )
            fixture =
                Fixture(
                    cache = cache,
                    store = store,
                    coordinator = coordinator,
                    swr = swr,
                    namespace = namespace,
                    now = now,
                )
            block(fixture)
        } finally {
            directory.deleteRecursively()
        }
    }

    private suspend fun waitUntil(predicate: () -> Boolean) {
        withTimeout(2_000L) {
            while (!predicate()) {
                delay(5L)
            }
        }
    }

    private data class Fixture(
        val cache: MetadataCache,
        val store: MetadataSnapshotStore,
        val coordinator: MetadataRequestCoordinator,
        val swr: MetadataSnapshotSwr,
        var namespace: MetadataNamespace,
        val now: Long,
    ) {
        fun seedSnapshot(key: String, value: String, savedAt: Long) {
            write(namespace, key, value, savedAt)
        }

        fun write(
            namespace: MetadataNamespace,
            key: String,
            value: String,
            savedAt: Long = now,
        ) {
            assertTrue(
                store.write(
                    MetadataFence(namespace, contentGeneration = 0L),
                    key,
                    value,
                    savedAt,
                ) is MetadataSnapshotWriteResult.Success
            )
        }

        suspend fun read(key: String, fetch: suspend () -> String): String {
            val fence = cache.currentFence()!!
            return swr.read(
                fence = fence,
                key = key,
                policy = MetadataSnapshotTtlPolicy.forKey(key),
                decodeSnapshot = { it as? String },
                fetch = fetch,
                materialize = { it },
            )
        }

        suspend fun readWithMetadata(
            key: String,
            validateFence: (suspend (MetadataFence) -> Boolean)? = null,
            fetch: suspend () -> String,
        ): MetadataSnapshotRead<String> {
            val fence = cache.currentFence()!!
            return swr.readWithMetadata(
                fence = fence,
                key = key,
                policy = MetadataSnapshotTtlPolicy.forKey(key),
                decodeSnapshot = { it as? String },
                fetch = fetch,
                materialize = { it },
                validateFence = validateFence,
            )
        }
    }
}
