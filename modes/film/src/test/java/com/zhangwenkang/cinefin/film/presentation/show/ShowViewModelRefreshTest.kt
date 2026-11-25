package com.zhangwenkang.cinefin.film.presentation.show

import com.zhangwenkang.cinefin.models.FindroidEpisode
import com.zhangwenkang.cinefin.models.FindroidImages
import com.zhangwenkang.cinefin.models.FindroidItemPerson
import com.zhangwenkang.cinefin.models.FindroidItemPersonImage
import com.zhangwenkang.cinefin.models.FindroidSeason
import com.zhangwenkang.cinefin.models.FindroidShow
import com.zhangwenkang.cinefin.repository.JellyfinRepository
import java.lang.reflect.Proxy
import java.util.UUID
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.jellyfin.sdk.model.api.PersonKind
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** W1-T05（#16）：节目页静默刷新——同剧保留旧明细、换剧清旧数据。 */
@OptIn(ExperimentalCoroutinesApi::class)
class ShowViewModelRefreshTest {
    private lateinit var mainDispatcher: TestDispatcher

    @Before
    fun setUp() {
        mainDispatcher = StandardTestDispatcher()
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun sameShowRefreshKeepsSeasonsAndActorsUntilNewDataArrives() =
        runTest(mainDispatcher.scheduler) {
            val showId = UUID.randomUUID()
            val reloadGate = CompletableDeferred<Unit>()
            var showCalls = 0
            var seasonsCalls = 0
            val repository =
                controlledRepository(
                    scope = backgroundScope,
                    loadShow = {
                        when (++showCalls) {
                            1 -> show(showId, "老友记", people = listOf(actor("演员甲")))
                            else -> {
                                reloadGate.await()
                                show(
                                    showId,
                                    "老友记",
                                    people = listOf(actor("演员甲"), actor("演员乙")),
                                )
                            }
                        }
                    },
                    loadSeasons = {
                        when (++seasonsCalls) {
                            1 -> listOf(season(showId, 1))
                            else -> listOf(season(showId, 1), season(showId, 2))
                        }
                    },
                    loadNextUp = { listOf(episode(showId, "S1E1")) },
                )
            val viewModel = ShowViewModel(repository)

            viewModel.loadShow(showId)
            runCurrent()
            awaitState(viewModel) { state ->
                state.seasons.size == 1 && state.actors.size == 1 && state.nextUp != null
            }
            assertNull(viewModel.state.value.error)

            val emissions = mutableListOf<ShowState>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                viewModel.state.collect { emissions += it }
            }
            runCurrent()

            viewModel.loadShow(showId)
            runCurrent()

            // 新数据未到位：已展示的季 / 演员 / 下一集必须还在，不能先清空。
            assertEquals(1, viewModel.state.value.seasons.size)
            assertEquals(1, viewModel.state.value.actors.size)
            assertEquals("S1E1", viewModel.state.value.nextUp?.name)

            reloadGate.complete(Unit)
            runCurrent()
            awaitState(viewModel) { state -> state.seasons.size == 2 }

            assertEquals(2, viewModel.state.value.actors.size)
            assertEquals("S1E1", viewModel.state.value.nextUp?.name)
            assertEquals("老友记", viewModel.state.value.show?.name)
            assertTrue(
                "静默刷新期间不应外发清空季 / 演员的中间态：$emissions",
                emissions.none { state -> state.seasons.isEmpty() || state.actors.isEmpty() },
            )
        }

    @Test
    fun loadingAnotherShowClearsPreviousShowDetail() =
        runTest(mainDispatcher.scheduler) {
            val firstShowId = UUID.randomUUID()
            val secondShowId = UUID.randomUUID()
            val secondShowGate = CompletableDeferred<Unit>()
            val secondSeasonsGate = CompletableDeferred<Unit>()
            val repository =
                controlledRepository(
                    scope = backgroundScope,
                    loadShow = { itemId ->
                        if (itemId == firstShowId) {
                            show(firstShowId, "老友记", people = listOf(actor("演员甲")))
                        } else {
                            secondShowGate.await()
                            show(secondShowId, "生活大爆炸", people = emptyList())
                        }
                    },
                    loadSeasons = { seriesId ->
                        if (seriesId == firstShowId) {
                            listOf(season(firstShowId, 1))
                        } else {
                            secondSeasonsGate.await()
                            listOf(season(secondShowId, 1))
                        }
                    },
                    loadNextUp = { seriesId -> listOf(episode(seriesId ?: firstShowId, "S1E1")) },
                )
            val viewModel = ShowViewModel(repository)

            viewModel.loadShow(firstShowId)
            runCurrent()
            awaitState(viewModel) { state -> state.seasons.size == 1 && state.nextUp != null }

            val emissions = mutableListOf<ShowState>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                viewModel.state.collect { emissions += it }
            }
            runCurrent()

