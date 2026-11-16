package com.zhangwenkang.cinefin.player.local.presentation

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import com.zhangwenkang.cinefin.api.JellyfinApi
import com.zhangwenkang.cinefin.models.FindroidEpisode
import com.zhangwenkang.cinefin.models.FindroidMovie
import com.zhangwenkang.cinefin.models.FindroidSegment
import com.zhangwenkang.cinefin.models.FindroidSegmentType
import com.zhangwenkang.cinefin.player.core.domain.models.PLAYER_EXTRA_EPISODE_NUMBER
import com.zhangwenkang.cinefin.player.core.domain.models.PLAYER_EXTRA_MEDIA_INFO
import com.zhangwenkang.cinefin.player.core.domain.models.PLAYER_EXTRA_MEDIA_SOURCE_ID
import com.zhangwenkang.cinefin.player.core.domain.models.PLAYER_EXTRA_SEASON_NUMBER
import com.zhangwenkang.cinefin.player.core.domain.models.PLAYER_EXTRA_SUBTITLE_SOURCES
import com.zhangwenkang.cinefin.player.core.domain.models.PlaybackMediaIdentity
import com.zhangwenkang.cinefin.player.core.domain.models.PlayerChapter
import com.zhangwenkang.cinefin.player.core.domain.models.PlayerItem
import com.zhangwenkang.cinefin.player.core.domain.models.PlayerMediaInfo
import com.zhangwenkang.cinefin.player.core.domain.models.PlayerSubtitleSource
import com.zhangwenkang.cinefin.player.core.domain.models.SubtitleAppearance
import com.zhangwenkang.cinefin.player.core.domain.models.SubtitleStyle
import com.zhangwenkang.cinefin.player.local.R
import com.zhangwenkang.cinefin.player.local.audio.AudioDelayProcessor
import com.zhangwenkang.cinefin.player.local.domain.PlaybackCoordinatorImpl
import com.zhangwenkang.cinefin.player.local.domain.PlaybackPositionWriter
import com.zhangwenkang.cinefin.player.local.domain.PlaybackQueueEntry
import com.zhangwenkang.cinefin.player.local.domain.PlaybackRequestToken
import com.zhangwenkang.cinefin.player.local.domain.PlaybackSnapshotState
import com.zhangwenkang.cinefin.player.local.domain.PlayerDecodeMode
import com.zhangwenkang.cinefin.player.local.domain.PlayerEndBehavior
import com.zhangwenkang.cinefin.player.local.domain.PlayerExitAction
import com.zhangwenkang.cinefin.player.local.domain.PlayerExplicitExitController
import com.zhangwenkang.cinefin.player.local.domain.PlayerExtraPreferences
import com.zhangwenkang.cinefin.player.local.domain.PlayerItemEndAction
import com.zhangwenkang.cinefin.player.local.domain.PlayerProgressFallbackController
import com.zhangwenkang.cinefin.player.local.domain.PlayerProgressFallbackInput
import com.zhangwenkang.cinefin.player.local.domain.PlayerProgressRecoveryController
import com.zhangwenkang.cinefin.player.local.domain.PlayerProgressRecoveryInput
import com.zhangwenkang.cinefin.player.local.domain.PlayerProgressRecoveryPhase
import com.zhangwenkang.cinefin.player.local.domain.PlayerProgressRecoveryRequest
import com.zhangwenkang.cinefin.player.local.domain.PlayerProgressRecoveryStatus
import com.zhangwenkang.cinefin.player.local.domain.PlayerQueueEndAction
import com.zhangwenkang.cinefin.player.local.domain.SeekMediaIdentity
import com.zhangwenkang.cinefin.player.local.domain.SeekState
import com.zhangwenkang.cinefin.player.local.domain.SeriesPlaybackTraceTracker
import com.zhangwenkang.cinefin.player.local.domain.SeriesResolutionTraceLog
import com.zhangwenkang.cinefin.player.local.domain.SleepTimerController
import com.zhangwenkang.cinefin.player.local.domain.TrackSelectionEngine
import com.zhangwenkang.cinefin.player.local.domain.TrickplayTiles
import com.zhangwenkang.cinefin.player.local.domain.acceptCurrentStreamSnapshot
import com.zhangwenkang.cinefin.player.local.domain.advanceCurrentStreamRequest
import com.zhangwenkang.cinefin.player.local.domain.beginPlaybackRequest
import com.zhangwenkang.cinefin.player.local.domain.bindSourceSnapshot
import com.zhangwenkang.cinefin.player.local.domain.isTranscodeStreamUri
import com.zhangwenkang.cinefin.player.local.domain.playbackMediaIdentity
import com.zhangwenkang.cinefin.player.local.domain.playerExitAction
import com.zhangwenkang.cinefin.player.local.domain.playerProgressFallbackSourceKind
import com.zhangwenkang.cinefin.player.local.domain.redactUrlSecrets
import com.zhangwenkang.cinefin.player.local.domain.seekMediaIdentityFor
import com.zhangwenkang.cinefin.player.local.domain.seriesBackendKind
import com.zhangwenkang.cinefin.player.local.domain.shouldReportStopOnPlayerExit
import com.zhangwenkang.cinefin.player.local.domain.shouldStopPlaybackServiceOnExit
import com.zhangwenkang.cinefin.player.local.domain.speedForPlaybackSession
import com.zhangwenkang.cinefin.player.local.mpv.MPVPlayer
import com.zhangwenkang.cinefin.player.local.subtitle.MpvSubtitleTrackMapping
import com.zhangwenkang.cinefin.player.local.subtitle.MpvSubtitleTrackRef
import com.zhangwenkang.cinefin.player.local.subtitle.PlayerSubtitleController
import com.zhangwenkang.cinefin.player.local.subtitle.ScopedSubtitleSelection
import com.zhangwenkang.cinefin.player.local.subtitle.SideloadedSubtitle
import com.zhangwenkang.cinefin.player.local.subtitle.SideloadedSubtitleStore
import com.zhangwenkang.cinefin.player.local.subtitle.SubtitleAppearancePreferences
import com.zhangwenkang.cinefin.player.local.subtitle.SubtitleOverlayState
import com.zhangwenkang.cinefin.player.local.subtitle.SubtitleSecondaryUnavailableReason
import com.zhangwenkang.cinefin.player.local.subtitle.SubtitleSelectionScope
import com.zhangwenkang.cinefin.player.local.subtitle.shouldSuppressSecondaryForMpvPrimarySelection
import com.zhangwenkang.cinefin.repository.JellyfinRepository
import com.zhangwenkang.cinefin.settings.domain.AppPreferences
import com.zhangwenkang.cinefin.settings.domain.Constants
import com.zhangwenkang.cinefin.settings.domain.PlayerDecodeFallback
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.jellyfin.sdk.model.api.BaseItemKind
import timber.log.Timber

