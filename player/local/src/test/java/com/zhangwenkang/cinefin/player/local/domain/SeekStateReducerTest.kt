package com.zhangwenkang.cinefin.player.local.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SeekStateReducerTest {

    private val identity = SeekMediaIdentity("item-a", "source-a", "uri-hash-a")

    private fun request(
        generation: Long,
        targetMs: Long = 60_000L,
        issuedAtMs: Long = 1_000L,
    ) =
        SeekRequest(
            targetMs = targetMs,
            requestGeneration = generation,
            sourceEpoch = 3L,
            mediaIdentity = identity,
            durationMs = 120_000L,
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
        reducer.request(request(generation = 1L, targetMs = 125_000L))

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
        reducer.request(request(generation = 1L, targetMs = 60_000L))

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
    fun `continuous seek keeps only the latest generation`() {
        val reducer = SeekStateReducer()
        reducer.request(request(generation = 2L, targetMs = 90_000L))
        reducer.request(request(generation = 1L, targetMs = 20_000L))

        val seeking = reducer.state as SeekState.Seeking
        assertEquals(2L, seeking.requestGeneration)
        assertEquals(90_000L, seeking.targetMs)

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
        reducer.request(request(generation = 5L, targetMs = 70_000L))

        reducer.onFailure(
            SeekEngineFailure(
                requestGeneration = 5L,
                sourceEpoch = 3L,
                mediaIdentity = identity,
                reason = SeekFailureReason.ENGINE_ERROR,
                retryable = true,
            ),
            nowElapsedMs = 2_000L,
        )

        val failed = reducer.state as SeekState.Failed
        assertEquals(70_000L, failed.targetMs)
        assertEquals(5L, failed.requestGeneration)
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
        reducer.request(request(generation = 6L, targetMs = 42_000L, issuedAtMs = 1_000L))

        reducer.onTick(nowElapsedMs = 4_999L)
        assertTrue(reducer.state is SeekState.Seeking)

        reducer.onTick(nowElapsedMs = 5_000L)
        val failed = reducer.state as SeekState.Failed
        assertEquals(SeekFailureReason.TIMEOUT, failed.reason)
        assertTrue(failed.retryable)

        val retry = reducer.retryRequest(nowElapsedMs = 5_500L)!!
        assertEquals(42_000L, retry.targetMs)
        assertEquals(7L, retry.requestGeneration)
    }

    @Test
    fun `source replacement keeps target generation and requires a fresh anchor`() {
        val newIdentity = SeekMediaIdentity("item-a", "source-a", "uri-hash-b")
        val reducer = SeekStateReducer()
        reducer.request(request(generation = 8L, targetMs = 80_000L))
        reducer.onAccepted(
            SeekEngineEvidence(8L, 3L, identity, 80_000L, SeekEngineEvidenceKind.SEEK_ACCEPTED)
        )

        reducer.onSourceReplaced(sourceEpoch = 4L, mediaIdentity = newIdentity)
        val seeking = reducer.state as SeekState.Seeking
        assertEquals(8L, seeking.requestGeneration)
        assertEquals(80_000L, seeking.targetMs)
        assertEquals(4L, seeking.sourceEpoch)
        assertEquals(null, seeking.acceptedPositionMs)

        reducer.onCompletionEvidence(
            SeekEngineEvidence(8L, 3L, identity, 80_100L, SeekEngineEvidenceKind.PLAYBACK_READY),
            nowElapsedMs = 2_000L,
        )
        assertTrue(reducer.state is SeekState.Seeking)

        reducer.onAccepted(
            SeekEngineEvidence(8L, 4L, newIdentity, 80_000L, SeekEngineEvidenceKind.SEEK_ACCEPTED)
        )
        reducer.onCompletionEvidence(
            SeekEngineEvidence(8L, 4L, newIdentity, 79_900L, SeekEngineEvidenceKind.PLAYBACK_READY),
            nowElapsedMs = 3_000L,
        )
        val confirmed = reducer.state as SeekState.Confirmed
        assertEquals(79_900L, confirmed.positionMs)
        assertEquals(4L, confirmed.sourceEpoch)
    }

    @Test
    fun `display keeps target for minimum window then confirms engine position`() {
        val reducer = SeekStateReducer()
        reducer.request(request(generation = 9L, targetMs = 60_000L, issuedAtMs = 1_000L))
        reducer.onAccepted(
            SeekEngineEvidence(9L, 3L, identity, 60_100L, SeekEngineEvidenceKind.SEEK_ACCEPTED)
        )
        reducer.onCompletionEvidence(
            SeekEngineEvidence(9L, 3L, identity, 60_300L, SeekEngineEvidenceKind.PLAYBACK_READY),
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
        reducer.request(request(generation = 10L, targetMs = 0L, issuedAtMs = 1_000L))

        reducer.onTargetResolved(requestGeneration = 10L, targetMs = 45_000L)
        reducer.onDispatched(requestGeneration = 10L, nowElapsedMs = 5_000L)
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
        reducer.request(request(generation = 11L, targetMs = 60_000L, issuedAtMs = 1_000L))
        reducer.onAccepted(
            SeekEngineEvidence(11L, 3L, identity, 60_100L, SeekEngineEvidenceKind.SEEK_ACCEPTED)
        )

        reducer.onCompletionEvidence(
            SeekEngineEvidence(11L, 3L, identity, 60_100L, SeekEngineEvidenceKind.PLAYBACK_READY),
            nowElapsedMs = 5_000L,
        )

        val failed = reducer.state as SeekState.Failed
        assertEquals(SeekFailureReason.TIMEOUT, failed.reason)
    }

    @Test
    fun `stale generation cannot replace an already confirmed request`() {
        val reducer = SeekStateReducer()
        reducer.request(request(generation = 5L, targetMs = 60_000L))
        reducer.onAccepted(
            SeekEngineEvidence(5L, 3L, identity, 60_000L, SeekEngineEvidenceKind.SEEK_ACCEPTED)
        )
        reducer.onCompletionEvidence(
            SeekEngineEvidence(5L, 3L, identity, 60_100L, SeekEngineEvidenceKind.PLAYBACK_READY),
            nowElapsedMs = 2_000L,
        )

        reducer.request(request(generation = 4L, targetMs = 20_000L))

        assertTrue(reducer.state is SeekState.Confirmed)
        assertEquals(
            60_100L,
            seekDisplayPositionMs(reducer.state, enginePositionMs = 60_100L, nowElapsedMs = 3_000L),
        )
    }
}
