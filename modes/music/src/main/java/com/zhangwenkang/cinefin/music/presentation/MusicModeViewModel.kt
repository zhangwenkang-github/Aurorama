package com.zhangwenkang.cinefin.music.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zhangwenkang.cinefin.music.data.MusicAlbum
import com.zhangwenkang.cinefin.music.data.MusicArtist
import com.zhangwenkang.cinefin.music.data.MusicPlaylist
import com.zhangwenkang.cinefin.music.data.MusicRepository
import com.zhangwenkang.cinefin.music.data.MusicSong
import com.zhangwenkang.cinefin.music.data.MusicTrackResolver
import com.zhangwenkang.cinefin.music.data.lyrics.LyricsDisplayLanguage
import com.zhangwenkang.cinefin.music.data.lyrics.LyricsDisplayState
import com.zhangwenkang.cinefin.music.data.lyrics.LyricsDocument
import com.zhangwenkang.cinefin.music.data.lyrics.LyricsPresenter
import com.zhangwenkang.cinefin.music.data.lyrics.LyricsRepository
import com.zhangwenkang.cinefin.music.data.lyrics.LyricsRow
import com.zhangwenkang.cinefin.player.core.domain.models.MusicQueue
import com.zhangwenkang.cinefin.player.core.domain.models.PlayerItem
import com.zhangwenkang.cinefin.player.core.domain.models.QueueSource
import com.zhangwenkang.cinefin.player.local.domain.MusicPlaybackController
import com.zhangwenkang.cinefin.player.local.domain.MusicPlaybackStateSource
import com.zhangwenkang.cinefin.player.local.domain.MusicQueueEditor
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 音乐模式 ViewModel（W1 R2 最小闭环，W2 R2 扩到四维浏览 + 队列操作）。
 *
 * 负责：曲库浏览（专辑 / 艺术家 / 歌曲 / 歌单）、"点歌 → 整份列表入队起播"、 队列操作（拖拽排序 / 下一首播放 / 点队列跳转 / 移除）。播放本身完全交给
 * [MusicPlaybackController]，UI 不接触播放器 API。
 *
 * W3 R2 追加歌词（MU-5）：当前曲目变化 → 拉取 / 解析歌词（外挂 LRC → 服务端 → 本地缓存）， 播放位置 → 当前行索引（滚动同步与高亮）， 语言切换与双语对照只影响显示侧。
 */
