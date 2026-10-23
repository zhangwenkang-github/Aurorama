package com.zhangwenkang.cinefin.player.local.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SeekRequestCoordinatorTest {

    private val identity = SeekMediaIdentity("item-a", "source-a", "uri-hash-a")
    private val nextIdentity = SeekMediaIdentity("item-b", "source-b", "uri-hash-b")
    private val policy =
        SeekConfirmationPolicy(
            completionTimeoutMs = 4_000L,
            positionToleranceMs = 1_000L,
            minimumTargetDisplayMs = 300L,
        )

    @Test
    fun `late exo events from superseded dispatch cannot accept or confirm current dispatch`() {
        val coordinator = SeekRequestCoordinator()
        coordinator.observeSource(identity)
        val first =
            coordinator.beginRequest(
                targetMs = 20_000L,
                durationMs = 120_000L,
                issuedAtMs = 1_000L,
                policy = policy,
            )
        coordinator.onDispatched(
            requestGeneration = first,
            engine = SeekEngine.EXOPLAYER,
            expectedMediaIdentity = identity,
            nowElapsedMs = 1_003L,
        )

        val second =
            coordinator.beginRequest(
                targetMs = 70_000L,
                durationMs = 120_000L,
                issuedAtMs = 1_100L,
                policy = policy,
            )
        coordinator.onDispatched(
            requestGeneration = second,
            engine = SeekEngine.EXOPLAYER,
            expectedMediaIdentity = identity,
            nowElapsedMs = 1_103L,
        )

        assertFalse(
            coordinator.onSeekAccepted(
                engine = SeekEngine.EXOPLAYER,
                mediaIdentity = identity,
                positionMs = 20_000L,
            )
        )
        assertFalse(
            coordinator.onPlaybackReady(
                engine = SeekEngine.EXOPLAYER,
                mediaIdentity = identity,
                positionMs = 20_200L,
                nowElapsedMs = 1_200L,
            )
        )
        val stillSeeking = coordinator.state as SeekState.Seeking
        assertEquals(second, stillSeeking.requestGeneration)
        assertEquals(null, stillSeeking.acceptedPositionMs)

        assertTrue(
            coordinator.onSeekAccepted(
                engine = SeekEngine.EXOPLAYER,
                mediaIdentity = identity,
                positionMs = 70_000L,
            )
        )
        assertTrue(
            coordinator.onPlaybackReady(
                engine = SeekEngine.EXOPLAYER,
                mediaIdentity = identity,
                positionMs = 70_100L,
                nowElapsedMs = 1_300L,
            )
        )
        val confirmed = coordinator.state as SeekState.Confirmed
        assertEquals(second, confirmed.requestGeneration)
        assertEquals(70_100L, confirmed.positionMs)
    }

    @Test
    fun `late mpv restart cannot complete a newer dispatch without its fresh seek anchor`() {
        val coordinator = SeekRequestCoordinator()
        coordinator.observeSource(identity)
        val first =
            coordinator.beginRequest(
                targetMs = 40_000L,
                durationMs = 120_000L,
                issuedAtMs = 1_000L,
                policy = policy,
            )
        coordinator.onDispatched(
            requestGeneration = first,
            engine = SeekEngine.MPV,
            expectedMediaIdentity = identity,
            nowElapsedMs = 1_003L,
        )
        coordinator.onSeekAccepted(
            engine = SeekEngine.MPV,
            mediaIdentity = identity,
            positionMs = null,
        )

        val second =
            coordinator.beginRequest(
                targetMs = 80_000L,
                durationMs = 120_000L,
                issuedAtMs = 1_100L,
                policy = policy,
            )
        coordinator.onDispatched(
            requestGeneration = second,
            engine = SeekEngine.MPV,
            expectedMediaIdentity = identity,
            nowElapsedMs = 1_103L,
        )

        assertFalse(
            coordinator.onPlaybackReady(
                engine = SeekEngine.MPV,
                mediaIdentity = identity,
                positionMs = 80_100L,
                nowElapsedMs = 1_200L,
            )
        )
        assertEquals(second, (coordinator.state as SeekState.Seeking).requestGeneration)

        coordinator.onSeekAccepted(
            engine = SeekEngine.MPV,
            mediaIdentity = identity,
            positionMs = null,
        )
        assertTrue(
            coordinator.onPlaybackReady(
                engine = SeekEngine.MPV,
                mediaIdentity = identity,
                positionMs = 80_100L,
                nowElapsedMs = 1_300L,
            )
        )
    }

    @Test
    fun `source cursor advances once across accepted and transition and feeds the next request`() {
        val coordinator = SeekRequestCoordinator()
        coordinator.observeSource(identity)
        val queueRequest =
            coordinator.beginRequest(
                targetMs = 0L,
                durationMs = 120_000L,
                issuedAtMs = 1_000L,
                policy = policy,
            )
        coordinator.onDispatched(
            requestGeneration = queueRequest,
            engine = SeekEngine.EXOPLAYER,
            expectedMediaIdentity = nextIdentity,
            nowElapsedMs = 1_003L,
        )

        coordinator.onSeekAccepted(
            engine = SeekEngine.EXOPLAYER,
            mediaIdentity = nextIdentity,
            positionMs = 0L,
        )
        val pendingTransition = coordinator.state as SeekState.Seeking
        assertEquals(1L, pendingTransition.sourceEpoch)
        assertEquals(identity, pendingTransition.mediaIdentity)
        assertEquals(null, pendingTransition.acceptedPositionMs)

        coordinator.onSourceTransition(nextIdentity)
        val accepted = coordinator.state as SeekState.Seeking
        assertEquals(2L, accepted.sourceEpoch)
        assertEquals(nextIdentity, accepted.mediaIdentity)
        assertEquals(0L, accepted.acceptedPositionMs)

        val followUp =
            coordinator.beginRequest(
                targetMs = 30_000L,
                durationMs = 120_000L,
                issuedAtMs = 1_100L,
                policy = policy,
            )
        val followUpSeeking = coordinator.state as SeekState.Seeking
        assertEquals(followUp, followUpSeeking.requestGeneration)
        assertEquals(2L, followUpSeeking.sourceEpoch)
        assertEquals(nextIdentity, followUpSeeking.mediaIdentity)

        val afterTransition = coordinator.state as SeekState.Seeking
        assertEquals(2L, afterTransition.sourceEpoch)
        assertEquals(nextIdentity, afterTransition.mediaIdentity)
        assertEquals(null, afterTransition.acceptedPositionMs)

        coordinator.onDispatched(
            requestGeneration = followUp,
            engine = SeekEngine.EXOPLAYER,
            expectedMediaIdentity = nextIdentity,
            nowElapsedMs = 1_103L,
        )
        coordinator.onSeekAccepted(
            engine = SeekEngine.EXOPLAYER,
            mediaIdentity = nextIdentity,
            positionMs = 30_000L,
        )
        coordinator.onSourceTransition(nextIdentity)
        assertEquals(
            30_000L,
            (coordinator.state as SeekState.Seeking).acceptedPositionMs,
        )
        coordinator.onPlaybackReady(
            engine = SeekEngine.EXOPLAYER,
            mediaIdentity = nextIdentity,
            positionMs = 30_100L,
            nowElapsedMs = 1_200L,
        )

        val confirmed = coordinator.state as SeekState.Confirmed
        assertEquals(followUp, confirmed.requestGeneration)
        assertEquals(2L, confirmed.sourceEpoch)
    }

    @Test
    fun `media item policy follows target source for direct hls and back to direct`() {
        val directUri = "https://media.example/videos/item?static=true"
        val transcodeUri = "https://media.example/videos/item/master.m3u8?VideoBitrate=3000000"

        val direct =
            seekConfirmationPolicyForTarget(
                engine = SeekEngine.EXOPLAYER,
                mediaSourceUri = directUri,
            )
        val hls =
            seekConfirmationPolicyForTarget(
                engine = SeekEngine.EXOPLAYER,
                mediaSourceUri = transcodeUri,
            )
        val directAgain =
            seekConfirmationPolicyForTarget(
                engine = SeekEngine.EXOPLAYER,
                mediaSourceUri = directUri,
            )

        assertEquals(4_000L, direct.completionTimeoutMs)
        assertEquals(35_000L, hls.completionTimeoutMs)
        assertEquals(1_000L, direct.positionToleranceMs)
        assertEquals(2_000L, hls.positionToleranceMs)
        assertEquals(direct, directAgain)
    }

    @Test
    fun `coordinator selects policy from each queue target uri`() {
        val coordinator = SeekRequestCoordinator()
        coordinator.observeSource(identity)

        coordinator.beginRequestForTarget(
            targetMs = 0L,
            durationMs = 120_000L,
            issuedAtMs = 1_000L,
            engine = SeekEngine.EXOPLAYER,
            targetSourceUri = "https://media.example/item/master.m3u8",
        )
        assertEquals(
            35_000L,
            (coordinator.state as SeekState.Seeking).policy.completionTimeoutMs,
        )

        coordinator.observeSource(nextIdentity)
        coordinator.beginRequestForTarget(
            targetMs = 0L,
            durationMs = 120_000L,
            issuedAtMs = 1_100L,
            engine = SeekEngine.EXOPLAYER,
            targetSourceUri = "https://media.example/item?static=true",
        )
        assertEquals(
            4_000L,
            (coordinator.state as SeekState.Seeking).policy.completionTimeoutMs,
        )
    }

    @Test
    fun `transcode replacement keeps one request across prepared accepted and transition order`() {
        val coordinator = SeekRequestCoordinator()
        coordinator.observeSource(identity)
        val generation =
            coordinator.beginRequest(
                targetMs = 45_000L,
                durationMs = 120_000L,
                issuedAtMs = 1_000L,
                policy =
                    SeekConfirmationPolicy(
                        completionTimeoutMs = 35_000L,
                        positionToleranceMs = 2_000L,
                        minimumTargetDisplayMs = 300L,
                    ),
            )
        coordinator.onDispatched(
            requestGeneration = generation,
            engine = SeekEngine.EXOPLAYER,
            expectedMediaIdentity = nextIdentity,
            nowElapsedMs = 1_003L,
        )
        val lease = requireNotNull(coordinator.captureRequestLease())

        coordinator.prepareSourceReplacement(lease, nextIdentity)
        val replaced = coordinator.state as SeekState.Seeking
        assertEquals(2L, replaced.sourceEpoch)
        assertEquals(nextIdentity, replaced.mediaIdentity)
        assertEquals(null, replaced.acceptedPositionMs)

        coordinator.onSeekAccepted(
            engine = SeekEngine.EXOPLAYER,
            mediaIdentity = nextIdentity,
            positionMs = 45_000L,
        )
        coordinator.onSourceTransition(nextIdentity)
        val transitioned = coordinator.state as SeekState.Seeking
        assertEquals(2L, transitioned.sourceEpoch)
        assertEquals(45_000L, transitioned.acceptedPositionMs)

        coordinator.onPlaybackReady(
            engine = SeekEngine.EXOPLAYER,
            mediaIdentity = nextIdentity,
            positionMs = 45_100L,
            nowElapsedMs = 2_000L,
        )
        val confirmed = coordinator.state as SeekState.Confirmed
        assertEquals(generation, confirmed.requestGeneration)
        assertEquals(2L, confirmed.sourceEpoch)
    }

    @Test
    fun `correlated engine error fails the current accepted dispatch`() {
        val coordinator = SeekRequestCoordinator()
        coordinator.observeSource(identity)
        val generation =
            coordinator.beginRequest(
                targetMs = 30_000L,
                durationMs = 120_000L,
                issuedAtMs = 1_000L,
                policy = policy,
            )
        coordinator.onDispatched(
            requestGeneration = generation,
            engine = SeekEngine.EXOPLAYER,
            expectedMediaIdentity = identity,
            nowElapsedMs = 1_003L,
        )
        coordinator.onSeekAccepted(
            engine = SeekEngine.EXOPLAYER,
            mediaIdentity = identity,
            positionMs = 30_000L,
        )

        assertTrue(
            coordinator.onEngineError(
                engine = SeekEngine.EXOPLAYER,
                mediaIdentity = identity,
                retryable = true,
                nowElapsedMs = 1_200L,
            )
        )
        val failed = coordinator.state as SeekState.Failed
        assertEquals(generation, failed.requestGeneration)
        assertEquals(30_000L, failed.targetMs)
        assertEquals(SeekFailureReason.ENGINE_ERROR, failed.reason)
    }

    @Test
    fun `late engine error is consumed by the superseded dispatch and cannot fail current`() {
        val coordinator = SeekRequestCoordinator()
        coordinator.observeSource(identity)
        val first =
            coordinator.beginRequest(
                targetMs = 20_000L,
                durationMs = 120_000L,
                issuedAtMs = 1_000L,
                policy = policy,
            )
        coordinator.onDispatched(
            requestGeneration = first,
            engine = SeekEngine.EXOPLAYER,
            expectedMediaIdentity = identity,
            nowElapsedMs = 1_003L,
        )
        coordinator.onSeekAccepted(
            engine = SeekEngine.EXOPLAYER,
            mediaIdentity = identity,
            positionMs = 20_000L,
        )

        val second =
            coordinator.beginRequest(
                targetMs = 70_000L,
                durationMs = 120_000L,
                issuedAtMs = 1_100L,
                policy = policy,
            )
        coordinator.onDispatched(
            requestGeneration = second,
            engine = SeekEngine.EXOPLAYER,
            expectedMediaIdentity = identity,
            nowElapsedMs = 1_103L,
        )
        coordinator.onSeekAccepted(
            engine = SeekEngine.EXOPLAYER,
            mediaIdentity = identity,
            positionMs = 70_000L,
        )

        assertFalse(
            coordinator.onEngineError(
                engine = SeekEngine.EXOPLAYER,
                mediaIdentity = identity,
                retryable = true,
                nowElapsedMs = 1_200L,
            )
        )
        assertEquals(second, (coordinator.state as SeekState.Seeking).requestGeneration)

        assertTrue(
            coordinator.onPlaybackReady(
                engine = SeekEngine.EXOPLAYER,
                mediaIdentity = identity,
                positionMs = 70_100L,
                nowElapsedMs = 1_300L,
            )
        )
    }

    @Test
    fun `deadline job fails as timeout and late ready cannot confirm`() {
        val coordinator = SeekRequestCoordinator()
        coordinator.observeSource(identity)
        val generation =
            coordinator.beginRequest(
                targetMs = 30_000L,
                durationMs = 120_000L,
                issuedAtMs = 1_000L,
                policy = policy,
            )
        coordinator.onDispatched(
            requestGeneration = generation,
            engine = SeekEngine.EXOPLAYER,
            expectedMediaIdentity = identity,
            nowElapsedMs = 1_003L,
        )
        coordinator.onSeekAccepted(
            engine = SeekEngine.EXOPLAYER,
            mediaIdentity = identity,
            positionMs = 30_000L,
        )

        coordinator.onTick(nowElapsedMs = 5_003L)
        val failed = coordinator.state as SeekState.Failed
        assertEquals(SeekFailureReason.TIMEOUT, failed.reason)
        assertTrue(failed.retryable)
        assertFalse(
            coordinator.onPlaybackReady(
                engine = SeekEngine.EXOPLAYER,
                mediaIdentity = identity,
                positionMs = 30_100L,
                nowElapsedMs = 5_100L,
            )
        )
        assertEquals(SeekFailureReason.TIMEOUT, (coordinator.state as SeekState.Failed).reason)
    }

    @Test
    fun `replacement error remains correlated to the same generation`() {
        val coordinator = SeekRequestCoordinator()
        coordinator.observeSource(identity)
        val generation =
            coordinator.beginRequest(
                targetMs = 45_000L,
                durationMs = 120_000L,
                issuedAtMs = 1_000L,
                policy = policy,
            )
        coordinator.onDispatched(
            requestGeneration = generation,
            engine = SeekEngine.EXOPLAYER,
            expectedMediaIdentity = nextIdentity,
            nowElapsedMs = 1_003L,
        )
        coordinator.prepareSourceReplacement(
            requireNotNull(coordinator.captureRequestLease()),
            nextIdentity,
        )
        coordinator.onSeekAccepted(
            engine = SeekEngine.EXOPLAYER,
            mediaIdentity = nextIdentity,
            positionMs = 45_000L,
        )

        assertTrue(
            coordinator.onEngineError(
                engine = SeekEngine.EXOPLAYER,
                mediaIdentity = nextIdentity,
                retryable = true,
                nowElapsedMs = 1_200L,
            )
        )
        val failed = coordinator.state as SeekState.Failed
        assertEquals(generation, failed.requestGeneration)
        assertEquals(2L, failed.sourceEpoch)
    }

    @Test
    fun `seek media identity depends on stable source fields rather than mutable position`() {
        val first =
            seekMediaIdentityFor(
                itemId = "item-a",
                mediaSourceId = "source-a",
                mediaSourceUri = "https://media.example/item/master.m3u8?StartTimeTicks=0",
            )
        val sameSource =
            seekMediaIdentityFor(
                itemId = "item-a",
                mediaSourceId = "source-a",
                mediaSourceUri = "https://media.example/item/master.m3u8?StartTimeTicks=0",
            )
        val rebuiltSource =
            seekMediaIdentityFor(
                itemId = "item-a",
                mediaSourceId = "source-a",
                mediaSourceUri = "https://media.example/item/master.m3u8?StartTimeTicks=450000000",
            )

        assertEquals(first, sameSource)
        assertTrue(first != rebuiltSource)
    }

    @Test
    fun `retry after source change reads the current source cursor`() {
        val coordinator = SeekRequestCoordinator()
        coordinator.observeSource(identity)
        coordinator.beginRequest(
            targetMs = 30_000L,
            durationMs = 120_000L,
            issuedAtMs = 1_000L,
            policy = policy,
        )
        coordinator.failCurrent(
            reason = SeekFailureReason.ENGINE_ERROR,
            retryable = true,
            nowElapsedMs = 1_200L,
        )

        coordinator.onSourceTransition(nextIdentity)
        coordinator.retryRequest(nowElapsedMs = 1_300L)

        val retrying = coordinator.state as SeekState.Seeking
        assertEquals(2L, retrying.sourceEpoch)
        assertEquals(nextIdentity, retrying.mediaIdentity)
        assertEquals(null, retrying.acceptedPositionMs)
    }

    @Test
    fun `timeout retires old dispatch before same target retry`() {
        val coordinator = SeekRequestCoordinator()
        coordinator.observeSource(identity)
        val first =
            coordinator.beginRequest(
                targetMs = 60_000L,
                durationMs = 120_000L,
                issuedAtMs = 1_000L,
                policy = policy,
            )
        coordinator.onDispatched(
            requestGeneration = first,
            engine = SeekEngine.EXOPLAYER,
            expectedMediaIdentity = identity,
            nowElapsedMs = 1_000L,
        )

        coordinator.onTick(nowElapsedMs = 5_000L)
        assertEquals(SeekFailureReason.TIMEOUT, (coordinator.state as SeekState.Failed).reason)

        val retry = requireNotNull(coordinator.retryRequest(nowElapsedMs = 5_100L))
        coordinator.onDispatched(
            requestGeneration = retry,
            engine = SeekEngine.EXOPLAYER,
            expectedMediaIdentity = identity,
            nowElapsedMs = 5_100L,
        )

        assertTrue(
            coordinator.onSeekAccepted(
                engine = SeekEngine.EXOPLAYER,
                mediaIdentity = identity,
                positionMs = 60_000L,
            )
        )
        assertTrue(
            coordinator.onPlaybackReady(
                engine = SeekEngine.EXOPLAYER,
                mediaIdentity = identity,
                positionMs = 60_100L,
                nowElapsedMs = 5_200L,
            )
        )
        assertEquals(retry, (coordinator.state as SeekState.Confirmed).requestGeneration)
    }

    @Test
    fun `one coalesced mpv seek and restart confirm the latest same target request`() {
        val coordinator = SeekRequestCoordinator()
        coordinator.observeSource(identity)
        val first =
            coordinator.beginRequest(
                targetMs = 60_000L,
                durationMs = 120_000L,
                issuedAtMs = 1_000L,
                policy = policy,
            )
        coordinator.onDispatched(
            requestGeneration = first,
            engine = SeekEngine.MPV,
            expectedMediaIdentity = identity,
            nowElapsedMs = 1_000L,
        )
        val second =
            coordinator.beginRequest(
                targetMs = 60_000L,
                durationMs = 120_000L,
                issuedAtMs = 1_100L,
                policy = policy,
            )
        coordinator.onDispatched(
            requestGeneration = second,
            engine = SeekEngine.MPV,
            expectedMediaIdentity = identity,
            nowElapsedMs = 1_100L,
        )

        assertTrue(
            coordinator.onSeekAccepted(
                engine = SeekEngine.MPV,
                mediaIdentity = identity,
                positionMs = null,
            )
        )
        assertTrue(
            coordinator.onPlaybackReady(
                engine = SeekEngine.MPV,
                mediaIdentity = identity,
                positionMs = 60_050L,
                nowElapsedMs = 1_200L,
            )
        )
        assertEquals(second, (coordinator.state as SeekState.Confirmed).requestGeneration)
    }

    @Test
    fun `late source a evidence cannot pull the cursor back after transition to b`() {
        val coordinator = SeekRequestCoordinator()
        coordinator.observeSource(identity)
        val generation =
            coordinator.beginRequest(
                targetMs = 45_000L,
                durationMs = 120_000L,
                issuedAtMs = 1_000L,
                policy = policy,
            )
        coordinator.onDispatched(
            requestGeneration = generation,
            engine = SeekEngine.EXOPLAYER,
            expectedMediaIdentity = identity,
            nowElapsedMs = 1_000L,
        )

        coordinator.onSourceTransition(nextIdentity)

        val failed = coordinator.state as SeekState.Failed
        assertEquals(generation, failed.requestGeneration)
        assertEquals(45_000L, failed.targetMs)
        assertTrue(failed.reason != SeekFailureReason.TIMEOUT)
        assertEquals(nextIdentity, coordinator.sourceMediaIdentity)
        assertEquals(2L, coordinator.sourceEpoch)

        assertFalse(
            coordinator.onSeekAccepted(
                engine = SeekEngine.EXOPLAYER,
                mediaIdentity = identity,
                positionMs = 45_000L,
            )
        )
        assertFalse(
            coordinator.onPlaybackReady(
                engine = SeekEngine.EXOPLAYER,
                mediaIdentity = identity,
                positionMs = 45_050L,
                nowElapsedMs = 1_200L,
            )
        )
        assertEquals(nextIdentity, coordinator.sourceMediaIdentity)
        assertEquals(2L, coordinator.sourceEpoch)
        assertTrue(coordinator.state is SeekState.Failed)
    }

    @Test
    fun `old rebuild lease cannot replace a newer target request`() {
        val coordinator = SeekRequestCoordinator()
        coordinator.observeSource(identity)
        val first =
            coordinator.beginRequest(
                targetMs = 45_000L,
                durationMs = 120_000L,
                issuedAtMs = 1_000L,
                policy = policy,
            )
        coordinator.onDispatched(
            requestGeneration = first,
            engine = SeekEngine.EXOPLAYER,
            expectedMediaIdentity = identity,
            nowElapsedMs = 1_000L,
        )
        val oldLease = requireNotNull(coordinator.captureRequestLease())

        val second =
            coordinator.beginRequest(
                targetMs = 0L,
                durationMs = 120_000L,
                issuedAtMs = 1_100L,
                policy = policy,
            )
        coordinator.onDispatched(
            requestGeneration = second,
            engine = SeekEngine.EXOPLAYER,
            expectedMediaIdentity = nextIdentity,
            nowElapsedMs = 1_100L,
        )

        assertFalse(coordinator.isCurrent(oldLease))
        assertFalse(coordinator.prepareSourceReplacement(oldLease, nextIdentity))
        val current = coordinator.state as SeekState.Seeking
        assertEquals(second, current.requestGeneration)
        assertEquals(0L, current.targetMs)
        assertEquals(identity, current.mediaIdentity)

        coordinator.onSourceTransition(nextIdentity)
        assertTrue(
            coordinator.onSeekAccepted(
                engine = SeekEngine.EXOPLAYER,
                mediaIdentity = nextIdentity,
                positionMs = 0L,
            )
        )
        assertTrue(
            coordinator.onPlaybackReady(
                engine = SeekEngine.EXOPLAYER,
                mediaIdentity = nextIdentity,
                positionMs = 20L,
                nowElapsedMs = 1_200L,
            )
        )
        assertEquals(second, (coordinator.state as SeekState.Confirmed).requestGeneration)
        assertEquals(0L, (coordinator.state as SeekState.Confirmed).targetMs)
    }

    @Test
    fun `rebuild failure only fails its current lease and retry keeps target`() {
        val coordinator = SeekRequestCoordinator()
        coordinator.observeSource(identity)
        val first =
            coordinator.beginRequest(
                targetMs = 45_000L,
                durationMs = 120_000L,
                issuedAtMs = 1_000L,
                policy = policy,
            )
        coordinator.onDispatched(
            requestGeneration = first,
            engine = SeekEngine.EXOPLAYER,
            expectedMediaIdentity = identity,
            nowElapsedMs = 1_000L,
        )
        val oldLease = requireNotNull(coordinator.captureRequestLease())

        val second =
            coordinator.beginRequest(
                targetMs = 60_000L,
                durationMs = 120_000L,
                issuedAtMs = 1_100L,
                policy = policy,
            )
        coordinator.onDispatched(
            requestGeneration = second,
            engine = SeekEngine.EXOPLAYER,
            expectedMediaIdentity = identity,
            nowElapsedMs = 1_100L,
        )
        val currentLease = requireNotNull(coordinator.captureRequestLease())

        assertFalse(
            coordinator.failCurrent(
                lease = oldLease,
                reason = SeekFailureReason.SESSION_REBUILD_FAILED,
                retryable = true,
                nowElapsedMs = 1_200L,
            )
        )
        assertEquals(second, (coordinator.state as SeekState.Seeking).requestGeneration)

        assertTrue(
            coordinator.failCurrent(
                lease = currentLease,
                reason = SeekFailureReason.SESSION_REBUILD_FAILED,
                retryable = true,
                nowElapsedMs = 1_300L,
            )
        )
        val failed = coordinator.state as SeekState.Failed
        assertEquals(second, failed.requestGeneration)
        assertEquals(60_000L, failed.targetMs)
        assertEquals(SeekFailureReason.SESSION_REBUILD_FAILED, failed.reason)

        val retry = requireNotNull(coordinator.retryRequest(nowElapsedMs = 1_400L))
        val retrying = coordinator.state as SeekState.Seeking
        assertEquals(retry, retrying.requestGeneration)
        assertEquals(60_000L, retrying.targetMs)
    }

    @Test
    fun `attached current source can begin a media item request without a prior observation`() {
        val coordinator = SeekRequestCoordinator()

        val generation =
            coordinator.beginRequestForTargetFromSource(
                currentMediaIdentity = identity,
                targetMs = 0L,
                durationMs = 120_000L,
                issuedAtMs = 1_000L,
                engine = SeekEngine.EXOPLAYER,
                targetSourceUri = "https://media.example/item/master.m3u8",
            )

        val seeking = coordinator.state as SeekState.Seeking
        assertEquals(generation, seeking.requestGeneration)
        assertEquals(identity, coordinator.sourceMediaIdentity)
        assertEquals(1L, coordinator.sourceEpoch)
        assertEquals(0L, seeking.targetMs)
        assertEquals(35_000L, seeking.policy.completionTimeoutMs)
    }

    @Test
    fun `engine error without an accepted seek anchor is not attributed to the request`() {
        val coordinator = SeekRequestCoordinator()
        coordinator.observeSource(identity)
        coordinator.beginRequest(
            targetMs = 30_000L,
            durationMs = 120_000L,
            issuedAtMs = 1_000L,
            policy = policy,
        )
        coordinator.onDispatched(
            requestGeneration = 1L,
            engine = SeekEngine.EXOPLAYER,
            expectedMediaIdentity = identity,
            nowElapsedMs = 1_000L,
        )

        assertFalse(
            coordinator.onEngineError(
                engine = SeekEngine.EXOPLAYER,
                mediaIdentity = identity,
                retryable = true,
                nowElapsedMs = 1_100L,
            )
        )
        assertTrue(coordinator.state is SeekState.Seeking)
    }
}
