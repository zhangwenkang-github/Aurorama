package com.zhangwenkang.cinefin.film.presentation.home

import android.content.SharedPreferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zhangwenkang.cinefin.database.ServerDatabaseDao
import com.zhangwenkang.cinefin.film.R as FilmR
import com.zhangwenkang.cinefin.models.CollectionType
import com.zhangwenkang.cinefin.models.HomeItem
import com.zhangwenkang.cinefin.models.HomeSection
import com.zhangwenkang.cinefin.models.UiText
import com.zhangwenkang.cinefin.models.View
import com.zhangwenkang.cinefin.repository.catalog.CatalogHomeData
import com.zhangwenkang.cinefin.repository.catalog.CatalogHomeQuery
import com.zhangwenkang.cinefin.repository.catalog.CatalogReadOutcome
import com.zhangwenkang.cinefin.repository.catalog.CatalogReadPort
import com.zhangwenkang.cinefin.repository.catalog.CatalogScope
import com.zhangwenkang.cinefin.settings.domain.AppPreferences
import com.zhangwenkang.cinefin.settings.domain.models.HomeLibrarySettings
import com.zhangwenkang.cinefin.utils.BookCoverProvider
import com.zhangwenkang.cinefin.utils.BookCoverRules
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber

@HiltViewModel
class HomeViewModel
@Inject
constructor(
    private val catalogReadPort: CatalogReadPort,
    val appPreferences: AppPreferences,
    val database: ServerDatabaseDao,
    private val bookCoverProvider: BookCoverProvider,
) : ViewModel() {
    private val _state = MutableStateFlow(HomeState())
    val state = _state.asStateFlow()
    private var loadSerial = 0L

    /** W64：首页书籍卡本地封面（同书架链路）：itemId → `files/book_covers/<id>.jpg` 绝对路径。 */
    private val _bookCovers = MutableStateFlow<Map<UUID, String>>(emptyMap())
    val bookCovers = _bookCovers.asStateFlow()
    private val requestedBookCovers = mutableSetOf<UUID>()
    private var loadedScope: CatalogScope? = null

    /**
     * W64：首页书籍条目（继续阅读 / 最近添加 · 书籍）可见时懒生成封面。
     *
     * 与书架 `LibraryViewModel` 同一条 `BookCoverProvider` 链路：服务器图优先 → 生成缓存 → 生成（本地已下载文件 / HTTP Range）→
     * 失败类型占位。
     */
    fun requestBookCover(
        itemId: UUID,
        serverImageUrl: String? = null,
        /** W77-5：服务器元数据判定该条目是 PDF（未下载时远端封面直接回退占位，不做整本 Range 解析）。 */
        bookIsPdf: Boolean = false,
    ) {
        val cached = bookCoverProvider.cached(itemId)
        publishBookCover(itemId, cached)
        when (
            BookCoverRules.planCover(
                serverImageUrl = serverImageUrl,
                generatedPath = cached,
                generationFailed = cached == null && bookCoverProvider.isMarkedFailed(itemId),
            )
        ) {
            BookCoverRules.CoverSource.SERVER_IMAGE,
            BookCoverRules.CoverSource.PLACEHOLDER -> return
            BookCoverRules.CoverSource.GENERATED_CACHE -> return
            BookCoverRules.CoverSource.GENERATE -> Unit
        }
        if (!requestedBookCovers.add(itemId)) return
        viewModelScope.launch {
            val path = bookCoverProvider.ensureCover(itemId, serverImageUrl, bookIsPdf = bookIsPdf)
            publishBookCover(itemId, path)
        }
    }

    /** W69b：**服务器图确认不可用**（404 / 加载失败）时的回落——忽略服务器 URL，直接走本地生成。 */
    fun requestBookCoverFallback(itemId: UUID, bookIsPdf: Boolean = false) {
        val cached = bookCoverProvider.cached(itemId)
        if (cached != null) {
            publishBookCover(itemId, cached)
            return
        }
        Timber.d("Book cover fallback (server image unavailable): %s", itemId)
        val plan =
            BookCoverRules.planCover(
                serverImageUrl = null,
                generatedPath = null,
                generationFailed = bookCoverProvider.isMarkedFailed(itemId),
                serverImageUnavailable = true,
            )
        if (plan != BookCoverRules.CoverSource.GENERATE) return
        if (!requestedBookCovers.add(itemId)) return
        viewModelScope.launch {
            val path =
                bookCoverProvider.ensureCover(
                    itemId = itemId,
                    serverImageUrl = null,
                    serverImageUnavailable = true,
                    bookIsPdf = bookIsPdf,
                )
            publishBookCover(itemId, path)
        }
    }

    private fun publishBookCover(itemId: UUID, path: String?) {
        if (path == null) return
        _bookCovers.update { covers ->
            if (covers[itemId] == path) covers else covers + (itemId to path)
        }
    }

    private val catalogScopeListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (
                key == appPreferences.mediaCatalogScopeRevision.backendName ||
                    key == appPreferences.offlineMode.backendName
            ) {
                loadData()
            }
        }

    init {
        appPreferences.sharedPreferences.registerOnSharedPreferenceChangeListener(
            catalogScopeListener
        )
    }

    override fun onCleared() {
        appPreferences.sharedPreferences.unregisterOnSharedPreferenceChangeListener(
            catalogScopeListener
        )
        super.onCleared()
    }

    /** 在同一个 canonical scope、同一个 composite fence 下读取全部启用走廊；只发布一次完整快照。 */
    fun loadData(force: Boolean = false) {
        Timber.i("Loading catalog home")
        val requestSerial = ++loadSerial
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true, error = null)
            try {
                val scope = catalogReadPort.currentScope()
                if (scope == null) {
                    loadedScope = null
                    if (requestSerial == loadSerial) {
                        _state.value = _state.value.withoutCatalog().copy(isLoading = false)
                    }
                    return@launch
                }
                if (loadedScope != scope) {
                    loadedScope = null
                    _state.value = _state.value.withoutCatalog()
                }
                loadServerName(scope)
                val options = homeCatalogOptions(appPreferences)
                val outcome =
                    catalogReadPort.home(
                        CatalogHomeQuery(
                            frozenScope = scope,
                            includeSuggestions = options.includeSuggestions,
                            includeContinueWatching = options.includeContinueWatching,
                            includeContinueReading = options.includeContinueReading,
                            includeContinueListening = options.includeContinueListening,
                            includeNextUp = options.includeNextUp,
                            includeUserViews = options.includeUserViews,
                            forceRefresh = force,
                        )
                    )
                if (requestSerial != loadSerial) return@launch
                val currentScope = catalogReadPort.currentScope()
                when (outcome) {
                    CatalogReadOutcome.IdentityUnavailable -> {
                        loadedScope = null
                        _state.value = _state.value.withoutCatalog()
                    }
                    is CatalogReadOutcome.Available -> {
                        if (
                            isHomeCatalogResultCurrent(
                                frozenScope = scope,
                                snapshotScope = outcome.snapshot.scope,
                                currentScope = currentScope,
                            )
                        ) {
                            _state.value = outcome.snapshot.value.toHomeState(_state.value, options)
                            loadedScope = scope
                        } else {
                            loadedScope = null
                            _state.value = _state.value.withoutCatalog()
                        }
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                if (requestSerial == loadSerial) {
                    if (loadedScope == null) {
                        _state.value = _state.value.withoutCatalog()
                    }
                    _state.value = _state.value.copy(error = error)
                }
            } finally {
                if (requestSerial == loadSerial) {
                    _state.value = _state.value.copy(isLoading = false)
                }
            }
        }
    }

    private suspend fun loadServerName(scope: CatalogScope) {
        database.getServer(scope.origin.serverId)?.let { server ->
            _state.value = _state.value.copy(server = server)
        }
    }

    fun onAction(action: HomeAction) {
        when (action) {
            is HomeAction.OnRetryClick -> loadData(force = true)
            else -> Unit
        }
    }
}

