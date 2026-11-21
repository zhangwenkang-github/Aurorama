package com.zhangwenkang.cinefin.presentation.film

import android.content.SharedPreferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zhangwenkang.cinefin.models.CollectionType
import com.zhangwenkang.cinefin.models.FindroidCollection
import com.zhangwenkang.cinefin.models.FindroidItem
import com.zhangwenkang.cinefin.presentation.utils.parseStoredLibraryId
import com.zhangwenkang.cinefin.presentation.utils.storedLibraryIdValue
import com.zhangwenkang.cinefin.repository.JellyfinRepository
import com.zhangwenkang.cinefin.repository.ReaderRepository
import com.zhangwenkang.cinefin.settings.domain.AppPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.BaseItemKind

/**
 * 书架页（顶部「书架」Tab 的落点）状态机。
 *
 * 2026-10-01 验收缺陷：书架入口原来直接读 `DrawerViewModel` 的库列表，而抽屉数据只在抽屉打开时才加载， 冷启动点「书架」时 `booksLibrary ==
 * null` → 回退到媒体库总览（两台设备都复现）。 现在书架自己解析：**永远进书架页**，页面内部按「第一个非空的书籍库 → 第一个书籍库 → 空态」三段式收敛， 不再依赖抽屉是否打开过。
 */
sealed interface BookshelfState {
    /** 正在解析服务器上的书籍库。 */
    data object Loading : BookshelfState

    /** 命中书籍库：交给库内容页渲染真实书目。 */
    data class Ready(val library: FindroidCollection) : BookshelfState

    /** 服务器上没有书籍库（或当前账号看不到）——显示空态，不再跳媒体库。 */
    data object Empty : BookshelfState

    data class Failed(val error: Throwable) : BookshelfState
}

/**
 * 书架「使用哪个书籍库」（W54-C，用户 2026-10-03 确认）：[selectedId] = null 表示「自动」（第一个非空书籍库）。
 *
 * [libraries] 只含服务器上的 books 库、顺序与服务器返回一致——顶栏「库选择」菜单的来源。
 */
internal data class BookshelfLibrarySelection(
    val selectedId: UUID?,
    val libraries: List<FindroidCollection> = emptyList(),
)

/** 书架增强数据（W6-T10）：最近阅读、本地书架、移出标记与卡片附加信息行。 */
internal data class BookshelfExtras(
    val recent: List<BookshelfRecentEntry> = emptyList(),
    val localBooks: List<BookshelfLocalBook> = emptyList(),
    /** `pref_reader_shelf_hidden`：服务器书目的本地隐藏标记（移出书架）。 */
    val hiddenIds: Set<String> = emptySet(),
    /** 条目附加信息行（`作者 · 42%` / `未读`），键 = 服务器 itemId。 */
    val metaLineById: Map<UUID, String> = emptyMap(),
    /** 阅读进度百分比缓存（本地书架卡片用）。 */
    val progressPercentByItemId: Map<UUID, Int> = emptyMap(),
    /** 服务器书目本体（最近阅读卡片点击 → 详情 / 阅读器）。 */
    val itemsById: Map<UUID, FindroidItem> = emptyMap(),
)

/** 书架一次加载的服务器书目上限（最近阅读归因 + 作者/进度映射的取样窗口）。 */
internal const val BOOKSHELF_SERVER_BOOK_LIMIT: Int = 200

/** 最近阅读取的本机进度条数上限（再按 [BOOKSHELF_RECENT_LIMIT] 截显示数）。 */
internal const val BOOKSHELF_PROGRESS_LIMIT: Int = 80

/** 书架卡片投影：书名 / 封面 URL / 作者（`FindroidFolder.authors`，非书籍为空）。 */
private fun FindroidItem.toShelfServerBook(): BookshelfServerBook =
    BookshelfServerBook(
        itemId = id,
        title = name,
        coverUrl = images.primary?.toString(),
        authors = authors,
    )

