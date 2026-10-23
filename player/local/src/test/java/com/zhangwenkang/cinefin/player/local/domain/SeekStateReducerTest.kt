package com.zhangwenkang.cinefin.player.local.domain

import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SeekStateReducerTest {

    private val identity = SeekMediaIdentity("item-a", "source-a", "uri-hash-a")

    private fun begin(
        reducer: SeekStateReducer,
        targetMs: Long = 60_000L,
        durationMs: Long = 120_000L,
        issuedAtMs: Long = 1_000L,
        mediaIdentity: SeekMediaIdentity = identity,
    ) =
        reducer.beginRequest(
            targetMs = targetMs,
            sourceEpoch = 3L,
            mediaIdentity = mediaIdentity,
            durationMs = durationMs,
            issuedAtMs = issuedAtMs,
            policy =
                SeekConfirmationPolicy(
                    completionTimeoutMs = 4_000L,
                    positionToleranceMs = 1_000L,
                    minimumTargetDisplayMs = 300L,
                ),
        )

    @Test
    fun `request clamps target to duration and exposes seeking target`() {
        val reducer = SeekStateReducer()
        begin(reducer, targetMs = 125_000L)

        val seeking = reducer.state as SeekState.Seeking
        assertEquals(120_000L, seeking.targetMs)
        assertEquals(1L, seeking.requestGeneration)
        assertEquals(3L, seeking.sourceEpoch)
        assertEquals(
            120_000L,
            seekDisplayPositionMs(
                state = reducer.state,
                enginePositionMs = 0L,
                nowElapsedMs = 1_100L,
            ),
        )
        assertTrue(seeking.requestedAtMs == 1_000L)
    }

    @Test
    fun `accepted seek then ready near target confirms`() {
        val reducer = SeekStateReducer()
        begin(reducer)

        reducer.onAccepted(
            SeekEngineEvidence(
                requestGeneration = 1L,
                sourceEpoch = 3L,
                mediaIdentity = identity,
                positionMs = 60_200L,
                kind = SeekEngineEvidenceKind.SEEK_ACCEPTED,
            )
        )
        reducer.onCompletionEvidence(
            SeekEngineEvidence(
                requestGeneration = 1L,
                sourceEpoch = 3L,
                mediaIdentity = identity,
                positionMs = 60_700L,
                kind = SeekEngineEvidenceKind.PLAYBACK_READY,
            ),
            nowElapsedMs = 2_000L,
        )

        val confirmed = reducer.state as SeekState.Confirmed
        assertEquals(60_700L, confirmed.positionMs)
        assertEquals(1L, confirmed.requestGeneration)
    }

    @Test
    fun `synchronous accepted before dispatch survives dispatch and confirms before deadline`() {
        val reducer = SeekStateReducer()
        val policy =
            SeekConfirmationPolicy(
                completionTimeoutMs = 4_000L,
                positionToleranceMs = 1_000L,
                minimumTargetDisplayMs = 300L,
            )
        begin(reducer)
        begin(reducer, targetMs = 180_182L, durationMs = 240_000L, issuedAtMs = 1_001L)

        reducer.onAccepted(
            SeekEngineEvidence(
                requestGeneration = 2L,
                sourceEpoch = 3L,
                mediaIdentity = identity,
                positionMs = 180_182L,
                kind = SeekEngineEvidenceKind.SEEK_ACCEPTED,
            )
        )
        // ExoPlayer emits SEEK_ACCEPTED synchronously from seekTo(), before the dispatch call
        // returns.
        reducer.onDispatched(requestGeneration = 2L, nowElapsedMs = 1_003L)
        reducer.onCompletionEvidence(
            SeekEngineEvidence(
                requestGeneration = 2L,
                sourceEpoch = 3L,
                mediaIdentity = identity,
                positionMs = 180_182L,
                kind = SeekEngineEvidenceKind.PLAYBACK_READY,
            ),
            nowElapsedMs = 1_024L,
        )
        // A late tick must not turn a confirmed seek into a timeout.
        reducer.onTick(nowElapsedMs = 5_003L)

        assertEquals(
            SeekState.Confirmed(
                targetMs = 180_182L,
                positionMs = 180_182L,
                requestGeneration = 2L,
                sourceEpoch = 3L,
                requestedAtMs = 1_003L,
                confirmedAtMs = 1_024L,
                policy = policy,
            ),
            reducer.state,
        )
    }

    @Test
    fun `asynchronous accepted after dispatch confirms near target`() {
        val reducer = SeekStateReducer()
        begin(reducer)

        reducer.onDispatched(requestGeneration = 1L, nowElapsedMs = 1_003L)
        reducer.onAccepted(
            SeekEngineEvidence(
                requestGeneration = 1L,
                sourceEpoch = 3L,
                mediaIdentity = identity,
                positionMs = 60_200L,
                kind = SeekEngineEvidenceKind.SEEK_ACCEPTED,
            )
        )
        reducer.onCompletionEvidence(
            SeekEngineEvidence(
                requestGeneration = 1L,
                sourceEpoch = 3L,
                mediaIdentity = identity,
                positionMs = 60_700L,
                kind = SeekEngineEvidenceKind.PLAYBACK_READY,
            ),
            nowElapsedMs = 2_000L,
        )

        val confirmed = reducer.state as SeekState.Confirmed
        assertEquals(60_700L, confirmed.positionMs)
        assertEquals(1_003L, confirmed.requestedAtMs)
    }

    @Test
    fun `continuous seek keeps only the latest generation`() {
        val reducer = SeekStateReducer()
        begin(reducer, targetMs = 90_000L)
        begin(reducer, targetMs = 20_000L)

        val seeking = reducer.state as SeekState.Seeking
        assertEquals(2L, seeking.requestGeneration)
        assertEquals(20_000L, seeking.targetMs)

        reducer.onAccepted(
            SeekEngineEvidence(
                requestGeneration = 1L,
                sourceEpoch = 3L,
                mediaIdentity = identity,
                positionMs = 20_000L,
                kind = SeekEngineEvidenceKind.SEEK_ACCEPTED,
            )
        )
        assertEquals(null, (reducer.state as SeekState.Seeking).acceptedPositionMs)
    }

    @Test
    fun `engine failure keeps request target and exposes retryability`() {
        val reducer = SeekStateReducer()
        begin(reducer, targetMs = 70_000L)

        reducer.onFailure(
            SeekEngineFailure(
                requestGeneration = 1L,
                sourceEpoch = 3L,
                mediaIdentity = identity,
                reason = SeekFailureReason.ENGINE_ERROR,
                retryable = true,
            ),
            nowElapsedMs = 2_000L,
        )

        val failed = reducer.state as SeekState.Failed
        assertEquals(70_000L, failed.targetMs)
        assertEquals(1L, failed.requestGeneration)
        assertEquals(SeekFailureReason.ENGINE_ERROR, failed.reason)
        assertTrue(failed.retryable)
        assertEquals(
            70_000L,
            seekDisplayPositionMs(failed, enginePositionMs = 0L, nowElapsedMs = 2_100L),
        )
    }

    @Test
    fun `fake clock timeout fails as retryable and retry keeps original target`() {
        val reducer = SeekStateReducer()
        begin(reducer, targetMs = 42_000L, issuedAtMs = 1_000L)

        reducer.onTick(nowElapsedMs = 4_999L)
        assertTrue(reducer.state is SeekState.Seeking)

        reducer.onTick(nowElapsedMs = 5_000L)
        val failed = reducer.state as SeekState.Failed
        assertEquals(SeekFailureReason.TIMEOUT, failed.reason)
        assertTrue(failed.retryable)

        assertEquals(2L, reducer.retryRequest(nowElapsedMs = 5_500L))
        val retrying = reducer.state as SeekState.Seeking
        assertEquals(42_000L, retrying.targetMs)
        assertEquals(2L, retrying.requestGeneration)
    }

    @Test
    fun `source replacement keeps target generation and requires a fresh anchor`() {
        val newIdentity = SeekMediaIdentity("item-a", "source-a", "uri-hash-b")
        val reducer = SeekStateReducer()
        begin(reducer, targetMs = 80_000L)
        reducer.onAccepted(
            SeekEngineEvidence(1L, 3L, identity, 80_000L, SeekEngineEvidenceKind.SEEK_ACCEPTED)
        )

        reducer.onSourceReplaced(sourceEpoch = 4L, mediaIdentity = newIdentity)
        val seeking = reducer.state as SeekState.Seeking
        assertEquals(1L, seeking.requestGeneration)
        assertEquals(80_000L, seeking.targetMs)
        assertEquals(4L, seeking.sourceEpoch)
        assertEquals(null, seeking.acceptedPositionMs)

        reducer.onCompletionEvidence(
            SeekEngineEvidence(1L, 3L, identity, 80_100L, SeekEngineEvidenceKind.PLAYBACK_READY),
            nowElapsedMs = 2_000L,
        )
        assertTrue(reducer.state is SeekState.Seeking)

        reducer.onAccepted(
            SeekEngineEvidence(1L, 4L, newIdentity, 80_000L, SeekEngineEvidenceKind.SEEK_ACCEPTED)
        )
        reducer.onCompletionEvidence(
            SeekEngineEvidence(1L, 4L, newIdentity, 79_900L, SeekEngineEvidenceKind.PLAYBACK_READY),
            nowElapsedMs = 3_000L,
        )
        val confirmed = reducer.state as SeekState.Confirmed
        assertEquals(79_900L, confirmed.positionMs)
        assertEquals(4L, confirmed.sourceEpoch)
    }

    @Test
    fun `display keeps target for minimum window then confirms engine position`() {
        val reducer = SeekStateReducer()
        begin(reducer, targetMs = 60_000L, issuedAtMs = 1_000L)
        reducer.onAccepted(
            SeekEngineEvidence(1L, 3L, identity, 60_100L, SeekEngineEvidenceKind.SEEK_ACCEPTED)
        )
        reducer.onCompletionEvidence(
            SeekEngineEvidence(1L, 3L, identity, 60_300L, SeekEngineEvidenceKind.PLAYBACK_READY),
            nowElapsedMs = 1_100L,
        )

        assertEquals(
            60_000L,
            seekDisplayPositionMs(reducer.state, enginePositionMs = 60_300L, nowElapsedMs = 1_299L),
        )
        assertEquals(
            60_300L,
            seekDisplayPositionMs(reducer.state, enginePositionMs = 60_300L, nowElapsedMs = 1_300L),
        )
    }

    @Test
    fun `queued request resolves target and starts completion timeout when dispatched`() {
        val reducer = SeekStateReducer()
        begin(reducer, targetMs = 0L, issuedAtMs = 1_000L)

        reducer.onTargetResolved(requestGeneration = 1L, targetMs = 45_000L)
        reducer.onDispatched(requestGeneration = 1L, nowElapsedMs = 5_000L)
        reducer.onTick(nowElapsedMs = 8_999L)
        assertTrue(reducer.state is SeekState.Seeking)

        reducer.onTick(nowElapsedMs = 9_000L)
        val failed = reducer.state as SeekState.Failed
        assertEquals(45_000L, failed.targetMs)
        assertEquals(SeekFailureReason.TIMEOUT, failed.reason)
    }

    @Test
    fun `late ready after deadline fails instead of confirming`() {
        val reducer = SeekStateReducer()
        begin(reducer, targetMs = 60_000L, issuedAtMs = 1_000L)
        reducer.onAccepted(
            SeekEngineEvidence(1L, 3L, identity, 60_100L, SeekEngineEvidenceKind.SEEK_ACCEPTED)
        )

        reducer.onCompletionEvidence(
            SeekEngineEvidence(1L, 3L, identity, 60_100L, SeekEngineEvidenceKind.PLAYBACK_READY),
            nowElapsedMs = 5_000L,
        )

        val failed = reducer.state as SeekState.Failed
        assertEquals(SeekFailureReason.TIMEOUT, failed.reason)
    }

    @Test
    fun `a new request replaces a confirmed request with the next generation`() {
        val reducer = SeekStateReducer()
        begin(reducer, targetMs = 60_000L)
        reducer.onAccepted(
            SeekEngineEvidence(1L, 3L, identity, 60_000L, SeekEngineEvidenceKind.SEEK_ACCEPTED)
        )
        reducer.onCompletionEvidence(
            SeekEngineEvidence(1L, 3L, identity, 60_100L, SeekEngineEvidenceKind.PLAYBACK_READY),
            nowElapsedMs = 2_000L,
        )
        assertTrue(reducer.state is SeekState.Confirmed)

        begin(reducer, targetMs = 20_000L)

        assertTrue(reducer.state is SeekState.Seeking)
        assertEquals(
            20_000L,
            seekDisplayPositionMs(reducer.state, enginePositionMs = 60_100L, nowElapsedMs = 3_000L),
        )
    }

    @Test
    fun `retry and next seek receive three continuous generations from reducer`() {
        val reducer = SeekStateReducer()
        val firstGeneration = begin(reducer, targetMs = 30_000L)
        reducer.onFailure(
            SeekEngineFailure(
                requestGeneration = firstGeneration,
                sourceEpoch = 3L,
                mediaIdentity = identity,
                reason = SeekFailureReason.ENGINE_ERROR,
                retryable = true,
            ),
            nowElapsedMs = 2_000L,
        )

        val retryGeneration = reducer.retryRequest(nowElapsedMs = 3_000L)!!
        assertEquals(2L, retryGeneration)
        assertEquals(30_000L, (reducer.state as SeekState.Seeking).targetMs)

        val nextGeneration = begin(reducer, targetMs = 75_000L, issuedAtMs = 4_000L)
        assertEquals(3L, nextGeneration)

        reducer.onAccepted(
            SeekEngineEvidence(
                requestGeneration = retryGeneration,
                sourceEpoch = 3L,
                mediaIdentity = identity,
                positionMs = 30_000L,
                kind = SeekEngineEvidenceKind.SEEK_ACCEPTED,
            )
        )
        val seeking = reducer.state as SeekState.Seeking
        assertEquals(3L, seeking.requestGeneration)
        assertEquals(75_000L, seeking.targetMs)
        assertEquals(null, seeking.acceptedPositionMs)

        reducer.onAccepted(
            SeekEngineEvidence(
                requestGeneration = nextGeneration,
                sourceEpoch = 3L,
                mediaIdentity = identity,
                positionMs = 75_100L,
                kind = SeekEngineEvidenceKind.SEEK_ACCEPTED,
            )
        )
        reducer.onCompletionEvidence(
            SeekEngineEvidence(
                requestGeneration = nextGeneration,
                sourceEpoch = 3L,
                mediaIdentity = identity,
                positionMs = 75_200L,
                kind = SeekEngineEvidenceKind.PLAYBACK_READY,
            ),
            nowElapsedMs = 4_500L,
        )
        assertEquals(3L, (reducer.state as SeekState.Confirmed).requestGeneration)
    }

    @Test
    fun `queued timeout ignores stale resolution and retry keeps original target`() {
        val reducer = SeekStateReducer()
        val generation =
            begin(
                reducer,
                targetMs = 42_000L,
                durationMs = -1L,
                issuedAtMs = 1_000L,
            )

        reducer.onTick(nowElapsedMs = 5_000L)
        val failed = reducer.state as SeekState.Failed
        assertEquals(SeekFailureReason.TIMEOUT, failed.reason)
        assertTrue(failed.retryable)

        reducer.onTargetResolved(
            requestGeneration = generation,
            targetMs = 99_000L,
            durationMs = 120_000L,
        )
        val stillFailed = reducer.state as SeekState.Failed
        assertEquals(42_000L, stillFailed.targetMs)
        assertEquals(
            null,
            seekDispatchTargetMs(
                state = reducer.state,
                requestGeneration = generation,
                durationMs = 120_000L,
            ),
        )

        val retryGeneration = reducer.retryRequest(nowElapsedMs = 6_000L)!!
        val retrying = reducer.state as SeekState.Seeking
        assertEquals(2L, retryGeneration)
        assertEquals(42_000L, retrying.targetMs)
    }

    @Test
    fun `dispatch uses the duration-clamped target resolved by reducer`() {
        val reducer = SeekStateReducer()
        val generation =
            begin(
                reducer,
                targetMs = 0L,
                durationMs = -1L,
                issuedAtMs = 1_000L,
            )

        reducer.onTargetResolved(
            requestGeneration = generation,
            targetMs = 150_000L,
            durationMs = 120_000L,
        )

        assertEquals(
            120_000L,
            seekDispatchTargetMs(
                state = reducer.state,
                requestGeneration = generation,
                durationMs = 120_000L,
            ),
        )
    }

    @Test
    fun `queue media item seek keeps accepted across source transition and confirms`() {
        val oldIdentity = SeekMediaIdentity("episode-06", "source-06", "uri-hash-06")
        val newIdentity = SeekMediaIdentity("episode-07", "source-07", "uri-hash-07")
        val policy =
            SeekConfirmationPolicy(
                completionTimeoutMs = 35_000L,
                positionToleranceMs = 1_000L,
                minimumTargetDisplayMs = 300L,
            )
        val reducer = SeekStateReducer()
        repeat(7) {
            reducer.beginRequest(
                targetMs = 60_000L,
                sourceEpoch = 8L,
                mediaIdentity = oldIdentity,
                durationMs = 1_482_000L,
                issuedAtMs = 900L,
                policy = policy,
            )
        }
        val generation =
            reducer.beginRequest(
                targetMs = 0L,
                sourceEpoch = 8L,
                mediaIdentity = oldIdentity,
                durationMs = 1_482_000L,
                issuedAtMs = 1_000L,
                policy = policy,
            )
        assertEquals(8L, generation)
        reducer.onDispatched(requestGeneration = generation, nowElapsedMs = 1_003L)

        // Media3 reports the new item's SEEK discontinuity before MEDIA_ITEM_TRANSITION. At
        // that boundary the media item is already E07 while the app's source epoch is still 8.
        reducer.onAccepted(
            ExoSeekAdapter.onSeekDiscontinuity(
                requestGeneration = generation,
                sourceEpoch = 8L,
                mediaIdentity = newIdentity,
                newPositionMs = 0L,
            )
        )
        reducer.onSourceReplaced(sourceEpoch = 9L, mediaIdentity = newIdentity)
        val transitionedSeeking = reducer.state as SeekState.Seeking
        assertEquals(9L, transitionedSeeking.sourceEpoch)
        assertEquals(newIdentity, transitionedSeeking.mediaIdentity)
        assertEquals(0L, transitionedSeeking.acceptedPositionMs)

        reducer.onCompletionEvidence(
            requireNotNull(
                ExoSeekAdapter.onPlaybackStateChanged(
                    requestGeneration = generation,
                    sourceEpoch = 9L,
                    mediaIdentity = newIdentity,
                    playbackState = Player.STATE_READY,
                    positionMs = 40L,
                )
            ),
            nowElapsedMs = 2_431L,
        )

        reducer.onTick(nowElapsedMs = 36_003L)

        assertEquals(
            SeekState.Confirmed(
                targetMs = 0L,
                positionMs = 40L,
                requestGeneration = 8L,
                sourceEpoch = 9L,
                requestedAtMs = 1_003L,
                confirmedAtMs = 2_431L,
                policy = policy,
            ),
            reducer.state,
        )
    }
}
