package com.zhangwenkang.cinefin.player.local.domain

import androidx.media3.exoplayer.DefaultRenderersFactory
import com.zhangwenkang.cinefin.settings.domain.PlayerDecodeFallback
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

    @Test
    fun localSoftwareFallbackStage_forcesSoftwareWithoutTouchingPreference() {
        // W16 回退链第 3 档：偏好仍是「硬解优先」，但本会话实际按软解建实例
        val effective =
            PlayerDecodeMode.effectiveMode(
                PlayerDecodeMode.HARDWARE,
                PlayerDecodeFallback.STAGE_LOCAL_SOFTWARE,
            )
        assertEquals(PlayerDecodeMode.SOFTWARE, effective)
        assertEquals(
            DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER,
            PlayerDecodeMode.extensionRendererMode(effective),
        )
        assertEquals("no", PlayerDecodeMode.mpvHwDec(effective))
    }

    @Test
    fun serverTranscodeStage_keepsHardwareDecoding() {
        // 第 2 档只是把流换成服务器转码的 h264，本地仍优先硬解
        assertEquals(
            PlayerDecodeMode.HARDWARE,
            PlayerDecodeMode.effectiveMode(
                PlayerDecodeMode.HARDWARE,
                PlayerDecodeFallback.STAGE_SERVER_TRANSCODE,
            ),
        )
    }

    @Test
    fun decodeStageLabel_followsKernelAndFallbackStage() {
        // W18：面板「当前档位」文案必须带内核，按实际生效的内核 + 解码方式映射
        assertEquals(
            PlayerDecodeMode.DecodeStage.EXO_HARDWARE,
            PlayerDecodeMode.decodeStage(
                PlayerDecodeFallback.BACKEND_EXOPLAYER,
                PlayerDecodeMode.HARDWARE,
                PlayerDecodeFallback.STAGE_NONE,
            ),
        )
        assertEquals(
            PlayerDecodeMode.DecodeStage.MPV_HARDWARE,
            PlayerDecodeMode.decodeStage(
                PlayerDecodeFallback.BACKEND_MPV,
                PlayerDecodeMode.HARDWARE,
                PlayerDecodeFallback.STAGE_NONE,
            ),
        )
        assertEquals(
            PlayerDecodeMode.DecodeStage.SERVER_TRANSCODE,
            PlayerDecodeMode.decodeStage(
                PlayerDecodeFallback.BACKEND_MPV,
                PlayerDecodeMode.HARDWARE,
                PlayerDecodeFallback.STAGE_SERVER_TRANSCODE,
            ),
        )
        assertEquals(
            PlayerDecodeMode.DecodeStage.MPV_SOFTWARE,
            PlayerDecodeMode.decodeStage(
                PlayerDecodeFallback.BACKEND_MPV,
                PlayerDecodeMode.HARDWARE,
                PlayerDecodeFallback.STAGE_LOCAL_SOFTWARE,
            ),
        )
        // 「仅软解」策略：两个内核各自映射到软解档
        assertEquals(
            PlayerDecodeMode.DecodeStage.MPV_SOFTWARE,
            PlayerDecodeMode.decodeStage(
                PlayerDecodeFallback.BACKEND_MPV,
                PlayerDecodeMode.SOFTWARE,
                PlayerDecodeFallback.STAGE_NONE,
            ),
        )
        assertEquals(
            PlayerDecodeMode.DecodeStage.EXO_SOFTWARE,
            PlayerDecodeMode.decodeStage(
                PlayerDecodeFallback.BACKEND_EXOPLAYER,
                PlayerDecodeMode.SOFTWARE,
                PlayerDecodeFallback.STAGE_NONE,
            ),
        )
    }
}
