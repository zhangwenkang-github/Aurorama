package com.zhangwenkang.cinefin.film.presentation.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zhangwenkang.cinefin.database.ServerDatabaseDao
import com.zhangwenkang.cinefin.film.R as FilmR
import com.zhangwenkang.cinefin.models.CollectionType
import com.zhangwenkang.cinefin.models.HomeItem
import com.zhangwenkang.cinefin.models.HomeSection
import com.zhangwenkang.cinefin.models.UiText
import com.zhangwenkang.cinefin.repository.JellyfinRepository
import com.zhangwenkang.cinefin.settings.domain.AppPreferences
import com.zhangwenkang.cinefin.settings.domain.models.HomeLibrarySettings
import com.zhangwenkang.cinefin.utils.toView
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.BaseItemKind
import timber.log.Timber

@HiltViewModel
class HomeViewModel
@Inject
constructor(
    val repository: JellyfinRepository,
    val appPreferences: AppPreferences,
    val database: ServerDatabaseDao,
) : ViewModel() {
    private val _state = MutableStateFlow(HomeState())
    val state = _state.asStateFlow()

    private val uuidSuggestions = UUID.fromString("31e47044-9b79-4bb0-99d0-0e477ed65420")
    private val uuidContinueWatching =
        UUID(4937169328197226115, -4704919157662094443) // 44845958-8326-4e83-beb4-c4f42e9eeb95
    private val uuidNextUp =
        UUID(1783371395749072194, -6164625418200444295) // 18bfced5-f237-4d42-aa72-d9d7fed19279
    private val uuidContinueReading = UUID.fromString("6f2f1c6a-9c31-4d2e-9f0a-2b8e6a5d1f01")
    private val uuidContinueListening = UUID.fromString("b3a1e0d2-7c4f-4a92-8e21-5d9c6b7a0f02")

    private val uiTextContinueWatching = UiText.StringResource(FilmR.string.continue_watching)
    private val uiTextContinueReading = UiText.StringResource(FilmR.string.continue_reading)
    private val uiTextContinueListening = UiText.StringResource(FilmR.string.continue_listening)
    private val uiTextNextUp = UiText.StringResource(FilmR.string.next_up)

    fun loadData() {
        Timber.i("Loading data")
        viewModelScope.launch(Dispatchers.Default) {
            _state.emit(_state.value.copy(isLoading = true, error = null))
            try {
                appPreferences.getValue(appPreferences.currentServer)?.let { serverId ->
                    loadServerName(serverId)
                }

                loadSuggestions()
                loadResumeItems()
                loadResumeReading()
                loadResumeListening()
                loadNextUpItems()
                loadViews()
            } catch (e: Exception) {
                _state.emit(_state.value.copy(error = e))
            }
            _state.emit(_state.value.copy(isLoading = false))
        }
    }

    private suspend fun loadServerName(serverId: String) {
        val server = database.getServer(serverId)
        if (server != null) {
            _state.emit(_state.value.copy(server = server))
        }
    }

    private suspend fun loadSuggestions() {
        Timber.i("Loading suggestions")
        if (!appPreferences.getValue(appPreferences.homeSuggestions)) {
            _state.emit(_state.value.copy(suggestionsSection = null))
            return
        }

        val items = repository.getSuggestions()

        val section =
            if (items.isEmpty()) {
                null
            } else {
                HomeItem.Suggestions(id = uuidSuggestions, items = items)
            }

        _state.emit(_state.value.copy(suggestionsSection = section))
    }

    private suspend fun loadResumeItems() {
        Timber.i("Loading resume items")
        if (!appPreferences.getValue(appPreferences.homeContinueWatching)) {
            _state.emit(_state.value.copy(resumeSection = null))
            return
        }

        val resumeItems =
            repository.getResumeItems(
                includeItemTypes = listOf(BaseItemKind.MOVIE, BaseItemKind.EPISODE)
            )

        val section =
            if (resumeItems.isEmpty()) {
                null
            } else {
                HomeItem.Section(
                    HomeSection(uuidContinueWatching, uiTextContinueWatching, resumeItems)
                )
            }

        _state.emit(_state.value.copy(resumeSection = section))
    }

    /** 继续阅读（W54-D）：书籍续读。服务器没有阅读进度 / 开关关闭时走廊自动隐藏。 */
    private suspend fun loadResumeReading() {
        Timber.i("Loading resume reading items")
        if (!appPreferences.getValue(appPreferences.homeContinueReading)) {
            _state.emit(_state.value.copy(resumeReadingSection = null))
            return
        }

        // 书籍续读是服务器可选能力（老服务器可能不支持 Book 续播）：查询失败按「无内容」处理，
        // 只隐藏这条走廊，不让整个首页进错误态。
        val items = runCatching {
            repository.getResumeItems(includeItemTypes = listOf(BaseItemKind.BOOK))
        }
            .onFailure { Timber.w(it, "Resume reading items unavailable") }
            .getOrDefault(emptyList())

        val section =
            if (items.isEmpty()) {
                null
            } else {
                HomeItem.Section(HomeSection(uuidContinueReading, uiTextContinueReading, items))
            }

        _state.emit(_state.value.copy(resumeReadingSection = section))
    }

    /** 继续收听（W54-D）：音频续播。 */
    private suspend fun loadResumeListening() {
        Timber.i("Loading resume listening items")
        if (!appPreferences.getValue(appPreferences.homeContinueListening)) {
            _state.emit(_state.value.copy(resumeListeningSection = null))
            return
        }

        val items = runCatching {
            repository.getResumeItems(includeItemTypes = listOf(BaseItemKind.AUDIO))
        }
            .onFailure { Timber.w(it, "Resume listening items unavailable") }
            .getOrDefault(emptyList())

        val section =
            if (items.isEmpty()) {
                null
            } else {
                HomeItem.Section(HomeSection(uuidContinueListening, uiTextContinueListening, items))
            }

        _state.emit(_state.value.copy(resumeListeningSection = section))
    }

    private suspend fun loadNextUpItems() {
        Timber.i("Loading next up items")
        if (!appPreferences.getValue(appPreferences.homeNextUp)) {
            _state.emit(_state.value.copy(nextUpSection = null))
            return
        }

        val nextUpItems = repository.getNextUp()

        val section =
            if (nextUpItems.isEmpty()) {
                null
            } else {
                HomeItem.Section(HomeSection(uuidNextUp, uiTextNextUp, nextUpItems))
            }

        _state.emit(_state.value.copy(nextUpSection = section))
    }

    private suspend fun loadViews() {
        Timber.i("Loading views")
        val allViews =
            repository.getUserViews().filter { view ->
                CollectionType.fromString(view.collectionType?.serialName) in
                    CollectionType.supported
            }

        // W54-D：逐库「在首页显示」开关（存的是关闭集合，默认全部开）+ 设置里的媒体库顺序
        // （未列入顺序的库按服务器顺序追加）。旧实现用 `pref_ui_home_library_id` 只留一个库，
        // 是「首页只有最新电影」的根因，已删除。
        val hiddenLibraryIds = appPreferences.getValue(appPreferences.uiHomeLibrariesHidden)
        val storedOrder =
            HomeLibrarySettings.decodeIdList(
                appPreferences.getValue(appPreferences.uiHomeLibraryOrder)
            )
        val orderedViewIds =
            HomeLibrarySettings.applyOrder(
                allViews
                    .map { it.id.toString() }
                    .filter { HomeLibrarySettings.isLibraryVisible(it, hiddenLibraryIds) },
                storedOrder,
            )
        val enabledViews = orderedViewIds.mapNotNull { id ->
            allViews.firstOrNull { it.id.toString() == id }
        }

        val views =
            enabledViews
                .map { view -> view to repository.getLatestMedia(view.id) }
                .filter { (_, latest) -> latest.isNotEmpty() }
                .map { (view, latest) -> view.toView(latest) }
                .map { HomeItem.ViewItem(it) }

        val recentVideos =
            if (appPreferences.getValue(appPreferences.homeRecentlyAddedVideos)) {
                recentItems(views, VIDEO_LIBRARY_TYPES)
            } else {
                emptyList()
            }
        val recentBooks =
            if (appPreferences.getValue(appPreferences.homeRecentlyAddedBooks)) {
                recentItems(views, setOf(CollectionType.Books))
            } else {
                emptyList()
            }
        val recentMusic =
            if (appPreferences.getValue(appPreferences.homeRecentlyAddedMusic)) {
                recentItems(views, setOf(CollectionType.Music))
            } else {
                emptyList()
            }

        _state.emit(
            _state.value.copy(
                views = views,
                recentlyAddedVideos = recentVideos,
                recentlyAddedBooks = recentBooks,
                recentlyAddedMusic = recentMusic,
            )
        )
    }

    /** 「最近添加」分区：合并同类库的最新条目、去重后取前 60（与旧海报墙同一口径）。 */
    private fun recentItems(
        views: List<HomeItem.ViewItem>,
        types: Set<CollectionType>,
    ) =
        views
            .filter { it.view.type in types }
            .flatMap { it.view.items }
            .distinctBy { it.id }
            .take(60)

    fun onAction(action: HomeAction) {
        when (action) {
            is HomeAction.OnRetryClick -> {
                loadData()
            }
            else -> Unit
        }
    }

    private companion object {
        /** 「最近添加 · 视频」归属的库类型（含混合 / 家庭视频 / 文件夹等视频向库）。 */
        val VIDEO_LIBRARY_TYPES =
            setOf(
                CollectionType.Movies,
                CollectionType.TvShows,
                CollectionType.HomeVideos,
                CollectionType.Mixed,
                CollectionType.BoxSets,
                CollectionType.Folders,
                CollectionType.Playlists,
            )
    }
}
