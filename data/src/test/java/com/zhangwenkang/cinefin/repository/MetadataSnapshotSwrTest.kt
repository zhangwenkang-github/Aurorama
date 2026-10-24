package com.zhangwenkang.cinefin.repository

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

    private suspend fun withSwr(block: suspend (Fixture) -> Unit) {
        val directory = Files.createTempDirectory("metadata-swr-test").toFile()
        try {
            val namespace = MetadataNamespace("server-a", "user-a")
            var now = 10_000_000L
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
    }
}
