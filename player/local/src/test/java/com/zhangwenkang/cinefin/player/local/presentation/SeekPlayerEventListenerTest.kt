package com.zhangwenkang.cinefin.player.local.presentation

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.zhangwenkang.cinefin.player.local.domain.SeekConfirmationPolicy
import com.zhangwenkang.cinefin.player.local.domain.SeekEngine
import com.zhangwenkang.cinefin.player.local.domain.SeekMediaIdentity
import com.zhangwenkang.cinefin.player.local.domain.SeekRequestCoordinator
import com.zhangwenkang.cinefin.player.local.domain.SeekState
import org.junit.Assert.assertEquals
import org.junit.Test

class SeekPlayerEventListenerTest {
    private val identity = SeekMediaIdentity("item-a", "source-a", "uri-hash-a")
    private val policy =
        SeekConfirmationPolicy(
            completionTimeoutMs = 4_000L,
            positionToleranceMs = 1_000L,
            minimumTargetDisplayMs = 300L,
        )
    private val mediaItem = MediaItem.fromUri("https://media.example/item.mp4")

    @Test
    fun `real exo listener callbacks accept and confirm the dispatched seek`() {
        val coordinator = SeekRequestCoordinator()
        coordinator.observeSource(identity)
        val generation =
            coordinator.beginRequest(
                targetMs = 60_000L,
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
        var playbackState = Player.STATE_BUFFERING
        var positionMs = 60_000L
        val listener =
            SeekPlayerEventListener(
                coordinator = coordinator,
                engine = { SeekEngine.EXOPLAYER },
                mediaIdentity = { identity },
                readyPositionMs = { if (playbackState == Player.STATE_READY) positionMs else null },
                nowElapsedMs = { 1_100L },
            )

        listener.onPositionDiscontinuity(
            positionInfo(positionMs = 60_000L),
            positionInfo(positionMs = 60_000L),
            Player.DISCONTINUITY_REASON_SEEK,
        )
        assertEquals(
            60_000L,
            (coordinator.state as SeekState.Seeking).acceptedPositionMs,
        )

        playbackState = Player.STATE_READY
        positionMs = 60_050L
        listener.onPlaybackStateChanged(Player.STATE_READY)

        val confirmed = coordinator.state as SeekState.Confirmed
        assertEquals(generation, confirmed.requestGeneration)
        assertEquals(60_050L, confirmed.positionMs)
    }

    @Test
    fun `real mpv listener uses one accepted event and position checked restart`() {
        val coordinator = SeekRequestCoordinator()
        coordinator.observeSource(identity)
        val generation =
            coordinator.beginRequest(
                targetMs = 42_000L,
                durationMs = 120_000L,
                issuedAtMs = 1_000L,
                policy = policy,
            )
        coordinator.onDispatched(
            requestGeneration = generation,
            engine = SeekEngine.MPV,
            expectedMediaIdentity = identity,
            nowElapsedMs = 1_000L,
        )
        val listener =
            SeekPlayerEventListener(
                coordinator = coordinator,
                engine = { SeekEngine.MPV },
                mediaIdentity = { identity },
                readyPositionMs = { null },
                nowElapsedMs = { 1_100L },
            )

        listener.onMpvSeekAccepted()
        listener.onMpvPlaybackRestarted(positionMs = 42_100L)

        val confirmed = coordinator.state as SeekState.Confirmed
        assertEquals(generation, confirmed.requestGeneration)
        assertEquals(42_100L, confirmed.positionMs)
    }

    @Test
    fun `old accepted seek does not steal the new ready callback`() {
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
            nowElapsedMs = 1_000L,
        )
        val listener =
            SeekPlayerEventListener(
                coordinator = coordinator,
                engine = { SeekEngine.EXOPLAYER },
                mediaIdentity = { identity },
                readyPositionMs = { 70_100L },
                nowElapsedMs = { 1_100L },
            )
        listener.onPositionDiscontinuity(
            positionInfo(positionMs = 20_000L),
            positionInfo(positionMs = 20_000L),
            Player.DISCONTINUITY_REASON_SEEK,
        )

        val second =
            coordinator.beginRequest(
                targetMs = 70_000L,
                durationMs = 120_000L,
                issuedAtMs = 1_200L,
                policy = policy,
            )
        coordinator.onDispatched(
            requestGeneration = second,
            engine = SeekEngine.EXOPLAYER,
            expectedMediaIdentity = identity,
            nowElapsedMs = 1_200L,
        )
        listener.onPositionDiscontinuity(
            positionInfo(positionMs = 70_000L),
            positionInfo(positionMs = 70_000L),
            Player.DISCONTINUITY_REASON_SEEK,
        )

        val confirmed = coordinator.state as SeekState.Confirmed
        assertEquals(second, confirmed.requestGeneration)
        assertEquals(70_100L, confirmed.positionMs)
    }

    private fun positionInfo(positionMs: Long): Player.PositionInfo =
        Player.PositionInfo(
            Any(),
            0,
            mediaItem,
            Any(),
            0,
            positionMs,
            positionMs,
            -1,
            -1,
        )
}
