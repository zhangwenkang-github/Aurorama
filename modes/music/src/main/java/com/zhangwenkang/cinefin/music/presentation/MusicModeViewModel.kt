package com.zhangwenkang.cinefin.music.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zhangwenkang.cinefin.models.FindroidSourceType
import com.zhangwenkang.cinefin.music.data.MusicAlbum
import com.zhangwenkang.cinefin.music.data.MusicArtist
import com.zhangwenkang.cinefin.music.data.MusicLibrary
import com.zhangwenkang.cinefin.music.data.MusicLyricsOverlayController
import com.zhangwenkang.cinefin.music.data.MusicPlayMode
import com.zhangwenkang.cinefin.music.data.MusicPlaybackHistoryTracker
import com.zhangwenkang.cinefin.music.data.MusicPlaylist
import com.zhangwenkang.cinefin.music.data.MusicQueuePersister
import com.zhangwenkang.cinefin.music.data.MusicRecentStore
import com.zhangwenkang.cinefin.music.data.MusicRepository
import com.zhangwenkang.cinefin.music.data.MusicSleepTimer
import com.zhangwenkang.cinefin.music.data.MusicSong
import com.zhangwenkang.cinefin.music.data.MusicTrackResolver
import com.zhangwenkang.cinefin.music.data.groupAlbums
import com.zhangwenkang.cinefin.music.data.groupArtists
import com.zhangwenkang.cinefin.music.data.lyrics.LyricEditLine
import com.zhangwenkang.cinefin.music.data.lyrics.LyricsDisplayLanguage
import com.zhangwenkang.cinefin.music.data.lyrics.LyricsDisplayState
import com.zhangwenkang.cinefin.music.data.lyrics.LyricsDocument
import com.zhangwenkang.cinefin.music.data.lyrics.LyricsPresenter
import com.zhangwenkang.cinefin.music.data.lyrics.LyricsRepository
import com.zhangwenkang.cinefin.music.data.lyrics.LyricsRow
import com.zhangwenkang.cinefin.music.data.lyrics.lyricEditLines
import com.zhangwenkang.cinefin.music.data.lyrics.lyricEditLinesFromText
import com.zhangwenkang.cinefin.music.data.lyrics.lyricEditLinesToLyricLines
import com.zhangwenkang.cinefin.music.data.lyrics.lyricTimeDeltaMs
import com.zhangwenkang.cinefin.music.data.lyrics.nudgeLyricEditLine
import com.zhangwenkang.cinefin.music.data.lyrics.parseLyricOffsetMs
import com.zhangwenkang.cinefin.music.data.lyrics.shiftLyricEditLines
import com.zhangwenkang.cinefin.music.data.lyrics.shiftLyricWords
import com.zhangwenkang.cinefin.music.data.musicPlayModeOf
import com.zhangwenkang.cinefin.music.data.musicQueueFillOrder
import com.zhangwenkang.cinefin.music.data.musicQueueRemoveAt
import com.zhangwenkang.cinefin.music.data.next
import com.zhangwenkang.cinefin.music.data.toRepeatMode
import com.zhangwenkang.cinefin.music.data.toShuffleEnabled
import com.zhangwenkang.cinefin.player.core.domain.models.MusicQueue
import com.zhangwenkang.cinefin.player.core.domain.models.PlayerItem
import com.zhangwenkang.cinefin.player.core.domain.models.QueueSource
import com.zhangwenkang.cinefin.player.core.domain.models.RepeatMode
import com.zhangwenkang.cinefin.player.local.audio.MusicAudioEffectsController
import com.zhangwenkang.cinefin.player.local.audio.MusicEqualizerPreset
import com.zhangwenkang.cinefin.player.local.audio.ReplayGainMode
import com.zhangwenkang.cinefin.player.local.domain.MusicPlaybackController
import com.zhangwenkang.cinefin.player.local.domain.MusicPlaybackStateSource
import com.zhangwenkang.cinefin.player.local.domain.MusicQueueEditor
import com.zhangwenkang.cinefin.repository.JellyfinRepository
import com.zhangwenkang.cinefin.settings.domain.AppPreferences
import com.zhangwenkang.cinefin.utils.DownloadTaskStatus
import com.zhangwenkang.cinefin.utils.Downloader
import com.zhangwenkang.cinefin.utils.OfflineMediaEntryKind
import com.zhangwenkang.cinefin.utils.OfflineMediaRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
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
    private val recentStore: MusicRecentStore,
    private val sleepTimer: MusicSleepTimer,
    private val persister: MusicQueuePersister,
    private val historyTracker: MusicPlaybackHistoryTracker,
    private val lyricsOverlay: MusicLyricsOverlayController,
    private val playbackStateSource: MusicPlaybackStateSource,
    private val appPreferences: AppPreferences,
    private val audioEffects: MusicAudioEffectsController,
    private val downloader: Downloader,
    private val jellyfinRepository: JellyfinRepository,
    /** W36：离线模式的曲库来源（本机已下载曲目）。 */
    private val offlineMediaRepository: OfflineMediaRepository,
) : ViewModel() {

    /** W34：曲目下载态（下载列表层级化要求音乐侧也能发起下载）。 */
    data class SongDownloadState(
        val downloadedItemIds: Set<UUID> = emptySet(),
        val activeStatuses: Map<UUID, DownloadTaskStatus> = emptyMap(),
    ) {
        fun isDownloaded(itemId: UUID): Boolean = itemId in downloadedItemIds

        fun activeStatus(itemId: UUID): DownloadTaskStatus? = activeStatuses[itemId]

        fun isActive(itemId: UUID): Boolean = activeStatuses[itemId] != null
    }

    private val _downloadState = MutableStateFlow(SongDownloadState())
    val downloadState: StateFlow<SongDownloadState> = _downloadState.asStateFlow()

    data class UiState(
        val loading: Boolean = true,
        val tab: MusicTab = MusicTab.ALBUMS,
        /** W36：当前曲库来源是否为离线（离线模式只显示本机已下载曲目）。 */
        val offline: Boolean = false,
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
        /** 播放位置（毫秒）：逐字高亮推进用（W28-MUSIC）。 */
        val positionMs: Long = 0,
        val message: String? = null,
    )

    private val _lyricsState = MutableStateFlow(LyricsUiState())
    val lyricsState: StateFlow<LyricsUiState> = _lyricsState.asStateFlow()

    /**
     * 歌词编辑器状态（W25-MUSIC）：编辑当前曲歌词 / 导入 `.lrc` / 清除本机覆盖。
     *
     * [lines] 是编辑态行（时间戳以文本保存，保存时校验）；[hasOverride] 决定「清除覆盖」是否有东西可清；[message] 是面板内提示。
     */
    data class LyricsEditorUiState(
        val open: Boolean = false,
        val title: String? = null,
        val lines: List<LyricEditLine> = emptyList(),
        val hasOverride: Boolean = false,
        val message: String? = null,
        /** 当前选中行（单行时间微调目标）；null = 未选中（W28-MUSIC）。 */
        val selectedLineId: Long? = null,
        /** 「整体偏移」自定义输入（毫秒，W28-MUSIC）。 */
        val offsetText: String = "",
        /** 「选中行微调」自定义输入（毫秒，W28-MUSIC）。 */
        val stepText: String = "",
    )

    private val _lyricsEditorState = MutableStateFlow(LyricsEditorUiState())
    val lyricsEditorState: StateFlow<LyricsEditorUiState> = _lyricsEditorState.asStateFlow()

    private var nextEditLineId = 0L
    private var loadedLyricsItemId: UUID? = null
    private var lyricsLoadJob: Job? = null
    /** 逐字高亮日志去重（行下标 to 词下标，W28-MUSIC）。 */
    private var lastLoggedWord: Pair<Int, Int>? = null
    private var refreshJob: Job? = null
    private var playJob: Job? = null

    /** 上次会话的队列快照（W21-R2）：未点播放前只在本地展示 / 编辑。 */
    private val _restoredQueue = MutableStateFlow<MusicQueue?>(null)

    /** 正在播放的队列（当前曲目 / 播放模式 / 顺序）；无播放会话时回落到恢复快照，底栏与队列面板直接消费。 */
    val queue: StateFlow<MusicQueue?> =
        combine(playbackController.queue, _restoredQueue) { live, restored -> live ?: restored }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** 恢复态（重启后尚未点播放）：底栏文案与播放键语义据此区分。 */
    val isRestored: StateFlow<Boolean> =
        combine(playbackController.queue, _restoredQueue) { live, restored ->
                live == null && restored != null
            }
            .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** 音乐睡眠定时（W21-R2，MU-7）：进程级单例，离开音乐页 / 后台播放时仍生效。 */
    val sleepTimerState: StateFlow<MusicSleepTimer.State> = sleepTimer.state

    /** 是否正在播放（底栏按钮图标用）。 */
    val isPlaying: StateFlow<Boolean> = playbackStateSource.isPlaying

    /** 播放模式（W23-MUSIC · C 组）：从队列的 repeatMode + shuffleEnabled 反推四种模式， 队列被持久化时模式跟着一起落盘，不需要额外偏好键。 */
    val playMode: StateFlow<MusicPlayMode> =
        combine(playbackController.queue, _restoredQueue) { live, restored ->
                val queue = live ?: restored
                musicPlayModeOf(
                    repeatMode = queue?.repeatMode ?: RepeatMode.OFF,
                    shuffleEnabled = queue?.shuffleEnabled ?: false,
                )
            }
            .stateIn(viewModelScope, SharingStarted.Eagerly, MusicPlayMode.SEQUENTIAL)

    /** 当前播放位置（毫秒）：底栏时间与全屏进度条共用；恢复态没有会话时为 0（由 UI 回落到快照位置）。 */
    val positionMs: StateFlow<Long> = playbackController.positionMs

    /** 当前曲目总时长（毫秒）：内核还没给出时为 0，UI 用曲库元数据兜底。 */
    val durationMs: StateFlow<Long> = playbackStateSource.durationMs

    /** 桌面歌词悬浮窗状态（W23-MUSIC · D 组）：开关 / 颜色 / 字号 / 语言 / 锁定都在这里。 */
    val lyricsOverlayState: StateFlow<MusicLyricsOverlayController.State> = lyricsOverlay.state

    // W30-MUSIC-FX：音效面板状态（EQ / ReplayGain / 淡入淡出），实现与偏好都在 player:local 的音效中枢。
    val effectsEqualizerEnabled: StateFlow<Boolean> = audioEffects.equalizerEnabled
    val effectsEqualizerPreset: StateFlow<MusicEqualizerPreset> = audioEffects.equalizerPreset
    val effectsEqualizerBands: StateFlow<List<Float>> = audioEffects.equalizerBands
    val effectsReplayGainMode: StateFlow<ReplayGainMode> = audioEffects.replayGainMode
    val effectsReplayGainLabel: StateFlow<String?> = audioEffects.replayGainLabel
    val effectsCrossfadeSeconds: StateFlow<Int> = audioEffects.crossfadeSeconds
    val effectsHasCurrentTrack: StateFlow<Boolean> = audioEffects.hasCurrentTrack
    val effectsOverrideTrackDb: StateFlow<Float?> = audioEffects.overrideTrackDb
    val effectsOverrideAlbumDb: StateFlow<Float?> = audioEffects.overrideAlbumDb

    /**
     * 曲目元数据查询（W23-MUSIC）：[PlayerItem] 只带名字与封面，全屏播放页要显示的歌手 / 专辑、以及恢复态的时长兜底都从曲库快照里按 itemId 取；查不到返回
     * null（UI 降级）。
     */
    fun songMeta(itemId: UUID?): MusicSong? {
        if (itemId == null) return null
        val state = _uiState.value
        return state.songs.firstOrNull { it.itemId == itemId }
            ?: state.detail?.songs?.firstOrNull { it.itemId == itemId }
            ?: state.albums
                .asSequence()
                .flatMap { album -> album.songs.asSequence() }
                .firstOrNull { it.itemId == itemId }
            ?: state.artists
                .asSequence()
                .flatMap { artist -> artist.songs.asSequence() }
                .firstOrNull { it.itemId == itemId }
    }

    init {
        persister.start()
        historyTracker.start()
        lyricsOverlay.start()
        refresh()
        observeLyrics()
        restoreQueue()
        observeRecent()
        observeOverlayArtists()
        observeRestoredQueueCleanup()
    }

    /**
     * 无歌词回落显示（W24 · A3）：曲库元数据就绪 / 队列切歌时，把当前曲目的歌手喂给桌面歌词控制器。
     *
     * 控制器自身还有一次按需曲库加载兜底（ViewModel 不存在的场景，如离开音乐页后自动切歌）。
     */
    private fun observeOverlayArtists() {
        viewModelScope.launch {
            combine(playbackController.queue, _uiState) { _, _ -> Unit }
                .collect {
                    val itemId = queue.value?.currentItem?.itemId ?: return@collect
                    val song = songMeta(itemId) ?: return@collect
                    lyricsOverlay.updateTrackArtist(song.itemId, song.artist)
                }
        }
    }

    /**
     * 启动时读取上次队列快照（W21-R2）：只恢复展示与续播位置，不自动出声； 用户点播放 / 切歌时按快照里的 `playbackPosition` 走既有 setQueue
     * 续播路径（MU-9 同一条）。
     */
    private fun restoreQueue() {
        viewModelScope.launch {
            val snapshot = runCatching { persister.load() }.getOrNull() ?: return@launch
            if (playbackController.queue.value == null) {
                _restoredQueue.value = snapshot.queue
            }
        }
    }

    /** 当前曲目发生变化（起播 / 切歌）→ 记一条本地最近播放；恢复态不算"播放过"。 */
    private fun observeRecent() {
        viewModelScope.launch {
            playbackController.queue
                .map { queue -> queue?.currentItem }
                .distinctUntilChanged { old, new -> old?.itemId == new?.itemId }
                .collect { item ->
                    if (item != null) {
                        recentStore.record(
                            item = item,
                            librarySong =
                                _uiState.value.songs.firstOrNull { song ->
                                    song.itemId == item.itemId
                                },
                        )
                    }
                }
        }
    }

    /** 播放器装载队列后清掉恢复快照（通知栏 / 锁屏续播等非本页路径同样适用）。 */
    private fun observeRestoredQueueCleanup() {
        viewModelScope.launch {
            playbackController.queue.filterNotNull().collect { _restoredQueue.value = null }
        }
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
            lastLoggedWord = null
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
                    positionMs = 0,
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
                        positionMs = 0,
                        message = "该曲目暂无歌词（本机覆盖 / 外挂 LRC / 服务端都没有）",
                    )
                } else {
                    val display = LyricsPresenter.defaultDisplay(document)
                    val rows = LyricsPresenter.rows(document, display)
                    val position = playbackController.positionMs.value
                    state.copy(
                        loading = false,
                        document = document,
                        languages = LyricsPresenter.displayLanguages(document),
                        display = display,
                        rows = rows,
                        activeIndex = LyricsPresenter.activeIndex(rows, position),
                        positionMs = position,
                        message = null,
                    )
                }
            }
        }
    }

    private fun syncActiveLyricLine(positionMs: Long) {
        val state = _lyricsState.value
        if (state.rows.isEmpty()) {
            if (state.positionMs != 0L) {
                _lyricsState.update { it.copy(positionMs = 0L) }
            }
            return
        }
        val index = LyricsPresenter.activeIndex(state.rows, positionMs)
        if (index != state.activeIndex || positionMs != state.positionMs) {
            _lyricsState.update { it.copy(activeIndex = index, positionMs = positionMs) }
        }
        logWordProgress(state.rows, index, positionMs)
    }

    /** 逐字高亮推进取证日志（W28-MUSIC）：当前行有逐字数据时，词切换打一条（同一行 / 词的重复采样不重复打）。 无逐字数据的曲目不打日志（行为与整行高亮完全一致）。 */
    private fun logWordProgress(rows: List<LyricsRow>, rowIndex: Int, positionMs: Long) {
        val row = rows.getOrNull(rowIndex) ?: return
        val words = row.words
        if (words.isEmpty()) return
        val wordIndex = LyricsPresenter.activeWordIndex(words, positionMs)
        if (wordIndex < 0 || lastLoggedWord == (rowIndex to wordIndex)) return
        lastLoggedWord = rowIndex to wordIndex
        Timber.i(
            "逐字歌词：pos=%dms 第 %d 行 第 %d/%d 词「%s」",
            positionMs,
            rowIndex + 1,
            wordIndex + 1,
            words.size,
            words[wordIndex].text.trim(),
        )
    }

    /** 打开歌词面板（曲目在底栏点「词」触发）。 */
    fun openLyrics() {
        val restored = _restoredQueue.value
        if (playbackController.queue.value == null && restored != null) {
            // 恢复态没有播放器状态流，按恢复条目主动拉一次歌词
            onCurrentItemChanged(restored.currentItem)
        }
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

    /** 打开歌词编辑器：预填当前歌词文档（无歌词则从空列表开始，可手工添加行）。 */
    fun openLyricsEditor() {
        val item = queue.value?.currentItem ?: return
        val lines = lyricEditLines(_lyricsState.value.document)
        nextEditLineId = (lines.maxOfOrNull { it.id } ?: -1L) + 1
        _lyricsEditorState.value =
            LyricsEditorUiState(open = true, title = item.name, lines = lines)
        viewModelScope.launch {
            val hasOverride = runCatching {
                lyricsRepository.hasLocalOverride(item.itemId)
            }
                .getOrDefault(false)
            _lyricsEditorState.update { it.copy(hasOverride = hasOverride) }
        }
    }

    fun closeLyricsEditor() {
        _lyricsEditorState.value = LyricsEditorUiState()
    }

    /** 编辑器内提示（导入失败等场景）。 */
    fun showLyricsEditorMessage(message: String) {
        _lyricsEditorState.update { it.copy(message = message) }
    }

    fun addLyricsEditorLine() {
        val line = LyricEditLine(id = nextEditLineId++, timeText = "", text = "")
        _lyricsEditorState.update { it.copy(lines = it.lines + line, message = null) }
    }

    fun removeLyricsEditorLine(id: Long) {
        _lyricsEditorState.update { state ->
            state.copy(lines = state.lines.filterNot { it.id == id }, message = null)
        }
    }

    fun updateLyricsEditorLineText(id: Long, text: String) {
        _lyricsEditorState.update { state ->
            state.copy(
                lines = state.lines.map { if (it.id == id) it.copy(text = text) else it },
                message = null,
            )
        }
    }

    fun updateLyricsEditorLineTime(id: Long, timeText: String) {
        _lyricsEditorState.update { state ->
            state.copy(
                lines =
                    state.lines.map { line ->
                        if (line.id != id) line
                        else {
                            // W28：直接输入时间 = 自定义微调；两侧都能解析时同步平移逐字数据，否则保留原值（保存时统一校验）
                            val delta = lyricTimeDeltaMs(line.timeText, timeText)
                            line.copy(
                                timeText = timeText,
                                words =
                                    if (delta == null) line.words
                                    else shiftLyricWords(line.words, delta),
                            )
                        }
                    },
                message = null,
            )
        }
    }

    /** 点选 / 取消选中微调行（W28-MUSIC）：再次点击同一行取消选中。 */
    fun selectLyricsEditorLine(id: Long) {
        _lyricsEditorState.update {
            it.copy(selectedLineId = if (it.selectedLineId == id) null else id, message = null)
        }
    }

    fun updateLyricsEditorOffsetText(text: String) {
        _lyricsEditorState.update { it.copy(offsetText = text, message = null) }
    }

    fun updateLyricsEditorStepText(text: String) {
        _lyricsEditorState.update { it.copy(stepText = text, message = null) }
    }

    /** 整段时间轴偏移（±100ms 档 / 自定义毫秒，W28-MUSIC）：所有有时间戳的行一起平移。 */
    fun shiftLyricsEditorLines(deltaMs: Long) {
        if (deltaMs == 0L) return
        _lyricsEditorState.update {
            it.copy(lines = shiftLyricEditLines(it.lines, deltaMs), message = null)
        }
    }

    /** 选中行时间微调（±100ms 档 / 自定义毫秒，W28-MUSIC）。 */
    fun shiftSelectedLyricsEditorLine(deltaMs: Long) {
        val id = _lyricsEditorState.value.selectedLineId
        if (id == null) {
            _lyricsEditorState.update { it.copy(message = "先在列表里点选一行，再微调时间") }
            return
        }
        if (deltaMs == 0L) return
        _lyricsEditorState.update {
            it.copy(lines = nudgeLyricEditLine(it.lines, id, deltaMs), message = null)
        }
    }

    /** 应用「整体偏移」自定义输入（毫秒）。 */
    fun applyLyricsEditorOffsetText() {
        val delta = parseLyricOffsetMs(_lyricsEditorState.value.offsetText)
        if (delta == null) {
            _lyricsEditorState.update { it.copy(message = "偏移量应为毫秒整数（例：-500 / +250）") }
            return
        }
        shiftLyricsEditorLines(delta)
    }

    /** 应用「选中行微调」自定义输入（毫秒）。 */
    fun applyLyricsEditorStepText() {
        val delta = parseLyricOffsetMs(_lyricsEditorState.value.stepText)
        if (delta == null) {
            _lyricsEditorState.update { it.copy(message = "偏移量应为毫秒整数（例：-100 / +100）") }
            return
        }
        shiftSelectedLyricsEditorLine(delta)
    }

    /** 保存编辑结果为本机覆盖（来源链最高优先级，不写服务器）。 */
    fun saveLyricsEditor() {
        val item = queue.value?.currentItem ?: return
        val lines = lyricEditLinesToLyricLines(_lyricsEditorState.value.lines)
        if (lines == null) {
            _lyricsEditorState.update { it.copy(message = "时间格式应为 mm:ss.xx（例：01:23.45）") }
            return
        }
        if (lines.isEmpty()) {
            _lyricsEditorState.update { it.copy(message = "没有可保存的歌词行") }
            return
        }
        viewModelScope.launch {
            val ok = runCatching {
                lyricsRepository.saveLocalOverride(item.itemId, lines)
            }
                .getOrDefault(false)
            if (!ok) {
                _lyricsEditorState.update { it.copy(message = "保存失败：本机存储不可写") }
                return@launch
            }
            _lyricsEditorState.value = LyricsEditorUiState()
            reloadLyrics(item)
        }
    }

    /** 导入 `.lrc` 文本为本机覆盖（原样保存，保留 `[ti:]` 等标签）。 */
    fun importLyricsOverride(text: String) {
        val item = queue.value?.currentItem ?: return
        if (text.isBlank()) {
            _lyricsEditorState.update { it.copy(message = "文件里没有可用的歌词内容") }
            return
        }
        viewModelScope.launch {
            val ok = runCatching {
                lyricsRepository.saveLocalOverrideText(item.itemId, text)
            }
                .getOrDefault(false)
            if (!ok) {
                _lyricsEditorState.update { it.copy(message = "导入失败：本机存储不可写") }
                return@launch
            }
            val lines = lyricEditLinesFromText(text)
            nextEditLineId = (lines.maxOfOrNull { it.id } ?: -1L) + 1
            _lyricsEditorState.update {
                it.copy(
                    lines = lines,
                    hasOverride = true,
                    message = "已导入 ${lines.size} 行（本机覆盖）",
                )
            }
            reloadLyrics(item)
        }
    }

    /** 清除本机覆盖：回落到外挂 LRC / 服务端 / 缓存。 */
    fun clearLyricsOverride() {
        val item = queue.value?.currentItem ?: return
        viewModelScope.launch {
            val cleared = runCatching {
                lyricsRepository.clearLocalOverride(item.itemId)
            }
                .getOrDefault(false)
            _lyricsEditorState.update {
                it.copy(
                    hasOverride = false,
                    message = if (cleared) "已清除本机覆盖" else "当前没有本机覆盖",
                )
            }
            if (cleared) reloadLyrics(item)
        }
    }

    /** 覆盖变更后强制重拉歌词（绕过"同一曲目不重复加载"守卫）。 */
    private fun reloadLyrics(item: PlayerItem) {
        loadedLyricsItemId = null
        onCurrentItemChanged(item)
    }

    private fun updateLyricsDisplay(transform: (LyricsDisplayState) -> LyricsDisplayState) {
        _lyricsState.update { state ->
            val display = transform(state.display)
            val document = state.document
            if (document == null) {
                state.copy(display = display)
            } else {
                val rows = LyricsPresenter.rows(document, display)
                val position = playbackController.positionMs.value
                state.copy(
                    display = display,
                    rows = rows,
                    activeIndex = LyricsPresenter.activeIndex(rows, position),
                    positionMs = position,
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
            // W36：离线模式只读本机已下载曲目，不发任何网络请求。
            if (appPreferences.getValue(appPreferences.offlineMode)) {
                val library = runCatching {
                    loadOfflineLibrary()
                }
                    .onFailure { Timber.w(it, "读取离线曲库失败") }
                    .getOrElse {
                        MusicLibrary(
                            songs = emptyList(),
                            albums = emptyList(),
                            artists = emptyList(),
                        )
                    }
                _uiState.update {
                    it.copy(
                        loading = false,
                        offline = true,
                        albums = library.albums,
                        artists = library.artists,
                        songs = library.songs,
                        playlists = emptyList(),
                        errorTitle = null,
                        errorMessage = null,
                    )
                }
                refreshDownloadState()
                return@launch
            }
            try {
                val (library, playlists) = loadLibraryWithRetry()
                _uiState.update {
                    it.copy(
                        loading = false,
                        offline = false,
                        albums = library.albums,
                        artists = library.artists,
                        songs = library.songs,
                        playlists = playlists,
                        errorTitle = null,
                        errorMessage = null,
                    )
                }
                refreshDownloadState()
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
        val library = repository.getLibrary(libraryId = selectedMusicLibraryId())
        return library to repository.getPlaylists()
    }

    /**
     * W36 离线曲库：本机已下载且「允许离线观看」的曲目（按专辑 / 艺术家聚合）。
     *
     * 曲目元数据来自下载侧车（专辑 / 艺人 / 音轨号）；播放走 [MusicTrackResolver] → 离线仓库 `getMediaSources` 优先 LOCAL
     * 来源，即本地文件。
     */
    private suspend fun loadOfflineLibrary(): MusicLibrary {
        val songs =
            offlineMediaRepository
                .listEntries()
                .filter { it.kind == OfflineMediaEntryKind.MUSIC && it.allowOffline }
                .map { entry ->
                    MusicSong(
                        itemId = entry.itemId,
                        name = entry.name,
                        albumName = entry.albumName ?: "未分类",
                        artist = entry.artist,
                        indexNumber = entry.trackIndex.takeIf { it > 0 },
                        runtimeTicks = 0,
                        imageUri = null,
                    )
                }
                .sortedWith(compareBy({ it.albumName }, { it.indexNumber ?: 0 }, { it.name }))
        return MusicLibrary(
            songs = songs,
            albums = groupAlbums(songs),
            artists = groupArtists(songs),
        )
    }

    /** 客户端设置「音乐库」：指定库失效（被删除 / 重建）时回落自动（全部音乐库）。 */
    private fun selectedMusicLibraryId(): UUID? {
        val raw =
            appPreferences.getValue(appPreferences.uiMusicLibraryId)?.takeIf { it.isNotBlank() }
                ?: return null
        return runCatching { UUID.fromString(raw) }.getOrNull()
    }

    fun selectTab(tab: MusicTab) {
        _uiState.update { it.copy(tab = tab, detail = null) }
    }

    /** W34：刷新曲目下载态（进行中任务 + 已下载条目）。 */
    fun refreshDownloadState() {
        viewModelScope.launch {
            runCatching {
                val tasks = downloader.refreshDownloadTasks()
                val active =
                    tasks
                        .filter { it.status != DownloadTaskStatus.COMPLETED }
                        .associate { it.itemId to it.status }
                val downloaded = downloader.downloadedItemIds()
                _downloadState.value =
                    SongDownloadState(
                        downloadedItemIds = downloaded,
                        activeStatuses = active,
                    )
            }
                .onFailure { Timber.w(it, "刷新曲目下载态失败") }
        }
    }

    /** W34：下载 / 删除单曲（音乐侧入口；下载引擎仍走 DownloadManager + sources 表）。 */
    fun toggleSongDownload(song: MusicSong) {
        viewModelScope.launch {
            val item =
                runCatching { jellyfinRepository.getItem(song.itemId) }.getOrNull() ?: return@launch
            val localSources = item.sources.filter { it.type == FindroidSourceType.LOCAL }
            val completed = localSources.firstOrNull { !it.path.endsWith(".download") }
            if (completed != null) {
                downloader.deleteItem(item, completed)
                refreshDownloadState()
                return@launch
            }
            val activeTask =
                downloader.refreshDownloadTasks().firstOrNull {
                    it.itemId == song.itemId && it.status != DownloadTaskStatus.COMPLETED
                }
            if (activeTask != null) {
                downloader.deleteTask(activeTask)
                refreshDownloadState()
                return@launch
            }
            val sourceId =
                runCatching { jellyfinRepository.getMediaSources(song.itemId, true) }
                    .getOrNull()
                    ?.firstOrNull()
                    ?.id ?: return@launch
            downloader.downloadItem(
                item = item,
                sourceId = sourceId,
                storageIndex = 0,
                albumName = song.albumName,
                artist = song.artist,
                trackIndex = song.indexNumber ?: 0,
            )
            refreshDownloadState()
        }
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

    /** 恢复态下点播放 / 切歌：把恢复快照交给播放器（按保存位置续播），随后进入正常播放语义。 */
    private fun resumeRestored(queue: MusicQueue, index: Int) {
        if (index !in queue.items.indices) return
        _restoredQueue.value = null
        playbackController.setQueue(queue, startIndex = index)
    }

    fun moveQueueItem(fromIndex: Int, toIndex: Int) {
        val restored = _restoredQueue.value
        if (playbackController.queue.value == null && restored != null) {
            val moved = restored.move(fromIndex, toIndex)
            if (moved != restored) {
                _restoredQueue.value = moved
                persister.persistNow(moved)
            }
            return
        }
        playbackController.move(fromIndex, toIndex)
    }

    fun jumpToQueueItem(index: Int) {
        val restored = _restoredQueue.value
        if (playbackController.queue.value == null && restored != null) {
            resumeRestored(restored, index)
            return
        }
        queueEditor.jumpTo(index)
    }

    fun removeQueueItem(index: Int) {
        val restored = _restoredQueue.value
        if (playbackController.queue.value == null && restored != null) {
            val updated = musicQueueRemoveAt(restored, index)
            when {
                updated == null -> {
                    _restoredQueue.value = null
                    viewModelScope.launch { persister.clear() }
                }
                updated != restored -> {
                    _restoredQueue.value = updated
                    persister.persistNow(updated)
                }
            }
            return
        }
        queueEditor.removeAt(index)
    }

    fun togglePlayPause() {
        val restored = _restoredQueue.value
        if (playbackController.queue.value == null && restored != null) {
            resumeRestored(restored, restored.currentIndex)
            return
        }
        playbackController.playPause()
    }

    fun skipToNext() {
        val restored = _restoredQueue.value
        if (playbackController.queue.value == null && restored != null) {
            resumeRestored(
                restored,
                (restored.currentIndex + 1).coerceAtMost(restored.items.lastIndex),
            )
            return
        }
        if (playMode.value == MusicPlayMode.SHUFFLE) {
            // 随机下一曲交给内核 shuffle 顺序（DefaultShuffleOrder：一轮内每首各一次 = 未播优先）
            Timber.i(
                "随机下一曲：当前 index=%d，按内核随机顺序切歌",
                playbackController.queue.value?.currentIndex ?: -1,
            )
        }
        playbackController.next()
    }

    fun skipToPrevious() {
        val restored = _restoredQueue.value
        if (playbackController.queue.value == null && restored != null) {
            resumeRestored(restored, (restored.currentIndex - 1).coerceAtLeast(0))
            return
        }
        val live = playbackController.queue.value
        if (playMode.value == MusicPlayMode.SHUFFLE && live != null) {
            // 随机规则（用户明确）：上一曲 = 播放历史里实际播放过的上一首，不是随机跳
            val index =
                historyTracker.previousIndex(
                    queueItemIds = live.items.map { item -> item.itemId },
                    currentItemId = live.currentItem?.itemId,
                )
            if (index != null) {
                Timber.i(
                    "随机上一曲：回到历史曲目 index=%d（当前 index=%d）",
                    index,
                    live.currentIndex,
                )
                queueEditor.jumpTo(index)
                return
            }
            Timber.i("随机上一曲：历史为空 / 曲目已不在队列，回退到内核上一首")
        }
        playbackController.previous()
    }

    /** 图标循环切换播放模式（全屏播放界面的模式键）。 */
    fun cyclePlayMode() {
        setPlayMode(playMode.value.next())
    }

    /** 桌面歌词开关（全屏播放界面 / 设置页）：悬浮窗只在"播放中 + 有权限"时出现。 */
    fun toggleLyricsOverlay() = lyricsOverlay.toggle()

    /** 授权页返回且已拿到权限后调用：打开开关并把悬浮窗拉起来。 */
    fun enableLyricsOverlay() = lyricsOverlay.setEnabled(true)

    /** 从系统权限页返回后复核权限（拿到权限才能显示悬浮窗）。 */
    fun refreshLyricsOverlayPermission() = lyricsOverlay.refreshPermission()

    /** 应用播放模式：活动会话直接改内核开关；恢复态只改快照并落盘（下次起播即生效）。 */
    fun setPlayMode(mode: MusicPlayMode) {
        Timber.i(
            "播放模式切换：%s（repeat=%s shuffle=%s）",
            mode.label,
            mode.toRepeatMode(),
            mode.toShuffleEnabled(),
        )
        if (playbackController.queue.value != null) {
            playbackController.setRepeatMode(mode.toRepeatMode())
            playbackController.setShuffleEnabled(mode.toShuffleEnabled())
            return
        }
        val restored = _restoredQueue.value ?: return
        val updated =
            restored.copy(
                repeatMode = mode.toRepeatMode(),
                shuffleEnabled = mode.toShuffleEnabled(),
            )
        if (updated != restored) {
            _restoredQueue.value = updated
            persister.persistNow(updated)
        }
    }

    /** 拖动全屏进度条（B 组）：恢复态没有播放会话，忽略。 */
    fun seekTo(positionMs: Long) {
        if (playbackController.queue.value == null) return
        playbackController.seekTo(positionMs.coerceAtLeast(0L))
    }

    /** 打开服务端收藏列表（顶栏「收藏」入口）。 */
    fun openFavorites() {
        _uiState.update {
            it.copy(
                detail = MusicDetail.Favorites(emptyList(), loading = true),
                errorTitle = null,
                errorMessage = null,
            )
        }
        viewModelScope.launch {
            runCatching { repository.getFavoriteSongs() }
                .onSuccess { songs ->
                    _uiState.update { state ->
                        if (state.detail is MusicDetail.Favorites) {
                            state.copy(detail = MusicDetail.Favorites(songs))
                        } else {
                            state
                        }
                    }
                }
                .onFailure { error ->
                    _uiState.update { state ->
                        if (state.detail is MusicDetail.Favorites) {
                            state.copy(
                                detail = null,
                                errorTitle = "收藏加载失败",
                                errorMessage =
                                    error.message?.takeIf { it.isNotBlank() } ?: "无法读取服务器收藏",
                            )
                        } else {
                            state
                        }
                    }
                }
        }
    }

    /** 打开本地最近播放（顶栏「最近」入口，按播放时间倒序）。 */
    fun openRecent() {
        viewModelScope.launch {
            val songs = runCatching {
                recentStore.load()
            }
                .getOrDefault(emptyList())
                .map { recent ->
                    // 曲库已加载时用最新收藏态覆盖，避免最近列表里的「收藏 / 取消收藏」文案过时
                    _uiState.value.songs
                        .firstOrNull { song -> song.itemId == recent.itemId }
                        ?.let { song -> recent.copy(isFavorite = song.isFavorite) } ?: recent
                }
            _uiState.update {
                it.copy(
                    detail = MusicDetail.Recent(songs),
                    errorTitle = null,
                    errorMessage = null,
                )
            }
        }
    }

    /** 歌曲行「收藏 / 取消收藏」：写服务端白名单成功后同步所有列表里的收藏态。 */
    fun toggleFavorite(song: MusicSong) {
        val favorite = !song.isFavorite
        viewModelScope.launch {
            runCatching { repository.setFavorite(song.itemId, favorite) }
                .onSuccess {
                    Timber.i("收藏成功：itemId=${song.itemId} favorite=$favorite")
                    _uiState.update { state -> state.withFavorite(song.itemId, favorite) }
                }
                .onFailure { error ->
                    Timber.w(error, "收藏失败：itemId=${song.itemId}")
                    _uiState.update {
                        it.copy(
                            errorTitle = "收藏失败",
                            errorMessage =
                                error.message?.takeIf { message -> message.isNotBlank() }
                                    ?: "无法写入服务器收藏",
                        )
                    }
                }
        }
    }

    private fun UiState.withFavorite(itemId: UUID, favorite: Boolean): UiState {
        fun MusicSong.refreshed(): MusicSong =
            if (this.itemId == itemId) copy(isFavorite = favorite) else this

        val updatedAlbums = albums.map { album ->
            album.copy(songs = album.songs.map { song -> song.refreshed() })
        }
        val updatedArtists = artists.map { artist ->
            artist.copy(songs = artist.songs.map { song -> song.refreshed() })
        }
        val updatedSongs = songs.map { song -> song.refreshed() }
        val updatedDetail =
            when (val current = detail) {
                null -> null
                is MusicDetail.Album ->
                    current.copy(
                        album =
                            current.album.copy(songs = current.album.songs.map { it.refreshed() })
                    )
                is MusicDetail.Artist ->
                    current.copy(
                        artist =
                            current.artist.copy(songs = current.artist.songs.map { it.refreshed() })
                    )
                is MusicDetail.Playlist ->
                    current.copy(songs = current.songs.map { it.refreshed() })
                is MusicDetail.Favorites ->
                    if (favorite) {
                        current.copy(songs = current.songs.map { it.refreshed() })
                    } else {
                        current.copy(songs = current.songs.filterNot { it.itemId == itemId })
                    }
                is MusicDetail.Recent -> current.copy(songs = current.songs.map { it.refreshed() })
            }
        return copy(
            albums = updatedAlbums,
            artists = updatedArtists,
            songs = updatedSongs,
            detail = updatedDetail,
        )
    }

    /** 选择睡眠定时档位（分钟）；null = 关闭。 */
    fun selectSleepTimer(minutes: Int?) = sleepTimer.select(minutes)

    // W30-MUSIC-FX：音效面板动作（全部转发给 player:local 的音效中枢）。
    fun setEqualizerEnabled(enabled: Boolean) = audioEffects.setEqualizerEnabled(enabled)

    fun selectEqualizerPreset(preset: MusicEqualizerPreset) =
        audioEffects.selectEqualizerPreset(preset)

    /** 拖动频段滑杆（实时生效、不落盘）。 */
    fun previewEqualizerBand(index: Int, gainDb: Float) =
        audioEffects.setEqualizerBand(index, gainDb)

    /** 拖动结束落盘。 */
    fun commitEqualizerBands() = audioEffects.commitEqualizerBands()

    fun selectReplayGainMode(mode: ReplayGainMode) = audioEffects.setReplayGainMode(mode)

    /** W35：本机增益覆盖（拖动实时生效、松手落盘；清除 = 删除本机覆盖文件）。 */
    fun previewReplayGainOverrideTrack(gainDb: Float) = audioEffects.previewOverrideTrackDb(gainDb)

    fun previewReplayGainOverrideAlbum(gainDb: Float) = audioEffects.previewOverrideAlbumDb(gainDb)

    fun commitReplayGainOverride() = audioEffects.commitOverride()

    fun clearReplayGainOverride() = audioEffects.clearLocalOverride()

    fun selectCrossfadeSeconds(seconds: Int) = audioEffects.setCrossfadeSeconds(seconds)

    fun dismissError() {
        _uiState.update { it.copy(errorTitle = null, errorMessage = null) }
    }

    private companion object {
        /** 曲库加载失败后的自动重试等待（毫秒）：给弱网 / 服务端偶发超时一次恢复窗口。 */
        const val AUTO_RETRY_DELAY_MS = 1_200L
    }
}
