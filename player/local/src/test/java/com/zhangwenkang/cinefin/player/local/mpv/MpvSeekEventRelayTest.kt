package com.zhangwenkang.cinefin.player.local.mpv

import org.junit.Assert.assertEquals
import org.junit.Test

class MpvSeekEventRelayTest {
    @Test
    fun `each physical mpv event is normalized once`() {
        val events = mutableListOf<MpvSeekEvent>()
        val relay = MpvSeekEventRelay(events::add)

        relay.onSeekEvent()
        relay.onPlaybackRestartedEvent(positionMs = 41_000L)

        assertEquals(
            listOf(
                MpvSeekEvent.SeekAccepted,
                MpvSeekEvent.PlaybackRestarted(positionMs = 41_000L),
            ),
            events,
        )
    }
}
