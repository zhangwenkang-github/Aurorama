package com.zhangwenkang.cinefin.player.local.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/** W74-S2（#9）：倍速的「会话内继承 / 新会话回落 1×」策略。 */
class PlaybackSpeedSessionTest {

    @Test
    fun sameSessionKeepsChosenSpeed() {
        assertEquals(1.5f, speedForPlaybackSession(sessionSpeed = 1.5f, isNewSession = false), 0f)
        assertEquals(2f, speedForPlaybackSession(sessionSpeed = 2f, isNewSession = false), 0f)
    }

    @Test
    fun newSessionFallsBackToOne() {
        assertEquals(1f, speedForPlaybackSession(sessionSpeed = 1.5f, isNewSession = true), 0f)
        assertEquals(1f, speedForPlaybackSession(sessionSpeed = 1f, isNewSession = true), 0f)
    }

    @Test
    fun defaultSpeedIsOne() {
        assertEquals(1f, DEFAULT_PLAYBACK_SPEED, 0f)
    }
}
