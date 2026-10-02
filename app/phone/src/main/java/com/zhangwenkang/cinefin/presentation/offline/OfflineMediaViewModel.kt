package com.zhangwenkang.cinefin.presentation.offline

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zhangwenkang.cinefin.music.data.MusicSong
import com.zhangwenkang.cinefin.music.data.MusicTrackResolver
import com.zhangwenkang.cinefin.player.core.domain.models.MusicQueue
import com.zhangwenkang.cinefin.player.core.domain.models.QueueSource
import com.zhangwenkang.cinefin.player.local.domain.MusicPlaybackController
import com.zhangwenkang.cinefin.settings.domain.AppPreferences
import com.zhangwenkang.cinefin.utils.OfflineMediaEntry
import com.zhangwenkang.cinefin.utils.OfflineMediaEntryKind
import com.zhangwenkang.cinefin.utils.OfflineMediaRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * W36 离线媒体库状态机：读取本机已下载媒体（视频 / 音乐 / 书籍）、切换「允许离线模式观看」开关， 并在离线媒体库里直接起播音乐队列（本地文件）。
 *
 * 视频播放与书籍阅读由 UI 侧直接触发（Intent PlayerActivity / ReaderActivity），本 ViewModel 不持有 Context。
 */
@HiltViewModel
class OfflineMediaViewModel
@Inject
constructor(
    private val offlineMediaRepository: OfflineMediaRepository,
    private val trackResolver: MusicTrackResolver,
    private val playbackController: MusicPlaybackController,
    private val appPreferences: AppPreferences,
) : ViewModel() {

    data class UiState(
        val loading: Boolean = true,
        val entries: List<OfflineMediaEntry> = emptyList(),
        /** 管理视图：显示被关闭「允许离线模式观看」的条目（默认隐藏）。 */
        val showHidden: Boolean = false,
        /** W36 预留（W37 内容）：本地媒体库入口是否显示。 */
        val localLibraryVisible: Boolean = false,
        val message: String? = null,
    ) {
        val visibleEntries: List<OfflineMediaEntry>
            get() = OfflineMediaVisibility.visibleEntries(entries, showHidden)

        val videoCount: Int
            get() = visibleEntries.count { it.kind == OfflineMediaEntryKind.VIDEO }

        val musicCount: Int
            get() = visibleEntries.count { it.kind == OfflineMediaEntryKind.MUSIC }

        val bookCount: Int
            get() = visibleEntries.count { it.kind == OfflineMediaEntryKind.BOOK }

        val hasAnyVisible: Boolean
            get() = visibleEntries.isNotEmpty()
    }

    private val _state = MutableStateFlow(UiState())
    val state = _state.asStateFlow()

    private var loaded = false

    fun load(force: Boolean = false) {
        if (loaded && !force) return
        loaded = true
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            val entries = runCatching {
                offlineMediaRepository.listEntries()
            }
                .onFailure { Timber.w(it, "读取离线媒体库失败") }
                .getOrElse { emptyList() }
            _state.update {
                it.copy(
                    loading = false,
                    entries = entries,
                    localLibraryVisible =
                        appPreferences.getValue(appPreferences.localLibraryVisible),
                )
            }
        }
    }

    fun setShowHidden(show: Boolean) {
        _state.update { it.copy(showHidden = show) }
    }

    /** W36 预留：本地媒体库入口可见性（W37 接入真实内容）。 */
    fun setLocalLibraryVisible(visible: Boolean) {
        appPreferences.setValue(appPreferences.localLibraryVisible, visible)
        _state.update { it.copy(localLibraryVisible = visible) }
    }

    fun consumeMessage() {
        _state.update { it.copy(message = null) }
    }

    /** 切换「允许离线模式观看」，成功后刷新列表（关闭后条目从默认视图消失）。 */
    fun setAllowOffline(itemId: UUID, allow: Boolean) {
        viewModelScope.launch {
            runCatching { offlineMediaRepository.setAllowOffline(itemId, allow) }
                .onFailure {
                    Timber.w(it, "切换离线开关失败")
                    _state.update { it.copy(message = "切换离线开关失败，请重试") }
                }
            val entries = runCatching {
                offlineMediaRepository.listEntries()
            }
                .getOrElse { emptyList() }
            _state.update { it.copy(entries = entries) }
        }
    }

    /** 批量切换（节目 / 专辑容器开关）。 */
    fun setAllowOffline(itemIds: List<UUID>, allow: Boolean) {
        viewModelScope.launch {
            itemIds.forEach { itemId ->
                runCatching { offlineMediaRepository.setAllowOffline(itemId, allow) }
                    .onFailure { Timber.w(it, "切换离线开关失败：$itemId") }
            }
            val entries = runCatching {
                offlineMediaRepository.listEntries()
            }
                .getOrElse { emptyList() }
            _state.update { it.copy(entries = entries) }
        }
    }

    /** 离线媒体库直接起播音乐：把曲目所在专辑的可见曲目整份入队（本地文件解析，无网络）， 当前曲目 = 被点曲目。 */
    fun playMusic(entry: OfflineMediaEntry) {
        viewModelScope.launch {
            val albumKey = entry.albumName ?: UNKNOWN_ALBUM
            val albumEntries =
                _state.value.visibleEntries
                    .filter {
                        it.kind == OfflineMediaEntryKind.MUSIC &&
                            (it.albumName ?: UNKNOWN_ALBUM) == albumKey
                    }
                    .sortedWith(compareBy({ it.trackIndex }, { it.name }))
                    .ifEmpty { listOf(entry) }
            val resolved = albumEntries.mapNotNull { item ->
                runCatching { trackResolver.toPlayerItem(item.toMusicSong()) }
                    .onFailure { Timber.w(it, "离线曲目解析失败：${item.name}") }
                    .getOrNull()
            }
            if (resolved.isEmpty()) {
                _state.update { it.copy(message = "无法播放「${entry.name}」：本地文件不可用") }
                return@launch
            }
            val startIndex =
                albumEntries
                    .indexOfFirst { it.itemId == entry.itemId }
                    .coerceAtLeast(0)
                    .coerceAtMost(resolved.size - 1)
            playbackController.setQueue(
                MusicQueue(
                    items = resolved,
                    currentIndex = startIndex,
                    source = QueueSource.ALBUM,
                    sourceId = entry.albumName,
                ),
                startIndex = startIndex,
            )
        }
    }

    fun playMusic(itemId: UUID) {
        val entry = _state.value.entries.firstOrNull { it.itemId == itemId } ?: return
        playMusic(entry)
    }

    private companion object {
        const val UNKNOWN_ALBUM = "未分类"
    }
}

/** 离线媒体条目 → 音乐域模型（播放链路的输入）。 */
internal fun OfflineMediaEntry.toMusicSong(): MusicSong =
    MusicSong(
        itemId = itemId,
        name = name,
        albumName = albumName ?: "未分类",
        artist = artist,
        indexNumber = trackIndex.takeIf { it > 0 },
        runtimeTicks = 0,
        imageUri = null,
    )
