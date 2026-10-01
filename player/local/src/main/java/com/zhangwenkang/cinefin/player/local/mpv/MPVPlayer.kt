package com.zhangwenkang.cinefin.player.local.mpv

import android.content.Context
import android.graphics.SurfaceTexture
import android.media.AudioManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
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
import com.zhangwenkang.cinefin.player.core.domain.models.PlayerMediaInfo
import com.zhangwenkang.cinefin.player.core.domain.models.SubtitleStyle
import com.zhangwenkang.cinefin.player.local.domain.VideoMirrorMode
import com.zhangwenkang.cinefin.player.local.domain.cropScale
import com.zhangwenkang.cinefin.player.local.domain.hdrFromMpv
import com.zhangwenkang.cinefin.player.local.domain.mpvResizeProperties
import dev.jdtech.mpv.MPVLib
import dev.jdtech.mpv.MPVLib.MpvEvent
import dev.jdtech.mpv.MPVLib.MpvFormat
import java.io.File
import java.io.IOException
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.ln
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import timber.log.Timber

class MPVPlayer(
    context: Context,
    private val audioAttributes: AudioAttributes = AudioAttributes.DEFAULT,
    private val handleAudioFocus: Boolean = true,
    private var trackSelectionParameters: TrackSelectionParameters =
        TrackSelectionParameters.DEFAULT,
    private val seekBackIncrement: Long = C.DEFAULT_SEEK_BACK_INCREMENT_MS,
    private val seekForwardIncrement: Long = C.DEFAULT_SEEK_FORWARD_INCREMENT_MS,
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

    /**
     * mpv 命令专用线程（ANR 修复）。
     *
     * [MPVLib.command] 是同步调用：`loadfile` 需要打开 / 探测网络流，单条就可能阻塞几十到几百毫秒。 播放页补全整季队列时每集一条
     * loadfile，原先这些调用全部压在主线程上，真机复测出现过 `Waited 5000ms for MotionEvent` 的 ANR（dropbox 栈：addMediaItems
     * ← fillQueueInBackground）。
     *
     * 这里把所有 command 调用按提交顺序串行放到专用线程，主线程只做轻量状态维护。 libmpv 允许从任意线程调用 API，命令之间的先后顺序由本队列保证。
     */
    private val commandThread: HandlerThread = HandlerThread("mpv-command").apply { start() }
    private val commandHandler: Handler = Handler(commandThread.looper)
    private val commandsClosed = AtomicBoolean(false)

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

    // MPV Custom
    private var isPlayerReady: Boolean = false
    private var isSeekable: Boolean = false
    private var currentMediaItemIndex: Int = 0
    private var currentPositionMs: Long? = null
    private var currentDurationMs: Long? = null
    private var currentCacheDurationMs: Long? = null
    private var initialCommands = mutableListOf<Array<String>>()
    private var initialIndex: Int = 0
    private var initialSeekTo: Long = 0L
    private var oldMediaItem: MediaItem? = null

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
        handler.post {
            when (property) {
                "time-pos" -> currentPositionMs = value * C.MILLIS_PER_SECOND
                "duration" -> {
                    val newDuration = value * C.MILLIS_PER_SECOND
                    if (currentDurationMs != newDuration) {
                        currentDurationMs = newDuration
                    }
                }
                "demuxer-cache-time" -> currentCacheDurationMs = value * C.MILLIS_PER_SECOND
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
                    currentMediaItemIndex = value.toInt()
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
                    playbackParameters = playbackParameters.withSpeed(value.toFloat())
                    listeners.sendEvent(EVENT_PLAYBACK_PARAMETERS_CHANGED) { listener ->
                        listener.onPlaybackParametersChanged(playbackParameters)
                    }
                }
            }
        }
    }

    override fun event(eventId: Int) {
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
                    currentDurationMs =
                        (mpvLib.getPropertyDouble("duration")?.times(C.MILLIS_PER_SECOND))?.toLong()
                }
                MpvEvent.MPV_EVENT_SEEK -> {
                    setPlayerStateAndNotifyIfChanged(playbackState = STATE_BUFFERING)
                    listeners.sendEvent(EVENT_POSITION_DISCONTINUITY) { listener ->
                        @Suppress("DEPRECATION")
                        listener.onPositionDiscontinuity(DISCONTINUITY_REASON_SEEK)
                    }
                }
                MpvEvent.MPV_EVENT_PLAYBACK_RESTART -> {
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
                }
                else -> Unit
            }
        }
    }

    private fun setPlayerStateAndNotifyIfChanged(
        playWhenReady: Boolean = getPlayWhenReady(),
        @Player.PlayWhenReadyChangeReason
        playWhenReadyChangeReason: Int = PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST,
        @Player.State playbackState: Int = getPlaybackState(),
    ) {
        var playerStateChanged = false
        val wasPlaying = isPlaying
        if (playbackState != getPlaybackState()) {
            this.playbackState = playbackState
            listeners.queueEvent(EVENT_PLAYBACK_STATE_CHANGED) { listener ->
                listener.onPlaybackStateChanged(playbackState)
            }
            playerStateChanged = true
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

    /** 把面板的字幕外观档位翻译成 mpv 的 sub-* 属性 */
    fun applySubtitleStyle(style: SubtitleStyle) {
        // 字号：mpv 默认 55；直接按倍率缩放
        mpvLib.setPropertyDouble("sub-font-size", 55.0 * style.textScale)
        mpvLib.setPropertyString("sub-color", style.textColor.toMpvColor())
        mpvLib.setPropertyString("sub-back-color", style.backgroundColor.toMpvColor())
        mpvLib.setPropertyDouble("sub-border-size", style.edgeWidthDp.toDouble())
        // sub-pos 是「距底部百分比」的互补值：100 = 贴底，65 = 靠上
        mpvLib.setPropertyDouble("sub-pos", (100.0 - style.bottomFraction * 100.0))
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
            PlayerMediaInfo(
                container = mpvString("file-format"),
                videoCodec = mpvString("video-format"),
                width = mpvInt("width")?.takeIf { it > 0 },
                height = mpvInt("height")?.takeIf { it > 0 },
                videoBitrate = mpvDouble("video-bitrate")?.takeIf { it > 0 }?.toInt(),
                frameRate =
                    (mpvDouble("container-fps") ?: mpvDouble("estimated-vf-fps"))
                        ?.takeIf { it > 0 }
                        ?.toFloat(),
                hdr = hdrFromMpv(gamma = gamma, primaries = primaries, dolbyVision = dolbyVision),
                audioCodec = mpvString("audio-codec-name"),
                audioChannels =
                    (mpvInt("audio-params/channel-count") ?: mpvInt("audio-params/channels"))
                        ?.takeIf { it > 0 },
                audioSampleRate = mpvInt("audio-params/samplerate")?.takeIf { it > 0 },
                audioBitrate = mpvDouble("audio-bitrate")?.takeIf { it > 0 }?.toInt(),
                fileSizeBytes = mpvDouble("file-size")?.takeIf { it > 0 }?.toLong(),
            )
        }
            .onFailure { Timber.w(it, "mpv 媒体信息读取失败") }
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

            /**
             * Populates a [Timeline.Window] with data for the window at the specified index.
             *
             * @param windowIndex The index of the window.
             * @param window The [Timeline.Window] to populate. Must not be null.
             * @param defaultPositionProjectionUs A duration into the future that the populated
             *   window's default start position should be projected.
             * @return The populated [Timeline.Window], for convenience.
             */
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

            /**
             * Populates a [Timeline.Period] with data for the period at the specified index.
             *
             * @param periodIndex The index of the period.
             * @param period The [Timeline.Period] to populate. Must not be null.
             * @param setIds Whether [Timeline.Period.id] and [Timeline.Period.uid] should be
             *   populated. If false, the fields will be set to null. The caller should pass false
             *   for efficiency reasons unless the fields are required.
             * @return The populated [Timeline.Period], for convenience.
             */
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

            /**
             * Returns the index of the period identified by its unique [Timeline.Period.uid], or
             * [ ][C.INDEX_UNSET] if the period is not in the timeline.
             *
             * @param uid A unique identifier for a period.
             * @return The index of the period, or [C.INDEX_UNSET] if the period was not found.
             */
            override fun getIndexOfPeriod(uid: Any): Int {
                return uid as Int
            }

            /**
             * Returns the unique id of the period identified by its index in the timeline.
             *
             * @param periodIndex The index of the period.
             * @return The unique id of the period.
             */
            override fun getUidOfPeriod(periodIndex: Int): Any {
                return periodIndex
            }
        }

    // OnAudioFocusChangeListener implementation.

    /**
     * Called on the listener to notify it the audio focus for this listener has been changed. The
     * focusChange value indicates whether the focus was gained, whether the focus was lost, and
     * whether that loss is transient, or whether the new focus holder will hold it for an unknown
     * amount of time. When losing focus, listeners can use the focus change information to decide
     * what behavior to adopt when losing focus. A music player could for instance elect to lower
     * the volume of its music stream (duck) for transient focus losses, and pause otherwise.
     *
     * @param focusChange the type of focus change, one of [AudioManager.AUDIOFOCUS_GAIN],
     *   [AudioManager.AUDIOFOCUS_LOSS], [AudioManager.AUDIOFOCUS_LOSS_TRANSIENT] and
     *   [AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK].
     */
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

    /**
     * Returns the [Looper] associated with the application thread that's used to access the player
     * and on which player events are received.
     */
    override fun getApplicationLooper(): Looper {
        return handler.looper
    }

    /**
     * Registers a listener to receive all events from the player.
     *
     * @param listener The listener to register.
     */
    override fun addListener(listener: Player.Listener) {
        listeners.add(listener)
        videoListeners.add(listener)
    }

    /**
     * Unregister a listener registered through [.addListener]. The listener will no longer receive
     * events.
     *
     * @param listener The listener to unregister.
     */
    override fun removeListener(listener: Player.Listener) {
        listeners.remove(listener)
        videoListeners.remove(listener)
    }

    /**
     * Clears the playlist and adds the specified [MediaItems][MediaItem].
     *
     * @param mediaItems The new [MediaItems][MediaItem].
     * @param resetPosition Whether the playback position should be reset to the default position in
     *   the first [Timeline.Window]. If false, playback will start from the position defined by
     *   [.getCurrentWindowIndex] and [.getCurrentPosition].
     */
    override fun setMediaItems(mediaItems: MutableList<MediaItem>, resetPosition: Boolean) {
        postCommand(arrayOf("playlist-clear"))
        postCommand(arrayOf("playlist-remove", "current"))
        internalMediaItems = mediaItems
    }

    /**
     * Clears the playlist and adds the specified [MediaItems][MediaItem].
     *
     * @param mediaItems The new [MediaItems][MediaItem].
     * @param startWindowIndex The window index to start playback from. If [C.INDEX_UNSET] is
     *   passed, the current position is not reset.
     * @param startPositionMs The position in milliseconds to start playback from. If
     *   [ ][C.TIME_UNSET] is passed, the default position of the given window is used. In any case,
     *   if `startWindowIndex` is set to [C.INDEX_UNSET], this parameter is ignored and the position
     *   is not reset at all.
     * @throws androidx.media3.common.IllegalSeekPositionException If the provided
     *   `startWindowIndex` is not within the bounds of the list of media items.
     */
    override fun setMediaItems(
        mediaItems: MutableList<MediaItem>,
        startWindowIndex: Int,
        startPositionMs: Long,
    ) {
        postCommand(arrayOf("playlist-clear"))
        postCommand(arrayOf("playlist-remove", "current"))
        internalMediaItems = mediaItems
        initialIndex = startWindowIndex
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
        internalMediaItems.addAll(safeIndex, mediaItems)
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

        val moved = internalMediaItems.subList(from, to).toList()
        internalMediaItems.subList(from, to).clear()
        internalMediaItems.addAll(target.coerceAtMost(internalMediaItems.size), moved)
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

        internalMediaItems.subList(from, to).clear()
        // 从后往前删：先删大下标，前面的下标不会漂移
        for (index in (to - 1) downTo from) {
            postCommand(arrayOf("playlist-remove", index.toString()))
        }
        notifyTimelineChanged()
    }

    /** 时间线变化通知（队列增删 / 重排后刷新控制层的队列快照） */
    private fun notifyTimelineChanged() {
        handler.post {
            listeners.sendEvent(EVENT_TIMELINE_CHANGED) { listener ->
                listener.onTimelineChanged(timeline, TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED)
            }
        }
    }

    /**
     * Returns the player's currently available [Commands].
     *
     * The returned [Commands] are not updated when available commands change. Use
     * [ ][Player.Listener.onAvailableCommandsChanged] to get an update when the available commands
     * change.
     *
     * Executing a command that is not available (for example, calling [.next] if
     * [ ][.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM] is unavailable) will neither throw an exception nor
     * generate a [.getPlayerError] player error}.
     *
     * [.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM] and [.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM] are unavailable
     * if there is no such [MediaItem].
     *
     * @return The currently available [Commands].
     * @see Player.Listener.onAvailableCommandsChanged
     */
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
        currentPositionMs = null
        currentDurationMs = null
        currentCacheDurationMs = null
        currentTracks = Tracks.EMPTY
        playbackParameters = PlaybackParameters.DEFAULT
        initialCommands.clear()
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

    /**
     * Returns the current [playback state][Player.State] of the player.
     *
     * @return The current [playback state][Player.State].
     * @see Player.Listener.onPlaybackStateChanged
     */
    override fun getPlaybackState(): Int {
        return playbackState
    }

    /**
     * Returns the reason why playback is suppressed even though [.getPlayWhenReady] is `true`, or
     * [.PLAYBACK_SUPPRESSION_REASON_NONE] if playback is not suppressed.
     *
     * @return The current [playback suppression reason][Player.PlaybackSuppressionReason].
     * @see Player.Listener.onPlaybackSuppressionReasonChanged
     */
    override fun getPlaybackSuppressionReason(): Int {
        return PLAYBACK_SUPPRESSION_REASON_NONE
    }

    /**
     * Returns the error that caused playback to fail. This is the same error that will have been
     * reported via [Player.Listener.onPlayerError] at the time of failure. It can be queried using
     * this method until the player is re-prepared.
     *
     * Note that this method will always return `null` if [.getPlaybackState] is not [.STATE_IDLE].
     *
     * @return The error, or `null`.
     * @see Player.Listener.onPlayerError
     */
    override fun getPlayerError(): PlaybackException? {
        return null
    }

    /**
     * Sets whether playback should proceed when [.getPlaybackState] == [.STATE_READY].
     *
     * If the player is already in the ready state then this method pauses and resumes playback.
     *
     * @param playWhenReady Whether playback should proceed when ready.
     */
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

    /**
     * Whether playback will proceed when [.getPlaybackState] == [.STATE_READY].
     *
     * @return Whether playback will proceed when ready.
     * @see Player.Listener.onPlayWhenReadyChanged
     */
    override fun getPlayWhenReady(): Boolean {
        return currentPlayWhenReady
    }

    /**
     * Sets the [Player.RepeatMode] to be used for playback.
     *
     * @param repeatMode The repeat mode.
     */
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

    /**
     * Returns the current [Player.RepeatMode] used for playback.
     *
     * @return The current repeat mode.
     * @see Player.Listener.onRepeatModeChanged
     */
    override fun getRepeatMode(): Int {
        return repeatMode
    }

    /**
     * Sets whether shuffling of windows is enabled.
     *
     * @param shuffleModeEnabled Whether shuffling is enabled.
     */
    override fun setShuffleModeEnabled(shuffleModeEnabled: Boolean) {
        TODO("Not yet implemented")
    }

    /**
     * Returns whether shuffling of windows is enabled.
     *
     * @see Player.Listener.onShuffleModeEnabledChanged
     */
    override fun getShuffleModeEnabled(): Boolean {
        return false
    }

    /**
     * Whether the player is currently loading the source.
     *
     * @return Whether the player is currently loading the source.
     * @see Player.Listener.onIsLoadingChanged
     */
    override fun isLoading(): Boolean {
        return false
    }

    /**
     * Seeks to a position specified in milliseconds in the specified window.
     *
     * @param mediaItemIndex The index of the window.
     * @param positionMs The seek position in the specified window, or [C.TIME_UNSET] to seek to the
     *   window's default position.
     * @param seekCommand The {@link Player.Command} used to trigger the seek.
     * @param isRepeatingCurrentItem Whether this seeks repeats the current item.
     * @throws androidx.media3.common.IllegalSeekPositionException If the player has a non-empty
     *   timeline and the provided `windowIndex` is not within the bounds of the current timeline.
     */
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
            resetInternalState()
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

    override fun getMaxSeekToPreviousPosition(): Long {
        return C.DEFAULT_MAX_SEEK_TO_PREVIOUS_POSITION_MS
    }

    /**
     * Attempts to set the playback parameters. Passing [PlaybackParameters.DEFAULT] resets the
     * player to the default, which means there is no speed or pitch adjustment.
     *
     * Playback parameters changes may cause the player to buffer.
     * [ ][Player.Listener.onPlaybackParametersChanged] will be called whenever the currently active
     * playback parameters change.
     *
     * @param playbackParameters The playback parameters.
     */
    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) {
        if (getPlaybackParameters().speed != playbackParameters.speed) {
            mpvLib.setPropertyDouble("speed", playbackParameters.speed.toDouble())
        }
    }

    /**
     * Returns the currently active playback parameters.
     *
     * @see Player.Listener.onPlaybackParametersChanged
     */
    override fun getPlaybackParameters(): PlaybackParameters {
        return playbackParameters
    }

    override fun stop() {
        postCommand(arrayOf("stop", "keep-playlist"))
    }

    /**
     * Releases the player. This method must be called when the player is no longer required. The
     * player must not be used after calling this method.
     */
    override fun release() {
        if (released) return
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
        return currentPositionMs ?: C.TIME_UNSET
    }

    /**
     * Returns an estimate of the position in the current content window or ad up to which data is
     * buffered, in milliseconds.
     */
    override fun getBufferedPosition(): Long {
        return currentCacheDurationMs ?: contentPosition
    }

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

    /**
     * Sets the [Surface] onto which video will be rendered. The caller is responsible for tracking
     * the lifecycle of the surface, and must clear the surface by calling `setVideoSurface(null)`
     * if the surface is destroyed.
     *
     * If the surface is held by a [SurfaceView], [TextureView] or [ ] then it's recommended to use
     * [.setVideoSurfaceView], [ ][.setVideoTextureView] or [.setVideoSurfaceHolder] rather than
     * this method, since passing the holder allows the player to track the lifecycle of the surface
     * automatically.
     *
     * @param surface The [Surface].
     */
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

    /**
     * Gets the current volume of the device.
     *
     * For devices with [local playback][DeviceInfo.PLAYBACK_TYPE_LOCAL], the volume returned by
     * this method varies according to the current [stream type][C.StreamType]. The stream type is
     * determined by [AudioAttributes.usage] which can be converted to stream type with
     * [Util.getStreamTypeForAudioUsage].
     *
     * For devices with [remote playback][DeviceInfo.PLAYBACK_TYPE_REMOTE], the volume of the remote
     * device is returned.
     */
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

            /**
             * This is called immediately after any structural changes (format or size) have been
             * made to the surface. You should at this point update the imagery in the surface. This
             * method is always called at least once, after [.surfaceCreated].
             *
             * @param holder The SurfaceHolder whose surface has changed.
             * @param format The new [android.graphics.PixelFormat] of the surface.
             * @param width The new width of the surface.
             * @param height The new height of the surface.
             */
            override fun surfaceChanged(
                holder: SurfaceHolder,
                format: Int,
                width: Int,
                height: Int,
            ) {
                if (released) return
                mpvLib.setPropertyString("android-surface-size", "${width}x$height")
            }

            /**
             * This is called immediately before a surface is being destroyed. After returning from
             * this call, you should no longer try to access this surface. If you have a rendering
             * thread that directly accesses the surface, you must ensure that thread is no longer
             * touching the Surface before returning from this function.
             *
             * @param holder The SurfaceHolder whose surface is being destroyed.
             */
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
