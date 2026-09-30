package com.zhangwenkang.cinefin.music.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zhangwenkang.cinefin.music.data.MusicAlbum
import com.zhangwenkang.cinefin.music.data.MusicRepository
import com.zhangwenkang.cinefin.music.data.MusicSong
import com.zhangwenkang.cinefin.music.data.MusicTrackResolver
import com.zhangwenkang.cinefin.player.core.domain.models.MusicQueue
import com.zhangwenkang.cinefin.player.core.domain.models.QueueSource
import com.zhangwenkang.cinefin.player.local.domain.MusicPlaybackController
import com.zhangwenkang.cinefin.player.local.domain.MusicPlaybackStateSource
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 音乐模式 ViewModel（W1 R2 最小闭环）。
 *
 * 负责两件事：曲库浏览（专辑列表 → 专辑曲目）与"点击歌曲 → 整张专辑起播"。 播放本身完全交给 [MusicPlaybackController]（UI 不接触播放器 API）。
 */
@HiltViewModel
class MusicModeViewModel
@Inject
constructor(
    private val repository: MusicRepository,
    private val trackResolver: MusicTrackResolver,
    private val playbackController: MusicPlaybackController,
    playbackStateSource: MusicPlaybackStateSource,
) : ViewModel() {

    data class UiState(
        val loading: Boolean = false,
        val albums: List<MusicAlbum> = emptyList(),
        /** 非空 = 已进入专辑详情（曲目列表） */
        val openedAlbum: MusicAlbum? = null,
        val errorMessage: String? = null,
    ) {
        val songs: List<MusicSong>
            get() = openedAlbum?.songs ?: emptyList()
    }

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    /** 正在播放的队列（当前曲目 / 播放模式 / 顺序），底栏直接消费。 */
    val queue: StateFlow<MusicQueue?> = playbackController.queue

    /** 是否正在播放（底栏按钮图标用）。 */
    val isPlaying: StateFlow<Boolean> = playbackStateSource.isPlaying

    init {
        refreshAlbums()
    }

    fun refreshAlbums() {
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true, errorMessage = null) }
            runCatching { repository.getAlbums() }
                .onSuccess { albums ->
                    _uiState.update { it.copy(loading = false, albums = albums) }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(loading = false, errorMessage = error.message ?: "专辑加载失败")
                    }
                }
        }
    }

    fun openAlbum(album: MusicAlbum) {
        _uiState.update { it.copy(openedAlbum = album, errorMessage = null) }
    }

    fun closeAlbum() {
        _uiState.update { it.copy(openedAlbum = null, errorMessage = null) }
    }

    /** 点击歌曲：整张专辑作为队列起播，当前曲目 = 点击的那首。 */
    fun playSong(song: MusicSong) {
        val album = _uiState.value.openedAlbum ?: return
        val songs = album.songs
        val startIndex = songs.indexOfFirst { it.itemId == song.itemId }
        if (startIndex < 0) return
        viewModelScope.launch {
            runCatching {
                val items = trackResolver.toPlayerItems(songs)
                playbackController.setQueue(
                    MusicQueue(
                        items = items,
                        currentIndex = startIndex,
                        source = QueueSource.ALBUM,
                        sourceId = album.key,
                    ),
                    startIndex = startIndex,
                )
            }
                .onFailure { error ->
                    _uiState.update { it.copy(errorMessage = error.message ?: "播放失败") }
                }
        }
    }

    fun togglePlayPause() = playbackController.playPause()

    fun playNext() = playbackController.next()

    fun playPrevious() = playbackController.previous()

    fun dismissError() {
        _uiState.update { it.copy(errorMessage = null) }
    }
}
