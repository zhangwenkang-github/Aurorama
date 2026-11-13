package com.zhangwenkang.cinefin.presentation.film

import com.zhangwenkang.cinefin.film.presentation.show.ShowViewModel
import com.zhangwenkang.cinefin.models.FindroidEpisode
import com.zhangwenkang.cinefin.models.FindroidImages
import com.zhangwenkang.cinefin.models.FindroidSeason
import com.zhangwenkang.cinefin.models.FindroidShow
import com.zhangwenkang.cinefin.repository.JellyfinRepository
import java.io.IOException
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
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ShowViewModelRestoreTest {
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
    fun olderResumedRefreshSuccessCannotOverwriteNewestContent() =
        runTest(mainDispatcher.scheduler) {
            val olderRefreshGate = CompletableDeferred<Unit>()
            val showId = UUID.randomUUID()
            var getShowCalls = 0
            val repository =
                controlledRepository(
                    scope = backgroundScope,
                    loadShow = {
                        when (++getShowCalls) {
                            1 -> show(showId, "initial")
                            2 -> {
                                olderRefreshGate.await()
                                show(showId, "older-refresh")
                            }
                            else -> show(showId, "newest-refresh")
                        }
                    },
                )
            val viewModel = ShowViewModel(repository)

            viewModel.loadShow(showId)
            runCurrent()
            awaitShowName(viewModel, "initial")

            viewModel.loadShow(showId)
            runCurrent()
            assertEquals("initial", viewModel.state.value.show?.name)

            viewModel.loadShow(showId)
            runCurrent()
            awaitShowName(viewModel, "newest-refresh")

            olderRefreshGate.complete(Unit)
            runCurrent()
            settleDefaultDispatcher()

            assertEquals("newest-refresh", viewModel.state.value.show?.name)
            assertNull(viewModel.state.value.error)
        }

    @Test
    fun olderResumedRefreshFailureCannotReplaceNewestContentWithError() =
        runTest(mainDispatcher.scheduler) {
            val olderRefreshGate = CompletableDeferred<Unit>()
            val showId = UUID.randomUUID()
            var getShowCalls = 0
            val repository =
                controlledRepository(
                    scope = backgroundScope,
                    loadShow = {
                        when (++getShowCalls) {
                            1 -> show(showId, "initial")
                            2 -> {
                                olderRefreshGate.await()
                                throw IOException("older refresh failed")
                            }
                            else -> show(showId, "newest-refresh")
                        }
                    },
                )
            val viewModel = ShowViewModel(repository)

            viewModel.loadShow(showId)
            runCurrent()
            awaitShowName(viewModel, "initial")
            viewModel.loadShow(showId)
            runCurrent()
            viewModel.loadShow(showId)
            runCurrent()
            awaitShowName(viewModel, "newest-refresh")

            olderRefreshGate.complete(Unit)
            runCurrent()
            settleDefaultDispatcher()

            assertEquals("newest-refresh", viewModel.state.value.show?.name)
            assertNull(viewModel.state.value.error)
        }

    @Test
    fun coreShowIsPublishedBeforeOptionalEnrichmentCompletes() =
        runTest(mainDispatcher.scheduler) {
            val nextUpGate = CompletableDeferred<Unit>()
            val showId = UUID.randomUUID()
            val repository =
                controlledRepository(
                    scope = backgroundScope,
                    loadShow = { show(showId, "core") },
                    loadNextUp = {
                        nextUpGate.await()
                        emptyList()
                    },
                )
            val viewModel = ShowViewModel(repository)

            viewModel.loadShow(showId)
            runCurrent()

            assertEquals("core", viewModel.state.value.show?.name)

            nextUpGate.complete(Unit)
            runCurrent()
            settleDefaultDispatcher()
            runCurrent()
            settleDefaultDispatcher()
            runCurrent()
        }

    @Test
    fun optionalShowEnrichmentFailureKeepsCoreShowVisible() =
        runTest(mainDispatcher.scheduler) {
            val showId = UUID.randomUUID()
            val repository =
                controlledRepository(
                    scope = backgroundScope,
                    loadShow = { show(showId, "core") },
                    loadNextUp = { throw IOException("next up failed") },
                )
            val viewModel = ShowViewModel(repository)

            viewModel.loadShow(showId)
            runCurrent()
            settleDefaultDispatcher()
            runCurrent()

            assertEquals("core", viewModel.state.value.show?.name)
            assertEquals("next up failed", viewModel.state.value.error?.message)
        }

    private suspend fun awaitShowName(viewModel: ShowViewModel, expected: String) {
        withContext(Dispatchers.Default) {
            withTimeout(5_000) {
                while (viewModel.state.value.show?.name != expected) {
                    delay(10)
                }
            }
        }
    }

    private suspend fun settleDefaultDispatcher() {
        withContext(Dispatchers.Default) { delay(100) }
    }

    private fun controlledRepository(
        scope: CoroutineScope,
        loadShow: suspend () -> FindroidShow,
        loadNextUp: suspend () -> List<FindroidEpisode> = { emptyList() },
        loadSeasons: suspend () -> List<FindroidSeason> = { emptyList() },
    ): JellyfinRepository =
        Proxy.newProxyInstance(
            JellyfinRepository::class.java.classLoader,
            arrayOf(JellyfinRepository::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getShow" -> {
                    @Suppress("UNCHECKED_CAST")
                    val continuation = args!!.last() as Continuation<FindroidShow>
                    scope.launch { continuation.resumeWith(runCatching { loadShow() }) }
                    COROUTINE_SUSPENDED
                }
                "getNextUp" -> {
                    @Suppress("UNCHECKED_CAST")
                    val continuation = args!!.last() as Continuation<List<FindroidEpisode>>
                    scope.launch { continuation.resumeWith(runCatching { loadNextUp() }) }
                    COROUTINE_SUSPENDED
                }
                "getSeasons" -> {
                    @Suppress("UNCHECKED_CAST")
                    val continuation = args!!.last() as Continuation<List<FindroidSeason>>
                    scope.launch { continuation.resumeWith(runCatching { loadSeasons() }) }
                    COROUTINE_SUSPENDED
                }
                "toString" -> "ShowViewModelRestoreTestRepository"
                "hashCode" -> System.identityHashCode(this)
                "equals" -> false
                else -> throw AssertionError("Unexpected repository call: ${method.name}")
            }
        } as JellyfinRepository

    private fun show(id: UUID, name: String): FindroidShow =
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
}
