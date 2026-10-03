package com.zhangwenkang.cinefin.presentation.film

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zhangwenkang.cinefin.models.CollectionType
import com.zhangwenkang.cinefin.models.FindroidCollection
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

@HiltViewModel
class BookshelfViewModel
@Inject
constructor(
    private val repository: JellyfinRepository,
    private val appPreferences: AppPreferences,
) : ViewModel() {
    private val _state = MutableStateFlow<BookshelfState>(BookshelfState.Loading)
    val state = _state.asStateFlow()

    /** 已加载的库键（null = 默认书库）；换库（含侧栏临时库视图）时重新解析。 */
    private var loadedLibraryKey: String? = null

    /** [libraryId] 非空 = 「临时库视图」（W53 追加）：侧栏点具体书籍库 → 只解析该库（找不到走空态）， 不读「客户端设置 → 书架媒体库」偏好、不写偏好。 */
    fun load(force: Boolean = false, libraryId: String? = null) {
        if (!force && loadedLibraryKey == libraryId && state.value !is BookshelfState.Loading)
            return
        loadedLibraryKey = libraryId
        viewModelScope.launch {
            _state.value = BookshelfState.Loading
            _state.value =
                runCatching { resolveBooksLibrary(temporaryLibraryId = libraryId) }
                    .fold(
                        onSuccess = { library ->
                            library?.let { BookshelfState.Ready(it) } ?: BookshelfState.Empty
                        },
                        onFailure = { BookshelfState.Failed(it) },
                    )
        }
    }

    /**
     * 「优先第一个非空的 books 库」：服务器上常有"建好但还没放书"的空壳书库， 用一次 `limit = 1` 的轻量查询确认非空（与库内容页同样的 `BOOK` +
     * 递归口径），都空则退回第一个书库—— 由库内容页显示空态，而不是把用户丢到媒体库总览。
     */
    private suspend fun resolveBooksLibrary(
        temporaryLibraryId: String? = null
    ): FindroidCollection? {
        val libraries = repository.getLibraries()
        if (temporaryLibraryId != null) {
            return libraries.firstOrNull {
                it.id.toString() == temporaryLibraryId && it.type == CollectionType.Books
            }
        }
        // 客户端设置「书架媒体库」：显式选定且仍然存在的书籍库优先，不再逐库判空。
        val preferredLibraryId =
            appPreferences
                .getValue(appPreferences.uiBookshelfLibraryId)
                ?.takeIf { it.isNotBlank() }
                ?.let { raw -> runCatching { UUID.fromString(raw) }.getOrNull() }
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