@HiltViewModel
class BookshelfViewModel
@Inject
constructor(
    private val repository: JellyfinRepository,
    private val appPreferences: AppPreferences,
    private val readerRepository: ReaderRepository,
) : ViewModel() {
    private val _state = MutableStateFlow<BookshelfState>(BookshelfState.Loading)
    val state = _state.asStateFlow()

    /**
     * 书架增强（W6-T10，READER-39）：最近阅读 / 本地书架 / 移出标记 / 条目附加信息。
     *
     * 与 [BookshelfState] 分开：库解析失败时增强数据仍可显示（本地书不依赖服务器），也避免状态机多一层壳。
     */
    private val _extras = MutableStateFlow(BookshelfExtras())
    internal val extras = _extras.asStateFlow()

    /** 顶栏「库选择」的候选项 + 落到实处的选择（临时库视图只显示路由指定的库，仍照常解析偏好）。 */
    private val _librarySelection = MutableStateFlow(BookshelfLibrarySelection(selectedId = null))
    internal val librarySelection = _librarySelection.asStateFlow()

    /** 已加载的库键（null = 默认书库）；换库（含侧栏临时库视图）时重新解析。 */
    private var loadedLibraryKey: String? = null

    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == appPreferences.uiBookshelfLibraryId.backendName) {
            // 顶栏 / 客户端设置改「书架媒体库」后即时生效；临时库视图保持路由指定的那个库。
            load(force = true, libraryId = loadedLibraryKey)
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

    /** [libraryId] 非空 = 「临时库视图」（W53 追加）：侧栏点具体书籍库 → 只解析该库（找不到走空态）， 不读「客户端设置 → 书架媒体库」偏好、不写偏好。 */
    fun load(force: Boolean = false, libraryId: String? = null) {
        if (!force && loadedLibraryKey == libraryId && state.value !is BookshelfState.Loading)
            return
        loadedLibraryKey = libraryId
        viewModelScope.launch {
            _state.value = BookshelfState.Loading
            val resolved = runCatching {
                val libraries = repository.getLibraries()
                val selection =
                    resolveBookshelfLibrarySelection(
                        libraries = libraries,
                        storedId = appPreferences.getValue(appPreferences.uiBookshelfLibraryId),
                    )
                _librarySelection.value = selection
                resolveBooksLibrary(
                    libraries = libraries,
                    temporaryLibraryId = libraryId,
                    preferredLibraryId = selection.selectedId,
                )
            }
            _state.value =
                resolved.fold(
                    onSuccess = { library ->
                        library?.let { BookshelfState.Ready(it) } ?: BookshelfState.Empty
                    },
                    onFailure = { BookshelfState.Failed(it) },
                )
            // 增强数据不阻塞主状态：解析失败也照常刷新（本地书架 + 最近阅读与服务器无关的部分仍可用）。
            loadShelfExtras(resolved.getOrNull())
        }
    }

    /**
     * 刷新书架增强数据（W6-T10）：最近阅读行 + 条目作者/进度 + 移出标记 + 本地书架。
     *
     * [library] 为空（无书籍库）时仍读取本地书架与最近阅读（本地书不依赖服务器）。
     */
    private suspend fun loadShelfExtras(library: FindroidCollection?) {
        val hiddenIds = appPreferences.getValue(appPreferences.readerShelfHiddenIds)
        val localBooks =
            LocalShelfRegistry.decode(appPreferences.getValue(appPreferences.readerLocalShelf))
        val progress = runCatching {
            readerRepository.listReadingProgress(limit = BOOKSHELF_PROGRESS_LIMIT)
        }
            .getOrDefault(emptyList())
        val serverItems =
            library
                ?.let { books ->
                    runCatching {
                            repository.getItems(
                                parentId = books.id,
                                includeTypes = listOf(BaseItemKind.BOOK),
                                recursive = true,
                                limit = BOOKSHELF_SERVER_BOOK_LIMIT,
                            )
                        }
                        .getOrDefault(emptyList())
                }
                .orEmpty()
        val serverBooks = serverItems.map { it.toShelfServerBook() }
        val percentById =
            progress
                .mapNotNull { item ->
                    bookshelfProgressPercent(item.progression)?.let { item.itemId to it }
                }
                .toMap()
        _extras.value =
            BookshelfExtras(
                recent =
                    bookshelfRecentEntries(
                        progress = progress,
                        serverBooks = bookshelfServerBooks(serverBooks),
                        localBooks = localBooks,
                    ),
                localBooks = localBooks,
                hiddenIds = hiddenIds,
                metaLineById =
                    serverBooks.associate {
                        it.itemId to bookshelfMetaLine(it.authors, percentById[it.itemId])
                    },
                progressPercentByItemId = percentById,
                itemsById = serverItems.associateBy { it.id },
            )
    }

    /** 手动刷新增强数据（导入成功后 / 从阅读器返回）。 */
    fun refreshExtras() {
        viewModelScope.launch { loadShelfExtras((state.value as? BookshelfState.Ready)?.library) }
    }

    /** 「移出书架」= 本地隐藏标记（服务器数据不动，红线）。 */
    fun hideFromShelf(itemId: UUID) {
        appPreferences.setValue(
            appPreferences.readerShelfHiddenIds,
            bookshelfToggleHidden(
                hiddenIds = appPreferences.getValue(appPreferences.readerShelfHiddenIds),
                itemId = itemId,
                hidden = true,
            ),
        )
        refreshExtras()
    }

    /** 「加入书架」= 清掉本地隐藏标记。 */
    fun addToShelf(itemId: UUID) {
        appPreferences.setValue(
            appPreferences.readerShelfHiddenIds,
            bookshelfToggleHidden(
                hiddenIds = appPreferences.getValue(appPreferences.readerShelfHiddenIds),
                itemId = itemId,
                hidden = false,
            ),
        )
        refreshExtras()
    }

    /** 导入书籍登记进本地书架（只存 URI / 显示名，不拷贝源文件）。 */
    fun addLocalBook(uri: String, name: String) {
        val book =
            BookshelfLocalBook(uri = uri, name = name, addedAtMs = System.currentTimeMillis())
        appPreferences.setValue(
            appPreferences.readerLocalShelf,
            LocalShelfRegistry.withBook(
                raw = appPreferences.getValue(appPreferences.readerLocalShelf),
                book = book,
            ),
        )
        refreshExtras()
    }

    /** 本地书「移出书架」= 删登记，不删源文件。 */
    fun removeLocalBook(uri: String) {
        appPreferences.setValue(
            appPreferences.readerLocalShelf,
            LocalShelfRegistry.withoutUri(
                raw = appPreferences.getValue(appPreferences.readerLocalShelf),
                uri = uri,
            ),
        )
        refreshExtras()
    }

    /** 顶栏「库选择」（W54-C）：null = 自动。写偏好触发监听器即时重解析（与视频页同一机制）。 */
    fun selectLibrary(libraryId: UUID?) {
        appPreferences.setValue(
            appPreferences.uiBookshelfLibraryId,
            storedLibraryIdValue(libraryId),
        )
    }

    /**
     * 「优先第一个非空的 books 库」：服务器上常有"建好但还没放书"的空壳书库， 用一次 `limit = 1` 的轻量查询确认非空（与库内容页同样的 `BOOK` +
     * 递归口径），都空则退回第一个书库—— 由库内容页显示空态，而不是把用户丢到媒体库总览。
     */
    private suspend fun resolveBooksLibrary(
        libraries: List<FindroidCollection>,
        temporaryLibraryId: String? = null,
        preferredLibraryId: UUID? = null,
    ): FindroidCollection? {
        if (temporaryLibraryId != null) {
            return libraries.firstOrNull {
                it.id.toString() == temporaryLibraryId && it.type == CollectionType.Books
            }
        }
        // 客户端设置「书架媒体库」：显式选定且仍然存在的书籍库优先，不再逐库判空。
        if (preferredLibraryId != null) {
            libraries
                .firstOrNull { it.type == CollectionType.Books && it.id == preferredLibraryId }
                ?.let {
                    return it
                }
        }
        val nonEmptyLibraryIds =
            libraries
                .filter { it.type == CollectionType.Books }
                .filter { library ->
                    repository
                        .getItems(
                            parentId = library.id,
                            includeTypes = listOf(BaseItemKind.BOOK),
                            recursive = true,
                            limit = 1,
                        )
                        .isNotEmpty()
                }
                .map { it.id }
                .toSet()
        return pickBooksLibrary(libraries = libraries, nonEmptyLibraryIds = nonEmptyLibraryIds)
    }
}

