package com.zhangwenkang.cinefin.player.local.domain

import com.zhangwenkang.cinefin.models.FindroidImages
import com.zhangwenkang.cinefin.models.FindroidMovie
import com.zhangwenkang.cinefin.models.FindroidSource
import com.zhangwenkang.cinefin.models.FindroidSourceType
import com.zhangwenkang.cinefin.repository.JellyfinRepository
import java.io.File
import java.lang.reflect.Proxy
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.jellyfin.sdk.model.api.BaseItemKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistManagerInitialItemTest {
    @Test
    fun `video item kind resolves through movie branch`() = runBlocking {
        val itemId = UUID.fromString("11111111-1111-4111-8111-111111111111")
        val sourceId = "video-source"
        var getMovieCalls = 0
        var getEpisodeCalls = 0
        var getMediaSourcesCalls = 0
        val repository =
            Proxy.newProxyInstance(
                JellyfinRepository::class.java.classLoader,
                arrayOf(JellyfinRepository::class.java),
            ) { proxy, method, args ->
                when (method.name) {
                    "getMovie" -> {
                        getMovieCalls += 1
                        movie(itemId = itemId, sourceId = sourceId)
                    }
                    "getEpisode" -> {
                        getEpisodeCalls += 1
                        error("VIDEO must not use the episode branch")
                    }
                    "getMediaSources" -> {
                        getMediaSourcesCalls += 1
                        listOf(source(id = sourceId))
                    }
                    "toString" -> "PlaylistManagerInitialItemTestRepository"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.firstOrNull()
                    else -> error("Unexpected repository call: ${method.name}")
                }
            } as JellyfinRepository
        val manager = PlaylistManager(repository)

        val item = manager.getInitialItem(itemId = itemId, itemKind = BaseItemKind.VIDEO)

        assertNotNull(item)
        assertEquals(itemId, item?.itemId)
        assertEquals("home video", item?.name)
        assertEquals(1, getMovieCalls)
        assertEquals(0, getEpisodeCalls)
        assertEquals(1, getMediaSourcesCalls)
    }

    @Test
    fun `stale local source without remote keeps typed playback failure`() = runBlocking {
        val itemId = UUID.fromString("22222222-2222-4222-8222-222222222222")
        val missingPath =
            File(
                    System.getProperty("java.io.tmpdir"),
                    "cinefin-stale-local-${UUID.randomUUID()}.mkv",
                )
                .path
        assertFalse(File(missingPath).exists())
        val repository =
            Proxy.newProxyInstance(
                JellyfinRepository::class.java.classLoader,
                arrayOf(JellyfinRepository::class.java),
            ) { proxy, method, args ->
                when (method.name) {
                    "getMovie" -> movie(itemId = itemId, sources = listOf(localSource(missingPath)))
                    "getMediaSources" -> listOf(localSource(missingPath))
                    "toString" -> "PlaylistManagerStaleLocalSourceTestRepository"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.firstOrNull()
                    else -> error("Unexpected repository call: ${method.name}")
                }
            } as JellyfinRepository
        val manager = PlaylistManager(repository)

        val failure = runCatching {
            manager.getInitialItem(itemId = itemId, itemKind = BaseItemKind.MOVIE)
        }
            .exceptionOrNull()

        assertTrue(
            "expected PlaybackStartException, got ${failure?.javaClass?.name}: ${failure?.message}",
            failure is PlaybackStartException,
        )
        assertEquals("这个条目暂时没有可播放的媒体源", failure?.message)
    }

    @Test
    fun `seek rebuild preserves selected source or fails when it is no longer available`() =
        runBlocking {
            val itemId = UUID.fromString("33333333-3333-4333-8333-333333333333")
            val selectedSourceId = "selected-source"
            val otherSourceId = "other-source"
            var availableSources = listOf(source(selectedSourceId), source(otherSourceId))
            val repository =
                Proxy.newProxyInstance(
                    JellyfinRepository::class.java.classLoader,
                    arrayOf(JellyfinRepository::class.java),
                ) { proxy, method, args ->
                    when (method.name) {
                        "getMovie" -> movie(itemId = itemId, sources = availableSources)
                        "getMediaSources" -> availableSources
                        "toString" -> "PlaylistManagerSelectedSourceTestRepository"
                        "hashCode" -> System.identityHashCode(proxy)
                        "equals" -> proxy === args?.firstOrNull()
                        else -> error("Unexpected repository call: ${method.name}")
                    }
                } as JellyfinRepository
            val manager = PlaylistManager(repository)
            val initial =
                manager.getInitialItem(
                    itemId = itemId,
                    itemKind = BaseItemKind.MOVIE,
                    preferredMediaSourceId = selectedSourceId,
                )
            assertEquals(selectedSourceId, initial?.mediaSourceId)

            availableSources = listOf(source(otherSourceId))
            val rebuilt = manager.rebuildPlayerItemForPosition(itemId, 60_000L)

            assertNull(rebuilt)
            assertEquals(selectedSourceId, manager.getPlayerItem(itemId)?.mediaSourceId)
        }

    private fun movie(itemId: UUID, sourceId: String): FindroidMovie =
        movie(itemId = itemId, sources = listOf(source(id = sourceId)))

    private fun movie(itemId: UUID, sources: List<FindroidSource>): FindroidMovie =
        FindroidMovie(
            id = itemId,
            name = "home video",
            originalTitle = null,
            overview = "",
            sources = sources,
            played = false,
            favorite = false,
            canPlay = true,
            canDownload = false,
            runtimeTicks = 0L,
            playbackPositionTicks = 0L,
            premiereDate = null,
            people = emptyList(),
            genres = emptyList(),
            communityRating = null,
            officialRating = null,
            status = "Released",
            productionYear = null,
            endDate = null,
            trailer = null,
            images = FindroidImages(),
            chapters = emptyList(),
            trickplayInfo = emptyMap(),
            kind = BaseItemKind.VIDEO.name,
        )

    private fun source(id: String): FindroidSource =
        FindroidSource(
            id = id,
            name = "home-video.mp4",
            type = FindroidSourceType.REMOTE,
            path = "https://example.invalid/home-video.mp4",
            size = 1L,
            mediaStreams = emptyList(),
        )

    private fun localSource(path: String): FindroidSource =
        FindroidSource(
            id = "stale-local-source",
            name = "missing.mkv",
            type = FindroidSourceType.LOCAL,
            path = path,
            size = 0L,
            mediaStreams = emptyList(),
            container = "mkv",
            downloadTaskStatus = "COMPLETED",
        )
}
