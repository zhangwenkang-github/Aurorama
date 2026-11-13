package com.zhangwenkang.cinefin.film.presentation.movie

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zhangwenkang.cinefin.film.domain.VideoMetadataParser
import com.zhangwenkang.cinefin.film.domain.selectSourceForMetadata
import com.zhangwenkang.cinefin.film.presentation.detail.LatestContentRequest
import com.zhangwenkang.cinefin.film.presentation.detail.readOptionalDetail
import com.zhangwenkang.cinefin.models.FindroidItemPerson
import com.zhangwenkang.cinefin.models.FindroidMovie
import com.zhangwenkang.cinefin.models.FindroidSource
import com.zhangwenkang.cinefin.models.VideoMetadata
import com.zhangwenkang.cinefin.repository.JellyfinRepository
import com.zhangwenkang.cinefin.repository.catalog.CatalogMovieQuery
import com.zhangwenkang.cinefin.repository.catalog.CatalogReadOutcome
import com.zhangwenkang.cinefin.repository.catalog.CatalogReadPort
import com.zhangwenkang.cinefin.repository.catalog.CatalogScope
import com.zhangwenkang.cinefin.settings.domain.AppPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.model.api.PersonKind

@HiltViewModel
class MovieViewModel
private constructor(
    private val catalogReadPort: CatalogReadPort,
    private val repository: JellyfinRepository,
    private val appPreferences: AppPreferences,
    private val parseVideoMetadata: suspend (FindroidSource) -> VideoMetadata,
    private val peopleDispatcher: CoroutineDispatcher,
    @Suppress("UNUSED_PARAMETER") constructorMarker: Unit,
) : ViewModel() {
    @Inject
    constructor(
        catalogReadPort: CatalogReadPort,
        repository: JellyfinRepository,
        appPreferences: AppPreferences,
        videoMetadataParser: VideoMetadataParser,
    ) : this(
        catalogReadPort = catalogReadPort,
        repository = repository,
        appPreferences = appPreferences,
        parseVideoMetadata = videoMetadataParser::parse,
        peopleDispatcher = Dispatchers.Default,
        constructorMarker = Unit,
    )

    internal constructor(
        catalogReadPort: CatalogReadPort,
        repository: JellyfinRepository,
        appPreferences: AppPreferences,
        parseVideoMetadata: suspend (FindroidSource) -> VideoMetadata,
        peopleDispatcher: CoroutineDispatcher,
    ) : this(
        catalogReadPort = catalogReadPort,
        repository = repository,
        appPreferences = appPreferences,
        parseVideoMetadata = parseVideoMetadata,
        peopleDispatcher = peopleDispatcher,
        constructorMarker = Unit,
    )

    private val _state = MutableStateFlow(MovieState())
    val state = _state.asStateFlow()
    private val contentRequests = LatestContentRequest()

    lateinit var movieId: UUID
        private set

    private var loadSerial = 0L
    private var loadedScope: CatalogScope? = null
    private var loadedMovieId: UUID? = null

    fun loadMovie(movieId: UUID, preferredMediaSourceId: String? = null) {
        this.movieId = movieId
        val requestSerial = ++loadSerial
        val contentRequest = contentRequests.begin()
        viewModelScope.launch {
            try {
                val scope = catalogReadPort.currentScope()
                if (scope == null) {
                    loadedScope = null
                    loadedMovieId = null
                    if (requestSerial == loadSerial && contentRequests.isCurrent(contentRequest)) {
                        _state.value = _state.value.clearCatalog()
                    }
                    return@launch
                }
                if (loadedScope != scope || loadedMovieId != movieId) {
                    loadedScope = null
                    loadedMovieId = null
                    _state.value = _state.value.clearCatalog()
                }
                val outcome =
                    catalogReadPort.movie(CatalogMovieQuery(itemId = movieId, frozenScope = scope))
                if (requestSerial != loadSerial || !contentRequests.isCurrent(contentRequest)) {
                    return@launch
                }
                when (outcome) {
                    CatalogReadOutcome.IdentityUnavailable -> {
                        loadedScope = null
                        loadedMovieId = null
                        _state.value =
                            _state.value
                                .clearCatalog()
                                .copy(error = IllegalStateException("movie identity unavailable"))
                    }
                    is CatalogReadOutcome.Available -> {
                        val token =
                            MovieCatalogRequestToken(
                                serial = requestSerial,
                                movieId = movieId,
                                scope = scope,
                            )
                        val movie = outcome.snapshot.value
                        val actorsResult = readOptionalDetail { getActors(movie) }
                        val directorResult = readOptionalDetail { getDirector(movie) }
                        val writersResult = readOptionalDetail { getWriters(movie) }
                        val currentScope = catalogReadPort.currentScope()
                        if (
                            !contentRequests.isCurrent(contentRequest) ||
                                !isMovieCatalogResultCurrent(
                                    request = token,
                                    currentSerial = loadSerial,
                                    currentMovieId = this@MovieViewModel.movieId,
                                    frozenScope = scope,
                                    currentScope = currentScope,
                                    snapshotScope = outcome.snapshot.scope,
                                )
                        ) {
                            if (
                                requestSerial != loadSerial ||
                                    !contentRequests.isCurrent(contentRequest)
                            ) {
                                return@launch
                            }
                            loadedScope = null
                            loadedMovieId = null
                            _state.value = _state.value.clearCatalog()
                            return@launch
                        }
                        val peopleError =
                            listOf(actorsResult, directorResult, writersResult)
                                .firstNotNullOfOrNull { result ->
                                    result.exceptionOrNull() as? Exception
                                }
                        _state.value =
                            _state.value.withCatalogMovie(
                                movie = movie,
                                videoMetadata = null,
                                actors = actorsResult.getOrDefault(emptyList()),
                                director = directorResult.getOrNull(),
                                writers = writersResult.getOrDefault(emptyList()),
                                error = peopleError,
                            )
                        loadedScope = scope
                        loadedMovieId = movieId

                        val videoMetadataResult = readOptionalDetail {
                            selectSourceForMetadata(
                                    movie.sources,
                                    preferredMediaSourceId,
                                )
                                ?.let { source -> parseVideoMetadata(source) }
                        }
                        val displayExtraInfoResult = readOptionalDetail {
                            appPreferences.getValue(appPreferences.displayExtraInfo)
                        }
                        val currentScopeAfterEnrichment = catalogReadPort.currentScope()
                        if (
                            !contentRequests.isCurrent(contentRequest) ||
                                !isMovieCatalogResultCurrent(
                                    request = token,
                                    currentSerial = loadSerial,
                                    currentMovieId = this@MovieViewModel.movieId,
                                    frozenScope = scope,
                                    currentScope = currentScopeAfterEnrichment,
                                    snapshotScope = outcome.snapshot.scope,
                                )
                        ) {
                            if (
                                requestSerial != loadSerial ||
                                    !contentRequests.isCurrent(contentRequest)
                            ) {
                                return@launch
                            }
                            loadedScope = null
                            loadedMovieId = null
                            _state.value = _state.value.clearCatalog()
                            return@launch
                        }
                        val enrichmentError =
                            listOf(
                                    actorsResult,
                                    directorResult,
                                    writersResult,
                                    videoMetadataResult,
                                    displayExtraInfoResult,
                                )
                                .firstNotNullOfOrNull { result ->
                                    result.exceptionOrNull() as? Exception
                                }
                        _state.value =
                            _state.value.copy(
                                videoMetadata = videoMetadataResult.getOrNull(),
                                displayExtraInfo =
                                    displayExtraInfoResult.getOrDefault(
                                        _state.value.displayExtraInfo
                                    ),
                                error = enrichmentError,
                            )
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                if (requestSerial == loadSerial && contentRequests.isCurrent(contentRequest)) {
                    if (loadedScope == null || loadedMovieId != movieId) {
                        _state.value = _state.value.clearCatalog()
                    }
                    _state.value = _state.value.copy(error = error)
                }
            }
        }
    }

    private suspend fun getActors(item: FindroidMovie): List<FindroidItemPerson> =
        withContext(peopleDispatcher) { item.people.filter { it.type == PersonKind.ACTOR } }

    private suspend fun getDirector(item: FindroidMovie): FindroidItemPerson? =
        withContext(peopleDispatcher) { item.people.firstOrNull { it.type == PersonKind.DIRECTOR } }

    private suspend fun getWriters(item: FindroidMovie): List<FindroidItemPerson> =
        withContext(peopleDispatcher) { item.people.filter { it.type == PersonKind.WRITER } }

    fun onAction(action: MovieAction) {
        when (action) {
            is MovieAction.MarkAsPlayed -> {
                viewModelScope.launch {
                    repository.markAsPlayed(movieId)
                    loadMovie(movieId)
                }
            }
            is MovieAction.UnmarkAsPlayed -> {
                viewModelScope.launch {
                    repository.markAsUnplayed(movieId)
                    loadMovie(movieId)
                }
            }
            is MovieAction.MarkAsFavorite -> {
                viewModelScope.launch {
                    repository.markAsFavorite(movieId)
                    loadMovie(movieId)
                }
            }
            is MovieAction.UnmarkAsFavorite -> {
                viewModelScope.launch {
                    repository.unmarkAsFavorite(movieId)
                    loadMovie(movieId)
                }
            }
            else -> Unit
        }
    }
}

internal data class MovieCatalogRequestToken(
    val serial: Long,
    val movieId: UUID,
    val scope: CatalogScope,
)

internal fun isMovieCatalogResultCurrent(
    request: MovieCatalogRequestToken,
    currentSerial: Long,
    currentMovieId: UUID?,
    frozenScope: CatalogScope,
    currentScope: CatalogScope?,
    snapshotScope: CatalogScope,
): Boolean =
    request.serial == currentSerial &&
        request.movieId == currentMovieId &&
        request.scope == frozenScope &&
        currentScope == frozenScope &&
        snapshotScope == frozenScope

internal fun MovieState.clearCatalog(): MovieState =
    copy(
        movie = null,
        videoMetadata = null,
        actors = emptyList(),
        director = null,
        writers = emptyList(),
        error = null,
    )

internal fun MovieState.withCatalogMovie(
    movie: FindroidMovie,
    videoMetadata: VideoMetadata?,
    actors: List<FindroidItemPerson>,
    director: FindroidItemPerson?,
    writers: List<FindroidItemPerson>,
    displayExtraInfo: Boolean = this.displayExtraInfo,
    error: Exception? = null,
): MovieState =
    copy(
        movie = movie,
        videoMetadata = videoMetadata,
        actors = actors,
        director = director,
        writers = writers,
        displayExtraInfo = displayExtraInfo,
        error = error,
    )
