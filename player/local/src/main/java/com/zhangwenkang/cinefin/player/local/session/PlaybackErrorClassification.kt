package com.zhangwenkang.cinefin.player.local.session

import androidx.media3.common.PlaybackException

/**
 * 播放错误码分类（VIDEO-04 / L5 回退链口径）。
 *
 * 从 `PlayerViewModel` 提出成纯函数：回退链是否接管只由错误码决定，单测直接喂 [PlaybackException.ERROR_CODE_*] 钉住口径，避免判定被悄悄改宽。
 */
internal object PlaybackErrorClassification {

    /** 只有「内核解不了这个格式」类错误才值得换内核；网络、DRM、容器损坏等换内核也没用。 */
    fun isCodecCapabilityError(errorCode: Int): Boolean =
        when (errorCode) {
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> true
            else -> false
        }

    /** 网络 / IO / 未分类：走原地重试，换内核救不了。 */
    fun isNetworkOrSourceError(errorCode: Int): Boolean =
        when (errorCode) {
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
            PlaybackException.ERROR_CODE_UNSPECIFIED -> true
            else -> false
        }
}
