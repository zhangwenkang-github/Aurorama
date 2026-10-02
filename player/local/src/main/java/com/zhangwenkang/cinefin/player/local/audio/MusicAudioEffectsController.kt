package com.zhangwenkang.cinefin.player.local.audio

import com.zhangwenkang.cinefin.settings.domain.AppPreferences
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.pow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** ReplayGain 模式（对齐 mpv 的 `replaygain=no|track|album` 三态）。 */
enum class ReplayGainMode(val key: String, val label: String) {
    OFF("off", "关闭"),
    TRACK("track", "曲目"),
    ALBUM("album", "专辑");

    companion object {
        fun fromKey(key: String?): ReplayGainMode = entries.firstOrNull { it.key == key } ?: OFF
    }
}

/** 音效面板「本机增益覆盖」的可调范围（dB）：手动覆盖的取值上下限。 */
const val MUSIC_REPLAYGAIN_OVERRIDE_MIN_DB = -12f
const val MUSIC_REPLAYGAIN_OVERRIDE_MAX_DB = 12f

/** 滑杆步进（dB）：0.5 dB 一档，24 dB 区间共 48 档。 */
const val MUSIC_REPLAYGAIN_OVERRIDE_STEP_DB = 0.5f

/**
 * 音乐音效状态中枢（W30-MUSIC-FX 进程级单例）。
 *
 * 职责：持有 EQ / ReplayGain / 交叉淡化的偏好与运行时状态，写入 [processor]（音频链）与 `AppPreferences`（持久化）。 `player/local`
 * 的播放链（[com.zhangwenkang.cinefin.player.local.presentation.PlayerHolder]、MusicPlaybackControllerImpl）
 * 与 `modes:music` 的 UI 都只与本控制器交互，避免音频线程直接读偏好。
 *
 * W35：ReplayGain 增加「本机增益覆盖」编辑（写 `files/replaygain/<itemId>.txt`，不写服务器）。
 *
 * 线程约定：方法可在任意线程调用（StateFlow 与 processor 的 `@Volatile` 字段均线程安全）。
 */
@Singleton
class MusicAudioEffectsController
@Inject
constructor(
    private val appPreferences: AppPreferences,
    private val replayGainReader: ReplayGainTagReader,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

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

    /** 当前是否有曲目（供面板决定「本机增益覆盖」是否可编辑）。 */
    private val _hasCurrentTrack = MutableStateFlow(false)
    val hasCurrentTrack: StateFlow<Boolean> = _hasCurrentTrack.asStateFlow()

    /** 当前曲目的本机覆盖值（null = 未设置 / 未播放曲目）。 */
    private val _overrideTrackDb = MutableStateFlow<Float?>(null)
    val overrideTrackDb: StateFlow<Float?> = _overrideTrackDb.asStateFlow()

    private val _overrideAlbumDb = MutableStateFlow<Float?>(null)
    val overrideAlbumDb: StateFlow<Float?> = _overrideAlbumDb.asStateFlow()

    /** 覆盖文件变更版本号：播放链 collect 后重读标签，保证"写入 / 清除"立即生效。 */
    private val _overrideRevision = MutableStateFlow(0L)
    val overrideRevision: StateFlow<Long> = _overrideRevision.asStateFlow()

    private var currentItemId: UUID? = null
    private var currentTags: TrackReplayGain? = null
    private var overrideLoadToken = 0

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

    /** 由播放链在切歌 / 起播时调用（与 ReplayGain 模式无关）：登记当前曲目并加载本机覆盖值， 供音效面板编辑；传 null 表示没有在播曲目。 */
    fun setCurrentTrack(itemId: UUID?) {
        currentItemId = itemId
        _hasCurrentTrack.value = itemId != null
        overrideLoadToken += 1
        _overrideTrackDb.value = null
        _overrideAlbumDb.value = null
        if (itemId == null) return
        val token = overrideLoadToken
        scope.launch {
            val override = replayGainReader.readOverrideOnly(itemId)
            if (token != overrideLoadToken || currentItemId != itemId) return@launch
            _overrideTrackDb.value = override?.trackGainDb
            _overrideAlbumDb.value = override?.albumGainDb
        }
    }

    /** 由播放链在标签读取完成后调用；传 null 表示当前曲目无标签（覆盖值不清，面板仍可编辑）。 */
    fun setCurrentTrackReplayGain(itemId: UUID, tags: TrackReplayGain?) {
        currentItemId = itemId
        _hasCurrentTrack.value = true
        currentTags = tags
        if (tags?.source == ReplayGainSource.LOCAL_OVERRIDE) {
            _overrideTrackDb.value = tags.trackGainDb
            _overrideAlbumDb.value = tags.albumGainDb
        }
        applyReplayGainToProcessor()
    }

    /** 只清空标签（如切到「关闭」档），保留当前曲目与覆盖编辑状态。 */
    fun clearReplayGainTags() {
        currentTags = null
        applyReplayGainToProcessor()
    }

    /** 停止播放 / 换队列失败：清空当前曲目与覆盖编辑状态。 */
    fun clearCurrentTrack() {
        currentItemId = null
        currentTags = null
        overrideLoadToken += 1
        _hasCurrentTrack.value = false
        _overrideTrackDb.value = null
        _overrideAlbumDb.value = null
        applyReplayGainToProcessor()
    }

    /** 拖动「曲目增益」滑杆：立即生效（不落盘，拖动结束由 [commitOverride] 写入）。 */
    fun previewOverrideTrackDb(gainDb: Float?) {
        _overrideTrackDb.value = sanitizeOverrideDb(gainDb)
        applyOverridePreview()
    }

    /** 拖动「专辑增益」滑杆：立即生效（不落盘）。 */
    fun previewOverrideAlbumDb(gainDb: Float?) {
        _overrideAlbumDb.value = sanitizeOverrideDb(gainDb)
        applyOverridePreview()
    }

    /** 把当前覆盖值写入本机文件（两个都为空 = 删除文件）；随后通知播放链重读标签。 */
    fun commitOverride() {
        val itemId = currentItemId ?: return
        val track = _overrideTrackDb.value
        val album = _overrideAlbumDb.value
        scope.launch {
            val written = replayGainReader.writeOverride(itemId, track, album)
            if (currentItemId != itemId) return@launch
            currentTags = written
            _overrideTrackDb.value = written?.trackGainDb
            _overrideAlbumDb.value = written?.albumGainDb
            applyReplayGainToProcessor()
            _overrideRevision.value += 1
        }
    }

    /** 清除当前曲目的本机覆盖（删除覆盖文件）。 */
    fun clearLocalOverride() {
        if (currentItemId == null) return
        _overrideTrackDb.value = null
        _overrideAlbumDb.value = null
        commitOverride()
    }

    private fun applyOverridePreview() {
        val track = _overrideTrackDb.value
        val album = _overrideAlbumDb.value
        currentTags =
            TrackReplayGain(track, album, ReplayGainSource.LOCAL_OVERRIDE).takeUnless { it.isEmpty }
        applyReplayGainToProcessor()
    }

    private fun sanitizeOverrideDb(gainDb: Float?): Float? =
        gainDb
            ?.takeIf { it.isFinite() }
            ?.coerceIn(MUSIC_REPLAYGAIN_OVERRIDE_MIN_DB, MUSIC_REPLAYGAIN_OVERRIDE_MAX_DB)

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
