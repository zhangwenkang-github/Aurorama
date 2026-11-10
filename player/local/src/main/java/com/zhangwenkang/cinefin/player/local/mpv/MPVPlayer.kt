package com.zhangwenkang.cinefin.player.local.mpv

import android.content.Context
import android.graphics.SurfaceTexture
import android.media.AudioManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.TextureView
import androidx.core.content.getSystemService
import androidx.media3.common.AdPlaybackState
import androidx.media3.common.AudioAttributes
import androidx.media3.common.BasePlayer
import androidx.media3.common.C
import androidx.media3.common.DeviceInfo
import androidx.media3.common.FlagSet
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Player.Commands
import androidx.media3.common.Timeline
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.audio.AudioFocusRequestCompat
import androidx.media3.common.audio.AudioManagerCompat
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.Clock
import androidx.media3.common.util.ListenerSet
import androidx.media3.common.util.Size
import androidx.media3.common.util.Util
import com.zhangwenkang.cinefin.player.core.domain.models.PLAYER_EXTRA_MEDIA_SOURCE_ID
import com.zhangwenkang.cinefin.player.core.domain.models.PLAYER_EXTRA_SUBTITLE_SOURCES
import com.zhangwenkang.cinefin.player.core.domain.models.PlayerMediaInfo
import com.zhangwenkang.cinefin.player.core.domain.models.PlayerSubtitleSource
import com.zhangwenkang.cinefin.player.core.domain.models.SubtitleLayout
import com.zhangwenkang.cinefin.player.core.domain.models.SubtitleStyle
import com.zhangwenkang.cinefin.player.local.domain.VideoMirrorMode
import com.zhangwenkang.cinefin.player.local.domain.cropScale
import com.zhangwenkang.cinefin.player.local.domain.mpvResizeProperties
import com.zhangwenkang.cinefin.player.local.presentation.mpvMediaInfo
import com.zhangwenkang.cinefin.player.local.presentation.normalizeEnginePosition
import dev.jdtech.mpv.MPVLib
import dev.jdtech.mpv.MPVLib.MpvEvent
import dev.jdtech.mpv.MPVLib.MpvFormat
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.ln
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import timber.log.Timber

interface MpvSeekEventListener {
    fun onMpvSeekAccepted()

    fun onMpvPlaybackRestarted(positionMs: Long)
}

internal data class MpvSourcePositionState(
    val sourceEpoch: Long = 0L,
    val positionMs: Long? = null,
    val cacheEndMs: Long? = null,
    val pendingDiscontinuityFromMs: Long? = null,
    val isAnchored: Boolean = false,
) {
    val bufferedPositionMs: Long?
        get() =
            positionMs
                ?.takeIf { isAnchored }
                ?.let { position -> cacheEndMs?.takeIf { it > position } ?: position }

    fun replaceSource(epoch: Long): MpvSourcePositionState = MpvSourcePositionState(epoch)

    fun acceptPosition(epoch: Long, positionMs: Long): MpvSourcePositionState? = takeIf {
        sourceEpoch == epoch && isAnchored
    }
        ?.let { normalizeEnginePosition(positionMs)?.let(::copyPosition) }

    fun acceptCache(epoch: Long, cacheEndMs: Long): MpvSourcePositionState? = takeIf {
        sourceEpoch == epoch && isAnchored && positionMs != null
    }
        ?.let { normalizeEnginePosition(cacheEndMs)?.let { copy(cacheEndMs = it) } }

    fun seekStarted(epoch: Long): MpvSourcePositionState? = takeIf {
        sourceEpoch == epoch
    }
        ?.copy(cacheEndMs = null, pendingDiscontinuityFromMs = positionMs)

    fun restart(epoch: Long, positionMs: Long): MpvSourcePositionState? = takeIf {
        sourceEpoch == epoch
    }
        ?.let { normalizeEnginePosition(positionMs)?.let(::copyRestart) }

    fun consumeDiscontinuity(epoch: Long): Pair<MpvSourcePositionState, Long>? {
        if (sourceEpoch != epoch) return null
        val from = pendingDiscontinuityFromMs ?: return null
        return copy(pendingDiscontinuityFromMs = null) to from
    }

    private fun copyPosition(positionMs: Long): MpvSourcePositionState =
        copy(positionMs = positionMs)

    private fun copyRestart(positionMs: Long): MpvSourcePositionState =
        copy(
            positionMs = positionMs,
            cacheEndMs = null,
            pendingDiscontinuityFromMs = this.positionMs ?: positionMs,
            isAnchored = true,
        )
}

