package com.zhangwenkang.cinefin.film.presentation.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.cachedIn
import com.zhangwenkang.cinefin.models.CollectionType
import com.zhangwenkang.cinefin.models.FindroidItem
import com.zhangwenkang.cinefin.models.SortBy
import com.zhangwenkang.cinefin.models.SortOrder
import com.zhangwenkang.cinefin.repository.ItemsPagingSource
import com.zhangwenkang.cinefin.repository.JellyfinRepository
import com.zhangwenkang.cinefin.repository.MetadataCacheRules
import com.zhangwenkang.cinefin.repository.MetadataPreloader
import com.zhangwenkang.cinefin.settings.domain.AppPreferences
import com.zhangwenkang.cinefin.utils.BookCoverProvider
import com.zhangwenkang.cinefin.utils.BookCoverRules
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.BaseItemKind
import timber.log.Timber

/**
 * 库内容页 ViewModel（视频库 / 书籍库 / 书架共用）。
 *
 * W54-B 在此之上补齐头部所依赖的状态：顶部 tabs、工具行（计数 / 网格列表 / 排序 / 筛选）、库内类型与 制片发行商、下拉刷新。口径（tab 出现规则 / 筛选映射 /
 * 计数文案）全部走 [LibraryHeaderRules] 的纯函数， 页面上只做渲染。
 */