internal data class HomeCatalogOptions(
    val includeSuggestions: Boolean,
    val includeContinueWatching: Boolean,
    val includeContinueReading: Boolean,
    val includeContinueListening: Boolean,
    val includeNextUp: Boolean,
    val includeUserViews: Boolean,
    val hiddenLibraryIds: Set<String>,
    val libraryOrder: List<String>,
    val includeRecentVideos: Boolean,
    val includeRecentBooks: Boolean,
    val includeRecentMusic: Boolean,
)

internal fun isHomeCatalogResultCurrent(
    frozenScope: CatalogScope,
    snapshotScope: CatalogScope,
    currentScope: CatalogScope?,
): Boolean = currentScope == frozenScope && snapshotScope == frozenScope

internal fun CatalogHomeData.toHomeState(
    current: HomeState,
    options: HomeCatalogOptions,
): HomeState {
    val visibleViews =
        views
            .filter { it.latestMedia.isNotEmpty() && it.view.type in CollectionType.supported }
            .filter {
                HomeLibrarySettings.isLibraryVisible(
                    it.view.id.toString(),
                    options.hiddenLibraryIds,
                )
            }
    val orderedIds =
        HomeLibrarySettings.applyOrder(
            visibleViews.map { it.view.id.toString() },
            options.libraryOrder,
        )
    val byId = visibleViews.associateBy { it.view.id.toString() }
    val projectedViews = orderedIds.mapNotNull { id ->
        byId[id]?.let { entry ->
            HomeItem.ViewItem(
                View(
                    id = entry.view.id,
                    name = entry.view.name,
                    items = entry.latestMedia,
                    type = entry.view.type,
                )
            )
        }
    }
    val recentVideos =
        if (options.includeRecentVideos) recentItems(projectedViews, VIDEO_LIBRARY_TYPES)
        else emptyList()
    val recentBooks =
        if (options.includeRecentBooks) recentItems(projectedViews, setOf(CollectionType.Books))
        else emptyList()
    val recentMusic =
        if (options.includeRecentMusic) recentItems(projectedViews, setOf(CollectionType.Music))
        else emptyList()
    return current.copy(
        suggestionsSection =
            suggestions
                .takeIf { options.includeSuggestions && it.isNotEmpty() }
                ?.let { HomeItem.Suggestions(UUID_SUGGESTIONS, it) },
        resumeSection =
            resumeWatching
                .takeIf { options.includeContinueWatching && it.isNotEmpty() }
                ?.let {
                    HomeItem.Section(
                        HomeSection(UUID_CONTINUE_WATCHING, uiTextContinueWatching, it)
                    )
                },
        resumeReadingSection =
            resumeReading
                .takeIf { options.includeContinueReading && it.isNotEmpty() }
                ?.let {
                    HomeItem.Section(HomeSection(UUID_CONTINUE_READING, uiTextContinueReading, it))
                },
        resumeListeningSection =
            resumeListening
                .takeIf { options.includeContinueListening && it.isNotEmpty() }
                ?.let {
                    HomeItem.Section(
                        HomeSection(UUID_CONTINUE_LISTENING, uiTextContinueListening, it)
                    )
                },
        nextUpSection =
            nextUp
                .takeIf { options.includeNextUp && it.isNotEmpty() }
                ?.let { HomeItem.Section(HomeSection(UUID_NEXT_UP, uiTextNextUp, it)) },
        views = if (options.includeUserViews) projectedViews else emptyList(),
        recentlyAddedVideos = recentVideos,
        recentlyAddedBooks = recentBooks,
        recentlyAddedMusic = recentMusic,
    )
}

