@file:Suppress("UNCHECKED_CAST")

package com.zhangwenkang.cinefin.presentation.selection

import com.zhangwenkang.cinefin.film.presentation.detail.DetailDownloadRules
import com.zhangwenkang.cinefin.models.FindroidEpisode
import com.zhangwenkang.cinefin.models.FindroidImages
import com.zhangwenkang.cinefin.models.FindroidItem
import com.zhangwenkang.cinefin.models.FindroidSeason
import com.zhangwenkang.cinefin.models.FindroidShow
import com.zhangwenkang.cinefin.models.FindroidSource
import com.zhangwenkang.cinefin.models.FindroidSourceType
import com.zhangwenkang.cinefin.repository.JellyfinRepository
import com.zhangwenkang.cinefin.repository.ReaderRepository
import com.zhangwenkang.cinefin.utils.Downloader
import java.util.UUID
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MediaBatchViewModelDownloadSelectionTest {
    private val mainDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `season container cannot enqueue children without an explicit episode manifest`() =
        runTest(mainDispatcher.scheduler) {
            val seriesId = UUID.fromString("00000000-0000-0000-0000-000000000100")
            val seasonId = UUID.fromString("00000000-0000-0000-0000-000000000200")
            val episodes = (1..6).map { episode(seriesId, seasonId, it) }
            val season = season(seriesId, seasonId)
            val repository = repository(episodes)
            val downloader = downloader()
            val viewModel =
                MediaBatchViewModel(
                    repositoryProvider = { repository },
                    downloader = downloader,
                    readerRepository = readerRepository(),
                )
            viewModel.downloadSelected(listOf(season))
            advanceUntilIdle()

            assertTrue(downloader.enqueuedIds.isEmpty())
        }

    @Test
    fun `confirmed episode ids are the only downloaded item ids`() =
        runTest(mainDispatcher.scheduler) {
            val seriesId = UUID.fromString("00000000-0000-0000-0000-000000000110")
            val seasonId = UUID.fromString("00000000-0000-0000-0000-000000000210")
            val episodes = (1..6).map { episode(seriesId, seasonId, it) }
            val season = season(seriesId, seasonId)
            val repository = repository(episodes)
            val downloader = downloader()
            val viewModel =
                MediaBatchViewModel(
                    repositoryProvider = { repository },
                    downloader = downloader,
                    readerRepository = readerRepository(),
                )
            viewModel.prepareDownloadSelection(
                requestedSelectionIds = setOf(season.id.toString()),
                sourceItems = listOf(season),
            )
            advanceUntilIdle()
            val preparation = viewModel.downloadPreparation.value!!

            assertTrue(preparation.isReady)
            assertEquals(episodes, preparation.candidates)
            assertTrue(downloader.enqueuedIds.isEmpty())

            viewModel.downloadPreparedSelection(
                requestId = preparation.requestId,
                confirmedIds = setOf(episodes[0].id, episodes[2].id),
            )
            advanceUntilIdle()

            assertEquals(listOf(episodes[0].id, episodes[2].id), downloader.enqueuedIds)
            val finished = viewModel.events.first() as MediaBatchEvent.DownloadSelectionFinished
            assertEquals(
                listOf(episodes[0].id, episodes[2].id),
                finished.manifest.requestedIds,
            )
            assertEquals(listOf(episodes[0].id, episodes[2].id), finished.manifest.plannedIds)
            assertEquals(listOf(episodes[0].id, episodes[2].id), finished.acceptedIds)
            assertNull(viewModel.downloadPreparation.value)
        }

    @Test
    fun `show expansion binds origin scope and deduplicates overlapping season candidates`() =
        runTest(mainDispatcher.scheduler) {
            val seriesId = UUID.fromString("00000000-0000-0000-0000-000000000120")
            val seasonId = UUID.fromString("00000000-0000-0000-0000-000000000220")
            val userId = UUID.fromString("00000000-0000-0000-0000-000000000320")
            val episodes = (1..6).map { episode(seriesId, seasonId, it) }
            val season = season(seriesId, seasonId)
            val show = show(seriesId)
            val repository =
                repository(
                    episodes = episodes,
                    seasons = listOf(season),
                    userId = { userId },
                )
            val viewModel =
                MediaBatchViewModel(
                    repositoryProvider = { repository },
                    downloader = downloader(),
                    readerRepository = readerRepository(),
                )

            viewModel.prepareDownloadSelection(
                requestedSelectionIds = setOf(show.id.toString(), season.id.toString()),
                sourceItems = listOf(show, season),
            )
            advanceUntilIdle()
            val preparation = viewModel.downloadPreparation.value!!

            assertEquals("https://server.invalid|$userId", preparation.scopeKey)
            assertEquals(episodes, preparation.candidates)
            assertEquals(episodes.size, preparation.deduplicatedCandidateCount)
        }

    @Test
    fun `already downloaded confirmed episodes are reported as skipped`() =
        runTest(mainDispatcher.scheduler) {
            val seriesId = UUID.fromString("00000000-0000-0000-0000-000000000125")
            val seasonId = UUID.fromString("00000000-0000-0000-0000-000000000225")
            val episodes = (1..3).map { episode(seriesId, seasonId, it) }
            val season = season(seriesId, seasonId)
            val downloader = downloader(downloadedIds = setOf(episodes[1].id))
            val repository = repository(episodes)
            val viewModel =
                MediaBatchViewModel(
                    repositoryProvider = { repository },
                    downloader = downloader,
                    readerRepository = readerRepository(),
                )
            viewModel.prepareDownloadSelection(
                requestedSelectionIds = setOf(season.id.toString()),
                sourceItems = listOf(season),
            )
            advanceUntilIdle()
            val preparation = viewModel.downloadPreparation.value!!

            viewModel.downloadPreparedSelection(
                requestId = preparation.requestId,
                confirmedIds = episodes.map { it.id }.toSet(),
            )
            advanceUntilIdle()

            assertEquals(listOf(episodes[0].id, episodes[2].id), downloader.enqueuedIds)
            val finished = viewModel.events.first() as MediaBatchEvent.DownloadSelectionFinished
            assertEquals(episodes.map { it.id }, finished.manifest.plannedIds)
            assertEquals(listOf(episodes[0].id, episodes[2].id), finished.acceptedIds)
            assertEquals(listOf(episodes[1].id), finished.skippedIds)
            assertTrue(
                mediaDownloadExecutionIds(
                        plannedIds = finished.manifest.plannedIds,
                        acceptedIds = finished.acceptedIds,
                        skippedIds = finished.skippedIds,
                        failedIds = finished.failedIds,
                    )
                    .isComplete
            )
        }

    @Test
    fun `scope drift before confirmation blocks every download`() =
        runTest(mainDispatcher.scheduler) {
            val seriesId = UUID.fromString("00000000-0000-0000-0000-000000000130")
            val seasonId = UUID.fromString("00000000-0000-0000-0000-000000000230")
            val firstUser = UUID.fromString("00000000-0000-0000-0000-000000000330")
            val secondUser = UUID.fromString("00000000-0000-0000-0000-000000000331")
            var currentUser = firstUser
            val episodes = (1..6).map { episode(seriesId, seasonId, it) }
            val season = season(seriesId, seasonId)
            val downloader = downloader()
            val repository =
                repository(
                    episodes = episodes,
                    userId = { currentUser },
                )
            val viewModel =
                MediaBatchViewModel(
                    repositoryProvider = { repository },
                    downloader = downloader,
                    readerRepository = readerRepository(),
                )

            viewModel.prepareDownloadSelection(
                requestedSelectionIds = setOf(season.id.toString()),
                sourceItems = listOf(season),
            )
            advanceUntilIdle()
            val preparation = viewModel.downloadPreparation.value!!
            currentUser = secondUser

            viewModel.downloadPreparedSelection(
                requestId = preparation.requestId,
                confirmedIds = setOf(episodes[0].id),
            )
            advanceUntilIdle()

            assertTrue(downloader.enqueuedIds.isEmpty())
            assertNull(viewModel.downloadPreparation.value)
        }

    @Test
    fun `unknown confirmed episode id blocks the entire manifest`() =
        runTest(mainDispatcher.scheduler) {
            val seriesId = UUID.fromString("00000000-0000-0000-0000-000000000150")
            val seasonId = UUID.fromString("00000000-0000-0000-0000-000000000250")
            val episodes = (1..6).map { episode(seriesId, seasonId, it) }
            val season = season(seriesId, seasonId)
            val downloader = downloader()
            val repository = repository(episodes)
            val viewModel =
                MediaBatchViewModel(
                    repositoryProvider = { repository },
                    downloader = downloader,
                    readerRepository = readerRepository(),
                )

            viewModel.prepareDownloadSelection(
                requestedSelectionIds = setOf(season.id.toString()),
                sourceItems = listOf(season),
            )
            advanceUntilIdle()
            val preparation = viewModel.downloadPreparation.value!!

            viewModel.downloadPreparedSelection(
                requestId = preparation.requestId,
                confirmedIds =
                    setOf(
                        episodes[0].id,
                        UUID.fromString("00000000-0000-0000-0000-000000000999"),
                    ),
            )
            advanceUntilIdle()

            assertTrue(downloader.enqueuedIds.isEmpty())
            assertNull(viewModel.downloadPreparation.value)
        }

    @Test
    fun `episode load failure keeps prepared candidates empty and creates no task`() =
        runTest(mainDispatcher.scheduler) {
            val seriesId = UUID.fromString("00000000-0000-0000-0000-000000000140")
            val seasonId = UUID.fromString("00000000-0000-0000-0000-000000000240")
            val season = season(seriesId, seasonId)
            val downloader = downloader()
            val repository = repository(episodes = emptyList(), failLoad = true)
            val viewModel =
                MediaBatchViewModel(
                    repositoryProvider = { repository },
                    downloader = downloader,
                    readerRepository = readerRepository(),
                )

            viewModel.prepareDownloadSelection(
                requestedSelectionIds = setOf(season.id.toString()),
                sourceItems = listOf(season),
            )
            advanceUntilIdle()

            val preparation = viewModel.downloadPreparation.value!!
            assertTrue(preparation.candidates.isEmpty())
            assertEquals(1, preparation.loadFailureCount)
            assertTrue(downloader.enqueuedIds.isEmpty())
        }

    private fun repository(
        episodes: List<FindroidEpisode>,
        seasons: List<FindroidSeason> = emptyList(),
        failLoad: Boolean = false,
        userId: () -> UUID = { UUID.fromString("00000000-0000-0000-0000-000000000300") },
    ): JellyfinRepository =
        proxy(JellyfinRepository::class.java) { method, args ->
            when (method.name) {
                "getEpisodes" -> {
                    val callArgs = args!!
                    assertEquals(DetailDownloadRules.EPISODE_FETCH_FIELDS, callArgs[2])
                    val continuation = callArgs.last() as Continuation<List<FindroidEpisode>>
                    if (failLoad) {
                        continuation.resumeWith(
                            Result.failure(IllegalStateException("episode load failed"))
                        )
                        COROUTINE_SUSPENDED
                    } else {
                        resume(continuation, episodes)
                    }
                }
                "getSeasons" ->
                    resume(
                        args!!.last() as Continuation<List<FindroidSeason>>,
                        seasons,
                    )
                "getBaseUrl" -> "https://server.invalid"
                "getUserId" -> userId()
                "toString" -> "DownloadSelectionTestRepository"
                "hashCode" -> System.identityHashCode(episodes)
                "equals" -> false
                else -> throw AssertionError("Unexpected repository call: ${method.name}")
            }
        }

    private fun downloader(
        downloadedIds: Set<UUID> = emptySet(),
        queuedIds: Set<UUID> = emptySet(),
    ): RecordingDownloader {
        val enqueuedIds = mutableListOf<UUID>()
        val delegate =
            proxy(Downloader::class.java) { method, args ->
                when (method.name) {
                    "downloadItem" -> {
                        val callArgs = args!!
                        val item = callArgs[0] as FindroidItem
                        enqueuedIds += item.id
                        resume(callArgs.last() as Continuation<Any?>, 1L to null)
                    }
                    "downloadedItemIds" ->
                        resume(
                            args!!.last() as Continuation<Set<UUID>>,
                            downloadedIds,
                        )
                    "activeItemIds" ->
                        resume(
                            args!!.last() as Continuation<Set<UUID>>,
                            queuedIds,
                        )
                    "toString" -> "RecordingDownloader"
                    "hashCode" -> System.identityHashCode(args)
                    "equals" -> false
                    else -> throw AssertionError("Unexpected downloader call: ${method.name}")
                }
            }
        return RecordingDownloader(delegate, enqueuedIds)
    }

    private fun readerRepository(): ReaderRepository =
        proxy(ReaderRepository::class.java) { method, args ->
            when (method.name) {
                "listLocalFiles" ->
                    resume(
                        args!!.last()
                            as
                            Continuation<List<com.zhangwenkang.cinefin.repository.LocalBookFile>>,
                        emptyList(),
                    )
                "toString" -> "DownloadSelectionTestReaderRepository"
                "hashCode" -> System.identityHashCode(args)
                "equals" -> false
                else -> throw AssertionError("Unexpected reader repository call: ${method.name}")
            }
        }

    private fun <T> proxy(
        type: Class<T>,
        handler: (java.lang.reflect.Method, Array<Any?>?) -> Any?,
    ): T =
        java.lang.reflect.Proxy.newProxyInstance(
            type.classLoader,
            arrayOf(type),
        ) { _, method, args ->
            handler(method, args)
        } as T

    private fun <T> resume(continuation: Continuation<T>, value: T): Any {
        continuation.resumeWith(Result.success(value))
        return COROUTINE_SUSPENDED
    }

    private fun season(seriesId: UUID, seasonId: UUID) =
        FindroidSeason(
            id = seasonId,
            name = "第 1 季",
            seriesId = seriesId,
            seriesName = "测试剧",
            originalTitle = null,
            overview = "",
            sources = emptyList(),
            indexNumber = 1,
            episodes = emptyList(),
            played = false,
            favorite = false,
            canPlay = true,
            canDownload = true,
            unplayedItemCount = 6,
            images = FindroidImages(),
        )

    private fun show(seriesId: UUID) =
        FindroidShow(
            id = seriesId,
            name = "测试剧",
            originalTitle = null,
            overview = "",
            sources = emptyList(),
            seasons = emptyList(),
            played = false,
            favorite = false,
            canPlay = true,
            canDownload = true,
            unplayedItemCount = 6,
            genres = emptyList(),
            people = emptyList(),
            runtimeTicks = 0L,
            communityRating = null,
            officialRating = null,
            status = "Ended",
            productionYear = null,
            endDate = null,
            trailer = null,
            images = FindroidImages(),
        )

    private fun episode(seriesId: UUID, seasonId: UUID, index: Int) =
        FindroidEpisode(
            id = UUID.fromString("00000000-0000-0000-0000-00000000030$index"),
            name = "第 $index 集",
            originalTitle = null,
            overview = "",
            indexNumber = index,
            indexNumberEnd = null,
            parentIndexNumber = 1,
            sources = listOf(source(index)),
            played = false,
            favorite = false,
            canPlay = true,
            canDownload = true,
            runtimeTicks = 0L,
            playbackPositionTicks = 0L,
            premiereDate = null,
            seriesId = seriesId,
            seriesName = "测试剧",
            seasonId = seasonId,
            seasonName = "第 1 季",
            communityRating = null,
            people = emptyList(),
            images = FindroidImages(),
            chapters = emptyList(),
            trickplayInfo = null,
        )

    private fun source(index: Int) =
        FindroidSource(
            id = "source-$index",
            name = "source-$index",
            type = FindroidSourceType.REMOTE,
            path = "https://example.invalid/$index",
            size = 1L,
            mediaStreams = emptyList(),
        )
}

private class RecordingDownloader(
    private val delegate: Downloader,
    val enqueuedIds: MutableList<UUID>,
) : Downloader by delegate
