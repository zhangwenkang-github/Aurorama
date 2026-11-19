package com.zhangwenkang.cinefin.player.local.engine.exo

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.exoplayer.ExoPlayer
import com.zhangwenkang.cinefin.player.core.domain.models.SubtitleStyle
import com.zhangwenkang.cinefin.player.core.playback.EngineCapabilities
import com.zhangwenkang.cinefin.player.core.playback.EngineTrack
import com.zhangwenkang.cinefin.player.core.playback.EngineTrackType
import com.zhangwenkang.cinefin.player.core.playback.PlaybackEngine
import com.zhangwenkang.cinefin.player.local.audio.AudioDelayProcessor
import com.zhangwenkang.cinefin.player.local.engine.Media3PlaybackEngine
import com.zhangwenkang.cinefin.player.local.presentation.NATIVE_SUBTITLE_SOURCE_ID_PREFIX
import com.zhangwenkang.cinefin.player.local.subtitle.PlayerSubtitleController

/**
 * ExoPlayer 引擎适配器（W2-T4；W4-T3a-1 补轨道 / 延迟 / 样式动词）。
 *
 * 播放动词只做逐一直接委托，不改变任何语义：实例的创建、监听与生命周期仍由 `PlayerHolder` / ViewModel 按既有方式管理。
 *
 * [media3Player] 是 MediaSession、UI 与尚未迁移的既有代码的接入点。
 *
 * 轨道动词直接读写 `Tracks` / `TrackSelectionParameters`，是「落 override」的原语；语言优先级
 * （`TrackSelectionEngine`）与字幕接管路由仍归上层，引擎不重算策略。
 *
 * 能力位取值见 ADR-0010 的「能力位取值表」。
 */