            viewModel.loadShow(secondShowId)
            runCurrent()
            secondShowGate.complete(Unit)
            runCurrent()

            // 新剧核心数据到位的那一刻，旧剧的季 / 演员 / 下一集同时清掉（不串剧）。
            assertEquals(secondShowId, viewModel.state.value.show?.id)
            assertTrue(viewModel.state.value.seasons.isEmpty())
            assertTrue(viewModel.state.value.actors.isEmpty())
            assertNull(viewModel.state.value.nextUp)

            secondSeasonsGate.complete(Unit)
            runCurrent()
            awaitState(viewModel) { state -> state.seasons.size == 1 }

            assertEquals(secondShowId, viewModel.state.value.seasons.first().seriesId)
            assertTrue(
                "旧剧的季不应出现在新剧之下：$emissions",
                emissions.none { state ->
                    state.show?.id == secondShowId &&
                        state.seasons.any { season -> season.seriesId == firstShowId }
                },
            )
        }

    private suspend fun awaitState(viewModel: ShowViewModel, predicate: (ShowState) -> Boolean) {
        withContext(Dispatchers.Default) {
            withTimeout(5_000) {
                while (!predicate(viewModel.state.value)) {
                    delay(10)
                }
            }
        }
    }

    private fun controlledRepository(
        scope: CoroutineScope,
        loadShow: suspend (UUID) -> FindroidShow,
        loadSeasons: suspend (UUID) -> List<FindroidSeason>,
        loadNextUp: suspend (UUID?) -> List<FindroidEpisode>,
    ): JellyfinRepository =
        Proxy.newProxyInstance(
            JellyfinRepository::class.java.classLoader,
            arrayOf(JellyfinRepository::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getShow" -> {
                    @Suppress("UNCHECKED_CAST")
                    val continuation = args!!.last() as Continuation<FindroidShow>
                    val itemId = args.first() as UUID
                    scope.launch { continuation.resumeWith(runCatching { loadShow(itemId) }) }
                    COROUTINE_SUSPENDED
                }
                "getSeasons" -> {
                    @Suppress("UNCHECKED_CAST")
                    val continuation = args!!.last() as Continuation<List<FindroidSeason>>
                    val seriesId = args.first() as UUID
                    scope.launch { continuation.resumeWith(runCatching { loadSeasons(seriesId) }) }
                    COROUTINE_SUSPENDED
                }
                "getNextUp" -> {
                    @Suppress("UNCHECKED_CAST")
                    val continuation = args!!.last() as Continuation<List<FindroidEpisode>>
                    val seriesId = args.first() as UUID?
                    scope.launch { continuation.resumeWith(runCatching { loadNextUp(seriesId) }) }
                    COROUTINE_SUSPENDED
                }
                "toString" -> "ShowViewModelRefreshTestRepository"
                "hashCode" -> System.identityHashCode(this)
                "equals" -> false
                else -> throw AssertionError("Unexpected repository call: ${method.name}")
            }
        } as JellyfinRepository

    private fun show(
        id: UUID,
        name: String,
        people: List<FindroidItemPerson> = emptyList(),
    ): FindroidShow =
        FindroidShow(
            id = id,
            name = name,
            originalTitle = null,
            overview = "",
            sources = emptyList(),
            seasons = emptyList(),
            played = false,
            favorite = false,
            canPlay = true,
            canDownload = false,
            unplayedItemCount = null,
            genres = emptyList(),
            people = people,
            runtimeTicks = 0L,
            communityRating = null,
            officialRating = null,
            status = "Ended",
            productionYear = null,
            endDate = null,
            trailer = null,
            images = FindroidImages(),
        )

    private fun season(seriesId: UUID, indexNumber: Int): FindroidSeason =
        FindroidSeason(
            id = UUID.nameUUIDFromBytes("$seriesId-season-$indexNumber".toByteArray()),
            name = "第 $indexNumber 季",
            seriesId = seriesId,
            seriesName = "",
            originalTitle = null,
            overview = "",
            sources = emptyList(),
            indexNumber = indexNumber,
            episodes = emptyList(),
            played = false,
            favorite = false,
            canPlay = true,
            canDownload = false,
            unplayedItemCount = null,
            images = FindroidImages(),
        )

    private fun episode(seriesId: UUID, name: String): FindroidEpisode =
        FindroidEpisode(
            id = UUID.randomUUID(),
            name = name,
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
            seriesId = seriesId,
            seriesName = "",
            seasonId = UUID.randomUUID(),
            seasonName = null,
            communityRating = null,
            people = emptyList(),
            images = FindroidImages(),
            chapters = emptyList(),
            trickplayInfo = null,
        )

    private fun actor(name: String): FindroidItemPerson =
        FindroidItemPerson(
            id = UUID.nameUUIDFromBytes(name.toByteArray()),
            name = name,
            type = PersonKind.ACTOR,
            role = "",
            image = FindroidItemPersonImage(uri = null, blurHash = null),
        )
}
