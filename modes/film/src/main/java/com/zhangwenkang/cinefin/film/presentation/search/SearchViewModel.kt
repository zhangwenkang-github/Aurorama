package com.zhangwenkang.cinefin.film.presentation.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zhangwenkang.cinefin.local.LocalLibraryRepository
import com.zhangwenkang.cinefin.local.LocalSearchHit
import com.zhangwenkang.cinefin.models.FindroidItem
import com.zhangwenkang.cinefin.repository.JellyfinRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

@HiltViewModel
class SearchViewModel
@Inject
constructor(
    private val repository: JellyfinRepository,
    /** W43：本地媒体库并入搜索（服务器 + 本地两来源并行查询）。 */
    private val localLibraryRepository: LocalLibraryRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(SearchState())
    val state = _state.asStateFlow()

    var currentJob: Job? = null

    /** 已完成查询：返回搜索结果页时 `LaunchedEffect(query)` 会再触发一次，这里跳过重复请求。 */
    private var completedQuery: String? = null

    private fun search(query: String, force: Boolean = false) {
        if (!force && query == _state.value.query && _state.value.loading) return
        if (!force && query == completedQuery && currentJob?.isActive != true) return
        currentJob?.cancel()
        currentJob = viewModelScope.launch {
            try {
                if (query.isBlank()) {
                    completedQuery = null
                    _state.emit(SearchState(loading = false))
                    return@launch
                }

                // 新查询先清掉上一轮结果，避免慢请求回来时把两个关键词的结果混在一起。
                _state.emit(SearchState(loading = true, query = query))
                val serverDeferred = async { serverSearch(query) }
                val localDeferred = async { localSearch(query) }
                val serverResult = serverDeferred.await()
                val localResult = localDeferred.await()

                _state.emit(
                    SearchState(
                        serverItems =
                            SearchResultRules.dedupeServer(serverResult.getOrDefault(emptyList())),
                        localItems =
                            SearchResultRules.dedupeLocal(localResult.getOrDefault(emptyList())),
                        loading = false,
                        query = query,
                        serverFailed = serverResult.isFailure || localResult.isFailure,
                    )
                )
                completedQuery = query
            } catch (_: CancellationException) {} catch (e: Exception) {
                Timber.e(e)
                _state.emit(SearchState(loading = false, query = query, serverFailed = true))
            }
        }
    }

    /** 两个来源各自兜底：任一失败只影响自身分区，另一来源仍可展示。 */
    private suspend fun serverSearch(query: String): Result<List<FindroidItem>> =
        try {
            Result.success(repository.getSearchItems(query))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e)
            Result.failure(e)
        }

    private suspend fun localSearch(query: String): Result<List<LocalSearchHit>> =
        try {
            Result.success(localLibraryRepository.search(query))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e)
            Result.failure(e)
        }

    fun onAction(action: SearchAction) {
        when (action) {
            is SearchAction.Search -> {
                search(query = action.query)
            }
            SearchAction.Retry -> {
                search(query = _state.value.query, force = true)
            }
            else -> Unit
        }
    }
}
