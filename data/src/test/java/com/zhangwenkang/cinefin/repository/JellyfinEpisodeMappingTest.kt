package com.zhangwenkang.cinefin.repository

import android.content.Context
import android.content.SharedPreferences
import com.zhangwenkang.cinefin.api.JellyfinApi
import com.zhangwenkang.cinefin.database.ServerDatabaseDao
import com.zhangwenkang.cinefin.local.LocalLibraryRepository
import com.zhangwenkang.cinefin.settings.domain.AppPreferences
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.nio.file.Files
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
import org.jellyfin.sdk.model.api.MediaProtocol
import org.jellyfin.sdk.model.api.MediaSourceInfo
import org.jellyfin.sdk.model.api.MediaSourceType
import org.jellyfin.sdk.model.api.MediaStreamProtocol
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
        withSeededEpisodeSnapshot(EPISODE_FIELDS) { store, _, _ ->
            val response = episodeResponse(emptyList())
            val showsApi = episodesApi { _, _ -> response }
            val repository =
                repository(
                    api = jellyfinApi(showsApi),
                    database = database(),
                    metadataSnapshotStore = store,
                    appPreferences = testAppPreferences(SERVER_ID),
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
            coVerify(exactly = 1) {
                showsApi.getEpisodes(
                    SERIES_ID,
                    USER_ID,
                    seasonId = SEASON_ID,
                    fields = EPISODE_FIELDS,
                    startItemId = null,
                    limit = null,
                )
            }
        }
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

    @Test
    fun `source dependent episode read bypasses a fresh source stripped snapshot`() = runBlocking {
        withSeededEpisodeSnapshot(EPISODE_FIELDS) { store, fence, key ->
            val snapshotBefore =
                store.read(fence, key, maxAgeMs = Long.MAX_VALUE) as MetadataSnapshotReadResult.Hit
            val liveDtos = episodeDtos(count = 12, withSources = true)
            val showsApi = episodesApi { _, _ -> episodeResponse(liveDtos) }
            val repository =
                repository(
                    api = jellyfinApi(showsApi),
                    database = database(),
                    metadataSnapshotStore = store,
                    appPreferences = testAppPreferences(SERVER_ID),
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

            assertEquals(12, episodes.size)
            assertEquals(12, episodes.sumOf { it.sources.size })
            assertTrue(episodes.all { it.sources.isNotEmpty() })
            coVerify(exactly = 1) {
                showsApi.getEpisodes(
                    SERIES_ID,
                    USER_ID,
                    seasonId = SEASON_ID,
                    fields = EPISODE_FIELDS,
                    startItemId = null,
                    limit = null,
                )
            }

            val snapshotAfter =
                store.read(fence, key, maxAgeMs = Long.MAX_VALUE) as MetadataSnapshotReadResult.Hit
            assertEquals(snapshotBefore.savedAtEpochMs, snapshotAfter.savedAtEpochMs)
            val storedDtos = snapshotAfter.value as List<*>
            assertEquals(12, storedDtos.size)
            assertTrue(storedDtos.all { dto -> (dto as BaseItemDto).mediaSources.isNullOrEmpty() })
        }
    }

    @Test
    fun `metadata only episode read still uses the source stripped snapshot`() = runBlocking {
        withSeededEpisodeSnapshot(METADATA_FIELDS) { store, _, _ ->
            val showsApi = mockk<TvShowsApi>(relaxed = true)
            val repository =
                repository(
                    api = jellyfinApi(showsApi),
                    database = database(),
                    metadataSnapshotStore = store,
                    appPreferences = testAppPreferences(SERVER_ID),
                )

            val episodes =
                repository.getEpisodes(
                    seriesId = SERIES_ID,
                    seasonId = SEASON_ID,
                    fields = METADATA_FIELDS,
                    startItemId = null,
                    limit = null,
                    offline = false,
                )

            assertEquals(12, episodes.size)
            assertTrue(episodes.all { it.sources.isEmpty() })
            coVerify(exactly = 0) {
                showsApi.getEpisodes(
                    SERIES_ID,
                    USER_ID,
                    seasonId = SEASON_ID,
                    fields = METADATA_FIELDS,
                    startItemId = null,
                    limit = null,
                )
            }
        }
    }

    @Test
    fun `source dependent episode failure does not fall back to a stripped snapshot`() =
        runBlocking {
            withSeededEpisodeSnapshot(EPISODE_FIELDS) { store, _, _ ->
                val failure = IllegalStateException("live episode source failure")
                val response = mockk<Response<BaseItemDtoQueryResult>>(relaxed = true)
                every { response.content } throws failure
                val repository =
                    repository(
                        api = jellyfinApi(episodesApiReturning(response)),
                        database = database(),
                        metadataSnapshotStore = store,
                        appPreferences = testAppPreferences(SERVER_ID),
                    )

                val caught = runCatching {
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

                assertTrue(
                    "expected IllegalStateException, got ${caught?.javaClass?.name}",
                    caught is IllegalStateException,
                )
                assertEquals(failure.message, caught?.message)
                assertSame(failure, caught?.cause)
            }
        }

    @Test
    fun `offline episode read stays on the room branch with a stripped snapshot present`() =
        runBlocking {
            withSeededEpisodeSnapshot(EPISODE_FIELDS) { store, _, _ ->
                val showsApi = mockk<TvShowsApi>(relaxed = true)
                val database = database()
                coEvery { database.getEpisodesBySeasonId(SEASON_ID) } returns emptyList()
                val repository =
                    repository(
                        api = jellyfinApi(showsApi),
                        database = database,
                        metadataSnapshotStore = store,
                        appPreferences = testAppPreferences(SERVER_ID),
                    )

                val episodes =
                    repository.getEpisodes(
                        seriesId = SERIES_ID,
                        seasonId = SEASON_ID,
                        fields = EPISODE_FIELDS,
                        startItemId = null,
                        limit = null,
                        offline = true,
                    )

                assertTrue(episodes.isEmpty())
                coVerify(exactly = 0) {
                    showsApi.getEpisodes(
                        SERIES_ID,
                        USER_ID,
                        seasonId = SEASON_ID,
                        fields = EPISODE_FIELDS,
                        startItemId = null,
                        limit = null,
                    )
                }
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
        metadataSnapshotStore: MetadataSnapshotStore = mockk(relaxed = true),
        appPreferences: AppPreferences = testAppPreferences(),
    ): JellyfinRepositoryImpl =
        JellyfinRepositoryImpl(
            context = mockk<Context>(relaxed = true),
            jellyfinApi = api,
            database = database,
            appPreferences = appPreferences,
            localLibrary = mockk<LocalLibraryRepository>(relaxed = true),
            metadataSnapshotStore = metadataSnapshotStore,
        )

    private fun testAppPreferences(serverId: String? = null): AppPreferences {
        val sharedPreferences = mockk<SharedPreferences>(relaxed = true)
        every { sharedPreferences.getString("pref_current_server", null) } returns serverId
        return AppPreferences(sharedPreferences)
    }

    private suspend fun withSeededEpisodeSnapshot(
        fields: List<ItemFields>,
        count: Int = 12,
        block: suspend (MetadataSnapshotStore, MetadataFence, String) -> Unit,
    ) {
        val directory = Files.createTempDirectory("episode-source-cache-test").toFile()
        try {
            val store = MetadataSnapshotStore(directory)
            val fence =
                MetadataFence(
                    namespace = MetadataNamespace(SERVER_ID, USER_ID.toString()),
                    contentGeneration = 0L,
                )
            val key = episodeCacheKey(fields)
            assertTrue(
                store.write(fence, key, episodeDtos(count)) is MetadataSnapshotWriteResult.Success
            )
            block(store, fence, key)
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun episodeCacheKey(fields: List<ItemFields>): String =
        MetadataCacheKeys.episodes(
            seriesId = SERIES_ID,
            seasonId = SEASON_ID,
            fields = fields,
            startItemId = null,
            limit = null,
            offline = false,
        )

    private fun episodeResponse(dtos: List<BaseItemDto>): Response<BaseItemDtoQueryResult> =
        mockk<Response<BaseItemDtoQueryResult>>(relaxed = true).apply {
            every { content } returns
                BaseItemDtoQueryResult(
                    items = dtos,
                    totalRecordCount = dtos.size,
                    startIndex = 0,
                )
        }

    private fun episodeDtos(count: Int, withSources: Boolean = false): List<BaseItemDto> =
        List(count) { index -> episodeDto(index = index, withSource = withSources) }

    private fun episodeDto(index: Int = 0, withSource: Boolean = false): BaseItemDto =
        BaseItemDto(
            id =
                if (index == 0) {
                    EPISODE_ID
                } else {
                    UUID(0x5555555555554555L, (index + 1).toLong())
                },
            name = "Episode ${index + 1}",
            type = BaseItemKind.EPISODE,
            seriesId = SERIES_ID,
            seasonId = SEASON_ID,
            seriesName = "Sample Show",
            seasonName = "Season 1",
            indexNumber = index + 1,
            canDownload = true,
            mediaSources = if (withSource) listOf(mediaSource(index)) else null,
        )

    private fun mediaSource(index: Int): MediaSourceInfo =
        MediaSourceInfo(
            protocol = MediaProtocol.FILE,
            id = "source-${index + 1}",
            path = "/media/episode-${index + 1}.mkv",
            type = MediaSourceType.DEFAULT,
            container = "mkv",
            size = 12_345L,
            name = "episode-${index + 1}.mkv",
            isRemote = false,
            readAtNativeFramerate = false,
            ignoreDts = false,
            ignoreIndex = false,
            genPtsInput = false,
            supportsTranscoding = true,
            supportsDirectStream = true,
            supportsDirectPlay = true,
            isInfiniteStream = false,
            requiresOpening = false,
            requiresClosing = false,
            requiresLooping = false,
            supportsProbing = true,
            bitrate = 8_000_000,
            transcodingUrl = null,
            transcodingSubProtocol = MediaStreamProtocol.HLS,
            hasSegments = false,
        )

    private companion object {
        const val SERVER_ID = "server-episodes-source-cache"
        val SERIES_ID: UUID = UUID.fromString("33333333-3333-4333-8333-333333333333")
        val SEASON_ID: UUID = UUID.fromString("44444444-4444-4444-8444-444444444444")
        val EPISODE_ID: UUID = UUID.fromString("55555555-5555-4555-8555-555555555555")
        val USER_ID: UUID = UUID.fromString("22222222-2222-4222-8222-222222222222")
        val EPISODE_FIELDS =
            listOf(ItemFields.OVERVIEW, ItemFields.CAN_DOWNLOAD, ItemFields.MEDIA_SOURCES)
        val METADATA_FIELDS = listOf(ItemFields.OVERVIEW)
    }
}
