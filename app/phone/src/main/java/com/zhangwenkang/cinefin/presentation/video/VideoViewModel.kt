package com.zhangwenkang.cinefin.presentation.video

import android.content.SharedPreferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.zhangwenkang.cinefin.models.FindroidCollection
import com.zhangwenkang.cinefin.models.FindroidItem
import com.zhangwenkang.cinefin.player.local.domain.SleepTimerController
import com.zhangwenkang.cinefin.presentation.utils.storedLibraryIdValue
import com.zhangwenkang.cinefin.repository.MetadataCacheRules
import com.zhangwenkang.cinefin.repository.catalog.*
import com.zhangwenkang.cinefin.settings.domain.AppPreferences
import com.zhangwenkang.cinefin.settings.domain.models.VideoDisplayMode
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import timber.log.Timber

enum class VideoLibrarySource {
    Online,
    Offline,
}

internal data class VideoLoadedMarker(
    val scope: CatalogScope,
    val contentGeneration: Long,
)

internal data class VideoLoadRequest(val serial: Long, val scope: CatalogScope)

internal fun shouldReuseVideoCache(
    cachedMarker: VideoLoadedMarker?,
    requestedScope: CatalogScope,
    requestedContentGeneration: Long,
    loaded: Boolean,
    sameTemporaryLibrary: Boolean,
    force: Boolean,
    loadedAtMs: Long,
    nowMs: Long,
): Boolean =
    !force &&
        loaded &&
        cachedMarker?.scope == requestedScope &&
        cachedMarker.contentGeneration == requestedContentGeneration &&
        sameTemporaryLibrary &&
        MetadataCacheRules.isFresh(loadedAtMs, nowMs)

internal fun isCurrentVideoLoad(
    request: VideoLoadRequest,
    currentSerial: Long,
    currentScope: CatalogScope?,
): Boolean = request.serial == currentSerial && request.scope == currentScope

/** 视频模式页（W53）状态。 */
data class VideoState(
    val source: VideoLibrarySource = VideoLibrarySource.Online,
    val allLibraries: List<FindroidCollection> = emptyList(),
    val libraries: List<FindroidCollection> = emptyList(),
    val selectedLibraryId: UUID? = null,
    val isLoading: Boolean = false,
    val loaded: Boolean = false,
    val error: Exception? = null,
    val displayMode: VideoDisplayMode = VideoDisplayMode.defaultValue,
    val aggregateItems: Flow<PagingData<FindroidItem>> = flowOf(PagingData.empty()),
    val loadedAtMs: Long = 0L,
    val temporaryLibrary: FindroidCollection? = null,
)

