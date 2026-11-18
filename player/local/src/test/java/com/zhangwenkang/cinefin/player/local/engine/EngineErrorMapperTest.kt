package com.zhangwenkang.cinefin.player.local.engine

import androidx.media3.common.PlaybackException
import com.zhangwenkang.cinefin.player.core.playback.EngineErrorKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [EngineErrorMapper] 的映射入口单测（W2-T4）。
 *
 * 只钉住「错误码 → 种类」与载荷透传，不改任何既有错误流转。
 */
class EngineErrorMapperTest {

    @Test
    fun `IO 类错误码映射为 IO_FAILED`() {
        val ioCodes =
            listOf(
                PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
                PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
                PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
                PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
                PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED,
                PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
            )

        for (code in ioCodes) {
            assertEquals(EngineErrorKind.IO_FAILED, EngineErrorMapper.kindForErrorCode(code))
        }
    }

    @Test
    fun `解码类错误码映射为 DECODING_FAILED`() {
        val decodingCodes =
            listOf(
                PlaybackException.ERROR_CODE_DECODING_FAILED,
                PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
                PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
                PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            )

        for (code in decodingCodes) {
            assertEquals(EngineErrorKind.DECODING_FAILED, EngineErrorMapper.kindForErrorCode(code))
        }
    }

    @Test
    fun `解析类错误码映射为 SOURCE_START_FAILED`() {
        val parsingCodes =
            listOf(
                PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
                PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
                PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
                PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
            )

        for (code in parsingCodes) {
            assertEquals(
                EngineErrorKind.SOURCE_START_FAILED,
                EngineErrorMapper.kindForErrorCode(code),
            )
        }
    }

    @Test
    fun `未知错误码映射为 UNKNOWN`() {
        assertEquals(
            EngineErrorKind.UNKNOWN,
            EngineErrorMapper.kindForErrorCode(PlaybackException.ERROR_CODE_UNSPECIFIED),
        )
        assertEquals(EngineErrorKind.UNKNOWN, EngineErrorMapper.kindForErrorCode(Int.MIN_VALUE))
    }

    @Test
    fun `异常映射保留种类 说明与原始异常`() {
        val cause = IllegalStateException("底层失败")
        val error =
            PlaybackException(
                "网络连接失败",
                cause,
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            )

        val mapped = EngineErrorMapper.fromPlaybackException(error)

        assertEquals(EngineErrorKind.IO_FAILED, mapped.kind)
        assertEquals("网络连接失败", mapped.message)
        assertSame(error, mapped.cause)
    }

    @Test
    fun `说明为空时退回错误码名`() {
        val error =
            PlaybackException(null, null, PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED)

        val mapped = EngineErrorMapper.fromPlaybackException(error)

        assertEquals(EngineErrorKind.DECODING_FAILED, mapped.kind)
        assertTrue(mapped.message.contains("DECODING_FORMAT_UNSUPPORTED"))
    }
}
