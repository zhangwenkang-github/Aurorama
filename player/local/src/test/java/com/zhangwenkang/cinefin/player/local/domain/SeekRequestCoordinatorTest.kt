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
    fun `same target late events are consumed by the superseded dispatch first`() {
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
            nowElapsedMs = 1_003L,
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
            engine = SeekEngine.EXOPLAYER,
            expectedMediaIdentity = identity,
            nowElapsedMs = 1_103L,
        )

        coordinator.onSeekAccepted(
            engine = SeekEngine.EXOPLAYER,
            mediaIdentity = identity,
            positionMs = 60_000L,
        )
        coordinator.onPlaybackReady(
            engine = SeekEngine.EXOPLAYER,
            mediaIdentity = identity,
            positionMs = 60_050L,
            nowElapsedMs = 1_200L,
        )

        assertEquals(second, (coordinator.state as SeekState.Seeking).requestGeneration)
        assertEquals(null, (coordinator.state as SeekState.Seeking).acceptedPositionMs)

        coordinator.onSeekAccepted(
            engine = SeekEngine.EXOPLAYER,
            mediaIdentity = identity,
            positionMs = 60_000L,
        )
        coordinator.onPlaybackReady(
            engine = SeekEngine.EXOPLAYER,
            mediaIdentity = identity,
            positionMs = 60_100L,
            nowElapsedMs = 1_300L,
        )

        assertEquals(
            second,
            (coordinator.state as SeekState.Confirmed).requestGeneration,
        )
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

        coordinator.onSourceTransition(nextIdentity)
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

        coordinator.prepareSourceReplacement(nextIdentity)
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
        coordinator.prepareSourceReplacement(nextIdentity)
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
}
