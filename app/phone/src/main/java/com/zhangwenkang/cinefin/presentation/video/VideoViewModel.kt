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
import com.zhangwenkang.cinefin.player.local.domain.SleepTimerController
import com.zhangwenkang.cinefin.presentation.utils.storedLibraryIdValue
import com.zhangwenkang.cinefin.repository.JellyfinRepository
import com.zhangwenkang.cinefin.repository.MetadataCacheRules
import com.zhangwenkang.cinefin.settings.domain.AppPreferences
import com.zhangwenkang.cinefin.settings.domain.models.VideoDisplayMode
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
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
 * - [allLibraries]：服务器上的全部视频库（movies + tvshows），顺序与服务器返回一致——「库选择」菜单的来源；
 * - [libraries]：当前显示（过滤后）的库：库卡网格与聚合流都用它；[selectedLibraryId] 非空时它是单元素列表；
 * - [loaded]：库列表是否成功加载过一次（false 时显示加载骨架，而不是"没有视频库"空态）；
 * - [displayMode]：库卡列表 / 聚合列表，来自「客户端设置 → 媒体库 → 视频显示方式」；
 * - [aggregateItems]：聚合列表分页流；库列表为空时为 [PagingData.empty]。
 */
data class VideoState(
    val allLibraries: List<FindroidCollection> = emptyList(),
    val libraries: List<FindroidCollection> = emptyList(),
    /**
     * 顶栏「库选择」（W54-C）：null = 全部库；服务器上找不到该库时同样回落 null。
     *
     * D-F7：库卡模式下恒为 null（选中具体库 = 直达内容）；聚合模式下 = 所选库。
     */
    val selectedLibraryId: UUID? = null,
    val isLoading: Boolean = false,
    val loaded: Boolean = false,
    val error: Exception? = null,
    val displayMode: VideoDisplayMode = VideoDisplayMode.defaultValue,
    val aggregateItems: Flow<PagingData<FindroidItem>> = flowOf(PagingData.empty()),
    /** W69：最近一次成功加载库列表的时间（epoch ms）；TTL 内重进页面不重建聚合分页流。 */
    val loadedAtMs: Long = 0L,
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
    /** W55 睡眠定时统一：视频页顶栏入口与播放器 / 音乐共享同一状态源。 */
    private val sleepTimerController: SleepTimerController,
) : ViewModel() {
    private val _state = MutableStateFlow(VideoState())
    val state = _state.asStateFlow()

    /** W55 睡眠定时状态（视频页顶栏图标激活态 + 对话框）。 */
    val sleepTimerState = sleepTimerController.state

    /** W55：选择睡眠定时分钟；null = 取消。 */
    fun selectSleepTimer(minutes: Int?) = sleepTimerController.select(minutes)

    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            // D-F7：显示方式变化同样要重算可见库（聚合 ↔ 库卡的选择语义不同）。
            appPreferences.uiVideoDisplayMode.backendName,
            appPreferences.uiVideoLibraryId.backendName -> applyCurrentSelection()
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

    /**
     * 加载 / 复用视频库与聚合列表。
     *
     * W69（缓存优先 + 静默刷新）：库列表与聚合分页流都留在 ViewModel 内，TTL（[MetadataCacheRules.DEFAULT_TTL_MS]）
     * 内再次进入页面（例如从剧集 / 季详情返回）**不重建分页流**——`LazyPagingItems` 的 `cachedIn` 数据原样复用， 网格不闪空、骨架不盖海报；[force]
     * = 用户主动刷新（重试 / 下拉）时才失效缓存并重取。
     */
    fun load(temporaryLibraryId: String? = null, force: Boolean = false) {
        val current = _state.value
        val sameTemporaryLibrary = current.temporaryLibrary?.id?.toString() == temporaryLibraryId
        if (
            !force &&
                current.loaded &&
                sameTemporaryLibrary &&
                MetadataCacheRules.isFresh(current.loadedAtMs, System.currentTimeMillis())
        ) {
            return
        }
        viewModelScope.launch {
            val repository = repositoryProvider.get()
            if (force) repository.invalidateMetadataCache()
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
                    val selection =
                        resolveVideoLibrariesForMode(
                            readDisplayMode(),
                            libraries,
                            readStoredLibraryId(),
                        )
                    // 临时库视图只加载该库；库在服务器上找不到（被删 / 重建）时回落默认视图。
                    val visibleLibraries = temporaryLibrary?.let { listOf(it) } ?: selection.visible
                    _state.value =
                        _state.value.copy(
                            allLibraries = libraries,
                            libraries = visibleLibraries,
                            selectedLibraryId = selection.selectedId,
                            temporaryLibrary = temporaryLibrary,
                            loaded = true,
                            isLoading = false,
                            error = null,
                            aggregateItems = aggregateFlow(repository, visibleLibraries),
                            loadedAtMs = System.currentTimeMillis(),
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

    /**
     * 顶栏「库选择」（W54-C）：null = 全部库。先落盘（跨页面 / 重启保留）再即时生效。
     *
     * D-F7：库卡模式下选中具体库改走「直达该库内容」的导航回调，不再调用本方法；聚合模式保持原语义。
     */
    fun selectLibrary(libraryId: UUID?) {
        // 写偏好会触发监听器即时重算（与「视频显示方式」同一机制）；写入同样的值不会触发回调，
        // 页面本来也没有变化。
        appPreferences.setValue(appPreferences.uiVideoLibraryId, storedLibraryIdValue(libraryId))
    }

    /** 偏好变化（显示方式 / 库选择）共用的解析（不重新请求服务器）：更新可见库与聚合流。 */
    private fun applyCurrentSelection() {
        val current = _state.value
        val displayMode = readDisplayMode()
        val selection =
            resolveVideoLibrariesForMode(
                displayMode,
                current.allLibraries,
                readStoredLibraryId(),
            )
        // 临时库视图只显示那一个库；选择仍照常解析（退出临时视图后按选择显示）。
        if (current.temporaryLibrary != null) {
            _state.value =
                current.copy(
                    displayMode = displayMode,
                    selectedLibraryId = selection.selectedId,
                )
            return
        }
        _state.value =
            current.copy(
                displayMode = displayMode,
                selectedLibraryId = selection.selectedId,
                libraries = selection.visible,
                aggregateItems =
                    if (selection.visible.isEmpty()) flowOf(PagingData.empty())
                    else aggregateFlow(repositoryProvider.get(), selection.visible),
            )
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

    private fun readStoredLibraryId(): String? =
        appPreferences.getValue(appPreferences.uiVideoLibraryId)

    private companion object {
        const val AGGREGATE_PAGE_SIZE = 30
    }
}

private fun Throwable.toExceptionOrNull(): Exception = this as? Exception ?: Exception(this)
