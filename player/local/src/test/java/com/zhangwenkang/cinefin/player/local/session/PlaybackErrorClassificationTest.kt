package com.zhangwenkang.cinefin.player.local.session

import androidx.media3.common.PlaybackException
import com.zhangwenkang.cinefin.settings.domain.PlayerDecodeFallback
import com.zhangwenkang.cinefin.settings.domain.PlayerStreamingQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * L5（W4-T7 验收）：自动回退链只在**编解码能力错误**上接管。
 *
 * 这组断言直接喂 media3 的 [PlaybackException]（fake 错误码）到回退链判定，钉住「哪些码会进
 * [PlaybackErrorClassification.isCodecCapabilityError]」的现状。
 */
class PlaybackErrorClassificationTest {

    private fun codecLadder(error: PlaybackException) =
        PlayerDecodeFallback.stageAfterFailure(
            stage = PlayerDecodeFallback.STAGE_NONE,
            backend = PlayerDecodeFallback.BACKEND_EXOPLAYER,
            bitratePreference = PlayerStreamingQuality.AUTO,
            codecCapabilityError =
                PlaybackErrorClassification.isCodecCapabilityError(error.errorCode),
            networkError = PlaybackErrorClassification.isNetworkOrSourceError(error.errorCode),
        )

    @Test
    fun `FORMAT_UNSUPPORTED 进入自动回退链`() {
        val error =
            PlaybackException(
                "fake codec failure",
                null,
                PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            )
        assertTrue(PlaybackErrorClassification.isCodecCapabilityError(error.errorCode))
        assertFalse(PlaybackErrorClassification.isNetworkOrSourceError(error.errorCode))
        // 第 1 档（本地硬解）失败 → 按链路降到服务器解码 / 转码
        assertEquals(PlayerDecodeFallback.STAGE_SERVER_TRANSCODE, codecLadder(error))
    }

    @Test
    fun `四条编解码能力错误码全部进回退链`() {
        val codes =
            listOf(
                PlaybackException.ERROR_CODE_DECODING_FAILED,
                PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
                PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
                PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            )
        codes.forEach { code ->
            val error = PlaybackException("fake $code", null, code)
            assertTrue(
                "errorCode=$code",
                PlaybackErrorClassification.isCodecCapabilityError(error.errorCode),
            )
            assertEquals(
                "errorCode=$code",
                PlayerDecodeFallback.STAGE_SERVER_TRANSCODE,
                codecLadder(error),
            )
        }
    }

    @Test
    fun `IO_FILE_NOT_FOUND 不进自动回退`() {
        val error =
            PlaybackException(
                "fake missing file",
                null,
                PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
            )
        assertFalse(PlaybackErrorClassification.isCodecCapabilityError(error.errorCode))
        assertTrue(PlaybackErrorClassification.isNetworkOrSourceError(error.errorCode))
        // 网络 / IO 错误换内核救不了：原地重试，不推进回退档位
        assertNull(codecLadder(error))
    }
}
