package com.zhangwenkang.cinefin.presentation.film

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zhangwenkang.cinefin.models.FindroidItem
import com.zhangwenkang.cinefin.repository.JellyfinRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import javax.inject.Provider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 「我的收藏」页 UI 状态（W60b）：跨库汇总条目 + 类型筛选 + 服务端排序。 */
data class FavoritesState(
    val isLoading: Boolean = true,
    val error: Throwable? = null,
    val items: List<FindroidItem> = emptyList(),
    val filter: FavoriteTypeFilter = FavoriteTypeFilter.ALL,
    val sort: FavoriteSort = FavoriteSort.DATE_ADDED,
) {
    /** 当前筛选下展示的条目（排序保持服务器返回顺序）。 */
    val visibleItems: List<FindroidItem>
        get() = filterFavoriteItems(items, filter)
}

/**
 * 「我的收藏」页数据源（W60b）：`filters=IsFavorite` 跨库汇总（电影 / 剧集 / 单集）。
 *
 * 仓库按当前偏好（在线 / 离线）解析；离线模式返回空表，页面显示空态。排序切换会重新查询（客户端没有 `DateCreated` 字段）； 类型筛选在本地做，不打扰服务器。
 */
@HiltViewModel
class FavoritesItemsViewModel
@Inject
constructor(private val repositoryProvider: Provider<JellyfinRepository>) : ViewModel() {
    private val _state = MutableStateFlow(FavoritesState())
    val state: StateFlow<FavoritesState> = _state.asStateFlow()

    fun load() {
        val requestedSort = _state.value.sort
        viewModelScope.launch {
            _state.update { it.copy(isLoading = it.items.isEmpty(), error = null) }
            val spec = favoriteSortSpec(requestedSort)
            val result = runCatching {
                repositoryProvider
                    .get()
                    .getFavoriteItems(sortBy = spec.sortBy, sortOrder = spec.sortOrder)
            }
            _state.update { current ->
                // 请求期间用户又换了排序：丢弃这份过期结果，由新请求覆盖。
                if (current.sort != requestedSort) {
                    current
                } else {
                    result.fold(
                        onSuccess = { items ->
                            current.copy(isLoading = false, error = null, items = items)
                        },
                        onFailure = { error -> current.copy(isLoading = false, error = error) },
                    )
                }
            }
        }
    }

    fun setFilter(filter: FavoriteTypeFilter) {
        _state.update { it.copy(filter = filter) }
    }

    fun setSort(sort: FavoriteSort) {
        if (_state.value.sort == sort) return
        _state.update { it.copy(sort = sort) }
        load()
    }
}
