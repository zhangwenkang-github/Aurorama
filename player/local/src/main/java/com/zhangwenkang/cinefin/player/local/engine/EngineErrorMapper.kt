package com.zhangwenkang.cinefin.player.local.engine

import androidx.media3.common.PlaybackException
import com.zhangwenkang.cinefin.player.core.playback.EngineError
import com.zhangwenkang.cinefin.player.core.playback.EngineErrorKind

/**
 * media3 错误 → 统一 [EngineError] 的映射入口（W2-T4）。
 *
 * 两个内核都经 `Player.Listener.onPlayerError(PlaybackException)` 上报（mpv 也把自身失败包成
 * `PlaybackException`），因此只需要一个入口。
 *
 * 本步只提供映射，不改任何现有错误流转：`ErrorPolicy` / `playerError` 仍按 `PlaybackException` 走，接入归 T5。
 */
internal object EngineErrorMapper {
    /** 把引擎上报的异常映射成统一错误载荷；message 为空时退回错误码名。 */
    fun fromPlaybackException(error: PlaybackException): EngineError =
        EngineError(
            kind = kindForErrorCode(error.errorCode),
            message =
                error.message?.takeIf { message -> message.isNotBlank() } ?: error.errorCodeName,
            cause = error,
        )

    /**
     * 错误码 → 错误种类。
     *
     * 只做粗分类：同一个码在不同内核下可能含义略有差异，细分与分档仍归 `ErrorPolicy`。 未识别的码一律 [EngineErrorKind.UNKNOWN]。
     */
    fun kindForErrorCode(errorCode: Int): EngineErrorKind =
        when (errorCode) {
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
            PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
            PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED,
            PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE -> EngineErrorKind.IO_FAILED

            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ->
                EngineErrorKind.DECODING_FAILED

            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED ->
                EngineErrorKind.SOURCE_START_FAILED

            else -> EngineErrorKind.UNKNOWN
        }
}
