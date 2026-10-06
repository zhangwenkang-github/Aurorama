package com.zhangwenkang.cinefin.player.local.presentation

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.widget.Toast
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import com.zhangwenkang.cinefin.language.LanguageMatcher
import com.zhangwenkang.cinefin.models.FindroidEpisode
import com.zhangwenkang.cinefin.models.FindroidMovie
import com.zhangwenkang.cinefin.models.FindroidSegment
import com.zhangwenkang.cinefin.models.FindroidSegmentType
import com.zhangwenkang.cinefin.player.core.domain.models.PLAYER_EXTRA_EPISODE_NUMBER
import com.zhangwenkang.cinefin.player.core.domain.models.PLAYER_EXTRA_MEDIA_INFO
import com.zhangwenkang.cinefin.player.core.domain.models.PLAYER_EXTRA_SEASON_NUMBER
import com.zhangwenkang.cinefin.player.core.domain.models.PLAYER_EXTRA_SUBTITLE_SOURCES
import com.zhangwenkang.cinefin.player.core.domain.models.PlayerChapter
import com.zhangwenkang.cinefin.player.core.domain.models.PlayerItem
import com.zhangwenkang.cinefin.player.core.domain.models.PlayerMediaInfo
import com.zhangwenkang.cinefin.player.core.domain.models.PlayerSubtitleSource
import com.zhangwenkang.cinefin.player.core.domain.models.SubtitleStyle
import com.zhangwenkang.cinefin.player.local.R
import com.zhangwenkang.cinefin.player.local.audio.AudioDelayProcessor
import com.zhangwenkang.cinefin.player.local.domain.PlaybackPositionWriter
import com.zhangwenkang.cinefin.player.local.domain.PlaybackQueueEntry
import com.zhangwenkang.cinefin.player.local.domain.PlayerDecodeMode
import com.zhangwenkang.cinefin.player.local.domain.PlayerEndBehavior
import com.zhangwenkang.cinefin.player.local.domain.PlayerExtraPreferences
import com.zhangwenkang.cinefin.player.local.domain.PlayerItemEndAction
import com.zhangwenkang.cinefin.player.local.domain.PlayerQueueEndAction
import com.zhangwenkang.cinefin.player.local.domain.PlaylistManager
import com.zhangwenkang.cinefin.player.local.domain.SleepTimerController
import com.zhangwenkang.cinefin.player.local.domain.TrackSelectionEngine
import com.zhangwenkang.cinefin.player.local.domain.TrickplayTiles
import com.zhangwenkang.cinefin.player.local.domain.isSeekRequestReady
import com.zhangwenkang.cinefin.player.local.domain.isTranscodeStreamUri
import com.zhangwenkang.cinefin.player.local.domain.seekTargetFromFraction
import com.zhangwenkang.cinefin.player.local.domain.shouldReleasePlayerOnExit
import com.zhangwenkang.cinefin.player.local.domain.shouldRestartTranscodeSession
import com.zhangwenkang.cinefin.player.local.mpv.MPVPlayer
import com.zhangwenkang.cinefin.player.local.subtitle.PlayerSubtitleController
import com.zhangwenkang.cinefin.player.local.subtitle.SideloadedSubtitle
import com.zhangwenkang.cinefin.player.local.subtitle.SideloadedSubtitleStore
import com.zhangwenkang.cinefin.player.local.subtitle.SubtitleOverlayState
import com.zhangwenkang.cinefin.repository.JellyfinRepository
import com.zhangwenkang.cinefin.settings.domain.AppPreferences
import com.zhangwenkang.cinefin.settings.domain.Constants
import com.zhangwenkang.cinefin.settings.domain.PlayerDecodeFallback
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.model.api.BaseItemKind
import timber.log.Timber

