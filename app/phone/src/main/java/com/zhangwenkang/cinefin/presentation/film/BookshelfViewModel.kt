package com.zhangwenkang.cinefin.presentation.film

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zhangwenkang.cinefin.models.CollectionType
import com.zhangwenkang.cinefin.models.FindroidCollection
import com.zhangwenkang.cinefin.repository.JellyfinRepository
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
class BookshelfViewModel @Inject constructor(private val repository: JellyfinRepository) :
    ViewModel() {
    private val _state = MutableStateFlow<BookshelfState>(BookshelfState.Loading)
    val state = _state.asStateFlow()

    private var loaded = false

    fun load(force: Boolean = false) {
        if (loaded && !force) return
        loaded = true
        viewModelScope.launch {
            _state.value = BookshelfState.Loading
            _state.value =
                runCatching { resolveBooksLibrary() }
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
    private suspend fun resolveBooksLibrary(): FindroidCollection? {
        val libraries = repository.getLibraries()
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
 * null（书架页显示空态，不回退媒体库）。
 */
internal fun pickBooksLibrary(
    libraries: List<FindroidCollection>,
    nonEmptyLibraryIds: Set<UUID> = emptySet(),
): FindroidCollection? {
    val booksLibraries = libraries.filter { it.type == CollectionType.Books }
    if (booksLibraries.isEmpty()) return null
    return booksLibraries.firstOrNull { it.id in nonEmptyLibraryIds } ?: booksLibraries.first()
}
