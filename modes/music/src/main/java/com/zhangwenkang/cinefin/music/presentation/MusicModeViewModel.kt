package com.zhangwenkang.cinefin.music.presentation

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zhangwenkang.cinefin.core.presentation.components.LibrarySelectorOption
import com.zhangwenkang.cinefin.core.presentation.downloader.DownloadPrecheckContent
import com.zhangwenkang.cinefin.core.presentation.downloader.DownloadPrecheckKind
import com.zhangwenkang.cinefin.core.selection.MultiSelectState
import com.zhangwenkang.cinefin.local.LocalLibraryRepository
import com.zhangwenkang.cinefin.local.LocalMediaKind
import com.zhangwenkang.cinefin.models.FindroidCollection
import com.zhangwenkang.cinefin.models.FindroidSourceType
import com.zhangwenkang.cinefin.music.data.LocalPlaylist
import com.zhangwenkang.cinefin.music.data.LocalPlaylistEntry
import com.zhangwenkang.cinefin.music.data.LocalPlaylistRepositoryImpl
import com.zhangwenkang.cinefin.music.data.MusicAlbum
import com.zhangwenkang.cinefin.music.data.MusicArtist
import com.zhangwenkang.cinefin.music.data.MusicAutoDjController
import com.zhangwenkang.cinefin.music.data.MusicItemSource
import com.zhangwenkang.cinefin.music.data.MusicItemSourceFilter
import com.zhangwenkang.cinefin.music.data.MusicLibrary
import com.zhangwenkang.cinefin.music.data.MusicLyricsOverlayController
import com.zhangwenkang.cinefin.music.data.MusicPlayMode
import com.zhangwenkang.cinefin.music.data.MusicPlaybackHistoryTracker
import com.zhangwenkang.cinefin.music.data.MusicPlaylist
import com.zhangwenkang.cinefin.music.data.MusicPlaylistKind
import com.zhangwenkang.cinefin.music.data.MusicQueuePersister
import com.zhangwenkang.cinefin.music.data.MusicQueueSnapshot
import com.zhangwenkang.cinefin.music.data.MusicRecentStore
import com.zhangwenkang.cinefin.music.data.MusicRepository
import com.zhangwenkang.cinefin.music.data.MusicSong
import com.zhangwenkang.cinefin.music.data.MusicTrackResolver
import com.zhangwenkang.cinefin.music.data.PlaylistExchangeAdapter
import com.zhangwenkang.cinefin.music.data.PlaylistExchangeDocument
import com.zhangwenkang.cinefin.music.data.PlaylistExchangeEntry
import com.zhangwenkang.cinefin.music.data.PlaylistExchangeException
import com.zhangwenkang.cinefin.music.data.ServerPlaylistCreateOutcome
import com.zhangwenkang.cinefin.music.data.SongMetadataRefreshResult
import com.zhangwenkang.cinefin.music.data.TrackRef
import com.zhangwenkang.cinefin.music.data.groupAlbums
import com.zhangwenkang.cinefin.music.data.groupArtists
import com.zhangwenkang.cinefin.music.data.isLocalPlaybackUri
import com.zhangwenkang.cinefin.music.data.isShuffle
import com.zhangwenkang.cinefin.music.data.lyrics.LyricEditLine
import com.zhangwenkang.cinefin.music.data.lyrics.LyricsBilingualPreference
import com.zhangwenkang.cinefin.music.data.lyrics.LyricsDisplayLanguage
import com.zhangwenkang.cinefin.music.data.lyrics.LyricsDisplayState
import com.zhangwenkang.cinefin.music.data.lyrics.LyricsDocument
import com.zhangwenkang.cinefin.music.data.lyrics.LyricsFontScale
import com.zhangwenkang.cinefin.music.data.lyrics.LyricsLineSpacing
import com.zhangwenkang.cinefin.music.data.lyrics.LyricsPresenter
import com.zhangwenkang.cinefin.music.data.lyrics.LyricsRepository
import com.zhangwenkang.cinefin.music.data.lyrics.LyricsRow
import com.zhangwenkang.cinefin.music.data.lyrics.LyricsTextSettings
import com.zhangwenkang.cinefin.music.data.lyrics.lyricEditLines
import com.zhangwenkang.cinefin.music.data.lyrics.lyricEditLinesFromText
import com.zhangwenkang.cinefin.music.data.lyrics.lyricEditLinesToLyricLines
import com.zhangwenkang.cinefin.music.data.lyrics.lyricTimeDeltaMs
import com.zhangwenkang.cinefin.music.data.lyrics.nudgeLyricEditLine
import com.zhangwenkang.cinefin.music.data.lyrics.parseLyricOffsetMs
import com.zhangwenkang.cinefin.music.data.lyrics.shiftLyricEditLines
import com.zhangwenkang.cinefin.music.data.lyrics.shiftLyricWords
import com.zhangwenkang.cinefin.music.data.mergedWithFresh
import com.zhangwenkang.cinefin.music.data.musicPlayModeOf
import com.zhangwenkang.cinefin.music.data.musicQueueFillBatches
import com.zhangwenkang.cinefin.music.data.musicQueueInsertAt
import com.zhangwenkang.cinefin.music.data.musicQueueRemoveAt
import com.zhangwenkang.cinefin.music.data.next
import com.zhangwenkang.cinefin.music.data.projectLocalPlaylistTrack
import com.zhangwenkang.cinefin.music.data.reparsedForPlayback
import com.zhangwenkang.cinefin.music.data.toLocalMusicSong
import com.zhangwenkang.cinefin.music.data.toLocalPlaylistTrack
import com.zhangwenkang.cinefin.music.data.toRepeatMode
import com.zhangwenkang.cinefin.music.data.toShuffleEnabled
import com.zhangwenkang.cinefin.music.data.toShuffleMode
import com.zhangwenkang.cinefin.player.core.domain.models.MusicQueue
import com.zhangwenkang.cinefin.player.core.domain.models.PlayerItem
import com.zhangwenkang.cinefin.player.core.domain.models.QueueSource
import com.zhangwenkang.cinefin.player.core.domain.models.RepeatMode
import com.zhangwenkang.cinefin.player.core.domain.models.TrackSource
import com.zhangwenkang.cinefin.player.core.domain.models.TrackSourceType
import com.zhangwenkang.cinefin.player.local.audio.MusicAudioEffectsController
import com.zhangwenkang.cinefin.player.local.audio.MusicAudioOutputInfoSource
import com.zhangwenkang.cinefin.player.local.audio.MusicEqualizerPreset
import com.zhangwenkang.cinefin.player.local.audio.ReplayGainMode
import com.zhangwenkang.cinefin.player.local.domain.MusicPlaybackController
import com.zhangwenkang.cinefin.player.local.domain.MusicPlaybackFailure
import com.zhangwenkang.cinefin.player.local.domain.MusicPlaybackStateSource
import com.zhangwenkang.cinefin.player.local.domain.MusicQueueEditor
import com.zhangwenkang.cinefin.player.local.domain.SleepTimerController
import com.zhangwenkang.cinefin.repository.JellyfinRepository
import com.zhangwenkang.cinefin.repository.UserDataWriteFailure
import com.zhangwenkang.cinefin.repository.UserDataWriteOutcome
import com.zhangwenkang.cinefin.settings.domain.AppPreferences
import com.zhangwenkang.cinefin.utils.DownloadCanonicalIdentity
import com.zhangwenkang.cinefin.utils.DownloadMediaKind
import com.zhangwenkang.cinefin.utils.DownloadMediaRecord
import com.zhangwenkang.cinefin.utils.DownloadMusicIntentRequest
import com.zhangwenkang.cinefin.utils.DownloadTask
import com.zhangwenkang.cinefin.utils.DownloadTaskRules
import com.zhangwenkang.cinefin.utils.DownloadTaskStatus
import com.zhangwenkang.cinefin.utils.Downloader
import com.zhangwenkang.cinefin.utils.OfflineMediaEntryKind
import com.zhangwenkang.cinefin.utils.OfflineMediaRepository
import com.zhangwenkang.cinefin.utils.canonicalIdentity
import com.zhangwenkang.cinefin.utils.downloadIdentity
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber

/** W7-T2：音乐批量下载受理结果（歌单 / 曲库 / 专辑多选），三态口径与视频详情页一致： 已加入队列 / 已在队列 / 已下载；另带单次上限推迟数。 */
data class MusicBatchDownloadFeedback(
    val added: Int = 0,
    val alreadyQueued: Int = 0,
    val alreadyDownloaded: Int = 0,
    val deferredByLimit: Int = 0,
)

/** W7-T2：批量下载反馈文案（纯函数；只拼非零项，全零时给「没有可下载的曲目」）。 */
internal fun musicBatchDownloadFeedbackMessage(feedback: MusicBatchDownloadFeedback): String {
    val parts = buildList {
        if (feedback.added > 0) add("已加入下载队列 · ${feedback.added} 首")
        if (feedback.alreadyQueued > 0) add("已在队列 ${feedback.alreadyQueued} 首")
        if (feedback.alreadyDownloaded > 0) add("已下载 ${feedback.alreadyDownloaded} 首")
        if (feedback.deferredByLimit > 0) add("超出单次上限 ${feedback.deferredByLimit} 首")
    }
    return if (parts.isEmpty()) "没有可下载的曲目" else parts.joinToString("；")
}

/** W7-T2：批量下载前置检查（提示层内容 + 因单次上限推迟的曲目数）。 */
data class MusicBatchPrecheck(
    val content: DownloadPrecheckContent,
    val deferredByLimit: Int,
)