@HiltViewModel
class PlayerViewModel
@Inject
constructor(
    private val application: Application,
    private val playlistManager: PlaylistManager,
    private val repository: JellyfinRepository,
    /**
     * W20（§1.11）：进度除了走会话上报，还要**直接写 UserData**——真机实测 `Sessions/Playing/Progress` 不落
     * `UserData.PlaybackPositionTicks`， 进程被杀之后续播会回到 0（详见 [PlaybackPositionWriter] 注释）。
     */
    private val playbackPositionWriter: PlaybackPositionWriter,
    private val appPreferences: AppPreferences,
    /** 播放器实例由进程级单例持有：播放页关闭后通知栏 / 后台播放仍要能控制它（阶段 4.1） */
    private val playerHolder: PlayerHolder,
    private val savedStateHandle: SavedStateHandle,
    /** W55 睡眠定时统一：进程级状态源（音乐 / 视频共用同一计时器）。 */
    private val sleepTimerController: SleepTimerController,
) : ViewModel(), Player.Listener {
    companion object {
        /** 播放核心取值，与 `AppPreferences.playerBackend` 里存的一致 */
        const val PLAYER_BACKEND_EXOPLAYER = "exoplayer"
        const val PLAYER_BACKEND_MPV = "mpv"

        /**
         * 解码策略（W12 反馈 B）：硬解优先（失败自动回退） / 仅软解。
         *
         * 优先级（W16 用户拍板，与 W12 相反）：**本地硬解 → 服务器解码/转码 → 本地软解**。
         * 软解最耗电，排在最后：只有硬解报「解码能力不足」且服务器转码救不回来时才落地。
         */
        const val DECODE_MODE_HARDWARE = "hardware"
        const val DECODE_MODE_SOFTWARE = "software"

        /** 单次队列补片最多新增条数：超大剧集（库里有 370 集的）不一次拉满，避免长时间占用网络与播放器时间线 */
        private const val MAX_QUEUE_FILL_ITEMS = 150

        /** 每条插入之间让出主线程的时间（> 1 帧），保证输入 / 渲染消息有机会执行 */
        private const val QUEUE_FILL_STEP_DELAY_MS = 50L

        /** W20（§1.11）：进度常驻上报间隔。播放中每 5 秒向服务端写一次 UserData 进度 */
        private const val PROGRESS_REPORT_INTERVAL_MS = 5_000L

        /** W20：同一集「停止上报」的去重窗口（自然播完已报过 stop，换集回调不要再报一次） */
        private const val STOP_REPORT_DEDUPE_MS = 15_000L

        /** W73（#7）：进度条 seek 在「未就绪窗口」的排队轮询间隔 / 超时（约 30 s） */
        private const val PENDING_SEEK_RETRY_MS = 200L
        private const val PENDING_SEEK_MAX_RETRIES = 150

        /** W73（#7）：排队相对增量的饱和上限（±6 小时）。 */
        private const val PENDING_SEEK_DELTA_MAX_MS = 6L * 60L * 60L * 1000L

        /** W73（#7）：转码重开会话的尾部去抖（拖动中的连续 seek 只执行最后一次）。 */
        private const val TRANSCODE_RESTART_DEBOUNCE_MS = 150L

        /** W73（#7）：同一媒体的转码重开会话在窗口内的次数上限（防止异常时无限重开）。 */
        private const val TRANSCODE_RESTART_WINDOW_MS = 30_000L
        private const val TRANSCODE_RESTART_WINDOW_MAX = 6

        /** W73（#7）：直接 seek 后若目标附近仍在缓冲，先观察这么久再决定是否重开会话。 */
        private const val TRANSCODE_SEEK_WATCHDOG_DELAY_MS = 2_500L
        private const val TRANSCODE_SEEK_WATCHDOG_TOLERANCE_MS = 8_000L

        /** W73（#8）：网络类播放错误的原地重试窗口 / 次数上限（超过则交给错误卡片，不换内核、不重启页面）。 */
        private const val NETWORK_RETRY_WINDOW_MS = 30_000L
        private const val NETWORK_RETRY_MIN_INTERVAL_MS = 3_000L
        private const val NETWORK_RETRY_MAX = 2
    }

    val player: Player
        get() = playerHolder.player

    private val _uiState =
        MutableStateFlow(
            UiState(
                currentItemTitle = "",
                currentSegment = null,
                currentSkipButtonStringRes = R.string.player_controls_skip_intro,
                currentChapters = emptyList(),
                fileLoaded = false,
            )
        )
    val uiState = _uiState.asStateFlow()

    /** W55 睡眠定时状态（统一状态源）：播放器睡眠键激活态与睡眠面板消费。 */
    val sleepTimerState = sleepTimerController.state

    /** W55：选择睡眠定时分钟；null = 取消。 */
    fun selectSleepTimer(minutes: Int?) = sleepTimerController.select(minutes)

    /**
     * W18：事件通道必须有缓冲。
     *
     * 无缓冲（rendezvous）通道上 `trySend` 只有在「接收方此刻正挂起等待」时才成功——回退链的事件是在
     * `onPlayerError`（主线程）里发出的，若此刻主线程正忙于重组 / 处理上一条事件，事件会被**静默丢弃**， 而 `handleCodecFallback`
     * 已经返回「已接管」→ 既没有回退、也没有错误卡片（用户看到的就是 「解码失败后没有切回 mpv」）。缓冲后 `trySend` 不会丢事件。
     */
    private val eventsChannel = Channel<PlayerEvents>(capacity = Channel.BUFFERED)
    val eventsChannelFlow = eventsChannel.receiveAsFlow()

    /** W18：上一次被回退链接管的失败（内核:档位 + 时间），用于吞掉同一次失败的重复上报 */
    private var lastFailureHandledKey: String? = null
    private var lastFailureHandledAtMs: Long = 0L

    data class UiState(
        val currentItemTitle: String,
        /** 当前播放条目的 id，供播放页做海报取色等与条目相关的效果 */
        val currentItemId: UUID? = null,
        /** 当前条目的媒体源元数据（§1.8 信息面板；内核侧实测值由面板再合并一层） */
        val currentMediaInfo: PlayerMediaInfo? = null,
        val currentSegment: FindroidSegment?,
        val currentSkipButtonStringRes: Int,
        /**
         * W20（§1.11 Trickplay）：预览图时间间隔（毫秒）；0 = 本条目没有 trickplay / 已关闭， 控制层不做任何预览查询。缩略图不再随 uiState
         * 整批携带，改成按需向 [trickplayFrameAt] 取。
         */
        val trickplayIntervalMs: Int = 0,
        /** W20：每加载好一张精灵图 +1；Compose 用它触发重组，把新到位的预览图刷出来 */
        val trickplayVersion: Int = 0,
        val currentChapters: List<PlayerChapter>,
        val fileLoaded: Boolean,
        /**
         * W20（§6.1 片头片尾）：跳过提示条的显示时长（毫秒）。
         *
         * 来源是设置里的 `pref_player_media_segments_skip_button_duration`（秒）； 之前播放页写死 8 秒，用户设置的阈值根本不生效。
         */
        val skipChipDurationMs: Long = 5_000L,
        /** 播放失败时的错误信息；非空时控制层显示错误卡片 */
        val playerError: PlayerErrorInfo? = null,
    )

    /** 播放错误：给控制层的错误卡片用（阶段 1.3） */
    data class PlayerErrorInfo(
        /** 后端给出的说明，可能较长，UI 侧截断显示 */
        val message: String,
        /** 错误码名，例如 ERROR_CODE_DECODING_FAILED */
        val codeName: String,
        /** 出错时使用的内核：exoplayer / mpv */
        val backend: String,
    )

    /** 字幕面板里的一行轨道选项（两个内核共用同一套 UI） */
    data class SubtitleOption(
        /** ExoPlayer：Jellyfin 字幕源序号；mpv：mpv 的 track id */
        val id: Int,
        val label: String,
        val caption: String? = null,
        /** 图形字幕等不能调节的轨道：仍可选中（交给内核渲染），但面板会说明 */
        val adjustable: Boolean = true,
        val selected: Boolean = false,
        /** W27：本机导入的侧载字幕（面板行尾给「移除」入口） */
        val sideloaded: Boolean = false,
    )

    /**
     * 字幕面板状态。
     *
     * 两个内核最终都汇总成这一份状态：ExoPlayer 走自研字幕管线（Jellyfin 源清单）， mpv 走 mpv 自己的字幕轨（track id），面板 UI 不需要知道区别。
     */
    data class SubtitlePanelState(
        val primaryOptions: List<SubtitleOption> = emptyList(),
        val secondaryOptions: List<SubtitleOption> = emptyList(),
        val primaryId: Int? = null,
        val secondaryId: Int? = null,
        val delayMs: Long = 0L,
        val style: SubtitleStyle = SubtitleStyle(),
        /** 主字幕是否由自研渲染接管（决定延迟是否即时生效、能否做双语） */
        val managed: Boolean = false,
        /** 当前媒体里存在可用的文字字幕（能调延迟 / 双语） */
        val controllable: Boolean = false,
        val loading: Boolean = false,
    )

    /** 音轨面板里的一行：轨道信息（编码 / 声道 / 码率） */
    data class AudioOption(
        /** 音频轨道组下标：ExoPlayer 与 mpv 都用同一套下标定位 */
        val id: Int,
        val label: String,
        val caption: String? = null,
        val selected: Boolean = false,
    )

    /** 音轨面板状态（§1.2：音轨延迟 + 轨道描述） */
    data class AudioPanelState(
        val options: List<AudioOption> = emptyList(),
        /** 音轨延迟（毫秒），正 = 声音延后 */
        val delayMs: Long = 0L,
    )

    private var items: MutableList<PlayerItem> = mutableListOf()

    /* ---------- W73（#7）：seek 排队 + 转码重开会话状态 ---------- */

    /** 未就绪窗口里排队的 seek：绝对目标 / 进度条比例 / 相对增量三选一；null = 无排队。 */
    private sealed interface PendingSeek {
        data class Absolute(val targetMs: Long) : PendingSeek

        data class Fraction(val fraction: Float) : PendingSeek

        data class Relative(val deltaMs: Long) : PendingSeek
    }

    private var pendingSeek: PendingSeek? = null

    private var pendingSeekSource: String = ""
    private var pendingSeekFlushJob: Job? = null

    /** 转码重开会话的尾部去抖任务；拖动 / 连点只落最后一次目标。 */
    private var transcodeRestartDebounceJob: Job? = null

    /** 重开会话进行中时又有新目标：记下来，完成后再合并执行一次。 */
    private var transcodeRestartPendingTargetMs: Long? = null
    private var transcodeRestartInFlight = false
    private var transcodeRestartWindowStartMs = 0L
    private var transcodeRestartWindowCount = 0
    private var lastTranscodeRestartMediaId: String? = null
    private var transcodeSeekWatchdogJob: Job? = null

    /* ---------- W73（#8）：网络类播放错误的原地重试状态 ---------- */

    private var lastNetworkErrorMediaId: String? = null
    private var lastNetworkErrorAtMs = 0L
    private var networkRetryAttempts = 0

    /** 字幕/音轨的智能选择引擎：按语言优先级自动选轨，并记住用户的手动选择 */
    private val trackSelectionEngine = TrackSelectionEngine(appPreferences)

    /** 自研字幕管线：延迟 / 双语 / 外观（ExoPlayer 文字字幕由它接管） */
    val subtitleController = PlayerSubtitleController(viewModelScope, appPreferences)

    /**
     * W27 侧载字幕：用户从文件选择器导入的字幕文件按播放条目保存在本机（文件存储，不写服务器）。
     *
     * [sideloads] 是「当前播放条目」的记录快照；换集 / 换片时用 [applySubtitlePipelineForMedia] 重载。
     */
    private val sideloadStore by lazy {
        SideloadedSubtitleStore(File(application.filesDir, "player_subtitles"))
    }

    private var sideloads: List<SideloadedSubtitle> = emptyList()

    private val _subtitlePanelState = MutableStateFlow(SubtitlePanelState())
    val subtitlePanelState = _subtitlePanelState.asStateFlow()

    private val _audioPanelState = MutableStateFlow(AudioPanelState())
    val audioPanelState = _audioPanelState.asStateFlow()

    /** mpv 内核当前使用的次字幕 track id（mpv 侧的状态，不落偏好） */
    private var mpvSecondarySubtitleId: Int? = null

    /** 用户在当前媒体里手动选过轨后，不再自动干预 */
    private var manualTrackSelectionMediaId: String? = null

    /** 后台补全播放队列的任务：重新起播时取消重来，避免两个任务同时插队 */
    private var queueFillJob: Job? = null

    /** 用户是否手动整理过队列（§1.7）：整理过之后不再让后台补片把删掉的条目补回来 */
    private var queueManuallyEdited = false

    var playWhenReady = true

    /**
     * W68：当前是否有音乐会话正在使用共享播放器实例。
     *
     * 视频播放页（含用户经锁屏 / 通知误入的情况）用它避免打断音乐后台播放：退出时不停服务、不释放实例。
     */
    val isMusicSessionActive: Boolean
        get() = playerHolder.musicSessionActive || playerHolder.isCurrentItemMusic

    /**
     * 起播窗口：`initializePlayer` 已发出、但媒体还没真正交给播放器（还在拉流 / 建播放信息）。
     *
     * bug ②（打开视频不自动播）：这个窗口里播放器的 `playWhenReady` 还是默认值 false， 此时若发生 pause / resume（通知权限弹窗、
     * 加载中切后台、切内核重启…），BasePlayerActivity 会把 false 回存再写回， 把随后 `initializePlayer` 里的 `play()`
     * 覆盖成暂停，用户必须手点一次。起播窗口内两边都不动播放状态，保证「打开即播」。
     */
    private var startupInProgress = false

    private var currentMediaItemIndex = savedStateHandle["mediaItemIndex"] ?: 0
    private var playbackPosition: Long = savedStateHandle["position"] ?: 0
    private var currentMediaItemSegments: List<FindroidSegment> = emptyList()

    /*
     * W20（§1.11 进度记忆）：最近一次「已上报 / 已知」的条目快照。
     *
     * `onMediaItemTransition` 触发时播放器里已经是**新**条目，读不到上一集的位置，
     * 所以切集 / 退出时用这份快照给上一条补一条 `Playing/Stopped`，
     * 保证「退出播放 / 切集 / 被杀后」服务端都能恢复到正确位置。
     */
    private var lastProgressItemId: UUID? = null
    private var lastProgressPositionMs: Long = 0L
    private var lastProgressDurationMs: Long = 0L
    private var lastStopReportedItemId: UUID? = null
    private var lastStopReportedAtMs: Long = 0L

    /** W20（§1.11 Trickplay）：按需加载的预览图（只拉当前拖动位置所在的精灵图） */
    private var trickplayLoader: TrickplayPreviewLoader? = null

    // Segments preferences
    var segmentsSkipButton: Boolean = false
    private var segmentsSkipButtonTypes: Set<String> = emptySet()
    var segmentsSkipButtonDuration: Long = 0L
    var segmentsAutoSkip: Boolean = false
    private var segmentsAutoSkipTypes: Set<String> = emptySet()
    private var segmentsAutoSkipMode: String = "always"

    var playbackSpeed: Float = 1f

    var isInPictureInPictureMode: Boolean = false

    /** 当前播放核心：`exoplayer` 或 `mpv`。换内核要重建 player，由 Activity 重启播放页生效 */
    val playerBackend: String
        get() = playerHolder.backend

    init {
        migrateEndBehaviorPreferences()
        segmentsSkipButton = appPreferences.getValue(appPreferences.playerMediaSegmentsSkipButton)
        segmentsSkipButtonTypes =
            appPreferences.getValue(appPreferences.playerMediaSegmentsSkipButtonType)
        segmentsSkipButtonDuration =
            appPreferences.getValue(appPreferences.playerMediaSegmentsSkipButtonDuration)
        segmentsAutoSkip = appPreferences.getValue(appPreferences.playerMediaSegmentsAutoSkip)
        segmentsAutoSkipTypes =
            appPreferences.getValue(appPreferences.playerMediaSegmentsAutoSkipType)
        segmentsAutoSkipMode =
            appPreferences.getValue(appPreferences.playerMediaSegmentsAutoSkipMode)

        publishAudioPanelState()

        /*
         * W20（§1.11）：进度上报从「Activity STARTED 期间才跑」改成 ViewModel 常驻协程。
         *
         * 旧实现挂在 `repeatOnLifecycle(STARTED)` 里：切后台 / 锁屏后循环就停了，
         * 后台继续播（后台播放开关）时被系统杀掉，服务端只剩进入后台前的位置。
         * 现在按播放状态判断——播放中每 5 秒上报一次，暂停 / 没内容时什么都不做。
         */
        viewModelScope.launch {
            while (isActive) {
                delay(PROGRESS_REPORT_INTERVAL_MS)
                val player = playerHolder.existingPlayer ?: continue
                if (!player.isPlaying) continue
                if (player.currentMediaItem == null) continue
                updatePlaybackProgress()
            }
        }

        // 自研字幕状态变化 → 刷新面板状态，并把「原生文字渲染」的开关同步给播放内核
        viewModelScope.launch {
            subtitleController.overlayState.collect { state ->
                publishSubtitlePanelState(state)
                applySubtitleRoutingOnly()
            }
        }
    }

    /**
     * 初始化并开始播放。
     *
     * @param startPositionMs 精确续播位置（毫秒）。换解码内核重启播放页时由 Activity 带入， 避免只依赖服务端 5 秒一次的上报而丢掉几秒进度；0
     *   表示按服务端记录续播。
     * @param queueEntries W58b 视频多选批量播放：非空 = 显式播放队列（电影 / 单集）， [itemId] 只用于在队列里找起播项（回退重启后仍指向实际播放条目）。
     */
    fun initializePlayer(
        itemId: UUID,
        itemKind: String,
        startFromBeginning: Boolean,
        startPositionMs: Long = 0L,
        playbackSessionId: String,
        queueEntries: List<PlaybackQueueEntry> = emptyList(),
    ) {
        /*
         * W19：回退档位按「播放会话」判定，必须在本会话拉取 PlaybackInfo 之前处理。
         *
         * 旧实现按 Intent 条目 id 判定「换条目」，但季 / 剧集入口与队列换集时 Intent 条目 ≠
         * 实际播放条目（如 Intent=季 id、实际播放=集 id）→ 每次回退重启都把刚推进的档位清零，
         * 链路在「硬解失败 → 请求转码 → 重启」之间死循环（真机实测 60s 重启 7 次，永远到不了 mpv）。
         * 现在回退重启复用同一个会话 id，档位活过 Activity 重建；新开播放页 = 新会话 → 清零。
         */
        prepareDecodeFallback(playbackSessionId)
        Timber.d(
            "initializePlayer: itemId=%s kind=%s session=%s fallbackStage=%d",
            itemId,
            itemKind,
            playbackSessionId,
            PlayerDecodeFallback.normalize(
                appPreferences.getValue(appPreferences.playerDecodeFallbackStage)
            ),
        )
        // 打开条目就先声明「要播」的意图：起播窗口内不被 pause/resume 回存覆盖（bug ②）
        startupInProgress = true
        playWhenReady = true
        player.addListener(this)
        applySavedSubtitlePreferences()
        syncMpvSubtitleMode()
        publishSubtitlePanelState()

        if (startPositionMs > 0L) {
            playbackPosition = startPositionMs
        }

        viewModelScope.launch {
            val startItem =
                try {
                    if (queueEntries.isNotEmpty()) {
                        playlistManager.getInitialItemForQueue(
                            entries = queueEntries,
                            preferredItemId = itemId,
                            startFromBeginning = startFromBeginning,
                            startPositionMs = startPositionMs.takeIf { it > 0L },
                        )
                    } else {
                        playlistManager.getInitialItem(
                            itemId = itemId,
                            itemKind = BaseItemKind.fromName(itemKind),
                            mediaSourceIndex = null,
                            startFromBeginning = startFromBeginning,
                            startPositionMs = startPositionMs.takeIf { it > 0L },
                        )
                    }
                } catch (e: Exception) {
                    Timber.e(e)
                    Toast.makeText(application, e.localizedMessage, Toast.LENGTH_LONG).show()
                    null
                }

            if (startItem == null) {
                Timber.e("No start item, stopping player initialization")
                startupInProgress = false
                return@launch
            }

            items = listOfNotNull(startItem).toMutableList()
            currentMediaItemIndex = items.indexOf(startItem)

            val mediaItems = mutableListOf<MediaItem>()
            try {
                for (item in items) {
                    mediaItems.add(item.toMediaItem())
                }
            } catch (e: Exception) {
                Timber.e(e)
            }

            val startPosition =
                if (playbackPosition == 0L) {
                    items.getOrNull(currentMediaItemIndex)?.playbackPosition ?: C.TIME_UNSET
                } else {
                    playbackPosition
                }

            player.setMediaItems(mediaItems, 0, startPosition)
            player.prepare()
            player.play()
            // 媒体已交给播放器：之后的 playWhenReady 变化都算用户 / 系统意图，恢复正常回存
            startupInProgress = false
            // 起播之后再补全整剧队列（用户反馈：队列面板只显示当前一集）
            fillQueueInBackground()
        }
    }

    /**
     * 播放页回到前台时恢复播放状态。
     *
     * 起播窗口内播放器的 `playWhenReady` 还没落地，直接写回会把自动起播覆盖成暂停（bug ②），因此这个窗口里什么都不做—— `initializePlayer` 随后会自己
     * `play()`。
     */
    fun restorePlayWhenReady() {
        if (startupInProgress) return
        /*
         * W68：后台播放（含音乐会话）期间，播放状态可能已被通知栏 / 锁屏 / 蓝牙改变；
         * 回前台先以播放器实际状态为准，避免把「后台已暂停」覆盖成自动续播。
         */
        if (
            appPreferences.getValue(appPreferences.playerBackgroundAudio) ||
                playerHolder.musicSessionActive
        ) {
            playWhenReady = player.playWhenReady
            return
        }
        player.playWhenReady = playWhenReady
    }

    /** 播放页离开前台时回存播放状态；起播窗口内的值不可信（还是播放器默认值），不回存（bug ②） */
    fun rememberPlayWhenReady() {
        if (startupInProgress) return
        playWhenReady = player.playWhenReady
    }

    /**
     * 后台把整剧队列补进播放器（ANR 修复版）。
     *
     * 旧实现虽然把「构建 PlayerItem」放在 IO，但 `player.addMediaItem` 的插入仍在主线程逐条执行； mpv 内核的 `addMediaItems`
     * 会同步调用 `mpvLib.command("loadfile", …)`，整季补片时主线程被连续 阻塞，真机复测出现过 `Waited 5000ms for MotionEvent`
     * 的 ANR。现在： ① 协程整体跑在 IO，插入时只切主线程做一次轻量时间线更新（mpv 命令已由 MPVPlayer 内部异步执行）； ② 每条插入之间让出一帧以上，输入 /
     * 渲染消息有机会执行； ③ 单次补片有数量上限，超大剧集不会一次把网络、内存与播放器时间线全部拉满。
     *
     * 播放器起播时只带着当前这一集，队列面板因此只有一条；这里先播当前集，再按「后面的集依次追加 → 前面的集倒序前插」逐集构建并插入，中途用户切集也不受影响——插入前按 mediaId
     * 去重。
     */
    private fun fillQueueInBackground() {
        queueFillJob?.cancel()
        queueFillJob =
            viewModelScope.launch(Dispatchers.IO) {
                val (currentIndex, queueSize) =
                    withContext(Dispatchers.Main) {
                        playlistManager.queueIndex to playlistManager.queueSize
                    }
                if (queueSize <= 1 || currentIndex < 0) return@launch

                // player 只能在主线程访问：先快照当前列表里的 mediaId，之后插入前用它去重（O(1)）
                val knownMediaIds =
                    withContext(Dispatchers.Main) {
                        (0 until player.mediaItemCount).mapTo(mutableSetOf()) {
                            player.getMediaItemAt(it).mediaId
                        }
                    }
                var added = 0
                var stoppedAtLimit = false

                for (index in (currentIndex + 1) until queueSize) {
                    // 用户手动整理过队列（排序 / 删除 / 清空）：不再把条目补回来
                    if (queueManuallyEdited) return@launch
                    if (added >= MAX_QUEUE_FILL_ITEMS) {
                        stoppedAtLimit = true
                        break
                    }
                    val item = playlistManager.buildPlayerItemAt(index) ?: continue
                    if (!isActive) return@launch
                    if (insertQueueItem(item, atFront = false, knownMediaIds)) added++
                    delay(QUEUE_FILL_STEP_DELAY_MS)
                }
                if (!stoppedAtLimit) {
                    for (index in (currentIndex - 1) downTo 0) {
                        if (queueManuallyEdited) return@launch
                        if (added >= MAX_QUEUE_FILL_ITEMS) {
                            stoppedAtLimit = true
                            break
                        }
                        val item = playlistManager.buildPlayerItemAt(index) ?: continue
                        if (!isActive) return@launch
                        if (insertQueueItem(item, atFront = true, knownMediaIds)) added++
                        delay(QUEUE_FILL_STEP_DELAY_MS)
                    }
                }

                val total = withContext(Dispatchers.Main) { player.mediaItemCount }
                if (stoppedAtLimit) {
                    Timber.w("播放队列补全达上限：本次新增 %d 项（播放器共 %d 项）", added, total)
                } else {
                    Timber.d("播放队列补全完成：本次新增 %d 项（播放器共 %d 项）", added, total)
                }
            }
    }

    /** 在主线程把一条队列项插进播放器；[knownMediaIds] 只在主线程读写，用作 O(1) 去重。 返回是否真正插入（重复项返回 false）。 */
    private suspend fun insertQueueItem(
        item: PlayerItem,
        atFront: Boolean,
        knownMediaIds: MutableSet<String>,
    ): Boolean =
        withContext(Dispatchers.Main) {
            val mediaId = item.itemId.toString()
            if (!knownMediaIds.add(mediaId)) return@withContext false
            // 显式给下标：不依赖 BasePlayer 对"无下标 addMediaItem"的封装（mpv 内核上它传过越界值）
            val index = if (atFront) 0 else player.mediaItemCount
            player.addMediaItem(index, item.toMediaItem())
            rememberQueueItem(item)
            true
        }

    private fun rememberQueueItem(item: PlayerItem) {
        if (items.none { it.itemId == item.itemId }) {
            items.add(item)
        }
    }

    /**
     * 接管一个已经在播放的会话（从通知 / 锁屏回到播放页，阶段 4）。
     *
     * 播放器实例、播放列表与进度都还在服务里跑，这里**不能**重新 `setMediaItems`（会跳回开头）。 只需要重新挂监听，再把 uiState
     * 里缺的标题补上；选集栏直接读播放器里的媒体项，不受影响。
     */
    fun attachToExistingSession() {
        player.addListener(this)
        applySavedSubtitlePreferences()
        // W27：接管会话（通知 / 锁屏回前台）时补上侧载字幕；自研管线还没建立才重建，避免覆盖本次会话的手动选轨
        player.currentMediaItem?.mediaId?.let { mediaId ->
            sideloads = runCatching { sideloadStore.list(mediaId) }.getOrDefault(emptyList())
            ensureMpvSideloadsInjected()
            if (
                playerBackend != PLAYER_BACKEND_MPV &&
                    subtitleController.overlayState.value.sources.isEmpty()
            ) {
                applySubtitlePipelineForMedia(
                    mediaId,
                    pipelineSourcesFor(mediaId, currentPlaybackItem()),
                )
            }
        }
        refreshUiStateFromPlayer()
        publishSubtitlePanelState()
    }

    /** 用播放器当前条目刷新 uiState（标题 / 条目 id），不改动任何播放状态 */
    private fun refreshUiStateFromPlayer() {
        val mediaItem = player.currentMediaItem ?: return
        val itemId = runCatching { UUID.fromString(mediaItem.mediaId) }.getOrNull()
        val extras = mediaItem.mediaMetadata.extras
        val season = extras?.getInt(PLAYER_EXTRA_SEASON_NUMBER, -1) ?: -1
        val episode = extras?.getInt(PLAYER_EXTRA_EPISODE_NUMBER, -1) ?: -1
        val name = mediaItem.mediaMetadata.title?.toString().orEmpty()
        val title = if (season >= 0 && episode >= 0) "S$season:E$episode - $name" else name
        @Suppress("DEPRECATION")
        val mediaInfo = extras?.getParcelable(PLAYER_EXTRA_MEDIA_INFO) as? PlayerMediaInfo
        _uiState.update {
            it.copy(
                currentItemTitle = title,
                currentItemId = itemId ?: it.currentItemId,
                currentMediaInfo = mediaInfo ?: it.currentMediaInfo,
            )
        }
    }

    private fun PlayerItem.toMediaItem(): MediaItem {
        val streamUrl = mediaSourceUri
        val mediaSubtitles = externalSubtitles.map { externalSubtitle ->
            MediaItem.SubtitleConfiguration.Builder(externalSubtitle.uri)
                .setLabel(
                    externalSubtitle.title.ifBlank { application.getString(R.string.external) }
                )
                .setMimeType(externalSubtitle.mimeType)
                .setLanguage(externalSubtitle.language)
                .build()
        }

        Timber.d("Stream url: $streamUrl")
        val mediaItem =
            MediaItem.Builder()
                .setMediaId(itemId.toString())
                .setUri(streamUrl)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(name)
                        // 缩略图随媒体项一起带走：队列列表显示缩略图、通知显示封面都读它
                        .setArtworkUri(thumbnailUri?.let { Uri.parse(it) })
                        // 队列面板需要按季分组：把季号/集号随媒体项一起带过去
                        .setExtras(
                            Bundle().apply {
                                putInt(PLAYER_EXTRA_SEASON_NUMBER, parentIndexNumber ?: -1)
                                putInt(PLAYER_EXTRA_EPISODE_NUMBER, indexNumber ?: -1)
                                // 信息面板（§1.8）：媒体源元数据随媒体项一起带走
                                mediaInfo?.let { putParcelable(PLAYER_EXTRA_MEDIA_INFO, it) }
                                // mpv 转码场景的 libass 兜底：字幕清单随条目走，容器无内嵌字幕时 sub-add
                                putParcelableArrayList(
                                    PLAYER_EXTRA_SUBTITLE_SOURCES,
                                    ArrayList(subtitleSources),
                                )
                            }
                        )
                        .build()
                )
                .setSubtitleConfigurations(mediaSubtitles)
                .build()

        return mediaItem
    }

    @OptIn(DelicateCoroutinesApi::class)
    private fun releasePlayer() {
        /*
         * W20（§1.11 退出播放）：退出 / 被系统杀掉前必须把最终位置写回服务端。
         * 旧实现先 `delay(200)` 再发：进程可能在延迟窗口里被回收，最后一次进度就丢了。
         * 现在同步取好快照后立刻发出（GlobalScope 不受 onCleared 取消影响，进程还活着就能发完）。
         */
        val activePlayer = playerHolder.existingPlayer
        val mediaId = activePlayer?.currentMediaItem?.mediaId
        val position = (activePlayer?.currentPosition ?: 0L).coerceAtLeast(0L)
        val duration = activePlayer?.duration ?: C.TIME_UNSET
        val itemId = mediaId?.let { raw -> runCatching { UUID.fromString(raw) }.getOrNull() }
        if (itemId != null && duration != C.TIME_UNSET) {
            markStopReported(itemId)
            GlobalScope.launch(Dispatchers.IO) {
                try {
                    Timber.d("Sending playback stop")
                    repository.postPlaybackStop(
                        itemId,
                        position.times(10000),
                        playedPercentage(position, duration),
                    )
                } catch (e: Exception) {
                    Timber.e(e)
                }
            }
        }

        trickplayLoader = null
        _uiState.update {
            it.copy(trickplayIntervalMs = 0, trickplayVersion = it.trickplayVersion + 1)
        }
        playWhenReady = false
        playbackPosition = 0L
        currentMediaItemIndex = 0
        player.removeListener(this)
        val backgroundAudioEnabled = appPreferences.getValue(appPreferences.playerBackgroundAudio)
        if (!shouldReleasePlayerOnExit(playerHolder.musicSessionActive, backgroundAudioEnabled)) {
            /*
             * 后台播放开启 / 音乐会话活跃：播放页关闭后实例继续由前台服务与通知栏控制，
             * 这里只把最后位置写回，不能释放实例。
             */
            savedStateHandle["position"] = player.currentPosition
        } else {
            playerHolder.release()
        }
    }

    fun updatePlaybackProgress() {
        val player = playerHolder.existingPlayer ?: return
        val mediaItem = player.currentMediaItem ?: return
        val itemId = runCatching { UUID.fromString(mediaItem.mediaId) }.getOrNull() ?: return
        val position = player.currentPosition.coerceAtLeast(0L)
        val duration = player.duration
        savedStateHandle["position"] = position
        recordProgressSnapshot(itemId, position, duration)
        viewModelScope.launch(Dispatchers.Main) {
            try {
                repository.postPlaybackProgress(itemId, position.times(10000), !player.isPlaying)
            } catch (e: Exception) {
                Timber.e(e)
            }
        }
        /*
         * W20（§1.11）：会话上报之外，把位置直接写进 UserData——
         * 服务端只在停止上报时落库，杀进程 / 崩溃时最后一段进度会丢。
         */
        viewModelScope.launch {
            playbackPositionWriter.writePosition(itemId, position.times(10000))
        }
    }

    /** 记录「已知的当前条目位置」，供切集 / 退出时给上一条补停止上报（W20） */
    private fun recordProgressSnapshot(itemId: UUID, positionMs: Long, durationMs: Long) {
        lastProgressItemId = itemId
        lastProgressPositionMs = positionMs
        lastProgressDurationMs = durationMs
    }

    private fun markStopReported(itemId: UUID) {
        lastStopReportedItemId = itemId
        lastStopReportedAtMs = SystemClock.elapsedRealtime()
    }

    /** 已播百分比（0–100）；时长未知时按 0 处理，交给服务端自己的判定 */
    private fun playedPercentage(positionMs: Long, durationMs: Long): Int {
        if (durationMs <= 0L || durationMs == C.TIME_UNSET) return 0
        return ((positionMs.toDouble() / durationMs.toDouble()) * 100).toInt().coerceIn(0, 100)
    }

    /**
     * W20（§1.11 切集）：换集 / 手动切条目后给**上一条**补一条停止上报。
     *
     * `onMediaItemTransition` 触发时播放器里已经是新条目，上一集的位置只能来自 [recordProgressSnapshot] 的快照；自然播完已经在结束分支报过
     * stop， 用 [lastStopReportedItemId] + 去重窗口避免重复。
     */
    private fun reportOutgoingItemStop() {
        val itemId = lastProgressItemId ?: return
        val currentId =
            playerHolder.existingPlayer?.currentMediaItem?.mediaId?.let { raw ->
                runCatching { UUID.fromString(raw) }.getOrNull()
            }
        if (itemId == currentId) return
        val now = SystemClock.elapsedRealtime()
        if (itemId == lastStopReportedItemId && now - lastStopReportedAtMs < STOP_REPORT_DEDUPE_MS)
            return
        val position = lastProgressPositionMs
        val duration = lastProgressDurationMs
        lastProgressItemId = null
        markStopReported(itemId)
        Timber.d("换集：为上一集补停止上报 itemId=%s position=%d", itemId, position)
        viewModelScope.launch {
            try {
                repository.postPlaybackStop(
                    itemId,
                    position.times(10000),
                    playedPercentage(position, duration),
                )
            } catch (e: Exception) {
                Timber.w(e, "切集：上一集停止上报失败")
            }
        }
    }

    fun updateCurrentSegment() {
        // 页内改过「跳过片头片尾」设置就现读一次（§1.9：不重开播放页也生效）
        refreshSegmentPreferences()
        // 没有片段数据、或两个开关都关：收起按钮直接返回（每秒一次的轮询不该刷日志 / 干活）
        if (currentMediaItemSegments.isEmpty() || (!segmentsSkipButton && !segmentsAutoSkip)) {
            if (_uiState.value.currentSegment != null) {
                _uiState.update { it.copy(currentSegment = null) }
            }
            return
        }
        viewModelScope.launch(Dispatchers.Main) {
            val milliSeconds = player.currentPosition

            // Get current segment, - 100 milliseconds to avoid showing button after segment ends
            val currentSegment = currentMediaItemSegments.find { segment ->
                milliSeconds in segment.startTicks..<(segment.endTicks - 100L)
            }

            if (currentSegment == null) {
                // Remove button if not pressed and there is no current segment
                if (_uiState.value.currentSegment != null) {
                    _uiState.update { it.copy(currentSegment = null) }
                }
                return@launch
            }

            Timber.tag("SegmentInfo").d("currentSegment: %s", currentSegment)

            if (
                segmentsAutoSkip &&
                    segmentsAutoSkipTypes.contains(currentSegment.type.toString()) &&
                    (segmentsAutoSkipMode == Constants.PlayerMediaSegmentsAutoSkip.ALWAYS ||
                        (segmentsAutoSkipMode == Constants.PlayerMediaSegmentsAutoSkip.PIP &&
                            isInPictureInPictureMode))
            ) {
                // Auto Skip segment
                skipSegment(currentSegment)
            } else if (segmentsSkipButtonTypes.contains(currentSegment.type.toString())) {
                // Skip Button segment
                _uiState.update {
                    it.copy(
                        currentSegment = currentSegment,
                        currentSkipButtonStringRes = getSkipButtonTextStringId(currentSegment),
                    )
                }
            } else {
                _uiState.update { it.copy(currentSegment = null) }
            }
        }
    }

    /**
     * 轨道信息就绪后按语言优先级自动选轨。
     *
     * 同一媒体可能多次回调（例如外挂字幕稍后才挂载），因此每次都重新计算； 但用户在当前媒体里手动选过轨时不再干预。
     */
    override fun onTracksChanged(tracks: Tracks) {
        if (player !is ExoPlayer) {
            /*
             * mpv：字幕轨（sid）与音轨（aid）都来自这个回调，刷新面板跟上。
             * W27：文件加载完成 / 注入完成后这里会再次回调，把还没注入的侧载字幕补上。
             */
            ensureMpvSideloadsInjected(tracks)
            publishSubtitlePanelState()
            publishAudioPanelState()
            return
        }
        val mediaId = player.currentMediaItem?.mediaId ?: return
        if (manualTrackSelectionMediaId != mediaId) {
            // 自动选轨 + 字幕路由：一次算完再设置，避免参数来回变化
            applyAutoSelectAndRoute(tracks)
        } else {
            // 用户手动选过轨：音轨保持现状，只同步字幕路由
            applySubtitleRoutingOnly()
        }
        publishAudioPanelState()
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        Timber.d("Playing MediaItem: ${mediaItem?.mediaId}")
        // W20（§1.11）：先把「上一条」的进度收尾（补 stop），再按新条目走后续流程
        reportOutgoingItemStop()
        // mpv 换集后重新对齐字幕开关（off → sid=no；auto / always → 按 slang 重选）
        syncMpvSubtitleMode()
        savedStateHandle["mediaItemIndex"] = player.currentMediaItemIndex
        viewModelScope.launch {
            try {
                val item = items.firstOrNull {
                    it.itemId.toString() == player.currentMediaItem?.mediaId
                }
                if (item == null) {
                    /*
                     * 播放页是从通知回到前台、或后台自动切集后新开的页面：items 还没建。
                     * 用播放器里的元数据兜底刷新标题，播放本身不受影响。
                     */
                    refreshUiStateFromPlayer()
                    // 字幕源清单跟着播放器里的媒体 id 走：能从 PlaylistManager 找回就恢复
                    player.currentMediaItem?.mediaId?.let { mediaId ->
                        val remembered = runCatching {
                            UUID.fromString(mediaId)
                        }
                            .getOrNull()
                            ?.let { playlistManager.getPlayerItem(it) }
                        if (remembered != null) {
                            applySubtitlePipelineForMedia(
                                mediaId,
                                pipelineSourcesFor(mediaId, remembered),
                            )
                        } else {
                            // 找不回原始条目也要把侧载记录挂上（mpv 直接 sub-add / Exo 至少能列出已存文件）
                            applySubtitlePipelineForMedia(
                                mediaId,
                                pipelineSourcesFor(mediaId, null),
                            )
                        }
                    }
                    return@launch
                }
                item.let { item ->
                    // 换集 / 换片：字幕源清单随条目更新并重新自动选字幕
                    applySubtitlePipelineForMedia(
                        item.itemId.toString(),
                        pipelineSourcesFor(item.itemId.toString(), item),
                    )
                    mpvSecondarySubtitleId = null
                    val itemTitle =
                        if (item.parentIndexNumber != null && item.indexNumber != null) {
                            if (item.indexNumberEnd == null) {
                                "S${item.parentIndexNumber}:E${item.indexNumber} - ${item.name}"
                            } else {
                                "S${item.parentIndexNumber}:E${item.indexNumber}-${item.indexNumberEnd} - ${item.name}"
                            }
                        } else {
                            item.name
                        }
                    _uiState.update {
                        it.copy(
                            currentItemTitle = itemTitle,
                            currentItemId = item.itemId,
                            currentSegment = null,
                            currentChapters = item.chapters,
                            fileLoaded = false,
                            // 信息面板的媒体源元数据（§1.8）：跟着当前条目一起刷新
                            currentMediaInfo = item.mediaInfo,
                        )
                    }

                    repository.postPlaybackStart(item.itemId)

                    if (segmentsSkipButton || segmentsAutoSkip) {
                        getSegments(item.itemId)
                    }

                    // W20（§1.11）：Trickplay 改为按需加载——这里只准备加载器，不拉任何图
                    prepareTrickplay(item)

                    playlistManager.setCurrentMediaItemIndex(item.itemId)

                    val previousItem = playlistManager.getPreviousPlayerItem()
                    if (previousItem != null) {
                        items.add(player.currentMediaItemIndex, previousItem)
                        player.addMediaItem(
                            player.currentMediaItemIndex,
                            previousItem.toMediaItem(),
                        )
                    }

                    val nextItem = playlistManager.getNextPlayerItem()
                    if (nextItem != null) {
                        items.add(player.currentMediaItemIndex + 1, nextItem)
                        player.addMediaItem(
                            player.currentMediaItemIndex + 1,
                            nextItem.toMediaItem(),
                        )
                    }

                    Timber.tag("PlayerItems").d(items.map { it.indexNumber }.toString())
                }
            } catch (e: Exception) {
                Timber.e(e)
            }
        }
    }

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        if (playWhenReady || startupInProgress) return
        /*
         * W20（§1.11）：暂停（通知栏 / 耳机拔出 / 音频焦点丢失 / 手动）立刻落一次进度，
         * 不等 5 秒轮询，也不等退出播放页——「被杀」前最后的状态因此总是最新的。
         */
        if (reason != Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) {
            updatePlaybackProgress()
            return
        }
        // Report playback stopped for current item and transition to the next one
        /*
         * 注意：这里**不能**再要求 `playbackState == STATE_READY`。
         * 播放器设了 pauseAtEndOfMediaItems，一集播完会在「渲染结束的那一刻」先把 playWhenReady 置 false，
         * 那个瞬间状态可能还是 STATE_BUFFERING（比如手动拖到片尾后），一旦把这种情形挡掉，
         * 这一集就会永远卡在片尾。
         */
        Timber.d(
            "end of media item: state=${player.playbackState} repeat=${player.repeatMode} " +
                "shuffle=${player.shuffleModeEnabled} index=${player.currentMediaItemIndex}/" +
                "${player.mediaItemCount} hasNext=${player.hasNextMediaItem()}"
        )
        viewModelScope.launch {
            val mediaId = player.currentMediaItem?.mediaId
            val position = player.currentPosition
            val duration = player.duration
            val itemId = mediaId?.let { raw -> runCatching { UUID.fromString(raw) }.getOrNull() }
            if (itemId != null) {
                markStopReported(itemId)
                recordProgressSnapshot(itemId, position, duration)
                try {
                    repository.postPlaybackStop(
                        itemId,
                        position.times(10000),
                        playedPercentage(position, duration),
                    )
                } catch (e: Exception) {
                    Timber.e(e)
                }
            }
            advanceAfterItemEnd()
        }
    }

    /**
     * 一集播完后的走向，由循环模式（控制层底栏的循环面板）决定：
     * - 单集循环：回到本集开头继续播；
     * - 顺序播放：往下一集走，走到队列末尾就停在片尾（由播放器发 STATE_ENDED 收尾）；
     * - 列表循环：往下一集走，队列末尾会绕回第一集；
     * - 随机播放：往「打乱后的下一集」走，队列末尾同样绕回。
     *
     * 播放器本身设了 `pauseAtEndOfMediaItems`，一集结束会先停住，由这里显式决定去向， 所以列表循环与随机也不会出现「跳两集」。
     *
     * W27 起「自动下一集」开关（设置 → 播放 / `pref_player_auto_next_episode`）参与判定：
     * 关闭时当前一集播完停在结束帧，不自动跳下一集（旧「播完暂停」语义）。
     */
    private fun advanceAfterItemEnd() {
        val from = player.currentMediaItemIndex
        val repeatMode = player.repeatMode
        val autoNext = appPreferences.getValue(PlayerExtraPreferences.autoNextEpisode)
        when (
            PlayerEndBehavior.itemEndAction(
                repeatMode = repeatMode,
                hasNextMediaItem = player.hasNextMediaItem(),
                autoNextEpisode = autoNext,
            )
        ) {
            PlayerItemEndAction.LOOP_CURRENT -> player.seekTo(from, 0L)
            PlayerItemEndAction.PLAY_NEXT -> {
                // hasNextMediaItem() 已经把列表循环与随机算进去了：循环/随机在队列末尾会绕回第一集
                player.seekToNextMediaItem()
            }
            PlayerItemEndAction.STAY_AT_END -> {
                /*
                 * 两种「停留」要分开：
                 * ①「自动下一集」关闭 = 播完暂停（§1.7）：停在当前结束帧，播放页保持不动；
                 * ② 自动下一集开着、但队列没有下一集 = 队列末尾：按「停在结束帧」决定保持还是收尾
                 *    （旧行为 = 关闭播放页；STATE_ENDED 分支仍作为兜底）。
                 */
                if (!autoNext) {
                    Timber.d(
                        "stay at end frame: index=$from repeat=$repeatMode autoNext=false (播完停在结束帧)"
                    )
                    return
                }
                when (
                    PlayerEndBehavior.queueEndAction(
                        appPreferences.getValue(PlayerExtraPreferences.stayAtEndOfFrame)
                    )
                ) {
                    PlayerQueueEndAction.STAY_AT_END ->
                        Timber.d("queue end: stay at end frame (停在结束帧)")
                    PlayerQueueEndAction.CLOSE_PLAYER -> {
                        Timber.d("queue end: close player (队列播完)")
                        eventsChannel.trySend(PlayerEvents.NavigateBack)
                    }
                }
                return
            }
        }
        Timber.d(
            "advance after item end: from=$from repeat=$repeatMode " +
                "shuffle=${player.shuffleModeEnabled} -> index=${player.currentMediaItemIndex}"
        )
        player.play()
    }

    /** 队列走到尽头时的兜底：单集循环回到本集开头，列表循环/随机回第一集 */
    private fun restartAtQueueEnd() {
        val index =
            if (player.repeatMode == Player.REPEAT_MODE_ONE) {
                player.currentMediaItemIndex
            } else {
                0
            }
        player.seekTo(index, 0L)
        player.play()
    }

    /**
     * W27 播放结束行为：把旧的「播完暂停」迁移成「自动下一集 = 关」。
     *
     * 只在旧键为 true 且新键从未写过时迁一次，迁完复位旧键；不会改动用户既有的其他偏好。
     */
    private fun migrateEndBehaviorPreferences() {
        val prefs = appPreferences.sharedPreferences
        val autoNextKey = PlayerExtraPreferences.autoNextEpisode.backendName
        val legacyKey = PlayerExtraPreferences.pauseAfterCurrentItem.backendName
        if (!prefs.contains(autoNextKey) && prefs.getBoolean(legacyKey, false)) {
            prefs.edit().putBoolean(autoNextKey, false).putBoolean(legacyKey, false).apply()
            Timber.d("W27 迁移：播完暂停 → 关闭自动下一集")
        }
    }

    override fun onPlaybackStateChanged(state: Int) {
        var stateString = "UNKNOWN_STATE             -"
        when (state) {
            ExoPlayer.STATE_IDLE -> {
                stateString = "ExoPlayer.STATE_IDLE      -"
            }
            ExoPlayer.STATE_BUFFERING -> {
                stateString = "ExoPlayer.STATE_BUFFERING -"
            }
            ExoPlayer.STATE_READY -> {
                stateString = "ExoPlayer.STATE_READY     -"
                // 能进 READY 就说明上一次失败已经恢复，顺手收起错误卡片
                _uiState.update { it.copy(fileLoaded = true, playerError = null) }
            }
            ExoPlayer.STATE_ENDED -> {
                stateString = "ExoPlayer.STATE_ENDED     -"
                /*
                 * 顺序播放走到最后一集就收尾（关闭播放页，与既有行为一致）；
                 * 列表循环 / 随机播放则绕回队列开头继续，单集循环回到本集开头，
                 * 这是「片尾那一帧没能触发到下一集」时的兜底。
                 *
                 * W27：顺序播放 + 「停在结束帧」开启时留在最后一帧，不退出播放页。
                 */
                if (player.repeatMode == Player.REPEAT_MODE_OFF) {
                    when (
                        PlayerEndBehavior.queueEndAction(
                            appPreferences.getValue(PlayerExtraPreferences.stayAtEndOfFrame)
                        )
                    ) {
                        PlayerQueueEndAction.CLOSE_PLAYER ->
                            eventsChannel.trySend(PlayerEvents.NavigateBack)
                        PlayerQueueEndAction.STAY_AT_END ->
                            Timber.d("queue end: stay at end frame (停在结束帧)")
                    }
                } else {
                    restartAtQueueEnd()
                }
            }
        }
        Timber.d("Changed player state to $stateString")
    }

    /**
     * 播放失败：记下错误让控制层显示错误卡片。
     *
     * W18：mpv 也走这里了——`MPVPlayer` 在 `MPV_EVENT_END_FILE` 且 `eof-reached=false`（非正常播完）时上报
     * [PlaybackException]，于是「手动选 mpv 后播放失败」同样能进回退链 / 错误卡片（此前 mpv 的错误静默丢失）。
     */
    override fun onPlayerError(error: PlaybackException) {
        Timber.e(error, "Player error on backend=$playerBackend: ${error.errorCodeName}")
        /*
         * W73（#8）：网络 / IO 类错误先原地处理——换内核救不了网络，而回退重启（viewModelStore.clear + recreate）在
         * 网络未恢复时会初始化失败，用户看到的就是「播放中偶发退回详情页」。这里重试当前内核 / 带位置重开转码会话。
         */
        if (isNetworkOrSourceError(error)) {
            if (handleTransientNetworkError(error)) return
        } else {
            /*
             * W17：回退链还有下一档时**不显示错误卡片**（每一步真实生效，只有全部失败才提示错误）。
             * handleCodecFallback 接管本次错误（返回 true）时，错误卡片保持隐藏，由回退动作自己续播。
             */
            if (handleCodecFallback(error)) return
        }
        _uiState.update {
            it.copy(
                playerError =
                    PlayerErrorInfo(
                        message =
                            error.message?.takeIf { message -> message.isNotBlank() }
                                ?: error.errorCodeName,
                        codeName = error.errorCodeName,
                        backend = playerBackend,
                    )
            )
        }
    }

    /**
     * W73（#8）：网络 / IO 类播放错误的原地兜底。
     *
     * 第一次错误原地 `prepare` 重试当前内核；同一媒体在 [NETWORK_RETRY_WINDOW_MS] 内再次失败且当前是转码流 → 带当前位置重开转码会话
     * （服务器换一路新分片）；超过 [NETWORK_RETRY_MAX] 次才交给错误卡片。全程**不切内核、不重启 Activity**。
     *
     * @return true = 已接管（不要显示错误卡片、不要走解码回退链）
     */
    private fun handleTransientNetworkError(error: PlaybackException): Boolean {
        if (!isNetworkOrSourceError(error)) return false
        val mediaId = player.currentMediaItem?.mediaId ?: return false
        val nowMs = SystemClock.elapsedRealtime()
        if (
            mediaId != lastNetworkErrorMediaId ||
                nowMs - lastNetworkErrorAtMs > NETWORK_RETRY_WINDOW_MS
        ) {
            lastNetworkErrorMediaId = mediaId
            networkRetryAttempts = 0
        }
        // 同一次故障可能连发多条错误（mpv 尤其明显）：重试间隔内的重复上报直接吞掉，不叠加重试次数
        if (
            networkRetryAttempts > 0 && nowMs - lastNetworkErrorAtMs < NETWORK_RETRY_MIN_INTERVAL_MS
        ) {
            return true
        }
        lastNetworkErrorAtMs = nowMs
        if (networkRetryAttempts >= NETWORK_RETRY_MAX) {
            Timber.w(
                "网络错误原地重试已达上限（media=%s attempts=%d），交给错误卡片",
                mediaId,
                networkRetryAttempts,
            )
            return false
        }
        networkRetryAttempts++
        val position = currentResumePositionMs()
        val item = currentPlaybackItem()
        val isTranscode = item != null && isTranscodeStreamUri(item.mediaSourceUri)
        val restartTranscodeSession =
            isTranscode && playerBackend == PLAYER_BACKEND_EXOPLAYER && networkRetryAttempts > 1
        if (restartTranscodeSession) {
            Timber.w(
                "网络错误（%s）第 %d 次：转码流带位置重开会话 target=%d",
                error.errorCodeName,
                networkRetryAttempts,
                position,
            )
            scheduleTranscodeRestart(position, "network-retry")
        } else {
            Timber.w(
                "网络错误（%s）第 %d 次：原地重试当前内核 position=%d",
                error.errorCodeName,
                networkRetryAttempts,
                position,
            )
            retryPlayback()
        }
        return true
    }

    /**
     * 网络 / IO / 源不可用类错误：换内核与回退链都救不了，只能原地重试或重开会话。
     *
     * `ERROR_CODE_UNSPECIFIED` 一并纳入：mpv 上报的错误没有细分码（网络中断 / 打开失败都走它）， 对它切内核 /
     * 重建页面只会重演 #8（播放中退回详情页）；原地重试最多两次后交错误卡片更安全。
     */
    private fun isNetworkOrSourceError(error: PlaybackException): Boolean =
        when (error.errorCode) {
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
            PlaybackException.ERROR_CODE_UNSPECIFIED -> true
            else -> false
        }

    /**
     * 解码能力不足时的静默回退（W16 用户拍板的链路）：**本地硬解 → 服务器解码/转码 → 本地软解**。
     *
     * 场景：ExoPlayer 硬解不了的片源（10-bit H.264 等）会报 `NO_EXCEEDS_CAPABILITIES`。此时： ① 还没降级 + 码率是「自动」→
     * 请求服务器转码（换一路 h264 流重发），重启播放页续播； ② 已经在服务器转码档 / 用户选了具体 Mbps / 原始画质 → 落到本地软解（mpv
     * hwdec=no，最耗电放最后）。
     *
     * 不弹提示（用户在观影，提示属于打扰）。档位落盘（[AppPreferences.playerDecodeFallbackStage]）是为了
     * 「重启播放页续播」不丢档位，同时天然防死循环：升到软解档后再报错也不再推进。换条目 / 用户显式改 码率、内核、解码策略时档位清 0。
     *
     * W17 修复（用户实测「Exo 硬解失败后没有重新切换 mpv 软解」）：① 第 3 档不再提前写 `playerBackend` 偏好——回退动作由 Activity 显式
     * `setBackend(mpv)` 完成，避免「偏好已 mpv、实例仍被读成 Exo」时 toggle 语义把内核切回 ExoPlayer；②
     * 去掉「同一媒体只降级一次」的拦截，改由档位状态机防死循环， 否则第 2 档失败会卡在 Exo 不落第 3 档；③ 只有第 3 档（本地软解）也失败才把错误交给调用方显示。
     *
     * W18 修复（用户实测「解码面板手动切 ExoPlayer 后失败不回退」）： ① 判定抽到 [PlayerDecodeFallback.stageAfterFailure]
     * 纯函数并补单测——**内核是手动还是自动不影响链路**， ExoPlayer 与 mpv 的失败都走同一条「本地硬解 → 服务器解码/转码 → 本地软解」； ② 手动切内核（解码面板 /
     * 错误卡片）统一由 Activity 清空回退档位，链路从第 1 档重新走； ③ 事件通道改为带缓冲，`trySend` 不再静默丢事件（丢事件会造成「既不回退也不报错」的假死）。
     *
     * @return true = 本次错误已被回退链接管（不要显示错误卡片）；false = 链路用尽或不该回退。
     */
    private fun handleCodecFallback(error: PlaybackException): Boolean {
        val backend = playerBackend
        val mediaId = player.currentMediaItem?.mediaId ?: return false
        // W19：「失败自动回退」开关关闭 = 强制使用所选内核，失败只提示错误（不切换、不重启）
        val autoFallback = appPreferences.getValue(appPreferences.playerAutoFallback)
        val currentStage =
            PlayerDecodeFallback.normalize(
                appPreferences.getValue(appPreferences.playerDecodeFallbackStage)
            )
        /*
         * W18：同一次失败内核可能连续上报多条（mpv 一次打开失败会连发 2–3 条 END_FILE；
         * 不去重的话「第 1 档失败」会被处理两次，档位直接从 0 跳到 2，服务器转码那一档被跳过）。
         */
        val nowMs = SystemClock.elapsedRealtime()
        if (
            PlayerDecodeFallback.isDuplicateFailure(
                lastKey = lastFailureHandledKey,
                lastHandledAtMs = lastFailureHandledAtMs,
                backend = backend,
                stage = currentStage,
                nowMs = nowMs,
            )
        ) {
            Timber.d(
                "忽略重复的解码失败上报（%s，%dms 内已接管）",
                PlayerDecodeFallback.failureKey(backend, currentStage),
                nowMs - lastFailureHandledAtMs,
            )
            return true
        }
        // 链路判定（W18 纯函数）：下一档 / null = 链路已用尽
        val candidateStage =
            PlayerDecodeFallback.stageAfterFailure(
                stage = currentStage,
                backend = backend,
                bitratePreference = appPreferences.getValue(appPreferences.playerStreamingBitrate),
                codecCapabilityError = isCodecCapabilityError(error),
                networkError = isNetworkOrSourceError(error),
            )
        /*
         * W19 循环保护：同一媒体 + 同一目标档位的重启次数超过上限即判定循环。
         * 正常链路每档只会重启一次，触顶说明档位没能生效（例如被其它路径清零），直接报错比无限重启好。
         */
        val restartGuard =
            PlayerDecodeFallback.parseGuard(
                appPreferences.getValue(appPreferences.playerDecodeFallbackGuard)
            )
        val nextGuard = candidateStage?.let {
            PlayerDecodeFallback.recordRestart(restartGuard, mediaId, it)
        }
        val nextStage =
            PlayerDecodeFallback.fallbackDecision(
                autoFallbackEnabled = autoFallback,
                candidateStage = candidateStage,
                restartGuardExceeded = nextGuard?.exceeded() == true,
            )
        if (nextStage == null) {
            when {
                !autoFallback -> Timber.i("自动回退已关闭，保持所选内核（backend=%s），交给错误卡片", backend)
                nextGuard?.exceeded() == true ->
                    Timber.w(
                        "回退重启次数已达上限（media=%s target_stage=%d attempts=%d），判定为循环，交给错误卡片",
                        mediaId,
                        nextGuard.targetStage,
                        nextGuard.attempts - 1,
                    )
            }
        }
        // null = 链路已用尽（第 3 档也失败）：这一次才是「全部失败」，交给错误卡片
        if (nextStage == null || nextStage == currentStage) return false
        lastFailureHandledKey = PlayerDecodeFallback.failureKey(backend, currentStage)
        lastFailureHandledAtMs = nowMs
        nextGuard?.let {
            appPreferences.setValue(
                appPreferences.playerDecodeFallbackGuard,
                PlayerDecodeFallback.formatGuard(it),
            )
            Timber.d(
                "回退重启计数：media=%s target_stage=%d attempts=%d/%d",
                it.mediaId,
                it.targetStage,
                it.attempts,
                PlayerDecodeFallback.MAX_FALLBACK_RESTARTS_PER_STAGE,
            )
        }
        appPreferences.setValue(appPreferences.playerDecodeFallbackStage, nextStage)
        appPreferences.setValue(appPreferences.playerDecodeFallbackMediaId, mediaId)

        when (nextStage) {
            PlayerDecodeFallback.STAGE_SERVER_TRANSCODE -> {
                Timber.i(
                    "解码能力不足（backend=%s，%s），先请求服务器解码/转码重试（优先级：本地硬解 → 服务器转码 → 本地软解）",
                    backend,
                    error.errorCodeName,
                )
                eventsChannel.trySend(PlayerEvents.RestartWithServerTranscode)
            }
            else -> {
                /*
                 * 本地软解：mpv + hwdec=no（PlayerHolder 按回退档位强制软解）。
                 * 这里**只写档位**，内核由 Activity 收到事件后显式 setBackend(mpv) + 重启播放页；
                 * 提前写偏好会让 PlayerHolder 在原地把实例重建为 mpv，随后 toggle 又把它切回 Exo（W17 根因）。
                 */
                Timber.i(
                    "解码能力不足（backend=%s，%s），服务器转码不可用/已用尽，降级到本地软解（mpv hwdec=no）",
                    backend,
                    error.errorCodeName,
                )
                eventsChannel.trySend(PlayerEvents.FallbackToSoftware)
            }
        }
        return true
    }

    /** 只有"内核解不了这个格式"类错误才值得换内核；网络、DRM、容器损坏等换内核也没用 */
    private fun isCodecCapabilityError(error: PlaybackException): Boolean =
        when (error.errorCode) {
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> true
            else -> false
        }

    /**
     * W19：播放会话切换时整理解码回退状态（必须在拉取 PlaybackInfo 之前调用）。
     *
     * 回退 / 手动切内核的 Activity 重启会复用同一个会话 id → 档位与重启守卫都保留； 新开播放页（详情页 / 通知另起播放）使用新会话 id → 清空档位与守卫，链路从头走。
     * 旧实现按 Intent 条目 id 比对，季 / 剧集入口与队列换集时每次回退重启都会清零档位 → 死循环。
     */
    private fun prepareDecodeFallback(playbackSessionId: String) {
        val storedSession = appPreferences.getValue(appPreferences.playerDecodeFallbackSession)
        if (storedSession == playbackSessionId) return
        if (storedSession.isNotBlank()) {
            Timber.d("播放会话切换（%s → %s），清空解码回退档位", storedSession, playbackSessionId)
        }
        appPreferences.setValue(appPreferences.playerDecodeFallbackSession, playbackSessionId)
        clearDecodeFallback()
    }

    /**
     * 清空解码回退档位（回 0 = 本地硬解）与重启守卫。
     *
     * 用户显式改码率 / 内核 / 解码策略（或关掉自动回退开关）时由 Activity 调用：用户的显式选择优先， 回退链重新从头走。
     */
    fun clearDecodeFallback() {
        if (
            appPreferences.getValue(appPreferences.playerDecodeFallbackStage) != 0 ||
                appPreferences.getValue(appPreferences.playerDecodeFallbackGuard).isNotBlank()
        ) {
            Timber.d("清空解码回退档位（回到本地硬解）")
        }
        appPreferences.setValue(
            appPreferences.playerDecodeFallbackStage,
            PlayerDecodeFallback.STAGE_NONE,
        )
        appPreferences.setValue(appPreferences.playerDecodeFallbackMediaId, "")
        appPreferences.setValue(appPreferences.playerDecodeFallbackGuard, "")
    }

    /**
     * W19：当前正在播放条目的条目类型（`BaseItemKind.serialName`），用于启动/重启时把播放页恢复到这一条。
     *
     * 季 / 剧集入口与队列换集时，Intent 里的原始条目和实际播放条目不是同一条；回退重启必须带上 实际条目（见
     * PlayerActivity.restartPlaybackFromPosition），否则会跳回第一集。找不到时返回 null， 调用方保持原 Intent 条目。
     */
    fun currentPlaybackItemKind(): String? {
        val itemId =
            player.currentMediaItem?.mediaId?.let { raw ->
                runCatching { UUID.fromString(raw) }.getOrNull()
            } ?: return null
        return when (playlistManager.findItem(itemId)) {
            is FindroidEpisode -> BaseItemKind.EPISODE.serialName
            is FindroidMovie -> BaseItemKind.MOVIE.serialName
            else -> null
        }
    }

    override fun onCleared() {
        super.onCleared()
        Timber.d("Clearing Player ViewModel")
        releasePlayer()
    }

    // ---------- 字幕面板：延迟 / 双语 / 外观（§1.1） ----------

    /**
     * 选主字幕；null = 关闭。
     *
     * 两个内核共用一套面板：ExoPlayer 走自研渲染管线（文本字幕）或内核渲染（图形字幕）， mpv 直接切它的 `sid`。
     */
    fun selectSubtitlePrimary(id: Int?) {
        manualTrackSelectionMediaId = player.currentMediaItem?.mediaId
        if (playerBackend == PLAYER_BACKEND_MPV) {
            val mpv = player as? MPVPlayer ?: return
            if (mpvSecondarySubtitleId == id) {
                mpvSecondarySubtitleId = null
                mpv.setSecondarySubtitle(null)
            }
            mpv.selectSubtitleTrack(id)
            publishSubtitlePanelState()
            return
        }
        subtitleController.selectPrimary(id)
        applySubtitleRoutingOnly()
    }

    /** 选次字幕（双语）；null = 关闭。次字幕不允许与主字幕同轨。 */
    fun selectSubtitleSecondary(id: Int?) {
        if (playerBackend == PLAYER_BACKEND_MPV) {
            val mpv = player as? MPVPlayer ?: return
            mpvSecondarySubtitleId = id
            mpv.setSecondarySubtitle(id?.toString())
            publishSubtitlePanelState()
            return
        }
        subtitleController.selectSecondary(id)
    }

    /**
     * W27 侧载字幕导入：由 Activity 的系统文件选择器回调。
     *
     * 文件复制到 App 私有目录（按当前播放条目保存，不写服务器）；导入成功后立即选中： Exo 走自研 libass 管线，mpv 走 `sub-add`（mpv 内置 libass）。
     *
     * @param onResult (成功?, 展示名或错误说明)
     */
    fun importSideloadedSubtitle(
        displayName: String,
        bytes: ByteArray,
        onResult: (Boolean, String) -> Unit,
    ) {
        val mediaId = currentPlaybackMediaId()
        if (mediaId == null) {
            onResult(false, application.getString(R.string.player_subtitle_sideload_no_media))
            return
        }
        viewModelScope.launch {
            val record =
                withContext(Dispatchers.IO) {
                    runCatching { sideloadStore.import(mediaId, displayName, bytes) }.getOrNull()
                }
            if (record == null) {
                onResult(
                    false,
                    application.getString(R.string.player_subtitle_sideload_unsupported),
                )
                return@launch
            }
            sideloads = runCatching { sideloadStore.list(mediaId) }.getOrDefault(emptyList())
            Timber.d("侧载字幕导入：%s（语言=%s）", record.displayName, record.language)
            if (playerBackend == PLAYER_BACKEND_MPV) {
                (player as? MPVPlayer)?.addSubtitleFile(record.path, select = true)
            } else {
                val item = currentPlaybackItem()
                applySubtitlePipelineForMedia(mediaId, pipelineSourcesFor(mediaId, item))
                val index =
                    SideloadedSubtitleStore.INDEX_BASE +
                        sideloads.indexOfFirst { it.path == record.path }
                if (index >= SideloadedSubtitleStore.INDEX_BASE) {
                    subtitleController.selectPrimary(index)
                }
                applySubtitleRoutingOnly()
            }
            publishSubtitlePanelState()
            onResult(true, record.displayName)
        }
    }

    /**
     * W27 侧载字幕移除：删除本机文件并重建字幕管线。
     *
     * Exo 的 [optionId] 是侧载源序号（≥ [SideloadedSubtitleStore.INDEX_BASE]）； mpv 的 [optionId] 是 track
     * id，按「路径 → track id」反查记录。
     */
    fun removeSideloadedSubtitle(optionId: Int) {
        val mediaId = currentPlaybackMediaId() ?: return
        val record = resolveSideloadedRecord(optionId) ?: return
        viewModelScope.launch {
            withContext(Dispatchers.IO) { runCatching { sideloadStore.remove(record) } }
            sideloads = runCatching { sideloadStore.list(mediaId) }.getOrDefault(emptyList())
            Timber.d("侧载字幕已移除：%s", record.displayName)
            if (playerBackend == PLAYER_BACKEND_MPV) {
                (player as? MPVPlayer)?.removeSubtitleFile(record.path)
            } else {
                val item = currentPlaybackItem()
                applySubtitlePipelineForMedia(mediaId, pipelineSourcesFor(mediaId, item))
                applySubtitleRoutingOnly()
            }
            publishSubtitlePanelState()
        }
    }

    private fun resolveSideloadedRecord(optionId: Int): SideloadedSubtitle? =
        if (playerBackend == PLAYER_BACKEND_MPV) {
            val mpv = player as? MPVPlayer
            sideloads.firstOrNull { record -> mpv?.subtitleTrackIdForPath(record.path) == optionId }
        } else {
            if (optionId < SideloadedSubtitleStore.INDEX_BASE) {
                null
            } else {
                sideloads.getOrNull(optionId - SideloadedSubtitleStore.INDEX_BASE)
            }
        }

    /**
     * W27 PlayerDebugOverlay：读取一份实时快照。
     *
     * ExoPlayer 的 `currentPosition` / `bufferedPosition` 等 API 有线程检查，必须在主线程读； mpv 的属性查询会走
     * native、要求在主线程之外，只有它放 IO。
     */
    suspend fun readDebugStats(): PlayerDebugStats {
        val current = player
        val stats =
            if (current is MPVPlayer) {
                withContext(Dispatchers.IO) { readPlayerDebugStats(current) }
            } else {
                readPlayerDebugStats(current)
            }
        return stats.copy(decodeStage = currentDecodeStage().name)
    }

    /** 字幕延迟 ±0.1s；两个内核都立即生效，并写回偏好 */
    fun adjustSubtitleDelay(deltaMs: Long) {
        val current = appPreferences.getValue(appPreferences.playerSubtitleDelayMs)
        val next =
            (current + deltaMs).coerceIn(
                -PlayerSubtitleController.DELAY_LIMIT_MS,
                PlayerSubtitleController.DELAY_LIMIT_MS,
            )
        subtitleController.setDelay(next)
        if (playerBackend == PLAYER_BACKEND_MPV) {
            (player as? MPVPlayer)?.setSubtitleDelay(next)
        }
        publishSubtitlePanelState()
    }

    /** 字幕延迟归零 */
    fun resetSubtitleDelay() {
        subtitleController.setDelay(0L)
        if (playerBackend == PLAYER_BACKEND_MPV) {
            (player as? MPVPlayer)?.setSubtitleDelay(0L)
        }
        publishSubtitlePanelState()
    }

    /** 字幕外观：写偏好 + 应用到当前内核（自绘层直接读状态，原生层由 Activity 更新） */
    fun updateSubtitleStyle(style: SubtitleStyle) {
        subtitleController.updateStyle(style)
        if (playerBackend == PLAYER_BACKEND_MPV) {
            (player as? MPVPlayer)?.applySubtitleStyle(style)
        } else {
            eventsChannel.trySend(PlayerEvents.SubtitleStyleChanged(style))
        }
        publishSubtitlePanelState()
    }

    /** 起播 / 接管会话时把已保存的延迟与外观应用到当前内核 */
    private fun applySavedSubtitlePreferences() {
        val delayMs = appPreferences.getValue(appPreferences.playerSubtitleDelayMs)
        if (playerBackend == PLAYER_BACKEND_MPV) {
            (player as? MPVPlayer)?.let { mpv ->
                mpv.setSubtitleDelay(delayMs)
                mpv.applySubtitleStyle(readSubtitleStyle())
            }
        } else {
            eventsChannel.trySend(PlayerEvents.SubtitleStyleChanged(readSubtitleStyle()))
        }
    }

    private fun readSubtitleStyle(): SubtitleStyle =
        SubtitleStyle(
            sizeIndex = appPreferences.getValue(appPreferences.playerSubtitleStyleSize),
            colorIndex = appPreferences.getValue(appPreferences.playerSubtitleStyleColor),
            backgroundIndex = appPreferences.getValue(appPreferences.playerSubtitleStyleBackground),
            edgeIndex = appPreferences.getValue(appPreferences.playerSubtitleStyleEdge),
            positionIndex = appPreferences.getValue(appPreferences.playerSubtitleStylePosition),
        )

    /**
     * 自研字幕只在 ExoPlayer 内核下接管。
     *
     * mpv 自带字幕渲染（还能做 secondary-sid 双语），如果这边也加载一份， 画面上会出现两条一模一样的字幕（双显）。
     */
    private fun subtitleSourcesForBackend(item: PlayerItem): List<PlayerSubtitleSource> =
        playerSubtitleSourcesForBackend(
            playerBackend,
            item,
            // W27 侧载字幕：Exo 走自研管线（libass 渲染），mpv 侧返回空清单、由 native sub-add 接管
            sideloadSourcesFor(item.itemId.toString()),
        )

    /** 侧载字幕 → 自研管线的字幕源（序号从 [SideloadedSubtitleStore.INDEX_BASE] 起，避开 Jellyfin 的 0..n） */
    private fun sideloadSourcesFor(mediaId: String): List<PlayerSubtitleSource> = runCatching {
        sideloadStore.list(mediaId)
    }
        .getOrDefault(emptyList())
        .mapIndexed { position, record ->
            PlayerSubtitleSource(
                index = SideloadedSubtitleStore.INDEX_BASE + position,
                title = record.displayName,
                language = record.language,
                uri = "file://${record.path}",
                codec = record.codec,
                isExternal = true,
            )
        }

    /**
     * 换集 / 换片 / 导入 / 移除后重建字幕管线：重载当前条目的侧载记录，自研管线整体替换源清单。
     *
     * mpv 侧不经过自研管线，注入交给 [ensureMpvSideloadsInjected]。
     */
    private fun applySubtitlePipelineForMedia(
        mediaId: String,
        sources: List<PlayerSubtitleSource>,
    ) {
        sideloads = runCatching { sideloadStore.list(mediaId) }.getOrDefault(emptyList())
        subtitleController.reset(mediaId, sources)
        /*
         * W27：用户为这个条目导入过字幕，默认沿用第一条侧载（导入本身就是显式选择）；
         * 仍可在面板里切回服务器字幕或移除。mpv 侧由 sid / slang 决定，不需要这里选。
         */
        if (playerBackend != PLAYER_BACKEND_MPV && sideloads.isNotEmpty()) {
            subtitleController.selectPrimary(SideloadedSubtitleStore.INDEX_BASE)
        }
        ensureMpvSideloadsInjected()
    }

    /** 当前条目要给（Exo）自研字幕管线的源清单：服务器字幕 + 侧载字幕；mpv 返回空（native 接管） */
    private fun pipelineSourcesFor(
        mediaId: String,
        item: PlayerItem?,
    ): List<PlayerSubtitleSource> =
        when {
            playerBackend == PLAYER_BACKEND_MPV -> emptyList()
            item != null -> subtitleSourcesForBackend(item)
            else -> sideloadSourcesFor(mediaId)
        }

    /**
     * mpv：把当前媒体还没注入的侧载字幕补 `sub-add`。
     *
     * 在每次 track-list 变化后调用（文件加载完成、注入完成都会再次回调）；没注入成功时 不会在这里死循环重试，等下一次轨道变化或用户操作再补。
     */
    private fun ensureMpvSideloadsInjected(tracks: Tracks? = null) {
        if (playerBackend != PLAYER_BACKEND_MPV) return
        if (tracks != null && tracks.groups.isEmpty()) return
        val mpv = player as? MPVPlayer ?: return
        // 「已提交 sub-add、track-list 还没回来」也算已注入，避免重复注入（真机踩到过 3 份）
        val missing = sideloads.filter { !mpv.isSubtitleFileAttached(it.path) }
        if (missing.isEmpty()) return
        missing.forEach { record -> mpv.addSubtitleFile(record.path, select = false) }
        Timber.d(
            "mpv 侧载字幕注入：%d 条（媒体 %s）",
            missing.size,
            player.currentMediaItem?.mediaId,
        )
    }

    /** 当前实际播放条目的媒体 id（优先播放器，退回 uiState） */
    private fun currentPlaybackMediaId(): String? =
        player.currentMediaItem?.mediaId?.takeIf { it.isNotBlank() }
            ?: _uiState.value.currentItemId?.toString()

    /** 当前实际播放条目在 ViewModel 清单里的原始 PlayerItem（队列换集 / 通知回前台都可能取不到） */
    private fun currentPlaybackItem(): PlayerItem? {
        val mediaId = player.currentMediaItem?.mediaId ?: return null
        return items.firstOrNull { it.itemId.toString() == mediaId }
    }

    private fun currentDecodeStage(): PlayerDecodeMode.DecodeStage =
        PlayerDecodeMode.decodeStage(
            backend = playerBackend,
            mode = appPreferences.getValue(appPreferences.playerDecodeMode),
            fallbackStage =
                PlayerDecodeFallback.normalize(
                    appPreferences.getValue(appPreferences.playerDecodeFallbackStage)
                ),
        )

    /** 字幕渲染路由：文字轨交给谁渲染 */
    private sealed interface SubtitleRouting {
        /** 自研字幕接管（或正在加载）：禁用内核文字轨 */
        data object Managed : SubtitleRouting

        /** 用户显式关闭字幕 */
        data object Off : SubtitleRouting

        /** 图形字幕：由内核渲染，尽量匹配到具体轨道 */
        data class Native(val source: PlayerSubtitleSource) : SubtitleRouting

        /** 没有特殊约定：交给语言优先级引擎 */
        data object Auto : SubtitleRouting
    }

    private fun resolveSubtitleRouting(
        state: SubtitleOverlayState = subtitleController.overlayState.value
    ): SubtitleRouting {
        if (state.disabledByUser) return SubtitleRouting.Off
        val selected = state.sources.firstOrNull { it.index == state.primaryIndex }
        if (selected != null) {
            // 自管字幕「已接管」或「正在加载」都先禁掉内核文字轨：
            // 否则下载完成的瞬间会先闪一下原生字幕再被自绘层替换
            if (selected.isTextBased && (state.primaryManaged || state.primaryLoading)) {
                return SubtitleRouting.Managed
            }
            if (selected.isGraphic) return SubtitleRouting.Native(selected)
        }
        return SubtitleRouting.Auto
    }

    /**
     * 自动选轨 + 字幕路由：一次算出最终参数、一次设置。
     *
     * 计算与设置必须合成一次：先让语言引擎选文字轨、再让路由禁掉它， 两次 setTrackSelectionParameters 会让轨道状态变化两轮，形成回调循环。
     */
    private fun applyAutoSelectAndRoute(tracks: Tracks = player.currentTracks) {
        if (player !is ExoPlayer) return
        if (player.currentMediaItem == null) return
        val routing = resolveSubtitleRouting()
        runCatching {
            val parameters =
                trackSelectionEngine.parameters(
                    player.trackSelectionParameters,
                    tracks,
                    subtitlesManaged = routing !is SubtitleRouting.Auto,
                )
            player.trackSelectionParameters = routeSubtitleParameters(parameters, routing)
        }
            .onFailure { Timber.w(it, "自动选择字幕/音轨失败") }
        publishSubtitlePanelState()
    }

    /** 只调整字幕部分（音轨保持现状）：用户手动选过轨之后走这里 */
    private fun applySubtitleRoutingOnly() {
        if (player !is ExoPlayer) return
        if (player.currentMediaItem == null) return
        val routing = resolveSubtitleRouting()
        if (routing is SubtitleRouting.Auto) return
        runCatching {
            player.trackSelectionParameters =
                routeSubtitleParameters(player.trackSelectionParameters, routing)
        }
            .onFailure { Timber.w(it, "字幕渲染路由失败") }
        publishSubtitlePanelState()
    }

    /** 把路由规则应用到轨道选择参数 */
    private fun routeSubtitleParameters(
        parameters: TrackSelectionParameters,
        routing: SubtitleRouting,
    ): TrackSelectionParameters =
        when (routing) {
            SubtitleRouting.Managed,
            SubtitleRouting.Off ->
                parameters
                    .buildUpon()
                    .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                    .build()
            is SubtitleRouting.Native -> applyNativeSubtitleOverride(parameters, routing.source)
            SubtitleRouting.Auto -> parameters
        }

    /** 图形字幕：按语言匹配内核文字轨（匹配不到就用第一条文字轨兜底） */
    private fun applyNativeSubtitleOverride(
        parameters: TrackSelectionParameters,
        source: PlayerSubtitleSource,
    ): TrackSelectionParameters {
        val groups =
            player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_TEXT && it.isSupported }
        if (groups.isEmpty()) return parameters
        val base = LanguageMatcher.baseOf(source.language)
        val matched =
            groups.firstOrNull { group ->
                val format = group.mediaTrackGroup.getFormat(0)
                val tag = LanguageMatcher.detect(format.language, format.label, format.id)
                tag != null && source.language.isNotBlank() && LanguageMatcher.baseOf(tag) == base
            } ?: groups.firstOrNull()
        if (matched == null) return parameters
        return parameters
            .buildUpon()
            .setOverrideForType(TrackSelectionOverride(matched.mediaTrackGroup, 0))
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .build()
    }

    /** 刷新字幕面板状态：两个内核在这里汇总成同一份 UI 数据 */
    private fun publishSubtitlePanelState(
        state: SubtitleOverlayState = subtitleController.overlayState.value
    ) {
        val delayMs = appPreferences.getValue(appPreferences.playerSubtitleDelayMs)
        val style = readSubtitleStyle()

        if (playerBackend == PLAYER_BACKEND_MPV) {
            val mpv = player as? MPVPlayer
            val sideloadTrackIds =
                sideloads.mapNotNull { record -> mpv?.subtitleTrackIdForPath(record.path) }.toSet()
            val options = nativeSubtitleOptions(sideloadTrackIds)
            _subtitlePanelState.value =
                SubtitlePanelState(
                    primaryOptions = options,
                    secondaryOptions = options.filter { it.id != mpvSecondarySubtitleId },
                    primaryId = options.firstOrNull { it.selected }?.id,
                    secondaryId = mpvSecondarySubtitleId,
                    delayMs = delayMs,
                    style = style,
                    managed = false,
                    controllable = options.isNotEmpty(),
                    loading = false,
                )
            return
        }

        _subtitlePanelState.value =
            SubtitlePanelState(
                primaryOptions =
                    state.sources.map { source ->
                        subtitleOptionOf(
                            source,
                            selected = source.index == state.primaryIndex,
                            sideloaded = source.index >= SideloadedSubtitleStore.INDEX_BASE,
                        )
                    },
                secondaryOptions =
                    state.sources
                        .filter { it.isTextBased && it.index != state.primaryIndex }
                        .map { source ->
                            subtitleOptionOf(
                                source,
                                selected = source.index == state.secondaryIndex,
                            )
                        },
                primaryId = state.primaryIndex,
                secondaryId = state.secondaryIndex,
                delayMs = delayMs,
                style = style,
                managed = state.primaryManaged,
                controllable = state.sources.any { it.isTextBased },
                loading = state.loading,
            )
    }

    private fun subtitleOptionOf(
        source: PlayerSubtitleSource,
        selected: Boolean,
        sideloaded: Boolean = false,
    ): SubtitleOption {
        val kind =
            when {
                source.isGraphic -> application.getString(R.string.player_subtitle_graphic)
                source.isExternal -> application.getString(R.string.player_subtitle_external)
                else -> application.getString(R.string.player_subtitle_embedded)
            }
        return SubtitleOption(
            id = source.index,
            label = source.title,
            caption = "${source.formatLabel} · $kind",
            adjustable = source.isTextBased,
            selected = selected,
            sideloaded = sideloaded,
        )
    }

    /** mpv 字幕轨列表：Format.id 就是 mpv 的 sid */
    private fun nativeSubtitleOptions(
        sideloadTrackIds: Set<Int> = emptySet()
    ): List<SubtitleOption> =
        player.currentTracks.groups
            .filter { it.type == C.TRACK_TYPE_TEXT && it.isSupported }
            .mapNotNull { group ->
                val format = group.mediaTrackGroup.getFormat(0)
                val id = format.id?.toIntOrNull() ?: return@mapNotNull null
                SubtitleOption(
                    id = id,
                    label =
                        format.label?.takeIf { it.isNotBlank() }
                            ?: format.language?.takeIf { it.isNotBlank() }
                            ?: "字幕 $id",
                    caption = format.language,
                    adjustable = true,
                    selected = group.isSelected,
                    sideloaded = id in sideloadTrackIds,
                )
            }

    // ---------- 音轨面板：延迟 + 轨道描述（§1.2） ----------

    /** 选音轨；复用换轨逻辑（会记住所选语言，跨视频沿用） */
    fun selectAudioTrack(index: Int) {
        switchToTrack(C.TRACK_TYPE_AUDIO, index)
        publishAudioPanelState()
    }

    /** 音轨延迟 ±0.05s；ExoPlayer 与 mpv 都即时生效 */
    fun adjustAudioDelay(deltaMs: Long) {
        val current = appPreferences.getValue(appPreferences.playerAudioDelayMs)
        val next =
            (current + deltaMs).coerceIn(
                -AudioDelayProcessor.MAX_DELAY_MS,
                AudioDelayProcessor.MAX_DELAY_MS,
            )
        setAudioDelay(next)
    }

    /** 音轨延迟归零 */
    fun resetAudioDelay() {
        setAudioDelay(0L)
    }

    private fun setAudioDelay(delayMs: Long) {
        appPreferences.setValue(appPreferences.playerAudioDelayMs, delayMs)
        playerHolder.setAudioDelay(delayMs)
        publishAudioPanelState()
    }

    /** 刷新音轨面板：两个内核汇总成同一份 UI 数据 */
    private fun publishAudioPanelState() {
        val options =
            player.currentTracks.groups
                .filter { it.type == C.TRACK_TYPE_AUDIO && it.isSupported }
                .mapIndexed { index, group ->
                    val format =
                        (0 until group.length)
                            .firstOrNull { group.isTrackSelected(it) }
                            ?.let { group.getTrackFormat(it) } ?: group.mediaTrackGroup.getFormat(0)
                    AudioOption(
                        id = index,
                        label = audioTrackLabel(format, index),
                        caption = audioTrackCaption(format),
                        selected = group.isSelected,
                    )
                }
        _audioPanelState.value =
            AudioPanelState(
                options = options,
                delayMs = appPreferences.getValue(appPreferences.playerAudioDelayMs),
            )
    }

    private fun audioTrackLabel(
        format: Format,
        index: Int,
    ): String =
        format.label?.takeIf { it.isNotBlank() }
            ?: format.language?.takeIf { it.isNotBlank() }?.let { languageDisplayName(it) }
            ?: application.getString(R.string.player_audio_track_index, index + 1)

    /** 轨道描述：编码 · 声道 · 码率（没有码率就显示采样率） */
    private fun audioTrackCaption(format: Format): String {
        val parts = mutableListOf<String>()
        audioCodecLabel(format)?.let { parts += it }
        if (format.channelCount > 0) {
            parts += application.getString(R.string.player_audio_channels, format.channelCount)
        }
        when {
            format.bitrate > 0 ->
                parts += application.getString(R.string.player_audio_bitrate, format.bitrate / 1000)
            format.sampleRate > 0 ->
                parts +=
                    application.getString(
                        R.string.player_audio_sample_rate,
                        format.sampleRate / 1000,
                    )
        }
        return parts.joinToString(" · ")
    }

    /** 音频编码短名：面板里要能一眼看懂 */
    private fun audioCodecLabel(format: Format): String? {
        val mime = format.sampleMimeType.orEmpty()
        val codecs = format.codecs.orEmpty()
        return when {
            mime.contains("eac3", ignoreCase = true) ||
                codecs.startsWith("ec-3", ignoreCase = true) -> "E-AC-3"
            mime.contains("ac3", ignoreCase = true) ||
                codecs.startsWith("ac-3", ignoreCase = true) -> "AC-3"
            mime.contains("opus", ignoreCase = true) -> "Opus"
            mime.contains("aac", ignoreCase = true) ||
                codecs.startsWith("mp4a", ignoreCase = true) -> "AAC"
            mime.contains("flac", ignoreCase = true) -> "FLAC"
            mime.contains("mpeg", ignoreCase = true) ||
                codecs.startsWith("mp3", ignoreCase = true) -> "MP3"
            mime.contains("truehd", ignoreCase = true) -> "TrueHD"
            mime.contains("dts", ignoreCase = true) -> "DTS"
            mime.contains("pcm", ignoreCase = true) -> "PCM"
            codecs.isNotBlank() -> codecs.uppercase()
            else -> null
        }
    }

    /** 语言标签转可读名（jpn → 日语）；识别不出来就原样显示 */
    private fun languageDisplayName(tag: String): String =
        runCatching { Locale.forLanguageTag(tag).getDisplayLanguage(Locale.getDefault()) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() && !it.equals(tag, ignoreCase = true) } ?: tag

    fun switchToTrack(trackType: @C.TrackType Int, index: Int) {
        // 用户手动选择后，本次播放不再自动改轨；同时把语言记为首选，供后续视频沿用
        manualTrackSelectionMediaId = player.currentMediaItem?.mediaId

        // Index -1 equals disable track
        if (index == -1) {
            player.trackSelectionParameters =
                player.trackSelectionParameters
                    .buildUpon()
                    .clearOverridesOfType(trackType)
                    .setTrackTypeDisabled(trackType, true)
                    .build()
        } else {
            val group =
                player.currentTracks.groups.filter { it.type == trackType && it.isSupported }[index]
            trackSelectionEngine.rememberSelectedLanguage(
                trackType,
                group.mediaTrackGroup.getFormat(0),
            )
            player.trackSelectionParameters =
                player.trackSelectionParameters
                    .buildUpon()
                    .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, 0))
                    .setTrackTypeDisabled(trackType, false)
                    .build()
        }
    }

    fun selectSpeed(speed: Float) {
        player.setPlaybackSpeed(speed)
        playbackSpeed = speed
    }

    /**
     * 从错误中重试：清掉错误卡片，回到失败前的位置重新 prepare。
     *
     * ExoPlayer 出错后停在 STATE_IDLE，重新 prepare 即重新拉流； mpv 的 prepare 会重新 loadfile 并回到片头，所以这里统一把位置挪回去。
     */
    fun retryPlayback() {
        val position = currentResumePositionMs()
        Timber.d("Retrying playback on backend=$playerBackend from position=$position")
        _uiState.update { it.copy(playerError = null) }
        player.prepare()
        if (position > 0L) {
            player.seekTo(position)
        }
        player.play()
    }

    /**
     * W73（#8）：当前可用的续播位置。
     *
     * 播放器实时位置优先；错误态（mpv 报错后位置为 0 / 未知）回退到最近一次进度快照（每 5 秒 / 暂停 / 切集落盘，误差 ≤5 秒）， 避免网络抖动恢复后从片头重播。
     */
    private fun currentResumePositionMs(): Long {
        val livePosition = player.currentPosition
        if (livePosition > 0L) return livePosition
        val mediaId =
            player.currentMediaItem?.mediaId?.let { raw ->
                runCatching { UUID.fromString(raw) }.getOrNull()
            }
        return if (mediaId != null && mediaId == lastProgressItemId) lastProgressPositionMs else 0L
    }

    /**
     * 一键切换解码内核：写入偏好并返回新内核名，由调用方重启播放页生效。
     *
     * 不能就地换：player 与 MediaSession 都在构造时绑定，换实例要连带重建会话。
     */
    fun switchBackend(): String {
        val next =
            if (playerBackend == PLAYER_BACKEND_MPV) PLAYER_BACKEND_EXOPLAYER
            else PLAYER_BACKEND_MPV
        appPreferences.setValue(appPreferences.playerBackend, next)
        Timber.d("Player backend switched: $playerBackend -> $next")
        return next
    }

    /**
     * 直接从偏好切内核（§1.9 设置面板的「解码」组）：播放页随后走「重启 + 续播」。
     *
     * 与 [switchBackend] 的区别是目标值由调用方给定（面板点哪一项就切哪一项）。
     */
    fun setBackend(backend: String) {
        appPreferences.setValue(appPreferences.playerBackend, backend)
        Timber.d("Player backend set to %s by settings panel", backend)
    }

    /**
     * 页内改字幕模式（§1.9 设置面板的「字幕」组）：写偏好并立即重选 / 关闭字幕。
     *
     * - `off`：关掉主 / 次字幕（自研管线与内核字幕一起停）；
     * - `auto` / `always`：清掉「手动选过轨」的标记，让语言引擎重新按优先级挑一条。
     */
    fun setSubtitleMode(mode: String) {
        appPreferences.setValue(appPreferences.subtitleMode, mode)
        Timber.d("Subtitle mode set to %s by settings panel", mode)
        val mpv = if (playerBackend == PLAYER_BACKEND_MPV) player as? MPVPlayer else null
        if (mode == Constants.SubtitleMode.OFF) {
            selectSubtitlePrimary(null)
            selectSubtitleSecondary(null)
            // 关闭后同时关掉「自动选轨」意图：否则转码注入字幕时 mpv 又按 slang 选回来
            mpv?.setSubtitleAutoSelect(false)
        } else {
            manualTrackSelectionMediaId = null
            if (mpv != null) {
                // mpv 不走 TrackSelectionParameters：把 auto / always 都翻译成 sid=auto（按 slang 重选）
                mpv.setSubtitleAutoSelect(true)
            } else {
                applyAutoSelectAndRoute()
            }
        }
        publishSubtitlePanelState()
    }

    /**
     * mpv 当前的字幕自动选轨意图与 App 字幕模式对齐。
     *
     * `off` → `sid=no`；`auto` / `always` → `sid=auto`（mpv 按 `slang` / 默认轨挑选）。 ExoPlayer 的同类逻辑走
     * TrackSelectionParameters 路由，不需要这个桥。
     */
    private fun syncMpvSubtitleMode() {
        val mpv = player as? MPVPlayer ?: return
        val mode = appPreferences.getValue(appPreferences.subtitleMode)
        mpv.setSubtitleAutoSelect(mode != Constants.SubtitleMode.OFF)
    }

    /** 重读「跳过片头片尾」相关偏好（面板改完即时生效，不必重开播放页） */
    fun refreshSegmentPreferences() {
        segmentsSkipButton = appPreferences.getValue(appPreferences.playerMediaSegmentsSkipButton)
        segmentsSkipButtonTypes =
            appPreferences.getValue(appPreferences.playerMediaSegmentsSkipButtonType)
        segmentsSkipButtonDuration =
            appPreferences.getValue(appPreferences.playerMediaSegmentsSkipButtonDuration)
        segmentsAutoSkip = appPreferences.getValue(appPreferences.playerMediaSegmentsAutoSkip)
        segmentsAutoSkipTypes =
            appPreferences.getValue(appPreferences.playerMediaSegmentsAutoSkipType)
        segmentsAutoSkipMode =
            appPreferences.getValue(appPreferences.playerMediaSegmentsAutoSkipMode)

        /*
         * W20（§6.1）：提示条时长以前写死在控制层（8 秒），设置里的「显示时长」形同虚设。
         * 这里把偏好换算成毫秒放进 uiState，控制层按它超时收起；改设置后下一秒轮询即生效。
         */
        val skipChipDurationMs = segmentsSkipButtonDuration.coerceAtLeast(1L) * 1000L
        if (_uiState.value.skipChipDurationMs != skipChipDurationMs) {
            _uiState.update { it.copy(skipChipDurationMs = skipChipDurationMs) }
        }
    }

    // ---------- 队列管理：拖拽排序 / 删除 / 清空 / 跳转（§1.7） ----------

    /**
     * 队列排序（拖拽）：把 [fromIndex] 的条目移到 [toIndex]。
     *
     * 直接改播放器时间线（ExoPlayer 用原生 `moveMediaItem`，mpv 走 `playlist-move`）； 整理过之后置
     * [queueManuallyEdited]，避免后台补片把用户刚删掉的条目又补回来。
     */
    fun moveQueueItem(fromIndex: Int, toIndex: Int) {
        val count = player.mediaItemCount
        if (fromIndex == toIndex || fromIndex !in 0 until count || toIndex !in 0 until count) return
        queueManuallyEdited = true
        player.moveMediaItem(fromIndex, toIndex)
        Timber.d("queue move: %d -> %d（共 %d 项）", fromIndex, toIndex, player.mediaItemCount)
    }

    /** 从队列移除一条；正在播放的条目不允许移除（UI 上那一行的删除键禁用） */
    fun removeQueueItem(index: Int) {
        val count = player.mediaItemCount
        if (index !in 0 until count || index == player.currentMediaItemIndex) return
        queueManuallyEdited = true
        player.removeMediaItem(index)
        Timber.d("queue remove: %d（共 %d 项）", index, player.mediaItemCount)
    }

    /**
     * 清空队列：**保留正在播放的条目**，把其它待播项全部移除。
     *
     * 直接 clearMediaItems 会把当前条目也删掉并停播，不符合「清空待播列表」的预期。
     */
    fun clearQueue() {
        queueManuallyEdited = true
        queueFillJob?.cancel()
        val before = player.currentMediaItemIndex
        if (before > 0) player.removeMediaItems(0, before)
        val current = player.currentMediaItemIndex
        val count = player.mediaItemCount
        if (count > current + 1) player.removeMediaItems(current + 1, count)
        Timber.d("queue clear: 保留当前条目，队列剩 %d 项", player.mediaItemCount)
    }

    /** 手动收起错误卡片（不改播放状态） */
    fun dismissPlayerError() {
        _uiState.update { it.copy(playerError = null) }
    }

    private suspend fun getSegments(itemId: UUID) {
        try {
            currentMediaItemSegments = repository.getSegments(itemId)
        } catch (e: Exception) {
            currentMediaItemSegments = emptyList()
            Timber.e(e)
        }
    }

    /**
     * W20（§1.11 Trickplay 预加载与失败降级）：换集时只准备**按需加载器**。
     *
     * 不再像旧实现那样把整部片的精灵图一次性拉全并解码常驻内存；真正拉图发生在 用户拖动进度条 / 手势 seek 时（[trickplayFrameAt]），每次只拉当前那张图。 服务器没有
     * trickplay（`trickplayInfo == null`、总张数为 0）或用户关掉开关时， 加载器直接为 null，UI 走"无预览图"降级，不产生任何请求。
     */
    private fun prepareTrickplay(item: PlayerItem) {
        val info = item.trickplayInfo
        if (info == null || !appPreferences.getValue(appPreferences.playerTrickplay)) {
            trickplayLoader = null
            _uiState.update {
                it.copy(trickplayIntervalMs = 0, trickplayVersion = it.trickplayVersion + 1)
            }
            return
        }
        val loader =
            TrickplayPreviewLoader(
                info = info,
                scope = viewModelScope,
                fetchTileSheet = { sheet ->
                    repository.getTrickplayData(item.itemId, info.width, sheet)
                },
                onSheetReady = {
                    _uiState.update { state ->
                        state.copy(trickplayVersion = state.trickplayVersion + 1)
                    }
                },
            )
        trickplayLoader = loader
        Timber.d(
            "Trickplay 按需加载就绪：interval=%dms tiles=%d sheets=%d",
            info.interval,
            TrickplayTiles.tileCount(info),
            TrickplayTiles.sheetCount(info),
        )
        _uiState.update {
            it.copy(
                trickplayIntervalMs = if (loader.available) info.interval else 0,
                trickplayVersion = it.trickplayVersion + 1,
            )
        }
    }

    /**
     * 取 [positionMs] 处的 Trickplay 预览图（W20）。
     *
     * 只读缓存 + 触发后台拉取，不阻塞调用方（拖动 / Composition 都直接调用）； 未命中或拉取失败返回 null，调用方按"没有预览图"处理即可。
     */
    fun trickplayFrameAt(positionMs: Long): Bitmap? = trickplayLoader?.frameAt(positionMs)

    /* ---------- W73（#7）：seek 排队 + 转码会话重开 ---------- */

    /**
     * 统一 seek 入口（进度条 / ±键 / 手势 / 章节 / 片头尾跳过）。
     *
     * 之前只有手势 seek 有「未就绪窗口排队」（W67c），进度条 drag/tap 的 seek 在媒体未 prepared 时会被随后到来的恢复位置覆盖——切集后立刻 seek
     * 就表现为「回退完成后从 0 开始播」。这里把排队语义下沉到 ViewModel：
     *
     * - 未就绪（未挂载 / 时长未知 / 媒体未 prepared）：按绝对目标、进度条比例或相对增量排队，就绪后补投，不静默丢失；
     * - 已就绪：转码（HLS）流上目标超出当前转码会话可用窗口 → 带目标位置重开会话（服务器从目标开始转码）；否则直接落点。
     *
     * @param fraction 进度条拖动比例（0–1）。时长未知时用它排队，就绪后按真实时长换算落点；时长已知传 null。
     */
    fun requestSeek(targetMs: Long, source: String, fraction: Float? = null) {
        val activePlayer = playerHolder.existingPlayer
        val attached = activePlayer != null
        val duration = activePlayer?.duration ?: C.TIME_UNSET
        val state = activePlayer?.playbackState ?: Player.STATE_IDLE
        val position = activePlayer?.currentPosition ?: 0L
        if (!isSeekRequestReady(attached, duration, state, position)) {
            when {
                fraction != null -> mergePendingSeek(PendingSeek.Fraction(fraction))
                targetMs > 0L -> mergePendingSeek(PendingSeek.Absolute(targetMs))
                else -> {
                    Timber.d("seek 排队忽略（目标不可用）：source=%s target=%d", source, targetMs)
                    return
                }
            }
            pendingSeekSource = source
            schedulePendingSeekFlush()
            Timber.d(
                "seek 排队：source=%s target=%d fraction=%s state=%d duration=%d position=%d",
                source,
                targetMs,
                fraction,
                state,
                duration,
                position,
            )
            return
        }
        executeSeek(activePlayer!!, targetMs, source)
    }

    /** 相对增量 seek（±键 / 快进快退键）：未就绪时并入排队，就绪后按真实位置一次落点。 */
    fun requestSeekRelative(deltaMs: Long, source: String) {
        val activePlayer = playerHolder.existingPlayer
        val ready =
            activePlayer != null &&
                isSeekRequestReady(
                    playerAttached = true,
                    durationMs = activePlayer.duration,
                    playbackState = activePlayer.playbackState,
                    currentPositionMs = activePlayer.currentPosition,
                )
        if (ready && activePlayer != null) {
            executeSeek(activePlayer, activePlayer.currentPosition + deltaMs, source)
            return
        }
        mergePendingSeek(PendingSeek.Relative(deltaMs))
        pendingSeekSource = source
        schedulePendingSeekFlush()
        Timber.d("相对 seek 排队：source=%s Δ=%d", source, deltaMs)
    }

    /** 就绪后的实际落点：转码超窗重开会话，否则直接 seek。 */
    private fun executeSeek(activePlayer: Player, targetMs: Long, source: String) {
        val duration = activePlayer.duration
        val upperBound = if (duration > 0L) duration else Long.MAX_VALUE
        val target = targetMs.coerceIn(0L, upperBound)
        val item = currentPlaybackItem()
        val isTranscode = item?.let { isTranscodeStreamUri(it.mediaSourceUri) } == true
        val isExoPlayer = playerBackend == PLAYER_BACKEND_EXOPLAYER
        val restartSession =
            shouldRestartTranscodeSession(
                isTranscodeStream = isTranscode,
                isExoPlayer = isExoPlayer,
                targetMs = target,
                currentPositionMs = activePlayer.currentPosition,
                bufferedPositionMs = activePlayer.bufferedPosition,
                sessionStartMs = item?.playbackPosition ?: 0L,
            )
        if (restartSession) {
            Timber.d(
                "转码 seek 超出可用窗口：source=%s target=%d position=%d buffered=%d sessionStart=%d",
                source,
                target,
                activePlayer.currentPosition,
                activePlayer.bufferedPosition,
                item?.playbackPosition,
            )
            scheduleTranscodeRestart(target, source)
            return
        }
        activePlayer.seekTo(target)
        Timber.d(
            "seek 落点：source=%s target=%d position=%d buffered=%d transcode=%s",
            source,
            target,
            activePlayer.currentPosition,
            activePlayer.bufferedPosition,
            isTranscode,
        )
        armTranscodeSeekWatchdog(target, source, enabled = isTranscode && isExoPlayer)
    }

    private fun schedulePendingSeekFlush() {
        if (pendingSeekFlushJob?.isActive == true) return
        pendingSeekFlushJob =
            viewModelScope.launch(Dispatchers.Main) {
                var retries = 0
                while (isActive) {
                    val activePlayer = playerHolder.existingPlayer
                    val ready =
                        activePlayer != null &&
                            isSeekRequestReady(
                                playerAttached = true,
                                durationMs = activePlayer.duration,
                                playbackState = activePlayer.playbackState,
                                currentPositionMs = activePlayer.currentPosition,
                            )
                    if (ready && activePlayer != null) {
                        val pending = pendingSeek ?: return@launch
                        val target = resolvePendingSeek(pending, activePlayer) ?: return@launch
                        pendingSeek = null
                        executeSeek(activePlayer, target, pendingSeekSource)
                        return@launch
                    }
                    if (retries++ >= PENDING_SEEK_MAX_RETRIES) {
                        Timber.w("seek 排队超时丢弃：%s", pendingSeek)
                        pendingSeek = null
                        return@launch
                    }
                    delay(PENDING_SEEK_RETRY_MS)
                }
            }
    }

    private fun resolvePendingSeek(pending: PendingSeek, activePlayer: Player): Long? =
        when (pending) {
            is PendingSeek.Absolute -> pending.targetMs
            is PendingSeek.Fraction ->
                seekTargetFromFraction(pending.fraction, activePlayer.duration)
            is PendingSeek.Relative -> activePlayer.currentPosition + pending.deltaMs
        }

    private fun mergePendingSeek(next: PendingSeek) {
        pendingSeek =
            when (val current = pendingSeek) {
                null -> next
                is PendingSeek.Relative ->
                    when (next) {
                        is PendingSeek.Relative ->
                            PendingSeek.Relative(
                                (current.deltaMs + next.deltaMs).coerceIn(
                                    -PENDING_SEEK_DELTA_MAX_MS,
                                    PENDING_SEEK_DELTA_MAX_MS,
                                )
                            )
                        else -> next
                    }
                is PendingSeek.Absolute ->
                    when (next) {
                        is PendingSeek.Relative ->
                            PendingSeek.Absolute(
                                (current.targetMs + next.deltaMs).coerceAtLeast(0L)
                            )
                        else -> next
                    }
                is PendingSeek.Fraction ->
                    when (next) {
                        // 时长未知时无法把增量换算成比例：保留比例，忽略增量（极边角场景）
                        is PendingSeek.Relative -> current
                        else -> next
                    }
            }
    }

    /** 转码重开会话的尾部去抖入口：拖动 / 连点只执行最后一次目标。 */
    private fun scheduleTranscodeRestart(targetMs: Long, source: String) {
        transcodeRestartPendingTargetMs = targetMs
        transcodeRestartDebounceJob?.cancel()
        transcodeRestartDebounceJob =
            viewModelScope.launch(Dispatchers.Main) {
                delay(TRANSCODE_RESTART_DEBOUNCE_MS)
                if (transcodeRestartInFlight) return@launch
                val pendingTarget = transcodeRestartPendingTargetMs ?: return@launch
                transcodeRestartPendingTargetMs = null
                doRestartTranscodeSession(pendingTarget, source)
            }
    }

    /**
     * 原位重开转码会话：重新拉 PlaybackInfo（startTimeTicks = 目标位置）→ `replaceMediaItem` 替换当前条目 → 从目标位置起播。
     *
     * 不重启 Activity（不触发 viewModelStore.clear / recreate）；成功一次计数一次，同一媒体在
     * [TRANSCODE_RESTART_WINDOW_MS] 内超过 [TRANSCODE_RESTART_WINDOW_MAX] 次则降级为普通 seek，避免异常场景无限重开。
     */
    private fun doRestartTranscodeSession(targetMs: Long, source: String) {
        val activePlayer = playerHolder.existingPlayer ?: return
        if (playerBackend != PLAYER_BACKEND_EXOPLAYER) return
        val currentItem = currentPlaybackItem()
        if (currentItem == null) {
            activePlayer.seekTo(targetMs)
            return
        }
        val mediaId = currentItem.itemId.toString()
        val now = SystemClock.elapsedRealtime()
        if (mediaId != lastTranscodeRestartMediaId) {
            lastTranscodeRestartMediaId = mediaId
            transcodeRestartWindowStartMs = now
            transcodeRestartWindowCount = 0
        } else if (now - transcodeRestartWindowStartMs > TRANSCODE_RESTART_WINDOW_MS) {
            transcodeRestartWindowStartMs = now
            transcodeRestartWindowCount = 0
        }
        if (transcodeRestartWindowCount >= TRANSCODE_RESTART_WINDOW_MAX) {
            Timber.w(
                "转码重开会话过于频繁（%d 次 / %d ms），降级为普通 seek：target=%d source=%s",
                transcodeRestartWindowCount,
                TRANSCODE_RESTART_WINDOW_MS,
                targetMs,
                source,
            )
            activePlayer.seekTo(targetMs)
            return
        }
        val index = activePlayer.currentMediaItemIndex
        val wasPlaying = activePlayer.playWhenReady
        transcodeRestartInFlight = true
        viewModelScope.launch(Dispatchers.Main) {
            try {
                val rebuilt = runCatching {
                    withContext(Dispatchers.IO) {
                        playlistManager.rebuildPlayerItemForPosition(
                            currentItem.itemId,
                            targetMs,
                        )
                    }
                }
                    .onFailure { Timber.e(it, "转码重开会话：重建播放信息异常") }
                    .getOrNull()
                val livePlayer = playerHolder.existingPlayer
                if (livePlayer == null || livePlayer !== activePlayer) return@launch
                if (
                    livePlayer.currentMediaItem?.mediaId != mediaId ||
                        livePlayer.currentMediaItemIndex != index
                ) {
                    Timber.d("转码重开会话丢弃：播放条目已切换（%s）", mediaId)
                    return@launch
                }
                if (rebuilt == null) {
                    /*
                     * W73（#8）：播放信息拉取失败（典型是网络抖动）时不切内核 / 不重启页面，原地重试当前内核；
                     * 播放器已在错误态时直接 seekTo 无效。
                     */
                    Timber.w("转码重开会话失败（重建播放信息为空），原地重试当前内核：target=%d", targetMs)
                    retryPlayback()
                    return@launch
                }
                val itemIndex = items.indexOfFirst { it.itemId == currentItem.itemId }
                if (itemIndex >= 0) {
                    items[itemIndex] = rebuilt
                }
                livePlayer.replaceMediaItem(index, rebuilt.toMediaItem())
                // 先 seek 再 prepare：prepare 时从目标位置开始加载分片，不再从 0 请求
                livePlayer.seekTo(index, targetMs)
                livePlayer.prepare()
                // 保持用户的播放 / 暂停意图：暂停中 seek 不自动起播
                if (wasPlaying) livePlayer.play() else livePlayer.pause()
                transcodeRestartWindowCount++
                Timber.d(
                    "转码 seek 重开会话：item=%s target=%d source=%s index=%d count=%d wasPlaying=%s",
                    mediaId,
                    targetMs,
                    source,
                    index,
                    transcodeRestartWindowCount,
                    wasPlaying,
                )
            } finally {
                transcodeRestartInFlight = false
                val merged = transcodeRestartPendingTargetMs
                transcodeRestartPendingTargetMs = null
                if (merged != null && kotlin.math.abs(merged - targetMs) > 2_000L) {
                    scheduleTranscodeRestart(merged, "merged")
                }
            }
        }
    }

    /** 转码流直接 seek 的看门狗：目标附近仍在缓冲且位置没有推进 → 判定为超出服务器已生成窗口，带目标重开会话兜底。 */
    private fun armTranscodeSeekWatchdog(targetMs: Long, source: String, enabled: Boolean) {
        transcodeSeekWatchdogJob?.cancel()
        if (!enabled) return
        transcodeSeekWatchdogJob =
            viewModelScope.launch(Dispatchers.Main) {
                delay(TRANSCODE_SEEK_WATCHDOG_DELAY_MS)
                val activePlayer = playerHolder.existingPlayer ?: return@launch
                val position = activePlayer.currentPosition
                val stalled =
                    activePlayer.playbackState == Player.STATE_BUFFERING &&
                        kotlin.math.abs(position - targetMs) > TRANSCODE_SEEK_WATCHDOG_TOLERANCE_MS
                if (stalled) {
                    Timber.w(
                        "转码 seek 看门狗：target=%d 仍在缓冲（position=%d buffered=%d source=%s），重开会话",
                        targetMs,
                        position,
                        activePlayer.bufferedPosition,
                        source,
                    )
                    scheduleTranscodeRestart(targetMs, "watchdog")
                }
            }
    }

    fun skipSegment(segment: FindroidSegment) {
        if (shouldSkipToNextEpisode(segment)) {
            player.seekToNextMediaItem()
        } else {
            requestSeek(segment.endTicks, "skip-segment")
        }
        _uiState.update { it.copy(currentSegment = null) }
    }

    // Check if the outro segment's end time is within n milliseconds of the player's total duration
    private fun shouldSkipToNextEpisode(segment: FindroidSegment): Boolean {
        return if (segment.type == FindroidSegmentType.OUTRO && player.hasNextMediaItem()) {
            val segmentEndTimeMillis = segment.endTicks
            val playerDurationMillis = player.duration
            val thresholdMillis =
                playerDurationMillis -
                    appPreferences.getValue(appPreferences.playerMediaSegmentsNextEpisodeThreshold)

            segmentEndTimeMillis > thresholdMillis
        } else {
            false
        }
    }

    private fun getSkipButtonTextStringId(segment: FindroidSegment): Int {
        return when (shouldSkipToNextEpisode(segment)) {
            true -> R.string.player_controls_next_episode
            false ->
                when (segment.type) {
                    FindroidSegmentType.INTRO -> R.string.player_controls_skip_intro
                    FindroidSegmentType.OUTRO -> R.string.player_controls_skip_outro
                    FindroidSegmentType.RECAP -> R.string.player_controls_skip_recap
                    FindroidSegmentType.COMMERCIAL -> R.string.player_controls_skip_commercial
                    FindroidSegmentType.PREVIEW -> R.string.player_controls_skip_preview
                    else -> R.string.player_controls_skip_unknown
                }
        }
    }

    /**
     * Get chapters of current item
     *
     * @return list of [PlayerChapter]
     */
    private fun getChapters(): List<PlayerChapter> {
        return uiState.value.currentChapters
    }

    /**
     * Get the index of the current chapter
     *
     * @return the index of the current chapter
     */
    private fun getCurrentChapterIndex(): Int? {
        val chapters = getChapters()

        for (i in chapters.indices.reversed()) {
            if (chapters[i].startPosition < player.currentPosition) {
                return i
            }
        }

        return null
    }

    /**
     * Get the index of the next chapter
     *
     * @return the index of the next chapter
     */
    private fun getNextChapterIndex(): Int? {
        val chapters = getChapters()
        val currentChapterIndex = getCurrentChapterIndex() ?: return null

        return minOf(chapters.size - 1, currentChapterIndex + 1)
    }

    /**
     * Get the index of the previous chapter. Only use this for seeking as it will return the
     * current chapter when player position is more than 5 seconds past the start of the chapter
     *
     * @return the index of the previous chapter
     */
    private fun getPreviousChapterIndex(): Int? {
        val chapters = getChapters()
        val currentChapterIndex = getCurrentChapterIndex() ?: return null

        // Return current chapter when more than 5 seconds past chapter start
        if (player.currentPosition > chapters[currentChapterIndex].startPosition + 5000L) {
            return currentChapterIndex
        }

        return maxOf(0, currentChapterIndex - 1)
    }

    fun isLastChapter(): Boolean =
        getChapters().let { chapters -> getCurrentChapterIndex() == chapters.size - 1 }

    /**
     * Seek to chapter
     *
     * @param [chapterIndex] the index of the chapter to seek to
     * @return the [PlayerChapter] which has been sought to
     */
    private fun seekToChapter(chapterIndex: Int): PlayerChapter? {
        return getChapters().getOrNull(chapterIndex)?.also { chapter ->
            requestSeek(chapter.startPosition, "chapter")
        }
    }

    /**
     * Seek to the next chapter
     *
     * @return the [PlayerChapter] which has been sought to
     */
    fun seekToNextChapter(): PlayerChapter? {
        return getNextChapterIndex()?.let { seekToChapter(it) }
    }

    /**
     * Seek to the previous chapter Will seek to start of current chapter if player position is more
     * than 5 seconds past start of chapter
     *
     * @return the [PlayerChapter] which has been sought to
     */
    fun seekToPreviousChapter(): PlayerChapter? {
        return getPreviousChapterIndex()?.let { seekToChapter(it) }
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        super.onIsPlayingChanged(isPlaying)
        eventsChannel.trySend(PlayerEvents.IsPlayingChanged(isPlaying))
    }
}

