package com.zhangwenkang.cinefin.presentation.film

import android.content.SharedPreferences
import com.zhangwenkang.cinefin.film.domain.VideoMetadataParser
import com.zhangwenkang.cinefin.film.presentation.episode.EpisodeViewModel
import com.zhangwenkang.cinefin.models.FindroidEpisode
import com.zhangwenkang.cinefin.models.FindroidImages
import com.zhangwenkang.cinefin.repository.JellyfinRepository
import com.zhangwenkang.cinefin.settings.domain.AppPreferences
import java.lang.reflect.Proxy
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EpisodeViewModelTest {
    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `optional episode enrichment failure keeps core episode visible`() = runBlocking {
        val episodeId = UUID.randomUUID()
        val viewModel =
            EpisodeViewModel(
                repository = repository(episode(episodeId)),
                appPreferences = failingPreferences(),
                videoMetadataParser = VideoMetadataParser,
            )

        viewModel.loadEpisode(episodeId)
        val settled = withTimeout(5_000) { viewModel.state.first { state -> state.error != null } }

        assertEquals(episodeId, settled.episode?.id)
        assertEquals("prefs failed", settled.error?.message)
    }

    private fun repository(episode: FindroidEpisode): JellyfinRepository =
        Proxy.newProxyInstance(
            JellyfinRepository::class.java.classLoader,
            arrayOf(JellyfinRepository::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                "getEpisode" -> episode
                "toString" -> "EpisodeViewModelTestRepository"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                else -> throw AssertionError("Unexpected repository call: ${method.name}")
            }
        } as JellyfinRepository

    private fun failingPreferences(): AppPreferences {
        val preferences =
            Proxy.newProxyInstance(
                SharedPreferences::class.java.classLoader,
                arrayOf(SharedPreferences::class.java),
            ) { proxy, method, args ->
                when (method.name) {
                    "getBoolean",
                    "edit" -> throw IllegalStateException("prefs failed")
                    "toString" -> "EpisodeViewModelTestPreferences"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.firstOrNull()
                    else -> throw AssertionError("Unexpected preference call: ${method.name}")
                }
            } as SharedPreferences
        return AppPreferences(preferences)
    }

    private fun episode(id: UUID): FindroidEpisode =
        FindroidEpisode(
            id = id,
            name = "core episode",
            originalTitle = null,
            overview = "",
            indexNumber = 1,
            indexNumberEnd = null,
            parentIndexNumber = 1,
            sources = emptyList(),
            played = false,
            favorite = false,
            canPlay = true,
            canDownload = false,
            runtimeTicks = 0L,
            playbackPositionTicks = 0L,
            premiereDate = null,
            seriesId = UUID.randomUUID(),
            seriesName = "show",
            seasonId = UUID.randomUUID(),
            seasonName = null,
            communityRating = null,
            people = emptyList(),
            images = FindroidImages(),
            chapters = emptyList(),
            trickplayInfo = null,
        )
}
