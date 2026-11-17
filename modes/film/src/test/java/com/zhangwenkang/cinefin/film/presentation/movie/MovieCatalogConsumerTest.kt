package com.zhangwenkang.cinefin.film.presentation.movie

import android.content.SharedPreferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.zhangwenkang.cinefin.models.AudioChannel
import com.zhangwenkang.cinefin.models.AudioCodec
import com.zhangwenkang.cinefin.models.DisplayProfile
import com.zhangwenkang.cinefin.models.FindroidCollection
import com.zhangwenkang.cinefin.models.FindroidImages
import com.zhangwenkang.cinefin.models.FindroidItem
import com.zhangwenkang.cinefin.models.FindroidMovie
import com.zhangwenkang.cinefin.models.FindroidSource
import com.zhangwenkang.cinefin.models.FindroidSourceType
import com.zhangwenkang.cinefin.models.Resolution
import com.zhangwenkang.cinefin.models.VideoCodec
import com.zhangwenkang.cinefin.models.VideoMetadata
import com.zhangwenkang.cinefin.repository.JellyfinOrigin
import com.zhangwenkang.cinefin.repository.JellyfinRepository
import com.zhangwenkang.cinefin.repository.catalog.CatalogFreshness
import com.zhangwenkang.cinefin.repository.catalog.CatalogHomeData
import com.zhangwenkang.cinefin.repository.catalog.CatalogHomeQuery
import com.zhangwenkang.cinefin.repository.catalog.CatalogItemsPageQuery
import com.zhangwenkang.cinefin.repository.catalog.CatalogLibrariesQuery
import com.zhangwenkang.cinefin.repository.catalog.CatalogMovieQuery
import com.zhangwenkang.cinefin.repository.catalog.CatalogPrincipalContext
import com.zhangwenkang.cinefin.repository.catalog.CatalogReadOutcome
import com.zhangwenkang.cinefin.repository.catalog.CatalogReadPort
import com.zhangwenkang.cinefin.repository.catalog.CatalogScope
import com.zhangwenkang.cinefin.repository.catalog.CatalogSearchQuery
import com.zhangwenkang.cinefin.repository.catalog.CatalogSnapshot
import com.zhangwenkang.cinefin.settings.domain.AppPreferences
import java.io.IOException
import java.lang.reflect.Proxy
import java.util.UUID
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MovieCatalogConsumerTest {
    private var viewModelStore: ViewModelStore? = null

    @After
    fun tearDown() {
        viewModelStore?.clear()
    }

    @Test
    fun `movie snapshot is accepted only for the same request movie and frozen scope`() {
        val token = MovieCatalogRequestToken(serial = 4L, movieId = MOVIE_ID, scope = SCOPE)

        assertTrue(
            isMovieCatalogResultCurrent(
                request = token,
                currentSerial = 4L,
                currentMovieId = MOVIE_ID,
                frozenScope = SCOPE,
                currentScope = SCOPE,
                snapshotScope = SCOPE,
            )
        )
    }

    @Test
    fun `movie snapshot rejects older serial another movie and scope generation changes`() {
        val token = MovieCatalogRequestToken(serial = 4L, movieId = MOVIE_ID, scope = SCOPE)

        assertFalse(
            isMovieCatalogResultCurrent(
                request = token,
                currentSerial = 5L,
                currentMovieId = MOVIE_ID,
                frozenScope = SCOPE,
                currentScope = SCOPE,
                snapshotScope = SCOPE,
            )
        )
        assertFalse(
            isMovieCatalogResultCurrent(
                request = token,
                currentSerial = 4L,
                currentMovieId = OTHER_MOVIE_ID,
                frozenScope = SCOPE,
                currentScope = SCOPE,
                snapshotScope = SCOPE,
            )
        )
        val next = SCOPE.copy(identityGeneration = 2L)
        assertFalse(
            isMovieCatalogResultCurrent(
                request = token,
                currentSerial = 4L,
                currentMovieId = MOVIE_ID,
                frozenScope = SCOPE,
                currentScope = next,
                snapshotScope = next,
            )
        )
        assertFalse(
            isMovieCatalogResultCurrent(
                request = token,
                currentSerial = 4L,
                currentMovieId = MOVIE_ID,
                frozenScope = SCOPE,
                currentScope = SCOPE,
                snapshotScope = SCOPE.copy(identityGeneration = 2L),
            )
        )
    }

    @Test
    fun `catalog clear helper removes stale content before outcome publication`() {
        val previous =
            MovieState(
                movie = movie("old"),
                actors = emptyList(),
                director = null,
                writers = emptyList(),
                error = IllegalStateException("old"),
            )

        val cleared = previous.clearCatalog()

        assertNull(cleared.movie)
        assertNull(cleared.videoMetadata)
        assertTrue(cleared.actors.isEmpty())
        assertTrue(cleared.writers.isEmpty())
        assertNull(cleared.error)
    }

    @Test
    fun `loaded movie state retains display preference while replacing detail atomically`() {
        val state =
            MovieState(movie = movie("old"), displayExtraInfo = true)
                .withCatalogMovie(
                    movie = movie("new"),
                    videoMetadata = null,
                    actors = emptyList(),
                    director = null,
                    writers = emptyList(),
                )

        assertEquals("new", state.movie?.name)
        assertEquals(true, state.displayExtraInfo)
    }

    @Test
    fun `identity unavailable publishes retryable error and recovers on retry`() = runBlocking {
        val catalog = MovieCatalogPort(SCOPE)
        var requestCount = 0
        catalog.movieResult = { query ->
            requestCount += 1
            if (requestCount == 1) {
                CatalogReadOutcome.IdentityUnavailable
            } else {
                available(
                    scope = query.frozenScope,
                    movie = movie(name = "retry-ok", id = query.itemId),
                )
            }
        }
        val viewModel =
            installViewModel(
                MovieViewModel(
                        catalogReadPort = catalog,
                        repository = repository(),
                        appPreferences = appPreferences(),
                        parseVideoMetadata = { metadata() },
                        peopleDispatcher = Dispatchers.Unconfined,
                    )
                    .withUnconfinedViewModelScope()
            )

        viewModel.loadMovie(MOVIE_ID)

        assertNull(viewModel.state.value.movie)
        assertNotNull(viewModel.state.value.error)

        viewModel.loadMovie(MOVIE_ID)

        assertEquals("retry-ok", viewModel.state.value.movie?.name)
        assertNull(viewModel.state.value.error)
    }

    @Test
    fun `metadata enrichment failure still publishes core movie and clears metadata`() =
        runBlocking {
            val catalog = MovieCatalogPort(SCOPE)
            catalog.movieResult = { query ->
                available(
                    scope = query.frozenScope,
                    movie = movie(name = "metadata-core", id = query.itemId),
                )
            }
            val viewModel =
                installViewModel(
                    MovieViewModel(
                            catalogReadPort = catalog,
                            repository = repository(),
                            appPreferences = appPreferences(),
                            parseVideoMetadata = { throw IOException("metadata failed") },
                            peopleDispatcher = Dispatchers.Unconfined,
                        )
                        .withUnconfinedViewModelScope()
                )

            viewModel.loadMovie(MOVIE_ID)

            assertEquals("metadata-core", viewModel.state.value.movie?.name)
            assertNull(viewModel.state.value.videoMetadata)
            assertEquals("metadata failed", viewModel.state.value.error?.message)
        }

    @Test
    fun `preference enrichment failure still publishes core movie with default display preference`() =
        runBlocking {
            val catalog = MovieCatalogPort(SCOPE)
            catalog.movieResult = { query ->
                available(
                    scope = query.frozenScope,
                    movie = movie(name = "preference-core", id = query.itemId),
                )
            }
            val viewModel =
                installViewModel(
                    MovieViewModel(
                            catalogReadPort = catalog,
                            repository = repository(),
                            appPreferences = appPreferences(failReads = true),
                            parseVideoMetadata = { metadata() },
                            peopleDispatcher = Dispatchers.Unconfined,
                        )
                        .withUnconfinedViewModelScope()
                )

            viewModel.loadMovie(MOVIE_ID)

            assertEquals("preference-core", viewModel.state.value.movie?.name)
            assertFalse(viewModel.state.value.displayExtraInfo)
            assertEquals("prefs failed", viewModel.state.value.error?.message)
        }

    @Test
    fun `people projection failure still publishes core movie with safe fallback`() = runBlocking {
        val catalog = MovieCatalogPort(SCOPE)
        catalog.movieResult = { query ->
            available(
                scope = query.frozenScope,
                movie = movie(name = "people-core", id = query.itemId),
            )
        }
        val viewModel =
            installViewModel(
                MovieViewModel(
                        catalogReadPort = catalog,
                        repository = repository(),
                        appPreferences = appPreferences(),
                        parseVideoMetadata = { metadata() },
                        peopleDispatcher = FailingDispatcher(),
                    )
                    .withUnconfinedViewModelScope()
            )

        viewModel.loadMovie(MOVIE_ID)

        assertEquals("people-core", viewModel.state.value.movie?.name)
        assertTrue(viewModel.state.value.actors.isEmpty())
        assertNull(viewModel.state.value.director)
        assertTrue(viewModel.state.value.writers.isEmpty())
        assertEquals("people failed", viewModel.state.value.error?.message)
    }

    @Test
    fun `metadata cancellation keeps core movie and publishes no data error`() = runBlocking {
        val catalog = MovieCatalogPort(SCOPE)
        catalog.movieResult = { query ->
            available(
                scope = query.frozenScope,
                movie = movie(name = "cancel-core", id = query.itemId),
            )
        }
        val viewModel =
            installViewModel(
                MovieViewModel(
                        catalogReadPort = catalog,
                        repository = repository(),
                        appPreferences = appPreferences(),
                        parseVideoMetadata = { throw CancellationException("metadata cancelled") },
                        peopleDispatcher = Dispatchers.Unconfined,
                    )
                    .withUnconfinedViewModelScope()
            )

        viewModel.loadMovie(MOVIE_ID)

        assertEquals("cancel-core", viewModel.state.value.movie?.name)
        assertNull(viewModel.state.value.error)
    }

    @Test
    fun `refresh optional failure publishes new core movie instead of preserving stale detail`() =
        runBlocking {
            var requestCount = 0
            var parseCount = 0
            val catalog = MovieCatalogPort(SCOPE)
            catalog.movieResult = { query ->
                requestCount += 1
                available(
                    scope = query.frozenScope,
                    movie = movie(name = "version-$requestCount", id = query.itemId),
                )
            }
            val viewModel =
                installViewModel(
                    MovieViewModel(
                            catalogReadPort = catalog,
                            repository = repository(),
                            appPreferences = appPreferences(),
                            parseVideoMetadata = {
                                parseCount += 1
                                if (parseCount == 1) metadata()
                                else throw IOException("refresh metadata failed")
                            },
                            peopleDispatcher = Dispatchers.Unconfined,
                        )
                        .withUnconfinedViewModelScope()
                )

            viewModel.loadMovie(MOVIE_ID)
            viewModel.loadMovie(MOVIE_ID)

            assertEquals("version-2", viewModel.state.value.movie?.name)
            assertNull(viewModel.state.value.videoMetadata)
            assertEquals("refresh metadata failed", viewModel.state.value.error?.message)
        }

    @Test
    fun `real movie view model fences completion after all people projections`() = runBlocking {
        val peopleDispatcher = ManualDispatcher()
        val nextScope = SCOPE.copy(identityGeneration = 2L)
        val catalog = MovieCatalogPort(SCOPE)
        catalog.movieResult = { query ->
            available(
                scope = query.frozenScope,
                movie =
                    movie(
                        name = if (query.itemId == OTHER_MOVIE_ID) "new" else "old",
                        id = query.itemId,
                        sourceId = if (query.itemId == OTHER_MOVIE_ID) "source-b" else "source-a",
                    ),
            )
        }
        val viewModel =
            installViewModel(
                MovieViewModel(
                        catalogReadPort = catalog,
                        repository = repository(),
                        appPreferences = appPreferences(),
                        parseVideoMetadata = { metadata() },
                        peopleDispatcher = peopleDispatcher,
                    )
                    .withUnconfinedViewModelScope()
            )

        viewModel.loadMovie(MOVIE_ID)
        assertEquals(1, peopleDispatcher.size())
        catalog.scope = nextScope
        viewModel.loadMovie(OTHER_MOVIE_ID)
        assertEquals(2, peopleDispatcher.size())

        peopleDispatcher.runAt(1)
        peopleDispatcher.runAt(1)
        peopleDispatcher.runAt(1)
        assertEquals("new", viewModel.state.value.movie?.name)

        peopleDispatcher.runAt(0)
        peopleDispatcher.runAt(0)
        peopleDispatcher.runAt(0)

        assertEquals("new", viewModel.state.value.movie?.name)
    }

    private fun movie(
        name: String,
        id: UUID = MOVIE_ID,
        sourceId: String = "source-a",
    ): FindroidMovie =
        FindroidMovie(
            id = id,
            name = name,
            originalTitle = null,
            overview = "",
            sources =
                listOf(
                    FindroidSource(
                        id = sourceId,
                        name = "$sourceId.mkv",
                        type = FindroidSourceType.REMOTE,
                        path = "",
                        size = 0L,
                        mediaStreams = emptyList(),
                    )
                ),
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
        )

    private fun metadata(): VideoMetadata =
        VideoMetadata(
            size = 0L,
            videoTracks = emptyList(),
            audioTracks = emptyList(),
            subtitleTracks = emptyList(),
            resolution = emptyList<Resolution>(),
            videoCodecs = emptyList<VideoCodec?>(),
            displayProfiles = emptyList<DisplayProfile>(),
            audioChannels = emptyList<AudioChannel>(),
            audioCodecs = emptyList<AudioCodec?>(),
            isAtmos = emptyList(),
        )

    private fun available(
        scope: CatalogScope,
        movie: FindroidMovie,
    ): CatalogReadOutcome<FindroidMovie> =
        CatalogReadOutcome.Available(
            CatalogSnapshot(
                scope = scope,
                contentGeneration = 1L,
                freshness = CatalogFreshness.FRESH,
                observedAtEpochMs = 1L,
                value = movie,
            )
        )

    private fun appPreferences(failReads: Boolean = false): AppPreferences {
        val preferences =
            Proxy.newProxyInstance(
                SharedPreferences::class.java.classLoader,
                arrayOf(SharedPreferences::class.java),
            ) { proxy, method, args ->
                when (method.name) {
                    "getBoolean" ->
                        if (failReads) {
                            throw IllegalStateException("prefs failed")
                        } else {
                            args?.getOrNull(1) ?: false
                        }
                    "getString" -> args?.getOrNull(1)
                    "getInt" -> args?.getOrNull(1) ?: 0
                    "getLong" -> args?.getOrNull(1) ?: 0L
                    "getFloat" -> args?.getOrNull(1) ?: 0f
                    "getStringSet" -> args?.getOrNull(1)
                    "edit" ->
                        if (failReads) {
                            throw IllegalStateException("prefs failed")
                        } else {
                            null
                        }
                    "contains" -> false
                    "getAll" -> emptyMap<String, Any>()
                    "toString" -> "MovieCatalogConsumerTestPreferences"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.firstOrNull()
                    else -> null
                }
            } as SharedPreferences
        return AppPreferences(preferences)
    }

    private fun repository(): JellyfinRepository =
        Proxy.newProxyInstance(
            JellyfinRepository::class.java.classLoader,
            arrayOf(JellyfinRepository::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                "toString" -> "MovieCatalogConsumerTestRepository"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                else -> null
            }
        } as JellyfinRepository

    private fun <T : ViewModel> T.withUnconfinedViewModelScope(): T {
        val closeableClass =
            Class.forName("androidx.lifecycle.viewmodel.internal.CloseableCoroutineScope")
        val closeable =
            closeableClass
                .getConstructor(CoroutineScope::class.java)
                .newInstance(CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        ViewModel::class
            .java
            .getMethod("addCloseable", String::class.java, AutoCloseable::class.java)
            .invoke(
                this,
                "androidx.lifecycle.viewmodel.internal.ViewModelCoroutineScope.JOB_KEY",
                closeable,
            )
        return this
    }

    private fun installViewModel(viewModel: MovieViewModel): MovieViewModel {
        val store = ViewModelStore()
        viewModelStore = store
        val provider =
            ViewModelProvider(
                store,
                object : ViewModelProvider.Factory {
                    override fun <T : ViewModel> create(modelClass: Class<T>): T {
                        @Suppress("UNCHECKED_CAST")
                        return viewModel as T
                    }
                },
            )
        return provider[MovieViewModel::class.java]
    }

    private class ManualDispatcher : CoroutineDispatcher() {
        private val tasks = ArrayDeque<Runnable>()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            tasks.addLast(block)
        }

        fun size(): Int = tasks.size

        fun runAt(index: Int) {
            check(index in 0 until tasks.size) { "invalid task index $index/${tasks.size}" }
            tasks.removeAt(index).run()
        }
    }

    private class FailingDispatcher : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            throw IllegalStateException("people failed")
        }
    }

    private class MovieCatalogPort(var scope: CatalogScope?) : CatalogReadPort {
        var movieResult: suspend (CatalogMovieQuery) -> CatalogReadOutcome<FindroidMovie> = {
            CatalogReadOutcome.IdentityUnavailable
        }

        override suspend fun currentScope(): CatalogScope? = scope

        override suspend fun search(
            query: CatalogSearchQuery
        ): CatalogReadOutcome<List<FindroidItem>> = error("search must not run")

        override suspend fun home(query: CatalogHomeQuery): CatalogReadOutcome<CatalogHomeData> =
            error("home must not run")

        override suspend fun libraries(
            query: CatalogLibrariesQuery
        ): CatalogReadOutcome<List<FindroidCollection>> = error("libraries must not run")

        override suspend fun itemsPage(
            query: CatalogItemsPageQuery
        ): CatalogReadOutcome<List<FindroidItem>> = error("items must not run")

        override suspend fun movie(query: CatalogMovieQuery): CatalogReadOutcome<FindroidMovie> =
            movieResult(query)
    }

    private companion object {
        const val SERVER_ID = "server-a"
        val USER_ID: UUID = UUID.fromString("22222222-2222-4222-8222-222222222222")
        val MOVIE_ID: UUID = UUID.fromString("11111111-1111-4111-8111-111111111111")
        val OTHER_MOVIE_ID: UUID = UUID.fromString("99999999-9999-4999-8999-999999999999")
        val SCOPE =
            CatalogScope(
                origin = JellyfinOrigin(SERVER_ID, USER_ID),
                principalContext = CatalogPrincipalContext.ONLINE_ACCOUNT,
                identityGeneration = 1L,
            )
    }
}
