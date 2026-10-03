package com.zhangwenkang.cinefin.presentation.film

import android.content.SharedPreferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zhangwenkang.cinefin.models.CollectionType
import com.zhangwenkang.cinefin.models.FindroidCollection
import com.zhangwenkang.cinefin.presentation.utils.parseStoredLibraryId
import com.zhangwenkang.cinefin.presentation.utils.storedLibraryIdValue
import com.zhangwenkang.cinefin.repository.JellyfinRepository
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

@HiltViewModel
class BookshelfViewModel
@Inject
constructor(
    private val repository: JellyfinRepository,
    private val appPreferences: AppPreferences,
) : ViewModel() {
    private val _state = MutableStateFlow<BookshelfState>(BookshelfState.Loading)
    val state = _state.asStateFlow()

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
            _state.value =
                runCatching {
                        val libraries = repository.getLibraries()
                        val selection =
                            resolveBookshelfLibrarySelection(
                                libraries = libraries,
                                storedId =
                                    appPreferences.getValue(appPreferences.uiBookshelfLibraryId),
                            )
                        _librarySelection.value = selection
                        resolveBooksLibrary(
                            libraries = libraries,
                            temporaryLibraryId = libraryId,
                            preferredLibraryId = selection.selectedId,
                        )
                    }
                    .fold(
                        onSuccess = { library ->
                            library?.let { BookshelfState.Ready(it) } ?: BookshelfState.Empty
                        },
                        onFailure = { BookshelfState.Failed(it) },
                    )
        }
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