internal class ExoPlaybackEngine(
    private val exoPlayer: ExoPlayer,
    private val audioDelayProcessor: AudioDelayProcessor? = null,
) : Media3PlaybackEngine {
    /**
     * 字幕延迟 / 样式的执行者，由装配层注入（见 [ExoSubtitleHooks]）。
     *
     * 未注入时字幕延迟退化为实例内记忆值（其它动词不变），保证引擎面可独立使用。
     */
    internal var subtitleHooks: ExoSubtitleHooks? = null

    private var detachedSubtitleDelayMs: Long = 0L

    override val capabilities: EngineCapabilities
        get() = CAPABILITIES

    override val backendName: String
        get() = PlaybackEngine.BACKEND_EXOPLAYER

    /** 底层 media3 实例；与委托目标同一个对象。 */
    override val media3Player: Player
        get() = exoPlayer

    override fun prepare() = exoPlayer.prepare()

    override fun play() = exoPlayer.play()

    override fun pause() = exoPlayer.pause()

    override fun seekTo(positionMs: Long) = exoPlayer.seekTo(positionMs)

    override fun seekTo(itemIndex: Int, positionMs: Long) = exoPlayer.seekTo(itemIndex, positionMs)

    override fun setRate(rate: Float) = exoPlayer.setPlaybackSpeed(rate)

    override fun release() = exoPlayer.release()

    override fun availableTracks(type: EngineTrackType): List<EngineTrack> {
        val trackType = type.toMedia3TrackType()
        return exoPlayer.currentTracks.groups.flatMapIndexed { groupIndex, group ->
            if (group.type != trackType) return@flatMapIndexed emptyList()
            (0 until group.length).mapNotNull { trackIndex ->
                if (!group.isTrackSupported(trackIndex)) return@mapNotNull null
                val format = group.getTrackFormat(trackIndex)
                EngineTrack(
                    id = exoTrackId(groupIndex, trackIndex),
                    type = type,
                    language = format.language,
                    title = format.label,
                    codec = exoCodecName(format.codecs, format.sampleMimeType),
                    isExternal = format.isInjectedSubtitle(),
                    isDefault = format.selectionFlags and C.SELECTION_FLAG_DEFAULT != 0,
                    isForced = format.selectionFlags and C.SELECTION_FLAG_FORCED != 0,
                )
            }
        }
    }

    override fun selectedTrack(type: EngineTrackType): String? {
        val trackType = type.toMedia3TrackType()
        exoPlayer.currentTracks.groups.forEachIndexed { groupIndex, group ->
            if (group.type != trackType) return@forEachIndexed
            for (trackIndex in 0 until group.length) {
                if (group.isTrackSelected(trackIndex)) return exoTrackId(groupIndex, trackIndex)
            }
        }
        return null
    }

    override fun selectTrack(
        type: EngineTrackType,
        trackId: String?,
    ) {
        val trackType = type.toMedia3TrackType()
        if (trackId == null) {
            // 只有字幕允许「null = 关闭」；音轨 / 视频轨传 null 视为无操作。
            if (type != EngineTrackType.SUBTITLE) return
            exoPlayer.trackSelectionParameters =
                exoPlayer.trackSelectionParameters
                    .buildUpon()
                    .clearOverridesOfType(trackType)
                    .setTrackTypeDisabled(trackType, true)
                    .build()
            return
        }
        val (groupIndex, trackIndex) = decodeExoTrackId(trackId) ?: return
        val group = exoPlayer.currentTracks.groups.getOrNull(groupIndex) ?: return
        if (group.type != trackType) return
        if (trackIndex !in 0 until group.length) return
        exoPlayer.trackSelectionParameters =
            exoPlayer.trackSelectionParameters
                .buildUpon()
                .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, trackIndex))
                .setTrackTypeDisabled(trackType, false)
                .build()
    }

    override fun setSubtitleDelay(delayMs: Long) {
        val clamped =
            delayMs.coerceIn(
                -PlayerSubtitleController.DELAY_LIMIT_MS,
                PlayerSubtitleController.DELAY_LIMIT_MS,
            )
        detachedSubtitleDelayMs = clamped
        subtitleHooks?.setSubtitleDelayMs(clamped)
    }

    override fun getSubtitleDelay(): Long =
        subtitleHooks?.subtitleDelayMs() ?: detachedSubtitleDelayMs

    override fun setAudioDelay(delayMs: Long) {
        val clamped =
            delayMs.coerceIn(
                -AudioDelayProcessor.MAX_DELAY_MS,
                AudioDelayProcessor.MAX_DELAY_MS,
            )
        audioDelayProcessor?.delayMs = clamped
    }

    override fun getAudioDelay(): Long = audioDelayProcessor?.delayMs ?: 0L

    override fun setSubtitleStyle(style: SubtitleStyle) {
        subtitleHooks?.applySubtitleStyle(style)
    }

    internal companion object {
        /**
         * ExoPlayer 的能力位（2026-11-19 现状）。
         *
         * HLS 靠 `media3-exoplayer-hls` 依赖；软解靠 `CinefinRenderersFactory` 的扩展渲染器与解码器回退；
         * 字幕外观由自绘层承接（`PlayerEvents.SubtitleStyleChanged`）；音频延迟挂在音频链的 `AudioDelayProcessor`
         * 上；字幕延迟由自研字幕覆盖层兑现（`PlayerSubtitleController`）；侧载字幕走字幕管线 +
         * `SideloadedSubtitleStore.INDEX_BASE`； 随机播放是 ExoPlayer 原生能力。
         */
        val CAPABILITIES =
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
    }
}

/**
 * 服务器字幕 / 侧载字幕都经 `MediaItem.SubtitleConfiguration` 注入，id 带固定前缀；容器内嵌轨没有该前缀。
 *
 * 与 mpv 的 `external` 标志语义一致：这里判的是「容器外附加」，不是「是否来自服务器」。
 */
private fun Format.isInjectedSubtitle(): Boolean =
    id?.startsWith(NATIVE_SUBTITLE_SOURCE_ID_PREFIX) == true
