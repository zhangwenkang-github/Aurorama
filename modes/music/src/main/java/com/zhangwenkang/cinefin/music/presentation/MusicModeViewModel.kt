package com.zhangwenkang.cinefin.music.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zhangwenkang.cinefin.music.data.MusicAlbum
import com.zhangwenkang.cinefin.music.data.MusicArtist
import com.zhangwenkang.cinefin.music.data.MusicPlaylist
import com.zhangwenkang.cinefin.music.data.MusicRepository
import com.zhangwenkang.cinefin.music.data.MusicSong
import com.zhangwenkang.cinefin.music.data.MusicTrackResolver
import com.zhangwenkang.cinefin.player.core.domain.models.MusicQueue
import com.zhangwenkang.cinefin.player.core.domain.models.QueueSource
import com.zhangwenkang.cinefin.player.local.domain.MusicPlaybackController
import com.zhangwenkang.cinefin.player.local.domain.MusicPlaybackStateSource
import com.zhangwenkang.cinefin.player.local.domain.MusicQueueEditor
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 音乐模式 ViewModel（W1 R2 最小闭环，W2 R2 扩到四维浏览 + 队列操作）。
 *
 * 负责：曲库浏览（专辑 / 艺术家 / 歌曲 / 歌单）、"点歌 → 整份列表入队起播"、 队列操作（拖拽排序 / 下一首播放 / 点队列跳转 / 移除）。播放本身完全交给
 * [MusicPlaybackController]，UI 不接触播放器 API。
 */
@HiltViewModel
class MusicModeViewModel
@Inject
constructor(
    private val repository: MusicRepository,
    private val trackResolver: MusicTrackResolver,
    private val playbackController: MusicPlaybackController,
    private val queueEditor: MusicQueueEditor,
    playbackStateSource: MusicPlaybackStateSource,
) : ViewModel() {

    data class UiState(
        val loading: Boolean = true,
        val tab: MusicTab = MusicTab.ALBUMS,
        val albums: List<MusicAlbum> = emptyList(),
        val artists: List<MusicArtist> = emptyList(),
        val songs: List<MusicSong> = emptyList(),
        val playlists: List<MusicPlaylist> = emptyList(),
        val detail: MusicDetail? = null,
        val errorMessage: String? = null,
    ) {
        /** 当前详情页的曲目；无详情时为空。 */
        val detailSongs: List<MusicSong>
            get() = detail?.songs ?: emptyList()
    }

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    /** 正在播放的队列（当前曲目 / 播放模式 / 顺序），底栏与队列面板直接消费。 */
    val queue: StateFlow<MusicQueue?> = playbackController.queue

    /** 是否正在播放（底栏按钮图标用）。 */
    val isPlaying: StateFlow<Boolean> = playbackStateSource.isPlaying

    init {
        refresh()
    }

    /** 重新加载曲库与歌单（一次曲目请求 + 一次歌单请求）。 */
    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true, errorMessage = null) }
            runCatching {
                val library = repository.getLibrary()
                library to repository.getPlaylists()
            }
                .onSuccess { (library, playlists) ->
                    _uiState.update {
                        it.copy(
                            loading = false,
                            albums = library.albums,
                            artists = library.artists,
                            songs = library.songs,
                            playlists = playlists,
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(loading = false, errorMessage = error.message ?: "曲库加载失败")
                    }
                }
        }
    }

    fun selectTab(tab: MusicTab) {
        _uiState.update { it.copy(tab = tab, detail = null) }
    }

    fun openAlbum(album: MusicAlbum) {
        _uiState.update { it.copy(detail = MusicDetail.Album(album), errorMessage = null) }
    }

    fun openArtist(artist: MusicArtist) {
        _uiState.update { it.copy(detail = MusicDetail.Artist(artist), errorMessage = null) }
    }

    fun openPlaylist(playlist: MusicPlaylist) {
        _uiState.update {
            it.copy(detail = MusicDetail.Playlist(playlist, emptyList(), loading = true))
        }
        viewModelScope.launch {
            runCatching { repository.getPlaylistSongs(playlist.id) }
                .onSuccess { songs ->
                    _uiState.update { state ->
                        val detail = state.detail
                        if (detail is MusicDetail.Playlist && detail.playlist.id == playlist.id) {
                            state.copy(
                                detail = MusicDetail.Playlist(playlist, songs, loading = false)
                            )
                        } else {
                            state
                        }
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            detail = null,
                            errorMessage = error.message ?: "歌单加载失败",
                        )
                    }
                }
        }
    }

    fun closeDetail() {
        _uiState.update { it.copy(detail = null, errorMessage = null) }
    }

    /** 点击歌曲：以当前列表（专辑 / 艺术家 / 歌单 / 全部歌曲）整份入队，当前曲目 = 点击的那首。 */
    fun playSong(song: MusicSong) {
        val state = _uiState.value
        val detail =
            state.detail?.takeIf { detail -> detail.songs.any { it.itemId == song.itemId } }
        val songs = detail?.songs ?: state.songs
        val startIndex = songs.indexOfFirst { it.itemId == song.itemId }
        if (startIndex < 0) return
        val source = detail?.source ?: QueueSource.MANUAL
        val sourceId = detail?.sourceId
        viewModelScope.launch {
            runCatching {
                val items = trackResolver.toPlayerItems(songs)
                playbackController.setQueue(
                    MusicQueue(
                        items = items,
                        currentIndex = startIndex,
                        source = source,
                        sourceId = sourceId,
                    ),
                    startIndex = startIndex,
                )
            }
                .onFailure { error ->
                    _uiState.update { it.copy(errorMessage = error.message ?: "播放失败") }
                }
        }
    }

    /** 下一首播放（MU-3）：解析出可播放条目后插到当前曲目之后。 */
    fun playNext(song: MusicSong) {
        viewModelScope.launch {
            runCatching { trackResolver.toPlayerItems(listOf(song)).first() }
                .onSuccess { item -> playbackController.insertNext(item) }
                .onFailure { error ->
                    _uiState.update { it.copy(errorMessage = error.message ?: "添加到队列失败") }
                }
        }
    }

    fun moveQueueItem(fromIndex: Int, toIndex: Int) = playbackController.move(fromIndex, toIndex)

    fun jumpToQueueItem(index: Int) = queueEditor.jumpTo(index)

    fun removeQueueItem(index: Int) = queueEditor.removeAt(index)

    fun togglePlayPause() = playbackController.playPause()

    fun skipToNext() = playbackController.next()

    fun skipToPrevious() = playbackController.previous()

    fun dismissError() {
        _uiState.update { it.copy(errorMessage = null) }
    }
}