/** 视频模式页数据源：从 canonical catalog scope 解析视频库，并以同一 scope 维护聚合分页流。 */
@HiltViewModel
class VideoViewModel
private constructor(
    private val catalogReadPort: CatalogReadPort,
    private val appPreferences: AppPreferences,
    initialSleepTimerState: StateFlow<SleepTimerController.State>,
    private val selectSleepTimerAction: (Int?) -> Unit,
) : ViewModel() {
    @Inject
    constructor(
        catalogReadPort: CatalogReadPort,
        appPreferences: AppPreferences,
        sleepTimerController: SleepTimerController,
    ) : this(
        catalogReadPort = catalogReadPort,
        appPreferences = appPreferences,
        initialSleepTimerState = sleepTimerController.state,
        selectSleepTimerAction = sleepTimerController::select,
    )

    internal constructor(
        catalogReadPort: CatalogReadPort,
        appPreferences: AppPreferences,
        sleepTimerState: StateFlow<SleepTimerController.State>,
    ) : this(
        catalogReadPort = catalogReadPort,
        appPreferences = appPreferences,
        initialSleepTimerState = sleepTimerState,
        selectSleepTimerAction = {},
    )

    private val _state = MutableStateFlow(VideoState())
    val state = _state.asStateFlow()
    private var loadSerial = 0L
    private var loadedMarker: VideoLoadedMarker? = null

    internal val loadedCatalogMarker: VideoLoadedMarker?
        get() = loadedMarker

    val sleepTimerState = initialSleepTimerState

    fun selectSleepTimer(minutes: Int?) = selectSleepTimerAction(minutes)

    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            appPreferences.uiVideoDisplayMode.backendName,
            appPreferences.uiVideoLibraryId.backendName -> applyCurrentSelection()
            appPreferences.offlineMode.backendName -> load()
        }
    }

    init {
        appPreferences.sharedPreferences.registerOnSharedPreferenceChangeListener(
            preferenceListener
        )
    }

    override fun onCleared() {
        appPreferences.sharedPreferences.unregisterOnSharedPreferenceChangeListener(
            preferenceListener
        )
        super.onCleared()
    }

    fun load(temporaryLibraryId: String? = null, force: Boolean = false) {
        val requestSerial = ++loadSerial
        viewModelScope.launch {
            val scope = catalogReadPort.currentScope()
            if (scope == null) {
                if (requestSerial == loadSerial) clearCatalog()
                return@launch
            }
            val current = _state.value
            val source = scope.librarySource()
            val sameTemporaryLibrary =
                current.temporaryLibrary?.id?.toString() == temporaryLibraryId
            val request = VideoLoadRequest(serial = requestSerial, scope = scope)
            val scopeChanged = loadedMarker?.scope != scope
            if (scopeChanged) loadedMarker = null
            _state.value =
                current.copy(
                    source = source,
                    allLibraries = if (scopeChanged) emptyList() else current.allLibraries,
                    libraries = if (scopeChanged) emptyList() else current.libraries,
                    selectedLibraryId = if (scopeChanged) null else current.selectedLibraryId,
                    temporaryLibrary = if (scopeChanged) null else current.temporaryLibrary,
                    isLoading = true,
                    loaded = if (scopeChanged) false else current.loaded,
                    error = null,
                    displayMode = readDisplayMode(),
                    aggregateItems =
                        if (scopeChanged) flowOf(PagingData.empty()) else current.aggregateItems,
                    loadedAtMs = if (scopeChanged) 0L else current.loadedAtMs,
                )
            try {
                val outcome =
                    catalogReadPort.libraries(
                        CatalogLibrariesQuery(
                            frozenScope = scope,
                            forceRefresh = force,
                        )
                    )
                if (!isCurrentVideoLoad(request, loadSerial, catalogReadPort.currentScope())) {
                    return@launch
                }
                when (outcome) {
                    CatalogReadOutcome.IdentityUnavailable -> {
                        if (requestSerial == loadSerial) clearCatalog()
                    }
                    is CatalogReadOutcome.Available -> {
                        if (outcome.snapshot.scope != scope) {
                            if (requestSerial == loadSerial) clearCatalog()
                            return@launch
                        }
                        val nowMs = System.currentTimeMillis()
                        if (
                            shouldReuseVideoCache(
                                cachedMarker = loadedMarker,
                                requestedScope = scope,
                                requestedContentGeneration = outcome.snapshot.contentGeneration,
                                loaded = _state.value.loaded,
                                sameTemporaryLibrary = sameTemporaryLibrary,
                                force = force,
                                loadedAtMs = _state.value.loadedAtMs,
                                nowMs = nowMs,
                            )
                        ) {
                            _state.value = _state.value.copy(isLoading = false)
                            return@launch
                        }
                        val libraries = pickVideoLibraries(outcome.snapshot.value)
                        val temporaryLibrary = temporaryLibraryId?.let { id ->
                            libraries.firstOrNull { it.id.toString() == id }
                        }
                        val selection =
                            resolveVideoLibrariesForMode(
                                readDisplayMode(),
                                libraries,
                                readStoredLibraryId(),
                            )
                        val visibleLibraries =
                            temporaryLibrary?.let { listOf(it) } ?: selection.visible
                        _state.value =
                            _state.value.copy(
                                allLibraries = libraries,
                                libraries = visibleLibraries,
                                selectedLibraryId = selection.selectedId,
                                temporaryLibrary = temporaryLibrary,
                                loaded = true,
                                isLoading = false,
                                error = null,
                                aggregateItems = aggregateFlow(scope, visibleLibraries),
                                loadedAtMs = nowMs,
                            )
                        loadedMarker =
                            VideoLoadedMarker(
                                scope = scope,
                                contentGeneration = outcome.snapshot.contentGeneration,
                            )
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Exception) {
                if (requestSerial == loadSerial) {
                    Timber.w(throwable, "读取视频库失败")
                    if (loadedMarker?.scope != scope) {
                        _state.value =
                            _state.value.copy(
                                allLibraries = emptyList(),
                                libraries = emptyList(),
                                aggregateItems = flowOf(PagingData.empty()),
                                loaded = false,
                                loadedAtMs = 0L,
                            )
                    }
                    _state.value =
                        _state.value.copy(
                            isLoading = false,
                            error = throwable.toExceptionOrNull(),
                        )
                }
            }
        }
    }

    fun selectLibrary(libraryId: UUID?) {
        appPreferences.setValue(appPreferences.uiVideoLibraryId, storedLibraryIdValue(libraryId))
    }

    private fun applyCurrentSelection() {
        val current = _state.value
        val displayMode = readDisplayMode()
        val selection =
            resolveVideoLibrariesForMode(
                displayMode,
                current.allLibraries,
                readStoredLibraryId(),
            )
        if (current.temporaryLibrary != null) {
            _state.value =
                current.copy(
                    displayMode = displayMode,
                    selectedLibraryId = selection.selectedId,
                )
            return
        }
        val scope = loadedMarker?.scope
        _state.value =
            current.copy(
                displayMode = displayMode,
                selectedLibraryId = selection.selectedId,
                libraries = selection.visible,
                aggregateItems =
                    if (scope == null || selection.visible.isEmpty()) {
                        flowOf(PagingData.empty())
                    } else {
                        aggregateFlow(scope, selection.visible)
                    },
            )
    }

    private fun aggregateFlow(
        scope: CatalogScope,
        libraries: List<FindroidCollection>,
    ): Flow<PagingData<FindroidItem>> {
        if (libraries.isEmpty()) return flowOf(PagingData.empty())
        return Pager(
                config = PagingConfig(pageSize = AGGREGATE_PAGE_SIZE, enablePlaceholders = false),
                pagingSourceFactory = {
                    VideoAggregatePagingSource(
                        catalogReadPort = catalogReadPort,
                        frozenScope = scope,
                        libraryIds = libraries.map { it.id },
                    )
                },
            )
            .flow
            .cachedIn(viewModelScope)
    }

    private fun clearCatalog() {
        loadedMarker = null
        _state.value =
            _state.value.copy(
                allLibraries = emptyList(),
                libraries = emptyList(),
                selectedLibraryId = null,
                temporaryLibrary = null,
                isLoading = false,
                loaded = false,
                error = null,
                aggregateItems = flowOf(PagingData.empty()),
                loadedAtMs = 0L,
            )
    }

    private fun readDisplayMode(): VideoDisplayMode =
        VideoDisplayMode.fromString(appPreferences.getValue(appPreferences.uiVideoDisplayMode))

    private fun readStoredLibraryId(): String? =
        appPreferences.getValue(appPreferences.uiVideoLibraryId)

    private companion object {
        const val AGGREGATE_PAGE_SIZE = 30
    }
}

private fun CatalogScope.librarySource(): VideoLibrarySource =
    if (principalContext == CatalogPrincipalContext.SAVED_OFFLINE_ACCOUNT) {
        VideoLibrarySource.Offline
    } else {
        VideoLibrarySource.Online
    }

private fun Throwable.toExceptionOrNull(): Exception = this as? Exception ?: Exception(this)
