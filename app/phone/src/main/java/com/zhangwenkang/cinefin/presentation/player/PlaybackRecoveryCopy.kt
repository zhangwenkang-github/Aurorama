package com.zhangwenkang.cinefin.presentation.player

import com.zhangwenkang.cinefin.player.local.R as PlayerR
import com.zhangwenkang.cinefin.player.local.presentation.PlayerViewModel

internal enum class PlaybackRecoveryTitle {
    Stalled,
    ProgressUnconfirmed,
    Generic,
}

internal enum class PlaybackRecoveryDetail {
    RetryFromStart,
    RetryFromPosition,
}

internal data class PlaybackRecoveryCopy(
    val title: PlaybackRecoveryTitle,
    val detail: PlaybackRecoveryDetail,
    val positionMs: Long?,
)

internal data class PlaybackRecoveryPresentation(
    val forceControlsVisible: Boolean,
    val canAutoHideControls: Boolean,
    val showProgressRecoveryAction: Boolean,
)

internal fun playbackRecoveryPresentation(
    showPlayerError: Boolean,
    seekFailed: Boolean,
    progressRecovery: Boolean,
): PlaybackRecoveryPresentation {
    val controlsBlocked = showPlayerError || seekFailed
    return PlaybackRecoveryPresentation(
        forceControlsVisible = controlsBlocked,
        canAutoHideControls = !controlsBlocked,
        showProgressRecoveryAction = false,
    )
}

internal fun resolvePlaybackRecoveryTitle(kind: String): PlaybackRecoveryTitle =
    when (kind) {
        "PLAYBACK_PROGRESS_TIMEOUT" -> PlaybackRecoveryTitle.Stalled
        "PROGRESS_UNCONFIRMED" -> PlaybackRecoveryTitle.ProgressUnconfirmed
        else -> PlaybackRecoveryTitle.Generic
    }

internal fun PlaybackRecoveryTitle.stringRes(): Int =
    when (this) {
        PlaybackRecoveryTitle.Stalled -> PlayerR.string.player_progress_recovery_stalled
        PlaybackRecoveryTitle.ProgressUnconfirmed ->
            PlayerR.string.player_progress_recovery_unconfirmed
        PlaybackRecoveryTitle.Generic -> PlayerR.string.player_progress_recovery_generic
    }

internal fun PlaybackRecoveryDetail.stringRes(): Int =
    when (this) {
        PlaybackRecoveryDetail.RetryFromStart ->
            PlayerR.string.player_progress_recovery_retry_from_start
        PlaybackRecoveryDetail.RetryFromPosition ->
            PlayerR.string.player_progress_recovery_retry_from_position
    }

internal fun resolvePlaybackRecoveryCopy(
    kind: String,
    positionMs: Long,
    positionSource: PlayerViewModel.PlaybackRetryPositionSource,
): PlaybackRecoveryCopy {
    val title = resolvePlaybackRecoveryTitle(kind)
    val restartFromBeginning =
        positionSource == PlayerViewModel.PlaybackRetryPositionSource.START_ZERO || positionMs <= 0L
    return PlaybackRecoveryCopy(
        title = title,
        detail =
            if (restartFromBeginning) {
                PlaybackRecoveryDetail.RetryFromStart
            } else {
                PlaybackRecoveryDetail.RetryFromPosition
            },
        positionMs = positionMs.takeUnless { restartFromBeginning },
    )
}
