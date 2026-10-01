package com.zhangwenkang.cinefin.player.local.domain

import androidx.media3.exoplayer.DefaultRenderersFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 解码策略映射的回归测试（W12 反馈 B）。
 *
 * 硬解优先必须满足两件事：① 扩展渲染器作为兜底（不是关掉）；② 解码器自动回退开启—— 这是「ExoPlayer 硬解报错要自动回退且不崩溃」的第一道防线（第二道是 ViewModel 静默换
 * mpv）。
 */
class PlayerDecodeModeTest {

    @Test
    fun hardware_prefersMediaCodecWithExtensionFallback() {
        assertEquals(
            DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON,
            PlayerDecodeMode.extensionRendererMode(PlayerDecodeMode.HARDWARE),
        )
        assertTrue(
            "硬解失败必须允许解码器自动回退",
            PlayerDecodeMode.decoderFallbackEnabled(PlayerDecodeMode.HARDWARE),
        )
        assertEquals("mediacodec", PlayerDecodeMode.mpvHwDec(PlayerDecodeMode.HARDWARE))
    }

    @Test
    fun software_prefersExtensionRendererAndTurnsMpvHwDecOff() {
        assertEquals(
            DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER,
            PlayerDecodeMode.extensionRendererMode(PlayerDecodeMode.SOFTWARE),
        )
        assertEquals("no", PlayerDecodeMode.mpvHwDec(PlayerDecodeMode.SOFTWARE))
    }

    @Test
    fun unknownValue_fallsBackToHardwareInsteadOfSoftware() {
        // 未知值不能静默变成软解：软解最耗电，必须由用户显式选择
        assertEquals(PlayerDecodeMode.HARDWARE, PlayerDecodeMode.normalize(null))
        assertEquals(PlayerDecodeMode.HARDWARE, PlayerDecodeMode.normalize(""))
        assertEquals(PlayerDecodeMode.HARDWARE, PlayerDecodeMode.normalize("hwdec"))
        assertEquals(
            DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON,
            PlayerDecodeMode.extensionRendererMode("未知"),
        )
    }
}