@HiltViewModel
class MusicModeViewModel
@Inject
constructor(
    private val repository: MusicRepository,
    private val trackResolver: MusicTrackResolver,
    private val playbackController: MusicPlaybackController,
    private val queueEditor: MusicQueueEditor,
    private val lyricsRepository: LyricsRepository,
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

    /**
     * 歌词面板状态（MU-5）。
     *
     * [languages] 由逐行识别结果聚合（原文恒在末位）；[display] 默认简体中文（[LyricsPresenter.defaultDisplay]）；
     * [activeIndex] 由 [LyricsPresenter.activeIndex] 根据播放位置算出，供高亮与跟随滚动使用。
     */
    data class LyricsUiState(
        val open: Boolean = false,
        val loading: Boolean = false,
        val title: String? = null,
        val document: LyricsDocument? = null,
        val languages: List<LyricsDisplayLanguage> = emptyList(),
        val display: LyricsDisplayState = LyricsDisplayState(),
        val rows: List<LyricsRow> = emptyList(),
        val activeIndex: Int = 0,
        val message: String? = null,
    )

    private val _lyricsState = MutableStateFlow(LyricsUiState())
    val lyricsState: StateFlow<LyricsUiState> = _lyricsState.asStateFlow()

    private var loadedLyricsItemId: UUID? = null
    private var lyricsLoadJob: Job? = null

    /** 正在播放的队列（当前曲目 / 播放模式 / 顺序），底栏与队列面板直接消费。 */
    val queue: StateFlow<MusicQueue?> = playbackController.queue

    /** 是否正在播放（底栏按钮图标用）。 */
    val isPlaying: StateFlow<Boolean> = playbackStateSource.isPlaying

    init {
        refresh()
        observeLyrics()
    }

    /** 当前曲目 → 拉歌词；播放位置 → 当前行。两条流各自独立，互不阻塞。 */
    private fun observeLyrics() {
        viewModelScope.launch {
            playbackController.queue
                .map { it?.currentItem }
                .distinctUntilChanged { old, new -> old?.itemId == new?.itemId }
                .collect(::onCurrentItemChanged)
        }
        viewModelScope.launch { playbackController.positionMs.collect(::syncActiveLyricLine) }
    }

    private fun onCurrentItemChanged(item: PlayerItem?) {
        lyricsLoadJob?.cancel()
        if (item == null) {
            loadedLyricsItemId = null
            _lyricsState.value = LyricsUiState()
            return
        }
        if (loadedLyricsItemId == item.itemId && _lyricsState.value.document != null) return
        loadedLyricsItemId = item.itemId
        val localPath = localMediaPath(item.mediaSourceUri)
        lyricsLoadJob = viewModelScope.launch {
            _lyricsState.update {
                it.copy(
                    loading = true,
                    title = item.name,
                    document = null,
                    languages = emptyList(),
                    rows = emptyList(),
                    activeIndex = 0,
                    message = null,
                )
            }
            val document = runCatching {
                lyricsRepository.getLyrics(item.itemId, localPath)
            }
                .getOrNull()
            if (loadedLyricsItemId != item.itemId) return@launch
            _lyricsState.update { state ->
                if (document == null) {
                    state.copy(
                        loading = false,
                        document = null,
                        languages = emptyList(),
                        rows = emptyList(),
                        activeIndex = 0,
                        message = "该曲目暂无歌词（服务端 / 外挂 LRC 都没有）",
                    )
                } else {
                    val display = LyricsPresenter.defaultDisplay(document)
                    val rows = LyricsPresenter.rows(document, display)
                    state.copy(
                        loading = false,
                        document = document,
                        languages = LyricsPresenter.displayLanguages(document),
                        display = display,
                        rows = rows,
                        activeIndex =
                            LyricsPresenter.activeIndex(rows, playbackController.positionMs.value),
                        message = null,
                    )
                }
            }
        }
    }

    private fun syncActiveLyricLine(positionMs: Long) {
        _lyricsState.update { state ->
            if (state.rows.isEmpty()) {
                state
            } else {
                val index = LyricsPresenter.activeIndex(state.rows, positionMs)
                if (index == state.activeIndex) state else state.copy(activeIndex = index)
            }
        }
    }

    /** 打开歌词面板（曲目在底栏点「词」触发）。 */
    fun openLyrics() {
        _lyricsState.update { it.copy(open = true) }
    }

    fun closeLyrics() {
        _lyricsState.update { it.copy(open = false) }
    }

    /** 语言切换（只改显示侧；默认简体中文）。 */
    fun selectLyricsLanguage(language: LyricsDisplayLanguage) {
        updateLyricsDisplay { it.copy(language = language) }
    }

    fun toggleLyricsBilingual() {
        updateLyricsDisplay { it.copy(bilingual = !it.bilingual) }
    }

    /** 跟随滚动开关；关闭后高亮仍随播放位置变化，只是不自动滚动。 */
    fun toggleLyricsFollow() {
        updateLyricsDisplay { it.copy(follow = !it.follow) }
    }

    /** 点击歌词行：跳到该行时间戳（不改变播放 / 暂停状态）。 */
    fun seekToLyricLine(startMs: Long) = playbackController.seekTo(startMs)

    private fun updateLyricsDisplay(transform: (LyricsDisplayState) -> LyricsDisplayState) {
        _lyricsState.update { state ->
            val display = transform(state.display)
            val document = state.document
            if (document == null) {
                state.copy(display = display)
            } else {
                val rows = LyricsPresenter.rows(document, display)
                state.copy(
                    display = display,
                    rows = rows,
                    activeIndex =
                        LyricsPresenter.activeIndex(rows, playbackController.positionMs.value),
                )
            }
        }
    }

    /** 本地音频路径（外挂 LRC 查找用）；在线 / 转码地址返回 null。 */
    private fun localMediaPath(mediaSourceUri: String?): String? =
        when {
            mediaSourceUri == null -> null
            mediaSourceUri.startsWith("file://") -> mediaSourceUri.removePrefix("file://")
            mediaSourceUri.contains("://") -> null
            else -> mediaSourceUri
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
