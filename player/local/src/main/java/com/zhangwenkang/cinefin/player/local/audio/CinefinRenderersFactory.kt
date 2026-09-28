package com.zhangwenkang.cinefin.player.local.audio

import android.content.Context
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioOffloadSupport
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink

/**
 * 在 Media3 默认渲染器基础上，把 [AudioDelayProcessor] 挂进音频处理链（§1.2）。
 *
 * 除了多挂一个处理器，其余参数与 `DefaultRenderersFactory.buildAudioSink` 的默认实现保持一致。
 */
class CinefinRenderersFactory(
    context: Context,
    private val audioDelayProcessor: AudioDelayProcessor,
) : DefaultRenderersFactory(context) {

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean,
    ): AudioSink =
        DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioOutputPlaybackParameters(enableAudioTrackPlaybackParams)
            .setAudioProcessors(arrayOf(audioDelayProcessor))
            /*
             * 关掉音频 offload：offload 会把压缩音频直接交给硬件解码播放，
             * 不经过 PCM 处理链，音轨延迟会静默失效。这里宁可多耗一点电，也要保证
             * 「面板上能调、调了就生效」。
             */
            .setAudioOffloadSupportProvider { _, _ -> AudioOffloadSupport.DEFAULT_UNSUPPORTED }
            .build()
}
