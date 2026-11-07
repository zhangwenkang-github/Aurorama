package com.zhangwenkang.cinefin.film.presentation.search

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zhangwenkang.cinefin.local.LocalLibraryRepository
import com.zhangwenkang.cinefin.repository.SearchScope
import com.zhangwenkang.cinefin.repository.catalog.CatalogReadPort
import com.zhangwenkang.cinefin.repository.catalog.CatalogScope
import com.zhangwenkang.cinefin.repository.catalog.CatalogSearchQuery
import com.zhangwenkang.cinefin.utils.Downloader
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@HiltViewModel
class SearchViewModel
@Inject
constructor(
    private val catalogReadPort: CatalogReadPort,
    /** W43：本地媒体库并入搜索（服务器 + 本地两来源并行查询）。 */
    private val localLibraryRepository: LocalLibraryRepository,
    private val downloader: Downloader,
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
    private var completedScope: CatalogScope? = null
    private var completedScopeResolved = false
    private val catalogGuard = SearchCatalogGuard()

    private fun search(
        query: String,
        scope: SearchScope = _state.value.scope,
        force: Boolean = false,
    ) {
        val request = SearchRequest(query = query, scope = scope)
        currentJob?.cancel()
        catalogGuard.invalidate()
        currentJob = viewModelScope.launch {
            if (query.isBlank()) {
                completedRequest = null
                completedScope = null
                completedScopeResolved = false
                _state.emit(SearchState(loading = false, scope = scope))
                return@launch
            }
            val requestGeneration = catalogGuard.beginPending()
            try {
                val requestedScope = catalogReadPort.currentScope()
                val currentAtStart = catalogReadPort.currentScope()
                if (catalogGuard.start(requestGeneration, requestedScope, currentAtStart).not()) {
                    if (catalogGuard.isCurrent(requestGeneration)) {
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
                    return@launch
                }
                if (!catalogGuard.isScopeStarted(requestGeneration)) {
                    _state.emit(
                        SearchState(
                            loading = false,
                            query = query,
                            scope = scope,
                            serverFailed = true,
                            localFailed = true,
                        )
                    )
                    return@launch
                }
                val frozenScope =
                    requestedScope
                        ?: run {
                            _state.emit(
                                SearchState(
                                    loading = false,
                                    query = query,
                                    scope = scope,
                                    serverFailed = true,
                                    localFailed = true,
                                )
                            )
                            return@launch
                        }
                if (
                    !force &&
                        request == completedRequest &&
                        completedScopeResolved &&
                        frozenScope == completedScope
                ) {
                    return@launch
                }

                // 新 query / scope 先清掉上一轮结果，避免旧域结果在新域短暂泄漏。
                _state.emit(SearchState(loading = true, query = query, scope = scope))
                val downloadRecords =
                    withContext(Dispatchers.IO) {
                        runCatching { downloader.downloadedMediaRecords() }
                            .getOrDefault(emptyList())
                    }
                val outcome =
                    executeSearch(
                        request = request,
                        serverSearch = {
                            catalogReadPort.search(
                                CatalogSearchQuery(
                                    term = it.query,
                                    scope = it.scope,
                                    frozenScope = frozenScope,
                                )
                            )
                        },
                        localSearch = { localLibraryRepository.search(it.query, it.scope) },
                        scope = frozenScope,
                        downloads = downloadRecords,
                    )
                if (!catalogGuard.isCurrent(requestGeneration)) return@launch
                val currentScope = catalogReadPort.currentScope()
                if (
                    !catalogGuard.accepts(
                        requestGeneration,
                        outcome.serverSnapshot,
                        currentScope,
                    )
                ) {
                    _state.emit(
                        SearchState(
                            loading = false,
                            query = query,
                            scope = scope,
                            serverFailed = true,
                            localFailed = true,
                        )
                    )
                    return@launch
                }
                _state.emit(
                    SearchState(
                        results = outcome.results,
                        loading = false,
                        query = query,
                        scope = scope,
                        serverFailed = outcome.serverFailed,
                        localFailed = outcome.localFailed,
                    )
                )
                completedRequest = request
                completedScope = frozenScope
                completedScopeResolved = true
            } catch (_: CancellationException) {} catch (_: Exception) {
                if (!catalogGuard.isCurrent(requestGeneration)) return@launch
                if (!catalogGuard.isScopeStarted(requestGeneration)) {
                    _state.emit(
                        SearchState(
                            loading = false,
                            query = query,
                            scope = scope,
                            serverFailed = true,
                            localFailed = true,
                        )
                    )
                    return@launch
                }
                val currentScope = runCatching { catalogReadPort.currentScope() }.getOrNull()
                if (!catalogGuard.accepts(requestGeneration, null, currentScope)) {
                    _state.emit(
                        SearchState(
                            loading = false,
                            query = query,
                            scope = scope,
                            serverFailed = true,
                            localFailed = true,
                        )
                    )
                    return@launch
                }
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
                catalogGuard.invalidate()
                completedRequest = null
                completedScope = null
                completedScopeResolved = false
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
                catalogGuard.invalidate()
                completedRequest = null
                completedScope = null
                completedScopeResolved = false
                savedStateHandle[SEARCH_SCOPE_KEY] = action.scope.name
                _state.value =
                    _state.value.copy(
                        scope = action.scope,
                        results = emptyList(),
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