class MPVPlayer(
    context: Context,
    private val audioAttributes: AudioAttributes = AudioAttributes.DEFAULT,
    private val handleAudioFocus: Boolean = true,
    private var trackSelectionParameters: TrackSelectionParameters =
        TrackSelectionParameters.DEFAULT,
    private var seekBackIncrement: Long = C.DEFAULT_SEEK_BACK_INCREMENT_MS,
    private var seekForwardIncrement: Long = C.DEFAULT_SEEK_FORWARD_INCREMENT_MS,
    private val pauseAtEndOfMediaItems: Boolean = false,
    private val videoOutput: String = "gpu-next",
    audioOutput: String = "aaudio",
    hwDec: String = "mediacodec",
) : BasePlayer(), MPVLib.EventObserver, AudioManager.OnAudioFocusChangeListener {
    private val mpvLib: MPVLib
    private val audioManager: AudioManager by lazy { context.getSystemService()!! }
    private var audioFocusCallback: () -> Unit = {}
    private lateinit var audioFocusRequest: AudioFocusRequestCompat
    private val handler: Handler = Handler(context.mainLooper)

    /** 同步 `loadfile` 等命令必须串行留在专用线程，避免主线程 ANR；libmpv 保证提交顺序。 */
    private val commandThread: HandlerThread = HandlerThread("mpv-command").apply { start() }
    private val commandHandler: Handler = Handler(commandThread.looper)
    private val commandsClosed = AtomicBoolean(false)
    private val seekEventListeners = mutableSetOf<MpvSeekEventListener>()
    private val seekEventRelay = MpvSeekEventRelay { event ->
        when (event) {
            MpvSeekEvent.SeekAccepted ->
                seekEventListeners.toList().forEach { it.onMpvSeekAccepted() }
            is MpvSeekEvent.PlaybackRestarted ->
                seekEventListeners.toList().forEach { it.onMpvPlaybackRestarted(event.positionMs) }
        }
    }

    private constructor(
        builder: Builder
    ) : this(
        context = builder.context,
        audioAttributes = builder.audioAttributes,
        handleAudioFocus = builder.handleAudioFocus,
        trackSelectionParameters = builder.trackSelectionParameters,
        seekBackIncrement = builder.seekBackIncrementMs,
        seekForwardIncrement = builder.seekForwardIncrementMs,
        pauseAtEndOfMediaItems = builder.pauseAtEndOfMediaItems,
        videoOutput = builder.videoOutput,
        audioOutput = builder.audioOutput,
        hwDec = builder.hwDec,
    )

    class Builder(val context: Context) {
        var audioAttributes: AudioAttributes = AudioAttributes.DEFAULT
            private set

        var handleAudioFocus: Boolean = true
            private set

        var trackSelectionParameters: TrackSelectionParameters = TrackSelectionParameters.DEFAULT
            private set

        var seekBackIncrementMs: Long = C.DEFAULT_SEEK_BACK_INCREMENT_MS
            private set

        var seekForwardIncrementMs: Long = C.DEFAULT_SEEK_FORWARD_INCREMENT_MS
            private set

        var pauseAtEndOfMediaItems: Boolean = false
            private set

        var videoOutput: String = "gpu-next"
            private set

        var audioOutput: String = "aaudio"
            private set

        var hwDec: String = "mediacodec"
            private set

        fun setAudioAttributes(audioAttributes: AudioAttributes, handleAudioFocus: Boolean) =
            apply {
                this.audioAttributes = audioAttributes
                this.handleAudioFocus = handleAudioFocus
            }

        fun setTrackSelectionParameters(trackSelectionParameters: TrackSelectionParameters) =
            apply {
                this.trackSelectionParameters = trackSelectionParameters
            }

        fun setSeekBackIncrementMs(seekBackIncrementMs: Long) = apply {
            this.seekBackIncrementMs = seekBackIncrementMs
        }

        fun setSeekForwardIncrementMs(seekForwardIncrementMs: Long) = apply {
            this.seekForwardIncrementMs = seekForwardIncrementMs
        }

        fun setPauseAtEndOfMediaItems(pauseAtEndOfMediaItems: Boolean) = apply {
            this.pauseAtEndOfMediaItems = pauseAtEndOfMediaItems
        }

        fun setVideoOutput(videoOutput: String) = apply { this.videoOutput = videoOutput }

        fun setAudioOutput(audioOutput: String) = apply { this.audioOutput = audioOutput }

        fun setHwDec(hwDec: String) = apply { this.hwDec = hwDec }

        fun build() = MPVPlayer(this)
    }

    init {
        val configDir = File(context.filesDir, "mpv")
        val cacheDir = File(context.cacheDir, "mpv")

        setupDirectories(context, configDir, cacheDir)

        mpvLib =
            MPVLib.create(context) ?: throw IllegalStateException("MPVLib.create() returned null")

        // General
        mpvLib.setOptionString("config", "yes")
        mpvLib.setOptionString("config-dir", configDir.path)
        for (opt in arrayOf("gpu-shader-cache-dir", "icc-cache-dir")) mpvLib.setOptionString(
            opt,
            cacheDir.path,
        )
        mpvLib.setOptionString("profile", "fast")
        mpvLib.setOptionString("vo", videoOutput)
        mpvLib.setOptionString("ao", audioOutput)
        mpvLib.setOptionString("gpu-context", "android")
        mpvLib.setOptionString("opengl-es", "yes")

        // Hardware video decoding
        mpvLib.setOptionString("hwdec", hwDec)
        mpvLib.setOptionString("hwdec-codecs", "h264,hevc,mpeg4,mpeg2video,vp8,vp9,av1")
        // W17 验收：把实际生效的 hwdec 打进日志（回退链第 3 档必须看到 hwdec=no）
        Timber.i("MPVPlayer hwdec=%s vo=%s", hwDec, videoOutput)

        // TLS
        mpvLib.setOptionString("tls-verify", "no")

        // Cache
        mpvLib.setOptionString("cache", "yes")
        mpvLib.setOptionString("cache-pause-initial", "yes")
        mpvLib.setOptionString("demuxer-max-bytes", "64MiB")
        mpvLib.setOptionString("demuxer-max-back-bytes", "32MiB")

        // Subs
        mpvLib.setOptionString("sub-scale-with-window", "yes")
        mpvLib.setOptionString("sub-use-margins", "no")

        /*
         * Language（bug ① 修复点）：把语言优先级整份交给 mpv，让 mpv 自己按列表顺序挑轨。
         *
         * 旧实现取 firstOrNull() 再 split("-").last()，把 "zh-Hans" 截成 "Hans" 写进 slang，
         * 没有任何轨道会命中 → mpv 打开新片不选字幕。alang / slang 本身就支持逗号分隔的优先列表
         * （mpv 自己会做 ISO 639-1 / 639-2 与地区后缀的归一化），所以这里直接原样传全量标签。
         */
        trackSelectionParameters.preferredAudioLanguages
            .takeIf { it.isNotEmpty() }
            ?.let { mpvLib.setOptionString("alang", it.joinToString(",")) }
        trackSelectionParameters.preferredTextLanguages
            .takeIf { it.isNotEmpty() }
            ?.let { mpvLib.setOptionString("slang", it.joinToString(",")) }

        // Other options
        mpvLib.setOptionString("force-window", "no")
        mpvLib.setOptionString("keep-open", "always")
        mpvLib.setOptionString("save-position-on-quit", "no")
        mpvLib.setOptionString("ytdl", "no")
        mpvLib.setOptionString("audio-set-media-role", "yes")

        mpvLib.init()

        mpvLib.addObserver(this)

        // Observe properties
        data class Property(val name: String, val format: Int)
        arrayOf(
                Property("track-list", MpvFormat.MPV_FORMAT_STRING),
                Property("paused-for-cache", MpvFormat.MPV_FORMAT_FLAG),
                Property("eof-reached", MpvFormat.MPV_FORMAT_FLAG),
                Property("seekable", MpvFormat.MPV_FORMAT_FLAG),
                Property("time-pos", MpvFormat.MPV_FORMAT_INT64),
                Property("duration", MpvFormat.MPV_FORMAT_INT64),
                Property("demuxer-cache-time", MpvFormat.MPV_FORMAT_INT64),
                Property("speed", MpvFormat.MPV_FORMAT_DOUBLE),
                Property("playlist-count", MpvFormat.MPV_FORMAT_INT64),
                Property("playlist-current-pos", MpvFormat.MPV_FORMAT_INT64),
            )
            .forEach { (name, format) -> mpvLib.observeProperty(name, format) }

        val audioSessionId = audioManager.generateAudioSessionId()
        if (audioSessionId != AudioManager.ERROR) {
            mpvLib.setPropertyInt("audiotrack-session-id", audioSessionId)
            mpvLib.setPropertyInt("aaudio-session-id", audioSessionId)
        }

        if (handleAudioFocus) {
            audioFocusRequest =
                AudioFocusRequestCompat.Builder(AudioManagerCompat.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(audioAttributes)
                    .setOnAudioFocusChangeListener(this)
                    .build()
            val res = AudioManagerCompat.requestAudioFocus(audioManager, audioFocusRequest)
            if (res != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                mpvLib.setPropertyBoolean("pause", true)
            }
        }
    }

    /**
     * 把 mpv 命令投递到专用线程，调用方（主线程）不再同步等待 mpv core。
     *
     * 命令按提交顺序串行执行；[release] 之后提交的命令会被丢弃。
     */
    private fun postCommand(command: Array<String>) {
        if (commandsClosed.get()) return
        commandHandler.post {
            if (commandsClosed.get()) return@post
            runCatching { mpvLib.command(command) }
                .onFailure { Timber.w(it, "mpv 命令执行失败：%s", command.firstOrNull()) }
        }
    }

    private fun setupDirectories(context: Context, configDir: File, cacheDir: File) {
        Timber.i("mpv config dir: $configDir")
        Timber.i("mpv cache dir: $cacheDir")

        if (!configDir.exists()) configDir.mkdirs()
        if (!cacheDir.exists()) cacheDir.mkdirs()

        writeFontsConf(context, configDir)
    }

    private fun writeFontsConf(context: Context, configDir: File) {
        val configFile = File(configDir, "fonts.conf")
        if (configFile.exists()) return

        val cacheDir = File(context.cacheDir, "fontconfig")
        if (!cacheDir.exists()) cacheDir.mkdirs()

        val config =
            """
            <fontconfig>
                <dir>/system/fonts/</dir>
                <dir>/product/fonts/</dir>

                <cachedir>${cacheDir.path}</cachedir>

                <alias>
                    <family>serif</family>
                    <prefer><family>Noto Serif</family></prefer>
                </alias>

                <alias>
                    <family>sans-serif</family>
                    <prefer>
                        <family>Roboto</family>
                        <family>Noto Sans</family>
                    </prefer>
                </alias>

                <alias>
                    <family>monospace</family>
                    <prefer><family>Droid Sans Mono</family></prefer>
                </alias>

            </fontconfig>
        """
                .trimIndent()

        try {
            configFile.writeText(config)
        } catch (e: IOException) {
            Timber.w("Failed to write fonts.conf: $e")
        }
    }

    // Listeners and notification.
    private val listeners: ListenerSet<Player.Listener> =
        ListenerSet(context.mainLooper, Clock.DEFAULT) { listener: Player.Listener, flags: FlagSet
            ->
            listener.onEvents(this, Player.Events(flags))
        }
    private val videoListeners = CopyOnWriteArraySet<Player.Listener>()

    // Internal state.
    private var internalMediaItems = mutableListOf<MediaItem>()

    @Player.State private var playbackState: Int = STATE_IDLE
    private var currentPlayWhenReady: Boolean = false

    @Player.RepeatMode private val repeatMode: Int = REPEAT_MODE_OFF
    private var currentTracks: Tracks = Tracks.EMPTY
    private var playbackParameters: PlaybackParameters = PlaybackParameters.DEFAULT

    /**
     * mpv `speed` 属性的当前值（本端最近一次写入，或 mpv 上报的变化）。
     *
     * 与 [playbackParameters] 分开维护：mpv 换集（loadfile）不会重置 `speed` 属性，而 [resetInternalState] 只把
     * [playbackParameters] 复位成 1×——若只按 [playbackParameters] 判定要不要写属性，就会出现「字段已是 1×、mpv
     * 仍停在旧速度」时被漏写（#9：面板读数 / 媒体会话速度与实际播放不一致，新会话回落 1× 不生效）。
     */
    private var mpvSpeed: Float = 1f

    // MPV Custom
    private var isPlayerReady: Boolean = false
    private var isSeekable: Boolean = false
    private var currentMediaItemIndex: Int = 0
    private val sourceEpoch = AtomicLong()
    private var sourcePosition = MpvSourcePositionState()
    private var pendingPlaylistRemap = false
    private var currentDurationMs: Long? = null
    private var initialCommands = mutableListOf<Array<String>>()
    private var initialIndex: Int = 0
    private var initialSeekTo: Long = 0L
    private var oldMediaItem: MediaItem? = null

    /** 仅在没有内嵌字幕轨时注入服务端字幕，避免 DirectPlay 出现重复轨道。 */
    private var pendingServerSubtitles: List<PlayerSubtitleSource>? = null

    /** 侧载字幕路径到 mpv track id 的映射，仅在主线程随 track-list 重解析。 */
    private val externalSubtitleTrackIds = ConcurrentHashMap<String, Int>()

    /** 防止 track-list 回包前重复 `sub-add` 同一条侧载字幕。 */
    private val pendingSideloadedSubtitleAdds = CopyOnWriteArraySet<String>()

    /** App 字幕模式的自动选轨意图：true = sid=auto（按 slang / 默认轨选），false = sid=no */
    private var subtitleAutoSelect: Boolean = true

    /** 最近一次落地的字幕外观；双字幕预留区按它重新计算 `sub-pos`。 */
    private var appliedSubtitleStyle: SubtitleStyle = SubtitleStyle()
    private var appliedSubtitleLayout: SubtitleLayout = SubtitleLayout()

    /** 进入双字幕前 mpv 的 `sub-ass-override`，退出双字幕时原样恢复。 */
    private var savedSubAssOverride: String? = null

    /** App 侧 libass 接管主字幕前 mpv 的 `sub-visibility`；null = 当前没有接管。 */
    private var savedNativeSubVisibility: String? = null

    /** 当前是否正在为 Compose 次字幕预留主字幕位置；改字号 / 位置时沿用。 */
    private var subtitleReservation: Float? = null

    /** 用 EOF、主动操作计数和时间窗共同区分正常换源与真实播放失败。 */
    private var intentionalEndFileBudget: Int = 0
    private var suppressEndFileErrorUntilMs: Long = 0L

    /** 记录主动操作预计产生的 END_FILE 条数。 */
    private fun markIntentionalEndFile(count: Int = 1) {
        // 只有「当前已经有文件在播」时才会有旧文件的 END_FILE 需要抵消；
        // 起播阶段（还没加载任何文件）不留预算，否则会把首个文件的打开失败一起吞掉
        if (!isPlayerReady) return
        intentionalEndFileBudget += count
        suppressEndFileErrorUntilMs = SystemClock.elapsedRealtime() + END_FILE_SUPPRESS_WINDOW_MS
    }

    // mpv events
    override fun eventProperty(property: String) {
        // Nothing to do...
    }

    override fun eventProperty(property: String, value: String) {
        handler.post {
            when (property) {
                "track-list" -> {
                    val newTracks = getTracks(value)
                    currentTracks = newTracks
                    updateExternalSubtitleTracks(value)
                    listeners.sendEvent(EVENT_TRACKS_CHANGED) { listener ->
                        listener.onTracksChanged(currentTracks)
                    }
                }
            }
        }
    }

    override fun eventProperty(property: String, value: Boolean) {
        handler.post {
            when (property) {
                "eof-reached" -> {
                    if (value && isPlayerReady) {
                        if (currentMediaItemIndex < (internalMediaItems.size - 1)) {
                            if (pauseAtEndOfMediaItems) {
                                setPlayerStateAndNotifyIfChanged(
                                    playWhenReady = false,
                                    playWhenReadyChangeReason =
                                        PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM,
                                    playbackState = STATE_READY,
                                )
                            } else {
                                prepareMediaItem(currentMediaItemIndex + 1)
                                play()
                            }
                        } else {
                            setPlayerStateAndNotifyIfChanged(
                                playWhenReady = false,
                                playWhenReadyChangeReason =
                                    PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM,
                                playbackState = STATE_ENDED,
                            )
                            resetInternalState()
                        }
                    }
                }
                "paused-for-cache" -> {
                    if (isPlayerReady) {
                        if (value) {
                            setPlayerStateAndNotifyIfChanged(playbackState = STATE_BUFFERING)
                        } else {
                            setPlayerStateAndNotifyIfChanged(playbackState = STATE_READY)
                        }
                    }
                }
                "seekable" -> {
                    if (isSeekable != value) {
                        isSeekable = value
                    }
                }
            }
        }
    }

    override fun eventProperty(property: String, value: Long) {
        val epoch = sourceEpoch.get()
        handler.post {
            if (epoch != sourceEpoch.get()) return@post
            when (property) {
                "time-pos" -> updateEnginePosition(epoch, value * C.MILLIS_PER_SECOND)
                "duration" -> {
                    val newDuration = value * C.MILLIS_PER_SECOND
                    if (currentDurationMs != newDuration) {
                        currentDurationMs = newDuration
                    }
                }
                "demuxer-cache-time" ->
                    sourcePosition =
                        sourcePosition.acceptCache(epoch, value * C.MILLIS_PER_SECOND)
                            ?: sourcePosition
                "playlist-count" -> {
                    listeners.sendEvent(EVENT_TIMELINE_CHANGED) { listener ->
                        listener.onTimelineChanged(
                            timeline,
                            TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED,
                        )
                    }
                }
                "playlist-current-pos" -> {
                    if (value < 0) {
                        return@post
                    }
                    val nextIndex = value.toInt()
                    if (
                        pendingPlaylistRemap &&
                            sourceIdentity(internalMediaItems, nextIndex) !=
                                sourceIdentity(internalMediaItems, currentMediaItemIndex)
                    ) {
                        return@post
                    }
                    pendingPlaylistRemap = false
                    commitPlaylistState(internalMediaItems, nextIndex, remapPending = false)
                    val newMediaItem = currentMediaItem
                    if (oldMediaItem?.mediaId != newMediaItem?.mediaId) {
                        oldMediaItem = newMediaItem
                        listeners.sendEvent(EVENT_MEDIA_ITEM_TRANSITION) { listener ->
                            listener.onMediaItemTransition(
                                newMediaItem,
                                MEDIA_ITEM_TRANSITION_REASON_AUTO,
                            )
                        }
                    }
                }
            }
        }
    }

    override fun eventProperty(property: String, value: Double) {
        handler.post {
            when (property) {
                "speed" -> {
                    val speed = value.toFloat()
                    mpvSpeed = speed
                    playbackParameters = playbackParameters.withSpeed(speed)
                    listeners.sendEvent(EVENT_PLAYBACK_PARAMETERS_CHANGED) { listener ->
                        listener.onPlaybackParametersChanged(playbackParameters)
                    }
                }
            }
        }
    }

    override fun event(eventId: Int) {
        val epoch = sourceEpoch.get()
        handler.post {
            // release 之后主线程队列里可能还排着事件回调：此时 mpv 可能正在销毁，必须短路
            if (released) return@post
            when (eventId) {
                MpvEvent.MPV_EVENT_START_FILE -> {
                    if (!isPlayerReady) {
                        for (command in initialCommands) {
                            postCommand(command)
                        }
                    }
                }
                MpvEvent.MPV_EVENT_FILE_LOADED -> {
                    isSeekable = mpvLib.getPropertyBoolean("seekable") == true
                    // 新文件已经解析成功：旧文件的 END_FILE 预算作废（避免吞掉之后的真实失败）
                    intentionalEndFileBudget = 0
                    currentDurationMs =
                        (mpvLib.getPropertyDouble("duration")?.times(C.MILLIS_PER_SECOND))?.toLong()
                    // 容器解析完成：此时 track-list 是完整的，按「有无内嵌字幕」决定是否注入服务端字幕
                    maybeAddServerSubtitles()
                }
                MpvEvent.MPV_EVENT_SEEK -> {
                    sourcePosition = sourcePosition.seekStarted(epoch) ?: return@post
                    setPlayerStateAndNotifyIfChanged(playbackState = STATE_BUFFERING)
                    seekEventRelay.onSeekEvent()
                }
                MpvEvent.MPV_EVENT_PLAYBACK_RESTART -> {
                    val restartedPositionMs =
                        mpvLib.getPropertyDouble("time-pos")?.times(C.MILLIS_PER_SECOND)?.toLong()
                            ?: sourcePosition.positionMs
                            ?: return@post
                    sourcePosition =
                        sourcePosition.restart(epoch, restartedPositionMs) ?: return@post
                    publishPositionDiscontinuity(epoch)
                    if (!isPlayerReady) {
                        isPlayerReady = true
                        seekTo(C.TIME_UNSET)
                        if (playWhenReady) {
                            Timber.d("Starting playback...")
                            mpvLib.setPropertyBoolean("pause", false)
                        }
                        for (videoListener in videoListeners) {
                            videoListener.onRenderedFirstFrame()
                        }
                    } else {
                        setPlayerStateAndNotifyIfChanged(playbackState = STATE_READY)
                    }
                    seekEventRelay.onPlaybackRestartedEvent(restartedPositionMs)
                }
                /* 非 EOF 且不在主动操作预算内的 END_FILE 走 Player.Listener.onPlayerError 回退链。 */
                MpvEvent.MPV_EVENT_END_FILE -> {
                    val eofReached = mpvLib.getPropertyBoolean("eof-reached") == true
                    val withinWindow = SystemClock.elapsedRealtime() < suppressEndFileErrorUntilMs
                    if (!withinWindow) intentionalEndFileBudget = 0
                    val intentional =
                        released ||
                            commandsClosed.get() ||
                            (withinWindow && intentionalEndFileBudget > 0)
                    if (intentional && withinWindow && intentionalEndFileBudget > 0) {
                        intentionalEndFileBudget--
                    }
                    val suppressed = released || commandsClosed.get() || intentional
                    if (!eofReached && !suppressed) {
                        val error =
                            PlaybackException(
                                "mpv 播放失败：文件提前结束（eof-reached=false）",
                                null,
                                PlaybackException.ERROR_CODE_UNSPECIFIED,
                            )
                        Timber.e("MPVPlayer 播放失败：END_FILE 未到文件末尾（eof-reached=false），上报回退链")
                        listeners.sendEvent(EVENT_PLAYER_ERROR) { listener ->
                            listener.onPlayerError(error)
                        }
                        setPlayerStateAndNotifyIfChanged(
                            playWhenReady = false,
                            playWhenReadyChangeReason = PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST,
                            playbackState = STATE_IDLE,
                        )
                    }
                }
                else -> Unit
            }
        }
    }

    private fun updateEnginePosition(epoch: Long, positionMs: Long) {
        sourcePosition = sourcePosition.acceptPosition(epoch, positionMs) ?: return
    }

    private fun publishPositionDiscontinuity(epoch: Long) {
        val (next, fromPositionMs) = sourcePosition.consumeDiscontinuity(epoch) ?: return
        sourcePosition = next
        val positionMs = next.positionMs ?: return
        listeners.sendEvent(EVENT_POSITION_DISCONTINUITY) { listener ->
            listener.onPositionDiscontinuity(
                positionInfo(fromPositionMs),
                positionInfo(positionMs),
                DISCONTINUITY_REASON_SEEK,
            )
        }
    }

    private fun positionInfo(positionMs: Long) =
        Player.PositionInfo(
            null,
            currentMediaItemIndex,
            currentMediaItem ?: MediaItem.EMPTY,
            null,
            currentMediaItemIndex,
            positionMs,
            positionMs,
            C.INDEX_UNSET,
            C.INDEX_UNSET,
        )

    private fun setPlayerStateAndNotifyIfChanged(
        playWhenReady: Boolean = getPlayWhenReady(),
        @Player.PlayWhenReadyChangeReason
        playWhenReadyChangeReason: Int = PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST,
        @Player.State playbackState: Int = getPlaybackState(),
    ) {
        val wasLoading = isLoading()
        var playerStateChanged = false
        val wasPlaying = isPlaying
        if (playbackState != getPlaybackState()) {
            this.playbackState = playbackState
            listeners.queueEvent(EVENT_PLAYBACK_STATE_CHANGED) { listener ->
                listener.onPlaybackStateChanged(playbackState)
            }
            playerStateChanged = true
        }
        if (wasLoading != isLoading()) {
            listeners.queueEvent(EVENT_IS_LOADING_CHANGED) { listener ->
                listener.onIsLoadingChanged(isLoading())
            }
        }
        if (playWhenReady != getPlayWhenReady()) {
            this.currentPlayWhenReady = playWhenReady
            listeners.queueEvent(EVENT_PLAY_WHEN_READY_CHANGED) { listener ->
                listener.onPlayWhenReadyChanged(playWhenReady, playWhenReadyChangeReason)
            }
            playerStateChanged = true
        }
        if (playerStateChanged) {
            listeners.queueEvent(C.INDEX_UNSET) { listener ->
                listener.onPlaybackStateChanged(playbackState)
            }
        }
        if (wasPlaying != isPlaying) {
            listeners.queueEvent(EVENT_IS_PLAYING_CHANGED) { listener ->
                listener.onIsPlayingChanged(isPlaying)
            }
        }
        listeners.flushEvents()
    }

    /**
     * Select a Track or disable a [MPVTrackType] in the current player.
     *
     * @param trackType The [MPVTrackType]
     * @param id Id to select or [C.INDEX_UNSET] to disable [MPVTrackType]
     * @return true if the track is or was already selected
     */
    private fun selectTrack(trackType: MPVTrackType, id: String) {
        mpvLib.setPropertyString(trackType.type, id)
    }

    // ---------- 字幕：延迟 / 双语 / 外观（§1.1） ----------
    // mpv 原生支持这些能力，和 ExoPlayer 的自研字幕渲染共用同一套面板，
    // 差异只在底层实现：这里把面板设置翻译成 mpv 属性。

    /** 音轨延迟（毫秒）；正 = 声音延后（§1.2） */
    fun setAudioDelay(delayMs: Long) {
        Timber.d("mpv 音频延迟 = %d ms", delayMs)
        mpvLib.setPropertyDouble("audio-delay", delayMs / 1000.0)
    }

    fun addSeekEventListener(listener: MpvSeekEventListener) {
        seekEventListeners.add(listener)
    }

    fun removeSeekEventListener(listener: MpvSeekEventListener) {
        seekEventListeners.remove(listener)
    }

    /** 字幕延迟（毫秒）；正 = 字幕延后出现 */
    fun setSubtitleDelay(delayMs: Long) {
        Timber.d("mpv 字幕延迟 = %d ms", delayMs)
        mpvLib.setPropertyDouble("sub-delay", delayMs / 1000.0)
        logSubtitleState()
    }

    /** 选主字幕（mpv track id，见 [getCurrentTracks] 的 Format.id）；null = 关闭 */
    fun selectSubtitleTrack(trackId: Int?) {
        mpvLib.setPropertyString("sid", trackId?.toString() ?: "no")
        logSubtitleState()
    }

    /**
     * 当前 `sid` 的原始值（数字 / `no` / `auto`）；读不到返回 null。
     *
     * 与 [getCurrentTracks] 的 `selected` 快照不同，`sid` 是同步属性：刚写完立刻能读回新值， 可供渲染层在 `track-list`
     * 异步回调之前判断主字幕是否已经切换。
     */
    fun currentSubtitleSelection(): String? = runCatching {
        mpvLib.getPropertyString("sid")
    }
        .getOrNull()
        ?.trim()

    /** 排障：打印 mpv 眼里的字幕状态（sid / 可见性 / 延迟 / 当前文本） */
    fun logSubtitleState() {
        val sid = mpvLib.getPropertyString("sid")
        val visibility = mpvLib.getPropertyBoolean("sub-visibility")
        val delay = mpvLib.getPropertyDouble("sub-delay")
        val text = mpvLib.getPropertyString("sub-text")
        Timber.d(
            "mpv 字幕状态: sid=%s visible=%s delay=%s text=%s",
            sid,
            visibility,
            delay,
            text?.take(60),
        )
    }

    /**
     * 次字幕（mpv 的 track id 字符串）。
     *
     * 传 null 关闭。注意必须用 mpv 的 id（见 [getCurrentTracks] 里 Format.id）， 不是字幕数组下标。
     */
    fun setSecondarySubtitle(trackId: String?) {
        val value = trackId?.takeIf { it.isNotBlank() } ?: "no"
        mpvLib.setPropertyString("secondary-sid", value)
    }

    /** 把面板的字幕外观（单字幕原生路径）翻译成 mpv 的 sub-* 属性 */
    fun applySubtitleStyle(
        style: SubtitleStyle,
        layout: SubtitleLayout = SubtitleLayout(),
    ) {
        appliedSubtitleStyle = style
        appliedSubtitleLayout = layout
        // 字号：mpv 默认 55；直接按倍率缩放
        mpvLib.setPropertyDouble("sub-font-size", 55.0 * style.textScale)
        mpvLib.setPropertyString("sub-color", style.textColor.toMpvColor())
        mpvLib.setPropertyString("sub-back-color", style.backgroundArgb.toMpvColor())
        mpvLib.setPropertyDouble("sub-border-size", style.edgeWidthDp.toDouble())
        // sub-pos 是「距底部百分比」的互补值：100 = 贴底，65 = 靠上
        applyMpvSubtitlePosition(reservedFraction = subtitleReservation)
    }

    /**
     * 为 Compose 次字幕预留一条主字幕上方的安全区。
     *
     * [reservedFraction] = 次字幕可见顶边到安全底边的距离 + 4dp 间隙占视频高度的比例；null = 退出双字幕，恢复面板位置。
     *
     * 双字幕期间临时使用 `sub-ass-override=strip` 去掉 ASS 原生 `\pos`，否则固定坐标可以越过 App 的统一安全区；主字幕仍保留文本内容与面板颜色 /
     * 描边。单字幕时立即恢复进入双字幕前的 ASS override，不改变原有特效路径。
     *
     * 文本主字幕进入双字幕后由 App 侧 libass 接管（见 [applyNativePrimarySubtitleVisible]），不再走这条百分比兜底； 只有图形字幕等无法交给
     * libass 的主字幕才继续按 [reservedFraction] 预留。
     */
    fun applySecondarySubtitleReservation(reservedFraction: Float?) {
        if (released) return
        // 原生次字幕轨已由 Compose 次字幕接管，避免同一句出现两次。
        mpvLib.setPropertyString("secondary-sid", "no")
        if (reservedFraction == null) {
            subtitleReservation = null
            savedSubAssOverride?.let { previous ->
                setMpvString("sub-ass-override", previous)
                savedSubAssOverride = null
            }
            applyMpvSubtitlePosition(reservedFraction = null)
            return
        }
        subtitleReservation = reservedFraction
        if (savedSubAssOverride == null) {
            savedSubAssOverride = readMpvString("sub-ass-override") ?: "yes"
        }
        setMpvString("sub-ass-override", "strip")
        applyMpvSubtitlePosition(
            reservedFraction = reservedFraction.takeIf { it.isFinite() }?.coerceIn(0f, 1f)
        )
    }

    /**
     * App 侧 libass 是否接管主字幕。
     *
     * 双字幕 + 文本主字幕时，主字幕和次字幕必须共用同一套可见边界几何；mpv 的 `sub-pos` 百分比既拿不到原生字幕的实际 包围盒，也无法与 Compose 的
     * `videoRect` 对齐（手机 / 平板视频区高度不同，误差直接变成重叠或过大间距）。 因此接管期间临时把原生字幕整体隐藏，退出双字幕 / 离开播放页时恢复进入前的
     * `sub-visibility`。
     *
     * 同一句绝不会出现「原生 + App」两份：接管请求先于 libass 首帧生效，次字幕也会等主字幕就绪后才绘制。
     */
    fun applyNativePrimarySubtitleVisible(visible: Boolean) {
        if (released) return
        if (!visible) {
            if (savedNativeSubVisibility == null) {
                savedNativeSubVisibility = readMpvString("sub-visibility") ?: "yes"
            }
            setMpvString("sub-visibility", "no")
            // 原生已隐藏：清掉百分比预留，退出接管时不会残留上移过的 sub-pos。
            subtitleReservation = null
            applyMpvSubtitlePosition(reservedFraction = null)
        } else {
            savedNativeSubVisibility?.let { previous -> setMpvString("sub-visibility", previous) }
            savedNativeSubVisibility = null
        }
    }

    private fun applyMpvSubtitlePosition(reservedFraction: Float?) {
        val base = 100.0 - appliedSubtitleLayout.bottomFraction * 100.0
        val position =
            if (reservedFraction == null) {
                base
            } else {
                base - reservedFraction.toDouble() * 100.0
            }
        mpvLib.setPropertyDouble("sub-pos", position.coerceIn(0.0, 100.0))
    }

    /**
     * 字幕模式的自动选轨联动（App 侧字幕后端之间的桥）： `enabled=false` → `sid=no`（关闭字幕）；`enabled=true` → `sid=auto`（按
     * slang / 默认轨重选）。
     *
     * 命令投到命令线程，与 sub-add 保序执行，避免刚注入的轨又被旧 sid 覆盖。
     */
    fun setSubtitleAutoSelect(enabled: Boolean) {
        subtitleAutoSelect = enabled
        postCommand(arrayOf("set", "sid", if (enabled) "auto" else "no"))
        commandHandler.post { runCatching { logSubtitleState() } }
    }

    // ---------- 侧载字幕（W27） ----------

    /**
     * 把一条本机侧载字幕 sub-add 给 mpv（mpv 内置 libass 渲染，ASS/SSA 特效生效）。
     *
     * [select] = true 时立即选中（用户刚导入的预期就是「马上显示」）；重进条目时用 false， 仍按 slang / 默认轨自动选，避免覆盖用户的自动选轨偏好。
     */
    fun addSubtitleFile(
        path: String,
        select: Boolean,
    ) {
        if (released) return
        // 已经有这条轨 / 已经提交过注入：不重复 sub-add
        if (
            externalSubtitleTrackIds.containsKey(path) || !pendingSideloadedSubtitleAdds.add(path)
        ) {
            return
        }
        postCommand(arrayOf("sub-add", path, if (select) "select" else "auto"))
        Timber.d("mpv 注入侧载字幕：%s（select=%b）", path, select)
    }

    /** 这条本机字幕是否已经注入（含「已提交、track-list 还没回来」的窗口） */
    fun isSubtitleFileAttached(path: String): Boolean =
        externalSubtitleTrackIds.containsKey(path) || pendingSideloadedSubtitleAdds.contains(path)

    /** 移除一条侧载字幕（按路径找 track id → `sub-remove`） */
    fun removeSubtitleFile(path: String) {
        if (released) return
        pendingSideloadedSubtitleAdds.remove(path)
        commandHandler.post {
            runCatching {
                if (!externalSubtitleTrackIds.containsKey(path)) {
                    updateExternalSubtitleTracks(mpvLib.getPropertyString("track-list"))
                }
                externalSubtitleTrackIds[path]?.let { id ->
                    postCommand(arrayOf("sub-remove", id.toString()))
                    Timber.d("mpv 移除侧载字幕：%s（track id=%d）", path, id)
                }
            }
                .onFailure { Timber.w(it, "mpv 移除侧载字幕失败") }
        }
    }

    /** 某条本机字幕文件的 mpv track id（没注入时返回 null） */
    fun subtitleTrackIdForPath(path: String): Int? = externalSubtitleTrackIds[path]

    /** 反向查找：mpv track id → 本机字幕文件路径（只对侧载轨有效） */
    fun subtitleTrackPath(trackId: Int): String? =
        externalSubtitleTrackIds.entries.firstOrNull { it.value == trackId }?.key

    private fun updateExternalSubtitleTracks(trackListJson: String?) {
        val parsed =
            parseMpvSubtitleTracks(trackListJson)
                .mapNotNull { track ->
                    val id = track.id ?: return@mapNotNull null
                    val fileName = track.fileName ?: return@mapNotNull null
                    fileName to id
                }
                .toMap()
        externalSubtitleTrackIds.clear()
        externalSubtitleTrackIds.putAll(parsed)
        pendingSideloadedSubtitleAdds.removeAll(parsed.keys)
    }

    /**
     * 容器解析完成后的服务端字幕注入。
     *
     * 规则：容器里已经有内嵌字幕轨（DirectPlay mkv）→ 保持现状，让 mpv 直接读容器字幕； 容器里没有（典型是服务器转码 HLS）→ 把 Jellyfin
     * 交付的独立文本字幕文件 sub-add 给 mpv， 由 mpv 内置的 libass 渲染 ASS/SSA 特效（定位 / 字体 / 动画）。
     */
    private fun maybeAddServerSubtitles() {
        val pending = pendingServerSubtitles ?: return
        // 每个媒体只处理一次：无论是否注入，都清掉待处理标记，避免 track-list 抖动反复触发
        pendingServerSubtitles = null
        val injectable = selectServerSubtitlesToInject(pending)
        if (injectable.isEmpty()) return
        val hasInternal = runCatching {
            hasInternalSubtitleTrack(parseMpvSubtitleTracks(mpvLib.getPropertyString("track-list")))
        }
            .getOrDefault(false)
        if (hasInternal) {
            Timber.d("mpv 容器已含内嵌字幕，跳过服务端字幕注入（候选 %d 条）", injectable.size)
            return
        }
        Timber.i("mpv 容器无内嵌字幕（转码等），注入 %d 条服务端文本字幕", injectable.size)
        injectable.forEach { source ->
            postCommand(
                arrayOf(
                    /* command= */ "sub-add",
                    /* url= */ source.uri,
                    /* flags= */ "auto",
                    /* title= */ source.title,
                    /* lang= */ source.language,
                )
            )
            Timber.d(
                "mpv 注入字幕：%s（%s / %s）",
                source.title,
                source.language,
                source.codec,
            )
        }
        // 注入完成后按 App 的字幕模式重新选轨（auto = 交给 slang / 默认轨，no = 保持关闭）
        postCommand(arrayOf("set", "sid", if (subtitleAutoSelect) "auto" else "no"))
    }

    /** 读取随 MediaItem 一起下发的 Jellyfin 字幕清单（可能为空） */
    private fun MediaItem.serverSubtitleSources(): List<PlayerSubtitleSource> {
        @Suppress("DEPRECATION")
        val sources =
            mediaMetadata.extras?.getParcelableArrayList<PlayerSubtitleSource>(
                PLAYER_EXTRA_SUBTITLE_SOURCES
            )
        return sources?.toList() ?: emptyList()
    }

    /** mpv 颜色格式是 #AARRGGBB；本项目的颜色是 ARGB，直接换个写法即可 */
    private fun Int.toMpvColor(): String = "#%08X".format(this)

    // ---------- 画面调整：旋转 / 镜像 / 裁剪 / 去黑边（§1.6） ----------
    // mpv 侧全部走原生属性，immediate 生效；两个内核的差异只在实现方式（ExoPlayer 走视图变换）。

    /**
     * 画面几何变换（mpv 内核）。
     *
     * @param rotationDegrees 0 / 90 / 180 / 270（mpv `video-rotate`）
     * @param mirror 0 关 / 1 水平 / 2 垂直（`video-scale-x|y` 取负值即镜像，gpu-next 支持）
     * @param cropPercent 四边各裁掉的百分比（0–20）：`video-zoom = log2(1/(1-2p))`
     * @param fillScale 去黑边的填满倍数（由播放页按「画面区比例 / 视频比例」算好传入；1 = 不放大）
     */
    fun applyVideoTransform(
        rotationDegrees: Int,
        mirror: Int,
        cropPercent: Int,
        fillScale: Float,
    ) {
        if (released) return
        val rotate = ((rotationDegrees % 360) + 360) % 360
        val zoomScale = cropScale(cropPercent) * fillScale.coerceAtLeast(1f)
        runCatching {
            mpvLib.setPropertyInt("video-rotate", rotate)
            mpvLib.setPropertyDouble(
                "video-scale-x",
                if (mirror == VideoMirrorMode.HORIZONTAL) -1.0 else 1.0,
            )
            mpvLib.setPropertyDouble(
                "video-scale-y",
                if (mirror == VideoMirrorMode.VERTICAL) -1.0 else 1.0,
            )
            // mpv 的 video-zoom 以 2 为底：zoom = log2(裁剪放大 × 填满倍数)
            val zoom = if (zoomScale <= 1f) 0.0 else ln(zoomScale.toDouble()) / ln(2.0)
            mpvLib.setPropertyDouble("video-zoom", zoom)
            // 排障 / 验收证据：真机走查用文本核对实际写进 mpv 的属性值
            Timber.d(
                "mpv 画面变换: video-rotate=%d video-scale-x=%.1f video-scale-y=%.1f video-zoom=%.4f",
                rotate,
                if (mirror == VideoMirrorMode.HORIZONTAL) -1.0 else 1.0,
                if (mirror == VideoMirrorMode.VERTICAL) -1.0 else 1.0,
                zoom,
            )
        }
            .onFailure { Timber.w(it, "mpv 画面变换应用失败") }
    }

    /**
     * mpv 硬件解码（`hwdec`）可以播放中切换：面板改「解码」组时立即生效。
     *
     * 取值：`mediacodec`（默认硬解）/ `no`（软解）。
     */
    fun applyHwDec(hwDec: String) {
        if (released) return
        runCatching { mpvLib.setPropertyString("hwdec", hwDec) }
            .onFailure { Timber.w(it, "mpv 切换硬件解码失败：%s", hwDec) }
    }

    // ---------- 播放信息面板用的内核侧快照（§1.8） ----------

    /**
     * 读 mpv 的媒体参数（容器 / 编码 / 分辨率 / 帧率 / 码率 / HDR / 音频）。
     *
     * 全部字段按「取不到就是 null」处理，调用方负责降级显示「—」； 必须在主线程之外调用（部分属性会走到 mpv core），UI 侧用 IO 调度。
     */
    fun queryMediaInfo(): PlayerMediaInfo? {
        if (released) return null
        return runCatching {
            val gamma = mpvString("video-params/gamma")
            val primaries = mpvString("video-params/primaries")
            val dolbyVision = mpvString("video-params/dolbyvision") != null
            mpvMediaInfo(
                container = mpvString("file-format"),
                videoCodec = mpvString("video-format"),
                width = mpvInt("width"),
                height = mpvInt("height"),
                videoBitrate = mpvDouble("video-bitrate"),
                frameRate = mpvDouble("container-fps") ?: mpvDouble("estimated-vf-fps"),
                gamma = gamma,
                primaries = primaries,
                dolbyVision = dolbyVision,
                audioCodec = mpvString("audio-codec-name"),
                audioChannels =
                    mpvInt("audio-params/channel-count") ?: mpvInt("audio-params/channels"),
                audioSampleRate = mpvInt("audio-params/samplerate"),
                audioBitrate = mpvDouble("audio-bitrate"),
                fileSizeBytes = mpvDouble("file-size"),
            )
        }
            .onFailure { Timber.w(it, "mpv 媒体信息读取失败") }
            .getOrNull()
    }

    /**
     * W27 PlayerDebugOverlay 需要的 mpv 实时数值（硬件解码器 / 丢帧 / 缓冲）。
     *
     * 取不到一律 null，调用方显示「—」；和 [queryMediaInfo] 一样必须在主线程之外调用。
     */
    data class MpvDebugStats(
        /** 当前生效的硬件解码方式（mediacodec / no / auto-safe…） */
        val hwdec: String?,
        val videoBitrate: Int?,
        val droppedFrames: Int?,
        /** demuxer 缓存时长（毫秒） */
        val cacheDurationMs: Long?,
    )

    fun queryDebugStats(): MpvDebugStats? {
        if (released) return null
        return runCatching {
            MpvDebugStats(
                hwdec = mpvString("hwdec-current"),
                videoBitrate = mpvDouble("video-bitrate")?.takeIf { it > 0 }?.toInt(),
                droppedFrames =
                    (mpvInt("frame-drop-count") ?: mpvDouble("frame-drop-count")?.toInt())?.takeIf {
                        it >= 0
                    },
                cacheDurationMs =
                    mpvDouble("demuxer-cache-duration")?.takeIf { it >= 0 }?.times(1000)?.toLong(),
            )
        }
            .onFailure { Timber.w(it, "mpv 调试信息读取失败") }
            .getOrNull()
    }

    private fun mpvString(name: String): String? = runCatching {
        mpvLib.getPropertyString(name)?.takeIf { it.isNotBlank() }
    }
        .getOrNull()

    /** mpv 的属性类型在不同版本下可能是 int 也可能是 string，这里统一收敛 */
    private fun mpvInt(name: String): Int? =
        runCatching { mpvLib.getPropertyInt(name) }.getOrNull()
            ?: runCatching { mpvLib.getPropertyString(name)?.toDoubleOrNull()?.toInt() }.getOrNull()

    private fun mpvDouble(name: String): Double? =
        runCatching { mpvLib.getPropertyDouble(name) }.getOrNull()
            ?: runCatching { mpvLib.getPropertyString(name)?.toDoubleOrNull() }.getOrNull()

    // Timeline wrapper
    private val timeline: Timeline =
        object : Timeline() {
            /** Returns the number of windows in the timeline. */
            override fun getWindowCount(): Int {
                return internalMediaItems.size
            }

            // Window projection for the current mpv playlist item.
            override fun getWindow(
                windowIndex: Int,
                window: Window,
                defaultPositionProjectionUs: Long,
            ): Window {
                val currentMediaItem =
                    internalMediaItems.getOrNull(windowIndex) ?: MediaItem.Builder().build()
                return window.set(
                    /* uid = */ windowIndex,
                    /* mediaItem = */ currentMediaItem,
                    /* manifest = */ null,
                    /* presentationStartTimeMs = */ C.TIME_UNSET,
                    /* windowStartTimeMs = */ C.TIME_UNSET,
                    /* elapsedRealtimeEpochOffsetMs = */ C.TIME_UNSET,
                    /* isSeekable = */ isSeekable,
                    /* isDynamic = */ false,
                    /* liveConfiguration = */ currentMediaItem.liveConfiguration,
                    /* defaultPositionUs = */ 0,
                    /* durationUs = */ Util.msToUs(currentDurationMs ?: C.TIME_UNSET),
                    /* firstPeriodIndex = */ windowIndex,
                    /* lastPeriodIndex = */ windowIndex,
                    /* positionInFirstPeriodUs = */ 0,
                )
            }

            /** Returns the number of periods in the timeline. */
            override fun getPeriodCount(): Int {
                return internalMediaItems.size
            }

            // Period projection mirrors the mpv playlist item.
            override fun getPeriod(periodIndex: Int, period: Period, setIds: Boolean): Period {
                return period.set(
                    periodIndex,
                    periodIndex,
                    periodIndex,
                    Util.msToUs(currentDurationMs ?: C.TIME_UNSET),
                    0,
                    AdPlaybackState.NONE,
                    false,
                )
            }

            /** Maps the playlist uid back to its index. */
            override fun getIndexOfPeriod(uid: Any): Int {
                return uid as Int
            }

            /** Uses the playlist index as the period uid. */
            override fun getUidOfPeriod(periodIndex: Int): Any {
                return periodIndex
            }
        }

    // OnAudioFocusChangeListener implementation.

    /** Applies the existing mpv audio-focus policy. */
    override fun onAudioFocusChange(focusChange: Int) {
        if (released) return
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                val oldAudioFocusCallback = audioFocusCallback
                val wasPlaying = isPlaying
                mpvLib.setPropertyBoolean("pause", true)
                setPlayerStateAndNotifyIfChanged(
                    playWhenReady = false,
                    playWhenReadyChangeReason = PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS,
                )
                audioFocusCallback = {
                    oldAudioFocusCallback()
                    if (wasPlaying) mpvLib.setPropertyBoolean("pause", false)
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                postCommand(arrayOf("multiply", "volume", "$AUDIO_FOCUS_DUCKING"))
                audioFocusCallback = {
                    postCommand(arrayOf("multiply", "volume", "${1f / AUDIO_FOCUS_DUCKING}"))
                }
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                audioFocusCallback()
                audioFocusCallback = {}
            }
        }
    }

    // Player implementation.

    /** Player callbacks run on the main looper. */
    override fun getApplicationLooper(): Looper {
        return handler.looper
    }

    /** Registers both Media3 and first-frame listeners. */
    override fun addListener(listener: Player.Listener) {
        listeners.add(listener)
        videoListeners.add(listener)
    }

    /** Removes a Media3 and first-frame listener. */
    override fun removeListener(listener: Player.Listener) {
        listeners.remove(listener)
        videoListeners.remove(listener)
    }

    /** Replaces the playlist without starting playback. */
    override fun setMediaItems(mediaItems: MutableList<MediaItem>, resetPosition: Boolean) {
        val nextIndex = currentMediaItemIndex.coerceIn(0, (mediaItems.size - 1).coerceAtLeast(0))
        commitPlaylistState(mediaItems, nextIndex)
        markIntentionalEndFile(count = 2)
        postCommand(arrayOf("playlist-clear"))
        postCommand(arrayOf("playlist-remove", "current"))
    }

    /** Replaces the playlist and remembers the requested start item and position. */
    override fun setMediaItems(
        mediaItems: MutableList<MediaItem>,
        startWindowIndex: Int,
        startPositionMs: Long,
    ) {
        val nextIndex = startWindowIndex.coerceIn(0, (mediaItems.size - 1).coerceAtLeast(0))
        commitPlaylistState(mediaItems, nextIndex)
        markIntentionalEndFile(count = 2)
        postCommand(arrayOf("playlist-clear"))
        postCommand(arrayOf("playlist-remove", "current"))
        initialIndex = nextIndex
        initialSeekTo = startPositionMs
    }

    /**
     * Adds a list of media items at the given index of the playlist.
     *
     * @param index The index at which to add the media items. If the index is larger than the size
     *   of the playlist, the media items are added to the end of the playlist.
     * @param mediaItems The [MediaItems][MediaItem] to add.
     */
    override fun addMediaItems(index: Int, mediaItems: MutableList<MediaItem>) {
        /*
         * 下标可能来自 BasePlayer 的封装（C.INDEX_UNSET 或已越界的值），
         * 直接 addAll 会抛 IndexOutOfBoundsException 把播放页整个打崩（真机实测），
         * 这里统一收敛到 [0, size]：越界一律当作"追加到末尾"。
         */
        val safeIndex = if (index in 0..internalMediaItems.size) index else internalMediaItems.size
        val activeItem = internalMediaItems.getOrNull(currentMediaItemIndex)
        val nextItems = internalMediaItems.toMutableList()
        nextItems.addAll(safeIndex, mediaItems)
        val nextIndex =
            nextItems.indexOfFirst { it === activeItem }.takeIf { it >= 0 }
                ?: safeIndex.coerceAtMost((nextItems.size - 1).coerceAtLeast(0))
        commitPlaylistState(nextItems, nextIndex)
        mediaItems.forEach { mediaItem ->
            postCommand(
                arrayOf(
                    "loadfile",
                    "${mediaItem.localConfiguration?.uri}",
                    "insert-at",
                    safeIndex.toString(),
                )
            )
        }
    }

    /**
     * Moves the media item range to the new index.
     *
     * @param fromIndex The start of the range to move.
     * @param toIndex The first item not to be included in the range (exclusive).
     * @param newIndex The new index of the first media item of the range. If the new index is
     *   larger than the size of the remaining playlist after removing the range, the range is moved
     *   to the end of the playlist.
     */
    override fun moveMediaItems(fromIndex: Int, toIndex: Int, newIndex: Int) {
        /*
         * 队列管理（§1.7）用到的单条移动：mpv `playlist-move <index> <newIndex>`
         * 语义与 Media3 一致（把 fromIndex 的条目移到 newIndex 的位置）。
         * 面板只做单条拖拽，这里也按逐条移动实现，保持下标语义可控。
         */
        val size = internalMediaItems.size
        if (fromIndex !in 0 until size || toIndex <= fromIndex) return
        val from = fromIndex
        val to = toIndex.coerceAtMost(size)
        val target = newIndex.coerceIn(0, size - (to - from))

        val activeItem = internalMediaItems.getOrNull(currentMediaItemIndex)
        val moved = internalMediaItems.subList(from, to).toList()
        val nextItems = internalMediaItems.toMutableList()
        nextItems.subList(from, to).clear()
        nextItems.addAll(target.coerceAtMost(nextItems.size), moved)
        val nextIndex =
            nextItems.indexOfFirst { it === activeItem }.takeIf { it >= 0 }
                ?: target.coerceIn(0, (nextItems.size - 1).coerceAtLeast(0))
        commitPlaylistState(nextItems, nextIndex)
        // 单条移动 == 一条 playlist-move；多条时从后往前发，避免中途下标漂移
        for (offset in moved.indices.reversed()) {
            postCommand(arrayOf("playlist-move", (from + offset).toString(), target.toString()))
        }
        notifyTimelineChanged()
    }

    override fun replaceMediaItems(
        fromIndex: Int,
        toIndex: Int,
        mediaItems: MutableList<MediaItem>,
    ) {
        TODO("Not yet implemented")
    }

    /**
     * Removes a range of media items from the playlist.
     *
     * @param fromIndex The index at which to start removing media items.
     * @param toIndex The index of the first item to be kept (exclusive). If the index is larger
     *   than the size of the playlist, media items to the end of the playlist are removed.
     */
    override fun removeMediaItems(fromIndex: Int, toIndex: Int) {
        val size = internalMediaItems.size
        val from = fromIndex.coerceIn(0, size)
        val to = toIndex.coerceIn(from, size)
        if (from >= to) return

        val activeItem = internalMediaItems.getOrNull(currentMediaItemIndex)
        val nextItems = internalMediaItems.toMutableList()
        nextItems.subList(from, to).clear()
        val currentRemoved = activeItem != null && nextItems.none { it === activeItem }
        val nextIndex =
            nextItems.indexOfFirst { it === activeItem }.takeIf { it >= 0 }
                ?: from.coerceIn(0, (nextItems.size - 1).coerceAtLeast(0))
        commitPlaylistState(nextItems, nextIndex)
        if (currentRemoved) markIntentionalEndFile()
        // 从后往前删：先删大下标，前面的下标不会漂移
        for (index in (to - 1) downTo from) {
            postCommand(arrayOf("playlist-remove", index.toString()))
        }
        notifyTimelineChanged()
    }

    private fun sourceIdentity(items: List<MediaItem>, index: Int): String? =
        items.getOrNull(index)?.let {
            "${it.mediaId}|${it.mediaMetadata.extras?.getString(PLAYER_EXTRA_MEDIA_SOURCE_ID)}|" +
                (it.localConfiguration?.uri ?: it.requestMetadata.mediaUri)
        }

    private fun commitPlaylistState(
        items: MutableList<MediaItem>,
        index: Int,
        remapPending: Boolean = true,
    ) {
        val identityChanged =
            sourceIdentity(internalMediaItems, currentMediaItemIndex) !=
                sourceIdentity(items, index)
        val indexChanged = currentMediaItemIndex != index
        if (identityChanged) advanceSourceEpoch()
        internalMediaItems = items
        currentMediaItemIndex = index
        if (remapPending) pendingPlaylistRemap = !identityChanged && indexChanged
    }

    private fun notifyTimelineChanged() {
        if (commandsClosed.get()) return
        handler.post {
            listeners.sendEvent(EVENT_TIMELINE_CHANGED) { listener ->
                listener.onTimelineChanged(timeline, TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED)
            }
        }
    }

    override fun getAvailableCommands(): Commands {
        return Commands.Builder()
            .addAll(permanentAvailableCommands)
            .addIf(COMMAND_SEEK_TO_DEFAULT_POSITION, !isPlayingAd)
            .addIf(COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, isCurrentMediaItemSeekable && !isPlayingAd)
            .addIf(COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM, hasPreviousMediaItem() && !isPlayingAd)
            .addIf(
                COMMAND_SEEK_TO_PREVIOUS,
                !currentTimeline.isEmpty &&
                    (hasPreviousMediaItem() ||
                        !isCurrentMediaItemLive ||
                        isCurrentMediaItemSeekable) &&
                    !isPlayingAd,
            )
            .addIf(COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, hasNextMediaItem() && !isPlayingAd)
            .addIf(
                COMMAND_SEEK_TO_NEXT,
                !currentTimeline.isEmpty &&
                    (hasNextMediaItem() || (isCurrentMediaItemLive && isCurrentMediaItemDynamic)) &&
                    !isPlayingAd,
            )
            .addIf(COMMAND_SEEK_TO_MEDIA_ITEM, !isPlayingAd)
            .addIf(COMMAND_SEEK_BACK, isCurrentMediaItemSeekable && !isPlayingAd)
            .addIf(COMMAND_SEEK_FORWARD, isCurrentMediaItemSeekable && !isPlayingAd)
            .build()
    }

    private fun resetInternalState() {
        isPlayerReady = false
        isSeekable = false
        playbackState = STATE_IDLE
        currentPlayWhenReady = false
        advanceSourceEpoch()
        currentDurationMs = null
        currentTracks = Tracks.EMPTY
        externalSubtitleTrackIds.clear()
        pendingSideloadedSubtitleAdds.clear()
        playbackParameters = PlaybackParameters.DEFAULT
        initialCommands.clear()
    }

    private fun advanceSourceEpoch(): Long {
        val nextEpoch = sourceEpoch.incrementAndGet()
        sourcePosition = sourcePosition.replaceSource(nextEpoch)
        return nextEpoch
    }

    /** Prepares the player. */
    override fun prepare() {
        internalMediaItems.forEachIndexed { index, mediaItem ->
            postCommand(
                arrayOf(
                    "loadfile",
                    "${mediaItem.localConfiguration?.uri}",
                    if (index == 0) "replace" else "append",
                )
            )
        }
        prepareMediaItem(initialIndex)
    }

    override fun getPlaybackState(): Int {
        return playbackState
    }

    override fun getPlaybackSuppressionReason(): Int {
        return PLAYBACK_SUPPRESSION_REASON_NONE
    }

    override fun getPlayerError(): PlaybackException? {
        return null
    }

    override fun setPlayWhenReady(playWhenReady: Boolean) {
        if (currentPlayWhenReady != playWhenReady) {
            setPlayerStateAndNotifyIfChanged(
                playWhenReady = playWhenReady,
                playWhenReadyChangeReason = PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST,
            )
            if (isPlayerReady) {
                // Request audio focus when starting playback
                if (handleAudioFocus && playWhenReady) {
                    val res = AudioManagerCompat.requestAudioFocus(audioManager, audioFocusRequest)
                    if (res != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                        mpvLib.setPropertyBoolean("pause", true)
                    } else {
                        mpvLib.setPropertyBoolean("pause", false)
                    }
                } else {
                    mpvLib.setPropertyBoolean("pause", !playWhenReady)
                }
            }
        }
    }

    override fun getPlayWhenReady(): Boolean {
        return currentPlayWhenReady
    }

    override fun setRepeatMode(repeatMode: Int) {
        when (repeatMode) {
            REPEAT_MODE_OFF -> {
                mpvLib.setOptionString("loop-file", "no")
                mpvLib.setOptionString("loop-playlist", "no")
            }
            REPEAT_MODE_ONE -> {
                mpvLib.setOptionString("loop-file", "inf")
                mpvLib.setOptionString("loop-playlist", "no")
            }
            REPEAT_MODE_ALL -> {
                mpvLib.setOptionString("loop-file", "no")
                mpvLib.setOptionString("loop-playlist", "inf")
            }
        }
    }

    override fun getRepeatMode(): Int {
        return repeatMode
    }

    override fun setShuffleModeEnabled(shuffleModeEnabled: Boolean) {
        TODO("Not yet implemented")
    }

    override fun getShuffleModeEnabled(): Boolean {
        return false
    }

    /** mpv 的 buffering 状态就是 Media3 与 MediaSession 共享的 loading 锚点。 */
    override fun isLoading(): Boolean = playbackState == STATE_BUFFERING

    /** Routes seeks through the current mpv item or starts the requested item. */
    override fun seekTo(
        mediaItemIndex: Int,
        positionMs: Long,
        @Player.Command seekCommand: Int,
        isRepeatingCurrentItem: Boolean,
    ) {
        if (mediaItemIndex == currentMediaItemIndex) {
            val seekTo = if (positionMs != C.TIME_UNSET) positionMs else initialSeekTo
            initialSeekTo =
                if (isPlayerReady) {
                    sourcePosition = sourcePosition.seekStarted(sourceEpoch.get()) ?: sourcePosition
                    postCommand(
                        arrayOf("seek", "${seekTo.toDouble().div(C.MILLIS_PER_SECOND)}", "absolute")
                    )
                    0L
                } else {
                    seekTo
                }
        } else {
            prepareMediaItem(mediaItemIndex)
            play()
        }
    }

    private fun prepareMediaItem(index: Int) {
        internalMediaItems.getOrNull(index)?.let { mediaItem ->
            // 主动切集 / 重载：旧文件随即产生的非 EOF END_FILE 不算播放失败
            markIntentionalEndFile()
            resetInternalState()
            // 每个条目自带 Jellyfin 字幕清单：转码（容器无内嵌字幕）时由 mpv 侧补 sub-add
            pendingServerSubtitles = mediaItem.serverSubtitleSources()
            mediaItem.localConfiguration?.subtitleConfigurations?.forEach { subtitle ->
                initialCommands.add(
                    arrayOf(
                        /* command= */ "sub-add",
                        /* url= */ "${subtitle.uri}",
                        /* flags= */ "auto",
                        /* title= */ "${subtitle.label}",
                        /* lang= */ "${subtitle.language}",
                    )
                )
            }
            // Only set the playlist index when the index is not the currently playing item.
            // Otherwise
            // playback will be restarted.
            // This is a problem on initial load when the first item is still loading causing
            // duplicate
            // external subtitle entries.
            if (currentMediaItemIndex != index) {
                postCommand(arrayOf("playlist-play-index", "$index"))
            }
            setPlayerStateAndNotifyIfChanged(playbackState = STATE_BUFFERING)
        }
    }

    override fun getSeekBackIncrement(): Long {
        return seekBackIncrement
    }

    override fun getSeekForwardIncrement(): Long {
        return seekForwardIncrement
    }

    /**
     * W74 U6：更新 seek 步进（设置页改完立即生效）。
     *
     * Media3 的 `ExoPlayer` 有 `setSeekBackIncrementMs` / `setSeekForwardIncrementMs`， mpv
     * 这个自研实现原先只在构造期注入一次， 通知栏 / 媒体会话的 ± 命令会一直用旧步进；这里补一个运行期入口，由 `PlayerHolder.applySeekIncrements()`
     * 统一推送。
     */
    fun setSeekIncrements(
        backMs: Long,
        forwardMs: Long,
    ) {
        seekBackIncrement = backMs
        seekForwardIncrement = forwardMs
    }

    override fun getMaxSeekToPreviousPosition(): Long {
        return C.DEFAULT_MAX_SEEK_TO_PREVIOUS_POSITION_MS
    }

    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) {
        // #9：内部字段与 mpv 属性一起同步。是否写属性按 [mpvSpeed]（mpv 一侧的真实值）判定，
        // 不能按 [playbackParameters]——换集后它会被 resetInternalState 复位成 1×，与 mpv 实际速度脱节。
        this.playbackParameters = playbackParameters
        val speed = playbackParameters.speed
        if (mpvSpeed != speed) {
            mpvSpeed = speed
            mpvLib.setPropertyDouble("speed", speed.toDouble())
        }
    }

    override fun getPlaybackParameters(): PlaybackParameters {
        return playbackParameters
    }

    override fun stop() {
        markIntentionalEndFile()
        postCommand(arrayOf("stop", "keep-playlist"))
    }

    /**
     * Releases the player. This method must be called when the player is no longer required. The
     * player must not be used after calling this method.
     */
    override fun release() {
        if (released) return
        seekEventListeners.clear()
        // 先摘掉挂着的 TextureView 输出（此时 mpv 还活着，能正常 detach）；
        // 之后再置 released：TextureView 被 detach 时的回调不能再碰已销毁的 mpvLib
        // （真机上就是这里崩的：MPVLib is not initialized）
        runCatching { detachTextureSurface() }
        released = true
        if (handleAudioFocus) {
            AudioManagerCompat.abandonAudioFocusRequest(audioManager, audioFocusRequest)
        }
        resetInternalState()
        runCatching { mpvLib.removeObserver(this) }
        /*
         * 命令线程收尾：先关闸（丢弃排队中的命令、拒绝新命令），让仍在执行的最后一条命令跑完，
         * 最后在命令线程上销毁 mpv——destroy 不再阻塞主线程，也不会和命令执行并发。
         */
        commandsClosed.set(true)
        commandHandler.removeCallbacksAndMessages(null)
        commandHandler.post {
            runCatching { mpvLib.destroy() }
            commandThread.quitSafely()
        }
    }

    override fun getCurrentTracks(): Tracks {
        return currentTracks
    }

    override fun getTrackSelectionParameters(): TrackSelectionParameters {
        return trackSelectionParameters
    }

    override fun setTrackSelectionParameters(parameters: TrackSelectionParameters) {
        trackSelectionParameters = parameters

        // Disabled track types
        val disabledTrackTypes =
            parameters.disabledTrackTypes.map { MPVTrackType.fromMedia3TrackType(it) }

        // Overrides
        val notOverriddenTypes =
            mutableSetOf(MPVTrackType.VIDEO, MPVTrackType.AUDIO, MPVTrackType.SUBTITLE)
        for (override in parameters.overrides) {
            val trackType = MPVTrackType.fromMedia3TrackType(override.key.type)
            notOverriddenTypes.remove(trackType)
            val id = override.key.getFormat(0).id ?: continue

            selectTrack(trackType, id)
        }
        for (notOverriddenType in notOverriddenTypes) {
            if (notOverriddenType in disabledTrackTypes) {
                selectTrack(notOverriddenType, "no")
            } else {
                selectTrack(notOverriddenType, "auto")
            }
        }
    }

    /**
     * Returns the current combined [MediaMetadata], or [MediaMetadata.EMPTY] if not supported.
     *
     * This [MediaMetadata] is a combination of the [MediaItem.mediaMetadata] and the static and
     * dynamic metadata sourced from [Player.Listener.onMediaMetadataChanged].
     */
    override fun getMediaMetadata(): MediaMetadata {
        /*
         * mpv 内核没有独立的元数据来源：直接用当前媒体项上带的信息（标题 + 季集号）。
         * 通知栏 / 锁屏 / 车机都读这里，返回 EMPTY 会让它们只剩一个没有名字的播放器（阶段 4.2）。
         */
        return internalMediaItems.getOrNull(currentMediaItemIndex)?.mediaMetadata
            ?: MediaMetadata.EMPTY
    }

    override fun getPlaylistMetadata(): MediaMetadata {
        return MediaMetadata.EMPTY
    }

    override fun setPlaylistMetadata(mediaMetadata: MediaMetadata) {
        TODO("Not yet implemented")
    }

    /**
     * Returns the current [Timeline]. Never null, but may be empty.
     *
     * @see Player.Listener.onTimelineChanged
     */
    override fun getCurrentTimeline(): Timeline {
        return timeline
    }

    /** Returns the index of the period currently being played. */
    override fun getCurrentPeriodIndex(): Int {
        return currentMediaItemIndex
    }

    override fun getCurrentMediaItemIndex(): Int {
        return currentMediaItemIndex
    }

    /**
     * Returns the duration of the current content window or ad in milliseconds, or
     * [ ][C.TIME_UNSET] if the duration is not known.
     */
    override fun getDuration(): Long {
        return timeline.getWindow(currentMediaItemIndex, window).durationMs
    }

    /**
     * Returns the playback position in the current content window or ad, in milliseconds, or the
     * prospective position in milliseconds if the [current timeline][.getCurrentTimeline] is empty.
     */
    override fun getCurrentPosition(): Long {
        return confirmedPosition() ?: C.TIME_UNSET
    }

    /**
     * Returns an estimate of the position in the current content window or ad up to which data is
     * buffered, in milliseconds.
     */
    override fun getBufferedPosition(): Long {
        return confirmedBufferedPosition() ?: C.TIME_UNSET
    }

    fun confirmedPosition(): Long? =
        sourcePosition.positionMs?.takeIf {
            sourcePosition.isAnchored &&
                sourcePosition.sourceEpoch == sourceEpoch.get() &&
                currentMediaItem != null
        }

    fun confirmedBufferedPosition(): Long? =
        sourcePosition.bufferedPositionMs?.takeIf {
            sourcePosition.sourceEpoch == sourceEpoch.get() && currentMediaItem != null
        }

    fun currentSourceEpoch(): Long = sourceEpoch.get()

    /**
     * Returns an estimate of the total buffered duration from the current position, in
     * milliseconds. This includes pre-buffered data for subsequent ads and windows.
     */
    override fun getTotalBufferedDuration(): Long {
        return bufferedPosition
    }

    /** Returns whether the player is currently playing an ad. */
    override fun isPlayingAd(): Boolean {
        return false
    }

    /**
     * If [.isPlayingAd] returns true, returns the index of the ad group in the period currently
     * being played. Returns [C.INDEX_UNSET] otherwise.
     */
    override fun getCurrentAdGroupIndex(): Int {
        return C.INDEX_UNSET
    }

    /**
     * If [.isPlayingAd] returns true, returns the index of the ad in its ad group. Returns
     * [C.INDEX_UNSET] otherwise.
     */
    override fun getCurrentAdIndexInAdGroup(): Int {
        return C.INDEX_UNSET
    }

    /**
     * If [.isPlayingAd] returns `true`, returns the content position that will be played once all
     * ads in the ad group have finished playing, in milliseconds. If there is no ad playing, the
     * returned position is the same as that returned by [.getCurrentPosition].
     */
    override fun getContentPosition(): Long {
        return currentPosition
    }

    /**
     * If [.isPlayingAd] returns `true`, returns an estimate of the content position in the current
     * content window up to which data is buffered, in milliseconds. If there is no ad playing, the
     * returned position is the same as that returned by [.getBufferedPosition].
     */
    override fun getContentBufferedPosition(): Long {
        return bufferedPosition
    }

    /** Returns the attributes for audio playback. */
    override fun getAudioAttributes(): AudioAttributes {
        return AudioAttributes.DEFAULT
    }

    /**
     * Sets the audio volume, with 0 being silence and 1 being unity gain (signal unchanged).
     *
     * @param audioVolume Linear output gain to apply to all audio channels.
     */
    override fun setVolume(audioVolume: Float) {
        TODO("Not yet implemented")
    }

    /**
     * Returns the audio volume, with 0 being silence and 1 being unity gain (signal unchanged).
     *
     * @return The linear gain applied to all audio channels.
     */
    override fun getVolume(): Float {
        return mpvLib.getPropertyInt("volume")?.div(100f) ?: 0f
    }

    /**
     * Clears any [Surface], [SurfaceHolder], [SurfaceView] or [TextureView] currently set on the
     * player.
     */
    override fun clearVideoSurface() {
        TODO("Not yet implemented")
    }

    /**
     * Clears the [Surface] onto which video is being rendered if it matches the one passed. Else
     * does nothing.
     *
     * @param surface The surface to clear.
     */
    override fun clearVideoSurface(surface: Surface?) {
        TODO("Not yet implemented")
    }

    /** Raw surface output is unused; typed surface setters own the lifecycle. */
    override fun setVideoSurface(surface: Surface?) {
        TODO("Not yet implemented")
    }

    /**
     * Sets the [SurfaceHolder] that holds the [Surface] onto which video will be rendered. The
     * player will track the lifecycle of the surface automatically.
     *
     * @param surfaceHolder The surface holder.
     */
    override fun setVideoSurfaceHolder(surfaceHolder: SurfaceHolder?) {
        TODO("Not yet implemented")
    }

    /**
     * Clears the [SurfaceHolder] that holds the [Surface] onto which video is being rendered if it
     * matches the one passed. Else does nothing.
     *
     * @param surfaceHolder The surface holder to clear.
     */
    override fun clearVideoSurfaceHolder(surfaceHolder: SurfaceHolder?) {
        TODO("Not yet implemented")
    }

    /**
     * Sets the [SurfaceView] onto which video will be rendered. The player will track the lifecycle
     * of the surface automatically.
     *
     * @param surfaceView The surface view.
     */
    override fun setVideoSurfaceView(surfaceView: SurfaceView?) {
        surfaceView?.holder?.addCallback(surfaceHolder)
    }

    /**
     * Clears the [SurfaceView] onto which video is being rendered if it matches the one passed.
     * Else does nothing.
     *
     * @param surfaceView The texture view to clear.
     */
    override fun clearVideoSurfaceView(surfaceView: SurfaceView?) {
        surfaceView?.holder?.removeCallback(surfaceHolder)
    }

    /**
     * Sets the [TextureView] onto which video will be rendered. The player will track the lifecycle
     * of the surface automatically.
     *
     * @param textureView The texture view.
     */
    override fun setVideoTextureView(textureView: TextureView?) {
        if (textureView == null) {
            detachTextureSurface()
            return
        }
        textureView.surfaceTextureListener = surfaceTextureListener
        // 已经可用时系统不会再补发 onSurfaceTextureAvailable，这里手动接一次
        val surfaceTexture = textureView.surfaceTexture
        if (textureView.isAvailable && surfaceTexture != null) {
            attachTextureSurface(surfaceTexture, textureView.width, textureView.height)
        }
    }

    /**
     * Clears the [TextureView] onto which video is being rendered if it matches the one passed.
     * Else does nothing.
     *
     * @param textureView The texture view to clear.
     */
    override fun clearVideoTextureView(textureView: TextureView?) {
        textureView?.surfaceTextureListener = null
        detachTextureSurface()
    }

    /**
     * Gets the size of the video.
     *
     * The video's width and height are `0` if there is no video or its size has not been determined
     * yet.
     *
     * @see Player.Listener.onVideoSizeChanged
     */
    override fun getVideoSize(): VideoSize {
        val width = mpvLib.getPropertyInt("width")
        val height = mpvLib.getPropertyInt("height")
        if (width == null || height == null) return VideoSize.UNKNOWN
        return VideoSize(width, height)
    }

    override fun getSurfaceSize(): Size {
        val mpvSize =
            mpvLib.getPropertyString("android-surface-size")?.split("x") ?: return Size.UNKNOWN
        return try {
            Size(mpvSize[0].toInt(), mpvSize[1].toInt())
        } catch (_: IndexOutOfBoundsException) {
            Size.UNKNOWN
        }
    }

    /** Returns the current [CueGroup]. This list may be empty. */
    override fun getCurrentCues(): CueGroup {
        return CueGroup(emptyList(), 0)
    }

    /** Gets the device information. */
    override fun getDeviceInfo(): DeviceInfo {
        return DeviceInfo.Builder(DeviceInfo.PLAYBACK_TYPE_LOCAL)
            .setMaxVolume(0)
            .setMaxVolume(100)
            .build()
    }

    /** Maps the player volume API to mpv's volume property. */
    override fun getDeviceVolume(): Int {
        return mpvLib.getPropertyInt("volume") ?: 0
    }

    /** Gets whether the device is muted or not. */
    override fun isDeviceMuted(): Boolean {
        return mpvLib.getPropertyBoolean("mute") ?: false
    }

    /**
     * Sets the volume of the device.
     *
     * @param volume The volume to set.
     */
    @Deprecated("Deprecated in Java")
    override fun setDeviceVolume(volume: Int) {
        throw IllegalArgumentException(
            "You should use global volume controls. Check out AUDIO_SERVICE."
        )
    }

    override fun setDeviceVolume(volume: Int, flags: Int) {
        mpvLib.setPropertyInt("volume", volume)
    }

    /** Increases the volume of the device. */
    @Deprecated("Deprecated in Java")
    override fun increaseDeviceVolume() {
        throw IllegalArgumentException(
            "You should use global volume controls. Check out AUDIO_SERVICE."
        )
    }

    override fun increaseDeviceVolume(flags: Int) {
        throw IllegalArgumentException(
            "You should use global volume controls. Check out AUDIO_SERVICE."
        )
    }

    /** Decreases the volume of the device. */
    @Deprecated("Deprecated in Java")
    override fun decreaseDeviceVolume() {
        throw IllegalArgumentException(
            "You should use global volume controls. Check out AUDIO_SERVICE."
        )
    }

    override fun decreaseDeviceVolume(flags: Int) {
        throw IllegalArgumentException(
            "You should use global volume controls. Check out AUDIO_SERVICE."
        )
    }

    /** Sets the mute state of the device. */
    @Deprecated("Deprecated in Java")
    override fun setDeviceMuted(muted: Boolean) {
        throw IllegalArgumentException(
            "You should use global volume controls. Check out AUDIO_SERVICE."
        )
    }

    override fun setDeviceMuted(muted: Boolean, flags: Int) {
        return mpvLib.setPropertyBoolean("mute", muted)
    }

    override fun mute() {
        mpvLib.setPropertyBoolean("mute", true)
    }

    override fun unmute() {
        mpvLib.setPropertyBoolean("mute", false)
    }

    override fun setAudioAttributes(audioAttributes: AudioAttributes, handleAudioFocus: Boolean) {
        TODO("Not yet implemented")
    }

    /**
     * 画面比例（mpv 内核）：适应 / 裁剪填满 / 拉伸填满。
     *
     * mpv 不读 Media3 的 `resizeMode`，比例必须落到它自己的原生属性上：`keepaspect` 控制是否等比、 `panscan`
     * 控制等比铺满时是否裁掉溢出。两个属性都用 property 优先、option 兜底写入（libmpv 运行时只接受有对应 property 的 option），保证「切档位 / 切内核
     * / 退出重进」都不会残留旧值。
     */
    fun applyResizeMode(resizeMode: Int) {
        if (released) return
        val props = mpvResizeProperties(resizeMode)
        val keepAspect = if (props.keepAspect) "yes" else "no"
        val panscan = if (props.panscan) "1" else "0"
        runCatching {
            setMpvString("keepaspect", keepAspect)
            setMpvString("panscan", panscan)
            // 铺满时字幕留在可见区域，避免被裁到画面外
            val margins = if (props.panscan) "yes" else "no"
            setMpvString("sub-use-margins", margins)
            setMpvString("sub-ass-force-margins", margins)
            // 排障 / 验收证据：真机走查用文本核对实际生效的属性值
            Timber.d(
                "mpv 画面比例: resizeMode=%d keepaspect=%s panscan=%s video-zoom=%s",
                resizeMode,
                readMpvString("keepaspect") ?: keepAspect,
                readMpvString("panscan") ?: panscan,
                readMpvString("video-zoom") ?: "—",
            )
        }
            .onFailure { Timber.w(it, "mpv 画面比例应用失败") }
    }

    /**
     * libmpv 的 `setPropertyString` 不返回错误码，`setOptionString` 返回（0 = 成功）。 两个都写：property 负责即时生效，option
     * 负责没有同名 property 的项兜底。
     */
    private fun setMpvString(name: String, value: String) {
        runCatching { mpvLib.setPropertyString(name, value) }
        runCatching { mpvLib.setOptionString(name, value) }
    }

    private fun readMpvString(name: String): String? = runCatching {
        mpvLib.getPropertyString(name)
    }
        .getOrNull()

    /** 当前挂在 mpv 上的 TextureView 对应 Surface；null 表示没接 */
    private var textureSurface: Surface? = null

    /** release() 之后置位：原生库已销毁，任何 surface 回调都必须短路 */
    private var released: Boolean = false

    private fun attachTextureSurface(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
        // Surface 生命周期与 mpv 的 attachSurface 都必须在主线程；命令线程只承接 command()
        check(Looper.myLooper() == handler.looper) { "mpv surface 操作必须在主线程" }
        if (released) return
        if (textureSurface != null) return
        val surface = Surface(surfaceTexture)
        textureSurface = surface
        mpvLib.attachSurface(surface)
        mpvLib.setOptionString("force-window", "yes")
        mpvLib.setOptionString("vo", videoOutput)
        if (width > 0 && height > 0) {
            mpvLib.setPropertyString("android-surface-size", "${width}x$height")
        }
    }

    private fun detachTextureSurface() {
        check(Looper.myLooper() == handler.looper) { "mpv surface 操作必须在主线程" }
        val surface = textureSurface ?: return
        textureSurface = null
        if (released) {
            // mpv 已销毁：只需要把本地 Surface 还掉，不能再调 mpv
            surface.release()
            return
        }
        mpvLib.setOptionString("vo", "null")
        mpvLib.setOptionString("force-window", "no")
        mpvLib.detachSurface()
        surface.release()
    }

    /**
     * TextureView 版的画面输出。
     *
     * 播放页控制层改 Compose 后，视频输出从 SurfaceView 换成了 TextureView（避免被控件层遮挡变黑）， 而 mpv 这个后端原来只实现了
     * SurfaceView，一挂载就 NotImplementedError 崩掉。 这里按 SurfaceView 的同一套接法补上，差别只是 Surface 来自
     * SurfaceTexture。
     */
    private val surfaceTextureListener =
        object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(
                surfaceTexture: SurfaceTexture,
                width: Int,
                height: Int,
            ) {
                attachTextureSurface(surfaceTexture, width, height)
            }

            override fun onSurfaceTextureSizeChanged(
                surfaceTexture: SurfaceTexture,
                width: Int,
                height: Int,
            ) {
                if (released) return
                mpvLib.setPropertyString("android-surface-size", "${width}x$height")
            }

            override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
                detachTextureSurface()
                // Surface 已自行释放，SurfaceTexture 交回系统回收
                return true
            }

            override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) = Unit
        }

    private val surfaceHolder: SurfaceHolder.Callback =
        object : SurfaceHolder.Callback {
            /**
             * This is called immediately after the surface is first created. Implementations of
             * this should start up whatever rendering code they desire. Note that only one thread
             * can ever draw into a [Surface], so you should not draw into the Surface here if your
             * normal rendering will be in another thread.
             *
             * @param holder The SurfaceHolder whose surface is being created.
             */
            override fun surfaceCreated(holder: SurfaceHolder) {
                if (released) return
                mpvLib.attachSurface(holder.surface)
                mpvLib.setOptionString("force-window", "yes")
                mpvLib.setOptionString("vo", videoOutput)
            }

            /** Forwards the current surface size to mpv. */
            override fun surfaceChanged(
                holder: SurfaceHolder,
                format: Int,
                width: Int,
                height: Int,
            ) {
                if (released) return
                mpvLib.setPropertyString("android-surface-size", "${width}x$height")
            }

            /** Detaches mpv before the surface is destroyed. */
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                if (released) return
                mpvLib.setOptionString("vo", "null")
                mpvLib.setOptionString("force-window", "no")
                mpvLib.detachSurface()
            }
        }

    companion object {
        /** Fraction to which audio volume is ducked on loss of audio focus */
        private const val AUDIO_FOCUS_DUCKING = 0.5f

        /** Suppresses the short non-EOF END_FILE burst caused by our own source operations. */
        private const val END_FILE_SUPPRESS_WINDOW_MS = 3_000L

        private val permanentAvailableCommands: Commands =
            Commands.Builder()
                .addAll(
                    COMMAND_PLAY_PAUSE,
                    COMMAND_SET_SPEED_AND_PITCH,
                    COMMAND_GET_CURRENT_MEDIA_ITEM,
                    COMMAND_GET_METADATA,
                    COMMAND_CHANGE_MEDIA_ITEMS,
                    COMMAND_SET_VIDEO_SURFACE,
                    COMMAND_GET_TRACKS,
                    COMMAND_SET_TRACK_SELECTION_PARAMETERS,
                )
                .build()

        private fun JSONObject.optNullableString(name: String): String? {
            return if (this.has(name) && !this.isNull(name)) {
                this.getString(name)
            } else {
                null
            }
        }

        private fun JSONObject.optNullableDouble(name: String): Double? {
            return if (this.has(name) && !this.isNull(name)) {
                this.getDouble(name)
            } else {
                null
            }
        }

        private fun createTracksGroupfromMpvJson(json: JSONObject): Tracks.Group {
            val trackType = MPVTrackType.entries.first { it.type == json.optString("type") }

            // Base format shared between video, audio and subtitles
            val baseFormat =
                Format.Builder()
                    .setId(json.optInt("id"))
                    .setLabel(json.optNullableString("title"))
                    .setLanguage(json.optNullableString("lang"))
                    .setSelectionFlags(
                        if (json.optBoolean("default")) C.SELECTION_FLAG_DEFAULT else 0
                    )
                    .setCodecs(json.optNullableString("codec"))
                    .build()

            // Video, audio and subtitle specific values
            val format =
                when (trackType) {
                    MPVTrackType.VIDEO -> {
                        baseFormat
                            .buildUpon()
                            .setSampleMimeType("video/${baseFormat.codecs}")
                            .setWidth(json.optInt("demux-w", Format.NO_VALUE))
                            .setHeight(json.optInt("demux-h", Format.NO_VALUE))
                            .setFrameRate(
                                (json.optNullableDouble("demux-w") ?: Format.NO_VALUE).toFloat()
                            )
                            .build()
                    }

                    MPVTrackType.AUDIO -> {
                        baseFormat
                            .buildUpon()
                            .setSampleMimeType("audio/${baseFormat.codecs}")
                            .setChannelCount(json.optInt("demux-channel-count", Format.NO_VALUE))
                            .setSampleRate(json.optInt("demux-samplerate", Format.NO_VALUE))
                            .build()
                    }

                    MPVTrackType.SUBTITLE -> {
                        baseFormat
                            .buildUpon()
                            .setSampleMimeType("text/${baseFormat.codecs}")
                            .build()
                    }
                }

            val trackGroup = TrackGroup(format)

            return Tracks.Group(
                trackGroup,
                false,
                IntArray(trackGroup.length) { C.FORMAT_HANDLED },
                BooleanArray(trackGroup.length) { json.optBoolean("selected") },
            )
        }

        private fun getTracks(trackList: String): Tracks {
            var tracks = Tracks.EMPTY
            val trackGroups = mutableListOf<Tracks.Group>()
            try {
                val currentTrackList = JSONArray(trackList)
                for (index in 0 until currentTrackList.length()) {
                    val tracksGroup =
                        createTracksGroupfromMpvJson(currentTrackList.getJSONObject(index))
                    trackGroups.add(tracksGroup)
                }
                if (trackGroups.isNotEmpty()) {
                    tracks = Tracks(trackGroups)
                }
            } catch (_: JSONException) {}
            return tracks
        }
    }
}

