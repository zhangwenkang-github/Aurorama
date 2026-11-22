package com.zhangwenkang.cinefin.film.presentation.movie

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zhangwenkang.cinefin.film.domain.VideoMetadataParser
import com.zhangwenkang.cinefin.film.domain.selectSourceForMetadata
import com.zhangwenkang.cinefin.film.presentation.detail.DetailWriteEvent
import com.zhangwenkang.cinefin.film.presentation.detail.LatestContentRequest
import com.zhangwenkang.cinefin.film.presentation.detail.readOptionalDetail
import com.zhangwenkang.cinefin.film.presentation.detail.toDetailWriteEvent
import com.zhangwenkang.cinefin.models.FindroidItemPerson
import com.zhangwenkang.cinefin.models.FindroidMovie
import com.zhangwenkang.cinefin.models.FindroidSource
import com.zhangwenkang.cinefin.models.VideoMetadata
import com.zhangwenkang.cinefin.repository.ItemMetadataSnapshot
import com.zhangwenkang.cinefin.repository.JellyfinRepository
import com.zhangwenkang.cinefin.repository.UserDataWriteOutcome
import com.zhangwenkang.cinefin.repository.UserPolicyStore
import com.zhangwenkang.cinefin.repository.catalog.CatalogMovieQuery
import com.zhangwenkang.cinefin.repository.catalog.CatalogReadOutcome
import com.zhangwenkang.cinefin.repository.catalog.CatalogReadPort
import com.zhangwenkang.cinefin.repository.catalog.CatalogScope
import com.zhangwenkang.cinefin.repository.localStateChanged
import com.zhangwenkang.cinefin.settings.domain.AppPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
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
    /** W8-T6：元数据编辑入口的权限来源；测试构造器不注入时保持 fail-closed。 */
    private val userPolicyStore: UserPolicyStore?,
    @Suppress("UNUSED_PARAMETER") constructorMarker: Unit,
) : ViewModel() {
    @Inject
    constructor(
        catalogReadPort: CatalogReadPort,
        repository: JellyfinRepository,
        appPreferences: AppPreferences,
        videoMetadataParser: VideoMetadataParser,
        userPolicyStore: UserPolicyStore,
    ) : this(
        catalogReadPort = catalogReadPort,
        repository = repository,
        appPreferences = appPreferences,
        parseVideoMetadata = videoMetadataParser::parse,
        peopleDispatcher = Dispatchers.Default,
        userPolicyStore = userPolicyStore,
        constructorMarker = Unit,
    )

    internal constructor(
        catalogReadPort: CatalogReadPort,
        repository: JellyfinRepository,
        appPreferences: AppPreferences,
        parseVideoMetadata: suspend (FindroidSource) -> VideoMetadata,
        peopleDispatcher: CoroutineDispatcher,
        userPolicyStore: UserPolicyStore? = null,
    ) : this(
        catalogReadPort = catalogReadPort,
        repository = repository,
        appPreferences = appPreferences,
        parseVideoMetadata = parseVideoMetadata,
        peopleDispatcher = peopleDispatcher,
        userPolicyStore = userPolicyStore,
        constructorMarker = Unit,
    )

    private val _state = MutableStateFlow(MovieState())
    val state = _state.asStateFlow()

    /** W8-T1：收藏 / 已看的写结果（权限失败 / 排队待同步）交给屏幕弹 Snackbar。 */
    private val writeEventsChannel = Channel<DetailWriteEvent>(Channel.BUFFERED)
    val writeEvents = writeEventsChannel.receiveAsFlow()

    /** W8-T5：删除服务器条目的结果；[DetailWriteEvent.Confirmed] = 屏幕关闭详情页，其余弹 Snackbar。 */
    private val deleteEventsChannel = Channel<DetailWriteEvent>(Channel.BUFFERED)
    val deleteEvents = deleteEventsChannel.receiveAsFlow()

    /** W8-T6：当前账号能否编辑元数据（管理员专属）；未拉取 / 身份切换后一律 false（fail-closed）。 */
    private val _canEditMetadata = MutableStateFlow(false)
    val canEditMetadata = _canEditMetadata.asStateFlow()

    /** W8-T6：编辑对话框预填快照；null = 服务器当前值未取到（字段禁用）。 */
    private val _metadataSnapshot = MutableStateFlow<ItemMetadataSnapshot?>(null)
    val metadataSnapshot = _metadataSnapshot.asStateFlow()

    /** W8-T6：快照读取中——屏幕等它结束后再展示对话框。 */
    private val _metadataSnapshotLoading = MutableStateFlow(false)
    val metadataSnapshotLoading = _metadataSnapshotLoading.asStateFlow()

    /** W8-T6：提交中——对话框据此禁用保存按钮。 */
    private val _metadataSaving = MutableStateFlow(false)
    val metadataSaving = _metadataSaving.asStateFlow()

    /** W8-T6：编辑结果；[DetailWriteEvent.Confirmed] = 屏幕关闭对话框，其余走既有 Snackbar 文案。 */
    private val metadataEventsChannel = Channel<DetailWriteEvent>(Channel.BUFFERED)
    val metadataEvents = metadataEventsChannel.receiveAsFlow()

    init {
        val policyStore = userPolicyStore
        if (policyStore != null) {
            viewModelScope.launch {
                runCatching { policyStore.ensureLoaded() }
                policyStore.access.collect { access ->
                    _canEditMetadata.value = access.canEditMetadata()
                }
            }
        }
    }

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
            is MovieAction.MarkAsPlayed -> writeUserData { repository.markAsPlayed(movieId) }
            is MovieAction.UnmarkAsPlayed -> writeUserData { repository.markAsUnplayed(movieId) }
            is MovieAction.MarkAsFavorite -> writeUserData { repository.markAsFavorite(movieId) }
            is MovieAction.UnmarkAsFavorite ->
                writeUserData { repository.unmarkAsFavorite(movieId) }
            else -> Unit
        }
    }

    /** W8-T1：写结果先给提示（权限失败 / 排队待同步），再重取详情以服务端结果为准。 */
    private fun writeUserData(write: suspend () -> UserDataWriteOutcome) {
        viewModelScope.launch {
            val outcome = runCatching { write() }.getOrNull()
            writeEventsChannel.send(outcome.toDetailWriteEvent())
            loadMovie(movieId)
        }
    }

    /** W8-T5：删除服务器条目。失败时本地未改动，不重取详情；成功由屏幕关闭详情页。 */
    fun deleteItem() {
        viewModelScope.launch {
            val outcome = runCatching { repository.deleteItem(movieId) }.getOrNull()
            deleteEventsChannel.send(outcome.toDetailWriteEvent())
        }
    }

    /** W8-T6：打开编辑对话框前先取服务器当前值；取不到（null）时对话框字段禁用。 */
    fun loadMetadataSnapshot() {
        _metadataSnapshotLoading.value = true
        _metadataSnapshot.value = null
        viewModelScope.launch {
            _metadataSnapshot.value =
                runCatching { repository.fetchItemMetadataSnapshot(movieId) }.getOrNull()
            _metadataSnapshotLoading.value = false
        }
    }

    /** W8-T6：提交元数据编辑。成功后重取详情（以服务端为准）并由屏幕关闭对话框；失败走既有 Snackbar 文案。 */
    fun saveMetadata(name: String, overview: String?, sortName: String?) {
        if (_metadataSaving.value) return
        _metadataSaving.value = true
        viewModelScope.launch {
            val outcome = runCatching {
                repository.updateItemMetadata(
                    itemId = movieId,
                    name = name,
                    overview = overview,
                    sortName = sortName,
                )
            }
                .getOrNull()
            _metadataSaving.value = false
            if (outcome?.localStateChanged == true) loadMovie(movieId)
            metadataEventsChannel.send(outcome.toDetailWriteEvent())
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
