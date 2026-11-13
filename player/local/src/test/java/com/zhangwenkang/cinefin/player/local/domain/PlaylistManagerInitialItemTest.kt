package com.zhangwenkang.cinefin.player.local.domain

import com.zhangwenkang.cinefin.models.FindroidImages
import com.zhangwenkang.cinefin.models.FindroidMovie
import com.zhangwenkang.cinefin.models.FindroidSource
import com.zhangwenkang.cinefin.models.FindroidSourceType
import com.zhangwenkang.cinefin.repository.JellyfinRepository
import java.lang.reflect.Proxy
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.jellyfin.sdk.model.api.BaseItemKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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

    private fun movie(itemId: UUID, sourceId: String): FindroidMovie =
        FindroidMovie(
            id = itemId,
            name = "home video",
            originalTitle = null,
            overview = "",
            sources = listOf(source(id = sourceId)),
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
}