/**
 * 音乐模式 ViewModel（W1 R2 最小闭环，W2 R2 扩到四维浏览 + 队列操作）。
 *
 * 负责：曲库浏览（专辑 / 艺术家 / 歌曲 / 歌单）、"点歌 → 追加队尾并起播（W3-T17）"、 队列操作（拖拽排序 / 下一首播放 / 点队列跳转 / 移除）。播放本身完全交给
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
    private val sleepTimer: SleepTimerController,
    private val persister: MusicQueuePersister,
    private val historyTracker: MusicPlaybackHistoryTracker,
    private val lyricsOverlay: MusicLyricsOverlayController,
    private val playbackStateSource: MusicPlaybackStateSource,
    private val appPreferences: AppPreferences,
    private val audioEffects: MusicAudioEffectsController,
    /** W5-T12（MUSIC-14）：队列播完自动续播；刹车计数由页内传输 / 队列操作清零。 */
    private val autoDjController: MusicAutoDjController,
    /** W5-T6：音频输出信息面板的 Exo `Format` 运行时读数入口。 */
    private val audioOutputInfo: MusicAudioOutputInfoSource,
    private val downloader: Downloader,
    private val jellyfinRepository: JellyfinRepository,
    /** W36：离线模式的曲库来源（本机已下载曲目）。 */
    private val offlineMediaRepository: OfflineMediaRepository,
    /** W37：本地媒体库（用户自选文件夹里的音乐并入曲库）。 */
    private val localLibraryRepository: LocalLibraryRepository,
    /** W87-A：app-scope Room 本地歌单真源（ViewModel 只观察并转发意图）。 */
    private val localPlaylistRepository: LocalPlaylistRepositoryImpl,
    /**
     * W53 Bug B1：从侧栏点具体音乐库进入时，路由参数（`libraryId`）随导航条目进 SavedStateHandle—— 这里按「路由参数 → 客户端设置偏好 →
     * 自动」的优先级解析要加载哪个音乐库。
     */
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {

    /** W34：曲目下载态（下载列表层级化要求音乐侧也能发起下载）。 */
    data class SongDownloadState(
        val downloadedItemIds: Set<UUID> = emptySet(),
        val activeStatuses: Map<DownloadCanonicalIdentity, DownloadTaskStatus> = emptyMap(),
        val downloadedRecords: List<DownloadMediaRecord> = emptyList(),
        val activeTasks: List<DownloadTask> = emptyList(),
    ) {
        fun isDownloaded(itemId: UUID): Boolean = itemId in downloadedItemIds

        fun isDownloaded(song: MusicSong): Boolean =
            song.downloadIdentity()?.let { identity ->
                downloadedRecords.any { it.canonicalIdentity().sameNamespace(identity) }
            } ?: false

        fun activeStatus(itemId: UUID): DownloadTaskStatus? =
            activeStatuses.entries.firstOrNull { it.key.itemId == itemId.toString() }?.value

        fun activeStatus(song: MusicSong): DownloadTaskStatus? {
            val identity = song.downloadIdentity() ?: return null
            return activeStatuses.entries
                .firstOrNull { (key) -> key.matchesProvenance(identity) }
                ?.value
        }

        fun isActive(itemId: UUID): Boolean = activeStatus(itemId) != null

        fun isActive(song: MusicSong): Boolean = activeStatus(song) != null

        fun downloadedIdentities(): Set<DownloadCanonicalIdentity> =
            downloadedRecords.mapTo(mutableSetOf()) { it.canonicalIdentity() }
    }

    private val _downloadState = MutableStateFlow(SongDownloadState())
    val downloadState: StateFlow<SongDownloadState> = _downloadState.asStateFlow()
    private var downloadStateRefreshJob: Job? = null

    /** W60b：下载反馈（已加入队列 · N 首）→ 音乐页 Snackbar（带「查看」跳下载页）。 */
    private val downloadQueuedChannel = Channel<Int>(Channel.BUFFERED)
    val downloadQueued = downloadQueuedChannel.receiveAsFlow()

    /** W7-T2：批量下载三态反馈（已加入 / 已在队列 / 已下载）→ 音乐页 Snackbar。 */
    private val batchDownloadFeedbackChannel = Channel<MusicBatchDownloadFeedback>(Channel.BUFFERED)
    val batchDownloadFeedback = batchDownloadFeedbackChannel.receiveAsFlow()

    private val playlistExchangeFeedbackChannel =
        Channel<PlaylistExchangeFeedback>(Channel.BUFFERED)
    val playlistExchangeFeedback = playlistExchangeFeedbackChannel.receiveAsFlow()

    private val playlistExportReadyChannel = Channel<PlaylistExportReady>(Channel.BUFFERED)
    val playlistExportReady = playlistExportReadyChannel.receiveAsFlow()

    /** W5-T8（MUSIC-48）：播放失败（重试仍失败 / 本地文件不可用）→ 音乐页非阻塞 snackbar。 */
    private val playbackFailureChannel = Channel<MusicPlaybackFailure>(Channel.BUFFERED)
    val playbackFailures = playbackFailureChannel.receiveAsFlow()

    /** W5-T12（MUSIC-14 Auto DJ）：连续续播达 3 批（刹车）→ 音乐页非阻塞 snackbar。 */
    private val autoDjBatchLimitChannel = Channel<Unit>(Channel.CONFLATED)
    val autoDjBatchLimit = autoDjBatchLimitChannel.receiveAsFlow()

    /** W9-T4：收藏写排队（网络 / 5xx 已落本地待同步）→ 音乐页非阻塞 snackbar（与影视详情页同口径）。 */
    private val favoriteWriteNoticeChannel = Channel<String>(Channel.BUFFERED)
    val favoriteWriteNotices = favoriteWriteNoticeChannel.receiveAsFlow()

    /** W3-T20：手动刷新元数据的成功 / 失败反馈 → 音乐页非阻塞 snackbar。 */
    private val metadataRefreshFeedbackChannel = Channel<String>(Channel.BUFFERED)
    val metadataRefreshFeedback = metadataRefreshFeedbackChannel.receiveAsFlow()

    /** W3-T20：无封面补取已尝试的曲目（进程内一次；失败不重试，避免批量轰炸）。 */
    private val artworkBackfillTried = mutableSetOf<UUID>()
    private var artworkBackfillJob: Job? = null

    /** W58：长按多选状态（按曲目 itemId 选中；列表刷新后按 id 求交集）。 */
    private val _batchSelectionState = MutableStateFlow(MusicBatchSelectionState())
    val batchSelection: StateFlow<MultiSelectState> =
        _batchSelectionState
            .map { state -> state.selection }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.Eagerly,
                initialValue = MultiSelectState(),
            )

    data class UiState(
        val loading: Boolean = true,
        /** W39：下拉刷新进行中（与首次加载区分：刷新保留现有列表，只转顶部指示）。 */
        val refreshing: Boolean = false,
        val tab: MusicTab = MusicTab.SUGGESTIONS,
        /** W36：当前曲库来源是否为离线（离线模式只显示本机已下载曲目）。 */
        val offline: Boolean = false,
        val albums: List<MusicAlbum> = emptyList(),
        val artists: List<MusicArtist> = emptyList(),
        val songs: List<MusicSong> = emptyList(),
        val playlists: List<MusicPlaylist> = emptyList(),
        /** W37：曲库来源筛选（全部 / 服务器 / 本地）。 */
        val sourceFilter: MusicItemSourceFilter = MusicItemSourceFilter.ALL,
        /** W37：曲目行是否显示来源徽标。 */
        val showSourceBadge: Boolean = true,
        /** W66：音乐库选择器候选（「全部音乐库」+ 各库；离线 / 未加载时为空 → 隐藏）。 */
        val musicLibraries: List<LibrarySelectorOption> = emptyList(),
        /** W66：当前选中的音乐库 id（null = 全部音乐库）。 */
        val selectedMusicLibraryId: UUID? = null,
        /** W66：当前选中音乐库名（null = 全部 → 顶栏沿用「音乐」标题）。 */
        val selectedMusicLibraryName: String? = null,
        /** W3-T14：歌曲 Tab 客户端排序键 / 方向 / 每页数量（落偏好，冷启恢复）。 */
        val songSortKey: MusicSongSortKey = MusicSongSortKey.NAME,
        val songSortAscending: Boolean = true,
        val songPageSize: Int = MUSIC_SONG_DEFAULT_PAGE_SIZE,
        /** W3-T14：歌曲 Tab 当前页（0 基）；排序 / 筛选 / 每页数量变化时复位到第 1 页。 */
        val songPage: Int = 0,
        /** W3-T14：筛选「仅收藏」（只作用于歌曲 Tab）。 */
        val songsFavoritesOnly: Boolean = false,
        /** W3-T14：播放历史时间表（itemId → 最近播放时间；播放日期排序与「建议 · 最常播放」用）。 */
        val recentPlayedAt: Map<UUID, Long> = emptyMap(),
        /** W37：本地曲目条数（筛选行副文案用）。 */
        val localSongCount: Int = 0,
        val serverSongCount: Int = 0,
        val detail: MusicDetail? = null,
        /** 错误标题（错误态面板顶部文案：曲库加载 / 播放 / 歌单加载）。 */
        val errorTitle: String? = null,
        val errorMessage: String? = null,
        /** 曲库 / 歌单来源加载错误；已有内容时作为非阻塞提示显示。 */
        val loadErrorTitle: String? = null,
        val loadErrorMessage: String? = null,
    ) {
        /** 当前详情页的曲目；无详情时为空。 */
        val detailSongs: List<MusicSong>
            get() = detail?.songs ?: emptyList()

        /** W3-T14：歌曲 Tab 总页数（空列表 = 1 页）。 */
        val songPageCount: Int
            get() = musicSongPageCount(songs.size, songPageSize)

        /** W3-T14：歌曲 Tab 当前页切片（专辑 / 艺术家 / 歌单 Tab 不分页）。 */
        val songPageSongs: List<MusicSong>
            get() = musicSongPageSlice(songs, songPage, songPageSize)

        /** W3-T14：「建议」Tab 三段的展示数据（空段已隐藏）。 */
        internal val suggestionSections: List<MusicSuggestionSection>
            get() = musicSuggestionSections(songs, recentPlayedAt)
    }

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    /**
     * 歌词面板状态（MU-5）。
     *
     * [languages] 由逐行识别结果聚合（原文恒在末位）；[display] 默认简体中文（[LyricsPresenter.defaultDisplay]）；
     * [activeIndex] 由 [LyricsPresenter.activeIndex] 根据播放位置算出，供高亮与跟随滚动使用。 [text] 是字号 / 行距档位（W79
     * · #37，落偏好键，冷启保持）。
     */
    data class LyricsUiState(
        val open: Boolean = false,
        val loading: Boolean = false,
        val title: String? = null,
        val document: LyricsDocument? = null,
        val languages: List<LyricsDisplayLanguage> = emptyList(),
        val display: LyricsDisplayState = LyricsDisplayState(),
        val text: LyricsTextSettings = LyricsTextSettings(),
        val rows: List<LyricsRow> = emptyList(),
        val activeIndex: Int = 0,
        /** 播放位置（毫秒）：逐字高亮推进用（W28-MUSIC）。 */
        val positionMs: Long = 0,
        val message: String? = null,
    )

    private val _lyricsState = MutableStateFlow(LyricsUiState(text = storedLyricsText()))
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
    private var restorePlayJob: Job? = null
    private var nextPlayRequestId = 0L
    /** W82 #25：搜索路由要求起播的曲目（曲库尚未加载时暂存，加载完成后再播）。 */
    private var pendingRouteSongId: UUID? = null
    private var pendingRouteSongIds: List<UUID>? = null

    /** W37：服务器（或离线已下载）曲库与本地媒体库曲库分开缓存，切换来源筛选不重新请求。 */
    private var serverLibrary: MusicLibrary = MusicLibrary(emptyList(), emptyList(), emptyList())
    private var localLibrary: MusicLibrary = MusicLibrary(emptyList(), emptyList(), emptyList())
    private var serverPlaylists: List<MusicPlaylist> = emptyList()
    private var localPlaylists: List<LocalPlaylist> = emptyList()
    private var localPlaylistEntriesJob: Job? = null
    private var restoredSnapshot: MusicQueueSnapshot? = null

    /** 上次会话的队列快照（W21-R2）：未点播放前只在本地展示 / 编辑。 */
    private val _restoredQueue = MutableStateFlow<MusicQueue?>(null)

    /** 正在播放的队列（当前曲目 / 播放模式 / 顺序）；无播放会话时回落到恢复快照，底栏与队列面板直接消费。 */
    val queue: StateFlow<MusicQueue?> =
        combine(playbackController.queue, _restoredQueue) { live, restored -> live ?: restored }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** 点歌后、目标曲解析完成前的乐观展示目标；只影响当前曲高亮与底部条，不碰真实播放队列。 */
    private val _pendingPlayback = MutableStateFlow<PendingPlaybackUi?>(null)
    val optimisticCurrentItem: StateFlow<PlayerItem?> =
        _pendingPlayback
            .map { pending -> pending?.item }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** 当前曲高亮 / 底部条使用的展示当前曲：乐观目标优先，否则回落到真实队列（或恢复快照）。 */
    val displayCurrentItem: StateFlow<PlayerItem?> =
        combine(queue, _pendingPlayback) { currentQueue, pending ->
                displayPlaybackItem(pending, currentQueue?.currentItem)
            }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** 恢复态（重启后尚未点播放）：底栏文案与播放键语义据此区分。 */
    val isRestored: StateFlow<Boolean> =
        combine(playbackController.queue, _restoredQueue) { live, restored ->
                live == null && restored != null
            }
            .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** 睡眠定时（W55 统一，前身 W21-R2 MU-7）：进程级单例，与视频 / 播放器共用；离开音乐页 / 后台播放时仍生效。 */
    val sleepTimerState: StateFlow<SleepTimerController.State> = sleepTimer.state

    /** 是否正在播放（底栏按钮图标用）。 */
    val isPlaying: StateFlow<Boolean> = playbackStateSource.isPlaying

    /** 播放模式（W23-MUSIC · C 组）：从队列的 repeatMode + shuffleEnabled 反推四种模式， 队列被持久化时模式跟着一起落盘，不需要额外偏好键。 */
    val playMode: StateFlow<MusicPlayMode> =
        combine(playbackController.queue, _restoredQueue) { live, restored ->
                val queue = live ?: restored
                musicPlayModeOf(
                    repeatMode = queue?.repeatMode ?: RepeatMode.OFF,
                    shuffleEnabled = queue?.shuffleEnabled ?: false,
                    shuffleKeepCurrentFirst = queue?.shuffleKeepCurrentFirst ?: false,
                )
            }
            .stateIn(viewModelScope, SharingStarted.Eagerly, MusicPlayMode.SEQUENTIAL)

    /** 当前播放位置（毫秒）：底栏时间与全屏进度条共用；恢复态没有会话时为 0（由 UI 回落到快照位置）。 */
    val positionMs: StateFlow<Long> = playbackController.positionMs

    /** 当前曲目总时长（毫秒）：内核还没给出时为 0，UI 用曲库元数据兜底。 */
    val durationMs: StateFlow<Long> = playbackStateSource.durationMs

    /** W5-T8（MUSIC-49）：缓冲中（播放意图不变，只叠加轻量指示）。 */
    val isBuffering: StateFlow<Boolean> = playbackStateSource.isBuffering

    /** 桌面歌词悬浮窗状态（W23-MUSIC · D 组）：开关 / 颜色 / 字号 / 语言 / 锁定都在这里。 */
    val lyricsOverlayState: StateFlow<MusicLyricsOverlayController.State> = lyricsOverlay.state

    // W30-MUSIC-FX：音效面板状态（EQ / ReplayGain / 淡入淡出），实现与偏好都在 player:local 的音效中枢。
    val effectsEqualizerEnabled: StateFlow<Boolean> = audioEffects.equalizerEnabled
    val effectsEqualizerPreset: StateFlow<MusicEqualizerPreset> = audioEffects.equalizerPreset
    val effectsEqualizerBands: StateFlow<List<Float>> = audioEffects.equalizerBands
    val effectsReplayGainMode: StateFlow<ReplayGainMode> = audioEffects.replayGainMode
    val effectsReplayGainLabel: StateFlow<String?> = audioEffects.replayGainLabel
    val effectsCrossfadeSeconds: StateFlow<Int> = audioEffects.crossfadeSeconds
    /** W5-T6 · MUSIC-32：音乐倍速（进程级，不落偏好）。 */
    val effectsPlaybackSpeed: StateFlow<Float> = audioEffects.playbackSpeed
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

    /** W82 #25：搜索结果打开曲目。直接从服务器 / 本地曲库快照起播，不受当前来源筛选影响； 曲库尚未加载时记下 id，等 [publishLibraries] 后补播一次。 */
    fun playSongFromRoute(rawItemId: String, rawQueueItemIds: String? = null) {
        val itemId = runCatching { UUID.fromString(rawItemId) }.getOrNull() ?: return
        if (rawQueueItemIds == null) {
            playRouteSong(itemId)
        } else {
            playRouteSongs(parseMusicQueueIds(rawQueueItemIds, itemId))
        }
    }

    /**
     * W1-T08：搜索结果打开音乐条目。
     *
     * - 专辑 / 艺术家 / 歌单结果进各自详情层（与库内点卡片的链路一致）；
     * - 服务器歌曲结果（`Audio`）起播后再打开所属专辑详情——音乐页没有「单曲详情」层，专辑详情是曲目所属的唯一详情页；
     * - 本地曲目 / 已下载音乐 / 下载通知等入口（不带类型）保持原有「进音乐页并起播」。
     *
     * 曲库 / 歌单尚未加载时先挂起，发布后补开一次。
     */
    fun openItemFromRoute(
        rawItemId: String,
        kind: String? = null,
        title: String? = null,
        rawQueueItemIds: String? = null,
    ) {
        val itemId = runCatching { UUID.fromString(rawItemId) }.getOrNull() ?: return
        if (rawQueueItemIds != null) {
            playSongFromRoute(rawItemId, rawQueueItemIds)
            return
        }
        val target = musicRouteDetailTarget(kind, title, itemId)
        if (target == null) {
            playSongFromRoute(rawItemId)
            if (kind.equals("AUDIO", ignoreCase = true)) {
                pendingRouteSongAlbumId = itemId
                retryPendingRouteSongAlbum()
            }
            return
        }
        if (!openRouteDetail(target)) pendingRouteDetail = target
    }

    /** 路由要求的详情层（曲库 / 歌单未加载完时暂存，发布后重试一次）。 */
    private var pendingRouteDetail: MusicRouteDetailTarget? = null

    /** 搜索点开的曲目（曲库未加载完时暂存 id，发布后补开所属专辑详情）。 */
    private var pendingRouteSongAlbumId: UUID? = null

    private fun openRouteDetail(target: MusicRouteDetailTarget): Boolean {
        val state = _uiState.value
        val songs = serverLibrary.songs + localLibrary.songs
        val albums = serverLibrary.albums + localLibrary.albums
        val artists = serverLibrary.artists + localLibrary.artists
        return when (target) {
            is MusicRouteDetailTarget.Album -> {
                val album = findMusicAlbum(albums, songs, target.name) ?: return false
                openAlbum(album)
                true
            }
            is MusicRouteDetailTarget.Artist -> {
                val artist = findMusicArtist(artists, songs, target.name) ?: return false
                openArtist(artist)
                true
            }
            is MusicRouteDetailTarget.Playlist -> {
                val playlist =
                    state.playlists.firstOrNull { it.id == target.id }
                        ?: serverPlaylists.firstOrNull { it.id == target.id }
                        ?: return false
                openPlaylist(playlist)
                true
            }
        }
    }

    private fun retryPendingRouteDetail() {
        pendingRouteDetail?.let { target -> if (openRouteDetail(target)) pendingRouteDetail = null }
        retryPendingRouteSongAlbum()
    }

    /** 曲目 → 所属专辑详情（曲目或专辑解析不到时丢弃，不阻塞起播）。 */
    private fun retryPendingRouteSongAlbum() {
        val songId = pendingRouteSongAlbumId ?: return
        val songs = serverLibrary.songs + localLibrary.songs
        val song = songs.firstOrNull { it.itemId == songId } ?: return
        pendingRouteSongAlbumId = null
        val album =
            findMusicAlbum(serverLibrary.albums + localLibrary.albums, songs, song.albumName)
        if (album != null) openAlbum(album)
    }

    /**
     * W3-T17：单曲路由（搜索结果 / 下载页单条 / 通知点开）走与行点击一致的「追加队尾并起播」，
     * 不再用整份曲库列表重建队列；显式多选排序参数（[playRouteSongs]）仍按给定列表整列播放。
     */
    private fun playRouteSong(itemId: UUID) {
        val serverIndex = serverLibrary.songs.indexOfFirst { it.itemId == itemId }
        if (serverIndex >= 0) {
            pendingRouteSongId = null
            val song = serverLibrary.songs[serverIndex]
            playSongAppend(
                song = song,
                tailSongs = listOf(song),
                source = QueueSource.MANUAL,
                sourceId = null,
                snapshotItems = emptyMap(),
            )
            return
        }
        val localIndex = localLibrary.songs.indexOfFirst { it.itemId == itemId }
        if (localIndex >= 0) {
            pendingRouteSongId = null
            val song = localLibrary.songs[localIndex]
            playSongAppend(
                song = song,
                tailSongs = listOf(song),
                source = QueueSource.MANUAL,
                sourceId = null,
                snapshotItems = emptyMap(),
            )
            return
        }
        pendingRouteSongId = itemId
    }

    private fun playPendingRouteSong() {
        pendingRouteSongId?.let(::playRouteSong)
        pendingRouteSongIds?.let(::playRouteSongs)
        retryPendingRouteDetail()
    }

    /** 下载页多选播放：保持下载页选择顺序，只播放已解析到的本地 / 服务器曲目。 */
    private fun playRouteSongs(itemIds: List<UUID>) {
        val songs = orderedMusicSongs(itemIds, serverLibrary.songs, localLibrary.songs)
        if (songs.isEmpty()) {
            pendingRouteSongIds = itemIds
            return
        }
        pendingRouteSongIds = null
        pendingRouteSongId = null
        startQueuePlayback(
            song = songs.first(),
            songs = songs,
            startIndex = 0,
            source = QueueSource.MANUAL,
            sourceId = null,
        )
    }

    /** W36：离线模式开关变化时曲库来源也跟着变（离线 = 本机已下载曲目，在线 = 服务器曲库）。 */
    private val offlineModeListener =
        android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == appPreferences.offlineMode.backendName) {
                refresh()
            }
        }

    init {
        persister.start()
        historyTracker.start()
        lyricsOverlay.start()
        // W5-T12：Auto DJ 进程级单例启动（幂等；离开音乐页后队列播完仍可续播）。
        autoDjController.start()
        // W37：来源筛选 / 来源徽标从偏好恢复。
        // W3-T14：歌曲 Tab 排序键 / 方向 / 每页数量同样从偏好恢复。
        _uiState.update {
            it.copy(
                sourceFilter =
                    MusicItemSourceFilter.fromName(
                        appPreferences.getValue(appPreferences.localLibraryMusicSource)
                    ),
                showSourceBadge = appPreferences.getValue(appPreferences.localLibrarySourceBadge),
                songSortKey =
                    MusicSongSortKey.fromPrefValue(
                        appPreferences.getValue(appPreferences.musicSongSortKey)
                    ),
                songSortAscending = appPreferences.getValue(appPreferences.musicSongSortAscending),
                songPageSize =
                    normalizeMusicSongPageSize(
                        appPreferences.getValue(appPreferences.musicSongPageSize)
                    ),
            )
        }
        refresh(forceServerPlaylistRefresh = false)
        observeLocalPlaylists()
        observeLyrics()
        restoreQueue()
        observeRecent()
        viewModelScope.launch { refreshRecentPlayedAt() }
        observeOverlayArtists()
        observeRestoredQueueCleanup()
        observePlaybackFailures()
        observeAutoDjBatchLimit()
        viewModelScope.launch { downloader.queueChanges.collect { refreshDownloadState() } }
        // W36：进入页面后切离线 / 回在线也要立刻重载曲库（VM 复用同一实例）。
        appPreferences.sharedPreferences.registerOnSharedPreferenceChangeListener(
            offlineModeListener
        )
    }

    override fun onCleared() {
        appPreferences.sharedPreferences.unregisterOnSharedPreferenceChangeListener(
            offlineModeListener
        )
        super.onCleared()
    }

    private fun observeLocalPlaylists() {
        viewModelScope.launch {
            localPlaylistRepository.playlists.collect { playlists ->
                localPlaylists = playlists
                publishPlaylists()
                val detail = _uiState.value.detail as? MusicDetail.Playlist
                if (detail?.playlist?.kind == MusicPlaylistKind.LOCAL) {
                    refreshLocalPlaylistEntries(detail.playlist.id)
                }
            }
        }
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
            val snapshot = runCatching { persister.load() }.getOrNull()
            restoredSnapshot = snapshot
            if (snapshot == null) return@launch
            if (playbackController.queue.value == null) {
                _restoredQueue.value = snapshot.queue
            }
        }
    }

    /** 优先用活动队列，其次用本次恢复态编辑后的队列，最后才用磁盘只读快照。 */
    private fun latestQueueSnapshot(): MusicQueueSnapshot? =
        playbackController.queue.value?.let { queue ->
            MusicQueueSnapshot(
                queue = queue,
                positionMs = playbackController.positionMs.value,
                savedAt = 0L,
            )
        }
            ?: _restoredQueue.value?.let { queue ->
                MusicQueueSnapshot(
                    queue = queue,
                    positionMs = queue.currentItem?.playbackPosition ?: 0L,
                    savedAt = restoredSnapshot?.savedAt ?: 0L,
                )
            }
            ?: restoredSnapshot

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
                        // W3-T14：播放历史变化 → 播放日期排序 / 建议「最常播放」段立即跟上
                        refreshRecentPlayedAt()
                    }
                }
        }
    }

    /** W3-T14：重读播放历史时间表；只有「播放日期」排序需要立即重排列表。 */
    private suspend fun refreshRecentPlayedAt() {
        val playedAt = runCatching {
            recentStore.loadPlayedAt()
        }
            .getOrElse {
                return
            }
        if (playedAt == _uiState.value.recentPlayedAt) return
        _uiState.update { state ->
            val songs =
                if (state.songSortKey == MusicSongSortKey.DATE_PLAYED) {
                    sortMusicSongs(
                        songs = state.songs,
                        key = state.songSortKey,
                        ascending = state.songSortAscending,
                        playedAt = playedAt,
                    )
                } else {
                    state.songs
                }
            state.copy(recentPlayedAt = playedAt, songs = songs)
        }
    }

    /** 播放器装载队列后清掉恢复快照（通知栏 / 锁屏续播等非本页路径同样适用）。 */
    private fun observeRestoredQueueCleanup() {
        viewModelScope.launch {
            playbackController.queue.filterNotNull().collect { _restoredQueue.value = null }
        }
    }

    /** W5-T8（MUSIC-48）：控制器的失败事件转进页内 snackbar 通道（非阻塞，不弹模态）。 */
    private fun observePlaybackFailures() {
        viewModelScope.launch {
            playbackStateSource.playbackFailures.collect { failure ->
                playbackFailureChannel.send(failure)
            }
        }
    }

    /** W5-T12（MUSIC-14）：Auto DJ 刹车提示转进页内 snackbar 通道（用户任一传输操作后计数清零、可继续续播）。 */
    private fun observeAutoDjBatchLimit() {
        viewModelScope.launch {
            autoDjController.batchLimitReached.collect { autoDjBatchLimitChannel.trySend(Unit) }
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
            _lyricsState.value = LyricsUiState(text = storedLyricsText())
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
                    val display =
                        LyricsPresenter.displayForNewDocument(
                            document,
                            bilingual = bilingualEnabled(),
                            previous = state.display,
                        )
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

    /** 双语对照开关（W79 · #35）：首次操作即落显式偏好（`on` / `off`），此后冷启不再被"默认开"覆盖。 */
    fun toggleLyricsBilingual() {
        val bilingual = !_lyricsState.value.display.bilingual
        appPreferences.setValue(
            appPreferences.musicLyricsBilingual,
            LyricsBilingualPreference.encode(bilingual),
        )
        updateLyricsDisplay { it.copy(bilingual = bilingual) }
    }

    /** 跟随滚动开关；关闭后高亮仍随播放位置变化，只是不自动滚动。 */
    fun toggleLyricsFollow() {
        updateLyricsDisplay { it.copy(follow = !it.follow) }
    }

    /** 歌词字号档位（W79 · #37）：即时生效 + 落盘（歌词页与面板共用同一档位）。 */
    fun selectLyricsFontScale(scale: LyricsFontScale) {
        appPreferences.setValue(appPreferences.musicLyricsFontScale, scale.key)
        _lyricsState.update { it.copy(text = it.text.withFontScale(scale)) }
    }

    /** 歌词行距档位（W79 · #37）：即时生效 + 落盘。 */
    fun selectLyricsLineSpacing(spacing: LyricsLineSpacing) {
        appPreferences.setValue(appPreferences.musicLyricsLineSpacing, spacing.key)
        _lyricsState.update { it.copy(text = it.text.withLineSpacing(spacing)) }
    }

    /** 点击歌词行：跳到该行时间戳（不改变播放 / 暂停状态）。 */
    fun seekToLyricLine(startMs: Long) {
        autoDjController.onUserTransportAction()
        playbackController.seekTo(startMs)
    }

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

    /** 已落盘的歌词字号 / 行距档位（W79 · #37；未设置 / 未知值回落"标准"）。 */
    private fun storedLyricsText(): LyricsTextSettings =
        LyricsTextSettings.fromKeys(
            appPreferences.getValue(appPreferences.musicLyricsFontScale),
            appPreferences.getValue(appPreferences.musicLyricsLineSpacing),
        )

    /** 双语对照生效值（W79 · #35）：显式偏好优先，从未设置 → 默认开。 */
    private fun bilingualEnabled(): Boolean =
        LyricsBilingualPreference.resolve(
            appPreferences.getValue(appPreferences.musicLyricsBilingual)
        )

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
    fun refresh() = refresh(forceServerPlaylistRefresh = true)

    private fun refresh(forceServerPlaylistRefresh: Boolean) {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            // W39 验收取证：下拉刷新 / 重试都走这里；logcat 关键词「曲库刷新」。
            val offlineMode = appPreferences.getValue(appPreferences.offlineMode)
            Timber.d("曲库刷新：requested offline=%s", offlineMode)
            // W39：首次加载走整页 loading；已有内容时走 PullToRefreshBox 指示，列表不闪空。
            val firstLoad = _uiState.value.loading
            _uiState.update {
                if (firstLoad) {
                    it.copy(
                        loading = true,
                        errorTitle = null,
                        errorMessage = null,
                        loadErrorTitle = null,
                        loadErrorMessage = null,
                    )
                } else {
                    it.copy(
                        refreshing = true,
                        errorTitle = null,
                        errorMessage = null,
                        loadErrorTitle = null,
                        loadErrorMessage = null,
                    )
                }
            }
            try {
                // 曲库与歌单摘要独立加载：歌单命中磁盘摘要后可先上屏，不等待曲库请求或重试。
                val sources =
                    loadMusicLibrarySources(
                        offline = offlineMode,
                        forceServerPlaylistRefresh = forceServerPlaylistRefresh,
                        loadLibrary = { loadLibraryWithRetry() },
                        loadOfflineLibrary = {
                            runCatching { loadOfflineLibrary() }
                                .onFailure { Timber.w(it, "读取离线曲库失败") }
                                .getOrElse { MusicLibrary(emptyList(), emptyList(), emptyList()) }
                        },
                        loadPlaylists = { forceRefresh ->
                            repository.getPlaylists(forceRefresh = forceRefresh)
                        },
                        onLibraryLoaded = { library ->
                            serverLibrary = library
                            _uiState.update { it.copy(loading = false, offline = offlineMode) }
                            publishLibraries()
                            playPendingRouteSong()
                            scheduleArtworkBackfill()
                        },
                        onPlaylistsLoaded = { playlists ->
                            serverPlaylists = playlists
                            _uiState.update { it.copy(loading = false, offline = offlineMode) }
                            publishPlaylists()
                        },
                    )
                localLibrary = loadLocalLibrary()

                if (offlineMode) {
                    Timber.d("曲库刷新：离线模式 → 只重读本机索引（不发服务器请求）")
                    serverPlaylists = emptyList()
                    _uiState.update {
                        it.copy(
                            loading = false,
                            refreshing = false,
                            offline = true,
                            musicLibraries = emptyList(),
                            selectedMusicLibraryId = null,
                            selectedMusicLibraryName = null,
                            errorTitle = null,
                            errorMessage = null,
                            loadErrorTitle = null,
                            loadErrorMessage = null,
                        )
                    }
                    publishPlaylists()
                    publishLibraries()
                    playPendingRouteSong()
                    Timber.d(
                        "曲库刷新：完成 offline=true albums=%d songs=%d",
                        _uiState.value.albums.size,
                        _uiState.value.songs.size,
                    )
                    refreshDownloadState()
                    return@launch
                }

                // W9-T5（F4）：降级曲库（已下载）只在原本没有曲库时填补，绝不覆盖已上屏的服务器曲库。
                val onlineLibrary = sources.library.takeIf { !sources.libraryFallback }
                onlineLibrary?.let { serverLibrary = it }
                if (onlineLibrary == null && serverLibrary.songs.isEmpty()) {
                    sources.library?.let { serverLibrary = it }
                }
                sources.playlists?.let { serverPlaylists = it }
                val musicLibraries =
                    if (onlineLibrary != null) loadMusicLibraryList() else emptyList()
                val selectedMusicLibrary =
                    if (onlineLibrary != null) {
                        resolveSelectedMusicLibrary(
                            musicLibraries,
                            appPreferences.getValue(appPreferences.uiMusicLibraryId),
                        )
                    } else {
                        null
                    }
                _uiState.update {
                    it.copy(
                        loading = false,
                        refreshing = false,
                        offline = false,
                        musicLibraries =
                            if (onlineLibrary != null) {
                                musicLibraryOptions(musicLibraries)
                            } else {
                                it.musicLibraries
                            },
                        selectedMusicLibraryId =
                            if (onlineLibrary != null) {
                                selectedMusicLibrary?.id
                            } else {
                                it.selectedMusicLibraryId
                            },
                        selectedMusicLibraryName =
                            if (onlineLibrary != null) {
                                selectedMusicLibrary?.name
                            } else {
                                it.selectedMusicLibraryName
                            },
                        errorTitle = null,
                        errorMessage = null,
                        loadErrorTitle = null,
                        loadErrorMessage = null,
                    )
                }
                publishPlaylists()
                publishLibraries()
                val loadError = musicSourceLoadError(sources)
                _uiState.update {
                    it.copy(
                        tab =
                            musicTabAfterInitialSourceLoad(
                                currentTab = it.tab,
                                firstLoad = firstLoad,
                                libraryFailed = sources.libraryError != null,
                                albumCount = it.albums.size,
                                playlistCount = serverPlaylists.size,
                            ),
                        loadErrorTitle = loadError?.first,
                        loadErrorMessage = loadError?.second,
                    )
                }
                playPendingRouteSong()
                Timber.d(
                    "曲库刷新：完成 offline=false albums=%d songs=%d",
                    _uiState.value.albums.size,
                    _uiState.value.songs.size,
                )
                refreshDownloadState()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                // 服务器不可达时本地媒体库仍然可用（错误面板关闭后能看到本地曲目）。
                localLibrary = loadLocalLibrary()
                // W9-T5（F4）：整次刷新失败（非单源失败）时同样降级读已下载曲库，冷启动离线也能看到已下载音乐。
                if (serverLibrary.songs.isEmpty()) {
                    runCatching { loadOfflineLibrary() }
                        .onFailure { Timber.w(it, "读取离线曲库失败") }
                        .getOrNull()
                        ?.takeIf { offlineLibrary -> offlineLibrary.songs.isNotEmpty() }
                        ?.let { offlineLibrary ->
                            serverLibrary = offlineLibrary
                            Timber.d(
                                "曲库刷新失败，降级到已下载离线曲库 songs=%d",
                                offlineLibrary.songs.size,
                            )
                        }
                }
                publishLibraries()
                publishPlaylists()
                _uiState.update {
                    it.copy(
                        loading = false,
                        refreshing = false,
                        errorTitle = "曲库加载失败",
                        errorMessage =
                            error.message?.takeIf { message -> message.isNotBlank() }
                                ?: "无法连接服务器，请检查网络后重试",
                    )
                }
            }
        }
    }

    /** W37：来源筛选（全部 / 服务器 / 本地）与来源徽标开关。 */
    fun setSourceFilter(filter: MusicItemSourceFilter) {
        if (_uiState.value.sourceFilter == filter) return
        appPreferences.setValue(appPreferences.localLibraryMusicSource, filter.name)
        _batchSelectionState.update { state -> state.userChanged(state.selection) }
        _uiState.update { it.copy(sourceFilter = filter) }
        publishLibraries()
    }

    fun setShowSourceBadge(show: Boolean) {
        appPreferences.setValue(appPreferences.localLibrarySourceBadge, show)
        _uiState.update { it.copy(showSourceBadge = show) }
    }

    /**
     * W3-T14 工具行「排序」：歌曲 Tab 客户端排序键。
     *
     * 只重排当前已加载列表（先落盘再重排），页码复位到第 1 页；不改服务端请求。
     */
    fun setSongSortKey(key: MusicSongSortKey) {
        if (_uiState.value.songSortKey == key) return
        appPreferences.setValue(appPreferences.musicSongSortKey, key.prefValue)
        _uiState.update { it.copy(songSortKey = key, songPage = 0) }
        publishLibraries()
    }

    /** W3-T14 工具行「排序」：升 / 降序。 */
    fun setSongSortAscending(ascending: Boolean) {
        if (_uiState.value.songSortAscending == ascending) return
        appPreferences.setValue(appPreferences.musicSongSortAscending, ascending)
        _uiState.update { it.copy(songSortAscending = ascending, songPage = 0) }
        publishLibraries()
    }

    /** W3-T14 工具行「每页数量」：50 / 100 / 200（非法值回落默认 100）。 */
    fun setSongPageSize(size: Int) {
        val normalized = normalizeMusicSongPageSize(size)
        if (_uiState.value.songPageSize == normalized) return
        appPreferences.setValue(appPreferences.musicSongPageSize, normalized)
        _uiState.update {
            it.copy(
                songPageSize = normalized,
                songPage = 0,
            )
        }
    }

    /** W3-T14 工具行「筛选 · 仅收藏」（只作用于歌曲 Tab，不落盘）。 */
    fun setSongsFavoritesOnly(enabled: Boolean) {
        if (_uiState.value.songsFavoritesOnly == enabled) return
        _uiState.update { it.copy(songsFavoritesOnly = enabled, songPage = 0) }
        publishLibraries()
    }

    /** W3-T14 底部分页栏：0 基页码，越界自动收敛。 */
    fun setSongPage(page: Int) {
        _uiState.update { state ->
            state.copy(
                songPage =
                    clampMusicSongPage(
                        page,
                        musicSongPageCount(state.songs.size, state.songPageSize),
                    )
            )
        }
    }

    /** W66：顶栏音乐库选择（null = 「全部音乐库」）。先落盘（跨页面 / 重启保留）再即时重载当前 Tab： 关闭页内详情、退出多选，然后重新请求曲库（标题语义随选中库更新）。 */
    fun selectMusicLibrary(libraryId: UUID?) {
        if (_uiState.value.selectedMusicLibraryId == libraryId) return
        appPreferences.setValue(appPreferences.uiMusicLibraryId, libraryId?.toString())
        clearBatchSelection()
        _uiState.update { it.copy(detail = null) }
        refresh()
    }

    /** 当前筛选下的曲库视图（专辑 / 艺术家 / 歌曲三个维度同源重建）。 */
    private fun publishLibraries() {
        val songs =
            when (_uiState.value.sourceFilter) {
                MusicItemSourceFilter.ALL -> serverLibrary.songs + localLibrary.songs
                MusicItemSourceFilter.SERVER -> serverLibrary.songs
                MusicItemSourceFilter.LOCAL -> localLibrary.songs
            }
        // W3-T14：「筛选 · 仅收藏」+ 客户端排序只作用于歌曲 Tab 列表；专辑 / 艺术家分组仍用全量曲目。
        val state = _uiState.value
        val visibleSongs =
            sortMusicSongs(
                songs = if (state.songsFavoritesOnly) songs.filter { it.isFavorite } else songs,
                key = state.songSortKey,
                ascending = state.songSortAscending,
                playedAt = state.recentPlayedAt,
            )
        _uiState.update {
            it.copy(
                songs = visibleSongs,
                albums = groupAlbums(songs),
                artists = groupArtists(songs),
                localSongCount = localLibrary.songs.size,
                serverSongCount = serverLibrary.songs.size,
                songPage =
                    clampMusicSongPage(
                        it.songPage,
                        musicSongPageCount(visibleSongs.size, it.songPageSize),
                    ),
            )
        }
        val detail = _uiState.value.detail as? MusicDetail.Playlist
        if (detail?.playlist?.kind == MusicPlaylistKind.LOCAL) {
            refreshLocalPlaylistEntries(detail.playlist.id)
        }
    }

    /** W37：本地媒体库里的音乐条目 → 曲目（内嵌标签优先，缺失回退「本地文件」专辑）。 */
    private suspend fun loadLocalLibrary(): MusicLibrary {
        val songs = runCatching {
            localLibraryRepository.libraries().flatMap { library ->
                localLibraryRepository
                    .entries(library.id)
                    .filter { entry -> entry.kind == LocalMediaKind.MUSIC }
                    .map { entry -> entry.toLocalMusicSong(library.id) }
            }
        }
            .onFailure { Timber.w(it, "读取本地媒体库失败") }
            .getOrElse { emptyList() }
            .sortedWith(compareBy({ it.albumName }, { it.indexNumber ?: 0 }, { it.name }))
        Timber.d("曲库刷新：重读本地媒体库索引 songs=%d", songs.size)
        return MusicLibrary(
            songs = songs,
            albums = groupAlbums(songs),
            artists = groupArtists(songs),
        )
    }

    private fun publishPlaylists() {
        val localRows = localPlaylists.map { playlist ->
            MusicPlaylist(
                id = playlist.id,
                name = playlist.name,
                imageUri = null,
                songCount = playlist.entryCount,
                kind = MusicPlaylistKind.LOCAL,
            )
        }
        _uiState.update { it.copy(playlists = localRows + serverPlaylists) }
        retryPendingRouteDetail()
    }

    /** 第一次失败视为偶发（服务端超时 / 弱网），自动重试一次后仍失败才抛给调用方。 */
    private suspend fun loadLibraryWithRetry(): MusicLibrary =
        try {
            loadLibraryOnce()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (first: Exception) {
            Timber.w(first, "曲库加载失败，自动重试一次")
            delay(AUTO_RETRY_DELAY_MS)
            loadLibraryOnce()
        }

    private suspend fun loadLibraryOnce(): MusicLibrary {
        val libraryId = selectedMusicLibraryId()
        Timber.d("曲库刷新：重新请求服务器曲库 library=%s", libraryId)
        return repository.getLibrary(libraryId = libraryId)
    }

    private fun musicSourceLoadError(sources: MusicLibrarySources): Pair<String, String>? {
        val libraryMessage =
            sources.libraryError?.message?.takeIf { it.isNotBlank() }
                ?: sources.libraryError?.let { "无法读取曲库，请稍后重试" }
        val playlistMessage =
            sources.playlistsError?.message?.takeIf { it.isNotBlank() }
                ?: sources.playlistsError?.let { "无法读取歌单，请稍后重试" }
        return when {
            libraryMessage != null && playlistMessage != null ->
                "曲库与歌单加载失败" to "曲库：$libraryMessage；歌单：$playlistMessage"
            libraryMessage != null -> "曲库加载失败" to libraryMessage
            playlistMessage != null -> "歌单加载失败" to playlistMessage
            else -> null
        }
    }

    /** W66：服务器音乐库列表（顶栏选择器候选；读取失败返回空列表，不阻塞曲库加载）。 */
    private suspend fun loadMusicLibraryList(): List<FindroidCollection> = runCatching {
        pickMusicLibraries(jellyfinRepository.getLibraries())
    }
        .onFailure { Timber.w(it, "读取音乐库列表失败") }
        .getOrElse { emptyList() }

    /**
     * W36 离线曲库：本机已下载且「允许离线观看」的曲目（按专辑 / 艺术家聚合）。
     *
     * 曲目元数据来自下载侧车（专辑 / 艺人 / 音轨号）；播放走 [MusicTrackResolver] → 离线仓库 `getMediaSources` 优先 LOCAL
     * 来源，即本地文件。
     *
     * W9-T5（F4）：除 App 内离线模式外，在线模式服务器曲库加载失败（飞行模式 / 断网）也以本索引兜底，冷启动可继续播放已下载音乐。
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

    /** 当前曲库来源库：**侧栏点进来的库优先**（W53 Bug B1），否则读客户端设置「音乐库」； 指定库失效（被删除 / 重建）时回落自动（全部音乐库）。 */
    private fun selectedMusicLibraryId(): UUID? =
        resolveMusicLibraryId(
            routeLibraryId = savedStateHandle.get<String>(MUSIC_ROUTE_LIBRARY_ID),
            preferredLibraryId = appPreferences.getValue(appPreferences.uiMusicLibraryId),
        )

    fun selectTab(tab: MusicTab) {
        clearBatchSelection()
        _uiState.update { it.copy(tab = tab, detail = null) }
    }

    /** W34：刷新曲目下载态（进行中任务 + 已下载条目）。 */
    fun refreshDownloadState() {
        downloadStateRefreshJob?.cancel()
        downloadStateRefreshJob = viewModelScope.launch {
            runCatching {
                val tasks = downloader.refreshDownloadTasks()
                val active = activeSongDownloadStatuses(tasks)
                val downloaded = downloader.downloadedItemIds()
                val downloadedRecords = downloader.downloadedMediaRecords()
                _downloadState.value =
                    SongDownloadState(
                        downloadedItemIds = downloaded,
                        activeStatuses = active,
                        downloadedRecords = downloadedRecords,
                        activeTasks =
                            tasks.filter {
                                DownloadTaskRules.isActiveQueueStatus(it.status) &&
                                    it.mediaKind == DownloadMediaKind.MUSIC
                            },
                    )
            }
                .onFailure { Timber.w(it, "刷新曲目下载态失败") }
        }
    }

    // ---- W58：长按多选 + 批量操作（音乐打样；删除仅本地） ----

    /** 当前视图**已加载**的曲目（歌曲 Tab 或专辑 / 艺术家 / 歌单 / 收藏 / 最近播放详情）。 */
    private fun visibleSongs(): List<MusicSong> {
        val state = _uiState.value
        return state.detail?.songs ?: state.songs
    }

    private fun updateBatchSelectionByUser(transform: (MultiSelectState) -> MultiSelectState) {
        _batchSelectionState.update { state -> state.userChanged(transform(state.selection)) }
    }

    private fun clearBatchSelection() {
        updateBatchSelectionByUser { MultiSelectState() }
    }

    private fun syncBatchSelection(ids: Set<String>) {
        _batchSelectionState.update { state ->
            val retained = state.selection.retain(ids)
            if (retained == state.selection) state else state.userChanged(retained)
        }
    }

    /**
     * W3-T16 · §4.3：歌曲行长按 = 进入多选并把该曲目加入选中。
     *
     * 长按不再是上下文菜单入口（菜单只由行内 ⋮ 打开）；多选态内长按等价于切换该曲选中（调用方分派 [onBatchToggle]）。
     */
    fun onBatchLongPress(song: MusicSong) {
        val ids = visibleSongs().map { it.playlistSelectionKey() }.toSet()
        updateBatchSelectionByUser { selection ->
            selection.retain(ids).longPress(song.playlistSelectionKey())
        }
    }

    /** 多选态单击行：切换单曲选中。 */
    fun onBatchToggle(song: MusicSong) {
        val ids = visibleSongs().map { it.playlistSelectionKey() }.toSet()
        updateBatchSelectionByUser { selection ->
            selection.retain(ids).toggle(song.playlistSelectionKey())
        }
    }

    // ---- W59：专辑列表长按多选（批量下载整张；复用同一 MultiSelectState / 批量下载链路） ----

    /** 专辑 Tab 的多选作用域（列表视图；进入专辑 / 其它详情后仍按曲目口径）。 */
    private fun albumBatchScope(): Boolean =
        _uiState.value.detail == null && _uiState.value.tab == MusicTab.ALBUMS

    private fun visibleAlbums(): List<MusicAlbum> = _uiState.value.albums

    private fun selectedAlbums(): List<MusicAlbum> {
        val selection = _batchSelectionState.value.selection.selectedIds
        return visibleAlbums().filter { it.key in selection }
    }

    /** W59：长按专辑进入多选（选中该专辑卡）。 */
    fun onAlbumBatchLongPress(album: MusicAlbum) {
        val ids = visibleAlbums().map { it.key }.toSet()
        updateBatchSelectionByUser { selection -> selection.retain(ids).longPress(album.key) }
    }

    /** W59：多选态点击专辑卡 = 切换该专辑选中。 */
    fun onAlbumBatchToggle(album: MusicAlbum) {
        val ids = visibleAlbums().map { it.key }.toSet()
        updateBatchSelectionByUser { selection -> selection.retain(ids).toggle(album.key) }
    }

    /** 全选当前视图已加载曲目（不拉全库）。 */
    fun onBatchSelectAll() {
        val ids =
            if (albumBatchScope()) visibleAlbums().map { it.key }
            else visibleSongs().map { it.playlistSelectionKey() }
        updateBatchSelectionByUser { selection -> selection.selectAll(ids) }
    }

    /** 取消全选（保留多选模式）。 */
    fun onBatchSelectNone() {
        val ids =
            if (albumBatchScope()) visibleAlbums().map { it.key }
            else visibleSongs().map { it.playlistSelectionKey() }
        updateBatchSelectionByUser { selection -> selection.selectNone(ids) }
    }

    /** 退出多选（清空选择）。 */
    fun onBatchExit() {
        clearBatchSelection()
    }

    /** 批量动作条可用性（纯函数判定；UI 直接消费）。 */
    fun batchEnabled(action: MusicBatchAction): Boolean {
        if (albumBatchScope()) {
            // W59：专辑多选只提供「下载整张」——任一选中专辑还有缺失曲目即可用。
            if (action != MusicBatchAction.DOWNLOAD) return false
            return selectedAlbums().any { album -> albumDownloadPlan(album).targets.isNotEmpty() }
        }
        val selected = selectedSongs()
        return musicBatchActionEnabled(
            action = action,
            selected = selected.map { batchCaps(it, _downloadState.value) },
            inPlaylist = _uiState.value.detail is MusicDetail.Playlist,
        )
    }

    /** W59：专辑缺失曲目计划（只补齐缺失 + 单次 100 首上限）。 */
    private fun albumDownloadPlan(album: MusicAlbum): MusicAlbumDownloadRules.Plan {
        val downloadState = _downloadState.value
        return MusicAlbumDownloadRules.plan(
            album = album,
            downloadedIdentities = downloadState.downloadedIdentities(),
            activeIdentities = downloadState.activeStatuses.keys,
        )
    }

    /** W7-T2：批量下载计划（专辑多选 = 多专辑合并；曲目多选 / 歌单 = 保序合并）——只补齐缺失 + 单次 100 首上限。 */
    private fun batchDownloadPlan(): MusicAlbumDownloadRules.Plan {
        val downloadState = _downloadState.value
        return if (albumBatchScope()) {
            MusicAlbumDownloadRules.planAlbums(
                albums = selectedAlbums(),
                downloadedIdentities = downloadState.downloadedIdentities(),
                activeIdentities = downloadState.activeStatuses.keys,
            )
        } else {
            MusicAlbumDownloadRules.planSongs(
                songs = selectedSongs(),
                downloadedIdentities = downloadState.downloadedIdentities(),
                activeIdentities = downloadState.activeStatuses.keys,
            )
        }
    }

    /** W7-T2：批量下载前置确认内容；没有待入队曲目返回 null（此时直接给三态反馈，不弹提示层）。 */
    fun batchPrecheck(): MusicBatchPrecheck? {
        val plan = batchDownloadPlan()
        if (plan.targets.isEmpty()) return null
        return MusicBatchPrecheck(
            content =
                DownloadPrecheckContent(
                    kind = DownloadPrecheckKind.AUDIO_BATCH,
                    title =
                        _uiState.value.detail?.title ?: if (albumBatchScope()) "已选专辑" else "已选曲目",
                    itemCount = plan.targets.size,
                ),
            deferredByLimit = plan.skippedByLimit,
        )
    }

    private fun selectedSongs(): List<MusicSong> {
        val selection = _batchSelectionState.value.selection.selectedIds
        return visibleSongs().filter { it.playlistSelectionKey() in selection }
    }

    /** W8-T4：批量「加入歌单」的选中曲目（作用域与批量动作条一致）。 */
    fun batchSelectedSongs(): List<MusicSong> = selectedSongs()

    private fun batchCaps(song: MusicSong, downloadState: SongDownloadState): SongBatchCaps =
        musicSongBatchCaps(
            song = song,
            downloadedRecords = downloadState.downloadedRecords,
            activeTasks = downloadState.activeTasks,
        )

    /**
     * 批量「播放」= 加入当前播放队列开始播（W58 用户口径）。
     *
     * 队列为空时先建队列再补其余；已有队列则把选中曲目追加到队尾并跳过去播放第一首。 解析按列表顺序串行进行，单曲失败跳过。
     */
    fun playSelected() {
        val songs = selectedSongs()
        if (songs.isEmpty() || _pendingPlayback.value != null) return
        playJob?.cancel()
        val requestId = ++nextPlayRequestId
        val selectionSnapshot = _batchSelectionState.value.snapshotForPlayback()
        playJob =
            launchSelectedPlayback(
                scope = viewModelScope,
                songs = songs,
                pendingPlayback = _pendingPlayback,
                requestId = requestId,
                resolve = { song -> resolveQueueItem(song) ?: error("无法解析「${song.name}」的播放地址") },
                onFirstResolved = { first ->
                    val queueBefore = playbackController.queue.value
                    if (queueBefore == null) {
                        playbackController.setQueue(
                            MusicQueue(
                                items = listOf(first),
                                currentIndex = 0,
                                source = QueueSource.MANUAL,
                                sourceId = null,
                            ),
                            startIndex = 0,
                        )
                    } else {
                        // W78：先追加再跳转，避免自动切歌改变当前索引后条目落到错误位置。
                        queueEditor.appendToQueue(listOf(first))
                        playbackController.queue.value?.items?.lastIndex?.let { index ->
                            queueEditor.jumpTo(index)
                        }
                    }
                },
                onAccepted = {
                    _batchSelectionState.update { state ->
                        state.acceptedForPlayback(requestId, selectionSnapshot)
                    }
                },
                appendItems = queueEditor::appendToQueue,
                shouldContinue = { playbackController.queue.value != null },
                isCurrentRequest = { activeRequestId -> activeRequestId == nextPlayRequestId },
                onFailure = { failure ->
                    val ui = selectedPlaybackFailureUi(failure)
                    showBatchFailure(ui.title, ui.message)
                },
                onPartialFailure = { failure ->
                    val unacceptedIds =
                        failure.unacceptedSongs.mapTo(mutableSetOf()) { song ->
                            song.playlistSelectionKey()
                        }
                    val visibleIds =
                        visibleSongs().mapTo(mutableSetOf()) { song -> song.playlistSelectionKey() }
                    _batchSelectionState.update { state ->
                        state.restoreUnaccepted(
                            requestId = requestId,
                            unacceptedIds = unacceptedIds,
                            visibleIds = visibleIds,
                        )
                    }
                    val ui = selectedPlaybackFailureUi(failure)
                    showBatchFailure(ui.title, ui.message)
                },
            )
    }

    /**
     * 批量下载（W58 曲目多选 / W59 专辑多选 / W7-T2 歌单共用）：跳过已下载 / 已在队列的曲目。
     *
     * 专辑多选 = 多张专辑合并计划（按专辑顺序 + 音轨序）；曲目多选 / 歌单 = 保持列表顺序；两者都去重并受单次 100 首上限约束， 结果统一走三态反馈（已加入 / 已在队列 /
     * 已下载）。
     */
    fun downloadSelected() {
        val plan = batchDownloadPlan()
        viewModelScope.launch {
            if (plan.targets.isEmpty()) {
                // 三态反馈：没有待入队曲目时不能静默无反应（已下载 / 已在队列）。
                batchDownloadFeedbackChannel.send(
                    MusicBatchDownloadFeedback(
                        alreadyQueued = plan.skippedActive,
                        alreadyDownloaded = plan.skippedDownloaded,
                    )
                )
                return@launch
            }
            val receipt = runCatching {
                downloader.receiveMusicBatch(plan.targets.toIntentRequests())
            }
                .getOrElse {
                    showBatchFailure("下载受理失败", it.message ?: "请稍后重试")
                    return@launch
                }
            batchDownloadFeedbackChannel.send(
                MusicBatchDownloadFeedback(
                    added = receipt.acceptedIds.size,
                    alreadyQueued = plan.skippedActive,
                    alreadyDownloaded = plan.skippedDownloaded,
                    deferredByLimit = plan.skippedByLimit,
                )
            )
            refreshDownloadState()
        }
    }

    /** W59 专辑详情「下载专辑」：只补齐缺失 + 单次 100 首上限。 */
    fun downloadAlbum() {
        val album = (_uiState.value.detail as? MusicDetail.Album)?.album ?: return
        val plan = albumDownloadPlan(album)
        if (plan.targets.isEmpty()) return
        viewModelScope.launch {
            val receipt = runCatching {
                downloader.receiveMusicBatch(plan.targets.toIntentRequests())
            }
                .getOrElse {
                    showBatchFailure("下载受理失败", it.message ?: "请稍后重试")
                    return@launch
                }
            if (receipt.acceptedIds.isNotEmpty()) {
                downloadQueuedChannel.send(receipt.acceptedIds.size)
            }
            refreshDownloadState()
            if (plan.skippedByLimit > 0) {
                showBatchFailure(
                    "单次上限 ${MusicAlbumDownloadRules.MAX_TRACKS_PER_REQUEST} 首",
                    "已加入前 ${plan.targets.size} 首，剩余 ${plan.skippedByLimit} 首请再次下载",
                )
            }
        }
    }

    /** 专辑详情「下载专辑」按钮文案状态（仅补齐缺失；null = 当前详情不是专辑）。 */
    fun albumDownloadMissingCount(): Int? {
        val album = (_uiState.value.detail as? MusicDetail.Album)?.album ?: return null
        return albumDownloadPlan(album).targets.size
    }

    private fun List<MusicSong>.toIntentRequests(): List<DownloadMusicIntentRequest> =
        musicIntentRequests(this)

    /** 批量收藏：任一所选未收藏 → 全部收藏；全部已收藏 → 全部取消。 */
    fun favoriteSelected() {
        val selected = selectedSongs()
        val favorite = batchFavoriteTarget(selected) ?: return
        val ids = selected.map { it.itemId }.toSet()
        viewModelScope.launch {
            var failure: UserDataWriteFailure? = null
            var queued = false
            val written = runCatching {
                ids.forEach { id ->
                    val outcome = repository.setFavorite(id, favorite)
                    when (outcome) {
                        is UserDataWriteOutcome.Failed -> failure = failure ?: outcome.failure
                        UserDataWriteOutcome.QueuedForSync -> queued = true
                        UserDataWriteOutcome.Confirmed -> Unit
                    }
                }
                failure == null
            }
                .getOrDefault(false)
            if (written) {
                // W9-T4：与单曲路径同口径——有排队（网络 / 5xx）就提示「联网后自动同步」，只提示一次。
                if (queued) favoriteWriteNoticeChannel.send(FAVORITE_QUEUED_NOTICE)
                _uiState.update { state -> state.withFavorites(ids, favorite) }
            } else {
                _uiState.update {
                    it.copy(
                        errorTitle = "收藏失败",
                        errorMessage = failure.writeErrorMessage("无法写入服务器收藏"),
                    )
                }
            }
        }
    }

    /** 批量删除：**只删本地**——已下载服务器条目逐个删除本地文件与索引；纯服务器条目不在目标内。 */
    fun deleteSelected() {
        val targets = batchDeleteTargets(batchCapabilities(selectedSongs(), _downloadState.value))
        if (targets.isEmpty()) return
        viewModelScope.launch {
            var failures = 0
            for (song in targets) {
                val item = runCatching { jellyfinRepository.getItem(song.itemId) }.getOrNull()
                val source =
                    item?.sources?.firstOrNull { source ->
                        source.type == FindroidSourceType.LOCAL &&
                            !source.path.endsWith(".download")
                    }
                if (item == null || source == null) {
                    failures++
                    continue
                }
                runCatching { downloader.deleteItem(item, source) }.onFailure { failures++ }
            }
            refreshDownloadState()
            if (failures > 0) {
                _uiState.update {
                    it.copy(
                        errorTitle = "部分删除失败",
                        errorMessage = "有 $failures 项本地下载未能删除，请稍后重试",
                    )
                }
            }
        }
    }

    /** 批量「从播放列表移除」（仅歌单详情）：移除服务器歌单条目，不删除媒体本身。 */
    fun removeSelectedFromPlaylist() {
        val detail = _uiState.value.detail as? MusicDetail.Playlist ?: return
        val selected = selectedSongs()
        if (selected.isEmpty()) return
        if (detail.playlist.kind == MusicPlaylistKind.LOCAL) {
            val entryIds = selected.mapNotNull { it.playlistEntryId }.toSet()
            if (entryIds.isEmpty()) return
            viewModelScope.launch {
                runCatching { localPlaylistRepository.remove(detail.playlist.id, entryIds) }
                    .onSuccess { removed ->
                        if (removed) {
                            clearBatchSelection()
                        } else {
                            showLocalPlaylistFailure("移出歌单失败", "本地歌单条目已变化，请重试")
                        }
                    }
                    .onFailure { error ->
                        showLocalPlaylistFailure(
                            "移出歌单失败",
                            error.message ?: "本地歌单写入失败，请稍后重试",
                        )
                    }
            }
            return
        }
        viewModelScope.launch {
            var failure: UserDataWriteFailure? = null
            val removed = runCatching {
                selected.forEach { song ->
                    val outcome =
                        repository.removeFromPlaylist(
                            detail.playlist.id,
                            song.playlistEntryId ?: song.itemId.toString(),
                        )
                    if (outcome is UserDataWriteOutcome.Failed) {
                        failure = failure ?: outcome.failure
                    }
                }
                failure == null
            }
                .getOrDefault(false)
            if (removed) {
                val removedIds = selected.map { it.itemId }.toSet()
                _uiState.update { state ->
                    val current = state.detail as? MusicDetail.Playlist ?: return@update state
                    state.copy(
                        detail =
                            current.copy(songs = current.songs.filter { it.itemId !in removedIds })
                    )
                }
                clearBatchSelection()
            } else {
                _uiState.update {
                    it.copy(
                        errorTitle = "从播放列表移除失败",
                        errorMessage = failure.writeErrorMessage("无法写入服务器歌单"),
                    )
                }
            }
        }
    }

    private fun batchCapabilities(
        songs: List<MusicSong>,
        downloadState: SongDownloadState,
    ): List<MusicBatchTargetCapability> = songs.map { song ->
        MusicBatchTargetCapability(
            song = song,
            caps = batchCaps(song, downloadState),
        )
    }

    private fun SongDownloadState.activeItemIds(): Set<UUID> =
        activeStatuses.keys.mapNotNullTo(mutableSetOf()) { identity ->
            runCatching { UUID.fromString(identity.itemId) }.getOrNull()
        }

    private fun showBatchFailure(title: String, message: String) {
        _uiState.update { it.copy(errorTitle = title, errorMessage = message) }
    }

    /**
     * W7-T1：单曲下载前置确认内容。
     *
     * 只用本地曲库快照（名称 / 艺人 / 容器），**不发网络请求**——提示层必须先于任何入队请求出现。 快照没有文件大小字段，大小显示为「未知」，真实大小由引擎入队时解析。
     */
    fun songPrecheckContent(song: MusicSong): DownloadPrecheckContent =
        DownloadPrecheckContent(
            kind = DownloadPrecheckKind.AUDIO,
            title = song.name,
            itemCount = 1,
            formatLabel =
                song.container?.takeIf { it.isNotBlank() }
                    ?: song.codec?.takeIf { it.isNotBlank() },
            secondaryLabel = song.artist?.takeIf { it.isNotBlank() },
            durationLabel = musicRuntimeLabel(song.runtimeTicks),
        )

    /** W7-T1：专辑「下载专辑」前置确认内容；没有缺失曲目返回 null（按钮本身已置灰）。 */
    fun albumPrecheckContent(): DownloadPrecheckContent? {
        val album = (_uiState.value.detail as? MusicDetail.Album)?.album ?: return null
        val plan = albumDownloadPlan(album)
        if (plan.targets.isEmpty()) return null
        return DownloadPrecheckContent(
            kind = DownloadPrecheckKind.AUDIO_BATCH,
            title = album.name,
            itemCount = plan.targets.size,
            secondaryLabel = album.artist?.takeIf { it.isNotBlank() },
        )
    }

    private fun musicRuntimeLabel(runtimeTicks: Long): String? {
        val totalSeconds = runtimeTicks / 10_000_000
        if (totalSeconds <= 0) return null
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format(java.util.Locale.US, "%d:%02d", minutes, seconds)
    }

    /**
     * W9-T2：单曲下载入口（**永远不删除**）。
     *
     * 已完成下载 / 已在队列时只发提示：不删文件、不重复入队。删除只能走 [deleteSong]。
     */
    fun downloadSong(song: MusicSong) {
        viewModelScope.launch {
            val item =
                runCatching { jellyfinRepository.getItem(song.itemId) }.getOrNull() ?: return@launch
            val completed =
                item.sources
                    .filter { it.type == FindroidSourceType.LOCAL }
                    .firstOrNull { !it.path.endsWith(".download") }
            val activeTask = activeSongDownloadTask(song)
            when (
                songDownloadRequestOutcome(
                    hasCompletedSource = completed != null,
                    downloaded = _downloadState.value.isDownloaded(song),
                    queued = activeTask != null,
                )
            ) {
                SongDownloadRequestOutcome.ALREADY_DOWNLOADED -> {
                    batchDownloadFeedbackChannel.send(
                        MusicBatchDownloadFeedback(alreadyDownloaded = 1)
                    )
                    return@launch
                }
                SongDownloadRequestOutcome.ALREADY_QUEUED -> {
                    batchDownloadFeedbackChannel.send(MusicBatchDownloadFeedback(alreadyQueued = 1))
                    return@launch
                }
                SongDownloadRequestOutcome.ENQUEUE -> Unit
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
            downloadQueuedChannel.send(1)
        }
    }

    /** W9-T2：取消队列中的单曲下载；不动已下载文件、也不删除索引。 */
    fun cancelSongDownload(song: MusicSong) {
        viewModelScope.launch {
            val activeTask = activeSongDownloadTask(song) ?: return@launch
            downloader.cancelTask(activeTask)
            refreshDownloadState()
        }
    }

    /** W9-T2：显式删除单曲下载（全 App 唯一的单曲删除入口）。 */
    fun deleteSong(song: MusicSong) {
        viewModelScope.launch {
            val item =
                runCatching { jellyfinRepository.getItem(song.itemId) }.getOrNull() ?: return@launch
            val completed =
                item.sources
                    .filter { it.type == FindroidSourceType.LOCAL }
                    .firstOrNull { !it.path.endsWith(".download") }
            if (completed != null) {
                downloader.deleteItem(item, completed)
            }
            refreshDownloadState()
        }
    }

    /** 队列中属于该曲目的任务（同 itemId 且同一来源；identity 缺失时退回 itemId）。 */
    private suspend fun activeSongDownloadTask(song: MusicSong): DownloadTask? =
        downloader.refreshDownloadTasks().firstOrNull {
            DownloadTaskRules.isActiveQueueStatus(it.status) &&
                (song.downloadIdentity()?.let { identity ->
                    it.downloadIdentity().matchesProvenance(identity)
                } ?: (it.itemId == song.itemId))
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
        val cachedSnapshot = latestQueueSnapshot()
        val immediateSongs = playlistSnapshotSongs(playlist, cachedSnapshot)
        _uiState.update {
            it.copy(
                detail =
                    MusicDetail.Playlist(
                        playlist = playlist,
                        songs = immediateSongs,
                        loading = immediateSongs.isEmpty(),
                    ),
                errorTitle = null,
                errorMessage = null,
            )
        }
        clearBatchSelection()
        if (playlist.kind == MusicPlaylistKind.LOCAL) {
            refreshLocalPlaylistEntries(playlist.id)
            return
        }
        viewModelScope.launch {
            val snapshot =
                restoredSnapshot
                    ?: runCatching { persister.load() }
                        .getOrNull()
                        ?.also { loaded -> restoredSnapshot = loaded }
            if (immediateSongs.isEmpty()) {
                publishPlaylistSnapshot(playlist, snapshot)
            }
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
                    _uiState.update { state ->
                        val detail = state.detail
                        if (
                            detail is MusicDetail.Playlist &&
                                detail.playlist.id == playlist.id &&
                                detail.songs.isNotEmpty()
                        ) {
                            state.copy(
                                detail = detail.copy(loading = false),
                                errorTitle = "歌单刷新失败",
                                errorMessage = "当前显示本地快照，联网内容暂未刷新",
                            )
                        } else {
                            state.copy(
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
    }

    private fun publishPlaylistSnapshot(
        playlist: MusicPlaylist,
        snapshot: MusicQueueSnapshot?,
    ) {
        val songs = playlistSnapshotSongs(playlist, snapshot)
        if (songs.isEmpty()) return
        _uiState.update { state ->
            val detail = state.detail
            if (detail is MusicDetail.Playlist && detail.playlist.id == playlist.id) {
                state.copy(detail = detail.copy(songs = songs, loading = false))
            } else {
                state
            }
        }
    }

    fun createLocalPlaylist(name: String, id: UUID = UUID.randomUUID()) {
        viewModelScope.launch {
            runCatching { localPlaylistRepository.create(id, name) }
                .onFailure { error ->
                    showLocalPlaylistFailure(
                        "新建本地歌单失败",
                        error.message ?: "本地歌单写入失败，请稍后重试",
                    )
                }
        }
    }

    /** 文件已在 UI 侧完整、有界读取；这里只有整份解析成功后才进入本地原子导入。 */
    fun importLocalPlaylist(content: String) {
        viewModelScope.launch {
            try {
                val format =
                    PlaylistExchangeFileTypes.detect(content)
                        ?: throw PlaylistExchangeException("无法识别歌单文件格式")
                val document =
                    when (format) {
                        PlaylistExchangeFormat.JSON -> PlaylistExchangeAdapter.decodeJson(content)
                        PlaylistExchangeFormat.M3U8 -> PlaylistExchangeAdapter.decodeM3u8(content)
                    }
                val summary = localPlaylistRepository.importPlaylist(document)
                playlistExchangeFeedbackChannel.send(
                    PlaylistExchangeFeedback("已导入「${summary.name}」 · ${summary.entryCount} 首")
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                playlistExchangeFeedbackChannel.send(
                    PlaylistExchangeFeedback(
                        error.message?.takeIf { it.isNotBlank() } ?: "歌单导入失败，未写入本地歌单"
                    )
                )
            }
        }
    }

    /** 仅导出当前打开的本地歌单；服务器歌单不会进入文件交换写路径。 */
    fun exportLocalPlaylist(playlistId: UUID, format: PlaylistExchangeFormat) {
        viewModelScope.launch {
            try {
                val detail =
                    _uiState.value.detail as? MusicDetail.Playlist
                        ?: throw IllegalStateException("没有打开的歌单")
                if (
                    detail.playlist.id != playlistId ||
                        detail.playlist.kind != MusicPlaylistKind.LOCAL
                ) {
                    throw IllegalStateException("只能导出当前本地歌单")
                }
                val availableRefs = availablePlaylistTrackRefs()
                val entries =
                    localPlaylistRepository.entries(playlistId.toString()).first().map { entry ->
                        PlaylistExchangeEntry(
                            track = entry.track,
                            unavailable =
                                entry.track.ref is TrackRef.Unresolved ||
                                    entry.track.ref !in availableRefs,
                        )
                    }
                val document =
                    PlaylistExchangeDocument(
                        name = detail.playlist.name,
                        entries = entries,
                    )
                val content =
                    when (format) {
                        PlaylistExchangeFormat.JSON -> PlaylistExchangeAdapter.encodeJson(document)
                        PlaylistExchangeFormat.M3U8 -> PlaylistExchangeAdapter.encodeM3u8(document)
                    }
                playlistExportReadyChannel.send(
                    PlaylistExportReady(
                        fileName =
                            PlaylistExchangeFileTypes.suggestedFileName(
                                detail.playlist.name,
                                format,
                            ),
                        format = format,
                        content = content,
                    )
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                playlistExchangeFeedbackChannel.send(
                    PlaylistExchangeFeedback(
                        error.message?.takeIf { it.isNotBlank() } ?: "歌单导出准备失败"
                    )
                )
            }
        }
    }

    fun renameLocalPlaylist(playlistId: UUID, name: String) {
        viewModelScope.launch {
            runCatching { localPlaylistRepository.rename(playlistId, name) }
                .onSuccess { renamed ->
                    if (!renamed) {
                        showLocalPlaylistFailure("重命名失败", "歌单名不能为空或歌单已不存在")
                    }
                }
                .onFailure { error ->
                    showLocalPlaylistFailure(
                        "重命名失败",
                        error.message ?: "本地歌单写入失败，请稍后重试",
                    )
                }
        }
    }

    fun deleteLocalPlaylist(playlistId: UUID) {
        viewModelScope.launch {
            runCatching { localPlaylistRepository.delete(playlistId) }
                .onSuccess { deleted ->
                    if (!deleted) {
                        showLocalPlaylistFailure("删除歌单失败", "本地歌单不存在")
                        return@onSuccess
                    }
                    val detail = _uiState.value.detail as? MusicDetail.Playlist
                    if (detail?.playlist?.id == playlistId) closeDetail()
                    clearBatchSelection()
                }
                .onFailure { error ->
                    showLocalPlaylistFailure(
                        "删除歌单失败",
                        error.message ?: "本地歌单写入失败，请稍后重试",
                    )
                }
        }
    }

    /**
     * W3-T19：服务器歌单改名（`POST /Playlists/{id}`，只改 Name 不动曲目）。
     *
     * 成功后同步已打开详情的标题并刷新歌单列表（服务端为唯一事实源）。
     */
    fun renameServerPlaylist(playlistId: UUID, name: String) {
        val normalized = name.trim()
        if (normalized.isEmpty()) {
            showLocalPlaylistFailure("重命名失败", "歌单名不能为空")
            return
        }
        viewModelScope.launch {
            val outcome = runCatching {
                repository.renameServerPlaylist(playlistId, normalized)
            }
                .getOrNull()
            if (outcome == UserDataWriteOutcome.Confirmed) {
                _uiState.update { state ->
                    val detail = state.detail as? MusicDetail.Playlist
                    if (detail?.playlist?.id == playlistId) {
                        state.copy(
                            detail = detail.copy(playlist = detail.playlist.copy(name = normalized))
                        )
                    } else {
                        state
                    }
                }
                refreshServerPlaylists()
            } else {
                showLocalPlaylistFailure(
                    "重命名失败",
                    (outcome as? UserDataWriteOutcome.Failed)
                        ?.failure
                        .writeErrorMessage("服务器歌单改名失败，请稍后重试"),
                )
            }
        }
    }

    /** W3-T19：删除服务器歌单（不可逆）；成功后关闭对应详情并刷新歌单列表。 */
    fun deleteServerPlaylist(playlistId: UUID) {
        viewModelScope.launch {
            val outcome = runCatching { repository.deleteServerPlaylist(playlistId) }.getOrNull()
            if (outcome == UserDataWriteOutcome.Confirmed) {
                val detail = _uiState.value.detail as? MusicDetail.Playlist
                if (detail?.playlist?.id == playlistId) closeDetail()
                clearBatchSelection()
                refreshServerPlaylists()
            } else {
                showLocalPlaylistFailure(
                    "删除歌单失败",
                    (outcome as? UserDataWriteOutcome.Failed)
                        ?.failure
                        .writeErrorMessage("服务器歌单删除失败，请稍后重试"),
                )
            }
        }
    }

    /** W3-T19：服务器歌单内换位（`POST /Playlists/{id}/Items/{entryId}/Move/{newIndex}`），成功后回读顺序。 */
    fun moveServerPlaylistEntry(playlistId: UUID, entryId: String, targetPosition: Int) {
        if (targetPosition < 0) return
        viewModelScope.launch {
            val outcome = runCatching {
                repository.moveServerPlaylistItem(playlistId, entryId, targetPosition)
            }
                .getOrNull()
            if (outcome == UserDataWriteOutcome.Confirmed) {
                refreshServerPlaylistEntries(playlistId)
            } else {
                showLocalPlaylistFailure(
                    "排序失败",
                    (outcome as? UserDataWriteOutcome.Failed)
                        ?.failure
                        .writeErrorMessage("服务器歌单排序失败，请稍后重试"),
                )
            }
        }
    }

    /** W3-T19：重新拉取服务器歌单卡片列表（改名 / 删除后刷新入口）。 */
    private fun refreshServerPlaylists() {
        viewModelScope.launch {
            runCatching { repository.getPlaylists(forceRefresh = true) }
                .onSuccess { playlists ->
                    serverPlaylists = playlists
                    publishPlaylists()
                }
                .onFailure { error -> Timber.w(error, "刷新服务器歌单列表失败") }
        }
    }

    /** W3-T19：重新拉取服务器歌单内曲目（换位后以服务端顺序为准）。 */
    private fun refreshServerPlaylistEntries(playlistId: UUID) {
        viewModelScope.launch {
            runCatching { repository.getPlaylistSongs(playlistId) }
                .onSuccess { songs ->
                    _uiState.update { state ->
                        val detail = state.detail as? MusicDetail.Playlist
                        if (
                            detail != null &&
                                detail.playlist.id == playlistId &&
                                detail.playlist.kind == MusicPlaylistKind.SERVER
                        ) {
                            state.copy(detail = detail.copy(songs = songs, loading = false))
                        } else {
                            state
                        }
                    }
                }
                .onFailure { error ->
                    showLocalPlaylistFailure(
                        "歌单刷新失败",
                        error.message?.takeIf { it.isNotBlank() } ?: "无法读取歌单内容",
                    )
                }
        }
    }

    fun addSongsToLocalPlaylist(playlistId: UUID, songs: List<MusicSong>) {
        if (songs.isEmpty()) return
        val serverId = appPreferences.getValue(appPreferences.currentServer)
        val userId = runCatching { jellyfinRepository.getUserId() }.getOrNull()
        val tracks = runCatching {
            songs
                .map { song -> song.toLocalPlaylistTrack(serverId = serverId, userId = userId) }
                .distinctBy { track -> track.ref }
        }
            .getOrElse { error ->
                showLocalPlaylistFailure(
                    "加入歌单失败",
                    error.message ?: "曲目缺少稳定身份，无法加入本地歌单",
                )
                return
            }
        viewModelScope.launch {
            runCatching { localPlaylistRepository.addAll(playlistId, tracks) }
                .onSuccess { added ->
                    if (added) {
                        clearBatchSelection()
                    } else {
                        showLocalPlaylistFailure("加入歌单失败", "本地歌单已不存在")
                    }
                }
                .onFailure { error ->
                    showLocalPlaylistFailure(
                        "加入歌单失败",
                        error.message ?: "本地歌单写入失败，请稍后重试",
                    )
                }
        }
    }

    /** W8-T4：新建服务器歌单。空名不写服务器；成功后只按服务端回读刷新（不做本地乐观插入）。 */
    fun createServerPlaylist(name: String) {
        val normalized = normalizedServerPlaylistName(name)
        if (normalized == null) {
            _uiState.update { it.copy(errorTitle = "新建服务器歌单失败", errorMessage = "歌单名不能为空") }
            return
        }
        viewModelScope.launch {
            val outcome = runCatching { repository.createServerPlaylist(normalized) }.getOrNull()
            when (outcome) {
                is ServerPlaylistCreateOutcome.Created -> refresh(forceServerPlaylistRefresh = true)
                is ServerPlaylistCreateOutcome.Failed ->
                    _uiState.update {
                        it.copy(
                            errorTitle = "新建服务器歌单失败",
                            errorMessage = outcome.failure.writeErrorMessage("无法写入服务器歌单"),
                        )
                    }
                null ->
                    _uiState.update {
                        it.copy(
                            errorTitle = "新建服务器歌单失败",
                            errorMessage = "无法写入服务器歌单",
                        )
                    }
            }
        }
    }

    /** W8-T4：把选中曲目加入服务器歌单（只取服务器来源曲目，一次批量请求）。 */
    fun addSongsToServerPlaylist(playlistId: UUID, songs: List<MusicSong>) {
        val itemIds = serverPlaylistItemIds(songs)
        if (itemIds.isEmpty()) return
        viewModelScope.launch {
            val outcome = runCatching { repository.addToPlaylist(playlistId, itemIds) }.getOrNull()
            if (outcome is UserDataWriteOutcome.Failed) {
                val failure = outcome.failure
                _uiState.update {
                    it.copy(
                        errorTitle = "加入歌单失败",
                        errorMessage = failure.writeErrorMessage("无法写入服务器歌单"),
                    )
                }
                return@launch
            }
            if (outcome == null) {
                _uiState.update {
                    it.copy(
                        errorTitle = "加入歌单失败",
                        errorMessage = "无法写入服务器歌单",
                    )
                }
                return@launch
            }
            val detail = _uiState.value.detail as? MusicDetail.Playlist
            clearBatchSelection()
            refresh(forceServerPlaylistRefresh = true)
            if (detail?.playlist?.id == playlistId) reloadServerPlaylistDetail(playlistId)
        }
    }

    /** W8-T4：写成功后按服务端回读当前打开的服务器歌单。 */
    private fun reloadServerPlaylistDetail(playlistId: UUID) {
        viewModelScope.launch {
            runCatching { repository.getPlaylistSongs(playlistId) }
                .onSuccess { songs ->
                    _uiState.update { state ->
                        val detail = state.detail as? MusicDetail.Playlist
                        if (detail != null && detail.playlist.id == playlistId) {
                            state.copy(detail = detail.copy(songs = songs, loading = false))
                        } else {
                            state
                        }
                    }
                }
        }
    }

    fun moveLocalPlaylistEntry(
        playlistId: UUID,
        entryId: String,
        targetPosition: Int,
    ) {
        viewModelScope.launch {
            runCatching {
                localPlaylistRepository.move(
                    playlistId = playlistId,
                    entryId = entryId,
                    targetPosition = targetPosition,
                )
            }
                .onSuccess { moved ->
                    if (!moved) {
                        showLocalPlaylistFailure("排序失败", "目标位置无效或条目已变化")
                    }
                }
                .onFailure { error ->
                    showLocalPlaylistFailure(
                        "排序失败",
                        error.message ?: "本地歌单排序失败，请稍后重试",
                    )
                }
        }
    }

    private fun refreshLocalPlaylistEntries(playlistId: UUID) {
        localPlaylistEntriesJob?.cancel()
        localPlaylistEntriesJob = viewModelScope.launch {
            localPlaylistRepository.entries(playlistId.toString()).collect { entries ->
                val availableRefs = availablePlaylistTrackRefs()
                val songs = entries.map { entry ->
                    entry.toMusicSong(availableRefs = availableRefs)
                }
                _uiState.update { state ->
                    val detail = state.detail as? MusicDetail.Playlist
                    if (
                        detail != null &&
                            detail.playlist.id == playlistId &&
                            detail.playlist.kind == MusicPlaylistKind.LOCAL
                    ) {
                        state.copy(
                            detail = detail.copy(songs = songs, loading = false),
                            errorTitle = null,
                            errorMessage = null,
                        )
                    } else {
                        state
                    }
                }
            }
        }
    }

    private fun LocalPlaylistEntry.toMusicSong(availableRefs: Set<TrackRef>): MusicSong =
        projectLocalPlaylistTrack(
            entryId = entryId,
            track = track,
            isAvailable = track.ref in availableRefs,
        )

    private fun availablePlaylistTrackRefs(): Set<TrackRef> {
        val serverId = appPreferences.getValue(appPreferences.currentServer)
        val userId = runCatching { jellyfinRepository.getUserId() }.getOrNull()
        return (serverLibrary.songs + localLibrary.songs)
            .mapNotNull { song ->
                runCatching {
                    song.toLocalPlaylistTrack(serverId = serverId, userId = userId).ref
                }
                    .getOrNull()
            }
            .toSet()
    }

    private fun showLocalPlaylistFailure(title: String, message: String) {
        _uiState.update { it.copy(errorTitle = title, errorMessage = message) }
    }

    fun closeDetail() {
        clearBatchSelection()
        _uiState.update { it.copy(detail = null, errorTitle = null, errorMessage = null) }
    }

    /**
     * 点击歌曲（W3-T17 · §4.4 / V2-MUSIC-50）：**追加队尾并起播**，不再整列重建队列。
     *
     * - 该曲已在队列（活动队列或恢复快照）→ 不重复追加，直接跳到队列里的既有位置起播；
     * - 不在 → 只解析被点的这一首后追加到队尾并跳到新下标；
     * - 详情层（专辑 / 艺术家 / 歌单 / 收藏 / 最近播放）把「被点曲到列表结尾」的余下曲目在后台按列表序补到队尾， 保持列表连续播放，同时不动用户已有的点击序列；库列表（歌曲 /
     *   建议 Tab）只追加被点的一首；
     * - 整列加载只剩「播放全部 / 随机播放」以及批量播放、下载页多选播放这些显式列表入口。
     */
    fun playSong(song: MusicSong) {
        if (song.isPlaylistPlaceholder) {
            showLocalPlaylistFailure("曲目暂不可用", "已保留「${song.name}」的歌单条目和显示快照")
            return
        }
        val state = _uiState.value
        val selectionKey = song.playlistSelectionKey()
        val detail =
            state.detail?.takeIf { detail ->
                detail.songs.any { it.playlistSelectionKey() == selectionKey }
            }
        val listSongs = detail?.songs ?: state.songs
        val startIndex = listSongs.indexOfFirst { it.playlistSelectionKey() == selectionKey }
        if (startIndex < 0) return
        val tailSongs =
            if (detail != null) {
                listSongs.subList(startIndex, listSongs.size).toList()
            } else {
                listOf(song)
            }
        playSongAppend(
            song = song,
            tailSongs = tailSongs,
            source = detail?.source ?: QueueSource.MANUAL,
            sourceId = detail?.sourceId,
            snapshotItems =
                (detail as? MusicDetail.Playlist)?.let { playlistDetail ->
                    playlistSnapshotPlaybackItems(playlistDetail.playlist, latestQueueSnapshot())
                } ?: emptyMap(),
        )
    }

    /** W3-T17：点单曲队列计划 → 跳位或追加起播（计划由 [musicPlaySongPlan] 纯函数给出）。 */
    private fun playSongAppend(
        song: MusicSong,
        tailSongs: List<MusicSong>,
        source: QueueSource,
        sourceId: String?,
        snapshotItems: Map<UUID, PlayerItem>,
    ) {
        autoDjController.onUserTransportAction()
        val existingItemIds =
            playbackController.queue.value?.items?.map { item -> item.itemId }
                ?: _restoredQueue.value?.items?.map { item -> item.itemId }
                ?: emptyList()
        val plan =
            musicPlaySongPlan(
                existingItemIds = existingItemIds,
                clickedItemId = song.itemId,
                tailItemIds = tailSongs.map { tailSong -> tailSong.itemId },
            )
        when (plan) {
            is MusicPlaySongPlan.JumpToExisting -> jumpToQueueItem(plan.index)
            is MusicPlaySongPlan.AppendAndPlay ->
                startAppendPlayback(
                    song = song,
                    tailSongs = tailSongs,
                    source = source,
                    sourceId = sourceId,
                    snapshotItems = snapshotItems,
                )
        }
    }

    /**
     * W3-T17：解析被点曲目 → 追加到队尾并跳到新下标 → 后台按列表序补详情层余下曲目。
     *
     * 已有活动队列走 [MusicQueueEditor] 追加；只有恢复快照时把新曲追加进快照队列后 `setQueue`；两者都没有才新建单曲队列。
     */
    private fun startAppendPlayback(
        song: MusicSong,
        tailSongs: List<MusicSong>,
        source: QueueSource,
        sourceId: String?,
        snapshotItems: Map<UUID, PlayerItem>,
    ) {
        playJob?.cancel()
        val requestId = ++nextPlayRequestId
        val pending =
            PendingPlaybackUi(
                requestId = requestId,
                item = song.toPendingPlayerItem(),
            )
        _pendingPlayback.value = pending
        playJob = viewModelScope.launch {
            val first =
                try {
                    resolveQueueItem(song, snapshotItems) ?: error("无法解析「${song.name}」的播放地址")
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: Exception) {
                    if (_pendingPlayback.value?.requestId == requestId) {
                        _pendingPlayback.value = pendingPlaybackAfterFailure(pending, requestId)
                        _uiState.update {
                            it.copy(
                                errorTitle = "播放失败",
                                errorMessage =
                                    error.message?.takeIf { message -> message.isNotBlank() }
                                        ?: "无法解析「${song.name}」的播放地址",
                            )
                        }
                    }
                    return@launch
                }
            if (_pendingPlayback.value?.requestId != requestId) return@launch
            try {
                appendAndStart(first, source, sourceId)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                if (_pendingPlayback.value?.requestId == requestId) {
                    _pendingPlayback.value = pendingPlaybackAfterFailure(pending, requestId)
                    _uiState.update {
                        it.copy(
                            errorTitle = "播放失败",
                            errorMessage =
                                error.message?.takeIf { message -> message.isNotBlank() }
                                    ?: "无法将「${song.name}」加入播放队列",
                        )
                    }
                }
                return@launch
            }
            if (_pendingPlayback.value?.requestId == requestId) {
                _pendingPlayback.value = null
            }
            // 详情层：余下曲目按列表序后台补到队尾（换队列 / 再点歌时由 requestId 取消）。
            appendTailAfterPlayback(tailSongs.drop(1), requestId, snapshotItems)
        }
    }

    /** W3-T17：把首曲放进队列并起播（活动队列追加 / 恢复快照续上 / 无队列新建）。 */
    private fun appendAndStart(first: PlayerItem, source: QueueSource, sourceId: String?) {
        val liveQueue = playbackController.queue.value
        val restored = _restoredQueue.value
        if (liveQueue == null && restored != null) {
            val rebuilt =
                restored.copy(
                    items = restored.items + first,
                    currentIndex = restored.items.size,
                    shuffleOrder = null,
                    shuffleSeed = null,
                )
            _restoredQueue.value = null
            playbackController.setQueue(rebuilt, startIndex = rebuilt.currentIndex)
            return
        }
        if (liveQueue == null) {
            playbackController.setQueue(
                MusicQueue(
                    items = listOf(first),
                    currentIndex = 0,
                    source = source,
                    sourceId = sourceId,
                ),
                startIndex = 0,
            )
            return
        }
        // 先追加再跳转：与批量播放同口径，避免自动切歌改变当前索引后条目落到错误位置。
        queueEditor.appendToQueue(listOf(first))
        playbackController.queue.value?.items?.lastIndex?.let { index -> queueEditor.jumpTo(index) }
    }

    /** W3-T17：详情层余下曲目按列表序分批追加队尾；解析失败只跳过该曲。 */
    private suspend fun appendTailAfterPlayback(
        tailSongs: List<MusicSong>,
        requestId: Long,
        snapshotItems: Map<UUID, PlayerItem>,
    ) {
        if (tailSongs.isEmpty()) return
        for (batch in tailSongs.indices.toList().chunked(QUEUE_FILL_BATCH_SIZE)) {
            if (!isCurrentPlaybackRequest(requestId, nextPlayRequestId)) return
            if (playbackController.queue.value == null) return
            val items = resolveQueueBatch(tailSongs, batch, snapshotItems)
            if (!isCurrentPlaybackRequest(requestId, nextPlayRequestId)) return
            if (items.isNotEmpty()) queueEditor.appendToQueue(items)
        }
    }

    /**
     * W64：歌曲 Tab「播放全部」——把当前列表完整按列表序入队，并从第 1 首开始播。
     *
     * [shuffle] = true 时只随机首曲，自然队列保持原列表序；实际随机序由 ExoPlayer 提供并持久化。 只在歌曲 Tab 主列表生效
     * （详情页有自己的行点击语义，不提供该按钮）。
     */
    fun playAll(shuffle: Boolean = false) {
        val state = _uiState.value
        if (state.detail != null) return
        val songs = state.songs
        if (songs.isEmpty()) return
        val startIndex = playAllStartIndex(songs.size, shuffle)
        startQueuePlayback(
            song = songs[startIndex],
            songs = songs,
            startIndex = startIndex,
            source = QueueSource.MANUAL,
            sourceId = null,
            playMode = if (shuffle) MusicPlayMode.SHUFFLE else null,
        )
    }

    /** 起播共用路径：解析目标曲目 → `setQueue` 起播 → 后台按 [songs] 顺序补齐队列。 */
    private fun startQueuePlayback(
        song: MusicSong,
        songs: List<MusicSong>,
        startIndex: Int,
        source: QueueSource,
        sourceId: String?,
        playMode: MusicPlayMode? = null,
        snapshotItems: Map<UUID, PlayerItem> = emptyMap(),
    ) {
        // W5-T12：用户起播 = 传输操作，Auto DJ 刹车计数清零。
        autoDjController.onUserTransportAction()
        playJob?.cancel()
        val requestId = ++nextPlayRequestId
        val pending =
            PendingPlaybackUi(
                requestId = requestId,
                item = song.toPendingPlayerItem(),
            )
        // 先同步发布高亮 / 底栏目标；旧音频继续播放，直到下面解析完成才 setQueue。
        _pendingPlayback.value = pending
        playJob = viewModelScope.launch {
            val first =
                try {
                    // W9-T5（F4b）：歌单快照的流地址可能是上个会话的，只复用本地可播条目；其余按当前解析链重解析。
                    snapshotItems[song.itemId]?.takeIf { item ->
                        isLocalPlaybackUri(item.mediaSourceUri)
                    } ?: trackResolver.toPlayerItem(song)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: Exception) {
                    if (_pendingPlayback.value?.requestId == requestId) {
                        _pendingPlayback.value = pendingPlaybackAfterFailure(pending, requestId)
                        _uiState.update {
                            it.copy(
                                errorTitle = "播放失败",
                                errorMessage =
                                    error.message?.takeIf { message -> message.isNotBlank() }
                                        ?: "无法解析「${song.name}」的播放地址",
                            )
                        }
                    }
                    return@launch
                }
            if (_pendingPlayback.value?.requestId != requestId) return@launch
            playbackController.setQueue(
                MusicQueue(
                    items = listOf(first),
                    currentIndex = 0,
                    repeatMode = playMode?.toRepeatMode() ?: RepeatMode.OFF,
                    shuffleEnabled = playMode?.toShuffleEnabled() ?: false,
                    source = source,
                    sourceId = sourceId,
                ),
                startIndex = 0,
            )
            // setQueue 已同步发布真实队列；清掉乐观目标后展示自然无缝落到同一首。
            if (_pendingPlayback.value?.requestId == requestId) {
                _pendingPlayback.value = null
            }
            // 随机全部：setQueue 后立即切模式；内核起播任务读取的是最新 queue 快照。
            playMode?.let { mode -> setPlayMode(mode) }
            fillQueueAround(songs, startIndex, requestId, snapshotItems)
        }
    }

    /**
     * 起播后补齐队列（后台，按顺序，跳过解析失败的曲目）。
     *
     * 队列最终顺序 = 浏览列表顺序：先补"当前之后"的曲目（按列表序**批量追加队尾**）， 再补"当前之前"的曲目（按倒序**批量前插队首**，逐批前插后整体仍是列表序）。
     *
     * **W78 修正**：原中断判据是"当前曲目还是不是被点的那首"。但"第一首自然播完、播放器自动切到下一首" 同样会让判据为 false ——
     * 队列于是只补到"第一首播完前解析完的那几首"（真机实测：点 2:07 的歌补到 226/349；点 0:38 的歌只补到 **63** 首，即用户报的「队列只有 64
     * 首」、随机只能在少数几首里循环）。 现在补齐的终止条件只有"本次起播被取消"（换队列 / 点新歌走 `playJob?.cancel()`）与"队列已清空"，
     * 与当前播到第几首无关，因此自动切歌不再打断补齐。
     *
     * 分批（[QUEUE_FILL_BATCH_SIZE]）并发解析后一次性入队：把 349 首串行解析的 ~2.5 分钟压到 ~30 秒， 同时把内核 `addMediaItems`
     * 与媒体通知重建次数从 349 次降到约 1/6。
     */
    private suspend fun fillQueueAround(
        songs: List<MusicSong>,
        startIndex: Int,
        requestId: Long,
        snapshotItems: Map<UUID, PlayerItem>,
    ) {
        // 批次计划由纯函数给出（见 musicQueueFillBatches），批次顺序有单测钉死
        val batches = musicQueueFillBatches(startIndex, songs.size, QUEUE_FILL_BATCH_SIZE)
        // 当前之后的曲目：按批次追加到队尾（第一批就含"下一首"，用户随时点下一首都有效）
        for (batch in batches.append) {
            if (!isCurrentPlaybackRequest(requestId, nextPlayRequestId)) return
            if (playbackController.queue.value == null) return
            val items = resolveQueueBatch(songs, batch, snapshotItems)
            if (!isCurrentPlaybackRequest(requestId, nextPlayRequestId)) return
            if (items.isNotEmpty()) queueEditor.appendToQueue(items)
        }
        // 当前之前的曲目：按批次前插到队首（批间降序、批内升序 —— 见 musicQueueFillBatches）
        for (batch in batches.prepend) {
            if (!isCurrentPlaybackRequest(requestId, nextPlayRequestId)) return
            if (playbackController.queue.value == null) return
            val items = resolveQueueBatch(songs, batch, snapshotItems)
            if (!isCurrentPlaybackRequest(requestId, nextPlayRequestId)) return
            if (items.isNotEmpty()) queueEditor.prependToQueue(items)
        }
        if (playbackController.queue.value != null) {
            Timber.d("音乐队列补齐结束：目标 %d 首", songs.size)
        }
    }

    /** 批内并发解析、结果保持批内顺序；解析失败的曲目不进队列（起播不因列表里某一首坏文件整体失败）。 */
    private suspend fun resolveQueueBatch(
        songs: List<MusicSong>,
        indexes: List<Int>,
        snapshotItems: Map<UUID, PlayerItem>,
    ): List<PlayerItem> = coroutineScope {
        indexes.map { index -> async { resolveQueueItem(songs[index], snapshotItems) } }.awaitAll()
    }
        .filterNotNull()

    /** 单曲解析；失败只跳过该曲（起播不因列表里某一首坏文件而整体失败）。 */
    private suspend fun resolveQueueItem(
        song: MusicSong,
        snapshotItems: Map<UUID, PlayerItem> = emptyMap(),
    ): PlayerItem? =
        // W9-T5（F4b）：歌单快照只复用本地可播地址，服务器流地址按当前会话重解析。
        snapshotItems[song.itemId]?.takeIf { item -> isLocalPlaybackUri(item.mediaSourceUri) }
            ?: try {
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

    /**
     * 恢复态下点播放 / 切歌：把恢复快照交给播放器（按保存位置续播），随后进入正常播放语义。
     *
     * W9-T5（F4b）：快照里的 http(s) 流地址是上个会话的产物（旧主机 / 旧账号 / 已断网），起播前按当前解析链重解析当前曲目；
     * 下载曲在解析链内本地短路，离线也能直接命中本地文件。解析失败沿用快照条目，不阻断这次起播。
     */
    private fun resumeRestored(queue: MusicQueue, index: Int) {
        // W5-T12：恢复态点播放 / 切歌 = 传输操作，Auto DJ 刹车计数清零。
        autoDjController.onUserTransportAction()
        if (index !in queue.items.indices) return
        restorePlayJob?.cancel()
        restorePlayJob = viewModelScope.launch {
            val focus = queue.upNextPlaying ?: queue.items[index]
            val reparsed = focus.reparsedForPlayback { song -> trackResolver.toPlayerItem(song) }
            val ready =
                if (queue.upNextPlaying != null) {
                    queue.copy(upNextPlaying = reparsed)
                } else {
                    queue.copy(
                        items =
                            queue.items.mapIndexed { itemIndex, item ->
                                if (itemIndex == index) reparsed else item
                            }
                    )
                }
            _restoredQueue.value = null
            playbackController.setQueue(ready, startIndex = index)
        }
    }

    /**
     * W5 T1（MUSIC-47）+ W5 T5：长按上下文菜单「加到队尾」——解析出可播放条目后加到**「接下来播放」队尾**。
     *
     * 与「下一首播放」（[playNext] / `insertNext`）互补：一个插队首、一个排队尾，两者都优先于主队列消费、播完即出。
     */
    fun enqueueSongAtEnd(song: MusicSong) {
        autoDjController.onUserTransportAction()
        viewModelScope.launch {
            runCatching { trackResolver.toPlayerItem(song) }
                .onSuccess { item -> queueEditor.addToUpNext(item) }
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

    /**
     * W3-T15：上下文菜单队列动作统一入口——插入位置由 [musicSongQueueInsertion] 决定，队列语义与既有方法一致 （[playNext] 插队首 /
     * [enqueueSongAtEnd] 排队尾），本卡不改队列语义（T17 才改 playSong）。
     */
    fun enqueueSongForAction(song: MusicSong, action: MusicSongAction) {
        when (musicSongQueueInsertion(action)) {
            MusicQueueInsertion.UP_NEXT_HEAD -> playNext(song)
            MusicQueueInsertion.UP_NEXT_TAIL -> enqueueSongAtEnd(song)
            null -> Unit
        }
    }

    /** W3-T15：曲目上下文菜单「查看专辑」——按当前曲库聚合定位所属专辑（找不到时静默忽略，与 [openArtistByName] 同口径）。 */
    fun openAlbumOfSong(song: MusicSong) {
        val state = _uiState.value
        val album = findMusicAlbum(state.albums, state.songs, song.albumName) ?: return
        openAlbum(album)
    }

    /**
     * W3-T20：手动刷新单曲元数据（曲目 ⋮ 菜单「刷新元数据」）。
     *
     * 只对服务器曲目生效：`POST /Items/{id}/Refresh`（Default + 不覆盖用户字段）→ 短暂等待服务端任务 → 回读该曲并 合并进列表 / 详情（名称 / 封面
     * / 时长等即时更新），最后用 snackbar 反馈「已刷新元数据」/「刷新失败」。
     */
    fun refreshSongMetadata(song: MusicSong) {
        if (song.source != MusicItemSource.SERVER) {
            viewModelScope.launch { metadataRefreshFeedbackChannel.send("刷新失败") }
            return
        }
        viewModelScope.launch {
            when (repository.refreshSongMetadata(song.itemId)) {
                is SongMetadataRefreshResult.Failed -> {
                    Timber.w("刷新元数据：item=%s 失败", song.itemId)
                    metadataRefreshFeedbackChannel.send("刷新失败")
                }
                SongMetadataRefreshResult.Refreshed -> {
                    // 刷新是服务端异步任务：短暂等待后再回读，避免立刻拿到旧快照。
                    delay(METADATA_REFRESH_REREAD_DELAY_MS)
                    val fresh = repository.getSong(song.itemId)
                    fresh?.let(::applySongMetadataUpdate)
                    Timber.i(
                        "刷新元数据：item=%s 成功，回读=%s",
                        song.itemId,
                        if (fresh == null) "无数据" else "已更新",
                    )
                    metadataRefreshFeedbackChannel.send("已刷新元数据")
                }
            }
        }
    }

    /** W3-T20：把刷新后的曲目合并回曲库与当前详情层（行内标题 / 封面即时更新）。 */
    private fun applySongMetadataUpdate(fresh: MusicSong) {
        serverLibrary =
            serverLibrary.copy(
                songs =
                    serverLibrary.songs.map { existing ->
                        if (existing.itemId == fresh.itemId) existing.mergedWithFresh(fresh)
                        else existing
                    }
            )
        _uiState.update { state ->
            val detail = state.detail
            val updatedDetail =
                if (detail != null && detail.songs.any { song -> song.itemId == fresh.itemId }) {
                    val merged = detail.songs.first { song -> song.itemId == fresh.itemId }
                    detail.withUpdatedSong(merged.mergedWithFresh(fresh))
                } else {
                    detail
                }
            state.copy(detail = updatedDetail)
        }
        publishLibraries()
    }

    /**
     * W3-T20：无封面补取（负责人裁定口径）。
     *
     * 列表加载完成后等待 [MUSIC_ARTWORK_BACKFILL_DELAY_MS]，对「服务器来源且无封面」的曲目**只发 GET 回读** （每会话每曲一次，最多
     * [MUSIC_ARTWORK_BACKFILL_BATCH_LIMIT] 首、并发 [MUSIC_ARTWORK_BACKFILL_CONCURRENCY]）；
     * 回读仍无封面就跳过——**不自动调用 Refresh POST**（生产服只读红线；Refresh 只保留菜单手动触发）。
     */
    private fun scheduleArtworkBackfill() {
        if (_uiState.value.offline) return
        if (artworkBackfillJob?.isActive == true) return
        artworkBackfillJob = viewModelScope.launch {
            delay(MUSIC_ARTWORK_BACKFILL_DELAY_MS)
            val candidates =
                musicArtworkBackfillCandidates(
                    songs = serverLibrary.songs,
                    triedItemIds = artworkBackfillTried,
                )
            if (candidates.isEmpty()) {
                Timber.d("无封面补取：本次无可补取曲目（已试 %d）", artworkBackfillTried.size)
                return@launch
            }
            var hit = 0
            for (chunk in musicArtworkBackfillChunks(candidates)) {
                if (_uiState.value.offline) return@launch
                val resolved = coroutineScope {
                    chunk
                        .map { itemId ->
                            async {
                                itemId to runCatching { repository.getSong(itemId) }.getOrNull()
                            }
                        }
                        .awaitAll()
                }
                resolved.forEach { (itemId, fresh) ->
                    artworkBackfillTried += itemId
                    if (fresh?.imageUri != null) {
                        applySongMetadataUpdate(fresh)
                        hit++
                    }
                }
            }
            Timber.d(
                "无封面补取：候选 %d（已试 %d），命中 %d",
                candidates.size,
                artworkBackfillTried.size,
                hit,
            )
        }
    }

    /** W5 T1（MUSIC-19）：播放页点艺术家名 → 艺术家详情（按当前曲库聚合的艺人分组定位，找不到则忽略）。 */
    fun openArtistByName(name: String) {
        val state = _uiState.value
        val artist =
            state.artists.firstOrNull { it.name == name }
                ?: groupArtists(state.songs.filter { song -> song.artist == name }).firstOrNull()
        if (artist != null) openArtist(artist)
    }

    fun moveQueueItem(fromIndex: Int, toIndex: Int) {
        autoDjController.onUserTransportAction()
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
        autoDjController.onUserTransportAction()
        val restored = _restoredQueue.value
        if (playbackController.queue.value == null && restored != null) {
            resumeRestored(restored, index)
            return
        }
        queueEditor.jumpTo(index)
    }

    /** W5 T5：「接下来播放」第 [index] 条立即播放（中间的待播条目按「播完即出」出列）。 */
    fun jumpToUpNextItem(index: Int) {
        autoDjController.onUserTransportAction()
        val restored = _restoredQueue.value
        if (playbackController.queue.value == null && restored != null) {
            // 恢复态没有播放器：先把快照交给播放器，再跳到该条
            val target = restored.upNext.getOrNull(index) ?: return
            resumeRestored(
                restored.copy(upNextPlaying = target, upNext = restored.upNext.drop(index + 1)),
                restored.currentIndex,
            )
            return
        }
        queueEditor.jumpToUpNext(index)
    }

    fun removeQueueItem(index: Int) {
        autoDjController.onUserTransportAction()
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

    /** W5 T5（MUSIC-11）：左滑 / ✕ 移除「接下来播放」第 [index] 条。 */
    /**
     * W5-T3（MUSIC-42）：队列左滑移除的「撤销」——原位插回。
     *
     * 恢复态直接改快照（[musicQueueInsertAt]）；实时态走既有用户级操作组合：先 `appendToQueue` 追加队尾（同步写回 `_queue.value`）， 再
     * `move` 到原下标（`MusicQueueEditor` 没有 insert-at，`player/local` 属禁改边界）。
     */
    fun undoRemoveQueueItem(index: Int, item: PlayerItem) {
        autoDjController.onUserTransportAction()
        val restored = _restoredQueue.value
        if (playbackController.queue.value == null && restored != null) {
            val updated = musicQueueInsertAt(restored, index, item)
            _restoredQueue.value = updated
            persister.persistNow(updated)
            return
        }
        val live = playbackController.queue.value ?: return
        val target = index.coerceIn(0, live.items.size)
        queueEditor.appendToQueue(listOf(item))
        playbackController.move(live.items.size, target)
    }

    fun removeUpNextItem(index: Int) {
        autoDjController.onUserTransportAction()
        val restored = _restoredQueue.value
        if (playbackController.queue.value == null && restored != null) {
            val updated = restored.removeUpNextAt(index)
            if (updated != restored) {
                _restoredQueue.value = updated
                persister.persistNow(updated)
            }
            return
        }
        queueEditor.removeUpNextAt(index)
    }

    /** W5 T5（MUSIC-11）：清空「接下来播放」（不动主队列，也不打断正在播放的曲目）。 */
    fun clearUpNext() {
        autoDjController.onUserTransportAction()
        val restored = _restoredQueue.value
        if (playbackController.queue.value == null && restored != null) {
            val updated = restored.clearUpNext()
            if (updated != restored) {
                _restoredQueue.value = updated
                persister.persistNow(updated)
            }
            return
        }
        queueEditor.clearUpNext()
    }

    fun togglePlayPause() {
        autoDjController.onUserTransportAction()
        val restored = _restoredQueue.value
        if (playbackController.queue.value == null && restored != null) {
            resumeRestored(restored, restored.currentIndex)
            return
        }
        playbackController.playPause()
    }

    /**
     * W56：迷你播放条「关闭面板」= 停止播放并收起面板（用户 2026-10-04 拍板）。
     *
     * 活动会话走 [MusicPlaybackController.stop]（停播 + 清内存队列；队列存档只写不清，重启仍可恢复）；
     * 恢复态只丢本次进页面的展示快照，不动磁盘存档；收起后再次选择曲目走既有起播路径。
     */
    fun dismissNowPlayingBar() {
        autoDjController.onUserTransportAction()
        when (
            musicMiniBarDismissTarget(
                hasLiveQueue = playbackController.queue.value != null,
                hasRestoredQueue = _restoredQueue.value != null,
            )
        ) {
            MusicMiniBarDismissTarget.StopPlayback -> playbackController.stop()
            MusicMiniBarDismissTarget.DropRestoredQueue -> _restoredQueue.value = null
            MusicMiniBarDismissTarget.None -> Unit
        }
    }

    fun skipToNext() {
        autoDjController.onUserTransportAction()
        val restored = _restoredQueue.value
        if (playbackController.queue.value == null && restored != null) {
            resumeRestored(
                restored,
                (restored.currentIndex + 1).coerceAtMost(restored.items.lastIndex),
            )
            return
        }
        if (playMode.value.isShuffle) {
            // 随机下一曲交给内核 shuffle 顺序（DefaultShuffleOrder：一轮内每首各一次 = 未播优先）
            Timber.i(
                "随机下一曲：当前 index=%d，按内核随机顺序切歌",
                playbackController.queue.value?.currentIndex ?: -1,
            )
        }
        playbackController.next()
    }

    fun skipToPrevious() {
        autoDjController.onUserTransportAction()
        val restored = _restoredQueue.value
        if (playbackController.queue.value == null && restored != null) {
            resumeRestored(restored, (restored.currentIndex - 1).coerceAtLeast(0))
            return
        }
        val live = playbackController.queue.value
        if (playMode.value.isShuffle && live != null) {
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
            mode.toShuffleMode(),
        )
        if (playbackController.queue.value != null) {
            playbackController.setRepeatMode(mode.toRepeatMode())
            queueEditor.setShuffleMode(mode.toShuffleMode())
            return
        }
        val restored = _restoredQueue.value ?: return
        val updated =
            restored.copy(
                repeatMode = mode.toRepeatMode(),
                shuffleEnabled = mode.toShuffleEnabled(),
                shuffleKeepCurrentFirst = mode == MusicPlayMode.SHUFFLE_CURRENT_FIRST,
            )
        if (updated != restored) {
            _restoredQueue.value = updated
            persister.persistNow(updated)
        }
    }

    /** 拖动全屏进度条（B 组）：恢复态没有播放会话，忽略。 */
    fun seekTo(positionMs: Long) {
        autoDjController.onUserTransportAction()
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
            val outcome = runCatching { repository.setFavorite(song.itemId, favorite) }.getOrNull()
            if (outcome is UserDataWriteOutcome.Failed || outcome == null) {
                val failure = (outcome as? UserDataWriteOutcome.Failed)?.failure
                Timber.w("收藏失败：itemId=${song.itemId}")
                _uiState.update {
                    it.copy(
                        errorTitle = "收藏失败",
                        errorMessage = failure.writeErrorMessage("无法写入服务器收藏"),
                    )
                }
            } else {
                // W9-T4：网络 / 5xx 时本地已落收藏位并排队，提示「联网后自动同步」（与影视详情页同口径）。
                favoriteWriteNotice(outcome)?.let { favoriteWriteNoticeChannel.send(it) }
                Timber.i("收藏成功：itemId=${song.itemId} favorite=$favorite queued=$outcome")
                _uiState.update { state -> state.withFavorite(song.itemId, favorite) }
            }
        }
    }

    /** W8-T1：写失败文案按可重试性区分——权限拒绝不提示「稍后重试」（V2-WRITE-09）。 */
    private fun UserDataWriteFailure?.writeErrorMessage(fallback: String): String =
        when (this) {
            is UserDataWriteFailure.PermissionDenied -> "没有权限执行该操作（服务器返回 $status）"
            is UserDataWriteFailure.Network -> "网络不可用，请检查网络后重试"
            is UserDataWriteFailure.ServerError -> "服务器暂时不可用，请稍后重试"
            is UserDataWriteFailure.Unknown,
            null -> fallback
        }

    private fun UiState.withFavorite(itemId: UUID, favorite: Boolean): UiState {
        return withFavorites(setOf(itemId), favorite)
    }

    /** W58：批量收藏 / 取消收藏后同步所有列表（单曲路径复用同一实现）。 */
    private fun UiState.withFavorites(itemIds: Set<UUID>, favorite: Boolean): UiState {
        fun MusicSong.refreshed(): MusicSong =
            if (this.itemId in itemIds) copy(isFavorite = favorite) else this

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
                        current.copy(songs = current.songs.filterNot { it.itemId in itemIds })
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

    /** W5-T6 · MUSIC-33：「播完当前曲目停止」——暂停挂起，AUTO 切歌后由播放链暂停。 */
    fun stopAfterCurrentTrack() = sleepTimer.selectStopAfterCurrentTrack()

    /** 音频信息（W5-T6 · MUSIC-34）：源侧来自 [TrackSource]；只有该曲目正是当前音乐会话曲目时才带输出侧读数与转码判定。 */
    fun audioInfoFor(song: MusicSong): MusicAudioInfo {
        val liveItem = playbackController.queue.value?.currentItem
        val playing = liveItem?.itemId == song.itemId
        return buildMusicAudioInfo(
            title = song.name,
            source = if (playing) liveItem?.trackSource else song.toTrackSourceSnapshot(),
            output = if (playing) audioOutputInfo.snapshot(song.itemId) else null,
            playing = playing,
        )
    }

    /** 音效面板「输出信息」入口：当前展示曲目（无会话时回落到恢复快照，输出侧显「未播放」）。 */
    fun audioInfoForCurrent(): MusicAudioInfo? {
        val liveItem = playbackController.queue.value?.currentItem
        val item = liveItem ?: displayCurrentItem.value ?: return null
        val playing = liveItem != null && liveItem.itemId == item.itemId
        return buildMusicAudioInfo(
            title = item.name,
            source = item.trackSource,
            output = if (playing) audioOutputInfo.snapshot(item.itemId) else null,
            playing = playing,
        )
    }

    /** 曲库快照 → 源侧信息（非当前曲目没有 Exo 协商结果，转码状态不猜）。 */
    private fun MusicSong.toTrackSourceSnapshot(): TrackSource =
        TrackSource(
            id = itemId.toString(),
            sourceType = if (localUri != null) TrackSourceType.LOCAL else TrackSourceType.SERVER,
            streamUrl = localUri.orEmpty(),
            duration = runtimeTicks / TICKS_PER_MS,
            bitrate = bitrate,
            codec = codec,
            container = container,
            sampleRate = sampleRate,
            bitDepth = bitDepth,
            isTranscoded = false,
        )

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

    /** W5-T6 · MUSIC-32：选择音乐倍速（进程级，不落偏好；起播时回到 1.0）。 */
    fun selectPlaybackSpeed(speed: Float) = audioEffects.setPlaybackSpeed(speed)

    fun dismissError() {
        _uiState.update {
            it.copy(
                errorTitle = null,
                errorMessage = null,
                loadErrorTitle = null,
                loadErrorMessage = null,
            )
        }
    }

    private companion object {
        /** 曲库加载失败后的自动重试等待（毫秒）：给弱网 / 服务端偶发超时一次恢复窗口。 */
        const val AUTO_RETRY_DELAY_MS = 1_200L

        /** W3-T20：手动刷新后等待服务端刷新任务落库再回读（毫秒；刷新接口异步执行）。 */
        const val METADATA_REFRESH_REREAD_DELAY_MS = 800L

        /** Jellyfin runtimeTicks：1 tick = 100 ns → 1 ms = 10_000 ticks。 */
        private const val TICKS_PER_MS = 10_000L

        /**
         * W78 补队列的批大小 = 批内并发度。
         *
         * 兼顾三点：①"下一首"尽快就位（第一批就含它，约 0.5 s 入队）； ②服务器压力（同时最多 6 个 `PlaybackInfo`，与批量播放的 4 路预取同量级）； ③内核
         * `addMediaItems` 与媒体通知的重建次数从"每首一次"降到"每批一次"。
         */
        const val QUEUE_FILL_BATCH_SIZE = 6
    }
}
