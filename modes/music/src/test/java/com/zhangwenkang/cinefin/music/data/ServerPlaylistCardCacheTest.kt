package com.zhangwenkang.cinefin.music.data

import com.zhangwenkang.cinefin.repository.MetadataNamespace
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerPlaylistCardCacheTest {

    private val serverAUserA = MetadataNamespace("server-a", "user-a")
    private val serverAUserB = MetadataNamespace("server-a", "user-b")
    private val card =
        CachedPlaylistCard(
            id = UUID.fromString("11111111-2222-3333-4444-555555555555"),
            name = "首屏歌单",
            songCount = 7,
            imageTag = "tag-1",
        )

    private class Harness {
        var now = 1_700_000_000_000L
        val writes = mutableMapOf<Pair<MetadataNamespace, String>, CachedPlaylistCards>()
        var reads = 0
        var networkCalls = 0
        var removals = 0

        val cache =
            ServerPlaylistCardCache(
                readSnapshot = { namespace, key, maxAgeMs ->
                    reads += 1
                    writes[namespace to key]?.takeIf { snapshot ->
                        now >= snapshot.savedAtMs && now - snapshot.savedAtMs < maxAgeMs
                    }
                },
                writeSnapshot = { namespace, key, cards, savedAtMs ->
                    writes[namespace to key] = CachedPlaylistCards(cards, savedAtMs)
                },
                deleteSnapshot = { namespace, key ->
                    removals += 1
                    writes.remove(namespace to key)
                },
                clock = { now },
            )
    }

    @Test
    fun `server playlist cards are fresh for twenty four hours`() = runBlocking {
        val harness = Harness()

        val first =
            harness.cache.load(
                namespace = serverAUserA,
                key = "playlists",
                isCurrent = { true },
                fetch = {
                    harness.networkCalls += 1
                    listOf(card)
                },
            )
        harness.now += ServerPlaylistCardCache.TTL_MS - 1L
        val cached =
            harness.cache.load(
                namespace = serverAUserA,
                key = "playlists",
                isCurrent = { true },
                fetch = {
                    harness.networkCalls += 1
                    listOf(card.copy(name = "不应读取"))
                },
            )

        assertEquals(listOf(card), first)
        assertEquals(listOf(card), cached)
        assertEquals(1, harness.networkCalls)
    }

    @Test
    fun `expired server playlist cards refresh and a failed refresh keeps the old cards`() =
        runBlocking {
            val harness = Harness()
            harness.cache.load(
                namespace = serverAUserA,
                key = "playlists",
                isCurrent = { true },
                fetch = { listOf(card) },
            )

            harness.now += ServerPlaylistCardCache.TTL_MS
            val oldAfterFailure =
                harness.cache.load(
                    namespace = serverAUserA,
                    key = "playlists",
                    isCurrent = { true },
                    fetch = { error("offline") },
                )

            assertEquals(listOf(card), oldAfterFailure)
            assertEquals(listOf(card), harness.writes[serverAUserA to "playlists"]?.cards)
        }

    @Test
    fun `server playlist cache never reuses another account namespace`() = runBlocking {
        val harness = Harness()
        harness.cache.load(
            namespace = serverAUserA,
            key = "playlists",
            isCurrent = { true },
            fetch = { listOf(card) },
        )

        val other =
            harness.cache.load(
                namespace = serverAUserB,
                key = "playlists",
                isCurrent = { true },
                fetch = { listOf(card.copy(id = UUID.randomUUID(), name = "账号 B")) },
            )

        assertEquals("账号 B", other.single().name)
        assertEquals(2, harness.writes.size)
        assertTrue(
            harness.writes[serverAUserA to "playlists"] !=
                harness.writes[serverAUserB to "playlists"]
        )
    }

    @Test
    fun `explicit refresh bypasses a fresh playlist summary once`() = runBlocking {
        val harness = Harness()
        harness.cache.load(
            namespace = serverAUserA,
            key = "playlists",
            isCurrent = { true },
            fetch = {
                harness.networkCalls += 1
                listOf(card)
            },
        )
        harness.now += 1L

        val refreshed =
            harness.cache.load(
                namespace = serverAUserA,
                key = "playlists",
                isCurrent = { true },
                forceRefresh = true,
                fetch = {
                    harness.networkCalls += 1
                    listOf(card.copy(name = "服务器新名称", songCount = 6))
                },
            )
        val cached =
            harness.cache.load(
                namespace = serverAUserA,
                key = "playlists",
                isCurrent = { true },
                fetch = {
                    harness.networkCalls += 1
                    listOf(card.copy(name = "不应再次请求"))
                },
            )

        assertEquals("服务器新名称", refreshed.single().name)
        assertEquals(6, refreshed.single().songCount)
        assertEquals("服务器新名称", cached.single().name)
        assertEquals(2, harness.networkCalls)
        assertEquals(harness.now, harness.writes[serverAUserA to "playlists"]?.savedAtMs)
    }

    @Test
    fun `concurrent refresh and load share one playlist request`() = runBlocking {
        val harness = Harness()
        harness.cache.load(
            namespace = serverAUserA,
            key = "playlists",
            isCurrent = { true },
            fetch = { listOf(card) },
        )
        val gate = CompletableDeferred<Unit>()
        var requests = 0

        val refresh =
            async(start = CoroutineStart.UNDISPATCHED) {
                harness.cache.load(
                    namespace = serverAUserA,
                    key = "playlists",
                    isCurrent = { true },
                    forceRefresh = true,
                    fetch = {
                        requests += 1
                        gate.await()
                        listOf(card.copy(name = "刷新结果"))
                    },
                )
            }
        val load =
            async(start = CoroutineStart.UNDISPATCHED) {
                harness.cache.load(
                    namespace = serverAUserA,
                    key = "playlists",
                    isCurrent = { true },
                    fetch = {
                        requests += 1
                        listOf(card.copy(name = "普通读取"))
                    },
                )
            }
        assertEquals(1, requests)

        gate.complete(Unit)

        assertEquals("刷新结果", refresh.await().single().name)
        assertEquals("刷新结果", load.await().single().name)
        assertEquals(1, requests)
    }

    @Test
    fun `failed explicit refresh keeps the previous playlist summary`() = runBlocking {
        val harness = Harness()
        harness.cache.load(
            namespace = serverAUserA,
            key = "playlists",
            isCurrent = { true },
            fetch = {
                harness.networkCalls += 1
                listOf(card)
            },
        )
        val originalSavedAt = harness.writes[serverAUserA to "playlists"]?.savedAtMs

        val fallback =
            harness.cache.load(
                namespace = serverAUserA,
                key = "playlists",
                isCurrent = { true },
                forceRefresh = true,
                fetch = {
                    harness.networkCalls += 1
                    error("offline")
                },
            )

        assertEquals(listOf(card), fallback)
        assertEquals(2, harness.networkCalls)
        assertEquals(originalSavedAt, harness.writes[serverAUserA to "playlists"]?.savedAtMs)
    }

    @Test
    fun `successful removal invalidation forces the next playlist read from network`() =
        runBlocking {
            val harness = Harness()
            harness.cache.load(
                namespace = serverAUserA,
                key = "playlists",
                isCurrent = { true },
                fetch = {
                    harness.networkCalls += 1
                    listOf(card)
                },
            )

            harness.cache.invalidate(serverAUserA, "playlists")
            val afterRemoval =
                harness.cache.load(
                    namespace = serverAUserA,
                    key = "playlists",
                    isCurrent = { true },
                    fetch = {
                        harness.networkCalls += 1
                        listOf(card.copy(name = "删除后的名称", songCount = 6))
                    },
                )

            assertEquals(1, harness.removals)
            assertEquals("删除后的名称", afterRemoval.single().name)
            assertEquals(6, afterRemoval.single().songCount)
            assertEquals(2, harness.networkCalls)
        }

    @Test
    fun `playlist invalidation does not clear another server or user namespace`() = runBlocking {
        val harness = Harness()
        listOf(serverAUserA, serverAUserB).forEach { namespace ->
            harness.cache.load(
                namespace = namespace,
                key = "playlists",
                isCurrent = { true },
                fetch = {
                    harness.networkCalls += 1
                    listOf(card.copy(name = namespace.userId))
                },
            )
        }

        harness.cache.invalidate(serverAUserA, "playlists")
        val unaffected =
            harness.cache.load(
                namespace = serverAUserB,
                key = "playlists",
                isCurrent = { true },
                fetch = { error("namespace B must remain cached") },
            )

        assertEquals("user-b", unaffected.single().name)
        assertEquals(2, harness.networkCalls)
    }

    @Test
    fun `playlist snapshot codec stores only card summary fields`() {
        val encoded = ServerPlaylistCardCache.encodeCards(listOf(card))
        val decoded = ServerPlaylistCardCache.decodeCards(encoded)

        assertEquals(listOf(card), decoded)
        assertTrue(encoded.contains("songCount"))
        assertTrue(encoded.contains("imageTag"))
        assertTrue(!encoded.contains("songs"))
        assertTrue(!encoded.contains("playbackPosition"))
    }
}