/**
 * 书库选择（纯逻辑，便于单测）： 按服务器顺序取**第一个非空**的 books 库；都为空时退回第一个 books 库（由库内容页显示空态）； 一个 books 库都没有时返回
 * null（书架页显示空态，不回退媒体库）。[preferredLibraryId] 是客户端设置里显式选定的书库， 只要仍在 books 库里就最高优先。
 */
internal fun pickBooksLibrary(
    libraries: List<FindroidCollection>,
    nonEmptyLibraryIds: Set<UUID> = emptySet(),
    preferredLibraryId: UUID? = null,
): FindroidCollection? {
    val booksLibraries = libraries.filter { it.type == CollectionType.Books }
    if (booksLibraries.isEmpty()) return null
    preferredLibraryId?.let { id ->
        booksLibraries
            .firstOrNull { it.id == id }
            ?.let {
                return it
            }
    }
    return booksLibraries.firstOrNull { it.id in nonEmptyLibraryIds } ?: booksLibraries.first()
}

/** 书架库选择解析（W54-C，纯逻辑便于单测）：只认服务器上的 books 库；[storedId] 指向的库不存在（被删 / 改了类型）→ 回落「自动」，不偷偷改选别家。 */
internal fun resolveBookshelfLibrarySelection(
    libraries: List<FindroidCollection>,
    storedId: String?,
): BookshelfLibrarySelection {
    val booksLibraries = libraries.filter { it.type == CollectionType.Books }
    val selectedId = booksLibraries.firstOrNull { it.id == parseStoredLibraryId(storedId) }?.id
    return BookshelfLibrarySelection(selectedId = selectedId, libraries = booksLibraries)
}
