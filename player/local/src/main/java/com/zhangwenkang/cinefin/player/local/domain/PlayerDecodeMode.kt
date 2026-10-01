package com.zhangwenkang.cinefin.player.local.domain

import androidx.media3.exoplayer.DefaultRenderersFactory

/**
 * 解码策略（W12 反馈 B）：**硬解优先（失败自动回退） / 仅软解**。
 *
 * 播放优先级：服务器转码 / 解码 → 本地硬解 → 软解（软解最耗电，只在硬解不可用时兜底，或由用户显式选择）。
 *
 * 抽成纯函数（+ 单测）是因为同一份偏好要被三个地方读：PlayerHolder 建 ExoPlayer 的渲染器模式、 mpv 的 `hwdec` 参数、以及解码面板的选中态——散落成三处
 * `if` 很容易出现「面板显示软解但内核还是硬解」。
 */
object PlayerDecodeMode {
    /** 硬解优先：ExoPlayer 先试 MediaCodec，初始化失败自动换下一个解码器（含 FFmpeg 软解） */
    const val HARDWARE = "hardware"

    /** 仅软解：优先走扩展（FFmpeg）渲染器；mpv 关掉硬解 */
    const val SOFTWARE = "software"

    /** 兜底：偏好里出现未知值时按硬解优先处理（不静默降级成软解，避免耗电） */
    fun normalize(mode: String?): String = if (mode == SOFTWARE) SOFTWARE else HARDWARE

    /**
     * ExoPlayer 的扩展渲染器模式：
     * - 硬解优先 = `EXTENSION_RENDERER_MODE_ON`（MediaCodec 在前，扩展渲染器兜底）；
     * - 仅软解 = `EXTENSION_RENDERER_MODE_PREFER`（扩展渲染器优先，即 FFmpeg 软解）。
     */
    fun extensionRendererMode(mode: String?): Int =
        if (normalize(mode) == SOFTWARE) {
            DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
        } else {
            DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
        }

    /**
     * 硬解失败是否允许自动回退到下一个解码器。
     *
     * 硬解优先时必须开启：解码器初始化失败（`ERROR_CODE_DECODER_INIT_FAILED` 这类）会先在同内核回退， 仍失败才由 ViewModel 静默换 mpv
     * 内核重播，全程不弹错误卡片、不崩溃。
     */
    fun decoderFallbackEnabled(mode: String?): Boolean = true

    /** mpv 的 `hwdec` 参数：硬解优先 = mediacodec，仅软解 = no */
    fun mpvHwDec(mode: String?): String = if (normalize(mode) == SOFTWARE) "no" else "mediacodec"
}