/**
 * 从 Jellyfin 字幕清单里挑出「需要 mpv sub-add 的条目」： 文本字幕、能拿到独立文件、且不是服务器上的外挂字幕（外挂字幕已由
 * MediaItem.subtitleConfigurations 注入）。
 *
 * 内嵌 ASS/SSA 也在其中：转码时容器没有它们，必须靠 Jellyfin 交付的 Stream.ass 才能保住 libass 特效。
 */
internal fun selectServerSubtitlesToInject(
    sources: List<PlayerSubtitleSource>
): List<PlayerSubtitleSource> = sources.filter { it.isTextBased && !it.isExternal }

/** mpv track-list 里一条字幕轨的摘要；判定「是否容器内嵌」，并记录侧载轨的 id / 文件名 */
internal data class MpvSubtitleTrackInfo(
    val external: Boolean,
    val id: Int? = null,
    val fileName: String? = null,
)

/**
 * 是否存在「容器内嵌字幕轨」（`external=false` 的 sub 轨）。
 *
 * 外挂 sub-add 的轨 `external=true`，不能算容器内嵌；DirectPlay 的 mkv 内嵌字幕则是 false。
 */
internal fun hasInternalSubtitleTrack(tracks: List<MpvSubtitleTrackInfo>): Boolean = tracks.any {
    !it.external
}

/** 解析 mpv track-list JSON（Android org.json；JVM 单测覆盖纯逻辑，不覆盖这一段） */
private fun parseMpvSubtitleTracks(trackListJson: String?): List<MpvSubtitleTrackInfo> {
    if (trackListJson.isNullOrBlank()) return emptyList()
    return runCatching {
            val tracks = JSONArray(trackListJson)
            (0 until tracks.length())
                .mapNotNull { index -> tracks.optJSONObject(index) }
                .filter { track -> track.optString("type") == MPVTrackType.SUBTITLE.type }
                .map { track ->
                    MpvSubtitleTrackInfo(
                        external = track.optBoolean("external"),
                        id = track.optInt("id").takeIf { it > 0 },
                        fileName = track.optString("external-filename").takeIf { it.isNotBlank() },
                    )
                }
        }
        .getOrDefault(emptyList())
}
