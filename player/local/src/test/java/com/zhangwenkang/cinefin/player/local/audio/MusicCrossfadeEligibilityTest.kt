package com.zhangwenkang.cinefin.player.local.audio

import androidx.media3.common.Player
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** W5-T7 · MUSIC-06：交叉淡化触发资格（只有队列自然推进的曲目可淡化）。 */
class MusicCrossfadeEligibilityTest {

    private val eligibility = MusicCrossfadeEligibility()

    @Test
    fun `natural advance marks the track eligible`() {
        eligibility.onMediaItemTransition(Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)
        assertTrue(eligibility.eligible)
    }

    @Test
    fun `manual transitions are never eligible`() {
        listOf(
                Player.MEDIA_ITEM_TRANSITION_REASON_SEEK,
                Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED,
                Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT,
            )
            .forEach { reason ->
                eligibility.onMediaItemTransition(Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)
                eligibility.onMediaItemTransition(reason)
                assertFalse("reason=$reason 不应允许淡化", eligibility.eligible)
            }
    }

    @Test
    fun `user seek clears eligibility for the rest of the track`() {
        eligibility.onMediaItemTransition(Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)
        eligibility.onPositionDiscontinuity(Player.DISCONTINUITY_REASON_SEEK)
        assertFalse(eligibility.eligible)
        // 同一曲目内的非 seek 断点不会恢复资格
        eligibility.onPositionDiscontinuity(Player.DISCONTINUITY_REASON_INTERNAL)
        assertFalse(eligibility.eligible)
    }

    @Test
    fun `other discontinuities keep eligibility`() {
        eligibility.onMediaItemTransition(Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)
        eligibility.onPositionDiscontinuity(Player.DISCONTINUITY_REASON_AUTO_TRANSITION)
        assertTrue(eligibility.eligible)
    }

    @Test
    fun `reset clears eligibility`() {
        eligibility.onMediaItemTransition(Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)
        eligibility.reset()
        assertFalse(eligibility.eligible)
    }
}
