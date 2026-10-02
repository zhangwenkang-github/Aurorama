package com.zhangwenkang.cinefin.player.local.audio

import com.zhangwenkang.cinefin.settings.domain.AppPreferences
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.pow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** ReplayGain 模式（对齐 mpv 的 `replaygain=no|track|album` 三态）。 */
enum class ReplayGainMode(val key: String, val label: String) {
    OFF("off", "关闭"),
    TRACK("track", "曲目"),
    ALBUM("album", "专辑");

    companion object {
        fun fromKey(key: String?): ReplayGainMode = entries.firstOrNull { it.key == key } ?: OFF
    }
}

/**
 * 音乐音效状态中枢（W30-MUSIC-FX 进程级单例）。
 *
 * 职责：持有 EQ / ReplayGain / 交叉淡化的偏好与运行时状态，写入 [processor]（音频链）与 `AppPreferences`（持久化）。 `player/local`
 * 的播放链（[com.zhangwenkang.cinefin.player.local.presentation.PlayerHolder]、MusicPlaybackControllerImpl）
 * 与 `modes:music` 的 UI 都只与本控制器交互，避免音频线程直接读偏好。
 *
 * 线程约定：方法可在任意线程调用（StateFlow 与 processor 的 `@Volatile` 字段均线程安全）。
 */
@Singleton
class MusicAudioEffectsController @Inject constructor(private val appPreferences: AppPreferences) {

    /** 挂在 ExoPlayer 音频链上的处理器（由 PlayerHolder 取走挂链）。 */
    val processor: MusicAudioEffectsProcessor = MusicAudioEffectsProcessor()

    private val _equalizerEnabled =
        MutableStateFlow(appPreferences.getValue(appPreferences.musicEqualizerEnabled))
    val equalizerEnabled: StateFlow<Boolean> = _equalizerEnabled.asStateFlow()

    private val _equalizerPreset =
        MutableStateFlow(
            MusicEqualizerPreset.fromKey(
                appPreferences.getValue(appPreferences.musicEqualizerPreset)
            )
        )
    val equalizerPreset: StateFlow<MusicEqualizerPreset> = _equalizerPreset.asStateFlow()

    private val _customBands =
        MutableStateFlow(
            parseMusicEqualizerBands(
                appPreferences.getValue(appPreferences.musicEqualizerCustomBands)
            )
        )

    private val _equalizerBands =
        MutableStateFlow(musicEqualizerBands(_equalizerPreset.value, _customBands.value))
    val equalizerBands: StateFlow<List<Float>> = _equalizerBands.asStateFlow()

    private val _replayGainMode =
        MutableStateFlow(
            ReplayGainMode.fromKey(appPreferences.getValue(appPreferences.musicReplayGainMode))
        )
    val replayGainMode: StateFlow<ReplayGainMode> = _replayGainMode.asStateFlow()

    private val _replayGainLabel = MutableStateFlow<String?>(null)
    val replayGainLabel: StateFlow<String?> = _replayGainLabel.asStateFlow()

    private val _crossfadeSeconds =
        MutableStateFlow(
            MusicCrossfadeMath.sanitizeCrossfadeSeconds(
                appPreferences.getValue(appPreferences.musicCrossfadeSeconds)
            )
        )
    val crossfadeSeconds: StateFlow<Int> = _crossfadeSeconds.asStateFlow()

    private var currentItemId: UUID? = null
    private var currentTags: TrackReplayGain? = null

    init {
        applyEqualizerToProcessor()
        applyReplayGainToProcessor()
    }

    fun setEqualizerEnabled(enabled: Boolean) {
        appPreferences.setValue(appPreferences.musicEqualizerEnabled, enabled)
        _equalizerEnabled.value = enabled
        applyEqualizerToProcessor()
    }

    fun selectEqualizerPreset(preset: MusicEqualizerPreset) {
        appPreferences.setValue(appPreferences.musicEqualizerPreset, preset.key)
        _equalizerPreset.value = preset
        _equalizerBands.value = musicEqualizerBands(preset, _customBands.value)
        applyEqualizerToProcessor()
    }

