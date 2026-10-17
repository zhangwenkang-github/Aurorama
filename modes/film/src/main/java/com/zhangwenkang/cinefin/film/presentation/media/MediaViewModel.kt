package com.zhangwenkang.cinefin.film.presentation.media

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zhangwenkang.cinefin.repository.JellyfinRepository
import com.zhangwenkang.cinefin.repository.MetadataCacheRules
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class MediaViewModel @Inject constructor(private val repository: JellyfinRepository) : ViewModel() {
    private val _state = MutableStateFlow(MediaState())
    val state = _state.asStateFlow()

    private var loadJob: Job? = null
    private var loadedAtMs: Long = 0L

    /**
     * 加载媒体库总览。
     *
     * W83 #31：沿用 W69「缓存优先 + 静默刷新」——已经有库卡时重进页面不再切整页 loading，[force] 只走 PTR 指示并保留旧内容；
     * 下拉刷新会先失效会话元数据缓存，确保拿到服务器新值。
     */
    fun loadData(force: Boolean = false) {
        val current = _state.value
        if (current.refreshing) return
        if (
            !force &&
                current.libraries.isNotEmpty() &&
                MetadataCacheRules.isFresh(loadedAtMs, System.currentTimeMillis())
        ) {
            return
        }
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            if (force) {
                repository.invalidateMetadataCache()
                _state.update { it.copy(refreshing = true, error = null) }
            } else {
                _state.update { it.copy(isLoading = it.libraries.isEmpty(), error = null) }
            }
            try {
                val libraries = repository.getLibraries()
                loadedAtMs = System.currentTimeMillis()
                _state.update {
                    it.copy(
                        libraries = libraries,
                        isLoading = false,
                        refreshing = false,
                        error = null,
                    )
                }
            } catch (e: Exception) {
                // 失败保留上一次成功的库列表，只提示错误（下拉刷新不把页面清空）。
                _state.update { it.copy(isLoading = false, refreshing = false, error = e) }
            }
        }
    }

    fun onAction(action: MediaAction) {
        when (action) {
            is MediaAction.OnRetryClick -> {
                loadData()
            }
            MediaAction.Refresh -> loadData(force = true)
            else -> Unit
        }
    }
}
