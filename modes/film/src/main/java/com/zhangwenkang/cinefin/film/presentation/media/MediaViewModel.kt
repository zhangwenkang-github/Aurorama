package com.zhangwenkang.cinefin.film.presentation.media

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zhangwenkang.cinefin.repository.JellyfinRepository
import com.zhangwenkang.cinefin.repository.MediaCatalogScope
import com.zhangwenkang.cinefin.repository.MediaCatalogSession
import com.zhangwenkang.cinefin.repository.MetadataCacheRules
import com.zhangwenkang.cinefin.settings.domain.AppPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import javax.inject.Provider
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class MediaViewModel
@Inject
constructor(
    repositoryProvider: Provider<JellyfinRepository>,
    appPreferences: AppPreferences,
) : ViewModel() {
    private val catalogSession = MediaCatalogSession(repositoryProvider, appPreferences)
    private var activeCatalogScope: MediaCatalogScope? = catalogSession.currentScope()
    private val _state = MutableStateFlow(MediaState(catalogScope = activeCatalogScope))
    val state = _state.asStateFlow()

    private var loadJob: Job? = null
    private var loadedAtMs: Long = 0L
    private var loadSerial = 0L

    init {
        viewModelScope.launch {
            catalogSession.observeChanges().collect {
                val nextScope = catalogSession.currentScope()
                if (nextScope == activeCatalogScope) return@collect
                activeCatalogScope = nextScope
                loadSerial += 1
                loadJob?.cancel()
                loadedAtMs = 0L
                _state.value =
                    MediaState(
                        catalogScope = nextScope,
                        isLoading = nextScope != null,
                        error = null,
                    )
                if (nextScope != null) loadData()
            }
        }
    }

    /**
     * 加载媒体库总览。
     *
     * W83 #31：沿用 W69「缓存优先 + 静默刷新」——已经有库卡时重进页面不再切整页 loading，[force] 只走 PTR 指示并保留旧内容；
     * 下拉刷新会先失效会话元数据缓存，确保拿到服务器新值。
     */
    fun loadData(force: Boolean = false) {
        val catalogScope = catalogSession.currentScope()
        var current = _state.value
        val sameCatalogScope = current.catalogScope == catalogScope
        if (catalogScope == null) {
            loadJob?.cancel()
            loadSerial += 1
            _state.value = MediaState(catalogScope = null)
            return
        }
        if (!sameCatalogScope) {
            loadJob?.cancel()
            loadSerial += 1
            loadedAtMs = 0L
            _state.value =
                MediaState(
                    catalogScope = catalogScope,
                    isLoading = true,
                )
            current = _state.value
        }
        if (current.refreshing) return
        if (
            sameCatalogScope &&
                !force &&
                current.libraries.isNotEmpty() &&
                MetadataCacheRules.isFresh(loadedAtMs, System.currentTimeMillis())
        ) {
            return
        }
        val request = ++loadSerial
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val repository = catalogSession.repository()
            if (force) {
                repository.invalidateMetadataCache()
                if (isCurrent(request, catalogScope)) {
                    _state.update { it.copy(refreshing = true, error = null) }
                }
            } else {
                if (isCurrent(request, catalogScope)) {
                    _state.update { it.copy(isLoading = it.libraries.isEmpty(), error = null) }
                }
            }
            try {
                val libraries = repository.getLibraries()
                if (!isCurrent(request, catalogScope)) return@launch
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
                if (!isCurrent(request, catalogScope)) return@launch
                // 失败保留上一次成功的库列表，只提示错误（下拉刷新不把页面清空）。
                _state.update { it.copy(isLoading = false, refreshing = false, error = e) }
            }
        }
    }

    private fun isCurrent(request: Long, scope: MediaCatalogScope): Boolean =
        request == loadSerial && catalogSession.isCurrent(scope)

    fun onAction(action: MediaAction) {
        when (action) {
            is MediaAction.OnRetryClick -> {
                loadData(force = true)
            }
            MediaAction.Refresh -> loadData(force = true)
            else -> Unit
        }
    }
}
