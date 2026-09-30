package com.zhangwenkang.cinefin.music.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zhangwenkang.cinefin.music.data.MusicAlbum
import com.zhangwenkang.cinefin.music.data.MusicArtist
import com.zhangwenkang.cinefin.music.data.MusicLibrary
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
import com.zhangwenkang.cinefin.music.data.musicQueueFillOrder
import com.zhangwenkang.cinefin.player.core.domain.models.MusicQueue
import com.zhangwenkang.cinefin.player.core.domain.models.PlayerItem
import com.zhangwenkang.cinefin.player.core.domain.models.QueueSource
import com.zhangwenkang.cinefin.player.local.domain.MusicPlaybackController
import com.zhangwenkang.cinefin.player.local.domain.MusicPlaybackStateSource
import com.zhangwenkang.cinefin.player.local.domain.MusicQueueEditor
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber

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
        /** 错误标题（错误态面板顶部文案：曲库加载 / 播放 / 歌单加载）。 */
        val errorTitle: String? = null,
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
    private var refreshJob: Job? = null
    private var playJob: Job? = null

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

    /**
     * 重新加载曲库与歌单（一次曲目请求 + 一次歌单请求）。
     *
     * W3-R3b：服务器偶发超时 / 弱网时先**自动重试一次**，两次都失败才进可重试错误态， 避免"间歇性曲库加载失败"直接把空列表丢给用户。
     */
    fun refresh() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            _uiState.update { it.copy(loading = true, errorTitle = null, errorMessage = null) }
            try {
                val (library, playlists) = loadLibraryWithRetry()
                _uiState.update {
                    it.copy(
                        loading = false,
                        albums = library.albums,
                        artists = library.artists,
                        songs = library.songs,
                        playlists = playlists,
                        errorTitle = null,
                        errorMessage = null,
                    )
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                _uiState.update {
                    it.copy(
                        loading = false,
                        errorTitle = "曲库加载失败",
                        errorMessage =
                            error.message?.takeIf { message -> message.isNotBlank() }
                                ?: "无法连接服务器，请检查网络后重试",
                    )
                }
            }
        }
    }

    /** 第一次失败视为偶发（服务端超时 / 弱网），自动重试一次后仍失败才抛给调用方。 */
    private suspend fun loadLibraryWithRetry(): Pair<MusicLibrary, List<MusicPlaylist>> =
        try {
            loadLibraryOnce()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (first: Exception) {
            Timber.w(first, "曲库加载失败，自动重试一次")
            delay(AUTO_RETRY_DELAY_MS)
            loadLibraryOnce()
        }

    private suspend fun loadLibraryOnce(): Pair<MusicLibrary, List<MusicPlaylist>> {
        val library = repository.getLibrary()
        return library to repository.getPlaylists()
    }

    fun selectTab(tab: MusicTab) {
        _uiState.update { it.copy(tab = tab, detail = null) }
    }

    fun openAlbum(album: MusicAlbum) {
        _uiState.update {
            it.copy(detail = MusicDetail.Album(album), errorTitle = null, errorMessage = null)
        }
    }

    fun openArtist(artist: MusicArtist) {
        _uiState.update {
            it.copy(detail = MusicDetail.Artist(artist), errorTitle = null, errorMessage = null)
        }
    }

    fun openPlaylist(playlist: MusicPlaylist) {
        _uiState.update {
            it.copy(
                detail = MusicDetail.Playlist(playlist, emptyList(), loading = true),
                errorTitle = null,
                errorMessage = null,
            )
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
                            errorTitle = "歌单加载失败",
                            errorMessage =
                                error.message?.takeIf { message -> message.isNotBlank() }
                                    ?: "无法读取歌单内容，请稍后重试",
                        )
                    }
                }
        }
    }

    fun closeDetail() {
        _uiState.update { it.copy(detail = null, errorTitle = null, errorMessage = null) }
    }

    /**
     * 点击歌曲：**先只解析被点的那一首立刻起播**，其余曲目随后按顺序补进队列。
     *
     * 旧实现要先把当前列表整份串行解析（歌曲页 100 首 = 100 次 PlaybackInfo）再 `setQueue`，
     * 真机表现为"点了没反应"，且任意一首解析失败会让整次点击失败（W3-R3b 缺陷 2）。 新流程最终队列与旧行为一致（整份列表入队、当前曲目 = 被点的曲目）：
     * 1) 解析被点曲目 → `setQueue` 起播（1 次请求）；
     * 2) 后台补队列：先补"当前之后"的曲目（下一首最先就位），再补"当前之前"的曲目； 单曲解析失败只跳过该曲，不影响已起播的播放。
     */
    fun playSong(song: MusicSong) {
        val state = _uiState.value
        val detail =
            state.detail?.takeIf { detail -> detail.songs.any { it.itemId == song.itemId } }
        val songs = detail?.songs ?: state.songs
        val startIndex = songs.indexOfFirst { it.itemId == song.itemId }
        if (startIndex < 0) return
        val source = detail?.source ?: QueueSource.MANUAL
        val sourceId = detail?.sourceId
        playJob?.cancel()
        playJob = viewModelScope.launch {
            val first =
                try {
                    trackResolver.toPlayerItem(song)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: Exception) {
                    _uiState.update {
                        it.copy(
                            errorTitle = "播放失败",
                            errorMessage =
                                error.message?.takeIf { message -> message.isNotBlank() }
                                    ?: "无法解析「${song.name}」的播放地址",
                        )
                    }
                    return@launch
                }
            playbackController.setQueue(
                MusicQueue(
                    items = listOf(first),
                    currentIndex = 0,
                    source = source,
                    sourceId = sourceId,
                ),
                startIndex = 0,
            )
            fillQueueAround(song, songs, startIndex)
        }
    }

    /**
     * 起播后补齐队列（后台，按顺序，跳过解析失败的曲目）。
     *
     * 队列最终顺序 = 浏览列表顺序：先补当前之后的曲目（`insertNext` 依次插到当前曲目之后）， 再补当前之前的曲目（`insertNext` 后 `move` 到队首）。
     * 用户若在补队列期间切歌 / 换队列，则立即停止补齐，避免打乱新队列。
     */
    private suspend fun fillQueueAround(
        current: MusicSong,
        songs: List<MusicSong>,
        startIndex: Int,
    ) {
        for (index in musicQueueFillOrder(startIndex, songs.size)) {
            if (!isCurrentQueueItem(current)) return
            val item = resolveQueueItem(songs[index]) ?: continue
            val queue = playbackController.queue.value ?: return
            playbackController.insertNext(item)
            // insertNext 固定插到 currentIndex + 1；再摆到最终位置：
            // 当前之后的曲目追加到队尾，当前之前的曲目前移到队首（顺序见 musicQueueFillOrder）
            val insertedAt = queue.currentIndex + 1
            if (index < startIndex) {
                playbackController.move(insertedAt, 0)
            } else {
                playbackController.move(insertedAt, queue.items.size)
            }
        }
        if (isCurrentQueueItem(current)) {
            Timber.d("音乐队列补齐完成：${songs.size} 首")
        }
    }

    /** 补队列期间用户是否还停在这首歌上（切歌 / 换队列时停止补齐）。 */
    private fun isCurrentQueueItem(song: MusicSong): Boolean =
        playbackController.queue.value?.currentItem?.itemId == song.itemId

    /** 单曲解析；失败只跳过该曲（起播不因列表里某一首坏文件而整体失败）。 */
    private suspend fun resolveQueueItem(song: MusicSong): PlayerItem? =
        try {
            trackResolver.toPlayerItem(song)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            Timber.w(error, "补队列失败，跳过「${song.name}」")
            null
        }

    /** 下一首播放（MU-3）：解析出可播放条目后插到当前曲目之后。 */
    fun playNext(song: MusicSong) {
        viewModelScope.launch {
            runCatching { trackResolver.toPlayerItem(song) }
                .onSuccess { item -> playbackController.insertNext(item) }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            errorTitle = "添加到队列失败",
                            errorMessage =
                                error.message?.takeIf { message -> message.isNotBlank() }
                                    ?: "无法解析「${song.name}」的播放地址",
                        )
                    }
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
        _uiState.update { it.copy(errorTitle = null, errorMessage = null) }
    }

    private companion object {
        /** 曲库加载失败后的自动重试等待（毫秒）：给弱网 / 服务端偶发超时一次恢复窗口。 */
        const val AUTO_RETRY_DELAY_MS = 1_200L
    }
}