@HiltViewModel
class PlayerViewModel
@Inject
constructor(
    private val application: Application,
    private val repository: JellyfinRepository,
    private val playbackPositionWriter: PlaybackPositionWriter,
    private val appPreferences: AppPreferences,
    private val playerHolder: PlayerHolder,
    private val savedStateHandle: SavedStateHandle,
    /** W55 睡眠定时统一：进程级状态源（音乐 / 视频共用同一计时器）。 */
    private val sleepTimerController: SleepTimerController,
    // Concrete binding avoids a missing TV-module coordinator binding.
    private val playbackCoordinator: PlaybackCoordinatorImpl,
    /** W86-B：当前用户 id 是字幕单片命名空间的权威来源，登录 / 切用户时由 SetupRepository 更新。 */
    private val jellyfinApi: JellyfinApi,
) : ViewModel(), Player.Listener {
    private val playlistManager
        get() = playerHolder.playlistManager

    companion object {
        /** 播放核心取值，与 `AppPreferences.playerBackend` 里存的一致 */
        const val PLAYER_BACKEND_EXOPLAYER = "exoplayer"
        const val PLAYER_BACKEND_MPV = "mpv"

        // Local hardware -> server transcode -> local software.
        const val DECODE_MODE_HARDWARE = "hardware"
        const val DECODE_MODE_SOFTWARE = "software"
        const val PLAYER_ERROR_SOURCE_RESOLUTION_FAILED = "SOURCE_RESOLUTION_FAILED"

        fun confirmedPosition(player: Player?): Long? =
            (player as? MPVPlayer)?.confirmedPosition()
                ?: player?.let {
                    confirmedEnginePosition(
                        it.currentMediaItem,
                        it.currentTimeline.isEmpty,
                        it.playbackState,
                        it.currentPosition,
                    )
                }

        fun confirmedBufferedPosition(player: Player?, currentPositionMs: Long?): Long? =
            currentPositionMs?.let {
                boundedBufferedPosition(it, player?.bufferedPosition ?: C.TIME_UNSET)
            }

        /** 单次队列补片最多新增条数：超大剧集（库里有 370 集的）不一次拉满，避免长时间占用网络与播放器时间线 */
        private const val MAX_QUEUE_FILL_ITEMS = 150

        /** 每条插入之间让出主线程的时间（> 1 帧），保证输入 / 渲染消息有机会执行 */
        private const val QUEUE_FILL_STEP_DELAY_MS = 50L

        /** W20（§1.11）：进度常驻上报间隔。播放中每 5 秒向服务端写一次 UserData 进度 */
        private const val PROGRESS_REPORT_INTERVAL_MS = 5_000L

        /** W20：同一集「停止上报」的去重窗口（自然播完已报过 stop，换集回调不要再报一次） */
        private const val STOP_REPORT_DEDUPE_MS = 15_000L

        private const val PLAYBACK_SESSION_SAMPLE_INTERVAL_MS = 1_000L
        private const val SOURCE_RESOLUTION_TIMEOUT_MS = 30_000L

        /** W73（#8）：网络类播放错误的原地重试窗口 / 次数上限（超过则交给错误卡片，不换内核、不重启页面）。 */
        private const val NETWORK_RETRY_WINDOW_MS = 30_000L
        private const val NETWORK_RETRY_MIN_INTERVAL_MS = 3_000L
        private const val NETWORK_RETRY_MAX = 2

        private const val PROGRESS_RECOVERY_OBSERVE_WINDOW_MS = 5_000L
    }

    val player: Player
        get() = playerHolder.player

    val existingPlayer: Player?
        get() = playerAccess.existingPlayer()

    /**
     * Compose 依赖更新只允许读取尚未退出 / finalize 的活动实例。
     *
     * 该属性与 retry、player error、segment 共用 [PlayerAccessInvariant]；关闭后绝不触碰 lazy [player] getter。
     */
    val uiPlayer: Player?
        get() = playerAccess.existingPlayer()

    fun createDelayedPlayerEventDispatcher(
        onFallbackToMpv: () -> Unit,
        onRestartWithServerTranscode: () -> Unit,
        onFallbackToSoftware: () -> Unit,
        onFallbackToExoForProgressStall: (String) -> Unit,
    ): PlayerDelayedEventDispatcher =
        PlayerDelayedEventDispatcher(
            playerAccess = playerAccess,
            onFallbackToMpv = onFallbackToMpv,
            onRestartWithServerTranscode = onRestartWithServerTranscode,
            onFallbackToSoftware = onFallbackToSoftware,
            onFallbackToExoForProgressStall = onFallbackToExoForProgressStall,
        )

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

    private var playbackSnapshots = PlaybackSnapshotState()
    private var activePlaybackToken: PlaybackRequestToken? = null
    private var playbackSessionState = PlaybackSessionState()
    private var playbackSessionJob: Job? = null
    private var playbackStartupJob: Job? = null
    private var failedSourceIntent: SourceResolutionIntent? = null
    private val progressFallbackController = PlayerProgressFallbackController()
    private val progressRecoveryController = PlayerProgressRecoveryController()
    private val explicitExitController = PlayerExplicitExitController()
    private val playbackResourcesGate = PlayerExitFinalizationGate()
    private val playerAccess =
        PlayerAccessInvariant(
            isClosed = { explicitExitController.isExited || playbackResourcesGate.isCompleted },
            existingPlayerProvider = { playerHolder.existingPlayer },
        )
    private val postSuspendCallbackCoordinator =
        PlayerPostSuspendCallbackCoordinator(playerAccess = playerAccess)
    private val subtitlePublicationCoordinator =
        PlayerSubtitlePublicationCoordinator(
            playerAccess = playerAccess,
            publishPanel = ::publishSubtitlePanelState,
            routeSubtitle = { state ->
                applySubtitleRoutingOnly()
                ensureMpvPrimaryContent(state)
            },
        )
    private val progressRecoveryOwnership =
        ProgressRecoveryJobOwner(
            scope = viewModelScope,
            controller = progressRecoveryController,
            currentSeekBinding = { seekSink.state.progressRecoverySeekBinding() },
            requestIsCurrent = ::progressRecoveryRequestIsCurrent,
            executeRetry = { request, terminal ->
                retryPlaybackAt(
                    position = request.targetPositionMs,
                    positionSource = terminal.positionSource,
                )
            },
            observeWindowMs = PROGRESS_RECOVERY_OBSERVE_WINDOW_MS,
        )
    private val segmentCallbackCoordinator =
        PlayerSegmentCallbackCoordinator(playerAccess = playerAccess)
    private var seriesPlaybackTrace: SeriesPlaybackTraceTracker? = null

    val sleepTimerState = sleepTimerController.state

    /** W55：选择睡眠定时分钟；null = 取消。 */
    fun selectSleepTimer(minutes: Int?) = sleepTimerController.select(minutes)

    // Buffering prevents codec-fallback events from being silently dropped.
    private val eventsChannel = Channel<PlayerEvents>(capacity = Channel.BUFFERED)
    val eventsChannelFlow = eventsChannel.receiveAsFlow()

    private var lastFailureHandledKey: String? = null
    private var lastFailureHandledAtMs: Long = 0L

    data class UiState(
        val currentItemTitle: String,
        /** 当前播放条目的 id，供播放页做海报取色等与条目相关的效果 */
        val currentItemId: UUID? = null,
        /** 当前条目的媒体源元数据（§1.8 信息面板；内核侧实测值由面板再合并一层） */
        val currentMediaInfo: PlayerMediaInfo? = null,
        /** 当前播放内核实际读到的流参数；绝不回退 source。 */
        val currentStreamMediaInfo: PlayerMediaInfo? = null,
        val playbackMediaIdentity: PlaybackMediaIdentity? = null,
        val playbackRequestGeneration: Long = 0L,
        val currentSegment: FindroidSegment?,
        val currentSkipButtonStringRes: Int,
        // Trickplay frames are loaded on demand.
        val trickplayIntervalMs: Int = 0,
        val trickplayVersion: Int = 0,
        val currentChapters: List<PlayerChapter>,
        val fileLoaded: Boolean,
        // Segment-chip duration comes from the live preference.
        val skipChipDurationMs: Long = 5_000L,
        val playerError: PlayerErrorInfo? = null,
        /** W86-C：seek 请求的唯一状态；UI 不再根据单次位置采样猜测完成。 */
        val seekState: SeekState = SeekState.Idle,
        /** 播放页 session 的唯一 loading / terminal / play-pause 投影。 */
        val playbackSession: PlaybackSessionUi = PlaybackSessionUi(),
    )

    data class PlaybackSessionUi(
        val pauseWhenReady: Boolean = true,
        val showPlayerError: Boolean = false,
        val showSeekFailure: Boolean = false,
        val loading: Boolean = true,
        val terminal: PlaybackRecovery? = null,
        val progressRecovery: PlaybackProgressRecoveryUi? = null,
        val lastConfirmedPositionMs: Long? = null,
    )

    data class PlaybackProgressRecoveryUi(
        val kind: String,
        val attempt: Int,
        val maxAttempts: Int,
        val exhausted: Boolean,
    )

    data class PlaybackRecovery(
        val kind: String,
        val positionMs: Long,
        val positionSource: PlaybackRetryPositionSource,
        val token: String,
        val cause: String? = null,
    )

    data class PlaybackProgressFallbackHandoff(
        val itemId: UUID,
        val itemKind: String,
        val mediaSourceId: String,
        val positionMs: Long,
    )

    enum class PlaybackRetryPositionSource {
        ENGINE_POSITION,
        USER_SEEK_TARGET_NEW_REQUEST,
        PERSISTED_RESUME,
        START_ZERO,
    }

    /** 播放错误：给控制层的错误卡片用（阶段 1.3） */
    data class PlayerErrorInfo(
        /** 后端给出的说明，可能较长，UI 侧截断显示 */
        val message: String,
        /** 错误码名，例如 ERROR_CODE_DECODING_FAILED */
        val codeName: String,
        /** 出错时使用的内核：exoplayer / mpv */
        val backend: String,
    )

    private data class SourceResolutionIntent(
        val itemId: UUID,
        val itemKind: String,
        val startFromBeginning: Boolean,
        val startPositionMs: Long,
        val playbackSessionId: String,
        val queueEntries: List<PlaybackQueueEntry>,
        val preferredMediaSourceId: String?,
    )

    /** W86-C：Activity / 手势只能提交这些意图，不能直接操作底层播放器位置。 */
    sealed interface SeekIntent {
        data class Absolute(val targetMs: Long) : SeekIntent

        data class Fraction(val fraction: Float) : SeekIntent

        data class Relative(val deltaMs: Long) : SeekIntent

        data class MediaItem(
            val index: Int,
            val positionMs: Long = C.TIME_UNSET,
        ) : SeekIntent

        data class AdjacentMediaItem(val forward: Boolean) : SeekIntent
    }

    /** 字幕来源类别：面板行统一显示内嵌 / 外挂 / 侧载。 */
    enum class SubtitleOrigin {
        Embedded,
        External,
        Sideloaded,
    }

    /** 一行轨道当前的落点；图形 / 无映射原生轨只可能是 [Native]。 */
    enum class SubtitleAssignment {
        Primary,
        Secondary,
        Native,
    }

    /** 交换主次不可用的原因（面板据此禁用并显示原因）。 */
    enum class SubtitleSwapBlock {
        NoPrimary,
        NoSecondary,
        PrimaryNativeOnly,
        SecondaryNativeOnly,
        SameTrack,
    }

    enum class SubtitlePrimaryUnavailableReason {
        NativeSourceIdentityUnavailable
    }

    /** 字幕面板里的一行轨道选项（两个内核共用同一套 UI） */
    data class SubtitleOption(
        /** 行身份：ExoPlayer = 源序号；mpv = 原生 track id。 */
        val id: Int,
        val label: String,
        val language: String,
        val format: String,
        val origin: SubtitleOrigin,
        /** 可交给 Compose / libass 做文本主次分配。 */
        val textAssignable: Boolean,
        /** PGS / VobSub 等图形字幕；只能交给内核原生渲染。 */
        val graphic: Boolean,
        /** W27：本机导入的侧载字幕（面板行尾给「移除」入口）。 */
        val sideloaded: Boolean = false,
        /** 主路动作 id：Exo = 源序号；mpv = 原生 sid；null = 该纯源轨尚未注入 mpv。 */
        val primaryActionId: Int? = null,
        /** 次路动作 id：Exo = 源序号；mpv = 可解析源序号；null = 不可分配。 */
        val secondaryActionId: Int? = null,
        val assignment: SubtitleAssignment? = null,
    )

    /**
     * 字幕面板状态。
     *
     * 两个内核最终都汇总成这一份状态：ExoPlayer 走自研字幕管线（Jellyfin 源清单）， mpv 走 mpv 自己的字幕轨（track id），面板 UI 不需要知道区别。
     */
    data class SubtitlePanelState(
        /** 唯一的全部轨道候选；每行只出现一次。 */
        val tracks: List<SubtitleOption> = emptyList(),
        val delayMs: Long = 0L,
        val appearance: SubtitleAppearance = SubtitleAppearance(),
        /** 主字幕是否由自研渲染接管（决定延迟是否即时生效、能否做双语） */
        val managed: Boolean = false,
        /** 当前媒体里存在可用的文字字幕（能调延迟 / 双语） */
        val controllable: Boolean = false,
        val loading: Boolean = false,
        /** 主字幕仅原生 / 无法映射时，次字幕整体不可分配。 */
        val secondaryAssignmentBlocked: Boolean = false,
        /** 选中图形 / 仅原生源无法唯一绑定 Media3 轨时的明确原因。 */
        val primaryUnavailableReason: SubtitlePrimaryUnavailableReason? = null,
        /** 当前 / 最近一次次字幕请求因图形或缺少 DeliveryUrl 被拒绝的明确原因。 */
        val secondaryUnavailableReason: SubtitleSecondaryUnavailableReason? = null,
        /** 仅当主、次都是不同文本轨且动作 id 可解析时为 null。 */
        val swapBlockedReason: SubtitleSwapBlock? = SubtitleSwapBlock.NoPrimary,
        /** ASS/SSA 落在次字幕时的文本降级提示。 */
        val assSecondaryHint: Boolean = false,
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

    private val seekSink
        get() = playerHolder.seekSink

    /* ---------- W73（#8）：网络类播放错误的原地重试状态 ---------- */

    private var lastNetworkErrorMediaId: String? = null
    private var lastNetworkErrorAtMs = 0L
    private var networkRetryAttempts = 0

    /** 字幕/音轨的智能选择引擎：按语言优先级自动选轨，并记住用户的手动选择 */
    private val trackSelectionEngine = TrackSelectionEngine(appPreferences)

    /** 自研字幕管线：延迟 / 双语 / 外观（ExoPlayer 文字字幕由它接管） */
    val subtitleController = PlayerSubtitleController(viewModelScope, appPreferences)
    private var subtitleOverlayJob: Job? = null

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
    private var lastSubtitleRoutingTrace: String? = null
    private var nativeSubtitleUnavailableReason: SubtitlePrimaryUnavailableReason? = null

    private val _audioPanelState = MutableStateFlow(AudioPanelState())
    val audioPanelState = _audioPanelState.asStateFlow()

    /** 用户在当前媒体里手动选过轨后，不再自动干预 */
    private var manualTrackSelectionMediaId: String? = null

    /** 后台补全播放队列的任务：重新起播时取消重来，避免两个任务同时插队 */
    private var queueFillJob: Job? = null

    /** W76-B11b：整剧队列的后台枚举（先枚举、再 [fillQueueInBackground] 补进播放器） */
    private var queueExpandJob: Job? = null

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

    // Latest known item snapshot used to close outgoing progress.
    private var lastProgressItemId: UUID? = null
    private var lastProgressMediaSourceId: String? = null
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
        playbackSessionJob = viewModelScope.launch {
            while (isActive) {
                refreshPlaybackSession()
                delay(PLAYBACK_SESSION_SAMPLE_INTERVAL_MS)
            }
        }
        viewModelScope.launch {
            seekSink.stateFlow.collect { state ->
                _uiState.update { it.copy(seekState = state) }
                refreshPlaybackSession()
            }
        }
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

        // Reporting continues while background playback continues.
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
        subtitleOverlayJob = viewModelScope.launch {
            subtitleController.overlayState.collect { state ->
                subtitlePublicationCoordinator.publish(state)
            }
        }
    }

    private fun refreshPlaybackSession(nowElapsedMs: Long = SystemClock.elapsedRealtime()) {
        if (!playerAccess.isOpen()) return
        val active = playerHolder.existingPlayer
        val mediaItem = active?.currentMediaItem
        val currentSourceId =
            mediaItem?.mediaMetadata?.extras?.getString(PLAYER_EXTRA_MEDIA_SOURCE_ID)
        val seekState = _uiState.value.seekState
        val explicitSeek = seekState.explicitSeekFields()
        val enginePositionMs = confirmedPosition(active)
        val sessionKey = currentPlaybackSessionKey(active, mediaItem)
        val sourceResolutionFailed =
            _uiState.value.playerError?.codeName == PLAYER_ERROR_SOURCE_RESOLUTION_FAILED
        val playWhenReadyIntent =
            if (startupInProgress || sourceResolutionFailed) {
                playWhenReady
            } else {
                active?.playWhenReady ?: playWhenReady
            }
        val input =
            PlaybackSessionInput(
                key = sessionKey,
                playWhenReady = playWhenReadyIntent,
                isPlaying = active?.isPlaying == true,
                isBuffering =
                    startupInProgress ||
                        active == null ||
                        active.mediaItemCount == 0 ||
                        active.playbackState == Player.STATE_BUFFERING,
                isReady = active?.playbackState == Player.STATE_READY,
                enginePositionMs = enginePositionMs,
                bufferedPositionMs = confirmedBufferedPosition(active, enginePositionMs),
                hasPlayerError = _uiState.value.playerError != null,
                hasSeekFailure = seekState is SeekState.Failed,
                explicitSeekTargetMs = explicitSeek?.first,
                explicitSeekRequestGeneration = explicitSeek?.second,
                explicitSeekSourceEpoch = explicitSeek?.third,
                persistedResumeMs = persistedResumePositionMs(mediaItem?.mediaId, currentSourceId),
            )
        playbackSessionState =
            PlaybackSessionRules.advance(playbackSessionState, input, nowElapsedMs)
        val recoveryTerminal = playbackSessionState.terminal
        progressRecoveryOwnership.retainTerminal(recoveryTerminal, sessionKey)
        val progressFallbackInput =
            progressFallbackInput(
                mediaItem = mediaItem,
                sessionKey = sessionKey,
                playWhenReady = playWhenReadyIntent,
            )
        val progressFallbackRequest = progressFallbackController.evaluate(progressFallbackInput)
        if (progressFallbackRequest != null) {
            val queued =
                eventsChannel.trySend(
                    PlayerEvents.FallbackToExoForProgressStall(progressFallbackRequest.token)
                )
            if (queued.isFailure) progressFallbackController.cancelCurrentGeneration()
        }
        val progressRecoveryInput =
            PlayerProgressRecoveryInput(
                sessionKey = sessionKey,
                mediaIdentity = progressFallbackInput.mediaIdentity,
                targetPositionMs = recoveryTerminal?.positionMs ?: 0L,
                terminalKind = recoveryTerminal?.kind,
                terminalToken = recoveryTerminal?.token,
                playWhenReady = playWhenReadyIntent,
                hasPlayerError = input.hasPlayerError,
                hasSeekFailure = input.hasSeekFailure,
                enginePositionMs = input.enginePositionMs,
                bufferedPositionMs = input.bufferedPositionMs,
                isReady = input.isReady,
                isPlaying = input.isPlaying,
                wasLoading = playbackSessionState.wasLoading,
            )
        val recoveryObservation = progressRecoveryController.observe(progressRecoveryInput)
        if (
            recoveryObservation.phase == PlayerProgressRecoveryPhase.CONFIRMED ||
                recoveryObservation.phase == PlayerProgressRecoveryPhase.CANCELLED
        ) {
            cancelProgressRecoveryOwnership()
        }
        val suppressTerminal = progressFallbackController.suppressesTerminal(sessionKey)
        if (recoveryTerminal != null && !suppressTerminal && !progressRecoveryOwnership.isActive) {
            progressRecoveryController.begin(progressRecoveryInput)?.let {
                launchProgressRecovery(it, recoveryTerminal)
            }
        }
        _uiState.update {
            it.copy(
                playbackSession =
                    PlaybackSessionRules.project(
                            playbackSessionState,
                            input,
                            suppressTerminal = suppressTerminal,
                        )
                        .copy(progressRecovery = progressRecoveryController.status()?.toUi())
            )
        }
        seriesPlaybackTrace?.observePosition(
            positionMs = playbackSessionState.lastEnginePositionMs,
            terminalKind = playbackSessionState.terminal?.kind,
            hasPlayerError = _uiState.value.playerError != null,
        )
    }

    private fun launchProgressRecovery(
        firstRequest: PlayerProgressRecoveryRequest,
        terminal: PlaybackRecovery,
    ) {
        progressRecoveryOwnership.start(firstRequest, terminal)
    }

    private fun cancelProgressRecoveryOwnership() {
        progressRecoveryOwnership.cancel()
    }

    private fun progressRecoveryRequestIsCurrent(
        request: PlayerProgressRecoveryRequest,
        expectedSeekBinding: ProgressRecoverySeekBinding?,
    ): Boolean {
        if (!playerAccess.isOpen() || !progressRecoveryController.isCurrent(request)) {
            return false
        }
        val active = playerHolder.existingPlayer ?: return false
        val mediaItem = active.currentMediaItem ?: return false
        if (currentPlaybackSessionKey(active, mediaItem) != request.sessionKey) return false
        val itemId =
            mediaItem.mediaId.let { raw -> runCatching { UUID.fromString(raw) }.getOrNull() }
                ?: return false
        val mediaSourceId =
            mediaItem.mediaMetadata.extras?.getString(PLAYER_EXTRA_MEDIA_SOURCE_ID) ?: return false
        return progressRecoveryRequestIdentityIsCurrent(
            request = request,
            expectedSeekBinding = expectedSeekBinding,
            currentSessionKey = currentPlaybackSessionKey(active, mediaItem),
            currentMediaIdentity = PlaybackMediaIdentity(itemId, mediaSourceId),
            currentSeekState = seekSink.state,
        )
    }

    private fun PlayerProgressRecoveryStatus.toUi() =
        PlaybackProgressRecoveryUi(
            kind = requireNotNull(terminalKind),
            attempt = attempt,
            maxAttempts = maxAttempts,
            exhausted = phase == PlayerProgressRecoveryPhase.EXHAUSTED,
        )

    private fun progressFallbackInput(
        mediaItem: MediaItem?,
        sessionKey: String?,
        playWhenReady: Boolean,
    ): PlayerProgressFallbackInput {
        val extras = mediaItem?.mediaMetadata?.extras
        val itemId =
            mediaItem?.mediaId?.let { raw -> runCatching { UUID.fromString(raw) }.getOrNull() }
        val mediaSourceId = extras?.getString(PLAYER_EXTRA_MEDIA_SOURCE_ID)
        val sourceItem =
            mediaItem?.mediaId?.let { mediaId ->
                items.firstOrNull { it.itemId.toString() == mediaId }
            }
        val sourceUri =
            extras?.getString(PLAYER_EXTRA_SEEK_SOURCE_URI)?.takeIf(String::isNotBlank)
                ?: mediaItem?.localConfiguration?.uri?.toString()
                ?: sourceItem?.mediaSourceUri
        val terminal = playbackSessionState.terminal
        return PlayerProgressFallbackInput(
            sessionKey = sessionKey,
            mediaIdentity = playbackMediaIdentity(itemId, mediaSourceId),
            sourceKind =
                playerProgressFallbackSourceKind(
                    sourceUri = sourceUri,
                    container = sourceItem?.mediaInfo?.container,
                    mimeType = mediaItem?.localConfiguration?.mimeType,
                ),
            backend = playerBackend,
            autoFallbackEnabled = appPreferences.getValue(appPreferences.playerAutoFallback),
            playWhenReady = playWhenReady,
            terminalKind = terminal?.kind,
            terminalToken = terminal?.token,
            positionMs = terminal?.positionMs ?: -1L,
        )
    }

    private fun currentPlaybackSessionKey(player: Player?, mediaItem: MediaItem?): String? =
        mediaItem?.let(::seekMediaIdentityFromItem)?.let {
            playbackSessionKeyFor(
                it,
                playbackSnapshots.generation,
                (player as? MPVPlayer)?.currentSourceEpoch(),
            )
        }

    private fun persistedResumePositionMs(
        itemId: String?,
        mediaSourceId: String?,
    ): Long? {
        val currentItemId =
            itemId?.let { raw -> runCatching { UUID.fromString(raw) }.getOrNull() } ?: return null
        if (
            currentItemId != lastProgressItemId ||
                mediaSourceId != lastProgressMediaSourceId ||
                lastProgressPositionMs <= 0L
        ) {
            return null
        }
        return lastProgressPositionMs
    }

    private fun publishPlaybackSnapshots() {
        val snapshots = playbackSnapshots
        _uiState.update {
            it.copy(
                currentMediaInfo = snapshots.sourceMediaInfo,
                currentStreamMediaInfo = snapshots.currentStreamMediaInfo,
                playbackMediaIdentity = snapshots.identity,
                playbackRequestGeneration = snapshots.generation,
            )
        }
        refreshPlaybackSession()
    }

    /** 新请求开始：清空 source / current，并返回单调递增的代次。 */
    private fun resetPlaybackSnapshots(): Long {
        val generation = playbackSnapshots.generation + 1L
        playbackSnapshots = PlaybackSnapshotState(generation = generation)
        activePlaybackToken = null
        publishPlaybackSnapshots()
        return generation
    }

    private fun beginMediaSnapshot(identity: PlaybackMediaIdentity): PlaybackRequestToken {
        val (snapshots, token) = beginPlaybackRequest(playbackSnapshots, identity)
        playbackSnapshots = snapshots
        activePlaybackToken = token
        publishPlaybackSnapshots()
        return token
    }

    private fun ensureMediaSnapshot(identity: PlaybackMediaIdentity): PlaybackRequestToken {
        val active = activePlaybackToken
        if (
            active != null &&
                active.identity == identity &&
                playbackSnapshots.identity == identity &&
                playbackSnapshots.generation == active.generation
        ) {
            return active
        }
        return beginMediaSnapshot(identity)
    }

    /** 切轨 / 内核轨道集变化：保留 source，只清旧 current 并让旧异步结果失效。 */
    private fun advanceCurrentStreamGeneration(): PlaybackRequestToken? {
        val (snapshots, token) = advanceCurrentStreamRequest(playbackSnapshots)
        playbackSnapshots = snapshots
        if (token == null) return null
        activePlaybackToken = token
        publishPlaybackSnapshots()
        return token
    }

    private fun applySourceSnapshot(token: PlaybackRequestToken, source: PlayerMediaInfo?) {
        playbackSnapshots = bindSourceSnapshot(playbackSnapshots, token, source)
        publishPlaybackSnapshots()
    }

    private fun applyCurrentStreamSnapshot(
        token: PlaybackRequestToken,
        current: PlayerMediaInfo?,
    ) {
        playbackSnapshots = acceptCurrentStreamSnapshot(playbackSnapshots, token, current)
        publishPlaybackSnapshots()
    }

    private fun refreshCurrentStreamSnapshot(token: PlaybackRequestToken) {
        if (!playerAccess.isOpen()) return
        val activePlayer = playerAccess.existingPlayer() ?: return
        if (activePlayer !is MPVPlayer) {
            // ExoPlayer.currentTracks 是主线程状态；放到 IO 读取会拿到空 / 旧选中态。
            applyCurrentStreamSnapshot(
                token,
                runCatching { readKernelMediaInfo(activePlayer) }.getOrNull(),
            )
            return
        }
        viewModelScope.launch {
            val info =
                withContext(Dispatchers.IO) {
                    runCatching { readKernelMediaInfo(activePlayer) }.getOrNull()
                }
            if (!playerAccess.isOpen()) return@launch
            applyCurrentStreamSnapshot(token, info)
        }
    }

    fun initializePlayer(
        itemId: UUID,
        itemKind: String,
        startFromBeginning: Boolean,
        startPositionMs: Long = 0L,
        playbackSessionId: String,
        queueEntries: List<PlaybackQueueEntry> = emptyList(),
        preferredMediaSourceId: String? = null,
    ) {
        if (explicitExitController.isExited) return
        val sourceIntent =
            SourceResolutionIntent(
                itemId = itemId,
                itemKind = itemKind,
                startFromBeginning = startFromBeginning,
                startPositionMs = startPositionMs,
                playbackSessionId = playbackSessionId,
                queueEntries = queueEntries.toList(),
                preferredMediaSourceId = preferredMediaSourceId,
            )
        failedSourceIntent = null
        _uiState.update { it.copy(playerError = null) }
        seriesPlaybackTrace = null
        playbackStartupJob?.cancel()
        queueExpandJob?.cancel()
        queueFillJob?.cancel()
        cancelProgressRecoveryOwnership()
        // Release any music session before touching the shared player instance.
        playbackCoordinator.onVideoStartRequested()
        // Decode fallback and speed are scoped to the playback session.
        if (prepareDecodeFallback(playbackSessionId)) {
            playbackSpeed = speedForPlaybackSession(playbackSpeed, isNewSession = true)
            player.setPlaybackSpeed(playbackSpeed)
        }
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
        // 新播放页 / 新码率 / 新内核请求：旧 source 与 current 立即失效。
        val initializationGeneration = resetPlaybackSnapshots()
        val exitGeneration = explicitExitController.generation
        player.addListener(this)
        applySavedSubtitlePreferences()
        syncMpvSubtitleMode()
        publishSubtitlePanelState()

        if (startPositionMs > 0L) {
            playbackPosition = startPositionMs
        }

        playbackStartupJob = viewModelScope.launch {
            var sourceFailure: PlayerErrorInfo? = null
            val startItem =
                try {
                    runPlaybackStartupRequest {
                        withTimeout(SOURCE_RESOLUTION_TIMEOUT_MS) {
                            if (queueEntries.isNotEmpty()) {
                                playlistManager.getInitialItemForQueue(
                                    entries = queueEntries,
                                    preferredItemId = itemId,
                                    preferredMediaSourceId = preferredMediaSourceId,
                                    startFromBeginning = startFromBeginning,
                                    startPositionMs = startPositionMs.takeIf { it > 0L },
                                )
                            } else {
                                playlistManager.getInitialItem(
                                    itemId = itemId,
                                    itemKind = BaseItemKind.fromName(itemKind),
                                    mediaSourceIndex = null,
                                    preferredMediaSourceId = preferredMediaSourceId,
                                    startFromBeginning = startFromBeginning,
                                    startPositionMs = startPositionMs.takeIf { it > 0L },
                                )
                            }
                        }
                    }
                } catch (e: TimeoutCancellationException) {
                    if (!isActive || !explicitExitController.isCurrent(exitGeneration)) {
                        return@launch
                    }
                    Timber.w(e, "Playback source resolution timed out")
                    sourceFailure = sourceResolutionFailure()
                    null
                } catch (e: CancellationException) {
                    // W76-B11b：起播窗口内退出播放页 = 正常取消，不是解析失败。
                    // 旧实现让 CancellationException 落进下面的通用 catch → 给用户弹一句英文 `Job was cancelled`。
                    throw e
                } catch (e: Exception) {
                    // W76-B11b：协程已被取消时，网络层可能抛出「取消的副产品」而不是 CancellationException
                    // （连接被中断的 IOException 等）。离开播放页时同样不该把它当成解析失败弹提示。
                    if (!isActive || !explicitExitController.isCurrent(exitGeneration)) {
                        return@launch
                    }
                    Timber.e(e)
                    sourceFailure = sourceResolutionFailure()
                    null
                }

            if (startItem == null) {
                Timber.e("No start item, stopping player initialization")
                if (explicitExitController.isCurrent(exitGeneration)) {
                    startupInProgress = false
                    failedSourceIntent = sourceIntent
                    _uiState.update {
                        it.copy(playerError = sourceFailure ?: sourceResolutionFailure())
                    }
                    refreshPlaybackSession()
                }
                return@launch
            }
            failedSourceIntent = null
            if (!isCurrentPlaybackStartup(exitGeneration, initializationGeneration)) {
                Timber.d("Discard stale player initialization after generation advanced")
                return@launch
            }

            if (queueEntries.isEmpty() && BaseItemKind.fromName(itemKind) == BaseItemKind.SERIES) {
                playlistManager.lastSeriesResolutionTraceContext?.let { context ->
                    seriesPlaybackTrace =
                        SeriesPlaybackTraceTracker(
                            traceId = context.traceId,
                            backend = seriesBackendKind(playerBackend),
                            emit = SeriesResolutionTraceLog::emit,
                        )
                }
            }

            val identity =
                PlaybackMediaIdentity(
                    itemId = startItem.itemId,
                    mediaSourceId = startItem.mediaSourceId,
                )
            if (!isCurrentPlaybackStartup(exitGeneration, initializationGeneration)) {
                return@launch
            }
            val sourceToken = beginMediaSnapshot(identity)
            applySourceSnapshot(sourceToken, startItem.mediaInfo)

            items = listOfNotNull(startItem).toMutableList()
            currentMediaItemIndex = items.indexOf(startItem)

            val mediaItems = mutableListOf<MediaItem>()
            try {
                for (item in items) {
                    mediaItems.add(item.toMediaItem(application))
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

            if (!isCurrentPlaybackStartup(exitGeneration, initializationGeneration)) {
                return@launch
            }
            seekSink.observeSource(mediaItems.firstOrNull())
            player.setMediaItems(mediaItems, 0, startPosition)
            // W74 U2-B（D-F8）：进播放页 / 重建播放器实例时套用保存的循环 / 随机模式。
            // 必须在 setMediaItems 之后：媒体项集齐才会生成 shuffle 顺序，随机档才能真正排到队列上。
            applySavedPlaybackMode()
            player.prepare()
            player.play()
            // 媒体已交给播放器：之后的 playWhenReady 变化都算用户 / 系统意图，恢复正常回存
            startupInProgress = false
            // 起播之后再补全整剧队列（用户反馈：队列面板只显示当前一集）
            fillQueueInBackgroundAfterSeriesExpansion()
        }
    }

    private fun isCurrentPlaybackStartup(
        exitGeneration: Long,
        initializationGeneration: Long,
    ): Boolean =
        isActivePlaybackGeneration(exitGeneration) &&
            playbackSnapshots.generation == initializationGeneration

    private fun sourceResolutionFailure(): PlayerErrorInfo =
        PlayerErrorInfo(
            message = application.getString(R.string.player_source_resolution_failed),
            codeName = PLAYER_ERROR_SOURCE_RESOLUTION_FAILED,
            backend = playerBackend,
        )

    private fun isActivePlaybackGeneration(exitGeneration: Long): Boolean =
        !explicitExitController.isExited && explicitExitController.isCurrent(exitGeneration)

    // Expand series metadata after playback starts, then refill from the new queue size.
    private fun fillQueueInBackgroundAfterSeriesExpansion() {
        if (explicitExitController.isExited) return
        val pending = playlistManager.pendingSeriesQueueExpansion
        if (pending == null) {
            // 电影 / 显式队列播放：队列在起播前就是全的
            fillQueueInBackground()
            return
        }

        queueExpandJob?.cancel()
        val exitGeneration = explicitExitController.generation
        queueExpandJob =
            viewModelScope.launch(Dispatchers.IO) {
                val expanded =
                    try {
                        playlistManager.expandPendingSeriesQueue()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // 整剧补不出来不影响已经起播的这一集：保持单条队列，只留痕
                        Timber.w(e, "整剧队列补全失败，保持单条队列（series=%s）", pending.seriesId)
                        false
                    }
                if (expanded && isActive && isActivePlaybackGeneration(exitGeneration)) {
                    fillQueueInBackground()
                }
            }
    }

    // Restore background playback state without overwriting the startup intent window.
    fun restorePlayWhenReady() {
        if (startupInProgress) return
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

    // Fill the queue off the main thread with bounded insertion batches.
    private fun fillQueueInBackground() {
        if (explicitExitController.isExited) return
        queueFillJob?.cancel()
        val exitGeneration = explicitExitController.generation
        queueFillJob =
            viewModelScope.launch(Dispatchers.IO) {
                val (currentIndex, queueSize) =
                    withContext(Dispatchers.Main) {
                        playlistManager.queueIndex to playlistManager.queueSize
                    }
                if (queueSize <= 1 || currentIndex < 0) return@launch

                var added = 0
                var stoppedAtLimit = false

                for (index in (currentIndex + 1) until queueSize) {
                    if (!isActivePlaybackGeneration(exitGeneration)) return@launch
                    // 用户手动整理过队列（排序 / 删除 / 清空）：不再把条目补回来
                    if (queueManuallyEdited) return@launch
                    if (added >= MAX_QUEUE_FILL_ITEMS) {
                        stoppedAtLimit = true
                        break
                    }
                    val item = playlistManager.buildPlayerItemAt(index) ?: continue
                    if (!isActive) return@launch
                    if (insertQueueItem(item, atFront = false, exitGeneration = exitGeneration))
                        added++
                    delay(QUEUE_FILL_STEP_DELAY_MS)
                }
                if (!stoppedAtLimit) {
                    for (index in (currentIndex - 1) downTo 0) {
                        if (!isActivePlaybackGeneration(exitGeneration)) return@launch
                        if (queueManuallyEdited) return@launch
                        if (added >= MAX_QUEUE_FILL_ITEMS) {
                            stoppedAtLimit = true
                            break
                        }
                        val item = playlistManager.buildPlayerItemAt(index) ?: continue
                        if (!isActive) return@launch
                        if (insertQueueItem(item, atFront = true, exitGeneration = exitGeneration))
                            added++
                        delay(QUEUE_FILL_STEP_DELAY_MS)
                    }
                }

                if (!isActivePlaybackGeneration(exitGeneration)) return@launch
                val total =
                    withContext(Dispatchers.Main) {
                        if (!isActivePlaybackGeneration(exitGeneration)) return@withContext null
                        playerAccess.existingPlayer()?.mediaItemCount
                    } ?: return@launch
                if (stoppedAtLimit) {
                    Timber.w("播放队列补全达上限：本次新增 %d 项（播放器共 %d 项）", added, total)
                } else {
                    Timber.d("播放队列补全完成：本次新增 %d 项（播放器共 %d 项）", added, total)
                }
                // W76-Q6：请求瘦身取证——本次播放页实际打了几次 PlaybackInfo、省掉几次（整剧逐集 = queueSize 次）
                val stats = playlistManager.playbackRequestStats()
                Timber.d(
                    "PlaybackInfo 统计：实际请求 %d 次、复用缓存 %d 次（队列 %d 项）",
                    stats.requests,
                    stats.hits,
                    queueSize,
                )
            }
    }

    // Deduplicate against the live player timeline before inserting on the main thread.
    private suspend fun insertQueueItem(
        item: PlayerItem,
        atFront: Boolean,
        exitGeneration: Long,
    ): Boolean =
        withContext(Dispatchers.Main) {
            if (!isActivePlaybackGeneration(exitGeneration)) return@withContext false
            val activePlayer = playerAccess.existingPlayer() ?: return@withContext false
            val mediaId = item.itemId.toString()
            if (hasMediaItem(activePlayer, mediaId)) return@withContext false
            // 显式给下标：不依赖 BasePlayer 对"无下标 addMediaItem"的封装（mpv 内核上它传过越界值）
            val index = if (atFront) 0 else activePlayer.mediaItemCount
            activePlayer.addMediaItem(index, item.toMediaItem(application))
            rememberQueueItem(item)
            true
        }

    /** 播放器当前时间线里是否已有这个 mediaId。只在主线程调用。 */
    private fun hasMediaItem(activePlayer: Player, mediaId: String): Boolean =
        (0 until activePlayer.mediaItemCount).any {
            activePlayer.getMediaItemAt(it).mediaId == mediaId
        }

    private fun rememberQueueItem(item: PlayerItem) {
        if (items.none { it.itemId == item.itemId }) {
            items.add(item)
        }
    }

    // Attach without replacing media items or releasing an active music session.
    fun attachToExistingSession() {
        player.addListener(this)
        seekSink.observeSource(player.currentMediaItem)
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
        val activePlayer = playerAccess.existingPlayer() ?: return
        val mediaItem = activePlayer.currentMediaItem ?: return
        val itemId = runCatching { UUID.fromString(mediaItem.mediaId) }.getOrNull()
        val extras = mediaItem.mediaMetadata.extras
        val mediaSourceId = extras?.getString(PLAYER_EXTRA_MEDIA_SOURCE_ID)
        val identity = playbackMediaIdentity(itemId, mediaSourceId)
        if (identity == null) {
            resetPlaybackSnapshots()
        } else {
            val token = ensureMediaSnapshot(identity)
            @Suppress("DEPRECATION")
            val sourceInfo = extras?.getParcelable(PLAYER_EXTRA_MEDIA_INFO) as? PlayerMediaInfo
            applySourceSnapshot(token, sourceInfo)
        }
        val season = extras?.getInt(PLAYER_EXTRA_SEASON_NUMBER, -1) ?: -1
        val episode = extras?.getInt(PLAYER_EXTRA_EPISODE_NUMBER, -1) ?: -1
        val name = mediaItem.mediaMetadata.title?.toString().orEmpty()
        val title = if (season >= 0 && episode >= 0) "S$season:E$episode - $name" else name
        _uiState.update {
            it.copy(
                currentItemTitle = title,
                currentItemId = itemId,
            )
        }
    }

    /**
     * 用户明确从视频页返回时唯一允许的收尾入口。后台开关只影响 HOME / 锁屏， 不能覆盖明确的视频退出。
     *
     * @return Activity 是否应停止视频拥有的前台播放服务。音乐会话永远由音乐链路保留。
     */
    fun onExplicitVideoExit(): Boolean {
        if (explicitExitController.begin() == null) return false
        cancelPlaybackWork(cancelSessionSampler = true)
        val action =
            playerExitAction(
                musicSessionActive = isMusicSessionActive,
                explicitVideoExit = true,
                backgroundAudioEnabled =
                    appPreferences.getValue(appPreferences.playerBackgroundAudio),
            )
        val completed =
            playbackResourcesGate.run(
                detachVideoOutput = {},
                finalizePlayback = { finalizePlaybackSession(action) },
            )
        return completed &&
            shouldStopPlaybackServiceOnExit(
                musicSessionActive = isMusicSessionActive,
                explicitVideoExit = true,
                backgroundAudioEnabled =
                    appPreferences.getValue(appPreferences.playerBackgroundAudio),
            )
    }

    @OptIn(DelicateCoroutinesApi::class)
    private fun releasePlayer() {
        if (playbackResourcesGate.isCompleted) return
        cancelPlaybackWork(cancelSessionSampler = false)
        val action =
            playerExitAction(
                musicSessionActive = isMusicSessionActive,
                explicitVideoExit = explicitExitController.isExited,
                backgroundAudioEnabled =
                    appPreferences.getValue(appPreferences.playerBackgroundAudio),
            )
        playbackResourcesGate.run(
            detachVideoOutput = {},
            finalizePlayback = { finalizePlaybackSession(action) },
        )
    }

    @OptIn(DelicateCoroutinesApi::class)
    private fun finalizePlaybackSession(action: PlayerExitAction) {
        val activePlayer = playerHolder.existingPlayer
        val mediaId = activePlayer?.currentMediaItem?.mediaId
        val position = playbackSessionState.lastEnginePositionMs ?: confirmedPosition(activePlayer)
        val duration = activePlayer?.duration ?: C.TIME_UNSET
        val itemId = mediaId?.let { raw -> runCatching { UUID.fromString(raw) }.getOrNull() }
        // Only the page-owned session reports a stop; attached music remains music-owned.
        if (
            action != PlayerExitAction.PRESERVE_MUSIC &&
                action != PlayerExitAction.PRESERVE_BACKGROUND_VIDEO &&
                shouldReportStopOnPlayerExit(playerHolder.isCurrentItemMusic) &&
                itemId != null &&
                position != null &&
                duration != C.TIME_UNSET
        ) {
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
        playbackStartupJob?.cancel()
        playbackStartupJob = null
        startupInProgress = false
        playerHolder.existingPlayer?.removeListener(this)
        if (
            action != PlayerExitAction.STOP_AND_RELEASE &&
                action != PlayerExitAction.PAUSE_FOREGROUND_ONLY
        ) {
            // Background playback keeps the instance alive; persist only the position.
            position?.let { savedStateHandle["position"] = it }
        } else {
            activePlayer?.playWhenReady = false
            runCatching { activePlayer?.stop() }
            runCatching { activePlayer?.clearMediaItems() }
            playerHolder.release()
        }
    }

    private fun cancelPlaybackWork(cancelSessionSampler: Boolean) {
        subtitleOverlayJob?.cancel()
        subtitleOverlayJob = null
        playbackStartupJob?.cancel()
        playbackStartupJob = null
        queueExpandJob?.cancel()
        queueExpandJob = null
        queueFillJob?.cancel()
        queueFillJob = null
        progressFallbackController.cancelCurrentGeneration()
        cancelProgressRecoveryOwnership()
        if (cancelSessionSampler) {
            playbackSessionJob?.cancel()
            playbackSessionJob = null
        }
    }

    fun updatePlaybackProgress() {
        if (!playerAccess.isOpen()) return
        val player = playerHolder.existingPlayer ?: return
        val mediaItem = player.currentMediaItem ?: return
        val itemId = runCatching { UUID.fromString(mediaItem.mediaId) }.getOrNull() ?: return
        refreshPlaybackSession()
        val position = playbackSessionState.lastEnginePositionMs ?: return
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
        // Persist UserData directly so abrupt exits retain the latest position.
        viewModelScope.launch {
            playbackPositionWriter.writePosition(itemId, position.times(10000))
        }
    }

    /** 记录「已知的当前条目位置」，供切集 / 退出时给上一条补停止上报（W20） */
    private fun recordProgressSnapshot(itemId: UUID, positionMs: Long, durationMs: Long) {
        lastProgressItemId = itemId
        lastProgressMediaSourceId =
            playerHolder.existingPlayer
                ?.currentMediaItem
                ?.mediaMetadata
                ?.extras
                ?.getString(PLAYER_EXTRA_MEDIA_SOURCE_ID)
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

    // Close the outgoing item from its progress snapshot, with stop deduplication.
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

    fun updateCurrentSegment(): Boolean {
        if (!playerAccess.isOpen()) return false
        // 页内改过「跳过片头片尾」设置就现读一次（§1.9：不重开播放页也生效）
        refreshSegmentPreferences()
        // 没有片段数据、或两个开关都关：收起按钮直接返回（每秒一次的轮询不该刷日志 / 干活）
        if (currentMediaItemSegments.isEmpty() || (!segmentsSkipButton && !segmentsAutoSkip)) {
            if (_uiState.value.currentSegment != null) {
                _uiState.update { it.copy(currentSegment = null) }
            }
            return true
        }
        viewModelScope.launch(Dispatchers.Main) {
            segmentCallbackCoordinator.runIfActive { activePlayer ->
                val milliSeconds = activePlayer.currentPosition

                // Get current segment, - 100 milliseconds to avoid showing button after segment
                // ends
                val currentSegment = currentMediaItemSegments.find { segment ->
                    milliSeconds in segment.startTicks..<(segment.endTicks - 100L)
                }

                if (currentSegment == null) {
                    // Remove button if not pressed and there is no current segment
                    if (_uiState.value.currentSegment != null) {
                        _uiState.update { it.copy(currentSegment = null) }
                    }
                    return@runIfActive
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
        return true
    }

    // Re-evaluate automatic tracks on every callback unless the user selected manually.
    override fun onTracksChanged(tracks: Tracks) {
        if (!playerAccess.isOpen()) return
        val activePlayer = playerAccess.existingPlayer() ?: return
        val currentToken = advanceCurrentStreamGeneration()
        if (activePlayer !is ExoPlayer) {
            // Refresh mpv subtitle and audio panels, then inject pending sideloads.
            ensureMpvSideloadsInjected(tracks)
            publishSubtitlePanelState()
            publishAudioPanelState()
            applyMpvManualSubtitleSelection()
            // 双字幕期间主字幕改由 App 侧 libass 接管：轨道就绪后按实际 sid 补一次内容，
            // 覆盖「次字幕先于轨道列表加载」的顺序。
            ensureMpvPrimaryContent(subtitleController.overlayState.value)
            currentToken?.let(::refreshCurrentStreamSnapshot)
            return
        }
        val mediaId = activePlayer.currentMediaItem?.mediaId ?: return
        if (manualTrackSelectionMediaId != mediaId) {
            // 自动选轨 + 字幕路由：一次算完再设置，避免参数来回变化
            applyAutoSelectAndRoute(tracks)
        } else {
            // 用户手动选过轨：音轨保持现状，只同步字幕路由
            applySubtitleRoutingOnly()
        }
        publishAudioPanelState()
        currentToken?.let(::refreshCurrentStreamSnapshot)
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        if (!playerAccess.isOpen()) return
        val activePlayer = playerAccess.existingPlayer() ?: return
        Timber.d("Playing MediaItem: ${mediaItem?.mediaId}")
        // 条目 / 媒体源一变化先清空旧快照；后续只有同一 identity 的结果能重新填入。
        refreshUiStateFromPlayer()
        refreshPlaybackSession()
        // Preserve session speed across queue transitions on both engines.
        activePlayer.setPlaybackSpeed(playbackSpeed)
        // W20（§1.11）：先把「上一条」的进度收尾（补 stop），再按新条目走后续流程
        reportOutgoingItemStop()
        // mpv 换集后重新对齐字幕开关（off → sid=no；auto / always → 按 slang 重选）
        syncMpvSubtitleMode()
        savedStateHandle["mediaItemIndex"] = activePlayer.currentMediaItemIndex
        viewModelScope.launch {
            if (!playerAccess.isOpen()) return@launch
            val callbackPlayer = playerAccess.existingPlayer() ?: return@launch
            try {
                val item = items.firstOrNull {
                    it.itemId.toString() == callbackPlayer.currentMediaItem?.mediaId
                }
                if (item == null) {
                    // Recover title metadata when the page attached without local queue items.
                    refreshUiStateFromPlayer()
                    // 字幕源清单跟着播放器里的媒体 id 走：能从 PlaylistManager 找回就恢复
                    callbackPlayer.currentMediaItem?.mediaId?.let { mediaId ->
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
                    val identity =
                        PlaybackMediaIdentity(
                            itemId = item.itemId,
                            mediaSourceId = item.mediaSourceId,
                        )
                    val sourceToken = ensureMediaSnapshot(identity)
                    applySourceSnapshot(sourceToken, item.mediaInfo)
                    _uiState.update {
                        it.copy(
                            currentItemTitle = itemTitle,
                            currentItemId = item.itemId,
                            currentSegment = null,
                            currentChapters = item.chapters,
                            fileLoaded = false,
                        )
                    }

                    val continueCurrentItem: suspend () -> Unit = continueCurrentItem@{
                        if (
                            !playerAccess.runIfOpen {
                                // W20（§1.11）：Trickplay 改为按需加载——这里只准备加载器，不拉任何图
                                prepareTrickplay(item)
                                playlistManager.setCurrentMediaItemIndex(item.itemId)
                            }
                        ) {
                            return@continueCurrentItem
                        }

                        // W76-B11b：这两个插入没有历史去重（后台补片可能已经插过同一条），会让队列面板出现重复条目
                        var previousItem: PlayerItem? = null
                        if (
                            !postSuspendCallbackCoordinator.run(
                                suspendWork = {
                                    previousItem = playlistManager.getPreviousPlayerItem()
                                },
                                continuation = {
                                    previousItem?.let { itemToInsert ->
                                        if (
                                            !hasMediaItem(
                                                callbackPlayer,
                                                itemToInsert.itemId.toString(),
                                            )
                                        ) {
                                            items.add(
                                                callbackPlayer.currentMediaItemIndex,
                                                itemToInsert,
                                            )
                                            callbackPlayer.addMediaItem(
                                                callbackPlayer.currentMediaItemIndex,
                                                itemToInsert.toMediaItem(application),
                                            )
                                        }
                                    }
                                },
                            )
                        ) {
                            return@continueCurrentItem
                        }

                        var nextItem: PlayerItem? = null
                        if (
                            !postSuspendCallbackCoordinator.run(
                                suspendWork = { nextItem = playlistManager.getNextPlayerItem() },
                                continuation = {
                                    nextItem?.let { itemToInsert ->
                                        if (
                                            !hasMediaItem(
                                                callbackPlayer,
                                                itemToInsert.itemId.toString(),
                                            )
                                        ) {
                                            items.add(
                                                callbackPlayer.currentMediaItemIndex + 1,
                                                itemToInsert,
                                            )
                                            callbackPlayer.addMediaItem(
                                                callbackPlayer.currentMediaItemIndex + 1,
                                                itemToInsert.toMediaItem(application),
                                            )
                                        }
                                    }
                                },
                            )
                        ) {
                            return@continueCurrentItem
                        }

                        Timber.tag("PlayerItems").d(items.map { it.indexNumber }.toString())
                    }
                    if (segmentsSkipButton || segmentsAutoSkip) {
                        if (
                            !postSuspendCallbackCoordinator.run(
                                suspendWork = { repository.postPlaybackStart(item.itemId) },
                                continuation = {},
                            )
                        ) {
                            return@launch
                        }
                        if (!getSegments(item.itemId)) {
                            return@launch
                        }
                        continueCurrentItem()
                    } else if (
                        !postSuspendCallbackCoordinator.run(
                            suspendWork = { repository.postPlaybackStart(item.itemId) },
                            continuation = continueCurrentItem,
                        )
                    ) {
                        return@launch
                    }
                }
            } catch (e: Exception) {
                Timber.e(e)
            }
        }
    }

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        if (!playerAccess.isOpen()) return
        val activePlayer = playerAccess.existingPlayer() ?: return
        if (!startupInProgress) this.playWhenReady = playWhenReady
        refreshPlaybackSession()
        if (playWhenReady || startupInProgress) return
        // Persist progress immediately on pause.
        if (!PlaybackSessionRules.advancesToNextItem(reason)) {
            updatePlaybackProgress()
            return
        }
        // Report playback stopped for current item and transition to the next one
        // END reason is authoritative even if playbackState is still buffering.
        Timber.d(
            "end of media item: state=${activePlayer.playbackState} repeat=${activePlayer.repeatMode} " +
                "shuffle=${activePlayer.shuffleModeEnabled} index=${activePlayer.currentMediaItemIndex}/" +
                "${activePlayer.mediaItemCount} hasNext=${activePlayer.hasNextMediaItem()}"
        )
        viewModelScope.launch {
            if (!playerAccess.isOpen()) return@launch
            val callbackPlayer = playerAccess.existingPlayer() ?: return@launch
            val mediaId = callbackPlayer.currentMediaItem?.mediaId
            val position = playbackSessionState.lastEnginePositionMs
            val duration = callbackPlayer.duration
            val itemId = mediaId?.let { raw -> runCatching { UUID.fromString(raw) }.getOrNull() }
            val reportPlaybackStop: suspend () -> Unit
            if (itemId != null && position != null) {
                markStopReported(itemId)
                recordProgressSnapshot(itemId, position, duration)
                reportPlaybackStop = {
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
            } else {
                reportPlaybackStop = {}
            }
            if (
                !postSuspendCallbackCoordinator.run(
                    suspendWork = reportPlaybackStop,
                    continuation = { advanceAfterItemEnd(callbackPlayer) },
                )
            ) {
                return@launch
            }
        }
    }

    // Apply loop, shuffle, auto-next and stay-at-end policy after an authoritative end.
    private fun advanceAfterItemEnd(activePlayer: Player) {
        val from = activePlayer.currentMediaItemIndex
        val repeatMode = activePlayer.repeatMode
        val autoNext = appPreferences.getValue(PlayerExtraPreferences.autoNextEpisode)
        when (
            PlayerEndBehavior.itemEndAction(
                repeatMode = repeatMode,
                hasNextMediaItem = activePlayer.hasNextMediaItem(),
                autoNextEpisode = autoNext,
            )
        ) {
            PlayerItemEndAction.LOOP_CURRENT -> activePlayer.seekTo(from, 0L)
            PlayerItemEndAction.PLAY_NEXT -> {
                // hasNextMediaItem() 已经把列表循环与随机算进去了：循环/随机在队列末尾会绕回第一集
                activePlayer.seekToNextMediaItem()
            }
            PlayerItemEndAction.STAY_AT_END -> {
                // Auto-next off always stays at the current end frame.
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
                "shuffle=${activePlayer.shuffleModeEnabled} -> index=${activePlayer.currentMediaItemIndex}"
        )
        activePlayer.play()
    }

    /** 队列走到尽头时的兜底：单集循环回到本集开头，列表循环/随机回第一集 */
    private fun restartAtQueueEnd(activePlayer: Player) {
        val index =
            if (activePlayer.repeatMode == Player.REPEAT_MODE_ONE) {
                activePlayer.currentMediaItemIndex
            } else {
                0
            }
        activePlayer.seekTo(index, 0L)
        activePlayer.play()
    }

    // Migrate the legacy pause-after-item preference once.
    private fun migrateEndBehaviorPreferences() {
        val prefs = appPreferences.sharedPreferences
        val autoNextKey = PlayerExtraPreferences.autoNextEpisode.backendName
        val legacyKey = PlayerExtraPreferences.pauseAfterCurrentItem.backendName
        if (!prefs.contains(autoNextKey) && prefs.getBoolean(legacyKey, false)) {
            prefs.edit().putBoolean(autoNextKey, false).putBoolean(legacyKey, false).apply()
            Timber.d("W27 迁移：播完暂停 → 关闭自动下一集")
        }
    }

    override fun onRenderedFirstFrame() {
        if (!playerAccess.isOpen()) return
        seriesPlaybackTrace?.recordFirstFrame()
    }

    override fun onPlaybackStateChanged(state: Int) {
        if (!playerAccess.isOpen()) return
        val activePlayer = playerAccess.existingPlayer() ?: return
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
                // Fallback for an end frame that did not trigger the normal transition.
                if (activePlayer.repeatMode == Player.REPEAT_MODE_OFF) {
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
                    restartAtQueueEnd(activePlayer)
                }
            }
        }
        Timber.d("Changed player state to $stateString")
        refreshPlaybackSession()
    }

    // Surface errors only after recovery paths decline ownership.
    override fun onPlayerError(error: PlaybackException) {
        playerAccess.runIfOpen { handlePlayerError(error) }
    }

    private fun handlePlayerError(error: PlaybackException) {
        Timber.e(error, "Player error on backend=$playerBackend: ${error.errorCodeName}")
        seriesPlaybackTrace?.recordEngineError(error.errorCodeName)
        // Retry network errors in place instead of restarting the page.
        if (isNetworkOrSourceError(error)) {
            if (handleTransientNetworkError(error)) return
        } else {
            // A handled fallback keeps the error card hidden.
            if (handleCodecFallback(error)) return
        }
        seekSink.onEngineError(mediaIdentity = seekMediaIdentity(), retryable = true)
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
        refreshPlaybackSession()
    }

    // Retry transient network failures once or reopen a transcode session.
    private fun handleTransientNetworkError(error: PlaybackException): Boolean {
        if (!isNetworkOrSourceError(error)) return false
        if (!playerAccess.isOpen()) return false
        val activePlayer = playerAccess.existingPlayer() ?: return false
        val mediaId = activePlayer.currentMediaItem?.mediaId ?: return false
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
        if (!playerAccess.isOpen()) return false
        val position =
            (seekSink.state as? SeekState.Seeking)?.targetMs
                ?: currentResumePositionMs(activePlayer)
        val item = items.firstOrNull { it.itemId.toString() == mediaId }
        val isTranscode = item != null && isTranscodeStreamUri(item.mediaSourceUri)
        val restartTranscodeSession =
            isTranscode && playerBackend == PLAYER_BACKEND_EXOPLAYER && networkRetryAttempts > 1
        val seekLease = seekSink.coordinator.captureRequestLease()
        if (restartTranscodeSession && seekLease != null) {
            if (!playerAccess.isOpen()) return false
            Timber.w(
                "网络错误（%s）第 %d 次：转码流带位置重开会话 target=%d",
                error.errorCodeName,
                networkRetryAttempts,
                position,
            )
            seekSink.restartTranscodeForNetwork(seekLease, "network-retry")
        } else {
            Timber.w(
                "网络错误（%s）第 %d 次：原地重试当前内核 position=%d",
                error.errorCodeName,
                networkRetryAttempts,
                position,
            )
            retryPlaybackAt(position, activePlayer = activePlayer)
        }
        return true
    }

    // Network, IO and unspecified mpv failures use the in-place retry path.
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

    // Advance the persisted fallback ladder exactly once per logical failure.
    private fun handleCodecFallback(error: PlaybackException): Boolean {
        val backend = playerBackend
        val mediaId = player.currentMediaItem?.mediaId ?: return false
        // W19：「失败自动回退」开关关闭 = 强制使用所选内核，失败只提示错误（不切换、不重启）
        val autoFallback = appPreferences.getValue(appPreferences.playerAutoFallback)
        val currentStage =
            PlayerDecodeFallback.normalize(
                appPreferences.getValue(appPreferences.playerDecodeFallbackStage)
            )
        // Deduplicate duplicate END_FILE failures from the same fallback stage.
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
                bitratePreference = appPreferences.getPlayerStreamingBitrate(),
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

    // Keep fallback state for a session restart; clear it when the session id changes.
    private fun prepareDecodeFallback(playbackSessionId: String): Boolean {
        val storedSession = appPreferences.getValue(appPreferences.playerDecodeFallbackSession)
        if (storedSession == playbackSessionId) return false
        if (storedSession.isNotBlank()) {
            Timber.d("播放会话切换（%s → %s），清空解码回退档位", storedSession, playbackSessionId)
        }
        appPreferences.setValue(appPreferences.playerDecodeFallbackSession, playbackSessionId)
        clearDecodeFallback()
        return true
    }

    // User decode changes reset the fallback ladder to local hardware.
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

    // Return the kind of the actual playing item for restart restoration.
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
        cancelPlaybackWork(cancelSessionSampler = true)
        releasePlayer()
    }

    // ---------- 字幕面板：延迟 / 双语 / 外观（§1.1） ----------

    // Select the primary subtitle track; null explicitly disables it.
    fun selectSubtitlePrimary(id: Int?) {
        manualTrackSelectionMediaId = player.currentMediaItem?.mediaId
        if (playerBackend == PLAYER_BACKEND_MPV) {
            val mpv = player as? MPVPlayer ?: return
            // 次字幕由 Compose 负责；主字幕切到次字幕同一源时立即清掉次字幕，避免同句叠两次。
            val primarySourceIndex = sourceIndexForMpvSubtitleId(id)
            if (
                primarySourceIndex != null &&
                    primarySourceIndex == subtitleController.overlayState.value.secondaryIndex
            ) {
                // 这是「同轨换路」，不是用户关闭次字幕：不写手动关闭语义。
                subtitleController.clearSecondaryForReassignment()
            }
            mpv.setSecondarySubtitle(null)
            if (id == null) subtitleController.selectSecondary(null)
            // 新主字幕映射不到源时，先关闭 Compose 次字幕再切 sid：不能等 track-list 异步回调，
            // 否则新原生主字幕已经可见、旧次字幕还在画。
            if (
                shouldSuppressSecondaryForMpvPrimarySelection(
                    selectedTrackId = id,
                    mappedSourceIndex = primarySourceIndex,
                )
            ) {
                subtitleController.suppressSecondaryForSafety()
            }
            mpv.selectSubtitleTrack(id)
            subtitleController.syncPrimaryIndex(primarySourceIndex, rememberManual = true)
            primarySourceIndex?.let(subtitleController::rememberPrimaryLanguage)
            // 以用户意图先行更新「有原生主字幕」：旧 currentTracks 快照不能把它盖回 false。
            subtitleController.setNativePrimaryActive(id != null)
            if (
                id != null &&
                    primarySourceIndex != null &&
                    subtitleController.overlayState.value.secondaryManaged
            ) {
                // 只有次字幕已激活才预加载：单字幕 mpv 仍走原生，不额外下载 / 解析主字幕。
                // 用户之后开启次字幕时，overlay collector 里的 ensureMpvPrimaryContent() 会补加载。
                subtitleController.ensurePrimaryContentAvailable()
            }
            publishSubtitlePanelState()
            return
        }
        subtitleController.selectPrimary(id)
        applySubtitleRoutingOnly()
    }

    // Select the secondary subtitle track; a primary track is reassigned, never duplicated.
    fun selectSubtitleSecondary(id: Int?) {
        if (id != null) {
            assignSubtitleSecondary(id)
            return
        }
        manualTrackSelectionMediaId = player.currentMediaItem?.mediaId
        if (playerBackend == PLAYER_BACKEND_MPV) {
            val mpv = player as? MPVPlayer ?: return
            // mpv 原生 secondary-sid 不使用；次字幕统一走自研层，才能测高 / 80% / 隐藏。
            mpv.setSecondarySubtitle(null)
            subtitleController.syncPrimaryIndex(currentMpvPrimarySourceIndex())
            subtitleController.selectSecondary(null)
            publishSubtitlePanelState()
            return
        }
        subtitleController.selectSecondary(null)
        applySubtitleRoutingOnly()
    }

    /** 单列表「主」chip：Exo 传源序号，mpv 传原生 sid。 */
    fun assignSubtitlePrimary(primaryActionId: Int) {
        selectSubtitlePrimary(primaryActionId)
    }

    /** 单列表「次」chip：两个内核都传 Jellyfin 源序号。 */
    fun assignSubtitleSecondary(secondaryActionId: Int) {
        manualTrackSelectionMediaId = player.currentMediaItem?.mediaId
        if (playerBackend == PLAYER_BACKEND_MPV) {
            val mpv = player as? MPVPlayer ?: return
            if (currentMpvPrimarySourceIndex() == null && currentMpvPrimaryActive()) {
                // 主字幕映射不到源：不能猜测用户选的次字幕是否与它同源。保留原生主字幕，拒绝次字幕。
                subtitleController.suppressSecondaryForSafety()
                publishSubtitlePanelState()
                return
            }
            val source =
                subtitleController.overlayState.value.sources.firstOrNull {
                    it.index == secondaryActionId && it.isTextBased
                } ?: return
            val primarySourceIndex = currentMpvPrimarySourceIndex()
            if (source.index == primarySourceIndex) {
                // 同一条轨从主路移到次路：主路显式清空，但不是“用户关闭字幕”。
                mpv.selectSubtitleTrack(null)
                subtitleController.syncPrimaryIndex(null)
                subtitleController.setNativePrimaryActive(false)
            } else {
                subtitleController.syncPrimaryIndex(primarySourceIndex)
            }
            mpv.setSecondarySubtitle(null)
            subtitleController.assignSecondary(source.index)
            publishSubtitlePanelState()
            return
        }
        subtitleController.assignSecondary(secondaryActionId)
        applySubtitleRoutingOnly()
    }

    /** 关闭主字幕（显式语义：写手动记忆，下次仍保持关闭）。 */
    fun clearSubtitlePrimary() {
        selectSubtitlePrimary(null)
    }

    /** 关闭次字幕（不影响主字幕）。 */
    fun clearSubtitleSecondary() {
        selectSubtitleSecondary(null)
    }

    /** 交换主次。Exo 走源序号互换；mpv 先反查次字幕对应的原生 sid，再把旧主字幕源放到次路。 */
    fun swapSubtitleTracks() {
        manualTrackSelectionMediaId = player.currentMediaItem?.mediaId
        if (playerBackend != PLAYER_BACKEND_MPV) {
            subtitleController.swapPrimarySecondary()
            applySubtitleRoutingOnly()
            return
        }
        val mpv = player as? MPVPlayer ?: return
        val primarySourceIndex = currentMpvPrimarySourceIndex() ?: return
        val secondarySourceIndex = subtitleController.overlayState.value.secondaryIndex ?: return
        if (primarySourceIndex == secondarySourceIndex) return
        val primarySource = subtitleSource(primarySourceIndex)
        val secondarySource = subtitleSource(secondarySourceIndex)
        if (primarySource?.isTextBased != true || secondarySource?.isTextBased != true) return
        val secondaryTrackId = mpvTrackIdForSourceIndex(secondarySourceIndex) ?: return
        mpv.setSecondarySubtitle(null)
        mpv.selectSubtitleTrack(secondaryTrackId)
        subtitleController.syncPrimaryIndex(secondarySourceIndex)
        subtitleController.rememberPrimaryLanguage(secondarySourceIndex)
        subtitleController.setNativePrimaryActive(true)
        subtitleController.assignSecondary(primarySourceIndex)
        publishSubtitlePanelState()
    }

    /** 自动选择入口：清掉本媒体的手动记忆，按模式 / 语言优先级重选主次。 */
    fun autoSelectSubtitleTracks() {
        manualTrackSelectionMediaId = null
        val mpv = if (playerBackend == PLAYER_BACKEND_MPV) player as? MPVPlayer else null
        if (mpv != null) {
            subtitleController.forgetManualSelectionForAuto()
            subtitleController.clearSecondaryForReassignment()
            mpv.setSubtitleAutoSelect(true)
        } else {
            subtitleController.autoSelectAndForgetManual(autoSecondary = true)
            applyAutoSelectAndRoute()
        }
        publishSubtitlePanelState()
    }

    // Import a sideload to app-private storage and select it when supported.
    fun importSideloadedSubtitle(
        displayName: String,
        bytes: ByteArray,
        onResult: (Boolean, String) -> Unit,
    ) {
        if (!playerAccess.isOpen()) return
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
            if (!playerAccess.isOpen()) return@launch
            val activePlayer = playerAccess.existingPlayer() ?: return@launch
            sideloads = runCatching { sideloadStore.list(mediaId) }.getOrDefault(emptyList())
            Timber.d("侧载字幕导入：%s（语言=%s）", record.displayName, record.language)
            if (playerBackend == PLAYER_BACKEND_MPV) {
                (activePlayer as? MPVPlayer)?.addSubtitleFile(record.path, select = true)
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

    // Remove a sideload and rebuild the current subtitle pipeline.
    fun removeSideloadedSubtitle(optionId: Int) {
        if (!playerAccess.isOpen()) return
        val mediaId = currentPlaybackMediaId() ?: return
        val record = resolveSideloadedRecord(optionId) ?: return
        viewModelScope.launch {
            withContext(Dispatchers.IO) { runCatching { sideloadStore.remove(record) } }
            if (!playerAccess.isOpen()) return@launch
            val activePlayer = playerAccess.existingPlayer() ?: return@launch
            sideloads = runCatching { sideloadStore.list(mediaId) }.getOrDefault(emptyList())
            Timber.d("侧载字幕已移除：%s", record.displayName)
            if (playerBackend == PLAYER_BACKEND_MPV) {
                (activePlayer as? MPVPlayer)?.removeSubtitleFile(record.path)
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
                ?: sideloads.getOrNull(optionId - SideloadedSubtitleStore.INDEX_BASE)?.takeIf {
                    optionId >= SideloadedSubtitleStore.INDEX_BASE
                }
        } else {
            if (optionId < SideloadedSubtitleStore.INDEX_BASE) {
                null
            } else {
                sideloads.getOrNull(optionId - SideloadedSubtitleStore.INDEX_BASE)
            }
        }

    // Read debug fields on the thread required by the active backend.
    suspend fun readDebugStats(): PlayerDebugStats {
        if (!playerAccess.isOpen()) return PlayerDebugStats()
        val current = playerAccess.existingPlayer() ?: return PlayerDebugStats()
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

    /** 字幕外观：主 / 次 / 布局即时写偏好并应用到当前内核（自绘层直接读状态，原生层由 Activity 更新） */
    fun updateSubtitleAppearance(appearance: SubtitleAppearance) {
        subtitleController.updateAppearance(appearance)
        if (playerBackend == PLAYER_BACKEND_MPV) {
            (player as? MPVPlayer)?.applySubtitleStyle(appearance.primary, appearance.layout)
        } else {
            eventsChannel.trySend(PlayerEvents.SubtitleStyleChanged(appearance.primary))
        }
        publishSubtitlePanelState()
    }

    /** 起播 / 接管会话时把已保存的延迟与外观应用到当前内核 */
    private fun applySavedSubtitlePreferences() {
        val delayMs = appPreferences.getValue(appPreferences.playerSubtitleDelayMs)
        val appearance = readSubtitleAppearance()
        if (playerBackend == PLAYER_BACKEND_MPV) {
            (player as? MPVPlayer)?.let { mpv ->
                mpv.setSubtitleDelay(delayMs)
                mpv.applySubtitleStyle(appearance.primary, appearance.layout)
            }
        } else {
            eventsChannel.trySend(PlayerEvents.SubtitleStyleChanged(appearance.primary))
        }
    }

    // Restore repeat and shuffle when the backend supports them.
    private fun applySavedPlaybackMode() {
        val savedRepeat = appPreferences.getValue(PlayerExtraPreferences.repeatMode)
        val savedShuffle = appPreferences.getValue(PlayerExtraPreferences.shuffle)
        val shuffleSupported = player.availableCommands.contains(Player.COMMAND_SET_SHUFFLE_MODE)
        player.repeatMode = savedRepeat
        if (shuffleSupported) {
            player.shuffleModeEnabled = savedShuffle
        }
        Timber.i(
            "应用循环 / 随机偏好: repeat=%d shuffle=%b(支持=%b) backend=%s",
            savedRepeat,
            savedShuffle,
            shuffleSupported,
            playerBackend,
        )
    }

    /** W74 U6：设置页改完 seek 步进后（回播放页时）把新值推给内核，通知栏 / 媒体会话的 ± 命令立即生效 */
    fun syncSeekIncrements() {
        playerHolder.applySeekIncrements()
    }

    private fun readSubtitleAppearance(): SubtitleAppearance =
        SubtitleAppearancePreferences.read(appPreferences)

    // Only ExoPlayer uses the app-side primary subtitle pipeline.
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

    // Rebuild the media-scoped subtitle pipeline and restore manual selection.
    private fun applySubtitlePipelineForMedia(
        mediaId: String,
        sources: List<PlayerSubtitleSource>,
    ) {
        lastSubtitleRoutingTrace = null
        sideloads = runCatching { sideloadStore.list(mediaId) }.getOrDefault(emptyList())
        subtitleController.reset(
            mediaId = mediaId,
            sources = sources,
            selectionScope = subtitleSelectionScope(mediaId),
            // Imported sideloads are the fallback primary, after scoped restore.
            defaultPrimaryIndex =
                SideloadedSubtitleStore.INDEX_BASE.takeIf {
                    playerBackend != PLAYER_BACKEND_MPV && sideloads.isNotEmpty()
                },
            autoSecondary = playerBackend != PLAYER_BACKEND_MPV,
        )
        ensureMpvSideloadsInjected()
        applyMpvManualSubtitleSelection()
    }

    // Scope subtitle memory by server, user and item.
    private fun subtitleSelectionScope(mediaId: String): SubtitleSelectionScope? =
        SubtitleSelectionScope.create(
            serverId = appPreferences.getValue(appPreferences.currentServer),
            userId = jellyfinApi.userId?.toString(),
            itemId = mediaId,
        )

    /** 当前条目的字幕源清单：服务器字幕 + 侧载字幕。mpv 只取次字幕，主字幕仍由 native sid 接管。 */
    private fun pipelineSourcesFor(
        mediaId: String,
        item: PlayerItem?,
    ): List<PlayerSubtitleSource> =
        when {
            playerBackend == PLAYER_BACKEND_MPV ->
                item?.subtitleSources.orEmpty() + sideloadSourcesFor(mediaId)
            item != null -> subtitleSourcesForBackend(item)
            else -> sideloadSourcesFor(mediaId)
        }

    // Idempotently inject pending mpv sideloads on track-list changes.
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

    // Apply auto-selection and subtitle routing in one parameter update.
    private fun applyAutoSelectAndRoute(tracks: Tracks = player.currentTracks) {
        if (player !is ExoPlayer) return
        if (player.currentMediaItem == null) return
        val subtitleState = subtitleController.overlayState.value
        val routing = resolveSubtitleRouting(subtitleState)
        val update = runCatching {
            val parameters =
                trackSelectionEngine.parameters(
                    player.trackSelectionParameters,
                    tracks,
                    subtitlesManaged = routing !is SubtitleRouting.Auto,
                )
            applySubtitleRoutingParameters(parameters, routing, tracks)
        }
            .onFailure { Timber.w(it, "自动选择字幕/音轨失败") }
            .getOrNull()
        nativeSubtitleUnavailableReason = update?.nativeUnavailableReason
        traceSubtitleRouting(routing, subtitleState)
        update?.let { player.trackSelectionParameters = it.parameters }
        publishSubtitlePanelState()
    }

    /** 只调整字幕部分（音轨保持现状）：用户手动选过轨之后走这里 */
    private fun applySubtitleRoutingOnly() {
        if (player !is ExoPlayer) return
        if (player.currentMediaItem == null) return
        val subtitleState = subtitleController.overlayState.value
        val routing = resolveSubtitleRouting(subtitleState)
        val update = runCatching {
            applySubtitleRoutingParameters(
                player.trackSelectionParameters,
                routing,
                player.currentTracks,
            )
        }
            .onFailure { Timber.w(it, "字幕渲染路由失败") }
            .getOrNull()
        nativeSubtitleUnavailableReason = update?.nativeUnavailableReason
        traceSubtitleRouting(routing, subtitleState)
        if (routing !is SubtitleRouting.Auto) {
            update?.let { player.trackSelectionParameters = it.parameters }
        }
        publishSubtitlePanelState()
    }

    private fun traceSubtitleRouting(
        routing: SubtitleRouting,
        state: SubtitleOverlayState,
    ) {
        val route =
            when (routing) {
                SubtitleRouting.Managed -> "managed"
                SubtitleRouting.Off -> "off"
                is SubtitleRouting.Native -> "native"
                SubtitleRouting.Auto -> "auto"
            }
        val selected =
            (routing as? SubtitleRouting.Native)?.source
                ?: state.sources.firstOrNull { it.index == state.primaryIndex }
        val signature =
            listOf(
                    route,
                    state.primaryIndex,
                    state.secondaryIndex,
                    state.primaryManaged,
                    state.primaryLoading,
                    state.primaryRenderFailed,
                    nativeSubtitleUnavailableReason,
                    selected?.index,
                    selected?.codec,
                    selected?.isExternal,
                )
                .joinToString("|")
        if (signature == lastSubtitleRoutingTrace) return
        lastSubtitleRoutingTrace = signature
        Timber.d(
            "字幕渲染路由: route=%s primary=%s secondary=%s managed=%b loading=%b renderFailed=%b unavailable=%s sourceIndex=%s codec=%s external=%s",
            route,
            state.primaryIndex,
            state.secondaryIndex,
            state.primaryManaged,
            state.primaryLoading,
            state.primaryRenderFailed,
            nativeSubtitleUnavailableReason,
            selected?.index,
            selected?.codec,
            selected?.isExternal,
        )
    }

    private data class MpvNativeSubtitleTrack(
        val id: Int,
        val label: String,
        val language: String,
        val codec: String,
        val selected: Boolean,
        val sideloaded: Boolean,
    )

    private fun MpvNativeSubtitleTrack.toRef(): MpvSubtitleTrackRef =
        MpvSubtitleTrackRef(
            id = id,
            label = label,
            language = language.takeIf { it.isNotBlank() },
            codec = codec,
        )

    /** 刷新字幕面板状态：两个内核在这里汇总成同一份 UI 数据 */
    private fun publishSubtitlePanelState(
        state: SubtitleOverlayState = subtitleController.overlayState.value
    ) {
        val delayMs = appPreferences.getValue(appPreferences.playerSubtitleDelayMs)
        val appearance = readSubtitleAppearance()

        if (playerBackend == PLAYER_BACKEND_MPV) {
            val currentSid = currentMpvPrimaryTrackId()
            // 用同步 `sid` 反推，避免 track-list 回调前面板仍按旧选中轨过滤次字幕候选。
            val primarySourceIndex = currentSid?.let(::sourceIndexForMpvSubtitleId)
            // 映射不到主字幕源时无法排除同源：只要原生主字幕仍选中，就整体禁用次字幕候选。
            val secondarySelectable = primarySourceIndex != null || currentSid == null
            val nativeTracks = nativeSubtitleTracks()
            val sourceIndexBySid = nativeTracks.associate { track ->
                track.id to sourceIndexForMpvSubtitleId(track.id)
            }
            // 侧载路径映射也在这份集合里：native 行与纯源行共用同一判重口径。
            val representedSourceIndices = sourceIndexBySid.values.filterNotNull().toSet()
            val tracks =
                nativeTracks.map { track ->
                    val mappedSourceIndex = sourceIndexBySid[track.id]
                    val source = subtitleSource(mappedSourceIndex, state.sources)
                    val textAssignable = source?.isTextBased == true
                    val secondarySourceIndex =
                        if (track.sideloaded) {
                            // 侧载已由文件路径精确映射；通用标题 / 语言映射不参与。
                            mappedSourceIndex.takeIf { textAssignable }
                        } else {
                            MpvSubtitleTrackMapping.secondarySourceIndexForTrack(
                                track.toRef(),
                                state.sources,
                            )
                        }
                    val isPrimary = currentSid != null && track.id == currentSid
                    val isSecondary =
                        !isPrimary &&
                            mappedSourceIndex != null &&
                            mappedSourceIndex == state.secondaryIndex
                    SubtitleOption(
                        id = track.id,
                        label = track.label,
                        language = source?.language?.takeIf { it.isNotBlank() } ?: track.language,
                        format = source?.formatLabel.orEmpty(),
                        origin =
                            when {
                                track.sideloaded -> SubtitleOrigin.Sideloaded
                                source?.isExternal == true -> SubtitleOrigin.External
                                else -> SubtitleOrigin.Embedded
                            },
                        textAssignable = textAssignable,
                        graphic = source?.isGraphic == true,
                        sideloaded = track.sideloaded,
                        primaryActionId = track.id,
                        secondaryActionId = secondarySourceIndex.takeIf { secondarySelectable },
                        assignment =
                            when {
                                isPrimary && textAssignable -> SubtitleAssignment.Primary
                                isPrimary -> SubtitleAssignment.Native
                                isSecondary -> SubtitleAssignment.Secondary
                                else -> null
                            },
                    )
                } +
                    MpvSubtitleTrackMapping.unmappedTextSources(
                            representedSourceIndices,
                            state.sources,
                        )
                        .map { source ->
                            SubtitleOption(
                                id = source.index,
                                label = source.title,
                                language = source.language,
                                format = source.formatLabel,
                                origin = subtitleOriginOf(source),
                                textAssignable = true,
                                graphic = false,
                                sideloaded = source.index >= SideloadedSubtitleStore.INDEX_BASE,
                                // 未注入 mpv 的纯源没有可写的 sid：只允许放次路。
                                primaryActionId = null,
                                secondaryActionId = source.index.takeIf { secondarySelectable },
                                assignment =
                                    SubtitleAssignment.Secondary.takeIf {
                                        source.index == state.secondaryIndex
                                    },
                            )
                        }
            val primarySource = subtitleSource(primarySourceIndex, state.sources)
            val secondarySource = subtitleSource(state.secondaryIndex, state.sources)
            val swapBlockedReason =
                when {
                    currentSid == null -> SubtitleSwapBlock.NoPrimary
                    primarySource?.isTextBased != true -> SubtitleSwapBlock.PrimaryNativeOnly
                    state.secondaryIndex == null -> SubtitleSwapBlock.NoSecondary
                    secondarySource?.isTextBased != true -> SubtitleSwapBlock.SecondaryNativeOnly
                    state.secondaryIndex == primarySourceIndex -> SubtitleSwapBlock.SameTrack
                    mpvTrackIdForSourceIndex(state.secondaryIndex) == null ->
                        SubtitleSwapBlock.SecondaryNativeOnly
                    else -> null
                }
            _subtitlePanelState.value =
                SubtitlePanelState(
                    tracks = tracks,
                    delayMs = delayMs,
                    appearance = appearance,
                    managed = false,
                    controllable = tracks.isNotEmpty(),
                    loading = state.loading,
                    secondaryAssignmentBlocked = !secondarySelectable,
                    secondaryUnavailableReason = state.secondaryUnavailableReason,
                    swapBlockedReason = swapBlockedReason,
                    assSecondaryHint = secondarySource.isAssSecondary(),
                )
            return
        }

        val primarySource = subtitleSource(state.primaryIndex, state.sources)
        val secondarySource = subtitleSource(state.secondaryIndex, state.sources)
        val swapBlockedReason =
            when {
                primarySource == null -> SubtitleSwapBlock.NoPrimary
                !primarySource.isTextBased -> SubtitleSwapBlock.PrimaryNativeOnly
                secondarySource == null -> SubtitleSwapBlock.NoSecondary
                !secondarySource.isTextBased -> SubtitleSwapBlock.SecondaryNativeOnly
                primarySource.index == secondarySource.index -> SubtitleSwapBlock.SameTrack
                else -> null
            }
        _subtitlePanelState.value =
            SubtitlePanelState(
                tracks =
                    state.sources.map { source ->
                        val primary = source.index == state.primaryIndex
                        SubtitleOption(
                            id = source.index,
                            label = source.title,
                            language = source.language,
                            format = source.formatLabel,
                            origin = subtitleOriginOf(source),
                            textAssignable = source.isTextBased,
                            graphic = source.isGraphic,
                            sideloaded = source.index >= SideloadedSubtitleStore.INDEX_BASE,
                            primaryActionId = source.index,
                            secondaryActionId = source.index.takeIf { source.isTextBased },
                            assignment =
                                when {
                                    primary && source.isTextBased -> SubtitleAssignment.Primary
                                    primary -> SubtitleAssignment.Native
                                    source.index == state.secondaryIndex && source.isTextBased ->
                                        SubtitleAssignment.Secondary
                                    else -> null
                                },
                        )
                    },
                delayMs = delayMs,
                appearance = appearance,
                managed = state.primaryManaged,
                controllable = state.sources.any { it.isTextBased },
                loading = state.loading,
                primaryUnavailableReason = nativeSubtitleUnavailableReason,
                secondaryUnavailableReason = state.secondaryUnavailableReason,
                swapBlockedReason = swapBlockedReason,
                assSecondaryHint = secondarySource.isAssSecondary(),
            )
    }

    private fun subtitleSource(
        index: Int?,
        sources: List<PlayerSubtitleSource> = subtitleController.overlayState.value.sources,
    ): PlayerSubtitleSource? = index?.let { value -> sources.firstOrNull { it.index == value } }

    private fun subtitleOriginOf(source: PlayerSubtitleSource): SubtitleOrigin =
        when {
            source.index >= SideloadedSubtitleStore.INDEX_BASE -> SubtitleOrigin.Sideloaded
            source.isExternal -> SubtitleOrigin.External
            else -> SubtitleOrigin.Embedded
        }

    private fun PlayerSubtitleSource?.isAssSecondary(): Boolean {
        if (this?.isTextBased != true) return false
        return codec.lowercase() in setOf("ass", "ssa")
    }

    /** mpv 字幕轨列表：Format.id 就是 mpv 的 sid */
    private fun nativeSubtitleTracks(
        sideloadTrackIds: Set<Int> = mpvSideloadTrackIds()
    ): List<MpvNativeSubtitleTrack> =
        player.currentTracks.groups
            .filter { it.type == C.TRACK_TYPE_TEXT && it.isSupported }
            .mapNotNull { group ->
                val format = group.mediaTrackGroup.getFormat(0)
                val id = format.id?.toIntOrNull() ?: return@mapNotNull null
                MpvNativeSubtitleTrack(
                    id = id,
                    label =
                        format.label?.takeIf { it.isNotBlank() }
                            ?: format.language?.takeIf { it.isNotBlank() }
                            ?: application.getString(R.string.player_subtitle_track_fallback, id),
                    language = format.language.orEmpty(),
                    codec =
                        format.codecs?.takeIf { it.isNotBlank() }
                            ?: format.sampleMimeType?.takeIf {
                                it.isNotBlank() && !it.endsWith("/null")
                            }
                            ?: "",
                    selected = group.isSelected,
                    sideloaded = id in sideloadTrackIds,
                )
            }

    private fun mpvSideloadTrackIds(): Set<Int> {
        val mpv = player as? MPVPlayer ?: return emptySet()
        return sideloads.mapNotNull { record -> mpv.subtitleTrackIdForPath(record.path) }.toSet()
    }

    /**
     * mpv 原生 track id → Jellyfin 字幕源序号。
     *
     * 面板主字幕仍使用 mpv 的 sid；次字幕改由 Compose 加载文件，需要把 sid 映射回 [PlayerSubtitleSource]。 顺序为类型约束下“标题 + 语言 →
     * 唯一标题 → 唯一语言 → 同序号兜底”；侧载先按文件路径精确反查。
     */
    private fun sourceIndexForMpvSubtitleId(id: Int?): Int? {
        if (id == null) return null
        val track = nativeSubtitleTracks().firstOrNull { it.id == id } ?: return null
        val sideloadPath = (player as? MPVPlayer)?.subtitleTrackPath(id)
        if (sideloadPath != null) {
            val position = sideloads.indexOfFirst { it.path == sideloadPath }
            if (position >= 0) return SideloadedSubtitleStore.INDEX_BASE + position
        }
        val sources = subtitleController.overlayState.value.sources
        return MpvSubtitleTrackMapping.sourceIndexForTrack(
            track.toRef(),
            sources,
        )
    }

    /** 次字幕源序号 → mpv 原生 sid（侧载按文件路径精确反查）。 */
    private fun mpvTrackIdForSourceIndex(sourceIndex: Int): Int? {
        val mpv = player as? MPVPlayer ?: return null
        if (sourceIndex >= SideloadedSubtitleStore.INDEX_BASE) {
            val record = sideloads.getOrNull(sourceIndex - SideloadedSubtitleStore.INDEX_BASE)
            return record?.let { mpv.subtitleTrackIdForPath(it.path) }
        }
        return MpvSubtitleTrackMapping.trackForSource(
                sourceIndex = sourceIndex,
                tracks = nativeSubtitleTracks().map { it.toRef() },
                sources = subtitleController.overlayState.value.sources,
            )
            ?.id
    }

    // Prefer the synchronous mpv sid; fall back to the selected native track snapshot.
    private fun currentMpvPrimaryTrackId(): Int? {
        val raw = (player as? MPVPlayer)?.currentSubtitleSelection()
        raw?.toIntOrNull()?.let {
            return it
        }
        if (raw.equals("no", ignoreCase = true)) return null
        return nativeSubtitleTracks().firstOrNull { it.selected }?.id
    }

    private fun currentMpvPrimarySourceIndex(): Int? =
        currentMpvPrimaryTrackId()?.let { sourceIndexForMpvSubtitleId(it) }

    // Reapply the current item's manual mpv subtitle selection after track discovery.
    private fun applyMpvManualSubtitleSelection() {
        val mpv = player as? MPVPlayer ?: return
        if (!subtitleController.hasManualSelectionForCurrentItem()) return
        val selection = subtitleController.currentManualSelection() ?: return
        if (selection.scope.itemId != currentPlaybackMediaId()) return
        if (selection.primaryIndex == ScopedSubtitleSelection.NO_INDEX) {
            if (currentMpvPrimaryTrackId() != null) {
                mpv.setSubtitleAutoSelect(false)
            }
            subtitleController.setNativePrimaryActive(false)
            return
        }
        val trackId = mpvTrackIdForSourceIndex(selection.primaryIndex) ?: return
        if (currentMpvPrimaryTrackId() != trackId) {
            mpv.selectSubtitleTrack(trackId)
        }
        subtitleController.syncPrimaryIndex(selection.primaryIndex)
        subtitleController.setNativePrimaryActive(true)
    }

    // Load primary mpv text only for dual subtitles and fail closed on unmapped tracks.
    private fun ensureMpvPrimaryContent(state: SubtitleOverlayState) {
        if (player !is MPVPlayer) return
        val nativePrimaryActive = currentMpvPrimaryActive()
        subtitleController.setNativePrimaryActive(nativePrimaryActive)
        if (!state.secondaryManaged) return
        val primarySourceIndex = currentMpvPrimarySourceIndex()
        if (primarySourceIndex == null) {
            subtitleController.syncPrimaryIndex(null)
            if (nativePrimaryActive) {
                // 映射不到主字幕源就无法判断主 / 次是否同源：保留原生主字幕，关闭次字幕，
                // 绝不把同一句同时交给原生主字幕和 Compose 次字幕。
                subtitleController.suppressSecondaryForSafety()
            }
            return
        }
        if (state.primaryIndex != primarySourceIndex) {
            subtitleController.syncPrimaryIndex(primarySourceIndex)
        }
        subtitleController.ensurePrimaryContentAvailable()
    }

    /** mpv 当前是否有选中的原生字幕轨（与能否映射到 Jellyfin 源无关）。 */
    private fun currentMpvPrimaryActive(): Boolean = currentMpvPrimaryTrackId() != null

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

    fun consumeProgressFallback(requestToken: String): PlaybackProgressFallbackHandoff? {
        if (explicitExitController.isExited) return null
        refreshPlaybackSession()
        val active = playerHolder.existingPlayer ?: return null
        val mediaItem = active.currentMediaItem ?: return null
        val sessionKey = currentPlaybackSessionKey(active, mediaItem) ?: return null
        val itemKind = currentPlaybackItemKind()
        if (itemKind == null) {
            progressFallbackController.cancelCurrentGeneration()
            return null
        }
        val playWhenReadyIntent = if (startupInProgress) playWhenReady else active.playWhenReady
        val request =
            progressFallbackController.consume(
                requestToken,
                progressFallbackInput(
                    mediaItem = mediaItem,
                    sessionKey = sessionKey,
                    playWhenReady = playWhenReadyIntent,
                ),
            ) ?: return null
        return PlaybackProgressFallbackHandoff(
            itemId = request.mediaIdentity.itemId,
            itemKind = itemKind,
            mediaSourceId = request.mediaIdentity.mediaSourceId,
            positionMs = request.positionMs,
        )
    }

    fun cancelProgressFallback() {
        progressFallbackController.cancelCurrentGeneration()
    }

    fun retryPlayback(terminalToken: String) {
        if (!playerAccess.isOpen()) return
        val terminal = playbackSessionState.terminal
        if (terminal?.token != terminalToken || playbackSessionState.retryConsumed) {
            Timber.d("Ignoring stale playback retry terminal=%s", terminalToken)
            return
        }
        val active = playerAccess.existingPlayer()
        val currentKey = currentPlaybackSessionKey(active, active?.currentMediaItem)
        if (!PlaybackSessionRules.retrySourceIsCurrent(playbackSessionState, currentKey)) {
            return refreshPlaybackSession()
        }
        playbackSessionState =
            PlaybackSessionRules.consumeRetry(playbackSessionState, terminalToken) ?: return
        Timber.d(
            "Retrying playback terminal=%s backend=$playerBackend from position=%d",
            terminalToken,
            terminal.positionMs,
        )
        retryPlaybackAt(terminal.positionMs, terminal.positionSource)
    }

    fun retryPlayerError() {
        val sourceIntent = failedSourceIntent?.takeIf {
            _uiState.value.playerError?.codeName == PLAYER_ERROR_SOURCE_RESOLUTION_FAILED
        }
        if (sourceIntent != null) {
            if (explicitExitController.isExited) return
            initializePlayer(
                itemId = sourceIntent.itemId,
                itemKind = sourceIntent.itemKind,
                startFromBeginning = sourceIntent.startFromBeginning,
                startPositionMs = sourceIntent.startPositionMs,
                playbackSessionId = sourceIntent.playbackSessionId,
                queueEntries = sourceIntent.queueEntries,
                preferredMediaSourceId = sourceIntent.preferredMediaSourceId,
            )
            return
        }
        playerAccess.withExistingPlayer { activePlayer ->
            Timber.d("Retrying player error on backend=$playerBackend")
            retryPlaybackAt(
                position = currentResumePositionMs(activePlayer),
                activePlayer = activePlayer,
            )
        }
    }

    private fun retryPlaybackAt(
        position: Long,
        positionSource: PlaybackRetryPositionSource? = null,
        activePlayer: Player? = playerAccess.existingPlayer(),
    ) {
        if (!playerAccess.isOpen()) return
        val active = activePlayer ?: return
        _uiState.update { it.copy(playerError = null) }
        active.prepare()
        if (position > 0L) {
            if (positionSource == PlaybackRetryPositionSource.USER_SEEK_TARGET_NEW_REQUEST) {
                seekSink.requestSeek(position, "progress-recovery-user-target")
            } else {
                active.seekTo(position)
            }
        }
        active.play()
        refreshPlaybackSession()
    }

    private fun currentResumePositionMs(activePlayer: Player): Long {
        if (!playerAccess.isOpen()) return 0L
        refreshPlaybackSession()
        if (!playerAccess.isOpen()) return 0L
        val livePosition =
            confirmedPosition(activePlayer)
                ?: playbackSessionState.lastEnginePositionMs
                ?: C.TIME_UNSET
        if (livePosition > 0L) return livePosition
        val mediaId =
            activePlayer.currentMediaItem?.mediaId?.let { raw ->
                runCatching { UUID.fromString(raw) }.getOrNull()
            }
        val mediaSourceId =
            activePlayer.currentMediaItem
                ?.mediaMetadata
                ?.extras
                ?.getString(PLAYER_EXTRA_MEDIA_SOURCE_ID)
        return if (
            mediaId != null &&
                mediaId == lastProgressItemId &&
                mediaSourceId == lastProgressMediaSourceId
        ) {
            lastProgressPositionMs
        } else {
            0L
        }
    }

    fun switchBackend(): String {
        val next =
            if (playerBackend == PLAYER_BACKEND_MPV) PLAYER_BACKEND_EXOPLAYER
            else PLAYER_BACKEND_MPV
        appPreferences.setValue(appPreferences.playerBackend, next)
        Timber.d("Player backend switched: $playerBackend -> $next")
        return next
    }

    // Persist an explicitly selected backend for restart and resume.
    fun setBackend(backend: String) {
        appPreferences.setValue(appPreferences.playerBackend, backend)
        Timber.d("Player backend set to %s by settings panel", backend)
    }

    // Apply a global subtitle mode without overwriting item-specific selection.
    fun setSubtitleMode(mode: String) {
        appPreferences.setValue(appPreferences.subtitleMode, mode)
        Timber.d("Subtitle mode set to %s by settings panel", mode)
        val mpv = if (playerBackend == PLAYER_BACKEND_MPV) player as? MPVPlayer else null
        val appliedToCurrentItem =
            subtitleController.applyGlobalSelection(
                autoSecondary = playerBackend != PLAYER_BACKEND_MPV
            )
        if (!appliedToCurrentItem) {
            if (mpv != null) {
                applyMpvManualSubtitleSelection()
            } else {
                applySubtitleRoutingOnly()
            }
            publishSubtitlePanelState()
            return
        }
        manualTrackSelectionMediaId = null
        if (mpv != null) {
            // mpv 不走 TrackSelectionParameters：把 off / auto / always 翻译成 sid 意图。
            mpv.setSubtitleAutoSelect(mode != Constants.SubtitleMode.OFF)
        } else {
            applyAutoSelectAndRoute()
        }
        publishSubtitlePanelState()
    }

    // Map app subtitle mode to mpv sid=auto/no unless the item has manual selection.
    private fun syncMpvSubtitleMode() {
        val mpv = player as? MPVPlayer ?: return
        if (subtitleController.hasManualSelectionForCurrentItem()) {
            applyMpvManualSubtitleSelection()
            return
        }
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

        // Expose the live segment-chip duration to the overlay in milliseconds.
        val skipChipDurationMs = segmentsSkipButtonDuration.coerceAtLeast(1L) * 1000L
        if (_uiState.value.skipChipDurationMs != skipChipDurationMs) {
            _uiState.update { it.copy(skipChipDurationMs = skipChipDurationMs) }
        }
    }

    // ---------- 队列管理：拖拽排序 / 删除 / 清空 / 跳转（§1.7） ----------

    // Reorder the live timeline and stop background refill from undoing user edits.
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

    // Clear pending items while preserving the current playing item.
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

    private suspend fun getSegments(itemId: UUID): Boolean {
        var loadedSegments: List<FindroidSegment> = emptyList()
        return postSuspendCallbackCoordinator.run(
            suspendWork = {
                loadedSegments =
                    try {
                        repository.getSegments(itemId)
                    } catch (e: Exception) {
                        Timber.e(e)
                        emptyList()
                    }
            },
            continuation = { currentMediaItemSegments = loadedSegments },
        )
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

    private fun seekMediaIdentity(
        mediaItem: MediaItem? = playerHolder.existingPlayer?.currentMediaItem
    ): SeekMediaIdentity? {
        val item = mediaItem ?: return null
        val itemId = item.mediaId.takeIf(String::isNotBlank) ?: return null
        val mediaSourceId =
            item.mediaMetadata.extras?.getString(PLAYER_EXTRA_MEDIA_SOURCE_ID).orEmpty()
        val sourceUri =
            item.mediaMetadata.extras
                ?.getString(PLAYER_EXTRA_SEEK_SOURCE_URI)
                ?.takeIf(String::isNotBlank)
                ?: item.localConfiguration?.uri?.toString()
                ?: item.requestMetadata.mediaUri?.toString().orEmpty()
        if (sourceUri.isBlank()) return null
        return seekMediaIdentityFor(
            itemId = itemId,
            mediaSourceId = mediaSourceId,
            mediaSourceUri = sourceUri,
        )
    }

    /** All in-app seek controls enter the same process-scoped sink used by MediaSession. */
    fun requestSeek(targetMs: Long, source: String, fraction: Float? = null) {
        seekSink.requestSeek(targetMs, source, fraction)
    }

    fun requestSeekRelative(deltaMs: Long, source: String) {
        seekSink.requestSeekRelative(deltaMs, source)
    }

    fun submitSeek(intent: SeekIntent, source: String) {
        seekSink.submit(intent, source)
    }

    fun seekDisplayPositionMs(): Long = seekSink.seekDisplayPositionMs()

    fun retrySeek() {
        if (!playerAccess.isOpen()) return
        seekSink.retrySeek()
    }

    fun skipSegment(segment: FindroidSegment) {
        if (shouldSkipToNextEpisode(segment)) {
            seekSink.submit(
                SeekIntent.AdjacentMediaItem(forward = true),
                source = "skip-segment-next",
            )
        } else {
            requestSeek(segment.endTicks, "skip-segment")
        }
        _uiState.update { it.copy(currentSegment = null) }
    }

    // Check if the outro segment's end time is within n milliseconds of the player's total duration
    private fun shouldSkipToNextEpisode(segment: FindroidSegment): Boolean {
        val activePlayer = playerHolder.existingPlayer ?: return false
        return if (segment.type == FindroidSegmentType.OUTRO && activePlayer.hasNextMediaItem()) {
            val segmentEndTimeMillis = segment.endTicks
            val playerDurationMillis = activePlayer.duration
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
        if (!playerAccess.isOpen()) return
        eventsChannel.trySend(PlayerEvents.IsPlayingChanged(isPlaying))
        refreshPlaybackSession()
    }
}

internal fun SeekState.explicitSeekFields() =
    (this as? SeekState.Seeking)?.let { Triple(it.targetMs, it.requestGeneration, it.sourceEpoch) }
        ?: (this as? SeekState.Failed)?.let {
            Triple(it.targetMs, it.requestGeneration, it.sourceEpoch)
        }

internal data class ProgressRecoverySeekBinding(
    val targetMs: Long,
    val requestGeneration: Long,
    val sourceEpoch: Long,
)

internal fun SeekState.progressRecoverySeekBinding(): ProgressRecoverySeekBinding? =
    when (this) {
        is SeekState.Seeking ->
            ProgressRecoverySeekBinding(targetMs, requestGeneration, sourceEpoch)
        is SeekState.Confirmed ->
            ProgressRecoverySeekBinding(targetMs, requestGeneration, sourceEpoch)
        is SeekState.Failed -> ProgressRecoverySeekBinding(targetMs, requestGeneration, sourceEpoch)
        SeekState.Idle -> null
    }

internal fun progressRecoveryRequestIdentityIsCurrent(
    request: PlayerProgressRecoveryRequest,
    expectedSeekBinding: ProgressRecoverySeekBinding?,
    currentSessionKey: String?,
    currentMediaIdentity: PlaybackMediaIdentity?,
    currentSeekState: SeekState,
): Boolean =
    currentSessionKey == request.sessionKey &&
        currentMediaIdentity == request.mediaIdentity &&
        currentSeekState.progressRecoverySeekBinding() == expectedSeekBinding

internal fun retireConfirmedProgressRecoveryTerminal(
    terminal: PlayerViewModel.PlaybackRecovery?,
    observation: PlayerProgressRecoveryPhase,
): PlayerViewModel.PlaybackRecovery? = terminal.takeUnless {
    observation == PlayerProgressRecoveryPhase.CONFIRMED
}

class PlayerExitFinalizationGate {
    private var completed = false

    val isCompleted: Boolean
        get() = completed

    fun runIfActive(action: () -> Unit): Boolean {
        if (completed) return false
        action()
        return true
    }

    fun run(
        detachVideoOutput: () -> Unit,
        finalizePlayback: () -> Unit,
    ): Boolean {
        if (completed) return false
        completed = true
        detachVideoOutput()
        finalizePlayback()
        return true
    }
}

/**
 * 唯一允许 retry / player error / Compose / segment 读取活动播放器的入口。
 *
 * 关闭后不得触碰 lazy factory，也不得读取 current item / position / lease / seek sink。
 */
class PlayerAccessInvariant(
    private val isClosed: () -> Boolean,
    private val existingPlayerProvider: () -> Player?,
) {
    var existingPlayerAccessCount: Int = 0
        private set

    fun isOpen(): Boolean = !isClosed()

    fun runIfOpen(action: () -> Unit): Boolean {
        if (!isOpen()) return false
        action()
        return true
    }

    fun existingPlayer(): Player? {
        if (!isOpen()) return null
        existingPlayerAccessCount += 1
        return existingPlayerProvider()
    }

    fun withExistingPlayer(action: (Player) -> Unit): Boolean {
        if (!isOpen()) return false
        val activePlayer = existingPlayer() ?: return false
        if (!isOpen()) return false
        action(activePlayer)
        return true
    }
}

class PlayerPostSuspendCallbackCoordinator(private val playerAccess: PlayerAccessInvariant) {
    suspend fun run(
        suspendWork: suspend () -> Unit,
        continuation: suspend () -> Unit,
    ): Boolean {
        if (!playerAccess.isOpen()) return false
        suspendWork()
        if (!playerAccess.isOpen()) return false
        continuation()
        return true
    }
}

class PlayerDelayedEventDispatcher(
    private val playerAccess: PlayerAccessInvariant,
    private val onFallbackToMpv: () -> Unit,
    private val onRestartWithServerTranscode: () -> Unit,
    private val onFallbackToSoftware: () -> Unit,
    private val onFallbackToExoForProgressStall: (String) -> Unit,
) {
    fun dispatch(event: PlayerEvents): Boolean =
        when (event) {
            PlayerEvents.FallbackToMpv -> playerAccess.runIfOpen(onFallbackToMpv)
            PlayerEvents.RestartWithServerTranscode ->
                playerAccess.runIfOpen(onRestartWithServerTranscode)
            PlayerEvents.FallbackToSoftware -> playerAccess.runIfOpen(onFallbackToSoftware)
            is PlayerEvents.FallbackToExoForProgressStall ->
                playerAccess.runIfOpen { onFallbackToExoForProgressStall(event.requestToken) }
            else -> false
        }
}

class PlayerSubtitlePublicationCoordinator(
    private val playerAccess: PlayerAccessInvariant,
    private val publishPanel: (SubtitleOverlayState) -> Unit,
    private val routeSubtitle: (SubtitleOverlayState) -> Unit,
) {
    fun publish(state: SubtitleOverlayState): Boolean = playerAccess.runIfOpen {
        publishPanel(state)
        routeSubtitle(state)
    }
}

class PlayerSegmentCallbackCoordinator(private val playerAccess: PlayerAccessInvariant) {
    fun runIfActive(action: (Player) -> Unit): Boolean = playerAccess.withExistingPlayer(action)
}

internal class ProgressRecoveryJobOwner(
    private val scope: CoroutineScope,
    private val controller: PlayerProgressRecoveryController,
    private val currentSeekBinding: () -> ProgressRecoverySeekBinding?,
    private val requestIsCurrent:
        (PlayerProgressRecoveryRequest, ProgressRecoverySeekBinding?) -> Boolean,
    private val executeRetry:
        (PlayerProgressRecoveryRequest, PlayerViewModel.PlaybackRecovery) -> Unit,
    private val observeWindowMs: Long,
) {
    private var job: Job? = null
    private var terminal: PlayerViewModel.PlaybackRecovery? = null
    private var sessionKey: String? = null

    val isActive: Boolean
        get() = job?.isActive == true

    val activeTerminal: PlayerViewModel.PlaybackRecovery?
        get() = terminal

    fun start(
        firstRequest: PlayerProgressRecoveryRequest,
        recoveryTerminal: PlayerViewModel.PlaybackRecovery,
    ) {
        job?.cancel()
        terminal = recoveryTerminal
        sessionKey = firstRequest.sessionKey
        job = scope.launch {
            var request: PlayerProgressRecoveryRequest? = firstRequest
            var expectedSeekBinding = currentSeekBinding()
            while (isActive && request != null) {
                val currentRequest = request
                delay(currentRequest.delayMs)
                if (
                    !isActive ||
                        !requestIsCurrent(
                            currentRequest,
                            expectedSeekBinding,
                        )
                ) {
                    cancel()
                    return@launch
                }
                executeRetry(currentRequest, recoveryTerminal)
                expectedSeekBinding = currentSeekBinding()
                delay(observeWindowMs)
                if (!isActive) return@launch
                request = controller.nextAttempt(currentRequest)
            }
            if (terminal?.token == recoveryTerminal.token) {
                terminal = null
                sessionKey = null
            }
        }
    }

    fun retainTerminal(
        currentTerminal: PlayerViewModel.PlaybackRecovery?,
        currentSessionKey: String?,
    ) {
        val activeTerminal = terminal ?: return
        if (
            currentTerminal == null ||
                currentTerminal.token != activeTerminal.token ||
                currentSessionKey != sessionKey
        ) {
            cancel()
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
        controller.cancel()
        terminal = null
        sessionKey = null
    }
}

internal fun playbackSessionKeyFor(
    mediaIdentity: SeekMediaIdentity,
    snapshotGeneration: Long,
    engineEpoch: Long? = null,
): String {
    return with(mediaIdentity) {
        "$itemId|$mediaSourceId|$snapshotGeneration|$sourceFingerprint|${engineEpoch ?: "x"}"
    }
}

internal data class PlaybackSessionInput(
    val key: String?,
    val playWhenReady: Boolean,
    val isPlaying: Boolean,
    val isBuffering: Boolean,
    val isReady: Boolean,
    val enginePositionMs: Long?,
    val bufferedPositionMs: Long?,
    val hasPlayerError: Boolean,
    val hasSeekFailure: Boolean,
    val explicitSeekTargetMs: Long? = null,
    val explicitSeekRequestGeneration: Long? = null,
    val explicitSeekSourceEpoch: Long? = null,
    val persistedResumeMs: Long? = null,
)

internal data class PlaybackSessionState(
    val key: String? = null,
    val lastEnginePositionMs: Long? = null,
    val lastBufferedPositionMs: Long? = null,
    val lastProgressAtMs: Long? = null,
    val wasLoading: Boolean = false,
    val terminal: PlayerViewModel.PlaybackRecovery? = null,
    val retryConsumed: Boolean = false,
    val pendingSeekTargetMs: Long? = null,
    val pendingSeekGeneration: Long? = null,
    val pendingSeekBinding: String? = null,
    val recoverySeekBinding: String? = null,
)

internal object PlaybackSessionRules {
    const val STALL_TIMEOUT_MS = 20_000L

    fun advance(
        state: PlaybackSessionState,
        input: PlaybackSessionInput,
        nowElapsedMs: Long,
    ): PlaybackSessionState {
        val input =
            input.copy(
                enginePositionMs = normalizeEnginePosition(input.enginePositionMs),
                bufferedPositionMs = normalizeEnginePosition(input.bufferedPositionMs),
            )
        val key = input.key ?: return PlaybackSessionState()
        val explicitGeneration = input.explicitSeekRequestGeneration ?: input.explicitSeekTargetMs
        val explicitBinding =
            input.explicitSeekTargetMs?.let {
                "$explicitGeneration|${input.explicitSeekSourceEpoch}|$key"
            }
        val seekBindingChanged =
            explicitBinding != null && explicitBinding != state.recoverySeekBinding
        val sameGeneration = state.pendingSeekGeneration == explicitGeneration
        val pendingTarget =
            when {
                explicitBinding == null -> null
                !sameGeneration -> input.explicitSeekTargetMs
                seekBindingChanged && input.explicitSeekTargetMs != state.pendingSeekTargetMs ->
                    input.explicitSeekTargetMs
                state.pendingSeekBinding == explicitBinding -> input.explicitSeekTargetMs
                else -> null
            }
        val pendingBinding = pendingTarget?.let { explicitBinding }
        var next =
            if (state.key == key) {
                if (seekBindingChanged) {
                    state.copy(
                        lastEnginePositionMs = input.enginePositionMs ?: state.lastEnginePositionMs,
                        lastBufferedPositionMs =
                            input.bufferedPositionMs ?: state.lastBufferedPositionMs,
                        lastProgressAtMs = nowElapsedMs,
                        wasLoading = isLoading(input),
                        terminal = null,
                        retryConsumed = false,
                    )
                } else {
                    state
                }
            } else {
                PlaybackSessionState(
                    key = key,
                    lastEnginePositionMs = input.enginePositionMs,
                    lastBufferedPositionMs = input.bufferedPositionMs,
                    lastProgressAtMs = nowElapsedMs,
                    wasLoading = isLoading(input),
                )
            }
        next =
            next.copy(
                pendingSeekTargetMs = pendingTarget,
                pendingSeekGeneration = explicitGeneration,
                pendingSeekBinding = pendingBinding,
                recoverySeekBinding = explicitBinding,
            )
        if (!input.playWhenReady || input.hasPlayerError || input.hasSeekFailure) {
            return next.copy(
                lastEnginePositionMs = input.enginePositionMs ?: next.lastEnginePositionMs,
                lastBufferedPositionMs = input.bufferedPositionMs ?: next.lastBufferedPositionMs,
                lastProgressAtMs = null,
                wasLoading = isLoading(input),
                terminal = null,
                retryConsumed = false,
            )
        }
        val loading = isLoading(input)
        val progressed =
            input.enginePositionMs?.let { engine ->
                next.lastEnginePositionMs?.let { engine > it } == true
            } == true || next.wasLoading && !loading && input.isPlaying && input.isReady
        val progressAtMs = if (progressed) nowElapsedMs else next.lastProgressAtMs ?: nowElapsedMs
        next =
            next.copy(
                lastEnginePositionMs = input.enginePositionMs ?: next.lastEnginePositionMs,
                lastBufferedPositionMs = input.bufferedPositionMs ?: next.lastBufferedPositionMs,
                lastProgressAtMs = progressAtMs,
                wasLoading = loading,
                terminal = if (progressed) null else next.terminal,
                retryConsumed = if (progressed) false else next.retryConsumed,
            )
        if (next.terminal == null && nowElapsedMs - progressAtMs >= STALL_TIMEOUT_MS) {
            val kind =
                if (input.isBuffering) "PLAYBACK_PROGRESS_TIMEOUT" else "PROGRESS_UNCONFIRMED"
            val (positionMs, source) =
                retryPosition(
                    next.pendingSeekTargetMs,
                    next.lastEnginePositionMs,
                    input.persistedResumeMs,
                )
            next =
                next.copy(
                    terminal =
                        PlayerViewModel.PlaybackRecovery(
                            kind = kind,
                            positionMs = positionMs,
                            positionSource = source,
                            token = "${next.key}:$nowElapsedMs:$kind",
                        )
                )
        }
        return next
    }

    fun retrySourceIsCurrent(state: PlaybackSessionState, currentSessionKey: String?): Boolean =
        state.key == currentSessionKey

    fun consumeRetry(state: PlaybackSessionState, token: String): PlaybackSessionState? =
        state
            .takeIf { !it.retryConsumed && it.terminal?.token == token }
            ?.copy(retryConsumed = true)

    fun project(
        state: PlaybackSessionState,
        input: PlaybackSessionInput,
        suppressTerminal: Boolean = false,
    ): PlayerViewModel.PlaybackSessionUi {
        val progressTerminal =
            state.terminal?.takeUnless { state.retryConsumed || suppressTerminal }
        val showError = input.playWhenReady && input.hasPlayerError
        val showSeekFailure = input.playWhenReady && !showError && input.hasSeekFailure
        return PlayerViewModel.PlaybackSessionUi(
            pauseWhenReady = input.playWhenReady,
            showPlayerError = showError,
            showSeekFailure = showSeekFailure,
            loading =
                !showError && !showSeekFailure && (progressTerminal != null || isLoading(input)),
            terminal = null,
            lastConfirmedPositionMs = state.lastEnginePositionMs,
        )
    }

    fun advancesToNextItem(reason: Int): Boolean =
        reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM

    private fun isLoading(input: PlaybackSessionInput): Boolean =
        input.playWhenReady &&
            !input.hasPlayerError &&
            !input.hasSeekFailure &&
            (input.isBuffering || !input.isPlaying)

    private fun retryPosition(
        pendingSeekTargetMs: Long?,
        lastConfirmedPositionMs: Long?,
        persistedResumeMs: Long?,
    ): Pair<Long, PlayerViewModel.PlaybackRetryPositionSource> =
        when {
            pendingSeekTargetMs != null ->
                pendingSeekTargetMs.coerceAtLeast(0L) to
                    PlayerViewModel.PlaybackRetryPositionSource.USER_SEEK_TARGET_NEW_REQUEST
            lastConfirmedPositionMs != null ->
                lastConfirmedPositionMs.coerceAtLeast(0L) to
                    PlayerViewModel.PlaybackRetryPositionSource.ENGINE_POSITION
            persistedResumeMs != null && persistedResumeMs > 0L ->
                persistedResumeMs to PlayerViewModel.PlaybackRetryPositionSource.PERSISTED_RESUME
            else -> 0L to PlayerViewModel.PlaybackRetryPositionSource.START_ZERO
        }
}

internal fun normalizeEnginePosition(positionMs: Long?): Long? = positionMs?.takeIf {
    it >= 0L && it != C.TIME_UNSET
}

internal fun confirmedEnginePosition(
    mediaItem: MediaItem?,
    timelineEmpty: Boolean,
    playbackState: Int,
    currentPositionMs: Long,
): Long? =
    if (
        mediaItem == null ||
            timelineEmpty ||
            (playbackState != Player.STATE_READY && playbackState != Player.STATE_ENDED)
    ) {
        null
    } else {
        normalizeEnginePosition(currentPositionMs)
    }

internal fun boundedBufferedPosition(currentPositionMs: Long?, buffered: Long): Long? =
    currentPositionMs?.let { current ->
        (normalizeEnginePosition(buffered) ?: current).coerceAtLeast(current)
    }

internal fun PlayerItem.toMediaItem(application: Application): MediaItem {
    val streamUrl = mediaSourceUri
    val mediaSubtitles =
        subtitleConfigurationsForNativeFallback(
            item = this,
            fallbackLabel = application.getString(R.string.external),
        )

    Timber.d("Stream url: ${redactUrlSecrets(streamUrl)}")
    return MediaItem.Builder()
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
                        putString(PLAYER_EXTRA_MEDIA_SOURCE_ID, mediaSourceId)
                        putLong(PLAYER_EXTRA_SEEK_SESSION_START_MS, playbackPosition)
                        putString(PLAYER_EXTRA_SEEK_SOURCE_URI, streamUrl)
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
}

internal const val NATIVE_SUBTITLE_SOURCE_ID_PREFIX = "cinefin:subtitle-source:"

internal fun nativeSubtitleSourceTrackId(index: Int): String =
    "$NATIVE_SUBTITLE_SOURCE_ID_PREFIX$index"

/** 原生兜底清单：所有可自管文本源都携带 Jellyfin 源序号，原生轨身份因此可反查。 */
internal fun subtitleConfigurationsForNativeFallback(
    item: PlayerItem,
    fallbackLabel: String,
): List<MediaItem.SubtitleConfiguration> =
    item.subtitleSources
        .filter { it.isTextBased }
        .distinctBy { it.index }
        .map { source ->
            MediaItem.SubtitleConfiguration.Builder(Uri.parse(source.uri))
                .setId(nativeSubtitleSourceTrackId(source.index))
                .setLabel(source.title.ifBlank { fallbackLabel })
                .setMimeType(nativeSubtitleMimeType(source.codec))
                .setLanguage(source.language)
                .build()
        }

private fun nativeSubtitleMimeType(codec: String): String =
    when (codec.lowercase()) {
        "subrip",
        "webvtt" -> MimeTypes.APPLICATION_SUBRIP
        "ass",
        "ssa" -> MimeTypes.TEXT_SSA
        else -> MimeTypes.TEXT_UNKNOWN
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

    /** mpv remote/HLS 已由既有 20 秒 watchdog 确认无进度：同代次静默切到 ExoPlayer 一次 */
    data class FallbackToExoForProgressStall(val requestToken: String) : PlayerEvents

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

/** 字幕渲染路由：文字轨交给谁渲染 */
internal sealed interface SubtitleRouting {
    /** 自研字幕接管（或正在加载）：禁用内核文字轨 */
    data object Managed : SubtitleRouting

    /** 用户显式关闭字幕 */
    data object Off : SubtitleRouting

    /** 图形字幕 / 不可自管字幕：由内核渲染，尽量匹配到具体轨道 */
    data class Native(val source: PlayerSubtitleSource) : SubtitleRouting

    /** 没有特殊约定：交给语言优先级引擎 */
    data object Auto : SubtitleRouting
}

internal data class SubtitleRoutingUpdate(
    val parameters: TrackSelectionParameters,
    val nativeUnavailableReason: PlayerViewModel.SubtitlePrimaryUnavailableReason? = null,
)

internal fun applySubtitleRoutingParameters(
    parameters: TrackSelectionParameters,
    routing: SubtitleRouting,
    tracks: Tracks,
): SubtitleRoutingUpdate =
    when (routing) {
        SubtitleRouting.Managed,
        SubtitleRouting.Off ->
            SubtitleRoutingUpdate(
                parameters
                    .buildUpon()
                    .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                    .build()
            )
        is SubtitleRouting.Native -> applyNativeSubtitleOverride(parameters, routing.source, tracks)
        SubtitleRouting.Auto -> SubtitleRoutingUpdate(parameters)
    }

private data class MatchedNativeSubtitleTrack(
    val group: TrackGroup,
    val trackIndex: Int,
)

/** Native 只接受可由稳定源 id 唯一证明的轨道；其余情况显式关闭文字轨。 */
private fun applyNativeSubtitleOverride(
    parameters: TrackSelectionParameters,
    source: PlayerSubtitleSource,
    tracks: Tracks,
): SubtitleRoutingUpdate {
    val matched =
        if (source.isGraphic) {
            null
        } else {
            tracks.groups
                .filter { it.type == C.TRACK_TYPE_TEXT && it.isSupported }
                .flatMap { group ->
                    (0 until group.mediaTrackGroup.length).mapNotNull { trackIndex ->
                        val format = group.mediaTrackGroup.getFormat(trackIndex)
                        if (nativeSubtitleSourceIndex(format.id) == source.index) {
                            MatchedNativeSubtitleTrack(group.mediaTrackGroup, trackIndex)
                        } else {
                            null
                        }
                    }
                }
                .singleOrNull()
        }
    if (matched == null) {
        return SubtitleRoutingUpdate(
            parameters =
                parameters
                    .buildUpon()
                    .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                    .build(),
            nativeUnavailableReason =
                PlayerViewModel.SubtitlePrimaryUnavailableReason.NativeSourceIdentityUnavailable,
        )
    }
    return SubtitleRoutingUpdate(
        parameters
            .buildUpon()
            .setOverrideForType(TrackSelectionOverride(matched.group, matched.trackIndex))
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .build()
    )
}

private fun nativeSubtitleSourceIndex(formatId: String?): Int? {
    val id = formatId?.takeIf { it.startsWith(NATIVE_SUBTITLE_SOURCE_ID_PREFIX) } ?: return null
    val index = id.removePrefix(NATIVE_SUBTITLE_SOURCE_ID_PREFIX).toIntOrNull() ?: return null
    if (index < 0 || nativeSubtitleSourceTrackId(index) != id) return null
    return index
}

/** 当前字幕 overlay 状态到内核渲染路由的唯一判定入口。 */
internal fun resolveSubtitleRouting(state: SubtitleOverlayState): SubtitleRouting {
    if (state.disabledByUser) return SubtitleRouting.Off
    // 主路被「移到次路」清空时不是用户关闭字幕；次路仍由 Compose 渲染，
    // 加载期间必须禁掉内核文字轨；失败后则显式关闭，不能继续留下 Managed-only 空态。
    if (state.primaryIndex == null && state.secondaryIndex != null) {
        return if (state.secondaryManaged || state.loading) {
            SubtitleRouting.Managed
        } else {
            SubtitleRouting.Off
        }
    }
    val selected = state.sources.firstOrNull { it.index == state.primaryIndex }
    if (selected != null) {
        // 已选中的可自管文本轨一律先由 App 接管；即使尚未发布 loading
        // 也不能退回 Auto 让语言引擎挑另一条轨。加载失败后显式回退到
        // 同一源对应的内核轨，保证选中身份确定。
        if (selected.isTextBased && !state.primaryRenderFailed) {
            return SubtitleRouting.Managed
        }
        return SubtitleRouting.Native(selected)
    }
    return SubtitleRouting.Auto
}

/** PlaybackInfo / Jellyfin 条目解析含真实网络，不能继承 viewModelScope 的主线程调度。 */
internal suspend fun <T> runPlaybackStartupRequest(block: suspend () -> T): T =
    withContext(Dispatchers.IO) { block() }

internal fun playbackStartFailureMessage(error: Throwable, fallback: String): String =
    error.localizedMessage?.takeIf { it.isNotBlank() } ?: fallback