    /** 拖动某一频段的滑杆：隐式切到「自定义」预设（**不落盘**，拖动结束由 [commitEqualizerBands] 写入偏好）。 */
    fun setEqualizerBand(index: Int, gainDb: Float) {
        if (index !in 0 until MUSIC_EQUALIZER_BAND_COUNT) return
        val bands =
            _equalizerBands.value.toMutableList().also { list ->
                list[index] =
                    gainDb
                        .takeIf { it.isFinite() }
                        ?.coerceIn(
                            MUSIC_EQUALIZER_MIN_GAIN_DB,
                            MUSIC_EQUALIZER_MAX_GAIN_DB,
                        ) ?: 0f
            }
        _customBands.value = bands
        _equalizerPreset.value = MusicEqualizerPreset.CUSTOM
        _equalizerBands.value = bands
        applyEqualizerToProcessor()
    }

    /** 把当前「自定义」频段写入偏好（滑杆拖动结束 / 按下预设后调用）。 */
    fun commitEqualizerBands() {
        appPreferences.setValue(
            appPreferences.musicEqualizerPreset,
            MusicEqualizerPreset.CUSTOM.key,
        )
        appPreferences.setValue(
            appPreferences.musicEqualizerCustomBands,
            encodeMusicEqualizerBands(_equalizerBands.value),
        )
    }

    fun setReplayGainMode(mode: ReplayGainMode) {
        appPreferences.setValue(appPreferences.musicReplayGainMode, mode.key)
        _replayGainMode.value = mode
        applyReplayGainToProcessor()
    }

    fun setCrossfadeSeconds(seconds: Int) {
        val sanitized = MusicCrossfadeMath.sanitizeCrossfadeSeconds(seconds)
        appPreferences.setValue(appPreferences.musicCrossfadeSeconds, sanitized)
        _crossfadeSeconds.value = sanitized
    }

    /** 由播放链在切歌 / 标签读取完成后调用；传 null 表示当前曲目无标签。 */
    fun setCurrentTrackReplayGain(itemId: UUID, tags: TrackReplayGain?) {
        currentItemId = itemId
        currentTags = tags
        applyReplayGainToProcessor()
    }

    fun clearCurrentTrackReplayGain() {
        currentItemId = null
        currentTags = null
        applyReplayGainToProcessor()
    }

    /** 当前生效的 ReplayGain dB（UI / 日志用）；未应用时为 null。 */
    fun effectiveReplayGainDb(): Float? {
        if (_replayGainMode.value == ReplayGainMode.OFF) return null
        return effectiveGainDb(currentTags, _replayGainMode.value)
    }

    private fun applyEqualizerToProcessor() {
        processor.equalizerEnabled = _equalizerEnabled.value
        processor.equalizerGainsDb = _equalizerBands.value
    }

    private fun applyReplayGainToProcessor() {
        val db = effectiveReplayGainDb()
        processor.replayGainFactor =
            db?.let { 10f.pow(it / 20f).coerceIn(MIN_GAIN_FACTOR, MAX_GAIN_FACTOR) } ?: 1f
        _replayGainLabel.value = replayGainLabel()
    }

    private fun replayGainLabel(): String? {
        val mode = _replayGainMode.value
        if (mode == ReplayGainMode.OFF) return null
        val tags = currentTags
        if (currentItemId == null || tags == null || tags.isEmpty) return "未检测到 ReplayGain 标签"
        val db = effectiveGainDb(tags, mode) ?: return "未检测到 ReplayGain 标签"
        val fallback = mode == ReplayGainMode.ALBUM && tags.albumGainDb == null
        return when {
            fallback -> "本曲无专辑标签，已回落曲目 %+.1f dB（%s）".format(db, tags.source.label)
            mode == ReplayGainMode.TRACK -> "曲目标签 %+.1f dB（%s）".format(db, tags.source.label)
            else -> "专辑标签 %+.1f dB（%s）".format(db, tags.source.label)
        }
    }

    private fun effectiveGainDb(tags: TrackReplayGain?, mode: ReplayGainMode): Float? =
        when (mode) {
            ReplayGainMode.OFF -> null
            ReplayGainMode.TRACK -> tags?.trackGainDb
            ReplayGainMode.ALBUM -> tags?.albumGainDb ?: tags?.trackGainDb
        }

    companion object {
        /** 增益上限 +18 dB（8×）与下限 -26 dB（0.05×）：避免脏标签造成爆音或局部静音。 */
        private const val MAX_GAIN_FACTOR = 8f
        private const val MIN_GAIN_FACTOR = 0.05f

        fun equalizerActive(enabled: Boolean, bands: List<Float>): Boolean =
            enabled && bands.any { abs(it) > 0.01f }
    }
}