@HiltViewModel
class LibraryViewModel
@Inject
constructor(
    private val jellyfinRepository: JellyfinRepository,
    private val appPreferences: AppPreferences,
    /** W59：书籍封面自动生成（书籍库 / 书架条目可见时懒生成）。 */
    private val bookCoverProvider: BookCoverProvider,
    /** W69b：元数据预加载（预取下一页 / 可见卡片详情）。 */
    private val metadataPreloader: MetadataPreloader,
) : ViewModel() {
    private val _state = MutableStateFlow(LibraryState())
    val state = _state.asStateFlow()

    /** W59：书籍封面缓存（itemId → 本地绝对路径），UI 逐条目读取。 */
    private val _bookCovers = MutableStateFlow<Map<UUID, String>>(emptyMap())
    val bookCovers = _bookCovers.asStateFlow()
    private val requestedBookCovers = mutableSetOf<UUID>()

    lateinit var parentId: UUID
    lateinit var libraryType: CollectionType

    lateinit var sortBy: SortBy
    lateinit var sortOrder: SortOrder

    /** W54-D：首页「全部」入口传入的初始排序（不写偏好，仅本次进入生效）。 */
    private var initialSortBy: SortBy? = null
    private var initialSortOrder: SortOrder? = null

    private var countJob: Job? = null
    private var tabJob: Job? = null

    /** W69：最近一次成功换入分页列表的时间（epoch ms）；TTL 内重进页面不重建 Pager。 */
    private var itemsLoadedAtMs: Long = 0L

    /** W69b：本 ViewModel 例的预取来源键（页面销毁时只取消自己的在途预取）。 */
    private val prefetchSource = "library-${hashCode()}"

    /** W69b：最近一次「静默重取」信号时间——同页最小刷新冷却 30 s，避免反复重进触发刷新风暴。 */
    private var lastSilentRevalidateAtMs: Long = 0L

    fun setup(
        parentId: UUID,
        libraryType: CollectionType,
        initialSortBy: SortBy? = null,
        initialSortOrder: SortOrder? = null,
    ) {
        this.parentId = parentId
        this.libraryType = libraryType
        this.initialSortBy = initialSortBy
        this.initialSortOrder = initialSortOrder
        _state.update { it.copy(tabs = libraryTabs(libraryType)) }
    }

    /**
     * W59：书籍条目可见时懒生成封面——**条目可见时一次** + 磁盘缓存 + 失败标记。
     *
     * 非书籍库直接忽略；命中缓存 / 失败标记 / 已请求由 provider 与本类拦截，滚动回来不会重复生成。
     */
    fun requestBookCover(
        itemId: UUID,
        serverImageUrl: String? = null,
        /** W77-5：服务器元数据判定该条目是 PDF（未下载时远端封面直接回退占位，不做整本 Range 解析）。 */
        bookIsPdf: Boolean = false,
    ) {
        if (!::libraryType.isInitialized || libraryType != CollectionType.Books) return
        val cached = bookCoverProvider.cached(itemId)
        when (
            BookCoverRules.planCover(
                serverImageUrl = serverImageUrl,
                generatedPath = cached,
                generationFailed = cached == null && bookCoverProvider.isMarkedFailed(itemId),
            )
        ) {
            // 服务器图优先 / 生成失败占位：都不需要生成。
            BookCoverRules.CoverSource.SERVER_IMAGE,
            BookCoverRules.CoverSource.PLACEHOLDER -> return
            BookCoverRules.CoverSource.GENERATED_CACHE -> {
                val path = cached ?: return
                publishBookCover(itemId, path)
                return
            }
            BookCoverRules.CoverSource.GENERATE -> Unit
        }
        if (!requestedBookCovers.add(itemId)) return
        viewModelScope.launch {
            val path = bookCoverProvider.ensureCover(itemId, serverImageUrl, bookIsPdf = bookIsPdf)
            publishBookCover(itemId, path)
        }
    }

    /**
     * W69b：**服务器图确认不可用**（404 / 加载失败）时的回落——忽略服务器 URL，直接本地生成 （未下载在线书籍也生成）。与首页 / 书架共用
     * `BookCoverProvider` 单一链路。
     */
    fun requestBookCoverFallback(itemId: UUID, bookIsPdf: Boolean = false) {
        if (!::libraryType.isInitialized || libraryType != CollectionType.Books) return
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

    /** W69b：可见卡片详情预取（详情页打开时命中缓存）；非视频条目直接忽略。 */
    fun prefetchItemDetail(item: FindroidItem) {
        metadataPreloader.prefetchDetail(item, source = prefetchSource)
    }

    override fun onCleared() {
        // W69b：页面销毁 → 取消尚未完成的预取（已完成的缓存保留）。
        metadataPreloader.cancel(prefetchSource)
        super.onCleared()
    }

    /**
     * 换入分页列表。
     *
     * W69（缓存优先 + 静默刷新）：TTL（[MetadataCacheRules.DEFAULT_TTL_MS]）内且已有一份分页列表时直接复用—— 进入页面 / 从详情返回不再新建
     * Pager（否则 `LazyPagingItems` 会先清空、骨架盖住整面海报）。 [force] = 下拉刷新 / 排序筛选变化 / 重试，先失效会话缓存再重取。
     */
    fun loadItems(force: Boolean = false) {
        val itemType = libraryItemTypes(libraryType)
        val recursive = libraryRecursive(itemType)

        if (!force && itemsLoadedAtMs > 0L) {
            // 已经上屏过一份分页列表：TTL 内直接复用（不请求、不重建 Pager）；
            // TTL 外同样不重建——只发一个"静默重取"信号，页面保留现有海报，
            // Paging 刷新回来后原地替换（重建 Pager 会先清空列表、骨架盖住整面海报）。
            if (
                MetadataCacheRules.shouldSilentlyRevalidate(
                    loadedAtMs = itemsLoadedAtMs,
                    nowMs = System.currentTimeMillis(),
                )
            ) {
                val now = System.currentTimeMillis()
                if (MetadataCacheRules.canRefresh(lastSilentRevalidateAtMs, now)) {
                    lastSilentRevalidateAtMs = now
                    itemsLoadedAtMs = now
                    _state.update { it.copy(refreshSignal = it.refreshSignal + 1) }
                    loadCount()
                }
            }
            return
        }
        if (force) jellyfinRepository.invalidateMetadataCache()

        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }

            initSorting()

            try {
                val items =
                    jellyfinRepository
                        .getItemsPaging(
                            parentId = parentId,
                            includeTypes = itemType,
                            recursive = recursive,
                            sortBy = activeSortBy(),
                            sortOrder = activeSortOrder(),
                            filters = libraryFilterItemFilters(_state.value.filter),
                            genres = _state.value.genre?.let { listOf(it) },
                            studios = _state.value.studio?.let { listOf(it) },
                        )
                        .cachedIn(viewModelScope)
                _state.update { it.copy(items = items, isLoading = false) }
                itemsLoadedAtMs = System.currentTimeMillis()
                prefetchNextPage(itemType = itemType, recursive = recursive)
            } catch (e: Exception) {
                _state.update { it.copy(error = e, isLoading = false) }
            }

            loadCount()
        }
    }

    /**
     * W69b：首屏渲染后预取下一页（第二页）——起点 / 长度与 Paging 完全一致（首屏 `initialLoadSize` 之后的第一页： `startIndex =
     * 30`、`limit = 10`）；只有「明确还有更多条目」时才发（总数未知时也放行一次）， 之后 Paging 滚到页尾加载该页即命中缓存；失败静默（预取不参与页面状态）。
     */
    private fun prefetchNextPage(itemType: List<BaseItemKind>?, recursive: Boolean) {
        val total = _state.value.totalCount
        if (total != null && total <= ItemsPagingSource.INITIAL_LOAD_SIZE) return
        metadataPreloader.prefetchItemsPage(
            parentId = parentId,
            includeTypes = itemType,
            recursive = recursive,
            sortBy = activeSortBy(),
            sortOrder = activeSortOrder(),
            startIndex = ItemsPagingSource.INITIAL_LOAD_SIZE,
            limit = ItemsPagingSource.PAGE_SIZE,
            filters = libraryFilterItemFilters(_state.value.filter),
            genres = _state.value.genre?.let { listOf(it) },
            studios = _state.value.studio?.let { listOf(it) },
            source = prefetchSource,
        )
    }

    /** 下拉刷新：计数与当前 tab 真实重取（分页列表由页面用 `LazyPagingItems.refresh()` 重取，避免重建 Pager 让列表闪空）。 */
    fun refresh() {
        // W69：强制刷新 = 先失效元数据缓存，之后的计数 / tab / 分页读取直打服务器（静默替换，不清列表）。
        jellyfinRepository.invalidateMetadataCache()
        viewModelScope.launch {
            _state.update { it.copy(refreshing = true) }
            try {
                listOfNotNull(loadCount(), loadTab()).forEach { it.join() }
            } finally {
                _state.update { it.copy(refreshing = false) }
            }
        }
    }

    private suspend fun initSorting() {
        if (!::sortBy.isInitialized || !::sortOrder.isInitialized) {
            // W54-D：首页「全部」入口默认「最近添加」倒序（不写全局偏好）；其余入口沿用偏好。
            if (initialSortBy != null) {
                sortBy = initialSortBy!!
                sortOrder = initialSortOrder ?: SortOrder.DESCENDING
            } else {
                sortBy = SortBy.fromString(appPreferences.getValue(appPreferences.sortBy))
                sortOrder = SortOrder.fromString(appPreferences.getValue(appPreferences.sortOrder))
            }
            _state.update { it.copy(sortBy = sortBy, sortOrder = sortOrder) }
        }
    }

    /** 排序映射（剧集库的「按观看时间」→ `SeriesDatePlayed`）：纯函数在 [librarySortByFor]。 */
    private fun activeSortBy(): SortBy =
        librarySortByFor(
            libraryType,
            if (::sortBy.isInitialized) sortBy else SortBy.defaultValue,
        )

    private fun activeSortOrder(): SortOrder =
        if (::sortOrder.isInitialized) sortOrder else SortOrder.ASCENDING

    private fun setSorting(sortBy: SortBy, sortOrder: SortOrder) {
        this.sortBy = sortBy
        this.sortOrder = sortOrder
        viewModelScope.launch {
            _state.update { it.copy(sortBy = sortBy, sortOrder = sortOrder) }
            appPreferences.setValue(appPreferences.sortBy, sortBy.toString())
            appPreferences.setValue(appPreferences.sortOrder, sortOrder.toString())
        }
    }

    /** 工具行「1-94 / 94」的总数：单发一条 `TotalRecordCount` 请求，与网格同过滤条件。 */
    private fun loadCount(): Job {
        countJob?.cancel()
        val job = viewModelScope.launch {
            val itemType = libraryItemTypes(libraryType)
            val total = runCatching {
                jellyfinRepository.getItemCount(
                    parentId = parentId,
                    includeTypes = itemType,
                    recursive = libraryRecursive(itemType),
                    filters = libraryFilterItemFilters(_state.value.filter),
                    genres = _state.value.genre?.let { listOf(it) },
                    studios = _state.value.studio?.let { listOf(it) },
                )
            }
                .getOrNull()
            _state.update { it.copy(totalCount = total) }
        }
        countJob = job
        return job
    }

    /** 当前 tab 的内容：库名 tab 走分页列表，其余 tab 单发请求。 */
    private fun loadTab(): Job? {
        val tab = _state.value.tab
        tabJob?.cancel()
        if (tab == LibraryTab.Library) {
            _state.update { it.copy(tabItems = emptyList(), tags = emptyList(), tabError = null) }
            return null
        }
        val job = viewModelScope.launch {
            _state.update { it.copy(tabLoading = true, tabError = null) }
            val itemType = libraryItemTypes(libraryType)
            try {
                when (tab) {
                    LibraryTab.Suggestions -> {
                        val items =
                            jellyfinRepository.getLibrarySuggestions(
                                parentId = parentId,
                                includeTypes = itemType,
                                limit = SuggestionsLimit,
                            )
                        _state.update {
                            it.copy(tabItems = items, tags = emptyList(), tabLoading = false)
                        }
                    }
                    LibraryTab.Upcoming -> {
                        val items =
                            jellyfinRepository.getUpcomingEpisodes(
                                parentId = parentId,
                                limit = SuggestionsLimit,
                            )
                        _state.update {
                            it.copy(tabItems = items, tags = emptyList(), tabLoading = false)
                        }
                    }
                    LibraryTab.Episodes -> {
                        val items =
                            jellyfinRepository.getItems(
                                parentId = parentId,
                                includeTypes = listOf(BaseItemKind.EPISODE),
                                recursive = true,
                                sortBy = activeSortBy(),
                                sortOrder = activeSortOrder(),
                                limit = EpisodesLimit,
                            )
                        _state.update {
                            it.copy(tabItems = items, tags = emptyList(), tabLoading = false)
                        }
                    }
                    LibraryTab.Genres -> {
                        val tags = jellyfinRepository.getGenres(parentId, itemType)
                        _state.update {
                            it.copy(tags = tags, tabItems = emptyList(), tabLoading = false)
                        }
                    }
                    LibraryTab.Studios -> {
                        val tags = jellyfinRepository.getStudios(parentId, itemType)
                        _state.update {
                            it.copy(tags = tags, tabItems = emptyList(), tabLoading = false)
                        }
                    }
                    LibraryTab.Library -> Unit
                }
            } catch (e: Exception) {
                _state.update { it.copy(tabError = e, tabLoading = false) }
            }
        }
        tabJob = job
        return job
    }

    /** 排序 / 筛选 / 库内过滤变化后重新加载分页列表与计数。 */
    private fun reloadList() {
        loadItems(force = true)
    }

    fun onAction(action: LibraryAction) {
        when (action) {
            is LibraryAction.ChangeSorting -> {
                if (action.sortBy != this.sortBy || action.sortOrder != this.sortOrder) {
                    setSorting(sortBy = action.sortBy, sortOrder = action.sortOrder)
                    reloadList()
                }
            }
            is LibraryAction.SelectTab -> {
                if (_state.value.tab != action.tab) {
                    _state.update { it.copy(tab = action.tab) }
                    loadTab()
                }
            }
            is LibraryAction.SelectViewMode -> {
                _state.update { it.copy(viewMode = action.viewMode) }
            }
            is LibraryAction.SelectFilter -> {
                if (_state.value.filter != action.filter) {
                    _state.update { it.copy(filter = action.filter) }
                    reloadList()
                }
            }
            is LibraryAction.SelectTag -> {
                val fromGenres = _state.value.tab == LibraryTab.Genres
                _state.update {
                    it.copy(
                        tab = LibraryTab.Library,
                        genre = if (fromGenres) action.tag.name else it.genre,
                        studio = if (fromGenres) it.studio else action.tag.name,
                    )
                }
                reloadList()
            }
            LibraryAction.ClearTag -> {
                if (_state.value.genre != null || _state.value.studio != null) {
                    _state.update { it.copy(genre = null, studio = null) }
                    reloadList()
                }
            }
            LibraryAction.Refresh -> refresh()
            else -> Unit
        }
    }

    private companion object {
        /** 建议 / 即将播出一次取多少条：够一屏，又不把整库拉下来。 */
        const val SuggestionsLimit = 24

        /** 剧集 tab 一次取多少集：覆盖常见整剧，不做分页。 */
        const val EpisodesLimit = 300
    }
}
