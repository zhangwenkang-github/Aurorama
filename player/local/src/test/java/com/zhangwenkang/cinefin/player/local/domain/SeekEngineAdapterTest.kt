package com.zhangwenkang.cinefin.player.local.domain

import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SeekEngineAdapterTest {

    private val identity = SeekMediaIdentity("item-a", "source-a", "uri-hash-a")

    @Test
    fun `exo seek discontinuity is the acceptance anchor with its new position`() {
        val evidence =
            ExoSeekAdapter.onSeekDiscontinuity(
                requestGeneration = 4L,
                sourceEpoch = 7L,
                mediaIdentity = identity,
                newPositionMs = 61_250L,
            )

        assertEquals(SeekEngineEvidenceKind.SEEK_ACCEPTED, evidence.kind)
        assertEquals(61_250L, evidence.positionMs)
        assertEquals(4L, evidence.requestGeneration)
        assertEquals(7L, evidence.sourceEpoch)
        assertEquals(identity, evidence.mediaIdentity)
    }

    @Test
    fun `exo buffering is not completion and ready becomes completion evidence`() {
        val buffering =
            ExoSeekAdapter.onPlaybackStateChanged(
                requestGeneration = 4L,
                sourceEpoch = 7L,
                mediaIdentity = identity,
                playbackState = Player.STATE_BUFFERING,
                positionMs = 61_000L,
            )
        assertNull(buffering)

        val ready =
            ExoSeekAdapter.onPlaybackStateChanged(
                requestGeneration = 4L,
                sourceEpoch = 7L,
                mediaIdentity = identity,
                playbackState = Player.STATE_READY,
                positionMs = 61_000L,
            )
        assertEquals(SeekEngineEvidenceKind.PLAYBACK_READY, ready?.kind)
        assertEquals(61_000L, ready?.positionMs)
    }

    @Test
    fun `mpv fresh seek and playback restart map to acceptance then completion`() {
        val accepted =
            MpvSeekAdapter.onSeekAccepted(
                requestGeneration = 9L,
                sourceEpoch = 2L,
                mediaIdentity = identity,
            )
        val restarted =
            MpvSeekAdapter.onPlaybackRestart(
                requestGeneration = 9L,
                sourceEpoch = 2L,
                mediaIdentity = identity,
                positionMs = 60_500L,
            )

        assertEquals(SeekEngineEvidenceKind.SEEK_ACCEPTED, accepted.kind)
        assertEquals(SeekEngineEvidenceKind.PLAYBACK_READY, restarted.kind)
        assertEquals(60_500L, restarted.positionMs)
    }

    @Test
    fun `exo player error maps to engine failure`() {
        val failure =
            ExoSeekAdapter.onPlayerError(
                requestGeneration = 3L,
                sourceEpoch = 1L,
                mediaIdentity = identity,
                retryable = true,
            )

        assertEquals(SeekFailureReason.ENGINE_ERROR, failure.reason)
        assert(failure.retryable)
        assertEquals(3L, failure.requestGeneration)
    }

    @Test
    fun `mpv only non eof end file maps to failure`() {
        val failure =
            MpvSeekAdapter.onEndFile(
                requestGeneration = 5L,
                sourceEpoch = 2L,
                mediaIdentity = identity,
                eofReached = false,
            )
        val normalEnd =
            MpvSeekAdapter.onEndFile(
                requestGeneration = 5L,
                sourceEpoch = 2L,
                mediaIdentity = identity,
                eofReached = true,
            )

        assertEquals(SeekFailureReason.ENGINE_ERROR, failure?.reason)
        assertNull(normalEnd)
    }
}
