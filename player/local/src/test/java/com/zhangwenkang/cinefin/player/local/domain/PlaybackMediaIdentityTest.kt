package com.zhangwenkang.cinefin.player.local.domain

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackMediaIdentityTest {

    @Test
    fun `缺 item 或 mediaSource extras 时不构造身份`() {
        val itemId = UUID.randomUUID()

        assertNull(playbackMediaIdentity(itemId, null))
        assertNull(playbackMediaIdentity(itemId, ""))
        assertNull(playbackMediaIdentity(null, "source-a"))
    }

    @Test
    fun `item 与 mediaSource 都存在时构造稳定身份`() {
        val itemId = UUID.randomUUID()

        assertEquals(
            "source-a",
            playbackMediaIdentity(itemId, "source-a")?.mediaSourceId,
        )
    }
}
