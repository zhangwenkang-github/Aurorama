package com.zhangwenkang.cinefin.repository

import android.content.Context
import android.content.SharedPreferences
import com.zhangwenkang.cinefin.api.JellyfinApi
import com.zhangwenkang.cinefin.database.ServerDatabaseDao
import com.zhangwenkang.cinefin.local.LocalLibraryRepository
import com.zhangwenkang.cinefin.settings.domain.AppPreferences
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.util.UUID
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.jellyfin.sdk.api.client.Response
import org.jellyfin.sdk.api.operations.TvShowsApi
import org.jellyfin.sdk.api.operations.UserLibraryApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemDtoQueryResult
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ItemFields
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class JellyfinEpisodeMappingTest {
    @Test
    fun `video dto requested as episode fails with illegal state instead of npe`() = runBlocking {
        val itemId = UUID.fromString("11111111-1111-4111-8111-111111111111")
        val userId = UUID.fromString("22222222-2222-4222-8222-222222222222")
        val item =
            BaseItemDto(
                id = itemId,
                name = "home video",
                type = BaseItemKind.VIDEO,
            )
        val response = mockk<Response<BaseItemDto>>(relaxed = true)
        every { response.content } returns item
        val userLibraryApi = mockk<UserLibraryApi>()
        coEvery { userLibraryApi.getItem(itemId, userId) } returns response
        val api =
            mockk<JellyfinApi>(relaxed = true).apply {
                every { this@apply.userId } returns userId
                every { this@apply.userLibraryApi } returns userLibraryApi
            }
        val database = mockk<ServerDatabaseDao>(relaxed = true)
        coEvery { database.getCompletedSource(itemId) } returns null
        coEvery { database.getSources(itemId) } returns emptyList()
        val repository =
            JellyfinRepositoryImpl(
                context = mockk<Context>(relaxed = true),
                jellyfinApi = api,
                database = database,
                appPreferences = AppPreferences(mockk<SharedPreferences>(relaxed = true)),
                localLibrary = mockk<LocalLibraryRepository>(relaxed = true),
                metadataSnapshotStore = mockk(relaxed = true),
            )

        val failure = runCatching { repository.getEpisode(itemId) }.exceptionOrNull()

        assertTrue(
            "expected IllegalStateException, got ${failure?.javaClass?.name}: ${failure?.message}",
            failure is IllegalStateException,
        )
        assertFalse(failure is NullPointerException)
        assertEquals("该条目不是可播放的剧集", failure?.message)
    }

    @Test
    fun `online episodes sdk response is consumed off the caller thread`() = runBlocking {
        val callerDispatcher =
            Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "caller-main-like") }
                .asCoroutineDispatcher()
        try {
            lateinit var callerThread: Thread
            lateinit var sdkCallThread: Thread
            lateinit var sdkResumeThread: Thread
            lateinit var bodyConsumptionThread: Thread
            lateinit var continuationThread: Thread
            val response = mockk<Response<BaseItemDtoQueryResult>>(relaxed = true)
            every { response.content } answers
                {
                    bodyConsumptionThread = Thread.currentThread()
                    BaseItemDtoQueryResult(
                        items = listOf(episodeDto()),
                        totalRecordCount = 1,
                        startIndex = 0,
                    )
                }
            val showsApi = episodesApi { callThread, resumeThread ->
                sdkCallThread = callThread
                sdkResumeThread = resumeThread
                response
            }
            val repository =
                repository(
                    api = jellyfinApi(showsApi),
                    database = database(),
                )

            val episodes =
                withContext(callerDispatcher) {
                    callerThread = Thread.currentThread()
                    val result =
                        repository.getEpisodes(
                            seriesId = SERIES_ID,
                            seasonId = SEASON_ID,
                            fields = EPISODE_FIELDS,
                            startItemId = null,
                            limit = null,
                            offline = false,
                        )
                    continuationThread = Thread.currentThread()
                    result
                }

            assertEquals(listOf(EPISODE_ID), episodes.map { it.id })
            assertEquals("Episode 1", episodes.single().name)
            assertEquals(SERIES_ID, episodes.single().seriesId)
            assertEquals(SEASON_ID, episodes.single().seasonId)
            assertNotEquals(callerThread, sdkCallThread)
            assertNotEquals(callerThread, sdkResumeThread)
            assertNotEquals(callerThread, bodyConsumptionThread)
            assertSame(callerThread, continuationThread)
        } finally {
            callerDispatcher.close()
        }
    }

    @Test
    fun `online episodes empty response remains an empty target list`() = runBlocking {
        val response = mockk<Response<BaseItemDtoQueryResult>>(relaxed = true)
        every { response.content } returns
            BaseItemDtoQueryResult(items = emptyList(), totalRecordCount = 0, startIndex = 0)
        val repository =
            repository(
                api = jellyfinApi(episodesApi { _, _ -> response }),
                database = database(),
            )

        val episodes =
            repository.getEpisodes(
                seriesId = SERIES_ID,
                seasonId = SEASON_ID,
                fields = EPISODE_FIELDS,
                startItemId = null,
                limit = null,
                offline = false,
            )

        assertTrue(episodes.isEmpty())
    }

    @Test
    fun `online episodes sdk failure propagates to the caller continuation unchanged`() =
        runBlocking {
            val callerDispatcher =
                Executors.newSingleThreadExecutor { runnable ->
                        Thread(runnable, "caller-main-like")
                    }
                    .asCoroutineDispatcher()
            try {
                lateinit var callerThread: Thread
                lateinit var continuationThread: Thread
                val failure = IllegalStateException("episodes sdk failure")
                val response = mockk<Response<BaseItemDtoQueryResult>>(relaxed = true)
                every { response.content } throws failure
                val repository =
                    repository(
                        api = jellyfinApi(episodesApiReturning(response)),
                        database = database(),
                    )

                val caught =
                    withContext(callerDispatcher) {
                        callerThread = Thread.currentThread()
                        val result = runCatching {
                            repository.getEpisodes(
                                seriesId = SERIES_ID,
                                seasonId = SEASON_ID,
                                fields = EPISODE_FIELDS,
                                startItemId = null,
                                limit = null,
                                offline = false,
                            )
                        }
                            .exceptionOrNull()
                        continuationThread = Thread.currentThread()
                        result
                    }

                assertTrue(
                    "expected IllegalStateException, got ${caught?.javaClass?.name}",
                    caught is IllegalStateException,
                )
                assertEquals(failure.message, caught?.message)
                assertSame(failure, caught?.cause)
                assertSame(callerThread, continuationThread)
            } finally {
                callerDispatcher.close()
            }
        }

    private fun episodesApi(
        responseFor: suspend (Thread, Thread) -> Response<BaseItemDtoQueryResult>
    ): TvShowsApi {
        val showsApi = mockk<TvShowsApi>()
        coEvery {
            showsApi.getEpisodes(
                SERIES_ID,
                USER_ID,
                seasonId = SEASON_ID,
                fields = EPISODE_FIELDS,
                startItemId = null,
                limit = null,
            )
        } coAnswers
            {
                val callThread = Thread.currentThread()
                yield()
                val response = responseFor(callThread, Thread.currentThread())
                response.content
                response
            }
        return showsApi
    }

    private fun episodesApiReturning(response: Response<BaseItemDtoQueryResult>): TvShowsApi {
        val showsApi = mockk<TvShowsApi>()
        coEvery {
            showsApi.getEpisodes(
                SERIES_ID,
                USER_ID,
                seasonId = SEASON_ID,
                fields = EPISODE_FIELDS,
                startItemId = null,
                limit = null,
            )
        } returns response
        return showsApi
    }

    private fun jellyfinApi(showsApi: TvShowsApi): JellyfinApi =
        mockk<JellyfinApi>(relaxed = true).apply {
            every { this@apply.userId } returns USER_ID
            every { this@apply.showsApi } returns showsApi
        }

    private fun database(): ServerDatabaseDao =
        mockk<ServerDatabaseDao>(relaxed = true).apply {
            coEvery { getUserData(EPISODE_ID, USER_ID) } returns null
            coEvery { getSources(EPISODE_ID) } returns emptyList()
        }

    private fun repository(
        api: JellyfinApi,
        database: ServerDatabaseDao,
    ): JellyfinRepositoryImpl =
        JellyfinRepositoryImpl(
            context = mockk<Context>(relaxed = true),
            jellyfinApi = api,
            database = database,
            appPreferences = AppPreferences(mockk<SharedPreferences>(relaxed = true)),
            localLibrary = mockk<LocalLibraryRepository>(relaxed = true),
            metadataSnapshotStore = mockk(relaxed = true),
        )

    private fun episodeDto(): BaseItemDto =
        BaseItemDto(
            id = EPISODE_ID,
            name = "Episode 1",
            type = BaseItemKind.EPISODE,
            seriesId = SERIES_ID,
            seasonId = SEASON_ID,
            seriesName = "Sample Show",
            seasonName = "Season 1",
            indexNumber = 1,
            canDownload = true,
        )

    private companion object {
        val SERIES_ID: UUID = UUID.fromString("33333333-3333-4333-8333-333333333333")
        val SEASON_ID: UUID = UUID.fromString("44444444-4444-4444-8444-444444444444")
        val EPISODE_ID: UUID = UUID.fromString("55555555-5555-4555-8555-555555555555")
        val USER_ID: UUID = UUID.fromString("22222222-2222-4222-8222-222222222222")
        val EPISODE_FIELDS =
            listOf(ItemFields.OVERVIEW, ItemFields.CAN_DOWNLOAD, ItemFields.MEDIA_SOURCES)
    }
}