sealed interface PlayerEvents {
    data object NavigateBack : PlayerEvents

    data class IsPlayingChanged(val isPlaying: Boolean) : PlayerEvents

    /** 当前内核解不了这个片源：静默换 mpv 软解重播（播放页收到后直接重启播放页；保留给 MPV 兜底路径） */
    data object FallbackToMpv : PlayerEvents

    /**
     * 硬解失败的第 2 档：请求服务器解码/转码后重播（W16 回退链：本地硬解 → 服务器转码 → 本地软解）。
     *
     * PlaybackInfo 已经按回退档位强制转码（data 层读 [AppPreferences.playerDecodeFallbackStage]），
     * 播放页只需从当前位置重启、重新拉一次播放信息。
     */
    data object RestartWithServerTranscode : PlayerEvents

    /** 硬解失败的第 3 档：本地软解（mpv hwdec=no）重播 */
    data object FallbackToSoftware : PlayerEvents

    /** 字幕外观变化：Activity 用它更新 PlayerView 原生字幕样式（图形字幕 / 兜底路径） */
    data class SubtitleStyleChanged(val style: SubtitleStyle) : PlayerEvents
}

/**
 * 自研字幕覆盖层只服务 ExoPlayer 的文字字幕；mpv 的字幕（含 ASS/SSA 特效）由 mpv 内置 libass 渲染。
 *
 * mpv 下返回空清单 = 自研覆盖层不接管、不叠加文本层，避免两套字幕同时出现。
 */
internal fun playerSubtitleSourcesForBackend(
    backend: String,
    item: PlayerItem,
    /** W27 侧载字幕源（序号 ≥ INDEX_BASE）；mpv 侧不使用（由 native sub-add 接管） */
    sideloaded: List<PlayerSubtitleSource> = emptyList(),
): List<PlayerSubtitleSource> =
    if (backend == PlayerViewModel.PLAYER_BACKEND_MPV) {
        emptyList()
    } else {
        item.subtitleSources + sideloaded
    }
