package com.zhangwenkang.cinefin.film.presentation.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zhangwenkang.cinefin.local.LocalLibraryRepository
import com.zhangwenkang.cinefin.local.LocalSearchHit
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

    private fun search(query: String) {
        currentJob?.cancel()
        currentJob = viewModelScope.launch {
            try {
                if (query.isBlank()) {
                    _state.emit(SearchState(loading = false))
                    return@launch
                }

                _state.emit(_state.value.copy(loading = true))
                val serverDeferred = async { repository.getSearchItems(query) }
                val localDeferred = async { localSearch(query) }

                _state.emit(
                    SearchState(
                        serverItems = serverDeferred.await(),
                        localItems = localDeferred.await(),
                        loading = false,
                    )
                )
            } catch (_: CancellationException) {} catch (e: Exception) {
                Timber.e(e)
                _state.emit(_state.value.copy(loading = false))
            }
        }
    }

    /** 本地维度单独兜底：本地查询失败不拖垮服务器结果（转成空分区）。 */
    private suspend fun localSearch(query: String): List<LocalSearchHit> =
        try {
            localLibraryRepository.search(query)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e)
            emptyList()
        }

    fun onAction(action: SearchAction) {
        when (action) {
            is SearchAction.Search -> {
                search(query = action.query)
            }
            else -> Unit
        }
    }
}
