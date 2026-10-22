package com.zhangwenkang.cinefin.player.local.domain

import androidx.media3.common.Player

object ExoSeekAdapter {
    fun onSeekDiscontinuity(
        requestGeneration: Long,
        sourceEpoch: Long,
        mediaIdentity: SeekMediaIdentity,
        newPositionMs: Long,
    ): SeekEngineEvidence =
        SeekEngineEvidence(
            requestGeneration = requestGeneration,
            sourceEpoch = sourceEpoch,
            mediaIdentity = mediaIdentity,
            positionMs = newPositionMs,
            kind = SeekEngineEvidenceKind.SEEK_ACCEPTED,
        )

    fun onPlaybackStateChanged(
        requestGeneration: Long,
        sourceEpoch: Long,
        mediaIdentity: SeekMediaIdentity,
        playbackState: Int,
        positionMs: Long,
    ): SeekEngineEvidence? {
        if (playbackState != Player.STATE_READY) return null
        return SeekEngineEvidence(
            requestGeneration = requestGeneration,
            sourceEpoch = sourceEpoch,
            mediaIdentity = mediaIdentity,
            positionMs = positionMs,
            kind = SeekEngineEvidenceKind.PLAYBACK_READY,
        )
    }

    fun onPlayerError(
        requestGeneration: Long,
        sourceEpoch: Long,
        mediaIdentity: SeekMediaIdentity,
        retryable: Boolean,
    ): SeekEngineFailure =
        SeekEngineFailure(
            requestGeneration = requestGeneration,
            sourceEpoch = sourceEpoch,
            mediaIdentity = mediaIdentity,
            reason = SeekFailureReason.ENGINE_ERROR,
            retryable = retryable,
        )
}

object MpvSeekAdapter {
    fun onSeekAccepted(
        requestGeneration: Long,
        sourceEpoch: Long,
        mediaIdentity: SeekMediaIdentity,
    ): SeekEngineEvidence =
        SeekEngineEvidence(
            requestGeneration = requestGeneration,
            sourceEpoch = sourceEpoch,
            mediaIdentity = mediaIdentity,
            positionMs = 0L,
            kind = SeekEngineEvidenceKind.SEEK_ACCEPTED,
        )

    fun onPlaybackRestart(
        requestGeneration: Long,
        sourceEpoch: Long,
        mediaIdentity: SeekMediaIdentity,
        positionMs: Long,
    ): SeekEngineEvidence =
        SeekEngineEvidence(
            requestGeneration = requestGeneration,
            sourceEpoch = sourceEpoch,
            mediaIdentity = mediaIdentity,
            positionMs = positionMs,
            kind = SeekEngineEvidenceKind.PLAYBACK_READY,
        )

    fun onEndFile(
        requestGeneration: Long,
        sourceEpoch: Long,
        mediaIdentity: SeekMediaIdentity,
        eofReached: Boolean,
    ): SeekEngineFailure? {
        if (eofReached) return null
        return SeekEngineFailure(
            requestGeneration = requestGeneration,
            sourceEpoch = sourceEpoch,
            mediaIdentity = mediaIdentity,
            reason = SeekFailureReason.ENGINE_ERROR,
            retryable = true,
        )
    }
}
