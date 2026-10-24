package com.zhangwenkang.cinefin.film.presentation.search

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zhangwenkang.cinefin.local.LocalLibraryRepository
import com.zhangwenkang.cinefin.repository.JellyfinRepository
import com.zhangwenkang.cinefin.repository.SearchScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class SearchViewModel
@Inject
constructor(
    private val repository: JellyfinRepository,
    /** W43：本地媒体库并入搜索（服务器 + 本地两来源并行查询）。 */
    private val localLibraryRepository: LocalLibraryRepository,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val _state =
        MutableStateFlow(
            SearchState(
                query = savedStateHandle.get<String>(SEARCH_QUERY_KEY).orEmpty(),
                scope =
                    SearchScope.fromRouteValue(
                        savedStateHandle.get<String>(SEARCH_ROUTE_SCOPE_KEY)
                    ),
            )
        )
    val state = _state.asStateFlow()

    var currentJob: Job? = null

    /** 已完成请求：返回搜索结果页时 `LaunchedEffect(query)` 会再触发一次，这里跳过重复请求。 */
    private var completedRequest: SearchRequest? = null

    private fun search(
        query: String,
        scope: SearchScope = _state.value.scope,
        force: Boolean = false,
    ) {
        val request = SearchRequest(query = query, scope = scope)
        if (!force && request == completedRequest && currentJob?.isActive != true) return
        currentJob?.cancel()
        currentJob = viewModelScope.launch {
            try {
                if (query.isBlank()) {
                    completedRequest = null
                    _state.emit(SearchState(loading = false, scope = scope))
                    return@launch
                }

                // 新 query / scope 先清掉上一轮结果，避免旧域结果在新域短暂泄漏。
                _state.emit(SearchState(loading = true, query = query, scope = scope))
                val outcome =
                    executeSearch(
                        request = request,
                        serverSearch = { repository.getSearchItems(it.query, it.scope) },
                        localSearch = { localLibraryRepository.search(it.query, it.scope) },
                    )
                _state.emit(
                    SearchState(
                        serverItems = outcome.serverItems,
                        localItems = outcome.localItems,
                        loading = false,
                        query = query,
                        scope = scope,
                        serverFailed = outcome.serverFailed,
                        localFailed = outcome.localFailed,
                    )
                )
                completedRequest = request
            } catch (_: CancellationException) {} catch (_: Exception) {
                _state.emit(
                    SearchState(
                        loading = false,
                        query = query,
                        scope = scope,
                        serverFailed = true,
                        localFailed = true,
                    )
                )
            }
        }
    }

    fun onAction(action: SearchAction) {
        when (action) {
            is SearchAction.UpdateQuery -> {
                if (action.query == _state.value.query) return
                currentJob?.cancel()
                completedRequest = null
                savedStateHandle[SEARCH_QUERY_KEY] = action.query
                _state.value =
                    _state.value.copy(
                        query = action.query,
                        loading = false,
                        serverFailed = false,
                        localFailed = false,
                    )
            }
            is SearchAction.Search -> search(query = action.query, scope = _state.value.scope)
            is SearchAction.ChangeScope -> {
                if (action.scope == _state.value.scope) return
                currentJob?.cancel()
                completedRequest = null
                savedStateHandle[SEARCH_SCOPE_KEY] = action.scope.name
                _state.value =
                    _state.value.copy(
                        scope = action.scope,
                        serverItems = emptyList(),
                        localItems = emptyList(),
                        loading = _state.value.query.isNotBlank(),
                        serverFailed = false,
                        localFailed = false,
                    )
                if (_state.value.query.isNotBlank()) {
                    search(query = _state.value.query, scope = action.scope)
                }
            }
            SearchAction.Retry ->
                search(query = _state.value.query, scope = _state.value.scope, force = true)
            else -> Unit
        }
    }

    private companion object {
        const val SEARCH_QUERY_KEY = "search_query"
        const val SEARCH_SCOPE_KEY = "search_scope"
        const val SEARCH_ROUTE_SCOPE_KEY = "initialScope"
    }
}
