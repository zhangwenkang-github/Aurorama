package com.zhangwenkang.cinefin.player.local.syncplay

import com.zhangwenkang.cinefin.player.core.domain.models.SubtitleStyle
import com.zhangwenkang.cinefin.player.core.playback.EngineCapabilities
import com.zhangwenkang.cinefin.player.core.playback.EngineTrack
import com.zhangwenkang.cinefin.player.core.playback.EngineTrackType
import com.zhangwenkang.cinefin.player.core.playback.PlaybackEngine
import kotlinx.coroutines.CoroutineScope

/** 记录动词调用的假内核：SyncPlay 只碰 play/pause/setRate（以及不该被直接调用的 seekTo）。 */
class FakePlaybackEngine(override val backendName: String = PlaybackEngine.BACKEND_EXOPLAYER) :
    PlaybackEngine {
    val calls = mutableListOf<String>()
    var appliedRate: Float = 1f
    var subtitleDelayMs: Long = 0L
    var audioDelayMs: Long = 0L

    override val capabilities =
        EngineCapabilities(
            canPlayHls = true,
            supportsSoftwareDecode = true,
            supportsSubtitleStyling = true,
            supportsAudioDelay = true,
            supportsSideloadedSubtitles = true,
            supportsShuffleMode = true,
            supportsTrackEnumeration = true,
            supportsSubtitleDelay = true,
        )

    override fun prepare() {
        calls += "prepare"
    }

    override fun play() {
        calls += "play"
    }

    override fun pause() {
        calls += "pause"
    }

    override fun seekTo(positionMs: Long) {
        calls += "seekTo:$positionMs"
    }

    override fun seekTo(itemIndex: Int, positionMs: Long) {
        calls += "seekToItem:$itemIndex:$positionMs"
    }

    override fun setRate(rate: Float) {
        calls += "setRate:$rate"
        this.appliedRate = rate
    }

    override fun release() {
        calls += "release"
    }

    override fun availableTracks(type: EngineTrackType): List<EngineTrack> = emptyList()

    override fun selectedTrack(type: EngineTrackType): String? = null

    override fun selectTrack(type: EngineTrackType, trackId: String?) {
        calls += "selectTrack:$type:$trackId"
    }

    override fun setSubtitleDelay(delayMs: Long) {
        subtitleDelayMs = delayMs
    }

    override fun getSubtitleDelay(): Long = subtitleDelayMs

    override fun setAudioDelay(delayMs: Long) {
        audioDelayMs = delayMs
    }

    override fun getAudioDelay(): Long = audioDelayMs

    override fun setSubtitleStyle(style: SubtitleStyle) {
        calls += "setSubtitleStyle"
    }
}

/** 假宿主：SyncPlay 装配层对播放器的全部依赖都在这里收口，单测不碰 media3 / Android。 */
class FakeSyncPlayHost(
    override val scope: CoroutineScope,
    engine: FakePlaybackEngine = FakePlaybackEngine(),
) : SyncPlayPlayerHost {
    override var backendName: String = engine.backendName
    var engineImpl: FakePlaybackEngine? = engine
    var positionMs: Long = 0L
    var playing: Boolean = false
    val seeks = mutableListOf<Pair<Long, String>>()

    override val engine: PlaybackEngine?
        get() = engineImpl

    override val currentPositionMs: Long
        get() = positionMs

    override val isPlaying: Boolean
        get() = playing

    override fun submitSeek(positionMs: Long, source: String) {
        seeks += positionMs to source
    }

    private val backendReboundListeners = mutableListOf<() -> Unit>()

    override fun addBackendReboundListener(listener: () -> Unit) {
        backendReboundListeners += listener
    }

    /** 模拟 `PlayerHolder.notifyBackendRebound()`。 */
    fun notifyBackendRebound() {
        backendReboundListeners.forEach { it() }
    }
}
