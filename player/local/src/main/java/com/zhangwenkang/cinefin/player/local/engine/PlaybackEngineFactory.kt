package com.zhangwenkang.cinefin.player.local.engine

import androidx.media3.exoplayer.ExoPlayer
import com.zhangwenkang.cinefin.player.local.audio.AudioDelayProcessor
import com.zhangwenkang.cinefin.player.local.engine.exo.ExoPlaybackEngine
import com.zhangwenkang.cinefin.player.local.engine.mpv.MpvPlaybackEngine
import com.zhangwenkang.cinefin.player.local.mpv.MPVPlayer

/**
 * 引擎工厂（W2-T4）：把建好的 media3 实例包成 [Media3PlaybackEngine]。
 *
 * 内核配置（音频属性、轨道选择器、解码策略、mpv 输出参数）仍留在 `PlayerHolder`：本步只把「创建」与「持有」 分开，不移动任何偏好读取顺序，也不改变实例创建时机。
 */
internal object PlaybackEngineFactory {
    /**
     * ExoPlayer 实例 → 引擎适配器。
     *
     * [audioDelayProcessor] 与播放实例共用同一个处理链：引擎的音轨延迟动词直接写它的目标值。
     */
    fun exo(
        exoPlayer: ExoPlayer,
        audioDelayProcessor: AudioDelayProcessor? = null,
    ): Media3PlaybackEngine = ExoPlaybackEngine(exoPlayer, audioDelayProcessor)

    /** MPVPlayer 实例 → 引擎适配器。 */
    fun mpv(mpvPlayer: MPVPlayer): Media3PlaybackEngine = MpvPlaybackEngine(mpvPlayer)
}