private fun homeCatalogOptions(appPreferences: AppPreferences): HomeCatalogOptions =
    HomeCatalogOptions(
        includeSuggestions = appPreferences.getValue(appPreferences.homeSuggestions),
        includeContinueWatching = appPreferences.getValue(appPreferences.homeContinueWatching),
        includeContinueReading = appPreferences.getValue(appPreferences.homeContinueReading),
        includeContinueListening = appPreferences.getValue(appPreferences.homeContinueListening),
        includeNextUp = appPreferences.getValue(appPreferences.homeNextUp),
        includeUserViews = true,
        hiddenLibraryIds = appPreferences.getValue(appPreferences.uiHomeLibrariesHidden),
        libraryOrder =
            HomeLibrarySettings.decodeIdList(
                appPreferences.getValue(appPreferences.uiHomeLibraryOrder)
            ),
        includeRecentVideos = appPreferences.getValue(appPreferences.homeRecentlyAddedVideos),
        includeRecentBooks = appPreferences.getValue(appPreferences.homeRecentlyAddedBooks),
        includeRecentMusic = appPreferences.getValue(appPreferences.homeRecentlyAddedMusic),
    )

private fun HomeState.withoutCatalog(): HomeState =
    copy(
        suggestionsSection = null,
        resumeSection = null,
        resumeReadingSection = null,
        resumeListeningSection = null,
        nextUpSection = null,
        recentlyAddedVideos = emptyList(),
        recentlyAddedBooks = emptyList(),
        recentlyAddedMusic = emptyList(),
        views = emptyList(),
    )

/** 「最近添加」分区：合并同类库的最新条目、去重后取前 60（与旧海报墙同一口径）。 */
private fun recentItems(
    views: List<HomeItem.ViewItem>,
    types: Set<CollectionType>,
) = views.filter { it.view.type in types }.flatMap { it.view.items }.distinctBy { it.id }.take(60)

private val UUID_SUGGESTIONS = UUID.fromString("31e47044-9b79-4bb0-99d0-0e477ed65420")
private val UUID_CONTINUE_WATCHING = UUID(4937169328197226115, -4704919157662094443)
private val UUID_NEXT_UP = UUID(1783371395749072194, -6164625418200444295)
private val UUID_CONTINUE_READING = UUID.fromString("6f2f1c6a-9c31-4d2e-9f0a-2b8e6a5d1f01")
private val UUID_CONTINUE_LISTENING = UUID.fromString("b3a1e0d2-7c4f-4a92-8e21-5d9c6b7a0f02")
private val uiTextContinueWatching = UiText.StringResource(FilmR.string.continue_watching)
private val uiTextContinueReading = UiText.StringResource(FilmR.string.continue_reading)
private val uiTextContinueListening = UiText.StringResource(FilmR.string.continue_listening)
private val uiTextNextUp = UiText.StringResource(FilmR.string.next_up)
private val VIDEO_LIBRARY_TYPES =
    setOf(
        CollectionType.Movies,
        CollectionType.TvShows,
        CollectionType.HomeVideos,
        CollectionType.Mixed,
        CollectionType.BoxSets,
        CollectionType.Folders,
        CollectionType.Playlists,
    )
