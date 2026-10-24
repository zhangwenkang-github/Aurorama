package com.zhangwenkang.cinefin.music.data

import com.zhangwenkang.cinefin.repository.MetadataNamespace
import java.util.UUID
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
