package com.zhangwenkang.cinefin.presentation.player

import com.zhangwenkang.cinefin.player.local.R as PlayerR
import com.zhangwenkang.cinefin.player.local.presentation.PlayerViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackRecoveryCopyTest {

    @Test
    fun progressTimeout_mapsToStalledRecoveryCopy() {
        val copy =
            resolvePlaybackRecoveryCopy(
                kind = "PLAYBACK_PROGRESS_TIMEOUT",
                positionMs = 0L,
                positionSource = PlayerViewModel.PlaybackRetryPositionSource.START_ZERO,
            )

        assertEquals(PlaybackRecoveryTitle.Stalled, copy.title)
        assertEquals(
            PlayerR.string.player_progress_recovery_stalled,
            PlaybackRecoveryTitle.Stalled.stringRes(),
        )
        assertEquals(PlaybackRecoveryDetail.RetryFromStart, copy.detail)
        assertEquals(
            PlayerR.string.player_progress_recovery_retry_from_start,
            PlaybackRecoveryDetail.RetryFromStart.stringRes(),
        )
    }

    @Test
    fun progressUnconfirmed_mapsToUnconfirmedRecoveryCopy() {
        val copy =
            resolvePlaybackRecoveryCopy(
                kind = "PROGRESS_UNCONFIRMED",
                positionMs = 0L,
                positionSource = PlayerViewModel.PlaybackRetryPositionSource.START_ZERO,
            )

        assertEquals(PlaybackRecoveryTitle.ProgressUnconfirmed, copy.title)
        assertEquals(
            PlayerR.string.player_progress_recovery_unconfirmed,
            PlaybackRecoveryTitle.ProgressUnconfirmed.stringRes(),
        )
        assertEquals(PlaybackRecoveryDetail.RetryFromStart, copy.detail)
    }

    @Test
    fun unknownKind_mapsToGenericRecoveryCopy() {
        val copy =
            resolvePlaybackRecoveryCopy(
                kind = "NEW_BACKEND_FAILURE",
                positionMs = 0L,
                positionSource = PlayerViewModel.PlaybackRetryPositionSource.START_ZERO,
            )

        assertEquals(PlaybackRecoveryTitle.Generic, copy.title)
        assertEquals(
            PlayerR.string.player_progress_recovery_generic,
            PlaybackRecoveryTitle.Generic.stringRes(),
        )
        assertEquals(PlaybackRecoveryDetail.RetryFromStart, copy.detail)
        assertFalse(copy.toString().contains("NEW_BACKEND_FAILURE"))
    }

    @Test
    fun startZero_neverExposesPositionSourceOrTimestamp() {
        PlayerViewModel.PlaybackRetryPositionSource.entries.forEach { source ->
            val copy =
                resolvePlaybackRecoveryCopy(
                    kind = "PLAYBACK_PROGRESS_TIMEOUT",
                    positionMs = 42_000L,
                    positionSource = source,
                )
            val rendered = copy.toString()

            if (source == PlayerViewModel.PlaybackRetryPositionSource.START_ZERO) {
                assertEquals(PlaybackRecoveryDetail.RetryFromStart, copy.detail)
                assertNull(copy.positionMs)
            }
            assertFalse(rendered.contains(source.name))
        }
    }

    @Test
    fun confirmedEnginePosition_exposesOnlySafeFormattedPosition() {
        val copy =
            resolvePlaybackRecoveryCopy(
                kind = "PLAYBACK_PROGRESS_TIMEOUT",
                positionMs = 42_000L,
                positionSource = PlayerViewModel.PlaybackRetryPositionSource.ENGINE_POSITION,
            )

        assertEquals(PlaybackRecoveryDetail.RetryFromPosition, copy.detail)
        assertEquals(
            PlayerR.string.player_progress_recovery_retry_from_position,
            PlaybackRecoveryDetail.RetryFromPosition.stringRes(),
        )
        assertEquals(42_000L, copy.positionMs)
        assertFalse(copy.toString().contains("ENGINE_POSITION"))
    }

    @Test
    fun knownKinds_neverAppearInRenderedCopyModel() {
        listOf("PLAYBACK_PROGRESS_TIMEOUT", "PROGRESS_UNCONFIRMED").forEach { kind ->
            val copy =
                resolvePlaybackRecoveryCopy(
                    kind = kind,
                    positionMs = 0L,
                    positionSource = PlayerViewModel.PlaybackRetryPositionSource.START_ZERO,
                )

            assertFalse(copy.toString().contains(kind))
        }
    }
}
