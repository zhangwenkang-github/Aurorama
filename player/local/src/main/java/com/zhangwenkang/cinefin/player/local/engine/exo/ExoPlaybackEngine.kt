package com.zhangwenkang.cinefin.player.local.engine.exo

import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.zhangwenkang.cinefin.player.core.playback.EngineCapabilities
import com.zhangwenkang.cinefin.player.core.playback.PlaybackEngine
import com.zhangwenkang.cinefin.player.local.engine.Media3PlaybackEngine

/**
 * ExoPlayer 引擎适配器（W2-T4）。
 *
 * 只做逐一直接委托，不改变任何语义：实例的创建、监听与生命周期仍由 `PlayerHolder` / ViewModel 按既有方式管理。
 *
 * [media3Player] 是 MediaSession、UI 与尚未迁移的既有代码的接入点。
 *
 * 能力位取值见 ADR-0010 的「能力位取值表」。
 */
internal class ExoPlaybackEngine(private val exoPlayer: ExoPlayer) : Media3PlaybackEngine {
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

    internal companion object {
        /**
         * ExoPlayer 的能力位（2026-11-18 现状）。
         *
         * HLS 靠 `media3-exoplayer-hls` 依赖；软解靠 `CinefinRenderersFactory` 的扩展渲染器与解码器回退；
         * 字幕外观由自绘层承接（`PlayerEvents.SubtitleStyleChanged`）；音频延迟挂在音频链的 `AudioDelayProcessor`
         * 上；侧载字幕走字幕管线 + `SideloadedSubtitleStore.INDEX_BASE`； 随机播放是 ExoPlayer 原生能力。
         */
        val CAPABILITIES =
            EngineCapabilities(
                canPlayHls = true,
                supportsSoftwareDecode = true,
                supportsSubtitleStyling = true,
                supportsAudioDelay = true,
                supportsSideloadedSubtitles = true,
                supportsShuffleMode = true,
            )
    }
}
