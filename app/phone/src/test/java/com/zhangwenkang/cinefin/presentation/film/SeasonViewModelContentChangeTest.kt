package com.zhangwenkang.cinefin.presentation.film

import com.zhangwenkang.cinefin.core.presentation.dummy.dummyEpisode
import com.zhangwenkang.cinefin.core.presentation.dummy.dummySeason
import com.zhangwenkang.cinefin.film.presentation.season.SeasonViewModel
import com.zhangwenkang.cinefin.models.FindroidEpisode
import com.zhangwenkang.cinefin.models.FindroidSeason
import com.zhangwenkang.cinefin.repository.JellyfinRepository
import com.zhangwenkang.cinefin.repository.UserDataEvents
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

/**
 * W9-T6 ⑩：季页头部「季 · N」= `state.episodes.size`；删除服务器条目后仓库广播
 * [UserDataEvents.contentVersion]，季页必须在广播当下重载，不能等下一次生命周期重进。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SeasonViewModelContentChangeTest {
    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `内容变更广播后季页集数立即刷新`() = runBlocking {
        val episodes =
            (1..14).map { index ->
                dummyEpisode.copy(
                    id = UUID.randomUUID(),
                    indexNumber = index,
                    seasonId = SEASON_ID,
                )
            }
        var currentEpisodes = episodes
        val viewModel = SeasonViewModel(repository { currentEpisodes })

        viewModel.loadSeason(SEASON_ID)
        withTimeout(5_000) { viewModel.state.first { it.episodes.size == 14 } }

        // 模拟删除一集后的仓库广播：服务器已少一集。
        currentEpisodes = episodes.drop(1).map { it.copy(indexNumber = it.indexNumber - 1) }
        UserDataEvents.notifyContentChanged()

        val refreshed = withTimeout(5_000) { viewModel.state.first { it.episodes.size == 13 } }
        assertEquals(13, refreshed.episodes.size)
        assertEquals(1, refreshed.episodes.first().indexNumber)
    }

    private fun repository(episodes: () -> List<FindroidEpisode>): JellyfinRepository =
        Proxy.newProxyInstance(
            JellyfinRepository::class.java.classLoader,
            arrayOf(JellyfinRepository::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                "getSeason" -> season()
                "getEpisodes" -> episodes()
                "toString" -> "SeasonViewModelContentChangeTestRepository"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                else -> throw AssertionError("Unexpected repository call: ${method.name}")
            }
        } as JellyfinRepository

    private fun season(): FindroidSeason =
        dummySeason.copy(id = SEASON_ID, seriesId = SERIES_ID, episodes = emptyList())

    private companion object {
        val SEASON_ID: UUID = UUID.fromString("11111111-1111-4111-8111-111111111111")
        val SERIES_ID: UUID = UUID.fromString("22222222-2222-4222-8222-222222222222")
    }
}
