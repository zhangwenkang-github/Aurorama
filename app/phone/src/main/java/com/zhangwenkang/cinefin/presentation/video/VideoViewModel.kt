package com.zhangwenkang.cinefin.presentation.video

import android.content.SharedPreferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.zhangwenkang.cinefin.models.FindroidCollection
import com.zhangwenkang.cinefin.models.FindroidItem
import com.zhangwenkang.cinefin.repository.JellyfinRepository
import com.zhangwenkang.cinefin.settings.domain.AppPreferences
import com.zhangwenkang.cinefin.settings.domain.models.VideoDisplayMode
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import javax.inject.Provider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * 视频模式页（W53）状态。
 *
 * - [libraries]：服务器上的视频库（movies + tvshows），顺序与服务器返回一致；
 * - [loaded]：库列表是否成功加载过一次（false 时显示加载骨架，而不是"没有视频库"空态）；
 * - [displayMode]：库卡列表 / 聚合列表，来自「客户端设置 → 媒体库 → 视频显示方式」；
 * - [aggregateItems]：聚合列表分页流；库列表为空时为 [PagingData.empty]。
 */
data class VideoState(
    val libraries: List<FindroidCollection> = emptyList(),
    val isLoading: Boolean = false,
    val loaded: Boolean = false,
    val error: Exception? = null,
    val displayMode: VideoDisplayMode = VideoDisplayMode.defaultValue,
    val aggregateItems: Flow<PagingData<FindroidItem>> = flowOf(PagingData.empty()),
    /**
     * 临时库视图（W53 追加）：非空 = 侧栏点进来的某个视频库——顶栏显示「库名 + 类型 + 项目数」、 内容直显该库条目网格；返回键 / 「返回默认 ×」回默认视频页。不写偏好。
     */
    val temporaryLibrary: FindroidCollection? = null,
)

/**
 * 视频模式页数据源：解析视频库 + 维护聚合列表分页流。
 *
 * 显示方式是「客户端设置」里的全局偏好，切换后不重启 Activity——挂 SharedPreferences 监听即时生效（与侧栏开关同一机制）。
 */
@HiltViewModel
class VideoViewModel
@Inject
constructor(
    /**
     * 离线开关不重启 Activity（W6-R6N），所以这里按需取仓库：每次 load() 都按**当前**偏好解析 在线 / 离线实现 （踩坑 33）——离线模式下
     * `getLibraries()` 返回空列表，视频页给空态，而不是继续拉服务器内容。
     */
    private val repositoryProvider: Provider<JellyfinRepository>,
    private val appPreferences: AppPreferences,
) : ViewModel() {
    private val _state = MutableStateFlow(VideoState())
    val state = _state.asStateFlow()

    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == appPreferences.uiVideoDisplayMode.backendName) {
            _state.value = _state.value.copy(displayMode = readDisplayMode())
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

    fun load(temporaryLibraryId: String? = null) {
        viewModelScope.launch {
            val repository = repositoryProvider.get()
            _state.value =
                _state.value.copy(
                    isLoading = true,
                    error = null,
                    displayMode = readDisplayMode(),
                )
            runCatching { pickVideoLibraries(repository.getLibraries()) }
                .onSuccess { libraries ->
                    val temporaryLibrary = temporaryLibraryId?.let { id ->
                        libraries.firstOrNull { it.id.toString() == id }
                    }
                    // 临时库视图只加载该库；库在服务器上找不到（被删 / 重建）时回落默认视图。
                    val visibleLibraries = temporaryLibrary?.let { listOf(it) } ?: libraries
                    _state.value =
                        _state.value.copy(
                            libraries = visibleLibraries,
                            temporaryLibrary = temporaryLibrary,
                            loaded = true,
                            isLoading = false,
                            error = null,
                            aggregateItems = aggregateFlow(repository, visibleLibraries),
                        )
                }
                .onFailure { throwable ->
                    Timber.w(throwable, "读取视频库失败")
                    // 拉取失败时保留上一次成功加载的库列表，只提示错误（不清空页面）。
                    _state.value =
                        _state.value.copy(
                            isLoading = false,
                            error = throwable.toExceptionOrNull(),
                        )
                }
        }
    }

    private fun aggregateFlow(
        repository: JellyfinRepository,
        libraries: List<FindroidCollection>,
    ): Flow<PagingData<FindroidItem>> {
        if (libraries.isEmpty()) return flowOf(PagingData.empty())
        return Pager(
                config = PagingConfig(pageSize = AGGREGATE_PAGE_SIZE, enablePlaceholders = false),
                pagingSourceFactory = {
                    VideoAggregatePagingSource(
                        repository = repository,
                        libraryIds = libraries.map { it.id },
                    )
                },
            )
            .flow
            .cachedIn(viewModelScope)
    }

    private fun readDisplayMode(): VideoDisplayMode =
        VideoDisplayMode.fromString(appPreferences.getValue(appPreferences.uiVideoDisplayMode))

    private companion object {
        const val AGGREGATE_PAGE_SIZE = 30
    }
}

private fun Throwable.toExceptionOrNull(): Exception = this as? Exception ?: Exception(this)
