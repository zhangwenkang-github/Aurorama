package com.zhangwenkang.cinefin.player.local.mpv

import android.content.Context
import android.graphics.SurfaceTexture
import android.media.AudioManager
import android.net.Uri
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
import dev.jdtech.mpv.binding.MpvPlaybackEvent
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
    private val sourceEpoch = AtomicLong()
    @Volatile private var sourceEpochReady = false
    private val mpvLib: MPVLib
    private val audioManager: AudioManager by lazy { context.getSystemService()!! }
    private var audioFocusCallback: () -> Unit = {}
    private lateinit var audioFocusRequest: AudioFocusRequestCompat
    private val handler: Handler = Handler(context.mainLooper)
    private var eventPoster: (Runnable) -> Unit = handler::post
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
        sourceEpochReady = true
        val configDir = File(context.filesDir, "mpv")
        val cacheDir = File(context.cacheDir, "mpv")
        setupDirectories(context, configDir, cacheDir)
        mpvLib =
            MPVLib.create(context) ?: throw IllegalStateException("MPVLib.create() returned null")
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
        mpvLib.setOptionString("hwdec", hwDec)
        mpvLib.setOptionString("hwdec-codecs", "h264,hevc,mpeg4,mpeg2video,vp8,vp9,av1")
        Timber.i("MPVPlayer hwdec=%s vo=%s", hwDec, videoOutput)
        mpvLib.setOptionString("tls-verify", "no")
        mpvLib.setOptionString("cache", "yes")
        mpvLib.setOptionString("cache-pause-initial", "yes")
        mpvLib.setOptionString("demuxer-max-bytes", "64MiB")
        mpvLib.setOptionString("demuxer-max-back-bytes", "32MiB")
        mpvLib.setOptionString("sub-scale-with-window", "yes")
        mpvLib.setOptionString("sub-use-margins", "no")
        trackSelectionParameters.preferredAudioLanguages
            .takeIf { it.isNotEmpty() }
            ?.let { mpvLib.setOptionString("alang", it.joinToString(",")) }
        trackSelectionParameters.preferredTextLanguages
            .takeIf { it.isNotEmpty() }
            ?.let { mpvLib.setOptionString("slang", it.joinToString(",")) }
        mpvLib.setOptionString("force-window", "no")
        mpvLib.setOptionString("keep-open", "always")
        mpvLib.setOptionString("save-position-on-quit", "no")
        mpvLib.setOptionString("ytdl", "no")
        mpvLib.setOptionString("audio-set-media-role", "yes")
        mpvLib.init()
        mpvLib.addObserver(this)
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

    private var commandExecutor: (Array<String>, (Boolean) -> Unit) -> Unit = { command, done ->
        val posted = commandHandler.post {
            if (commandsClosed.get()) {
                done(false)
                return@post
            }
            done(
                runCatching { mpvLib.command(command) }
                    .onFailure { Timber.w(it, "mpv 命令执行失败：%s", command.firstOrNull()) }
                    .isSuccess
            )
        }
        if (!posted) done(false)
    }
    private var sourceCommandExecutor: ((Array<String>, Long, (Boolean) -> Unit) -> Unit)? =
        { command, actionGeneration, done ->
            val posted = commandHandler.post {
                if (commandsClosed.get()) {
                    done(false)
                    return@post
                }
                done(
                    runCatching { mpvLib.commandForSource(command, actionGeneration).accepted }
                        .onFailure { Timber.w(it, "mpv source 命令执行失败：%s", command.firstOrNull()) }
                        .getOrDefault(false)
                )
            }
            if (!posted) done(false)
        }
    private var recoveryScheduler: (Long, () -> Unit) -> Unit = { delayMs, action ->
        if (!commandHandler.postDelayed(action, delayMs)) action()
    }

    private fun postCommand(
        command: Array<String>,
        onComplete: ((Boolean) -> Unit)? = null,
    ): Boolean {
        if (commandsClosed.get()) {
            onComplete?.invoke(false)
            return false
        }
        val completed = AtomicBoolean(false)
        fun complete(success: Boolean) {
            if (completed.compareAndSet(false, true)) onComplete?.invoke(success)
        }
        return runCatching { commandExecutor(command, ::complete) }
            .onFailure {
                Timber.w(it, "mpv 命令执行失败：%s", command.firstOrNull())
                complete(false)
            }
            .isSuccess
    }

    private fun postSourceCommand(
        command: Array<String>,
        actionGeneration: Long,
        onComplete: (Boolean) -> Unit,
    ): Boolean {
        if (commandsClosed.get()) {
            onComplete(false)
            return false
        }
        val completed = AtomicBoolean(false)
        fun complete(success: Boolean) {
            if (completed.compareAndSet(false, true)) onComplete(success)
        }
        val executor = sourceCommandExecutor
        if (executor == null) return postCommand(command, ::complete)
        return runCatching { executor(command, actionGeneration, ::complete) }
            .onFailure {
                Timber.w(it, "mpv source 命令执行失败：%s", command.firstOrNull())
                complete(false)
            }
            .isSuccess
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

    private val listeners: ListenerSet<Player.Listener> =
        ListenerSet(context.mainLooper, Clock.DEFAULT) { listener: Player.Listener, flags: FlagSet
            ->
            listener.onEvents(this, Player.Events(flags))
        }
    private val videoListeners = CopyOnWriteArraySet<Player.Listener>()
    private var internalMediaItems = mutableListOf<MediaItem>()
    @Player.State private var playbackState: Int = STATE_IDLE
    private var currentPlayWhenReady: Boolean = false
    @Player.RepeatMode private val repeatMode: Int = REPEAT_MODE_OFF
    private var currentTracks: Tracks = Tracks.EMPTY
    private var playbackParameters: PlaybackParameters = PlaybackParameters.DEFAULT
    private var mpvSpeed: Float = 1f
    private var isPlayerReady: Boolean = false
    private var isSeekable: Boolean = false
    private var currentMediaItemIndex: Int = 0
    private val sourceStartAttemptSequence = AtomicLong()
    private var sourcePosition = MpvSourcePositionState()
    private var pendingPlaylistRemap = false
    private var preserveSourceOnPrepare = false
    private var preparePreservedSourceOnPrepare = false
    private var currentDurationMs: Long? = null
    private var initialCommands = mutableListOf<Array<String>>()

    private data class InitialCommandOwner(
        val epoch: Long,
        val sourceUri: String,
        val sourceIndex: Int,
    )

    private val initialCommandOwners = ArrayDeque<InitialCommandOwner>()

    private data class SourceStartAttempt(
        val epoch: Long,
        val sourceUri: String,
        val sourceIndex: Int,
        val command: Array<String>,
        val actionGeneration: Long,
        val bootstrap: Boolean = false,
        val followUpCommands: List<Array<String>> = emptyList(),
        var startObserved: Boolean = false,
        var commandEntered: Boolean = false,
        var commandDispatched: Boolean = false,
        var commandAttempts: Int = 0,
        var drainAttempts: Int = 0,
        var drainInFlight: Boolean = false,
        var deadlineGeneration: Long = 0L,
    )

    private data class SourceEventResolution(
        val epoch: Long,
        val claim: List<Array<String>>? = null,
        val nextAttempt: SourceStartAttempt? = null,
        val replay: Boolean = false,
        val ignoreBody: Boolean = false,
    )

    private var sourceStartInFlight: SourceStartAttempt? = null
    private var pendingSourceStart: SourceStartAttempt? = null
    private var initialCommandRecoveryEpoch: Long? = null
    private var initialIndex: Int = 0
    private var initialSeekTo: Long = 0L
    private var oldMediaItem: MediaItem? = null
    private var pendingServerSubtitles: List<PlayerSubtitleSource>? = null
    private val externalSubtitleTrackIds = ConcurrentHashMap<String, Int>()
    private val pendingSideloadedSubtitleAdds = CopyOnWriteArraySet<String>()
    private var subtitleAutoSelect: Boolean = true
    private var appliedSubtitleStyle: SubtitleStyle = SubtitleStyle()
    private var appliedSubtitleLayout: SubtitleLayout = SubtitleLayout()
    private var savedSubAssOverride: String? = null
    private var savedNativeSubVisibility: String? = null
    private var subtitleReservation: Float? = null
    private var intentionalEndFileBudget: Int = 0
    private var suppressEndFileErrorUntilMs: Long = 0L

    private fun markIntentionalEndFile(count: Int = 1) {
        if (!isPlayerReady) return
        intentionalEndFileBudget += count
        suppressEndFileErrorUntilMs = SystemClock.elapsedRealtime() + END_FILE_SUPPRESS_WINDOW_MS
    }

    override fun eventProperty(property: String) {}

    override fun eventProperty(property: String, value: String) {
        if (!sourceEpochReady) return
        val epoch = sourceEpoch.get()
        handler.post {
            if (epoch != sourceEpoch.get()) return@post
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
        if (!sourceEpochReady) return
        val epoch = sourceEpoch.get()
        handler.post {
            if (epoch != sourceEpoch.get()) return@post
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
        if (!sourceEpochReady) return
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
        if (
            eventId == MpvEvent.MPV_EVENT_START_FILE ||
                eventId == MpvEvent.MPV_EVENT_FILE_LOADED ||
                eventId == MpvEvent.MPV_EVENT_END_FILE
        ) {
            return
        }
        handleEvent(eventId, resolution = null)
    }

    override fun playbackEvent(event: MpvPlaybackEvent) {
        val resolution =
            when (event.eventId) {
                MpvEvent.MPV_EVENT_START_FILE,
                MpvEvent.MPV_EVENT_FILE_LOADED ->
                    resolveSourceStartEvent(event.eventId, event.actionGeneration)
                MpvEvent.MPV_EVENT_END_FILE -> releaseSourceStart(event.actionGeneration)
                else -> return handleEvent(event.eventId, resolution = null)
            }
        resolution ?: return
        handleEvent(event.eventId, resolution)
    }

    private fun handleEvent(eventId: Int, resolution: SourceEventResolution?) {
        val initialCommands = resolution?.claim
        val publishInitialCommands = AtomicBoolean(initialCommands != null)
        val epoch = resolution?.epoch ?: sourceEpoch.get()
        eventPoster post@{
            if (resolution?.ignoreBody != true && !released && epoch == sourceEpoch.get()) {
                if (
                    (eventId == MpvEvent.MPV_EVENT_START_FILE ||
                        eventId == MpvEvent.MPV_EVENT_FILE_LOADED) && !isPlayerReady
                ) {
                    if (publishInitialCommands.compareAndSet(true, false)) {
                        initialCommands?.forEach { postCommand(it) }
                    }
                }
                when (eventId) {
                    MpvEvent.MPV_EVENT_START_FILE ->
                        if (resolution?.replay == true) replayInitialCommands(epoch)
                    MpvEvent.MPV_EVENT_FILE_LOADED -> {
                        isSeekable = mpvLib.getPropertyBoolean("seekable") == true
                        intentionalEndFileBudget = 0
                        currentDurationMs =
                            (mpvLib.getPropertyDouble("duration")?.times(C.MILLIS_PER_SECOND))
                                ?.toLong()
                        maybeAddServerSubtitles()
                    }
                    MpvEvent.MPV_EVENT_SEEK -> {
                        sourcePosition = sourcePosition.seekStarted(epoch) ?: return@post
                        setPlayerStateAndNotifyIfChanged(playbackState = STATE_BUFFERING)
                        seekEventRelay.onSeekEvent()
                    }
                    MpvEvent.MPV_EVENT_PLAYBACK_RESTART -> {
                        val restartedPositionMs =
                            mpvLib
                                .getPropertyDouble("time-pos")
                                ?.times(C.MILLIS_PER_SECOND)
                                ?.toLong() ?: sourcePosition.positionMs ?: return@post
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
                    MpvEvent.MPV_EVENT_END_FILE -> {
                        val eofReached = mpvLib.getPropertyBoolean("eof-reached") == true
                        val withinWindow =
                            SystemClock.elapsedRealtime() < suppressEndFileErrorUntilMs
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
                                playWhenReadyChangeReason =
                                    PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST,
                                playbackState = STATE_IDLE,
                            )
                        }
                    }
                    else -> Unit
                }
            }
            resolution?.nextAttempt?.let(::requestSourceCommand)
        }
    }

    private fun resolveSourceStartEvent(
        eventId: Int,
        actionGeneration: Long,
    ): SourceEventResolution? =
        synchronized(initialCommands) {
            val attempt =
                sourceStartInFlight?.takeIf { it.actionGeneration == actionGeneration }
                    ?: return@synchronized null
            if (!attempt.commandEntered && !attempt.commandDispatched) {
                return@synchronized SourceEventResolution(attempt.epoch, ignoreBody = true)
            }
            val current =
                attempt.epoch == sourceEpoch.get() && attemptMatchesCurrentSourceLocked(attempt)
            if (!current || attempt.bootstrap) {
                when (eventId) {
                    MpvEvent.MPV_EVENT_START_FILE,
                    MpvEvent.MPV_EVENT_FILE_LOADED -> attempt.startObserved = true
                }
                val next =
                    if (eventId == MpvEvent.MPV_EVENT_FILE_LOADED) {
                        dropSourceStartLocked(attempt)
                    } else {
                        null
                    }
                return@synchronized SourceEventResolution(
                    attempt.epoch,
                    nextAttempt = attemptReadyLocked(next),
                    ignoreBody = true,
                )
            }
            if (eventId == MpvEvent.MPV_EVENT_START_FILE && attempt.startObserved) {
                return@synchronized SourceEventResolution(attempt.epoch)
            }
            val claim =
                if (!attempt.startObserved) {
                    attempt.startObserved = true
                    takeInitialCommandsLocked(attempt)
                } else {
                    null
                }
            if (eventId == MpvEvent.MPV_EVENT_FILE_LOADED) {
                SourceEventResolution(
                    attempt.epoch,
                    claim,
                    nextAttempt = dropSourceStartLocked(attempt),
                )
            } else {
                SourceEventResolution(attempt.epoch, claim)
            }
        }

    private fun releaseSourceStart(actionGeneration: Long): SourceEventResolution? =
        synchronized(initialCommands) {
            sourceStartInFlight
                ?.takeIf { it.actionGeneration == actionGeneration }
                ?.let { attempt ->
                    val next = if (attempt.startObserved) dropSourceStartLocked(attempt) else null
                    SourceEventResolution(
                        attempt.epoch,
                        nextAttempt = attemptReadyLocked(next),
                        ignoreBody = !attempt.startObserved,
                    )
                }
        }

    private fun takeInitialCommandsLocked(attempt: SourceStartAttempt): List<Array<String>>? {
        if (initialCommandOwners.isEmpty()) return null
        val attemptKey = canonicalSource(attempt.sourceUri)
        if (!attemptOwnsCurrentCommandsLocked(attempt)) {
            initialCommandOwners.removeAll {
                it.epoch == attempt.epoch &&
                    it.sourceIndex == attempt.sourceIndex &&
                    (attemptKey == null || canonicalSource(it.sourceUri) == attemptKey)
            }
            return null
        }
        initialCommandOwners.clear()
        initialCommandRecoveryEpoch = null
        return initialCommands.map { it.copyOf() }.also { initialCommands.clear() }
    }

    private fun attemptOwnsCurrentCommandsLocked(attempt: SourceStartAttempt): Boolean {
        if (attempt.epoch != sourceEpoch.get()) return false
        val owner = initialCommandOwners.lastOrNull { it.epoch == attempt.epoch } ?: return false
        val attemptKey = canonicalSource(attempt.sourceUri) ?: return false
        return owner.sourceIndex == attempt.sourceIndex &&
            canonicalSource(owner.sourceUri) == attemptKey
    }

    private fun attemptMatchesCurrentSourceLocked(attempt: SourceStartAttempt): Boolean {
        if (attempt.epoch != sourceEpoch.get()) return false
        if (attempt.bootstrap) {
            val uri = internalMediaItems.getOrNull(attempt.sourceIndex)?.let(::mediaUri)?.toString()
            return canonicalSource(uri) == canonicalSource(attempt.sourceUri)
        }
        val owner = initialCommandOwners.lastOrNull { it.epoch == attempt.epoch }
        if (owner != null) {
            return owner.sourceIndex == attempt.sourceIndex &&
                canonicalSource(owner.sourceUri) == canonicalSource(attempt.sourceUri)
        }
        val currentUri =
            internalMediaItems.getOrNull(currentMediaItemIndex)?.let(::mediaUri)?.toString()
                ?: return false
        return attempt.sourceIndex == currentMediaItemIndex &&
            canonicalSource(attempt.sourceUri) == canonicalSource(currentUri)
    }

    private fun canonicalSource(uri: String?): String? {
        val raw = uri?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return runCatching {
            val parsed = java.net.URI(raw.replace(" ", "%20"))
            val scheme = parsed.scheme?.lowercase()
            val host = parsed.host?.lowercase()
            val path = canonicalSourceComponent(parsed.rawPath.orEmpty())
            val query = parsed.rawQuery?.let(::canonicalSourceComponent)?.let { "?$it" }.orEmpty()
            when {
                scheme == null || scheme == "file" -> File(path).normalize().path
                host != null ->
                    buildString {
                        append(scheme).append("://")
                        parsed.rawUserInfo?.let { append(it).append('@') }
                        append(host)
                        if (parsed.port >= 0) append(':').append(parsed.port)
                        append(path).append(query)
                    }
                else -> "$scheme:${canonicalSourceComponent(parsed.rawSchemeSpecificPart)}$query"
            }
        }
            .getOrNull()
    }

    private fun canonicalSourceComponent(raw: String): String {
        val output = StringBuilder(raw.length)
        var index = 0
        while (index < raw.length) {
            if (raw[index] != '%' || index + 2 >= raw.length) {
                output.append(raw[index++])
                continue
            }
            val bytes = mutableListOf<Byte>()
            val encoded = StringBuilder()
            while (index + 2 < raw.length && raw[index] == '%') {
                val value = raw.substring(index + 1, index + 3).toIntOrNull(16) ?: break
                bytes += value.toByte()
                encoded.append('%').append(raw.substring(index + 1, index + 3).uppercase())
                index += 3
            }
            if (bytes.isEmpty()) {
                output.append(raw[index++])
                continue
            }
            val decoded =
                java.nio.charset.StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .runCatching {
                        decode(java.nio.ByteBuffer.wrap(bytes.toByteArray())).toString()
                    }
                    .getOrNull()
            if (
                decoded != null &&
                    decoded.all { char ->
                        char.code >= 0x80 && !char.isISOControl() ||
                            char.isLetterOrDigit() ||
                            char in "-._~ "
                    }
            ) {
                output.append(decoded)
            } else {
                output.append(encoded)
            }
        }
        return output.toString()
    }

    private fun replayInitialCommands(epoch: Long) {
        synchronized(initialCommands) {
            if (
                epoch != sourceEpoch.get() ||
                    initialCommandRecoveryEpoch == epoch ||
                    sourceStartInFlight != null ||
                    initialCommandOwners.none { it.epoch == epoch }
            ) {
                return
            }
            initialCommandRecoveryEpoch = epoch
        }
        postSourceStartCommand(
            arrayOf("playlist-play-index", "$currentMediaItemIndex"),
            currentMediaItemIndex,
        )
    }

    private fun replaceInitialCommands(
        commands: List<Array<String>>,
        sourceUri: String? = null,
        sourceIndex: Int = currentMediaItemIndex,
    ) {
        synchronized(initialCommands) {
            val copiedCommands = commands.map { it.copyOf() }
            val ownerUri =
                sourceUri
                    ?: internalMediaItems
                        .getOrNull(currentMediaItemIndex)
                        ?.let(::mediaUri)
                        ?.toString()
            val epoch = sourceEpoch.get()
            initialCommands.clear()
            initialCommands.addAll(copiedCommands)
            initialCommandOwners.removeAll { it.epoch == epoch }
            if (ownerUri != null) {
                initialCommandOwners.addLast(InitialCommandOwner(epoch, ownerUri, sourceIndex))
            }
            initialCommandRecoveryEpoch = null
        }
    }

    private fun postSourceStartCommand(
        command: Array<String>,
        sourceIndex: Int = currentMediaItemIndex,
        sourceUri: String? = null,
    ) = enqueueSourceStart(command, sourceIndex, sourceUri)

    private fun enqueueSourceStart(
        command: Array<String>,
        sourceIndex: Int,
        sourceUri: String?,
        bootstrap: Boolean = false,
        followUpCommands: List<Array<String>> = emptyList(),
    ) {
        if (commandsClosed.get()) return
        val ownerUri =
            sourceUri
                ?: internalMediaItems.getOrNull(sourceIndex)?.let(::mediaUri)?.toString()
                ?: return
        val attempt =
            synchronized(initialCommands) {
                SourceStartAttempt(
                        sourceEpoch.get(),
                        ownerUri,
                        sourceIndex,
                        command.copyOf(),
                        sourceStartAttemptSequence.incrementAndGet(),
                        bootstrap,
                        followUpCommands.map { it.copyOf() },
                    )
                    .also { it ->
                        if (sourceStartInFlight == null) {
                            sourceStartInFlight = it
                            pendingSourceStart = null
                        } else {
                            pendingSourceStart = it
                        }
                    }
            }
        requestSourceCommand(attempt)
    }

    private fun dropSourceStartLocked(attempt: SourceStartAttempt): SourceStartAttempt? {
        if (sourceStartInFlight !== attempt) return null
        sourceStartInFlight = null
        val next = pendingSourceStart
        if (next == null || attemptReadyLocked(next) == null) return null
        pendingSourceStart = null
        next.startObserved = false
        sourceStartInFlight = next
        return next
    }

    private fun attemptReadyLocked(attempt: SourceStartAttempt?): SourceStartAttempt? =
        attempt?.takeIf {
            it.epoch == sourceEpoch.get() && attemptMatchesCurrentSourceLocked(it)
        }

    private fun scheduleSourceRecovery(attempt: SourceStartAttempt) {
        val generation =
            synchronized(initialCommands) {
                if (sourceStartInFlight !== attempt && pendingSourceStart !== attempt) return
                ++attempt.deadlineGeneration
            }
        runCatching {
            recoveryScheduler(SOURCE_RECOVERY_RETRY_MS) { retrySource(attempt, generation) }
        }
            .onFailure { Timber.w(it, "source recovery scheduling failed") }
    }

    private fun retrySource(attempt: SourceStartAttempt, generation: Long) {
        var activeToDrain: SourceStartAttempt? = null
        val action =
            synchronized(initialCommands) {
                if (
                    generation != attempt.deadlineGeneration ||
                        (sourceStartInFlight !== attempt && pendingSourceStart !== attempt)
                ) {
                    return
                }
                if (pendingSourceStart === attempt && sourceStartInFlight != null) {
                    activeToDrain = sourceStartInFlight
                    return@synchronized 4
                }
                when {
                    attempt.startObserved || attempt.commandEntered || attempt.commandDispatched ->
                        2
                    else -> {
                        if (attempt.commandAttempts == 0) attempt.commandAttempts++
                        3
                    }
                }
            }
        when (action) {
            2 -> beginSourceDrain(attempt)
            3 -> requestSourceCommand(attempt, recovery = true)
            4 -> activeToDrain?.let(::beginSourceDrain) ?: finishSourceDrain(attempt)
        }
    }

    private fun requestSourceCommand(attempt: SourceStartAttempt, recovery: Boolean = false) {
        val command =
            synchronized(initialCommands) {
                if (
                    attempt.drainInFlight ||
                        attempt.commandEntered ||
                        (attempt.commandAttempts > 0 && !attempt.commandDispatched && !recovery) ||
                        !attemptReadyLocked(attempt).let { it != null }
                ) {
                    return@synchronized null
                }
                if (attempt.commandDispatched) return@synchronized null
                if (sourceStartInFlight == null && pendingSourceStart === attempt) {
                    pendingSourceStart = null
                    sourceStartInFlight = attempt
                }
                if (sourceStartInFlight !== attempt) return@synchronized null
                attempt.commandEntered = true
                attempt.commandAttempts++
                attempt.command.copyOf()
            } ?: return
        scheduleSourceRecovery(attempt)
        postSourceCommand(command, attempt.actionGeneration) { success ->
            onSourceCommandComplete(attempt, success)
        }
    }

    private fun onSourceCommandComplete(attempt: SourceStartAttempt, success: Boolean) {
        var drain = false
        var followUps: List<Array<String>>? = null
        synchronized(initialCommands) {
            if (sourceStartInFlight !== attempt) return
            attempt.commandEntered = false
            if (success || attempt.startObserved) {
                attempt.commandDispatched = true
                if (attempt.followUpCommands.isNotEmpty()) {
                    followUps = attempt.followUpCommands
                }
            } else if (attempt.commandAttempts >= SOURCE_RECOVERY_LIMIT) {
                drain = true
            }
        }
        followUps?.forEach(::postCommand)
        if (drain) beginSourceDrain(attempt)
    }

    private fun beginSourceDrain(attempt: SourceStartAttempt) {
        var exhausted = false
        val run: Boolean =
            synchronized(initialCommands) {
                if (released || attempt.drainInFlight || sourceStartInFlight !== attempt) {
                    return
                }
                if (attempt.drainAttempts >= SOURCE_RECOVERY_LIMIT) {
                    exhausted = true
                    return@synchronized false
                }
                attempt.drainInFlight = true
                attempt.drainAttempts++
                true
            }
        if (exhausted) {
            finishSourceDrain(attempt)
            return
        }
        if (!run) return
        scheduleSourceRecovery(attempt)
        postCommand(arrayOf("stop")) { success ->
            synchronized(initialCommands) { attempt.drainInFlight = false }
            if (success) finishSourceDrain(attempt) else scheduleSourceRecovery(attempt)
        }
    }

    private fun finishSourceDrain(attempt: SourceStartAttempt) {
        val replacement =
            synchronized(initialCommands) {
                if (sourceStartInFlight !== attempt) return
                attempt.deadlineGeneration++
                sourceStartInFlight = null
                val pending =
                    pendingSourceStart
                        ?.takeIf { it.epoch == sourceEpoch.get() }
                        ?.also {
                            pendingSourceStart = null
                            it.startObserved = false
                        }
                (pending
                        ?: SourceStartAttempt(
                                attempt.epoch,
                                attempt.sourceUri,
                                attempt.sourceIndex,
                                attempt.command.copyOf(),
                                sourceStartAttemptSequence.incrementAndGet(),
                                bootstrap = attempt.bootstrap,
                                followUpCommands = attempt.followUpCommands.map { it.copyOf() },
                            )
                            .takeIf { attempt.epoch == sourceEpoch.get() })
                    ?.also { sourceStartInFlight = it }
            }
        replacement?.let(::requestSourceCommand)
    }

    private fun updateEnginePosition(epoch: Long, positionMs: Long) {
        if (epoch != sourceEpoch.get()) return
        val next = sourcePosition.acceptPosition(epoch, positionMs) ?: return
        val previousPositionMs = sourcePosition.positionMs ?: positionMs
        sourcePosition = next
        listeners.sendEvent(EVENT_POSITION_DISCONTINUITY) { listener ->
            listener.onPositionDiscontinuity(
                positionInfo(previousPositionMs),
                positionInfo(positionMs),
                DISCONTINUITY_REASON_INTERNAL,
            )
        }
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

    private fun selectTrack(trackType: MPVTrackType, id: String) =
        mpvLib.setPropertyString(trackType.type, id)

    fun setAudioDelay(delayMs: Long) = mpvLib.setPropertyDouble("audio-delay", delayMs / 1000.0)

    fun addSeekEventListener(listener: MpvSeekEventListener) = seekEventListeners.add(listener)

    fun removeSeekEventListener(listener: MpvSeekEventListener) =
        seekEventListeners.remove(listener)

    fun setSubtitleDelay(delayMs: Long) {
        mpvLib.setPropertyDouble("sub-delay", delayMs / 1000.0)
        logSubtitleState()
    }

    fun selectSubtitleTrack(trackId: Int?) {
        mpvLib.setPropertyString("sid", trackId?.toString() ?: "no")
        logSubtitleState()
    }

    fun currentSubtitleSelection(): String? = runCatching {
        mpvLib.getPropertyString("sid")
    }
        .getOrNull()
        ?.trim()

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

    fun setSecondarySubtitle(trackId: String?) {
        val value = trackId?.takeIf { it.isNotBlank() } ?: "no"
        mpvLib.setPropertyString("secondary-sid", value)
    }

    fun applySubtitleStyle(
        style: SubtitleStyle,
        layout: SubtitleLayout = SubtitleLayout(),
    ) {
        appliedSubtitleStyle = style
        appliedSubtitleLayout = layout
        mpvLib.setPropertyDouble("sub-font-size", 55.0 * style.textScale)
        mpvLib.setPropertyString("sub-color", style.textColor.toMpvColor())
        mpvLib.setPropertyString("sub-back-color", style.backgroundArgb.toMpvColor())
        mpvLib.setPropertyDouble("sub-border-size", style.edgeWidthDp.toDouble())
        applyMpvSubtitlePosition(reservedFraction = subtitleReservation)
    }

    fun applySecondarySubtitleReservation(reservedFraction: Float?) {
        if (released) return
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

    fun applyNativePrimarySubtitleVisible(visible: Boolean) {
        if (released) return
        if (!visible) {
            if (savedNativeSubVisibility == null) {
                savedNativeSubVisibility = readMpvString("sub-visibility") ?: "yes"
            }
            setMpvString("sub-visibility", "no")
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

    fun setSubtitleAutoSelect(enabled: Boolean) {
        subtitleAutoSelect = enabled
        postCommand(arrayOf("set", "sid", if (enabled) "auto" else "no"))
        commandHandler.post { runCatching { logSubtitleState() } }
    }

    fun addSubtitleFile(
        path: String,
        select: Boolean,
    ) {
        if (released) return
        if (
            externalSubtitleTrackIds.containsKey(path) || !pendingSideloadedSubtitleAdds.add(path)
        ) {
            return
        }
        postCommand(arrayOf("sub-add", path, if (select) "select" else "auto"))
    }

    fun isSubtitleFileAttached(path: String): Boolean =
        externalSubtitleTrackIds.containsKey(path) || pendingSideloadedSubtitleAdds.contains(path)

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
                }
            }
                .onFailure { Timber.w(it, "mpv 移除侧载字幕失败") }
        }
    }

    fun subtitleTrackIdForPath(path: String): Int? = externalSubtitleTrackIds[path]

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

    private fun maybeAddServerSubtitles() {
        val pending = pendingServerSubtitles ?: return
        pendingServerSubtitles = null
        val injectable = selectServerSubtitlesToInject(pending)
        if (injectable.isEmpty()) return
        val hasInternal = runCatching {
            hasInternalSubtitleTrack(parseMpvSubtitleTracks(mpvLib.getPropertyString("track-list")))
        }
            .getOrDefault(false)
        if (hasInternal) {
            return
        }
        injectable.forEach { source ->
            postCommand(arrayOf("sub-add", source.uri, "auto", source.title, source.language))
        }
        postCommand(arrayOf("set", "sid", if (subtitleAutoSelect) "auto" else "no"))
    }

    private fun MediaItem.serverSubtitleSources(): List<PlayerSubtitleSource> {
        @Suppress("DEPRECATION")
        val sources =
            mediaMetadata.extras?.getParcelableArrayList<PlayerSubtitleSource>(
                PLAYER_EXTRA_SUBTITLE_SOURCES
            )
        return sources?.toList() ?: emptyList()
    }

    private fun Int.toMpvColor(): String = "#%08X".format(this)

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
            val zoom = if (zoomScale <= 1f) 0.0 else ln(zoomScale.toDouble()) / ln(2.0)
            mpvLib.setPropertyDouble("video-zoom", zoom)
        }
            .onFailure { Timber.w(it, "mpv 画面变换应用失败") }
    }

    fun applyHwDec(hwDec: String) {
        if (released) return
        runCatching { mpvLib.setPropertyString("hwdec", hwDec) }
            .onFailure { Timber.w(it, "mpv 切换硬件解码失败：%s", hwDec) }
    }

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

    data class MpvDebugStats(
        val hwdec: String?,
        val videoBitrate: Int?,
        val droppedFrames: Int?,
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

    private fun mpvInt(name: String): Int? =
        runCatching { mpvLib.getPropertyInt(name) }.getOrNull()
            ?: runCatching { mpvLib.getPropertyString(name)?.toDoubleOrNull()?.toInt() }.getOrNull()

    private fun mpvDouble(name: String): Double? =
        runCatching { mpvLib.getPropertyDouble(name) }.getOrNull()
            ?: runCatching { mpvLib.getPropertyString(name)?.toDoubleOrNull() }.getOrNull()

    private val timeline: Timeline =
        object : Timeline() {
            override fun getWindowCount(): Int {
                return internalMediaItems.size
            }

            override fun getWindow(
                windowIndex: Int,
                window: Window,
                defaultPositionProjectionUs: Long,
            ): Window {
                val currentMediaItem =
                    internalMediaItems.getOrNull(windowIndex) ?: MediaItem.Builder().build()
                return window.set(
                    windowIndex,
                    currentMediaItem,
                    null,
                    C.TIME_UNSET,
                    C.TIME_UNSET,
                    C.TIME_UNSET,
                    isSeekable,
                    false,
                    currentMediaItem.liveConfiguration,
                    0,
                    Util.msToUs(currentDurationMs ?: C.TIME_UNSET),
                    windowIndex,
                    windowIndex,
                    0,
                )
            }

            override fun getPeriodCount(): Int {
                return internalMediaItems.size
            }

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

            override fun getIndexOfPeriod(uid: Any): Int {
                return uid as Int
            }

            override fun getUidOfPeriod(periodIndex: Int): Any {
                return periodIndex
            }
        }

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

    override fun getApplicationLooper(): Looper = handler.looper

    override fun addListener(listener: Player.Listener) {
        listeners.add(listener)
        videoListeners.add(listener)
    }

    override fun removeListener(listener: Player.Listener) {
        listeners.remove(listener)
        videoListeners.remove(listener)
    }

    override fun replaceMediaItems(
        fromIndex: Int,
        toIndex: Int,
        mediaItems: MutableList<MediaItem>,
    ) = TODO("Not yet implemented")

    override fun setShuffleModeEnabled(shuffleModeEnabled: Boolean) = TODO("Not yet implemented")

    override fun setPlaylistMetadata(mediaMetadata: MediaMetadata) = TODO("Not yet implemented")

    override fun setVolume(audioVolume: Float) = TODO("Not yet implemented")

    override fun clearVideoSurface() = TODO("Not yet implemented")

    override fun clearVideoSurface(surface: Surface?) = TODO("Not yet implemented")

    override fun setVideoSurface(surface: Surface?) = TODO("Not yet implemented")

    override fun setVideoSurfaceHolder(surfaceHolder: SurfaceHolder?) = TODO("Not yet implemented")

    override fun clearVideoSurfaceHolder(surfaceHolder: SurfaceHolder?) =
        TODO("Not yet implemented")

    override fun setAudioAttributes(
        audioAttributes: AudioAttributes,
        handleAudioFocus: Boolean,
    ) = TODO("Not yet implemented")

    override fun setMediaItems(mediaItems: MutableList<MediaItem>, resetPosition: Boolean) {
        val activeIdentity = sourceIdentity(internalMediaItems, currentMediaItemIndex)
        val nextIndex =
            mediaItems.indices.firstOrNull { sourceIdentity(mediaItems, it) == activeIdentity }
                ?: currentMediaItemIndex.coerceIn(0, (mediaItems.size - 1).coerceAtLeast(0))
        replaceMediaItems(mediaItems, nextIndex, startPositionMs = null)
    }

    override fun setMediaItems(
        mediaItems: MutableList<MediaItem>,
        startWindowIndex: Int,
        startPositionMs: Long,
    ) {
        val nextIndex = startWindowIndex.coerceIn(0, (mediaItems.size - 1).coerceAtLeast(0))
        replaceMediaItems(mediaItems, nextIndex, startPositionMs)
    }

    private fun replaceMediaItems(
        mediaItems: MutableList<MediaItem>,
        nextIndex: Int,
        startPositionMs: Long?,
    ) {
        mediaItems.forEach { mediaUri(it) }
        val oldItems = internalMediaItems
        val oldIndex = currentMediaItemIndex
        val preserveSource =
            sourceIdentity(oldItems, oldIndex)?.let {
                it == sourceIdentity(mediaItems, nextIndex)
            } == true
        commitPlaylistState(mediaItems, nextIndex)
        preserveSourceOnPrepare = preserveSource
        preparePreservedSourceOnPrepare = preserveSource && playbackState == STATE_IDLE
        if (preserveSource) {
            (oldItems.lastIndex downTo 0)
                .filter { it != oldIndex }
                .forEach { postCommand(arrayOf("playlist-remove", "$it")) }
            mediaItems.indices
                .filter { it != nextIndex }
                .forEach { index ->
                    val item = mediaItems[index]
                    postCommand(
                        arrayOf(
                            "loadfile",
                            mediaUri(item).toString(),
                            "insert-at",
                            "$index",
                        )
                    )
                }
        } else {
            markIntentionalEndFile(count = 2)
            postCommand(arrayOf("playlist-clear"))
            postCommand(arrayOf("playlist-remove", "current"))
        }
        initialIndex = nextIndex
        initialSeekTo = startPositionMs ?: initialSeekTo
    }

    override fun addMediaItems(index: Int, mediaItems: MutableList<MediaItem>) {
        require(index in 0..internalMediaItems.size) { "index out of range: $index" }
        mediaItems.forEach { mediaUri(it) }
        val activeItem = internalMediaItems.getOrNull(currentMediaItemIndex)
        val nextItems = internalMediaItems.toMutableList()
        nextItems.addAll(index, mediaItems)
        val nextIndex =
            nextItems.indexOfFirst { it === activeItem }.takeIf { it >= 0 }
                ?: index.coerceAtMost((nextItems.size - 1).coerceAtLeast(0))
        commitPlaylistState(nextItems, nextIndex)
        mediaItems.forEachIndexed { offset, mediaItem ->
            postCommand(
                arrayOf(
                    "loadfile",
                    mediaUri(mediaItem).toString(),
                    "insert-at",
                    (index + offset).toString(),
                )
            )
        }
    }

    override fun moveMediaItems(fromIndex: Int, toIndex: Int, newIndex: Int) {
        val size = internalMediaItems.size
        require(fromIndex in 0 until size) { "fromIndex out of range: $fromIndex" }
        require(toIndex in (fromIndex + 1)..size) { "toIndex out of range: $toIndex" }
        require(newIndex in 0..size) { "newIndex out of range: $newIndex" }
        require(fromIndex != 0 || toIndex != newIndex) { "illegal no-op forward range" }
        val target = newIndex.coerceAtMost(size - (toIndex - fromIndex))
        val activeItem = internalMediaItems.getOrNull(currentMediaItemIndex)
        val moved = internalMediaItems.subList(fromIndex, toIndex).toList()
        val nextItems = internalMediaItems.toMutableList()
        nextItems.subList(fromIndex, toIndex).clear()
        nextItems.addAll(target.coerceAtMost(nextItems.size), moved)
        val nextIndex =
            nextItems.indexOfFirst { it === activeItem }.takeIf { it >= 0 }
                ?: target.coerceIn(0, (nextItems.size - 1).coerceAtLeast(0))
        commitPlaylistState(nextItems, nextIndex)
        val offsets = if (target < fromIndex) moved.indices else moved.indices.reversed()
        for (offset in offsets) {
            postCommand(
                arrayOf(
                    "playlist-move",
                    (fromIndex + offset).toString(),
                    (target + offset).toString(),
                )
            )
        }
        notifyTimelineChanged()
    }

    override fun removeMediaItems(fromIndex: Int, toIndex: Int) {
        val size = internalMediaItems.size
        require(fromIndex in 0 until size) { "fromIndex out of range: $fromIndex" }
        require(toIndex in (fromIndex + 1)..size) { "toIndex out of range: $toIndex" }
        val activeItem = internalMediaItems.getOrNull(currentMediaItemIndex)
        val nextItems = internalMediaItems.toMutableList()
        nextItems.subList(fromIndex, toIndex).clear()
        val currentRemoved = activeItem != null && nextItems.none { it === activeItem }
        val nextIndex =
            nextItems.indexOfFirst { it === activeItem }.takeIf { it >= 0 }
                ?: fromIndex.coerceIn(0, (nextItems.size - 1).coerceAtLeast(0))
        commitPlaylistState(nextItems, nextIndex)
        if (currentRemoved) markIntentionalEndFile()
        for (index in (toIndex - 1) downTo fromIndex) {
            postCommand(arrayOf("playlist-remove", index.toString()))
        }
        notifyTimelineChanged()
    }

    private fun sourceIdentity(items: List<MediaItem>, index: Int): String? =
        items.getOrNull(index)?.let {
            "${it.mediaId}|${it.mediaMetadata.extras?.getString(PLAYER_EXTRA_MEDIA_SOURCE_ID)}|" +
                (it.localConfiguration?.uri ?: it.requestMetadata.mediaUri)
        }

    private fun mediaUri(item: MediaItem): Uri =
        requireNotNull(item.localConfiguration?.uri ?: item.requestMetadata.mediaUri) {
            "MediaItem ${item.mediaId} has no localConfiguration.uri or requestMetadata.mediaUri"
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
        if (identityChanged) {
            advanceSourceEpoch()
            preserveSourceOnPrepare = false
            preparePreservedSourceOnPrepare = false
        }
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
        preserveSourceOnPrepare = false
        preparePreservedSourceOnPrepare = false
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
    }

    private fun advanceSourceEpoch(): Long {
        val nextEpoch = sourceEpoch.incrementAndGet()
        sourcePosition = sourcePosition.replaceSource(nextEpoch)
        return nextEpoch
    }

    override fun prepare() {
        if (preserveSourceOnPrepare) {
            preserveSourceOnPrepare = false
            if (preparePreservedSourceOnPrepare) {
                postSourceStartCommand(
                    arrayOf("playlist-play-index", "$currentMediaItemIndex"),
                    currentMediaItemIndex,
                )
            }
            preparePreservedSourceOnPrepare = false
            return
        }
        prepareMediaItem(initialIndex, postSelection = false)
        val first = internalMediaItems.firstOrNull() ?: return
        val followUps = internalMediaItems.drop(1).map { loadFileCommand(it, "append") }
        if (initialIndex == 0) {
            enqueueSourceStart(
                command = loadFileCommand(first, "replace"),
                sourceIndex = 0,
                sourceUri = mediaUri(first).toString(),
                followUpCommands = followUps,
            )
        } else {
            enqueueSourceStart(
                command = loadFileCommand(first, "replace"),
                sourceIndex = 0,
                sourceUri = mediaUri(first).toString(),
                bootstrap = true,
                followUpCommands = followUps,
            )
            postSourceStartCommand(arrayOf("playlist-play-index", "$initialIndex"), initialIndex)
        }
    }

    override fun getPlaybackState(): Int = playbackState

    override fun getPlaybackSuppressionReason(): Int = PLAYBACK_SUPPRESSION_REASON_NONE

    override fun getPlayerError(): PlaybackException? = null

    override fun setPlayWhenReady(playWhenReady: Boolean) {
        if (currentPlayWhenReady != playWhenReady) {
            setPlayerStateAndNotifyIfChanged(
                playWhenReady = playWhenReady,
                playWhenReadyChangeReason = PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST,
            )
            if (isPlayerReady) {
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

    override fun getPlayWhenReady(): Boolean = currentPlayWhenReady

    override fun setRepeatMode(repeatMode: Int) {
        val (file, playlist) =
            when (repeatMode) {
                REPEAT_MODE_ONE -> "inf" to "no"
                REPEAT_MODE_ALL -> "no" to "inf"
                else -> "no" to "no"
            }
        mpvLib.setOptionString("loop-file", file)
        mpvLib.setOptionString("loop-playlist", playlist)
    }

    override fun getRepeatMode(): Int = repeatMode

    override fun getShuffleModeEnabled(): Boolean = false

    override fun isLoading(): Boolean = playbackState == STATE_BUFFERING

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

    private fun prepareMediaItem(index: Int, postSelection: Boolean = true) {
        internalMediaItems.getOrNull(index)?.let { mediaItem ->
            markIntentionalEndFile()
            resetInternalState()
            pendingServerSubtitles = mediaItem.serverSubtitleSources()
            val subtitleCommands =
                mediaItem.localConfiguration?.subtitleConfigurations.orEmpty().map { subtitle ->
                    arrayOf(
                        "sub-add",
                        subtitle.uri.toString(),
                        "auto",
                        subtitle.label.orEmpty(),
                        subtitle.language.orEmpty(),
                    )
                }
            replaceInitialCommands(subtitleCommands, mediaUri(mediaItem).toString(), index)
            if (postSelection && currentMediaItemIndex != index) {
                postSourceStartCommand(
                    arrayOf("playlist-play-index", "$index"),
                    index,
                )
            }
            setPlayerStateAndNotifyIfChanged(playbackState = STATE_BUFFERING)
        }
    }

    private fun loadFileCommand(
        mediaItem: MediaItem,
        flags: String,
    ): Array<String> = arrayOf("loadfile", mediaUri(mediaItem).toString(), flags)

    override fun getSeekBackIncrement(): Long = seekBackIncrement

    override fun getSeekForwardIncrement(): Long = seekForwardIncrement

    fun setSeekIncrements(
        backMs: Long,
        forwardMs: Long,
    ) {
        seekBackIncrement = backMs.also { seekForwardIncrement = forwardMs }
    }

    override fun getMaxSeekToPreviousPosition(): Long = C.DEFAULT_MAX_SEEK_TO_PREVIOUS_POSITION_MS

    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) {
        this.playbackParameters = playbackParameters
        val speed = playbackParameters.speed
        if (mpvSpeed != speed) {
            mpvSpeed = speed
            mpvLib.setPropertyDouble("speed", speed.toDouble())
        }
    }

    override fun getPlaybackParameters(): PlaybackParameters = playbackParameters

    override fun stop() {
        markIntentionalEndFile()
        postCommand(arrayOf("stop", "keep-playlist"))
    }

    override fun release() {
        if (released) return
        seekEventListeners.clear()
        runCatching { detachTextureSurface() }
        released = true
        if (handleAudioFocus) {
            AudioManagerCompat.abandonAudioFocusRequest(audioManager, audioFocusRequest)
        }
        resetInternalState()
        runCatching { mpvLib.resetSourceEvents() }
        runCatching { mpvLib.removeObserver(this) }
        commandsClosed.set(true)
        commandHandler.removeCallbacksAndMessages(null)
        commandHandler.post {
            runCatching { mpvLib.destroy() }
            commandThread.quitSafely()
        }
    }

    override fun getCurrentTracks(): Tracks = currentTracks

    override fun getTrackSelectionParameters(): TrackSelectionParameters = trackSelectionParameters

    override fun setTrackSelectionParameters(parameters: TrackSelectionParameters) {
        trackSelectionParameters = parameters
        val disabledTrackTypes =
            parameters.disabledTrackTypes.map { MPVTrackType.fromMedia3TrackType(it) }
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

    override fun getMediaMetadata(): MediaMetadata =
        internalMediaItems.getOrNull(currentMediaItemIndex)?.mediaMetadata ?: MediaMetadata.EMPTY

    override fun getPlaylistMetadata(): MediaMetadata = MediaMetadata.EMPTY

    override fun getCurrentTimeline(): Timeline = timeline

    override fun getCurrentPeriodIndex(): Int = currentMediaItemIndex

    override fun getCurrentMediaItemIndex(): Int = currentMediaItemIndex

    override fun getDuration(): Long = timeline.getWindow(currentMediaItemIndex, window).durationMs

    override fun getCurrentPosition(): Long = confirmedPosition() ?: C.TIME_UNSET

    override fun getBufferedPosition(): Long = confirmedBufferedPosition() ?: C.TIME_UNSET

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

    override fun getTotalBufferedDuration(): Long = bufferedPosition

    override fun isPlayingAd(): Boolean = false

    override fun getCurrentAdGroupIndex(): Int = C.INDEX_UNSET

    override fun getCurrentAdIndexInAdGroup(): Int = C.INDEX_UNSET

    override fun getContentPosition(): Long = currentPosition

    override fun getContentBufferedPosition(): Long = bufferedPosition

    override fun getAudioAttributes(): AudioAttributes = AudioAttributes.DEFAULT

    override fun getVolume(): Float = mpvLib.getPropertyInt("volume")?.div(100f) ?: 0f

    override fun setVideoSurfaceView(surfaceView: SurfaceView?) =
        surfaceView?.holder?.addCallback(surfaceHolder) ?: Unit

    override fun clearVideoSurfaceView(surfaceView: SurfaceView?) =
        surfaceView?.holder?.removeCallback(surfaceHolder) ?: Unit

    override fun setVideoTextureView(textureView: TextureView?) {
        if (textureView == null) {
            detachTextureSurface()
            return
        }
        textureView.surfaceTextureListener = surfaceTextureListener
        val surfaceTexture = textureView.surfaceTexture
        if (textureView.isAvailable && surfaceTexture != null) {
            attachTextureSurface(surfaceTexture, textureView.width, textureView.height)
        }
    }

    override fun clearVideoTextureView(textureView: TextureView?) =
        textureView?.also { it.surfaceTextureListener = null }.let { detachTextureSurface() }

    override fun getVideoSize(): VideoSize {
        val width = mpvLib.getPropertyInt("width")
        val height = mpvLib.getPropertyInt("height")
        return if (width != null && height != null) VideoSize(width, height) else VideoSize.UNKNOWN
    }

    override fun getSurfaceSize(): Size {
        val mpvSize =
            mpvLib.getPropertyString("android-surface-size")?.split("x") ?: return Size.UNKNOWN
        return runCatching { Size(mpvSize[0].toInt(), mpvSize[1].toInt()) }
            .getOrDefault(Size.UNKNOWN)
    }

    override fun getCurrentCues(): CueGroup = CueGroup(emptyList(), 0)

    override fun getDeviceInfo(): DeviceInfo =
        DeviceInfo.Builder(DeviceInfo.PLAYBACK_TYPE_LOCAL).setMaxVolume(100).build()

    override fun getDeviceVolume(): Int = mpvLib.getPropertyInt("volume") ?: 0

    override fun isDeviceMuted(): Boolean = mpvLib.getPropertyBoolean("mute") ?: false

    @Deprecated("Deprecated in Java")
    override fun setDeviceVolume(volume: Int) {
        throw IllegalArgumentException(
            "You should use global volume controls. Check out AUDIO_SERVICE."
        )
    }

    override fun setDeviceVolume(volume: Int, flags: Int) = mpvLib.setPropertyInt("volume", volume)

    @Deprecated("Deprecated in Java")
    override fun increaseDeviceVolume() = unsupportedVolumeControls()

    override fun increaseDeviceVolume(flags: Int) = unsupportedVolumeControls()

    @Deprecated("Deprecated in Java")
    override fun decreaseDeviceVolume() = unsupportedVolumeControls()

    override fun decreaseDeviceVolume(flags: Int) = unsupportedVolumeControls()

    @Deprecated("Deprecated in Java")
    override fun setDeviceMuted(muted: Boolean) = unsupportedVolumeControls()

    override fun setDeviceMuted(muted: Boolean, flags: Int) =
        mpvLib.setPropertyBoolean("mute", muted)

    override fun mute() = mpvLib.setPropertyBoolean("mute", true)

    override fun unmute() = mpvLib.setPropertyBoolean("mute", false)

    private fun unsupportedVolumeControls(): Nothing =
        throw IllegalArgumentException(
            "You should use global volume controls. Check out AUDIO_SERVICE."
        )

    fun applyResizeMode(resizeMode: Int) {
        if (released) return
        val props = mpvResizeProperties(resizeMode)
        val keepAspect = if (props.keepAspect) "yes" else "no"
        val panscan = if (props.panscan) "1" else "0"
        runCatching {
            setMpvString("keepaspect", keepAspect)
            setMpvString("panscan", panscan)
            val margins = if (props.panscan) "yes" else "no"
            setMpvString("sub-use-margins", margins)
            setMpvString("sub-ass-force-margins", margins)
        }
            .onFailure { Timber.w(it, "mpv 画面比例应用失败") }
    }

    private fun setMpvString(name: String, value: String) {
        runCatching { mpvLib.setPropertyString(name, value) }
        runCatching { mpvLib.setOptionString(name, value) }
    }

    private fun readMpvString(name: String): String? = runCatching {
        mpvLib.getPropertyString(name)
    }
        .getOrNull()

    private var textureSurface: Surface? = null
    private var released: Boolean = false

    private fun attachTextureSurface(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
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
            surface.release()
            return
        }
        mpvLib.setOptionString("vo", "null")
        mpvLib.setOptionString("force-window", "no")
        mpvLib.detachSurface()
        surface.release()
    }

    private val surfaceTextureListener =
        object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(
                surfaceTexture: SurfaceTexture,
                width: Int,
                height: Int,
            ) = attachTextureSurface(surfaceTexture, width, height)

            override fun onSurfaceTextureSizeChanged(
                surfaceTexture: SurfaceTexture,
                width: Int,
                height: Int,
            ) =
                if (released) Unit
                else mpvLib.setPropertyString("android-surface-size", "${width}x$height")

            override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean =
                detachTextureSurface().let { true }

            override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) = Unit
        }
    private val surfaceHolder: SurfaceHolder.Callback =
        object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                if (released) return
                mpvLib.attachSurface(holder.surface)
                mpvLib.setOptionString("force-window", "yes")
                mpvLib.setOptionString("vo", videoOutput)
            }

            override fun surfaceChanged(
                holder: SurfaceHolder,
                format: Int,
                width: Int,
                height: Int,
            ) =
                if (released) Unit
                else mpvLib.setPropertyString("android-surface-size", "${width}x$height")

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                if (released) return
                mpvLib.setOptionString("vo", "null")
                mpvLib.setOptionString("force-window", "no")
                mpvLib.detachSurface()
            }
        }

    companion object {
        private const val AUDIO_FOCUS_DUCKING = 0.5f
        private const val END_FILE_SUPPRESS_WINDOW_MS = 3_000L
        private const val SOURCE_RECOVERY_LIMIT = 3
        private const val SOURCE_RECOVERY_RETRY_MS = 250L
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

        private fun JSONObject.optNullableString(name: String): String? =
            if (has(name) && !isNull(name)) getString(name) else null

        private fun JSONObject.optNullableDouble(name: String): Double? =
            if (has(name) && !isNull(name)) getDouble(name) else null

        private fun createTracksGroupfromMpvJson(json: JSONObject): Tracks.Group {
            val trackType = MPVTrackType.entries.first { it.type == json.optString("type") }
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

internal fun selectServerSubtitlesToInject(
    sources: List<PlayerSubtitleSource>
): List<PlayerSubtitleSource> = sources.filter { it.isTextBased && !it.isExternal }

internal data class MpvSubtitleTrackInfo(
    val external: Boolean,
    val id: Int? = null,
    val fileName: String? = null,
)

internal fun hasInternalSubtitleTrack(tracks: List<MpvSubtitleTrackInfo>): Boolean = tracks.any {
    !it.external
}

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
